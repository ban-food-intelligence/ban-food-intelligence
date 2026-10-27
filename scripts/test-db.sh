#!/usr/bin/env bash
# Runs the MySQL test suite (database-mysql/tests) against the running compose stack,
# then restores the clean demo seed. Needs only Docker (or Podman) and Python 3:
# pytest and pymysql are installed into ./.venv, and the mysql client inside the
# compose container is used, so no local mysql client is required.
#
#   ./scripts/test-db.sh            (COMPOSE="podman compose" for Podman)
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=${COMPOSE:-"docker compose"}

venv_python() { ls .venv/bin/python .venv/Scripts/python.exe 2>/dev/null | head -1 || true; }
if [ -z "$(venv_python)" ]; then
  for py in python3 python py; do
    if "$py" -c "import sys; sys.exit(sys.version_info < (3, 9))" 2>/dev/null; then
      "$py" -m venv .venv && break
    fi
  done
fi
PY=$(venv_python)
[ -n "$PY" ] || { echo "Python 3.9+ is required"; exit 1; }
"$PY" -m pip install -q --disable-pip-version-check -r requirements-dev.txt

rc=0
MYSQL_CLI="${MYSQL_CLI:-$COMPOSE exec -T mysql mysql}" "$PY" -m pytest database-mysql -q -p no:cacheprovider || rc=$?
COMPOSE="$COMPOSE" ./scripts/demo-reset.sh >/dev/null
echo ">> clean seed restored"
exit $rc
