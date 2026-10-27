#!/usr/bin/env bash
# Runs every automated check: Go tests, MySQL tests, Android build + unit tests,
# React build. Restores the clean seed at the end (the DB tests write data).
#
#   ./scripts/check-all.sh
#
# Needs: the compose stack running, Python with pytest + pymysql, a mysql client on PATH,
# JDK 17 + Android SDK, Node 18+. Go is taken from PATH or run in a golang container.
set -euo pipefail
cd "$(dirname "$0")/.."
PY=${PY:-python}

echo "== go test ./..."
if command -v go >/dev/null 2>&1; then
  (cd api-go && go vet ./... && go test ./...)
else
  docker run --rm -v "$(cd api-go && pwd -W 2>/dev/null || pwd):/src" -w /src golang:1.22-alpine \
    sh -c "go vet ./... && go test ./..."
fi

echo "== pytest database-mysql"
$PY -m pytest database-mysql -q

echo "== ./gradlew assembleDebug testDebugUnitTest"
(cd mobile-kotlin && ./gradlew assembleDebug testDebugUnitTest --console=plain -q)

echo "== npm run build"
(cd web-react && npm run build --silent)

echo "== restore clean seed"
./scripts/demo-reset.sh
echo "ALL CHECKS PASSED"
