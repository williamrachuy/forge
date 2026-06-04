#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: tools/simstats/run_simstats.sh <config.ini>" >&2
  exit 2
fi

config="$1"
jar="${FORGE_JAR:-}"

if [[ -z "$jar" ]]; then
  jar="$(find forge-gui-desktop/target -maxdepth 1 -type f \( -name '*jar-with-dependencies.jar' -o -name '*-shaded.jar' -o -name 'forge*.jar' \) | sort | tail -n 1 || true)"
fi

if [[ -z "$jar" || ! -f "$jar" ]]; then
  echo "No runnable Forge jar found. Build one with:" >&2
  echo "  mvn -pl forge-gui-desktop -am -DskipTests package" >&2
  echo "Or set FORGE_JAR=/path/to/forge.jar" >&2
  exit 1
fi

java -jar "$jar" simstats -config "$config"
