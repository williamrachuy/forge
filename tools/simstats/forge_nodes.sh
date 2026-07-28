#!/usr/bin/env bash
# Multi-node control for Forge sim workloads. ONE entry point for every cross-machine operation.
#
#   bash tools/simstats/forge_nodes.sh status
#   bash tools/simstats/forge_nodes.sh sync    <node>
#   bash tools/simstats/forge_nodes.sh run     <node> <config.ini> <games> <run_name> [seed_base]
#   bash tools/simstats/forge_nodes.sh collect <run_name>
#   bash tools/simstats/forge_nodes.sh offload <config.ini> <total_games> <run_name>
#   bash tools/simstats/forge_nodes.sh stop    <node>
#
# READ MULTI_NODE.md BEFORE USING THIS. It explains what may and may not be offloaded (gates may
# not), and the seed-disjointness rule that keeps parallel nodes from generating duplicate games.
set -uo pipefail

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NODES="$BASE/tools/simstats/nodes.conf"
REMOTE_REPO="/home/william/github/forge"

# ---------------------------------------------------------------- node table
# Fields: name | host (or "local") | cores | ram_gb | workers | xmx | role
node_field() { awk -v n="$1" -v f="$2" -F'|' '!/^#/ && $1 ~ n {gsub(/ /,"",$f); print $f; exit}' "$NODES"; }
node_names() { awk -F'|' '!/^#/ && NF>3 {gsub(/ /,"",$1); print $1}' "$NODES"; }

# Run a SCRIPT (passed on stdin, never interpolated into a command line) on a node. Passing scripts
# via stdin avoids the double-quoting hazard of `ssh host "cmd with \"nested\" quotes"`, which
# silently produced "DOWN (unreachable)" for every node on the first cut of this tool.
run_script() {
  local node="$1" script="$2" host
  host="$(node_field "$node" 2)"
  if [ "$host" = "local" ]; then
    bash -s <<<"$script"
  else
    timeout 900 ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" bash -s <<<"$script"
  fi
}

require_node() {
  if ! node_names | grep -qx "$1"; then
    echo "unknown node '$1'. Known: $(node_names | tr '\n' ' ')" >&2; exit 2
  fi
}

node_sha() {
  run_script "$1" "cd $REMOTE_REPO 2>/dev/null || exit 1; git rev-parse --short HEAD" 2>/dev/null
}

# ---------------------------------------------------------------- status
cmd_status() {
  local probe
  probe=$(cat <<'EOS'
cd ~/github/forge 2>/dev/null || exit 1
c=$(nproc)
a=$(free -g | awk '/Mem:/{print $7}')
# `pgrep -c` PRINTS 0 and EXITS 1 when nothing matches, so a `|| echo 0` fallback appends a SECOND
# line. The embedded newline then truncates the caller's `IFS='|' read`, silently blanking every
# field after this one -- which presented as "NO-JAR / not on local commit" for a node that was
# fully synced and built. Keep this a single value.
# pgrep -f self-matches the very shell running it (its own cmdline contains the pattern),
# reporting phantom JVMs. Standing project rule: key on real JVMs, comm==java.
s=$(ps -eo pid,comm,args 2>/dev/null | awk '$2=="java" && /simstats -config/ {n++} END{print n+0}'); s=${s:-0}
h=$(git rev-parse --short HEAD 2>/dev/null)
j=$(ls -la forge-gui-desktop/target/*jar-with-dependencies.jar 2>/dev/null | head -1 | awk '{print $6"-"$7}')
l=$(uptime | sed 's/.*load average: //' | cut -d, -f1)
printf '%s|%s|%s|%s|%s|%s\n' "$c" "$a" "$s" "$h" "${j:-NO-JAR}" "$l" | tr -d '\r' | head -1
EOS
)
  printf '%-13s %-6s %-6s %-9s %-5s %-11s %-11s %s\n' NODE REACH CORES AVAIL_GB SIMS LOAD HEAD JAR
  local lsha; lsha="$(git -C "$BASE" rev-parse --short HEAD)"
  for n in $(node_names); do
    local out; out="$(run_script "$n" "$probe" 2>/dev/null)"
    if [ -z "$out" ]; then
      printf '%-13s %-6s %s\n' "$n" "DOWN" "(unreachable, or no repo at $REMOTE_REPO)"
      continue
    fi
    IFS='|' read -r c a s h j l <<<"$out"
    local mark=""; [ "$h" != "$lsha" ] && mark=" <-- NOT on local commit"
    printf '%-13s %-6s %-6s %-9s %-5s %-11s %-11s %s%s\n' "$n" "ok" "$c" "${a:-?}" "${s:-0}" "${l:-?}" "$h" "${j:-NO-JAR}" "$mark"
  done
  echo
  echo "local HEAD: $lsha   — nodes must match this before a shared run (forge_nodes.sh sync <node>)"
}

# ---------------------------------------------------------------- sync
# Push the local commit to a node and rebuild its shaded jar. The jar is PER NODE -- the BUILD TRAP
# (see FORGE_TRACKER.md) applies independently on every machine. Syncing the commit is NOT enough.
cmd_sync() {
  local n="$1"; require_node "$n"
  local host; host="$(node_field "$n" 2)"
  [ "$host" = "local" ] && { echo "'$n' is local; nothing to sync"; return 0; }

  local branch; branch="$(git -C "$BASE" rev-parse --abbrev-ref HEAD)"
  echo "==> pushing $branch to $n"
  git -C "$BASE" remote get-url "$n" >/dev/null 2>&1 || \
      git -C "$BASE" remote add "$n" "${host}:${REMOTE_REPO}"
  # Requires `git config receive.denyCurrentBranch updateInstead` on the node (see MULTI_NODE.md §6).
  git -C "$BASE" push "$n" "$branch" || { echo "push failed (is the node's worktree dirty?)" >&2; return 1; }

  echo "==> rebuilding jar on $n"
  run_script "$n" "$(cat <<'EOS'
cd ~/github/forge || exit 1
tmux kill-session -t forge_build 2>/dev/null
tmux new-session -d -s forge_build "cd ~/github/forge && mvn -pl forge-ai,forge-gui-desktop -am package -DskipTests -Dcheckstyle.skip=true > /tmp/forge_build.log 2>&1; echo EXIT=\$? >> /tmp/forge_build.log"
echo "build started in tmux session forge_build"
EOS
)"
  echo "    watch:  ssh $host 'tail -f /tmp/forge_build.log'"
  echo "    verify: forge_nodes.sh status   (jar date must be newer than the commit)"
}

# ---------------------------------------------------------------- run
cmd_run() {
  local n="$1" cfg="$2" games="$3" run_name="$4" seed_base="${5:-}"
  require_node "$n"
  local host workers xmx
  host="$(node_field "$n" 2)"; workers="$(node_field "$n" 5)"; xmx="$(node_field "$n" 6)"
  [ -z "$seed_base" ] && seed_base=$(( 50000000 + RANDOM ))

  # Guard: a node on a different commit is running different AI. Refuse rather than produce a
  # corpus that is silently half-generated by older code.
  local lsha rsha
  lsha="$(git -C "$BASE" rev-parse --short HEAD)"
  rsha="$(node_sha "$n")"
  if [ "$lsha" != "$rsha" ]; then
    echo "REFUSING: $n is at '${rsha:-unreachable}', local is at '$lsha'." >&2
    echo "          run: bash tools/simstats/forge_nodes.sh sync $n" >&2
    return 1
  fi

  local out="${REMOTE_REPO}/simstats/out/${run_name}"
  echo "==> $n: $games games, seed_base=$seed_base, ${workers}x${xmx} -> simstats/out/${run_name}"
  run_script "$n" "$(cat <<EOS
set -uo pipefail
cd $REMOTE_REPO || exit 1
mkdir -p '$out'
sed -e 's/^seed=.*/seed=$seed_base/' \
    -e 's|^outputDir=.*|outputDir=$out|' \
    -e 's/^games=.*/games=$games/' \
    -e 's/^name=.*/name=$run_name/' '$cfg' > '$out/run.ini'
bash tools/simstats/install_watcher.sh '$out' $games >/dev/null 2>&1
tmux kill-session -t forge_run 2>/dev/null
tmux new-session -d -s forge_run "cd $REMOTE_REPO && export ULTRON_NN_EVAL=true ULTRON_SIM_MAX_TOP_LEVEL_CANDIDATES=4 FORGE_SKIP_GROOM=1 && bash tools/simstats/run_parallel.sh '$out/run.ini' --workers $workers --xmx $xmx > '$out/node_run.log' 2>&1"
sleep 3
echo "sim JVMs on \$(hostname): \$(ps -eo pid,comm,args 2>/dev/null | awk '\$2==\"java\" && /simstats -config/ {n++} END{print n+0}')"
EOS
)"
  if [ "$host" = "local" ]; then
    echo "    watch: bash simstats/out/${run_name}/watch.sh"
  else
    echo "    watch: ssh $host 'bash ${out}/watch.sh'"
  fi
}

# ---------------------------------------------------------------- collect
cmd_collect() {
  local run_name="$1"
  local dest="$BASE/simstats/out/${run_name}/nodes"
  mkdir -p "$dest"
  for n in $(node_names); do
    local host; host="$(node_field "$n" 2)"
    [ "$host" = "local" ] && continue
    echo "==> collecting $n"
    mkdir -p "$dest/$n"
    rsync -a "${host}:${REMOTE_REPO}/simstats/out/${run_name}/" "$dest/$n/" 2>&1 | tail -2
    local g; g=$(cat "$dest/$n"/round_*/shard_*/games.jsonl "$dest/$n"/shard_*/games.jsonl 2>/dev/null | wc -l)
    echo "    $g games from $n"
  done
  echo
  echo "collected under simstats/out/${run_name}/nodes/   (local-node output stays at simstats/out/${run_name}/)"
}

# ---------------------------------------------------------------- offload
cmd_offload() {
  local cfg="$1" total="$2" run_name="$3"
  local names=() weights=() sum=0 i=0
  for n in $(node_names); do
    local w; w="$(node_field "$n" 5)"; [ -z "$w" ] && w=1
    names+=("$n"); weights+=("$w"); sum=$(( sum + w ))
  done
  echo "==> splitting $total games across ${#names[@]} node(s), weighted by workers (total weight $sum)"
  for n in "${names[@]}"; do
    local share=$(( total * weights[i] / sum ))
    # Seed ranges 10,000,000 apart: far wider than any run consumes, so no two nodes can ever
    # generate the same game. THE #1 multi-node correctness rule -- see MULTI_NODE.md §3.1.
    local seed=$(( 40000000 + i * 10000000 ))
    cmd_run "$n" "$cfg" "$share" "$run_name" "$seed" || echo "    (skipped $n)"
    i=$(( i + 1 ))
  done
  echo
  echo "when finished: bash tools/simstats/forge_nodes.sh collect $run_name"
}

cmd_stop() {
  local n="$1"; require_node "$n"
  echo "==> stopping sim on $n"
  run_script "$n" "$(cat <<'EOS'
tmux kill-session -t forge_run 2>/dev/null
for p in $(pgrep -f "simstats -config" 2>/dev/null); do kill -9 "$p" 2>/dev/null; done
sleep 2
echo "sim JVMs now: $(ps -eo pid,comm,args 2>/dev/null | awk '$2=="java" && /simstats -config/ {n++} END{print n+0}')"
EOS
)"
}

case "${1:-}" in
  status)  cmd_status ;;
  sync)    cmd_sync "${2:?node}" ;;
  run)     cmd_run "${2:?node}" "${3:?config}" "${4:?games}" "${5:?run_name}" "${6:-}" ;;
  collect) cmd_collect "${2:?run_name}" ;;
  offload) cmd_offload "${2:?config}" "${3:?total_games}" "${4:?run_name}" ;;
  stop)    cmd_stop "${2:?node}" ;;
  *) sed -n '2,13p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
