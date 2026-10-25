"""Görev 1 (EKP) ve Görev 3 (izlenebilirlik) için şartname rakamlarıyla birebir testler."""
from decimal import Decimal as D

import pymysql
import pytest

from conftest import call_all, query, run_query_file


# ---------------------------------------------------------------- Görev 1
def test_ekp_matches_spec(db):
    row = query(db, "SELECT * FROM v_production_cost WHERE order_no = 'URT-2026-045'")[0]
    assert row["material_cost"] == D("1076.00")          # 970 + 48 + 58
    assert row["service_cost"] == D("467.50")            # 85 × 5,50
    assert row["total_cost"] == D("1543.50")
    assert row["actual_qty"] == D("85.000")
    assert row["ekp_display"] == D("18.16")              # 1543,50 / 85
    assert row["ekp"] == D("18.1588")


def test_cost_breakdown_lines_match_spec_table(db):
    rows = query(db, "SELECT cost_element, qty, unit_cost, amount FROM v_production_cost_breakdown "
                     "WHERE order_no = 'URT-2026-045' ORDER BY sort_key, amount DESC")
    amounts = [r["amount"] for r in rows]
    assert amounts == [D("970.00"), D("58.00"), D("48.00"), D("467.50")]


def test_finished_lot_carries_ekp_and_raw_materials_not_on_service_invoice(db):
    lot = query(db, "SELECT unit_cost, initial_qty FROM lot WHERE lot_no = 'SUC-2026-045'")[0]
    assert lot["unit_cost"] == D("18.1588")
    po = query(db, "SELECT service_invoice_total FROM production_order WHERE order_no = 'URT-2026-045'")[0]
    assert po["service_invoice_total"] == D("467.50")    # fatura sadece hizmet


def test_vkp_margin(db):
    row = query(db, "SELECT vkp, gross_profit_total, margin_pct FROM v_production_cost WHERE order_no='URT-2026-045'")[0]
    assert row["gross_profit_total"] == D("573.00")      # 85 × 24,90 − 1543,50
    assert row["margin_pct"] == D("27.07")


def test_quantity_variance_is_recorded(db):
    row = query(db, "SELECT qty_variance, ekp_display FROM v_production_cost WHERE order_no='URT-2026-046'")[0]
    assert row["qty_variance"] == D("-2.000")
    # aynı maliyetler daha az pakete bölünür -> EKP yükselir
    assert row["ekp_display"] > D("18.16")


# ---------------------------------------------------------------- Görev 3
def test_forward_trace_finds_markets_affected_by_et_2026_001(db):
    rows = run_query_file(db, "affected_markets", "ET-2026-001")
    tier1 = {r["customer"]: r["qty"] for r in rows if r["tier"] == 1}
    assert tier1 == {"Market A": D("30.000"), "Market B": D("25.000"), "Market C": D("30.000")}
    assert sum(tier1.values()) == D("85.000")
    tier2 = [(r["customer"], r["received_via"], r["qty"]) for r in rows if r["tier"] == 2]
    assert tier2 == [("Örnek Lokanta", "Market C", D("10.000"))]
    # ET-2026-001 SUC-2026-046'ya girmedi -> toptancı etkilenmemeli
    assert all(r["lots"] == "SUC-2026-045" for r in rows)


def test_forward_trace_procedure_result_sets(db):
    lots, *rest = call_all(db, "sp_trace_forward", "ET-2026-001")
    assert [r["lot_no"] for r in lots] == ["ET-2026-001", "SUC-2026-045"]
    shipments = next(s for s in rest if s and "customer_code" in s[0])
    assert {r["customer_code"] for r in shipments} == {"MKT-A", "MKT-B", "MKT-C", "REST-01"}


def test_backward_trace_reaches_raw_lots_suppliers_and_order(db):
    lots, docs = call_all(db, "sp_trace_backward", "SUC-2026-045")[:2]
    raw = {r["lot_no"]: r["supplier_code"] for r in lots if r["depth"] == 1}
    assert raw == {"ET-2026-001": "TED-ET-01", "BAH-2026-001": "TED-BAH-01", "BAG-2026-001": "TED-BAG-01"}
    assert {d["document_no"] for d in docs} == {"URT-2026-045"}
    assert {d["subcontractor"] for d in docs} == {"Örnek Fason Sucuk Üretim"}


def test_many_to_many_one_raw_lot_into_two_finished_lots(db):
    rows = run_query_file(db, "trace_forward", "BAH-2026-001")
    assert {r["lot_no"] for r in rows} == {"BAH-2026-001", "SUC-2026-045", "SUC-2026-046"}


def test_backward_trace_from_butchery_cut(db):
    rows = run_query_file(db, "trace_backward", "KZB-2026-001")
    assert [r["lot_no"] for r in rows] == ["KZB-2026-001", "KUZU-2026-001"]
    assert rows[1]["via_document"] == "PRC-2026-001"


def test_every_production_input_has_lot_link(db):
    missing = query(db, """
        SELECT pi.id FROM production_input pi
        JOIN production_order po ON po.id = pi.production_order_id AND po.status = 'RECEIVED'
        JOIN production_output pout ON pout.production_order_id = po.id
        LEFT JOIN lot_link ll ON ll.parent_lot_id = pi.lot_id AND ll.child_lot_id = pout.lot_id
        WHERE ll.id IS NULL""")
    assert missing == []


def test_unknown_lot_raises(db):
    with pytest.raises(pymysql.err.OperationalError, match="LOT_NOT_FOUND"):
        call_all(db, "sp_trace_forward", "YOK-0000")


# ---------------------------------------------------------------- Görev 4 entegrasyonu
def test_ml_prediction_shown_with_actual_and_deviation(db):
    row = query(db, "SELECT v.predicted_value, v.actual_value, v.deviation FROM v_ml_prediction_deviation v "
                    "JOIN production_order po ON po.id = v.ref_id AND v.ref_type = 'production_order' "
                    "WHERE po.order_no = 'URT-2026-045' AND v.target_name = 'actual_qty'")[0]
    assert row["actual_value"] == D("85.0000")
    assert abs(row["deviation"]) < D("2.5")
