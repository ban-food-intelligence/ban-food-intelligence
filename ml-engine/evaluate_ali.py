"""Ali - robust evaluation of the AI studies (spec section 4A / 4B).

    python ml-engine/evaluate_ali.py                 # -> artifacts/ali/evaluation.json + docs/ai/*.png

What it measures, and why it can be trusted:

* Rolling-origin (expanding window) CV: rows are sorted by date and cut into blocks at date
  boundaries. Fold k trains on everything before block k and predicts block k, so every
  prediction is a genuine "future" prediction. Folds 1-3 lie inside the original training period
  (before the contract's 80 % cut) and are the ONLY folds used to choose the champion; folds 4-5
  are the original holdout and serve as confirmation.
* Hyperparameters are tuned inside each fold's training data (last 20 % of it as a time-ordered
  validation window), never on the fold being scored (nested).
* Each metric: mean +/- std across folds and a moving-block bootstrap 95 % CI on the pooled
  out-of-sample rows. Model vs baseline: Diebold-Mariano test (Newey-West variance) and a paired
  block bootstrap CI of the MAE difference.
* Parsimony: the champion is the simplest candidate whose dev-fold error is not significantly
  worse than the best one (the paired 95 % CI of the difference contains zero).
* Uncertainty: split-conformal and CQR intervals, calibrated on the last 25 % of each fold's
  training window; empirical coverage is measured on the scored block.
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib
import sys

import numpy as np
import pandas as pd
from scipy import stats
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.linear_model import ElasticNet, HuberRegressor, Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import ml_contract as C  # noqa: E402
import train_model_ali as T  # noqa: E402

ART = HERE / "artifacts" / "ali"
DOCS = HERE.parent / "docs" / "ai"
SEED = 42
N_FOLDS = 5
FOLD_START = 0.5            # first scored block starts at the median date
DEV_FOLDS = 3               # blocks 50-80 %: inside the original training period
N_BOOT = 2000
BLOCK = 10                  # moving-block length for the bootstrap (rows, date-ordered)
INTERVAL_LEVEL = 0.80       # interval shown to users
ANOMALY_LEVEL = 0.98        # "anomaly" = actual outside this wider conformal band
CAL_FRACTION = 0.25         # last 25 % of a training window calibrates the interval
VAL_FRACTION = 0.20         # last 20 % of a training window tunes hyperparameters


# ================================================================ statistics
def block_bootstrap_idx(n: int, n_boot: int = N_BOOT, block: int = BLOCK, seed: int = SEED) -> np.ndarray:
    """(n_boot, n) row indices; moving blocks keep short-range time dependence intact."""
    rng = np.random.default_rng(seed)
    block = max(1, min(block, n))
    n_blocks = math.ceil(n / block)
    starts = rng.integers(0, n - block + 1, size=(n_boot, n_blocks))
    idx = (starts[:, :, None] + np.arange(block)[None, None, :]).reshape(n_boot, -1)
    return idx[:, :n]


def ci(samples: np.ndarray) -> list[float]:
    return [float(np.quantile(samples, 0.025)), float(np.quantile(samples, 0.975))]


def diebold_mariano(loss_a: np.ndarray, loss_b: np.ndarray) -> dict:
    """H0: equal expected loss. d = a - b; negative stat = a is better. Newey-West variance."""
    d = np.asarray(loss_a, float) - np.asarray(loss_b, float)
    n = len(d)
    dc = d - d.mean()
    lag = int(n ** (1 / 3))
    var = dc @ dc / n
    for k in range(1, lag + 1):
        var += 2 * (1 - k / (lag + 1)) * (dc[k:] @ dc[:-k]) / n
    stat = float(d.mean() / math.sqrt(max(var, 1e-18) / n))
    return {"stat": stat, "p_value": float(2 * stats.norm.sf(abs(stat))), "lag": lag}


def error_metrics(y: np.ndarray, p: np.ndarray, idx: np.ndarray | None = None) -> dict:
    """MAE, MAPE, wMAPE (+ bootstrap CIs when idx is given). y, p: 1-D."""
    e = np.abs(y - p)
    ape = e / np.abs(y)
    out = {"MAE": float(e.mean()), "MAPE_pct": float(100 * ape.mean()),
           "wMAPE_pct": float(100 * e.sum() / np.abs(y).sum())}
    if idx is not None:
        out["MAE_ci95"] = ci(e[idx].mean(1))
        out["MAPE_pct_ci95"] = ci(100 * ape[idx].mean(1))
        out["wMAPE_pct_ci95"] = ci(100 * e[idx].sum(1) / np.abs(y)[idx].sum(1))
    return out


def paired_test(loss_model: np.ndarray, loss_ref: np.ndarray, idx: np.ndarray) -> dict:
    diff = loss_model - loss_ref
    return {"mae_diff": float(diff.mean()), "mae_diff_ci95": ci(diff[idx].mean(1)),
            "dm": diebold_mariano(loss_model, loss_ref)}


conformal_q = T.conformal_q    # finite-sample split-conformal quantile


# ================================================================ folds
def rolling_folds(dates: pd.Series, start: float = FOLD_START, n_folds: int = N_FOLDS) -> list[tuple]:
    """Expanding-window folds on date-sorted rows; block edges snap to the first row of a date."""
    d = pd.to_datetime(dates).reset_index(drop=True)
    n = len(d)
    edges = []
    for f in np.linspace(start, 1.0, n_folds + 1):
        i = min(int(round(f * n)), n)
        while 0 < i < n and d[i] == d[i - 1]:
            i += 1
        edges.append(i)
    return [(np.arange(0, edges[k]), np.arange(edges[k], edges[k + 1])) for k in range(n_folds)]


# ================================================================ models (fit(X, y) / predict(X))
def _pre(cats, nums):
    return ColumnTransformer([
        ("cat", OneHotEncoder(handle_unknown="ignore", sparse_output=False), cats),
        ("num", make_pipeline(SimpleImputer(strategy="median"), StandardScaler()), nums),
    ])


def linear(cats, nums, est):
    return make_pipeline(_pre(cats, nums), est)


def lgbm(cats, nums, objective="regression", alpha=None, **kw):
    import lightgbm as lgb
    params = dict(n_estimators=400, learning_rate=0.03, num_leaves=15, min_child_samples=30,
                  subsample=0.8, subsample_freq=1, colsample_bytree=0.8, reg_lambda=1.0,
                  random_state=SEED, n_jobs=1, deterministic=True, force_row_wise=True, verbose=-1)
    params.update(kw)
    if objective == "quantile":
        params.update(objective="quantile", alpha=alpha)
    return make_pipeline(_pre(cats, nums), lgb.LGBMRegressor(**params))


class Constant:
    def __init__(self, value):
        self.value = value

    def fit(self, X, y):
        return self

    def predict(self, X):
        return np.tile(np.asarray(self.value, float), (len(X), 1)).squeeze()


class GroupMean:
    """Mean target per group (SKU / species) on the training window: the 'historical mean' baseline."""

    def __init__(self, col):
        self.col = col

    def fit(self, X, y):
        y = np.asarray(y, float)
        frame = pd.DataFrame(y.reshape(len(y), -1)).assign(g=X[self.col].to_numpy())
        self.means, self.overall = frame.groupby("g").mean(), frame.drop(columns="g").mean().to_numpy()
        return self

    def predict(self, X):
        out = np.vstack([self.means.loc[g].to_numpy() if g in self.means.index else self.overall
                         for g in X[self.col]])
        return out.squeeze()


class PhysicsFixedLoss:
    """Physics-informed baseline: yield = a_sku - b_sku / meat_kg (proportional drying loss plus a
    fixed filler/dead-volume loss per batch). Two parameters per SKU, nothing else learned."""

    def fit(self, X, y):
        self.coef = {}
        for g, idx in X.groupby("product_sku").indices.items():
            A = np.column_stack([np.ones(len(idx)), X["inv_meat_kg"].to_numpy()[idx]])
            self.coef[g] = np.linalg.lstsq(A, np.asarray(y)[idx], rcond=None)[0]
        self.fallback = np.mean(list(self.coef.values()), axis=0)
        return self

    def predict(self, X):
        c = np.vstack([self.coef.get(g, self.fallback) for g in X["product_sku"]])
        return c[:, 0] + c[:, 1] * X["inv_meat_kg"].to_numpy()


class TimeTuned:
    """Nested tuning: pick the config with the lowest MAE on the last VAL_FRACTION of the training
    window (time-ordered), then refit that config on the whole window."""

    def __init__(self, builders: list, name: str = ""):
        self.builders, self.name = builders, name

    def fit(self, X, y):
        y = np.asarray(y, float)
        self.choice = 0
        if len(self.builders) > 1:
            cut = int(len(X) * (1 - VAL_FRACTION))
            scores = [np.mean(np.abs(b().fit(X.iloc[:cut], y[:cut]).predict(X.iloc[cut:]) - y[cut:]))
                      for b in self.builders]
            self.choice = int(np.argmin(scores))
        self.model = self.builders[self.choice]().fit(X, y)
        return self

    def predict(self, X):
        return self.model.predict(X)


# ---- butchery wrappers: input shares (n, 6) in %, output shares that sum to 100
class PerShareProject:
    """One regressor per share, then project onto the simplex (clip at 0.1, renormalise)."""

    def __init__(self, builder):
        self.builder = builder

    def fit(self, X, Y):
        self.models = [self.builder().fit(X, Y[:, j]) for j in range(Y.shape[1])]
        return self

    def predict_raw(self, X):
        return np.column_stack([m.predict(X) for m in self.models])

    def predict(self, X):
        return T.normalize_pct(self.predict_raw(X))


class MultiProject:
    """One multi-output regressor (CatBoost MultiRMSE), then simplex projection."""

    def __init__(self, builder):
        self.builder = builder

    def fit(self, X, Y):
        self.model = self.builder().fit(X, Y)
        return self

    def predict_raw(self, X):
        return np.atleast_2d(self.model.predict(X))

    def predict(self, X):
        return T.normalize_pct(self.predict_raw(X))


class ALR:
    """Compositional: regress the additive log-ratios log(share_j / share_fire) and map back with
    softmax. Every predicted share is positive and the six sum to exactly 100 by construction."""

    def __init__(self, builder):
        self.builder = builder

    def fit(self, X, Y):
        Z = T.alr(Y)
        self.models = [self.builder().fit(X, Z[:, j]) for j in range(Z.shape[1])]
        return self

    def predict(self, X):
        return T.alr_inverse(np.column_stack([m.predict(X) for m in self.models]))


RIDGE_ALPHAS = [0.3, 1.0, 3.0, 10.0, 30.0]
ENET_GRID = [(a, r) for a in (0.003, 0.01, 0.03) for r in (0.2, 0.8)]
LGBM_GRID = [dict(num_leaves=nl, min_child_samples=mc, n_estimators=ne)
             for nl in (7, 15) for mc in (20, 50) for ne in (300, 600)]


def production_candidates(cats, nums) -> dict:
    """Ordered from simplest to most complex (the order breaks ties under the parsimony rule)."""
    return {
        "baseline_recipe": (lambda: Constant(T.RECIPE_YIELD_PCT), "baseline"),
        "baseline_hist_mean": (lambda: GroupMean("product_sku"), "baseline"),
        "physics_fixed_loss": (lambda: PhysicsFixedLoss(), "candidate"),
        "ridge": (lambda: TimeTuned([lambda a=a: linear(cats, nums, Ridge(alpha=a)) for a in RIDGE_ALPHAS]),
                  "candidate"),
        "elasticnet": (lambda: TimeTuned([lambda a=a, r=r: linear(cats, nums, ElasticNet(alpha=a, l1_ratio=r,
                                                                                        max_iter=20000))
                                          for a, r in ENET_GRID]), "candidate"),
        # robust loss: the 2 % machine-fault orders pull a squared loss, not an absolute one
        "huber": (lambda: TimeTuned([lambda a=a: linear(cats, nums, HuberRegressor(alpha=a, max_iter=1000))
                                     for a in (0.1, 1.0, 10.0)]), "candidate"),
        "lightgbm": (lambda: TimeTuned([lambda g=g: lgbm(cats, nums, **g) for g in LGBM_GRID]), "candidate"),
        "catboost": (lambda: T.CBRegressor(cats, T.PROD_CB_PARAMS), "candidate"),
    }


def butchery_candidates(cats, nums) -> dict:
    ridge_t = lambda: TimeTuned([lambda a=a: linear(cats, nums, Ridge(alpha=a)) for a in RIDGE_ALPHAS])  # noqa: E731
    return {
        "baseline_recipe": (lambda: _RecipeShares(), "baseline"),
        "baseline_hist_mean": (lambda: GroupMean("species"), "baseline"),
        "ridge_project": (lambda: PerShareProject(ridge_t), "candidate"),
        "ridge_alr": (lambda: ALR(ridge_t), "candidate"),
        "lightgbm_alr": (lambda: ALR(lambda: lgbm(cats, nums, num_leaves=7, min_child_samples=50,
                                                  n_estimators=300)), "candidate"),
        "catboost_project": (lambda: MultiProject(lambda: T.CBRegressor(cats, T.BUTCH_CB_PARAMS)), "candidate"),
    }


class _RecipeShares:
    def fit(self, X, Y):
        return self

    def predict(self, X):
        return np.vstack([T.RECIPE_BUTCHERY_PCT[s] for s in X["species"]]).astype(float)


# ================================================================ Task A
def _prod_frame(df: pd.DataFrame):
    df = df.sort_values(C.PRODUCTION["date"], kind="stable").reset_index(drop=True)
    X = T.production_features(df)
    cats = C.PRODUCTION["categorical"]
    return df, X, df[C.PRODUCTION["target"]].to_numpy(float), cats, [c for c in X.columns if c not in cats]


def production_business(df: pd.DataFrame, yield_pred: np.ndarray) -> dict:
    """Per order: packages, EKP, and the EUR value of the cost error (|EKP error| x packages made)."""
    qty = C.production_qty_from_yield(df, yield_pred)
    ekp = C.production_ekp(df, qty)
    return {"qty": qty, "ekp": ekp,
            "eur_error": np.abs(ekp - df.ekp.to_numpy()) * df.actual_qty.to_numpy(),
            "kg_error": np.abs(qty - df.actual_qty.to_numpy()) * df.pack_size_kg.to_numpy()}


def _fit_with_intervals(make, X, y, tr, te, levels):
    """Point prediction from a model fit on the whole window; split-conformal q from a model fit on
    the window minus its last CAL_FRACTION, scored on that calibration slice."""
    cut = int(len(tr) * (1 - CAL_FRACTION))
    proper, cal = tr[:cut], tr[cut:]
    m_cal = make().fit(X.iloc[proper], y[proper])
    scores = np.abs(np.asarray(y[cal]) - m_cal.predict(X.iloc[cal]))
    q = {lv: np.array([conformal_q(scores[:, j], lv) for j in range(scores.shape[1])]) if scores.ndim == 2
         else conformal_q(scores, lv) for lv in levels}
    model = make().fit(X.iloc[tr], y[tr])
    return model, model.predict(X.iloc[te]), q


def _cqr(cats, nums, X, y, tr, te, level):
    """Conformalised quantile regression (Romano et al. 2019) with LightGBM quantile models."""
    a = (1 - level) / 2
    cut = int(len(tr) * (1 - CAL_FRACTION))
    proper, cal = tr[:cut], tr[cut:]
    lo_m = lgbm(cats, nums, "quantile", a, num_leaves=7, min_child_samples=40).fit(X.iloc[proper], y[proper])
    hi_m = lgbm(cats, nums, "quantile", 1 - a, num_leaves=7, min_child_samples=40).fit(X.iloc[proper], y[proper])
    s = np.maximum(lo_m.predict(X.iloc[cal]) - y[cal], y[cal] - hi_m.predict(X.iloc[cal]))
    q = conformal_q(s, level)
    return lo_m.predict(X.iloc[te]) - q, hi_m.predict(X.iloc[te]) + q


def evaluate_production(raw: pd.DataFrame, models: list[str] | None = None, n_boot: int = N_BOOT,
                        faults: pd.Series | None = None, with_cqr: bool = True) -> dict:
    """faults: optional bool Series indexed by row_id (known injected faults, for flag quality)."""
    df, X, y, cats, nums = _prod_frame(raw)
    cands = production_candidates(cats, nums)
    models = models or list(cands)
    folds = rolling_folds(df[C.PRODUCTION["date"]])
    scored = np.concatenate([te for _, te in folds])
    fold_id = np.concatenate([np.full(len(te), k) for k, (_, te) in enumerate(folds)])
    levels = (INTERVAL_LEVEL, 0.90, ANOMALY_LEVEL)

    pred = {m: np.full(len(df), np.nan) for m in models}
    q = {m: [] for m in models}
    for k, (tr, te) in enumerate(folds):
        for m in models:
            _, p, qq = _fit_with_intervals(cands[m][0], X, y, tr, te, levels)
            pred[m][te] = p
            q[m].append(qq)
    cqr = None
    if with_cqr:
        cqr = np.full((len(df), 2), np.nan)
        for tr, te in folds:
            cqr[te, 0], cqr[te, 1] = _cqr(cats, nums, X, y, tr, te, INTERVAL_LEVEL)

    ys, sub = y[scored], df.iloc[scored].reset_index(drop=True)
    dev = fold_id < DEV_FOLDS
    idx_all = block_bootstrap_idx(len(scored), n_boot)
    idx_dev = block_bootstrap_idx(int(dev.sum()), n_boot)
    report = {"n_rows": len(df), "folds": [{"train_rows": len(tr), "test_rows": len(te),
                                            "test_from": str(df[C.PRODUCTION["date"]].iloc[te[0]]),
                                            "test_to": str(df[C.PRODUCTION["date"]].iloc[te[-1]]),
                                            "role": "dev" if k < DEV_FOLDS else "holdout"}
                                           for k, (tr, te) in enumerate(folds)],
              "models": {}}
    losses = {}
    for m in models:
        p = pred[m][scored]
        biz = production_business(sub, p)
        e_qty = np.abs(biz["qty"] - sub.actual_qty.to_numpy())
        losses[m] = e_qty
        per_fold = [error_metrics(sub.actual_qty.to_numpy()[fold_id == k], biz["qty"][fold_id == k])["MAE"]
                    for k in range(len(folds))]
        r = {
            "kind": cands[m][1],
            "yield_pct": error_metrics(ys, p, idx_all),
            "packages": error_metrics(sub.actual_qty.to_numpy(), biz["qty"], idx_all),
            "ekp_eur": error_metrics(sub.ekp.to_numpy(), biz["ekp"], idx_all),
            "packages_MAE_per_fold": per_fold,
            "packages_MAE_fold_mean_std": [float(np.mean(per_fold)), float(np.std(per_fold, ddof=1))],
            "dev_packages_MAE": float(e_qty[dev].mean()),
            "holdout_packages_MAE": float(e_qty[~dev].mean()),
            "business": {"eur_cost_error_per_order": float(biz["eur_error"].mean()),
                         "eur_cost_error_per_order_ci95": ci(biz["eur_error"][idx_all].mean(1)),
                         "kg_output_error_per_order": float(biz["kg_error"].mean())},
        }
        if cands[m][1] == "candidate":
            r["intervals"] = _coverage_1d(ys, p, q[m], fold_id, levels)
            if faults is not None:
                r["anomaly_flag"] = _flag_quality(ys, p, q[m], fold_id,
                                                  faults.reindex(sub.row_id).fillna(False).to_numpy(bool))
        report["models"][m] = r

    for m in models:
        for ref in ("baseline_hist_mean", "baseline_recipe"):
            if ref in losses and m != ref:
                report["models"][m].setdefault("vs", {})[ref] = paired_test(losses[m], losses[ref], idx_all)

    report["champion"], report["selection"] = _select(models, cands, losses, dev, idx_dev)
    champ = report["champion"]
    if with_cqr:
        lo, hi = cqr[scored, 0], cqr[scored, 1]
        report["cqr_80"] = {"coverage": float(np.mean((ys >= lo) & (ys <= hi))),
                            "mean_width_pts": float(np.mean(hi - lo)),
                            "coverage_per_fold": [float(np.mean(((ys >= lo) & (ys <= hi))[fold_id == k]))
                                                  for k in range(len(folds))]}
    report["_pooled"] = {"y": ys, "pred": {m: pred[m][scored] for m in models}, "fold": fold_id,
                         "q": q, "dates": sub[C.PRODUCTION["date"]].to_numpy(), "df": sub,
                         "champion": champ}
    return report


def _coverage_1d(y, p, qs, fold_id, levels) -> dict:
    out = {}
    for lv in levels:
        half = np.array([qs[k][lv] for k in fold_id])
        inside = np.abs(y - p) <= half
        out[f"{int(round(lv * 100))}"] = {
            "coverage": float(inside.mean()),
            "coverage_per_fold": [float(inside[fold_id == k].mean()) for k in range(len(qs))],
            "mean_half_width_pts": float(half.mean())}
    return out


def _flag_quality(y, p, qs, fold_id, fault) -> dict:
    out = {}
    for lv in (INTERVAL_LEVEL, ANOMALY_LEVEL):
        flag = np.abs(y - p) > np.array([qs[k][lv] for k in fold_id])
        tp = int((flag & fault).sum())
        out[f"outside_{int(round(lv * 100))}"] = {
            "flag_rate": float(flag.mean()), "recall_faults": float(tp / max(fault.sum(), 1)),
            "precision": float(tp / max(flag.sum(), 1)),
            "false_alarms_per_100_normal": float(100 * (flag & ~fault).sum() / max((~fault).sum(), 1)),
            "n_faults": int(fault.sum())}
    return out


def _select(models, cands, losses, dev, idx_dev):
    """Parsimony: simplest candidate whose dev loss is not significantly worse than the best."""
    pool = [m for m in models if cands[m][1] == "candidate"]
    if not pool:
        return None, {}
    best = min(pool, key=lambda m: losses[m][dev].mean())
    detail = {"rule": "simplest candidate whose dev-fold MAE is not significantly worse than the best "
                      "(paired block-bootstrap 95% CI of the difference contains 0)",
              "best_on_dev": best, "dev_MAE": {m: float(losses[m][dev].mean()) for m in pool}, "diff_vs_best": {}}
    for m in pool:                       # pool is ordered simple -> complex
        if m == best:
            return m, detail
        d = losses[m][dev] - losses[best][dev]
        lo_hi = ci(d[idx_dev].mean(1))
        detail["diff_vs_best"][m] = lo_hi
        if lo_hi[0] <= 0:
            return m, detail
    return best, detail


# ================================================================ Task B
def _butch_frame(df: pd.DataFrame):
    df = df.sort_values(C.BUTCHERY["date"], kind="stable").reset_index(drop=True)
    X = T.butchery_features(df)
    cats = C.BUTCHERY["categorical"]
    return df, X, df[C.BUTCHERY["targets"]].to_numpy(float), cats, [c for c in X.columns if c not in cats]


def constraint_violation(P_pct: np.ndarray, W: np.ndarray, tol_kg: float = 0.05) -> dict:
    """Mass balance: predicted parts + waste must equal the input weight."""
    gap = np.abs(P_pct.sum(1) * W / 100 - W)
    return {"violation_rate": float(np.mean(gap > tol_kg)), "mean_abs_gap_kg": float(gap.mean()),
            "max_abs_gap_kg": float(gap.max()), "negative_part_rate": float(np.mean((P_pct < 0).any(1))),
            "tolerance_kg": tol_kg}


def evaluate_butchery(raw: pd.DataFrame, models: list[str] | None = None, n_boot: int = N_BOOT) -> dict:
    df, X, Y, cats, nums = _butch_frame(raw)
    W = df.carcass_weight_kg.to_numpy(float)
    cands = butchery_candidates(cats, nums)
    models = models or list(cands)
    folds = rolling_folds(df[C.BUTCHERY["date"]])
    scored = np.concatenate([te for _, te in folds])
    fold_id = np.concatenate([np.full(len(te), k) for k, (_, te) in enumerate(folds)])
    levels = (INTERVAL_LEVEL, 0.90, ANOMALY_LEVEL)

    pred = {m: np.full(Y.shape, np.nan) for m in models}
    raw_pred = {m: np.full(Y.shape, np.nan) for m in models}
    q = {m: [] for m in models}
    for tr, te in folds:
        for m in models:
            model, p, qq = _fit_with_intervals(cands[m][0], X, Y, tr, te, levels)
            pred[m][te] = p
            q[m].append(qq)
            if hasattr(model, "predict_raw"):
                raw_pred[m][te] = model.predict_raw(X.iloc[te])

    Ys, Ws, sub = Y[scored], W[scored], df.iloc[scored].reset_index(drop=True)
    kg_true = Ys * Ws[:, None] / 100
    dev = fold_id < DEV_FOLDS
    idx_all = block_bootstrap_idx(len(scored), n_boot)
    idx_dev = block_bootstrap_idx(int(dev.sum()), n_boot)
    report = {"n_rows": len(df), "models": {}}
    losses = {}
    for m in models:
        P = pred[m][scored]
        kg = P * Ws[:, None] / 100
        e_kg = np.abs(kg - kg_true)
        losses[m] = e_kg.mean(1)                          # per carcass: mean abs kg error over 6 parts
        per_fold = [float(losses[m][fold_id == k].mean()) for k in range(len(folds))]
        r = {
            "kind": cands[m][1],
            "share_pts_MAE": float(np.abs(P - Ys).mean()),
            "part_kg": error_metrics(kg_true[:, :5].ravel(), kg[:, :5].ravel()),
            "waste_kg": error_metrics(kg_true[:, 5], kg[:, 5], idx_all),
            "kg_MAE_all_parts": float(losses[m].mean()),
            "kg_MAE_all_parts_ci95": ci(losses[m][idx_all].mean(1)),
            "kg_MAE_per_fold": per_fold,
            "kg_MAE_fold_mean_std": [float(np.mean(per_fold)), float(np.std(per_fold, ddof=1))],
            "dev_kg_MAE": float(losses[m][dev].mean()),
            "holdout_kg_MAE": float(losses[m][~dev].mean()),
            "business": {"kg_misallocated_per_carcass": float(e_kg.sum(1).mean()),
                         "sellable_kg_error_per_carcass": float(np.abs(kg[:, :5].sum(1) - kg_true[:, :5].sum(1)).mean())},
            "constraint_after": constraint_violation(P, Ws),
        }
        # part-kg CIs: bootstrap carcasses, not individual cells
        ek5 = e_kg[:, :5]
        r["part_kg"]["MAE_ci95"] = ci(ek5[idx_all].mean((1, 2)))
        if not np.isnan(raw_pred[m]).all():
            r["constraint_before"] = constraint_violation(raw_pred[m][scored], Ws)
        if cands[m][1] == "candidate":
            r["intervals"] = {}
            for lv in levels:
                half = np.vstack([q[m][k][lv] for k in fold_id])
                inside = np.abs(Ys - P) <= half
                r["intervals"][f"{int(round(lv * 100))}"] = {
                    "coverage_per_part": dict(zip(C.BUTCHERY_PARTS, map(float, inside.mean(0)))),
                    "coverage_all_cells": float(inside.mean()),
                    "coverage_per_fold": [float(inside[fold_id == k].mean()) for k in range(len(folds))],
                    "coverage_joint_6_parts": float(inside.all(1).mean()),
                    "mean_half_width_kg": float((half * Ws[:, None] / 100).mean())}
        report["models"][m] = r
    for m in models:
        for ref in ("baseline_hist_mean", "baseline_recipe"):
            if ref in losses and m != ref:
                report["models"][m].setdefault("vs", {})[ref] = paired_test(losses[m], losses[ref], idx_all)
    report["champion"], report["selection"] = _select(models, cands, losses, dev, idx_dev)
    report["_pooled"] = {"Y": Ys, "W": Ws, "pred": {m: pred[m][scored] for m in models}, "fold": fold_id,
                         "q": q, "df": sub, "champion": report["champion"]}
    return report


# ================================================================ learning curve
def learning_curve(task: str, raw: pd.DataFrame, sizes: list[int], n_boot: int = 500) -> list[dict]:
    """Train on the most recent n rows before the contract's 80 % cut, score the 20 % holdout."""
    if task == "production":
        df, X, y, cats, nums = _prod_frame(raw)
        date, make_m, make_b = C.PRODUCTION["date"], (lambda: linear(cats, nums, Ridge(alpha=1.0))), \
            (lambda: GroupMean("product_sku"))
    else:
        df, X, y, cats, nums = _butch_frame(raw)
        date = C.BUTCHERY["date"]
        make_m = lambda: ALR(lambda: linear(cats, nums, Ridge(alpha=1.0)))  # noqa: E731
        make_b = lambda: GroupMean("species")                               # noqa: E731
    split = C.assign_splits(df, date).split.to_numpy()
    tr_all, te = np.where(split == "train")[0], np.where(split == "test")[0]
    idx = block_bootstrap_idx(len(te), n_boot)

    def loss(p):
        if task == "production":
            return np.abs(C.production_qty_from_yield(df.iloc[te], p) - df.actual_qty.to_numpy()[te])
        W = df.carcass_weight_kg.to_numpy()[te][:, None]
        return (np.abs(p - y[te]) * W / 100).mean(1)

    out = []
    for n in sizes:
        tr = tr_all[-n:]
        row = {"n_train": int(len(tr))}
        for name, make in (("model", make_m), ("baseline", make_b)):
            ell = loss(make().fit(X.iloc[tr], y[tr]).predict(X.iloc[te]))
            row[name] = float(ell.mean())
            row[f"{name}_ci95"] = ci(ell[idx].mean(1))
        out.append(row)
    return out


# ================================================================ explainability
def permutation_importance(task: str, raw: pd.DataFrame, n_repeats: int = 10, top: int = 5) -> list[dict]:
    """Permute one RAW input column at a time on the holdout (derived features follow their source),
    report the MAE increase, plus a direction sentence from a p25 -> p75 (or category) what-if."""
    rng = np.random.default_rng(SEED)
    if task == "production":
        df, X, y, cats, nums = _prod_frame(raw)
        date, feats = C.PRODUCTION["date"], T.production_features
        cols = [c for c in C.PRODUCTION["categorical"] + C.PRODUCTION["numeric"] if c not in T.PRICE_COLS] + [date]
        model = linear(cats, nums, Ridge(alpha=1.0))

        def score(frame):
            return np.abs(model.predict(feats(frame)) - y[te]).mean()

        def effect(frame):
            return model.predict(feats(frame))[:, None]
        targets = ["yield"]
    else:
        df, X, y, cats, nums = _butch_frame(raw)
        date, feats = C.BUTCHERY["date"], T.butchery_features
        cols = [c for c in C.BUTCHERY["categorical"] + C.BUTCHERY["numeric"] if c not in T.PRICE_COLS] + [date]
        model = ALR(lambda: linear(cats, nums, Ridge(alpha=1.0)))
        W = None

        def score(frame):
            return (np.abs(model.predict(feats(frame)) - y[te]) * W).mean()

        def effect(frame):
            return model.predict(feats(frame))
        targets = C.BUTCHERY_PARTS
    split = C.assign_splits(df, date).split.to_numpy()
    tr, te = np.where(split == "train")[0], np.where(split == "test")[0]
    model.fit(X.iloc[tr], y[tr])
    hold = df.iloc[te].reset_index(drop=True)
    if task == "butchery":
        W = hold.carcass_weight_kg.to_numpy()[:, None] / 100
    base = score(hold)
    rows = []
    for c in cols:
        inc = []
        for _ in range(n_repeats):
            perm = hold.copy()
            perm[c] = rng.permutation(perm[c].to_numpy())
            inc.append(score(perm) - base)
        rows.append({"feature": c, "mae_increase": float(np.mean(inc)), "std": float(np.std(inc))})
    rows.sort(key=lambda r: -r["mae_increase"])
    for r in rows[:top]:
        r["sentence"] = _direction_sentence(task, r["feature"], hold, effect, targets, df.iloc[tr])
    return rows[:top]


LABEL = {"meat_kg": "batch size (meat kg)", "drying_days": "drying days", "meat_fat_pct": "meat fat %",
         "meat_age_days": "meat age (days)", "subcontractor_code": "subcontractor", "product_sku": "product",
         "meat_supplier_code": "meat supplier", "order_date": "season (month)", "op_date": "season (month)",
         "operator_code": "butcher (operator)", "fat_score": "fat score", "conformation_score": "conformation score",
         "carcass_weight_kg": "carcass weight", "chill_hours": "chill hours", "species": "species",
         "breed": "breed", "sex": "sex", "age_months": "age", "spice_kg": "spice kg", "casing_kg": "casing kg",
         "planned_qty": "planned packages", "pack_size_kg": "pack size", "supplier_code": "supplier"}


def _direction_sentence(task, col, hold, effect, targets, train) -> str:
    name = LABEL.get(col, col)
    unit = "yield points" if task == "production" else "share points"
    if col in ("order_date", "op_date"):
        means = {}
        for mth in range(1, 13):
            f = hold.copy()
            f[col] = pd.to_datetime(f[col]).map(lambda d, m=mth: d.replace(month=m, day=min(d.day, 28))) \
                .dt.strftime("%Y-%m-%d")
            means[mth] = effect(f).mean(0)
        M = np.vstack(list(means.values()))
        j = int(np.argmax(M.max(0) - M.min(0)))
        lo, hi = int(np.argmin(M[:, j])) + 1, int(np.argmax(M[:, j])) + 1
        what = "yield" if task == "production" else f"{targets[j]} share"
        return (f"Season matters: predicted {what} is lowest in month {lo} and highest in month {hi} "
                f"({M[:, j].max() - M[:, j].min():.2f} {unit} apart).")
    if not pd.api.types.is_numeric_dtype(hold[col]):
        levels = [lv for lv in pd.unique(train[col].dropna())]
        means = {}
        for lv in levels:
            f = hold.copy()
            f[col] = lv
            means[lv] = effect(f).mean(0)
        M = np.vstack(list(means.values()))
        j = int(np.argmax(M.max(0) - M.min(0)))
        lo, hi = levels[int(np.argmin(M[:, j]))], levels[int(np.argmax(M[:, j]))]
        what = "yield" if task == "production" else f"{targets[j]} share"
        return (f"{name.capitalize()} {hi} gives the highest predicted {what}, {lo} the lowest "
                f"({M[:, j].max() - M[:, j].min():.2f} {unit} apart, other inputs unchanged).")
    p25, p75 = np.nanpercentile(train[col], [25, 75])
    if p25 == p75:
        p25, p75 = np.nanmin(train[col]), np.nanmax(train[col])
    lo_f, hi_f = hold.copy(), hold.copy()
    lo_f[col], hi_f[col] = p25, p75
    if col == "meat_kg":   # keep recipe ratios when the batch size changes
        for c in ("spice_kg", "casing_kg", "planned_qty"):
            lo_f[c], hi_f[c] = hold[c] * p25 / hold.meat_kg, hold[c] * p75 / hold.meat_kg
    d = effect(hi_f).mean(0) - effect(lo_f).mean(0)
    j = int(np.argmax(np.abs(d)))
    what = "yield" if task == "production" else f"{targets[j]} share"
    arrow = "higher" if d[j] > 0 else "lower"
    return (f"Higher {name} ({p25:g} -> {p75:g}) -> {arrow} {what} ({d[j]:+.2f} {unit}).")


# ================================================================ stacking audit (from committed OOF files)
def stacking_audit(n_boot: int = N_BOOT) -> dict:
    out = {}
    nuri = HERE / "artifacts" / "nuri"
    if not (nuri / "oof_stack_meta_production.csv").exists():
        return out
    for task, files, models in (
            ("production", ["oof_ali_production.csv", "oof_nuri_production.csv", "oof_stack_meta_production.csv"],
             ["ali_ridge", "nuri_huber", "nuri_stack_meta"]),
            ("butchery", ["oof_ali_butchery.csv", "oof_nuri_butchery.csv", "oof_stack_meta_butchery.csv"],
             ["ali_ridge", "nuri_simplex_ridge", "nuri_stack_meta"])):
        frames = [pd.read_csv((ART if f.startswith("oof_ali") else nuri) / f) for f in files]
        oof = pd.concat(frames)
        te = oof[(oof.split == "test") & oof.model.isin(models)]
        err = te.assign(e=(te.y_true - te.y_pred).abs()).pivot_table(index="row_id", columns="model", values="e")
        err = err.sort_index()                       # row_id order == date order for the generator
        idx = block_bootstrap_idx(len(err), n_boot)
        r = {"n_test_rows": len(err), "MAE_pts": {m: float(err[m].mean()) for m in models}}
        for m in models[:2]:
            r[f"stack_minus_{m}"] = paired_test(err["nuri_stack_meta"].to_numpy(), err[m].to_numpy(), idx)
        out[task] = r
    return out


# ================================================================ charts
INK, MUTED, GRID, BLUE, ORANGE = "#2b2b2a", "#77766f", "#e6e5e0", "#2a78d6", "#eb6834"


def _style(ax):
    ax.spines[["top", "right"]].set_visible(False)
    ax.spines[["left", "bottom"]].set_color(MUTED)
    ax.tick_params(colors=MUTED, labelsize=9)
    ax.grid(axis="y", color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)


def plot_intervals(prod: dict, path: pathlib.Path, n_show: int = 60) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    pool = prod["_pooled"]
    champ = pool["champion"]
    hold = np.where(pool["fold"] == pool["fold"].max())[0][-n_show:]
    hold = hold[np.argsort(pool["pred"][champ][hold], kind="stable")]     # orders are not a series: sort
    y, p = pool["y"][hold], pool["pred"][champ][hold]
    half = np.array([pool["q"][champ][k][INTERVAL_LEVEL] for k in pool["fold"][hold]])
    inside = np.abs(y - p) <= half
    x = np.arange(len(hold))
    fig, ax = plt.subplots(figsize=(9, 3.8), dpi=150)
    ax.fill_between(x, p - half, p + half, color=BLUE, alpha=0.15, linewidth=0, label="80 % interval")
    ax.plot(x, p, color=BLUE, linewidth=2, label=f"prediction ({champ})")
    ax.scatter(x[inside], y[inside], s=16, color=INK, zorder=3, label="actual")
    ax.scatter(x[~inside], y[~inside], s=28, facecolor="white", edgecolor=ORANGE, linewidth=1.6, zorder=4,
               label="actual outside interval")
    _style(ax)
    ax.set_ylabel("yield (%)", color=MUTED, fontsize=9)
    ax.set_xlabel(f"last {len(hold)} production orders of the latest fold, sorted by predicted yield",
                  color=MUTED, fontsize=9)
    ax.set_title(f"Production yield: prediction vs actual, 80 % conformal interval "
                 f"(coverage on this fold {inside.mean():.0%})", loc="left", fontsize=10, color=INK)
    ax.legend(frameon=False, fontsize=8, ncol=2, loc="lower right")
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def plot_learning_curve(lc: dict, path: pathlib.Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.6), dpi=150)
    for ax, (task, unit, title) in zip(axes, (("production", "packages per order", "A: production (packages MAE)"),
                                              ("butchery", "kg per part", "B: butchery (part kg MAE)"))):
        rows = lc[task]
        n = [r["n_train"] for r in rows]
        for key, color, label in (("model", BLUE, "model"), ("baseline", ORANGE, "historical mean")):
            v = np.array([r[key] for r in rows])
            band = np.array([r[f"{key}_ci95"] for r in rows])
            ax.fill_between(n, band[:, 0], band[:, 1], color=color, alpha=0.12, linewidth=0)
            ax.plot(n, v, color=color, linewidth=2, marker="o", markersize=4, label=label)
        ax.set_xscale("log")
        ax.set_xticks(n)
        ax.set_xticklabels([str(v) for v in n])
        ax.minorticks_off()
        _style(ax)
        ax.set_title(title, loc="left", fontsize=10, color=INK)
        ax.set_xlabel("training rows (most recent)", color=MUTED, fontsize=9)
        ax.set_ylabel(f"MAE, {unit}", color=MUTED, fontsize=9)
        ax.set_ylim(bottom=0)
    axes[0].legend(frameon=False, fontsize=8)
    fig.suptitle("Error on the 20 % holdout vs training size (shaded: bootstrap 95 % CI)", x=0.01, ha="left",
                 fontsize=9, color=MUTED)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


# ================================================================ main
def _public(rep: dict) -> dict:
    return {k: v for k, v in rep.items() if not k.startswith("_")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--production", default=str(HERE / "data" / "train_task4a_ali.csv"))
    ap.add_argument("--butchery", default=str(HERE / "data" / "train_task4b_ali.csv"))
    ap.add_argument("--out", default=str(ART / "evaluation.json"))
    ap.add_argument("--charts", default=str(DOCS))
    args = ap.parse_args()

    prod = pd.read_csv(args.production)
    butch = pd.read_csv(args.butchery)
    faults = None
    if pathlib.Path(args.production).resolve() == (HERE / "data" / "train_task4a_ali.csv").resolve():
        # the injected machine faults: same seed and draw order as generator_ali.main()
        import generator_ali as G
        g = G.generate_production(len(prod), np.random.default_rng(42), with_fault_label=True)
        assert np.allclose(g.yield_pct, prod.yield_pct), "generator output differs from the CSV"
        faults = g.set_index("row_id").fault
    rp = evaluate_production(prod, faults=faults)
    rb = evaluate_butchery(butch)
    lc = {"production": learning_curve("production", prod, [25, 50, 100, 200, 400, 800, 1198]),
          "butchery": learning_curve("butchery", butch, [25, 50, 100, 200, 400, 1000, 2000])}
    imp = {"production": permutation_importance("production", prod),
           "butchery": permutation_importance("butchery", butch)}
    report = {"seed": SEED, "interval_level": INTERVAL_LEVEL, "anomaly_level": ANOMALY_LEVEL,
              "production": _public(rp), "butchery": _public(rb), "learning_curve": lc,
              "permutation_importance": imp, "stacking_audit": stacking_audit()}
    pathlib.Path(args.out).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    charts = pathlib.Path(args.charts)
    charts.mkdir(parents=True, exist_ok=True)
    plot_intervals(rp, charts / "production_pred_vs_actual.png")
    plot_learning_curve(lc, charts / "learning_curve.png")
    print(f"champions: production={rp['champion']}  butchery={rb['champion']}  -> {args.out}")


if __name__ == "__main__":
    import evaluate_ali as _module   # keep pickled classes under the module name
    _module.main()
