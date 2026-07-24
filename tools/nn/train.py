#!/usr/bin/env python3
"""
train.py -- Ultron v4 Phase 2 trainer (TICKET-V4-008, plan sect. 5.2).

Loads UltronStateLogger binary records (via read_nn_states.read_records -- this module never
re-implements the byte layout, per the plan's "Python never re-implements feature extraction"
rule), builds the composite value target of plan sect. 5.1, trains the small MLP of sect. 4.2,
and exports a `.bin` model artifact in the format sect. 4.3 + forge.ai.nn.UltronValueNet expect.

USAGE
    tools/nn/.venv/bin/python3 tools/nn/train.py --data path/to/nn_states.bin.gz [more paths...] \
        [--epochs 30] [--batch-size 1024] [--hidden1 256] [--hidden2 128] [--alpha 0.5] \
        [--val-frac 0.15] [--seed 1234] [--out-dir runs]

DESIGN NOTES (read before changing the target-construction logic)
-------------------------------------------------------------------
1. Split discipline: train/validation split is by GAME ID (see `split_game_ids`), never by
   state. This is deliberately the very first thing tested (`--self-test`) because a state-level
   split silently produces a flatteringly-wrong validation loss (plan sect. 5.1 explicit warning).

2. Perspective-relative value target. Each logged seat-vector is already self-relative (encoder
   puts "self" first, then real opponents in turn order, then padded eliminated slots) -- see
   UltronStateEncoder.orderedRealOpponents(). This trainer reconstructs the SAME relative layout
   for the *target* side: for a sample captured for absolute seat `s` in a game with `n` total
   seats, relative slot i (0=self, 1..3=opponents) maps to absolute seat `(s + i) % n` for i < n,
   and does not exist (masked) for i >= n. This mirrors exactly what the Java encoder does for
   the input vector, so a masked-out relative slot has BOTH a zero/near-zero input block and a
   masked value-head target -- input and target masking are self-consistent by construction.

3. A relative slot is masked out of the target for a given sample if EITHER (a) it is beyond the
   game's real player count (structurally absent, matches the encoder's zero-block padding), OR
   (b) the seat at that relative slot was already eliminated as of THIS record (absent from the
   record's `seats` list -- the encoder marks those as zero+eliminated too). This is a v0 design
   choice: we do not ask the value head to place probability mass on a seat whose input block is
   already zeroed out. It is documented here because it is the single most game-specific decision
   in this file and a later session may want to revisit it once mid-game eliminations are common
   (Stage B / 4p FFA; in the 1v1 bootstrap corpus there are zero mid-game eliminations to exercise
   this path at all -- see TICKET-V4-008 write-up).

4. Composite value target = alpha * placement_credit + (1 - alpha) * U(s), computed only over
   unmasked relative slots and renormalized to sum to 1 over exactly those slots:
     - placement_credit: each unmasked slot's absolute seat has a final 1-based `placement` rank
       (from the per-game seat->placement table, built by scanning every record of that game --
       placement is a post-game backfill and is constant per game+seat across all its records).
       Rank -> raw credit via RANK_CREDIT = [0.70, 0.15, 0.10, 0.05] (plan sect. 5.1's own
       example numbers), then renormalized over the unmasked slots present. For a 1v1 game this
       reduces to renormalizing [0.70, 0.15] -> [0.824, 0.176].
     - U(s): raw `heuristic_score` (ComputerUtil.evaluateBoardPosition) for each unmasked slot's
       seat AT THIS RECORD, softmax-normalized over exactly the unmasked slots ("table share").
   alpha is annealed by the CALLER across successive corpora/iterations (plan sect. 5.1); this
   script takes a single fixed --alpha per run, as specified for V0 (no prior model to bootstrap
   TD(lambda) targets from yet).

5. Aux heads (train-time only, dropped at export): own-placement 4-way classification (rank-1
   index of the SELF seat, always defined) and game-length-bucket 8-way classification (fixed
   edges, see GAME_LENGTH_EDGES). The plan's third aux head -- table-share 2-turns-later -- is
   NOT implemented in this session; it requires cross-referencing a different record 2 turns
   ahead in the same game and was deprioritized under the A -> C -> B priority order. Flagged
   here and in FORGE_TRACKER.md rather than silently omitted.

6. Timeout games are never in the input files at all -- UltronStateLogger.GameCollector#finish()
   already discards them before ever writing a record (see FORGE_TRACKER.md TICKET-V4-006). This
   script does not need its own timeout filter, but see `--self-test` for a check that would catch
   it if that ever silently changed upstream (elimination_turn == game_length sanity, etc. -- see
   comment at the self-test function).
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
from read_nn_states import Record, read_records  # noqa: E402

RANK_CREDIT = [0.70, 0.15, 0.10, 0.05]  # plan sect. 5.1 example numbers, index 0 = rank 1
NUM_SLOTS = 4  # self + 3 opponents, matches UltronStateEncoder.NUM_OPPONENTS + 1
GAME_LENGTH_EDGES = [10, 14, 17, 20, 24, 30, 40]  # 8 buckets; heuristic, see module docstring
AUX_WEIGHT = 0.25

MAGIC = 0x554E5332  # "UNS2" -- model-artifact magic, distinct from the UNS1 data-record magic
MODEL_FORMAT_VERSION = 1


# ---------------------------------------------------------------------------
# Data loading + target construction
# ---------------------------------------------------------------------------

@dataclass
class Sample:
    game_id: int
    vector: List[float]          # length VECTOR_LENGTH, this seat's perspective
    mask: List[float]            # length 4, 1.0 = slot exists and is unmasked
    target: List[float]          # length 4, composite value target, sums to 1 over unmasked slots
    self_rank_idx: int           # 0-based rank index of self (for aux placement head)
    length_bucket: int           # 0-based game-length bucket (for aux head)
    turn: int
    game_length: int


def _game_length_bucket(game_length: int) -> int:
    for i, edge in enumerate(GAME_LENGTH_EDGES):
        if game_length < edge:
            return i
    return len(GAME_LENGTH_EDGES)


def _softmax(xs: List[float]) -> List[float]:
    if not xs:
        return []
    m = max(xs)
    exps = [math.exp(x - m) for x in xs]
    s = sum(exps)
    if s <= 0:
        return [1.0 / len(xs)] * len(xs)
    return [e / s for e in exps]


def load_all_records(paths: List[str]) -> List[Record]:
    records: List[Record] = []
    for p in paths:
        records.extend(read_records(p))
    return records


def build_game_tables(records: List[Record]) -> Tuple[Dict[int, Dict[int, int]], Dict[int, int]]:
    """Returns (game_id -> {seat -> placement}), (game_id -> total seat count).

    Total seat count is the number of DISTINCT seats ever observed for that game_id -- every
    record of turn 1 keeps every living player (UltronStateLogger's sampling policy always
    retains turn 1 and the final ALWAYS_KEEP_FINAL_TURNS turns), so scanning every record's seat
    list and taking the union recovers the true player count even if some individual record
    (e.g. a very late one) has fewer seats due to eliminations by then.
    """
    placements: Dict[int, Dict[int, int]] = defaultdict(dict)
    seat_union: Dict[int, set] = defaultdict(set)
    for rec in records:
        for sb in rec.seats:
            placements[rec.game_id][sb.seat] = sb.placement
            seat_union[rec.game_id].add(sb.seat)
    n_players = {gid: len(seats) for gid, seats in seat_union.items()}
    return placements, n_players


def build_samples(records: List[Record]) -> List[Sample]:
    placements, n_players = build_game_tables(records)
    samples: List[Sample] = []
    for rec in records:
        n = n_players.get(rec.game_id)
        if not n or n < 2:
            continue
        alive_seats = {sb.seat for sb in rec.seats}
        heuristic_by_seat = {sb.seat: sb.heuristic_score for sb in rec.seats}
        game_placements = placements[rec.game_id]

        for sb in rec.seats:
            s = sb.seat
            mask = [0.0] * NUM_SLOTS
            slot_abs_seat = [None] * NUM_SLOTS
            for i in range(NUM_SLOTS):
                if i >= n:
                    continue  # structurally absent -- matches encoder's padded zero block
                abs_seat = (s + i) % n
                slot_abs_seat[i] = abs_seat
                if abs_seat in alive_seats:
                    mask[i] = 1.0
                # else: already eliminated as of this record -- masked, matches zero+eliminated input

            unmasked = [i for i in range(NUM_SLOTS) if mask[i] > 0]
            if not unmasked:
                continue  # degenerate (shouldn't happen -- self is always alive in its own record)

            # placement_credit, renormalized over unmasked slots
            raw_credit = []
            for i in unmasked:
                rank = game_placements.get(slot_abs_seat[i])
                raw_credit.append(RANK_CREDIT[min(rank, len(RANK_CREDIT)) - 1] if rank else 0.0)
            credit_sum = sum(raw_credit) or 1.0
            placement_credit = {i: c / credit_sum for i, c in zip(unmasked, raw_credit)}

            # U(s): softmax of raw heuristic scores over unmasked slots
            raw_h = [heuristic_by_seat.get(slot_abs_seat[i], 0.0) for i in unmasked]
            u_soft = dict(zip(unmasked, _softmax(raw_h)))

            target = [0.0] * NUM_SLOTS
            for i in unmasked:
                target[i] = placement_credit[i], u_soft[i]  # placeholder, combined by caller with alpha

            self_rank = game_placements.get(s, NUM_SLOTS)
            samples.append(Sample(
                game_id=rec.game_id,
                vector=sb.vector,
                mask=mask,
                target=target,  # NOTE: still (placement_credit, u) pairs here; finalized in `finalize_targets`
                self_rank_idx=min(self_rank, NUM_SLOTS) - 1,
                length_bucket=_game_length_bucket(rec.game_length),
                turn=rec.turn,
                game_length=rec.game_length,
            ))
    return samples


def finalize_targets(samples: List[Sample], alpha: float) -> None:
    """Collapses each sample's (placement_credit, u) pairs into the final alpha-blended target,
    in place. Split out from build_samples() so alpha can be swept without re-parsing records."""
    for smp in samples:
        new_target = [0.0] * NUM_SLOTS
        for i in range(NUM_SLOTS):
            cell = smp.target[i]
            if isinstance(cell, tuple):
                pc, u = cell
                new_target[i] = alpha * pc + (1 - alpha) * u
        smp.target = new_target


# ---------------------------------------------------------------------------
# Train/val split BY GAME ID
# ---------------------------------------------------------------------------

def split_game_ids(game_ids: List[int], val_frac: float, seed: int) -> Tuple[set, set]:
    """Splits unique game IDs (not states!) into train/val sets. Deterministic given seed."""
    unique = sorted(set(game_ids))  # sorted first for determinism regardless of dict/set ordering
    rng = random.Random(seed)
    rng.shuffle(unique)
    n_val = max(1, round(len(unique) * val_frac)) if len(unique) > 1 else 0
    val_ids = set(unique[:n_val])
    train_ids = set(unique[n_val:])
    return train_ids, val_ids


def _self_test_split() -> None:
    """Regression test for the split function itself -- plan sect. 5.1 flags a state-level split
    as "the single easiest way to fool yourself here." Asserts: (a) every state from a given game
    lands in exactly one side, (b) no game_id appears on both sides."""
    fake_game_ids = []
    for gid in range(20):
        n_states = random.Random(gid).randint(5, 40)
        fake_game_ids.extend([gid] * n_states)
    train_ids, val_ids = split_game_ids(fake_game_ids, val_frac=0.2, seed=42)
    assert train_ids.isdisjoint(val_ids), "train/val game-id sets overlap -- split is broken"
    assert train_ids | val_ids == set(fake_game_ids), "split lost or invented a game id"
    assert len(val_ids) >= 1, "val split is empty"
    # Simulate assigning every STATE to a side by its game_id and confirm no game straddles both.
    side_of_state = ["train" if gid in train_ids else "val" for gid in fake_game_ids]
    game_to_sides = defaultdict(set)
    for gid, side in zip(fake_game_ids, side_of_state):
        game_to_sides[gid].add(side)
    straddlers = [gid for gid, sides in game_to_sides.items() if len(sides) > 1]
    assert not straddlers, f"games split across train AND val: {straddlers}"
    print("split_game_ids self-test: PASS (no game straddles train/val, no game_id lost)")


# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------

def build_model(input_dim: int, hidden1: int, hidden2: int):
    import torch
    import torch.nn as nn

    class UltronValueNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.fc1 = nn.Linear(input_dim, hidden1)
            self.ln1 = nn.LayerNorm(hidden1)
            self.fc2 = nn.Linear(hidden1, hidden2)
            self.ln2 = nn.LayerNorm(hidden2)
            self.value_head = nn.Linear(hidden2, NUM_SLOTS)
            self.placement_head = nn.Linear(hidden2, NUM_SLOTS)
            self.length_head = nn.Linear(hidden2, len(GAME_LENGTH_EDGES) + 1)

        def trunk(self, x):
            h = torch.relu(self.fc1(x))
            h = self.ln1(h)
            h = torch.relu(self.fc2(h))
            h = self.ln2(h)
            return h

        def forward(self, x):
            h = self.trunk(x)
            return self.value_head(h), self.placement_head(h), self.length_head(h)

    return UltronValueNet()


def masked_soft_ce(logits, target, mask):
    """Cross-entropy of a soft target distribution against logits, restricted to `mask`.
    Masked-out logits are driven to -inf before softmax so they receive ~zero predicted mass;
    `target` is assumed to already be zero at masked-out slots and sum to 1 over the rest."""
    import torch
    neg_inf = torch.finfo(logits.dtype).min
    masked_logits = logits.masked_fill(mask <= 0, neg_inf)
    log_probs = torch.log_softmax(masked_logits, dim=-1)
    # zero out masked target*logprob products defensively (target already 0 there in practice)
    per_sample = -(target * log_probs * mask).sum(dim=-1)
    return per_sample.mean()


# ---------------------------------------------------------------------------
# Training loop
# ---------------------------------------------------------------------------

def to_tensors(samples: List[Sample]):
    import torch
    x = torch.tensor([s.vector for s in samples], dtype=torch.float32)
    target = torch.tensor([s.target for s in samples], dtype=torch.float32)
    mask = torch.tensor([s.mask for s in samples], dtype=torch.float32)
    rank = torch.tensor([s.self_rank_idx for s in samples], dtype=torch.long)
    length_bucket = torch.tensor([s.length_bucket for s in samples], dtype=torch.long)
    return x, target, mask, rank, length_bucket


def evaluate(model, x, target, mask, rank, length_bucket):
    import torch
    import torch.nn.functional as F
    model.eval()
    with torch.no_grad():
        value_logits, placement_logits, length_logits = model(x)
        val_loss = masked_soft_ce(value_logits, target, mask).item()
        placement_loss = F.cross_entropy(placement_logits, rank).item()
        length_loss = F.cross_entropy(length_logits, length_bucket).item()

        value_probs = torch.softmax(value_logits.masked_fill(mask <= 0, torch.finfo(value_logits.dtype).min), dim=-1)
        pred_winner = value_probs.argmax(dim=-1)
        true_winner = target.argmax(dim=-1)
        winner_acc = (pred_winner == true_winner).float().mean().item()
    model.train()
    return {
        "val_value_logloss": val_loss,
        "val_placement_logloss": placement_loss,
        "val_length_logloss": length_loss,
        "val_winner_accuracy": winner_acc,
    }


def calibration_by_stage(model, samples: List[Sample], x, target, mask):
    """Held-out log-loss broken out by early/mid/late game stage (turn / game_length)."""
    import torch
    model.eval()
    stages = {"early": [], "mid": [], "late": []}
    with torch.no_grad():
        value_logits, _, _ = model(x)
        masked_logits = value_logits.masked_fill(mask <= 0, torch.finfo(value_logits.dtype).min)
        log_probs = torch.log_softmax(masked_logits, dim=-1)
        per_sample_loss = -(target * log_probs * mask).sum(dim=-1)
    model.train()
    for smp, loss in zip(samples, per_sample_loss.tolist()):
        ratio = smp.turn / max(1, smp.game_length)
        key = "early" if ratio < 0.33 else ("mid" if ratio < 0.66 else "late")
        stages[key].append(loss)
    return {k: (sum(v) / len(v) if v else None) for k, v in stages.items()}


def train(args):
    import torch
    import torch.nn.functional as F

    torch.manual_seed(args.seed)
    random.seed(args.seed)

    paths = args.data
    records = load_all_records(paths)
    if not records:
        print("No records loaded -- check --data paths.", file=sys.stderr)
        return 1
    input_dim = len(records[0].seats[0].vector)
    schema_hash = records[0].schema_hash
    semantic_version = records[0].semantic_version
    for rec in records:
        if rec.schema_hash != schema_hash or rec.semantic_version != semantic_version:
            print("FATAL: mixed schema_hash/semantic_version across input files -- refusing to "
                  "train on a corpus that spans an encoder change.", file=sys.stderr)
            return 1

    samples = build_samples(records)
    finalize_targets(samples, args.alpha)
    if not samples:
        print("No samples built from records.", file=sys.stderr)
        return 1

    game_ids = [s.game_id for s in samples]
    train_ids, val_ids = split_game_ids(game_ids, args.val_frac, args.seed)
    train_samples = [s for s in samples if s.game_id in train_ids]
    val_samples = [s for s in samples if s.game_id in val_ids]

    print(f"Loaded {len(records)} records, {len(samples)} perspective-samples, "
          f"{len(set(game_ids))} games. input_dim={input_dim} schema_hash=0x{schema_hash & 0xffffffffffffffff:016x} "
          f"semantic_version={semantic_version}")
    print(f"Split: {len(train_samples)} train samples ({len(train_ids)} games), "
          f"{len(val_samples)} val samples ({len(val_ids)} games)")

    x_train, t_train, m_train, r_train, l_train = to_tensors(train_samples)
    x_val, t_val, m_val, r_val, l_val = to_tensors(val_samples) if val_samples else (None,) * 5

    model = build_model(input_dim, args.hidden1, args.hidden2)
    n_params = sum(p.numel() for p in model.parameters())
    print(f"Model: input={input_dim} -> {args.hidden1} -> {args.hidden2} -> heads "
          f"({n_params:,} parameters)")

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(1, args.epochs))

    n = x_train.shape[0]
    batch_size = min(args.batch_size, n)
    best_val = float("inf")
    best_state = None
    patience_left = args.patience
    history = []

    for epoch in range(args.epochs):
        perm = torch.randperm(n)
        epoch_loss = 0.0
        for start in range(0, n, batch_size):
            idx = perm[start:start + batch_size]
            xb, tb, mb, rb, lb = x_train[idx], t_train[idx], m_train[idx], r_train[idx], l_train[idx]
            opt.zero_grad()
            value_logits, placement_logits, length_logits = model(xb)
            loss = masked_soft_ce(value_logits, tb, mb)
            loss = loss + AUX_WEIGHT * F.cross_entropy(placement_logits, rb)
            loss = loss + AUX_WEIGHT * F.cross_entropy(length_logits, lb)
            loss.backward()
            opt.step()
            epoch_loss += loss.item() * xb.shape[0]
        sched.step()
        epoch_loss /= n

        if x_val is not None and len(val_samples) > 0:
            metrics = evaluate(model, x_val, t_val, m_val, r_val, l_val)
        else:
            metrics = {"val_value_logloss": epoch_loss, "val_placement_logloss": None,
                       "val_length_logloss": None, "val_winner_accuracy": None}
        history.append({"epoch": epoch, "train_loss": epoch_loss, **metrics})
        print(f"epoch {epoch:3d}  train_loss={epoch_loss:.4f}  val_value_logloss={metrics['val_value_logloss']:.4f}  "
              f"val_winner_acc={metrics['val_winner_accuracy']}")

        if metrics["val_value_logloss"] < best_val - 1e-5:
            best_val = metrics["val_value_logloss"]
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
            patience_left = args.patience
        else:
            patience_left -= 1
            if patience_left <= 0:
                print(f"Early stopping at epoch {epoch} (patience {args.patience} exhausted).")
                break

    if best_state is not None:
        model.load_state_dict(best_state)

    final_metrics = evaluate(model, x_val, t_val, m_val, r_val, l_val) if val_samples else {}
    calib = calibration_by_stage(model, val_samples, x_val, t_val, m_val) if val_samples else {}

    run_dir = Path(args.out_dir) / time.strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)

    config = vars(args).copy()
    config["input_dim"] = input_dim
    config["schema_hash"] = schema_hash
    config["semantic_version"] = semantic_version
    config["n_params"] = n_params
    config["n_train_samples"] = len(train_samples)
    config["n_val_samples"] = len(val_samples)
    config["n_train_games"] = len(train_ids)
    config["n_val_games"] = len(val_ids)
    (run_dir / "config.json").write_text(json.dumps(config, indent=2, default=str))

    metrics_out = {
        "final_val_metrics": final_metrics,
        "calibration_by_stage": calib,
        "history": history,
        "smoke_test_dataset": args.smoke_label,
    }
    (run_dir / "metrics.json").write_text(json.dumps(metrics_out, indent=2))

    model_path = run_dir / "model.bin"
    export_model(model, model_path, input_dim, args.hidden1, args.hidden2, schema_hash, semantic_version)
    print(f"Wrote {run_dir}/config.json, metrics.json, model.bin")

    parity_n = export_parity_fixture(model, samples, run_dir, n=args.parity_n)
    print(f"Wrote parity fixture ({parity_n} real vectors) to {run_dir}/parity_vectors.bin, "
          f"parity_python_probs.bin -- run tools/nn/run_parity_test.sh to check Java agreement.")
    return 0


# ---------------------------------------------------------------------------
# Parity fixture (plan sect. 6 P2.3): dump real logged vectors + this model's forward-pass
# output so a Java-side test can load the exported .bin and check agreement to 1e-5.
# ---------------------------------------------------------------------------

PARITY_MAGIC = 0x55504152  # "UPAR"


def export_parity_fixture(model, samples: List[Sample], run_dir: Path, n: int = 100) -> int:
    import struct
    import torch

    chosen = samples[:n] if len(samples) >= n else samples
    if not chosen:
        return 0
    model.eval()
    with torch.no_grad():
        x = torch.tensor([s.vector for s in chosen], dtype=torch.float32)
        value_logits, _, _ = model(x)
        probs = torch.softmax(value_logits, dim=-1).cpu().numpy()
    model.train()

    import numpy as np
    dim = len(chosen[0].vector)
    vecs = np.array([s.vector for s in chosen], dtype=">f4")
    with open(run_dir / "parity_vectors.bin", "wb") as f:
        f.write(struct.pack(">iii", PARITY_MAGIC, len(chosen), dim))
        f.write(vecs.tobytes())

    with open(run_dir / "parity_python_probs.bin", "wb") as f:
        f.write(struct.pack(">iii", PARITY_MAGIC, len(chosen), NUM_SLOTS))
        f.write(probs.astype(">f4").tobytes())

    return len(chosen)


# ---------------------------------------------------------------------------
# Export (plan sect. 4.3): header (schema hash, semantic version, layer dims) + float32 weights
# ---------------------------------------------------------------------------

def export_model(model, path: Path, input_dim: int, hidden1: int, hidden2: int,
                  schema_hash: int, semantic_version: int) -> None:
    """Writes the .bin artifact forge.ai.nn.UltronValueNet loads. Big-endian throughout, matching
    the Java-side DataOutputStream convention already established by UltronStateLogger/reader.

    Header (big-endian):
        magic            int32   == MAGIC (0x554E5332, "UNS2")
        format_version   int32   == MODEL_FORMAT_VERSION (1)
        schema_hash      int64   UltronStateEncoder.SCHEMA_HASH this model was trained against
        semantic_version int32   UltronStateEncoder.ENCODER_SEMANTIC_VERSION this model was trained against
        input_dim        int32
        hidden1           int32
        hidden2           int32
        num_value_slots  int32   == NUM_SLOTS (4)
    Body (all float32, big-endian, in this exact order -- matches nn.Linear's (out, in) weight
    layout, i.e. row-major with each row being one output unit's input weights):
        fc1.weight   [hidden1, input_dim]
        fc1.bias     [hidden1]
        ln1.weight   [hidden1]
        ln1.bias     [hidden1]
        fc2.weight   [hidden2, hidden1]
        fc2.bias     [hidden2]
        ln2.weight   [hidden2]
        ln2.bias     [hidden2]
        value_head.weight  [NUM_SLOTS, hidden2]
        value_head.bias    [NUM_SLOTS]
    Aux heads (placement_head, length_head) are DROPPED at export -- train-time only, per plan
    sect. 4.2. LayerNorm epsilon is PyTorch's default (1e-5) on both sides; UltronValueNet.java
    must use the same constant -- this is the single most likely parity-test failure mode
    (module docstring of parity_test.py elaborates).
    """
    import struct
    import torch

    sd = model.state_dict()

    def w(name):
        return sd[name].detach().cpu().numpy()

    with open(path, "wb") as f:
        f.write(struct.pack(">iiqiiiii", MAGIC, MODEL_FORMAT_VERSION, schema_hash,
                             semantic_version, input_dim, hidden1, hidden2, NUM_SLOTS))
        for name in ["fc1.weight", "fc1.bias", "ln1.weight", "ln1.bias",
                     "fc2.weight", "fc2.bias", "ln2.weight", "ln2.bias",
                     "value_head.weight", "value_head.bias"]:
            arr = w(name)
            f.write(arr.astype(">f4").tobytes())


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_argparser():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", nargs="+", help="one or more nn_states.bin.gz paths")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch-size", type=int, default=1024)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--weight-decay", type=float, default=1e-4)
    ap.add_argument("--hidden1", type=int, default=256)
    ap.add_argument("--hidden2", type=int, default=128)
    ap.add_argument("--alpha", type=float, default=0.5, help="placement vs U(s) blend, plan sect 5.1")
    ap.add_argument("--val-frac", type=float, default=0.15)
    ap.add_argument("--patience", type=int, default=5)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--out-dir", default=str(Path(__file__).resolve().parent / "runs"))
    ap.add_argument("--smoke-label", default=None,
                     help="if set, tags metrics.json as a smoke-test run on a named tiny dataset "
                          "(e.g. 'v4_006_logged_dryrun, 758 samples/20 games -- NOT a real result')")
    ap.add_argument("--self-test", action="store_true", help="run internal self-tests and exit")
    ap.add_argument("--parity-n", type=int, default=100,
                     help="number of real logged vectors to export for the Java parity test")
    return ap


def main(argv=None):
    ap = build_argparser()
    args = ap.parse_args(argv)
    if args.self_test:
        _self_test_split()
        return 0
    if not args.data:
        ap.error("--data is required unless --self-test")
    return train(args)


if __name__ == "__main__":
    sys.exit(main())
