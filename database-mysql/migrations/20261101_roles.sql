-- Apply to an existing food_intel database; preserves all tables and records.
-- Back-office roles: ADMIN (everything), QUALITY (recall, alerts, POS unlock),
-- OPERATOR (stock, production, butchery, shipments, counts), VIEWER (reads only).
-- Existing supervisors keep full rights (ADMIN); three demo users show the separation.
USE food_intel;

ALTER TABLE supervisor
  ADD COLUMN role ENUM('ADMIN','QUALITY','OPERATOR','VIEWER') NOT NULL DEFAULT 'VIEWER' AFTER pin_hash;
UPDATE supervisor SET role = 'ADMIN';

INSERT IGNORE INTO supervisor (username, display_name, role, pin_hash) VALUES
  ('kalite_uzmani', 'Kalite Uzmanı (demo)',      'QUALITY',  '$2a$12$aDoWEqnQ4UhG13ZPE4Y4M.ZtsIDM.TVsAkOCr0oS/hWfb/kuFuOE6'),
  ('uretim_sefi',   'Üretim Şefi (demo)',        'OPERATOR', '$2a$12$CiL3gk8TdOtFELikX0tyy.7C3g5JzRX1qdJ3eTg2SgF.PATOR.Zqq'),
  ('denetci',       'Denetçi, salt okur (demo)', 'VIEWER',   '$2a$12$PSprPHM3S0bMpKgAWhpWveEO0cBxNP60zlQlM4voZo/DbCZPxooEO');
