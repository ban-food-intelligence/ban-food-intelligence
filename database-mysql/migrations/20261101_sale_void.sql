-- Additive migration; apply once on existing databases (after 20261101_subcontractor_returns.sql).
-- Sale void (POST /sales/void): a wrong POS sale is never deleted. Each line gets a RETURN (+)
-- movement back into the same warehouse (ref_type='sale_void'); the void is kept here and in audit_log.
USE food_intel;
CREATE TABLE IF NOT EXISTS sale_void (
  sale_id     INT UNSIGNED NOT NULL PRIMARY KEY,
  reason      VARCHAR(255) NOT NULL,
  voided_by   VARCHAR(64)  NOT NULL,
  voided_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_sv_sale FOREIGN KEY (sale_id) REFERENCES sale(id),
  CONSTRAINT ck_sv_reason CHECK (CHAR_LENGTH(TRIM(reason)) >= 3)
) ENGINE=InnoDB;
DELIMITER $$
DROP TRIGGER IF EXISTS trg_sv_no_update$$
CREATE TRIGGER trg_sv_no_update
BEFORE UPDATE ON sale_void
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: sale_void is append-only';
END$$
DROP TRIGGER IF EXISTS trg_sv_no_delete$$
CREATE TRIGGER trg_sv_no_delete
BEFORE DELETE ON sale_void
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'IMMUTABLE: sale_void is append-only';
END$$
DELIMITER ;
