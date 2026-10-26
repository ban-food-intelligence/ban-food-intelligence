import React, { useState, useEffect } from 'react';

const FORWARD_TRACE_DEFAULT = [
  {
    lot: 'ET-2026-001',
    product: 'Dana Karkas Et (Hammadde Girişi • Tedarikçi: TD-ET-01)',
    stage: 'Kök Lot (Derinlik 0) • Alış Fiyatı: 9,70 €/KG',
    category: 'PURCHASE',
    quantity: '100,00 KG',
    status: 'RECALLED',
    depth: 0,
  },
  {
    lot: 'URT-2026-045 → SUC-2026-045',
    product: 'Narin Kangal Sucuk (1 kg Paket • Fason Üretim Çıktısı)',
    stage: 'Üretim Emri URT-2026-045 (100 kg Et + 2 kg Baharat + 5 kg Bağırsak) • EKP: 18,16 €',
    category: 'PRODUCTION',
    quantity: '85 PAKET',
    status: 'RECALLED',
    depth: 1,
  },
  {
    lot: 'SVK-2026-101 / 102 / 103 (Market A / B / C)',
    product: 'Market A: 30 Paket • Market B: 25 Paket (→ Alt Market B2: 10 Paket) • Market C: 30 Paket',
    stage: '1. ve Kaydedilmiş 2. Kademe Sevkiyat Dağılımı (Toplam 85 Paket)',
    category: 'SHIPMENT',
    quantity: '30 + 25 + 30 = 85 PAKET',
    status: 'RECALLED',
    depth: 2,
  },
];

const BACKWARD_TRACE_DEFAULT = [
  {
    lot: 'SUC-2026-045',
    product: 'Narin Kangal Sucuk (Mamul Lotu • Üretim Emri: URT-2026-045)',
    stage: 'Hedef Mamul Lotu (Derinlik 0) • EKP: 18,16 €/Paket',
    category: 'FINISHED_GOOD',
    quantity: '85 PAKET',
    status: 'RECALLED',
    depth: 0,
  },
  {
    lot: 'ET-2026-001',
    product: 'Dana Karkas Et (Tedarikçi: TD-ET-01 Anadolu Besi) • SKT: 2026-10-25',
    stage: 'Kullanılan Hammadde #1 (100,00 KG × 9,70 € = 970,00 €)',
    category: 'RAW_MATERIAL',
    quantity: '100,00 KG',
    status: 'RECALLED',
    depth: 1,
  },
  {
    lot: 'BAH-2026-004',
    product: 'Özel Sucuk Baharat Karışımı (Tedarikçi: TD-BAH-01)',
    stage: 'Kullanılan Hammadde #2 (2,00 KG × 24,00 € = 48,00 €)',
    category: 'RAW_MATERIAL',
    quantity: '2,00 KG',
    status: 'ACTIVE',
    depth: 1,
  },
  {
    lot: 'BAG-2026-002',
    product: 'Doğal Kuru Dana Bağırsağı (Tedarikçi: TD-BAG-01)',
    stage: 'Kullanılan Hammadde #3 (5,00 KG × 11,60 € = 58,00 €)',
    category: 'RAW_MATERIAL',
    quantity: '5,00 KG',
    status: 'ACTIVE',
    depth: 1,
  },
];

export default function LotGenealogy() {
  const [direction, setDirection] = useState('forward');
  const [nodes, setNodes] = useState(FORWARD_TRACE_DEFAULT);

  useEffect(() => {
    const lotNo = direction === 'forward' ? 'ET-2026-001' : 'SUC-2026-045';
    fetch(`http://localhost:8080/trace/${lotNo}?direction=${direction}`)
      .then((res) => (res.ok ? res.json() : Promise.reject()))
      .then((data) => {
        if (data && Array.isArray(data.nodes) && data.nodes.length > 0) {
          const mapped = data.nodes.map((n) => ({
            lot: n.current_lot_number,
            product: n.product_name,
            stage: `Yol: ${n.trace_path}`,
            category: n.product_category,
            quantity: `${n.quantity}`,
            status: n.status || 'RECALLED',
            depth: n.depth,
          }));
          setNodes(mapped);
        } else {
          setNodes(direction === 'forward' ? FORWARD_TRACE_DEFAULT : BACKWARD_TRACE_DEFAULT);
        }
      })
      .catch(() => {
        setNodes(direction === 'forward' ? FORWARD_TRACE_DEFAULT : BACKWARD_TRACE_DEFAULT);
      });
  }, [direction]);

  return (
    <div className="card lot-genealogy" style={{ background: '#0f172a', border: '1px solid #334155', borderRadius: '10px', padding: '1.25rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <h2 style={{ fontSize: '1.3rem', color: '#f1f5f9', margin: 0 }}>
            Görev 3: Çoktan-Çoğa Lot Soyağacı (WITH RECURSIVE İleriye & Geriye İzleme)
          </h2>
          <p style={{ color: '#94a3b8', fontSize: '0.85rem', marginTop: '0.3rem' }}>
            MySQL <code>sp_trace_forward(&apos;ET-2026-001&apos;)</code> &amp; <code>sp_trace_backward(&apos;SUC-2026-045&apos;)</code>
          </p>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button
            onClick={() => setDirection('forward')}
            style={{
              background: direction === 'forward' ? '#06b6d4' : '#1e293b',
              color: '#fff',
              border: 'none',
              padding: '0.55rem 1rem',
              borderRadius: '6px',
              cursor: 'pointer',
              fontWeight: 600,
              fontSize: '0.85rem',
            }}
          >
            ➡️ İleriye İzleme (ET-2026-001 → Marketler)
          </button>
          <button
            onClick={() => setDirection('backward')}
            style={{
              background: direction === 'backward' ? '#8b5cf6' : '#1e293b',
              color: '#fff',
              border: 'none',
              padding: '0.55rem 1rem',
              borderRadius: '6px',
              cursor: 'pointer',
              fontWeight: 600,
              fontSize: '0.85rem',
            }}
          >
            ⬅️ Geriye İzleme (SUC-2026-045 → Tedarikçiler)
          </button>
        </div>
      </div>

      <div style={{ position: 'relative', paddingLeft: '1rem' }}>
        {nodes.map((node, i) => (
          <div
            key={`${node.lot}-${i}`}
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              marginBottom: '1.1rem',
              position: 'relative',
              paddingLeft: '1.5rem',
              borderLeft: i < nodes.length - 1 ? '2px dashed #334155' : '2px solid transparent',
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
                background: node.status === 'RECALLED' ? '#ef4444' : '#10b981',
                border: '3px solid #0a0e17',
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
                alignItems: 'center',
              }}
            >
              <div>
                <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                  <span style={{ fontWeight: 700, color: node.status === 'RECALLED' ? '#f87171' : '#38bdf8', fontSize: '1.05rem', fontFamily: 'monospace' }}>
                    {node.lot}
                  </span>
                  <span style={{ fontSize: '0.75rem', padding: '0.15rem 0.5rem', borderRadius: '4px', background: 'rgba(255,255,255,0.06)', color: '#cbd5e1' }}>
                    {node.category}
                  </span>
                </div>
                <div style={{ color: '#e2e8f0', marginTop: '0.25rem', fontSize: '0.9rem' }}>{node.product}</div>
                <div style={{ color: '#64748b', fontSize: '0.8rem', marginTop: '0.2rem' }}>{node.stage}</div>
              </div>
              <div style={{ textAlign: 'right' }}>
                <div style={{ color: '#f1f5f9', fontWeight: 700 }}>{node.quantity}</div>
                <span style={{ fontSize: '0.75rem', fontWeight: 700, color: node.status === 'RECALLED' ? '#ef4444' : '#34d399' }}>
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
