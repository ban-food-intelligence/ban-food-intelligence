"""
Nuri Champion ML & Edge AI Test Suite:
- Verifies contract compliance (`DATA_CONTRACT.md` & `ml_contract.py`)
- Proves Nuri's models strictly beat Ali's `ali_ridge` and `ali_catboost` across
  Task 4A (Production Yield, Package Qty, EKP) and Task 4B (6-Part Butchery Yield)
- Verifies real multi-teammate Level-2 Stacking (`nuri_stack_meta`)
- Verifies dual-output ONNX Edge AI (`predicted_yield` + `uncertainty_variance`)
"""

import json
import pathlib
import sys
import numpy as np
import pandas as pd
import pytest

ENGINE = pathlib.Path(__file__).resolve().parents[1]
if str(ENGINE) not in sys.path:
    sys.path.insert(0, str(ENGINE))

import ml_contract as C  # noqa: E402
import predict_nuri as PN  # noqa: E402
import stacking_meta_learner as SM  # noqa: E402
import train_model_nuri as TN  # noqa: E402


@pytest.fixture(scope="module")
def nuri_artifacts(tmp_path_factory):
    out_dir = tmp_path_factory.mktemp("nuri")       # never overwrite the tracked artifacts/nuri files
    df4a = pd.read_csv(ENGINE / "data" / "train_task4a_ali.csv")
    df4b = pd.read_csv(ENGINE / "data" / "train_task4b_ali.csv")
    rep_p, oof_p = TN.train_task4a_production_nuri(df4a, out_dir)
    rep_b, oof_b = TN.train_task4b_butchery_nuri(df4b, out_dir)
    return out_dir, rep_p, rep_b, oof_p, oof_b


def test_nuri_beats_ali_on_task4a_production(nuri_artifacts):
    _, rep_p, _, _, _ = nuri_artifacts
    ali_metrics = json.loads((ENGINE / "artifacts" / "ali" / "metrics.json").read_text(encoding="utf-8"))
    ali_champ = ali_metrics["production"]["champion"]
    ali_m = ali_metrics["production"]["models"][ali_champ]

    nuri_champ = rep_p["champion"]
    nuri_m = rep_p["models"][nuri_champ]

    assert nuri_m["cv_oof_yield_pct"]["MAE"] < ali_m["cv_oof_yield_pct"]["MAE"]
    assert nuri_m["test_yield_pct"]["MAE"] < ali_m["test_yield_pct"]["MAE"]
    assert nuri_m["test_actual_qty"]["MAE"] < ali_m["test_actual_qty"]["MAE"]
    assert nuri_m["test_ekp_eur"]["MAE"] < ali_m["test_ekp_eur"]["MAE"]
    assert nuri_m["test_yield_pct"]["R2"] > ali_m["test_yield_pct"]["R2"]


def test_nuri_beats_ali_on_task4b_butchery_and_conserves_mass(nuri_artifacts):
    _, _, rep_b, _, _ = nuri_artifacts
    ali_metrics = json.loads((ENGINE / "artifacts" / "ali" / "metrics.json").read_text(encoding="utf-8"))
    ali_champ = ali_metrics["butchery"]["champion"]
    ali_m = ali_metrics["butchery"]["models"][ali_champ]

    nuri_champ = rep_b["champion"]
    nuri_m = rep_b["models"][nuri_champ]

    assert nuri_m["cv_oof_pct_MAE"] < ali_m["cv_oof_pct_MAE"]
    assert nuri_m["test_pct_MAE"] < ali_m["test_pct_MAE"]
    assert nuri_m["test_kg_MAE"] < ali_m["test_kg_MAE"]
    assert nuri_m["test_sellable_kg_MAE"] < ali_m["test_sellable_kg_MAE"]
    assert nuri_m["mass_balance_max_err_kg"] < 1e-12


def test_nuri_oof_files_follow_data_contract(nuri_artifacts):
    _, _, _, oof_p, oof_b = nuri_artifacts
    for oof in (oof_p, oof_b):
        train = oof[oof["split"] == "train"]
        assert train["fold"].between(0, C.N_FOLDS - 1).all()
        assert (oof[oof["split"] == "test"]["fold"] == -1).all()
        assert not oof["y_pred"].isna().any()
        assert not oof.duplicated(["row_id", "target", "model"]).any()


def test_real_stacking_meta_learner_beats_ali(nuri_artifacts):
    out_dir, *_ = nuri_artifacts
    s4a = SM.stack_task4a_production(out_dir)
    s4b = SM.stack_task4b_butchery(out_dir)
    assert s4a["🏆 nuri_stack_meta (L2)"]["qty_mae"] < s4a["Ali - ali_ridge (Champ)"]["qty_mae"]
    assert s4b["🏆 nuri_stack_meta (L2)"]["kg_mae"] < s4b["Ali - ali_ridge (Champ)"]["kg_mae"]


def test_predict_nuri_seed_cases_and_sql_emission(nuri_artifacts):
    import joblib
    out_dir, *_ = nuri_artifacts
    seed_p = pd.read_csv(ENGINE / "data" / "seed_cases_production.csv")
    res_p = PN.predict_production_nuri(seed_p, joblib.load(out_dir / "production_model_nuri.joblib"))
    urt45 = res_p[res_p["row_id"] == "DB-P-URT-2026-045"].iloc[0]
    assert abs(urt45["pred_qty"] - 85.0) <= 1.0
    assert abs(urt45["pred_ekp"] - 18.16) < 0.25
    assert "epistemic_var" in res_p.columns

    seed_b = pd.read_csv(ENGINE / "data" / "seed_cases_butchery.csv")
    res_b = PN.predict_butchery_nuri(seed_b, joblib.load(out_dir / "butchery_model_nuri.joblib"))
    assert np.isclose(res_b["pred_kg"].sum(), 25.0, atol=0.02)
    sql_text = PN.to_sql_nuri("production", res_p)
    assert "INSERT INTO ml_prediction" in sql_text


def test_consortium_dual_output_onnx_edge_inference():
    ort = pytest.importorskip("onnxruntime")
    onnx_path = ENGINE / "models" / "nuri_consortium_edge.onnx"
    assert onnx_path.exists()
    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    outputs = [o.name for o in sess.get_outputs()]
    assert outputs == ["predicted_yield", "uncertainty_variance"]

    normal_batch = np.array([[14.0, 3.5, 82.0, 0.92]], dtype=np.float32)
    ood_batch = np.array([[70.0, 17.5, 46.0, 0.36]], dtype=np.float32)
    pred_n, var_n = sess.run(None, {"float_input": normal_batch})
    pred_o, var_o = sess.run(None, {"float_input": ood_batch})

    assert 80.0 <= float(pred_n[0, 0]) <= 100.0
    assert float(var_n[0, 0]) < 0.045
    assert float(var_o[0, 0]) > 0.045
