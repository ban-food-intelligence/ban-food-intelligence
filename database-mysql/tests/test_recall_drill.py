"""Geri çağırma tatbikatı: zamanlar sunucudan, sonuç bir kez yazılır, PASS yalnızca üç koşulla, kayıt değişmez."""
import pymysql
import pytest

from conftest import query

CHECK_ERRORS = (pymysql.err.OperationalError, pymysql.err.IntegrityError)


def _exec(conn, sql, params=None):
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.lastrowid


def _drill(db, no, balanced=1, target=240):
    """ET-2026-001 için açık bir tatbikat (geçmiş bir başlangıç zamanı denenir) ve MKT-A müşterisi."""
    drill = _exec(db, "INSERT INTO recall_drill (drill_no, root_lot_id, target_minutes, started_by, started_at, trace_ms, "
                      "lots_forward, lots_backward, suppliers, balanced, snapshot) "
                      "SELECT %s, id, %s, 'py', '2020-01-01 00:00:00', 4, 2, 1, 1, %s, '{}' FROM lot WHERE lot_no = 'ET-2026-001'",
                  (no, target, balanced))
    contact = _exec(db, "INSERT INTO recall_drill_contact (drill_id, customer_id, tier) SELECT %s, id, 1 FROM customer WHERE code = 'MKT-A'",
                    (drill,))
    return drill, contact


def _close(db, drill, result, reasons=None, findings=None):
    _exec(db, "UPDATE recall_drill SET status = 'CLOSED', closed_by = 'py', closed_at = '2020-01-01', result = %s, "
              "fail_reasons = %s, findings = %s WHERE id = %s", (result, reasons, findings, drill))


def test_start_and_contact_times_are_the_servers(db):
    drill, contact = _drill(db, "TAT-PY-TIME")
    _exec(db, "UPDATE recall_drill_contact SET reached_by = 'py', reached_at = '2020-01-01' WHERE id = %s", (contact,))
    row = query(db, "SELECT d.started_at > NOW(3) - INTERVAL 1 MINUTE AS started, c.reached_at > NOW(3) - INTERVAL 1 MINUTE AS reached "
                    "FROM recall_drill d JOIN recall_drill_contact c ON c.drill_id = d.id WHERE d.id = %s", (drill,))
    assert row == [{"started": 1, "reached": 1}]


def test_a_drill_starts_open_with_unreached_customers(db):
    with pytest.raises(pymysql.err.OperationalError, match="INVALID_INPUT"):
        _exec(db, "INSERT INTO recall_drill (drill_no, root_lot_id, started_by, trace_ms, lots_forward, lots_backward, suppliers, "
                  "balanced, snapshot, status, closed_by, closed_at, result) "
                  "SELECT 'TAT-PY-CLOSED', id, 'py', 1, 1, 1, 0, 1, '{}', 'CLOSED', 'py', NOW(3), 'PASS' FROM lot WHERE lot_no = 'ET-2026-001'")
    drill, _ = _drill(db, "TAT-PY-OPEN")
    with pytest.raises(pymysql.err.OperationalError, match="IMMUTABLE"):
        _exec(db, "INSERT INTO recall_drill_contact (drill_id, customer_id, tier, reached_at, reached_by) "
                  "SELECT %s, id, 1, NOW(3), 'py' FROM customer WHERE code = 'MKT-B'", (drill,))


def test_a_customer_is_reached_once(db):
    _, contact = _drill(db, "TAT-PY-ONCE")
    _exec(db, "UPDATE recall_drill_contact SET reached_by = 'py' WHERE id = %s", (contact,))
    for sql in ["UPDATE recall_drill_contact SET reached_by = 'other' WHERE id = %s",
                "UPDATE recall_drill_contact SET note = 'later' WHERE id = %s",
                "DELETE FROM recall_drill_contact WHERE id = %s"]:
        with pytest.raises(pymysql.err.OperationalError, match="IMMUTABLE"):
            _exec(db, sql, (contact,))


@pytest.mark.parametrize("balanced,reach", [(1, False), (0, True)])
def test_pass_needs_every_quantity_explained_and_every_customer_reached(db, balanced, reach):
    drill, contact = _drill(db, f"TAT-PY-P{balanced}{int(reach)}", balanced=balanced)
    if reach:
        _exec(db, "UPDATE recall_drill_contact SET reached_by = 'py' WHERE id = %s", (contact,))
    with pytest.raises(pymysql.err.OperationalError, match="INVALID_INPUT"):
        _close(db, drill, "PASS")
    assert query(db, "SELECT status FROM recall_drill WHERE id = %s", (drill,)) == [{"status": "OPEN"}]


def test_result_must_match_its_reasons_and_a_fail_needs_findings(db):
    drill, _ = _drill(db, "TAT-PY-FAIL")
    with pytest.raises(CHECK_ERRORS, match="ck_rd_findings"):
        _close(db, drill, "FAIL", "CUSTOMER_NOT_REACHED")
    with pytest.raises(CHECK_ERRORS, match="ck_rd_reasons"):
        _close(db, drill, "FAIL", None, "x")
    _close(db, drill, "FAIL", "CUSTOMER_NOT_REACHED", "İletişim listesi güncellendi")
    row = query(db, "SELECT result, fail_reasons, closed_at > NOW(3) - INTERVAL 1 MINUTE AS now FROM recall_drill WHERE id = %s", (drill,))
    assert row == [{"result": "FAIL", "fail_reasons": "CUSTOMER_NOT_REACHED", "now": 1}]


def test_a_passed_drill_never_changes(db):
    drill, contact = _drill(db, "TAT-PY-PASS")
    _exec(db, "UPDATE recall_drill_contact SET reached_by = 'py' WHERE id = %s", (contact,))
    _close(db, drill, "PASS")
    assert query(db, "SELECT result, closed_at > NOW(3) - INTERVAL 1 MINUTE AS now FROM recall_drill WHERE id = %s",
                 (drill,)) == [{"result": "PASS", "now": 1}]
    for sql in ["UPDATE recall_drill SET findings = 'later' WHERE id = %s",
                "UPDATE recall_drill SET status = 'OPEN', closed_by = NULL, closed_at = NULL, result = NULL WHERE id = %s",
                "DELETE FROM recall_drill WHERE id = %s",
                "INSERT INTO recall_drill_contact (drill_id, customer_id, tier) SELECT %s, id, 1 FROM customer WHERE code = 'MKT-C'"]:
        with pytest.raises(pymysql.err.OperationalError, match="IMMUTABLE"):
            _exec(db, sql, (drill,))
