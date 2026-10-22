import React from 'react';

export default function LotGenealogy() {
  const genealogyChain = [
    {
      lot: 'ET-2026-001',
      product: 'Quartered Beef Carcass (Afyon Certified)',
      stage: 'Carcass Inbound',
      category: 'CARCASS',
      quantity: '450.0 KG',
      status: 'CONSUMED',
      depth: 0,
    },
    {
      lot: 'CUT-2026-001-TRM',
      product: 'Beef Trimming 80/20 (Butchery Task 2 Output)',
      stage: 'Butchery Breakdown',
      category: 'RAW_CUT',
      quantity: '310.0 KG',
      status: 'CONSUMED',
      depth: 1,
    },
    {
      lot: 'URT-2026-045',
      product: 'Spiced Fermented Sucuk Dough',
      stage: 'Mincing & Kneading',
      category: 'INTERMEDIATE_WIP',
      quantity: '320.0 KG',
      status: 'CONSUMED',
      depth: 2,
    },
    {
      lot: 'SUC-2026-045',
      product: 'Traditional Kangal Sucuk 500g',
      stage: 'Casing, Drying & Packaging',
      category: 'FINISHED_SUCUK',
      quantity: '285.0 KG',
      status: 'RECALLED',
      depth: 3,
    },
  ];

  return (
    <div className="card lot-genealogy">
      <div style={{ marginBottom: '1.5rem' }}>
        <h2 style={{ fontSize: '1.3rem', color: '#f1f5f9' }}>
          Recursive Lot Traceability Lineage
        </h2>
        <p style={{ color: '#94a3b8', fontSize: '0.85rem' }}>
          Ali's MySQL <code>WITH RECURSIVE</code> Tree Execution &bull; Root: ET-2026-001 &rarr; SUC-2026-045
        </p>
      </div>

      <div style={{ position: 'relative', paddingLeft: '1rem' }}>
        {genealogyChain.map((node, i) => (
          <div
            key={node.lot}
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              marginBottom: '1.25rem',
              position: 'relative',
              paddingLeft: '1.5rem',
              borderLeft: i < genealogyChain.length - 1 ? '2px dashed #334155' : '2px solid transparent'
            }}
          >
            <div
              style={{
                position: 'absolute',
                left: '-9px',
                top: '0px',
                width: '16px',
                height: '16px',
                borderRadius: '50%',
                background: node.status === 'RECALLED' ? '#ef4444' : '#06b6d4',
                border: '3px solid #0a0e17'
              }}
            />
            <div
              style={{
                background: '#121826',
                border: `1px solid ${node.status === 'RECALLED' ? 'rgba(239, 68, 68, 0.4)' : '#232d42'}`,
                borderRadius: '8px',
                padding: '1rem',
                width: '100%',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center'
              }}
            >
              <div>
                <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                  <span style={{ fontWeight: 700, color: node.status === 'RECALLED' ? '#f87171' : '#38bdf8', fontSize: '1.1rem' }}>
                    {node.lot}
                  </span>
                  <span style={{ fontSize: '0.75rem', padding: '0.15rem 0.5rem', borderRadius: '4px', background: 'rgba(255,255,255,0.06)', color: '#cbd5e1' }}>
                    {node.category}
                  </span>
                </div>
                <div style={{ color: '#e2e8f0', marginTop: '0.25rem', fontSize: '0.9rem' }}>{node.product}</div>
                <div style={{ color: '#64748b', fontSize: '0.8rem', marginTop: '0.2rem' }}>Stage: {node.stage}</div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ color: '#f1f5f9', fontWeight: 600 }}>{node.quantity}</div>
                <span style={{ fontSize: '0.75rem', fontWeight: 700, color: node.status === 'RECALLED' ? '#ef4444' : '#94a3b8' }}>
                  {node.status}
                </span>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
