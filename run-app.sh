#!/usr/bin/env bash
# ---------------------------------------------------------------------------------------------
#  Exam Halls & Proctoring Allocation Management System — launcher (macOS / Linux)
#
#  Usage:  ./run-app.sh            check the database, then start the app
#          ./run-app.sh --no-check start without the database check
#  Java:   runtime/ next to this script (portable edition), else JAVA_HOME, else PATH
#  Env:    JAVA_HOME / JAVA_OPTS / DB_URL / DB_USERNAME / DB_PASSWORD (override config file)
# ---------------------------------------------------------------------------------------------
set -u
cd "$(dirname "$0")" || exit 1

# ---- Java 21+ ---------------------------------------------------------------------------------
# Order: bundled runtime (portable edition) - JAVA_HOME - java on the PATH
if [ -x "runtime/bin/java" ]; then
    JAVA="runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA="java"
else
    echo "[ERROR] Java was not found. Install Java 21 or newer (e.g. Eclipse Temurin 21) and try again."
    exit 1
fi
JAVA_MAJOR=$("$JAVA" -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/')
if ! [[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || [ "$JAVA_MAJOR" -lt 21 ]; then
    echo "[ERROR] Java 21 or newer is required (found: $("$JAVA" -version 2>&1 | head -n 1))."
    exit 1
fi

# ---- Application jar: distribution bundle first, then a local Maven build ---------------------
APP_JAR="examhalls.jar"
if [ ! -f "$APP_JAR" ]; then
    APP_JAR=$(ls target/exam-halls-proctoring-*-all.jar 2>/dev/null | head -n 1)
fi
if [ -z "$APP_JAR" ] || [ ! -f "$APP_JAR" ]; then
    echo "[ERROR] Application jar not found. Build it with:  mvn -Pdist clean package"
    exit 1
fi

# ---- Configuration ----------------------------------------------------------------------------
if [ ! -f "config/application.properties" ]; then
    echo "[WARN] config/application.properties not found - using built-in defaults."
    echo "       Copy config/application.properties.example and enter the Oracle credentials."
fi

JAVA_OPTS="${JAVA_OPTS:--Xms128m -Xmx768m}"
BASE_OPTS="-Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED"

# ---- Oracle connection check (fallback: explain and let the user decide) ----------------------
if [ "${1:-}" != "--no-check" ]; then
    echo "Checking the Oracle connection..."
    if ! "$JAVA" $BASE_OPTS -jar "$APP_JAR" --check-db; then
        echo
        echo "The database is not reachable. Common fixes:"
        echo "  * Docker:  docker start examhalls-oracle   (wait ~30 s for 'DATABASE IS READY TO USE!')"
        echo "  * Check db.url / db.username / db.password in config/application.properties"
        echo "  * Lab network: make sure port 1521 of the database server is reachable"
        echo
        read -r -p "Start the application anyway? [y/N] " answer
        case "$answer" in
            [yY]*) ;;
            *) exit 1 ;;
        esac
    fi
fi

echo "Starting Exam Halls (logs: $(pwd)/logs/examhalls.log)..."
# shellcheck disable=SC2086
exec "$JAVA" $BASE_OPTS $JAVA_OPTS -jar "$APP_JAR"
