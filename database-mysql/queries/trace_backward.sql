-- Geri izleme (mamul lotu -> kullanılan hammadde lotları, tedarikçiler, üretim emirleri)
-- Parametre: ? = mamul lot no (ör. 'SUC-2026-045')
WITH RECURSIVE bwd (lot_id, depth, via_link_id, path) AS (
  SELECT l.id, 0, CAST(NULL AS UNSIGNED), CAST(CONCAT('/', l.lot_no, '/') AS CHAR(1000))
    FROM lot l WHERE l.lot_no = ?
  UNION ALL
  SELECT ll.parent_lot_id, b.depth + 1, ll.id, CONCAT(b.path, pl.lot_no, '/')
    FROM bwd b
    JOIN lot_link ll ON ll.child_lot_id = b.lot_id
    JOIN lot pl      ON pl.id = ll.parent_lot_id
   WHERE b.depth < 20 AND LOCATE(CONCAT('/', pl.lot_no, '/'), b.path) = 0
)
SELECT b.depth, l.lot_no, p.name AS product, l.origin_type, l.lot_date, l.expiry_date,
       ll.qty_consumed, l.uom_code, l.unit_cost,
       s.name AS supplier, l.supplier_lot_no,
       COALESCE(po.order_no, bo.op_no) AS via_document,
       sub.name AS subcontractor, b.path
  FROM bwd b
  JOIN lot l ON l.id = b.lot_id
  JOIN product p ON p.id = l.product_id
  LEFT JOIN supplier s  ON s.id = l.supplier_id
  LEFT JOIN lot_link ll ON ll.id = b.via_link_id
  LEFT JOIN production_order po   ON ll.ref_type = 'production_order'   AND po.id = ll.ref_id
  LEFT JOIN supplier sub          ON sub.id = po.subcontractor_id
  LEFT JOIN butchery_operation bo ON ll.ref_type = 'butchery_operation' AND bo.id = ll.ref_id
 ORDER BY b.depth, l.lot_no;
