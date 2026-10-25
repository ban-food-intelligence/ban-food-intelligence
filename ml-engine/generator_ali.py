"""Ali - sentetik eğitim verisi üreteci (Görev 4A ve 4B).

Yarışmada gerçek veri verilmediği için geçmiş üretim emirleri ve parçalama
kayıtları, şartnamedeki rakamlara sabitlenmiş bir süreç modeliyle üretilir:

  4A  100 kg et + 2 kg baharat + 5 kg bağırsak -> 85 × 1 kg Narin Sucuk
      (standart koşullarda verim %85), et 9,70 €/kg, baharat 24,00 €/kg,
      bağırsak 11,60 €/kg, hizmet 5,50 €/paket  ->  EKP 18,16 €
  4B  Bütün kuzu 25 kg × 11,50 €/kg; parçalar but/sırt/omuz/kaburga/kıyma
      22 kg + 3 kg kemik/fire (reçete %26/%14/%20/%12/%16/%12)

Çıktılar ml-engine/DATA_CONTRACT.md'deki ortak şemaya uyar; böylece Berke ve
Nuri'nin üreteçleri ile birleştirilip aynı fold'larla stacking yapılabilir.

Kullanım:
  python ml-engine/generator_ali.py            # -> ml-engine/data/train_task4a_ali.csv, train_task4b_ali.csv
"""
from __future__ import annotations

import argparse
import pathlib

import numpy as np
import pandas as pd

HISTORY_START = "2024-01-02"
HISTORY_END = "2026-09-30"          # seed kayıtlarından (Ekim 2026) önceki geçmiş

PARTS = ["but", "sirt", "omuz", "kaburga", "kiyma", "fire"]

# Tür bazında reçete payları (%); KUZU satırı şartnamedeki örnekle aynı: 22 kg parça + 3 kg fire / 25 kg
RECIPE_PCT = {
    "KUZU":  np.array([26.0, 14.0, 20.0, 12.0, 16.0, 12.0]),
    "OGLAK": np.array([25.0, 13.0, 21.0, 11.0, 14.0, 16.0]),
    "KOYUN": np.array([24.0, 13.0, 20.0, 13.0, 19.0, 11.0]),
}
SPECIES_WEIGHT = {"KUZU": (22.0, 4.0, 12, 35), "OGLAK": (14.0, 3.0, 8, 22), "KOYUN": (30.0, 5.0, 20, 45)}
SPECIES_PRICE = {"KUZU": 11.50, "OGLAK": 12.40, "KOYUN": 9.20}
BREEDS = {
    "KUZU":  (["KIVIRCIK", "AKKARAMAN", "MERINOS", "KARAYAKA"], [0.4, 0.3, 0.2, 0.1]),
    "KOYUN": (["KIVIRCIK", "AKKARAMAN", "MERINOS", "KARAYAKA"], [0.3, 0.4, 0.2, 0.1]),
    "OGLAK": (["KIL_KECISI", "SAANEN"], [0.7, 0.3]),
}
# ırk etkisi (yüzde puan): but, sırt, omuz, kaburga, kıyma, fire
BREED_EFFECT = {
    "KIVIRCIK":   np.array([+0.8, +0.3, 0.0, -0.2, -0.4, -0.5]),
    "AKKARAMAN":  np.array([-0.3, -0.2, +0.2, +0.3, +0.4, -0.4]),
    "MERINOS":    np.array([+0.4, +0.2, 0.0, 0.0, -0.2, -0.4]),
    "KARAYAKA":   np.array([-0.5, -0.3, 0.0, 0.0, +0.3, +0.5]),
    "KIL_KECISI": np.array([-0.3, 0.0, 0.0, 0.0, -0.2, +0.5]),
    "SAANEN":     np.array([+0.3, 0.0, 0.0, 0.0, +0.2, -0.5]),
}
# kasap becerisi: iyi kasap fireyi azaltır, kırpıntıyı kıymaya kazandırır
OPERATOR_SKILL = {"OP-1": 0.6, "OP-2": 0.0, "OP-3": -0.4, "OP-4": -1.0}
SUBCONTRACTORS = {"FAS-01": 0.000, "FAS-02": -0.015, "FAS-03": +0.008}
MEAT_SUPPLIERS = {"TED-ET-01": 0.000, "TED-ET-02": -0.006, "TED-ET-03": +0.004}
PRODUCTS = {"MM-SUCUK-1KG": 1.0, "MM-SUCUK-500G": 0.5}


def _years_since_spec(dates: pd.Series) -> np.ndarray:
    """Şartname fiyatları Ekim 2026'ya aittir; geçmişe doğru enflasyonla iner."""
    return ((dates - pd.Timestamp("2026-10-01")).dt.days / 365.25).to_numpy()


def _random_dates(rng, n):
    days = pd.date_range(HISTORY_START, HISTORY_END, freq="D")
    days = days[days.dayofweek < 6]                       # pazar üretim yok
    return pd.Series(np.sort(rng.choice(days, size=n))).dt.normalize()


def generate_production(n: int, rng: np.random.Generator) -> pd.DataFrame:
    dates = _random_dates(rng, n)
    t = _years_since_spec(dates)
    month = dates.dt.month.to_numpy()

    product = rng.choice(list(PRODUCTS), size=n, p=[0.8, 0.2])
    pack_kg = np.array([PRODUCTS[p] for p in product])
    sub = rng.choice(list(SUBCONTRACTORS), size=n, p=[0.5, 0.3, 0.2])
    msup = rng.choice(list(MEAT_SUPPLIERS), size=n, p=[0.6, 0.25, 0.15])

    meat_kg = np.clip(np.round(rng.lognormal(np.log(110), 0.35, n) / 5) * 5, 50, 300)
    spice_kg = np.round(meat_kg * rng.normal(0.020, 0.0012, n), 2)
    casing_kg = np.round(meat_kg * rng.normal(0.050, 0.0025, n), 2)
    fat = np.round(np.clip(rng.normal(22, 2.8, n), 14, 32), 1)
    age = rng.integers(0, 6, n)                           # et girişinden üretime gün
    drying = rng.choice([4, 5, 6, 7], size=n, p=[0.15, 0.55, 0.22, 0.08])

    # fiyatlar: Ekim 2026'da şartname değerleri, yıllık ~%6 enflasyon, mevsimsel dalga
    seasonal = 1 + 0.025 * np.sin(2 * np.pi * (month - 4) / 12)
    supplier_price = np.array([{"TED-ET-01": 1.0, "TED-ET-02": 0.97, "TED-ET-03": 1.03}[s] for s in msup])
    meat_price = np.round(9.70 * np.exp(0.06 * t) * seasonal * supplier_price * rng.normal(1, 0.015, n), 2)
    spice_price = np.round(24.00 * np.exp(0.05 * t) * rng.normal(1, 0.02, n), 2)
    casing_price = np.round(11.60 * np.exp(0.04 * t) * rng.normal(1, 0.02, n), 2)
    service_price = np.select([dates.dt.year == 2024, dates.dt.year == 2025], [5.00, 5.25], 5.50)
    service_price = np.where(product == "MM-SUCUK-500G", np.round(service_price * 0.6, 2), service_price)

    # verim süreci (çıkan sucuk kg / et kg). Standart koşul -> 0,85 (şartname)
    summer = np.isin(month, [6, 7, 8])
    y = (0.855                                                 # 0,855 − 0,40/100 kg sabit kayıp ≈ 0,85
         + 0.004 * (fat - 22)                                  # yağlı et daha az su kaybeder
         - 0.012 * (drying - 5)                                # uzun kurutma -> daha fazla kayıp
         - 0.003 * np.maximum(age - 2, 0)                      # bekleyen et su salar
         + np.array([SUBCONTRACTORS[s] for s in sub])
         + np.array([MEAT_SUPPLIERS[s] for s in msup])
         - 0.008 * summer
         - 0.010 * (summer & (sub == "FAS-02"))                # FAS-02 yazın aşırı kurutuyor (etkileşim)
         - 0.40 / meat_kg                                      # sabit makine/dolum kaybı (küçük partide büyük)
         - 0.006 * (product == "MM-SUCUK-500G")                # ince kalibre daha çok kurur
         + rng.normal(0, 0.009, n))
    anomaly = rng.random(n) < 0.02                             # makine arızası / soğuk zincir sorunu
    y = y - anomaly * rng.uniform(0.04, 0.10, n)

    actual_kg = meat_kg * y
    actual_qty = np.floor(actual_kg / pack_kg + 1e-9)
    planned_qty = np.floor(meat_kg * 0.85 / pack_kg)

    # gözlemlenmeyen laboratuvar sonuçları (gerçekçi eksik veri)
    fat_obs = np.where(rng.random(n) < 0.04, np.nan, fat)

    material = meat_kg * meat_price + spice_kg * spice_price + casing_kg * casing_price
    ekp = (material + actual_qty * service_price) / actual_qty

    return pd.DataFrame({
        "row_id": [f"ALI-P-{i:06d}" for i in range(1, n + 1)],
        "source": "ali",
        "order_date": dates.dt.strftime("%Y-%m-%d"),
        "product_sku": product,
        "pack_size_kg": pack_kg,
        "subcontractor_code": sub,
        "meat_supplier_code": msup,
        "meat_kg": meat_kg,
        "spice_kg": spice_kg,
        "casing_kg": casing_kg,
        "meat_fat_pct": fat_obs,
        "meat_age_days": age,
        "drying_days": drying,
        "meat_unit_price": meat_price,
        "spice_unit_price": spice_price,
        "casing_unit_price": casing_price,
        "service_unit_price": service_price,
        "planned_qty": planned_qty,
        "actual_qty": actual_qty,
        "yield_pct": np.round(100 * actual_qty * pack_kg / meat_kg, 3),
        "ekp": np.round(ekp, 4),
    })


def generate_butchery(n: int, rng: np.random.Generator) -> pd.DataFrame:
    dates = _random_dates(rng, n)
    t = _years_since_spec(dates)
    month = dates.dt.month.to_numpy()
    species = rng.choice(list(RECIPE_PCT), size=n, p=[0.75, 0.15, 0.10])

    rows = []
    for i in range(n):
        sp = species[i]
        mu, sd, lo, hi = SPECIES_WEIGHT[sp]
        # ilkbaharda kuzular hafif, sonbaharda ağır
        mu = mu + (1.5 * np.sin(2 * np.pi * (month[i] - 7) / 12) if sp == "KUZU" else 0)
        weight = float(np.round(np.clip(rng.normal(mu, sd), lo, hi), 2))
        breeds, bp = BREEDS[sp]
        breed = rng.choice(breeds, p=bp)
        age = float(np.round({"KUZU": rng.uniform(3, 8), "OGLAK": rng.uniform(3, 7), "KOYUN": rng.uniform(14, 60)}[sp], 1))
        fat_score = int(np.clip(np.round(rng.normal(3 + (weight - SPECIES_WEIGHT[sp][0]) / 8, 0.8)), 1, 5))
        conf = int(np.clip(np.round(rng.normal(3 + (breed in ("KIVIRCIK", "MERINOS")) * 0.4, 0.9)), 1, 5))
        operator = rng.choice(list(OPERATOR_SKILL), p=[0.35, 0.3, 0.2, 0.15])
        chill = float(rng.choice([12, 18, 24, 36, 48], p=[0.1, 0.15, 0.5, 0.15, 0.1]))
        supplier = rng.choice(["TED-HAY-01", "TED-HAY-02", "TED-HAY-03"], p=[0.5, 0.3, 0.2])
        sex = rng.choice(["E", "D"])

        pct = RECIPE_PCT[sp].copy()
        dw = weight - SPECIES_WEIGHT[sp][0]
        skill = OPERATOR_SKILL[operator]
        pct += (fat_score - 3) * np.array([-0.5, 0.0, -0.1, +0.4, +0.8, -0.3])
        pct += (conf - 3) * np.array([+0.6, +0.3, 0.0, -0.1, -0.3, -0.5])
        pct += dw * np.array([+0.05, +0.02, 0.0, +0.03, +0.10, -0.15])
        pct += skill * np.array([+0.3, +0.2, 0.0, 0.0, +0.5, -1.0])
        pct += BREED_EFFECT[breed]
        pct += (chill < 24) * np.array([0, 0, 0, 0, -0.3, +0.6])            # sıcak parçalama: damlama kaybı
        pct += (sex == "D") * np.array([-0.3, 0, 0, +0.1, +0.3, -0.1])
        pct += rng.normal(0, [0.8, 0.6, 0.8, 0.6, 0.9, 0.7])
        pct = np.clip(pct, 2, None)
        pct = 100 * pct / pct.sum()

        kg = np.round(weight * pct[:5] / 100 / 0.005) * 0.005               # terazi hassasiyeti 5 g
        fire = round(weight - kg.sum(), 3)                                   # kütle dengesi tam
        price = round(SPECIES_PRICE[sp] * np.exp(0.06 * t[i]) * rng.normal(1, 0.02), 2)
        rows.append([sp, breed, supplier, operator, weight, age, sex, fat_score, conf, chill, price,
                     *np.round(kg, 3), fire])

    df = pd.DataFrame(rows, columns=["species", "breed", "supplier_code", "operator_code", "carcass_weight_kg",
                                     "age_months", "sex", "fat_score", "conformation_score", "chill_hours",
                                     "purchase_unit_price"] + [f"{p}_kg" for p in PARTS])
    df.insert(0, "op_date", dates.dt.strftime("%Y-%m-%d"))
    df.insert(0, "source", "ali")
    df.insert(0, "row_id", [f"ALI-B-{i:06d}" for i in range(1, n + 1)])
    for p in PARTS:
        df[f"{p}_pct"] = np.round(100 * df[f"{p}_kg"] / df["carcass_weight_kg"], 3)
    return df


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(pathlib.Path(__file__).parent / "data"))
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--n-production", type=int, default=1500)
    ap.add_argument("--n-butchery", type=int, default=2500)
    args = ap.parse_args()

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(args.seed)
    prod = generate_production(args.n_production, rng)
    butch = generate_butchery(args.n_butchery, rng)
    prod.to_csv(out / "train_task4a_ali.csv", index=False)
    butch.to_csv(out / "train_task4b_ali.csv", index=False)
    print(f"train_task4a_ali.csv: {len(prod)} satır, ortalama verim %{prod.yield_pct.mean():.2f}, "
          f"ortalama EKP {prod.loc[prod.product_sku == 'MM-SUCUK-1KG', 'ekp'].mean():.2f} €")
    print(f"train_task4b_ali.csv: {len(butch)} satır, KUZU ortalama parça/fire % "
          + ", ".join(f"{p}={butch.loc[butch.species == 'KUZU', f'{p}_pct'].mean():.1f}" for p in PARTS))


if __name__ == "__main__":
    main()
