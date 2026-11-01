import { describe, expect, it } from 'vitest';
import { clock, drillPositions, drillVerdict, parseTarget } from '../views/RecallDrill.jsx';

const drill = (over = {}) => ({
  balanced: true,
  target_minutes: 240,
  contacts: [{ customer_code: 'MKT-A', reached_at: '2026-11-01T10:05:00Z' }, { customer_code: 'MKT-B', reached_at: '2026-11-01T10:09:00Z' }],
  ...over,
});

describe('recall drill', () => {
  it('passes only with every quantity documented, every customer reached and in time (same rule as the API)', () => {
    expect(drillVerdict(drill(), 240 * 60)).toEqual({ result: 'PASS', reasons: [] });
    expect(drillVerdict(drill({ contacts: [] }), 60)).toEqual({ result: 'PASS', reasons: [] });
    expect(drillVerdict(drill(), 240 * 60 + 1)).toEqual({ result: 'FAIL', reasons: ['OVER_TIME'] });
    expect(drillVerdict(drill({ balanced: false, contacts: [{ customer_code: 'MKT-A', reached_at: null }] }), 10 * 3600))
      .toEqual({ result: 'FAIL', reasons: ['UNEXPLAINED_QTY', 'CUSTOMER_NOT_REACHED', 'OVER_TIME'] });
  });

  it('shows the elapsed time as a clock', () => {
    expect(clock(0)).toBe('0:00:00');
    expect(clock(3909.7)).toBe('1:05:09');
    expect(clock(240 * 60)).toBe('4:00:00');
    expect(clock(-5)).toBe('0:00:00');
  });

  it('lists where the quantity is per unit and leaves out empty positions', () => {
    const totals = [
      { uom_code: 'KG', at_customers: 0, sold: 0, wasted: 0, in_stock: 0, unexplained: 0 }, // all used in other lots
      { uom_code: 'PAKET', at_customers: 83, sold: 0, wasted: 0, in_stock: 2, unexplained: -1 },
    ];
    expect(drillPositions(totals)).toEqual([['dr_posGap', -1, 'PAKET'], ['dr_posCustomers', 83, 'PAKET'], ['dr_posStock', 2, 'PAKET']]);
    expect(drillPositions(undefined)).toEqual([]);
  });

  it('accepts whole minutes from one minute to one week as the target', () => {
    expect(parseTarget(' 240 ')).toBe(240);
    expect(parseTarget('1')).toBe(1);
    expect(parseTarget('10080')).toBe(10080);
    for (const bad of ['0', '10081', '2.5', '', 'abc', '-3']) expect(parseTarget(bad)).toBeNull();
  });
});
