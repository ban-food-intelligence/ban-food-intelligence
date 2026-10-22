import React, { useState } from 'react';

export default function LotTraceViewer() {
  const [targetLot, setTargetLot] = useState('ET-2026-001');

  const sampleTrace = [
    { depth: 0, lot: 'ET-2026-001', product: 'Premium Smoked Beef Biltong 500g', role: 'Finished Good', qty: '250 KG', status: 'RELEASED' },
    { depth: 1, lot: 'WIP-2026-042', product: 'Marinated Flank Strips', role: 'Intermediate WIP', qty: '370 KG', status: 'CONSUMED' },
    { depth: 2, lot: 'RM-2026-010', product: 'Grade A Raw Beef Flank', role: 'Raw Material', qty: '350 KG', status: 'RELEASED' },
    { depth: 2, lot: 'RM-2026-005', product: 'Artisan Marinade Seasoning Blend', role: 'Raw Material', qty: '15 KG', status: 'RELEASED' },
    { depth: 2, lot: 'PKG-2026-099', product: 'Nitrogen Barrier Pouch', role: 'Packaging', qty: '500 PCS', status: 'RELEASED' }
  ];

  return (
    <div className="card lot-trace-section">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
        <h3>Multi-Tier Lot Traceability (Ali's Recursive Lineage)</h3>
        <span style={{ fontSize: '0.85rem', color: '#06b6d4' }}>Active Target: {targetLot}</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
        {sampleTrace.map((node, i) => (
          <div
            key={i}
            style={{
              padding: '0.85rem 1.2rem',
              borderRadius: '8px',
              backgroundColor: node.depth === 0 ? 'rgba(6, 182, 212, 0.12)' : 'rgba(255, 255, 255, 0.03)',
              borderLeft: `4px solid ${node.depth === 0 ? '#06b6d4' : node.depth === 1 ? '#f59e0b' : '#10b981'}`,
              marginLeft: `${node.depth * 1.5}rem`,
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center'
            }}
          >
            <div>
              <strong style={{ color: '#f8fafc', fontSize: '1rem' }}>{node.lot}</strong>
              <div style={{ color: '#94a3b8', fontSize: '0.85rem' }}>{node.product} &bull; {node.role}</div>
            </div>
            <div style={{ textAlign: 'right' }}>
              <span style={{ fontWeight: 600, color: '#38bdf8' }}>{node.qty}</span>
              <div style={{ fontSize: '0.75rem', color: '#64748b' }}>{node.status}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
