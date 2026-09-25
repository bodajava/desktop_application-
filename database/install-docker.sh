#!/usr/bin/env bash
# ---------------------------------------------------------------------------------------------
#  Installs (or re-installs) the schema + seed data into the Oracle Free Docker container and
#  runs the smoke test.   WARNING: install.sql drops and recreates every application object.
#
#  Usage:  ./install-docker.sh [container-name]          (default container: examhalls-oracle)
#  The EXAM_ADMIN password is read from $APP_USER_PASSWORD or asked for (not echoed).
# ---------------------------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"
CONTAINER="${1:-examhalls-oracle}"
if [ -z "${APP_USER_PASSWORD:-}" ]; then
    read -r -s -p "EXAM_ADMIN password: " APP_USER_PASSWORD
    echo
fi
CONNECT="EXAM_ADMIN/${APP_USER_PASSWORD}@//localhost:1521/FREEPDB1"

echo "Copying scripts into $CONTAINER ..."
docker exec -u root "$CONTAINER" rm -rf /tmp/examhalls-db
docker cp . "$CONTAINER":/tmp/examhalls-db
docker exec -u root "$CONTAINER" chmod -R a+rX /tmp/examhalls-db   # docker cp creates root-owned files

echo "Running install.sql ..."
docker exec -w /tmp/examhalls-db "$CONTAINER" sqlplus -S -L "$CONNECT" @install.sql
echo
echo "Running the smoke test ..."
docker exec -w /tmp/examhalls-db "$CONTAINER" sqlplus -S -L "$CONNECT" @05_smoke_test.sql | grep -E "PASS|FAIL|ABORTED|CHECK"
