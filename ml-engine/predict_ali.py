"""Ali - eğitilmiş modellerle tahmin + gerçek + sapma raporu (şartname 4A/4B).

Girdi, ml-engine/DATA_CONTRACT.md kolonlarına uyan bir CSV'dir; MySQL'deki
v_ml_production_features / v_ml_butchery_features görünümlerinin çıktısı
doğrudan kullanılabilir (seed örnekleri: ml-engine/data/seed_cases_*.csv).

  python ml-engine/predict_ali.py production ml-engine/data/seed_cases_production.csv
  python ml-engine/predict_ali.py butchery   ml-engine/data/seed_cases_butchery.csv --emit-sql out.sql

--emit-sql: sonuçları ml_prediction tablosuna yazan INSERT'ler üretir;
v_ml_prediction_deviation görünümü React panelinde tahmin/gerçek/sapma gösterir.
Gerçek değer biliniyorsa ve %90 aralığın dışındaysa satır `anomaly` (aralık dışı) olarak işaretlenir.
Kalibre edilmiş KI uyarısı (düşük üretim verimi / beklenmedik fire) ise anomaly_ali.py dedektörüyle
üretilir: --alerts ekranda gösterir, --emit-sql ayrıca ml_anomaly_alert INSERT'lerini yazar
(önce: python ml-engine/anomaly_ali.py).
"""
from __future__ import annotations

import argparse
import pathlib
import sys

import joblib
import numpy as np
import pandas as pd

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import anomaly_ali as A  # noqa: E402
import ml_contract as C  # noqa: E402
import train_model_ali as T  # noqa: E402

ART = HERE / "artifacts" / "ali"


def predict_production(df: pd.DataFrame, bundle=None) -> pd.DataFrame:
    bundle = bundle or joblib.load(ART / "production_model.joblib")
    model = bundle["models"][bundle["champion"]]
    y = model.predict(T.production_features(df))
    q = bundle["q90"]
    qty = C.production_qty_from_yield(df, y)
    out = pd.DataFrame({
        "row_id": df.row_id,
        "pred_yield_pct": np.round(y, 2),
        "pred_qty": np.round(qty, 1),
        "pred_qty_low90": np.round(C.production_qty_from_yield(df, y - q), 1),
        "pred_qty_high90": np.round(C.production_qty_from_yield(df, y + q), 1),
        "pred_ekp": np.round(C.production_ekp(df, qty), 4),
        "model": bundle["champion"], "version": bundle["version"],
    })
    if "actual_qty" in df and df.actual_qty.notna().all():
        out["actual_qty"] = df.actual_qty.to_numpy()
        out["deviation_qty"] = np.round(out.actual_qty - out.pred_qty, 1)
        out["actual_ekp"] = np.round(C.production_ekp(df, df.actual_qty), 4)
        out["deviation_ekp"] = np.round(out.actual_ekp - out.pred_ekp, 4)
        out["anomaly"] = (out.actual_qty < out.pred_qty_low90) | (out.actual_qty > out.pred_qty_high90)
    return out


def butchery_pct(df: pd.DataFrame, bundle) -> np.ndarray:
    model = bundle["models"][bundle["champion"]]
    X = T.butchery_features(df)
    raw = np.column_stack([m.predict(X) for m in model]) if isinstance(model, list) else model.predict(X)
    return T.normalize_pct(np.atleast_2d(raw))


def predict_butchery(df: pd.DataFrame, bundle=None) -> pd.DataFrame:
    bundle = bundle or joblib.load(ART / "butchery_model.joblib")
    P = butchery_pct(df, bundle)
    W = df.carcass_weight_kg.to_numpy(float)
    rows = []
    for i in range(len(df)):
        for j, part in enumerate(C.BUTCHERY_PARTS):
            pred_kg = P[i, j] * W[i] / 100
            half = bundle["q90"][j] * W[i] / 100
            r = {"row_id": df.row_id.iloc[i], "part": part, "pred_pct": round(P[i, j], 2),
                 "pred_kg": round(pred_kg, 3), "low90_kg": round(pred_kg - half, 3),
                 "high90_kg": round(pred_kg + half, 3), "model": bundle["champion"], "version": bundle["version"]}
            col = f"{part}_kg"
            if col in df and pd.notna(df[col].iloc[i]):
                r["actual_kg"] = float(df[col].iloc[i])
                r["deviation_kg"] = round(r["actual_kg"] - pred_kg, 3)
                r["anomaly"] = abs(r["deviation_kg"]) > half
            rows.append(r)
    return pd.DataFrame(rows)


def alerts(task: str, df: pd.DataFrame, model_bundle=None, detector=None) -> pd.DataFrame:
    """Gerçek değeri bilinen kayıtlar için kalibre edilmiş anomali uyarıları (severity boş = uyarı yok)."""
    detector = detector or joblib.load(ART / "anomaly_detector.joblib")
    if task == "production":
        model_bundle = model_bundle or joblib.load(ART / "production_model.joblib")
        df = df[df.actual_qty.notna()]
        y = model_bundle["models"][model_bundle["champion"]].predict(T.production_features(df))
        ekp = C.production_ekp(df, C.production_qty_from_yield(df, y))
        out = A.production_alerts(df, y, ekp, detector["production"])
    else:
        model_bundle = model_bundle or joblib.load(ART / "butchery_model.joblib")
        df = df[df[[f"{p}_kg" for p in C.BUTCHERY_PARTS]].notna().all(axis=1)]
        out = A.butchery_alerts(df, butchery_pct(df, model_bundle), detector["butchery"])
    out["detector"] = detector[task]["method"]
    out["threshold_warn"] = detector[task]["t_warn"]
    out["threshold_crit"] = detector[task]["t_crit"]
    out["model_version"] = f"{model_bundle['version']}+{detector['version']}"
    return out


def _sql_str(v) -> str:
    return "NULL" if v is None or v == "" else "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def alerts_to_sql(task: str, res: pd.DataFrame) -> str:
    """Yalnız uyarı üreten satırlar; aynı kayıt + sürüm tekrar yazılırsa günceller (idempotent)."""
    lines = []
    ref_type, table, col, prefix, enum = (
        ("production_order", "production_order", "order_no", "DB-P-", "PRODUCTION_YIELD") if task == "production"
        else ("butchery_operation", "butchery_operation", "op_no", "DB-B-", "BUTCHERY_YIELD"))
    for r in res[res.severity != ""].itertuples():
        ref = r.row_id.removeprefix(prefix)
        sub = f"(SELECT id FROM {table} WHERE {col} = {_sql_str(ref)})"
        top = getattr(r, "top_parts", None)
        lines.append(
            "INSERT INTO ml_anomaly_alert (task, detector, model_version, ref_type, ref_id, score, threshold_warn, "
            "threshold_crit, severity, direction, expected_value, actual_value, deviation, est_loss_eur, top_parts, "
            f"message) VALUES ('{enum}', '{r.detector}', '{r.model_version}', '{ref_type}', {sub}, {r.score}, "
            f"{round(r.threshold_warn, 3)}, {round(r.threshold_crit, 3)}, '{r.severity}', '{r.direction}', "
            f"{r.expected_value}, {r.actual_value}, {r.deviation}, {r.est_loss_eur}, {_sql_str(top)}, "
            f"{_sql_str(r.message)}) ON DUPLICATE KEY UPDATE score = VALUES(score), severity = VALUES(severity), "
            "direction = VALUES(direction), expected_value = VALUES(expected_value), actual_value = VALUES(actual_value), "
            "deviation = VALUES(deviation), est_loss_eur = VALUES(est_loss_eur), top_parts = VALUES(top_parts), "
            "message = VALUES(message);")
    return "\n".join(lines) + ("\n" if lines else "")


def to_sql(task: str, res: pd.DataFrame) -> str:
    lines = ["USE food_intel;"]
    if task == "production":
        for r in res.itertuples():
            ref = r.row_id.removeprefix("DB-P-")
            sub = f"(SELECT id FROM production_order WHERE order_no = '{ref}')"
            for tgt, pred, act in (("actual_qty", r.pred_qty, getattr(r, "actual_qty", None)),
                                   ("ekp", r.pred_ekp, getattr(r, "actual_ekp", None))):
                lines.append("INSERT INTO ml_prediction (task, model_name, model_version, ref_type, ref_id, target_name, "
                             f"predicted_value, actual_value) VALUES ('{'PRODUCTION_YIELD' if tgt == 'actual_qty' else 'PRODUCTION_EKP'}', "
                             f"'{r.model}', '{r.version}', 'production_order', {sub}, '{tgt}', {pred}, "
                             f"{'NULL' if act is None else act});")
    else:
        for r in res.itertuples():
            ref = r.row_id.removeprefix("DB-B-")
            sub = f"(SELECT id FROM butchery_operation WHERE op_no = '{ref}')"
            act = getattr(r, "actual_kg", None)
            lines.append("INSERT INTO ml_prediction (task, model_name, model_version, ref_type, ref_id, target_name, "
                         f"predicted_value, actual_value) VALUES ('BUTCHERY_YIELD', '{r.model}', '{r.version}', "
                         f"'butchery_operation', {sub}, '{r.part}_kg', {r.pred_kg}, {'NULL' if act is None else act});")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("task", choices=["production", "butchery"])
    ap.add_argument("csv")
    ap.add_argument("--emit-sql")
    ap.add_argument("--alerts", action="store_true", help="anomali uyarılarını da hesapla (anomaly_detector.joblib)")
    ap.add_argument("--demo", action="store_true", help="what-if senaryosu: uyarı mesajlarına [DEMO] öneki, ml_prediction yazılmaz")
    args = ap.parse_args()
    df = pd.read_csv(args.csv)
    res = predict_production(df) if args.task == "production" else predict_butchery(df)
    with pd.option_context("display.width", 200, "display.max_columns", 30):
        print(res.to_string(index=False))
    sql = to_sql(args.task, res) if args.emit_sql and not args.demo else ""
    if args.alerts or (args.emit_sql and (ART / "anomaly_detector.joblib").exists()):
        al = alerts(args.task, df)
        if args.demo:
            al["message"] = "[DEMO] " + al.message
        with pd.option_context("display.width", 200, "display.max_columns", 30, "display.max_colwidth", 120):
            print(al[["row_id", "score", "severity", "direction", "est_loss_eur", "message"]].to_string(index=False))
        sql += alerts_to_sql(args.task, al)
    if args.emit_sql:
        pathlib.Path(args.emit_sql).write_text(sql, encoding="utf-8")
        print(f"-> {args.emit_sql}")


if __name__ == "__main__":
    main()
