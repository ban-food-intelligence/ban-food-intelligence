"""Ali KI hattı: veri sözleşmesi, şartname çapaları, kütle dengesi, referansı geçme, OOF biçimi."""
import pathlib
import sys

import numpy as np
import pandas as pd
import pytest

ENGINE = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ENGINE))
import generator_ali as G  # noqa: E402
import ml_contract as C  # noqa: E402
import predict_ali as P  # noqa: E402
import train_model_ali as T  # noqa: E402


@pytest.fixture(scope="module")
def data():
    rng = np.random.default_rng(7)
    return G.generate_production(900, rng), G.generate_butchery(1200, rng)


def test_generator_is_deterministic():
    a = G.generate_production(50, np.random.default_rng(1))
    b = G.generate_production(50, np.random.default_rng(1))
    pd.testing.assert_frame_equal(a, b)


def test_columns_follow_contract(data):
    prod, butch = data
    need_p = ["row_id", "source", C.PRODUCTION["date"], *C.PRODUCTION["categorical"], *C.PRODUCTION["numeric"],
              C.PRODUCTION["target"], *C.PRODUCTION["derived_targets"]]
    need_b = ["row_id", "source", C.BUTCHERY["date"], *C.BUTCHERY["categorical"], *C.BUTCHERY["numeric"],
              *C.BUTCHERY["targets"], *[f"{p}_kg" for p in C.BUTCHERY_PARTS]]
    assert set(need_p) <= set(prod.columns)
    assert set(need_b) <= set(butch.columns)
    assert prod.row_id.is_unique and butch.row_id.is_unique


def test_spec_anchor_standard_conditions_yield_85_pct(data):
    prod, _ = data
    m = pd.to_datetime(prod.order_date).dt.month
    std = prod[(prod.product_sku == "MM-SUCUK-1KG") & (prod.subcontractor_code == "FAS-01")
               & (prod.meat_supplier_code == "TED-ET-01") & (prod.drying_days == 5)
               & prod.meat_fat_pct.between(20.5, 23.5) & (prod.meat_age_days <= 2) & ~m.isin([6, 7, 8])
               & prod.meat_kg.between(90, 130)]
    assert len(std) > 10
    assert 84.0 <= std.yield_pct.median() <= 85.5


def test_spec_anchor_ekp_formula():
    spec = pd.DataFrame({"meat_kg": [100.0], "meat_unit_price": [9.70], "spice_kg": [2.0], "spice_unit_price": [24.0],
                         "casing_kg": [5.0], "casing_unit_price": [11.60], "service_unit_price": [5.50]})
    assert round(float(C.production_ekp(spec, [85])[0]), 2) == 18.16


def test_butchery_mass_balance_exact(data):
    _, butch = data
    total = butch[[f"{p}_kg" for p in C.BUTCHERY_PARTS]].sum(axis=1)
    assert np.allclose(total, butch.carcass_weight_kg, atol=1e-6)
    lamb = butch[butch.species == "KUZU"]
    assert abs(lamb.fire_pct.mean() - 12) < 1.0          # şartname: 25 kg'da 3 kg fire


def test_fold_assignment_is_stable_across_languages():
    # Go / Kotlin uygulamaları da bu değerleri vermeli (SHA-1 ilk 8 hex mod 5)
    assert [C.fold_of(x) for x in ["ALI-P-000001", "BERKE-P-000001", "NURI-B-000042"]] == [1, 2, 2]


@pytest.fixture(scope="module")
def trained(data, tmp_path_factory):
    out = tmp_path_factory.mktemp("art")
    prod, butch = data
    rp, oof_p = T.train_production(prod, out)
    rb, oof_b = T.train_butchery(butch, out)
    return out, rp, rb, oof_p, oof_b


def test_models_beat_simple_reference_on_time_holdout(trained):
    _, rp, rb, _, _ = trained
    base_p = rp["models"]["baseline_hist_mean"]["test_actual_qty"]["MAE"]
    base_b = rb["models"]["baseline_hist_mean"]["test_kg_MAE"]
    assert rp["models"][rp["champion"]]["test_actual_qty"]["MAE"] < 0.8 * base_p
    assert rb["models"][rb["champion"]]["test_kg_MAE"] < 0.8 * base_b


def test_oof_file_is_stacking_ready(trained):
    _, _, _, oof_p, oof_b = trained
    for oof in (oof_p, oof_b):
        train = oof[oof.split == "train"]
        assert train.fold.between(0, C.N_FOLDS - 1).all()
        assert (oof[oof.split == "test"].fold == -1).all()
        assert not oof.y_pred.isna().any()
        assert not oof.duplicated(["row_id", "target", "model"]).any()
        assert set(oof.model) == {"ali_catboost", "ali_ridge"}


def test_predictions_conserve_mass_and_flag_anomalies(trained, data):
    out, *_ = trained
    import joblib
    _, butch = data
    case = butch.head(3).copy()
    res = P.predict_butchery(case, joblib.load(out / "butchery_model.joblib"))
    sums = res.groupby("row_id").pred_kg.sum()
    assert np.allclose(sums.to_numpy(), case.set_index("row_id").loc[sums.index, "carcass_weight_kg"], atol=0.01)
    assert {"actual_kg", "deviation_kg", "anomaly"} <= set(res.columns)


def test_seed_case_prediction_close_to_actual(trained):
    out, *_ = trained
    import joblib
    seed = pd.read_csv(ENGINE / "data" / "seed_cases_production.csv")
    res = P.predict_production(seed, joblib.load(out / "production_model.joblib"))
    urt45 = res[res.row_id == "DB-P-URT-2026-045"].iloc[0]
    assert abs(urt45.pred_qty - 85) < 2.5
    assert abs(urt45.pred_ekp - 18.16) < 0.6
