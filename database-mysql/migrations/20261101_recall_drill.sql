-- Additive migration; apply once, after 20261101_subcontractor_returns.sql, on existing databases.
-- Recall drills (POST /recall/drills): a documented traceability test of one lot. Nothing is
-- blocked and no stock moves; the trace, the mass balance and the customer contact times are kept.
USE food_intel;
SET NAMES utf8mb4; -- the CHECK literals are stored in the client charset, as in schema.sql
-- Geri çağırma tatbikatı (POST /recall/drills): bir lotun belgeli izlenebilirlik testi. Lotlar bloke
-- edilmez, stok ve geri çağırma kayıtları değişmez. Başlangıçta ileri/geri iz ve kütle dengesi
-- bulunduğu haliyle snapshot'a yazılır; her kayıtlı müşteriye ulaşma zamanı saklanır.
-- Sonuç hesaplanır, seçilmez: PASS = her miktar belgeyle açıklanmış (balanced), her müşteriye
-- ulaşılmış ve hedef süre içinde kapatılmış; değilse FAIL, nedenleri ve düzeltici faaliyet (findings).
CREATE TABLE recall_drill (
  id              INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  drill_no        VARCHAR(30)  NOT NULL UNIQUE,
  root_lot_id     INT UNSIGNED NOT NULL,
  target_minutes  SMALLINT UNSIGNED NOT NULL DEFAULT 240,
  note            VARCHAR(255) NULL,
  started_by      VARCHAR(64)  NOT NULL,
  started_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  trace_ms        INT UNSIGNED NOT NULL,                   -- ileri + geri iz ve kütle dengesi süresi
  lots_forward    SMALLINT UNSIGNED NOT NULL,
  lots_backward   SMALLINT UNSIGNED NOT NULL,
  suppliers       SMALLINT UNSIGNED NOT NULL,
  balanced        BOOLEAN      NOT NULL,                   -- başlangıçta her miktar belgeyle açıklanmış
  snapshot        JSON         NOT NULL,                   -- ileri/geri iz ve kütle dengesi, bulunduğu haliyle
  status          ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
  closed_by       VARCHAR(64)  NULL,
  closed_at       DATETIME(3)  NULL,
  result          ENUM('PASS','FAIL') NULL,
  fail_reasons    SET('UNEXPLAINED_QTY','CUSTOMER_NOT_REACHED','OVER_TIME') NULL,
  findings        VARCHAR(1000) NULL,                      -- düzeltici faaliyet; FAIL için zorunlu
  CONSTRAINT fk_rd_lot      FOREIGN KEY (root_lot_id) REFERENCES lot(id),
  CONSTRAINT ck_rd_target   CHECK (target_minutes BETWEEN 1 AND 10080),
  CONSTRAINT ck_rd_state    CHECK ((status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL AND result IS NULL)
                                OR (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL AND result IS NOT NULL)),
  CONSTRAINT ck_rd_reasons  CHECK (result IS NULL OR (result = 'PASS') = (fail_reasons IS NULL OR fail_reasons = '')),
  CONSTRAINT ck_rd_findings CHECK (result IS NULL OR result = 'PASS' OR findings IS NOT NULL)
) ENGINE=InnoDB;

-- Tatbikatta ulaşılacak müşteriler (başlangıçtaki ileri izin kayıtlı sevkiyatları, 1. ve 2. kademe).
CREATE TABLE recall_drill_contact (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  drill_id     INT UNSIGNED NOT NULL,
  customer_id  INT UNSIGNED NOT NULL,
  tier         TINYINT UNSIGNED NOT NULL,
  reached_at   DATETIME(3)  NULL,
  reached_by   VARCHAR(64)  NULL,
  note         VARCHAR(255) NULL,
  UNIQUE KEY uq_rdc_customer (drill_id, customer_id),
  CONSTRAINT fk_rdc_drill    FOREIGN KEY (drill_id)    REFERENCES recall_drill(id),
  CONSTRAINT fk_rdc_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
  CONSTRAINT ck_rdc_reached  CHECK ((reached_at IS NULL) = (reached_by IS NULL))
) ENGINE=InnoDB;

DELIMITER $$
-- Geri çağırma tatbikatı: zamanlar sunucudan; tatbikat bir kez kapanır, sonra değişmez; PASS
-- yalnızca her miktar açıklanmış, her müşteriye ulaşılmış ve hedef süre aşılmamışsa yazılabilir.
CREATE TRIGGER trg_rd_before_insert
BEFORE INSERT ON recall_drill
FOR EACH ROW
BEGIN
  SET NEW.started_at = CURRENT_TIMESTAMP(3);
  IF NEW.status <> 'OPEN' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_INPUT: a drill starts open';
  END IF;
END$$

CREATE TRIGGER trg_rd_before_update
BEFORE UPDATE ON recall_drill
FOR EACH ROW
BEGIN
  IF OLD.status <> 'OPEN' OR NEW.status <> 'CLOSED'
     OR NOT (NEW.id <=> OLD.id AND NEW.drill_no <=> OLD.drill_no AND NEW.root_lot_id <=> OLD.root_lot_id
             AND NEW.target_minutes <=> OLD.target_minutes AND NEW.note <=> OLD.note
             AND NEW.started_by <=> OLD.started_by AND NEW.started_at <=> OLD.started_at
             AND NEW.trace_ms <=> OLD.trace_ms AND NEW.lots_forward <=> OLD.lots_forward
             AND NEW.lots_backward <=> OLD.lots_backward AND NEW.suppliers <=> OLD.suppliers
             AND NEW.balanced <=> OLD.balanced AND NEW.snapshot <=> OLD.snapshot) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: a drill only gets its result, once';
  END IF;
  SET NEW.closed_at = CURRENT_TIMESTAMP(3);
  IF NEW.result = 'PASS' AND (NOT OLD.balanced
      OR TIMESTAMPDIFF(MICROSECOND, OLD.started_at, NEW.closed_at) > OLD.target_minutes * 60000000
      OR EXISTS (SELECT 1 FROM recall_drill_contact WHERE drill_id = OLD.id AND reached_at IS NULL)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_INPUT: a drill passes only fully explained, with every customer reached, in time';
  END IF;
END$$

CREATE TRIGGER trg_rd_no_delete
BEFORE DELETE ON recall_drill
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: a drill is never deleted';
END$$

CREATE TRIGGER trg_rdc_before_insert
BEFORE INSERT ON recall_drill_contact
FOR EACH ROW
BEGIN
  IF NEW.reached_at IS NOT NULL OR NEW.reached_by IS NOT NULL
     OR NOT EXISTS (SELECT 1 FROM recall_drill WHERE id = NEW.drill_id AND status = 'OPEN') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: drill customers are listed unreached, on an open drill';
  END IF;
END$$

CREATE TRIGGER trg_rdc_before_update
BEFORE UPDATE ON recall_drill_contact
FOR EACH ROW
BEGIN
  IF OLD.reached_at IS NOT NULL OR NEW.reached_by IS NULL
     OR NOT (NEW.id <=> OLD.id AND NEW.drill_id <=> OLD.drill_id AND NEW.customer_id <=> OLD.customer_id
             AND NEW.tier <=> OLD.tier)
     OR NOT EXISTS (SELECT 1 FROM recall_drill WHERE id = OLD.drill_id AND status = 'OPEN') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: a customer is marked reached once, while the drill is open';
  END IF;
  SET NEW.reached_at = CURRENT_TIMESTAMP(3);
END$$

CREATE TRIGGER trg_rdc_no_delete
BEFORE DELETE ON recall_drill_contact
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: drill contacts are never deleted';
END$$
DELIMITER ;
