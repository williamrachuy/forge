#!/bin/sh
cd "$(dirname "${0}")" || exit 1
unset _JAVA_OPTIONS
export ULTRON_LEARNING_DIR="${HOME}/.forge/ultron-learning"
export ULTRON_TRACE_DIR="${ULTRON_LEARNING_DIR}/trace"
: "${FORGE_DEEP_GAME_TRACE:=true}"
: "${FORGE_DEEP_GAME_TRACE_DIR:=${HOME}/.forge/deep-game-trace}"
export FORGE_DEEP_GAME_TRACE
export FORGE_DEEP_GAME_TRACE_DIR
JAR_NAME="$project.build.finalName$"
JAR_SOURCE="./${JAR_NAME}"
RUNTIME_DIR="${XDG_CACHE_HOME:-${HOME}/.cache}/forge/runtime-jars"

mkdir -p "${RUNTIME_DIR}" || exit 1
if command -v sha256sum >/dev/null 2>&1; then
    JAR_HASH=$(sha256sum "${JAR_SOURCE}" | awk '{print $1}')
else
    JAR_HASH=$(cksum "${JAR_SOURCE}" | awk '{print $1 "-" $2}')
fi
JAR_RUNTIME="${RUNTIME_DIR}/${JAR_NAME}.${JAR_HASH}.jar"

if [ ! -f "${JAR_RUNTIME}" ]; then
    JAR_TMP="${JAR_RUNTIME}.tmp.$$"
    cp "${JAR_SOURCE}" "${JAR_TMP}" || exit 1
    mv "${JAR_TMP}" "${JAR_RUNTIME}" || exit 1
fi

java -Dsun.awt.enableExtraMouseButtons=true $mandatory.java.args$ -jar "${JAR_RUNTIME}" "$@"
