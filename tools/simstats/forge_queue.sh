#!/usr/bin/env bash
# forge_queue.sh — run a QUEUE of sim experiments across the node pool, unattended.
#
#   bash tools/simstats/forge_queue.sh run <jobs.tsv>
#   bash tools/simstats/forge_queue.sh plan <jobs.tsv>     # dry run: show dispatch decisions only
#
# WHY THIS EXISTS: driving experiments by hand costs a command + output-parsing round trip per
# step, per experiment, and every one of those is a chance to forget a preflight (it already cost
# us a missing model and a missing config, both SILENT failures). The queue waits for capacity,
# dispatches to a capable node, blocks until done, computes the gate result itself, and appends one
# line of high-signal summary. Issue one command; read one report.
#
# JOB FILE — one job per line, '#' comments ignored, TAB or '|' separated:
#
#   name | kind | config | games | model | env-overrides
#
#   kind = gate  -> a MEASUREMENT. Pinned to the primary node (MULTI_NODE.md 3.4): a slower node
#                   times out more, and timeout exclusion biases win rate (V4-021). Never split.
#          gen   -> generation. May go to any capable node.
#   env  = space-separated KEY=VAL applied to that job only (e.g. the breadth cap).
#
# Capacity rule: a job starts only when the target node has 0 sim JVMs AND enough free RAM for its
# configured workers x xmx, plus a 2GB margin. Nothing is ever launched into a box that is already
# full -- that is how you turn one slow run into two wedged ones.
set -uo pipefail

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NODES_SH="$BASE/tools/simstats/forge_nodes.sh"
NODES_CONF="$BASE/tools/simstats/nodes.conf"
REMOTE_REPO="/home/william/github/forge"
POLL="${QUEUE_POLL:-120}"
MARGIN_GB="${QUEUE_MARGIN_GB:-2}"

node_field() { awk -v n="$1" -v f="$2" -F'|' '!/^#/ && $1 ~ n {gsub(/ /,"",$f); print $f; exit}' "$NODES_CONF"; }
node_names() { awk -F'|' '!/^#/ && NF>3 {gsub(/ /,"",$1); print $1}' "$NODES_CONF"; }
node_role()  { node_field "$1" 8; }
primary_node() { for n in $(node_names); do [ "$(node_role "$n")" = "primary" ] && { echo "$n"; return; }; done; node_names | head -1; }

rs() {
  local node="$1" script="$2" host; host="$(node_field "$node" 2)"
  if [ "$host" = "local" ]; then bash -s <<<"$script"
  else timeout 120 ssh -o BatchMode=yes -o ConnectTimeout=15 "$host" bash -s <<<"$script"; fi
}

# free RAM (GB) and sim-JVM count, in one round trip. comm=="java" so the probe cannot self-match.
node_capacity() {
  rs "$1" 'printf "%s|%s\n" "$(free -g | awk "/Mem:/{print \$7}")" "$(ps -eo comm,args | awk '"'"'$1=="java" && /simstats -config/ {n++} END{print n+0}'"'"')"' 2>/dev/null | tail -1
}

node_can_host() {   # node, kind -> 0 if allowed
  local n="$1" kind="$2"
  # UNSUITABLE means unsuitable for the NN-eval lane FULL STOP -- gate or generation. asus-vivopc
  # OOMed and wedged doing GENERATION (V4-024), so allowing it gen work would just re-run that.
  case "$(node_role "$n")" in
    *UNSUITABLE*) return 1 ;;
  esac
  return 0
}

log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*"; }

wait_for_capacity() {   # node, need_gb -> blocks
  local n="$1" need="$2" cap free jvms
  while true; do
    cap="$(node_capacity "$n")"; IFS='|' read -r free jvms <<<"${cap:-0|9}"
    free=${free:-0}; jvms=${jvms:-9}
    if [ "$jvms" -eq 0 ] && [ "$free" -ge "$need" ]; then return 0; fi
    log "  waiting on $n: ${jvms} sim JVM(s), ${free}GB free (need ${need}GB, 0 JVMs)"
    sleep "$POLL"
  done
}

run_job() {
  local name="$1" kind="$2" cfg="$3" games="$4" model="$5" env="$6"
  local node; node="$(primary_node)"
  if [ "$kind" = "gen" ]; then
    for n in $(node_names); do node_can_host "$n" gen && node="$n" && break; done
  fi
  node_can_host "$node" "$kind" || { log "SKIP $name: no node may host kind=$kind"; return 1; }

  local workers xmx need
  workers="$(node_field "$node" 5)"; xmx="$(node_field "$node" 6)"
  need=$(( workers * ${xmx%g} + MARGIN_GB ))

  log "JOB $name  kind=$kind node=$node games=$games  (needs ${need}GB)"
  wait_for_capacity "$node" "$need"

  # shellcheck disable=SC2086
  ( export ULTRON_NN_MODEL_PATH="$model"; [ -n "$env" ] && export $env
    bash "$NODES_SH" run "$node" "$cfg" "$games" "$name" "$(( 70000000 + RANDOM ))" ) >/dev/null 2>&1

  sleep 45
  local loaded
  loaded="$(rs "$node" "grep -lh 'NeuralStateEvaluator: loaded model' $REMOTE_REPO/simstats/out/$name/shard_*/run.log 2>/dev/null | head -1")"
  if [ -z "$loaded" ]; then
    log "  ABORT $name: no 'loaded model' line -- node may be running HEURISTIC, not the net"
    bash "$NODES_SH" stop "$node" >/dev/null 2>&1
    return 1
  fi
  log "  $name running, network confirmed loaded"

  bash "$BASE/tools/simstats/await_run.sh" "$name" "$POLL" | sed 's/^/    /'
  log "  $name finished"
  summarise "$name" "$node" "$kind" "$env"
}

summarise() {
  local name="$1" node="$2" kind="$3" env="$4"
  local tmp; tmp="$(mktemp)"
  if [ "$(node_field "$node" 2)" = "local" ]; then
    cat "$BASE/simstats/out/$name"/round_*/shard_*/games.jsonl "$BASE/simstats/out/$name"/shard_*/games.jsonl 2>/dev/null > "$tmp"
  else
    bash "$NODES_SH" collect "$name" >/dev/null 2>&1
    cat "$BASE/simstats/out/$name/nodes/$node"/shard_*/games.jsonl 2>/dev/null > "$tmp"
  fi
  local null=0.5
  grep -qE '^players=4' "$BASE/$5" 2>/dev/null && null=0.25
  {
    echo "### $name  [$kind on $node]  ${env:+env: $env}"
    python3 "$BASE/tools/simstats/gate.py" "$tmp" --profile Ultron --null "$null" 2>/dev/null \
      | grep -E "Games counted|Wins|Win rate|Timeouts|p-value" | sed 's/^/    /'
  } | tee -a "$BASE/simstats/out/queue_results.md"
  rm -f "$tmp"
}

cmd_run() {
  local jobs="${1:?jobs file}"
  log "=== forge_queue start: $jobs ==="
  while IFS= read -r line; do
    line="${line%%#*}"; [ -z "${line// }" ] && continue
    IFS='|' read -r name kind cfg games model env <<<"$line"
    name="$(echo "$name" | xargs)"; kind="$(echo "$kind" | xargs)"; cfg="$(echo "$cfg" | xargs)"
    games="$(echo "$games" | xargs)"; model="$(echo "$model" | xargs)"; env="$(echo "${env:-}" | xargs)"
    [ -z "$name" ] && continue
    run_job "$name" "$kind" "$cfg" "$games" "$model" "$env" || log "  job $name did not complete"
  done < "$jobs"
  log "=== forge_queue done — results in simstats/out/queue_results.md ==="
}

cmd_plan() {
  local jobs="${1:?jobs file}"
  printf '%-22s %-5s %-13s %-7s %s\n' JOB KIND NODE GAMES ENV
  while IFS= read -r line; do
    line="${line%%#*}"; [ -z "${line// }" ] && continue
    IFS='|' read -r name kind cfg games model env <<<"$line"
    name="$(echo "$name" | xargs)"; kind="$(echo "$kind" | xargs)"
    local node; node="$(primary_node)"
    [ "$kind" = "gen" ] && for n in $(node_names); do node_can_host "$n" gen && node="$n" && break; done
    node_can_host "$node" "$kind" || node="(none eligible)"
    printf '%-22s %-5s %-13s %-7s %s\n' "$name" "$kind" "$node" "$(echo "$games"|xargs)" "$(echo "${env:-}"|xargs)"
  done < "$jobs"
}

case "${1:-}" in
  run)  cmd_run "${2:?jobs file}" ;;
  plan) cmd_plan "${2:?jobs file}" ;;
  *) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
