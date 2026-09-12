#!/usr/bin/env sh
# Convenience wrapper around the fat jar.
#   ./jdt.sh server --archives data/archives --store data/store
#   ./jdt.sh client list
#   ./jdt.sh gen --out data/archives
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
JAR="$DIR/build/libs/jDeltaTransfer-1.0.0-all.jar"
if [ ! -f "$JAR" ]; then
    echo "Fat jar not found. Build it first:  ./gradlew fatJar" >&2
    exit 1
fi
JAVA_BIN="${JDT_JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}"
JAVA_BIN="${JAVA_BIN:-java}"
exec "$JAVA_BIN" ${JDT_OPTS:--Xmx4g} -jar "$JAR" "$@"
