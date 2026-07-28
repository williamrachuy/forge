#!/usr/bin/env bash
# forge.sh — high-level orchestration for multi-node Forge sim work.
#
# DESIGN CONTRACT: verbose internally, terse externally. Every command prints a compact PASS/FAIL
# block (a dozen lines at most) and writes full detail to simstats/out/<run>/orchestrator.log.
# An operator -- human or agent -- should never need to remember a sequence of steps, and should
# never need to read raw logs to know whether things are healthy. Exit codes are meaningful so
# callers can branch without parsing prose.
#
#   forge.sh doctor                                  health of every node, one screen
#   forge.sh preflight <config> <model>              make every node ready; auto-remediates
#   forge.sh generate  <config> <games> <run> <model>  preflight + launch + VERIFY nn actually loaded
#   forge.sh status    [run]                         aggregate across nodes, one screen
#   forge.sh wait      <run>                         block until all nodes finish, then summarise
#   forge.sh collect   <run>                         gather + merge + VERIFY seed disjointness
#   forge.sh stopall                                 stop sims everywhere
#
# Exit codes: 0 ok / 1 hard failure / 2 usage / 3 preflight remediation needed but not possible.
#
# See MULTI_NODE.md for policy (what may be offloaded, why gates may not, the seed rule).
set -uo pipefail

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NODES_SH="$BASE/tools/simstats/forge_nodes.sh"
NODES_CONF="$BASE/tools/simstats/nodes.conf"
REMOTE_REPO="/home/william/github/forge"

# $'...' so the escapes are real bytes; plain "\033" stays literal in echo and prints as text.
P=$'\033[32m\u2713\033[0m'; F=$'\033[31m\u2717\033[0m'; W=$'\033[33m!\033[0m'
DIM=$'\033[2m'; OFF=$'\033[0m'
ok=0; fail=0
pass() { printf "  $P %s\n" "$1"; ok=$((ok+1)); }
bad()  { printf "  $F %s\n" "$1"; fail=$((fail+1)); }
warn() { printf "  $W %s\n" "$1"; }

node_field() { awk -v n="$1" -v f="$2" -F'|' '!/^#/ && $1 ~ n {gsub(/ /,"",$f); print $f; exit}' "$NODES_CONF"; }
node_names() { awk -F'|' '!/^#/ && NF>3 {gsub(/ /,"",$1); print $1}' "$NODES_CONF"; }
is_local()   { [ "$(node_field "$1" 2)" = "local" ]; }

# Run a script on a node; scripts go over stdin so nested quoting can never mangle them.
rs() {
  local node="$1" script="$2" host
  host="$(node_field "$node" 2)"
  if [ "$host" = "local" ]; then bash -s <<<"$script"
  else timeout 300 ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" bash -s <<<"$script"; fi
}

# Count games for a run on a node. Games are written at BOTH round and shard level; take one level.
node_games() {
  rs "$1" "cd $REMOTE_REPO/simstats/out/$2 2>/dev/null || exit 0
for pat in 'round_*/shard_*/games.jsonl' 'shard_*/games.jsonl' 'round_*/games.jsonl' 'games.jsonl'; do
  n=\$(cat \$pat 2>/dev/null | wc -l); [ \"\$n\" -gt 0 ] && { echo \$n; exit 0; }
done; echo 0" 2>/dev/null | tail -1
}
node_jvms() { rs "$1" "ps -eo comm | grep -c '^java\$'" 2>/dev/null | tail -1; }

# ---------------------------------------------------------------- doctor
cmd_doctor() {
  local model="${1:-}"
  echo "=== forge doctor — $(date '+%H:%M:%S') ==="
  local lsha; lsha="$(git -C "$BASE" rev-parse --short HEAD)"
  echo "  local HEAD $lsha   branch $(git -C "$BASE" rev-parse --abbrev-ref HEAD)"
  if [ -n "$(git -C "$BASE" status --porcelain)" ]; then
    warn "local tree DIRTY — uncommitted changes will not reach remote nodes"
  fi
  for n in $(node_names); do
    echo; echo "  [$n]"
    local probe out
    probe="cd $REMOTE_REPO 2>/dev/null || exit 1
printf '%s|%s|%s|%s|%s|%s|%s\n' \
  \"\$(git rev-parse --short HEAD 2>/dev/null)\" \
  \"\$(ls -la forge-gui-desktop/target/*jar-with-dependencies.jar 2>/dev/null | head -1 | awk '{print \$6\"-\"\$7}')\" \
  \"\$(ps -eo comm | grep -c '^java\$')\" \
  \"\$(free -g | awk '/Mem:/{print \$7}')\" \
  \"\$(nproc)\" \
  \"\$(df -BG --output=avail \$HOME | tail -1 | tr -d ' G')\" \
  \"\$(uptime | sed 's/.*load average: //' | cut -d, -f1)\""
    out="$(rs "$n" "$probe" 2>/dev/null | tail -1)"
    if [ -z "$out" ]; then bad "unreachable, or no repo at $REMOTE_REPO"; continue; fi
    IFS='|' read -r sha jar jvms ram cores disk load <<<"$out"
    [ "$sha" = "$lsha" ] && pass "commit $sha matches local" || bad "commit $sha != local $lsha  (fix: forge.sh preflight)"
    [ -n "$jar" ] && pass "jar $jar" || bad "no shaded jar built  (fix: forge.sh preflight)"
    [ "${jvms:-0}" = "0" ] && pass "idle (0 sim JVMs)" || warn "$jvms sim JVM(s) already running"
    [ "${ram:-0}" -ge 4 ] && pass "RAM ${ram}GB available, ${cores} cores, load ${load}" \
                          || bad "only ${ram}GB RAM available — too little for a worker"
    [ "${disk:-0}" -ge 10 ] && pass "disk ${disk}GB free" || bad "disk only ${disk}GB free"
    if [ -n "$model" ]; then
      rs "$n" "[ -f '$model' ] && echo OK" 2>/dev/null | grep -q OK \
        && pass "model present" || bad "model missing  (fix: forge.sh preflight)"
    fi
  done
  echo; echo "  $ok ok, $fail problem(s)"
  [ "$fail" -eq 0 ] || return 1
}

# ---------------------------------------------------------------- preflight
# Idempotent and self-remediating: bring EVERY node to a launchable state, or explain why not.
cmd_preflight() {
  local cfg="${1:?config}" model="${2:?model}"
  echo "=== forge preflight — $(date '+%H:%M:%S') ==="

  # A config or model that is not committed cannot reach a node. Fail early with the exact fix.
  if [ ! -f "$BASE/$cfg" ]; then bad "config not found locally: $cfg"; return 1; fi
  if git -C "$BASE" status --porcelain -- "$cfg" | grep -q .; then
    bad "config $cfg is uncommitted — remote nodes read it from git"
    echo "      fix: git add $cfg && git commit -m 'config: ...'"
    return 3
  fi
  [ -f "$model" ] || { bad "model not found locally: $model"; return 1; }
  pass "config committed, model present locally"

  local lsha; lsha="$(git -C "$BASE" rev-parse --short HEAD)"
  for n in $(node_names); do
    echo "  [$n]"
    if is_local "$n"; then pass "local node — nothing to sync"; continue; fi
    local rsha; rsha="$(rs "$n" "cd $REMOTE_REPO && git rev-parse --short HEAD" 2>/dev/null | tail -1)"
    if [ "$rsha" != "$lsha" ]; then
      warn "commit $rsha != $lsha — syncing + rebuilding (this takes a minute)"
      bash "$NODES_SH" sync "$n" >/dev/null 2>&1
      # Block until the rebuild finishes; a half-built jar is the BUILD TRAP with extra steps.
      local waited=0
      while [ "$waited" -lt 900 ]; do
        rs "$n" "grep -q '^EXIT=' /tmp/forge_build.log 2>/dev/null && echo DONE" 2>/dev/null | grep -q DONE && break
        sleep 15; waited=$((waited+15))
      done
      local bexit; bexit="$(rs "$n" "grep '^EXIT=' /tmp/forge_build.log 2>/dev/null | tail -1" 2>/dev/null | tail -1)"
      [ "$bexit" = "EXIT=0" ] && pass "synced + rebuilt ($lsha)" || { bad "rebuild failed on $n ($bexit)"; continue; }
    else
      pass "commit $rsha matches"
    fi
    rs "$n" "[ -f '$BASE/$cfg' ] || [ -f '$REMOTE_REPO/$cfg' ] && echo OK" 2>/dev/null | grep -q OK \
      && pass "config present" || bad "config still missing after sync"
    if rs "$n" "[ -f '$model' ] && echo OK" 2>/dev/null | grep -q OK; then
      pass "model present"
    else
      warn "model missing — copying"
      bash "$NODES_SH" push-model "$n" "$model" >/dev/null 2>&1 \
        && pass "model copied" || bad "model copy failed"
    fi
  done
  echo; echo "  $ok ok, $fail problem(s)"
  [ "$fail" -eq 0 ] || return 1
}

# ---------------------------------------------------------------- generate
cmd_generate() {
  local cfg="${1:?config}" games="${2:?games}" run="${3:?run_name}" model="${4:?model}"
  cmd_preflight "$cfg" "$model" || { echo "PREFLIGHT FAILED — not launching."; return 1; }
  echo
  echo "=== forge generate: $run ($games games) ==="
  ULTRON_NN_MODEL_PATH="$model" bash "$NODES_SH" offload "$cfg" "$games" "$run" 2>&1 | sed 's/^/  /'

  # VERIFY the network actually loaded on every node. Without this a node silently falls back to the
  # heuristic AI and produces a corpus with no network in it -- no crash, no warning, worthless data.
  echo
  echo "  verifying neural eval is live on every node (30s)..."
  sleep 30
  local allgood=1
  for n in $(node_names); do
    local hit
    hit="$(rs "$n" "grep -ih 'NeuralStateEvaluator: loaded model' $REMOTE_REPO/simstats/out/$run/shard_*/run.log $REMOTE_REPO/simstats/out/$run/round_*/shard_*/run.log 2>/dev/null | head -1" 2>/dev/null | tail -1)"
    if [ -n "$hit" ]; then
      pass "$n: $(echo "$hit" | grep -oE 'schema [0-9a-f]+, semver [0-9]+' || echo 'model loaded')"
    else
      bad "$n: NO 'loaded model' line — may be running HEURISTIC, not the net"; allgood=0
    fi
  done
  echo
  [ "$allgood" = "1" ] && echo "  launched clean. monitor:  bash tools/simstats/forge.sh status $run" \
                       || { echo "  STOP AND INVESTIGATE — a node is not running the network."; return 1; }
}

# ---------------------------------------------------------------- status
cmd_status() {
  local run="${1:-}"
  echo "=== forge status — $(date '+%H:%M:%S') ==="
  if [ -z "$run" ]; then
    for n in $(node_names); do
      printf "  %-13s jvms=%-3s\n" "$n" "$(node_jvms "$n")"
    done
    return 0
  fi
  local total=0 anyalive=0
  for n in $(node_names); do
    local g j
    g="$(node_games "$n" "$run")"; j="$(node_jvms "$n")"
    g=${g:-0}; j=${j:-0}
    total=$(( total + g )); [ "$j" -gt 0 ] && anyalive=1
    local state="done"; [ "$j" -gt 0 ] && state="RUNNING"
    printf "  %-13s %-8s %5s games   %s JVM(s)\n" "$n" "$state" "$g" "$j"
  done
  echo "  ${DIM}────────────────────────────────${OFF}"
  echo "  TOTAL        $total games"
  # High-signal aggregate: win rate + timeouts + trouble, computed over every node's data at once.
  local tmp; tmp="$(mktemp)"
  for n in $(node_names); do
    if is_local "$n"; then
      cat "$BASE/simstats/out/$run"/round_*/shard_*/games.jsonl "$BASE/simstats/out/$run"/shard_*/games.jsonl 2>/dev/null
    else
      local host; host="$(node_field "$n" 2)"
      timeout 60 ssh -o BatchMode=yes "$host" "cat $REMOTE_REPO/simstats/out/$run/round_*/shard_*/games.jsonl $REMOTE_REPO/simstats/out/$run/shard_*/games.jsonl 2>/dev/null" 2>/dev/null
    fi
  done > "$tmp"
  python3 - "$tmp" <<'PY'
import sys, json, statistics as st
rows=[]
for l in open(sys.argv[1]):
    l=l.strip()
    if l:
        try: rows.append(json.loads(l))
        except Exception: pass
if not rows: print("  (no games yet)"); raise SystemExit
to=[r for r in rows if r.get("timeout")]
done=[r for r in rows if r.get("completedNormally") and not r.get("timeout")]
w=0
for r in done:
    p=r.get("run",{}).get("aiProfiles",[])
    if "Ultron" in p:
        s=p.index("Ultron")
        for pl in r.get("players",[]):
            if pl.get("seat")==s and pl.get("won"): w+=1
el=[r["elapsedMillis"]/1000 for r in rows if r.get("elapsedMillis")]
print(f"  timeouts     {len(to)} ({100*len(to)/len(rows):.1f}%)   median game {st.median(el):.0f}s" if el else "")
if done:
    import math
    p=w/len(done); half=1.96*math.sqrt(p*(1-p)/len(done)) if len(done)>1 else 0
    print(f"  Ultron wins  {w}/{len(done)} = {100*p:.1f}% (+/- {100*half:.1f})")
PY
  rm -f "$tmp"
  [ "$anyalive" = "1" ] && echo "  state: RUNNING" || echo "  state: ALL NODES IDLE"
}

# ---------------------------------------------------------------- wait
cmd_wait() {
  local run="${1:?run_name}" interval="${2:-120}"
  echo "waiting for $run across all nodes (poll ${interval}s)..."
  while true; do
    local alive=0
    for n in $(node_names); do
      local j; j="$(node_jvms "$n")"; [ "${j:-0}" -gt 0 ] && alive=1
    done
    [ "$alive" = "0" ] && break
    sleep "$interval"
  done
  echo "all nodes idle."
  cmd_status "$run"
}

# ---------------------------------------------------------------- collect
cmd_collect() {
  local run="${1:?run_name}"
  echo "=== forge collect: $run ==="
  bash "$NODES_SH" collect "$run" 2>&1 | sed 's/^/  /'
  # VERIFY the seed-disjointness rule instead of trusting it: duplicate gameSeeds across nodes mean
  # duplicated games, an inflated corpus, and falsely narrow confidence intervals.
  echo
  python3 - "$BASE/simstats/out/$run" <<'PY'
import sys, json, glob, os, collections
run=sys.argv[1]
pats=[os.path.join(run,"round_*/shard_*/games.jsonl"), os.path.join(run,"shard_*/games.jsonl"),
      os.path.join(run,"nodes/*/round_*/shard_*/games.jsonl"), os.path.join(run,"nodes/*/shard_*/games.jsonl")]
files=[]
for p in pats: files+=glob.glob(p)
seeds=collections.Counter(); n=0
for f in files:
    for l in open(f):
        l=l.strip()
        if not l: continue
        try: r=json.loads(l)
        except Exception: continue
        n+=1; seeds[r.get("run",{}).get("gameSeed")]+=1
dups=sum(c-1 for c in seeds.values() if c>1)
print(f"  games collected : {n}")
print(f"  distinct seeds  : {len(seeds)}")
if dups:
    print(f"  \033[31m✗ DUPLICATE GAMES: {dups}\033[0m — nodes shared a seed range. Corpus is NOT {n} independent games.")
    sys.exit(1)
print("  \033[32m✓\033[0m no duplicate seeds — nodes were properly disjoint")
PY
}

cmd_stopall() {
  for n in $(node_names); do bash "$NODES_SH" stop "$n" 2>&1 | sed 's/^/  /'; done
}

case "${1:-}" in
  doctor)    shift; cmd_doctor "$@" ;;
  preflight) shift; cmd_preflight "$@" ;;
  generate)  shift; cmd_generate "$@" ;;
  status)    shift; cmd_status "$@" ;;
  wait)      shift; cmd_wait "$@" ;;
  collect)   shift; cmd_collect "$@" ;;
  stopall)   cmd_stopall ;;
  *) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
