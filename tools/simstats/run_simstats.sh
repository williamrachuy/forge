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

# Resolve repeat count from config (default 1 = single run, no batching).
repeat="$(grep -E '^\s*repeat\s*=' "$config" | head -1 | sed 's/.*=\s*//')"
repeat="${repeat:-1}"

run_log="$output_dir/run.log"
games_jsonl="$output_dir/games.jsonl"

# Fresh run: clear log and games file so this invocation owns them cleanly.
# Java writes in APPEND mode, so batches accumulate into the same games.jsonl.
: > "$run_log"
rm -f "$games_jsonl"

echo "=== SimStats run started at $(date) — $repeat batch(es) ===" | tee -a "$run_log"
echo "Config: $config" | tee -a "$run_log"
echo "Output: $output_dir" | tee -a "$run_log"

# -Xmx8g: 4-player Battlebox runs accumulate LKI snapshots. 8g gives headroom per batch.
# -XX:+UseZGC: concurrent GC prevents the pause spikes that push complex games over the
#   per-game timeout. ZGC targets <200ms pauses regardless of heap size.
JVM_FLAGS="-Xmx8g -XX:+UseZGC -XX:MaxGCPauseMillis=200"

overall_exit=0
for batch_num in $(seq 1 "$repeat"); do
  echo "" | tee -a "$run_log"
  echo "=== BATCH $batch_num/$repeat started at $(date) ===" | tee -a "$run_log"
  cd "$FORGE_GUI"
  java $JVM_FLAGS -jar "$jar" simstats -config "$config" >> "$run_log" 2>&1
  batch_exit=$?
  echo "=== BATCH $batch_num/$repeat finished (exit=$batch_exit) at $(date) ===" | tee -a "$run_log"
  if [[ $batch_exit -ne 0 ]]; then
    overall_exit=$batch_exit
  fi
done

echo "" | tee -a "$run_log"
echo "=== All $repeat batch(es) complete at $(date) ===" | tee -a "$run_log"
exit $overall_exit
