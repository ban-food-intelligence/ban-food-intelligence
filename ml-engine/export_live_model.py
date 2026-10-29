"""
Exports the two champions (ali_ridge) as plain numbers, so the Go API can forecast a new
production order (study A) or a carcass about to be cut (study B) with no Python at runtime.

A Ridge pipeline is linear: one-hot categories + median-imputed, standardised numbers -> Ridge.
So y = intercept + sum(coef[category]) + sum(coef_j * (x_j - mean_j) / scale_j), with x_j replaced
by the training median when missing. Study B has one such model per share (leg, loin, shoulder,
ribs, mince, waste); the shares are clipped at 0.1 % and rescaled to 100 %. Each model is fitted
exactly like train_model_ali.py (same data, same time split, same rows), and the export is checked
against sklearn's own predictions before it is written.

Writes to api-go/controllers/aimodel/
  production_ridge.json, butchery_ridge.json   models, 80 % / 98 % conformal half-widths, baseline
                                               (historical mean per product / per species)
  *_golden.json                                inputs + expected outputs for the Go tests

Run: python ml-engine/export_live_model.py
"""
from __future__ import annotations

import json
import pathlib
import sys

import numpy as np
import pandas as pd

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import ml_contract as C  # noqa: E402
import train_model_ali as T  # noqa: E402

OUT = HERE.parent / "api-go" / "controllers" / "aimodel"
RAW_INPUTS = ["order_date", "product_sku", "subcontractor_code", "meat_supplier_code", "pack_size_kg",
              "meat_kg", "spice_kg", "casing_kg", "meat_fat_pct", "meat_age_days", "drying_days", "planned_qty"]


def linear_form(pipe, cats: list[str], nums: list[str]) -> dict:
    ct = pipe.named_steps["columntransformer"]
    rg = pipe.named_steps["ridge"]
    ohe = ct.named_transformers_["cat"]
    imp = ct.named_transformers_["num"].named_steps["simpleimputer"]
    sc = ct.named_transformers_["num"].named_steps["standardscaler"]
    coef, k = rg.coef_, 0
    categorical = []
    for name, values in zip(cats, ohe.categories_):
        categorical.append({"name": name, "coef": {str(v): float(coef[k + i]) for i, v in enumerate(values)}})
        k += len(values)
    numeric = [{"name": name, "median": float(imp.statistics_[j]), "mean": float(sc.mean_[j]),
                "scale": float(sc.scale_[j]), "coef": float(coef[k + j])} for j, name in enumerate(nums)]
    return {"intercept": float(rg.intercept_), "categorical": categorical, "numeric": numeric}


def predict_linear(model: dict, X: pd.DataFrame) -> np.ndarray:
    y = np.full(len(X), model["intercept"])
    for c in model["categorical"]:
        y += X[c["name"]].map(lambda v: c["coef"].get(str(v), 0.0)).to_numpy(float)
    for n in model["numeric"]:
        x = X[n["name"]].astype(float).fillna(n["median"]).to_numpy()
        y += n["coef"] * (x - n["mean"]) / n["scale"]
    return y


def export_production() -> None:
    df = pd.read_csv(HERE / "data" / "train_task4a_ali.csv")
    df = C.assign_splits(df.sort_values(C.PRODUCTION["date"]).reset_index(drop=True), C.PRODUCTION["date"])
    X = T.production_features(df)
    y = df[C.PRODUCTION["target"]].to_numpy(float)
    cats = C.PRODUCTION["categorical"]
    nums = [c for c in X.columns if c not in cats]
    tr = (df.split == "train").to_numpy()

    pipe = T.ridge(cats, nums).fit(X[tr], y[tr])                      # = the served final model
    q = T.time_conformal(lambda: T.ridge(cats, nums), X, y, tr)
    model = linear_form(pipe, cats, nums)

    gap = float(np.max(np.abs(predict_linear(model, X) - pipe.predict(X))))
    assert gap < 1e-9, f"linear export differs from sklearn by {gap}"

    seed = pd.read_csv(HERE / "data" / "seed_cases_production.csv")
    hist = df[tr].groupby("product_sku")[C.PRODUCTION["target"]].mean()
    out = {
        "model": "ali_ridge",
        "version": T.MODEL_VERSION,
        "target": "yield_pct",
        "trained_on": f"data/train_task4a_ali.csv, time split, {int(tr.sum())} training orders (synthetic)",
        "features": {"categorical": cats, "numeric": nums},
        **model,
        "interval_half_width": {"80": float(q[0.80]), "98": float(q[0.98])},
        "baseline_hist_mean_yield_pct": {k: float(v) for k, v in hist.items()},
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "production_ridge.json").write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")

    test_rows = df[~tr].iloc[:: max(1, int((~tr).sum()) // 30)]
    golden_src = pd.concat([seed, test_rows], ignore_index=True)
    golden_pred = pipe.predict(T.production_features(golden_src))
    golden = [{"row_id": r.row_id,
               "inputs": {k: (None if pd.isna(getattr(r, k)) else getattr(r, k)) for k in RAW_INPUTS},
               "yield_pct": float(p)}
              for r, p in zip(golden_src.itertuples(index=False), golden_pred)]
    (OUT / "production_ridge_golden.json").write_text(json.dumps(golden, indent=2, default=str) + "\n", encoding="utf-8")

    seed_pred = golden_pred[: len(seed)]
    print(f"exported {OUT / 'production_ridge.json'} (max gap to sklearn {gap:.1e})")
    print(f"golden rows: {len(golden)}; seed cases yield {np.round(seed_pred, 3).tolist()} "
          f"-> packages {np.round(seed.meat_kg * seed_pred / 100 / seed.pack_size_kg, 1).tolist()}")
    print(f"80 % half-width {q[0.80]:.3f} yield points, 98 % {q[0.98]:.3f}; hist mean {hist.round(3).to_dict()}")


BUTCHERY_INPUTS = ["op_date", "species", "breed", "supplier_code", "operator_code", "sex", "carcass_weight_kg",
                   "age_months", "fat_score", "conformation_score", "chill_hours"]


def export_butchery() -> None:
    df = pd.read_csv(HERE / "data" / "train_task4b_ali.csv")
    df = C.assign_splits(df.sort_values(C.BUTCHERY["date"]).reset_index(drop=True), C.BUTCHERY["date"])
    X = T.butchery_features(df)
    cats = C.BUTCHERY["categorical"]
    nums = [c for c in X.columns if c not in cats]
    targets = C.BUTCHERY["targets"]
    Y = df[targets].to_numpy(float)
    tr = (df.split == "train").to_numpy()

    pipes = [T.ridge(cats, nums).fit(X[tr], Y[tr, j]) for j in range(len(targets))]   # = the served models
    q = T.time_conformal(lambda: T._PerShare(lambda: T.ridge(cats, nums)), X, Y, tr)
    parts = [{"target": t, **linear_form(p, cats, nums)} for t, p in zip(targets, pipes)]

    raw = np.column_stack([predict_linear(m, X) for m in parts])
    gap = float(np.max(np.abs(raw - np.column_stack([p.predict(X) for p in pipes]))))
    assert gap < 1e-9, f"linear export differs from sklearn by {gap}"

    hist = df[tr].groupby("species")[targets].mean()
    out = {
        "model": "ali_ridge",
        "version": T.MODEL_VERSION,
        "targets": targets,
        "trained_on": f"data/train_task4b_ali.csv, time split, {int(tr.sum())} training carcasses (synthetic)",
        "features": {"categorical": cats, "numeric": nums},
        "species_ref_weight_kg": T.SPECIES_REF_WEIGHT_KG,
        "share_floor_pct": 0.1,
        "parts": parts,
        "interval_half_width_pct": {"80": [float(v) for v in q[0.80]], "98": [float(v) for v in q[0.98]]},
        "baseline_hist_mean_pct": {s: [float(v) for v in row] for s, row in hist.iterrows()},
    }
    (OUT / "butchery_ridge.json").write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")

    seed = pd.read_csv(HERE / "data" / "seed_cases_butchery.csv")
    test_rows = df[~tr].iloc[:: max(1, int((~tr).sum()) // 30)]
    # The database has no chill hours yet: cover the missing-value path (median, warm_cut = 0) too.
    missing = seed.assign(row_id="SYN-B-NO-CHILL-NO-FAT", chill_hours=np.nan, fat_score=np.nan)
    golden_src = pd.concat([seed, missing, test_rows], ignore_index=True)
    Xg = T.butchery_features(golden_src)
    shares = T.normalize_pct(np.column_stack([p.predict(Xg) for p in pipes]))
    golden = [{"row_id": r.row_id,
               "inputs": {k: (None if pd.isna(getattr(r, k)) else getattr(r, k)) for k in BUTCHERY_INPUTS},
               "shares_pct": [float(v) for v in s]}
              for r, s in zip(golden_src.itertuples(index=False), shares)]
    (OUT / "butchery_ridge_golden.json").write_text(json.dumps(golden, indent=2, default=str) + "\n", encoding="utf-8")

    seed_kg = shares[0] * seed.carcass_weight_kg.iloc[0] / 100
    print(f"exported {OUT / 'butchery_ridge.json'} (max gap to sklearn {gap:.1e}); golden rows: {len(golden)}")
    print(f"seed carcass {seed.row_id.iloc[0]}: kg {dict(zip(targets, np.round(seed_kg, 3)))}")
    print(f"80 % half-width (share points) {np.round(q[0.80], 3).tolist()}; missing chill_hours in golden: "
          f"{int(golden_src.chill_hours.isna().sum())}, missing fat_score: {int(golden_src.fat_score.isna().sum())}")


if __name__ == "__main__":
    export_production()
    export_butchery()
