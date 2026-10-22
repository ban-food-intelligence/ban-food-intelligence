import React, { useState } from 'react';

export default function RecallWarRoom() {
  const [isLockedDown, setIsLockedDown] = useState(true);

  const affectedMarkets = [
    { code: 'MKT-A', name: 'Market A - Kadikoy Store', status: 'LOCKED', posDevice: 'POS-DEVICE-KAD-01', isolatedUnits: '100 KG' },
    { code: 'MKT-B', name: 'Market B - Besiktas Express', status: 'LOCKED', posDevice: 'POS-DEVICE-BES-02', isolatedUnits: '80 KG' },
    { code: 'MKT-C', name: 'Market C - Cankaya Gourmet', status: 'LOCKED', posDevice: 'POS-DEVICE-ANK-03', isolatedUnits: '70 KG' },
  ];

  return (
    <div className="card recall-war-room" style={{ border: '2px solid #ef4444', background: 'rgba(239, 68, 68, 0.04)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <span style={{ backgroundColor: '#ef4444', color: '#fff', padding: '0.25rem 0.6rem', borderRadius: '4px', fontSize: '0.75rem', fontWeight: 700 }}>
            CLASS I CRITICAL RECALL
          </span>
          <h2 style={{ color: '#f87171', marginTop: '0.5rem', fontSize: '1.4rem' }}>
            Emergency Recall War Room: RCL-2026-ET001-SALMONELLA
          </h2>
          <p style={{ color: '#94a3b8', fontSize: '0.85rem' }}>
            Triggered by: Ali_Quality_Director &bull; Root Cause: Carcass Lot ET-2026-001
          </p>
        </div>
        <button
          onClick={() => setIsLockedDown(!isLockedDown)}
          style={{
            background: isLockedDown ? '#dc2626' : '#10b981',
            color: '#fff',
            border: 'none',
            padding: '0.75rem 1.5rem',
            borderRadius: '8px',
            fontWeight: 700,
            cursor: 'pointer'
          }}
        >
          {isLockedDown ? '🚨 BROADCAST ACTIVE (ALL POS LOCKED)' : 'DISARM LOCK'}
        </button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
        <div style={{ background: '#121826', padding: '1rem', borderRadius: '8px', border: '1px solid #232d42' }}>
          <div style={{ color: '#94a3b8', fontSize: '0.8rem' }}>Root Contaminated Lot</div>
          <div style={{ color: '#f87171', fontSize: '1.2rem', fontWeight: 700 }}>ET-2026-001</div>
          <small style={{ color: '#64748b' }}>Quartered Beef Carcass</small>
        </div>
        <div style={{ background: '#121826', padding: '1rem', borderRadius: '8px', border: '1px solid #232d42' }}>
          <div style={{ color: '#94a3b8', fontSize: '0.8rem' }}>Downstream Finished Goods</div>
          <div style={{ color: '#fbbf24', fontSize: '1.2rem', fontWeight: 700 }}>SUC-2026-045</div>
          <small style={{ color: '#64748b' }}>Via Intermediate WIP URT-2026-045</small>
        </div>
        <div style={{ background: '#121826', padding: '1rem', borderRadius: '8px', border: '1px solid #232d42' }}>
          <div style={{ color: '#94a3b8', fontSize: '0.8rem' }}>POS Terminals Enforced</div>
          <div style={{ color: '#38bdf8', fontSize: '1.2rem', fontWeight: 700 }}>3 / 3 Terminals</div>
          <small style={{ color: '#10b981' }}>Red Lock Screen Active</small>
        </div>
      </div>

      <h4 style={{ color: '#e2e8f0', marginBottom: '0.75rem' }}>Market Quarantine Status</h4>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
        {affectedMarkets.map((m, idx) => (
          <div key={idx} style={{ display: 'flex', justifyContent: 'space-between', padding: '0.75rem 1rem', background: '#121826', borderRadius: '6px' }}>
            <div>
              <strong style={{ color: '#f1f5f9' }}>{m.name}</strong> ({m.code})
              <div style={{ fontSize: '0.75rem', color: '#64748b' }}>Target Device: {m.posDevice}</div>
            </div>
            <div style={{ textAlign: 'right' }}>
              <span style={{ color: '#f87171', fontWeight: 700 }}>{m.status}</span>
              <div style={{ fontSize: '0.75rem', color: '#94a3b8' }}>Quarantined: {m.isolatedUnits}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
