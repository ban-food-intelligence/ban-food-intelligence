#!/usr/bin/env bash
# Şemayı ve seed'i sırayla yükler (docker kullanmayanlar için).
# Kullanım: MYSQL="mysql -uroot -h127.0.0.1 -P3306 -pban" ./database-mysql/load.sh
set -euo pipefail
MYSQL=${MYSQL:-"mysql -uroot"}
cd "$(dirname "$0")"
$MYSQL -e "DROP DATABASE IF EXISTS food_intel"
for f in schema.sql seed.sql; do
  echo ">> $f"
  $MYSQL --default-character-set=utf8mb4 < "$f"
done
echo "food_intel hazır."
