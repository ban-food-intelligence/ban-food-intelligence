-- Additive migration; apply after 20261029_production_dispatch_guard.sql on existing databases.
USE food_intel;
CREATE TABLE IF NOT EXISTS recall_customer_followup (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  recall_id      INT UNSIGNED NOT NULL,
  customer_id    INT UNSIGNED NOT NULL,
  contact_status ENUM('PENDING','CONTACTED','CONFIRMED') NOT NULL DEFAULT 'PENDING',
  note           VARCHAR(500) NOT NULL DEFAULT '',
  updated_by     VARCHAR(64) NOT NULL,
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_rcf_customer (recall_id, customer_id),
  CONSTRAINT fk_rcf_recall FOREIGN KEY (recall_id) REFERENCES recall(id),
  CONSTRAINT fk_rcf_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
) ENGINE=InnoDB;
