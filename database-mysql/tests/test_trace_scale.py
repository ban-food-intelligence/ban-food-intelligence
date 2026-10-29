"""İzleme ölçek koruması: 4.000 mamule giden bir hammadde lotunun ileri izlemesi hızlı kalmalı.

Lot tablosunda yalnız bu ağaç varken optimizer sp_fill_trace'in özyinelemeli adımını
"o ana kadar bulunan lotlar × tüm lot tablosu" olarak planlıyordu (4.000 lotta ~20 sn, 16 milyon
indeks araması); JOIN_ORDER ipucu planı sabitler. Test kendi boş veritabanını kurar ve siler.
"""
import subprocess
import time

import pytest

from conftest import DB_DIR, _cli_args, call_all, connect

DB = "trace_scale_test"
N = 4000


@pytest.fixture(scope="module")
def empty_db():
    cli = _cli_args()
    schema = (DB_DIR / "schema.sql").read_text(encoding="utf-8").replace("food_intel", DB)
    subprocess.run(cli + ["-e", f"DROP DATABASE IF EXISTS {DB}"], check=True)
    subprocess.run(cli, input=schema.encode("utf-8"), check=True)
    conn = connect(DB)
    yield conn
    conn.close()
    subprocess.run(cli + ["-e", f"DROP DATABASE IF EXISTS {DB}"], check=True)


def test_forward_trace_of_a_widely_used_lot_stays_fast(empty_db):
    empty_db.begin()                        # one commit for the 8.000 rows, not one fsync each
    with empty_db.cursor() as cur:
        cur.execute("INSERT INTO unit_of_measure (code, name, is_weight) VALUES ('PAKET', 'Paket', FALSE)")
        cur.execute("INSERT INTO product (id, sku, name, product_type, uom_code) "
                    "VALUES (1, 'MM-TEST', 'Test', 'FINISHED_GOOD', 'PAKET')")
        cur.executemany(
            "INSERT INTO lot (id, lot_no, product_id, origin_type, lot_date, initial_qty, uom_code, unit_cost) "
            "VALUES (%s, %s, 1, 'PRODUCTION', CURRENT_DATE, 1, 'PAKET', 1)",
            [(1, "SCALE-ROOT")] + [(i + 2, f"SCALE-{i:05d}") for i in range(N)])
        cur.executemany(
            "INSERT INTO lot_link (parent_lot_id, child_lot_id, link_type, qty_consumed, qty_produced, ref_type, ref_id) "
            "VALUES (1, %s, 'PRODUCTION', 1, 1, 'scale_test', 1)", [(i + 2,) for i in range(N)])
    empty_db.commit()
    with empty_db.cursor() as cur:
        cur.execute("ANALYZE TABLE lot, lot_link")
        cur.fetchall()

    t0 = time.perf_counter()
    lots = call_all(empty_db, "sp_trace_forward", "SCALE-ROOT")[0]
    elapsed = time.perf_counter() - t0

    assert len(lots) == N + 1
    assert elapsed < 3.0, f"forward trace over {N} lots took {elapsed:.1f} s"
