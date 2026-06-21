#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: tools/simstats/run_simstats.sh <config.ini>" >&2
  exit 2
fi

config="$1"
jar="${FORGE_JAR:-}"

# Locate the repo root (the directory containing this script's tools/ parent)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ -z "$jar" ]]; then
  jar="$(find "$REPO_ROOT/forge-gui-desktop/target" -maxdepth 1 -type f \( -name '*jar-with-dependencies.jar' -o -name '*-shaded.jar' -o -name 'forge*.jar' \) | sort | tail -n 1 || true)"
fi

if [[ -z "$jar" || ! -f "$jar" ]]; then
  echo "No runnable Forge jar found. Build one with:" >&2
  echo "  mvn -pl forge-gui-desktop -am -DskipTests package" >&2
  echo "Or set FORGE_JAR=/path/to/forge.jar" >&2
  exit 1
fi

# forge-gui/ must be the working directory: Forge reads locale bundles and res/ from there.
FORGE_GUI="$REPO_ROOT/forge-gui"
if [[ ! -d "$FORGE_GUI" ]]; then
  echo "Expected forge-gui directory not found at: $FORGE_GUI" >&2
  exit 1
fi

# Resolve outputDir from config so the run log lands next to games.jsonl.
output_dir="$(grep -E '^\s*outputDir\s*=' "$config" | head -1 | sed 's/.*=\s*//')"
if [[ -z "$output_dir" ]]; then
  echo "Config missing outputDir — cannot determine log location." >&2
  exit 1
fi
mkdir -p "$output_dir"
run_log="$output_dir/run.log"

# -Xmx8g: long sim runs accumulate LKI snapshot allocations across many games.
# Stock heap (4 GB) OOMs at ~37 games in a 4-player Battlebox run. 8g gives headroom.
cd "$FORGE_GUI"
exec java -Xmx8g -jar "$jar" simstats -config "$config" >> "$run_log" 2>&1
