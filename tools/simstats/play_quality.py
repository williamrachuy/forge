#!/usr/bin/env python3
"""play_quality.py — WHY a model wins or loses, not just whether.

    python3 tools/simstats/play_quality.py <run_dir> [<run_dir> ...]

Every game already logs 28 per-player stats and this project has evaluated exclusively on scalar
win rate. The MTG RL literature is explicit that this is insufficient -- win rate alone hides the
diagnostic structure. V1's "durdling" failure was found by a human reading logs by hand; it should
have been a number.

METHOD: PAIRED per-game deltas. Both players' stats come from the same game, so comparing
Ultron-minus-opponent within each game controls for game length, draw luck and board explosions
automatically. A raw per-model average would confound all three.

The metrics map onto what actually decides games of Magic:
  TEMPO           lands/turn, spells/turn -- mana efficiency is what tempo IS, mechanically
  CARD ADVANTAGE  cards drawn, hand size, graveyard
  AGGRESSION      attacks declared, combat damage dealt
  DEFENCE         blocks declared, damage taken
  BOARD           permanents, creatures, total power
  ACTIVITY        abilities activated, triggers -- the durdle detector
Reference: tempo vs card advantage is the central resource trade in Magic, and which one matters
depends on game stage (early = tempo, late = card advantage).
"""
import sys, json, glob, os, statistics as st

METRICS = [
    ("TEMPO", "lands/turn",      lambda p, t: p.get("landsPlayed", 0) / max(1, t)),
    ("TEMPO", "spells/turn",     lambda p, t: p.get("spellsCast", 0) / max(1, t)),
    ("CARDS", "drawn",           lambda p, t: p.get("cardsDrawnApprox", 0)),
    ("CARDS", "hand at end",     lambda p, t: p.get("handSize", 0)),
    ("CARDS", "graveyard",       lambda p, t: p.get("graveyardSize", 0)),
    ("AGGRO", "attacks",         lambda p, t: p.get("attacksDeclared", 0)),
    ("AGGRO", "cmbt dmg dealt",  lambda p, t: p.get("combatDamageDealt", 0)),
    ("AGGRO", "total dmg dealt", lambda p, t: p.get("damageDealt", 0)),
    ("DEF",   "blocks",          lambda p, t: p.get("blocksDeclared", 0)),
    ("DEF",   "cmbt dmg taken",  lambda p, t: p.get("combatDamageTaken", 0)),
    ("BOARD", "permanents",      lambda p, t: p.get("battlefieldCount", 0)),
    ("BOARD", "creatures",       lambda p, t: p.get("creatures", 0)),
    ("BOARD", "total power",     lambda p, t: p.get("totalPower", 0)),
    ("ACT",   "abils activated", lambda p, t: p.get("abilitiesActivated", 0)),
    ("ACT",   "triggers",        lambda p, t: p.get("triggeredAbilities", 0)),
    ("MISC",  "mulligans",       lambda p, t: p.get("mulligans", 0)),
]


def load(run):
    for pat in ("round_*/shard_*/games.jsonl", "shard_*/games.jsonl",
                "nodes/*/round_*/shard_*/games.jsonl", "nodes/*/shard_*/games.jsonl"):
        files = sorted(glob.glob(os.path.join(run, pat)))
        if files:
            break
    else:
        return []
    rows = []
    for f in files:
        try:
            for line in open(f):
                line = line.strip()
                if line:
                    try:
                        rows.append(json.loads(line))
                    except Exception:
                        pass
        except OSError:
            pass
    return rows


def analyse(run):
    rows = [r for r in load(run) if r.get("completedNormally") and not r.get("timeout")]
    if not rows:
        return None
    deltas = {k: [] for _, k, _ in METRICS}
    ult_abs = {k: [] for _, k, _ in METRICS}
    wins = turns = 0
    won_deltas, lost_deltas = {k: [] for _, k, _ in METRICS}, {k: [] for _, k, _ in METRICS}
    for r in rows:
        profs = r.get("run", {}).get("aiProfiles", [])
        if "Ultron" not in profs:
            continue
        seat = profs.index("Ultron")
        me = next((p for p in r["players"] if p.get("seat") == seat), None)
        opps = [p for p in r["players"] if p.get("seat") != seat]
        if not me or not opps:
            continue
        t = me.get("turnsTaken") or r.get("totalPlayerTurns", 1) or 1
        turns += t
        won = bool(me.get("won"))
        wins += won
        for _, name, fn in METRICS:
            mine = fn(me, t)
            # average the opponents so 1v1 and 4p are directly comparable
            theirs = st.mean([fn(o, o.get("turnsTaken") or t) for o in opps])
            d = mine - theirs
            deltas[name].append(d)
            ult_abs[name].append(mine)
            (won_deltas if won else lost_deltas)[name].append(d)
    n = len(deltas["lands/turn"])
    if not n:
        return None
    return dict(run=os.path.basename(run), n=n, wins=wins, winrate=100 * wins / n,
                avg_turns=turns / n, deltas=deltas, absolute=ult_abs,
                won=won_deltas, lost=lost_deltas)


def fmt(v):
    return f"{v:+.2f}" if abs(v) < 100 else f"{v:+.0f}"


def main(runs):
    reports = [a for a in (analyse(r) for r in runs) if a]
    if not reports:
        print("no completed games found in:", ", ".join(runs)); return 1

    w = 16
    print("\n=== PLAY QUALITY — Ultron minus opponent, paired within each game ===")
    hdr = "  " + " " * 26 + "".join(f"{r['run'][:w-1]:>{w}}" for r in reports)
    print(hdr)
    print("  " + " " * 26 + "".join(f"{'n=' + str(r['n']):>{w}}" for r in reports))
    print("  " + " " * 26 + "".join(f"{'win ' + format(r['winrate'], '.1f') + '%':>{w}}" for r in reports))
    print("  " + " " * 26 + "".join(f"{'turns ' + format(r['avg_turns'], '.1f'):>{w}}" for r in reports))
    last_group = None
    for group, name, _ in METRICS:
        if group != last_group:
            print(f"\n  [{group}]")
            last_group = group
        line = f"    {name:<24}"
        for r in reports:
            vals = r["deltas"][name]
            line += f"{fmt(st.mean(vals)):>{w}}"
        print(line)

    # The durdle signature: winning games vs losing games, same model. If a model's losses look
    # like "more permanents, fewer attacks", it is hoarding board instead of closing.
    print("\n=== WON vs LOST games (same model) — where does the loss come from? ===")
    for r in reports:
        if not r["won"]["attacks"] or not r["lost"]["attacks"]:
            continue
        print(f"\n  {r['run']}  ({r['wins']} won / {r['n'] - r['wins']} lost)")
        print(f"    {'metric':<24}{'won':>10}{'lost':>10}{'gap':>10}")
        for _, name, _ in METRICS:
            wv = st.mean(r["won"][name]) if r["won"][name] else 0
            lv = st.mean(r["lost"][name]) if r["lost"][name] else 0
            if abs(wv - lv) < 0.05:
                continue
            print(f"    {name:<24}{wv:>10.2f}{lv:>10.2f}{wv - lv:>+10.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))
