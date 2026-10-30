# KI veri sözleşmesi (Ali · Berke · Nuri)

Her üye kendi sentetik `train.csv`'sini kendi Python script'iyle üretir. Stacking'in çalışması için
üç şeyde anlaşmamız yeterli: **kolonlar**, **bölme kuralı** ve **OOF dosya biçimi**.
Referans uygulama: [`ml-engine/ml_contract.py`](ml_contract.py).

## 1. Kolonlar

### 4A — Üretim verimi / EKP (`train_task4a_<üye>.csv`, bir satır = bir üretim emri)

| Kolon | Tip | Açıklama |
|---|---|---|
| `row_id` | str | Benzersiz, üye önekli: `ALI-P-000001`, `BERKE-P-…`, `NURI-P-…` |
| `source` | str | `ali` / `berke` / `nuri` / `db` |
| `order_date` | YYYY-MM-DD | Üretim emri tarihi |
| `product_sku` | kategori | `MM-SUCUK-1KG`, `MM-SUCUK-500G` |
| `pack_size_kg` | float | 1.0 / 0.5 |
| `subcontractor_code` | kategori | `FAS-01..FAS-03` |
| `meat_supplier_code` | kategori | `TED-ET-01..03` |
| `meat_kg`, `spice_kg`, `casing_kg` | float | Tüketilen hammadde (şartname: 100 / 2 / 5) |
| `meat_fat_pct` | float, boş olabilir | Laboratuvar yağ oranı |
| `meat_age_days` | int | Et girişinden üretime gün |
| `drying_days` | int | Planlanan kurutma |
| `meat_unit_price`, `spice_unit_price`, `casing_unit_price`, `service_unit_price` | float € | Şartname: 9,70 / 24,00 / 11,60 / 5,50 |
| `planned_qty` | float | Planlanan paket |
| **`yield_pct`** | float | **Birincil hedef**: `actual_qty × pack_size_kg / meat_kg × 100` |
| `actual_qty`, `ekp` | float | Türetilmiş hedefler (yield'den deterministik) |

EKP öğrenilmez, hesaplanır: `EKP = (Σ hammadde + miktar × hizmet) / miktar` → şartname örneği 18,16 €.

### 4B — Parçalama verimi (`train_task4b_<üye>.csv`, bir satır = bir karkas)

| Kolon | Tip | Açıklama |
|---|---|---|
| `row_id`, `source` | | `ALI-B-000001` … |
| `op_date` | YYYY-MM-DD | |
| `species` | kategori | `KUZU`, `OGLAK`, `KOYUN` |
| `breed` | kategori | `KIVIRCIK`, `AKKARAMAN`, `MERINOS`, `KARAYAKA`, `KIL_KECISI`, `SAANEN` |
| `supplier_code`, `operator_code`, `sex` | kategori | `TED-HAY-0x`, `OP-1..4`, `E/D` |
| `carcass_weight_kg`, `age_months`, `fat_score` (1-5), `conformation_score` (1-5), `chill_hours`, `purchase_unit_price` | sayı | |
| **`but_pct`, `sirt_pct`, `omuz_pct`, `kaburga_pct`, `kiyma_pct`, `fire_pct`** | float | **Hedefler**, toplamı 100 |
| `*_kg` | float | Aynı payların kg karşılığı, toplamı = `carcass_weight_kg` |

Aynı kolonlar MySQL'de `v_ml_production_features` ve `v_ml_butchery_features` görünümlerinden de
gelir; gerçek kayıt biriktikçe model sentetik veri yerine doğrudan veritabanından eğitilebilir.

## 2. Bölme kuralı (koordinasyonsuz, deterministik)

1. Üç üyenin CSV'leri birleştirilir (`source` kolonu korunur).
2. **Holdout**: tarihe göre son %20 → `split = test`. Ne base modeller ne meta-learner bu satırlarla eğitilir.
3. **Fold**: `fold = int(sha1(row_id).hexdigest()[:8], 16) % 5` (yalnız `train` satırları; test için -1).
   Kontrol değerleri: `ALI-P-000001 → 1`, `BERKE-P-000001 → 2`, `NURI-B-000042 → 2`.

## 3. OOF dosya biçimi (Nuri'nin meta-learner'ına girdi)

`oof_<üye>_<görev>.csv`, uzun format:

| row_id | split | fold | task | target | y_true | model | y_pred |
|---|---|---|---|---|---|---|---|
| ALI-P-000123 | train | 3 | production | yield_pct | 84.2 | ali_catboost | 84.61 |

* `split=train`: tahmin, o satırın fold'u **dışarıda** bırakılarak eğitilmiş modelden (gerçek OOF).
* `split=test`: tüm train ile eğitilmiş modelden.
* Meta-learner: `train` satırlarında `pivot(row_id+target → model kolonları)` üzerinde eğitilir,
  `test` satırlarında raporlanır. Parçalama için meta-learner çıktısı da toplam 100'e normalize edilmeli.

## 4. Akış

```bash
python ml-engine/generator_ali.py      # data/train_task4a_ali.csv, data/train_task4b_ali.csv
python ml-engine/train_model_ali.py    # varsayılan: yukarıdaki iki dosya; --production / --butchery birden çok CSV alır
# -> ml-engine/artifacts/ali/oof_ali_production.csv, oof_ali_butchery.csv
```

Uygulamada yalnız Ali'nin verisi üretildi; Berke'nin XGBoost üyesi yapılmadı. Nuri'nin LightGBM'i
(`train_model_nuri.py`) aynı CSV'ler ve aynı `fold_of` ile OOF üretir; `stacking_meta_learner.py`
bunları `row_id + target` üzerinden birleştirir. Stacking denetlendi ve en iyi tek modele göre kazanç
getirmediği için sunulmuyor ([docs/AI.md](../docs/AI.md#why-not-stacking)).
