-- Apply to an existing food_intel database; preserves all tables and records.
-- Recall preview: the body of sp_recall_initiate moves to sp_recall_apply (no transaction of its
-- own), so POST /recall with "dry_run": true runs the real procedure and report inside a
-- transaction and rolls it back. sp_recall_initiate keeps its signature and result.
USE food_intel;
DELIMITER $$
DROP PROCEDURE IF EXISTS sp_recall_apply$$
DROP PROCEDURE IF EXISTS sp_recall_initiate$$
-- Geri çağırmanın gövdesi: transaction yönetmez. API önizlemesi (dry_run) bunu bir transaction
-- içinde çalıştırıp raporu okur ve geri alır; böylece önizleme gerçek geri çağırmayla aynıdır.
CREATE PROCEDURE sp_recall_apply(
  IN p_recall_no VARCHAR(30), IN p_lot_no VARCHAR(30),
  IN p_reason VARCHAR(500), IN p_user VARCHAR(64))
BEGIN
  DECLARE v_recall INT UNSIGNED; DECLARE v_root INT UNSIGNED;

  CALL sp_fill_trace(p_lot_no, 'DOWN');
  SELECT id INTO v_root FROM lot WHERE lot_no = p_lot_no;

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
  SET @app_user = NULL;
END$$

-- Tek transaction'lık sarmalayıcı (imza ve sonuç kümesi değişmedi).
CREATE PROCEDURE sp_recall_initiate(
  IN p_recall_no VARCHAR(30), IN p_lot_no VARCHAR(30),
  IN p_reason VARCHAR(500), IN p_user VARCHAR(64))
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; SET @app_user = NULL; RESIGNAL; END;
  START TRANSACTION;
  CALL sp_recall_apply(p_recall_no, p_lot_no, p_reason, p_user);
  COMMIT;
  SELECT r.id AS recall_id, COUNT(rl.lot_id) AS blocked_lots
    FROM recall r LEFT JOIN recall_lot rl ON rl.recall_id = r.id
   WHERE r.recall_no = p_recall_no GROUP BY r.id;
END$$
DELIMITER ;
