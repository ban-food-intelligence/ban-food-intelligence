# Glossary (TR / EN / DE)

One term per concept, used the same way in the Android app, the React dashboard, the API
error messages and the docs.

| English | Türkçe | Deutsch |
|---|---|---|
| lot (batch) | Lot | Charge |
| recall | Geri çağırma | Rückruf |
| recalled | Geri çağrıldı | Zurückgerufen |
| sale blocked | Satış engellendi | Verkauf gesperrt |
| unit cost (EKP) | Birim maliyet (EKP) | Einstandspreis (EKP) |
| sale price (VKP) | Satış fiyatı (VKP) | Verkaufspreis (VKP) |
| gross profit | Brüt kâr | Rohertrag |
| margin | Marj | Marge |
| production order | Üretim emri | Fertigungsauftrag |
| butchery (carcass cutting) | Parçalama | Zerlegung |
| waste | Fire | Ausschuss |
| by-product | Yan ürün | Nebenprodukt |
| supplier | Tedarikçi | Lieferant |
| shipment | Sevkiyat | Lieferung |
| expiry date | Son tüketim tarihi (SKT) | Mindesthaltbarkeitsdatum (MHD) |
| expired (past expiry date) | SKT geçti | Abgelaufen / MHD überschritten |
| stock movement | Stok hareketi | Lagerbewegung |
| sync | Senkronizasyon (tab: "Senkron") | Synchronisierung (tab: "Sync") |
| goods receipt | Mal kabulü | Wareneingang |
| supervisor | Yetkili | Aufsicht |
| source lot (root of a recall) | Kaynak lot | Ursprungscharge |
| baseline (simple reference method) | Referans | Referenz |

## Formatting

| | Türkçe | Deutsch | English |
|---|---|---|---|
| money | 18,16 € | 18,16 € | €18.16 |
| weight | 25,00 kg | 25,00 kg | 25.00 kg |
| date | 27.10.2026 | 27.10.2026 | 27 Oct 2026 |
| packs | 3 paket | 3 Packungen (1 Packung) | 3 packs (1 pack) |

## Rules

- Sentence case everywhere; no ALL CAPS headings.
- Turkish characters are required (ç ğ ı İ ö ş ü); case conversion always uses the locale
  (`Locale("tr")`), never the default `uppercase()`/`lowercase()`.
- Plurals use `<plurals>` (EN/DE); Turkish uses one form ("3 paket", never "3 paketler").
- Placeholders are positional (`%1$s`); no string concatenation for sentences.
- Error and empty states say what happened and what to do next.
- Tab labels are short ("Senkron", "Sync") because the screen title carries the full term.
