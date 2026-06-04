#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def metric(report, path, default=0):
    value = report
    for key in path:
        value = value.get(key, default) if isinstance(value, dict) else default
    return value


def delta_line(label, left, right):
    return f"- {label}: {left:.2f} -> {right:.2f} (delta {right - left:+.2f})"


def distribution_lines(label, baseline, candidate, path):
    return [
        delta_line(f"{label} mean", metric(baseline, path + ["mean"]), metric(candidate, path + ["mean"])),
        delta_line(f"{label} stddev", metric(baseline, path + ["stddev"]), metric(candidate, path + ["stddev"])),
        delta_line(f"{label} median", metric(baseline, path + ["median"]), metric(candidate, path + ["median"])),
        delta_line(f"{label} P90", metric(baseline, path + ["p90"]), metric(candidate, path + ["p90"])),
        delta_line(f"{label} P95", metric(baseline, path + ["p95"]), metric(candidate, path + ["p95"])),
        delta_line(f"{label} max", metric(baseline, path + ["max"]), metric(candidate, path + ["max"])),
    ]


def main():
    parser = argparse.ArgumentParser(description="Compare two SimStats report.json files")
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    baseline = load(args.baseline)
    candidate = load(args.candidate)
    lines = [
        f"# SimStats Comparison: {baseline.get('run', {}).get('runName')} vs {candidate.get('run', {}).get('runName')}",
        "",
        f"- Baseline games: {baseline.get('games')} completed {baseline.get('completed')}",
        f"- Candidate games: {candidate.get('games')} completed {candidate.get('completed')}",
        "",
        "## Deltas",
        "",
        delta_line("Completion rate", baseline.get("completionRate", 0), candidate.get("completionRate", 0)),
        delta_line("Mean monarch changes", metric(baseline, ["monarchChanges", "mean"]), metric(candidate, ["monarchChanges", "mean"])),
        "",
        "## Turn Distribution",
        "",
        *distribution_lines("Player turns", baseline, candidate, ["turns"]),
        "",
        "## Runtime Distribution",
        "",
        *distribution_lines("Elapsed ms", baseline, candidate, ["elapsedMillis"]),
        "",
        "## Winner Counts",
        "",
    ]
    seats = sorted(set(baseline.get("winnerCounts", {})) | set(candidate.get("winnerCounts", {})), key=str)
    for seat in seats:
        lines.append(f"- Seat {seat}: {baseline.get('winnerCounts', {}).get(seat, 0)} -> {candidate.get('winnerCounts', {}).get(seat, 0)}")

    output = "\n".join(lines) + "\n"
    if args.out:
        Path(args.out).write_text(output, encoding="utf-8")
        print(args.out)
    else:
        print(output)


if __name__ == "__main__":
    main()
