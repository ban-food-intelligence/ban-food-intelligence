"""Ali - KI anomali uyarıları (isteğe bağlı KI özelliği: düşük verim / beklenmedik fire).

Fikir: verim modeli "bu koşullarda ne çıkmalıydı" sorusunu cevaplar. Gerçek değer bundan
modelin normal hata payının çok ötesinde saparsa uyarı üretilir. Sabit bir kural
("verim %82'nin altındaysa") yağsız et, uzun kurutma, yaz ayı gibi meşru sebepleri de
alarm sanır; model bunları zaten bildiği için yalnız açıklanamayan sapma kalır.

  4A üretim   : artık r = gerçek verim − tahmin. Ölçek OOF artıklarının medyan/MAD'ı (arızalı
                satırlar ölçeği şişirmesin diye sağlam istatistik). z = (r − medyan) / (1,4826·MAD).
                z ≤ −eşik -> LOW_YIELD, z ≥ +kritik eşik -> HIGH_YIELD (sayım/kayıt hatası).
  4B parçalama: 6 payın artık vektörü birlikte değerlendirilir. Paylar toplamı 100 olduğu için
                artıklar ilişkilidir (but azalınca fire artar); sağlam kovaryans (MinCovDet) ile
                Mahalanobis uzaklığı d² hesaplanır. En çok sapan parçalar uyarı metnine yazılır
                (ör. "fire +0,39 kg, but −0,29 kg").

Ölçüm (kesin etiket gerektiği için sentetik): geçmişe bilinen anomaliler enjekte edilir,
eşikler YALNIZ train OOF üzerinde seçilir, sonuç zaman bazlı test kümesinde raporlanır;
iki basit kuralla (reçete eşiği, mevcut %90 aralık) kıyaslanır. Bkz. README_ali.md.

  python ml-engine/train_model_ali.py      # önce: OOF tahminleri (artifacts/ali/oof_ali_*.csv)
  python ml-engine/anomaly_ali.py          # -> artifacts/ali/anomaly_metrics.json, anomaly_detector.joblib
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

import joblib
import numpy as np
import pandas as pd
from sklearn.covariance import MinCovDet
from sklearn.metrics import average_precision_score

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import generator_ali as G  # noqa: E402
import ml_contract as C  # noqa: E402

DETECTOR_VERSION = "ali-anomaly-1.0.0"
ART = HERE / "artifacts" / "ali"
MAD_TO_SIGMA = 1.4826
CRITICAL_FALSE_ALARM = 0.001          # kritik eşik: temiz train satırlarında en fazla %0,1 yanlış alarm
INJECT_RATE = 0.06                    # her turda satırların %6'sına anomali
N_ROUNDS = 30                         # farklı rastgele enjeksiyonlarla tekrar -> ortalama ± std
PROD_DROP_PTS = (1.5, 6.0)            # enjekte verim düşüşü (puan); doğal arızalar 4-10 puan
BUTCH_SHIFT_PCT = (1.0, 4.0)          # karkasın %1-4'ü (25 kg kuzuda 0,25-1 kg) yanlış yere
BUTCH_LOSS_SHARE = 0.6                # %60 "parça fireye gitti", %40 "parça yanlış etiketlendi"
RECIPE_FIRE_PCT = {"KUZU": 12.0, "OGLAK": 16.0, "KOYUN": 11.0}


# ---------------------------------------------------------------- dedektörler
def fit_production_detector(resid_train: np.ndarray, clean_train: np.ndarray, t_warn: float) -> dict:
    center = float(np.median(resid_train))
    scale = float(MAD_TO_SIGMA * np.median(np.abs(resid_train - center)))
    z_clean = (resid_train[clean_train] - center) / scale
    t_crit = max(t_warn, float(np.quantile(np.abs(z_clean), 1 - CRITICAL_FALSE_ALARM)))
    return {"method": "robust_z", "center": center, "scale": scale, "t_warn": float(t_warn), "t_crit": t_crit}


def production_z(resid: np.ndarray, det: dict) -> np.ndarray:
    return (np.asarray(resid, float) - det["center"]) / det["scale"]


def production_level(z: np.ndarray, det: dict) -> tuple[np.ndarray, np.ndarray]:
    """(seviye, yön): düşük verim WARN/CRITICAL; yüksek verim yalnız kritik eşikte (kayıt hatası şüphesi)."""
    z = np.asarray(z, float)
    level = np.select([z <= -det["t_crit"], z <= -det["t_warn"], z >= det["t_crit"]],
                      ["CRITICAL", "WARN", "WARN"], "")
    direction = np.where(z < 0, "LOW_YIELD", "HIGH_YIELD")
    return level, np.where(level == "", "", direction)


def fit_butchery_detector(R_train: np.ndarray, t_warn: float) -> dict:
    """R_train: (n, 6) pay artıkları. Son kolon (fire) diğerlerinden belirlenir -> ilk 5 kolon."""
    mcd = MinCovDet(random_state=0).fit(R_train[:, :5])
    d2 = mcd.mahalanobis(R_train[:, :5])
    # d² kare ölçekli olduğu için kritik eşik en az uyarı eşiğinin 1,5 katı
    t_crit = max(1.5 * t_warn, float(np.quantile(d2, 1 - CRITICAL_FALSE_ALARM)))
    return {"method": "mahalanobis_mcd", "location": mcd.location_, "precision": mcd.get_precision(),
            "part_center": np.median(R_train, axis=0),
            "part_scale": MAD_TO_SIGMA * np.median(np.abs(R_train - np.median(R_train, axis=0)), axis=0),
            "t_warn": float(t_warn), "t_crit": t_crit}


def butchery_d2(R: np.ndarray, det: dict) -> np.ndarray:
    D = np.atleast_2d(R)[:, :5] - det["location"]
    return np.einsum("ij,jk,ik->i", D, det["precision"], D)


def butchery_level(d2: np.ndarray, det: dict) -> np.ndarray:
    d2 = np.asarray(d2, float)
    return np.select([d2 >= det["t_crit"], d2 >= det["t_warn"]], ["CRITICAL", "WARN"], "")


def butchery_direction(R: np.ndarray, det: dict) -> np.ndarray:
    """FIRE_HIGH: fire beklenenden belirgin fazla; aksi halde PART_MISMATCH (parçalar arası kayma)."""
    zf = (np.atleast_2d(R)[:, 5] - det["part_center"][5]) / det["part_scale"][5]
    return np.where(zf >= 2.0, "FIRE_HIGH", "PART_MISMATCH")


# ---------------------------------------------------------------- değerlendirme
def _prf(y: np.ndarray, flag: np.ndarray) -> dict:
    tp = int(np.sum(flag & y))
    fp = int(np.sum(flag & ~y))
    fn = int(np.sum(~flag & y))
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    return {"precision": p, "recall": r, "f1": 2 * p * r / (p + r) if p + r else 0.0,
            "false_alarms_per_100": 100 * fp / len(y)}


def _summary(rounds: list[dict]) -> dict:
    keys = rounds[0].keys()
    return {k: {"mean": float(np.mean([r[k] for r in rounds])), "std": float(np.std([r[k] for r in rounds]))}
            for k in keys}


def _best_threshold(scores: list[np.ndarray], labels: list[np.ndarray], grid: np.ndarray) -> float:
    """Validasyon (train OOF + enjeksiyon) üzerinde ortalama F1'i en yüksek eşik."""
    f1 = [np.mean([_prf(y, s >= t)["f1"] for s, y in zip(scores, labels)]) for t in grid]
    return round(float(grid[int(np.argmax(f1))]), 3)


def inject_production(df: pd.DataFrame, rows: np.ndarray, rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray]:
    """Seçilen satırlarda verimi 1,5-6 puan düşürür (paket sayısı tam sayıya yuvarlanır).
    Dönüş: (yeni yield_pct, enjekte edilen düşüş puanı)."""
    y = df.yield_pct.to_numpy(float).copy()
    drop = np.zeros(len(df))
    d = rng.uniform(*PROD_DROP_PTS, len(rows))
    meat, pack = df.meat_kg.to_numpy(float)[rows], df.pack_size_kg.to_numpy(float)[rows]
    qty = np.floor(meat * (y[rows] - d) / 100 / pack + 1e-9)
    y_new = 100 * qty * pack / meat
    drop[rows] = y[rows] - y_new
    y[rows] = y_new
    return y, drop


def inject_butchery(df: pd.DataFrame, rows: np.ndarray, rng: np.random.Generator) -> tuple[np.ndarray, np.ndarray]:
    """Seçilen karkaslarda karkasın %1-4'ü bir satılabilir parçadan fireye (kayıp/hırsızlık/kötü kesim)
    ya da başka parçaya (yanlış etiket) kaydırılır; kütle dengesi korunur. Dönüş: (yeni paylar, kaydırılan %)."""
    Y = df[C.BUTCHERY["targets"]].to_numpy(float).copy()
    W = df.carcass_weight_kg.to_numpy(float)
    shift = np.zeros(len(df))
    for i in rows:
        src = int(rng.integers(0, 5))
        dst = 5 if rng.random() < BUTCH_LOSS_SHARE else int(rng.choice([j for j in range(5) if j != src]))
        kg = round(W[i] * rng.uniform(*BUTCH_SHIFT_PCT) / 100 / 0.005) * 0.005       # terazi 5 g
        s = min(100 * kg / W[i], Y[i, src] - 0.5)
        Y[i, src] -= s
        Y[i, dst] += s
        shift[i] = s
    return Y, shift


def _pick(rng: np.random.Generator, pool: np.ndarray, n_total: int) -> np.ndarray:
    return rng.choice(pool, size=min(len(pool), max(1, int(INJECT_RATE * n_total))), replace=False)


def evaluate_production(df: pd.DataFrame, pred: np.ndarray, split: np.ndarray, q90: float,
                        n_rounds: int = N_ROUNDS, seed: int = 0) -> tuple[dict, dict]:
    """df: `fault` etiketli üretim verisi (generator_ali, with_fault_label=True), pred: OOF/test verim tahmini."""
    rng = np.random.default_rng(seed)
    fault = df.fault.to_numpy(bool)
    tr, te = split == "train", split == "test"

    def round_(mask):
        rows = _pick(rng, np.where(mask & ~fault)[0], int(mask.sum()))
        y, drop = inject_production(df, rows, rng)
        lab = fault | (drop > 0)
        return y, lab, drop

    # 1) eşik seçimi: yalnız train OOF artıkları (test görülmez)
    val = [round_(tr) for _ in range(n_rounds)]
    resid0 = df.yield_pct.to_numpy(float) - pred
    det0 = fit_production_detector(resid0[tr], ~fault[tr], t_warn=1.0)
    z_val = [-production_z(y - pred, det0)[tr] for y, _, _ in val]
    t_warn = _best_threshold(z_val, [lab[tr] for _, lab, _ in val], np.arange(1.5, 5.01, 0.1))
    det = fit_production_detector(resid0[tr], ~fault[tr], t_warn)
    recipe_scores = [(85.0 - y)[tr] for y, _, _ in val]
    k_recipe = _best_threshold(recipe_scores, [lab[tr] for _, lab, _ in val], np.arange(0.5, 8.01, 0.25))

    # 2) test: yeni enjeksiyon turları, eşikler sabit
    res = {"detector": [], "rule_recipe": [], "rule_interval90": []}
    by_size = {"small_1.5-3": [], "medium_3-4.5": [], "large_4.5+": []}
    ap, n_anom = [], []
    for _ in range(n_rounds):
        y, lab, drop = round_(te)
        n_anom.append(int(lab[te].sum()))
        z = production_z(y - pred, det)
        level, _ = production_level(z, det)
        flag = level != ""
        res["detector"].append(_prf(lab[te], flag[te]))
        res["rule_recipe"].append(_prf(lab[te], (y < 85.0 - k_recipe)[te]))
        res["rule_interval90"].append(_prf(lab[te], (np.abs(y - pred) > q90)[te]))
        ap.append(average_precision_score(lab[te], -z[te]))
        for name, lo, hi in (("small_1.5-3", 1.5, 3), ("medium_3-4.5", 3, 4.5), ("large_4.5+", 4.5, 99)):
            m = te & (drop >= lo) & (drop < hi)
            if m.any():
                by_size[name].append(float(flag[m].mean()))
    report = {
        "n_test": int(te.sum()), "anomalies_per_round_test": float(np.mean(n_anom)),
        "natural_faults_test": int(fault[te].sum()),
        "thresholds": {"z_warn": det["t_warn"], "z_crit": det["t_crit"], "recipe_rule_k_pts": k_recipe,
                       "interval90_half_width_pts": q90, "residual_scale_pts": det["scale"]},
        "test": {k: _summary(v) for k, v in res.items()},
        "detector_average_precision": {"mean": float(np.mean(ap)), "std": float(np.std(ap))},
        "detector_recall_by_drop_pts": {k: float(np.mean(v)) for k, v in by_size.items() if v},
    }
    return report, det


def evaluate_butchery(df: pd.DataFrame, P: np.ndarray, split: np.ndarray, q90: np.ndarray,
                      n_rounds: int = N_ROUNDS, seed: int = 1) -> tuple[dict, dict]:
    rng = np.random.default_rng(seed)
    tr, te = split == "train", split == "test"
    Y0 = df[C.BUTCHERY["targets"]].to_numpy(float)
    recipe_fire = df.species.map(RECIPE_FIRE_PCT).to_numpy(float)

    def round_(mask):
        rows = _pick(rng, np.where(mask)[0], int(mask.sum()))
        Y, shift = inject_butchery(df, rows, rng)
        return Y, shift > 0, shift

    det = fit_butchery_detector((Y0 - P)[tr], t_warn=1.0)
    val = [round_(tr) for _ in range(n_rounds)]
    t_warn = _best_threshold([butchery_d2((Y - P)[tr], det) for Y, _, _ in val], [lab[tr] for _, lab, _ in val],
                             np.arange(8.0, 30.01, 0.5))
    det = fit_butchery_detector((Y0 - P)[tr], t_warn)
    k_fire = _best_threshold([(Y[:, 5] - recipe_fire)[tr] for Y, _, _ in val], [lab[tr] for _, lab, _ in val],
                             np.arange(0.25, 6.01, 0.25))
    by_size = {"small_1-2": [], "medium_2-3": [], "large_3+": []}

    res = {"detector": [], "rule_recipe_fire": [], "rule_interval90": []}
    ap, n_anom = [], []
    for _ in range(n_rounds):
        Y, lab, shift = round_(te)
        n_anom.append(int(lab[te].sum()))
        d2 = butchery_d2(Y - P, det)
        flag = butchery_level(d2, det) != ""
        res["detector"].append(_prf(lab[te], flag[te]))
        res["rule_recipe_fire"].append(_prf(lab[te], (Y[:, 5] > recipe_fire + k_fire)[te]))
        res["rule_interval90"].append(_prf(lab[te], (np.abs(Y - P) > q90).any(axis=1)[te]))
        ap.append(average_precision_score(lab[te], d2[te]))
        for name, lo, hi in (("small_1-2", 0.5, 2), ("medium_2-3", 2, 3), ("large_3+", 3, 99)):
            m = te & (shift >= lo) & (shift < hi)
            if m.any():
                by_size[name].append(float(flag[m].mean()))
    report = {
        "n_test": int(te.sum()), "anomalies_per_round_test": float(np.mean(n_anom)),
        "thresholds": {"d2_warn": det["t_warn"], "d2_crit": det["t_crit"], "recipe_fire_rule_k_pts": k_fire},
        "test": {k: _summary(v) for k, v in res.items()},
        "detector_average_precision": {"mean": float(np.mean(ap)), "std": float(np.std(ap))},
        "detector_recall_by_shift_pct_of_carcass": {k: float(np.mean(v)) for k, v in by_size.items() if v},
    }
    return report, det


# ---------------------------------------------------------------- uyarı üretimi (predict_ali.py kullanır)
def production_alerts(df: pd.DataFrame, pred_yield: np.ndarray, pred_ekp: np.ndarray, det: dict) -> pd.DataFrame:
    """Gerçek miktarı bilinen üretim emirleri için uyarı satırları (seviye boşsa uyarı yok)."""
    actual_yield = 100 * df.actual_qty.to_numpy(float) * df.pack_size_kg.to_numpy(float) / df.meat_kg.to_numpy(float)
    z = production_z(actual_yield - pred_yield, det)
    level, direction = production_level(z, det)
    pred_qty = C.production_qty_from_yield(df, pred_yield)
    missing = pred_qty - df.actual_qty.to_numpy(float)
    loss = np.where(direction == "LOW_YIELD", np.maximum(missing, 0) * pred_ekp, 0.0)
    msg = [("" if not lv else
            f"Verim beklenenden düşük: {a:.0f} paket, beklenen {p:.1f} (≈{l:.0f} € kayıp). "
            "Fason kurutma/dolum ve et lotunu kontrol edin." if d == "LOW_YIELD" else
            f"Verim beklenenden yüksek: {a:.0f} paket, beklenen {p:.1f}. Sayım/kayıt hatası olabilir.")
           for lv, d, a, p, l in zip(level, direction, df.actual_qty, pred_qty, loss)]
    return pd.DataFrame({"row_id": df.row_id.to_numpy(), "score": np.round(z, 3), "severity": level,
                         "direction": direction, "expected_value": np.round(pred_qty, 3),
                         "actual_value": df.actual_qty.to_numpy(float), "deviation": np.round(-missing, 3),
                         "est_loss_eur": np.round(loss, 2), "message": msg})


def butchery_alerts(df: pd.DataFrame, P: np.ndarray, det: dict, top: int = 2) -> pd.DataFrame:
    Y = df[[f"{p}_kg" for p in C.BUTCHERY_PARTS]].to_numpy(float) / df.carcass_weight_kg.to_numpy(float)[:, None] * 100
    R = Y - P
    d2 = butchery_d2(R, det)
    level = butchery_level(d2, det)
    direction = butchery_direction(R, det)
    W = df.carcass_weight_kg.to_numpy(float)
    rows = []
    for i in range(len(df)):
        dev_kg = R[i] * W[i] / 100
        order = np.argsort(-np.abs(R[i] / det["part_scale"]))[:top]
        top_parts = ", ".join(f"{C.BUTCHERY_PARTS[j]} {dev_kg[j]:+.2f} kg" for j in order)
        fire_dev = dev_kg[5]
        loss = max(fire_dev, 0) * float(df.purchase_unit_price.iloc[i])
        lv = level[i]
        if not lv:
            msg = ""
        elif direction[i] == "FIRE_HIGH":
            msg = f"Beklenmedik fire: {top_parts} (≈{loss:.0f} € kayıp). Kesim/tartım ve soğutmayı kontrol edin."
        else:
            msg = f"Parça dağılımı beklenenden farklı: {top_parts}. Etiket/tartım hatası olabilir."
        rows.append({"row_id": df.row_id.iloc[i], "score": round(float(d2[i]), 3), "severity": lv,
                     "direction": direction[i] if lv else "", "expected_value": round(P[i, 5] * W[i] / 100, 3),
                     "actual_value": round(float(Y[i, 5] * W[i] / 100), 3), "deviation": round(float(fire_dev), 3),
                     "est_loss_eur": round(loss, 2) if lv else 0.0, "top_parts": top_parts, "message": msg})
    return pd.DataFrame(rows)


# ---------------------------------------------------------------- akış
def _champion_preds(oof: pd.DataFrame, champion: str, row_ids: pd.Series, targets: list[str]):
    o = oof[(oof.model == champion) & oof.row_id.isin(set(row_ids))]
    wide = o.pivot(index="row_id", columns="target", values="y_pred").loc[row_ids, targets]
    split = o.drop_duplicates("row_id").set_index("row_id").loc[row_ids, "split"].to_numpy()
    return wide.to_numpy(float), split


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--art", default=str(ART))
    ap.add_argument("--seed", type=int, default=42, help="generator_ali.py ile aynı seed (CSV'yi yeniden üretir)")
    ap.add_argument("--rounds", type=int, default=N_ROUNDS)
    args = ap.parse_args()
    art = pathlib.Path(args.art)

    metrics = json.loads((art / "metrics.json").read_text(encoding="utf-8"))
    rng = np.random.default_rng(args.seed)
    prod = G.generate_production(1500, rng, with_fault_label=True)
    butch = G.generate_butchery(2500, rng)
    csv = pd.read_csv(HERE / "data" / "train_task4a_ali.csv")
    assert csv.row_id.equals(prod.row_id) and np.allclose(csv.yield_pct, prod.yield_pct), \
        "CSV generator_ali.py varsayılanlarıyla üretilmemiş; önce generator_ali.py çalıştırın"

    oof_p = pd.read_csv(art / "oof_ali_production.csv")
    oof_b = pd.read_csv(art / "oof_ali_butchery.csv")
    mp, mb = metrics["production"], metrics["butchery"]
    pred_p, split_p = _champion_preds(oof_p, mp["champion"], prod.row_id, ["yield_pct"])
    P_b, split_b = _champion_preds(oof_b, mb["champion"], butch.row_id, C.BUTCHERY["targets"])

    rep_p, det_p = evaluate_production(prod, pred_p[:, 0], split_p, mp["interval90_yield_pct"]["half_width"],
                                       n_rounds=args.rounds)
    rep_b, det_b = evaluate_butchery(butch, P_b, split_b, np.array(list(mb["interval90_pct"].values())),
                                     n_rounds=args.rounds)
    joblib.dump({"production": det_p, "butchery": det_b, "version": DETECTOR_VERSION}, art / "anomaly_detector.joblib")
    report = {"version": DETECTOR_VERSION, "inject_rate": INJECT_RATE, "rounds": args.rounds,
              "production": rep_p, "butchery": rep_b}
    (art / "anomaly_metrics.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    def line(name, s):
        return (f"  {name:18s} precision {s['precision']['mean']:.2f}  recall {s['recall']['mean']:.2f}"
                f"  F1 {s['f1']['mean']:.2f} ± {s['f1']['std']:.2f}  yanlış alarm/100 {s['false_alarms_per_100']['mean']:.1f}")
    print(f"== 4A düşük verim uyarısı (test, {args.rounds} enjeksiyon turu) ==")
    for k, s in rep_p["test"].items():
        print(line(k, s))
    print(f"  eşik z ≤ −{det_p['t_warn']:.1f} (kritik −{det_p['t_crit']:.1f}), "
          f"düşüş büyüklüğüne göre recall {rep_p['detector_recall_by_drop_pts']}")
    print(f"== 4B beklenmedik fire / parça kayması (test) ==")
    for k, s in rep_b["test"].items():
        print(line(k, s))
    print(f"  eşik d² ≥ {det_b['t_warn']:.1f} (kritik {det_b['t_crit']:.1f}), "
          f"kayma büyüklüğüne göre recall {rep_b['detector_recall_by_shift_pct_of_carcass']}")


if __name__ == "__main__":
    main()
