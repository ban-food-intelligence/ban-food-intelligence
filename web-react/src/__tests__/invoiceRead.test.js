import { describe, expect, it } from 'vitest';
import { applyInvoice, fitWithin } from '../views/Entry.jsx';

const blank = { sku: '', lot_no: '', supplier_lot_no: '', qty: '', unit_price: '', expiry_date: '' };
const suggestion = {
  supplier_code: 'TED-ET-01', invoice_no: 'FTR-2026-0912',
  lines: [
    { sku: 'HM-ET-DANA', supplier_lot_no: 'TED-B-4411', qty: 120.5, unit_price: 9.8, expiry_date: '2026-11-08' },
    { sku: '', supplier_lot_no: '', qty: null, unit_price: 11, expiry_date: '' },
  ],
};

describe('reading an invoice into the receipt form', () => {
  it('fills supplier, invoice number and lines but never our lot number', () => {
    const head = { receipt_no: 'ALS-1', supplier_code: '', receipt_date: '2026-11-01', invoice_no: '' };
    const { head: h, lines } = applyInvoice(head, [blank], suggestion);
    expect(h).toEqual({ receipt_no: 'ALS-1', supplier_code: 'TED-ET-01', receipt_date: '2026-11-01', invoice_no: 'FTR-2026-0912' });
    expect(lines).toEqual([
      { sku: 'HM-ET-DANA', lot_no: '', supplier_lot_no: 'TED-B-4411', qty: '120.5', unit_price: '9.8', expiry_date: '2026-11-08' },
      { sku: '', lot_no: '', supplier_lot_no: '', qty: '', unit_price: '11', expiry_date: '' },
    ]);
  });

  it('keeps lines already typed and what the invoice could not tell', () => {
    const typed = { ...blank, sku: 'HM-BAHARAT', qty: '3' };
    const head = { receipt_no: '', supplier_code: 'TED-BAH-01', receipt_date: '2026-11-01', invoice_no: 'X-1' };
    const { head: h, lines } = applyInvoice(head, [typed, blank], { supplier_code: '', invoice_no: '', lines: suggestion.lines.slice(0, 1) });
    expect(h.supplier_code).toBe('TED-BAH-01');
    expect(h.invoice_no).toBe('X-1');
    expect(lines.map((l) => l.sku)).toEqual(['HM-BAHARAT', 'HM-ET-DANA']);
  });

  it('shrinks a phone photo to fit 1600 px and never enlarges a small one', () => {
    expect(fitWithin(4032, 3024)).toEqual({ width: 1600, height: 1200 });
    expect(fitWithin(3024, 4032)).toEqual({ width: 1200, height: 1600 });
    expect(fitWithin(800, 600)).toEqual({ width: 800, height: 600 });
  });
});
