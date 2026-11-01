"""Challenger wrappers in compare_models_ali.py: they fit, predict the right shape and keep the
butchery shares on the simplex. Small data; accuracy is not asserted (that is the report's job)."""
import pathlib
import sys

import numpy as np
import pytest

ENGINE = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ENGINE))
import compare_models_ali as M  # noqa: E402
import evaluate_ali as E  # noqa: E402
import generator_ali as G  # noqa: E402


@pytest.fixture(scope="module")
def data():
    rng = np.random.default_rng(5)
    return G.generate_production(300, rng), G.generate_butchery(300, rng)


@pytest.mark.parametrize("name", ["ridge+lgbm_residual", "avg(ridge,lightgbm)", "random_forest"])
def test_production_challengers_fit_and_predict(data, name):
    df, X, y, cats, nums = E._prod_frame(data[0])
    m = M.production_candidates(cats, nums)[name][0]().fit(X.iloc[:240], y[:240])
    p = m.predict(X.iloc[240:])
    assert p.shape == (60,) and np.isfinite(p).all()


def test_stack_weights_are_a_convex_combination(data):
    df, X, y, cats, nums = E._prod_frame(data[0])
    c = M.production_candidates(cats, nums)
    s = M.Stack([c["ridge"][0], c["avg(ridge,lightgbm)"][0]]).fit(X.iloc[:240], y[:240])
    assert (s.w >= 0).all() and np.isclose(s.w.sum(), 1.0)


@pytest.mark.parametrize("name", ["ridge+lgbm_residual_project", "random_forest_project"])
def test_butchery_challengers_stay_on_the_simplex(data, name):
    df, X, Y, cats, nums = E._butch_frame(data[1])
    m = M.butchery_candidates(cats, nums)[name][0]().fit(X.iloc[:240], Y[:240])
    P = m.predict(X.iloc[240:])
    assert (P > 0).all() and np.allclose(P.sum(1), 100)
