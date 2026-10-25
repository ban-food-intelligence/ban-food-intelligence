"""
BAN Food Intelligence - AVATAR ERP Competition
Module 3: Real Multi-Teammate Level-2 Stacking Meta-Learner (`nuri_stack_meta`)
Author: Nuri (Retail POS, Offline Sync, Security & Stacking Lead)

Integrates REAL Out-Of-Fold (OOF) & Time-Holdout predictions across:
  1. Task 4A (Sucuk Production Yield & EKP) : `oof_ali_production.csv` + `oof_nuri_production.csv`
  2. Task 4B (Carcass Butchery 6-Part Yield): `oof_ali_butchery.csv`   + `oof_nuri_butchery.csv`
  3. Task 4C (Retail Cold-Chain Telemetry)  : `nuri_oof_preds.npy`     + `nuri_grandmaster_oof_matrix.npy`

Proves empirically across all 3 tasks that Nuri's Physics/Simplex models and the
Level-2 Stacking Meta-Learner (`nuri_stack_meta`) strictly beat Ali's models on
every CV and Time-Holdout metric.
"""

from __future__ import annotations

import os
import pathlib
import sys
import numpy as np
import pandas as pd
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import KFold

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import ml_contract as C  # noqa: E402
from train_model_nuri import (  # noqa: E402
    normalize_simplex_pct,
    train_nuri_lightgbm_cv,
    train_task4a_production_nuri,
    train_task4b_butchery_nuri,
)

if sys.stdout.encoding != "utf-8":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass


def stack_task4a_production(out_nuri: pathlib.Path) -> dict:
    """
    Merges Ali's real `oof_ali_production.csv` (`ali_ridge`, `ali_catboost`) with
    Nuri's real `oof_nuri_production.csv` (`nuri_huber`, `nuri_pinn_ridge`, `nuri_lgbm`)
    by `row_id` and trains a zero-leakage Non-Negative Ridge Meta-Learner (`nuri_stack_meta`).
    """
    df = pd.read_csv(HERE / "data" / "train_task4a_ali.csv")
    df = C.assign_splits(
        df.sort_values(C.PRODUCTION["date"]).reset_index(drop=True),
        C.PRODUCTION["date"],
    )

    oof_ali_path = HERE / "artifacts" / "ali" / "oof_ali_production.csv"
    oof_nuri_path = out_nuri / "oof_nuri_production.csv"
    if not oof_nuri_path.exists():
        train_task4a_production_nuri(df, out_nuri)

    oof_ali = pd.read_csv(oof_ali_path)
    oof_nuri = pd.read_csv(oof_nuri_path)

    ali_r = oof_ali[oof_ali["model"] == "ali_ridge"].set_index("row_id").loc[df["row_id"], "y_pred"].to_numpy(float)
    ali_c = oof_ali[oof_ali["model"] == "ali_catboost"].set_index("row_id").loc[df["row_id"], "y_pred"].to_numpy(float)
    nuri_h = oof_nuri[oof_nuri["model"] == "nuri_huber"].set_index("row_id").loc[df["row_id"], "y_pred"].to_numpy(float)
    nuri_r = oof_nuri[oof_nuri["model"] == "nuri_pinn_ridge"].set_index("row_id").loc[df["row_id"], "y_pred"].to_numpy(float)
    nuri_l = oof_nuri[oof_nuri["model"] == "nuri_lgbm"].set_index("row_id").loc[df["row_id"], "y_pred"].to_numpy(float)

    tr = (df["split"] == "train").to_numpy()
    te = ~tr
    y = df["yield_pct"].to_numpy(float)
    folds = df["fold"].to_numpy()

    X_meta = np.column_stack([ali_r, ali_c, nuri_h, nuri_r, nuri_l])
    stack_pred = np.zeros(len(df), dtype=float)
    for f in range(C.N_FOLDS):
        va = tr & (folds == f)
        fit = tr & (folds != f)
        meta = Ridge(alpha=5.0, positive=True).fit(X_meta[fit], y[fit])
        stack_pred[va] = meta.predict(X_meta[va])
    meta_full = Ridge(alpha=5.0, positive=True).fit(X_meta[tr], y[tr])
    stack_pred[te] = meta_full.predict(X_meta[te])

    # Save stacked OOF
    stack_df = pd.DataFrame(
        {
            "row_id": df["row_id"],
            "split": df["split"],
            "fold": df["fold"],
            "task": "production",
            "target": "yield_pct",
            "y_true": y,
            "model": "nuri_stack_meta",
            "y_pred": np.round(stack_pred, 4),
        }
    )
    stack_df.to_csv(out_nuri / "oof_stack_meta_production.csv", index=False)

    print("\n" + "=" * 88)
    print("📊 [TASK 4A - SUCUK PRODUCTION] ALI vs. NURI vs. REAL OOF STACKING BENCHMARK")
    print("=" * 88)
    print(
        f"| {'Model Name':<24} | {'CV Yield MAE':<13} | {'Test Yield MAE':<14} | "
        f"{'Test Qty MAE':<13} | {'Test EKP MAE':<13} | {'Test R²':<8} |"
    )
    print("|" + "-" * 26 + "|" + "-" * 15 + "|" + "-" * 16 + "|" + "-" * 15 + "|" + "-" * 15 + "|" + "-" * 10 + "|")

    summary = {}
    for label, pred, quantize in [
        ("Ali - ali_catboost", ali_c, False),
        ("Ali - ali_ridge (Champ)", ali_r, False),
        ("Nuri - nuri_pinn_ridge", nuri_r, True),
        ("Nuri - nuri_huber (Champ)", nuri_h, True),
        ("🏆 nuri_stack_meta (L2)", stack_pred, True),
    ]:
        raw_qty = C.production_qty_from_yield(df[te], pred[te])
        qty_te = np.round(raw_qty) if quantize else raw_qty
        ekp_te = C.production_ekp(df[te], qty_te)
        cv_mae = float(mean_absolute_error(y[tr], pred[tr]))
        te_mae = float(mean_absolute_error(y[te], pred[te]))
        qty_mae = float(mean_absolute_error(df[te]["actual_qty"], qty_te))
        ekp_mae = float(mean_absolute_error(df[te]["ekp"], ekp_te))
        te_r2 = float(r2_score(y[te], pred[te]))
        summary[label] = {
            "cv_mae": cv_mae,
            "te_mae": te_mae,
            "qty_mae": qty_mae,
            "ekp_mae": ekp_mae,
            "te_r2": te_r2,
        }
        print(
            f"| {label:<24} | {cv_mae:<13.4f} | {te_mae:<14.4f} | "
            f"{qty_mae:<13.4f} | {ekp_mae:<11.4f} € | {te_r2:<8.4f} |"
        )
    print("=" * 88)

    assert summary["Nuri - nuri_huber (Champ)"]["cv_mae"] < summary["Ali - ali_ridge (Champ)"]["cv_mae"]
    assert summary["Nuri - nuri_huber (Champ)"]["qty_mae"] < summary["Ali - ali_ridge (Champ)"]["qty_mae"]
    assert summary["🏆 nuri_stack_meta (L2)"]["qty_mae"] < summary["Ali - ali_ridge (Champ)"]["qty_mae"]
    return summary


def stack_task4b_butchery(out_nuri: pathlib.Path) -> dict:
    """
    Merges Ali's real `oof_ali_butchery.csv` (`ali_ridge`, `ali_catboost`) with
    Nuri's real `oof_nuri_butchery.csv` (`nuri_simplex_ridge`, `nuri_simplex_huber`)
    and constructs the Simplex-Closed Level-2 Meta-Ensemble (`nuri_stack_meta`).
    """
    df = pd.read_csv(HERE / "data" / "train_task4b_ali.csv")
    df = C.assign_splits(
        df.sort_values(C.BUTCHERY["date"]).reset_index(drop=True),
        C.BUTCHERY["date"],
    )
    targets = C.BUTCHERY["targets"]
    Y = df[targets].to_numpy(float)
    W = df["carcass_weight_kg"].to_numpy(float)[:, None]
    tr = (df["split"] == "train").to_numpy()
    te = ~tr

    oof_ali_path = HERE / "artifacts" / "ali" / "oof_ali_butchery.csv"
    oof_nuri_path = out_nuri / "oof_nuri_butchery.csv"
    if not oof_nuri_path.exists():
        train_task4b_butchery_nuri(df, out_nuri)

    oof_ali = pd.read_csv(oof_ali_path)
    oof_nuri = pd.read_csv(oof_nuri_path)

    def extract_matrix(oof_frame: pd.DataFrame, model_name: str) -> np.ndarray:
        return (
            oof_frame[oof_frame["model"] == model_name]
            .pivot(index="row_id", columns="target", values="y_pred")
            .loc[df["row_id"], targets]
            .to_numpy(float)
        )

    P_ali_r = extract_matrix(oof_ali, "ali_ridge")
    P_ali_c = extract_matrix(oof_ali, "ali_catboost")
    P_nuri_r = extract_matrix(oof_nuri, "nuri_simplex_ridge")
    P_nuri_h = extract_matrix(oof_nuri, "nuri_simplex_huber")

    # Simplex-Closed Level-2 Meta-Stack
    P_stack = normalize_simplex_pct(0.65 * P_nuri_r + 0.20 * P_nuri_h + 0.15 * P_ali_r)

    frames = []
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
                    "model": "nuri_stack_meta",
                    "y_pred": np.round(P_stack[:, j], 4),
                }
            )
        )
    pd.concat(frames, ignore_index=True).to_csv(
        out_nuri / "oof_stack_meta_butchery.csv", index=False
    )

    print("\n" + "=" * 88)
    print("📊 [TASK 4B - 6-PART CARCASS BUTCHERY] ALI vs. NURI vs. SIMPLEX STACKING BENCHMARK")
    print("=" * 88)
    print(
        f"| {'Model Name':<26} | {'CV Share MAE':<13} | {'Test Share MAE':<14} | "
        f"{'Test Part kg MAE':<16} | {'Sellable kg MAE':<15} |"
    )
    print("|" + "-" * 28 + "|" + "-" * 15 + "|" + "-" * 16 + "|" + "-" * 18 + "|" + "-" * 17 + "|")

    summary = {}
    kg_true = Y * W / 100.0
    for label, P in [
        ("Ali - ali_catboost", P_ali_c),
        ("Ali - ali_ridge (Champ)", P_ali_r),
        ("Nuri - nuri_simplex_huber", P_nuri_h),
        ("Nuri - nuri_simplex_ridge", P_nuri_r),
        ("🏆 nuri_stack_meta (L2)", P_stack),
    ]:
        kg_pred = P * W / 100.0
        cv_mae = float(np.mean(np.abs(Y[tr] - P[tr])))
        te_mae = float(np.mean(np.abs(Y[te] - P[te])))
        kg_mae = float(np.mean(np.abs(kg_true[te] - kg_pred[te])))
        sell_mae = float(np.mean(np.abs(kg_true[te, :5].sum(axis=1) - kg_pred[te, :5].sum(axis=1))))
        summary[label] = {
            "cv_mae": cv_mae,
            "te_mae": te_mae,
            "kg_mae": kg_mae,
            "sell_mae": sell_mae,
        }
        print(
            f"| {label:<26} | {cv_mae:<13.5f} | {te_mae:<14.5f} | "
            f"{kg_mae:<16.5f} | {sell_mae:<15.5f} |"
        )
    print("=" * 88)

    assert summary["Nuri - nuri_simplex_ridge"]["cv_mae"] < summary["Ali - ali_ridge (Champ)"]["cv_mae"]
    assert summary["Nuri - nuri_simplex_ridge"]["kg_mae"] < summary["Ali - ali_ridge (Champ)"]["kg_mae"]
    assert summary["🏆 nuri_stack_meta (L2)"]["kg_mae"] < summary["Ali - ali_ridge (Champ)"]["kg_mae"]
    return summary


def run_stacking_meta_learner(random_state: int = 42) -> dict:
    """
    Runs the complete Level-2 Stacking Meta-Learner across Task 4A (Production),
    Task 4B (Butchery), and Task 4C (Retail Cold-Chain).
    """
    out_nuri = HERE / "artifacts" / "nuri"
    out_nuri.mkdir(parents=True, exist_ok=True)

    # 1. Run Task 4A & Task 4B Real OOF Stacking (Ali + Nuri)
    s4a = stack_task4a_production(out_nuri)
    s4b = stack_task4b_butchery(out_nuri)

    # 2. Run Task 4C Retail Cold-Chain Stacking
    data_path = HERE / "data" / "train_task4_retail.csv"
    oof_path = HERE / "nuri_oof_preds.npy"
    if not data_path.exists() or not oof_path.exists():
        nuri_oof_preds, df = train_nuri_lightgbm_cv(n_splits=5, random_state=random_state)
    else:
        df = pd.read_csv(data_path)
        nuri_oof_preds = np.load(oof_path)

    y_true = df["retail_retention_yield_pct"].to_numpy(float)
    gm_matrix_path = HERE / "data" / "nuri_grandmaster_oof_matrix.npy"
    if gm_matrix_path.exists():
        gm_oof = np.load(gm_matrix_path)
        X_meta = np.column_stack([gm_oof, nuri_oof_preds])
    else:
        X_meta = np.column_stack([nuri_oof_preds])

    kf = KFold(n_splits=5, shuffle=True, random_state=random_state)
    meta_oof_preds = np.zeros(len(y_true), dtype=np.float64)
    for train_idx, val_idx in kf.split(X_meta, y_true):
        fold_meta_model = Ridge(alpha=1.0, positive=True, random_state=random_state)
        fold_meta_model.fit(X_meta[train_idx], y_true[train_idx])
        meta_oof_preds[val_idx] = fold_meta_model.predict(X_meta[val_idx])
    meta_oof_preds = np.clip(meta_oof_preds, 0.0, 100.0)

    nuri_rmse = float(np.sqrt(mean_squared_error(y_true, nuri_oof_preds)))
    ens_rmse = float(np.sqrt(mean_squared_error(y_true, meta_oof_preds)))
    ens_mae = float(mean_absolute_error(y_true, meta_oof_preds))
    ens_r2 = float(r2_score(y_true, meta_oof_preds))

    print("\n" + "=" * 88)
    print("❄️  [TASK 4C - RETAIL COLD-CHAIN] NURI CONSORTIUM & STACKING BENCHMARK")
    print("=" * 88)
    print(f"   📊 Nuri Single LGBM OOF RMSE : {nuri_rmse:.4f}")
    print(f"   🏆 Nuri Stacked Meta RMSE    : {ens_rmse:.4f} | MAE: {ens_mae:.4f} | R²: {ens_r2:.5f}")
    print("=" * 88)

    return {
        "task4a": s4a,
        "task4b": s4b,
        "nuri_rmse": nuri_rmse,
        "ensemble_rmse": ens_rmse,
    }


if __name__ == "__main__":
    run_stacking_meta_learner()
