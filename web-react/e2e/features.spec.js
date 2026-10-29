import { expect, test } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';

const api = process.env.E2E_API_BASE || 'http://127.0.0.1:8080';

test('downloads one Turkish PDF with the live assessment evidence', async ({ page }) => {
  await page.goto('/');
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Değerlendirme raporu (PDF)' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^BAN-assessment-ET-2026-001-.*\.pdf$/);
  await fs.mkdir('test-results', { recursive: true });
  await file.saveAs(path.resolve('test-results/assessment-tr.pdf'));
  const bytes = await fs.readFile('test-results/assessment-tr.pdf');
  expect(bytes.subarray(0, 5).toString()).toBe('%PDF-');
  expect(bytes.length).toBeGreaterThan(20_000);
});

test('report failures stay visible and do not download incomplete evidence', async ({ page }) => {
  await page.route('**/reports/assessment', (route) => route.fulfill({ status: 404, contentType: 'application/json',
    body: JSON.stringify({ code: 'NOT_FOUND', message: 'Scenario record missing' }) }));
  let downloads = 0;
  page.on('download', () => downloads++);
  await page.goto('/');
  await page.getByRole('button', { name: 'Değerlendirme raporu (PDF)' }).click();
  await expect(page.getByText('Scenario record missing')).toBeVisible();
  expect(downloads).toBe(0);
  await expect(page.getByRole('button', { name: 'Değerlendirme raporu (PDF)' })).toBeEnabled();
});

test('records contact acknowledgement and physical recovery separately', async ({ page, request }) => {
  const auth = await request.post(`${api}/auth/session`, { data: { pin: '246813' } });
  expect(auth.ok()).toBeTruthy();
  const { token } = await auth.json();
  const code = `RCL-E2E-${Date.now()}`;
  const recall = await request.post(`${api}/recall`, { headers: { Authorization: `Bearer ${token}` },
    data: { recall_code: code, trigger_lot: 'ET-2026-001', reason: 'Browser test' } });
  expect(recall.status()).toBe(201);
  const before = await (await request.get(`${api}/recall/progress?recall_code=${code}`)).json();
  const a = before.lines.find((l) => l.customer_code === 'MKT-A');
  expect(a.qty_pending).toBeGreaterThanOrEqual(2);
  await page.goto('/#trace');
  const panel = page.locator('.recall').filter({ hasText: code });
  await panel.locator('summary').click();
  const row = panel.getByRole('row').filter({ hasText: 'MKT-A' }).filter({ has: page.getByRole('button', { name: 'Takibi güncelle' }) });
  await row.getByRole('button', { name: 'Takibi güncelle' }).click();
  await panel.getByRole('combobox', { name: 'İletişim', exact: true }).selectOption('CONFIRMED');
  await panel.getByLabel('Görüşme notu / teslim tutanağı').fill('Müşteri rafı boşalttığını teyit etti.');
  await panel.getByLabel('Yetkili PIN').fill('246813');
  await panel.getByRole('button', { name: 'Kaydet', exact: true }).click();
  await expect(panel.getByText('Takip kaydedildi.')).toBeVisible();
  const contacted = await (await request.get(`${api}/recall/progress?recall_code=${code}`)).json();
  expect(contacted.lines.find((l) => l.customer_code === 'MKT-A').qty_returned).toBe(a.qty_returned);
  await row.getByRole('button', { name: 'İade alındı kaydet' }).click();
  await panel.getByLabel('Miktar (PAKET)').fill('2');
  await panel.getByLabel('Yetkili PIN').fill('246813');
  await panel.getByRole('button', { name: 'Kaydet', exact: true }).click();
  await expect(panel.getByText('Takip kaydedildi.')).toBeVisible();
  const recovered = await (await request.get(`${api}/recall/progress?recall_code=${code}`)).json();
  expect(recovered.lines.find((l) => l.customer_code === 'MKT-A').qty_pending).toBe(a.qty_pending - 2);
  expect(recovered.totals[0].qty_to_recover).toBe(85); // 30/25/20 + recorded tier-2 10, not 95
  await page.screenshot({ path: 'test-results/recall-progress.png', fullPage: true });
});
