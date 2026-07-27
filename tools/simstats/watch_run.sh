#!/usr/bin/env bash
# Live watcher for a simstats run. Read-only: it never touches the run, only reads its output.
#
# Usage:  bash tools/simstats/watch_run.sh <run_dir> [refresh_seconds]
#   or:   bash simstats/out/<run_name>/watch.sh          (the per-run wrapper)
#
# Every sim run gets one of these dropped at the top level of its output directory so a run can be
# monitored from a separate terminal without asking anyone what to grep for.
set -uo pipefail

RUN_DIR="${1:-}"
REFRESH="${2:-15}"
if [ -z "$RUN_DIR" ] || [ ! -d "$RUN_DIR" ]; then
  echo "usage: $0 <run_dir> [refresh_seconds]" >&2
  exit 2
fi
RUN_DIR="$(cd "$RUN_DIR" && pwd)"
RUN_NAME="$(basename "$RUN_DIR")"

# The harness log is gate.log for gate runs, corpus.log for corpus runs.
LOG=""
for cand in "$RUN_DIR/gate.log" "$RUN_DIR/corpus.log" "$RUN_DIR/run.log"; do
  [ -f "$cand" ] && LOG="$cand" && break
done

while true; do
  clear
  printf '\033[1m=== %s ===\033[0m   %s\n' "$RUN_NAME" "$(date '+%H:%M:%S')"
  printf '%s\n' "$RUN_DIR"
  echo

  # --- liveness -----------------------------------------------------------
  jvms=$(pgrep -fc "simstats -config" 2>/dev/null || echo 0)
  if [ "$jvms" -gt 0 ]; then
    printf '  status      : \033[32mRUNNING\033[0m (%s sim JVM(s))\n' "$jvms"
  else
    printf '  status      : \033[33mno sim JVMs alive\033[0m (finished, or between rounds)\n'
  fi

  # heap pressure — the V4-022 failure mode; cheap early warning if it ever returns
  for p in $(pgrep -f "simstats -config" 2>/dev/null | head -2); do
    hi=$(timeout 5 jcmd "$p" GC.heap_info 2>/dev/null | grep -oE 'used [0-9]+M, capacity [0-9]+M' | head -1)
    [ -n "$hi" ] && printf '  heap (%s)  : %s\n' "$p" "$hi"
  done

  # --- progress -----------------------------------------------------------
  if [ -n "$LOG" ]; then
    last_round=$(grep -aoE -- "--- round [0-9]+(/[0-9]+)?" "$LOG" 2>/dev/null | tail -1)
    [ -n "$last_round" ] && printf '  round       : %s\n' "${last_round#--- }"
    recs=$(grep -aoE "records so far: [0-9]+/[0-9]+" "$LOG" 2>/dev/null | tail -1)
    [ -n "$recs" ] && printf '  %s\n' "$recs"
    grep -aq "TARGET MET" "$LOG" 2>/dev/null && printf '  \033[32mTARGET MET\033[0m\n'
  fi

  # --- game stats ---------------------------------------------------------
  # Authoritative start time: the harness log's own header line ("... started <date> ...").
  if [ -n "$LOG" ]; then
    started=$(grep -aoE "started [A-Z][a-z]{2} [A-Z][a-z]{2} +[0-9]+ [0-9:]+ [AP]M [A-Z]+ [0-9]{4}" "$LOG" 2>/dev/null | head -1 | sed 's/^started //')
    if [ -n "$started" ]; then
      WATCH_START_EPOCH=$(date -d "$started" +%s 2>/dev/null || true)
      export WATCH_START_EPOCH
    fi
  fi
  python3 - "$RUN_DIR" <<'PY'
import sys, json, glob, statistics as st, os, time
run = sys.argv[1]
files = sorted(glob.glob(os.path.join(run, "**", "games.jsonl"), recursive=True))
rows = []
for f in files:
    try:
        with open(f) as fh:
            for line in fh:
                line = line.strip()
                if line:
                    try: rows.append(json.loads(line))
                    except Exception: pass
    except OSError:
        pass
if not rows:
    print("  games       : none logged yet")
    sys.exit()

to   = [r for r in rows if r.get("timeout")]
done = [r for r in rows if r.get("completedNormally") and not r.get("timeout")]
el   = [r["elapsedMillis"]/1000 for r in rows if r.get("elapsedMillis")]

# Ultron win rate, seat-aware (seat rotates, so never assume seat 0)
wins = 0
for r in done:
    profs = r.get("run", {}).get("aiProfiles", [])
    if "Ultron" not in profs:
        continue
    seat = profs.index("Ultron")
    for p in r.get("players", []):
        if p.get("seat") == seat and p.get("won"):
            wins += 1

print(f"  games       : {len(rows)} logged   {len(done)} completed   {len(to)} timeout ({100*len(to)/len(rows):.1f}%)")
if done:
    p = wins/len(done)
    # Wald 95% CI is fine for a live readout; the real gate uses gate.py's Wilson interval.
    half = 1.96*((p*(1-p)/len(done))**0.5) if len(done) > 1 else 0
    print(f"  Ultron wins : {wins}/{len(done)} = {100*p:.1f}%  (+/- {100*half:.1f} at 95%, null 50% in 1v1)")
if el:
    print(f"  game secs   : median {st.median(el):.1f}   max {max(el):.0f}")

# Throughput + ETA. Start time is parsed from the harness log's own "... started <date>" header and
# passed in as WATCH_START_EPOCH by the shell above. Do NOT use os.path.getctime here: on Linux
# ctime is the inode *change* time, so it advances on every append to a live log and collapses the
# measured span to seconds (observed: a 50-minute run reporting "6433 games/hour over 3 min").
start = os.environ.get("WATCH_START_EPOCH")
start = float(start) if start and start.strip().isdigit() else None
if start is None:
    # Fallback: oldest shard run.log mtime is still a reasonable floor.
    logs = glob.glob(os.path.join(run, "**", "run.log"), recursive=True)
    mt = [os.path.getmtime(p) for p in logs if os.path.exists(p)]
    start = min(mt) if mt else None
mtimes = [os.path.getmtime(f) for f in files if os.path.exists(f)]
if mtimes and start:
    span = max(mtimes) - start
    if span > 60:
        rate = len(rows)/(span/3600)
        print(f"  throughput  : {rate:.0f} games/hour  (over {span/60:.0f} min)")
        tgt = os.environ.get("WATCH_TARGET_GAMES")
        if tgt and tgt.isdigit() and rate > 0:
            left = int(tgt) - len(rows)
            if left > 0:
                print(f"  ETA         : {left} games left -> ~{left/rate:.1f}h")
PY

  # --- trouble ------------------------------------------------------------
  echo
  trouble=$(grep -ahoE "OutOfMemoryError|WEDGE \(log stalled [0-9]+s\)|exceeded its 40s per-decision timeout|still draining in the background" \
            "$RUN_DIR"/*/*/run.log "$RUN_DIR"/*.log 2>/dev/null | sort | uniq -c | sort -rn | head -5)
  if [ -n "$trouble" ]; then
    printf '\033[33m  trouble signatures:\033[0m\n'
    printf '%s\n' "$trouble" | sed 's/^/    /'
  else
    printf '  trouble     : none (no OOM, no wedge, no decision timeouts)\n'
  fi

  echo
  printf '  refresh %ss — Ctrl-C to stop watching (does NOT affect the run)\n' "$REFRESH"
  sleep "$REFRESH"
done
