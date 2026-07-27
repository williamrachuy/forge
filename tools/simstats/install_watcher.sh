#!/usr/bin/env bash
# Drop a per-run watcher at the top level of a run's output directory.
#
#   bash tools/simstats/install_watcher.sh <run_dir> [target_games]
#
# Every simstats harness calls this right after it creates its output directory, so any run can be
# monitored from a separate terminal with:
#
#   bash simstats/out/<run_name>/watch.sh
#
# Standing requirement (William, 2026-07-27): every sim run ships with its own watcher, top level in
# the run directory. Do not launch a run without one.
set -uo pipefail

RUN_DIR="${1:?usage: install_watcher.sh <run_dir> [target_games]}"
TARGET="${2:-}"
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

mkdir -p "$RUN_DIR"
cat > "$RUN_DIR/watch.sh" <<EOF
#!/usr/bin/env bash
# Watcher for this run — auto-installed by tools/simstats/install_watcher.sh.
# Usage: bash $(realpath --relative-to="$BASE" "$RUN_DIR" 2>/dev/null || echo "$RUN_DIR")/watch.sh [refresh_seconds]
# Read-only: watching never affects the run.
cd "\$(dirname "\$0")"
${TARGET:+export WATCH_TARGET_GAMES=$TARGET}
exec bash "$BASE/tools/simstats/watch_run.sh" "\$(pwd)" "\${1:-15}"
EOF
chmod +x "$RUN_DIR/watch.sh"
echo "watcher installed: $RUN_DIR/watch.sh"
