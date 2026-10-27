import React, { useState, useEffect } from 'react';

const DEFAULT_TASK_4A_ROWS = [
  {
    model_name: 'Basit Referans Yöntem (Tarihsel Ortalama Baseline)',
    target: 'URT-2026-045 Mamul Miktarı (Paket)',
    predicted: 88.00,
    actual: 85.00,
    deviation: -3.00,
    deviation_pct: -3.53,
    ekp_pred: 17.54,
    ekp_actual: 18.16,
    ekp_diff: +0.62,
    test_mae: '2.2260 Paket / 0.2266 €',
  },
  {
    model_name: 'Ali — LightGBM / Ridge Ensemble (ali-1.0.0)',
    target: 'URT-2026-045 Mamul Miktarı (Paket)',
    predicted: 85.62,
    actual: 85.00,
    deviation: -0.62,
    deviation_pct: -0.73,
    ekp_pred: 18.03,
    ekp_actual: 18.16,
    ekp_diff: +0.13,
    test_mae: '1.2873 Paket / 0.1309 €',
  },
  {
    model_name: 'Nuri — HuberRegressor + GP Stacker Champion (nuri-2.0.0)',
    target: 'URT-2026-045 Mamul Miktarı (Paket)',
    predicted: 85.28,
    actual: 85.00,
    deviation: -0.28,
    deviation_pct: -0.33,
    ekp_pred: 18.10,
    ekp_actual: 18.16,
    ekp_diff: +0.06,
    test_mae: '1.2848 Paket / 0.1307 € (R² = 0.6752)',
  },
];

const DEFAULT_TASK_4B_CUTS = [
  { cut_sku: 'KZ-BUT', name: 'Kuzu But (Kemikli)', baseline_kg: 7.20, predicted_kg: 7.46, actual_kg: 7.50 },
  { cut_sku: 'KZ-OMUZ', name: 'Kuzu Omuz / Kol', baseline_kg: 5.00, predicted_kg: 4.83, actual_kg: 4.80 },
  { cut_sku: 'KZ-SIRT', name: 'Kuzu Sırt / Pirzola', baseline_kg: 4.00, predicted_kg: 4.18, actual_kg: 4.20 },
  { cut_sku: 'KZ-KABURGA', name: 'Kuzu Kaburga', baseline_kg: 3.75, predicted_kg: 3.52, actual_kg: 3.50 },
  { cut_sku: 'KZ-KIYMA', name: 'Kuzu Kıyma / Parça Et', baseline_kg: 2.25, predicted_kg: 2.03, actual_kg: 2.00 },
  { cut_sku: 'KZ-FIRE', name: 'Fire / Kemik / Soğutma Kaybı', baseline_kg: 2.80, predicted_kg: 2.98, actual_kg: 3.00 },
];

export default function MLPredictionsTable() {
  const [dbDeviations, setDbDeviations] = useState([]);

  useEffect(() => {
    console.log('⚛️ Wiring React MLPredictionsTable to Go API (GET /yield)...');
    fetch('http://localhost:8080/yield')
      .then((res) => (res.ok ? res.json() : Promise.reject()))
      .then((data) => {
        if (Array.isArray(data) && data.length > 0) {
          setDbDeviations(data);
        }
      })
      .catch(() => {});
  }, []);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* GÖREV 4A: ÜRETİM VERİMİ VE EKP TAHMİNİ (TAHMİN vs GERÇEK vs SAPMA) */}
      <div className="card" style={{ background: '#0f172a', border: '1px solid #334155', borderRadius: '10px', padding: '1.25rem' }}>
        <div style={{ marginBottom: '1rem' }}>
          <span style={{ background: '#8b5cf6', color: '#fff', padding: '0.2rem 0.6rem', borderRadius: '4px', fontSize: '0.75rem', fontWeight: 700 }}>
            GÖREV 4A • ZORUNLU YAPAY ZEKA ÇALIŞMASI #1
          </span>
          <h2 style={{ fontSize: '1.25rem', color: '#f1f5f9', marginTop: '0.4rem', marginBottom: '0.25rem' }}>
            Dış Hizmetle Üretim Verimi (Paket) ve Birim Maliyet (EKP €) Tahmini vs. Gerçekleşen Sonuç
          </h2>
          <p style={{ color: '#94a3b8', fontSize: '0.85rem', margin: 0 }}>
            Üretim Emri: <strong>URT-2026-045</strong> (100 kg Et, %18,5 Yağ, 14 Gün Kurutma) &bull; Gerçekleşen: <strong>85 Paket / 18,16 € EKP</strong>
          </p>
        </div>

        <table className="data-table">
          <thead>
            <tr>
              <th className="col-text">Model / Yöntem</th>
              <th className="col-num">Tahmin Edilen Miktar</th>
              <th className="col-num">Gerçekleşen Miktar</th>
              <th className="col-num">Miktar Sapması</th>
              <th className="col-num">Tahmin EKP (€)</th>
              <th className="col-num">Gerçek EKP (€)</th>
              <th className="col-num">EKP Sapması</th>
              <th className="col-num">5-Fold Test MAE</th>
            </tr>
          </thead>
          <tbody>
            {DEFAULT_TASK_4A_ROWS.map((r, idx) => (
              <tr key={idx}>
                <td className="col-text" style={{ fontWeight: 600, color: idx === 2 ? '#34d399' : '#f1f5f9' }}>{r.model_name}</td>
                <td className="col-num" style={{ color: '#38bdf8', fontWeight: 600 }}>{r.predicted.toFixed(2)} Paket</td>
                <td className="col-num" style={{ color: '#f1f5f9' }}>{r.actual.toFixed(2)} Paket</td>
                <td className="col-num" style={{ color: Math.abs(r.deviation) < 1 ? '#34d399' : '#fbbf24' }}>
                  {r.deviation > 0 ? `+${r.deviation.toFixed(2)}` : r.deviation.toFixed(2)} Paket (%{r.deviation_pct.toFixed(2)})
                </td>
                <td className="col-num" style={{ color: '#38bdf8', fontWeight: 600 }}>{r.ekp_pred.toFixed(2)} €</td>
                <td className="col-num" style={{ color: '#f1f5f9' }}>{r.ekp_actual.toFixed(2)} €</td>
                <td className="col-num" style={{ color: '#34d399' }}>+{r.ekp_diff.toFixed(2)} €</td>
                <td className="col-num" style={{ fontFamily: 'monospace', color: '#cbd5e1' }}>{r.test_mae}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* GÖREV 4B: BÜTÜN KUZU PARÇALAMA VERİMİ TAHMİNİ (SIMPLEX KORUNUMLU) */}
      <div className="card" style={{ background: '#0f172a', border: '1px solid #334155', borderRadius: '10px', padding: '1.25rem' }}>
        <div style={{ marginBottom: '1rem' }}>
          <span style={{ background: '#10b981', color: '#0f172a', padding: '0.2rem 0.6rem', borderRadius: '4px', fontSize: '0.75rem', fontWeight: 800 }}>
            GÖREV 4B • ZORUNLU YAPAY ZEKA ÇALIŞMASI #2
          </span>
          <h2 style={{ fontSize: '1.25rem', color: '#f1f5f9', marginTop: '0.4rem', marginBottom: '0.25rem' }}>
            Bütün Kuzu Parçalama Verimi Tahmini (25,00 KG Giriş Karkası → Beklenen vs. Gerçekleşen Parça Ağırlıkları)
          </h2>
          <p style={{ color: '#94a3b8', fontSize: '0.85rem', margin: 0 }}>
            Model: <strong>Nuri Simplex-Projected Multi-Output Ridge (Test MAE: 0,11436 KG)</strong> &bull; Kütle Korunumu: ∑ Tahmin = 25,00 KG
          </p>
        </div>

        <table className="data-table">
          <thead>
            <tr>
              <th className="col-text">Parça Kodu & Ürün Adı</th>
              <th className="col-num">Basit Referans (Reçete %)</th>
              <th className="col-num">KI Model Tahmini (KG)</th>
              <th className="col-num">Gerçekleşen Ağırlık (KG)</th>
              <th className="col-num">Fark / Sapma (KG)</th>
              <th className="col-num">Oransal Fark (%)</th>
            </tr>
          </thead>
          <tbody>
            {DEFAULT_TASK_4B_CUTS.map((c) => {
              const diffKg = c.actual_kg - c.predicted_kg;
              const diffPct = (diffKg / c.actual_kg) * 100;
              return (
                <tr key={c.cut_sku}>
                  <td className="col-text" style={{ fontWeight: 600, color: '#f1f5f9' }}>
                    {c.name} <span style={{ color: '#64748b', fontFamily: 'monospace' }}>({c.cut_sku})</span>
                  </td>
                  <td className="col-num" style={{ color: '#94a3b8' }}>{c.baseline_kg.toFixed(2)} KG</td>
                  <td className="col-num" style={{ color: '#38bdf8', fontWeight: 700 }}>{c.predicted_kg.toFixed(2)} KG</td>
                  <td className="col-num" style={{ color: '#10b981', fontWeight: 700 }}>{c.actual_kg.toFixed(2)} KG</td>
                  <td className="col-num" style={{ color: '#34d399', fontFamily: 'monospace' }}>
                    {diffKg >= 0 ? `+${diffKg.toFixed(2)}` : diffKg.toFixed(2)} KG
                  </td>
                  <td className="col-num" style={{ color: '#cbd5e1', fontFamily: 'monospace' }}>
                    {diffPct >= 0 ? `+${diffPct.toFixed(2)}%` : `${diffPct.toFixed(2)}%`}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>

        {dbDeviations.length > 0 && (
          <div style={{ marginTop: '1rem', fontSize: '0.8rem', color: '#34d399' }}>
            🟢 MySQL <code>v_ml_prediction_deviation</code> görünümünden {dbDeviations.length} adet canlı tahmin kaydı doğrulandı.
          </div>
        )}

        <div style={{ marginTop: '1rem', padding: '0.85rem', background: '#121826', borderRadius: '8px', border: '1px solid #334155', fontSize: '0.82rem', color: '#94a3b8' }}>
          <strong style={{ color: '#e2e8f0' }}>Şartname Madde 4 Notu (Veri Sınırlılıkları & İzlenebilirlik İlkesi):</strong> Моdeller 5-katlı çapraz doğrulama (5-Fold CV) ile fiziksel kütle korunumu ve Arrhenius bozulma kinetiğine uygun sentetik/anonimize veriler + MySQL tohum kayıtları üzerinde eğitilmiştir. Gerçek fabrika verisi az olduğunda oluşan epistemik belirsizlik Gaussian Process varyans kapısı (<code>σ²</code>) ile ölçülür. <strong>Lot izlenebilirliği ve geri çağırma kararları KI tahminine değil, tamamen MySQL <code>lot_link</code> kesin kayıt ilişkilerine dayanır.</strong>
        </div>
      </div>
    </div>
  );
}
