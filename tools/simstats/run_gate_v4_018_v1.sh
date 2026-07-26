#!/usr/bin/env bash
# TICKET-V4-016: the N=300 gate — Ultron(NN, depth-0, copy-budget, V4-015 mask fix) vs Default,
# 1v1 Monarch, null=0.50. Wedge-resilient round design: 6 rounds x 50 games, round-distinct seeds
# (NO `repeat` batching — repeat re-runs identical seeds and would duplicate games; see tracker).
# Each round gets a progress watchdog: if no shard run.log mtime advances for >240s, that round's
# JVMs are killed and the run moves on — a wedge costs at most one round's remainder.
set -uo pipefail

BASE=/home/william/github/forge
TEMPLATE=$BASE/configs/simstats/v4_012_gate_nn_1v1_monarch.ini
OUT=$BASE/simstats/out/v4_018d_gate_v1
ROUNDS=6
PER_ROUND=50
export ULTRON_NN_EVAL=true
export ULTRON_NN_MODEL_PATH=$BASE/tools/nn/runs/20260725-203035/model.bin
export ULTRON_SIM_MAX_TOP_LEVEL_CANDIDATES=4
export FORGE_SKIP_GROOM=1

mkdir -p "$OUT"
echo "=== V4-016 GATE started $(date) — $ROUNDS rounds x $PER_ROUND games ===" | tee "$OUT/gate.log"

for r in $(seq 1 "$ROUNDS"); do
  seed=$((30260801 + r))
  rdir=$OUT/round_$r
  cfg=$OUT/round_$r.ini
  sed -e "s/^seed=.*/seed=$seed/" \
      -e "s|^outputDir=.*|outputDir=$rdir|" \
      -e "s/^games=.*/games=$PER_ROUND/" \
      -e "s/^name=.*/name=v4_018d_gate_v1_round_$r/" "$TEMPLATE" > "$cfg"
  echo "--- round $r/$ROUNDS seed=$seed started $(date)" | tee -a "$OUT/gate.log"

  bash "$BASE/tools/simstats/run_parallel.sh" "$cfg" --workers 2 --xmx 6g >> "$OUT/gate.log" 2>&1 &
  rpid=$!

  # Progress watchdog for this round.
  while kill -0 "$rpid" 2>/dev/null; do
    sleep 45
    newest=$(find "$rdir" -name run.log -printf '%T@\n' 2>/dev/null | sort -rn | head -1 | cut -d. -f1)
    now=$(date +%s)
    age=$(( now - ${newest:-now} ))
    if [ "$age" -gt 240 ]; then
      echo "!!! round $r WEDGE (log stalled ${age}s) — killing round JVMs $(date)" | tee -a "$OUT/gate.log"
      pgrep -f "jar-with-dependencies.*round_$r" | xargs -r kill -9
      sleep 5
      kill "$rpid" 2>/dev/null
      break
    fi
  done
  wait "$rpid" 2>/dev/null
  n=$(cat "$rdir"/shard_*/games.jsonl 2>/dev/null | wc -l)
  echo "--- round $r done: $n/$PER_ROUND games recorded $(date)" | tee -a "$OUT/gate.log"
done

cat "$OUT"/round_*/shard_*/games.jsonl > "$OUT/games.jsonl" 2>/dev/null
total=$(wc -l < "$OUT/games.jsonl")
echo "=== GATE RUNS COMPLETE $(date): $total games in $OUT/games.jsonl ===" | tee -a "$OUT/gate.log"
echo "=== gate.py verdict (null=0.50, 1v1):" | tee -a "$OUT/gate.log"
python3 "$BASE/tools/simstats/gate.py" "$OUT/games.jsonl" --profile Ultron --null 0.5 --min-games 150 | tee -a "$OUT/gate.log"
