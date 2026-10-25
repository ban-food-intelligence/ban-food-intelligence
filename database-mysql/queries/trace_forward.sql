-- İleri izleme (hammadde lotu -> tüm mamul / parça lotları + stok)
-- Parametre: ? = kök lot no (ör. 'ET-2026-001')
-- Sevkiyat ve POS satışları için: CALL sp_trace_forward('ET-2026-001');
WITH RECURSIVE fwd (lot_id, depth, path) AS (
  SELECT l.id, 0, CAST(CONCAT('/', l.lot_no, '/') AS CHAR(1000))
    FROM lot l WHERE l.lot_no = ?
  UNION ALL
  SELECT ll.child_lot_id, f.depth + 1, CONCAT(f.path, c.lot_no, '/')
    FROM fwd f
    JOIN lot_link ll ON ll.parent_lot_id = f.lot_id
    JOIN lot c       ON c.id = ll.child_lot_id
   WHERE f.depth < 20                                         -- derinlik sınırı
     AND LOCATE(CONCAT('/', c.lot_no, '/'), f.path) = 0       -- döngü koruması
)
SELECT MIN(f.depth) AS depth, l.lot_no, p.name AS product, l.origin_type,
       l.initial_qty, l.uom_code, l.status,
       COALESCE((SELECT SUM(sb.qty) FROM stock_balance sb WHERE sb.lot_id = l.id), 0) AS qty_in_stock,
       MIN(f.path) AS path
  FROM fwd f JOIN lot l ON l.id = f.lot_id JOIN product p ON p.id = l.product_id
 GROUP BY l.id, l.lot_no, p.name, l.origin_type, l.initial_qty, l.uom_code, l.status
 ORDER BY depth, l.lot_no;
