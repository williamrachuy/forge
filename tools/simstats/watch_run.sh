#!/usr/bin/env bash
# Live watcher for a simstats run — MULTI-NODE aware. Read-only: never touches the run.
#
# Usage:  bash simstats/out/<run_name>/watch.sh [refresh_seconds]
#     or: bash tools/simstats/watch_run.sh <run_dir> [refresh_seconds]
#
# Keys:  [a] aggregate   [1..9] one node   [n] next view   [q]/Ctrl-C quit   any other key = refresh
#
# A run may be split across machines (see MULTI_NODE.md), so a watcher that only sees the local
# directory reports a fraction of the truth. This polls every node in nodes.conf for the same run
# name and shows both the combined picture and a per-node breakdown.
#
# RENDERING: the whole frame is composed into a buffer FIRST and painted in a SINGLE write
# (cursor-home + per-line erase + erase-to-end). Nothing prints while data is still being gathered.
# Do not reintroduce `clear` + sequential echo -- it visibly repaints line by line.
#
# SIGNALS: a bash trap on INT runs the handler and then CONTINUES; the handler must exit explicitly
# or Ctrl-C merely redraws. Only the EXIT trap restores the terminal.
set -uo pipefail

RUN_DIR="${1:-}"
REFRESH="${2:-15}"
[ -z "$RUN_DIR" ] || [ ! -d "$RUN_DIR" ] && { echo "usage: $0 <run_dir> [refresh_seconds]" >&2; exit 2; }
RUN_DIR="$(cd "$RUN_DIR" && pwd)"
RUN_NAME="$(basename "$RUN_DIR")"
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NODES_CONF="$BASE/tools/simstats/nodes.conf"
REMOTE_REPO="/home/william/github/forge"

node_field() { awk -v n="$1" -v f="$2" -F'|' '!/^#/ && $1 ~ n {gsub(/ /,"",$f); print $f; exit}' "$NODES_CONF"; }
if [ -f "$NODES_CONF" ]; then
  mapfile -t NODES < <(awk -F'|' '!/^#/ && NF>3 {gsub(/ /,"",$1); print $1}' "$NODES_CONF")
else
  NODES=(local)
fi

# Per-node result cache. A probe that times out must NOT blank the node -- the node is usually
# fine and merely slow (a loaded asus-vivopc answers a bare ssh in 6-8s). Showing "unreachable" for
# a healthy node is worse than showing slightly stale numbers, so the last good reading is kept and
# labelled with its age.
CACHE="$(mktemp -d)"
cleanup() { printf '\033[?25h\033[?1049l'; rm -rf "$CACHE" 2>/dev/null; }
on_signal() { exit 130; }
trap cleanup EXIT
trap on_signal INT TERM HUP
printf '\033[?1049h\033[?25l\033[2J'

B=$'\033[1m'; R=$'\033[0m'; G=$'\033[32m'; Y=$'\033[33m'; D=$'\033[2m'; C=$'\033[36m'

# ONE format for the per-node header AND its rows. They were previously separate strings -- a
# hand-written header over printf-formatted rows -- so the columns could not line up, and did not.
ROWFMT='  %-2s %-12s %13s %5s %7s %4s %7s %4s %8s  %s\n'

# One probe, run once per node per refresh (a single round trip). Emits one pipe-delimited line:
#   games|completed|timeouts|wins|sum_el|med_el|max_el|last_write|jvms|heap|drain|to40|wedge|oom
PROBE=$(cat <<'EOS'
RUN="$1"
cd "$RUN" 2>/dev/null || { echo "MISSING"; exit 0; }
# Count only JVMs belonging to THIS run. A plain java count is per-NODE, so a finished run would
# report "RUNNING" whenever any other run happened to be using the box -- observed with a completed
# gate showing 2 JVMs that actually belonged to a different corpus. Match the run dir in the cmdline.
RUNBASE=$(basename "$RUN")
# comm=="java" filter, NOT a bare grep: a `grep -c "<pattern>"` matches its OWN cmdline and reports
# a phantom JVM. That self-match has now bitten this project three times (forge_nodes status, the
# watcher liveness line, and here). Keying on comm makes it structurally impossible.
jvms=$(ps -eo comm,args 2>/dev/null | awk -v r="$RUNBASE" '$1=="java" && index($0, "/" r "/") {n++} END{print n+0}')
heap=""
for p in $(ps -eo pid,comm,args 2>/dev/null | awk -v r="$RUNBASE" '$2=="java" && index($0, "/" r "/") {print $1}' | head -1); do
  heap=$(timeout 3 jcmd "$p" GC.heap_info 2>/dev/null | grep -oE 'used [0-9]+M, capacity [0-9]+M' | head -1)
done
drain=$(grep -ahc "still draining in the background" */run.log */*/run.log 2>/dev/null | paste -sd+ | bc 2>/dev/null); drain=${drain:-0}
to40=$(grep -ahc "exceeded its 40s per-decision" */run.log */*/run.log 2>/dev/null | paste -sd+ | bc 2>/dev/null); to40=${to40:-0}
wedge=$(grep -ahc "WEDGE (log stalled" *.log 2>/dev/null | paste -sd+ | bc 2>/dev/null); wedge=${wedge:-0}
oom=$(grep -ahc "OutOfMemoryError" */run.log */*/run.log 2>/dev/null | paste -sd+ | bc 2>/dev/null); oom=${oom:-0}
# Per-node TARGET and launch time, so progress and ETA are computable per node. offload writes
# run.ini with this node's share; round harnesses write round_N.ini. run.ini's mtime is the launch
# instant, which is a better clock than first-game time (it includes JVM startup).
tgt=0; start=0
if [ -f run.ini ]; then
  tgt=$(grep -m1 '^games=' run.ini 2>/dev/null | cut -d= -f2)
  start=$(stat -c %Y run.ini 2>/dev/null)
elif ls round_*.ini >/dev/null 2>&1; then
  per=$(grep -hm1 '^games=' round_1.ini 2>/dev/null | cut -d= -f2)
  rounds=$(ls -1 round_*.ini 2>/dev/null | wc -l)
  tot=$(grep -aoE 'round [0-9]+/[0-9]+' *.log 2>/dev/null | tail -1 | cut -d/ -f2)
  [ -n "$tot" ] && rounds=$tot
  tgt=$(( ${per:-0} * ${rounds:-0} ))
  start=$(stat -c %Y round_1.ini 2>/dev/null)
fi
stats=$(python3 - "$RUN" <<'PY'
import sys, json, glob, os, statistics as st
run = sys.argv[1]
# Games are written at BOTH round and shard level; a recursive glob double-counts. One level only.
for pat in ("round_*/shard_*/games.jsonl","shard_*/games.jsonl","round_*/games.jsonl","games.jsonl"):
    files = sorted(glob.glob(os.path.join(run, pat)))
    if files: break
else:
    files = []
rows=[]
for f in files:
    try:
        for l in open(f):
            l=l.strip()
            if l:
                try: rows.append(json.loads(l))
                except Exception: pass
    except OSError: pass
if not rows:
    print("0|0|0|0|0|0|0|0"); raise SystemExit
to=[r for r in rows if r.get("timeout")]
done=[r for r in rows if r.get("completedNormally") and not r.get("timeout")]
w=0
for r in done:                      # seat-aware: these runs are seat-rotated
    p=r.get("run",{}).get("aiProfiles",[])
    if "Ultron" in p:
        s=p.index("Ultron")
        for pl in r.get("players",[]):
            if pl.get("seat")==s and pl.get("won"): w+=1
el=[r["elapsedMillis"]/1000 for r in rows if r.get("elapsedMillis")]
lw=max((os.path.getmtime(f) for f in files if os.path.exists(f)), default=0)
print("%d|%d|%d|%d|%.0f|%.0f|%.0f|%.0f" % (len(rows),len(done),len(to),w,
      sum(el) if el else 0, st.median(el) if el else 0, max(el) if el else 0, lw))
PY
)
echo "${stats}|${jvms}|${heap}|${drain}|${to40}|${wedge}|${oom}|${tgt:-0}|${start:-0}"
EOS
)

probe_node() {  # node -> the probe line
  local n="$1" host rundir
  host="$(node_field "$n" 2)"
  if [ -z "$host" ] || [ "$host" = "local" ]; then
    rundir="$BASE/simstats/out/$RUN_NAME"
    bash -s "$rundir" <<<"$PROBE" 2>/dev/null | tail -1
  else
    rundir="$REMOTE_REPO/simstats/out/$RUN_NAME"
    timeout 60 ssh -o BatchMode=yes -o ConnectTimeout=15 "$host" bash -s "$rundir" <<<"$PROBE" 2>/dev/null | tail -1
  fi
}

fmt_dur() { local s=${1:-0}; s=${s%.*}; local h=$((s/3600)) m=$(((s%3600)/60)); [ "$h" -gt 0 ] && echo "${h}h${m}m" || echo "${m}m"; }

VIEW="a"   # a = aggregate, or a node index (1-based)
PROBING=0; PROBE_START=0; LAST_REFRESH=0; TICK=0

while true; do
  # -------- gather: one round trip per node --------
  declare -A NG NC NT NW NS NM NX NL NJ NH ND N4 NWD NO NTG NST NETA
  now=$(date +%s)
  # Probes run in the BACKGROUND, in parallel, and the loop does NOT block on them. Blocking meant a
  # keypress sat unhandled for a whole probe cycle (~8s per slow node) before the view changed --
  # which reads as the tool being broken. Input is now serviced against cached data immediately, and
  # a spinner shows when a refresh is actually in flight.
  if [ "$PROBING" = "0" ]; then
    PROBING=1; PROBE_START=$(date +%s); rm -f "$CACHE"/*.done 2>/dev/null
    for i in "${!NODES[@]}"; do
      (
        line="$(probe_node "${NODES[$i]}")"
        if [ -n "$line" ] && [ "$line" != "MISSING" ]; then
          printf '%s\n%s\n' "$(date +%s)" "$line" > "$CACHE/$i.ok"
          echo OK > "$CACHE/$i.state"
        elif [ "$line" = "MISSING" ]; then
          echo NORUN > "$CACHE/$i.state"
        else
          echo FAIL > "$CACHE/$i.state"
        fi
        touch "$CACHE/$i.done"
      ) &
    done
  fi
  ndone=$(ls -1 "$CACHE"/*.done 2>/dev/null | wc -l)
  [ "$ndone" -ge "${#NODES[@]}" ] && { PROBING=0; LAST_REFRESH=$(date +%s); }

  declare -A NSTATE NAGE
  for i in "${!NODES[@]}"; do
    NSTATE[$i]="$(cat "$CACHE/$i.state" 2>/dev/null || echo FAIL)"
    if [ -f "$CACHE/$i.ok" ]; then
      ts="$(sed -n 1p "$CACHE/$i.ok")"; line="$(sed -n 2p "$CACHE/$i.ok")"
      NAGE[$i]=$(( now - ${ts:-now} ))
      IFS='|' read -r g c t w su me mx lw j hp dr t4 wd om tg0 st0 <<<"$line"
      NG[$i]=${g:-0}; NC[$i]=${c:-0}; NT[$i]=${t:-0}; NW[$i]=${w:-0}
      NS[$i]=${su:-0}; NM[$i]=${me:-0}; NX[$i]=${mx:-0}; NL[$i]=${lw:-0}
      NJ[$i]=${j:-0}; NH[$i]="${hp:-}"; ND[$i]=${dr:-0}; N4[$i]=${t4:-0}; NWD[$i]=${wd:-0}; NO[$i]=${om:-0}
      NTG[$i]=${tg0:-0}; NST[$i]=${st0:-0}
      # A cached reading older than two refreshes is stale, not current -- say so rather than
      # presenting old numbers as live.
      [ "${NSTATE[$i]}" != "OK" ] && [ "${NAGE[$i]}" -gt $(( REFRESH * 2 )) ] && NSTATE[$i]="STALE"
    else
      NG[$i]=-1; NAGE[$i]=0
    fi
  done

  # -------- aggregate --------
  tg=0; tc=0; tt=0; tw=0; tsu=0; tjv=0; tdr=0; t44=0; twd=0; tom=0; lastw=0; maxel=0
  for i in "${!NODES[@]}"; do
    [ "${NG[$i]}" = "-1" ] && continue
    tg=$((tg+${NG[$i]})); tc=$((tc+${NC[$i]})); tt=$((tt+${NT[$i]})); tw=$((tw+${NW[$i]}))
    tsu=$((tsu+${NS[$i]})); tjv=$((tjv+${NJ[$i]}))
    tdr=$((tdr+${ND[$i]})); t44=$((t44+${N4[$i]})); twd=$((twd+${NWD[$i]})); tom=$((tom+${NO[$i]}))
    [ "${NL[$i]}" -gt "$lastw" ] && lastw=${NL[$i]}
    [ "${NX[$i]}" -gt "$maxel" ] && maxel=${NX[$i]}
  done
  idle=$(( now - ${lastw:-now} ))
  finished=0; [ "$tjv" = "0" ] && [ "$idle" -gt 120 ] && finished=1

  # Per-node ETA, and the aggregate target. The run finishes when the LAST node finishes, so the
  # overall ETA is the MAX of per-node ETAs -- never a pooled rate, which would understate by
  # assuming a finished fast node keeps contributing.
  ttgt=0; worst=0; worstnode=""
  for i in "${!NODES[@]}"; do
    NETA[$i]=-1
    [ "${NG[$i]}" = "-1" ] && continue
    ttgt=$(( ttgt + ${NTG[$i]:-0} ))
    el=$(( now - ${NST[$i]:-now} ))
    if [ "${NTG[$i]:-0}" -gt 0 ] && [ "${NG[$i]}" -gt 0 ] && [ "$el" -gt 60 ] && [ "${NJ[$i]}" -gt 0 ]; then
      left=$(( ${NTG[$i]} - ${NG[$i]} ))
      if [ "$left" -gt 0 ]; then
        eta=$(( left * el / ${NG[$i]} ))
        NETA[$i]=$eta
        [ "$eta" -gt "$worst" ] && { worst=$eta; worstnode="${NODES[$i]}"; }
      else
        NETA[$i]=0
      fi
    fi
  done

  # Visual cue that a refresh is in flight: a growing ellipsis, plus how long it has been running.
  # Silence during a multi-second ssh round trip is indistinguishable from a hang.
  if [ "$PROBING" = "1" ]; then
    dots=$(( (TICK / 2) % 4 )); e=""
    for ((z=0; z<=dots; z++)); do e+="."; done
    act="${Y}refreshing${e}${R}$(printf '%*s' $((4-dots)) '')${D}($(( $(date +%s) - PROBE_START ))s)${R}"
  else
    nxt=$(( REFRESH - ( $(date +%s) - LAST_REFRESH ) )); [ "$nxt" -lt 0 ] && nxt=0
    act="${D}next refresh ${nxt}s${R}"
  fi
  frame="${B}=== ${RUN_NAME} ===${R}  $(date '+%H:%M:%S')  $act   ${D}[a]ggregate [1-9]node [n]ext [q]uit${R}"$'\n'

  if [ "$VIEW" = "a" ]; then
    frame+="${C}  ── AGGREGATE (all nodes) ──${R}"$'\n'
    if [ "$tjv" -gt 0 ]; then frame+="  state       : ${G}RUNNING${R} ($tjv JVM(s) across $(( ${#NODES[@]} )) node(s))"$'\n'
    elif [ "$finished" = "1" ]; then frame+="  state       : ${D}COMPLETE${R} (idle $(fmt_dur $idle))"$'\n'
    else frame+="  state       : ${Y}between rounds${R} (no JVMs, last write $(fmt_dur $idle) ago)"$'\n'; fi
    frame+="  games       : $tg logged   $tc completed   $tt timeout"
    [ "$tg" -gt 0 ] && frame+="  ($(awk -v a=$tt -v b=$tg 'BEGIN{printf "%.1f", 100*a/b}')%)"
    frame+=$'\n'
    if [ "$tc" -gt 0 ]; then
      frame+="  Ultron wins : $(awk -v w=$tw -v c=$tc 'BEGIN{p=w/c; h=1.96*sqrt(p*(1-p)/c); printf "%d/%d = %.1f%% (+/- %.1f)", w, c, 100*p, 100*h}')  ${D}null 50%${R}"$'\n'
    fi
    [ "$tg" -gt 0 ] && frame+="  game secs   : mean $(awk -v s=$tsu -v g=$tg 'BEGIN{printf "%.0f", s/g}')   max $maxel"$'\n'
    if [ "$ttgt" -gt 0 ]; then
      frame+="  progress    : $tg/$ttgt games ($(awk -v a=$tg -v b=$ttgt 'BEGIN{printf "%.0f", 100*a/b}')%)"$'\n'
      if [ "$finished" = "1" ]; then
        frame+="  ETA         : ${D}complete${R}"$'\n'
      elif [ "$worst" -gt 0 ]; then
        frame+="  ETA         : ${B}$(fmt_dur $worst)${R} remaining  ->  finish ~$(date -d "+$worst seconds" '+%H:%M')   ${D}(limited by $worstnode)${R}"$'\n'
      else
        frame+="  ETA         : ${D}measuring...${R}"$'\n'
      fi
    fi
    part=0; for k in "${!NODES[@]}"; do [ "${NG[$k]}" != "-1" ] && part=$((part+1)); done
    if [ "$part" -le 1 ]; then
      frame+=$'\n'"${C}  ── PER NODE ──${R} ${D}(single-node run — only $part of ${#NODES[@]} nodes participated)${R}"$'\n'
    else
      frame+=$'\n'"${C}  ── PER NODE ──${R} ${D}($part of ${#NODES[@]} nodes participated)${R}"$'\n'
    fi
    frame+="${D}$(printf "$ROWFMT" "" "node" "games/target" "%" "compl" "TO" "win%" "JVM" "ETA" "heap")${R}"$'\n'
    for i in "${!NODES[@]}"; do
      n="${NODES[$i]}"
      if [ "${NG[$i]}" = "-1" ]; then
        # Keep the table shape. A node that simply was not part of this run is NORMAL (most runs are
        # single-node) and must not look like a fault -- it previously rendered as a bare sentence
        # sitting where the numbers go, which reads as an error at a glance.
        case "${NSTATE[$i]}" in
          NORUN) frame+="$(printf "$ROWFMT" "$((i+1))" "$n" "-" "-" "-" "-" "-" "-" "-" "${D}not used by this run${R}")"$'\n' ;;
          FAIL)  frame+="$(printf "$ROWFMT" "$((i+1))" "$n" "?" "?" "?" "?" "?" "?" "?" "${Y}PROBE FAILED — node slow or unreachable${R}")"$'\n' ;;
          *)     frame+="$(printf "$ROWFMT" "$((i+1))" "$n" "?" "?" "?" "?" "?" "?" "?" "${Y}no reading yet${R}")"$'\n' ;;
        esac
        continue
      fi
      wr="-"; [ "${NC[$i]}" -gt 0 ] && wr="$(awk -v w=${NW[$i]} -v c=${NC[$i]} 'BEGIN{printf "%.1f", 100*w/c}')"
      tag=""
      [ "${NSTATE[$i]}" = "STALE" ] && tag=" ${Y}(stale ${NAGE[$i]}s)${R}"
      [ "${NSTATE[$i]}" = "FAIL" ] && tag=" ${Y}(probe failed; showing ${NAGE[$i]}s-old data)${R}"
      pgt="${NG[$i]}/${NTG[$i]:-?}"
      pct="-"; [ "${NTG[$i]:-0}" -gt 0 ] && pct="$(awk -v a=${NG[$i]} -v b=${NTG[$i]} 'BEGIN{printf "%.0f", 100*a/b}')%"
      eta="-"
      [ "${NETA[$i]}" = "0" ] && eta="done"
      [ "${NETA[$i]}" -gt 0 ] 2>/dev/null && eta="$(fmt_dur ${NETA[$i]})"
      frame+="$(printf "$ROWFMT" "$((i+1))" "$n" "$pgt" "$pct" "${NC[$i]}" "${NT[$i]}" "$wr" "${NJ[$i]}" "$eta" "${NH[$i]:-—}")$tag"$'\n'
    done
    frame+=$'\n'
    if [ $((tdr+t44+twd+tom)) -gt 0 ]; then
      frame+="${Y}  trouble${R}     : drain=$tdr  40s-timeouts=$t44  wedges=$twd  OOM=$tom"$'\n'
    else
      frame+="  trouble     : none"$'\n'
    fi
  else
    i=$((VIEW-1)); n="${NODES[$i]:-?}"
    frame+="${C}  ── NODE $VIEW: $n ──${R}   ${D}host $(node_field "$n" 2), $(node_field "$n" 5)x$(node_field "$n" 6)${R}"$'\n'
    if [ "${NG[$i]:--1}" = "-1" ]; then
      case "${NSTATE[$i]:-FAIL}" in
        NORUN) frame+="  ${D}this run has never executed on this node${R}"$'\n' ;;
        *)     frame+="  ${Y}probe failed — node slow or unreachable, no reading yet${R}"$'\n' ;;
      esac
    else
      st="idle"; [ "${NJ[$i]}" -gt 0 ] && st="${G}RUNNING${R}"
      [ "${NSTATE[$i]}" != "OK" ] && st="$st ${Y}(reading ${NAGE[$i]}s old)${R}"
      frame+="  state       : $st (${NJ[$i]} JVM(s))   heap ${NH[$i]:-—}"$'\n'
      frame+="  games       : ${NG[$i]} logged   ${NC[$i]} completed   ${NT[$i]} timeout"$'\n'
      if [ "${NTG[$i]:-0}" -gt 0 ]; then
        frame+="  progress    : ${NG[$i]}/${NTG[$i]} ($(awk -v a=${NG[$i]} -v b=${NTG[$i]} 'BEGIN{printf "%.0f", 100*a/b}')%)"
        if [ "${NETA[$i]}" = "0" ]; then frame+="   ${D}target reached${R}"
        elif [ "${NETA[$i]}" -gt 0 ] 2>/dev/null; then frame+="   ETA $(fmt_dur ${NETA[$i]}) -> ~$(date -d "+${NETA[$i]} seconds" '+%H:%M')"; fi
        frame+=$'\n'
      fi
      if [ "${NC[$i]}" -gt 0 ]; then
        frame+="  Ultron wins : $(awk -v w=${NW[$i]} -v c=${NC[$i]} 'BEGIN{p=w/c; h=1.96*sqrt(p*(1-p)/c); printf "%d/%d = %.1f%% (+/- %.1f)", w, c, 100*p, 100*h}')"$'\n'
      fi
      frame+="  game secs   : median ${NM[$i]}   max ${NX[$i]}"$'\n'
      frame+="  last write  : $(fmt_dur $(( now - ${NL[$i]} ))) ago"$'\n'
      frame+="  trouble     : drain=${ND[$i]}  40s=${N4[$i]}  wedge=${NWD[$i]}  OOM=${NO[$i]}"$'\n'
      frame+="  share       : $(awk -v a=${NG[$i]} -v b=$tg 'BEGIN{if(b>0) printf "%.0f%% of all games", 100*a/b; else printf "-"}')"$'\n'
    fi
  fi

  frame+=$'\n'"${D}  refresh ${REFRESH}s — [a]ggregate [1-9]node [n]ext [q]uit; watching never affects the run${R}"

  printf '\033[H%s\033[K\033[J' "${frame//$'\n'/$'\033[K\n'}"

  # Short poll so a keypress is serviced within ~0.4s and redraws from cache immediately, rather
  # than waiting out a probe cycle. The probe is re-launched only when REFRESH has actually elapsed.
  if [ -t 0 ]; then
    if read -rsn1 -t 0.4 key 2>/dev/null; then
      case "$key" in
        q|Q) break ;;
        a|A) VIEW="a" ;;
        n|N) if [ "$VIEW" = "a" ]; then VIEW=1; elif [ "$VIEW" -ge "${#NODES[@]}" ]; then VIEW="a"; else VIEW=$((VIEW+1)); fi ;;
        [1-9]) [ "$key" -le "${#NODES[@]}" ] && VIEW="$key" ;;
        r|R) LAST_REFRESH=0 ;;
      esac
    fi
    TICK=$((TICK+1))
    [ "$PROBING" = "0" ] && [ $(( $(date +%s) - LAST_REFRESH )) -lt "$REFRESH" ] && continue
  else
    sleep "$REFRESH"
  fi
done
