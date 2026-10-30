# ML report: making the AI defensible (branch `ml-improve`)

Goal: not the lowest MAE, but an AI contribution that holds up under questioning: honest
validation, a meaningful comparison with simple methods, physically consistent outputs,
calibrated uncertainty and plain-language explanations. The data generator was **not** changed.

## 1. Diagnosis (written before any code change)

### 1.1 What exists

| | Task A, production (sucuk) | Task B, butchery (carcass) |
|---|---|---|
| Data | `data/train_task4a_ali.csv`, 1,500 synthetic orders, 2024-01-03 .. 2026-09-30 (1,184 x 1 kg, 316 x 500 g) | `data/train_task4b_ali.csv`, 2,500 synthetic carcasses, 2024-01-02 .. 2026-09-30 (1,858 lamb, 396 kid, 246 sheep) |
| Target | `yield_pct` (packages x pack size / meat kg); packages and EKP are derived with the exact cost formula | six shares (`but, sirt, omuz, kaburga, kiyma, fire`), kg = share x carcass weight |
| Features | SKU, subcontractor, meat supplier, meat/spice/casing kg, fat %, meat age, drying days, planned qty, month (sin/cos), summer flag, 1/meat kg, spice and casing ratio. Prices removed (inflation = time proxy). | species, breed, supplier, operator, sex, carcass kg, age, fat and conformation score, chill hours, month (sin/cos), weight vs species norm, warm-cut flag. Price removed. |
| Models (Ali) | Ridge (champion), CatBoost depth 3 | Ridge per share + clip/renormalise (champion), CatBoost MultiRMSE + renormalise |
| Models (Nuri) | Huber, "PINN" ridge, LightGBM, hybrid; level-2 `nuri_stack_meta` (positive Ridge on 5 OOF columns) | simplex Ridge/Huber; `nuri_stack_meta` = fixed blend 0.65/0.20/0.15 |
| Validation | one time split: latest 20 % of dates = test (302 orders); champion chosen by 5-fold CV inside the train period, folds from `sha1(row_id) % 5` | same; 500 carcasses in test |
| Interval | split-conformal 90 % from hash-fold OOF residuals (+/- 1.74 yield points), test coverage 89.1 % | per-share 90 % from OOF residuals, per-cell test coverage 89.8 % |

Holdout results from the committed `artifacts/ali/metrics.json`:

| Target (test, 302 / 500 rows) | Recipe baseline | Hist. mean baseline | Ridge | CatBoost |
|---|---|---|---|---|
| A packages MAE | 3.26 (MAPE 2.85 %) | 2.69 (2.24 %) | **1.37 (1.18 %)** | 1.40 (1.21 %) |
| A EKP MAE (EUR) | 0.320 (1.90 %) | 0.259 (1.54 %) | **0.138 (0.81 %)** | 0.139 (0.83 %) |
| B part kg MAE (6 parts) | 0.185 | 0.184 | **0.115** | 0.117 |
| B share MAE (points) | 0.862 | 0.857 | **0.538** | 0.549 |

### 1.2 Leakage audit

| Check | Finding | Severity |
|---|---|---|
| Target in features | None. `planned_qty` = floor(meat_kg x 0.85 / pack) is set before production, so it is legitimate. `actual_qty`, `ekp` and all `*_kg` / `*_pct` targets are excluded. | OK |
| Future information via prices | Prices rise with inflation and act as a time index. They were already removed from both tasks; EKP uses them only inside the exact cost formula. | OK (already fixed) |
| Holdout chronological? | Yes: `assign_splits` cuts at the 80 % date quantile, and rows on the cut date all go to test. | OK |
| CV inside the train period | **Not chronological.** Folds come from a hash of `row_id`, so a 2024 order can be predicted by a model trained on 2026 orders. With prices removed and a stationary generator the optimism is small (CV MAE 0.916 vs holdout 0.974 yield points), but it would grow with real drift. | Medium: fixed by rolling-origin CV |
| Conformal interval calibration | Residuals come from the same hash-fold OOF, so they are not "future" errors. The 90 % interval under-covered slightly on the holdout (89.1 %). | Low/medium: recalibrate on a time-ordered calibration window |
| Stacking OOF (`nuri_stack_meta`, production) | The meta-learner for fold k is trained on base OOF predictions of the other folds. Those base predictions came from models that *had seen* fold k (standard 2nd-order leak of single-level OOF stacking). Test predictions come from base models refit on all train rows, so OOF and test inputs differ in distribution. No test labels are used. | Low |
| Stacking, butchery | The 0.65/0.20/0.15 blend weights are hard-coded. The code and docs do not record how they were chosen. If they were picked by looking at the test period, the reported stack number is optimistic. | Medium (documentation) |
| Stacking comparison fairness | `stack_task4a_production` rounds package predictions to whole packages for Nuri/stack models (`quantize=True`) but not for Ali's models, and then *asserts* that Nuri/stack beat Ali. Rounding alone changes MAE, so the comparison is not like-for-like. Tests that assert "model X beats model Y" encode a desired outcome, not a property. | Medium |
| "Oracle" feature engineering | The person who wrote the generator also wrote the features (`1/meat_kg`, summer flag; Nuri also uses `FAS-02 x summer`). On synthetic data that knowledge flatters the model. All of them are plausible physical priors (fixed filler loss, heat), but the real-data plan must re-check them. | Note for limitations |
| Test side effect | `tests/test_nuri.py` trains into the tracked `artifacts/nuri/` and rewrites `oof_stack_meta_production.csv`, so running the suite leaves a dirty tree. | Low: fixed to use tmp dir |

### 1.3 Is the stacking earning its keep?

From the committed OOF files, on the same 302 / 500 holdout rows:

| | Production yield MAE (points) | Butchery share MAE (points) |
|---|---|---|
| `ali_ridge` (single Ridge) | 0.974 | 0.538 |
| `nuri_stack_meta` (level-2 stack over 4-5 models) | 0.954 | 0.536 |

The stack gains 0.02 yield points (about 0.03 packages per order) and 0.002 share points, while
running 5 base models plus a meta-learner. Section 2 tests whether this difference is
statistically distinguishable from zero. Prior expectation: it is not, so the simpler model
should stay the champion.

### 1.4 Plan

1. Rolling-origin (expanding window) evaluation, folds aligned to date boundaries; model choice made only on the folds inside the original training period.
2. Mean +/- std across folds, block-bootstrap 95 % CIs, Diebold-Mariano and paired bootstrap tests vs the baselines, wMAPE and business metrics (EUR, kg).
3. Fair candidates with nested, time-ordered tuning; a physics-informed baseline; additive log-ratio (ALR) for butchery; conformal 80 % intervals (split vs CQR) with coverage; an anomaly flag.
4. Permutation importance with plain-language direction sentences; two calm PNG charts.
5. Docs and tests.

## 2. Robust evaluation (`evaluate_ali.py` -> `artifacts/ali/evaluation.json`)

- 5 expanding-window folds, test blocks from 2025-05-23 to 2026-09-30 (148-152 orders, about 250
  carcasses each), edges snapped to date boundaries. Folds 1-3 (before the contract's 80 % cut)
  choose the champion; folds 4-5 (the original holdout) confirm it.
- Nested tuning on the last 20 % of each fold's training window.
- Moving-block bootstrap (block 10, 2,000 resamples) 95 % CIs; Diebold-Mariano (Newey-West) and
  paired bootstrap CI of the MAE difference vs both baselines.
- Parsimony rule: the simplest candidate whose dev-fold MAE is not significantly worse than the best.

### Task A, production (748 out-of-sample orders)

| Model | Packages MAE [95 % CI] | Fold mean +/- std | MAPE / wMAPE | EKP MAE (EUR) | EUR cost error / order | 80 % coverage |
|---|---|---|---|---|---|---|
| recipe 85 % x meat kg | 3.21 [2.97, 3.43] | 3.21 +/- 0.51 | 2.79 / 2.68 % | 0.300 | 31.9 | |
| historical mean / SKU | 2.55 [2.35, 2.75] | 2.55 +/- 0.22 | 2.19 / 2.13 % | 0.245 | 26.6 | |
| physics a - b/meat_kg | 2.54 [2.34, 2.74] | 2.54 +/- 0.22 | 2.18 / 2.12 % | 0.244 | 26.5 | 81.3 % |
| **Ridge (champion)** | **1.31 [1.20, 1.42]** | **1.31 +/- 0.06** | **1.15 / 1.09 %** | **0.128** | **13.7 [12.6, 14.7]** | **79.3 %** |
| ElasticNet | 1.30 [1.19, 1.41] | 1.30 +/- 0.06 | 1.14 / 1.09 % | 0.128 | 13.6 | 79.0 % |
| Huber | 1.30 [1.19, 1.41] | | | 0.128 | 13.6 | 80.7 % |
| LightGBM (nested grid) | 1.40 [1.29, 1.50] | 1.40 +/- 0.04 | 1.22 / 1.17 % | 0.135 | 14.5 | 81.7 % |
| CatBoost | 1.35 [1.25, 1.46] | 1.35 +/- 0.05 | 1.18 / 1.13 % | 0.131 | 14.0 | 80.9 % |

Ridge vs historical mean: -1.24 packages [-1.40, -1.08], DM stat -15.3, p = 8e-53.
The physics baseline is **not** better than the historical mean (-0.011 [-0.045, 0.018], p = 0.47):
the fixed filler loss is real but small. The gain comes from fat %, drying, subcontractor and
season, which only the learned model uses. Huber was best on the dev folds, but Ridge's difference
from it is +0.008 packages [-0.019, +0.035]. The CI contains 0, so Ridge stays.

### Task B, butchery (1,250 out-of-sample carcasses)

| Model | Part kg MAE, 6 parts [95 % CI] | Fold mean +/- std | Sellable MAPE / wMAPE | Waste kg MAE | kg misallocated / carcass |
|---|---|---|---|---|---|
| species recipe | 0.186 [0.181, 0.191] | 0.186 +/- 0.004 | 4.68 / 4.45 % | 0.258 | 1.12 |
| historical mean / species | 0.184 [0.180, 0.189] | 0.184 +/- 0.005 | 4.67 / 4.45 % | 0.248 | 1.11 |
| **Ridge per share + projection (champion)** | **0.118 [0.116, 0.122]** | **0.118 +/- 0.006** | **3.27 / 3.07 %** | **0.119** | **0.71** |
| Ridge on ALR (compositional) | 0.119 [0.116, 0.122] | 0.119 +/- 0.006 | 3.28 / 3.08 % | 0.120 | 0.72 |
| LightGBM on ALR | 0.124 [0.121, 0.127] | 0.124 +/- 0.005 | 3.42 / 3.22 % | 0.122 | 0.74 |
| CatBoost multi + projection | 0.120 [0.117, 0.123] | 0.120 +/- 0.006 | 3.31 / 3.10 % | 0.122 | 0.72 |

Ridge vs historical mean: -0.066 kg [-0.070, -0.062], DM p = 4e-253.

### Learning curve (most recent n training rows, scored on the 20 % holdout)

| n | 25 | 50 | 100 | 200 | 400 | 800 / 1,000 | all |
|---|---|---|---|---|---|---|---|
| A model / mean (packages) | 4.45 / 2.95 | 5.02 / 2.67 | 5.99 / 2.69 | 3.21 / 2.69 | **1.38** / 2.72 | 1.36 / 2.70 | 1.37 / 2.69 |
| B model / mean (kg) | 0.178 / 0.196 | 0.195 / 0.189 | 0.176 / 0.186 | 0.159 / 0.185 | **0.126** / 0.184 | 0.116 / 0.184 | 0.116 / 0.184 |

How much real data do we need? About **400 production orders** to beat the mean. With fewer
rows the model is *worse* than the mean, because a few months cannot separate season from the
other inputs. Butchery needs **100-200 operations** to beat the mean and about 1,000 to plateau.
Recommendation: serve the historical mean until those thresholds are met.

### Stacking audit (committed OOF files, same 302 / 500 holdout rows)

| | MAE | stack - this model [95 % CI] | DM p |
|---|---|---|---|
| A `ali_ridge` | 0.974 yield pts | -0.020 [-0.042, -0.003] | 0.047 |
| A `nuri_huber` (best base) | 0.954 | +0.0003 [-0.014, 0.014] | 0.97 |
| A `nuri_stack_meta` | 0.954 | | |
| B `ali_ridge` | 0.538 share pts | -0.0027 [-0.0042, -0.0012] | 0.0003 |
| B `nuri_simplex_ridge` (best base) | 0.535 | +0.0003 [+0.0001, +0.0006] (stack worse) | 0.023 |
| B `nuri_stack_meta` | 0.536 | | |

The stack never beats its best single base model, so it does not earn its keep. Its edge over
`ali_ridge` in A comes from Huber's robust loss (0.03 packages per order), and in the
rolling-origin comparison Huber's edge over Ridge is not significant. **Decision: no stacking; a
single Ridge is served.** Nuri's code and artifacts are untouched; this is an audit, not a change.

## 3. Model changes: kept or dropped

| Change | Evidence | Decision |
|---|---|---|
| Rolling-origin CV + bootstrap CIs + DM test | replaces one split and hash folds | **kept** (`evaluate_ali.py`) |
| ElasticNet or Huber instead of Ridge | -0.006 / -0.008 packages, CI contains 0 | dropped (no significant gain) |
| LightGBM (nested grid), CatBoost | worse than Ridge (1.40 / 1.35 vs 1.31) | dropped |
| Physics baseline a - b/meat_kg | equal to the historical mean | kept as a reference only |
| Optuna | not installed; a small explicit grid with nested time-ordered validation does the same job with no new dependency | not used |
| Task A predicts the yield ratio, EKP via the formula | already the design; confirmed | kept |
| Task B ALR compositional model | 0.119 vs 0.118 kg (not better); both satisfy the constraint | dropped as champion, kept as a tested alternative |
| Task B simplex projection | violation rate 2.0 % -> 0 % | kept |
| Time-ordered split-conformal intervals (80 / 90 / 98 %) | coverage 79.3 % (A), 80.4 % (B) rolling; 83.1 % / 80.8 % on the holdout | **kept** (replaces the hash-OOF 90 % interval) |
| CQR (LightGBM quantiles) | coverage 81.7 %, intervals 25 % wider | dropped |
| `anomaly` on the 98 % band, not the 80 % interval | 98 %: precision 92 %, recall 11/14 faults, 0.14 false alarms per 100; 80 %: precision 9 %, 19 false alarms per 100 | **kept** (98 %) |
| Stacking (`nuri_stack_meta`) | not better than its best base model | not used |

### Constraint violation (Task B, 1,250 carcasses, tolerance 50 g)

| | Before (raw independent regressors) | After projection | ALR |
|---|---|---|---|
| Ridge per share (alpha tuned per share) | 2.0 % violate, mean gap 0.009 kg, max 0.19 kg | **0 %**, max gap < 1e-12 kg | 0 % by construction |
| CatBoost multi-output | 0 % (max 0.0002 kg) | 0 % | |
| Negative parts | 0 % | 0 % | impossible |

Ridge with *one* shared alpha already sums to 100 exactly, because the model is linear. The
violation appears as soon as the shares are tuned independently, which is why the projection is
always applied.

### Intervals (rolling-origin, champion)

| | 80 % | 90 % | 98 % |
|---|---|---|---|
| A yield coverage / half-width | 79.3 % / +/- 1.34 pts (folds 70-84 %) | 90.6 % / +/- 1.84 | 98.4 % / +/- 5.2 |
| B per-part coverage / half-width | 80.4 % / +/- 0.19 kg (parts 79-82 %) | 90.2 % / +/- 0.25 kg | 98.1 % / +/- 0.36 kg |
| B all six parts inside at once | 29 % | 56 % | 89 % |

## 4. Explainability

Permutation importance on the holdout (raw inputs, 10 repeats), with a what-if direction sentence
per feature. Stored in `evaluation.json` (`permutation_importance`) and listed in `docs/AI.md`.
Charts: `docs/ai/production_pred_vs_actual.png`, `docs/ai/learning_curve.png`.

## 5. Tests (`tests/test_evaluation.py`, all write to tmp_path)

- Part-sum constraint (projection and ALR), ALR round trip, violation detection.
- Finite-sample conformal quantile; 80 % coverage within [72 %, 88 %].
- Chronological folds with no shared dates; Diebold-Mariano sanity check.
- Reproducibility: two runs give identical JSON.
- The trained bundle carries the 80/90/98 % bands, and the old `interval90_*` keys are intact.
- `predict_ali` keeps all of its old columns.
- `/ai/summary` backward compatibility: the frozen key lists equal the `json:"..."` tags parsed
  from `api-go/controllers/ai_controller.go`, every old key is present with its type, and only the
  documented optional keys are added.

`tests/test_nuri.py` now trains into a tmp dir instead of rewriting `artifacts/nuri/`.

## 6. Before / after

| | Before (`ali-1.0.0`) | After (`ali-1.1.0`) |
|---|---|---|
| Validation | 1 time split + hash-fold CV in train | 5-fold rolling origin (selection on folds 1-3) + the same time split |
| Metric uncertainty | none | fold mean +/- std, block-bootstrap 95 % CI |
| Significance vs baseline | none | DM test + paired bootstrap CI |
| Metrics | MAE, MAPE | + wMAPE, EUR cost error per order, kg misallocated per carcass |
| A packages MAE | 1.37 (one split) | 1.31 [1.20, 1.42] rolling; 1.37 [1.20, 1.54] on the same split (model unchanged) |
| B part kg MAE | 0.115 (one split) | 0.118 [0.116, 0.122] rolling; 0.115 on the same split |
| Interval | 90 %, hash-OOF residuals, holdout coverage 89.1 % / 89.8 % | 80 % shown (+90 %, 98 %), time-ordered calibration; coverage 79.3 % / 80.4 % rolling, 83.1 % / 80.8 % holdout |
| Anomaly in `predict_ali` | outside the 90 % interval (about 10 % of normal records) | outside the 98 % band: 0.14 false alarms per 100 normal orders |
| Mass balance | projection (not measured) | measured: 2.0 % -> 0 % violations; ALR alternative tested |
| Learning curve | none | A needs about 400 orders, B 100-200 carcasses |
| Explainability | CatBoost importances only | permutation importance + direction sentences |
| Stacking | presented as the winner | audited: no gain over its best base model; not used |

The champion models and their point predictions are unchanged. The headline is the same as
before, now with evidence: the model roughly halves the error of the historical mean, and the
CIs are far from overlapping.

## 7. `/ai/summary`: new OPTIONAL fields

All existing fields keep their names, types and meanings. A preview of the payload is in
`artifacts/ali/ai_summary_preview.json`, built by `ai_summary.py`.

`metrics[]` (`AIMetricDto`):

| Field | Type | Meaning |
|---|---|---|
| `wmape_pct` | number | sum of abs errors / sum of actuals x 100, on the same rows |
| `mae_ci_low`, `mae_ci_high` | number | block-bootstrap 95 % CI of `mae` |
| `cv_mae_mean`, `cv_mae_std`, `cv_folds` | number, number, int | rolling-origin MAE across folds (only `actual_qty` rows for now) |

`comparisons[]` (`AIComparisonDto`):

| Field | Type | Meaning |
|---|---|---|
| `interval_low`, `interval_high` | number | conformal interval for `model_pred`, same unit (packages / EUR / kg); the EKP bounds come from the package bounds through the cost formula |
| `interval_level` | number | 0.8 |
| `anomaly` | bool | actual outside the 98 % band (absent for `ekp`, and when there is no actual) |
| `outside_interval` | bool | actual outside the shown 80 % interval (informational) |

Status after the merge into main: the `comparisons[]` fields are live. `ml_prediction` has the nullable
`interval_low`, `interval_high`, `interval_level`, `is_anomaly` columns, `predict_ali.py --emit-sql` writes
them, `seed.sql` carries them for the demo records (`ali-1.1.0`), the Go API passes them through, and the
dashboard and Android app show them. The `metrics[]` fields (CIs, CV) are still only in the preview.
Serving them needs nullable columns on `ml_model_metric`
(`wmape_pct`, `mae_ci_low`, `mae_ci_high`, `cv_mae_mean`, `cv_mae_std`, `cv_folds`), matching pointer
fields with `omitempty` in `ai_controller.go`, and the values in the `export_ai_report.py` INSERTs.

`anomaly_metrics.json` was regenerated because its `rule_interval90` reference now uses the
time-calibrated 90 % width; the detector itself is unchanged.
