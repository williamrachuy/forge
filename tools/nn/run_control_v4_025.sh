#!/usr/bin/env bash
# TICKET-V4-025: control for the round-1 expert-iteration claim.
#
# V2 = V0 fine-tuned on 20,027 ON-POLICY records, and it beat V0 by +9.2pp. But V2 also simply saw
# 20K MORE records than V0, so the round-1 conclusion has an unrun control:
#
#   was the gain the DISTRIBUTION of the new data, or merely its VOLUME?
#
# This fine-tunes V0 on a SIZE-MATCHED 20K slice of the original all-Default corpus, with byte
# identical hyperparameters, then scores it on the SAME on-policy validation split V0 and V2 were
# scored on. Reference points on that split:
#
#   V0 (no fine-tune)            0.5322
#   V2 (on-policy fine-tune)     0.5209
#
# Read the result as:
#   control ~= 0.5322  -> extra all-Default data does NOT help on-policy states; the round-1 gain
#                         was distributional. Expert iteration validated.
#   control ~= 0.5209  -> plain volume explains it; round 1 proves much less than claimed and the
#                         whole iteration programme needs rethinking.
#
# Cheap by design: minutes of CPU, no new games, uses corpora already on disk.
set -uo pipefail
BASE=/home/william/github/forge
PY="$BASE/tools/nn/.venv/bin/python"
V0="$BASE/tools/nn/runs/20260724-195756/model.bin"
ALLDEFAULT=("$BASE"/simstats/out/v4_007_bootstrap_corpus/shard_*/nn_states.bin.gz)
ONPOLICY=("$BASE"/simstats/out/v4_020_v2_onpolicy_corpus/round_*/shard_*/nn_states.bin.gz)
OUT="$BASE/simstats/out/v4_025_control"
mkdir -p "$OUT"

echo "=== V4-025 control started $(date) ===" | tee "$OUT/control.log"

# Step 1: fine-tune V0 on 20K all-Default records -- same hyperparameters as V2's fine-tune.
echo "--- step 1: fine-tune V0 on 20K all-Default records" | tee -a "$OUT/control.log"
"$PY" "$BASE/tools/nn/train.py" \
  --data "${ALLDEFAULT[@]}" --max-records 20000 \
  --init-from "$V0" --aux-weight 0.0 --lr 1e-4 --epochs 15 --patience 3 \
  --batch-size 1024 --weight-decay 1e-4 --hidden1 256 --hidden2 128 \
  --alpha 0.5 --val-frac 0.15 --seed 1234 --smoke-label v4_025_control \
  >> "$OUT/control.log" 2>&1
echo "    exit=$?" | tee -a "$OUT/control.log"

CTRL="$(ls -td "$BASE"/tools/nn/runs/*/ | head -1)model.bin"
echo "--- control model: $CTRL" | tee -a "$OUT/control.log"

# Step 2: score it on the SAME on-policy val split V0 and V2 were scored on.
echo "--- step 2: score control on the on-policy val split" | tee -a "$OUT/control.log"
"$PY" "$BASE/tools/nn/train.py" --data "${ONPOLICY[@]}" \
  --init-from "$CTRL" --eval-only 2>&1 | tee -a "$OUT/control.log" | grep -E "eval-only|val_value_logloss"

echo "=== V4-025 control done $(date) ===" | tee -a "$OUT/control.log"
echo "REFERENCE  V0=0.5322  V2=0.5209  <- compare the control number above" | tee -a "$OUT/control.log"
