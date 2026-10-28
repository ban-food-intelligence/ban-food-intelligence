"""Robust evaluation (evaluate_ali.py) and the /ai/summary preview (ai_summary.py).

Everything trains on freshly generated data and writes only to tmp_path; tracked artifacts are
read, never written.
"""
import json
import pathlib
import re
import sys

import joblib
import numpy as np
import pandas as pd
import pytest

ENGINE = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ENGINE))
import ai_summary as S  # noqa: E402
import evaluate_ali as E  # noqa: E402
import generator_ali as G  # noqa: E402
import ml_contract as C  # noqa: E402
import train_model_ali as T  # noqa: E402

GO_CONTROLLER = ENGINE.parent / "api-go" / "controllers" / "ai_controller.go"


@pytest.fixture(scope="module")
def data():
    rng = np.random.default_rng(11)
    return G.generate_production(1000, rng), G.generate_butchery(900, rng)


@pytest.fixture(scope="module")
def bundles(data, tmp_path_factory):
    """Ali's pipeline trained once into a tmp dir; CatBoost shortened (speed only, not asserted on)."""
    out = tmp_path_factory.mktemp("ali")
    saved = dict(T.PROD_CB_PARAMS), dict(T.BUTCH_CB_PARAMS)
    T.PROD_CB_PARAMS["iterations"] = T.BUTCH_CB_PARAMS["iterations"] = 100
    try:
        prod, butch = data
        rep_p, _ = T.train_production(prod.head(600), out)
        T.train_butchery(butch.head(500), out)
    finally:
        T.PROD_CB_PARAMS.update(saved[0])
        T.BUTCH_CB_PARAMS.update(saved[1])
    return out, rep_p


# ---------------------------------------------------------------- physical consistency
@pytest.mark.parametrize("model", ["ridge_project", "ridge_alr"])
def test_butchery_parts_plus_waste_equal_input_weight(data, model):
    _, butch = data
    df, X, Y, cats, nums = E._butch_frame(butch)
    m = E.butchery_candidates(cats, nums)[model][0]().fit(X.iloc[:600], Y[:600])
    P = m.predict(X.iloc[600:])
    W = df.carcass_weight_kg.to_numpy()[600:]
    assert (P > 0).all()
    assert np.allclose((P * W[:, None] / 100).sum(1), W, atol=1e-9)
    assert E.constraint_violation(P, W)["violation_rate"] == 0.0


def test_alr_round_trip_is_exact():
    P = np.array([[26, 14, 20, 12, 16, 12], [24.5, 13, 21, 11, 14.5, 16]], float)
    assert np.allclose(T.alr_inverse(T.alr(P)), P)


def test_constraint_violation_detects_raw_predictions():
    W = np.array([25.0, 20.0])
    raw = np.array([[26, 14, 20, 12, 16, 12.5], [25, 13, 21, 11, 14, 16]], float)   # first sums to 100.5
    v = E.constraint_violation(raw, W)
    assert v["violation_rate"] == 0.5 and v["max_abs_gap_kg"] == pytest.approx(0.125)


# ---------------------------------------------------------------- intervals
def test_conformal_quantile_is_finite_sample_valid():
    scores = np.arange(1, 100, dtype=float)                    # n = 99
    assert E.conformal_q(scores, 0.80) == 80.0                 # ceil(100 * 0.8) = 80th smallest
    assert E.conformal_q(scores[:5], 0.98) == 5.0              # small n -> the max, never out of range


def test_interval_coverage_close_to_nominal(data):
    prod, _ = data
    rep = E.evaluate_production(prod, models=["ridge"], n_boot=100, with_cqr=False)
    cov = rep["models"]["ridge"]["intervals"]["80"]["coverage"]
    assert 0.72 <= cov <= 0.88, cov
    assert rep["models"]["ridge"]["intervals"]["98"]["coverage"] >= 0.93


def test_trained_bundle_carries_interval_bands(bundles):
    out, rep = bundles
    b = joblib.load(out / "production_model.joblib")
    assert 0 < b["q80"] < b["q90"] < b["q98"]
    assert set(rep["intervals_yield_pct"]) == {"80", "90", "98"}
    assert rep["interval90_yield_pct"]["half_width"] == b["q90"]          # old key, same meaning


# ---------------------------------------------------------------- time hygiene + statistics
def test_rolling_folds_are_chronological_and_respect_dates(data):
    prod, _ = data
    df = prod.sort_values("order_date").reset_index(drop=True)
    folds = E.rolling_folds(df.order_date)
    assert len(folds) == E.N_FOLDS
    for tr, te in folds:
        assert tr.max() < te.min()
        assert df.order_date.iloc[tr].max() < df.order_date.iloc[te].min()   # no date on both sides


def test_diebold_mariano_sanity():
    rng = np.random.default_rng(0)
    a = np.abs(rng.normal(0, 1, 500))
    assert E.diebold_mariano(a, a + 0.5)["p_value"] < 1e-6
    assert E.diebold_mariano(a, rng.permutation(a))["p_value"] > 0.01


# ---------------------------------------------------------------- reproducibility
def test_same_seed_same_metrics(data):
    prod, _ = data
    runs = [E._public(E.evaluate_production(prod.head(600), models=["baseline_hist_mean", "ridge", "lightgbm"],
                                            n_boot=100, with_cqr=False)) for _ in range(2)]
    assert json.dumps(runs[0], sort_keys=True) == json.dumps(runs[1], sort_keys=True)


# ---------------------------------------------------------------- /ai/summary backward compatibility
def _go_json_tags(struct: str) -> list[str]:
    src = GO_CONTROLLER.read_text(encoding="utf-8")
    body = re.search(r"type %s struct \{(.*?)\n\}" % struct, src, re.S).group(1)
    return re.findall(r'json:"([a-z_]+)"', body)


def test_go_dto_matches_frozen_key_lists():
    if not GO_CONTROLLER.exists():
        pytest.skip("api-go not checked out")
    assert _go_json_tags("AIMetricDto") == S.METRIC_KEYS
    assert _go_json_tags("AIComparisonDto") == S.COMPARISON_KEYS
    assert _go_json_tags("AISummaryDto") == S.SUMMARY_KEYS


def test_ai_summary_is_backward_compatible(bundles, tmp_path):
    out, _ = bundles
    payload = S.build(None, joblib.load(out / "production_model.joblib"), joblib.load(out / "butchery_model.joblib"))
    (tmp_path / "ai_summary.json").write_text(json.dumps(payload), encoding="utf-8")
    payload = json.loads((tmp_path / "ai_summary.json").read_text(encoding="utf-8"))

    assert list(payload)[:5] == S.SUMMARY_KEYS
    assert payload["model"] == "ali_ridge" and payload["baseline"] == "baseline_hist_mean"
    num = (int, float)
    optional_metric = {"wmape_pct", "mae_ci_low", "mae_ci_high", "cv_mae_mean", "cv_mae_std", "cv_folds"}
    for m in payload["metrics"]:
        assert set(S.METRIC_KEYS) <= set(m) and set(m) - set(S.METRIC_KEYS) <= optional_metric
        assert m["role"] in ("MODEL", "BASELINE") and isinstance(m["n_rows"], int)
        assert isinstance(m["mae"], num) and isinstance(m["mape_pct"], num)
        assert m["mae_ci_low"] <= m["mae"] <= m["mae_ci_high"]
    optional_cmp = {"interval_low", "interval_high", "interval_level", "anomaly", "outside_interval"}
    targets = {(c["task"], c["target"]) for c in payload["comparisons"]}
    assert ("PRODUCTION", "actual_qty") in targets and ("BUTCHERY", "fire_kg") in targets
    for c in payload["comparisons"]:
        assert set(S.COMPARISON_KEYS) <= set(c) and set(c) - set(S.COMPARISON_KEYS) <= optional_cmp
        for k in ("actual", "model_pred", "model_dev", "baseline_pred"):
            assert c[k] is None or isinstance(c[k], num)
        assert c["interval_low"] <= c["model_pred"] <= c["interval_high"]
        if "anomaly" in c:
            assert isinstance(c["anomaly"], bool)
    # the seeded order URT-2026-045 (85 packages, spec example) is inside its interval and not an anomaly
    urt = next(c for c in payload["comparisons"] if c["ref_no"] == "URT-2026-045" and c["target"] == "actual_qty")
    assert urt["interval_low"] <= 85 <= urt["interval_high"] and urt["anomaly"] is False


def test_predict_output_keeps_old_columns(bundles):
    import predict_ali as P
    out, _ = bundles
    seed = pd.read_csv(ENGINE / "data" / "seed_cases_production.csv")
    res = P.predict_production(seed, joblib.load(out / "production_model.joblib"))
    old = {"row_id", "pred_yield_pct", "pred_qty", "pred_qty_low90", "pred_qty_high90", "pred_ekp", "model",
           "version", "actual_qty", "deviation_qty", "actual_ekp", "deviation_ekp", "anomaly"}
    assert old <= set(res.columns)
    assert {"interval_low", "interval_high", "interval_level"} <= set(res.columns)
    assert (res.pred_qty_low90 <= res.interval_low).all() and (res.interval_high <= res.pred_qty_high90).all()
