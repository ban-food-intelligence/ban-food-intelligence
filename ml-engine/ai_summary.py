"""Preview of the GET /ai/summary payload, including the new OPTIONAL fields.

    python ml-engine/ai_summary.py        # -> artifacts/ali/ai_summary_preview.json

The Go API (api-go/controllers/ai_controller.go) builds /ai/summary from the ml_model_metric and
ml_prediction tables. This module builds the same JSON straight from the ml-engine artifacts so
the new fields can be reviewed and tested before the schema / Go change (see ML_REPORT.md):

* every existing field keeps its name, type and meaning;
* new fields are additive and may be absent (old clients ignore them).

metrics[]      + wmape_pct, mae_ci_low, mae_ci_high (block-bootstrap 95 % CI on the holdout),
                 cv_mae_mean, cv_mae_std, cv_folds (rolling-origin CV, evaluate_ali.py)
comparisons[]  + interval_low, interval_high, interval_level (conformal), anomaly (actual outside the
                 98 % band), outside_interval (actual outside the shown 80 % interval)
"""
from __future__ import annotations

import json
import pathlib
import sys

import joblib
import numpy as np
import pandas as pd

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import evaluate_ali as E  # noqa: E402
import ml_contract as C  # noqa: E402
import predict_ali as P  # noqa: E402

ART = HERE / "artifacts" / "ali"
CHAMPION, BASELINE = "ali_ridge", "baseline_hist_mean"
EVAL_NAME = {"ali_ridge": {"production": "ridge", "butchery": "ridge_project"},
             "baseline_hist_mean": {"production": "baseline_hist_mean", "butchery": "baseline_hist_mean"},
             "baseline_recipe": {"production": "baseline_recipe", "butchery": "baseline_recipe"}}
DATA_NOTE = ("Models are trained on synthetic, anonymised history (ml-engine/generator_ali.py). Metrics are "
             "measured on the latest 20% of dates (time split). Small and synthetic data: indicative only.")

# keys of the existing Go DTOs; tests assert these never disappear or change type
METRIC_KEYS = ["task", "target", "unit", "model", "role", "eval_split", "n_rows", "mae", "mape_pct"]
COMPARISON_KEYS = ["task", "ref_no", "target", "actual", "model_pred", "model_dev", "model_dev_pct",
                   "baseline_pred", "baseline_dev", "baseline_dev_pct", "model_closer_than_baseline"]
SUMMARY_KEYS = ["model", "baseline", "data_note", "metrics", "comparisons"]


def _metric(task, target, unit, model, y, p, n_rows, evaluation, cv_key) -> dict:
    m = E.error_metrics(np.asarray(y, float), np.asarray(p, float), E.block_bootstrap_idx(len(y)))
    row = {"task": task, "target": target, "unit": unit, "model": model,
           "role": "MODEL" if model == CHAMPION else "BASELINE", "eval_split": "TEST_TIME_SPLIT",
           "n_rows": int(n_rows), "mae": round(m["MAE"], 4), "mape_pct": round(m["MAPE_pct"], 4),
           # optional, new
           "wmape_pct": round(m["wMAPE_pct"], 4),
           "mae_ci_low": round(m["MAE_ci95"][0], 4), "mae_ci_high": round(m["MAE_ci95"][1], 4)}
    ev = (evaluation or {}).get(task.lower(), {}).get("models", {}).get(EVAL_NAME[model][task.lower()])
    if ev and cv_key in ev:
        mean, std = ev[cv_key]
        row.update(cv_mae_mean=round(mean, 4), cv_mae_std=round(std, 4), cv_folds=len(ev[cv_key.replace(
            "_fold_mean_std", "_per_fold")]))
    return row


def metric_rows(evaluation: dict | None = None) -> list[dict]:
    rows = []
    prod = pd.read_csv(HERE / "data" / "train_task4a_ali.csv")
    oof = pd.read_csv(ART / "oof_ali_production.csv")
    split = oof.drop_duplicates("row_id").set_index("row_id").split
    prod = prod.assign(split=prod.row_id.map(split))
    te, tr = prod[prod.split == "test"], prod[prod.split == "train"]
    preds = {CHAMPION: oof[(oof.model == CHAMPION)].set_index("row_id").y_pred.loc[te.row_id].to_numpy(),
             "baseline_hist_mean": te.product_sku.map(tr.groupby("product_sku").yield_pct.mean()).to_numpy(),
             "baseline_recipe": np.full(len(te), 85.0)}
    for model, y_hat in preds.items():
        qty = C.production_qty_from_yield(te, y_hat)
        rows.append(_metric("PRODUCTION", "actual_qty", "paket", model, te.actual_qty, qty, len(te), evaluation,
                            "packages_MAE_fold_mean_std"))
        rows.append(_metric("PRODUCTION", "ekp", "EUR", model, te.ekp, C.production_ekp(te, qty), len(te), None, ""))

    butch = pd.read_csv(HERE / "data" / "train_task4b_ali.csv")
    oofb = pd.read_csv(ART / "oof_ali_butchery.csv")
    split = oofb.drop_duplicates("row_id").set_index("row_id").split
    butch = butch.assign(split=butch.row_id.map(split))
    te, tr = butch[butch.split == "test"], butch[butch.split == "train"]
    pct = [f"{p}_pct" for p in C.BUTCHERY_PARTS]
    model_pct = oofb[(oofb.split == "test") & (oofb.model == CHAMPION)].pivot(index="row_id", columns="target",
                                                                               values="y_pred").loc[te.row_id, pct]
    hist = tr.groupby("species")[pct].mean()
    recipe = pd.DataFrame(E.T.RECIPE_BUTCHERY_PCT, index=pct).T
    preds = {CHAMPION: E.T.normalize_pct(model_pct.to_numpy()),
             "baseline_hist_mean": hist.loc[te.species].to_numpy(),
             "baseline_recipe": recipe.loc[te.species].to_numpy()}
    w = te.carcass_weight_kg.to_numpy()[:, None]
    kg_true = te[[f"{p}_kg" for p in C.BUTCHERY_PARTS]].to_numpy()
    for model, share in preds.items():
        kg = share * w / 100
        rows.append(_metric("BUTCHERY", "part_kg", "kg", model, kg_true[:, :5].ravel(), kg[:, :5].ravel(), len(te),
                            None, ""))
        rows.append(_metric("BUTCHERY", "fire_kg", "kg", model, kg_true[:, 5], kg[:, 5], len(te), None, ""))
    return rows


def _dev(pred, actual):
    if pred is None or actual is None:
        return None, None
    actual, pred = float(actual), float(pred)
    d = round(actual - pred, 4)
    return d, (None if actual == 0 else round(100 * (actual - pred) / actual, 2))


def _comparison(task, ref, target, actual, model_pred, baseline_pred, extra) -> dict:
    md, mdp = _dev(model_pred, actual)
    bd, bdp = _dev(baseline_pred, actual)
    row = {"task": task, "ref_no": ref, "target": target, "actual": actual, "model_pred": model_pred,
           "model_dev": md, "model_dev_pct": mdp, "baseline_pred": baseline_pred, "baseline_dev": bd,
           "baseline_dev_pct": bdp,
           "model_closer_than_baseline": None if md is None or bd is None else bool(abs(md) <= abs(bd))}
    row.update({k: v for k, v in extra.items() if v is not None})      # optional, new
    return row


def comparison_rows(prod_bundle=None, butch_bundle=None) -> list[dict]:
    prod_bundle = prod_bundle or joblib.load(ART / "production_model.joblib")
    butch_bundle = butch_bundle or joblib.load(ART / "butchery_model.joblib")
    hist = pd.read_csv(HERE / "data" / "train_task4a_ali.csv")
    hist = C.assign_splits(hist.sort_values("order_date"), "order_date")
    mean_yield = hist[hist.split == "train"].groupby("product_sku").yield_pct.mean()
    out = []
    seed = pd.read_csv(HERE / "data" / "seed_cases_production.csv")
    res = P.predict_production(seed, prod_bundle)
    for (_, s), (_, r) in zip(seed.iterrows(), res.iterrows()):
        ref = s.row_id.removeprefix("DB-P-")
        one = seed[seed.row_id == s.row_id]
        b_qty = float(C.production_qty_from_yield(one, [mean_yield[s.product_sku]])[0])
        b_ekp = float(C.production_ekp(one, [b_qty])[0])
        out.append(_comparison("PRODUCTION", ref, "actual_qty", float(r.actual_qty), float(r.pred_qty), round(b_qty, 2),
                               {"interval_low": float(r.interval_low), "interval_high": float(r.interval_high),
                                "interval_level": r.interval_level, "anomaly": bool(r.anomaly),
                                "outside_interval": bool(r.outside_interval)}))
        out.append(_comparison("PRODUCTION", ref, "ekp", float(r.actual_ekp), float(r.pred_ekp), round(b_ekp, 4),
                               {"interval_low": float(r.ekp_interval_low), "interval_high": float(r.ekp_interval_high),
                                "interval_level": r.interval_level}))
    bh = pd.read_csv(HERE / "data" / "train_task4b_ali.csv")
    bh = C.assign_splits(bh.sort_values("op_date"), "op_date")
    shares = bh[bh.split == "train"].groupby("species")[[f"{p}_pct" for p in C.BUTCHERY_PARTS]].mean()
    seed = pd.read_csv(HERE / "data" / "seed_cases_butchery.csv")
    res = P.predict_butchery(seed, butch_bundle)
    for r in res.itertuples():
        s = seed[seed.row_id == r.row_id].iloc[0]
        b = round(shares.loc[s.species, f"{r.part}_pct"] * s.carcass_weight_kg / 100, 4)
        out.append(_comparison("BUTCHERY", r.row_id.removeprefix("DB-B-"), f"{r.part}_kg",
                               getattr(r, "actual_kg", None), float(r.pred_kg), b,
                               {"interval_low": r.interval_low, "interval_high": r.interval_high,
                                "interval_level": r.interval_level, "anomaly": bool(getattr(r, "anomaly", False)),
                                "outside_interval": bool(getattr(r, "outside_interval", False))}))
    return out


def build(evaluation: dict | None = None, prod_bundle=None, butch_bundle=None) -> dict:
    return {"model": CHAMPION, "baseline": BASELINE, "data_note": DATA_NOTE,
            "metrics": metric_rows(evaluation), "comparisons": comparison_rows(prod_bundle, butch_bundle)}


def main():
    ev_path = ART / "evaluation.json"
    evaluation = json.loads(ev_path.read_text(encoding="utf-8")) if ev_path.exists() else None
    payload = build(evaluation)
    (ART / "ai_summary_preview.json").write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"{len(payload['metrics'])} metrics, {len(payload['comparisons'])} comparisons -> ai_summary_preview.json")


if __name__ == "__main__":
    main()
