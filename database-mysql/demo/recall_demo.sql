-- =====================================================================
-- Videodaki uçtan uca geri çağırma demosu (sırayla çalıştırın)
--   mysql --default-character-set=utf8mb4 food_intel < database-mysql/demo/recall_demo.sql
-- =====================================================================
USE food_intel;

-- 1) Kalite sorunu: ET-2026-001. Etkilenen her şey (kayıt ilişkilerinden):
CALL sp_trace_forward('ET-2026-001');

-- 2) Geri izleme: SUC-2026-045 hangi lotlardan, hangi tedarikçiden, hangi emirle?
CALL sp_trace_backward('SUC-2026-045');

-- 3) Geri çağırmayı başlat: ET-2026-001 ve SUC-2026-045 RECALLED olur
CALL sp_recall_initiate('GC-2026-001', 'ET-2026-001',
     'Et partisinde kalite sorunu (laboratuvar bulgusu)', 'kalite.sorumlusu');

-- 4) Market A 30 paketi iade eder -> karantina deposuna RETURN hareketi
INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost, ref_type, ref_id, note)
SELECT l.id, w.id, 'RETURN', 30, l.unit_cost, 'customer', c.id, 'GC-2026-001 iadesi'
  FROM lot l, warehouse w, customer c
 WHERE l.lot_no = 'SUC-2026-045' AND w.code = 'KARANTINA' AND c.code = 'MKT-A';

-- 5) Geri çağrılan lot satılamaz (Kotlin POS / API aynı kuralı DB seviyesinde görür):
--    Beklenen hata: LOT_BLOCKED: lot is blocked or recalled
-- INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost)
-- SELECT l.id, w.id, 'SALE', -1, l.unit_cost FROM lot l, warehouse w
--  WHERE l.lot_no='SUC-2026-045' AND w.code='KARANTINA';

-- 6) Market bazlı miktar / sevkiyat / iletişim listesi ve depodaki bloke stok
CALL sp_recall_report('GC-2026-001');

-- 7) Denetim izi
SELECT table_name, row_id, action, old_value, new_value, changed_by, changed_at FROM audit_log;
