#!/usr/bin/env python3
"""Reader for UltronStateLogger's binary NN training-data records.

TICKET-V4-006 (Ultron v4 Phase 1, P1.3). Mirrors the exact byte layout written by
``forge.ai.nn.UltronStateLogger.GameCollector#writeRecord``
(forge-ai/src/main/java/forge/ai/nn/UltronStateLogger.java). Java's ``DataOutputStream`` writes
all primitives big-endian (network byte order), including floats (IEEE-754 single precision via
``Float.floatToIntBits``), so every struct format string below uses ``>`` (big-endian, no padding).

File format: one or more records concatenated inside a single gzip stream (``nn_states.bin.gz``).
Each record is fully self-describing (starts with a magic number + format version), so shard files
can simply be concatenated at the *gzip-stream* level -- decompressing N concatenated gzip members
back-to-back yields the same byte stream as decompressing one combined member, which is exactly
what ``cat shard_0/nn_states.bin.gz shard_1/nn_states.bin.gz ... > merged.bin.gz`` produces.

Record layout (all fields big-endian):
    magic            int32   == MAGIC (0x554E5331, ASCII "UNS1")
    format_version   int32   == FORMAT_VERSION (1)
    schema_hash       int64   UltronStateEncoder.SCHEMA_HASH at write time
    semantic_version int32   UltronStateEncoder.ENCODER_SEMANTIC_VERSION at write time
    game_id          int64   unique per-game ID (the sim runner's per-game seed)
    turn             int32   1-based turn number this record was captured on
    phase_ordinal    int32   forge.game.phase.PhaseType ordinal (always MAIN1's ordinal today)
    acting_seat      int32   seat index of the active player at capture time
    game_length      int32   final turn count of the game this record belongs to
    num_players      int32   number of living-player blocks that follow (<= 4)
    for each of num_players:
        seat              int32    seat index (0-based, game.getPlayers() order)
        vector_len        int32    length of the feature vector (UltronStateEncoder.VECTOR_LENGTH)
        vector            float32[vector_len]   UltronStateEncoder.encode() output for this seat
        heuristic_score   float32  raw ComputerUtil.evaluateBoardPosition(null, player) for this seat
        elimination_turn  int32    turn this seat was eliminated on, or -1 if it never lost
        placement         int32    1-based finish rank (1 = best); ties share a rank

Usage:
    python3 tools/nn/read_nn_states.py <path/to/nn_states.bin.gz>          # summary
    python3 tools/nn/read_nn_states.py <path> --dump-first-vector          # print full first vector

Verified round-trip (TICKET-V4-006): forge-gui-desktop's
UltronStateLoggerTest#testWritesParseableRecordsAndRoundTripsInJava writes a real 2-player, 3-turn
fixture to $TMPDIR/ultron_nn_state_fixture/nn_states.bin.gz (left on disk deliberately, not deleted
by the test) and this script was run against that exact file to confirm the schema hash, semantic
version, game ID, turn numbers, acting seats, vector lengths, and float payloads it prints match
the values the Java test itself asserts. See FORGE_TRACKER.md TICKET-V4-006 for the recorded
output of that run.
"""
from __future__ import annotations

import argparse
import gzip
import struct
import sys
from dataclasses import dataclass, field
from typing import BinaryIO, Iterator, List

MAGIC = 0x554E5331
FORMAT_VERSION = 1

_HEADER_FMT = ">iiqiqiiii"  # magic, formatVersion, schemaHash, semanticVersion, gameId, turn, phaseOrdinal, actingSeat, gameLength
_HEADER_SIZE = struct.calcsize(_HEADER_FMT)
_NUM_PLAYERS_FMT = ">i"
_SEAT_HEADER_FMT = ">ii"  # seat, vectorLen
_SEAT_TAIL_FMT = ">fii"   # heuristicScore, eliminationTurn, placement


@dataclass
class SeatBlock:
    seat: int
    vector: List[float]
    heuristic_score: float
    elimination_turn: int
    placement: int


@dataclass
class Record:
    magic: int
    format_version: int
    schema_hash: int
    semantic_version: int
    game_id: int
    turn: int
    phase_ordinal: int
    acting_seat: int
    game_length: int
    seats: List[SeatBlock] = field(default_factory=list)


def _read_exact(f: BinaryIO, n: int) -> bytes:
    buf = f.read(n)
    if len(buf) == 0:
        raise EOFError
    if len(buf) < n:
        raise IOError(f"truncated record: wanted {n} bytes, got {len(buf)}")
    return buf


def read_records(path: str) -> Iterator[Record]:
    """Yields every :class:`Record` in the gzip-compressed binary file at ``path``."""
    with gzip.open(path, "rb") as f:
        while True:
            try:
                header = _read_exact(f, _HEADER_SIZE)
            except EOFError:
                return
            (magic, fmt_version, schema_hash, semantic_version, game_id, turn, phase_ordinal,
             acting_seat, game_length) = struct.unpack(_HEADER_FMT, header)
            if magic != MAGIC:
                raise ValueError(f"bad magic 0x{magic:08x} (expected 0x{MAGIC:08x}) -- "
                                  f"corrupt file or format mismatch")
            if fmt_version != FORMAT_VERSION:
                raise ValueError(f"unsupported format version {fmt_version} "
                                  f"(this reader supports {FORMAT_VERSION})")

            (num_players,) = struct.unpack(_NUM_PLAYERS_FMT, _read_exact(f, 4))
            seats = []
            for _ in range(num_players):
                seat, vector_len = struct.unpack(_SEAT_HEADER_FMT, _read_exact(f, 8))
                vector_bytes = _read_exact(f, 4 * vector_len)
                vector = list(struct.unpack(f">{vector_len}f", vector_bytes))
                heuristic_score, elimination_turn, placement = struct.unpack(
                    _SEAT_TAIL_FMT, _read_exact(f, 12))
                seats.append(SeatBlock(seat, vector, heuristic_score, elimination_turn, placement))

            yield Record(magic, fmt_version, schema_hash, semantic_version, game_id, turn,
                         phase_ordinal, acting_seat, game_length, seats)


def _main(argv: List[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path", help="path to a nn_states.bin.gz file (or a shard-merged concatenation of them)")
    ap.add_argument("--dump-first-vector", action="store_true",
                     help="print the full first seat-vector of the first record")
    args = ap.parse_args(argv)

    count = 0
    first: Record | None = None
    for rec in read_records(args.path):
        if first is None:
            first = rec
        count += 1

    if first is None:
        print(f"{args.path}: 0 records")
        return 0

    print(f"{args.path}: {count} record(s)")
    print(f"  schema_hash=0x{first.schema_hash & 0xffffffffffffffff:016x} "
          f"semantic_version={first.semantic_version} format_version={first.format_version}")
    print(f"  first record: game_id={first.game_id} turn={first.turn} phase_ordinal={first.phase_ordinal} "
          f"acting_seat={first.acting_seat} game_length={first.game_length} num_seats={len(first.seats)}")
    for sb in first.seats:
        print(f"    seat={sb.seat} vector_len={len(sb.vector)} heuristic_score={sb.heuristic_score:.4f} "
              f"elimination_turn={sb.elimination_turn} placement={sb.placement}")
    if args.dump_first_vector and first.seats:
        print("  first seat vector:")
        print("   ", first.seats[0].vector)
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
