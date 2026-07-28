#!/usr/bin/env bash
# Emit an event stream for a (possibly multi-node) run, then EXIT when every node is finished.
#
#   bash tools/simstats/await_run.sh <run_name> [poll_seconds]
#
# Designed to be driven by the Monitor tool: each stdout line becomes one notification, and the
# script exits once the run is done, so the watch ends on its own instead of idling forever.
#
# Emits (low noise by design):
#   RUN <name>: <node> FINISHED (N games)      -- once per node, as it drains
#   RUN <name>: progress N games (per-node)    -- only every PROGRESS_EVERY games
#   RUN <name>: WARNING <what> on <node>       -- OOM / wedge, as soon as seen
#   RUN <name>: COMPLETE - <totals>            -- terminal event, then exit 0
#   RUN <name>: ABANDONED - no progress for Xm -- terminal event, then exit 1
#
# Liveness is scoped to THIS run (matching the run dir in the JVM cmdline), not "any java on the
# box" -- otherwise an unrelated run on the same machine makes a finished run look alive.
set -uo pipefail

# Accepts a COMMA-SEPARATED list of run names. A crash mid-run leaves surviving work under the
# original name and resumed work under a new one (fresh seed range, separate dir so the partial is
# not truncated); the completion signal has to span both or it fires early.
RUNS_CSV="${1:?usage: await_run.sh <run_name>[,<run_name>...] [poll_seconds]}"
IFS=',' read -ra RUNS <<< "$RUNS_CSV"
RUN="${RUNS[0]}"
POLL="${2:-300}"
PROGRESS_EVERY="${PROGRESS_EVERY:-300}"
STALL_MINUTES="${STALL_MINUTES:-45}"

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NODES_CONF="$BASE/tools/simstats/nodes.conf"
REMOTE_REPO="/home/william/github/forge"

node_field() { awk -v n="$1" -v f="$2" -F'|' '!/^#/ && $1 ~ n {gsub(/ /,"",$f); print $f; exit}' "$NODES_CONF"; }
mapfile -t NODES < <(awk -F'|' '!/^#/ && NF>3 {gsub(/ /,"",$1); print $1}' "$NODES_CONF")

# One round trip per node: "<jvms>|<games>|<oom>"
PROBE='RUN="$1"
D="/home/william/github/forge/simstats/out/$RUN"
if [ ! -d "$D" ]; then echo "0|0|0"; exit 0; fi
j=$(ps -eo comm,args 2>/dev/null | awk -v r="$RUN" '"'"'$1=="java" && index($0, "/" r "/") {n++} END{print n+0}'"'"')
# Count a whole LEVEL in one cat. Iterating "for pat in <glob>" walks the EXPANDED file list, so it
# counted only the first shard and silently under-reported a 2-shard node (82 instead of 142).
# Games are written at both round and shard level, so take the most specific level that has data.
g=0
for lvl in "round_*/shard_*" "shard_*" "round_*" "."; do
  n=$(cat $D/$lvl/games.jsonl 2>/dev/null | wc -l)
  [ "$n" -gt 0 ] && { g=$n; break; }
done
o=$(grep -ahc "OutOfMemoryError" "$D"/*/run.log "$D"/*/*/run.log 2>/dev/null | paste -sd+ | bc 2>/dev/null)
echo "${j}|${g}|${o:-0}"'

probe() {
  local n="$1" r="${2:-$RUN}" host
  host="$(node_field "$n" 2)"
  if [ "$host" = "local" ]; then bash -s "$r" <<<"$PROBE" 2>/dev/null | tail -1
  else timeout 60 ssh -o BatchMode=yes -o ConnectTimeout=15 "$host" bash -s "$r" <<<"$PROBE" 2>/dev/null | tail -1; fi
}

declare -A DONE_ANNOUNCED OOM_ANNOUNCED
last_total=0; last_change=$(date +%s); last_bucket=0

while true; do
  total=0; alive=0; detail=""
  for n in "${NODES[@]}"; do
    j=0; g=0; o=0
    for r in "${RUNS[@]}"; do
      line="$(RUN="$r" probe "$n" "$r")"
      IFS='|' read -r rj rg ro <<<"${line:-0|0|0}"
      j=$(( j + ${rj:-0} )); g=$(( g + ${rg:-0} )); o=$(( o + ${ro:-0} ))
    done
    total=$(( total + g ))
    [ "$j" -gt 0 ] && alive=1
    detail+="${n}=${g} "
    if [ "$o" -gt 0 ] && [ -z "${OOM_ANNOUNCED[$n]:-}" ]; then
      echo "RUN $RUNS_CSV: WARNING OutOfMemoryError on $n"; OOM_ANNOUNCED[$n]=1
    fi
    # A node is finished when it has no JVMs for this run but did produce games.
    if [ "$j" = "0" ] && [ "$g" -gt 0 ] && [ -z "${DONE_ANNOUNCED[$n]:-}" ]; then
      echo "RUN $RUNS_CSV: $n FINISHED ($g games)"; DONE_ANNOUNCED[$n]=1
    fi
  done

  now=$(date +%s)
  [ "$total" -ne "$last_total" ] && { last_change=$now; last_total=$total; }

  if [ "$alive" = "0" ] && [ "$total" -gt 0 ]; then
    echo "RUN $RUNS_CSV: COMPLETE - $total games total ($detail)"
    exit 0
  fi

  # Only surface progress at coarse intervals; every poll would be noise.
  bucket=$(( total / PROGRESS_EVERY ))
  if [ "$bucket" -gt "$last_bucket" ]; then
    echo "RUN $RUNS_CSV: progress $total games ($detail)"; last_bucket=$bucket
  fi

  # Nothing produced for a long time while JVMs still exist = wedged, not working.
  if [ $(( (now - last_change) / 60 )) -ge "$STALL_MINUTES" ]; then
    echo "RUN $RUNS_CSV: ABANDONED - no new games for ${STALL_MINUTES}m at $total games ($detail)"
    exit 1
  fi

  sleep "$POLL"
done
