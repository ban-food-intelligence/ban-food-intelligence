"""Stok defteri güvenilirliği, parçalama mutabakatı ve geri çağırma blokajı."""
from decimal import Decimal as D

import pymysql
import pytest

from conftest import call_all, query


def _exec(conn, sql, params=None):
    with conn.cursor() as cur:
        cur.execute(sql, params)


# ---------------------------------------------------------------- stok defteri
def test_balance_cache_equals_ledger(db):
    assert query(db, "SELECT * FROM v_stock_ledger_check WHERE diff <> 0") == []


def test_finished_lot_fully_shipped(db):
    bal = query(db, "SELECT COALESCE(SUM(sb.qty),0) q FROM stock_balance sb JOIN lot l ON l.id=sb.lot_id "
                    "WHERE l.lot_no='SUC-2026-045'")[0]["q"]
    assert bal == D("0.000")


def test_negative_stock_is_rejected(db):
    _exec(db, "INSERT INTO shipment (shipment_no, customer_id, from_warehouse_id, ship_date) "
              "SELECT 'SVK-TEST-NEG', c.id, w.id, '2026-10-20' FROM customer c, warehouse w "
              "WHERE c.code='MKT-A' AND w.code='ANA-DEPO'")
    with pytest.raises(pymysql.err.OperationalError, match="INSUFFICIENT_STOCK"):
        _exec(db, "INSERT INTO shipment_line (shipment_id, lot_id, qty) SELECT s.id, l.id, 1 "
                  "FROM shipment s, lot l WHERE s.shipment_no='SVK-TEST-NEG' AND l.lot_no='SUC-2026-045'")


def test_wrong_sign_is_rejected(db):
    with pytest.raises(pymysql.err.OperationalError, match="MOVEMENT_SIGN"):
        _exec(db, "INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost) "
                  "SELECT l.id, w.id, 'SALE', 1, 0 FROM lot l, warehouse w "
                  "WHERE l.lot_no='SUC-2026-046' AND w.code='MAGAZA-01'")


def test_ledger_is_append_only(db):
    with pytest.raises(pymysql.err.OperationalError, match="IMMUTABLE"):
        _exec(db, "UPDATE stock_movement SET qty = qty WHERE id = 1")
    with pytest.raises(pymysql.err.OperationalError, match="IMMUTABLE"):
        _exec(db, "DELETE FROM stock_movement WHERE id = 1")


def test_each_pos_sale_line_has_its_stock_movement(db):
    rows = query(db, "SELECT sl.qty, sm.qty AS sm_qty, sm.movement_type, sm.lot_id = sl.lot_id AS same_lot "
                     "FROM sale_line sl JOIN stock_movement sm ON sm.id = sl.stock_movement_id")
    assert len(rows) == 3
    for r in rows:
        assert r["movement_type"] == "SALE" and r["sm_qty"] == -r["qty"] and r["same_lot"] == 1


# ---------------------------------------------------------------- Görev 2
def test_spec_example_is_rejected_by_reconciliation_gate(db):
    r = query(db, "SELECT * FROM v_butchery_reconciliation WHERE op_no='PRC-2026-000'")[0]
    assert r["purchase_cost"] == D("287.50")             # 25 × 11,50
    assert r["declared_cost_diff"] == D("63.50")         # 351,00 − 287,50
    assert r["cut_weight_kg"] == D("22.000") and r["weight_diff_kg"] == D("3.000")
    assert (r["weight_ok"], r["declared_cost_ok"]) == (0, 0)
    assert r["status"] == "REJECTED"


def test_posted_butchery_balances_weight_and_cost(db):
    r = query(db, "SELECT * FROM v_butchery_reconciliation WHERE op_no='PRC-2026-001'")[0]
    assert (r["weight_ok"], r["declared_cost_ok"], r["allocation_ok"]) == (1, 1, 1)
    assert r["allocated_total"] == D("287.50")


def test_value_weighted_allocation_reference(db):
    rows = query(db, "SELECT product_name, allocated_cost FROM v_butchery_cost_allocation "
                     "WHERE op_no='PRC-2026-001' AND output_kind='CUT'")
    got = {r["product_name"]: r["allocated_cost"] for r in rows}
    assert got == {"Kuzu but": D("95.61"), "Kuzu sırt/pirzola": D("60.84"), "Kuzu omuz": D("60.18"),
                   "Kuzu kaburga": D("28.08"), "Kuzu kıyma": D("42.79")}


def test_posted_butchery_cuts_have_lots_and_links(db):
    assert query(db, """
        SELECT bout.id FROM butchery_output bout JOIN butchery_operation bo ON bo.id = bout.operation_id
        LEFT JOIN lot_link ll ON ll.child_lot_id = bout.lot_id AND ll.parent_lot_id = bo.input_lot_id
        WHERE bo.status = 'POSTED' AND bout.output_kind <> 'WASTE' AND ll.id IS NULL""") == []


# ---------------------------------------------------------------- geri çağırma
def test_recall_blocks_affected_lots_only(db):
    call_all(db, "sp_recall_initiate", "GC-TEST-001", "ET-2026-001", "Kalite sorunu", "test")
    status = {r["lot_no"]: r["status"] for r in query(db, "SELECT lot_no, status FROM lot")}
    assert status["ET-2026-001"] == status["SUC-2026-045"] == "RECALLED"
    assert status["SUC-2026-046"] == "ACTIVE"            # aynı baharatı paylaşsa da ET-2026-001'i içermez
    assert status["BAH-2026-001"] == "ACTIVE"
    audit = query(db, "SELECT COUNT(*) n FROM audit_log WHERE table_name='lot' AND action='STATUS_CHANGE'")[0]["n"]
    assert audit == 2


def test_recalled_lot_cannot_be_sold_but_can_be_returned(db):
    _exec(db, "INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost, ref_type, ref_id) "
              "SELECT l.id, w.id, 'RETURN', 30, l.unit_cost, 'customer', c.id FROM lot l, warehouse w, customer c "
              "WHERE l.lot_no='SUC-2026-045' AND w.code='KARANTINA' AND c.code='MKT-A'")
    with pytest.raises(pymysql.err.OperationalError, match="LOT_BLOCKED"):
        _exec(db, "INSERT INTO stock_movement (lot_id, warehouse_id, movement_type, qty, unit_cost) "
                  "SELECT l.id, w.id, 'SALE', -1, l.unit_cost FROM lot l, warehouse w "
                  "WHERE l.lot_no='SUC-2026-045' AND w.code='KARANTINA'")


def test_recall_report_lists_customers_with_quantities_and_contacts(db):
    customers, stock = call_all(db, "sp_recall_report", "GC-TEST-001")[:2]
    by_code = {r["customer_code"]: r for r in customers}
    assert {k: v["qty_shipped"] for k, v in by_code.items()} == {
        "MKT-A": D("30.000"), "MKT-B": D("25.000"), "MKT-C": D("30.000"), "REST-01": D("10.000")}
    assert by_code["MKT-A"]["qty_returned"] == D("30.000")
    assert by_code["REST-01"]["tier"] == 2 and by_code["REST-01"]["received_via"] == "Market C"
    assert all(v["phone"] and v["email"] for v in by_code.values())
    assert [(s["lot_no"], s["warehouse_code"], s["qty"]) for s in stock] == [("SUC-2026-045", "KARANTINA", D("30.000"))]
