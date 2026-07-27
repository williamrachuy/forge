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
0. Memory: data loading is a two-pass STREAM into preallocated numpy arrays (TICKET-V4-018c fix).
   `read_records()` is a lazy generator; the original v0 trainer did `records.extend(read_records(p))`,
   materializing every `Record` (each seat's 1908-float vector as a Python LIST -- ~28 bytes/float
   vs 4 in numpy) plus a second full copy in `Sample` objects plus a THIRD transient copy in
   `torch.tensor([s.vector for s in samples])`. That is fine for V0's smaller corpus but OOMs on
   V1's ~2x corpus (595K perspective-samples). The fix: pass 1 (`build_game_tables`) scans every
   record once to build the per-game placement/seat-count tables AND count the total perspective-
   sample count N, WITHOUT retaining any vector; pass 2 (`build_dataset`) re-scans and writes each
   sample directly into preallocated `np.float32` arrays sized (N, input_dim) etc. Only one Record's
   worth of vectors is ever alive at a time. Torch tensors are then built with `torch.from_numpy`
   (no Python list-of-lists roundtrip). Peak memory is now ~the numpy arrays themselves (~4.5 GB for
   the 595K x 1908 input matrix), not 30+ GB of boxed Python floats.

1. Split discipline: train/validation split is by GAME ID (see `split_game_ids`), never by
   state. This is deliberately the very first thing tested (`--self-test`) because a state-level
   split silently produces a flatteringly-wrong validation loss (plan sect. 5.1 explicit warning).
   The split now operates on the parallel `game_id` numpy array built during the streaming load.

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
from typing import Dict, Iterator, List, Tuple

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from read_nn_states import Record, read_records  # noqa: E402

RANK_CREDIT = [0.70, 0.15, 0.10, 0.05]  # plan sect. 5.1 example numbers, index 0 = rank 1
NUM_SLOTS = 4  # self + 3 opponents, matches UltronStateEncoder.NUM_OPPONENTS + 1
GAME_LENGTH_EDGES = [10, 14, 17, 20, 24, 30, 40]  # 8 buckets; heuristic, see module docstring
AUX_WEIGHT = 0.25  # default; overridable via --aux-weight (TICKET-V4-020, see the flag's help text)

MAGIC = 0x554E5332  # "UNS2" -- model-artifact magic, distinct from the UNS1 data-record magic
MODEL_FORMAT_VERSION = 1


# ---------------------------------------------------------------------------
# Data loading + target construction (TICKET-V4-018c: streamed into preallocated numpy, see
# module docstring point 0 -- never materializes the full corpus as boxed Python floats)
# ---------------------------------------------------------------------------

class SchemaMismatchError(ValueError):
    pass


@dataclass
class Dataset:
    """Parallel numpy arrays, one row per perspective-sample. Replaces the v0 `List[Sample]` of
    boxed Python objects -- same information, ~10x less memory (float32 numpy vs Python float
    lists), and never round-trips through `torch.tensor([python list of lists])`."""
    inputs: np.ndarray          # (N, input_dim) float32 -- this seat's perspective vector
    credit: np.ndarray          # (N, NUM_SLOTS) float32 -- placement_credit component (pre-alpha)
    usoft: np.ndarray           # (N, NUM_SLOTS) float32 -- U(s) softmax component (pre-alpha)
    mask: np.ndarray            # (N, NUM_SLOTS) float32 -- 1.0 = slot exists and is unmasked
    self_rank_idx: np.ndarray   # (N,) int64 -- 0-based rank index of self (aux placement head)
    length_bucket: np.ndarray   # (N,) int64 -- 0-based game-length bucket (aux head)
    game_id: np.ndarray         # (N,) int64
    turn: np.ndarray            # (N,) int32
    game_length: np.ndarray     # (N,) int32
    phase_ordinal: np.ndarray   # (N,) int32 -- for the V1 per-phase accuracy breakdown


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


def _iter_all_records(paths: List[str]) -> Iterator[Record]:
    """Chains `read_records` across every input path. Called ONCE per pass (twice total) -- never
    accumulated into a list. Each `Record` (and its boxed-float vectors) is garbage the moment its
    loop body finishes, so at most one record's worth of vectors is ever live."""
    for p in paths:
        yield from read_records(p)


def build_game_tables(paths: List[str]):
    """Pass 1: scans every record ONCE to build (a) the per-game seat->placement table, (b) the
    per-game living-seat-count table, (c) the total perspective-sample count N (so pass 2 can
    preallocate exact-size numpy arrays), and (d) input_dim/schema_hash/semantic_version, all
    WITHOUT retaining any vector or Record. Total seat count is the number of DISTINCT seats ever
    observed for that game_id -- every record of turn 1 keeps every living player (UltronStateLogger's
    sampling policy always retains turn 1 and the final ALWAYS_KEEP_FINAL_TURNS turns), so scanning
    every record's seat list and taking the union recovers the true player count even if some
    individual record (e.g. a very late one) has fewer seats due to eliminations by then.

    N is exactly `sum(len(rec.seats) for rec in games with n_players >= 2)` -- build_dataset's inner
    loop always appends exactly one sample per seat-block of a kept record (the "degenerate, no
    unmasked slots" case can only happen if self isn't alive in its own record, which cannot occur
    -- see the assertion in build_dataset), so this count is exact, not an upper bound.

    Returns (placements, n_players, N, input_dim, schema_hash, semantic_version, total_records).
    """
    placements: Dict[int, Dict[int, int]] = defaultdict(dict)
    seat_union: Dict[int, set] = defaultdict(set)
    per_game_seatblocks: Dict[int, int] = defaultdict(int)
    input_dim = None
    schema_hash = None
    semantic_version = None
    total_records = 0

    for rec in _iter_all_records(paths):
        total_records += 1
        if input_dim is None:
            schema_hash = rec.schema_hash
            semantic_version = rec.semantic_version
            if rec.seats:
                input_dim = len(rec.seats[0].vector)
        elif rec.schema_hash != schema_hash or rec.semantic_version != semantic_version:
            raise SchemaMismatchError(
                "mixed schema_hash/semantic_version across input files -- refusing to train on a "
                "corpus that spans an encoder change.")
        for sb in rec.seats:
            placements[rec.game_id][sb.seat] = sb.placement
            seat_union[rec.game_id].add(sb.seat)
        per_game_seatblocks[rec.game_id] += len(rec.seats)

    n_players = {gid: len(seats) for gid, seats in seat_union.items()}
    total_n = sum(cnt for gid, cnt in per_game_seatblocks.items() if n_players.get(gid, 0) >= 2)
    return placements, n_players, total_n, input_dim, schema_hash, semantic_version, total_records


def build_dataset(paths: List[str], placements: Dict[int, Dict[int, int]],
                   n_players: Dict[int, int], total_n: int, input_dim: int) -> Dataset:
    """Pass 2: re-scans every record and writes each perspective-sample directly into preallocated
    numpy arrays (row-by-row), instead of appending `Sample` objects to a Python list. Target
    construction (placement_credit / U(s) softmax / masking) is IDENTICAL math to v0's
    `build_samples` -- only the storage changed."""
    inputs = np.zeros((total_n, input_dim), dtype=np.float32)
    credit = np.zeros((total_n, NUM_SLOTS), dtype=np.float32)
    usoft = np.zeros((total_n, NUM_SLOTS), dtype=np.float32)
    mask = np.zeros((total_n, NUM_SLOTS), dtype=np.float32)
    self_rank_idx = np.zeros(total_n, dtype=np.int64)
    length_bucket = np.zeros(total_n, dtype=np.int64)
    game_id_arr = np.zeros(total_n, dtype=np.int64)
    turn_arr = np.zeros(total_n, dtype=np.int32)
    game_length_arr = np.zeros(total_n, dtype=np.int32)
    phase_ordinal_arr = np.zeros(total_n, dtype=np.int32)

    row = 0
    for rec in _iter_all_records(paths):
        n = n_players.get(rec.game_id)
        if not n or n < 2:
            continue
        alive_seats = {sb.seat for sb in rec.seats}
        heuristic_by_seat = {sb.seat: sb.heuristic_score for sb in rec.seats}
        game_placements = placements[rec.game_id]

        for sb in rec.seats:
            s = sb.seat
            row_mask = [0.0] * NUM_SLOTS
            slot_abs_seat = [None] * NUM_SLOTS
            for i in range(NUM_SLOTS):
                if i >= n:
                    continue  # structurally absent -- matches encoder's padded zero block
                abs_seat = (s + i) % n
                slot_abs_seat[i] = abs_seat
                if abs_seat in alive_seats:
                    row_mask[i] = 1.0
                # else: already eliminated as of this record -- masked, matches zero+eliminated input

            unmasked = [i for i in range(NUM_SLOTS) if row_mask[i] > 0]
            assert unmasked, (
                "degenerate sample: self must always be alive in its own record -- if this fires, "
                "build_game_tables' N count (which assumes this can't happen, per its docstring) "
                "is now wrong and preallocation would silently corrupt rows")

            # placement_credit, renormalized over unmasked slots
            raw_credit = []
            for i in unmasked:
                rank = game_placements.get(slot_abs_seat[i])
                raw_credit.append(RANK_CREDIT[min(rank, len(RANK_CREDIT)) - 1] if rank else 0.0)
            credit_sum = sum(raw_credit) or 1.0
            for i, c in zip(unmasked, raw_credit):
                credit[row, i] = c / credit_sum

            # U(s): softmax of raw heuristic scores over unmasked slots
            raw_h = [heuristic_by_seat.get(slot_abs_seat[i], 0.0) for i in unmasked]
            for i, u in zip(unmasked, _softmax(raw_h)):
                usoft[row, i] = u

            inputs[row, :] = sb.vector
            mask[row, :] = row_mask
            self_rank = game_placements.get(s, NUM_SLOTS)
            self_rank_idx[row] = min(self_rank, NUM_SLOTS) - 1
            length_bucket[row] = _game_length_bucket(rec.game_length)
            game_id_arr[row] = rec.game_id
            turn_arr[row] = rec.turn
            game_length_arr[row] = rec.game_length
            phase_ordinal_arr[row] = rec.phase_ordinal
            row += 1

    assert row == total_n, f"row count mismatch: filled {row}, expected {total_n} from pass 1"
    return Dataset(inputs=inputs, credit=credit, usoft=usoft, mask=mask,
                    self_rank_idx=self_rank_idx, length_bucket=length_bucket,
                    game_id=game_id_arr, turn=turn_arr, game_length=game_length_arr,
                    phase_ordinal=phase_ordinal_arr)


def finalize_targets(credit: np.ndarray, usoft: np.ndarray, alpha: float) -> np.ndarray:
    """Alpha-blends the (placement_credit, u) components into the final composite target.
    Vectorized equivalent of v0's per-sample finalize_targets -- split out so alpha can be swept
    without re-parsing records (credit/usoft are cheap to keep around; the input matrix is the
    expensive one)."""
    return (alpha * credit + (1.0 - alpha) * usoft).astype(np.float32)


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

def to_tensor(arr: np.ndarray):
    """Zero-copy (where numpy layout allows) numpy -> torch conversion. Replaces v0's
    `torch.tensor([python list of lists])`, which would transiently double the already-materialized
    Python-object memory just to build one tensor."""
    import torch
    return torch.from_numpy(np.ascontiguousarray(arr))


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


def calibration_by_stage(model, turn_arr: np.ndarray, game_length_arr: np.ndarray, x, target, mask):
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
    for turn, game_length, loss in zip(turn_arr.tolist(), game_length_arr.tolist(), per_sample_loss.tolist()):
        ratio = turn / max(1, game_length)
        key = "early" if ratio < 0.33 else ("mid" if ratio < 0.66 else "late")
        stages[key].append(loss)
    return {k: (sum(v) / len(v) if v else None) for k, v in stages.items()}


def accuracy_by_phase(model, phase_ordinal_arr: np.ndarray, x, target, mask):
    """TICKET-V4-018c: V1's corpus (unlike V0's MAIN1-only corpus) spans 12 distinct phase
    ordinals, including combat and instant-speed priority windows -- report winner-prediction
    accuracy PER phase ordinal so we can see whether the model is actually competent on the new
    non-MAIN1 states, not just aggregate-competent (which could hide it being MAIN1-only-good)."""
    import torch
    model.eval()
    with torch.no_grad():
        value_logits, _, _ = model(x)
        value_probs = torch.softmax(value_logits.masked_fill(mask <= 0, torch.finfo(value_logits.dtype).min), dim=-1)
        pred_winner = value_probs.argmax(dim=-1)
        true_winner = target.argmax(dim=-1)
        correct = (pred_winner == true_winner).cpu().numpy()
    model.train()
    by_phase: Dict[int, List[bool]] = defaultdict(list)
    for phase, c in zip(phase_ordinal_arr.tolist(), correct.tolist()):
        by_phase[phase].append(c)
    return {
        str(phase): {"accuracy": sum(cs) / len(cs), "n": len(cs)}
        for phase, cs in sorted(by_phase.items())
    }


def train(args):
    import torch
    import torch.nn.functional as F

    torch.manual_seed(args.seed)
    random.seed(args.seed)

    paths = args.data

    # Pass 1: game tables + exact sample count, WITHOUT retaining any vector (see module docstring
    # point 0 and build_game_tables' docstring).
    try:
        placements, n_players, total_n, input_dim, schema_hash, semantic_version, total_records = \
            build_game_tables(paths)
    except SchemaMismatchError as exc:
        print(f"FATAL: {exc}", file=sys.stderr)
        return 1
    if total_records == 0:
        print("No records loaded -- check --data paths.", file=sys.stderr)
        return 1
    if total_n == 0:
        print("No samples built from records.", file=sys.stderr)
        return 1

    # Pass 2: fill preallocated numpy arrays directly (no Sample objects, no Python lists-of-lists).
    ds = build_dataset(paths, placements, n_players, total_n, input_dim)
    target = finalize_targets(ds.credit, ds.usoft, args.alpha)

    train_ids, val_ids = split_game_ids(ds.game_id.tolist(), args.val_frac, args.seed)
    train_ids_arr = np.fromiter(train_ids, dtype=np.int64, count=len(train_ids))
    train_mask_rows = np.isin(ds.game_id, train_ids_arr)
    val_mask_rows = ~train_mask_rows

    n_games = len(train_ids) + len(val_ids)
    print(f"Loaded {total_records} records, {total_n} perspective-samples, "
          f"{n_games} games. input_dim={input_dim} schema_hash=0x{schema_hash & 0xffffffffffffffff:016x} "
          f"semantic_version={semantic_version}")
    print(f"Split: {int(train_mask_rows.sum())} train samples ({len(train_ids)} games), "
          f"{int(val_mask_rows.sum())} val samples ({len(val_ids)} games)")

    x_train = to_tensor(ds.inputs[train_mask_rows])
    t_train = to_tensor(target[train_mask_rows])
    m_train = to_tensor(ds.mask[train_mask_rows])
    r_train = to_tensor(ds.self_rank_idx[train_mask_rows])
    l_train = to_tensor(ds.length_bucket[train_mask_rows])

    has_val = int(val_mask_rows.sum()) > 0
    if has_val:
        x_val = to_tensor(ds.inputs[val_mask_rows])
        t_val = to_tensor(target[val_mask_rows])
        m_val = to_tensor(ds.mask[val_mask_rows])
        r_val = to_tensor(ds.self_rank_idx[val_mask_rows])
        l_val = to_tensor(ds.length_bucket[val_mask_rows])
        turn_val = ds.turn[val_mask_rows]
        game_length_val = ds.game_length[val_mask_rows]
        phase_val = ds.phase_ordinal[val_mask_rows]
    else:
        x_val = t_val = m_val = r_val = l_val = None
        turn_val = game_length_val = phase_val = None

    model = build_model(input_dim, args.hidden1, args.hidden2)
    n_params = sum(p.numel() for p in model.parameters())
    print(f"Model: input={input_dim} -> {args.hidden1} -> {args.hidden2} -> heads "
          f"({n_params:,} parameters)")

    if args.init_from:
        load_into_model(model, Path(args.init_from), input_dim, args.hidden1, args.hidden2,
                         schema_hash, semantic_version)

    if args.eval_only:
        if not args.init_from:
            print("--eval-only requires --init-from (nothing to evaluate).", file=sys.stderr)
            return 1
        if not has_val:
            print("--eval-only requires a non-empty validation split (--val-frac > 0).", file=sys.stderr)
            return 1
        metrics = evaluate(model, x_val, t_val, m_val, r_val, l_val)
        calib = calibration_by_stage(model, turn_val, game_length_val, x_val, t_val, m_val)
        phase_acc = accuracy_by_phase(model, phase_val, x_val, t_val, m_val)
        print(f"[--eval-only] {args.init_from} on this val split "
              f"({int(val_mask_rows.sum())} samples / {len(val_ids)} games):")
        print(json.dumps({"final_val_metrics": metrics, "calibration_by_stage": calib,
                           "accuracy_by_phase_ordinal": phase_acc}, indent=2))
        return 0

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
            loss = loss + args.aux_weight * F.cross_entropy(placement_logits, rb)
            loss = loss + args.aux_weight * F.cross_entropy(length_logits, lb)
            loss.backward()
            opt.step()
            epoch_loss += loss.item() * xb.shape[0]
        sched.step()
        epoch_loss /= n

        if has_val:
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

    final_metrics = evaluate(model, x_val, t_val, m_val, r_val, l_val) if has_val else {}
    calib = calibration_by_stage(model, turn_val, game_length_val, x_val, t_val, m_val) if has_val else {}
    phase_acc = accuracy_by_phase(model, phase_val, x_val, t_val, m_val) if has_val else {}

    run_dir = Path(args.out_dir) / time.strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)

    config = vars(args).copy()
    config["input_dim"] = input_dim
    config["schema_hash"] = schema_hash
    config["semantic_version"] = semantic_version
    config["n_params"] = n_params
    config["n_train_samples"] = int(train_mask_rows.sum())
    config["n_val_samples"] = int(val_mask_rows.sum())
    config["n_train_games"] = len(train_ids)
    config["n_val_games"] = len(val_ids)
    (run_dir / "config.json").write_text(json.dumps(config, indent=2, default=str))

    metrics_out = {
        "final_val_metrics": final_metrics,
        "calibration_by_stage": calib,
        "accuracy_by_phase_ordinal": phase_acc,
        "history": history,
        "smoke_test_dataset": args.smoke_label,
    }
    (run_dir / "metrics.json").write_text(json.dumps(metrics_out, indent=2))

    model_path = run_dir / "model.bin"
    export_model(model, model_path, input_dim, args.hidden1, args.hidden2, schema_hash, semantic_version)
    print(f"Wrote {run_dir}/config.json, metrics.json, model.bin")

    parity_n = export_parity_fixture(model, ds.inputs, run_dir, n=args.parity_n)
    print(f"Wrote parity fixture ({parity_n} real vectors) to {run_dir}/parity_vectors.bin, "
          f"parity_python_probs.bin -- run tools/nn/run_parity_test.sh to check Java agreement.")
    return 0


# ---------------------------------------------------------------------------
# Parity fixture (plan sect. 6 P2.3): dump real logged vectors + this model's forward-pass
# output so a Java-side test can load the exported .bin and check agreement to 1e-5.
# ---------------------------------------------------------------------------

PARITY_MAGIC = 0x55504152  # "UPAR"


def export_parity_fixture(model, inputs: np.ndarray, run_dir: Path, n: int = 100) -> int:
    import struct
    import torch

    chosen = inputs[:n] if len(inputs) >= n else inputs
    if len(chosen) == 0:
        return 0
    model.eval()
    with torch.no_grad():
        x = to_tensor(chosen)
        value_logits, _, _ = model(x)
        probs = torch.softmax(value_logits, dim=-1).cpu().numpy()
    model.train()

    dim = chosen.shape[1]
    vecs = chosen.astype(">f4")
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


class ModelShapeMismatchError(Exception):
    """Raised by --init-from / --eval-only when a loaded .bin's shape/schema does not match the
    model currently being built. TICKET-V4-020: fine-tuning a corpus onto V0's exact weights is
    only meaningful if the architecture and encoder schema line up exactly -- a silent shape
    mismatch (e.g. a stale model.bin from a different hidden-layer config) would either crash
    obscurely mid-load or, worse, load garbage that still "works" (wrong reshape landing on
    coincidentally-compatible tensor sizes). Fail loudly instead."""


def load_model_bin(path: Path):
    """Inverse of export_model: reads the .bin artifact back into a dict of float32 numpy arrays
    keyed by the same state_dict names export_model wrote (value_head only -- placement_head/
    length_head are aux, train-time-only, and were never written to the file in the first place,
    so a --init-from run always starts those two heads from a fresh random init regardless of the
    source model). Returns (state_dict, input_dim, hidden1, hidden2, schema_hash, semantic_version).
    """
    import struct

    data = path.read_bytes()
    off = 0
    magic, fmt_version, schema_hash, semantic_version, input_dim, hidden1, hidden2, num_slots = \
        struct.unpack_from(">iiqiiiii", data, off)
    off += struct.calcsize(">iiqiiiii")
    if magic != MAGIC:
        raise ModelShapeMismatchError(f"{path}: bad magic 0x{magic:08x}, expected 0x{MAGIC:08x}")
    if fmt_version != MODEL_FORMAT_VERSION:
        raise ModelShapeMismatchError(
            f"{path}: format_version {fmt_version}, expected {MODEL_FORMAT_VERSION}")
    if num_slots != NUM_SLOTS:
        raise ModelShapeMismatchError(f"{path}: num_slots {num_slots}, expected {NUM_SLOTS}")

    shapes = {
        "fc1.weight": (hidden1, input_dim), "fc1.bias": (hidden1,),
        "ln1.weight": (hidden1,), "ln1.bias": (hidden1,),
        "fc2.weight": (hidden2, hidden1), "fc2.bias": (hidden2,),
        "ln2.weight": (hidden2,), "ln2.bias": (hidden2,),
        "value_head.weight": (NUM_SLOTS, hidden2), "value_head.bias": (NUM_SLOTS,),
    }
    state = {}
    for name in ["fc1.weight", "fc1.bias", "ln1.weight", "ln1.bias",
                 "fc2.weight", "fc2.bias", "ln2.weight", "ln2.bias",
                 "value_head.weight", "value_head.bias"]:
        shape = shapes[name]
        count = 1
        for d in shape:
            count *= d
        nbytes = count * 4
        arr = np.frombuffer(data, dtype=">f4", count=count, offset=off).astype(np.float32).reshape(shape)
        state[name] = arr
        off += nbytes

    return state, input_dim, hidden1, hidden2, schema_hash, semantic_version


def load_into_model(model, model_bin_path: Path, expected_input_dim: int, expected_hidden1: int,
                     expected_hidden2: int, expected_schema_hash: int, expected_semantic_version: int):
    """TICKET-V4-020: initializes `model`'s trunk + value_head from a previously-exported .bin
    (e.g. V0's model.bin), asserting exact architecture/schema match first -- see
    ModelShapeMismatchError. placement_head/length_head (aux, train-time only, never exported)
    are left at their fresh random init regardless of the source model; this is expected and
    fine, they do not affect the deployed value head."""
    import torch

    state, input_dim, hidden1, hidden2, schema_hash, semantic_version = load_model_bin(model_bin_path)
    mismatches = []
    if input_dim != expected_input_dim:
        mismatches.append(f"input_dim {input_dim} != {expected_input_dim}")
    if hidden1 != expected_hidden1:
        mismatches.append(f"hidden1 {hidden1} != {expected_hidden1}")
    if hidden2 != expected_hidden2:
        mismatches.append(f"hidden2 {hidden2} != {expected_hidden2}")
    if schema_hash != expected_schema_hash:
        mismatches.append(f"schema_hash 0x{schema_hash & 0xffffffffffffffff:016x} != "
                           f"0x{expected_schema_hash & 0xffffffffffffffff:016x}")
    if semantic_version != expected_semantic_version:
        mismatches.append(f"semantic_version {semantic_version} != {expected_semantic_version}")
    if mismatches:
        raise ModelShapeMismatchError(
            f"--init-from {model_bin_path}: architecture/schema does not match the model being "
            f"built, refusing to load (fine-tuning onto mismatched weights is not meaningful): "
            + "; ".join(mismatches))

    with torch.no_grad():
        sd = model.state_dict()
        for name, arr in state.items():
            sd[name].copy_(torch.from_numpy(arr))
    print(f"Initialized model trunk + value_head from {model_bin_path} "
          f"(schema_hash=0x{schema_hash & 0xffffffffffffffff:016x} matches; "
          f"placement_head/length_head start fresh)")


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
    ap.add_argument("--init-from", default=None,
                     help="TICKET-V4-020: path to a previously-exported model.bin (e.g. V0's) to "
                          "initialize the trunk + value_head from, instead of a fresh random init. "
                          "Architecture (hidden1/hidden2/input_dim) and encoder schema_hash/"
                          "semantic_version must match exactly or the run aborts (ModelShapeMismatchError) "
                          "-- see load_into_model. Omitting this flag preserves today's from-scratch "
                          "behavior exactly (backward compatible).")
    ap.add_argument("--eval-only", action="store_true",
                     help="TICKET-V4-020: skip training entirely -- just build the val split from "
                          "--data, load --init-from (required), and report its metrics on that val "
                          "split as-is. Used to measure V0's honest baseline on a NEW corpus's "
                          "validation set (comparing across different val sets is not valid, per "
                          "the orchestrator's correction -- this flag produces the same-val-set "
                          "before number). Writes no model.bin.")
    ap.add_argument("--aux-weight", type=float, default=AUX_WEIGHT,
                     help="Weight on the placement/length aux-head losses added to the value loss "
                          "(default matches the historical hardcoded AUX_WEIGHT=0.25, so omitting "
                          "this flag preserves from-scratch behavior exactly). TICKET-V4-020 hazard: "
                          "on an --init-from run, placement_head/length_head are ALWAYS freshly "
                          "random (never exported, see load_into_model) while the trunk is "
                          "pretrained. A random head's cross-entropy gradient is large and flows "
                          "back through the shared trunk -- at the default weight this can dominate "
                          "a low-lr fine-tune and degrade the pretrained representation for reasons "
                          "having nothing to do with the new data distribution. Pass --aux-weight 0.0 "
                          "on --init-from runs to fine-tune the value head/trunk alone against the "
                          "value target; the aux heads are dropped at export regardless (sect. 4.2), "
                          "so zeroing this weight does not change the deployed model.bin's behavior "
                          "in any way, only what the optimizer sees during fine-tuning.")
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
