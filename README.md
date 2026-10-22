BAN Food Intelligence - AVATAR ERP

Enterprise-grade Food Intelligence, Real-Time Livestock Costing, Recursive Batch Traceability, Emergency Recall Lockdown, and Multi-Tier Ensemble Yield Prediction for the AVATAR AI Competition.

Exact Team Roles & Responsibilities

Ali (MySQL + SQL + Model)
- Central MySQL 8.0+ schema architecture & seed records (database-mysql)
- WITH RECURSIVE hierarchical lot tracing queries (forward & backward)
- Own synthetic data generation (generator_ali.py)
- Own model training (train_model_ali.py CatBoost Regressor)

Berke (Go API + React + Model)
- High-throughput Go REST API (/production, /butchery, /trace, /recall, /sync)
- Task 2 Butchery Reconciliation Logic (Physical Quantity vs Net Realizable Value Cost Allocation)
- React Admin Panel (Recall War Room, Lot Genealogy, Cost Tables)
- Own synthetic data generation (generator_berke.py)
- Own model training (train_model_berke.py XGBoost Regressor)

Nuri (Kotlin POS + Sync + Stacking + Security)
- Offline-first Android POS application (mobile-kotlin)
- SQLite Room database & sync_outbox_queue asynchronous synchronization
- Emergency Red Recall Lock Screen UI
- Enterprise Security: BFLA/IDOR auth middleware, UUIDv7 idempotency & HMAC-SHA256 outbox verification, prepared statements, Android Keystore hardware-encrypted tokens, immutable audit logs
- AI Ensemble: Own synthetic data generation (generator_nuri.py), model training (train_model_nuri.py LightGBM), and Stacking Meta-Learner integration (stacking_meta_learner.py)

Strict Development Workflow: Feature Branches & Pull Requests
MANDATORY POLICY: All team members MUST use feature branches and PRs. No direct commits to main.
Direct pushes to the main or master branches are strictly disabled and blocked via repository branch protection rules. Every change must be reviewed and merged via a Pull Request (PR).
