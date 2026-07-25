#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: tools/simstats/run_simstats.sh <config.ini>" >&2
  exit 2
fi

jar="${FORGE_JAR:-}"

# Locate the repo root (the directory containing this script's tools/ parent)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Resolve config to absolute path before any cd changes working directory
config="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

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
# Use `tail -1` (last match wins), matching SimStatsConfig.java's parser semantics — a key
# that appears twice (e.g. run_parallel.sh appends an override [run] section after the base
# config's [run] section) must resolve to the LAST occurrence in both places, or the shell
# script and the Java process disagree about where output actually lands.
# `|| true` on the grep guards against `set -o pipefail` aborting the script when the key is
# simply absent (grep exits 1 on no-match, which is not an error here — see `getBoolean`/
# `getInt` fallback semantics in SimStatsConfig).
output_dir="$( { grep -E '^\s*outputDir\s*=' "$config" || true; } | tail -1 | sed 's/.*=\s*//')"
if [[ -z "$output_dir" ]]; then
  echo "Config missing outputDir — cannot determine log location." >&2
  exit 1
fi
mkdir -p "$output_dir"

# Resolve repeat count from config (default 1 = single run, no batching).
repeat="$( { grep -E '^\s*repeat\s*=' "$config" || true; } | tail -1 | sed 's/.*=\s*//')"
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
# FORGE_SIM_XMX overrides the default 8g — used by run_parallel.sh so each worker JVM gets a
# heap sized to fit the machine's RAM budget when running W workers concurrently (see P0.1).
JVM_FLAGS="-Xmx${FORGE_SIM_XMX:-8g} -XX:+UseZGC -XX:MaxGCPauseMillis=200"

GROOM_SCRIPT="${HOME}/games/forge-testing/groom-battlebox.sh"

overall_exit=0
for batch_num in $(seq 1 "$repeat"); do
  echo "" | tee -a "$run_log"
  echo "=== BATCH $batch_num/$repeat started at $(date) ===" | tee -a "$run_log"

  # Refresh the BattleBox deck from Cube Cobra before each batch so mid-run
  # edits to the cube take effect without restarting the sim.
  # FORGE_SKIP_GROOM=1 skips this — set by run_parallel.sh so concurrent shard workers
  # don't all hit the Cube Cobra network fetch / clobber the deck file at once.
  if [[ -n "${FORGE_SKIP_GROOM:-}" ]]; then
    : # skip grooming; caller already groomed once (or explicitly opted out)
  elif [[ -x "$GROOM_SCRIPT" ]]; then
    echo "--- Grooming BattleBox deck..." | tee -a "$run_log"
    if bash "$GROOM_SCRIPT" >> "$run_log" 2>&1; then
      echo "--- Groom complete." | tee -a "$run_log"
    else
      echo "--- Groom FAILED (non-fatal, continuing with existing deck)." | tee -a "$run_log"
    fi
  else
    echo "--- Groom script not found at $GROOM_SCRIPT — skipping." | tee -a "$run_log"
  fi

  cd "$FORGE_GUI"
  # TICKET-V4-016: `|| batch_exit=$?` is load-bearing under `set -e` — with a bare invocation, a
  # non-zero java exit (OOM, kill -9 from a watchdog) terminated this whole script before
  # `batch_exit=$?` ever ran, so the multi-batch continue logic below was dead code (latent since
  # TICKET-101; confirmed by shard wrapper.logs ending at "Killed" with no BATCH-finished line).
  batch_exit=0
  java $JVM_FLAGS -jar "$jar" simstats -config "$config" >> "$run_log" 2>&1 || batch_exit=$?
  echo "=== BATCH $batch_num/$repeat finished (exit=$batch_exit) at $(date) ===" | tee -a "$run_log"
  if [[ $batch_exit -ne 0 ]]; then
    overall_exit=$batch_exit
  fi
done

echo "" | tee -a "$run_log"
echo "=== All $repeat batch(es) complete at $(date) ===" | tee -a "$run_log"
exit $overall_exit
