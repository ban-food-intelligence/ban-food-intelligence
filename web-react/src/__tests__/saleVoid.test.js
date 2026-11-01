import { describe, expect, it } from 'vitest';
import { canVoid, salesTotals, voidReady } from '../views/Reports.jsx';

// Seed-like POS lines: sale A has two lines, sale B one.
const lines = [
  { sale_uuid: 'A', line_no: 1, line_total: 24.9, cost: 18.16, voided: false },
  { sale_uuid: 'A', line_no: 2, line_total: 12.45, cost: 9.08, voided: false },
  { sale_uuid: 'B', line_no: 1, line_total: 7.5, cost: 6, voided: false },
];

describe('sale void on the Sales page', () => {
  it('keeps the totals of a list without voids exactly as the plain sums', () => {
    const t = salesTotals(lines);
    expect(t.rows).toHaveLength(3);
    expect(t.voided).toBe(0);
    expect(t.revenue).toBeCloseTo(44.85);
    expect(t.cost).toBeCloseTo(33.24);
    expect(t.gp).toBeCloseTo(11.61);
  });

  it('leaves the lines of a voided sale out of the totals but counts them', () => {
    const all = lines.map((r) => (r.sale_uuid === 'A' ? { ...r, voided: true, void_reason: 'Yanlış ürün' } : r));
    const t = salesTotals(all);
    expect(t.rows.map((r) => r.sale_uuid)).toEqual(['B']);
    expect(t.voided).toBe(2);
    expect(t.revenue).toBeCloseTo(7.5);
    expect(t.gp).toBeCloseTo(1.5);
  });

  it('offers one void per sale, on its first line, and none once voided', () => {
    expect(lines.map(canVoid)).toEqual([true, false, true]);
    expect(canVoid({ ...lines[0], voided: true })).toBe(false);
  });

  it('needs a reason of 3 to 200 characters and the supervisor PIN, like the API', () => {
    expect(voidReady('Yanlış ürün okutuldu', '246813')).toBe(true);
    expect(voidReady('  ab ', '246813')).toBe(false);
    expect(voidReady('ş'.repeat(200), '246813')).toBe(true);
    expect(voidReady('ş'.repeat(201), '246813')).toBe(false);
    expect(voidReady('Yanlış ürün', '123')).toBe(false);
  });
});
