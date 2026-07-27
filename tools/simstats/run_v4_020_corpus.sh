#!/usr/bin/env bash
# TICKET-V4-020: V2 on-policy corpus generation. Round harness modeled on run_gate_v4_016.sh
# (rounds x distinct seeds, per-round progress watchdog) with two fixes for known project scars:
#   1. The watchdog kills JVMs by walking the PROCESS TREE of the round's own run_parallel.sh
#      child (pgrep -P, filtered to comm==java), never a `pgrep -f <string>` pattern -- that
#      pattern has self-matched its own shell twice on this project (see FORGE_TRACKER standing
#      rules). A `pgrep -f jar-with-dependencies` run from inside a bash script whose own argv
#      can contain that same substring is exactly the failure mode being avoided.
#   2. A hard WALL-CLOCK budget (default 5h, TICKET-V4-020's corpus-math checkpoint) stops
#      launching new rounds once exhausted, rather than relying on a pre-computed game count that
#      may be wrong (the checkpoint's throughput estimate is necessarily approximate).
#
# RAM: this lane runs Ultron NN-eval (hidden-info pruning, depth-0, copy-budget) vs Default, the
# exact runtime validated across V4-016/V4-018d/V4-018e at --workers 2 --xmx 6g (12g committed,
# comfortably under this box's live-measured ~14-15g run_parallel.sh safety budget on 31g/8-core
# hardware). NOT scaled to more workers: RAM, not CPU, is what run_parallel.sh's own safety guard
# binds on this box, and this exact 2x6g config is the only one with a track record of finishing
# an Ultron-NN-eval lane without OOM.
set -uo pipefail

BASE=/home/william/github/forge
CONFIG=$BASE/configs/simstats/v4_020_v2_onpolicy_corpus.ini
OUT=$BASE/simstats/out/v4_020_v2_onpolicy_corpus
GAMES_PER_ROUND="${GAMES_PER_ROUND:-50}"
# TICKET-V4-020 (resumed after the V4-022 fix): the PRIMARY stop condition is a RECORD COUNT, not a
# wall clock. The first attempt at this ticket used a 5h budget, which — at the leak-throttled
# ~28 games/hour — would have produced ~5K records against V0's 284,458 and silently turned a
# single-variable experiment (state distribution) into a two-variable one (distribution AND corpus
# size). With V4-022 landed (~156 games/hour) a real corpus is affordable; size it explicitly.
# Wall clock is now only a backstop so an unattended overnight run cannot spin forever.
TARGET_RECORDS="${TARGET_RECORDS:-20000}"
WALL_CLOCK_BUDGET_SECONDS="${WALL_CLOCK_BUDGET_SECONDS:-43200}"  # 12h backstop
STALL_KILL_SECONDS=450
BASE_SEED=40260727

# Count records produced so far across every completed round/shard.
count_records() {
  local total=0 n
  for f in "$OUT"/round_*/shard_*/nn_states.bin.gz; do
    [ -f "$f" ] || continue
    n=$(python3 "$BASE/tools/nn/read_nn_states.py" "$f" 2>/dev/null \
        | head -1 | grep -oE '[0-9]+ record' | grep -oE '[0-9]+' || true)
    total=$(( total + ${n:-0} ))
  done
  echo "$total"
}

export ULTRON_NN_EVAL=true
export ULTRON_NN_MODEL_PATH=$BASE/tools/nn/runs/20260724-195756/model.bin
export ULTRON_SIM_MAX_TOP_LEVEL_CANDIDATES=4
export FORGE_SKIP_GROOM=1

mkdir -p "$OUT"
start_ts=$(date +%s)
echo "=== V4-020 CORPUS started $(date) — target=${TARGET_RECORDS} records, ${GAMES_PER_ROUND} games/round, ${WALL_CLOCK_BUDGET_SECONDS}s wall-clock backstop ===" | tee "$OUT/corpus.log"

round=0
while true; do
  round=$((round + 1))
  now=$(date +%s)

  # PRIMARY stop condition: enough records banked.
  recs=$(count_records)
  if (( recs >= TARGET_RECORDS )); then
    echo "=== TARGET MET: ${recs} records >= ${TARGET_RECORDS} after $((round - 1)) rounds ($(( ($(date +%s) - start_ts) / 60 )) min) — stopping ===" | tee -a "$OUT/corpus.log"
    break
  fi
  echo "--- records so far: ${recs}/${TARGET_RECORDS}" | tee -a "$OUT/corpus.log"
  elapsed=$((now - start_ts))
  remaining=$((WALL_CLOCK_BUDGET_SECONDS - elapsed))
  if (( remaining <= 0 )); then
    echo "=== wall-clock budget exhausted (${elapsed}s elapsed) — stopping before round $round ===" | tee -a "$OUT/corpus.log"
    break
  fi

  seed=$((BASE_SEED + round))
  rdir=$OUT/round_$round
  cfg=$OUT/round_$round.ini
  sed -e "s/^seed=.*/seed=$seed/" \
      -e "s|^outputDir=.*|outputDir=$rdir|" \
      -e "s/^games=.*/games=$GAMES_PER_ROUND/" \
      -e "s/^name=.*/name=v4_020_corpus_round_$round/" "$CONFIG" > "$cfg"
  echo "--- round $round seed=$seed started $(date) (${remaining}s remaining in budget)" | tee -a "$OUT/corpus.log"

  bash "$BASE/tools/simstats/run_parallel.sh" "$cfg" --workers 2 --xmx 6g >> "$OUT/corpus.log" 2>&1 &
  rpid=$!

  # Progress watchdog: kill only real JVM (comm==java) descendants of $rpid if no shard run.log
  # mtime advances for > STALL_KILL_SECONDS. Never a pgrep -f string-pattern kill.
  while kill -0 "$rpid" 2>/dev/null; do
    sleep 45
    newest=$(find "$rdir" -name run.log -printf '%T@\n' 2>/dev/null | sort -rn | head -1 | cut -d. -f1)
    now2=$(date +%s)
    age=$(( now2 - ${newest:-now2} ))
    if [ "$age" -gt "$STALL_KILL_SECONDS" ]; then
      echo "!!! round $round WEDGE (log stalled ${age}s) — killing round's java descendants $(date)" | tee -a "$OUT/corpus.log"
      # Walk the process tree rooted at $rpid, collect only comm==java PIDs, kill those.
      to_check=("$rpid")
      java_pids=()
      while [ "${#to_check[@]}" -gt 0 ]; do
        pid="${to_check[0]}"
        to_check=("${to_check[@]:1}")
        children=$(pgrep -P "$pid" 2>/dev/null || true)
        for c in $children; do
          to_check+=("$c")
          comm=$(ps -o comm= -p "$c" 2>/dev/null || true)
          if [ "$comm" = "java" ]; then
            java_pids+=("$c")
          fi
        done
      done
      if [ "${#java_pids[@]}" -gt 0 ]; then
        echo "    killing java PIDs: ${java_pids[*]}" | tee -a "$OUT/corpus.log"
        kill -9 "${java_pids[@]}" 2>/dev/null || true
      fi
      sleep 5
      kill "$rpid" 2>/dev/null || true
      break
    fi
    # Also respect the wall-clock budget mid-round.
    if (( now2 - start_ts >= WALL_CLOCK_BUDGET_SECONDS + STALL_KILL_SECONDS )); then
      echo "!!! round $round exceeded budget mid-round — killing $(date)" | tee -a "$OUT/corpus.log"
      kill "$rpid" 2>/dev/null || true
      break
    fi
  done
  wait "$rpid" 2>/dev/null

  n=$(cat "$rdir"/shard_*/games.jsonl 2>/dev/null | wc -l)
  echo "--- round $round done: $n/$GAMES_PER_ROUND games recorded $(date)" | tee -a "$OUT/corpus.log"
done

cat "$OUT"/round_*/shard_*/games.jsonl > "$OUT/games.jsonl" 2>/dev/null
gz_files=$(find "$OUT" -name nn_states.bin.gz 2>/dev/null | sort)
total=$(wc -l < "$OUT/games.jsonl" 2>/dev/null || echo 0)
echo "=== V4-020 CORPUS COMPLETE $(date): $total games in $OUT/games.jsonl ===" | tee -a "$OUT/corpus.log"
echo "nn_states.bin.gz shard files:" | tee -a "$OUT/corpus.log"
echo "$gz_files" | tee -a "$OUT/corpus.log"
