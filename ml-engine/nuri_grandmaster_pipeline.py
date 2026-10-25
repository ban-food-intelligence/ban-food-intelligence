"""
===============================================================================
BAN Food Intelligence - AVATAR ERP Competition
Upgraded to the "Consortium Architecture" (PINN-TabNet + Gaussian Process + Dual-Output ONNX)
Delegates directly to `nuri_consortium_pipeline.py` so both entrypoints execute
the Uncertainty-Aware Deep Learning & Gaussian Process Stacking Engine.
===============================================================================
"""

from nuri_consortium_pipeline import (
    PhysicsInformedTabNetRegressor,
    compute_arrhenius_physics_shrinkage,
    generate_consortium_dataset,
    engineer_consortium_features,
    train_level1_consortium,
    train_level2_gaussian_process_stacker,
    export_consortium_dual_output_onnx,
    main,
)

__all__ = [
    "PhysicsInformedTabNetRegressor",
    "compute_arrhenius_physics_shrinkage",
    "generate_consortium_dataset",
    "engineer_consortium_features",
    "train_level1_consortium",
    "train_level2_gaussian_process_stacker",
    "export_consortium_dual_output_onnx",
    "main",
]

if __name__ == "__main__":
    main()
