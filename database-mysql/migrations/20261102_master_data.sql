-- Additive migration; apply once, after 20261101_recall_drill.sql, on existing databases.
-- Master data can now be edited (product cards, suppliers, customers, recipes): the butchery
-- allocation view also gives the SKU, so the API matches outputs by SKU, not by a product name
-- that may change.
USE food_intel;
SET NAMES utf8mb4;

CREATE OR REPLACE VIEW v_butchery_cost_allocation AS
SELECT bo.op_no, p.name AS product_name, bout.output_kind, l.lot_no,
       bout.weight_kg,
       ROUND(100 * bout.weight_kg / bo.input_weight_kg, 2) AS yield_pct,
       bout.allocation_basis, bout.allocated_cost, bout.unit_cost, p.sku
FROM butchery_output bout
JOIN butchery_operation bo ON bo.id = bout.operation_id
JOIN product p ON p.id = bout.product_id
LEFT JOIN lot l ON l.id = bout.lot_id;
