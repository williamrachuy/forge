#!/usr/bin/env python3
"""
Analyze Ultron (seat 0) vs Default AI (seats 1-3) in a 4-player battlebox sim run.

Sections:
  1. Run health       — completion, timeouts, errors
  2. Win / Loss       — per-seat win rates vs baseline
  3. Survival         — elimination turn distributions
  4. Monarch          — monarch hold time
  5. Combat           — attacks, damage, spells
  6. Game duration    — elapsed time and turns
  7. Decision summary — fallback rate, mean score, prune rate, phase breakdown
  8. Action analysis  — top chosen cards/spells, scoreReason tokens, score distribution
  9. Weight evolution — per-game weight trajectory (if weights were captured)
"""

import argparse
import json
import re
import statistics
from collections import Counter, defaultdict
from pathlib import Path


# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------

def load_games(path):
    games = []
    with Path(path).open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                games.append(json.loads(line))
    return games


def player(game, seat):
    for p in game.get("players", []):
        if p.get("seat") == seat:
            return p
    return {}


# ---------------------------------------------------------------------------
# Stats helpers
# ---------------------------------------------------------------------------

def mean(v):    return statistics.fmean(v) if v else 0.0
def median(v):  return statistics.median(v) if v else 0.0
def stddev(v):  return statistics.stdev(v) if len(v) > 1 else 0.0
def pct(v, p):
    if not v: return 0.0
    return sorted(v)[round((len(v) - 1) * p)]

def dist(values, label, fmt=".1f"):
    if not values:
        return f"  {label}: n/a"
    return (f"  {label}: mean={mean(values):{fmt}}  median={median(values):{fmt}}  "
            f"stddev={stddev(values):{fmt}}  p90={pct(values, 0.9):{fmt}}  "
            f"min={min(values):{fmt}}  max={max(values):{fmt}}")

def bar(value, total, width=20):
    filled = round(value / total * width) if total else 0
    return "█" * filled + "░" * (width - filled)


# ---------------------------------------------------------------------------
# Section helpers
# ---------------------------------------------------------------------------

def section(title):
    print(f"\n── {title} {'─' * max(0, 58 - len(title))}")


def win_loss_split(games, ultron_seat):
    wins, losses = [], []
    for g in games:
        won = g.get("winnerSeat") == ultron_seat and g.get("winReason") != "Draw"
        if won:
            wins.append(g)
        else:
            losses.append(g)
    return wins, losses


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jsonl", nargs="?",
                        default="simstats/out/battlebox_monarch_4p_ultron/games.jsonl")
    parser.add_argument("--ultron-seat", type=int, default=0)
    parser.add_argument("--baseline-jsonl", default=None,
                        help="Ultron baseline (no adaptive) run for comparison")
    parser.add_argument("--weights-file", default=None,
                        help="Path to weights.json override file (optional)")
    parser.add_argument("--top-cards", type=int, default=15,
                        help="How many top chosen cards to show (default 15)")
    parser.add_argument("--no-decisions", action="store_true",
                        help="Skip per-decision action analysis (faster for large runs)")
    args = parser.parse_args()

    games = load_games(args.jsonl)
    if not games:
        print("No games found.")
        return

    us = args.ultron_seat
    default_seats = [s for s in range(4) if s != us]
    total = len(games)
    completed = sum(1 for g in games if g.get("completedNormally"))
    timeouts  = sum(1 for g in games if g.get("timeout"))
    errors    = sum(1 for g in games if g.get("error"))

    print(f"=== Ultron vs Default AI — Battlebox Monarch 4-Player ===")
    print(f"Source : {args.jsonl}")
    print(f"Games  : {total}  Completed: {completed} ({100*completed/total:.1f}%)  "
          f"Timeouts: {timeouts} ({100*timeouts/total:.1f}%)  Errors: {errors}")

    wins, losses = win_loss_split(games, us)
    ultron_wins  = len(wins)
    draws        = sum(1 for g in games if g.get("winnerSeat") == -1 or g.get("winReason") == "Draw")
    default_wins = total - ultron_wins - draws

    # ── 1. Win / Loss ──────────────────────────────────────────────────────
    section("Win / Loss")
    print(f"  Ultron (seat {us}) wins : {ultron_wins:3d} / {total}  "
          f"({100*ultron_wins/total:.1f}%)  {bar(ultron_wins, total)}")
    print(f"  Default AI wins (sum)  : {default_wins:3d} / {total}  "
          f"({100*default_wins/total:.1f}%)  {bar(default_wins, total)}")
    print(f"  Draws / timeouts       : {draws:3d} / {total}  "
          f"({100*draws/total:.1f}%)")
    print()
    print("  Per-seat win counts:")
    for seat in range(4):
        w = sum(1 for g in games if g.get("winnerSeat") == seat and g.get("winReason") != "Draw")
        profile = "Ultron" if seat == us else "Default"
        print(f"    Seat {seat} ({profile}): {w:3d}  ({100*w/total:.1f}%)  {bar(w, total)}")

    # baseline comparison inline
    if args.baseline_jsonl:
        baseline = load_games(args.baseline_jsonl)
        if baseline:
            bt = len(baseline)
            bw = sum(1 for g in baseline if g.get("winnerSeat") == us and g.get("winReason") != "Draw")
            bwr = bw / bt
            uwr = ultron_wins / total
            print(f"\n  Seat {us} vs baseline: Default={100*bwr:.1f}%  Ultron={100*uwr:.1f}%  "
                  f"delta={100*(uwr-bwr):+.1f}pp")

    # ── 2. Survival ────────────────────────────────────────────────────────
    section("Survival")
    u_elim, d_elim = [], []
    u_survived = 0
    for g in games:
        elims = {e["seat"]: e["turn"] for e in g.get("eliminations", [])}
        if us in elims:
            u_elim.append(elims[us])
        else:
            u_survived += 1
        for s in default_seats:
            if s in elims:
                d_elim.append(elims[s])
    print(f"  Ultron eliminated in {len(u_elim)}/{total} games  (survived to end: {u_survived})")
    if u_elim: print(dist(u_elim, "Ultron elim turn", ".0f"))
    if d_elim: print(dist(d_elim, "Default elim turn (per seat-game)", ".0f"))

    # ── 3. Monarch ─────────────────────────────────────────────────────────
    section("Monarch")
    u_mt, d_mt = [], []
    for g in games:
        by_seat = g.get("monarch", {}).get("turnsBySeat", [])
        if us < len(by_seat): u_mt.append(by_seat[us])
        for s in default_seats:
            if s < len(by_seat): d_mt.append(by_seat[s])
    print(dist(u_mt, "Ultron monarch turns/game", ".1f"))
    print(dist(d_mt, "Default monarch turns/seat-game", ".1f"))
    mc = [g.get("monarch", {}).get("changes", 0) for g in games]
    print(dist(mc, "Monarch changes/game", ".0f"))

    # ── 4. Combat ──────────────────────────────────────────────────────────
    section("Combat")
    for label, seat_list in [("Ultron", [us]), ("Default (avg/seat)", default_seats)]:
        atk, blk, dd, dt, sp, tt = [], [], [], [], [], []
        for g in games:
            for s in seat_list:
                p = player(g, s)
                atk.append(p.get("attacksDeclared", 0))
                blk.append(p.get("blocksDeclared", 0))
                dd.append(p.get("damageDealt", 0))
                dt.append(p.get("damageTaken", 0))
                sp.append(p.get("spellsCast", 0))
                tt.append(p.get("turnsTaken", 0))
        print(f"  {label}:")
        for vals, lbl in [(atk,"attacks/game"),(blk,"blocks/game"),(dd,"dmg dealt/game"),
                          (dt,"dmg taken/game"),(sp,"spells cast/game"),(tt,"turns taken/game")]:
            print(dist(vals, lbl, ".1f"))

    # ── 5. Game Duration ───────────────────────────────────────────────────
    section("Game Duration")
    elapsed = [g.get("elapsedMillis", 0) / 1000 for g in games]
    turns   = [g.get("totalPlayerTurns", 0) for g in games]
    print(dist(elapsed, "elapsed seconds/game", ".1f"))
    print(dist(turns,   "total player turns/game", ".1f"))

    # ── 6. Decision Summary ────────────────────────────────────────────────
    u_games = [g for g in games if "ultron" in g]
    section(f"Decision Summary  ({len(u_games)} games with Ultron stats)")
    if not u_games:
        print("  (no ultron block — rebuild needed)")
    else:
        summaries = [g["ultron"]["summary"] for g in u_games]
        for key, lbl, fmt in [
            ("totalDecisions",    "decisions/game",       ".1f"),
            ("fallbackRate",      "fallback rate",        ".3f"),
            ("meanChoiceScore",   "mean choice score",    ".1f"),
            ("meanPruneRate",     "mean prune rate",      ".3f"),
            ("mainPhaseChoices",  "main-phase choices/g", ".1f"),
            ("respondChoices",    "respond choices/g",    ".1f"),
        ]:
            print(dist([s[key] for s in summaries], lbl, fmt))

        # outcome correlation
        print()
        print("  Outcome correlation:")
        for key, lbl in [("fallbackRate", "fallback rate"), ("meanChoiceScore", "mean score"),
                          ("meanPruneRate", "prune rate")]:
            w_vals = [g["ultron"]["summary"][key] for g in u_games
                      if g.get("winnerSeat") == us and g.get("winReason") != "Draw"]
            l_vals = [g["ultron"]["summary"][key] for g in u_games
                      if not (g.get("winnerSeat") == us and g.get("winReason") != "Draw")]
            if w_vals and l_vals:
                delta = mean(w_vals) - mean(l_vals)
                print(f"    {lbl:22s}: WIN={mean(w_vals):.4f}  LOSS={mean(l_vals):.4f}  Δ={delta:+.4f}")

    # ── 7. Action Analysis ─────────────────────────────────────────────────
    if not args.no_decisions and u_games:
        section("Action Analysis  (decision-level)")

        # Flatten all CHOOSE decisions, tag with win/loss
        win_chosen  = Counter()
        loss_chosen = Counter()
        win_reasons = Counter()
        loss_reasons = Counter()
        win_scores, loss_scores = [], []
        phase_counts = defaultdict(Counter)  # phase → kind → count

        role_wins  = Counter()
        role_losses = Counter()
        pass_scores = []   # bestCandidateScore for PASS/FALLBACK — tells us what was rejected

        for g in u_games:
            won = g.get("winnerSeat") == us and g.get("winReason") != "Draw"
            for d in g["ultron"].get("decisions", []):
                phase = d.get("phase", "?")
                kind  = d.get("kind", "?")
                role  = d.get("role", "?")
                phase_counts[phase][kind] += 1

                if won: role_wins[role]  += 1
                else:   role_losses[role] += 1

                if kind == "CHOOSE":
                    card   = d.get("chosen", "?")
                    reason = d.get("scoreReason", "") or ""
                    score  = d.get("bestCandidateScore", d.get("score", 0))
                    if won:
                        win_chosen[card]  += 1
                        win_scores.append(score)
                        for tok in _reason_tokens(reason):
                            win_reasons[tok] += 1
                    else:
                        loss_chosen[card] += 1
                        loss_scores.append(score)
                        for tok in _reason_tokens(reason):
                            loss_reasons[tok] += 1
                elif kind in ("PASS", "FALLBACK"):
                    s = d.get("bestCandidateScore", d.get("score", 0))
                    if s > 0:
                        pass_scores.append(s)

        # Phase × kind table
        print("\n  Decisions by phase and kind:")
        all_phases = ["MAIN", "RESPOND", "OTHER"]
        all_kinds  = ["CHOOSE", "PASS", "FALLBACK", "NO_DECISION"]
        header = f"  {'Phase':8s}" + "".join(f"  {k:12s}" for k in all_kinds)
        print(header)
        for ph in all_phases:
            row_total = sum(phase_counts[ph].values())
            if row_total == 0: continue
            row = f"  {ph:8s}"
            for k in all_kinds:
                cnt = phase_counts[ph][k]
                row += f"  {cnt:5d} ({100*cnt/row_total:4.1f}%)"
            print(row)

        # Role breakdown — how often Ultron was in each role, split by outcome
        all_roles = sorted(set(role_wins) | set(role_losses))
        if all_roles:
            print(f"\n  Decision counts by role (win vs loss):")
            print(f"  {'Role':<16s}  {'Wins':>6}  {'Losses':>7}  {'Win%':>5}")
            for role in all_roles:
                w = role_wins[role]
                l = role_losses[role]
                wr = f"{100*w/(w+l):.0f}%" if w+l else "—"
                print(f"  {role:<16s}  {w:6d}  {l:7d}  {wr:>5}")

        # Rejected-score distribution: what scores did Ultron compute but not play?
        if pass_scores:
            print(f"\n  Best-candidate score when Ultron PASSED ({len(pass_scores)} decisions):")
            print(f"  {dist(pass_scores, 'score', '.1f').strip()}")

        # Top chosen cards
        n = args.top_cards
        all_cards = set(win_chosen) | set(loss_chosen)
        card_data = []
        for card in all_cards:
            w = win_chosen[card]
            l = loss_chosen[card]
            card_data.append((card, w, l, w + l))
        card_data.sort(key=lambda x: -x[3])

        print(f"\n  Top {n} chosen cards (by total frequency, win vs loss split):")
        print(f"  {'Card':<32s}  {'Total':>5}  {'Wins':>5}  {'Losses':>6}")
        for card, w, l, tot in card_data[:n]:
            print(f"  {card:<32s}  {tot:5d}  {w:5d}  {l:6d}")

        # Win-rate by card (cards chosen ≥5 times)
        print(f"\n  Win-rate by card (≥5 total choices):")
        card_wr = [(card, w, l, w+l, w/(w+l) if w+l else 0)
                   for card, w, l, _ in card_data if w + l >= 5]
        card_wr.sort(key=lambda x: -x[4])
        print(f"  {'Card':<32s}  {'Win%':>5}  {'W':>4}  {'L':>4}")
        for card, w, l, tot, wr in card_wr[:n]:
            print(f"  {card:<32s}  {100*wr:5.1f}%  {w:4d}  {l:4d}")

        # Score distribution by outcome
        if win_scores and loss_scores:
            print(f"\n  Choice score distribution by outcome:")
            print(f"  Wins  — {dist(win_scores,  'score', '.1f').strip()}")
            print(f"  Losses— {dist(loss_scores, 'score', '.1f').strip()}")

        # scoreReason token frequency (CHOOSE decisions — why cards were chosen)
        if win_reasons or loss_reasons:
            all_tokens = set(win_reasons) | set(loss_reasons)
            token_data = [(tok, win_reasons[tok], loss_reasons[tok])
                          for tok in all_tokens
                          if win_reasons[tok] + loss_reasons[tok] >= 3]
            token_data.sort(key=lambda x: -(x[1] + x[2]))
            print(f"\n  scoreReason token frequency — CHOOSE (≥3 occurrences):")
            print(f"  {'Token':<30s}  {'Wins':>5}  {'Losses':>6}")
            for tok, w, l in token_data[:20]:
                print(f"  {tok:<30s}  {w:5d}  {l:6d}")

        # PASS reason distribution — what caused Ultron to pass?
        pass_win_reasons, pass_loss_reasons = Counter(), Counter()
        for g in u_games:
            won = g.get("winnerSeat") == us and g.get("winReason") != "Draw"
            for d in g["ultron"].get("decisions", []):
                if d.get("kind") in ("PASS", "FALLBACK"):
                    reason = d.get("scoreReason", "") or ""
                    if reason:
                        tok = reason.split(":")[0].strip()  # e.g. "threat below threshold"
                        if won: pass_win_reasons[tok] += 1
                        else:   pass_loss_reasons[tok] += 1
        if pass_win_reasons or pass_loss_reasons:
            all_ptoks = set(pass_win_reasons) | set(pass_loss_reasons)
            ptok_data = [(t, pass_win_reasons[t], pass_loss_reasons[t])
                         for t in all_ptoks if pass_win_reasons[t] + pass_loss_reasons[t] >= 2]
            ptok_data.sort(key=lambda x: -(x[1] + x[2]))
            print(f"\n  PASS reason frequency (≥2 occurrences):")
            print(f"  {'Reason':<40s}  {'Wins':>5}  {'Losses':>6}")
            for tok, w, l in ptok_data[:15]:
                print(f"  {tok:<40s}  {w:5d}  {l:6d}")

    # ── 8. Weight Evolution ────────────────────────────────────────────────
    weight_games = [g for g in games if "ultronWeights" in g]
    section(f"Weight Evolution  ({len(weight_games)} games with weight snapshots)")

    if weight_games:
        # Show trajectory for each weight key across the run
        all_keys = set()
        for g in weight_games:
            all_keys.update(g["ultronWeights"].keys())

        for key in sorted(all_keys):
            values = [g["ultronWeights"].get(key, 1.0) for g in weight_games]
            first  = values[0]
            last   = values[-1]
            lo     = min(values)
            hi     = max(values)
            print(f"  {key:<24s}: start={first:.4f}  end={last:.4f}  "
                  f"lo={lo:.4f}  hi={hi:.4f}  Δ={last-first:+.4f}")

        # Correlate each weight level with win/loss for the games that have it
        if len(weight_games) > 5:
            print()
            print("  Weight level correlation with outcome (games with weight snapshots):")
            for key in sorted(all_keys):
                w_vals = [g["ultronWeights"].get(key, 1.0) for g in weight_games
                          if g.get("winnerSeat") == us and g.get("winReason") != "Draw"]
                l_vals = [g["ultronWeights"].get(key, 1.0) for g in weight_games
                          if not (g.get("winnerSeat") == us and g.get("winReason") != "Draw")]
                if w_vals and l_vals:
                    print(f"    {key:<24s}: WIN mean={mean(w_vals):.4f}  "
                          f"LOSS mean={mean(l_vals):.4f}  Δ={mean(w_vals)-mean(l_vals):+.4f}")
    else:
        print("  (no weight snapshots in data — weights were at baseline 1.0 or "
              "adaptive mode was off)")

    # show live weights file if provided
    if args.weights_file and Path(args.weights_file).exists():
        print(f"\n  Current weights file ({args.weights_file}):")
        with open(args.weights_file) as f:
            for line in f:
                print(f"    {line}", end="")
        print()

    # ── 9. Baseline Comparison (full) ──────────────────────────────────────
    if args.baseline_jsonl:
        baseline = load_games(args.baseline_jsonl)
        if baseline:
            section("Baseline Comparison (all-Default)")
            bt = len(baseline)
            bc = sum(1 for g in baseline if g.get("completedNormally"))
            be = [g.get("elapsedMillis", 0) / 1000 for g in baseline]
            bwins = {s: sum(1 for g in baseline if g.get("winnerSeat") == s
                            and g.get("winReason") != "Draw") for s in range(4)}
            print(f"  Baseline: {bt} games  completed={bc} ({100*bc/bt:.1f}%)  "
                  f"mean_elapsed={mean(be):.1f}s")
            print(f"  Baseline win rates by seat: " +
                  "  ".join(f"seat{s}={100*bwins[s]/bt:.1f}%" for s in range(4)))
            uwr = ultron_wins / total
            bwr = bwins[us] / bt
            print(f"  Seat {us}: Default={100*bwr:.1f}%  Ultron={100*uwr:.1f}%  "
                  f"delta={100*(uwr-bwr):+.1f}pp")

            b_elapsed = [g.get("elapsedMillis", 0) / 1000 for g in baseline]
            print(f"  Game duration: baseline={mean(b_elapsed):.1f}s  "
                  f"Ultron={mean(elapsed):.1f}s  delta={mean(elapsed)-mean(b_elapsed):+.1f}s")

    print("\n=== Done ===")


def _reason_tokens(reason: str) -> list:
    """Extract meaningful tokens from a scoreReason string."""
    if not reason:
        return []
    # split on spaces, =, digits; keep alpha tokens ≥4 chars
    tokens = re.findall(r'[a-zA-Z][a-zA-Z\-]+', reason)
    return [t.lower() for t in tokens if len(t) >= 4]


if __name__ == "__main__":
    main()
