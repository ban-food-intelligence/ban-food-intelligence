import React, { createContext, useContext, useMemo, useState } from 'react';
import { setApiLanguage } from './api';

// Terms follow docs/GLOSSARY.md.
const DICT = {
  tr: {
    appName: 'BAN Food Intelligence',
    appSub: 'Üretim, maliyet, satış ve lot izlenebilirliği',
    tab_overview: 'Genel bakış', tab_purchases: 'Alışlar', tab_production: 'Üretim (EKP)', tab_butchery: 'Parçalama',
    tab_movements: 'Stok hareketleri', tab_sales: 'Satışlar', tab_trace: 'İzleme ve geri çağırma', tab_ai: 'Yapay zekâ',
    loading: 'Yükleniyor…', retry: 'Tekrar dene', details: 'Teknik ayrıntılar', noData: 'Kayıt yok.',
    errorNetwork: "Sunucuya bağlanılamadı. Go API'nin çalıştığını kontrol edin.",
    total: 'Toplam', lot: 'Lot', product: 'Ürün', qty: 'Miktar', unitPrice: 'Birim fiyat', amount: 'Tutar', date: 'Tarih',
    supplier: 'Tedarikçi', customer: 'Müşteri', warehouse: 'Depo', status: 'Durum', document: 'Belge',

    ov_title: 'Genel bakış', ov_sub: 'Tüm değerler canlı olarak MySQL üzerinden Go API ile gelir.',
    ov_production: 'Üretim emri {0}', ov_ekp: 'Birim maliyet (EKP)', ov_vkp: 'Satış fiyatı (VKP)', ov_margin: 'Marj',
    ov_variance: 'Miktar farkı', ov_butchery: 'Parçalama', ov_alloc_ok: 'Dağıtılan = toplam maliyet', ov_alloc_bad: 'Dağıtım farkı',
    ov_rejected: 'Reddedilen kayıt {0}', ov_sales: 'Satışlar', ov_revenue: 'Ciro', ov_gp: 'Brüt kâr', ov_stock: 'Stok',
    ov_lots_in_stock: 'Stokta lot', ov_stock_value: 'Stok değeri', ov_recall: 'Geri çağırma', ov_active_recalls: 'Aktif geri çağırma',
    ov_ai: 'Yapay zekâ (test hatası)', ov_ai_packages: 'Paket tahmini MAPE', ov_vs_baseline: 'referans {0}',
    ov_lines: '{0} satır',
    ov_rej_sub: 'beyan; hesaplanan {0}, fark +{1}, {2} kayıtsız',

    pu_title: 'Alışlar', pu_sub: 'Hammadde girişleri: tedarikçi, lot, miktar, fiyat ve son tüketim tarihi.',
    receipt: 'Giriş fişi', invoice: 'Fatura', expiry: 'SKT', supplierLot: 'Tedarikçi lotu',

    pr_title: 'Üretim ve gerçek maliyet (EKP)', pr_sub: 'Dış hizmetle üretim: hammadde maliyeti + hizmet faturası, gerçek mamul miktarına bölünür.',
    planned: 'Planlanan', actual: 'Gerçekleşen', variance: 'Fark', totalCost: 'Toplam maliyet', materialCost: 'Hammadde',
    serviceCost: 'Hizmet faturası', ekp: 'Birim maliyet (EKP)', vkp: 'Satış fiyatı (VKP)', gpUnit: 'Brüt kâr / paket',
    gpTotal: 'Brüt kâr (toplam)', margin: 'Marj', revenueVkp: 'VKP ile ciro', breakdown: 'Maliyet kırılımı', costElement: 'Maliyet unsuru',
    outputLot: 'Mamul lotu', formula: '{0} ÷ {1} paket = {2}',

    bu_title: 'Parçalama ve maliyet dağıtımı', bu_sub: 'Giriş lotu, ağırlık dengesi ve maliyetin parça lotlarına dağıtımı.',
    findings: 'Kontroller', weightCheck: 'Ağırlık dengesi', input: 'Giriş', cuts: 'Parçalar', byProduct: 'Yan ürün', waste: 'Fire',
    unrecorded: 'Kayıtsız', tolerance: 'Tolerans', allocationStored: 'Kayıtlı dağıtım', allocationPreview: 'Dağıtım anahtarı',
    methodValue: 'Değer ağırlıklı (kg × VKP)', methodQty: 'Miktar ağırlıklı (kg)', share: 'Pay', allocated: 'Dağıtılan maliyet',
    unitCost: 'Birim maliyet', salesValue: 'Satış değeri', yield: 'Verim', sumsExact: 'Toplam tam olarak {0}',
    rejected: 'Reddedildi', posted: 'Kaydedildi', declared: 'Beyan edilen', computed: 'Hesaplanan', previewNote: 'Kuruş farkları en büyük kalanlı satıra verilir; toplam her zaman toplam maliyete eşittir.',

    mv_title: 'Stok hareketleri', mv_sub: 'Değiştirilemez stok defteri; her hareketten sonra lot ve depo bakiyesi.',
    filterLot: 'Lot filtresi', allLots: 'Tüm lotlar', type: 'Hareket', balanceAfter: 'Bakiye', reference: 'Referans',

    sa_title: 'Satışlar', sa_sub: 'Kotlin satış uygulamasından gelen satış satırları, lot maliyeti ve brüt kâr.',
    soldAt: 'Satış zamanı', store: 'Mağaza', device: 'Cihaz', lineTotal: 'Satır tutarı', cost: 'Maliyet', gp: 'Brüt kâr',

    tr_title: 'İzleme ve geri çağırma', tr_sub: 'Sonuçlar yalnızca kayıtlı lot ilişkilerinden gelir; yapay zekâ kullanılmaz.',
    lotNo: 'Lot numarası', forward: 'İleriye izleme', backward: 'Geriye izleme', show: 'Göster',
    lotsReached: 'Ulaşılan lotlar', depth: 'Düzey', origin: 'Kaynak', links: 'Lot ilişkileri', inputLot: 'Girdi lotu', outputLot2: 'Çıktı lotu',
    consumed: 'Kullanılan', produced: 'Üretilen', shipments: 'Sevkiyatlar ve alıcılar', tier: 'Kademe', via: 'Üzerinden',
    contact: 'Yetkili', phone: 'Telefon', email: 'E-posta', address: 'Adres', stockNow: 'Depolardaki stok', posSales: 'Mağaza satışları',
    recallTitle: 'Geri çağırma', startRecall: '{0} için geri çağırma başlat', confirmRecall: '{0} ve ondan üretilen tüm lotlar bloke edilecek. Emin misiniz?',
    confirm: 'Evet, başlat', cancel: 'Vazgeç', recallStarted: '{0} başlatıldı. {1} lot bloke edildi.', recallFailed: 'Geri çağırma başlatılamadı.',
    activeRecalls: 'Geri çağırmalar', noRecall: 'Henüz geri çağırma yok.', exportCsv: 'İletişim listesi (CSV)', print: 'Yazdır',
    secondTierRecorded: 'İleri sevk kayıtlı', secondTierNotRecorded: 'İleri sevk kayıtlı değil', shipped: 'Sevk edilen', returned: 'İade',
    affectedLots: 'Etkilenen lotlar', reason: 'Neden', recallReason: 'Hammadde lotunda kalite sorunu', lockEnforced: 'Satış engelli',

    ai_title: 'Yapay zekâ: verim ve maliyet tahmini', ai_sub: 'Model ile basit referans yöntem aynı test döneminde karşılaştırılır.',
    chartTitle: 'Test dönemi hatası (MAPE, düşük olan daha iyi)', model: 'Model', baseline: 'Referans (geçmiş ortalama)',
    deviation: 'Sapma', predictions: 'Demo kayıtlarında tahmin ve gerçekleşen', mae: 'MAE', mape: 'MAPE', rows: 'Test satırı',
    t_actual_qty: 'Paket', t_ekp: 'Birim maliyet (EKP)', t_part_kg: 'Parça ağırlıkları', t_fire_kg: 'Fire', t_but_kg: 'But',
    t_sirt_kg: 'Sırt', t_omuz_kg: 'Omuz', t_kaburga_kg: 'Kaburga', t_kiyma_kg: 'Kıyma',
    task_PRODUCTION: 'Üretim (A)', task_BUTCHERY: 'Parçalama (B)',
    aiNote: "Modeller sentetik, anonim geçmiş veriyle eğitildi (ml-engine/generator_ali.py). Hata, tarihlerin son %20'sinde (zaman bölmeli test) ölçülür. Veri az ve sentetik: sonuçlar yol göstericidir.",
    f_DECLARED_COST_MISMATCH: 'Maliyet tutarsızlığı', f_WEIGHT_IMBALANCE: 'Ağırlık farkı', f_WEIGHT_BALANCED: 'Ağırlık dengesi',
    f_ALLOCATION_BALANCED: 'Dağıtım', f_ALLOCATION_MISMATCH: 'Dağıtım farkı',
  },
  en: {
    appName: 'BAN Food Intelligence',
    appSub: 'Production, cost, sales and lot traceability',
    tab_overview: 'Overview', tab_purchases: 'Purchases', tab_production: 'Production (EKP)', tab_butchery: 'Butchery',
    tab_movements: 'Lot movements', tab_sales: 'Sales', tab_trace: 'Trace & recall', tab_ai: 'AI',
    loading: 'Loading…', retry: 'Try again', details: 'Technical details', noData: 'No records.',
    errorNetwork: "Can't reach the server. Check that the Go API is running.",
    total: 'Total', lot: 'Lot', product: 'Product', qty: 'Quantity', unitPrice: 'Unit price', amount: 'Amount', date: 'Date',
    supplier: 'Supplier', customer: 'Customer', warehouse: 'Warehouse', status: 'Status', document: 'Document',

    ov_title: 'Overview', ov_sub: 'All values come live from MySQL through the Go API.',
    ov_production: 'Production order {0}', ov_ekp: 'Unit cost (EKP)', ov_vkp: 'Sale price (VKP)', ov_margin: 'Margin',
    ov_variance: 'Quantity variance', ov_butchery: 'Butchery', ov_alloc_ok: 'Allocated = total cost', ov_alloc_bad: 'Allocation difference',
    ov_rejected: 'Rejected record {0}', ov_sales: 'Sales', ov_revenue: 'Revenue', ov_gp: 'Gross profit', ov_stock: 'Stock',
    ov_lots_in_stock: 'Lots in stock', ov_stock_value: 'Stock value', ov_recall: 'Recall', ov_active_recalls: 'Active recalls',
    ov_ai: 'AI (test error)', ov_ai_packages: 'Package forecast MAPE', ov_vs_baseline: 'baseline {0}',
    ov_lines: '{0} lines',
    ov_rej_sub: 'declared; computed {0}, difference +{1}, {2} unrecorded',

    pu_title: 'Purchases', pu_sub: 'Raw material receipts: supplier, lot, quantity, price and expiry date.',
    receipt: 'Receipt', invoice: 'Invoice', expiry: 'Expiry', supplierLot: 'Supplier lot',

    pr_title: 'Production and real cost (EKP)', pr_sub: 'External production: material cost + service invoice, divided by the packages actually received.',
    planned: 'Planned', actual: 'Actual', variance: 'Variance', totalCost: 'Total cost', materialCost: 'Materials',
    serviceCost: 'Service invoice', ekp: 'Unit cost (EKP)', vkp: 'Sale price (VKP)', gpUnit: 'Gross profit / pack',
    gpTotal: 'Gross profit (total)', margin: 'Margin', revenueVkp: 'Revenue at VKP', breakdown: 'Cost breakdown', costElement: 'Cost element',
    outputLot: 'Finished lot', formula: '{0} ÷ {1} packs = {2}',

    bu_title: 'Butchery and cost allocation', bu_sub: 'Input lot, weight balance and how the cost is split over the part lots.',
    findings: 'Checks', weightCheck: 'Weight balance', input: 'Input', cuts: 'Parts', byProduct: 'By-product', waste: 'Waste',
    unrecorded: 'Unrecorded', tolerance: 'Tolerance', allocationStored: 'Posted allocation', allocationPreview: 'Allocation key',
    methodValue: 'Value-weighted (kg × VKP)', methodQty: 'Quantity-weighted (kg)', share: 'Share', allocated: 'Allocated cost',
    unitCost: 'Unit cost', salesValue: 'Sales value', yield: 'Yield', sumsExact: 'Sums exactly to {0}',
    rejected: 'Rejected', posted: 'Posted', declared: 'Declared', computed: 'Computed', previewNote: 'Rounding cents go to the lines with the largest remainder; the sum always equals the total cost.',

    mv_title: 'Lot movements', mv_sub: 'Append-only stock ledger with the lot and warehouse balance after each movement.',
    filterLot: 'Lot filter', allLots: 'All lots', type: 'Movement', balanceAfter: 'Balance', reference: 'Reference',

    sa_title: 'Sales', sa_sub: 'Sale lines from the Kotlin POS with lot cost and gross profit.',
    soldAt: 'Sold at', store: 'Shop', device: 'Device', lineTotal: 'Line total', cost: 'Cost', gp: 'Gross profit',

    tr_title: 'Trace & recall', tr_sub: 'Results come only from recorded lot relations; no AI is used.',
    lotNo: 'Lot number', forward: 'Forward trace', backward: 'Backward trace', show: 'Show',
    lotsReached: 'Lots reached', depth: 'Level', origin: 'Origin', links: 'Lot relations', inputLot: 'Input lot', outputLot2: 'Output lot',
    consumed: 'Used', produced: 'Produced', shipments: 'Shipments and buyers', tier: 'Tier', via: 'Via',
    contact: 'Contact', phone: 'Phone', email: 'Email', address: 'Address', stockNow: 'Stock in warehouses', posSales: 'Shop sales',
    recallTitle: 'Recall', startRecall: 'Start recall for {0}', confirmRecall: '{0} and every lot made from it will be blocked. Are you sure?',
    confirm: 'Yes, start', cancel: 'Cancel', recallStarted: '{0} started. {1} lots blocked.', recallFailed: 'The recall could not be started.',
    activeRecalls: 'Recalls', noRecall: 'No recall yet.', exportCsv: 'Contact list (CSV)', print: 'Print',
    secondTierRecorded: 'Onward delivery recorded', secondTierNotRecorded: 'Onward delivery not recorded', shipped: 'Shipped', returned: 'Returned',
    affectedLots: 'Affected lots', reason: 'Reason', recallReason: 'Quality problem in the raw material lot', lockEnforced: 'Sale blocked',

    ai_title: 'AI: yield and cost forecasts', ai_sub: 'The model and a simple reference method are compared on the same test period.',
    chartTitle: 'Error on the test period (MAPE, lower is better)', model: 'Model', baseline: 'Baseline (historical mean)',
    deviation: 'Deviation', predictions: 'Prediction and actual on the demo records', mae: 'MAE', mape: 'MAPE', rows: 'Test rows',
    t_actual_qty: 'Packages', t_ekp: 'Unit cost (EKP)', t_part_kg: 'Part weights', t_fire_kg: 'Waste', t_but_kg: 'Leg',
    t_sirt_kg: 'Loin', t_omuz_kg: 'Shoulder', t_kaburga_kg: 'Ribs', t_kiyma_kg: 'Mince',
    task_PRODUCTION: 'Production (A)', task_BUTCHERY: 'Butchery (B)',
    aiNote: 'Models are trained on synthetic, anonymised history (ml-engine/generator_ali.py). Error is measured on the latest 20% of dates (time split). Small and synthetic data: indicative only.',
    f_DECLARED_COST_MISMATCH: 'Cost mismatch', f_WEIGHT_IMBALANCE: 'Weight gap', f_WEIGHT_BALANCED: 'Weight balance',
    f_ALLOCATION_BALANCED: 'Allocation', f_ALLOCATION_MISMATCH: 'Allocation gap',
  },
};

const MOVEMENT_TYPES = {
  tr: {
    PURCHASE_IN: 'Alış girişi', TRANSFER_OUT: 'Transfer çıkışı', TRANSFER_IN: 'Transfer girişi', PRODUCTION_CONSUME: 'Üretimde tüketim',
    PRODUCTION_RECEIPT: 'Mamul kabulü', BUTCHERY_CONSUME: 'Parçalamaya giriş', BUTCHERY_OUTPUT: 'Parçalama çıktısı', SHIPMENT: 'Sevkiyat',
    SALE: 'Satış', RETURN: 'İade', REWORK_CONSUME: 'Yeniden işleme girişi', REWORK_OUTPUT: 'Yeniden işleme çıktısı', WASTE: 'Fire',
    ADJUSTMENT_IN: 'Sayım fazlası', ADJUSTMENT_OUT: 'Sayım eksiği',
  },
  en: {
    PURCHASE_IN: 'Purchase receipt', TRANSFER_OUT: 'Transfer out', TRANSFER_IN: 'Transfer in', PRODUCTION_CONSUME: 'Used in production',
    PRODUCTION_RECEIPT: 'Production receipt', BUTCHERY_CONSUME: 'Into butchery', BUTCHERY_OUTPUT: 'Butchery output', SHIPMENT: 'Shipment',
    SALE: 'Sale', RETURN: 'Return', REWORK_CONSUME: 'Into rework', REWORK_OUTPUT: 'Rework output', WASTE: 'Waste',
    ADJUSTMENT_IN: 'Count surplus', ADJUSTMENT_OUT: 'Count shortage',
  },
};

const LangContext = createContext(null);

function readLang() {
  try {
    const saved = localStorage.getItem('ban-lang');
    if (saved === 'tr' || saved === 'en') return saved;
  } catch {
    /* storage unavailable */
  }
  return navigator.language?.startsWith('tr') ? 'tr' : 'en';
}

export function LangProvider({ children }) {
  const [lang, setLangState] = useState(readLang);
  setApiLanguage(lang);
  const value = useMemo(() => {
    const setLang = (l) => {
      try {
        localStorage.setItem('ban-lang', l);
      } catch {
        /* ignore */
      }
      setApiLanguage(l);
      setLangState(l);
    };
    const t = (key, ...args) => {
      const s = DICT[lang][key] ?? DICT.en[key] ?? key;
      return args.reduce((acc, a, i) => acc.replace(`{${i}}`, a), s);
    };
    return { lang, setLang, t, fmt: formatters(lang), movementType: (m) => MOVEMENT_TYPES[lang][m] ?? m };
  }, [lang]);
  return <LangContext.Provider value={value}>{children}</LangContext.Provider>;
}

export function useLang() {
  return useContext(LangContext);
}

function formatters(lang) {
  const locale = lang === 'tr' ? 'tr-TR' : 'en-GB';
  const num = (v, d = 2) =>
    v == null || Number.isNaN(Number(v))
      ? '–'
      : new Intl.NumberFormat(locale, { minimumFractionDigits: d, maximumFractionDigits: d }).format(Number(v));
  const unit = (uom, q) => {
    const u = (uom || '').toUpperCase();
    if (u === 'KG') return 'kg';
    if (u === 'PAKET') return lang === 'tr' ? 'paket' : Number(q) === 1 ? 'pack' : 'packs';
    if (u === 'ADET') return lang === 'tr' ? 'adet' : 'pcs';
    return (uom || '').toLowerCase();
  };
  return {
    num,
    money: (v) => (v == null ? '–' : lang === 'tr' ? `${num(v)} €` : `€${num(v)}`),
    qty: (v, uom) => (v == null ? '–' : `${num(v, (uom || '').toUpperCase() === 'KG' ? 2 : 0)} ${unit(uom, v)}`),
    pct: (v, d = 2) => {
      if (v == null) return '–';
      if (lang !== 'tr') return `${num(v, d)}%`;
      return Number(v) < 0 ? `-%${num(Math.abs(v), d)}` : `%${num(v, d)}`;
    },
    date: (v) => {
      if (!v) return '–';
      const dt = new Date(v.length === 10 ? `${v}T00:00:00` : v);
      if (Number.isNaN(dt.getTime())) return v;
      return lang === 'tr'
        ? dt.toLocaleDateString('tr-TR', { day: '2-digit', month: '2-digit', year: 'numeric' })
        : dt.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
    },
    dateTime: (v) => {
      if (!v) return '–';
      const dt = new Date(v);
      if (Number.isNaN(dt.getTime())) return v;
      return dt.toLocaleString(locale, { day: '2-digit', month: lang === 'tr' ? '2-digit' : 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    },
  };
}
