-- =====================================================================
-- BAN Food Intelligence | database-mysql/schema.sql
-- Bölümler: (1) tablolar  (2) raporlama görünümleri  (3) tetikleyiciler + saklı yordamlar
-- Yükleme sırası: schema.sql, sonra seed.sql (bkz. database-mysql/README.md)
-- =====================================================================

-- =====================================================================
-- BAN - Food Intelligence | Merkezi MySQL 8 şeması
-- Sahibi: Ali  (tüketenler: Go API - Berke, Kotlin/SQLite sync - Nuri)
--
-- Tasarım kuralları
--   * Tüm para alanları DECIMAL (asla FLOAT). Birim maliyet 4 hane, tutar 2 hane.
--   * Stok YALNIZCA stock_movement defterinden (append-only) türetilir;
--     stock_balance tetikleyici ile tutulan bir önbellektir ve negatif olamaz.
--   * Lot soyağacı tek bir kenar tablosunda (lot_link) tutulur; üretim,
--     parçalama ve yeniden işleme aynı WITH RECURSIVE sorgusuyla izlenir.
--   * Geri çağırma KI tahminine değil yalnızca bu kayıt ilişkilerine dayanır.
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE DATABASE IF NOT EXISTS food_intel
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE food_intel;

-- ---------------------------------------------------------------------
-- 1. Ana kartlar
-- ---------------------------------------------------------------------
CREATE TABLE unit_of_measure (
  code        VARCHAR(10)  PRIMARY KEY,           -- KG, PAKET, ADET
  name        VARCHAR(50)  NOT NULL,
  is_weight   BOOLEAN      NOT NULL DEFAULT FALSE
) ENGINE=InnoDB;

CREATE TABLE supplier (
  id              INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code            VARCHAR(20)  NOT NULL UNIQUE,
  name            VARCHAR(150) NOT NULL,
  supplier_type   ENUM('RAW_MATERIAL','SUBCONTRACTOR','LIVESTOCK') NOT NULL,
  contact_person  VARCHAR(100),
  phone           VARCHAR(30),
  email           VARCHAR(150),
  address         VARCHAR(255),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE customer (
  id              INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code            VARCHAR(20)  NOT NULL UNIQUE,
  name            VARCHAR(150) NOT NULL,
  customer_type   ENUM('MARKET','WHOLESALER','RESTAURANT','WALK_IN') NOT NULL,
  contact_person  VARCHAR(100),
  phone           VARCHAR(30),
  email           VARCHAR(150),
  address         VARCHAR(255),
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE warehouse (
  id              INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code            VARCHAR(20)  NOT NULL UNIQUE,
  name            VARCHAR(100) NOT NULL,
  warehouse_type  ENUM('MAIN','COLD','STORE','QUARANTINE','SUBCONTRACTOR') NOT NULL
) ENGINE=InnoDB;

CREATE TABLE product (
  id                      INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  sku                     VARCHAR(30)  NOT NULL UNIQUE,
  name                    VARCHAR(150) NOT NULL,
  product_type            ENUM('RAW_MATERIAL','FINISHED_GOOD','CARCASS','CUT','BY_PRODUCT','WASTE') NOT NULL,
  uom_code                VARCHAR(10)  NOT NULL,
  pack_size_kg            DECIMAL(10,3) NULL,          -- 1 paket Narin Sucuk = 1.000 kg
  species                 VARCHAR(20)  NULL,           -- KUZU, KOYUN, OGLAK ... (parçalama)
  standard_purchase_price DECIMAL(12,4) NULL,          -- alış fiyatı (öneri; gerçek fiyat lotta)
  sale_price              DECIMAL(12,4) NULL,          -- VKP (net satış fiyatı)
  shelf_life_days         INT UNSIGNED NULL,
  is_active               BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_product_uom FOREIGN KEY (uom_code) REFERENCES unit_of_measure(code)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 2. Lot (parti / Charge)
-- ---------------------------------------------------------------------
CREATE TABLE lot (
  id               INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  lot_no           VARCHAR(30)  NOT NULL UNIQUE,         -- ET-2026-001, SUC-2026-045 ...
  product_id       INT UNSIGNED NOT NULL,
  origin_type      ENUM('PURCHASE','PRODUCTION','BUTCHERY','REWORK','RETURN') NOT NULL,
  supplier_id      INT UNSIGNED NULL,                    -- satın alma lotlarında zorunlu
  supplier_lot_no  VARCHAR(50)  NULL,                    -- tedarikçinin kendi parti no'su
  lot_date         DATE         NOT NULL,                -- giriş / üretim tarihi
  expiry_date      DATE         NULL,                    -- SKT (uygun olduğunda)
  initial_qty      DECIMAL(14,3) NOT NULL,
  uom_code         VARCHAR(10)  NOT NULL,
  unit_cost        DECIMAL(14,4) NOT NULL,               -- alış fiyatı veya hesaplanan EKP
  -- KI özellikleri (girişte ölçülen; opsiyonel)
  fat_pct            DECIMAL(5,2) NULL,                  -- et: laboratuvar yağ oranı
  animal_breed       VARCHAR(30)  NULL,                  -- karkas: ırk
  animal_age_months  DECIMAL(4,1) NULL,
  animal_sex         ENUM('E','D') NULL,
  fat_score          TINYINT UNSIGNED NULL,              -- 1 (yağsız) .. 5 (çok yağlı)
  conformation_score TINYINT UNSIGNED NULL,              -- 1 (P) .. 5 (E) karkas yapısı
  status           ENUM('ACTIVE','BLOCKED','RECALLED','DEPLETED') NOT NULL DEFAULT 'ACTIVE',
  blocked_reason   VARCHAR(255) NULL,
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_lot_product  FOREIGN KEY (product_id)  REFERENCES product(id),
  CONSTRAINT fk_lot_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id),
  CONSTRAINT fk_lot_uom      FOREIGN KEY (uom_code)    REFERENCES unit_of_measure(code),
  CONSTRAINT ck_lot_qty      CHECK (initial_qty > 0),
  CONSTRAINT ck_lot_cost     CHECK (unit_cost >= 0),
  CONSTRAINT ck_lot_purchase_supplier CHECK (origin_type <> 'PURCHASE' OR supplier_id IS NOT NULL),
  INDEX ix_lot_product (product_id),
  INDEX ix_lot_status (status)
) ENGINE=InnoDB;

-- Lot soyağacı: çoktan-çoğa, miktarlı. Üretim / parçalama / yeniden işleme.
CREATE TABLE lot_link (
  id               INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  parent_lot_id    INT UNSIGNED NOT NULL,                -- girdi (ör. ET-2026-001)
  child_lot_id     INT UNSIGNED NOT NULL,                -- çıktı (ör. SUC-2026-045)
  link_type        ENUM('PRODUCTION','BUTCHERY','REWORK') NOT NULL,
  qty_consumed     DECIMAL(14,3) NOT NULL,               -- parent biriminde kullanılan miktar
  qty_produced     DECIMAL(14,3) NOT NULL,               -- child biriminde oluşan miktar
  ref_type         VARCHAR(30)  NOT NULL,                -- production_order / butchery_operation
  ref_id           INT UNSIGNED NOT NULL,
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_ll_parent FOREIGN KEY (parent_lot_id) REFERENCES lot(id),
  CONSTRAINT fk_ll_child  FOREIGN KEY (child_lot_id)  REFERENCES lot(id),
  CONSTRAINT ck_ll_self   CHECK (parent_lot_id <> child_lot_id),
  CONSTRAINT ck_ll_qty    CHECK (qty_consumed > 0 AND qty_produced > 0),
  UNIQUE KEY uq_ll (parent_lot_id, child_lot_id, ref_type, ref_id),
  INDEX ix_ll_child (child_lot_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 3. Satın alma (hammadde girişi)
-- ---------------------------------------------------------------------
CREATE TABLE purchase_receipt (
  id             INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  receipt_no     VARCHAR(30)  NOT NULL UNIQUE,
  supplier_id    INT UNSIGNED NOT NULL,
  warehouse_id   INT UNSIGNED NOT NULL,
  receipt_date   DATE         NOT NULL,
  invoice_no     VARCHAR(50)  NULL,
  delivery_note_no VARCHAR(50) NULL,
  CONSTRAINT fk_pr_supplier  FOREIGN KEY (supplier_id)  REFERENCES supplier(id),
  CONSTRAINT fk_pr_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(id)
) ENGINE=InnoDB;

CREATE TABLE purchase_receipt_line (
  id           INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  receipt_id   INT UNSIGNED NOT NULL,
  product_id   INT UNSIGNED NOT NULL,
  lot_id       INT UNSIGNED NOT NULL UNIQUE,
  qty          DECIMAL(14,3) NOT NULL,
  unit_price   DECIMAL(14,4) NOT NULL,
  line_total   DECIMAL(14,2) AS (ROUND(qty * unit_price, 2)) STORED,
  CONSTRAINT fk_prl_receipt FOREIGN KEY (receipt_id) REFERENCES purchase_receipt(id),
  CONSTRAINT fk_prl_product FOREIGN KEY (product_id) REFERENCES product(id),
  CONSTRAINT fk_prl_lot     FOREIGN KEY (lot_id)     REFERENCES lot(id),
  CONSTRAINT ck_prl_qty     CHECK (qty > 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 4. Görev 1 - Dış hizmetle (fason) üretim
-- ---------------------------------------------------------------------
CREATE TABLE production_order (
  id                    INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  order_no              VARCHAR(30)  NOT NULL UNIQUE,      -- URT-2026-045
  order_type            ENUM('EXTERNAL','INTERNAL') NOT NULL DEFAULT 'EXTERNAL',
  subcontractor_id      INT UNSIGNED NULL,                 -- fason üretici (supplier)
  product_id            INT UNSIGNED NOT NULL,             -- mamul
  planned_qty           DECIMAL(14,3) NOT NULL,
  actual_qty            DECIMAL(14,3) NULL,                -- teslim alınan gerçek miktar
  service_unit_price    DECIMAL(14,4) NOT NULL DEFAULT 0,  -- 5,50 € / paket
  service_invoice_no    VARCHAR(50)  NULL,
  service_invoice_total DECIMAL(14,2) NULL,                -- = actual_qty × service_unit_price
  drying_days           TINYINT UNSIGNED NULL,             -- planlanan kurutma süresi (KI özelliği)
  status                ENUM('PLANNED','MATERIAL_SENT','RECEIVED','CLOSED','CANCELLED') NOT NULL DEFAULT 'PLANNED',
  order_date            DATE NOT NULL,
  received_date         DATE NULL,
  notes                 VARCHAR(255) NULL,
  CONSTRAINT fk_po_sub     FOREIGN KEY (subcontractor_id) REFERENCES supplier(id),
  CONSTRAINT fk_po_product FOREIGN KEY (product_id)       REFERENCES product(id),
  CONSTRAINT ck_po_qty     CHECK (planned_qty > 0 AND (actual_qty IS NULL OR actual_qty >= 0))
) ENGINE=InnoDB;

-- Fasona sevk edilen hammadde lotları (bir emirde birden çok lot olabilir)
CREATE TABLE production_input (
  id                   INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  production_order_id  INT UNSIGNED NOT NULL,
  lot_id               INT UNSIGNED NOT NULL,
  qty_sent             DECIMAL(14,3) NOT NULL,
  qty_returned         DECIMAL(14,3) NOT NULL DEFAULT 0,       -- fasondan iade gelen artık
  qty_consumed         DECIMAL(14,3) AS (qty_sent - qty_returned) STORED,
  unit_cost            DECIMAL(14,4) NOT NULL,                 -- sevk anındaki lot maliyeti (snapshot)
  line_cost            DECIMAL(14,4) AS ((qty_sent - qty_returned) * unit_cost) STORED,
  CONSTRAINT fk_pi_order FOREIGN KEY (production_order_id) REFERENCES production_order(id),
  CONSTRAINT fk_pi_lot   FOREIGN KEY (lot_id) REFERENCES lot(id),
  CONSTRAINT ck_pi_qty   CHECK (qty_sent > 0 AND qty_returned >= 0 AND qty_returned <= qty_sent),
  UNIQUE KEY uq_pi (production_order_id, lot_id)
) ENGINE=InnoDB;

-- Teslim alınan mamul lotları (bir emir birden çok mamul lotu üretebilir)
CREATE TABLE production_output (
  id                   INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  production_order_id  INT UNSIGNED NOT NULL,
  lot_id               INT UNSIGNED NOT NULL UNIQUE,
  qty                  DECIMAL(14,3) NOT NULL,
  unit_cost            DECIMAL(14,4) NOT NULL,                 -- EKP
  CONSTRAINT fk_pout_order FOREIGN KEY (production_order_id) REFERENCES production_order(id),
  CONSTRAINT fk_pout_lot   FOREIGN KEY (lot_id) REFERENCES lot(id),
  CONSTRAINT ck_pout_qty   CHECK (qty > 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 5. Görev 2 - Bütün kuzu parçalama
-- ---------------------------------------------------------------------
CREATE TABLE butchery_recipe (
  id                 INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code               VARCHAR(30)  NOT NULL UNIQUE,
  name               VARCHAR(100) NOT NULL,
  species            VARCHAR(20)  NOT NULL,
  default_allocation ENUM('QUANTITY','VALUE') NOT NULL DEFAULT 'VALUE'
) ENGINE=InnoDB;

CREATE TABLE butchery_recipe_line (
  id                 INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  recipe_id          INT UNSIGNED NOT NULL,
  product_id         INT UNSIGNED NOT NULL,
  output_kind        ENUM('CUT','BY_PRODUCT','WASTE') NOT NULL,
  expected_yield_pct DECIMAL(6,3) NOT NULL,           -- reçete beklentisi (KI referansı)
  value_price_per_kg DECIMAL(12,4) NULL,              -- değer ağırlıklı dağıtım anahtarı
  CONSTRAINT fk_brl_recipe  FOREIGN KEY (recipe_id)  REFERENCES butchery_recipe(id),
  CONSTRAINT fk_brl_product FOREIGN KEY (product_id) REFERENCES product(id),
  UNIQUE KEY uq_brl (recipe_id, product_id)
) ENGINE=InnoDB;

CREATE TABLE butchery_operation (
  id                  INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  op_no               VARCHAR(30)  NOT NULL UNIQUE,
  recipe_id           INT UNSIGNED NOT NULL,
  input_lot_id        INT UNSIGNED NOT NULL,
  species             VARCHAR(20)  NOT NULL,
  input_weight_kg     DECIMAL(10,3) NOT NULL,
  purchase_unit_price DECIMAL(12,4) NOT NULL,                       -- 11,50 €/kg
  purchase_cost       DECIMAL(14,2) AS (ROUND(input_weight_kg * purchase_unit_price, 2)) STORED,
  additional_cost     DECIMAL(14,2) NOT NULL DEFAULT 0,             -- belgeli ek maliyet (kesim, nakliye)
  additional_cost_note VARCHAR(255) NULL,
  total_cost          DECIMAL(14,2) AS (ROUND(input_weight_kg * purchase_unit_price, 2) + additional_cost) STORED,
  declared_total_cost DECIMAL(14,2) NULL,                           -- faturada/görselde beyan edilen (ör. 351,00)
  allocation_method   ENUM('QUANTITY','VALUE') NOT NULL,
  op_date             DATE NOT NULL,
  operator_name       VARCHAR(100) NULL,
  chill_hours         DECIMAL(5,1) NULL,                            -- kesimden parçalamaya soğutma süresi
  status              ENUM('DRAFT','RECONCILED','POSTED','REJECTED') NOT NULL DEFAULT 'DRAFT',
  reconciliation_note VARCHAR(500) NULL,
  CONSTRAINT fk_bo_recipe FOREIGN KEY (recipe_id)    REFERENCES butchery_recipe(id),
  CONSTRAINT fk_bo_lot    FOREIGN KEY (input_lot_id) REFERENCES lot(id),
  CONSTRAINT ck_bo_weight CHECK (input_weight_kg > 0)
) ENGINE=InnoDB;

CREATE TABLE butchery_output (
  id               INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  operation_id     INT UNSIGNED NOT NULL,
  product_id       INT UNSIGNED NOT NULL,
  lot_id           INT UNSIGNED NULL UNIQUE,              -- fire için lot açılmaz
  output_kind      ENUM('CUT','BY_PRODUCT','WASTE') NOT NULL,
  weight_kg        DECIMAL(10,3) NOT NULL,
  allocation_basis DECIMAL(14,4) NOT NULL DEFAULT 0,      -- QUANTITY: kg | VALUE: kg × €/kg
  allocated_cost   DECIMAL(14,2) NOT NULL DEFAULT 0,
  unit_cost        DECIMAL(14,4) AS (IF(weight_kg > 0, allocated_cost / weight_kg, 0)) STORED,
  CONSTRAINT fk_bout_op      FOREIGN KEY (operation_id) REFERENCES butchery_operation(id),
  CONSTRAINT fk_bout_product FOREIGN KEY (product_id)   REFERENCES product(id),
  CONSTRAINT fk_bout_lot     FOREIGN KEY (lot_id)       REFERENCES lot(id),
  CONSTRAINT ck_bout_weight  CHECK (weight_kg >= 0)
  -- POSTED işlemlerde fire dışındaki her çıktının lotu olmalı: testlerde doğrulanır
  -- (REJECTED taslaklarda lot açılmaz).
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 6. Sevkiyat (market) ve POS satış (Kotlin)
-- ---------------------------------------------------------------------
CREATE TABLE shipment (
  id                 INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  shipment_no        VARCHAR(30) NOT NULL UNIQUE,
  customer_id        INT UNSIGNED NOT NULL,              -- alıcı
  from_warehouse_id  INT UNSIGNED NULL,                  -- 1. kademe: bizim depomuz
  from_customer_id   INT UNSIGNED NULL,                  -- 2. kademe: alıcının kaydedilmiş ileri sevki
  ship_date          DATE NOT NULL,
  delivery_note_no   VARCHAR(50) NULL,
  distribution_tier  TINYINT AS (IF(from_customer_id IS NULL, 1, 2)) STORED,
  CONSTRAINT fk_sh_customer FOREIGN KEY (customer_id)       REFERENCES customer(id),
  CONSTRAINT fk_sh_wh       FOREIGN KEY (from_warehouse_id) REFERENCES warehouse(id),
  CONSTRAINT fk_sh_from     FOREIGN KEY (from_customer_id)  REFERENCES customer(id),
  CONSTRAINT ck_sh_source   CHECK ((from_warehouse_id IS NULL) <> (from_customer_id IS NULL))
) ENGINE=InnoDB;

CREATE TABLE shipment_line (
  id           INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  shipment_id  INT UNSIGNED NOT NULL,
  lot_id       INT UNSIGNED NOT NULL,
  qty          DECIMAL(14,3) NOT NULL,
  unit_price   DECIMAL(14,4) NULL,
  CONSTRAINT fk_shl_sh  FOREIGN KEY (shipment_id) REFERENCES shipment(id),
  CONSTRAINT fk_shl_lot FOREIGN KEY (lot_id)      REFERENCES lot(id),
  CONSTRAINT ck_shl_qty CHECK (qty > 0),
  INDEX ix_shl_lot (lot_id)
) ENGINE=InnoDB;

CREATE TABLE sale (
  id             INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  sale_uuid      CHAR(36)     NOT NULL UNIQUE,          -- Kotlin tarafında üretilen UUIDv7 (idempotency)
  device_id      VARCHAR(64)  NOT NULL,
  warehouse_id   INT UNSIGNED NOT NULL,                 -- satış noktası (STORE)
  customer_id    INT UNSIGNED NULL,
  sold_at        DATETIME(3)  NOT NULL,
  synced_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_sale_wh   FOREIGN KEY (warehouse_id) REFERENCES warehouse(id),
  CONSTRAINT fk_sale_cust FOREIGN KEY (customer_id)  REFERENCES customer(id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 7. Stok hareket defteri (append-only) + bakiye
-- ---------------------------------------------------------------------
CREATE TABLE stock_movement (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  movement_uuid   CHAR(36)     NOT NULL UNIQUE DEFAULT (UUID()),
  lot_id          INT UNSIGNED NOT NULL,
  warehouse_id    INT UNSIGNED NOT NULL,
  movement_type   ENUM(
                    'PURCHASE_IN',          -- +  hammadde girişi
                    'TRANSFER_OUT',         -- -  depo transferi çıkış (fasona sevk dahil)
                    'TRANSFER_IN',          -- +  depo transferi giriş
                    'PRODUCTION_CONSUME',   -- -  üretimde tüketim
                    'PRODUCTION_RECEIPT',   -- +  mamul kabulü
                    'BUTCHERY_CONSUME',     -- -  parçalamaya giren karkas
                    'BUTCHERY_OUTPUT',      -- +  parça ürün lotu
                    'SHIPMENT',             -- -  markete sevk
                    'SALE',                 -- -  POS satış
                    'RETURN',               -- +  müşteri iadesi (geri çağırma dahil)
                    'REWORK_CONSUME',       -- -  yeniden işleme girdi
                    'REWORK_OUTPUT',        -- +  yeniden işleme çıktı
                    'WASTE',                -- -  fire / imha
                    'ADJUSTMENT_IN',        -- +  sayım farkı
                    'ADJUSTMENT_OUT'        -- -  sayım farkı
                  ) NOT NULL,
  qty             DECIMAL(14,3) NOT NULL,                 -- işaretli: + giriş, - çıkış
  unit_cost       DECIMAL(14,4) NOT NULL,
  ref_type        VARCHAR(30)  NULL,                      -- purchase_receipt, production_order, shipment, sale ...
  ref_id          INT UNSIGNED NULL,
  moved_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
  note            VARCHAR(255) NULL,
  CONSTRAINT fk_sm_lot FOREIGN KEY (lot_id)       REFERENCES lot(id),
  CONSTRAINT fk_sm_wh  FOREIGN KEY (warehouse_id) REFERENCES warehouse(id),
  CONSTRAINT ck_sm_qty CHECK (qty <> 0),
  INDEX ix_sm_lot_wh (lot_id, warehouse_id),
  INDEX ix_sm_ref (ref_type, ref_id)
) ENGINE=InnoDB;

CREATE TABLE stock_balance (
  lot_id        INT UNSIGNED NOT NULL,
  warehouse_id  INT UNSIGNED NOT NULL,
  qty           DECIMAL(14,3) NOT NULL DEFAULT 0,
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (lot_id, warehouse_id),
  CONSTRAINT fk_sb_lot FOREIGN KEY (lot_id)       REFERENCES lot(id),
  CONSTRAINT fk_sb_wh  FOREIGN KEY (warehouse_id) REFERENCES warehouse(id),
  CONSTRAINT ck_sb_nonneg CHECK (qty >= 0)
) ENGINE=InnoDB;

CREATE TABLE sale_line (
  id                 INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  sale_id            INT UNSIGNED NOT NULL,
  line_no            SMALLINT UNSIGNED NOT NULL,
  lot_id             INT UNSIGNED NOT NULL,
  qty                DECIMAL(14,3) NOT NULL,
  unit_price         DECIMAL(14,4) NOT NULL,
  line_total         DECIMAL(14,2) AS (ROUND(qty * unit_price, 2)) STORED,
  stock_movement_id  BIGINT UNSIGNED NOT NULL UNIQUE,     -- her satış satırı → tek stok hareketi
  CONSTRAINT fk_sl_sale FOREIGN KEY (sale_id) REFERENCES sale(id),
  CONSTRAINT fk_sl_lot  FOREIGN KEY (lot_id)  REFERENCES lot(id),
  CONSTRAINT fk_sl_sm   FOREIGN KEY (stock_movement_id) REFERENCES stock_movement(id),
  CONSTRAINT ck_sl_qty  CHECK (qty > 0),
  UNIQUE KEY uq_sl (sale_id, line_no)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 8. Geri çağırma
-- ---------------------------------------------------------------------
CREATE TABLE recall (
  id            INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  recall_no     VARCHAR(30)  NOT NULL UNIQUE,
  root_lot_id   INT UNSIGNED NOT NULL,
  reason        VARCHAR(500) NOT NULL,
  severity      ENUM('CLASS_I','CLASS_II','CLASS_III') NOT NULL DEFAULT 'CLASS_I',
  status        ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
  initiated_by  VARCHAR(64)  NOT NULL,
  initiated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  closed_at     DATETIME(3)  NULL,
  CONSTRAINT fk_recall_lot FOREIGN KEY (root_lot_id) REFERENCES lot(id)
) ENGINE=InnoDB;

CREATE TABLE recall_lot (
  recall_id        INT UNSIGNED NOT NULL,
  lot_id           INT UNSIGNED NOT NULL,
  depth            TINYINT UNSIGNED NOT NULL,              -- 0 = kök lot
  previous_status  VARCHAR(20) NOT NULL,
  PRIMARY KEY (recall_id, lot_id),
  CONSTRAINT fk_rl_recall FOREIGN KEY (recall_id) REFERENCES recall(id),
  CONSTRAINT fk_rl_lot    FOREIGN KEY (lot_id)    REFERENCES lot(id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 9. KI tahminleri (tahmin + gerçek + sapma birlikte saklanır)
-- ---------------------------------------------------------------------
CREATE TABLE ml_prediction (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  task             ENUM('PRODUCTION_YIELD','PRODUCTION_EKP','BUTCHERY_YIELD') NOT NULL,
  model_name       VARCHAR(50)  NOT NULL,                  -- ali_lgbm, berke_xgb, nuri_cat, stack_meta
  model_version    VARCHAR(30)  NOT NULL,
  ref_type         VARCHAR(30)  NOT NULL,                  -- production_order / butchery_operation
  ref_id           INT UNSIGNED NOT NULL,
  target_name      VARCHAR(50)  NOT NULL,                  -- actual_qty, ekp, but_kg ...
  predicted_value  DECIMAL(14,4) NOT NULL,
  actual_value     DECIMAL(14,4) NULL,
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX ix_mlp_ref (ref_type, ref_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- 10. Denetim günlüğü ve senkronizasyon idempotency (Nuri ile ortak)
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  table_name  VARCHAR(50)  NOT NULL,
  row_id      BIGINT UNSIGNED NOT NULL,
  action      VARCHAR(30)  NOT NULL,
  old_value   JSON NULL,
  new_value   JSON NULL,
  changed_by  VARCHAR(64)  NULL,
  changed_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

CREATE TABLE sync_inbox (
  event_uuid    CHAR(36)    PRIMARY KEY,                 -- outbox paketindeki UUIDv7
  device_id     VARCHAR(64) NOT NULL,
  entity        VARCHAR(30) NOT NULL,                    -- sale, return, stock_count ...
  payload_hash  CHAR(64)    NOT NULL,                    -- SHA-256 (HMAC doğrulaması API'de)
  received_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;

-- =====================================================================
-- Raporlama görünümleri (maliyet, stok, mutabakat, KI sapması)
-- =====================================================================
USE food_intel;

-- Lot × depo stok bakiyesi ve değeri
CREATE OR REPLACE VIEW v_stock_by_lot AS
SELECT l.lot_no, p.sku, p.name AS product_name, w.code AS warehouse_code,
       sb.qty, l.uom_code, l.unit_cost,
       ROUND(sb.qty * l.unit_cost, 2) AS stock_value, l.status, l.expiry_date
FROM stock_balance sb
JOIN lot l       ON l.id = sb.lot_id
JOIN product p   ON p.id = l.product_id
JOIN warehouse w ON w.id = sb.warehouse_id;

-- Defterden yeniden hesaplanan bakiye (stock_balance önbelleğinin denetimi için)
CREATE OR REPLACE VIEW v_stock_ledger_check AS
SELECT sm.lot_id, sm.warehouse_id,
       SUM(sm.qty)                 AS ledger_qty,
       COALESCE(MAX(sb.qty), 0)    AS balance_qty,
       SUM(sm.qty) - COALESCE(MAX(sb.qty), 0) AS diff
FROM stock_movement sm
LEFT JOIN stock_balance sb ON sb.lot_id = sm.lot_id AND sb.warehouse_id = sm.warehouse_id
GROUP BY sm.lot_id, sm.warehouse_id;

-- Görev 1: maliyet kırılımı (şartnamedeki tablo ile birebir)
CREATE OR REPLACE VIEW v_production_cost_breakdown AS
SELECT po.order_no, 1 AS sort_key, l.lot_no, p.name AS cost_element,
       pi.qty_consumed AS qty, l.uom_code, pi.unit_cost,
       ROUND(pi.line_cost, 2) AS amount
FROM production_order po
JOIN production_input pi ON pi.production_order_id = po.id
JOIN lot l     ON l.id = pi.lot_id
JOIN product p ON p.id = l.product_id
UNION ALL
SELECT po.order_no, 2, po.service_invoice_no, 'Fason hizmet / işçilik',
       po.actual_qty, 'PAKET', po.service_unit_price,
       ROUND(po.actual_qty * po.service_unit_price, 2)
FROM production_order po
WHERE po.actual_qty IS NOT NULL;

-- Görev 1: EKP, VKP, brüt kâr ve marj
CREATE OR REPLACE VIEW v_production_cost AS
SELECT po.order_no,
       p.sku, p.name AS product_name,
       po.status,
       po.planned_qty,
       po.actual_qty,
       po.actual_qty - po.planned_qty                                AS qty_variance,
       ROUND(100 * po.actual_qty / po.planned_qty, 2)                AS fulfilment_pct,
       m.input_kg,
       ROUND(100 * po.actual_qty * COALESCE(p.pack_size_kg, 1) / NULLIF(m.main_input_kg, 0), 2) AS yield_pct_of_meat,
       ROUND(m.material_cost, 2)                                     AS material_cost,
       ROUND(po.actual_qty * po.service_unit_price, 2)               AS service_cost,
       ROUND(m.material_cost + po.actual_qty * po.service_unit_price, 2) AS total_cost,
       ROUND((m.material_cost + po.actual_qty * po.service_unit_price) / po.actual_qty, 4) AS ekp,
       ROUND((m.material_cost + po.actual_qty * po.service_unit_price) / po.actual_qty, 2) AS ekp_display,
       p.sale_price                                                  AS vkp,
       ROUND(p.sale_price - (m.material_cost + po.actual_qty * po.service_unit_price) / po.actual_qty, 2) AS gross_profit_per_unit,
       ROUND(po.actual_qty * p.sale_price, 2)                        AS revenue_at_vkp,
       ROUND(po.actual_qty * p.sale_price - (m.material_cost + po.actual_qty * po.service_unit_price), 2) AS gross_profit_total,
       ROUND(100 * (1 - (m.material_cost + po.actual_qty * po.service_unit_price) / (po.actual_qty * p.sale_price)), 2) AS margin_pct
FROM production_order po
JOIN product p ON p.id = po.product_id
JOIN (
  SELECT pi.production_order_id,
         SUM(pi.line_cost) AS material_cost,
         SUM(IF(l.uom_code = 'KG', pi.qty_consumed, 0)) AS input_kg,
         MAX(IF(pr.sku LIKE 'HM-ET%', pi.qty_consumed, 0)) AS main_input_kg
  FROM production_input pi
  JOIN lot l      ON l.id = pi.lot_id
  JOIN product pr ON pr.id = l.product_id
  GROUP BY pi.production_order_id
) m ON m.production_order_id = po.id
WHERE po.actual_qty > 0;

-- Görev 2: parçalama mutabakat kapısı (ağırlık dengesi + maliyet dengesi)
CREATE OR REPLACE VIEW v_butchery_reconciliation AS
SELECT bo.op_no, bo.species, bo.allocation_method, bo.status,
       bo.input_weight_kg,
       o.output_weight_kg,
       o.cut_weight_kg,
       o.waste_weight_kg,
       bo.input_weight_kg - o.output_weight_kg                   AS weight_diff_kg,
       bo.purchase_unit_price,
       bo.purchase_cost,
       bo.additional_cost,
       bo.total_cost,
       bo.declared_total_cost,
       bo.declared_total_cost - bo.total_cost                    AS declared_cost_diff,
       o.allocated_total,
       bo.total_cost - o.allocated_total                         AS allocation_diff,
       (ABS(bo.input_weight_kg - o.output_weight_kg) <= 0.010)   AS weight_ok,
       (bo.declared_total_cost IS NULL
        OR ABS(bo.declared_total_cost - bo.total_cost) < 0.005)  AS declared_cost_ok,
       (ABS(bo.total_cost - o.allocated_total) < 0.005)          AS allocation_ok
FROM butchery_operation bo
JOIN (
  SELECT operation_id,
         SUM(weight_kg)                               AS output_weight_kg,
         SUM(IF(output_kind <> 'WASTE', weight_kg, 0)) AS cut_weight_kg,
         SUM(IF(output_kind =  'WASTE', weight_kg, 0)) AS waste_weight_kg,
         SUM(allocated_cost)                          AS allocated_total
  FROM butchery_output GROUP BY operation_id
) o ON o.operation_id = bo.id;

CREATE OR REPLACE VIEW v_butchery_cost_allocation AS
SELECT bo.op_no, p.name AS product_name, bout.output_kind, l.lot_no,
       bout.weight_kg,
       ROUND(100 * bout.weight_kg / bo.input_weight_kg, 2) AS yield_pct,
       bout.allocation_basis, bout.allocated_cost, bout.unit_cost
FROM butchery_output bout
JOIN butchery_operation bo ON bo.id = bout.operation_id
JOIN product p ON p.id = bout.product_id
LEFT JOIN lot l ON l.id = bout.lot_id;

-- KI: tahmin, gerçek ve sapma birlikte (şartname 4A/4B)
CREATE OR REPLACE VIEW v_ml_prediction_deviation AS
SELECT id, task, model_name, model_version, ref_type, ref_id, target_name,
       predicted_value, actual_value,
       actual_value - predicted_value                                      AS deviation,
       ROUND(100 * (actual_value - predicted_value) / NULLIF(actual_value, 0), 2) AS deviation_pct,
       created_at
FROM ml_prediction;

-- KI veri sözleşmesi (ml-engine/DATA_CONTRACT.md) ile aynı kolonlar: gerçek kayıtlar
-- biriktikçe model sentetik veri yerine doğrudan bu görünümlerden eğitilir.
CREATE OR REPLACE VIEW v_ml_production_features AS
SELECT CONCAT('DB-P-', po.order_no)                           AS row_id,
       'db'                                                   AS source,
       po.order_date,
       p.sku                                                  AS product_sku,
       p.pack_size_kg,
       sub.code                                               AS subcontractor_code,
       MAX(IF(rp.sku = 'HM-ET-DANA', ms.code, NULL))          AS meat_supplier_code,
       SUM(IF(rp.sku = 'HM-ET-DANA',  pi.qty_consumed, 0))    AS meat_kg,
       SUM(IF(rp.sku = 'HM-BAHARAT',  pi.qty_consumed, 0))    AS spice_kg,
       SUM(IF(rp.sku = 'HM-BAGIRSAK', pi.qty_consumed, 0))    AS casing_kg,
       MAX(IF(rp.sku = 'HM-ET-DANA', l.fat_pct, NULL))        AS meat_fat_pct,
       MAX(IF(rp.sku = 'HM-ET-DANA', DATEDIFF(po.order_date, l.lot_date), NULL)) AS meat_age_days,
       po.drying_days,
       MAX(IF(rp.sku = 'HM-ET-DANA',  pi.unit_cost, NULL))    AS meat_unit_price,
       MAX(IF(rp.sku = 'HM-BAHARAT',  pi.unit_cost, NULL))    AS spice_unit_price,
       MAX(IF(rp.sku = 'HM-BAGIRSAK', pi.unit_cost, NULL))    AS casing_unit_price,
       po.service_unit_price,
       po.planned_qty,
       po.actual_qty,
       ROUND(100 * po.actual_qty * p.pack_size_kg / NULLIF(SUM(IF(rp.sku = 'HM-ET-DANA', pi.qty_consumed, 0)), 0), 3) AS yield_pct,
       ROUND((SUM(pi.line_cost) + po.actual_qty * po.service_unit_price) / po.actual_qty, 4) AS ekp
FROM production_order po
JOIN product p   ON p.id = po.product_id
LEFT JOIN supplier sub ON sub.id = po.subcontractor_id
JOIN production_input pi ON pi.production_order_id = po.id
JOIN lot l       ON l.id = pi.lot_id
JOIN product rp  ON rp.id = l.product_id
LEFT JOIN supplier ms ON ms.id = l.supplier_id
WHERE po.status IN ('RECEIVED','CLOSED') AND po.actual_qty > 0
GROUP BY po.id, po.order_no, po.order_date, p.sku, p.pack_size_kg, sub.code, po.drying_days,
         po.service_unit_price, po.planned_qty, po.actual_qty;

CREATE OR REPLACE VIEW v_ml_butchery_features AS
SELECT CONCAT('DB-B-', bo.op_no)          AS row_id,
       'db'                               AS source,
       bo.op_date, bo.species,
       l.animal_breed                     AS breed,
       s.code                             AS supplier_code,
       bo.operator_name                   AS operator_code,
       bo.input_weight_kg                 AS carcass_weight_kg,
       l.animal_age_months                AS age_months,
       l.animal_sex                       AS sex,
       l.fat_score, l.conformation_score, bo.chill_hours,
       bo.purchase_unit_price,
       SUM(IF(p.sku = 'KZ-BUT',     o.weight_kg, 0)) AS but_kg,
       SUM(IF(p.sku = 'KZ-SIRT',    o.weight_kg, 0)) AS sirt_kg,
       SUM(IF(p.sku = 'KZ-OMUZ',    o.weight_kg, 0)) AS omuz_kg,
       SUM(IF(p.sku = 'KZ-KABURGA', o.weight_kg, 0)) AS kaburga_kg,
       SUM(IF(p.sku = 'KZ-KIYMA',   o.weight_kg, 0)) AS kiyma_kg,
       SUM(IF(o.output_kind = 'WASTE', o.weight_kg, 0)) AS fire_kg
FROM butchery_operation bo
JOIN lot l ON l.id = bo.input_lot_id
LEFT JOIN supplier s ON s.id = l.supplier_id
JOIN butchery_output o ON o.operation_id = bo.id
JOIN product p ON p.id = o.product_id
WHERE bo.status = 'POSTED'
GROUP BY bo.id, bo.op_no, bo.op_date, bo.species, l.animal_breed, s.code, bo.operator_name, bo.input_weight_kg,
         l.animal_age_months, l.animal_sex, l.fat_score, l.conformation_score, bo.chill_hours, bo.purchase_unit_price;

-- =====================================================================
-- Tetikleyiciler ve saklı yordamlar
--   * stock_movement: işaret kontrolü, negatif stok engeli, bloke lot engeli,
--     değiştirilemezlik (UPDATE/DELETE yasak), bakiye güncelleme
--   * shipment_line / sale_line: otomatik stok hareketi
--   * Üretim: sp_production_add_input, sp_production_complete (EKP)
--   * İzlenebilirlik: sp_trace_forward, sp_trace_backward (WITH RECURSIVE)
--   * Geri çağırma: sp_recall_initiate, sp_recall_report
-- =====================================================================
USE food_intel;

DELIMITER $$

-- ---------------------------------------------------------------------
-- Stok defteri
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_sm_before_insert
BEFORE INSERT ON stock_movement
FOR EACH ROW
BEGIN
  DECLARE v_status VARCHAR(20);
  DECLARE v_balance DECIMAL(14,3);

  -- 1) Hareket türü ile işaret uyumlu olmalı
  IF NEW.movement_type IN ('PURCHASE_IN','TRANSFER_IN','PRODUCTION_RECEIPT','BUTCHERY_OUTPUT',
                           'RETURN','REWORK_OUTPUT','ADJUSTMENT_IN') AND NEW.qty <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'MOVEMENT_SIGN: inbound movement must have qty > 0';
  END IF;
  IF NEW.movement_type IN ('TRANSFER_OUT','PRODUCTION_CONSUME','BUTCHERY_CONSUME','SHIPMENT',
                           'SALE','REWORK_CONSUME','WASTE','ADJUSTMENT_OUT') AND NEW.qty >= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'MOVEMENT_SIGN: outbound movement must have qty < 0';
  END IF;

  -- 2) Geri çağrılan / bloke lot satılamaz, sevk edilemez, üretimde kullanılamaz.
  --    (İade, imha ve karantinaya transfer serbest.)
  SELECT status INTO v_status FROM lot WHERE id = NEW.lot_id;
  IF v_status IN ('BLOCKED','RECALLED') AND NEW.movement_type IN
     ('SALE','SHIPMENT','PRODUCTION_CONSUME','BUTCHERY_CONSUME','REWORK_CONSUME') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LOT_BLOCKED: lot is blocked or recalled';
  END IF;

  -- 3) Negatif stok engeli (bakiye satırı kilitlenir -> eşzamanlı satışlarda yarış yok)
  IF NEW.qty < 0 THEN
    SELECT qty INTO v_balance FROM stock_balance
     WHERE lot_id = NEW.lot_id AND warehouse_id = NEW.warehouse_id
     FOR UPDATE;
    IF COALESCE(v_balance, 0) + NEW.qty < 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INSUFFICIENT_STOCK: movement would make lot balance negative';
    END IF;
  END IF;
END$$

CREATE TRIGGER trg_sm_after_insert
AFTER INSERT ON stock_movement
FOR EACH ROW
BEGIN
  -- Çıkışta satır zaten var (BEFORE tetikleyicisi kilitledi). Upsert kullanılmaz:
  -- MySQL CHECK'i aday satırın (negatif qty) üzerinde de değerlendirir.
  IF NEW.qty > 0 THEN
    INSERT INTO stock_balance (lot_id, warehouse_id, qty)
    VALUES (NEW.lot_id, NEW.warehouse_id, NEW.qty)
    ON DUPLICATE KEY UPDATE qty = qty + NEW.qty;
  ELSE
    UPDATE stock_balance SET qty = qty + NEW.qty
     WHERE lot_id = NEW.lot_id AND warehouse_id = NEW.warehouse_id;
  END IF;
END$$

CREATE TRIGGER trg_sm_no_update
BEFORE UPDATE ON stock_movement
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: stock_movement is append-only; post a correcting movement';
END$$

CREATE TRIGGER trg_sm_no_delete
BEFORE DELETE ON stock_movement
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: stock_movement is append-only; post a correcting movement';
END$$

CREATE TRIGGER trg_audit_no_update
BEFORE UPDATE ON audit_log
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: audit_log is append-only';
END$$

CREATE TRIGGER trg_audit_no_delete
BEFORE DELETE ON audit_log
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: audit_log is append-only';
END$$

-- Lot durum değişiklikleri denetim günlüğüne
CREATE TRIGGER trg_lot_status_audit
AFTER UPDATE ON lot
FOR EACH ROW
BEGIN
  IF NOT (OLD.status <=> NEW.status) THEN
    INSERT INTO audit_log (table_name, row_id, action, old_value, new_value, changed_by)
    VALUES ('lot', NEW.id, 'STATUS_CHANGE',
            JSON_OBJECT('lot_no', OLD.lot_no, 'status', OLD.status),
            JSON_OBJECT('lot_no', NEW.lot_no, 'status', NEW.status, 'reason', NEW.blocked_reason),
            COALESCE(@app_user, CURRENT_USER()));
  END IF;
END$$

-- 1. kademe sevkiyat satırı -> SHIPMENT stok hareketi (bloke lot ise reddedilir)
CREATE TRIGGER trg_shl_after_insert
AFTER INSERT ON shipment_line
FOR EACH ROW
BEGIN
  DECLARE v_wh INT UNSIGNED;
  SELECT from_warehouse_id INTO v_wh FROM shipment WHERE id = NEW.shipment_id;
  IF v_wh IS NOT NULL THEN
    INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost, ref_type, ref_id, created_by)
    SELECT NEW.lot_id, v_wh, 'SHIPMENT', -NEW.qty, l.unit_cost, 'shipment', NEW.shipment_id, COALESCE(@app_user, 'system')
    FROM lot l WHERE l.id = NEW.lot_id;
  END IF;
END$$

-- POS satış satırı -> SALE stok hareketi; satır hareketin id'sini saklar
CREATE TRIGGER trg_sl_before_insert
BEFORE INSERT ON sale_line
FOR EACH ROW
BEGIN
  DECLARE v_wh INT UNSIGNED;
  IF NEW.stock_movement_id IS NULL THEN
    SELECT warehouse_id INTO v_wh FROM sale WHERE id = NEW.sale_id;
    INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost, ref_type, ref_id, created_by)
    SELECT NEW.lot_id, v_wh, 'SALE', -NEW.qty, l.unit_cost, 'sale', NEW.sale_id, COALESCE(@app_user, 'pos')
    FROM lot l WHERE l.id = NEW.lot_id;
    SET NEW.stock_movement_id = LAST_INSERT_ID();
  END IF;
END$$

-- ---------------------------------------------------------------------
-- Görev 1: fason üretim
-- ---------------------------------------------------------------------

-- Hammadde lotunu fasona sevk et (transfer + üretim girdisi, maliyet snapshot)
CREATE PROCEDURE sp_production_add_input(
  IN p_order_no VARCHAR(30), IN p_lot_no VARCHAR(30), IN p_qty DECIMAL(14,3),
  IN p_from_wh VARCHAR(20), IN p_to_wh VARCHAR(20))
BEGIN
  DECLARE v_order INT UNSIGNED; DECLARE v_status VARCHAR(20);
  DECLARE v_lot INT UNSIGNED;   DECLARE v_cost DECIMAL(14,4);
  DECLARE v_from INT UNSIGNED;  DECLARE v_to INT UNSIGNED;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

  START TRANSACTION;
  SELECT id, status INTO v_order, v_status FROM production_order WHERE order_no = p_order_no FOR UPDATE;
  IF v_order IS NULL OR v_status NOT IN ('PLANNED','MATERIAL_SENT') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_STATE: order not found or already received';
  END IF;
  SELECT id, unit_cost INTO v_lot, v_cost FROM lot WHERE lot_no = p_lot_no;
  SELECT id INTO v_from FROM warehouse WHERE code = p_from_wh;
  SELECT id INTO v_to   FROM warehouse WHERE code = p_to_wh;

  INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost, ref_type, ref_id, note)
  VALUES (v_lot, v_from, 'TRANSFER_OUT', -p_qty, v_cost, 'production_order', v_order, CONCAT('Fasona sevk ', p_order_no)),
         (v_lot, v_to,   'TRANSFER_IN',   p_qty, v_cost, 'production_order', v_order, CONCAT('Fasona sevk ', p_order_no));

  INSERT INTO production_input (production_order_id, lot_id, qty_sent, unit_cost)
  VALUES (v_order, v_lot, p_qty, v_cost);

  UPDATE production_order SET status = 'MATERIAL_SENT' WHERE id = v_order;
  COMMIT;
END$$

-- Mamul kabulü: gerçek miktara göre EKP hesapla, mamul lotu aç, tüketimi düş,
-- soyağacı kenarlarını (lot_link) yaz. Tek transaction.
--   EKP = (Σ tüketilen hammadde × lot maliyeti + gerçek miktar × hizmet birim fiyatı) / gerçek miktar
CREATE PROCEDURE sp_production_complete(
  IN p_order_no VARCHAR(30), IN p_actual_qty DECIMAL(14,3),
  IN p_output_lot_no VARCHAR(30), IN p_lot_date DATE, IN p_expiry DATE,
  IN p_sub_wh VARCHAR(20), IN p_target_wh VARCHAR(20), IN p_invoice_no VARCHAR(50))
BEGIN
  DECLARE v_order INT UNSIGNED; DECLARE v_status VARCHAR(20);
  DECLARE v_product INT UNSIGNED; DECLARE v_uom VARCHAR(10);
  DECLARE v_service DECIMAL(14,4); DECLARE v_material DECIMAL(16,4);
  DECLARE v_ekp DECIMAL(14,4); DECLARE v_lot INT UNSIGNED;
  DECLARE v_sub INT UNSIGNED; DECLARE v_target INT UNSIGNED;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

  IF p_actual_qty IS NULL OR p_actual_qty <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ACTUAL_QTY: must be > 0';
  END IF;

  START TRANSACTION;
  SELECT po.id, po.status, po.product_id, p.uom_code, po.service_unit_price
    INTO v_order, v_status, v_product, v_uom, v_service
    FROM production_order po JOIN product p ON p.id = po.product_id
   WHERE po.order_no = p_order_no FOR UPDATE;
  IF v_order IS NULL OR v_status <> 'MATERIAL_SENT' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_STATE: material must be sent before receipt';
  END IF;
  SELECT id INTO v_sub    FROM warehouse WHERE code = p_sub_wh;
  SELECT id INTO v_target FROM warehouse WHERE code = p_target_wh;

  SELECT SUM(line_cost) INTO v_material FROM production_input WHERE production_order_id = v_order;
  SET v_ekp = ROUND((v_material + p_actual_qty * v_service) / p_actual_qty, 4);

  INSERT INTO lot (lot_no, product_id, origin_type, lot_date, expiry_date, initial_qty, uom_code, unit_cost)
  VALUES (p_output_lot_no, v_product, 'PRODUCTION', p_lot_date, p_expiry, p_actual_qty, v_uom, v_ekp);
  SET v_lot = LAST_INSERT_ID();

  INSERT INTO production_output (production_order_id, lot_id, qty, unit_cost)
  VALUES (v_order, v_lot, p_actual_qty, v_ekp);

  -- fasonda tüketilen hammadde
  INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost, ref_type, ref_id, note)
  SELECT pi.lot_id, v_sub, 'PRODUCTION_CONSUME', -pi.qty_consumed, pi.unit_cost, 'production_order', v_order, p_order_no
    FROM production_input pi WHERE pi.production_order_id = v_order AND pi.qty_consumed > 0;

  -- mamul kabulü
  INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost, ref_type, ref_id, note)
  VALUES (v_lot, v_target, 'PRODUCTION_RECEIPT', p_actual_qty, v_ekp, 'production_order', v_order, p_order_no);

  -- soyağacı: her girdi lotu -> mamul lotu
  INSERT INTO lot_link (parent_lot_id, child_lot_id, link_type, qty_consumed, qty_produced, ref_type, ref_id)
  SELECT pi.lot_id, v_lot, 'PRODUCTION', pi.qty_consumed, p_actual_qty, 'production_order', v_order
    FROM production_input pi WHERE pi.production_order_id = v_order AND pi.qty_consumed > 0;

  UPDATE production_order
     SET actual_qty = p_actual_qty, status = 'RECEIVED', received_date = p_lot_date,
         service_invoice_no = p_invoice_no,
         service_invoice_total = ROUND(p_actual_qty * v_service, 2)
   WHERE id = v_order;
  COMMIT;
END$$

-- ---------------------------------------------------------------------
-- Görev 3: izlenebilirlik (WITH RECURSIVE)
-- ---------------------------------------------------------------------

-- Yardımcı: kök lottan ileri (DOWN) veya geri (UP) tüm lotları tmp_trace_lot'a yazar.
-- Döngü koruması: yol (path) içinde aynı lot ikinci kez yer alamaz + derinlik sınırı.
CREATE PROCEDURE sp_fill_trace(IN p_lot_no VARCHAR(30), IN p_direction VARCHAR(4))
BEGIN
  DROP TEMPORARY TABLE IF EXISTS tmp_trace_lot;
  CREATE TEMPORARY TABLE tmp_trace_lot (
    lot_id INT UNSIGNED PRIMARY KEY,
    depth  TINYINT UNSIGNED NOT NULL,
    path   VARCHAR(1000) NOT NULL
  );

  IF p_direction = 'DOWN' THEN
    INSERT INTO tmp_trace_lot (lot_id, depth, path)
    WITH RECURSIVE fwd (lot_id, depth, path) AS (
      SELECT l.id, 0, CAST(CONCAT('/', l.lot_no, '/') AS CHAR(1000))
        FROM lot l WHERE l.lot_no = p_lot_no
      UNION ALL
      SELECT ll.child_lot_id, f.depth + 1, CONCAT(f.path, c.lot_no, '/')
        FROM fwd f
        JOIN lot_link ll ON ll.parent_lot_id = f.lot_id
        JOIN lot c       ON c.id = ll.child_lot_id
       WHERE f.depth < 20 AND LOCATE(CONCAT('/', c.lot_no, '/'), f.path) = 0
    )
    SELECT lot_id, MIN(depth), MIN(path) FROM fwd GROUP BY lot_id;
  ELSE
    INSERT INTO tmp_trace_lot (lot_id, depth, path)
    WITH RECURSIVE bwd (lot_id, depth, path) AS (
      SELECT l.id, 0, CAST(CONCAT('/', l.lot_no, '/') AS CHAR(1000))
        FROM lot l WHERE l.lot_no = p_lot_no
      UNION ALL
      SELECT ll.parent_lot_id, b.depth + 1, CONCAT(b.path, pl.lot_no, '/')
        FROM bwd b
        JOIN lot_link ll ON ll.child_lot_id = b.lot_id
        JOIN lot pl      ON pl.id = ll.parent_lot_id
       WHERE b.depth < 20 AND LOCATE(CONCAT('/', pl.lot_no, '/'), b.path) = 0
    )
    SELECT lot_id, MIN(depth), MIN(path) FROM bwd GROUP BY lot_id;
  END IF;

  IF (SELECT COUNT(*) FROM tmp_trace_lot) = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LOT_NOT_FOUND';
  END IF;
END$$

-- İleri izleme: 4 sonuç kümesi
--   1) etkilenen lotlar  2) depolardaki stok  3) sevkiyat/alıcılar (1. ve kaydedilmiş 2. kademe)
--   4) POS satışları
CREATE PROCEDURE sp_trace_forward(IN p_lot_no VARCHAR(30))
BEGIN
  CALL sp_fill_trace(p_lot_no, 'DOWN');

  SELECT t.depth, l.lot_no, p.sku, p.name AS product_name, l.origin_type,
         l.initial_qty, l.uom_code, l.status, l.expiry_date, t.path
    FROM tmp_trace_lot t JOIN lot l ON l.id = t.lot_id JOIN product p ON p.id = l.product_id
   ORDER BY t.depth, l.lot_no;

  SELECT l.lot_no, w.code AS warehouse_code, sb.qty, l.uom_code
    FROM tmp_trace_lot t
    JOIN stock_balance sb ON sb.lot_id = t.lot_id AND sb.qty > 0
    JOIN lot l ON l.id = t.lot_id
    JOIN warehouse w ON w.id = sb.warehouse_id
   ORDER BY l.lot_no, w.code;

  WITH RECURSIVE dist (shipment_line_id, shipment_id, customer_id, via_customer_id, lot_id, qty, tier) AS (
    SELECT sl.id, s.id, s.customer_id, CAST(NULL AS UNSIGNED), sl.lot_id, sl.qty, 1
      FROM tmp_trace_lot t
      JOIN shipment_line sl ON sl.lot_id = t.lot_id
      JOIN shipment s       ON s.id = sl.shipment_id AND s.from_customer_id IS NULL
    UNION ALL
    SELECT sl.id, s.id, s.customer_id, s.from_customer_id, sl.lot_id, sl.qty, d.tier + 1
      FROM dist d
      JOIN shipment s       ON s.from_customer_id = d.customer_id
      JOIN shipment_line sl ON sl.shipment_id = s.id AND sl.lot_id = d.lot_id
     WHERE d.tier < 5
  )
  SELECT MIN(d.tier) AS tier, c.code AS customer_code, c.name AS customer_name,
         c.contact_person, c.phone, c.email,
         vc.name AS received_via, l.lot_no, s.shipment_no, s.ship_date, s.delivery_note_no,
         MAX(d.qty) AS qty, l.uom_code
    FROM dist d
    JOIN shipment s  ON s.id = d.shipment_id
    JOIN customer c  ON c.id = d.customer_id
    LEFT JOIN customer vc ON vc.id = d.via_customer_id
    JOIN lot l       ON l.id = d.lot_id
   GROUP BY d.shipment_line_id, c.code, c.name, c.contact_person, c.phone, c.email,
            vc.name, l.lot_no, s.shipment_no, s.ship_date, s.delivery_note_no, l.uom_code
   ORDER BY tier, c.code, l.lot_no;

  SELECT l.lot_no, w.code AS store_code, COUNT(*) AS sale_lines, SUM(sl.qty) AS qty_sold,
         MIN(s.sold_at) AS first_sale, MAX(s.sold_at) AS last_sale
    FROM tmp_trace_lot t
    JOIN sale_line sl ON sl.lot_id = t.lot_id
    JOIN sale s       ON s.id = sl.sale_id
    JOIN lot l        ON l.id = t.lot_id
    JOIN warehouse w  ON w.id = s.warehouse_id
   GROUP BY l.lot_no, w.code;
END$$

-- Geri izleme: 2 sonuç kümesi
--   1) kullanılan tüm lotlar + tedarikçi  2) ilgili üretim emirleri / parçalama işlemleri
CREATE PROCEDURE sp_trace_backward(IN p_lot_no VARCHAR(30))
BEGIN
  CALL sp_fill_trace(p_lot_no, 'UP');

  SELECT t.depth, l.lot_no, p.sku, p.name AS product_name, l.origin_type,
         l.lot_date, l.expiry_date, l.initial_qty, l.uom_code, l.unit_cost,
         s.code AS supplier_code, s.name AS supplier_name, l.supplier_lot_no,
         s.contact_person AS supplier_contact, s.phone AS supplier_phone, t.path
    FROM tmp_trace_lot t
    JOIN lot l ON l.id = t.lot_id
    JOIN product p ON p.id = l.product_id
    LEFT JOIN supplier s ON s.id = l.supplier_id
   ORDER BY t.depth, l.lot_no;

  SELECT DISTINCT ll.link_type, ll.ref_type,
         COALESCE(po.order_no, bo.op_no) AS document_no,
         sub.name AS subcontractor,
         pl.lot_no AS input_lot_no, ll.qty_consumed,
         cl.lot_no AS output_lot_no, ll.qty_produced
    FROM tmp_trace_lot t
    JOIN lot_link ll ON ll.child_lot_id = t.lot_id
    JOIN lot pl ON pl.id = ll.parent_lot_id
    JOIN lot cl ON cl.id = ll.child_lot_id
    LEFT JOIN production_order po  ON ll.ref_type = 'production_order'   AND po.id = ll.ref_id
    LEFT JOIN supplier sub         ON sub.id = po.subcontractor_id
    LEFT JOIN butchery_operation bo ON ll.ref_type = 'butchery_operation' AND bo.id = ll.ref_id
   ORDER BY document_no, input_lot_no;
END$$

-- ---------------------------------------------------------------------
-- Geri çağırma
-- ---------------------------------------------------------------------
-- Kök lot ve ileri izlemede bulunan tüm lotlar RECALLED olur (tek transaction).
-- Bloke lotlarda SALE/SHIPMENT/üretim tüketimi tetikleyici tarafından reddedilir.
CREATE PROCEDURE sp_recall_initiate(
  IN p_recall_no VARCHAR(30), IN p_lot_no VARCHAR(30),
  IN p_reason VARCHAR(500), IN p_user VARCHAR(64))
BEGIN
  DECLARE v_recall INT UNSIGNED; DECLARE v_root INT UNSIGNED;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

  CALL sp_fill_trace(p_lot_no, 'DOWN');
  SELECT id INTO v_root FROM lot WHERE lot_no = p_lot_no;

  START TRANSACTION;
  SET @app_user = p_user;
  INSERT INTO recall (recall_no, root_lot_id, reason, initiated_by)
  VALUES (p_recall_no, v_root, p_reason, p_user);
  SET v_recall = LAST_INSERT_ID();

  INSERT INTO recall_lot (recall_id, lot_id, depth, previous_status)
  SELECT v_recall, t.lot_id, t.depth, l.status
    FROM tmp_trace_lot t JOIN lot l ON l.id = t.lot_id;

  UPDATE lot l JOIN tmp_trace_lot t ON t.lot_id = l.id
     SET l.status = 'RECALLED',
         l.blocked_reason = CONCAT(p_recall_no, ': ', p_reason);
  COMMIT;
  SET @app_user = NULL;

  SELECT v_recall AS recall_id, COUNT(*) AS blocked_lots FROM recall_lot WHERE recall_id = v_recall;
END$$

-- Müşteri/market bazlı miktar, sevkiyat ve iletişim listesi + depodaki bloke stok
CREATE PROCEDURE sp_recall_report(IN p_recall_no VARCHAR(30))
BEGIN
  DECLARE v_recall INT UNSIGNED;
  SELECT id INTO v_recall FROM recall WHERE recall_no = p_recall_no;

  WITH RECURSIVE dist (shipment_line_id, customer_id, via_customer_id, lot_id, qty, tier) AS (
    SELECT sl.id, s.customer_id, CAST(NULL AS UNSIGNED), sl.lot_id, sl.qty, 1
      FROM recall_lot rl
      JOIN shipment_line sl ON sl.lot_id = rl.lot_id
      JOIN shipment s ON s.id = sl.shipment_id AND s.from_customer_id IS NULL
     WHERE rl.recall_id = v_recall
    UNION ALL
    SELECT sl.id, s.customer_id, s.from_customer_id, sl.lot_id, sl.qty, d.tier + 1
      FROM dist d
      JOIN shipment s ON s.from_customer_id = d.customer_id
      JOIN shipment_line sl ON sl.shipment_id = s.id AND sl.lot_id = d.lot_id
     WHERE d.tier < 5
  ),
  uniq AS (
    SELECT shipment_line_id, MIN(tier) AS tier, MAX(customer_id) AS customer_id,
           MAX(via_customer_id) AS via_customer_id, MAX(lot_id) AS lot_id, MAX(qty) AS qty
      FROM dist GROUP BY shipment_line_id
  )
  SELECT u.tier, c.code AS customer_code, c.name AS customer_name, c.contact_person, c.phone, c.email,
         vc.name AS received_via,
         GROUP_CONCAT(DISTINCT l.lot_no ORDER BY l.lot_no) AS lots,
         SUM(u.qty) AS qty_shipped,
         COALESCE((SELECT SUM(sm.qty) FROM stock_movement sm
                    JOIN recall_lot rl2 ON rl2.recall_id = v_recall AND rl2.lot_id = sm.lot_id
                   WHERE sm.movement_type = 'RETURN' AND sm.ref_type = 'customer'
                     AND sm.ref_id = c.id), 0) AS qty_returned
    FROM uniq u
    JOIN customer c ON c.id = u.customer_id
    LEFT JOIN customer vc ON vc.id = u.via_customer_id
    JOIN lot l ON l.id = u.lot_id
   GROUP BY u.tier, c.id, c.code, c.name, c.contact_person, c.phone, c.email, vc.name
   ORDER BY u.tier, c.code;

  SELECT l.lot_no, w.code AS warehouse_code, sb.qty, l.uom_code, l.status
    FROM recall_lot rl
    JOIN stock_balance sb ON sb.lot_id = rl.lot_id AND sb.qty > 0
    JOIN lot l ON l.id = rl.lot_id
    JOIN warehouse w ON w.id = sb.warehouse_id
   WHERE rl.recall_id = v_recall;
END$$

DELIMITER ;
