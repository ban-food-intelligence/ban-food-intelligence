import React, { useState, useEffect } from 'react';

// Every number on this page comes from the Go API (MySQL views v_production_cost,
// v_production_cost_breakdown, v_butchery_reconciliation, v_butchery_cost_allocation).
const API_BASE = 'http://localhost:8080';
const ORDER_NO = 'URT-2026-045';

const eur = (v) => `${Number(v).toFixed(2)} €`;
const kg = (v) => `${Number(v).toFixed(2)} KG`;
const pct = (v) => `%${Number(v).toFixed(2)}`;

function useApi(path) {
  const [state, setState] = useState({ data: null, error: null });
  useEffect(() => {
    fetch(`${API_BASE}${path}`)
      .then((res) => (res.ok ? res.json() : Promise.reject(new Error(`HTTP ${res.status}`))))
      .then((data) => setState({ data, error: null }))
      .catch((err) => setState({ data: null, error: err.message || 'Go API unreachable' }));
  }, [path]);
  return state;
}

function StatusNote({ error, label }) {
  return (
    <p style={{ color: error ? '#f87171' : '#94a3b8', fontSize: '0.85rem' }}>
      {error ? `${label}: Go API'ye ulaşılamadı (${error}).` : `${label}: veriler yükleniyor...`}
    </p>
  );
}

function Kpi({ label, value, accent = '#f1f5f9' }) {
  return (
    <div className="kpi-tile">
      <div className="kpi-label">{label}</div>
      <div className="kpi-value" style={{ color: accent }}>{value}</div>
    </div>
  );
}

function StatusBadge({ status }) {
  const posted = status === 'POSTED';
  return (
    <span className={`status-badge ${posted ? 'status-posted' : 'status-rejected'}`}>{status}</span>
  );
}

export default function ButcheryCostTable() {
  const production = useApi(`/production?order_no=${ORDER_NO}`);
  const butchery = useApi('/butchery');

  const order = Array.isArray(production.data)
    ? production.data.find((o) => o.order_no === ORDER_NO) || null
    : null;
  const ops = Array.isArray(butchery.data) ? butchery.data : [];
  const rejectedOps = ops.filter((o) => o.status === 'REJECTED');
  const postedOps = ops.filter((o) => o.status === 'POSTED');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* GÖREV 1: DIŞ HİZMETLE ÜRETİM VE GERÇEK MALİYET (EKP) */}
      <section className="card">
        <span className="section-tag" style={{ background: '#0284c7' }}>GÖREV 1 • FASON ÜRETİM VE GERÇEK MALİYET (EKP)</span>
        {!order ? (
          production.data ? (
            <p style={{ color: '#94a3b8', marginTop: '0.75rem' }}>{ORDER_NO} için maliyet kaydı bulunamadı.</p>
          ) : (
            <StatusNote error={production.error} label="Üretim" />
          )
        ) : (
          <>
            <h2 className="section-title">
              Üretim Emri {order.order_no} → Mamul Lotu {order.output_lot_number || '-'}
            </h2>
            <p className="section-sub">
              {order.product_name} • Planlanan {Number(order.planned_qty).toFixed(0)} / Gerçekleşen{' '}
              {Number(order.actual_qty).toFixed(0)} paket (fark {Number(order.qty_variance).toFixed(0)})
            </p>

            <div className="kpi-row">
              <Kpi label="Toplam Maliyet" value={eur(order.total_cost)} />
              <Kpi label="EKP (Gerçek Birim Maliyet)" value={`${eur(order.ekp_display)} / paket`} accent="#38bdf8" />
              <Kpi label="VKP (Satış Fiyatı)" value={`${eur(order.vkp)} / paket`} />
              <Kpi label="Brüt Kâr / Paket" value={eur(order.gross_profit_per_unit)} accent="#34d399" />
              <Kpi label="Brüt Marj" value={pct(order.margin_pct)} accent="#34d399" />
            </div>

            <div className="table-scroll">
              <table className="data-table">
                <thead>
                  <tr>
                    <th className="col-text">Parti / Belge No</th>
                    <th className="col-text">Maliyet Unsuru</th>
                    <th className="col-num">Miktar</th>
                    <th className="col-num">Birim Fiyat</th>
                    <th className="col-num">Tutar</th>
                  </tr>
                </thead>
                <tbody>
                  {(order.breakdown || []).map((item, idx) => (
                    <tr key={idx}>
                      <td className="col-text mono" style={{ color: '#38bdf8' }}>{item.lot_no || '-'}</td>
                      <td className="col-text">{item.cost_element}</td>
                      <td className="col-num">{Number(item.qty).toFixed(2)} {item.uom_code}</td>
                      <td className="col-num">{eur(item.unit_cost)}</td>
                      <td className="col-num" style={{ fontWeight: 700, color: '#fbbf24' }}>{eur(item.amount)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr className="total-row">
                    <td className="col-text" colSpan={4}>
                      Toplam ({eur(order.material_cost)} hammadde + {eur(order.service_cost)} fason hizmet) /{' '}
                      {Number(order.actual_qty).toFixed(0)} paket
                    </td>
                    <td className="col-num" style={{ color: '#fbbf24' }}>{eur(order.total_cost)}</td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </>
        )}
      </section>

      {/* GÖREV 2: REDDEDİLEN KAYIT (TUTARSIZLIK TESPİTİ) */}
      {!butchery.data && <StatusNote error={butchery.error} label="Parçalama" />}

      {rejectedOps.map((op) => (
        <section key={op.op_no} className="discrepancy-warning" role="alert">
          <div className="warning-head">
            <span className="section-tag" style={{ background: '#f59e0b', color: '#0f172a' }}>
              GÖREV 2 • TUTARSIZLIK TESPİT EDİLDİ
            </span>
            <StatusBadge status={op.status} />
          </div>
          <h2 className="section-title" style={{ color: '#fbbf24' }}>
            {op.op_no}: beyan edilen {eur(op.declared_total_cost)} ≠ hesaplanan {eur(op.total_cost)}
          </h2>
          <div className="kpi-row">
            <Kpi label="Beyan Edilen Maliyet" value={eur(op.declared_total_cost)} accent="#fbbf24" />
            <Kpi
              label={`Hesaplanan (${kg(op.input_weight_kg)} × ${eur(op.purchase_unit_price)})`}
              value={eur(op.total_cost)}
              accent="#34d399"
            />
            <Kpi
              label="Belgesiz Fark"
              value={`${Number(op.declared_cost_diff) > 0 ? '+' : ''}${eur(op.declared_cost_diff)}`}
              accent="#f87171"
            />
            <Kpi label="Kayıtsız Ağırlık" value={kg(op.weight_diff_kg)} accent="#f87171" />
          </div>
          <p className="warning-text">
            Giriş karkası {kg(op.input_weight_kg)} × {eur(op.purchase_unit_price)}/KG = <strong>{eur(op.total_cost)}</strong>{' '}
            gerçek alış maliyetidir. Beyan edilen <strong>{eur(op.declared_total_cost)}</strong> ile arasındaki{' '}
            <strong>{eur(op.declared_cost_diff)}</strong> fark hiçbir belgeyle desteklenmiyor. Ayrıca parçaların toplamı{' '}
            {kg(op.cut_weight_kg)}, kayıtlı fire {kg(op.waste_weight_kg)}: {kg(op.weight_diff_kg)} kayıp kayıtsızdır.
            Mutabakat kapısı bu kaydı reddetti; stoğa ve maliyete hiçbir etkisi yoktur.
          </p>
        </section>
      ))}

      {/* GÖREV 2: ONAYLANAN (POSTED) PARÇALAMA VE MALİYET DAĞITIMI */}
      {butchery.data && postedOps.length === 0 && (
        <p style={{ color: '#94a3b8' }}>Onaylanmış (POSTED) parçalama işlemi yok.</p>
      )}

      {postedOps.map((op) => {
        const cuts = op.allocated_cuts || [];
        const basisTotal = cuts.reduce((acc, c) => acc + Number(c.allocation_basis || 0), 0);
        const salesTotal = cuts.reduce((acc, c) => acc + Number(c.total_sales_value || 0), 0);
        return (
          <section key={op.op_no} className="card">
            <div className="warning-head">
              <span className="section-tag" style={{ background: '#10b981', color: '#0f172a' }}>
                GÖREV 2 • BÜTÜN KUZU PARÇALAMA VE MALİYET DAĞITIMI
              </span>
              <StatusBadge status={op.status} />
            </div>
            <h2 className="section-title">
              {op.op_no}: {kg(op.input_weight_kg)} = {kg(op.cut_weight_kg)} parça + {kg(op.waste_weight_kg)} fire
            </h2>
            <p className="section-sub">
              Dağıtım yöntemi: {op.allocation_method === 'VALUE' ? 'değer ağırlıklı (kg × VKP)' : 'miktar ağırlıklı (kg)'} •
              Ağırlık dengesi {op.weight_ok ? 'tamam' : 'HATALI'} • Maliyet dengesi {op.allocation_ok ? 'tamam' : 'HATALI'}
            </p>

            <div className="table-scroll">
              <table className="data-table">
                <thead>
                  <tr>
                    <th className="col-text">Parça Ürün ve Lot</th>
                    <th className="col-num">Ağırlık</th>
                    <th className="col-num">Verim</th>
                    <th className="col-num">VKP (€/KG)</th>
                    <th className="col-num">Satış Değeri</th>
                    <th className="col-num">Dağıtım Payı</th>
                    <th className="col-num">Aktarılan Maliyet</th>
                    <th className="col-num">Birim Maliyet</th>
                    <th className="col-num">Brüt Marj</th>
                  </tr>
                </thead>
                <tbody>
                  {cuts.map((c) => {
                    const isWaste = c.output_kind === 'WASTE';
                    return (
                      <tr key={c.cut_sku} style={isWaste ? { background: 'rgba(239, 68, 68, 0.06)' } : undefined}>
                        <td className="col-text">
                          <strong style={{ color: isWaste ? '#f87171' : '#f1f5f9' }}>{c.name}</strong>
                          <div className="mono" style={{ fontSize: '0.75rem', color: '#64748b' }}>
                            {c.cut_sku} • Lot: {c.lot_no || '-'}
                          </div>
                        </td>
                        <td className="col-num" style={{ color: '#38bdf8', fontWeight: 600 }}>{kg(c.yield_weight_kg)}</td>
                        <td className="col-num">{pct(c.yield_pct)}</td>
                        <td className="col-num">{isWaste ? '-' : eur(c.market_price_per_kg)}</td>
                        <td className="col-num" style={{ color: '#10b981' }}>{eur(c.total_sales_value)}</td>
                        <td className="col-num">
                          {basisTotal > 0 ? pct((100 * Number(c.allocation_basis)) / basisTotal) : '-'}
                        </td>
                        <td className="col-num" style={{ color: '#fbbf24', fontWeight: 700 }}>{eur(c.allocated_cost)}</td>
                        <td className="col-num">{isWaste ? '-' : `${eur(c.allocated_unit_cost_per_kg)}/KG`}</td>
                        <td className="col-num" style={{ color: isWaste ? '#94a3b8' : '#34d399', fontWeight: 600 }}>
                          {isWaste ? '-' : pct(c.margin_percentage)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
                <tfoot>
                  <tr className="total-row">
                    <td className="col-text">TOPLAM</td>
                    <td className="col-num">{kg(op.output_weight_kg)}</td>
                    <td className="col-num">-</td>
                    <td className="col-num">-</td>
                    <td className="col-num" style={{ color: '#10b981' }}>{eur(salesTotal)}</td>
                    <td className="col-num">%100.00</td>
                    <td className="col-num" style={{ color: '#fbbf24' }}>{eur(op.allocated_total)}</td>
                    <td className="col-num" colSpan={2} style={{ color: op.allocation_ok ? '#34d399' : '#f87171' }}>
                      {op.allocation_ok
                        ? `Dağıtılan = toplam maliyet (${eur(op.total_cost)})`
                        : `Fark: ${eur(op.allocation_diff)} (toplam maliyet ${eur(op.total_cost)})`}
                    </td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </section>
        );
      })}
    </div>
  );
}
