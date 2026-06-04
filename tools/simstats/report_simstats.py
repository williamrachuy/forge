#!/usr/bin/env python3
import argparse
import json
import statistics
from pathlib import Path


def load_games(path):
    games = []
    with Path(path).open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                games.append(json.loads(line))
    return games


def mean(values):
    return statistics.fmean(values) if values else 0.0


def median(values):
    return statistics.median(values) if values else 0.0


def percentile(values, pct):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = round((len(ordered) - 1) * pct)
    return ordered[index]


def stddev(values):
    return statistics.stdev(values) if len(values) > 1 else 0.0


def scalar_stats(values):
    mu = mean(values)
    sigma = stddev(values)
    return {
        "count": len(values),
        "mean": mu,
        "stddev": sigma,
        "median": median(values),
        "p90": percentile(values, 0.90),
        "p95": percentile(values, 0.95),
        "min": min(values) if values else 0,
        "max": max(values) if values else 0,
        "normalBands": {
            "minus1Stddev": mu - sigma,
            "plus1Stddev": mu + sigma,
            "minus2Stddev": mu - (2 * sigma),
            "plus2Stddev": mu + (2 * sigma),
            "minus3Stddev": mu - (3 * sigma),
            "plus3Stddev": mu + (3 * sigma),
        },
    }


def add_matrix(a, b):
    if not a:
        return [row[:] for row in b]
    for i, row in enumerate(b):
        while len(a) <= i:
            a.append([0] * len(row))
        while len(a[i]) < len(row):
            a[i].append(0)
        for j, value in enumerate(row):
            a[i][j] += value
    return a


def player_metric_stats(games, key):
    values = {}
    for game in games:
        for player in game.get("players", []):
            seat = str(player.get("seat"))
            values.setdefault(seat, []).append(player.get(key, 0))
    return {seat: scalar_stats(values[seat]) for seat in sorted(values, key=int)}


def build_report(games):
    completed = [g for g in games if g.get("completedNormally")]
    lengths = [g.get("totalPlayerTurns", 0) for g in completed]
    rounds = [g.get("completedTableRounds", 0) for g in completed]
    elapsed = [g.get("elapsedMillis", 0) for g in games]
    winner_counts = {}
    feature_counts = {}
    attacks = []
    damage = []

    for game in games:
        winner = str(game.get("winnerSeat"))
        winner_counts[winner] = winner_counts.get(winner, 0) + 1
        for key, value in game.get("features", {}).items():
            if isinstance(value, bool) and value:
                feature_counts[key] = feature_counts.get(key, 0) + 1
        matrices = game.get("matrices", {})
        attacks = add_matrix(attacks, matrices.get("attacks", []))
        damage = add_matrix(damage, matrices.get("totalDamage", []))

    monarch_changes = [g.get("monarch", {}).get("changes", 0) for g in games]
    report = {
        "run": games[0].get("run", {}) if games else {},
        "games": len(games),
        "completed": len(completed),
        "timeouts": sum(1 for g in games if g.get("timeout")),
        "errors": sum(1 for g in games if g.get("error")),
        "completionRate": (len(completed) / len(games)) if games else 0,
        "turns": scalar_stats(lengths),
        "tableRounds": scalar_stats(rounds),
        "elapsedMillis": scalar_stats(elapsed),
        "winnerCounts": winner_counts,
        "featureCounts": feature_counts,
        "attacksMatrix": attacks,
        "damageMatrix": damage,
        "monarchChanges": scalar_stats(monarch_changes),
        "playerStats": {
            "life": player_metric_stats(games, "life"),
            "cardsDrawnApprox": player_metric_stats(games, "cardsDrawnApprox"),
            "spellsCast": player_metric_stats(games, "spellsCast"),
            "damageDealt": player_metric_stats(games, "damageDealt"),
            "attacksDeclared": player_metric_stats(games, "attacksDeclared"),
        },
        "outliers": sorted(
            [
                {
                    "gameIndex": g.get("run", {}).get("gameIndex"),
                    "gameSeed": g.get("run", {}).get("gameSeed"),
                    "totalPlayerTurns": g.get("totalPlayerTurns"),
                    "winnerSeat": g.get("winnerSeat"),
                }
                for g in games
            ],
            key=lambda item: item.get("totalPlayerTurns") or 0,
            reverse=True,
        )[:10],
    }
    return report


def write_markdown(report, path):
    run = report.get("run", {})
    def distribution_line(label, stats, unit=""):
        suffix = f" {unit}" if unit else ""
        return (
            f"- {label}: mean {stats['mean']:.2f}{suffix}, stddev {stats['stddev']:.2f}{suffix}, "
            f"median {stats['median']:.2f}{suffix}, p90 {stats['p90']}{suffix}, "
            f"p95 {stats['p95']}{suffix}, min {stats['min']}{suffix}, max {stats['max']}{suffix}"
        )

    def normal_band_line(label, stats, unit=""):
        suffix = f" {unit}" if unit else ""
        bands = stats["normalBands"]
        return (
            f"- {label} normal bands: 1σ [{bands['minus1Stddev']:.2f}, {bands['plus1Stddev']:.2f}]{suffix}, "
            f"2σ [{bands['minus2Stddev']:.2f}, {bands['plus2Stddev']:.2f}]{suffix}, "
            f"3σ [{bands['minus3Stddev']:.2f}, {bands['plus3Stddev']:.2f}]{suffix}"
        )

    lines = [
        f"# SimStats Report: {run.get('runName', 'unknown')}",
        "",
        f"- Games: {report['games']}",
        f"- Completed: {report['completed']} ({report['completionRate']:.1%})",
        f"- Timeouts: {report['timeouts']}",
        f"- Errors: {report['errors']}",
        f"- Format: {run.get('format')}",
        f"- Players: {run.get('playerCount')}",
        f"- Base seed: {run.get('baseSeed')}",
        "",
        "## Game Length",
        "",
        distribution_line("Player turns", report["turns"], "turns"),
        normal_band_line("Player turns", report["turns"], "turns"),
        distribution_line("Table rounds", report["tableRounds"], "rounds"),
        normal_band_line("Table rounds", report["tableRounds"], "rounds"),
        "",
        "## Runtime",
        "",
        distribution_line("Elapsed", report["elapsedMillis"], "ms"),
        normal_band_line("Elapsed", report["elapsedMillis"], "ms"),
        "",
        "## Winners",
        "",
    ]
    for seat, count in sorted(report["winnerCounts"].items(), key=lambda item: item[0]):
        lines.append(f"- Seat {seat}: {count}")
    lines.extend(["", "## Feature Observations", ""])
    for key, count in sorted(report["featureCounts"].items()):
        lines.append(f"- {key}: {count}")
    lines.extend(["", "## Player Distributions", ""])
    for metric, values in report["playerStats"].items():
        rendered = ", ".join(
            f"seat {seat}: mean {stats['mean']:.2f}, stddev {stats['stddev']:.2f}, p90 {stats['p90']}, max {stats['max']}"
            for seat, stats in values.items()
        )
        lines.append(f"- {metric}: {rendered}")
    lines.extend(["", "## Outlier Replay Seeds", ""])
    for outlier in report["outliers"]:
        lines.append(
            f"- game {outlier['gameIndex']} seed {outlier['gameSeed']} turns {outlier['totalPlayerTurns']} winner {outlier['winnerSeat']}"
        )
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Build a SimStats report from games.jsonl")
    parser.add_argument("jsonl")
    parser.add_argument("--out-dir", default=None)
    args = parser.parse_args()

    jsonl = Path(args.jsonl)
    out_dir = Path(args.out_dir) if args.out_dir else jsonl.parent
    out_dir.mkdir(parents=True, exist_ok=True)
    report = build_report(load_games(jsonl))
    (out_dir / "report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    write_markdown(report, out_dir / "report.md")
    print(out_dir / "report.md")


if __name__ == "__main__":
    main()
