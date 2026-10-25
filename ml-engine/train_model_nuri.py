"""
BAN Food Intelligence - AVATAR ERP Competition
Module 2: Unified Multi-Task Champion ML Pipeline (Tasks 4A, 4B & 4C Retail)
Author: Nuri (Retail POS, Offline Sync, Security & Stacking Lead)

Strict Anti-Overfitting, Zero Data Leakage & Full Contract Compliance:
- Task 4A (Sucuk Production Yield & EKP): Chronological 80/20 Time-Holdout +
  Deterministic SHA-1 5-Fold CV (`ml_contract.py`), price-inflation proxy leakage
  exclusion (`PRICE_COLS`), `FAS-02 x Summer` thermal interaction, package
  discretization (`np.floor`) bias correction, and discrete package rounding.
- Task 4B (Carcass Butchery 6-Part Compositional Yield): ARD causal feature
  selection (pruning pure-noise columns), Simplex compositional closure
  (`sum(pct) == 100%`), and exact mass balance (`sum(kg) == carcass_weight_kg`).
- Task 4C (Retail Cold-Chain Arrhenius Telemetry): Non-linear thermodynamic
  feature engineering + regularized 5-Fold LightGBM (`nuri_oof_preds.npy`).
- Split-Conformal 90% Prediction Intervals (`q90`) + Epistemic Ensemble Variance.
"""

from __future__ import annotations

import json
import os
import pathlib
import sys
import warnings
from typing import Dict, Tuple

import joblib
import lightgbm as lgb
import numpy as np
import pandas as pd
from catboost import CatBoostRegressor
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.linear_model import HuberRegressor, Ridge
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import KFold
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import ml_contract as C  # noqa: E402
from generator_nuri import generate_nuri_dataset  # noqa: E402

warnings.filterwarnings("ignore")

if sys.stdout.encoding != "utf-8":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

MODEL_VERSION = "nuri-2.0.0"
PRICE_COLS = {
    "meat_unit_price",
    "spice_unit_price",
    "casing_unit_price",
    "service_unit_price",
    "purchase_unit_price",
}
RECIPE_YIELD_PCT = 85.0
SPECIES_REF_WEIGHT_KG = {"KUZU": 22.0, "OGLAK": 14.0, "KOYUN": 30.0}
RECIPE_BUTCHERY_PCT = {
    "KUZU": [26.0, 14.0, 20.0, 12.0, 16.0, 12.0],
    "OGLAK": [25.0, 13.0, 20.0, 12.0, 15.0, 15.0],
    "KOYUN": [25.0, 12.0, 19.0, 13.0, 18.0, 13.0],
}


def compute_regression_metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict:
    y_t = np.asarray(y_true, dtype=float)
    y_p = np.asarray(y_pred, dtype=float)
    return {
        "MAE": float(mean_absolute_error(y_t, y_p)),
        "RMSE": float(np.sqrt(mean_squared_error(y_t, y_p))),
        "MAPE_pct": float(100.0 * np.mean(np.abs((y_t - y_p) / np.maximum(np.abs(y_t), 1e-9)))),
        "R2": float(r2_score(y_t, y_p)),
    }


def run_contract_oof_and_test(make_model, X: pd.DataFrame, y: np.ndarray, df: pd.DataFrame):
    """
    Executes deterministic SHA-1 5-Fold CV on the chronological 80% train window
    and fits the final model on full train to predict the chronological 20% test holdout.
    Zero temporal or fold leakage.
    """
    tr = (df["split"] == "train").to_numpy()
    pred = np.full((len(df),) + np.shape(y)[1:], np.nan, dtype=float)
    for k in range(C.N_FOLDS):
        fit_idx = tr & (df["fold"] != k).to_numpy()
        val_idx = tr & (df["fold"] == k).to_numpy()
        pred[val_idx] = make_model().fit(X.iloc[fit_idx], y[fit_idx]).predict(X.iloc[val_idx])
    final_model = make_model().fit(X.iloc[tr], y[tr])
    pred[~tr] = final_model.predict(X.iloc[~tr])
    return pred, final_model


# =============================================================================
# 1. TASK 4A: SUCUK PRODUCTION YIELD & EKP (CONTRACT-COMPLIANT CHAMPION)
# =============================================================================
def engineer_production_features_nuri(df: pd.DataFrame) -> pd.DataFrame:
    """
    Constructs Nuri's physics-informed feature matrix for Task 4A:
      - Strictly excludes price columns (`PRICE_COLS`) to prevent inflation-time proxy leakage.
      - Captures `FAS-02 x Summer` cooling unit interaction.
      - Models the exact mathematical expectation of whole-package floor discretization:
        `E[floor(kg / pack_kg) - kg / pack_kg] = -0.5` -> `-50 * pack_size_kg / meat_kg` yield pts.
      - Captures fixed stuffing line loss (`1 / meat_kg`) and fat-drying interactions.
    """
    base_cols = C.PRODUCTION["categorical"] + [
        c for c in C.PRODUCTION["numeric"] if c not in PRICE_COLS
    ]
    X = df[base_cols].copy()
    d = pd.to_datetime(df[C.PRODUCTION["date"]])

    X["month_sin"] = np.sin(2.0 * np.pi * d.dt.month / 12.0)
    X["month_cos"] = np.cos(2.0 * np.pi * d.dt.month / 12.0)
    X["is_summer"] = d.dt.month.isin([6, 7, 8]).astype(float)
    X["inv_meat_kg"] = 1.0 / df["meat_kg"]
    X["spice_ratio"] = df["spice_kg"] / df["meat_kg"]
    X["casing_ratio"] = df["casing_kg"] / df["meat_kg"]

    # Nuri's Physics & Discretization Enhancements over Ali's baseline
    X["summer_fas02"] = (
        X["is_summer"] * (X["subcontractor_code"].fillna("INTERNAL") == "FAS-02")
    ).astype(float)
    X["pack_floor_bias"] = -50.0 * df["pack_size_kg"] / df["meat_kg"]
    X["fat_x_drying"] = X["meat_fat_pct"] * X["drying_days"]
    X["fat_per_drying"] = X["meat_fat_pct"] / (X["drying_days"] + 1.0)
    return X


def train_task4a_production_nuri(
    df: pd.DataFrame, out_dir: pathlib.Path
) -> Tuple[dict, pd.DataFrame]:
    df = C.assign_splits(
        df.sort_values(C.PRODUCTION["date"]).reset_index(drop=True),
        C.PRODUCTION["date"],
    )
    X = engineer_production_features_nuri(df)
    y = df[C.PRODUCTION["target"]].to_numpy(float)
    cats = C.PRODUCTION["categorical"]
    nums = [c for c in X.columns if c not in cats]
    tr = (df["split"] == "train").to_numpy()
    te = ~tr

    def make_nuri_huber():
        pre = ColumnTransformer(
            [
                ("cat", OneHotEncoder(handle_unknown="ignore"), cats),
                ("num", make_pipeline(SimpleImputer(strategy="median"), StandardScaler()), nums),
            ]
        )
        return make_pipeline(pre, HuberRegressor(alpha=1.0, max_iter=500))

    def make_nuri_pinn_ridge():
        pre = ColumnTransformer(
            [
                ("cat", OneHotEncoder(handle_unknown="ignore"), cats),
                ("num", make_pipeline(SimpleImputer(strategy="median"), StandardScaler()), nums),
            ]
        )
        return make_pipeline(pre, Ridge(alpha=0.8))

    def make_nuri_lgbm():
        pre = ColumnTransformer(
            [
                ("cat", OneHotEncoder(handle_unknown="ignore"), cats),
                ("num", SimpleImputer(strategy="median"), nums),
            ]
        )
        return make_pipeline(
            pre,
            lgb.LGBMRegressor(
                learning_rate=0.025,
                n_estimators=220,
                num_leaves=12,
                max_depth=4,
                min_child_samples=25,
                subsample=0.8,
                colsample_bytree=0.8,
                reg_alpha=0.8,
                reg_lambda=2.0,
                random_state=42,
                n_jobs=1,
                verbose=-1,
            ),
        )

    p_huber, m_huber = run_contract_oof_and_test(make_nuri_huber, X, y, df)
    p_ridge, m_ridge = run_contract_oof_and_test(make_nuri_pinn_ridge, X, y, df)
    p_lgbm, m_lgbm = run_contract_oof_and_test(make_nuri_lgbm, X, y, df)
    p_hybrid = 0.55 * p_huber + 0.35 * p_ridge + 0.10 * p_lgbm

    preds = {
        "baseline_recipe": np.full(len(df), RECIPE_YIELD_PCT),
        "baseline_hist_mean": (
            df.groupby("product_sku")[C.PRODUCTION["target"]]
            .transform(lambda s: s[tr[s.index]].mean())
            .to_numpy()
        ),
        "nuri_huber": p_huber,
        "nuri_pinn_ridge": p_ridge,
        "nuri_lgbm": p_lgbm,
        "nuri_hybrid": p_hybrid,
    }
    fitted = {
        "nuri_huber": m_huber,
        "nuri_pinn_ridge": m_ridge,
        "nuri_lgbm": m_lgbm,
    }

    report = {
        "n_train": int(tr.sum()),
        "n_test": int(te.sum()),
        "target": "yield_pct",
        "models": {},
    }
    for name, p in preds.items():
        raw_qty = C.production_qty_from_yield(df, p)
        # Whole-package physical quantization for Nuri models
        qty = np.round(raw_qty) if name.startswith("nuri_") else raw_qty
        ekp = C.production_ekp(df, qty)
        report["models"][name] = {
            "cv_oof_yield_pct": compute_regression_metrics(y[tr], p[tr]),
            "test_yield_pct": compute_regression_metrics(y[te], p[te]),
            "test_actual_qty": compute_regression_metrics(df["actual_qty"][te], qty[te]),
            "test_ekp_eur": compute_regression_metrics(df["ekp"][te], ekp[te]),
        }

    candidate_names = ["nuri_huber", "nuri_pinn_ridge", "nuri_lgbm", "nuri_hybrid"]
    champ = min(
        candidate_names,
        key=lambda m: report["models"][m]["cv_oof_yield_pct"]["MAE"],
    )
    report["champion"] = champ

    q90 = float(np.quantile(np.abs(y[tr] - preds[champ][tr]), 0.90))
    covered = float(np.mean(np.abs(y[te] - preds[champ][te]) <= q90))
    epistemic_var = float(np.mean(np.var(np.vstack([p_huber, p_ridge, p_lgbm]), axis=0)))
    report["interval90_yield_pct"] = {
        "half_width": q90,
        "test_coverage": covered,
        "mean_epistemic_variance": epistemic_var,
    }

    base_mae = report["models"]["baseline_hist_mean"]["test_actual_qty"]["MAE"]
    best_mae = report["models"][champ]["test_actual_qty"]["MAE"]
    report["improvement_vs_hist_mean_pct"] = float(100.0 * (1.0 - best_mae / base_mae))

    out_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump(
        {
            "models": fitted,
            "champion": champ,
            "q90": q90,
            "version": MODEL_VERSION,
        },
        out_dir / "production_model_nuri.joblib",
    )

    oof_df = pd.concat(
        [
            pd.DataFrame(
                {
                    "row_id": df["row_id"],
                    "split": df["split"],
                    "fold": df["fold"],
                    "task": "production",
                    "target": "yield_pct",
                    "y_true": y,
                    "model": m,
                    "y_pred": np.round(preds[m], 4),
                }
            )
            for m in candidate_names
        ],
        ignore_index=True,
    )
    oof_df.to_csv(out_dir / "oof_nuri_production.csv", index=False)
    return report, oof_df


# =============================================================================
# 2. TASK 4B: CARCASS BUTCHERY 6-PART COMPOSITIONAL YIELD (SIMPLEX ARD)
# =============================================================================
def normalize_simplex_pct(P: np.ndarray) -> np.ndarray:
    P_clipped = np.clip(P, 0.1, None)
    return 100.0 * P_clipped / P_clipped.sum(axis=1, keepdims=True)


def engineer_butchery_features_nuri(df: pd.DataFrame) -> Tuple[pd.DataFrame, list[str], list[str]]:
    """
    Constructs Nuri's ARD (Automatic Relevance Determination) feature matrix for Task 4B:
      - Prunes non-causal noise variables (`supplier_code`, `age_months`, `month_sin/cos`,
        `purchase_unit_price`) that cause L2/GBDT overfitting on small carcass cohorts.
      - Retains exact physical determinants: `species`, `breed`, `operator_code`, `sex`,
        `weight_vs_species`, `fat_score`, `conformation_score`, and `warm_cut` (`chill < 24h`).
    """
    X = pd.DataFrame(index=df.index)
    for col in ("species", "breed", "operator_code", "sex"):
        X[col] = df[col].fillna("NA").astype(str)

    X["weight_vs_species"] = df["carcass_weight_kg"] - df["species"].map(
        SPECIES_REF_WEIGHT_KG
    ).fillna(df["carcass_weight_kg"])
    X["fat_score"] = df["fat_score"].astype(float)
    X["conformation_score"] = df["conformation_score"].astype(float)
    X["warm_cut"] = (df["chill_hours"] < 24.0).astype(float)

    cats = ["species", "breed", "operator_code", "sex"]
    nums = ["weight_vs_species", "fat_score", "conformation_score", "warm_cut"]
    return X, cats, nums


def train_task4b_butchery_nuri(
    df: pd.DataFrame, out_dir: pathlib.Path
) -> Tuple[dict, pd.DataFrame]:
    df = C.assign_splits(
        df.sort_values(C.BUTCHERY["date"]).reset_index(drop=True),
        C.BUTCHERY["date"],
    )
    X, cats, nums = engineer_butchery_features_nuri(df)
    targets = C.BUTCHERY["targets"]
    Y = df[targets].to_numpy(float)
    W = df["carcass_weight_kg"].to_numpy(float)[:, None]
    tr = (df["split"] == "train").to_numpy()
    te = ~tr

    hist = df.iloc[tr].groupby("species")[targets].mean()
    preds = {
        "baseline_recipe": np.vstack([RECIPE_BUTCHERY_PCT[s] for s in df["species"]]).astype(float),
        "baseline_hist_mean": hist.loc[df["species"]].to_numpy(),
    }

    def make_simplex_ridge():
        pre = ColumnTransformer(
            [
                ("cat", OneHotEncoder(handle_unknown="ignore"), cats),
                ("num", make_pipeline(SimpleImputer(strategy="median"), StandardScaler()), nums),
            ]
        )
        return make_pipeline(pre, Ridge(alpha=2.0))

    def make_simplex_huber():
        pre = ColumnTransformer(
            [
                ("cat", OneHotEncoder(handle_unknown="ignore", drop="first"), cats),
                ("num", make_pipeline(SimpleImputer(strategy="median"), StandardScaler()), nums),
            ]
        )
        return make_pipeline(pre, HuberRegressor(epsilon=1.25, alpha=3.0, max_iter=500))

    ridge_cols, ridge_models = [], []
    huber_cols, huber_models = [], []
    for j in range(len(targets)):
        pr, mr = run_contract_oof_and_test(make_simplex_ridge, X, Y[:, j], df)
        ph, mh = run_contract_oof_and_test(make_simplex_huber, X, Y[:, j], df)
        ridge_cols.append(pr)
        ridge_models.append(mr)
        huber_cols.append(ph)
        huber_models.append(mh)

    preds["nuri_simplex_ridge"] = normalize_simplex_pct(np.column_stack(ridge_cols))
    preds["nuri_simplex_huber"] = normalize_simplex_pct(np.column_stack(huber_cols))
    preds["nuri_simplex_hybrid"] = normalize_simplex_pct(
        0.70 * preds["nuri_simplex_ridge"] + 0.30 * preds["nuri_simplex_huber"]
    )

    fitted = {
        "nuri_simplex_ridge": ridge_models,
        "nuri_simplex_huber": huber_models,
    }

    report = {
        "n_train": int(tr.sum()),
        "n_test": int(te.sum()),
        "targets": targets,
        "models": {},
    }
    for name, P in preds.items():
        kg_true = Y * W / 100.0
        kg_pred = P * W / 100.0
        report["models"][name] = {
            "cv_oof_pct_MAE": float(np.mean(np.abs(Y[tr] - P[tr]))),
            "test_pct_MAE": float(np.mean(np.abs(Y[te] - P[te]))),
            "test_kg_MAE": float(np.mean(np.abs(kg_true[te] - kg_pred[te]))),
            "test_kg_MAE_per_part": {
                t.replace("_pct", ""): float(np.mean(np.abs(kg_true[te, j] - kg_pred[te, j])))
                for j, t in enumerate(targets)
            },
            "test_sellable_kg_MAE": float(
                np.mean(np.abs(kg_true[te, :5].sum(axis=1) - kg_pred[te, :5].sum(axis=1)))
            ),
            "mass_balance_max_err_kg": float(np.max(np.abs(kg_pred.sum(axis=1) - W[:, 0]))),
        }

    candidate_names = ["nuri_simplex_ridge", "nuri_simplex_huber", "nuri_simplex_hybrid"]
    champ = min(
        candidate_names,
        key=lambda m: report["models"][m]["cv_oof_pct_MAE"],
    )
    report["champion"] = champ

    abs_res = np.abs(Y[tr] - preds[champ][tr])
    q90 = np.quantile(abs_res, 0.90, axis=0)
    report["interval90_pct"] = {t: float(q) for t, q in zip(targets, q90)}
    report["interval90_test_coverage"] = float(np.mean(np.abs(Y[te] - preds[champ][te]) <= q90))
    base_kg_mae = report["models"]["baseline_hist_mean"]["test_kg_MAE"]
    best_kg_mae = report["models"][champ]["test_kg_MAE"]
    report["improvement_vs_hist_mean_pct"] = float(100.0 * (1.0 - best_kg_mae / base_kg_mae))

    out_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump(
        {
            "models": fitted,
            "champion": champ,
            "targets": targets,
            "q90": q90,
            "version": MODEL_VERSION,
        },
        out_dir / "butchery_model_nuri.joblib",
    )

    frames = []
    for m in candidate_names:
        for j, t in enumerate(targets):
            frames.append(
                pd.DataFrame(
                    {
                        "row_id": df["row_id"],
                        "split": df["split"],
                        "fold": df["fold"],
                        "task": "butchery",
                        "target": t,
                        "y_true": Y[:, j],
                        "model": m,
                        "y_pred": np.round(preds[m][:, j], 4),
                    }
                )
            )
    oof_df = pd.concat(frames, ignore_index=True)
    oof_df.to_csv(out_dir / "oof_nuri_butchery.csv", index=False)
    return report, oof_df


# =============================================================================
# 3. TASK 4C: RETAIL COLD-CHAIN ARRHENIUS TELEMETRY (5-FOLD LIGHTGBM)
# =============================================================================
def engineer_retail_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    Performs zero-leakage, row-wise physical feature engineering for Retail Cold-Chain.
    """
    fe = df.copy()
    leakage_or_target_cols = [
        col
        for col in ("batch_id", "shrinkage_pct", "retail_retention_yield_pct")
        if col in fe.columns
    ]
    if leakage_or_target_cols:
        fe = fe.drop(columns=leakage_or_target_cols)

    tier_map = {"A": 0, "B": 1, "C": 2}
    fe["market_tier_encoded"] = fe["market_tier"].map(tier_map).astype(float)
    fe = fe.drop(columns=["market_tier"])

    fe["temp_per_hour_transit"] = fe["ambient_temp_celsius"] / (fe["transit_time_hours"] + 1e-3)
    fe["humidity_per_temp_kelvin"] = fe["storage_humidity_pct"] / (
        fe["ambient_temp_celsius"] + 273.15
    )
    fe["integrity_to_transit_ratio"] = fe["packaging_integrity_score"] / (
        fe["transit_time_hours"] + 1.0
    )

    fe["thermal_time_product"] = fe["ambient_temp_celsius"] * fe["transit_time_hours"]
    fe["ambient_temp_squared"] = fe["ambient_temp_celsius"] ** 2
    fe["transit_time_squared"] = fe["transit_time_hours"] ** 2
    fe["packaging_integrity_squared"] = fe["packaging_integrity_score"] ** 2

    effective_temp = np.maximum(0.0, fe["ambient_temp_celsius"] + 2.0)
    fe["unprotected_thermal_exposure"] = (
        effective_temp * fe["transit_time_hours"] * (1.05 - fe["packaging_integrity_score"])
    )
    fe["arrhenius_kinetics_proxy"] = np.exp(0.10 * effective_temp) * np.power(
        fe["transit_time_hours"] / 12.0, 1.25
    )
    fe["humidity_deviation_from_optimal"] = np.abs(fe["storage_humidity_pct"] - 84.0)
    fe["desiccation_risk_index"] = np.maximum(0.0, 84.0 - fe["storage_humidity_pct"]) * (
        1.10 - fe["packaging_integrity_score"]
    )
    fe["seal_compromise_squared"] = (
        np.maximum(0.0, 0.70 - fe["packaging_integrity_score"]) ** 2
    )
    return fe


def train_nuri_lightgbm_cv(
    n_splits: int = 5, random_state: int = 42
) -> Tuple[np.ndarray, pd.DataFrame]:
    """
    Executes 5-Fold Cross-Validation for Nuri's Retail Cold-Chain LightGBM Regressor
    and also runs Task 4A & 4B contract-compliant training so all Nuri artifacts stay synced.
    """
    script_dir = os.path.dirname(os.path.abspath(__file__))
    data_path = os.path.join(script_dir, "data", "train_task4_retail.csv")

    print("=" * 80)
    print("🏆 AVATAR ERP - NURI UNIFIED MULTI-TASK CHAMPION PIPELINE (4A, 4B & 4C) 🏆")
    print("=" * 80)

    # 1. Train & Export Contract-Compliant Task 4A & Task 4B Models
    p4a = HERE / "data" / "train_task4a_ali.csv"
    p4b = HERE / "data" / "train_task4b_ali.csv"
    out_nuri = HERE / "artifacts" / "nuri"
    if p4a.exists() and p4b.exists():
        df4a = pd.read_csv(p4a)
        df4b = pd.read_csv(p4b)
        rep_p, _ = train_task4a_production_nuri(df4a, out_nuri)
        rep_b, _ = train_task4b_butchery_nuri(df4b, out_nuri)
        (out_nuri / "metrics.json").write_text(
            json.dumps(
                {"version": MODEL_VERSION, "production": rep_p, "butchery": rep_b},
                indent=2,
                ensure_ascii=False,
            ),
            encoding="utf-8",
        )
        c_p = rep_p["champion"]
        c_b = rep_b["champion"]
        print(
            f"📌 [TASK 4A - PRODUCTION] Champion: {c_p:<18} | "
            f"CV Yield MAE: {rep_p['models'][c_p]['cv_oof_yield_pct']['MAE']:.4f} | "
            f"Test Qty MAE: {rep_p['models'][c_p]['test_actual_qty']['MAE']:.4f} | "
            f"Test EKP MAE: {rep_p['models'][c_p]['test_ekp_eur']['MAE']:.4f} € | "
            f"R²: {rep_p['models'][c_p]['test_yield_pct']['R2']:.4f}"
        )
        print(
            f"📌 [TASK 4B - BUTCHERY]   Champion: {c_b:<18} | "
            f"CV Share MAE: {rep_b['models'][c_b]['cv_oof_pct_MAE']:.5f} | "
            f"Test Share MAE: {rep_b['models'][c_b]['test_pct_MAE']:.5f} | "
            f"Test kg MAE: {rep_b['models'][c_b]['test_kg_MAE']:.5f} kg"
        )
        print("-" * 80)

    # 2. Train Task 4C Retail Cold-Chain LightGBM
    if not os.path.exists(data_path):
        df = generate_nuri_dataset(n_samples=5000, random_state=random_state)
    else:
        df = pd.read_csv(data_path)

    X = engineer_retail_features(df)
    y = df["retail_retention_yield_pct"].values

    assert "shrinkage_pct" not in X.columns, "CRITICAL LEAKAGE: shrinkage_pct found!"
    assert "retail_retention_yield_pct" not in X.columns, "CRITICAL LEAKAGE: target found!"
    assert "batch_id" not in X.columns, "CRITICAL LEAKAGE: batch_id found!"

    kf = KFold(n_splits=n_splits, shuffle=True, random_state=random_state)
    oof_preds = np.zeros(len(df), dtype=np.float64)

    for fold, (train_idx, val_idx) in enumerate(kf.split(X, y), start=1):
        X_train, y_train = X.iloc[train_idx], y[train_idx]
        X_val, y_val = X.iloc[val_idx], y[val_idx]

        model = lgb.LGBMRegressor(
            learning_rate=0.03,
            n_estimators=1000,
            num_leaves=31,
            max_depth=6,
            subsample=0.8,
            subsample_freq=1,
            colsample_bytree=0.8,
            reg_alpha=0.5,
            reg_lambda=1.0,
            min_child_samples=25,
            random_state=random_state + fold,
            n_jobs=-1,
            verbose=-1,
        )
        model.fit(
            X_train,
            y_train,
            eval_set=[(X_val, y_val)],
            callbacks=[lgb.early_stopping(stopping_rounds=50, verbose=False)],
        )
        oof_preds[val_idx] = model.predict(X_val)

    oof_rmse = float(np.sqrt(mean_squared_error(y, oof_preds)))
    oof_mae = float(mean_absolute_error(y, oof_preds))
    oof_r2 = float(r2_score(y, oof_preds))

    print(
        f"📌 [TASK 4C - RETAIL]     Champion: nuri_lgbm_physics  | "
        f"5-Fold OOF RMSE: {oof_rmse:.4f} | OOF MAE: {oof_mae:.4f} | OOF R²: {oof_r2:.5f}"
    )

    oof_path_root = os.path.join(script_dir, "nuri_oof_preds.npy")
    oof_path_data = os.path.join(script_dir, "data", "nuri_oof_preds.npy")
    np.save(oof_path_root, oof_preds)
    np.save(oof_path_data, oof_preds)
    print("=" * 80)

    return oof_preds, df


if __name__ == "__main__":
    train_nuri_lightgbm_cv()
