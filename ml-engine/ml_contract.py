"""Takım ortak KI veri sözleşmesi (bkz. ml-engine/DATA_CONTRACT.md).

Üç üye de (Ali, Berke, Nuri) kendi sentetik verisini bu kolonlarla üretir;
fold ve holdout bölmesi deterministik olduğu için kimse koordinasyona gerek
duymadan aynı satırları aynı fold'a koyar -> OOF tahminleri stacking'e hazır.
"""
from __future__ import annotations

import hashlib

import numpy as np
import pandas as pd

N_FOLDS = 5
HOLDOUT_FRACTION = 0.20      # tarihçe sonundaki %20 -> zaman bazlı test (meta-learner da görmez)

PRODUCTION = {
    "date": "order_date",
    "categorical": ["product_sku", "subcontractor_code", "meat_supplier_code"],
    "numeric": ["pack_size_kg", "meat_kg", "spice_kg", "casing_kg", "meat_fat_pct", "meat_age_days",
                "drying_days", "meat_unit_price", "spice_unit_price", "casing_unit_price",
                "service_unit_price", "planned_qty"],
    "target": "yield_pct",                       # birincil hedef (ölçekten bağımsız)
    "derived_targets": ["actual_qty", "ekp"],    # yield_pct'den deterministik hesaplanır
}

BUTCHERY_PARTS = ["but", "sirt", "omuz", "kaburga", "kiyma", "fire"]
BUTCHERY = {
    "date": "op_date",
    "categorical": ["species", "breed", "supplier_code", "operator_code", "sex"],
    "numeric": ["carcass_weight_kg", "age_months", "fat_score", "conformation_score", "chill_hours",
                "purchase_unit_price"],
    "targets": [f"{p}_pct" for p in BUTCHERY_PARTS],   # toplamı 100; kg = pct × ağırlık / 100
}


def fold_of(row_id: str, k: int = N_FOLDS) -> int:
    """row_id'den deterministik fold (dil bağımsız: SHA-1'in ilk 8 hex'i mod k)."""
    return int(hashlib.sha1(row_id.encode("utf-8")).hexdigest()[:8], 16) % k


def assign_splits(df: pd.DataFrame, date_col: str) -> pd.DataFrame:
    """split = train|test (tarihe göre son %20 test), fold = 0..4 (yalnız train)."""
    df = df.copy()
    cutoff = df[date_col].sort_values().iloc[int(len(df) * (1 - HOLDOUT_FRACTION))]
    df["split"] = np.where(df[date_col] >= cutoff, "test", "train")
    df["fold"] = [fold_of(r) if s == "train" else -1 for r, s in zip(df["row_id"], df["split"])]
    return df


def production_material_cost(df: pd.DataFrame) -> pd.Series:
    return (df.meat_kg * df.meat_unit_price + df.spice_kg * df.spice_unit_price
            + df.casing_kg * df.casing_unit_price)


def production_qty_from_yield(df: pd.DataFrame, yield_pct) -> np.ndarray:
    return np.asarray(df.meat_kg * np.asarray(yield_pct) / 100 / df.pack_size_kg)


def production_ekp(df: pd.DataFrame, qty) -> np.ndarray:
    """EKP = (hammadde + miktar × hizmet) / miktar — şartname Görev 1 formülü."""
    qty = np.asarray(qty, dtype=float)
    return np.asarray((production_material_cost(df) + qty * df.service_unit_price) / qty)
