-- KI anomali uyarısı demosu (video / React paneli için what-if senaryosu).
-- seed.sql'deki gerçek kayıtlar normal çıkıyor (uyarı yok). Bu dosya iki varsayımsal durumu yazar:
--   URT-2026-045 85 yerine 78 paket teslim edilseydi  -> CRITICAL LOW_YIELD (~120 € kayıp)
--   PRC-2026-001'de 0,9 kg but fireye gitseydi          -> CRITICAL FIRE_HIGH
-- Mesajlar [DEMO] önekiyle işaretlidir; load.sh/seed.sql bunu yüklemez.
-- Yeniden üretmek için (önce train_model_ali.py + anomaly_ali.py):
--   python ml-engine/predict_ali.py production ml-engine/data/demo_cases_anomaly_production.csv --demo --emit-sql a.sql
--   python ml-engine/predict_ali.py butchery   ml-engine/data/demo_cases_anomaly_butchery.csv   --demo --emit-sql b.sql
-- Yükleme: mysql food_intel < database-mysql/demo/anomaly_demo.sql
USE food_intel;
INSERT INTO ml_anomaly_alert (task, detector, model_version, ref_type, ref_id, score, threshold_warn, threshold_crit, severity, direction, expected_value, actual_value, deviation, est_loss_eur, top_parts, message) VALUES ('PRODUCTION_YIELD', 'robust_z', 'ali-1.0.0+ali-anomaly-1.0.0', 'production_order', (SELECT id FROM production_order WHERE order_no = 'URT-2026-045'), -6.658, 2.5, 3.471, 'CRITICAL', 'LOW_YIELD', 84.61, 78.0, -6.61, 120.41, NULL, '[DEMO] Verim beklenenden düşük: 78 paket, beklenen 84.6 (≈120 € kayıp). Fason kurutma/dolum ve et lotunu kontrol edin.') ON DUPLICATE KEY UPDATE score = VALUES(score), severity = VALUES(severity), direction = VALUES(direction), expected_value = VALUES(expected_value), actual_value = VALUES(actual_value), deviation = VALUES(deviation), est_loss_eur = VALUES(est_loss_eur), top_parts = VALUES(top_parts), message = VALUES(message);
INSERT INTO ml_anomaly_alert (task, detector, model_version, ref_type, ref_id, score, threshold_warn, threshold_crit, severity, direction, expected_value, actual_value, deviation, est_loss_eur, top_parts, message) VALUES ('BUTCHERY_YIELD', 'mahalanobis_mcd', 'ali-1.0.0+ali-anomaly-1.0.0', 'butchery_operation', (SELECT id FROM butchery_operation WHERE op_no = 'PRC-2026-001'), 89.631, 18.0, 27.0, 'CRITICAL', 'FIRE_HIGH', 2.607, 3.9, 1.293, 14.87, 'fire +1.29 kg, but -1.19 kg', '[DEMO] Beklenmedik fire: fire +1.29 kg, but -1.19 kg (≈15 € kayıp). Kesim/tartım ve soğutmayı kontrol edin.') ON DUPLICATE KEY UPDATE score = VALUES(score), severity = VALUES(severity), direction = VALUES(direction), expected_value = VALUES(expected_value), actual_value = VALUES(actual_value), deviation = VALUES(deviation), est_loss_eur = VALUES(est_loss_eur), top_parts = VALUES(top_parts), message = VALUES(message);

SELECT ref_no, lot_no, severity, direction, expected_value, actual_value, est_loss_eur, message
  FROM v_ml_anomaly_alert WHERE status = 'OPEN' ORDER BY severity = 'CRITICAL' DESC, est_loss_eur DESC;
