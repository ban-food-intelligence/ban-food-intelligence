-- Apply to an existing food_intel database; preserves all tables and records.
USE food_intel;
DELIMITER $$
DROP PROCEDURE IF EXISTS sp_production_send_input$$
CREATE PROCEDURE sp_production_send_input(
  IN p_order_no VARCHAR(30), IN p_lot_no VARCHAR(30), IN p_qty DECIMAL(14,3),
  IN p_from_wh VARCHAR(20), IN p_to_wh VARCHAR(20), IN p_user VARCHAR(64))
BEGIN
  DECLARE v_order INT UNSIGNED; DECLARE v_status VARCHAR(20);
  DECLARE v_lot INT UNSIGNED;   DECLARE v_cost DECIMAL(14,4);
  DECLARE v_lot_status VARCHAR(20); DECLARE v_type VARCHAR(30);
  DECLARE v_expiry DATE; DECLARE v_lot_date DATE; DECLARE v_order_date DATE;
  DECLARE v_from INT UNSIGNED;  DECLARE v_to INT UNSIGNED;

  SELECT id, status, order_date INTO v_order, v_status, v_order_date FROM production_order WHERE order_no = p_order_no FOR UPDATE;
  IF v_order IS NULL OR v_status NOT IN ('PLANNED','MATERIAL_SENT') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_STATE: order not found or already received';
  END IF;
  SELECT l.id, l.unit_cost, l.status, l.expiry_date, l.lot_date, p.product_type
    INTO v_lot, v_cost, v_lot_status, v_expiry, v_lot_date, v_type
    FROM lot l JOIN product p ON p.id = l.product_id WHERE l.lot_no = p_lot_no FOR SHARE;
  IF v_lot IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LOT_NOT_FOUND: input lot does not exist';
  END IF;
  IF v_type <> 'RAW_MATERIAL' OR v_lot_date > v_order_date THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_INPUT: expected raw material received by order date';
  END IF;
  IF v_lot_status IN ('BLOCKED','RECALLED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LOT_BLOCKED: input lot is blocked or recalled';
  END IF;
  -- Historical orders use their recorded dispatch date; delayed receipt does not age the input.
  IF v_expiry IS NOT NULL AND v_expiry < v_order_date THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LOT_EXPIRED: input expired before order date';
  END IF;
  IF p_from_wh = p_to_wh THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_INPUT: dispatch warehouses must differ';
  END IF;
  SELECT id INTO v_from FROM warehouse WHERE code = p_from_wh;
  SELECT id INTO v_to   FROM warehouse WHERE code = p_to_wh;

  INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost, ref_type, ref_id, created_by, note)
  VALUES (v_lot, v_from, 'TRANSFER_OUT', -p_qty, v_cost, 'production_order', v_order, p_user, CONCAT('Fasona sevk ', p_order_no)),
         (v_lot, v_to,   'TRANSFER_IN',   p_qty, v_cost, 'production_order', v_order, p_user, CONCAT('Fasona sevk ', p_order_no));

  INSERT INTO production_input (production_order_id, lot_id, qty_sent, unit_cost)
  VALUES (v_order, v_lot, p_qty, v_cost);

  UPDATE production_order SET status = 'MATERIAL_SENT' WHERE id = v_order;
END$$
DELIMITER ;
