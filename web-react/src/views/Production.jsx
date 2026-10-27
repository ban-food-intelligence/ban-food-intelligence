import React from 'react';
import { useApi } from '../lib/api';
import { useLang } from '../lib/i18n';
import { Async, Group, Kpi, Mono, Page, Pill, Table } from '../lib/ui';

export default function Production() {
  const { t, fmt } = useLang();
  const result = useApi('/production');
  return (
    <Page title={t('pr_title')} subtitle={t('pr_sub')}>
      <Async result={result}>
        {(orders) =>
          (orders || []).map((o) => (
            <Group
              key={o.order_no}
              title={`${o.order_no} → ${o.output_lot_number || '–'} · ${o.product_name}`}
              aside={<Pill tone="neutral">{o.status}</Pill>}
            >
              <div className="kpi-row">
                <Kpi label={t('ekp')} value={fmt.money(o.ekp_display)} sub={t('formula', fmt.money(o.total_cost), fmt.num(o.actual_qty, 0), fmt.money(o.ekp))} />
                <Kpi label={t('vkp')} value={fmt.money(o.vkp)} />
                <Kpi label={t('gpUnit')} value={fmt.money(o.gross_profit_per_unit)} tone="ok" />
                <Kpi label={t('margin')} value={fmt.pct(o.margin_pct)} tone="ok" />
              </div>
              <div className="kpi-row">
                <Kpi label={t('planned')} value={fmt.num(o.planned_qty, 0)} />
                <Kpi label={t('actual')} value={fmt.num(o.actual_qty, 0)} />
                <Kpi label={t('variance')} value={fmt.num(o.qty_variance, 0)} tone={o.qty_variance < 0 ? 'warn' : undefined} sub={fmt.pct(o.fulfilment_pct)} />
                <Kpi label={t('revenueVkp')} value={fmt.money(o.revenue_at_vkp)} sub={`${t('gpTotal')}: ${fmt.money(o.gross_profit_total)}`} />
              </div>
              <Table
                rows={o.breakdown || []}
                columns={[
                  { key: 'lot_no', label: `${t('lot')} / ${t('document')}`, render: (r) => <Mono>{r.lot_no || '–'}</Mono> },
                  { key: 'cost_element', label: t('costElement') },
                  { key: 'qty', label: t('qty'), align: 'num', render: (r) => fmt.qty(r.qty, r.uom_code) },
                  { key: 'unit_cost', label: t('unitPrice'), align: 'num', render: (r) => fmt.money(r.unit_cost) },
                  { key: 'amount', label: t('amount'), align: 'num', render: (r) => <strong>{fmt.money(r.amount)}</strong> },
                ]}
                footer={
                  <tr>
                    <td colSpan={4}>
                      {t('totalCost')} ({t('materialCost')} {fmt.money(o.material_cost)} + {t('serviceCost')} {fmt.money(o.service_cost)})
                    </td>
                    <td className="num"><strong>{fmt.money(o.total_cost)}</strong></td>
                  </tr>
                }
              />
            </Group>
          ))
        }
      </Async>
    </Page>
  );
}
