#!/usr/bin/env bash
# Parallel sim runner (Phase 0, P0.1 of the Ultron v3 plan).
#
# Launches W worker JVMs, each running a disjoint shard of games (disjoint seed range via
# run.seedOffset — see SimStatsConfig/SimulateStats), each writing to its own shard directory,
# then merges all shards' games.jsonl into a single combined file.
#
# RAM SAFETY: this machine runs other important services. Worker count and per-JVM heap are
# computed from measured `nproc` / `free -g` so that total committed heap stays <= 50% of
# TOTAL system RAM and never exceeds currently-available RAM. Both are configurable via CLI
# flags if the computed defaults are wrong for a given moment (e.g. other services temporarily
# using more/less RAM than usual).
#
# Usage:
#   tools/simstats/run_parallel.sh <config.ini> [--workers N] [--xmx SIZEg] [--games N]
#
# --workers N   override the computed worker count
# --xmx SIZEg   override the computed per-worker -Xmx (e.g. "3g")
# --games N     override the total game count (default: run.games from the config)
#
# Refuses to run if the config has sim.adaptiveWeights=true — adaptive weights share mutable
# state in ~/.forge/ultron-learning/ and parallel workers would race/corrupt it. Ultron v3 does
# not use that mechanism; this is a hard guard against accidentally parallelizing a v2 config.

set -euo pipefail

usage() {
  echo "usage: tools/simstats/run_parallel.sh <config.ini> [--workers N] [--xmx SIZEg] [--games N]" >&2
  exit 2
}

if [[ $# -lt 1 ]]; then
  usage
fi

CONFIG_ARG="$1"; shift
WORKERS_OVERRIDE=""
XMX_OVERRIDE=""
GAMES_OVERRIDE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --workers) WORKERS_OVERRIDE="$2"; shift 2 ;;
    --xmx) XMX_OVERRIDE="$2"; shift 2 ;;
    --games) GAMES_OVERRIDE="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONFIG="$(cd "$(dirname "$CONFIG_ARG")" && pwd)/$(basename "$CONFIG_ARG")"

if [[ ! -f "$CONFIG" ]]; then
  echo "Config not found: $CONFIG" >&2
  exit 1
fi

# --- Guard: refuse configs with adaptive weights enabled -------------------------------------
if grep -Eq '^\s*adaptiveWeights\s*=\s*true\s*$' "$CONFIG"; then
  echo "REFUSING to run in parallel: config has sim.adaptiveWeights=true." >&2
  echo "Adaptive weights share mutable state in ~/.forge/ultron-learning/ — concurrent" >&2
  echo "workers would race on read-modify-write of weights.json / ultron_card_stats.json." >&2
  echo "Ultron v3 does not use adaptive weights. Set sim.adaptiveWeights=false (or remove" >&2
  echo "the key) to run this config in parallel." >&2
  exit 1
fi

# --- Parse config values we need at the shell level -------------------------------------------
cfg_get() {
  grep -E "^\s*$1\s*=" "$CONFIG" | tail -1 | sed -E 's/^[^=]*=\s*//'
}

base_seed="$(cfg_get seed)"
base_seed="${base_seed:-$(date +%s)}"
run_name="$(cfg_get name)"
run_name="${run_name:-$(basename "$CONFIG" .ini)}"
total_games_cfg="$(cfg_get games)"
total_games="${GAMES_OVERRIDE:-${total_games_cfg:-1}}"
output_dir="$(cfg_get outputDir)"
if [[ -z "$output_dir" ]]; then
  echo "Config missing outputDir — cannot determine shard/merge locations." >&2
  exit 1
fi

# --- Compute RAM-safe worker count / heap size -------------------------------------------------
cores="$(nproc)"
# `free -g` columns: total used free shared buff/cache available
read -r total_ram_gb _ _ _ _ avail_ram_gb < <(free -g | awk '/^Mem:/{print $2, $3, $4, $5, $6, $7}')

# Budget = min(50% of total RAM, currently-available RAM), with a 1 GB safety margin held back
# so the runner never eats into the last GB some other process might need mid-run.
half_total=$(( total_ram_gb / 2 ))
budget_gb=$(( half_total < avail_ram_gb ? half_total : avail_ram_gb ))
budget_gb=$(( budget_gb - 1 ))
if (( budget_gb < 2 )); then
  echo "Measured RAM budget too small for any worker (budget=${budget_gb}g after 1g safety margin;" >&2
  echo "total=${total_ram_gb}g avail=${avail_ram_gb}g). Free up RAM or run serially." >&2
  exit 1
fi

if [[ -n "$WORKERS_OVERRIDE" ]]; then
  workers="$WORKERS_OVERRIDE"
else
  # Leave at least one core for other services; cap at what the RAM budget can support at
  # a minimum 2g/worker heap.
  workers=$(( cores > 1 ? cores - 1 : 1 ))
  max_workers_by_ram=$(( budget_gb / 2 ))
  if (( max_workers_by_ram < 1 )); then max_workers_by_ram=1; fi
  if (( workers > max_workers_by_ram )); then workers=$max_workers_by_ram; fi
fi
if (( workers < 1 )); then workers=1; fi

if [[ -n "$XMX_OVERRIDE" ]]; then
  xmx="$XMX_OVERRIDE"
else
  xmx_gb=$(( budget_gb / workers ))
  if (( xmx_gb < 2 )); then xmx_gb=2; fi
  if (( xmx_gb > 8 )); then xmx_gb=8; fi   # 8g matches the largest heap ever validated (past serial runs)
  xmx="${xmx_gb}g"
fi

committed_gb=$(( workers * ${xmx%g} ))
echo "=== RAM safety check ==="
echo "cores=$cores  total_ram=${total_ram_gb}g  available_ram=${avail_ram_gb}g"
echo "budget (min(50% total, available) - 1g margin) = ${budget_gb}g"
echo "workers=$workers  xmx_per_worker=$xmx  total_committed=${committed_gb}g"
if (( committed_gb > budget_gb + 1 )); then
  echo "REFUSING: committed heap ${committed_gb}g exceeds budget ${budget_gb}g (+1g margin already spent)." >&2
  exit 1
fi
echo "========================="

# --- Split games across shards -----------------------------------------------------------------
base_per_shard=$(( total_games / workers ))
remainder=$(( total_games % workers ))
if (( base_per_shard == 0 && remainder == 0 )); then
  echo "run.games ($total_games) is 0." >&2
  exit 1
fi

mkdir -p "$output_dir"
merged_log="$output_dir/parallel_run.log"
: > "$merged_log"
echo "=== Parallel sim run started at $(date) ===" | tee -a "$merged_log"
echo "Config: $CONFIG" | tee -a "$merged_log"
echo "Total games: $total_games across $workers shard(s)" | tee -a "$merged_log"
echo "Base seed: $base_seed" | tee -a "$merged_log"

pids=()
offset=0
shard_dirs=()
for ((w = 0; w < workers; w++)); do
  shard_games=$base_per_shard
  if (( w < remainder )); then
    shard_games=$(( shard_games + 1 ))
  fi
  if (( shard_games == 0 )); then
    continue
  fi
  shard_dir="$output_dir/shard_$w"
  mkdir -p "$shard_dir"
  shard_dirs+=("$shard_dir")
  shard_config="$shard_dir/shard.ini"

  # Build the shard config: copy the base config verbatim, then append overrides. Later keys
  # win in SimStatsConfig's parser (values map keeps last-seen), so appending after [run] works
  # regardless of where in the file the original keys live.
  cp "$CONFIG" "$shard_config"
  {
    echo ""
    echo "[run]"
    echo "games=$shard_games"
    echo "seed=$base_seed"
    echo "seedOffset=$offset"
    echo "outputDir=$shard_dir"
  } >> "$shard_config"

  echo "Shard $w: games=$shard_games seedOffset=$offset dir=$shard_dir" | tee -a "$merged_log"

  (
    export FORGE_SIM_XMX="$xmx"
    export FORGE_SKIP_GROOM=1
    nice -n 10 bash "$REPO_ROOT/tools/simstats/run_simstats.sh" "$shard_config"
  ) >> "$shard_dir/wrapper.log" 2>&1 &
  pids+=("$!")

  offset=$(( offset + shard_games ))
done

echo "Launched ${#pids[@]} worker JVM(s), waiting..." | tee -a "$merged_log"

overall_exit=0
for i in "${!pids[@]}"; do
  pid="${pids[$i]}"
  if ! wait "$pid"; then
    echo "Worker $i (pid $pid) exited non-zero." | tee -a "$merged_log"
    overall_exit=1
  fi
done

echo "=== All shards finished at $(date) ===" | tee -a "$merged_log"

# --- Merge ---------------------------------------------------------------------------------
merged_jsonl="$output_dir/games.jsonl"
: > "$merged_jsonl"
for shard_dir in "${shard_dirs[@]}"; do
  shard_jsonl="$shard_dir/games.jsonl"
  if [[ -f "$shard_jsonl" ]]; then
    cat "$shard_jsonl" >> "$merged_jsonl"
  else
    echo "WARNING: missing $shard_jsonl — shard produced no output" | tee -a "$merged_log"
    overall_exit=1
  fi
done

merged_count="$(wc -l < "$merged_jsonl" | tr -d ' ')"
echo "Merged $merged_count game record(s) into $merged_jsonl" | tee -a "$merged_log"

exit $overall_exit
