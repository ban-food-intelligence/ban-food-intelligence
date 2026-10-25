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

---

Ali — Database, Traceability & Yield Models (details: database-mysql/README.md, ml-engine/README_ali.md)

Quick start:
    docker compose up -d mysql                                   # loads database-mysql/schema.sql + seed.sql
    docker compose exec mysql mysql -uroot -pban food_intel -e "CALL sp_trace_forward('ET-2026-001')"
    pip install -r ml-engine/requirements.txt
    MYSQL_HOST=127.0.0.1 MYSQL_PASSWORD=ban pytest database-mysql/tests -q   # 26 DB tests
    python ml-engine/generator_ali.py && python ml-engine/train_model_ali.py && pytest ml-engine/tests -q

Mandatory scenario, verified by tests:
- EKP: (970.00 + 48.00 + 58.00 + 467.50) / 85 = 1,543.50 / 85 = 18.16 EUR (view v_production_cost)
- Lamb inconsistency: 25 kg x 11.50 = 287.50 EUR, not 351.00 (63.50 undocumented); parts total 22 kg, not 25 (3 kg bone/waste unrecorded). The reconciliation gate rejects the draft; the corrected operation balances to the cent with value-weighted allocation.
- Markets affected by ET-2026-001 (WITH RECURSIVE over lot_link + shipments): Market A 30, Market B 25, Market C 30 packs of SUC-2026-045, plus recorded 2nd tier Market C -> Example Restaurant 10.
- Recall locks ET-2026-001 and SUC-2026-045; SALE/SHIPMENT of recalled lots is rejected inside MySQL (trigger, error prefix LOT_BLOCKED).
- AI (time-based holdout): pack-count error 49% lower and cut-weight error 37% lower than the historical-mean baseline; OOF predictions for stacking in ml-engine/artifacts/ali/ (contract: ml-engine/DATA_CONTRACT.md).
