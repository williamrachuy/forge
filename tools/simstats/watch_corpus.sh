#!/usr/bin/env bash
# Monitor the Ultron v4 bootstrap corpus run. Usage:
#   watch -n 30 tools/simstats/watch_corpus.sh
# or just run it once. ETA is an average-rate projection from process start.
set -uo pipefail

OUT="/home/william/github/forge/simstats/out/v4_007_bootstrap_corpus"
TARGET=8000

done=$(cat "$OUT"/shard_*/games.jsonl 2>/dev/null | wc -l)
pids=$(pgrep -f "v4_007_bootstrap_corpus/shard_" | tr '\n' ' ')
firstpid=$(pgrep -f "v4_007_bootstrap_corpus/shard_" | head -1)

echo "=== Ultron v4 bootstrap corpus  ($(date +'%H:%M:%S %a')) ==="
if [[ -z "$firstpid" ]]; then
  echo "STATUS: no worker JVMs alive."
  if [[ "$done" -ge "$TARGET" ]]; then echo "  -> looks FINISHED ($done/$TARGET games)."
  else echo "  -> STOPPED EARLY at $done/$TARGET. Check $OUT/shard_*/run.log for OutOfMemoryError."; fi
  oom=$(grep -l "OutOfMemoryError" "$OUT"/shard_*/run.log 2>/dev/null | wc -l)
  echo "  shards with OOM in log: $oom"
  exit 0
fi

elapsed=$(ps -o etimes= -p "$firstpid" | tr -d ' ')
pct=$(python3 -c "print(f'{$done/$TARGET*100:.1f}')")
rate=$(python3 -c "print(f'{$done/$elapsed*3600:.0f}' if $elapsed>0 else 'n/a')")
remain=$((TARGET-done))
if [[ "$elapsed" -gt 0 && "$done" -gt 0 ]]; then
  eta_s=$(python3 -c "print(int($remain/($done/$elapsed)))")
  eta_h=$(python3 -c "print(f'{$eta_s/3600:.1f}')")
  finish=$(date -d "+$eta_s seconds" +'%H:%M')
else
  eta_h="n/a"; finish="n/a"
fi
oom=$(grep -c "OutOfMemoryError" "$OUT"/shard_*/run.log 2>/dev/null | awk -F: '{s+=$2} END{print s+0}')
logsz=$(du -ch "$OUT"/shard_*/nn_states.bin.gz 2>/dev/null | tail -1 | cut -f1)

printf "games:   %s / %s  (%s%%)\n" "$done" "$TARGET" "$pct"
printf "rate:    %s games/hr   elapsed %sh %sm\n" "$rate" "$((elapsed/3600))" "$(((elapsed%3600)/60))"
printf "ETA:     ~%sh remaining  ->  finish ~%s\n" "$eta_h" "$finish"
printf "logdata: %s   OOM errors: %s   JVMs: %s\n" "${logsz:-0}" "$oom" "$pids"
