"""Ali - challenger models against the Ridge champion (spec section 4A / 4B).

    python ml-engine/compare_models_ali.py     # -> artifacts/ali/model_comparison.json + .md

Same data, same rolling-origin folds, same nested tuning, same metrics and the same parsimony
rule as evaluate_ali.py; it only widens the candidate list with:

* random forest and XGBoost (bagged / boosted trees),
* Ridge + LightGBM on the Ridge residuals (a boosted correction on top of the linear model),
* simple averages of Ridge with CatBoost or LightGBM,
* stacking: non-negative weights over Ridge, CatBoost and LightGBM, learned on the last 20 % of
  each training window (time-ordered, never on the scored block), bases refit on the full window.

The "holdout" columns are folds 4-5, i.e. the original latest 20 % of the data.
"""
from __future__ import annotations

import json
import pathlib
import sys

import numpy as np
import pandas as pd
from scipy.optimize import nnls

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import evaluate_ali as E  # noqa: E402
import train_model_ali as T  # noqa: E402

OUT = HERE / "artifacts" / "ali"
_BASE_PROD, _BASE_BUTCH = E.production_candidates, E.butchery_candidates


# ---------------------------------------------------------------- extra learners
def random_forest(cats, nums, **kw):
    from sklearn.ensemble import RandomForestRegressor
    params = dict(n_estimators=300, min_samples_leaf=5, max_features=0.5, random_state=E.SEED, n_jobs=-1)
    params.update(kw)
    return E.make_pipeline(E._pre(cats, nums), RandomForestRegressor(**params))


def xgboost(cats, nums, **kw):
    import xgboost as xgb
    params = dict(n_estimators=400, learning_rate=0.03, max_depth=3, min_child_weight=10, subsample=0.8,
                  colsample_bytree=0.8, reg_lambda=1.0, random_state=E.SEED, n_jobs=4, tree_method="hist")
    params.update(kw)
    return E.make_pipeline(E._pre(cats, nums), xgb.XGBRegressor(**params))


RF_GRID = [dict(min_samples_leaf=l, max_features=f) for l in (3, 10) for f in (0.3, 0.6)]
XGB_GRID = [dict(max_depth=d, n_estimators=n) for d in (2, 4) for n in (300, 600)]


class Average:
    """Plain mean of the member predictions (each member fit on the same window)."""

    def __init__(self, builders):
        self.builders = builders

    def fit(self, X, y):
        self.models = [b().fit(X, y) for b in self.builders]
        return self

    def predict(self, X):
        return np.mean([m.predict(X) for m in self.models], axis=0)


class Residual:
    """Linear model first, then a small tree model learns what the linear model missed."""

    def __init__(self, base, booster):
        self.base, self.booster = base, booster

    def fit(self, X, y):
        y = np.asarray(y, float)
        self.m0 = self.base().fit(X, y)
        self.m1 = self.booster().fit(X, y - self.m0.predict(X))
        return self

    def predict(self, X):
        return self.m0.predict(X) + self.m1.predict(X)


class Stack:
    """Level-2: non-negative least squares weights over the bases, learned on the last
    VAL_FRACTION of the training window (bases fit on the part before it), then bases refit on the
    whole window. Weights are normalised to sum to 1."""

    def __init__(self, builders):
        self.builders = builders

    def fit(self, X, y):
        y = np.asarray(y, float)
        cut = int(len(X) * (1 - E.VAL_FRACTION))
        P = np.column_stack([b().fit(X.iloc[:cut], y[:cut]).predict(X.iloc[cut:]) for b in self.builders])
        w, _ = nnls(P, y[cut:])
        self.w = w / w.sum() if w.sum() > 0 else np.full(len(w), 1 / len(w))
        self.models = [b().fit(X, y) for b in self.builders]
        return self

    def predict(self, X):
        return np.column_stack([m.predict(X) for m in self.models]) @ self.w


# ---------------------------------------------------------------- candidate lists
def production_candidates(cats, nums) -> dict:
    base = _BASE_PROD(cats, nums)
    keep = ["baseline_recipe", "baseline_hist_mean", "ridge", "huber", "lightgbm", "catboost"]
    ridge = base["ridge"][0]
    lgb_small = lambda: E.lgbm(cats, nums, num_leaves=7, min_child_samples=50, n_estimators=200,  # noqa: E731
                               learning_rate=0.02)
    cb = lambda: T.CBRegressor(cats, T.PROD_CB_PARAMS)  # noqa: E731
    lgb = base["lightgbm"][0]
    extra = {
        "random_forest": (lambda: E.TimeTuned([lambda g=g: random_forest(cats, nums, **g) for g in RF_GRID]),
                          "candidate"),
        "xgboost": (lambda: E.TimeTuned([lambda g=g: xgboost(cats, nums, **g) for g in XGB_GRID]), "candidate"),
        "ridge+lgbm_residual": (lambda: Residual(ridge, lgb_small), "candidate"),
        "avg(ridge,catboost)": (lambda: Average([ridge, cb]), "candidate"),
        "avg(ridge,lightgbm)": (lambda: Average([ridge, lgb]), "candidate"),
        "stack(ridge,catboost,lightgbm)": (lambda: Stack([ridge, cb, lgb]), "candidate"),
    }
    return {**{k: base[k] for k in keep}, **extra}


def butchery_candidates(cats, nums) -> dict:
    base = _BASE_BUTCH(cats, nums)
    keep = ["baseline_recipe", "baseline_hist_mean", "ridge_project", "lightgbm_alr", "catboost_project"]
    ridge_t = lambda: E.TimeTuned([lambda a=a: E.linear(cats, nums, E.Ridge(alpha=a))  # noqa: E731
                                   for a in E.RIDGE_ALPHAS])
    lgb_small = lambda: E.lgbm(cats, nums, num_leaves=7, min_child_samples=50, n_estimators=200,  # noqa: E731
                               learning_rate=0.02)
    rf = lambda: random_forest(cats, nums, min_samples_leaf=10, max_features=0.5, n_estimators=200)  # noqa: E731
    xg = lambda: xgboost(cats, nums, max_depth=3, n_estimators=300)  # noqa: E731
    cb_raw = lambda: _MultiRaw(lambda: T.CBRegressor(cats, T.BUTCH_CB_PARAMS))  # noqa: E731
    ridge_raw = lambda: _PerShareRaw(ridge_t)  # noqa: E731
    extra = {
        "random_forest_project": (lambda: E.PerShareProject(rf), "candidate"),
        "xgboost_project": (lambda: E.PerShareProject(xg), "candidate"),
        "ridge+lgbm_residual_project": (lambda: E.PerShareProject(lambda: Residual(ridge_t, lgb_small)),
                                        "candidate"),
        "avg(ridge,catboost)_project": (lambda: _Project(Average([ridge_raw, cb_raw])), "candidate"),
    }
    return {**{k: base[k] for k in keep}, **extra}


class _PerShareRaw(E.PerShareProject):
    def predict(self, X):
        return self.predict_raw(X)


class _MultiRaw(E.MultiProject):
    def predict(self, X):
        return self.predict_raw(X)


class _Project:
    def __init__(self, model):
        self.model = model

    def fit(self, X, Y):
        self.model.fit(X, Y)
        return self

    def predict(self, X):
        return T.normalize_pct(self.model.predict(X))


# ---------------------------------------------------------------- report
def _rows_prod(rep):
    ridge = rep["models"]["ridge"]
    out = []
    for m, r in rep["models"].items():
        vs = r.get("vs", {})
        out.append({"model": m, "kind": r["kind"],
                    "packages_MAE": r["packages"]["MAE"], "packages_MAE_ci95": r["packages"].get("MAE_ci95"),
                    "ekp_MAE_eur": r["ekp_eur"]["MAE"],
                    "dev_MAE": r["dev_packages_MAE"], "holdout_MAE": r["holdout_packages_MAE"],
                    "fold_mean_std": r["packages_MAE_fold_mean_std"],
                    "coverage80": r.get("intervals", {}).get("80", {}).get("coverage"),
                    "vs_hist_mean_p": vs.get("baseline_hist_mean", {}).get("dm", {}).get("p_value"),
                    "minus_ridge": r["packages"]["MAE"] - ridge["packages"]["MAE"]})
    return out


def _rows_butch(rep):
    ridge = rep["models"]["ridge_project"]
    return [{"model": m, "kind": r["kind"], "kg_MAE": r["kg_MAE_all_parts"],
             "kg_MAE_ci95": r["kg_MAE_all_parts_ci95"], "dev_MAE": r["dev_kg_MAE"],
             "holdout_MAE": r["holdout_kg_MAE"], "fold_mean_std": r["kg_MAE_fold_mean_std"],
             "coverage80": r.get("intervals", {}).get("80", {}).get("coverage_all_cells"),
             "minus_ridge": r["kg_MAE_all_parts"] - ridge["kg_MAE_all_parts"]}
            for m, r in rep["models"].items()]


def paired_vs_ridge(rep, ridge_name, key):
    """Paired block-bootstrap 95 % CI of (model - Ridge) loss on the holdout folds only."""
    pooled = rep["_pooled"]
    hold = pooled["fold"] >= E.DEV_FOLDS
    idx = E.block_bootstrap_idx(int(hold.sum()))
    out = {}
    for m in pooled["pred"]:
        if m == ridge_name:
            continue
        d = key(m)[hold] - key(ridge_name)[hold]
        out[m] = {"holdout_diff": float(d.mean()), "ci95": E.ci(d[idx].mean(1))}
    return out


def _fmt_ci(c):
    return f"[{c[0]:.3f}, {c[1]:.3f}]" if c else ""


def markdown(prod_rows, butch_rows, prod_sel, butch_sel, prod_hold, butch_hold) -> str:
    L = ["# Challenger models vs Ridge", "",
         "Rolling origin, 5 folds; pooled over 748 production orders / 1,250 carcasses. "
         "Dev = folds 1-3 (used to choose), holdout = folds 4-5 (latest 20 %). "
         "Holdout diff vs Ridge = paired block-bootstrap 95 % CI; negative means better than Ridge.", "",
         "## A, production (packages MAE)", "",
         "| Model | Packages MAE [95 % CI] | EKP MAE (EUR) | Dev | Holdout | Holdout diff vs Ridge [95 % CI] | 80 % coverage |",
         "|---|---|---|---|---|---|---|"]
    for r in prod_rows:
        h = prod_hold.get(r["model"])
        hd = f"{h['holdout_diff']:+.3f} {_fmt_ci(h['ci95'])}" if h else "-"
        cov = f"{100 * r['coverage80']:.1f} %" if r["coverage80"] is not None else ""
        L.append(f"| {r['model']} | {r['packages_MAE']:.3f} {_fmt_ci(r['packages_MAE_ci95'])} | "
                 f"{r['ekp_MAE_eur']:.4f} | {r['dev_MAE']:.3f} | {r['holdout_MAE']:.3f} | {hd} | {cov} |")
    L += ["", f"Selection rule result: **{prod_sel[0]}** (best on dev: {prod_sel[1]['best_on_dev']}).", "",
          "## B, butchery (part kg MAE, 6 parts)", "",
          "| Model | kg MAE [95 % CI] | Dev | Holdout | Holdout diff vs Ridge [95 % CI] | 80 % coverage |",
          "|---|---|---|---|---|---|"]
    for r in butch_rows:
        h = butch_hold.get(r["model"])
        hd = f"{h['holdout_diff']:+.4f} {_fmt_ci(h['ci95'])}" if h else "-"
        cov = f"{100 * r['coverage80']:.1f} %" if r["coverage80"] is not None else ""
        L.append(f"| {r['model']} | {r['kg_MAE']:.4f} {_fmt_ci(r['kg_MAE_ci95'])} | {r['dev_MAE']:.4f} | "
                 f"{r['holdout_MAE']:.4f} | {hd} | {cov} |")
    L += ["", f"Selection rule result: **{butch_sel[0]}** (best on dev: {butch_sel[1]['best_on_dev']}).", ""]
    return "\n".join(L)


def main():
    E.production_candidates, E.butchery_candidates = production_candidates, butchery_candidates
    prod = pd.read_csv(HERE / "data" / "train_task4a_ali.csv")
    butch = pd.read_csv(HERE / "data" / "train_task4b_ali.csv")
    rp = E.evaluate_production(prod, with_cqr=False)
    rb = E.evaluate_butchery(butch)

    pp = rp["_pooled"]
    actual = pp["df"].actual_qty.to_numpy()
    prod_hold = paired_vs_ridge(
        rp, "ridge", lambda m: np.abs(E.production_business(pp["df"], pp["pred"][m])["qty"] - actual))
    bp = rb["_pooled"]
    kg_true = bp["Y"] * bp["W"][:, None] / 100
    butch_hold = paired_vs_ridge(
        rb, "ridge_project", lambda m: np.abs(bp["pred"][m] * bp["W"][:, None] / 100 - kg_true).mean(1))

    prod_rows, butch_rows = _rows_prod(rp), _rows_butch(rb)
    report = {"production": {"rows": prod_rows, "champion": rp["champion"], "selection": rp["selection"],
                             "holdout_vs_ridge": prod_hold},
              "butchery": {"rows": butch_rows, "champion": rb["champion"], "selection": rb["selection"],
                           "holdout_vs_ridge": butch_hold}}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "model_comparison.json").write_text(json.dumps(report, indent=2, default=float) + "\n",
                                               encoding="utf-8")
    md = markdown(prod_rows, butch_rows, (rp["champion"], rp["selection"]), (rb["champion"], rb["selection"]),
                  prod_hold, butch_hold)
    (OUT / "model_comparison.md").write_text(md, encoding="utf-8")
    print(md)


if __name__ == "__main__":
    import compare_models_ali as _module   # keep pickled classes under the module name
    _module.main()
