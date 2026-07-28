#!/usr/bin/env bash
# Live watcher for a simstats run. Read-only: it never touches the run, only reads its output.
#
# Usage:  bash tools/simstats/watch_run.sh <run_dir> [refresh_seconds]
#   or:   bash simstats/out/<run_name>/watch.sh          (the per-run wrapper)
#
# Every sim run gets one of these dropped at the top level of its output directory so a run can be
# monitored from a separate terminal without asking anyone what to grep for.
#
# RENDERING: the entire frame is composed into a buffer FIRST, then painted in a single write
# (cursor-home + erase-to-end). Nothing is printed while data is still being gathered. Printing
# progressively -- especially with a blocking `jcmd` in the middle -- makes the display visibly
# repaint line by line, which is what this avoids. Do not reintroduce `clear` + sequential echo.
set -uo pipefail

RUN_DIR="${1:-}"
REFRESH="${2:-15}"
if [ -z "$RUN_DIR" ] || [ ! -d "$RUN_DIR" ]; then
  echo "usage: $0 <run_dir> [refresh_seconds]" >&2
  exit 2
fi
RUN_DIR="$(cd "$RUN_DIR" && pwd)"
RUN_NAME="$(basename "$RUN_DIR")"

LOG=""
for cand in "$RUN_DIR/gate.log" "$RUN_DIR/corpus.log" "$RUN_DIR/run.log"; do
  [ -f "$cand" ] && LOG="$cand" && break
done

# Alternate screen + hidden cursor, restored on any exit.
cleanup() { printf '\033[?25h\033[?1049l'; }
trap cleanup EXIT INT TERM
printf '\033[?1049h\033[?25l\033[2J'

B=$'\033[1m'; R=$'\033[0m'; G=$'\033[32m'; Y=$'\033[33m'; D=$'\033[2m'

while true; do
  # ---------------- gather everything (no output yet) ----------------------
  now_s=$(date '+%H:%M:%S')

  jvm_pids=$(pgrep -f "simstats -config" 2>/dev/null || true)
  jvms=$(printf '%s\n' "$jvm_pids" | grep -c . || true)

  heap_lines=""
  for p in $(printf '%s\n' "$jvm_pids" | head -2); do
    hi=$(timeout 3 jcmd "$p" GC.heap_info 2>/dev/null \
         | grep -oE 'used [0-9]+M, capacity [0-9]+M' | head -1)
    [ -n "$hi" ] && heap_lines+="  heap ${p}  : ${hi}"$'\n'
  done

  round_line=""; recs_line=""; target_met=""
  if [ -n "$LOG" ]; then
    lr=$(grep -aoE -- "--- round [0-9]+(/[0-9]+)?" "$LOG" 2>/dev/null | tail -1)
    [ -n "$lr" ] && round_line="  round       : ${lr#--- }"
    rc=$(grep -aoE "records so far: [0-9]+/[0-9]+" "$LOG" 2>/dev/null | tail -1)
    [ -n "$rc" ] && recs_line="  $rc"
    grep -aq "TARGET MET" "$LOG" 2>/dev/null && target_met="  ${G}TARGET MET${R}"

    started=$(grep -aoE "started [A-Z][a-z]{2} [A-Z][a-z]{2} +[0-9]+ [0-9:]+ [AP]M [A-Z]+ [0-9]{4}" \
              "$LOG" 2>/dev/null | head -1 | sed 's/^started //')
    if [ -n "$started" ]; then
      WATCH_START_EPOCH=$(date -d "$started" +%s 2>/dev/null || true)
      export WATCH_START_EPOCH
    fi

    # Infer the game target when the caller didn't supply one, so ETA works on ANY run:
    # "--- round 4/20" gives total rounds, "Total games: 50 across N shard(s)" gives per-round.
    if [ -z "${WATCH_TARGET_GAMES:-}" ]; then
      rt=$(printf '%s' "$lr" | grep -oE "[0-9]+/[0-9]+" | cut -d/ -f2)
      pr=$(grep -aoE "Total games: [0-9]+" "$LOG" 2>/dev/null | tail -1 | grep -oE "[0-9]+")
      if [ -n "$rt" ] && [ -n "$pr" ]; then
        export WATCH_TARGET_GAMES=$(( rt * pr ))
      fi
    fi
    # Corpus runs pace by records, not games — hand the pair to the ETA math.
    if [ -n "$rc" ]; then
      export WATCH_RECORDS_NOW="${rc#records so far: }"
      WATCH_RECORDS_NOW="${WATCH_RECORDS_NOW%%/*}"
      export WATCH_RECORDS_TARGET="${rc##*/}"
    fi
  fi

  stats=$(python3 - "$RUN_DIR" <<'PY'
import sys, json, glob, statistics as st, os
run = sys.argv[1]
# Games are written at BOTH round level (run_parallel.sh's aggregate) and shard level, so a
# recursive **/games.jsonl glob counts every game twice -- observed as "1513/1000 games (151%)"
# at round 16 of 20. Take exactly one level, most-specific first.
for pat in ("round_*/shard_*/games.jsonl", "shard_*/games.jsonl", "round_*/games.jsonl", "games.jsonl"):
    files = sorted(glob.glob(os.path.join(run, pat)))
    if files:
        break
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
    print("  games       : none logged yet"); sys.exit()

to   = [r for r in rows if r.get("timeout")]
done = [r for r in rows if r.get("completedNormally") and not r.get("timeout")]
el   = [r["elapsedMillis"]/1000 for r in rows if r.get("elapsedMillis")]

# Seat-aware: these runs are seat-rotated, so assuming seat 0 would report a
# first-player-advantage artifact rather than a win rate.
wins = 0
for r in done:
    profs = r.get("run", {}).get("aiProfiles", [])
    if "Ultron" not in profs: continue
    seat = profs.index("Ultron")
    for p in r.get("players", []):
        if p.get("seat") == seat and p.get("won"): wins += 1

print(f"  games       : {len(rows)} logged   {len(done)} completed   {len(to)} timeout ({100*len(to)/len(rows):.1f}%)")
if done:
    p = wins/len(done)
    half = 1.96*((p*(1-p)/len(done))**0.5) if len(done) > 1 else 0
    print(f"  Ultron wins : {wins}/{len(done)} = {100*p:.1f}%  (+/- {100*half:.1f} at 95%, null 50% in 1v1)")
if el:
    print(f"  game secs   : median {st.median(el):.1f}   max {max(el):.0f}")

# ctime is inode CHANGE time on Linux and advances on every append to a live log,
# which collapses the measured span. Use the parsed header timestamp instead.
start = os.environ.get("WATCH_START_EPOCH")
start = float(start) if start and start.strip().isdigit() else None
if start is None:
    logs = glob.glob(os.path.join(run, "**", "run.log"), recursive=True)
    mt = [os.path.getmtime(p) for p in logs if os.path.exists(p)]
    start = min(mt) if mt else None
def dur(sec):
    sec = int(max(0, sec))
    h, m = sec // 3600, (sec % 3600) // 60
    return f"{h}h{m:02d}m" if h else f"{m}m"

mtimes = [os.path.getmtime(f) for f in files if os.path.exists(f)]
if mtimes and start:
    import time
    span = time.time() - start
    if span > 60:
        rate = len(rows)/(span/3600)          # games/hour
        print(f"  throughput  : {rate:.0f} games/hour   elapsed {dur(span)}")

        # ETA is driven by whichever target this run is actually pacing against:
        # a corpus run stops on RECORD count, a gate run stops on game count.
        eta_h = None
        rt, rn = os.environ.get("WATCH_RECORDS_TARGET"), os.environ.get("WATCH_RECORDS_NOW")
        if rt and rn and rt.isdigit() and rn.isdigit() and int(rn) > 0:
            rec_rate = int(rn)/(span/3600)
            left = int(rt) - int(rn)
            if left > 0 and rec_rate > 0:
                eta_h = left/rec_rate
                pct = 100*int(rn)/int(rt)
                print(f"  progress    : {rn}/{rt} records ({pct:.0f}%)  {rec_rate:.0f} rec/hour")
        tgt = os.environ.get("WATCH_TARGET_GAMES")
        if eta_h is None and tgt and tgt.isdigit() and rate > 0:
            left = int(tgt) - len(rows)
            pct = 100*len(rows)/int(tgt)
            print(f"  progress    : {len(rows)}/{tgt} games ({pct:.0f}%)")
            if left > 0:
                eta_h = left/rate
        if eta_h is not None:
            finish = time.strftime("%H:%M", time.localtime(time.time() + eta_h*3600))
            print(f"  ETA         : {dur(eta_h*3600)} remaining  ->  finish ~{finish}")
        elif tgt and tgt.isdigit() and len(rows) >= int(tgt):
            print("  ETA         : target reached")
PY
)

  trouble=$(grep -ahoE "OutOfMemoryError|WEDGE \(log stalled [0-9]+s\)|exceeded its 40s per-decision timeout|still draining in the background" \
            "$RUN_DIR"/*/*/run.log "$RUN_DIR"/*.log 2>/dev/null | sort | uniq -c | sort -rn | head -5)

  # ---------------- compose the whole frame -------------------------------
  frame="${B}=== ${RUN_NAME} ===${R}   ${now_s}"$'\n'
  frame+="${D}${RUN_DIR}${R}"$'\n\n'
  if [ "${jvms:-0}" -gt 0 ]; then
    frame+="  status      : ${G}RUNNING${R} (${jvms} sim JVM(s))"$'\n'
  else
    frame+="  status      : ${Y}no sim JVMs alive${R} (finished, or between rounds)"$'\n'
  fi
  [ -n "$heap_lines" ] && frame+="$heap_lines"
  [ -n "$round_line" ] && frame+="$round_line"$'\n'
  [ -n "$recs_line" ]  && frame+="$recs_line"$'\n'
  [ -n "$target_met" ] && frame+="$target_met"$'\n'
  frame+="$stats"$'\n\n'
  if [ -n "$trouble" ]; then
    frame+="${Y}  trouble signatures:${R}"$'\n'
    frame+="$(printf '%s\n' "$trouble" | sed 's/^/    /')"$'\n'
  else
    frame+="  trouble     : none (no OOM, no wedge, no decision timeouts)"$'\n'
  fi
  frame+=$'\n'"${D}  refresh ${REFRESH}s — Ctrl-C to stop watching (does NOT affect the run)${R}"

  # ---------------- paint in ONE write ------------------------------------
  # \033[H home, each line cleared to EOL (\033[K) so shorter lines leave no debris, then \033[J
  # erases anything below. One write, no flicker, no sequential repaint.
  #
  # The erase-to-EOL is appended with bash parameter substitution, NOT sed: `sed 's/$/\033[K/'`
  # emits the literal characters "33[K" at the end of every line, because GNU sed does not
  # interpret \033 in a replacement. That is a display-corrupting bug, and it is invisible unless
  # you capture the raw bytes.
  printf '\033[H%s\033[K\033[J' "${frame//$'\n'/$'\033[K\n'}"

  sleep "$REFRESH"
done
