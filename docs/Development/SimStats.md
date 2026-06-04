# SimStats

`simstats` runs many headless games from an INI-style config and writes one JSON object per game to `games.jsonl`.

Build a desktop jar first:

```bash
mvn -pl forge-gui-desktop -am -DskipTests package
```

Run a profile:

```bash
tools/simstats/run_simstats.sh configs/simstats/battlebox_monarch_4p.ini
```

Generate a report:

```bash
tools/simstats/report_simstats.py simstats/out/battlebox_monarch_4p/games.jsonl
```

Compare reports:

```bash
tools/simstats/compare_reports.py \
  simstats/out/battlebox_no_monarch_4p/report.json \
  simstats/out/battlebox_monarch_4p/report.json
```

## Config Shape

```ini
[run]
name=battlebox_monarch_4p
games=1000
seed=123456
timeoutSeconds=120
outputDir=simstats/out/battlebox_monarch_4p

[game]
format=Battlebox
players=4
deck=Battlebox Test Deck.dck
aiProfile=Default
battleboxMonarch=true

[stats]
enabled=true
turnSnapshots=true
```

For Battlebox, a single `deck` value is repeated for every seat and the first registered deck remains the shared Battlebox source. For non-Battlebox runs, use `game.decks` with a comma-separated list if seats need different decks.

## Agent Reporting Checklist

When comparing profiles, report:

- Config files used.
- Completed games, timeouts, and errors.
- Raw `games.jsonl` and generated report paths.
- Game length deltas.
- Winner distribution deltas.
- Attack and damage matrix differences.
- Feature-specific deltas such as monarch observations, monarch changes, and approximate monarch extra draws.
- Replay seeds for outlier games.

`stats.enabled=false` runs the configured simulations without attaching the collector and does not write raw stats.
