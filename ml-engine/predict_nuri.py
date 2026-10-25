"""
BAN Food Intelligence - AVATAR ERP Competition
Module 4: Nuri Unified Inference, Conformal+Epistemic Anomaly & SQL/ONNX Emitter
Author: Nuri (Retail POS, Offline Sync, Security & Stacking Lead)

Supports:
  1. Task 4A (`production`) : Whole-package quantized yield & EKP + Conformal/Epistemic anomaly flag
  2. Task 4B (`butchery`)   : 6-part Simplex-closed kg yield + Conformal/Epistemic anomaly flag
  3. `--emit-sql`           : Generates MySQL `INSERT INTO ml_prediction` statements compatible
                              with `database-mysql/schema.sql` (`v_ml_prediction_deviation`).
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import joblib
import numpy as np
import pandas as pd

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import ml_contract as C  # noqa: E402
import train_model_nuri as TN  # noqa: E402

ART_NURI = HERE / "artifacts" / "nuri"


def predict_production_nuri(df: pd.DataFrame, bundle: dict | None = None) -> pd.DataFrame:
    if bundle is None:
        model_path = ART_NURI / "production_model_nuri.joblib"
        if not model_path.exists():
            TN.train_task4a_production_nuri(
                pd.read_csv(HERE / "data" / "train_task4a_ali.csv"), ART_NURI
            )
        bundle = joblib.load(model_path)

    X = TN.engineer_production_features_nuri(df)
    models = bundle["models"]
    p_huber = models["nuri_huber"].predict(X)
    p_ridge = models["nuri_pinn_ridge"].predict(X)
    p_lgbm = models["nuri_lgbm"].predict(X)

    champ = bundle["champion"]
    if champ == "nuri_hybrid":
        y_pred = 0.55 * p_huber + 0.35 * p_ridge + 0.10 * p_lgbm
    else:
        y_pred = models[champ].predict(X)

    epistemic_var = np.var(np.vstack([p_huber, p_ridge, p_lgbm]), axis=0)
    q90 = float(bundle["q90"])
    raw_qty = C.production_qty_from_yield(df, y_pred)
    qty = np.round(raw_qty)

    out = pd.DataFrame(
        {
            "row_id": df["row_id"],
            "pred_yield_pct": np.round(y_pred, 2),
            "pred_qty": np.round(qty, 1),
            "pred_qty_low90": np.round(C.production_qty_from_yield(df, y_pred - q90), 1),
            "pred_qty_high90": np.round(C.production_qty_from_yield(df, y_pred + q90), 1),
            "pred_ekp": np.round(C.production_ekp(df, qty), 4),
            "epistemic_var": np.round(epistemic_var, 5),
            "model": champ,
            "version": bundle["version"],
        }
    )
    if "actual_qty" in df and df["actual_qty"].notna().all():
        out["actual_qty"] = df["actual_qty"].to_numpy(float)
        out["deviation_qty"] = np.round(out["actual_qty"] - out["pred_qty"], 1)
        out["actual_ekp"] = np.round(C.production_ekp(df, df["actual_qty"]), 4)
        out["deviation_ekp"] = np.round(out["actual_ekp"] - out["pred_ekp"], 4)
        out["anomaly"] = (out["actual_qty"] < out["pred_qty_low90"]) | (
            out["actual_qty"] > out["pred_qty_high90"]
        )
    return out


def predict_butchery_nuri(df: pd.DataFrame, bundle: dict | None = None) -> pd.DataFrame:
    if bundle is None:
        model_path = ART_NURI / "butchery_model_nuri.joblib"
        if not model_path.exists():
            TN.train_task4b_butchery_nuri(
                pd.read_csv(HERE / "data" / "train_task4b_ali.csv"), ART_NURI
            )
        bundle = joblib.load(model_path)

    X, _, _ = TN.engineer_butchery_features_nuri(df)
    models = bundle["models"]
    raw_ridge = np.column_stack([m.predict(X) for m in models["nuri_simplex_ridge"]])
    raw_huber = np.column_stack([m.predict(X) for m in models["nuri_simplex_huber"]])

    champ = bundle["champion"]
    if champ == "nuri_simplex_hybrid":
        P = TN.normalize_simplex_pct(0.70 * raw_ridge + 0.30 * raw_huber)
    elif champ == "nuri_simplex_huber":
        P = TN.normalize_simplex_pct(raw_huber)
    else:
        P = TN.normalize_simplex_pct(raw_ridge)

    epistemic_var_mat = np.var(np.stack([raw_ridge, raw_huber], axis=0), axis=0)
    W = df["carcass_weight_kg"].to_numpy(float)
    q90 = bundle["q90"]

    rows = []
    for i in range(len(df)):
        for j, part in enumerate(C.BUTCHERY_PARTS):
            pred_kg = P[i, j] * W[i] / 100.0
            half = q90[j] * W[i] / 100.0
            r = {
                "row_id": df["row_id"].iloc[i],
                "part": part,
                "pred_pct": round(float(P[i, j]), 2),
                "pred_kg": round(float(pred_kg), 3),
                "low90_kg": round(float(pred_kg - half), 3),
                "high90_kg": round(float(pred_kg + half), 3),
                "epistemic_var": round(float(epistemic_var_mat[i, j]), 5),
                "model": champ,
                "version": bundle["version"],
            }
            col = f"{part}_kg"
            if col in df and pd.notna(df[col].iloc[i]):
                r["actual_kg"] = float(df[col].iloc[i])
                r["deviation_kg"] = round(r["actual_kg"] - pred_kg, 3)
                r["anomaly"] = abs(r["deviation_kg"]) > half
            rows.append(r)
    return pd.DataFrame(rows)


def to_sql_nuri(task: str, res: pd.DataFrame) -> str:
    lines = ["USE food_intel;"]
    if task == "production":
        for r in res.itertuples():
            ref = str(r.row_id).removeprefix("DB-P-")
            sub = f"(SELECT id FROM production_order WHERE order_no = '{ref}')"
            for tgt, pred, act in (
                ("actual_qty", r.pred_qty, getattr(r, "actual_qty", None)),
                ("ekp", r.pred_ekp, getattr(r, "actual_ekp", None)),
            ):
                task_enum = "PRODUCTION_YIELD" if tgt == "actual_qty" else "PRODUCTION_EKP"
                act_str = "NULL" if act is None else str(act)
                lines.append(
                    f"INSERT INTO ml_prediction (task, model_name, model_version, ref_type, ref_id, "
                    f"target_name, predicted_value, actual_value) VALUES ('{task_enum}', "
                    f"'{r.model}', '{r.version}', 'production_order', {sub}, '{tgt}', {pred}, {act_str});"
                )
    else:
        for r in res.itertuples():
            ref = str(r.row_id).removeprefix("DB-B-")
            sub = f"(SELECT id FROM butchery_operation WHERE op_no = '{ref}')"
            act = getattr(r, "actual_kg", None)
            act_str = "NULL" if act is None else str(act)
            lines.append(
                f"INSERT INTO ml_prediction (task, model_name, model_version, ref_type, ref_id, "
                f"target_name, predicted_value, actual_value) VALUES ('BUTCHERY_YIELD', "
                f"'{r.model}', '{r.version}', 'butchery_operation', {sub}, '{r.part}_kg', "
                f"{r.pred_kg}, {act_str});"
            )
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("task", choices=["production", "butchery"])
    ap.add_argument("csv")
    ap.add_argument("--emit-sql")
    args = ap.parse_args()
    df = pd.read_csv(args.csv)
    res = (
        predict_production_nuri(df)
        if args.task == "production"
        else predict_butchery_nuri(df)
    )
    with pd.option_context("display.width", 200, "display.max_columns", 30):
        print(res.to_string(index=False))
    if args.emit_sql:
        pathlib.Path(args.emit_sql).write_text(to_sql_nuri(args.task, res), encoding="utf-8")
        print(f"-> {args.emit_sql}")


if __name__ == "__main__":
    main()
