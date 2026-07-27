#!/usr/bin/env python3
"""V4-021: is the ~25% of games discarded as timeouts non-random w.r.t. Ultron's fortunes?

If timed-out games skew toward Ultron losing, every gate win-rate we've reported is optimistic.
Uses the final board state that games.jsonl records even for timed-out games.
"""
import json, sys, statistics as st

def load(path):
    with open(path) as f:
        return [json.loads(l) for l in f if l.strip()]

def ultron_seat(g):
    profs = g["run"]["aiProfiles"]
    return profs.index("Ultron") if "Ultron" in profs else None

def summarize(path, label):
    games = load(path)
    rows = []
    for g in games:
        us = ultron_seat(g)
        if us is None:
            continue
        ps = {p["seat"]: p for p in g["players"]}
        u, o = ps[us], ps[1 - us]
        rows.append(dict(
            timeout=bool(g.get("timeout")),
            completed=bool(g.get("completedNormally")),
            error=g.get("error"),
            won=bool(u.get("won")),
            turns=g.get("totalPlayerTurns") or 0,
            secs=(g.get("elapsedMillis") or 0) / 1000.0,
            u_life=u.get("life"), o_life=o.get("life"),
            u_pow=u.get("totalPower"), o_pow=o.get("totalPower"),
            u_perm=u.get("battlefieldCount"), o_perm=o.get("battlefieldCount"),
            u_hand=u.get("handSize"), o_hand=o.get("handSize"),
        ))

    done = [r for r in rows if r["completed"] and not r["timeout"]]
    to   = [r for r in rows if r["timeout"] or not r["completed"]]
    wins = sum(r["won"] for r in done)

    print(f"\n{'='*72}\n{label}\n{'='*72}")
    print(f"total games logged      : {len(rows)}")
    print(f"completed (gate sample) : {len(done)}   Ultron wins {wins} = {100*wins/max(1,len(done)):.1f}%")
    print(f"discarded (timeout/err) : {len(to)} = {100*len(to)/max(1,len(rows)):.1f}% of all games")
    if not to:
        return
    def med(rs, k):
        v = [r[k] for r in rs if r[k] is not None]
        return st.median(v) if v else float("nan")

    print(f"\n  {'metric':<22}{'completed':>12}{'timed-out':>12}")
    print(f"  {'-'*46}")
    for k, lbl in [("turns","player turns"),("secs","elapsed sec")]:
        print(f"  {lbl:<22}{med(done,k):>12.1f}{med(to,k):>12.1f}")

    # At the moment of the timeout, was Ultron ahead or behind?
    print(f"\n  --- position of Ultron at cutoff, in the {len(to)} DISCARDED games ---")
    print(f"  {'metric':<22}{'Ultron':>10}{'Default':>10}{'delta':>10}")
    print(f"  {'-'*52}")
    for uk, ok, lbl in [("u_life","o_life","life"),("u_pow","o_pow","total power"),
                        ("u_perm","o_perm","permanents"),("u_hand","o_hand","hand size")]:
        mu, mo = med(to, uk), med(to, ok)
        print(f"  {lbl:<22}{mu:>10.1f}{mo:>10.1f}{mu-mo:>+10.1f}")

    ahead_life = sum(1 for r in to if r["u_life"] is not None and r["o_life"] is not None and r["u_life"] > r["o_life"])
    behind_life = sum(1 for r in to if r["u_life"] is not None and r["o_life"] is not None and r["u_life"] < r["o_life"])
    ahead_pow = sum(1 for r in to if (r["u_pow"] or 0) > (r["o_pow"] or 0))
    behind_pow = sum(1 for r in to if (r["u_pow"] or 0) < (r["o_pow"] or 0))
    print(f"\n  Ultron ahead on life  : {ahead_life}/{len(to)}   behind: {behind_life}   tied: {len(to)-ahead_life-behind_life}")
    print(f"  Ultron ahead on power : {ahead_pow}/{len(to)}   behind: {behind_pow}   tied: {len(to)-ahead_pow-behind_pow}")

    # Bounds: what would the win rate be if every discarded game were a loss / a win?
    n_all = len(done) + len(to)
    print(f"\n  reported win rate           : {100*wins/max(1,len(done)):.1f}%  (n={len(done)}, discards dropped)")
    print(f"  worst case (all discards L) : {100*wins/n_all:.1f}%")
    print(f"  best  case (all discards W) : {100*(wins+len(to))/n_all:.1f}%")

if __name__ == "__main__":
    base = "/home/william/github/forge/simstats/out"
    summarize(f"{base}/v4_018e_regate_v0_pruning/games.jsonl", "V0 (MAIN1-only) — the 37.3% headline")
    summarize(f"{base}/v4_018d_gate_v1/games.jsonl", "V1 (multi-phase) — the 8.2% negative")
