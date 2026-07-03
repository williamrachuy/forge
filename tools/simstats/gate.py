#!/usr/bin/env python3
"""
gate.py — Phase 0 statistical gate for Ultron v3 sim runs (P0.4).

Reads a candidate games.jsonl (and optionally a paired control games.jsonl) and answers,
with real statistics instead of eyeballing a 25-game run: did the candidate actually beat
the baseline?

Usage:
    tools/simstats/gate.py candidate/games.jsonl [control/games.jsonl] [--profile Ultron]
                                                  [--seat N] [--min-games 150]

Win rate is computed by AI PROFILE, not by fixed seat, because seat rotation (see P0.2)
means the profile of interest may sit in a different seat every game. For each game, the
profile's seat is looked up in that game's own `run.aiProfiles` list (recorded per-game, so
seat rotation is transparent here). If a games.jsonl predates that field (or the profile
name never appears in it), pass --seat N to fall back to a fixed seat — this reproduces the
old seat-0-pinned behavior for legacy files.

Timeouts are reported separately and EXCLUDED from the win-rate denominator (they are not
losses; they're a measurement failure for that game) — never silently dropped from the report.

Statistics:
  - Wilson score 95% CI for the win rate (better small-sample behavior than a naive
    normal-approximation CI).
  - Exact one-sided binomial p-value for "win rate > 0.25" (4-player FFA null).
  - If a control file is given: per-profile-slot (i.e. per AI profile name) win rates in the
    control run, plus a two-proportion z-test of candidate-vs-control. Because the control
    lane is typically homogeneous (e.g. 4x Default) the most meaningful control baseline is
    the pooled per-seat win rate — see NOTE in `control_baseline()` for the statistical caveat.

--min-games 150 (default): below this, prints "SAMPLE TOO SMALL — NOISE" per the plan's
power analysis (a 25-game run has ~+-17pp of CI width — not informative).
"""

import argparse
import json
import math
from pathlib import Path


NULL_WIN_RATE = 0.25  # 4-player FFA baseline


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


def profile_seat(game, profile_name):
    """Return the seat occupied by profile_name in this specific game, or None."""
    run = game.get("run", {})
    profiles = run.get("aiProfiles")
    if not profiles:
        return None
    try:
        return profiles.index(profile_name)
    except ValueError:
        return None


def outcomes_for_profile(games, profile_name, fallback_seat=None):
    """
    Returns (wins, losses_or_other, timeouts, unresolved) counts for `profile_name` across
    `games`. `unresolved` counts games where the profile's seat could not be determined at
    all (no run.aiProfiles match and no --seat fallback) — these are excluded from every
    other count and reported so they're never silently dropped.
    """
    wins = losses = timeouts = unresolved = 0
    for g in games:
        seat = profile_seat(g, profile_name)
        if seat is None:
            seat = fallback_seat
        if seat is None:
            unresolved += 1
            continue
        if g.get("timeout"):
            timeouts += 1
            continue
        won = g.get("winnerSeat") == seat and g.get("winReason") != "Draw"
        if won:
            wins += 1
        else:
            losses += 1
    return wins, losses, timeouts, unresolved


def per_seat_rates(games):
    """Per-seat (win, total-non-timeout) tuples across all games, keyed by seat index."""
    wins = {}
    totals = {}
    for g in games:
        if g.get("timeout"):
            continue
        winner = g.get("winnerSeat")
        is_draw = g.get("winReason") == "Draw"
        run = g.get("run", {})
        profiles = run.get("aiProfiles", [])
        player_count = run.get("playerCount", len(profiles) or 4)
        for seat in range(player_count):
            totals[seat] = totals.get(seat, 0) + 1
            if not is_draw and winner == seat:
                wins[seat] = wins.get(seat, 0) + 1
    return wins, totals


def control_baseline(games):
    """
    Pooled per-seat baseline win rate from a (typically homogeneous, e.g. 4x-Default)
    control run: x = total non-draw wins summed across all seats, n = games * playerCount
    (every seat is one Bernoulli "did this seat win" observation per game).

    CAVEAT: the 4 seat-observations within one game are mutually exclusive (dependent), not
    independent Bernoulli trials — treating them as independent for the z-test slightly
    understates the true variance. This is a pragmatic engineering baseline (fast signal,
    "is the control near 25% or not"), not a peer-reviewed estimator. Good enough to catch
    engine regressions or a genuinely skewed baseline; treat p-values from it as indicative,
    not exact.
    """
    wins, totals = per_seat_rates(games)
    x = sum(wins.values())
    n = sum(totals.values())
    return x, n, wins, totals


# ---------------------------------------------------------------------------
# Statistics
# ---------------------------------------------------------------------------

def wilson_ci(x, n, z=1.959963984540054):
    if n == 0:
        return (0.0, 0.0)
    phat = x / n
    denom = 1 + z * z / n
    center = phat + z * z / (2 * n)
    adj = z * math.sqrt(phat * (1 - phat) / n + z * z / (4 * n * n))
    return ((center - adj) / denom, (center + adj) / denom)


def exact_binomial_sf(x, n, p):
    """P(X >= x) for X ~ Binomial(n, p) — exact, via math.comb (n <= ~1000 is fine)."""
    if x <= 0:
        return 1.0
    if x > n:
        return 0.0
    total = 0.0
    for k in range(x, n + 1):
        total += math.comb(n, k) * (p ** k) * ((1 - p) ** (n - k))
    return min(1.0, total)


def two_proportion_z_test(x1, n1, x2, n2):
    """One-sided z-test: H1 is p1 > p2. Returns (z, p_value) or (None, None) if undefined."""
    if n1 == 0 or n2 == 0:
        return None, None
    p1 = x1 / n1
    p2 = x2 / n2
    pooled = (x1 + x2) / (n1 + n2)
    se = math.sqrt(pooled * (1 - pooled) * (1 / n1 + 1 / n2))
    if se == 0:
        return None, None
    z = (p1 - p2) / se
    # one-sided p-value via the standard normal survival function (erf-based, stdlib only)
    p_value = 0.5 * math.erfc(z / math.sqrt(2))
    return z, p_value


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

def fmt_pct(x):
    return f"{100 * x:.1f}%"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("candidate", help="candidate games.jsonl")
    parser.add_argument("control", nargs="?", default=None, help="optional control games.jsonl")
    parser.add_argument("--profile", default="Ultron", help="AI profile name to gate (default: Ultron)")
    parser.add_argument("--seat", type=int, default=None,
                        help="fallback seat index when run.aiProfiles is absent/doesn't contain --profile")
    parser.add_argument("--min-games", type=int, default=150,
                        help="below this many counted games, warn SAMPLE TOO SMALL (default 150)")
    args = parser.parse_args()

    games = load_games(args.candidate)
    if not games:
        print(f"No games found in {args.candidate}")
        return 1

    wins, losses, timeouts, unresolved = outcomes_for_profile(games, args.profile, args.seat)
    counted = wins + losses  # excludes timeouts and unresolved from the win-rate denominator

    print("=== gate.py — Ultron v3 Phase 0 statistical gate ===")
    print(f"Candidate: {args.candidate}")
    print(f"Profile  : {args.profile}" + (f"  (fallback seat {args.seat})" if args.seat is not None else ""))
    print(f"Total games in file : {len(games)}")
    print(f"Timeouts (excluded)  : {timeouts}")
    if unresolved:
        print(f"UNRESOLVED (profile/seat not found — excluded, NOT silently dropped): {unresolved}")
    print(f"Games counted (wins+losses) : {counted}")
    print(f"Wins  : {wins}")
    print(f"Losses: {losses}")

    if counted == 0:
        print("\nNo resolvable games — cannot compute a win rate. Check --profile / --seat.")
        return 1

    win_rate = wins / counted
    lo, hi = wilson_ci(wins, counted)
    p_value_null = exact_binomial_sf(wins, counted, NULL_WIN_RATE)

    print(f"\nWin rate: {fmt_pct(win_rate)}  (Wilson 95% CI: [{fmt_pct(lo)}, {fmt_pct(hi)}])")
    print(f"Exact one-sided binomial p-value vs {fmt_pct(NULL_WIN_RATE)} null: {p_value_null:.4f}")

    if counted < args.min_games:
        print(f"\n*** SAMPLE TOO SMALL — NOISE *** (counted={counted} < --min-games={args.min_games})")
        print("    Per the v3 plan power analysis, runs under ~150 games have CI widths of")
        print("    +-15pp or more. Do not draw conclusions from this run alone.")

    control_verdict = None
    if args.control:
        control_games = load_games(args.control)
        if not control_games:
            print(f"\nControl file {args.control} has no games — skipping control comparison.")
        else:
            print(f"\n=== Control comparison ===")
            print(f"Control: {args.control}")
            cwins, ctotals = per_seat_rates(control_games)
            print("Per-seat win rates in control run:")
            for seat in sorted(ctotals):
                w = cwins.get(seat, 0)
                t = ctotals[seat]
                rate = w / t if t else 0.0
                print(f"  seat {seat}: {w}/{t}  ({fmt_pct(rate)})")

            cx, cn, _, _ = control_baseline(control_games)
            baseline_rate = cx / cn if cn else 0.0
            print(f"Pooled control baseline (all seats): {cx}/{cn}  ({fmt_pct(baseline_rate)})")
            print("  (see control_baseline() docstring for the pooling caveat)")

            z, p_two = two_proportion_z_test(wins, counted, cx, cn)
            if z is None:
                print("\nTwo-proportion z-test: undefined (zero variance / empty sample).")
            else:
                print(f"\nTwo-proportion z-test (candidate {args.profile} vs control baseline):")
                print(f"  candidate p1={fmt_pct(win_rate)} (n={counted})   control p2={fmt_pct(baseline_rate)} (n={cn})")
                print(f"  z={z:.3f}  one-sided p-value (candidate > control)={p_two:.4f}")
                control_verdict = p_two < 0.05 and win_rate > baseline_rate

    print("\n=== VERDICT ===")
    if control_verdict is not None:
        if counted < args.min_games:
            print("PASS/FAIL: withheld — sample too small (see warning above).")
        elif control_verdict:
            print(f"PASS — {args.profile} win rate ({fmt_pct(win_rate)}) beats the measured control "
                  f"baseline at p<0.05.")
        else:
            print(f"FAIL — {args.profile} win rate ({fmt_pct(win_rate)}) does not beat the measured "
                  f"control baseline at p<0.05.")
    else:
        if counted < args.min_games:
            print("PASS/FAIL: withheld — sample too small (see warning above).")
        elif p_value_null < 0.05 and win_rate > NULL_WIN_RATE:
            print(f"PASS — {args.profile} win rate ({fmt_pct(win_rate)}) beats the {fmt_pct(NULL_WIN_RATE)} "
                  f"null baseline at p<0.05 (no control file given).")
        else:
            print(f"FAIL — {args.profile} win rate ({fmt_pct(win_rate)}) does not beat the "
                  f"{fmt_pct(NULL_WIN_RATE)} null baseline at p<0.05 (no control file given).")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
