"""
BAN Food Intelligence - AVATAR ERP Competition
Module 1: Physics-Informed Synthetic Data Generator (Retail Yield & Shrinkage)
Author: Nuri (Retail POS, Offline Sync, Security & Stacking Lead)

Generates realistic, non-linear, physics-informed retail cold-chain data
with controlled Gaussian noise for the AVATAR ERP Machine Learning Pipeline.
"""

import os
import sys
import numpy as np
import pandas as pd

if sys.stdout.encoding != "utf-8":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass


def generate_nuri_dataset(n_samples: int = 5000, random_state: int = 42) -> pd.DataFrame:
    """
    Generates a physics-informed synthetic dataset for Nuri's domain
    (Retail Cold-Chain Shrinkage & Retention Yield).

    Parameters
    ----------
    n_samples : int, default=5000
        Number of retail batch shipments to simulate.
    random_state : int, default=42
        Seed for deterministic reproducibility.

    Returns
    -------
    pd.DataFrame
        Generated dataset saved at `data/train_task4_retail.csv`.
    """
    print("🚀 [NURI-GEN] Initializing Physics-Informed Retail Yield Generator...")
    rng = np.random.default_rng(random_state)

    # 1. Batch Identifiers
    batch_ids = [f"BATCH-RTL-{i:05d}" for i in range(1, n_samples + 1)]

    # 2. Market Tier (A: Metro Flagship, B: Regional Hub, C: Rural Outlet)
    market_tiers = rng.choice(["A", "B", "C"], size=n_samples, p=[0.45, 0.35, 0.20])

    # 3. Transit Time (Hours): Log-normal / Gamma distribution reflecting short to long-haul routes
    tier_transit_shift = np.where(
        market_tiers == "A", 0.0, np.where(market_tiers == "B", 6.0, 14.0)
    )
    transit_time_hours = np.clip(
        rng.gamma(shape=3.2, scale=5.5, size=n_samples) + tier_transit_shift,
        2.0,
        72.0,
    ).round(2)

    # 4. Ambient Temperature (Celsius): Cold-chain target is 0-4C, with thermal excursions up to 18C
    tier_temp_shift = np.where(
        market_tiers == "A", 0.0, np.where(market_tiers == "B", 0.8, 1.8)
    )
    ambient_temp_celsius = np.clip(
        rng.normal(loc=3.8, scale=2.6, size=n_samples) + tier_temp_shift,
        -2.0,
        18.0,
    ).round(2)

    # 5. Storage Humidity (%): Optimal chilled meat storage is ~82-88% RH
    storage_humidity_pct = np.clip(
        rng.normal(loc=78.0, scale=8.5, size=n_samples),
        45.0,
        98.0,
    ).round(2)

    # 6. Packaging Integrity Score (0.40 to 1.00, vacuum/MAP barrier effectiveness)
    # Beta distribution skewed toward high integrity, slightly degraded by long transit
    raw_packaging = rng.beta(a=6.5, b=1.8, size=n_samples)
    transit_wear = 0.0015 * transit_time_hours
    packaging_integrity_score = np.clip(
        0.40 + 0.60 * raw_packaging - transit_wear,
        0.35,
        1.00,
    ).round(4)

    # =========================================================================
    # Physics-Informed Non-Linear Shrinkage Mechanics
    # =========================================================================
    print("🧪 [NURI-GEN] Computing thermodynamic Arrhenius spoilage & evaporative loss...")

    # Base unavoidable physiological drip loss (%)
    base_drip_loss = 1.15

    # Market tier infrastructure penalty (handling & cold-room door opening frequency)
    tier_penalty = np.where(
        market_tiers == "A", 0.15, np.where(market_tiers == "B", 0.55, 1.15)
    )

    # Exponential thermal-time spoilage (Arrhenius-like kinetics):
    # Higher temperature + higher transit time exponentially increases shrinkage,
    # while high packaging integrity strongly mitigates thermal & oxidative exposure.
    effective_temp = np.maximum(0.0, ambient_temp_celsius + 2.0)
    thermal_transit_stress = (
        0.38
        * np.exp(0.115 * effective_temp)
        * np.power(transit_time_hours / 12.0, 1.28)
    )

    # Packaging mitigation factor: high integrity (near 1.0) blocks up to 75% of external stress;
    # compromised seals (<0.6) amplify spoilage non-linearly.
    packaging_mitigation = np.exp(-1.55 * (packaging_integrity_score - 0.40))
    seal_failure_penalty = np.maximum(0.0, 0.70 - packaging_integrity_score) ** 2 * 14.0

    # Hygrometric moisture dynamics:
    # Low humidity (<82%) drives evaporative desiccation; excessive humidity (>90%) + warmth drives microbial slime
    desiccation_loss = (
        0.042
        * np.maximum(0.0, 84.0 - storage_humidity_pct) ** 1.15
        * (1.15 - 0.75 * packaging_integrity_score)
    )
    condensation_spoilage = (
        0.025
        * np.maximum(0.0, storage_humidity_pct - 88.0)
        * np.maximum(0.0, ambient_temp_celsius - 4.0)
    )

    # Deterministic physical shrinkage before stochastic noise
    deterministic_shrinkage = (
        base_drip_loss
        + tier_penalty
        + (thermal_transit_stress * packaging_mitigation)
        + seal_failure_penalty
        + desiccation_loss
        + condensation_spoilage
    )

    # Realistic Gaussian process & measurement noise (challenging yet learnable)
    gaussian_noise = rng.normal(loc=0.0, scale=0.45, size=n_samples)

    shrinkage_pct = np.clip(
        deterministic_shrinkage + gaussian_noise,
        0.50,
        45.00,
    ).round(4)

    # Target Variable: Retail Retention Yield (%) = 100 - shrinkage_pct
    retail_retention_yield_pct = np.round(100.0 - shrinkage_pct, 4)

    df = pd.DataFrame(
        {
            "batch_id": batch_ids,
            "market_tier": market_tiers,
            "transit_time_hours": transit_time_hours,
            "ambient_temp_celsius": ambient_temp_celsius,
            "storage_humidity_pct": storage_humidity_pct,
            "packaging_integrity_score": packaging_integrity_score,
            "shrinkage_pct": shrinkage_pct,
            "retail_retention_yield_pct": retail_retention_yield_pct,
        }
    )

    # Save to ml-engine/data/train_task4_retail.csv
    script_dir = os.path.dirname(os.path.abspath(__file__))
    output_dir = os.path.join(script_dir, "data")
    os.makedirs(output_dir, exist_ok=True)
    output_path = os.path.join(output_dir, "train_task4_retail.csv")
    df.to_csv(output_path, index=False)

    # If executed from workspace root, also ensure relative 'data/train_task4_retail.csv' works if needed
    print(f"💾 [NURI-GEN] Saved {len(df):,} rows to: {output_path}")
    print(
        f"📊 [NURI-GEN] Shrinkage (%)        -> Mean: {df['shrinkage_pct'].mean():.2f}% | "
        f"Std: {df['shrinkage_pct'].std():.2f}% | Range: [{df['shrinkage_pct'].min():.2f}%, {df['shrinkage_pct'].max():.2f}%]"
    )
    print(
        f"🎯 [NURI-GEN] Retention Yield (%)  -> Mean: {df['retail_retention_yield_pct'].mean():.2f}% | "
        f"Std: {df['retail_retention_yield_pct'].std():.2f}% | Range: [{df['retail_retention_yield_pct'].min():.2f}%, {df['retail_retention_yield_pct'].max():.2f}%]"
    )
    return df


if __name__ == "__main__":
    print("=" * 75)
    print("🌟 AVATAR ERP - NURI RETAIL YIELD SYNTHETIC DATA PIPELINE 🌟")
    print("=" * 75)
    generate_nuri_dataset(n_samples=5000)
    print("✅ [NURI-GEN] Dataset generation complete!")
    print("=" * 75)
