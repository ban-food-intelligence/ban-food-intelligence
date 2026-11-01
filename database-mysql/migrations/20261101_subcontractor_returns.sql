-- Apply to an existing food_intel database; preserves all tables and records.
-- Records leftover raw material returned by the subcontractor (production_input.qty_returned)
-- and lets the API receive finished goods and those returns in one transaction:
--   sp_production_return_input  new: TRANSFER_OUT/IN back from the subcontractor, qty_returned += qty
--   sp_production_receive       new: the receipt body without its own transaction
--   sp_production_complete      same signature, now a one-transaction wrapper around sp_production_receive
USE food_intel;
DELIMITER $$
DROP PROCEDURE IF EXISTS sp_production_return_input$$
DROP PROCEDURE IF EXISTS sp_production_receive$$
DROP PROCEDURE IF EXISTS sp_production_complete$$
-- Fasondan artık hammadde iadesi (miktar farkı): emir hâlâ MATERIAL_SENT iken, sevk edilen lotun
-- kullanılmayan kısmı fason deposundan geri alınır. qty_returned artar, qty_consumed / line_cost
-- (ve dolayısıyla EKP) yalnızca gerçekten tüketilen miktarı içerir. Transaction yönetmez: çağıran
-- (sp_production_complete veya API) aynı işlemde mamul kabulünü de yapar.
CREATE PROCEDURE sp_production_return_input(
  IN p_order_no VARCHAR(30), IN p_lot_no VARCHAR(30), IN p_qty DECIMAL(14,3),
  IN p_sub_wh VARCHAR(20), IN p_return_wh VARCHAR(20), IN p_user VARCHAR(64))
BEGIN
  DECLARE v_order INT UNSIGNED; DECLARE v_status VARCHAR(20);
  DECLARE v_input INT UNSIGNED; DECLARE v_lot INT UNSIGNED;
  DECLARE v_sent DECIMAL(14,3); DECLARE v_returned DECIMAL(14,3); DECLARE v_cost DECIMAL(14,4);
  DECLARE v_sub INT UNSIGNED; DECLARE v_ret INT UNSIGNED;

  IF p_qty IS NULL OR p_qty <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_INPUT: returned qty must be > 0';
  END IF;
  SELECT id, status INTO v_order, v_status FROM production_order WHERE order_no = p_order_no FOR UPDATE;
  IF v_order IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_NOT_FOUND: production order does not exist';
  END IF;
  IF v_status <> 'MATERIAL_SENT' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_STATE: material can only be returned before receipt';
  END IF;
  SELECT pi.id, pi.lot_id, pi.qty_sent, pi.qty_returned, pi.unit_cost
    INTO v_input, v_lot, v_sent, v_returned, v_cost
    FROM production_input pi JOIN lot l ON l.id = pi.lot_id
   WHERE pi.production_order_id = v_order AND l.lot_no = p_lot_no FOR UPDATE;
  IF v_input IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_INPUT: lot was not sent on this order';
  END IF;
  IF v_returned + p_qty > v_sent THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_INPUT: returned qty exceeds the qty sent';
  END IF;
  IF p_sub_wh = p_return_wh THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_INPUT: return warehouses must differ';
  END IF;
  SELECT id INTO v_sub FROM warehouse WHERE code = p_sub_wh;
  SELECT id INTO v_ret FROM warehouse WHERE code = p_return_wh;
  IF v_sub IS NULL OR v_ret IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_INPUT: unknown warehouse';
  END IF;

  INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost, ref_type, ref_id, created_by, note)
  VALUES (v_lot, v_sub, 'TRANSFER_OUT', -p_qty, v_cost, 'production_order', v_order, p_user, CONCAT('Fasondan iade ', p_order_no)),
         (v_lot, v_ret, 'TRANSFER_IN',   p_qty, v_cost, 'production_order', v_order, p_user, CONCAT('Fasondan iade ', p_order_no));

  UPDATE production_input SET qty_returned = qty_returned + p_qty WHERE id = v_input;
END$$

-- Mamul kabulü: gerçek miktara göre EKP hesapla, mamul lotu aç, tüketimi düş,
-- soyağacı kenarlarını (lot_link) yaz. Transaction yönetmez (API iadelerle aynı işlemde çağırır).
--   EKP = (Σ tüketilen hammadde × lot maliyeti + gerçek miktar × hizmet birim fiyatı) / gerçek miktar
CREATE PROCEDURE sp_production_receive(
  IN p_order_no VARCHAR(30), IN p_actual_qty DECIMAL(14,3),
  IN p_output_lot_no VARCHAR(30), IN p_lot_date DATE, IN p_expiry DATE,
  IN p_sub_wh VARCHAR(20), IN p_target_wh VARCHAR(20), IN p_invoice_no VARCHAR(50))
BEGIN
  DECLARE v_order INT UNSIGNED; DECLARE v_status VARCHAR(20);
  DECLARE v_product INT UNSIGNED; DECLARE v_uom VARCHAR(10);
  DECLARE v_service DECIMAL(14,4); DECLARE v_material DECIMAL(16,4);
  DECLARE v_ekp DECIMAL(14,4); DECLARE v_lot INT UNSIGNED;
  DECLARE v_sub INT UNSIGNED; DECLARE v_target INT UNSIGNED;

  IF p_actual_qty IS NULL OR p_actual_qty <= 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ACTUAL_QTY: must be > 0';
  END IF;

  SELECT po.id, po.status, po.product_id, p.uom_code, po.service_unit_price
    INTO v_order, v_status, v_product, v_uom, v_service
    FROM production_order po JOIN product p ON p.id = po.product_id
   WHERE po.order_no = p_order_no FOR UPDATE;
  IF v_order IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_NOT_FOUND: production order does not exist';
  END IF;
  IF v_status = 'RECEIVED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_STATE: order already received';
  END IF;
  IF v_status <> 'MATERIAL_SENT' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ORDER_STATE: material must be sent before receipt';
  END IF;
  SELECT id INTO v_sub    FROM warehouse WHERE code = p_sub_wh;
  SELECT id INTO v_target FROM warehouse WHERE code = p_target_wh;
  IF v_sub IS NULL OR v_target IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'INVALID_INPUT: unknown warehouse';
  END IF;

  SELECT COALESCE(SUM(line_cost), 0) INTO v_material FROM production_input WHERE production_order_id = v_order;
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
END$$

-- Tek transaction'lık sarmalayıcı (seed ve doğrudan SQL kullanımı için; imza değişmedi).
CREATE PROCEDURE sp_production_complete(
  IN p_order_no VARCHAR(30), IN p_actual_qty DECIMAL(14,3),
  IN p_output_lot_no VARCHAR(30), IN p_lot_date DATE, IN p_expiry DATE,
  IN p_sub_wh VARCHAR(20), IN p_target_wh VARCHAR(20), IN p_invoice_no VARCHAR(50))
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  START TRANSACTION;
  CALL sp_production_receive(p_order_no, p_actual_qty, p_output_lot_no, p_lot_date, p_expiry,
                             p_sub_wh, p_target_wh, p_invoice_no);
  COMMIT;
END$$
DELIMITER ;
