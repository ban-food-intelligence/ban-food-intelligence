-- =====================================================================
-- DEMO SORGUSU: Sorunlu hammadde lotundan etkilenen marketler
-- Tek ifade, tek parametre. Go'dan: db.Query(sql, lotNo)  (? placeholder)
-- MySQL CLI'dan: SET @lot_no='ET-2026-001'; ve ? yerine @lot_no.
-- Sonuç kayıt ilişkilerine dayanır (lot_link + shipment), tahmin içermez.
-- =====================================================================
WITH RECURSIVE
affected_lot (lot_id, depth, path) AS (           -- 1) ileri soyağacı
  SELECT l.id, 0, CAST(CONCAT('/', l.lot_no, '/') AS CHAR(1000))
    FROM lot l WHERE l.lot_no = ?
  UNION ALL
  SELECT ll.child_lot_id, a.depth + 1, CONCAT(a.path, c.lot_no, '/')
    FROM affected_lot a
    JOIN lot_link ll ON ll.parent_lot_id = a.lot_id
    JOIN lot c       ON c.id = ll.child_lot_id
   WHERE a.depth < 20 AND LOCATE(CONCAT('/', c.lot_no, '/'), a.path) = 0
),
dist (shipment_line_id, customer_id, via_customer_id, lot_id, qty, tier) AS (  -- 2) sevkiyat zinciri
  SELECT sl.id, s.customer_id, CAST(NULL AS UNSIGNED), sl.lot_id, sl.qty, 1
    FROM (SELECT DISTINCT lot_id FROM affected_lot) a
    JOIN shipment_line sl ON sl.lot_id = a.lot_id
    JOIN shipment s ON s.id = sl.shipment_id AND s.from_customer_id IS NULL
  UNION ALL
  SELECT sl.id, s.customer_id, s.from_customer_id, sl.lot_id, sl.qty, d.tier + 1
    FROM dist d
    JOIN shipment s ON s.from_customer_id = d.customer_id
    JOIN shipment_line sl ON sl.shipment_id = s.id AND sl.lot_id = d.lot_id
   WHERE d.tier < 5
),
uniq AS (
  SELECT shipment_line_id, MIN(tier) tier, MAX(customer_id) customer_id,
         MAX(via_customer_id) via_customer_id, MAX(lot_id) lot_id, MAX(qty) qty
    FROM dist GROUP BY shipment_line_id
)
SELECT u.tier,
       c.name                                         AS customer,
       vc.name                                        AS received_via,
       GROUP_CONCAT(DISTINCT l.lot_no ORDER BY l.lot_no) AS lots,
       SUM(u.qty)                                     AS qty,
       MAX(l.uom_code)                                AS uom,
       c.contact_person, c.phone, c.email
  FROM uniq u
  JOIN customer c ON c.id = u.customer_id
  LEFT JOIN customer vc ON vc.id = u.via_customer_id
  JOIN lot l ON l.id = u.lot_id
 GROUP BY u.tier, c.id, c.name, vc.name, c.contact_person, c.phone, c.email
 ORDER BY u.tier, c.name;
