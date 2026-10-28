#!/usr/bin/env bash
# Runs every automated check: Go tests, MySQL tests, Android build + unit tests,
# React build. Restores the clean seed at the end (the DB tests write data).
#
#   ./scripts/check-all.sh
#
# Needs: the compose stack running, Python 3 (scripts/test-db.sh installs pytest into .venv),
# JDK 17 + Android SDK, Node 18+. Go is taken from PATH or run in a golang container.
set -euo pipefail
cd "$(dirname "$0")/.."
# Git Bash on Windows would rewrite "-w /src" into a Windows path.
export MSYS_NO_PATHCONV=1

echo "== gofmt + go vet + go test ./..."
if command -v go >/dev/null 2>&1; then
  (cd api-go && test -z "$(gofmt -l .)" && go vet ./... && go test ./...)
else
  docker run --rm -v "$(cd api-go && pwd -W 2>/dev/null || pwd):/src" -w /src golang:1.22-alpine \
    sh -c 'test -z "$(gofmt -l .)" && go vet ./... && go test ./...'
fi

echo "== go integration tests (real MySQL, compose network)"
./scripts/demo-reset.sh >/dev/null
NET=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' ban-mysql)
docker run --rm --network "$NET"   -e MYSQL_TEST_DSN='root:ban@tcp(mysql:3306)/food_intel?parseTime=true&multiStatements=true'   -v "$(cd api-go && pwd -W 2>/dev/null || pwd):/src" -w /src golang:1.22-alpine go test -count=1 ./controllers/ -run Integration

echo "== pytest database-mysql"
./scripts/test-db.sh

echo "== ./gradlew assembleDebug testDebugUnitTest"
(cd mobile-kotlin && ./gradlew assembleDebug testDebugUnitTest --console=plain -q)

echo "== npm test + npm run build"
(cd web-react && { [ -d node_modules/vitest ] || npm ci --no-audit --no-fund; } && npm test --silent && npm run build --silent)

echo "== restore clean seed"
./scripts/demo-reset.sh
echo "ALL CHECKS PASSED"
