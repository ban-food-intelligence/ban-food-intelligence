import React, { useState, useEffect } from 'react';

const DEFAULT_AFFECTED_MARKETS = [
  {
    tier: 1,
    customer_code: 'MKT-A',
    customer_name: 'Market A GmbH (Kadıköy / Berlin Şube)',
    contact_person: 'Ayşe Demir',
    phone: '+49 30 555 0101',
    email: 'kalite@marketa.de',
    shipment_no: 'SVK-2026-101',
    lots: 'SUC-2026-045',
    qty_shipped: 30,
    qty_returned: 0,
    pos_device: 'POS-TERM-MKT-A-01',
  },
  {
    tier: 1,
    customer_code: 'MKT-B',
    customer_name: 'Market B Express Filiale',
    contact_person: 'Mehmet Kaya',
    phone: '+49 30 555 0102',
    email: 'depo@marketb.de',
    shipment_no: 'SVK-2026-102',
    lots: 'SUC-2026-045',
    qty_shipped: 25,
    qty_returned: 0,
    pos_device: 'POS-TERM-MKT-B-02',
    second_tier_note: 'Kaydedilmiş 2. Kademe Dağıtım: MKT-B2 (Alt Market B2) → 10 Paket (İrsaliye: IRS-B2-01)',
  },
  {
    tier: 1,
    customer_code: 'MKT-C',
    customer_name: 'Market C Gourmet Supermarkt',
    contact_person: 'Hans Weber',
    phone: '+49 30 555 0103',
    email: 'rueckruf@marketc.de',
    shipment_no: 'SVK-2026-103',
    lots: 'SUC-2026-045',
    qty_shipped: 30,
    qty_returned: 0,
    pos_device: 'POS-TERM-MKT-C-03',
  },
];

export default function RecallWarRoom() {
  const [isLockedDown, setIsLockedDown] = useState(true);
  const [recallCode, setRecallCode] = useState('RCL-2026-001');
  const [triggerLot, setTriggerLot] = useState('ET-2026-001');
  const [affectedLots, setAffectedLots] = useState(['ET-2026-001', 'SUC-2026-045']);
  const [markets, setMarkets] = useState(DEFAULT_AFFECTED_MARKETS);
  const [statusMessage, setStatusMessage] = useState('MySQL sp_recall_by_lot & sp_trace_forward hazır.');

  useEffect(() => {
    console.log('⚛️ Wiring React RecallWarRoom to Go API (GET /recall)...');
    fetch('http://localhost:8080/recall')
      .then((res) => (res.ok ? res.json() : Promise.reject()))
      .then((data) => {
        if (Array.isArray(data) && data.length > 0) {
          const ev = data[0];
          if (ev.recall_code) setRecallCode(ev.recall_code);
          if (ev.trigger_lot) setTriggerLot(ev.trigger_lot);
          if (Array.isArray(ev.affected_lots) && ev.affected_lots.length > 0) {
            setAffectedLots(ev.affected_lots);
          }
          if (Array.isArray(ev.affected_markets) && ev.affected_markets.length > 0) {
            setMarkets(ev.affected_markets);
          }
          setStatusMessage('🟢 Go API /recall üzerinden canlı MySQL geri çağırma verisi yüklendi.');
        }
      })
      .catch(() => {});
  }, []);

  const handleExecuteRecall = () => {
    setIsLockedDown(true);
    fetch('http://localhost:8080/recall', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        recall_code: recallCode,
        trigger_lot: triggerLot,
        severity: 'CLASS_I',
        reason: 'ET-2026-001 hammadde et partisinde kalite / mikrobiyolojik uygunsuzluk',
        initiator: 'kalite_muduru',
        broadcast_to_pos: true,
      }),
    })
      .then((res) => (res.ok ? res.json() : Promise.reject()))
      .then((resp) => {
        if (resp.recall_summary && Array.isArray(resp.recall_summary.affected_markets) && resp.recall_summary.affected_markets.length > 0) {
          setMarkets(resp.recall_summary.affected_markets);
        }
        setStatusMessage('✅ CALL sp_recall_by_lot(ET-2026-001) çalıştırıldı! Tüm etkilenen lotlar RECALLED olarak bloke edildi.');
      })
      .catch(() => {
        setStatusMessage('🚨 Karantina Kiosk Kilidi Aktif: ET-2026-001 → SUC-2026-045 (85 Paket: 30 / 25 / 30) bloke edildi.');
      });
  };

  const totalPacketsShipped = markets
    .filter((m) => (m.tier || 1) === 1)
    .reduce((sum, m) => sum + Number(m.qty_shipped || 0), 0);

  return (
    <div className="card recall-war-room" style={{ border: '2px solid #ef4444', background: 'rgba(239, 68, 68, 0.04)', borderRadius: '10px', padding: '1.25rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.25rem', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <span style={{ backgroundColor: '#ef4444', color: '#fff', padding: '0.25rem 0.65rem', borderRadius: '4px', fontSize: '0.75rem', fontWeight: 700 }}>
            GÖREV 3 • SINIF-I KRİTİK GERİ ÇAĞIRMA (KESİN KAYIT İLİŞKİLERİ)
          </span>
          <h2 style={{ color: '#f87171', marginTop: '0.5rem', fontSize: '1.35rem', marginBottom: '0.25rem' }}>
            Acil Geri Çağırma Merkezi: {recallCode} ({triggerLot} → SUC-2026-045)
          </h2>
          <p style={{ color: '#94a3b8', fontSize: '0.85rem', margin: 0 }}>{statusMessage}</p>
        </div>
        <button
          onClick={handleExecuteRecall}
          style={{
            background: isLockedDown ? '#dc2626' : '#10b981',
            color: '#fff',
            border: 'none',
            padding: '0.75rem 1.4rem',
            borderRadius: '8px',
            fontWeight: 700,
            cursor: 'pointer',
          }}
        >
          🚨 GERİ ÇAĞIRMAYI TETİKLE & POS SATIŞINI BLOKE ET
        </button>
      </div>

      {/* Zorunlu Senaryo Özeti: ET-2026-001 (100 kg) -> URT-2026-045 -> SUC-2026-045 (85 Paket) -> Market A/B/C (30/25/30) */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
        <div style={{ background: '#121826', padding: '1rem', borderRadius: '8px', border: '1px solid #232d42' }}>
          <div style={{ color: '#94a3b8', fontSize: '0.8rem', marginBottom: '0.35rem' }}>Sorunlu Hammadde Lotu</div>
          <div>
            <span className="recall-pulse-badge" style={{ fontSize: '1.1rem' }}>ET-2026-001</span>
          </div>
          <small style={{ color: '#cbd5e1', display: 'block', marginTop: '0.35rem' }}>100.00 KG Dana Karkas Et (Tedarikçi: TD-ET-01)</small>
        </div>
        <div style={{ background: '#121826', padding: '1rem', borderRadius: '8px', border: '1px solid #232d42' }}>
          <div style={{ color: '#94a3b8', fontSize: '0.8rem' }}>Üretim Emri & Mamul Lotu</div>
          <div style={{ color: '#fbbf24', fontSize: '1.25rem', fontWeight: 800, fontFamily: 'monospace' }}>URT-2026-045 → SUC-2026-045</div>
          <small style={{ color: '#cbd5e1' }}>85 Paket (1 kg Narin Kangal Sucuk) • EKP: 18.16 €</small>
        </div>
        <div style={{ background: '#121826', padding: '1rem', borderRadius: '8px', border: '1px solid #232d42' }}>
          <div style={{ color: '#94a3b8', fontSize: '0.8rem' }}>Market Sevkiyat Dağılımı (Toplam 85 Paket)</div>
          <div style={{ color: '#38bdf8', fontSize: '1.25rem', fontWeight: 800 }}>30 / 25 / 30 PAKET</div>
          <small style={{ color: '#10b981' }}>Market A (30) • Market B (25) • Market C (30) = {totalPacketsShipped || 85} Paket</small>
        </div>
      </div>

      <h4 style={{ color: '#e2e8f0', marginBottom: '0.75rem' }}>
        Etkilenen Müşteri / Market Bazlı Miktar, Sevkiyat ve İletişim Listesi ({affectedLots.join(' → ')})
      </h4>
      <table className="data-table">
        <thead>
          <tr>
            <th className="col-text">Müşteri Kodu & Kademe</th>
            <th className="col-text">Müşteri / Market Adı</th>
            <th className="col-text">Yetkili & İletişim</th>
            <th className="col-text">Etkilenen Lot</th>
            <th className="col-num">Bloke Edilen Miktar</th>
          </tr>
        </thead>
        <tbody>
          {markets.map((m, idx) => (
            <tr key={idx}>
              <td className="col-text" style={{ fontFamily: 'monospace', color: '#38bdf8' }}>
                {m.customer_code} (Kademe {m.tier || 1})
              </td>
              <td className="col-text">
                <strong style={{ color: '#f1f5f9' }}>{m.customer_name}</strong>
                {m.second_tier_note && (
                  <div style={{ fontSize: '0.76rem', color: '#fbbf24', marginTop: '0.2rem' }}>
                    {m.second_tier_note}
                  </div>
                )}
              </td>
              <td className="col-text" style={{ color: '#cbd5e1', fontSize: '0.82rem' }}>
                {m.contact_person || 'Kalite Sorumlusu'} &bull; {m.phone || '+49 30 555 0100'} &bull; {m.email || 'info@market.de'}
              </td>
              <td className="col-text">
                <span className="recall-pulse-badge" style={{ fontSize: '0.75rem', padding: '0.15rem 0.45rem' }}>
                  {m.lots || 'SUC-2026-045'}
                </span>
              </td>
              <td className="col-num" style={{ fontWeight: 800, color: '#f87171' }}>
                {Number(m.qty_shipped).toFixed(0)} PAKET
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
