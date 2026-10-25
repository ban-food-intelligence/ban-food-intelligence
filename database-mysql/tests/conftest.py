"""MySQL entegrasyon testleri için ortak fixture'lar.

Bağlantı ortam değişkenlerinden okunur (CI ve yerel için aynı):
  MYSQL_HOST (127.0.0.1), MYSQL_PORT (3306), MYSQL_USER (root),
  MYSQL_PASSWORD (boş), MYSQL_SOCKET (opsiyonel)
Şema + seed her test modülü için sıfırdan yüklenir (load.sh ile aynı sıra).
"""
import os
import pathlib
import subprocess

import pymysql
import pytest

DB_DIR = pathlib.Path(__file__).resolve().parents[1]
SQL_FILES = [DB_DIR / "schema.sql", DB_DIR / "seed.sql"]


def _cli_args():
    args = ["mysql", "--default-character-set=utf8mb4", "-u", os.getenv("MYSQL_USER", "root")]
    if os.getenv("MYSQL_PASSWORD"):
        args.append(f"-p{os.environ['MYSQL_PASSWORD']}")
    if os.getenv("MYSQL_SOCKET"):
        args += ["-S", os.environ["MYSQL_SOCKET"]]
    else:
        args += ["-h", os.getenv("MYSQL_HOST", "127.0.0.1"), "-P", os.getenv("MYSQL_PORT", "3306")]
    return args


def load_database():
    cli = _cli_args()
    subprocess.run(cli + ["-e", "DROP DATABASE IF EXISTS food_intel"], check=True)
    for sql_file in SQL_FILES:
        with open(sql_file, "rb") as fh:
            subprocess.run(cli, stdin=fh, check=True)


def run_sql_file(path):
    """Bir SQL dosyasını mysql istemcisiyle çalıştırır (demo betikleri için)."""
    with open(path, "rb") as fh:
        subprocess.run(_cli_args(), stdin=fh, check=True, stdout=subprocess.DEVNULL)


def connect():
    kwargs = dict(user=os.getenv("MYSQL_USER", "root"), password=os.getenv("MYSQL_PASSWORD", ""),
                  database="food_intel", charset="utf8mb4", autocommit=True,
                  cursorclass=pymysql.cursors.DictCursor)
    if os.getenv("MYSQL_SOCKET"):
        kwargs["unix_socket"] = os.environ["MYSQL_SOCKET"]
    else:
        kwargs.update(host=os.getenv("MYSQL_HOST", "127.0.0.1"), port=int(os.getenv("MYSQL_PORT", "3306")))
    return pymysql.connect(**kwargs)


@pytest.fixture(scope="module")
def db():
    load_database()
    conn = connect()
    yield conn
    conn.close()


def call_all(conn, proc, *args):
    """CALL ile dönen tüm sonuç kümelerini liste olarak döndürür."""
    with conn.cursor() as cur:
        cur.callproc(proc, args)
        sets = [list(cur.fetchall())]
        while cur.nextset():
            rows = list(cur.fetchall())
            if cur.description is not None:
                sets.append(rows)
    return sets


def query(conn, sql, params=None):
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return list(cur.fetchall())


def run_query_file(conn, name, param):
    sql = (DB_DIR / "queries" / f"{name}.sql").read_text(encoding="utf-8")
    sql = "\n".join(line for line in sql.splitlines() if not line.lstrip().startswith("--"))
    return query(conn, sql.replace("?", "%s"), (param,))
