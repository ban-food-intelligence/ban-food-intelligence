"""Ali - Görev 4A (üretim verimi / EKP) ve 4B (parçalama verimi) modelleri.

Değerlendirme dürüstlüğü için:
  * Zaman bazlı holdout: tarihçenin son %20'si test; model ve meta-learner görmez.
  * Train içinde 5 katlı CV, fold'lar row_id hash'inden (takımla aynı) -> OOF.
  * Her model iki basit referansla kıyaslanır: reçete standardı ve geçmiş ortalaması.
  * EKP doğrudan öğrenilmez: verim tahmin edilir, EKP şartname formülüyle hesaplanır.
  * Parçalama: 6 pay ayrı tahmin edilir, toplam 100'e normalize edilir -> kütle dengesi.
  * Belirsizlik: zaman sıralı split-conformal; train döneminin ilk %75'iyle eğitilen model son %25'te
    kalibre edilir (gelecek hatası gibi). %80 aralık gösterilir, %98 bant anomali eşiğidir.
    Sağlam değerlendirme (kayan başlangıçlı CV, güven aralıkları, testler): evaluate_ali.py.

Modeller: CatBoost (takım planında Ali'nin modeli; kategorik değişkenleri doğal işler)
ve Ridge (doğrusal referans). İkisinin OOF tahmini de stacking'e verilir.

Kullanım:
  python ml-engine/train_model_ali.py                     # data/train_task4a_ali.csv + train_task4b_ali.csv
  python ml-engine/train_model_ali.py --production a.csv b.csv --butchery c.csv d.csv   # takım birleşimi
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

import joblib
import numpy as np
import pandas as pd
from catboost import CatBoostRegressor
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import ml_contract as C  # noqa: E402

MODEL_VERSION = "ali-1.1.0"
RECIPE_YIELD_PCT = 85.0                                         # şartname: 100 kg et -> 85 kg sucuk
RECIPE_BUTCHERY_PCT = {"KUZU": [26, 14, 20, 12, 16, 12], "OGLAK": [25, 13, 21, 11, 14, 16],
                       "KOYUN": [24, 13, 20, 13, 19, 11]}

CB_COMMON = dict(random_seed=42, verbose=0, allow_writing_files=False)
# 5-kat CV ile seçildi (verim puanı MAE): depth 3 / 1500 iter 0,964 < depth 4 0,971 < depth 6 0,992
PROD_CB_PARAMS = dict(iterations=1500, depth=3, learning_rate=0.04, l2_leaf_reg=5, **CB_COMMON)
# 4B: 6 payı tek modelde birlikte öğrenir (MultiRMSE); depth 4: 0,557 < depth 6: 0,570 pay puanı MAE
BUTCH_CB_PARAMS = dict(iterations=1000, depth=4, learning_rate=0.05, loss_function="MultiRMSE", **CB_COMMON)


# ---------------------------------------------------------------- özellikler
# Fiyatlar verimi fiziksel olarak etkilemez ama enflasyonla zamana bağlı artar. Zaman bazlı testte
# ağaç modeller onları "zaman vekili" olarak kullanıp eğitim aralığı dışına taşınca bozuldu
# (CatBoost test verim MAE 1,45 -> fiyatsız 0,99). Bu yüzden verim modellerine girmezler;
# EKP hesabında ise formülde kullanılırlar.
PRICE_COLS = ["meat_unit_price", "spice_unit_price", "casing_unit_price", "service_unit_price",
              "purchase_unit_price"]


def production_features(df: pd.DataFrame) -> pd.DataFrame:
    cols = C.PRODUCTION["categorical"] + [c for c in C.PRODUCTION["numeric"] if c not in PRICE_COLS]
    X = df[cols].copy()
    d = pd.to_datetime(df[C.PRODUCTION["date"]])
    X["month_sin"] = np.sin(2 * np.pi * d.dt.month / 12)
    X["month_cos"] = np.cos(2 * np.pi * d.dt.month / 12)
    X["is_summer"] = d.dt.month.isin([6, 7, 8]).astype(int)
    X["inv_meat_kg"] = 1 / df.meat_kg                            # sabit dolum kaybı fiziği
    X["spice_ratio"] = df.spice_kg / df.meat_kg
    X["casing_ratio"] = df.casing_kg / df.meat_kg
    return X


SPECIES_REF_WEIGHT_KG = {"KUZU": 22.0, "OGLAK": 14.0, "KOYUN": 30.0}   # tipik karkas ağırlığı


def butchery_features(df: pd.DataFrame) -> pd.DataFrame:
    cols = C.BUTCHERY["categorical"] + [c for c in C.BUTCHERY["numeric"] if c not in PRICE_COLS]
    X = df[cols].copy()
    d = pd.to_datetime(df[C.BUTCHERY["date"]])
    X["month_sin"] = np.sin(2 * np.pi * d.dt.month / 12)
    X["month_cos"] = np.cos(2 * np.pi * d.dt.month / 12)
    X["weight_vs_species"] = X["carcass_weight_kg"] - X["species"].map(SPECIES_REF_WEIGHT_KG).fillna(X["carcass_weight_kg"])
    X["warm_cut"] = (X["chill_hours"] < 24).astype(int)
    return X


class CBRegressor:
    """CatBoost; kategorik kolonlar string olarak verilir (eksik -> 'NA')."""

    def __init__(self, cats, params):
        self.cats, self.params, self.model = cats, params, None

    def _prep(self, X):
        X = X.copy()
        for c in self.cats:
            X[c] = X[c].fillna("NA").astype(str)
        return X

    def fit(self, X, y):
        self.model = CatBoostRegressor(**self.params, cat_features=self.cats).fit(self._prep(X), y)
        return self

    def predict(self, X):
        return self.model.predict(self._prep(X))


def ridge(cats, nums):
    pre = ColumnTransformer([
        ("cat", OneHotEncoder(handle_unknown="ignore"), cats),
        ("num", make_pipeline(SimpleImputer(strategy="median"), StandardScaler()), nums),
    ])
    return make_pipeline(pre, Ridge(alpha=1.0))


def metrics(y, p) -> dict:
    y, p = np.asarray(y, float), np.asarray(p, float)
    return {"MAE": float(mean_absolute_error(y, p)), "RMSE": float(np.sqrt(mean_squared_error(y, p))),
            "MAPE_pct": float(100 * np.mean(np.abs((y - p) / y))), "R2": float(r2_score(y, p))}


INTERVAL_LEVELS = (0.80, 0.90, 0.98)       # gösterilen / eski uyumlu / anomali bandı
CAL_FRACTION = 0.25


def conformal_q(scores, level: float) -> float:
    """Sonlu örnek split-conformal kantili: skorların ceil((n+1)·level)'inci küçüğü."""
    s = np.sort(np.asarray(scores, float))
    k = int(np.ceil((len(s) + 1) * level))
    return float(s[min(k, len(s)) - 1])


def time_conformal(make_model, X, y, tr: np.ndarray) -> dict:
    """Tarih sıralı train satırlarının son CAL_FRACTION'ı kalibrasyon: ilk kısımla eğitilen modelin
    mutlak artıkları -> seviye başına yarı genişlik (çok hedefte hedef başına)."""
    y = np.asarray(y)
    idx = np.where(tr)[0]
    cut = int(len(idx) * (1 - CAL_FRACTION))
    proper, cal = idx[:cut], idx[cut:]
    res = np.abs(y[cal] - make_model().fit(X.iloc[proper], y[proper]).predict(X.iloc[cal]))
    if res.ndim == 1:
        return {lv: conformal_q(res, lv) for lv in INTERVAL_LEVELS}
    return {lv: np.array([conformal_q(res[:, j], lv) for j in range(res.shape[1])]) for lv in INTERVAL_LEVELS}


def oof_and_test(make_model, X, y, df):
    """5-kat OOF (train) + tüm train ile eğitilmiş modelin test tahmini."""
    tr = (df.split == "train").to_numpy()
    oof = np.full((len(df),) + np.shape(y)[1:], np.nan)
    for k in range(C.N_FOLDS):
        fit_idx = tr & (df.fold != k).to_numpy()
        val_idx = tr & (df.fold == k).to_numpy()
        oof[val_idx] = make_model().fit(X[fit_idx], y[fit_idx]).predict(X[val_idx])
    final = make_model().fit(X[tr], y[tr])
    pred = oof.copy()
    pred[~tr] = final.predict(X[~tr])
    return pred, final


# ---------------------------------------------------------------- Görev 4A
def train_production(df: pd.DataFrame, out: pathlib.Path) -> tuple[dict, pd.DataFrame]:
    df = C.assign_splits(df.sort_values(C.PRODUCTION["date"]).reset_index(drop=True), C.PRODUCTION["date"])
    X = production_features(df)
    y = df[C.PRODUCTION["target"]].to_numpy(float)
    cats = C.PRODUCTION["categorical"]
    nums = [c for c in X.columns if c not in cats]
    tr, te = (df.split == "train").to_numpy(), (df.split == "test").to_numpy()

    preds = {
        "baseline_recipe": np.full(len(df), RECIPE_YIELD_PCT),
        "baseline_hist_mean": df.groupby("product_sku")[C.PRODUCTION["target"]]
                                .transform(lambda s: s[tr[s.index]].mean()).to_numpy(),
    }
    makers = {"ali_ridge": lambda: ridge(cats, nums), "ali_catboost": lambda: CBRegressor(cats, PROD_CB_PARAMS)}
    preds["ali_ridge"], ridge_model = oof_and_test(makers["ali_ridge"], X, y, df)
    preds["ali_catboost"], cb_model = oof_and_test(makers["ali_catboost"], X, y, df)
    fitted = {"ali_ridge": ridge_model, "ali_catboost": cb_model}

    report = {"n_train": int(tr.sum()), "n_test": int(te.sum()), "target": "yield_pct", "models": {}}
    for name, p in preds.items():
        qty = C.production_qty_from_yield(df, p)
        ekp = C.production_ekp(df, qty)
        report["models"][name] = {
            "cv_oof_yield_pct": metrics(y[tr], p[tr]),
            "test_yield_pct": metrics(y[te], p[te]),
            "test_actual_qty": metrics(df.actual_qty[te], qty[te]),
            "test_ekp_eur": metrics(df.ekp[te], ekp[te]),
        }
    # şampiyon test'e bakılmadan, yalnız CV OOF hatasıyla seçilir
    champ = min(fitted, key=lambda m: report["models"][m]["cv_oof_yield_pct"]["MAE"])
    report["champion"] = champ
    # zaman sıralı split-conformal aralıklar (verim puanı); kapsama zaman bazlı testte ölçülür
    q = time_conformal(makers[champ], X, y, tr)
    abs_te = np.abs(y[te] - preds[champ][te])
    report["intervals_yield_pct"] = {f"{round(lv * 100)}": {"half_width": q[lv],
                                                            "test_coverage": float(np.mean(abs_te <= q[lv]))}
                                     for lv in INTERVAL_LEVELS}
    report["interval_calibration"] = ("time-ordered split conformal (fit on first 75% of the train period, "
                                      "calibrate on the last 25%)")
    q90 = q[0.90]
    report["interval90_yield_pct"] = report["intervals_yield_pct"]["90"]
    base = report["models"]["baseline_hist_mean"]["test_actual_qty"]["MAE"]
    best = report["models"][champ]["test_actual_qty"]["MAE"]
    report["improvement_vs_hist_mean_pct"] = 100 * (1 - best / base)
    imp = cb_model.model.get_feature_importance()
    report["catboost_feature_importance"] = dict(sorted(zip(X.columns, map(float, np.round(imp, 2))),
                                                        key=lambda kv: -kv[1]))

    joblib.dump({"models": fitted, "champion": champ, "q80": q[0.80], "q90": q90, "q98": q[0.98],
                 "version": MODEL_VERSION}, out / "production_model.joblib")

    oof = pd.concat([pd.DataFrame({"row_id": df.row_id, "split": df.split, "fold": df.fold, "task": "production",
                                   "target": "yield_pct", "y_true": y, "model": m, "y_pred": np.round(preds[m], 4)})
                     for m in ("ali_catboost", "ali_ridge")])
    return report, oof


# ---------------------------------------------------------------- Görev 4B
def normalize_pct(P: np.ndarray) -> np.ndarray:
    P = np.clip(P, 0.1, None)
    return 100 * P / P.sum(axis=1, keepdims=True)


# Bileşimsel (ALR): log(pay_j / fire) öğrenilir, softmax ile geri dönülür -> paylar her zaman pozitif, toplam tam 100.
def alr(P: np.ndarray) -> np.ndarray:
    P = np.clip(np.asarray(P, float), 1e-3, None)
    return np.log(P[:, :-1] / P[:, -1:])


def alr_inverse(Z: np.ndarray) -> np.ndarray:
    Z = np.column_stack([np.atleast_2d(Z), np.zeros(len(np.atleast_2d(Z)))])
    E = np.exp(Z - Z.max(axis=1, keepdims=True))
    return 100 * E / E.sum(axis=1, keepdims=True)


class _PerShare:
    """Kalibrasyon için: pay başına bir model + simplex izdüşümü (eğitimdeki ali_ridge ile aynı)."""

    def __init__(self, make):
        self.make = make

    def fit(self, X, Y):
        self.models = [self.make().fit(X, Y[:, j]) for j in range(Y.shape[1])]
        return self

    def predict(self, X):
        return normalize_pct(np.column_stack([m.predict(X) for m in self.models]))


class _Projected:
    def __init__(self, model):
        self.model = model

    def fit(self, X, Y):
        self.model.fit(X, Y)
        return self

    def predict(self, X):
        return normalize_pct(np.atleast_2d(self.model.predict(X)))


def train_butchery(df: pd.DataFrame, out: pathlib.Path) -> tuple[dict, pd.DataFrame]:
    df = C.assign_splits(df.sort_values(C.BUTCHERY["date"]).reset_index(drop=True), C.BUTCHERY["date"])
    X = butchery_features(df)
    cats = C.BUTCHERY["categorical"]
    nums = [c for c in X.columns if c not in cats]
    targets = C.BUTCHERY["targets"]
    Y = df[targets].to_numpy(float)
    W = df.carcass_weight_kg.to_numpy(float)[:, None]
    tr, te = (df.split == "train").to_numpy(), (df.split == "test").to_numpy()

    hist = df[tr].groupby("species")[targets].mean()
    preds = {
        "baseline_recipe": np.vstack([RECIPE_BUTCHERY_PCT[s] for s in df.species]).astype(float),
        "baseline_hist_mean": hist.loc[df.species].to_numpy(),
    }
    models = {"ali_ridge": []}
    makers = {"ali_ridge": lambda: _PerShare(lambda: ridge(cats, nums)),
              "ali_catboost": lambda: _Projected(CBRegressor(cats, BUTCH_CB_PARAMS))}
    cols = []
    for j in range(len(targets)):                       # Ridge: her pay için ayrı model
        p, m = oof_and_test(lambda: ridge(cats, nums), X, Y[:, j], df)
        cols.append(p)
        models["ali_ridge"].append(m)
    preds["ali_ridge"] = normalize_pct(np.column_stack(cols))
    p, cb = oof_and_test(lambda: CBRegressor(cats, BUTCH_CB_PARAMS), X, Y, df)   # CatBoost: 6 pay birlikte
    models["ali_catboost"] = cb
    preds["ali_catboost"] = normalize_pct(p)

    report = {"n_train": int(tr.sum()), "n_test": int(te.sum()), "targets": targets, "models": {}}
    for name, P in preds.items():
        kg_true, kg_pred = Y * W / 100, P * W / 100
        report["models"][name] = {
            "cv_oof_pct_MAE": float(np.mean(np.abs(Y[tr] - P[tr]))),
            "test_pct_MAE": float(np.mean(np.abs(Y[te] - P[te]))),
            "test_kg_MAE": float(np.mean(np.abs(kg_true[te] - kg_pred[te]))),
            "test_kg_MAE_per_part": {t.replace("_pct", ""): float(np.mean(np.abs(kg_true[te, j] - kg_pred[te, j])))
                                     for j, t in enumerate(targets)},
            "test_sellable_kg_MAE": float(np.mean(np.abs(kg_true[te, :5].sum(1) - kg_pred[te, :5].sum(1)))),
            "mass_balance_max_err_kg": float(np.max(np.abs(kg_pred.sum(1) - W[:, 0]))),
        }
    champ = min(models, key=lambda m: report["models"][m]["cv_oof_pct_MAE"])
    report["champion"] = champ
    q = time_conformal(makers[champ], X, Y, tr)
    abs_te = np.abs(Y[te] - preds[champ][te])
    report["intervals_pct"] = {f"{round(lv * 100)}": {"half_width": dict(zip(targets, map(float, q[lv]))),
                                                      "test_coverage": float(np.mean(abs_te <= q[lv])),
                                                      "test_coverage_joint_6": float(np.mean((abs_te <= q[lv]).all(1)))}
                               for lv in INTERVAL_LEVELS}
    report["interval_calibration"] = ("time-ordered split conformal per share (fit on first 75% of the train "
                                      "period, calibrate on the last 25%)")
    q90 = q[0.90]
    report["interval90_pct"] = {t: float(v) for t, v in zip(targets, q90)}
    report["interval90_test_coverage"] = report["intervals_pct"]["90"]["test_coverage"]
    base = report["models"]["baseline_hist_mean"]["test_kg_MAE"]
    report["improvement_vs_hist_mean_pct"] = 100 * (1 - report["models"][champ]["test_kg_MAE"] / base)

    joblib.dump({"models": models, "champion": champ, "targets": targets, "q80": q[0.80], "q90": q90,
                 "q98": q[0.98], "version": MODEL_VERSION}, out / "butchery_model.joblib")

    frames = []
    for m in ("ali_catboost", "ali_ridge"):
        for j, t in enumerate(targets):
            frames.append(pd.DataFrame({"row_id": df.row_id, "split": df.split, "fold": df.fold, "task": "butchery",
                                        "target": t, "y_true": Y[:, j], "model": m,
                                        "y_pred": np.round(preds[m][:, j], 4)}))
    return report, pd.concat(frames)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--production", nargs="+", default=[str(HERE / "data" / "train_task4a_ali.csv")])
    ap.add_argument("--butchery", nargs="+", default=[str(HERE / "data" / "train_task4b_ali.csv")])
    ap.add_argument("--out", default=str(HERE / "artifacts" / "ali"))
    args = ap.parse_args()
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    prod = pd.concat([pd.read_csv(f) for f in args.production], ignore_index=True)
    butch = pd.concat([pd.read_csv(f) for f in args.butchery], ignore_index=True)
    rep_p, oof_p = train_production(prod, out)
    rep_b, oof_b = train_butchery(butch, out)

    oof_p.to_csv(out / "oof_ali_production.csv", index=False)
    oof_b.to_csv(out / "oof_ali_butchery.csv", index=False)
    (out / "metrics.json").write_text(json.dumps({"version": MODEL_VERSION, "production": rep_p,
                                                  "butchery": rep_b}, indent=2, ensure_ascii=False))

    print("== 4A Üretim verimi (test, zaman bazlı holdout) ==")
    for m, r in rep_p["models"].items():
        print(f"  {m:20s} paket MAE {r['test_actual_qty']['MAE']:6.2f}  MAPE %{r['test_actual_qty']['MAPE_pct']:5.2f}"
              f"  | EKP MAE {r['test_ekp_eur']['MAE']:.3f} €  | verim R2 {r['test_yield_pct']['R2']:.3f}")
    print(f"  Şampiyon (CV ile seçildi): {rep_p['champion']}; geçmiş ortalamaya göre paket hatasında"
          f" %{rep_p['improvement_vs_hist_mean_pct']:.1f} iyileşme;"
          f" %80 aralık ±{rep_p['intervals_yield_pct']['80']['half_width']:.2f} puan,"
          f" test kapsaması %{100 * rep_p['intervals_yield_pct']['80']['test_coverage']:.1f}")
    print("== 4B Parçalama verimi (test) ==")
    for m, r in rep_b["models"].items():
        print(f"  {m:20s} parça kg MAE {r['test_kg_MAE']:.3f}  pay MAE {r['test_pct_MAE']:.3f} puan"
              f"  | satılabilir kg MAE {r['test_sellable_kg_MAE']:.3f}")
    print(f"  Şampiyon (CV ile seçildi): {rep_b['champion']}; geçmiş ortalamaya göre kg hatasında"
          f" %{rep_b['improvement_vs_hist_mean_pct']:.1f} iyileşme,"
          f" %80 aralık test kapsaması %{100 * rep_b['intervals_pct']['80']['test_coverage']:.1f} (parça başına)")


if __name__ == "__main__":
    # modül adıyla çalıştır ki pickle'lanan sınıflar `train_model_ali` altında kalsın (predict_ali.py yükleyebilsin)
    import train_model_ali as _module
    _module.main()
