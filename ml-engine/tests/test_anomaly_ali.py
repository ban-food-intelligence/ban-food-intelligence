"""Ali KI anomali uyarıları: enjeksiyon doğruluğu, sızıntısız eşik seçimi, kurallardan iyi olma, uyarı/SQL biçimi."""
import pathlib
import sys

import numpy as np
import pandas as pd
import pytest

ENGINE = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ENGINE))
import anomaly_ali as A  # noqa: E402
import generator_ali as G  # noqa: E402
import ml_contract as C  # noqa: E402
import predict_ali as P  # noqa: E402
import train_model_ali as T  # noqa: E402


@pytest.fixture(scope="module")
def data():
    rng = np.random.default_rng(11)
    return G.generate_production(900, rng, with_fault_label=True), G.generate_butchery(1200, rng)


def test_fault_label_does_not_change_generated_data():
    a = G.generate_production(80, np.random.default_rng(3))
    b = G.generate_production(80, np.random.default_rng(3), with_fault_label=True)
    pd.testing.assert_frame_equal(a, b.drop(columns="fault"))
    assert b.fault.dtype == bool


def test_production_injection_lowers_yield_in_whole_packs(data):
    prod, _ = data
    rows = np.arange(0, 900, 10)
    y, drop = A.inject_production(prod, rows, np.random.default_rng(0))
    assert (drop[rows] > 1.0).all() and (drop[rows] < 7.5).all()
    assert (drop[np.setdiff1d(np.arange(900), rows)] == 0).all()
    qty = y * prod.meat_kg / 100 / prod.pack_size_kg
    assert np.allclose(qty, np.round(qty), atol=1e-6)


def test_butchery_injection_conserves_mass(data):
    _, butch = data
    rows = np.arange(0, 1200, 7)
    Y, shift = A.inject_butchery(butch, rows, np.random.default_rng(0))
    assert np.allclose(Y.sum(axis=1), 100, atol=0.01)
    assert (shift[rows] >= 0.9).all() and (shift[rows] <= 4.1).all()
    assert (Y >= 0).all()


@pytest.fixture(scope="module")
def evaluated(data, tmp_path_factory):
    prod, butch = data
    out = tmp_path_factory.mktemp("art")
    rp, oof_p = T.train_production(prod.drop(columns="fault"), out)
    rb, oof_b = T.train_butchery(butch, out)
    pred_p, split_p = A._champion_preds(oof_p, rp["champion"], prod.row_id, ["yield_pct"])
    P_b, split_b = A._champion_preds(oof_b, rb["champion"], butch.row_id, C.BUTCHERY["targets"])
    q90_b = np.array(list(rb["interval90_pct"].values()))
    ev_p = A.evaluate_production(prod, pred_p[:, 0], split_p, rp["interval90_yield_pct"]["half_width"], n_rounds=8)
    ev_b = A.evaluate_butchery(butch, P_b, split_b, q90_b, n_rounds=8)
    return out, (pred_p[:, 0], split_p, rp), (P_b, split_b, q90_b), ev_p, ev_b


def test_detector_beats_rules_on_time_holdout(evaluated):
    *_, (rep_p, _), (rep_b, _) = evaluated
    for rep in (rep_p, rep_b):
        f1 = {k: v["f1"]["mean"] for k, v in rep["test"].items()}
        assert f1["detector"] > 0.65
        assert all(f1["detector"] > f1[k] + 0.1 for k in f1 if k != "detector")
        assert rep["test"]["detector"]["precision"]["mean"] > 0.7
        assert rep["test"]["detector"]["false_alarms_per_100"]["mean"] < 3
    assert rep_p["detector_recall_by_drop_pts"]["large_4.5+"] > 0.9


def test_thresholds_are_chosen_without_test_rows(data, evaluated):
    prod, butch = data
    _, (pred_p, split_p, rp), (P_b, split_b, q90_b), (_, det_p), (_, det_b) = evaluated
    # test satırlarının gerçek değerleri bozulsa da eşikler aynı kalmalı (yalnız train OOF'tan öğrenilir)
    te = split_p == "test"
    bad = prod.copy()
    bad.loc[te, "yield_pct"] = 50.0
    _, det2 = A.evaluate_production(bad, pred_p, split_p, rp["interval90_yield_pct"]["half_width"], n_rounds=8)
    assert det2["t_warn"] == det_p["t_warn"] and det2["scale"] == det_p["scale"]
    teb = split_b == "test"
    badb = butch.copy()
    badb.loc[teb, C.BUTCHERY["targets"]] = [50, 10, 10, 10, 10, 10]
    _, det3 = A.evaluate_butchery(badb, P_b, split_b, q90_b, n_rounds=8)
    assert det3["t_warn"] == det_b["t_warn"]


def test_alerts_for_seed_and_what_if_cases(evaluated):
    import joblib
    out, *_, (_, det_p), (_, det_b) = evaluated
    detector = {"production": det_p, "butchery": det_b, "version": A.DETECTOR_VERSION}
    prod_model = joblib.load(out / "production_model.joblib")
    butch_model = joblib.load(out / "butchery_model.joblib")

    seed = pd.read_csv(ENGINE / "data" / "seed_cases_production.csv")
    assert (P.alerts("production", seed, prod_model, detector).severity == "").all()   # şartname örneği normal

    demo = pd.read_csv(ENGINE / "data" / "demo_cases_anomaly_production.csv")          # 85 yerine 78 paket
    al = P.alerts("production", demo, prod_model, detector).iloc[0]
    assert (al.severity, al.direction) == ("CRITICAL", "LOW_YIELD")
    assert al.est_loss_eur > 50 and "78 paket" in al.message

    demo_b = pd.read_csv(ENGINE / "data" / "demo_cases_anomaly_butchery.csv")          # 0,9 kg but -> fire
    alb = P.alerts("butchery", demo_b, butch_model, detector).iloc[0]
    assert alb.severity != "" and alb.direction == "FIRE_HIGH"
    assert alb.top_parts.startswith("fire +") and "but -" in alb.top_parts

    sql = P.alerts_to_sql("butchery", P.alerts("butchery", demo_b, butch_model, detector))
    assert sql.count("INSERT INTO ml_anomaly_alert") == 1
    assert "op_no = 'PRC-2026-001'" in sql and "ON DUPLICATE KEY UPDATE" in sql


def test_sql_escapes_quotes():
    assert P._sql_str("O'Brien") == "'O''Brien'"
    assert P._sql_str("") == "NULL"
