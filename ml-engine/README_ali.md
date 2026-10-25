# Ali — KI: üretim verimi/EKP (4A) ve parçalama verimi (4B)

```bash
pip install -r ml-engine/requirements.txt
python ml-engine/generator_ali.py         # data/train_task4a_ali.csv (1500), data/train_task4b_ali.csv (2500)
python ml-engine/train_model_ali.py       # artifacts/ali/metrics.json, oof_ali_*.csv, *_model.joblib (~1 dk)
python ml-engine/predict_ali.py production ml-engine/data/seed_cases_production.csv
python ml-engine/predict_ali.py butchery   ml-engine/data/seed_cases_butchery.csv --emit-sql /tmp/pred.sql
pytest ml-engine/tests -q
```

## Veri (sentetik — sınırlılık açıkça)

Yarışmada gerçek veri verilmediği için Ocak 2024 – Eylül 2026 arası geçmiş, şartname rakamlarına
sabitlenmiş bir süreç modeliyle üretildi:

* **4A**: standart koşulda 100 kg et + 2 kg baharat + 5 kg bağırsak → 85 × 1 kg (%85 verim); fiyatlar
  Ekim 2026'da 9,70 / 24,00 / 11,60 / 5,50 €, geçmişe doğru yıllık ~%6 enflasyon. Verimi etkileyenler:
  et yağ oranı, kurutma süresi, etin bekleme günü, fason firma, tedarikçi, yaz ayları, fason×yaz
  etkileşimi, parti büyüklüğüne bağlı sabit dolum kaybı; %2 arıza (anomali) satırı, %4 eksik lab sonucu.
* **4B**: tür (kuzu/oğlak/koyun) bazında reçete payları (kuzu %26/14/20/12/16/12 → 25 kg'da 22 kg parça + 3 kg
  fire, şartname görseliyle aynı); ırk, yağ ve karkas skoru, ağırlık, kasap becerisi, soğutma süresi etkileri.
  Fire = ağırlık − parçalar, yani kütle dengesi her satırda tam.

Sonuçlar bu sentetik sürecin ne kadar öğrenilebildiğini ölçer; gerçek işletme performansını kanıtlamaz.
Gerçek kayıt biriktikçe aynı kolonlar `v_ml_production_features` / `v_ml_butchery_features`
görünümlerinden (database-mysql/schema.sql) gelir ve model değiştirilmeden yeniden eğitilir.

## Yöntem

* **Hedef seçimi**: 4A'da EKP doğrudan öğrenilmez. Verim (`yield_pct`) tahmin edilir; paket sayısı ve EKP
  şartname formülüyle hesaplanır, böylece maliyet tarafı hep kesin muhasebeyle tutarlıdır.
  4B'de 6 pay ayrı tahmin edilip toplam 100'e normalize edilir → tahmin edilen parçalar + fire = giriş ağırlığı.
* **Modeller**: CatBoost (takım planında Ali'nin modeli; kategorikleri doğal işler, 4B'de 6 payı tek
  MultiRMSE modelinde birlikte öğrenir) ve Ridge (one-hot + ölçekleme). Şampiyon **test'e bakılmadan**
  5 katlı CV hatasıyla seçilir; iki modelin OOF'u da stacking'e gider (Berke XGBoost, Nuri LightGBM).
* **Zaman bazlı testin yakaladığı hata**: Fiyat kolonları verimi fiziksel olarak etkilemez ama enflasyonla
  zamana bağlı artar. İlk denemede CatBoost bunları "zaman vekili" olarak kullandı. Rastgele CV bunu
  göstermedi (0,96), zaman bazlı test gösterdi (test verim MAE 1,45). Fiyatlar verim modelinden çıkarılınca
  hata 0,99'a düştü. Fiyatlar yalnız EKP formülünde kullanılır.
* **Referanslar**: (1) reçete standardı (%85; kuzu reçete payları), (2) geçmiş ortalaması (ürün/tür bazında).
* **Değerlendirme**: zaman bazlı holdout (son %20, 302 emir / 500 karkas); train içinde takımla ortak
  deterministik fold'lar (bkz. [DATA_CONTRACT](DATA_CONTRACT.md)).
* **Belirsizlik**: OOF artıklarından split-conformal %90 aralık. Gerçek değer aralık dışındaysa satır
  **anomali** olarak işaretlenir (isteğe bağlı KI özelliği: düşük verim / beklenmedik fire uyarısı).

## Sonuçlar (zaman bazlı test kümesi)

**4A — üretim** (verim → paket → EKP)

| Model | Paket MAE | Paket MAPE | EKP MAE | Verim R² |
|---|---|---|---|---|
| Reçete standardı (%85) | 3,26 | %2,85 | 0,320 € | −0,57 |
| Geçmiş ortalaması | 2,69 | %2,24 | 0,259 € | 0,00 |
| **Ridge (şampiyon)** | **1,37** | **%1,18** | **0,138 €** | **0,67** |
| CatBoost | 1,40 | %1,21 | 0,139 € | 0,66 |

Geçmiş ortalamasına göre paket hatasında **%49,1** iyileşme. %90 aralık ±1,74 verim puanı, testte gerçek
kapsama %89,1.

**4B — parçalama** (6 çıktı, kg)

| Model | Parça kg MAE | Pay MAE (puan) | Satılabilir toplam kg MAE |
|---|---|---|---|
| Reçete standardı | 0,185 | 0,86 | 0,256 |
| Geçmiş ortalaması | 0,184 | 0,86 | 0,251 |
| **Ridge (şampiyon)** | **0,115** | **0,54** | **0,113** |
| CatBoost (MultiRMSE) | 0,117 | 0,55 | 0,116 |

Geçmiş ortalamasına göre **%37,5** iyileşme; tahminlerde kütle dengesi hatası < 1e-12 kg; %90 aralığın
parça başına test kapsaması %89,8.

Dürüst not: Ridge, CatBoost'u az farkla geçiyor; çünkü sentetik süreç büyük ölçüde doğrusal. Bu yüzden iki
model de OOF olarak stacking'e verilir, ağırlıklandırma meta-learner'a bırakılır.

## Örnek tahminler (seed kayıtları, `v_ml_prediction_deviation`)

| Kayıt | Tahmin | %90 aralık | Gerçek | Sapma |
|---|---|---|---|---|
| URT-2026-045 paket | 84,6 | 82,9 – 86,4 | 85 | +0,4 |
| URT-2026-045 EKP | 18,22 € | | 18,16 € | −0,06 € |
| URT-2026-046 paket (yağsız et, 6 gün kurutma) | 82,8 | 81,1 – 84,6 | 83 | +0,2 |
| PRC-2026-001 kuzu fire | 2,61 kg | 2,33 – 2,88 | 3,00 kg | +0,39 → **anomali** |
| PRC-2026-001 kuzu but | 6,79 kg | 6,50 – 7,09 | 6,50 kg | −0,29 → **anomali** |

Kuzu örneğinde model, Kıvırcık ırkı ve iyi kasap için beklenenden ~0,3 kg fazla fire ve ~0,3 kg az but
görüyor; panel bunu "but fireye gitmiş olabilir" uyarısı olarak gösterebilir.
