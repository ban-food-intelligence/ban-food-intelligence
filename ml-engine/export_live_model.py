"""
Exports the production yield champion (ali_ridge) as plain numbers, so the Go API can forecast a
new production order the moment it is recorded (no Python at runtime).

The Ridge pipeline is linear: one-hot categories + median-imputed, standardised numbers -> Ridge.
So yield_pct = intercept + sum(coef[category]) + sum(coef_j * (x_j - mean_j) / scale_j), with
x_j replaced by the training median when missing. The model is fitted exactly like
train_model_ali.train_production (same data, same time split, same rows), and the export is
checked against sklearn's own predictions before it is written.

Writes
  api-go/controllers/aimodel/production_ridge.json         model, 80 % / 98 % conformal half-widths,
                                                           historical mean yield per product (baseline)
  api-go/controllers/aimodel/production_ridge_golden.json  inputs + expected yield for the Go test

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


def main() -> None:
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


if __name__ == "__main__":
    main()
