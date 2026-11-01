# Challenger models vs Ridge

Rolling origin, 5 folds; pooled over 748 production orders / 1,250 carcasses. Dev = folds 1-3 (used to choose), holdout = folds 4-5 (latest 20 %). Holdout diff vs Ridge = paired block-bootstrap 95 % CI; negative means better than Ridge.

## A, production (packages MAE)

| Model | Packages MAE [95 % CI] | EKP MAE (EUR) | Dev | Holdout | Holdout diff vs Ridge [95 % CI] | 80 % coverage |
|---|---|---|---|---|---|---|
| baseline_recipe | 3.205 [2.975, 3.434] | 0.3000 | 3.175 | 3.249 | +1.885 [1.584, 2.239] |  |
| baseline_hist_mean | 2.548 [2.349, 2.750] | 0.2450 | 2.464 | 2.675 | +1.311 [1.071, 1.547] |  |
| ridge | 1.307 [1.197, 1.418] | 0.1283 | 1.268 | 1.364 | - | 79.3 % |
| huber | 1.299 [1.193, 1.412] | 0.1275 | 1.259 | 1.361 | -0.003 [-0.031, 0.020] | 80.7 % |
| lightgbm | 1.396 [1.289, 1.503] | 0.1354 | 1.374 | 1.428 | +0.064 [0.002, 0.134] | 81.7 % |
| catboost | 1.350 [1.251, 1.463] | 0.1314 | 1.331 | 1.378 | +0.014 [-0.041, 0.078] | 80.9 % |
| random_forest | 1.560 [1.426, 1.696] | 0.1492 | 1.497 | 1.655 | +0.291 [0.171, 0.401] | 79.5 % |
| xgboost | 1.353 [1.245, 1.461] | 0.1318 | 1.341 | 1.371 | +0.007 [-0.053, 0.077] | 79.1 % |
| ridge+lgbm_residual | 1.300 [1.197, 1.410] | 0.1282 | 1.273 | 1.342 | -0.022 [-0.054, 0.020] | 80.6 % |
| avg(ridge,catboost) | 1.299 [1.199, 1.410] | 0.1274 | 1.268 | 1.346 | -0.018 [-0.047, 0.018] | 80.2 % |
| avg(ridge,lightgbm) | 1.322 [1.216, 1.431] | 0.1297 | 1.292 | 1.369 | +0.005 [-0.029, 0.040] | 81.0 % |
| stack(ridge,catboost,lightgbm) | 1.298 [1.189, 1.408] | 0.1274 | 1.270 | 1.339 | -0.025 [-0.046, -0.001] | 79.4 % |

Selection rule result: **ridge** (best on dev: huber).

## B, butchery (part kg MAE, 6 parts)

| Model | kg MAE [95 % CI] | Dev | Holdout | Holdout diff vs Ridge [95 % CI] | 80 % coverage |
|---|---|---|---|---|---|
| baseline_recipe | 0.1862 [0.181, 0.191] | 0.1869 | 0.1852 | +0.0703 [0.065, 0.076] |  |
| baseline_hist_mean | 0.1843 [0.180, 0.189] | 0.1844 | 0.1842 | +0.0693 [0.064, 0.075] |  |
| ridge_project | 0.1185 [0.116, 0.122] | 0.1208 | 0.1149 | - | 80.4 % |
| lightgbm_alr | 0.1240 [0.121, 0.127] | 0.1264 | 0.1205 | +0.0057 [0.004, 0.007] | 80.5 % |
| catboost_project | 0.1201 [0.117, 0.123] | 0.1226 | 0.1164 | +0.0015 [0.000, 0.003] | 80.2 % |
| random_forest_project | 0.1284 [0.125, 0.132] | 0.1313 | 0.1240 | +0.0091 [0.007, 0.011] | 80.5 % |
| xgboost_project | 0.1208 [0.118, 0.124] | 0.1232 | 0.1171 | +0.0022 [0.001, 0.004] | 80.3 % |
| ridge+lgbm_residual_project | 0.1189 [0.116, 0.122] | 0.1214 | 0.1150 | +0.0001 [-0.000, 0.001] | 80.3 % |
| avg(ridge,catboost)_project | 0.1186 [0.116, 0.122] | 0.1209 | 0.1152 | +0.0003 [-0.000, 0.001] | 80.1 % |

Selection rule result: **ridge_project** (best on dev: ridge_project).
