#!/bin/bash
set -e

CODLY_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -n "${CODLY_JAVA_HOME:-}" ]; then
    CODLY_JAVA="$CODLY_JAVA_HOME/bin/java"
elif [ -x "$CODLY_DIR/runtime/bin/java" ]; then
    CODLY_JAVA="$CODLY_DIR/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    CODLY_JAVA="$JAVA_HOME/bin/java"
else
    CODLY_JAVA="java"
fi

if ! command -v "$CODLY_JAVA" >/dev/null 2>&1; then
    echo "Codly: 找不到 Java，请安装 Java 17+ 或设置 CODLY_JAVA_HOME。" >&2
    exit 1
fi
exec "$CODLY_JAVA" -jar "$CODLY_DIR/codly.jar" "$@"
