import React, { useState } from 'react';

export default function ButcheryCostTable() {
  const [method, setMethod] = useState('NET_REALIZABLE_VALUE'); // or PHYSICAL_QUANTITY

  const carcassData = {
    lot: 'ET-2026-001',
    weightKg: 450.0,
    unitCost: 12.00,
    totalCost: 5400.00,
  };

  const cuts = [
    { sku: 'CUT-TENDERLOIN', name: 'Prime Beef Tenderloin (Bonfile)', weightKg: 18.0, marketPrice: 48.00 },
    { sku: 'CUT-RIBEYE', name: 'Ribeye Steak (Antrikot)', weightKg: 28.0, marketPrice: 42.00 },
    { sku: 'CUT-TRIM-MINCE', name: 'Beef Trimming 80/20 (Sucuk Base)', weightKg: 310.0, marketPrice: 20.00 },
  ];

  const totalOutputWeight = cuts.reduce((acc, c) => acc + c.weightKg, 0);
  const totalSalesValue = cuts.reduce((acc, c) => acc + (c.weightKg * c.marketPrice), 0);

  const calculatedRows = cuts.map((cut) => {
    const cutSalesValue = cut.weightKg * cut.marketPrice;
    let allocatedCost = 0;

    if (method === 'PHYSICAL_QUANTITY') {
      allocatedCost = (cut.weightKg / totalOutputWeight) * carcassData.totalCost;
    } else {
      allocatedCost = (cutSalesValue / totalSalesValue) * carcassData.totalCost;
    }

    const unitCost = allocatedCost / cut.weightKg;
    const marginPct = ((cutSalesValue - allocatedCost) / cutSalesValue) * 100;

    return {
      ...cut,
      cutSalesValue,
      allocatedCost,
      unitCost,
      marginPct,
    };
  });

  return (
    <div className="card butchery-cost-table">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.25rem' }}>
        <div>
          <h2 style={{ fontSize: '1.3rem', color: '#f1f5f9' }}>Task 2 Butchery Reconciliation Cost Allocation</h2>
          <p style={{ color: '#94a3b8', fontSize: '0.85rem' }}>
            Carcass Lot: <strong>{carcassData.lot}</strong> &bull; Weight: {carcassData.weightKg} KG &bull; Carcass Total Cost: {carcassData.totalCost.toFixed(2)} TL
          </p>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button
            onClick={() => setMethod('PHYSICAL_QUANTITY')}
            style={{
              background: method === 'PHYSICAL_QUANTITY' ? '#06b6d4' : '#1e293b',
              color: '#fff',
              border: 'none',
              padding: '0.5rem 1rem',
              borderRadius: '6px',
              cursor: 'pointer',
              fontWeight: 600,
              fontSize: '0.85rem'
            }}
          >
            Physical Qty (Weight)
          </button>
          <button
            onClick={() => setMethod('NET_REALIZABLE_VALUE')}
            style={{
              background: method === 'NET_REALIZABLE_VALUE' ? '#06b6d4' : '#1e293b',
              color: '#fff',
              border: 'none',
              padding: '0.5rem 1rem',
              borderRadius: '6px',
              cursor: 'pointer',
              fontWeight: 600,
              fontSize: '0.85rem'
            }}
          >
            Net Realizable Value (Sales Value)
          </button>
        </div>
      </div>

      <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: '0.9rem' }}>
        <thead>
          <tr style={{ borderBottom: '1px solid #334155', color: '#94a3b8' }}>
            <th style={{ padding: '0.75rem' }}>Cut SKU & Name</th>
            <th style={{ padding: '0.75rem' }}>Yield (KG)</th>
            <th style={{ padding: '0.75rem' }}>Market Price/KG</th>
            <th style={{ padding: '0.75rem' }}>Total Sales Value</th>
            <th style={{ padding: '0.75rem' }}>Allocated Cost</th>
            <th style={{ padding: '0.75rem' }}>Unit Cost/KG</th>
            <th style={{ padding: '0.75rem' }}>Gross Margin %</th>
          </tr>
        </thead>
        <tbody>
          {calculatedRows.map((row) => (
            <tr key={row.sku} style={{ borderBottom: '1px solid #1e293b' }}>
              <td style={{ padding: '0.75rem' }}>
                <strong style={{ color: '#f1f5f9' }}>{row.name}</strong>
                <div style={{ fontSize: '0.75rem', color: '#64748b' }}>{row.sku}</div>
              </td>
              <td style={{ padding: '0.75rem', color: '#38bdf8' }}>{row.weightKg.toFixed(1)} KG</td>
              <td style={{ padding: '0.75rem' }}>{row.marketPrice.toFixed(2)} TL</td>
              <td style={{ padding: '0.75rem', color: '#10b981' }}>{row.cutSalesValue.toFixed(2)} TL</td>
              <td style={{ padding: '0.75rem', color: '#fbbf24' }}>{row.allocatedCost.toFixed(2)} TL</td>
              <td style={{ padding: '0.75rem', fontWeight: 600 }}>{row.unitCost.toFixed(2)} TL/KG</td>
              <td style={{ padding: '0.75rem', color: row.marginPct > 0 ? '#34d399' : '#f87171' }}>
                {row.marginPct.toFixed(1)}%
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <div style={{ marginTop: '1rem', padding: '0.75rem', background: '#0f172a', borderRadius: '6px', fontSize: '0.85rem', color: '#94a3b8' }}>
        <em>Selected Principle:</em> {method === 'PHYSICAL_QUANTITY'
          ? 'Physical Quantity distributes unit cost equally per KG regardless of cut premium, distorting high-value cut margins.'
          : 'Net Realizable Value (NRV) allocates joint slaughter costs proportionally to market revenue potential, ensuring equitable gross margin reporting.'}
      </div>
    </div>
  );
}
