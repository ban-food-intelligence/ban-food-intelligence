import { expect, test } from '@playwright/test';

const api = process.env.E2E_API_BASE || 'http://127.0.0.1:8080';
const day = () => new Date().toISOString().slice(0, 10);
const expiry = () => new Date(Date.now() + 30 * 86400_000).toISOString().slice(0, 10);
const suffix = () => `${Date.now()}`.slice(-9);
async function writer(request) {
  const auth = await request.post(`${api}/auth/session`, { data: { pin: '246813' } });
  expect(auth.ok()).toBeTruthy();
  const { token } = await auth.json();
  return async (path, data, status = 201) => {
    const res = await request.post(api + path, { data, headers: { Authorization: `Bearer ${token}` } });
    expect(res.status(), await res.text()).toBe(status);
    return res.json();
  };
}
async function save(page) {
  await page.getByLabel('Yetkili PIN').fill('246813');
  await page.getByRole('button', { name: 'Kaydet', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('Kayıt kaydedildi.');
}
function masterRow(page, code) { return page.getByRole('row').filter({ has: page.getByText(code, { exact: true }) }); }

test('master data creates units and partners and edits recall contacts in both languages', async ({ page, request }) => {
  const id = suffix();
  await page.goto('/#master');
  await expect(page.getByRole('heading', { name: 'Ana veriler' })).toBeVisible();
  await expect(page.getByRole('group', { name: '1 · Dış üretim ve EKP' })).toBeVisible();
  await page.getByRole('tab', { name: 'Ölçü birimleri', exact: true }).click();
  await page.getByRole('button', { name: 'Yeni kayıt' }).click();
  const unit = `M${id.replace(/[0-9]/g, (d) => String.fromCharCode(65 + Number(d)))}`;
  await page.getByLabel('Kod', { exact: true }).fill(unit);
  await page.getByLabel('Ad', { exact: true }).fill('Test koli');
  await save(page);
  await expect(masterRow(page, unit)).toContainText('Test koli');
  for (const [tab, code, name] of [['Tedarikçiler', `MDT-${id}`, 'Tarayıcı tedarikçi'], ['Müşteriler', `MDM-${id}`, 'Tarayıcı market']]) {
    await page.getByRole('tab', { name: tab, exact: true }).click();
    await page.getByRole('button', { name: 'Yeni kayıt' }).click();
    await page.getByLabel('Kod', { exact: true }).fill(code);
    await page.getByLabel('Ad', { exact: true }).fill(name);
    await page.getByLabel('İlgili kişi').fill('İlk kişi');
    await page.getByLabel('Telefon').fill('5550101');
    await page.getByLabel('E-posta').fill('test@example.test');
    await save(page);
    await masterRow(page, code).getByRole('button', { name: 'Düzenle' }).click();
    await expect(page.getByLabel('Kod', { exact: true })).toBeDisabled();
    await page.getByLabel('İlgili kişi').fill('Güncel kişi');
    await save(page);
    await expect(masterRow(page, code)).toContainText('Güncel kişi');
  }
  await page.getByRole('button', { name: 'EN', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Master data', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'TR', exact: true }).click();
  const customers = await (await request.get(`${api}/customers`)).json();
  expect(customers).toContainEqual(expect.objectContaining({ code: `MDM-${id}`, contact_person: 'Güncel kişi', phone: '5550101' }));
  await page.screenshot({ path: 'test-results/master-partners.png', fullPage: true });
});

test('product cards reach receipt suggestions and preserve booked cost when edited or deactivated', async ({ page, request }) => {
  const id = suffix();
  const sku = `MDP-${id}`;
  const write = await writer(request);
  await page.goto('/#master');
  await page.getByRole('button', { name: 'Yeni kayıt' }).click();
  await page.getByLabel('Kod', { exact: true }).fill(sku);
  await page.getByLabel('Ad', { exact: true }).fill('Yeni hammadde');
  await page.getByLabel('Standart alış fiyatı (€)').fill('9,70');
  await page.getByLabel('Raf ömrü (gün)').fill('7');
  await save(page);
  await page.goto('/#purchases');
  await page.getByRole('button', { name: 'Yeni giriş', exact: true }).click();
  await expect(page.locator(`#en-skus option[value="${sku}"]`)).toHaveCount(1);
  await write('/purchases', { receipt_no: `MD-A-${id}`, supplier_code: 'TED-ET-01', receipt_date: day(),
    lines: [{ sku, lot_no: `MD-L-${id}`, qty: 5, unit_price: 9.7, expiry_date: expiry() }] });
  await page.goto('/#master');
  await masterRow(page, sku).getByRole('button', { name: 'Düzenle' }).click();
  await expect(page.getByRole('combobox', { name: 'Tür', exact: true })).toBeDisabled();
  await expect(page.getByRole('combobox', { name: 'Ölçü birimi', exact: true })).toBeDisabled();
  await page.getByLabel('Ad', { exact: true }).fill('Adı güncellendi');
  await page.getByLabel('Standart alış fiyatı (€)').fill('100');
  await page.getByLabel('Aktif', { exact: true }).uncheck();
  await save(page);
  await expect(masterRow(page, sku)).toContainText('Pasif');
  const lots = await (await request.get(`${api}/lots`)).json();
  expect(lots).toContainEqual(expect.objectContaining({ lot_no: `MD-L-${id}`, unit_cost: 9.7, qty: 5, product_name: 'Adı güncellendi' }));
  await write('/purchases', { receipt_no: `MD-B-${id}`, supplier_code: 'TED-ET-01', receipt_date: day(),
    lines: [{ sku, lot_no: `MD-BL-${id}`, qty: 1, unit_price: 100, expiry_date: expiry() }] }, 422);
  await page.goto('/#purchases');
  await page.getByRole('button', { name: 'Yeni giriş', exact: true }).click();
  await expect(page.locator(`#en-skus option[value="${sku}"]`)).toHaveCount(0);
});

test('a recipe version fills products and its key while actual weights stay blank until weighing', async ({ page, request }) => {
  const id = suffix();
  const code = `MD-R-${id}`;
  const write = await writer(request);
  await page.goto('/#master');
  await page.getByRole('tab', { name: 'Parçalama reçeteleri', exact: true }).click();
  await masterRow(page, 'REC-KUZU-STD').getByRole('button', { name: 'Yeni sürüm oluştur' }).click();
  await expect(page.getByLabel('Kod', { exact: true })).toHaveValue('');
  await page.getByLabel('Kod', { exact: true }).fill(code);
  await page.getByLabel('Ad', { exact: true }).fill('Miktar anahtarlı yeni sürüm');
  await page.getByLabel('Varsayılan dağıtım anahtarı').selectOption('QUANTITY');
  await save(page);
  const recipes = await (await request.get(`${api}/butchery/recipes`)).json();
  const recipe = recipes.find((r) => r.code === code);
  expect(recipe.lines.length).toBeGreaterThan(1);
  await write('/butchery/recipes', recipe, 409); // a saved version cannot be overwritten
  const carcass = `MD-K-${id}`;
  await write('/purchases', { receipt_no: `MD-KA-${id}`, supplier_code: 'TED-HAY-01', receipt_date: day(),
    lines: [{ sku: 'KZ-KARKAS', lot_no: carcass, qty: 25, unit_price: 11.5, expiry_date: expiry() }] });
  await page.goto('/#butchery');
  await page.getByRole('button', { name: 'Parçalama kaydet' }).click();
  await page.getByLabel('Parçalama reçetesi').selectOption(code);
  await page.getByRole('button', { name: 'Reçeteden doldur' }).click();
  const form = page.locator('form');
  const weights = form.getByRole('textbox', { name: 'Miktar', exact: true });
  await expect(weights).toHaveCount(recipe.lines.length);
  for (const weight of await weights.all()) await expect(weight).toHaveValue('');
  await expect(page.getByRole('button', { name: 'Reçeteden doldur' })).toBeDisabled();
  await expect(form.getByRole('tab', { name: 'Miktar ağırlıklı (kg)' })).toHaveAttribute('aria-selected', 'true');
  await page.getByLabel('İşlem no').fill(`MD-OP-${id}`);
  await page.getByLabel('Girdi lotu', { exact: true }).fill(carcass);
  await page.getByLabel('Giriş (kg)', { exact: true }).fill('25');
  const lotInputs = form.getByRole('textbox', { name: 'Çıktı lotu', exact: true });
  let lotIndex = 0;
  for (let i = 0; i < recipe.lines.length; i++) {
    await weights.nth(i).fill(String(25 * recipe.lines[i].expected_yield_pct / 100));
    if (recipe.lines[i].output_kind !== 'WASTE') await lotInputs.nth(lotIndex++).fill(`MD-O${i}-${id}`);
  }
  await page.getByLabel('Yetkili PIN').fill('246813');
  const posted = page.waitForResponse((r) => r.url().endsWith('/butchery/operations') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Parçalamayı kaydet', exact: true }).click();
  const res = await posted;
  expect(res.status(), await res.text()).toBe(201);
  expect(res.request().postDataJSON().recipe_code).toBe(code);
  const result = await res.json();
  expect(result.total_cost).toBe(287.5);
  expect(result.allocation_method).toBe('QUANTITY');
  expect(result.allocation.allocated_total).toBe(287.5);
  const trace = await (await request.get(`${api}/trace/${carcass}?direction=forward`)).json();
  expect(JSON.stringify(trace)).toContain(`MD-O0-${id}`);
  await page.screenshot({ path: 'test-results/master-recipe-butchery.png', fullPage: true });
});

test('movement form takes a customer return from a depleted lot into quarantine and refuses excess', async ({ page, request }) => {
  const id = suffix();
  const write = await writer(request);
  const lot = `MD-RET-${id}`;
  // Use a fresh carcass receipt, deliver all of it, and then receive part back.
  await write('/purchases', { receipt_no: `MD-RA-${id}`, supplier_code: 'TED-HAY-01', receipt_date: day(),
    lines: [{ sku: 'KZ-KARKAS', lot_no: lot, qty: 2, unit_price: 11.5, expiry_date: expiry() }] });
  await write('/shipments', { shipment_no: `MD-RS-${id}`, customer_code: 'MKT-A', from_warehouse: 'ANA-DEPO', ship_date: day(), lines: [{ lot_no: lot, qty: 2 }] });
  await page.goto('/#movements');
  await page.getByRole('button', { name: 'Hareket kaydet', exact: true }).click();
  await page.getByRole('tab', { name: 'Müşteri iadesi', exact: true }).click();
  await page.getByLabel('İade lotu', { exact: true }).fill(lot);
  await page.getByRole('combobox', { name: 'Müşteri', exact: true }).selectOption('MKT-A');
  await expect(page.getByLabel('İadenin alınacağı depo')).toHaveValue('KARANTINA');
  await page.getByLabel('Miktar (kg)', { exact: true }).fill('1');
  await page.getByLabel('Yetkili PIN').fill('246813');
  await page.getByRole('button', { name: 'Hareketi kaydet' }).click();
  await expect(page.getByText('İade kaydedildi:')).toBeVisible();
  const lots = await (await request.get(`${api}/lots`)).json();
  expect(lots).toContainEqual(expect.objectContaining({ lot_no: lot, warehouse_code: 'KARANTINA', qty: 1 }));
  await page.getByRole('button', { name: 'Yeni hareket', exact: true }).click();
  await page.getByLabel('İade lotu', { exact: true }).fill(lot);
  await page.getByRole('combobox', { name: 'Müşteri', exact: true }).selectOption('MKT-A');
  await page.getByLabel('Miktar (kg)', { exact: true }).fill('2');
  await page.getByLabel('Yetkili PIN').fill('246813');
  await page.getByRole('button', { name: 'Hareketi kaydet' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  const after = await (await request.get(`${api}/lots`)).json();
  expect(after).toContainEqual(expect.objectContaining({ lot_no: lot, warehouse_code: 'KARANTINA', qty: 1 }));
});

test('every registered master-data write route rejects an anonymous request', async ({ request }) => {
  for (const path of ['/products', '/products/update', '/units', '/suppliers', '/suppliers/update', '/customers', '/customers/update', '/butchery/recipes']) {
    const res = await request.post(api + path, { data: {} });
    expect(res.status(), path).toBe(401);
    expect((await res.json()).code).toBe('AUTH_REQUIRED');
  }
});
