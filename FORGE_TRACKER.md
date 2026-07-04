# FORGE PROJECT TRACKER

A living, multi-threaded journal for agents and humans. Covers all active development lines on
this fork of the upstream Forge MTG engine. Read the relevant project before touching code.
Update the relevant ticket when work is done, or add a discussion note when you learn something.

**How to use this file:**
- Start here when picking up any thread. Find the project, find the ticket.
- Each ticket has: current status, what was done, why it was done that way, and open questions.
- Add `> AGENT NOTE [date]:` or `> HUMAN NOTE [date]:` comment blocks to tickets for discoveries,
  decisions made mid-session, or ideas that came up but weren't acted on.
- Status legend: `[DONE]` `[IN_PROGRESS]` `[PLANNED]` `[IDEA]` `[BLOCKED]` `[PAUSED]`
- Do NOT delete done tickets. History is the point.

---

# PROJECT: ULTRON-AI
**Status:** ACTIVE
**Branch:** `ultron-fast-ai-remodel`

> AGENT NOTE [2026-07-03]: Ultron v3 reapproach plan written (search + learned value
> function + belief state, full PlayerController ownership). Plan doc:
> `/home/william/agents/brainstorm/plans/ultron-v3-search-and-learning.md`.
> Latest adaptive run verdict: 41/250 games, 14.7% win rate on completed — per-card
> learning approach declared a dead end. v2 runtime components slated for staged
> retirement per plan §9 once v3 gates pass.
**Goal:** Make Ultron competitive in 4-player free-for-all Battlebox Monarch without requiring an
LLM API key. LLM infrastructure (DeepSeek advisor, strategic plans) is preserved but demoted to
an optional enhancement layer. The runtime AI must make good decisions in < 10ms per priority pass.

**Context:** The original Ultron AI was purely LLM-driven — every decision waited on a DeepSeek
API call. This made sim runs impossible and interactive play laggy. The remodel introduces a fast
heuristic runtime layer that handles all gameplay decisions independently, with the LLM optionally
providing strategic hints.

---

## EPIC: ULTRON-V3 / PHASE-0
**Status:** IN_PROGRESS
**Branch:** `ultron-v3` (created off `ultron-fast-ai-remodel`)
**Goal:** Measurement infrastructure that can't lie, per plan §7 Phase 0. Everything here is
scaffolding for statistically valid win-rate claims — no AI decision logic changes.
Plan doc: `/home/william/agents/brainstorm/plans/ultron-v3-search-and-learning.md`.

### TICKET-V3-001: Parallel sim runner [DONE 2026-07-03]
File: `tools/simstats/run_parallel.sh` (new).
Launches W worker JVMs concurrently, each running a disjoint shard of games via the new
`run.seedOffset` config key (see TICKET-V3-002), each writing to its own `shard_N/` directory
(config, `run.log`/`wrapper.log`, `games.jsonl`), then merges all shards' `games.jsonl` into
`<outputDir>/games.jsonl`.
**Measured machine limits (2026-07-03):** `nproc`=4 cores. `free -g`: total=15g, available=7g
(the "available" column, not "free" — accounts for reclaimable buff/cache). Budget formula:
`min(50% of total, available) - 1g safety margin` = min(7, 7) - 1 = **6g** total committed
heap ceiling. Computed defaults: workers=3, xmx=2g (3×2=6g). For the smoke test we overrode
to a more conservative **workers=2, xmx=3g** (6g total) given this box also runs other
Tailscale/hub agent services — see policy 19 (usage-aware acceptance). Both W and Xmx are
CLI-overridable (`--workers`, `--xmx`); computed defaults are printed every run under a
"RAM safety check" banner for auditing.
Workers run under `nice -n 10`. Refuses to launch any config with `sim.adaptiveWeights=true`
(shared mutable state in `~/.forge/ultron-learning/` would race across workers — Ultron v3
does not use that mechanism).
**Bugs found and fixed in existing infra while wiring this up (not new v3 code, but blocking):**
1. `run_simstats.sh` parsed `outputDir`/`repeat` via `grep | head -1` — picks the FIRST match
   in the file. `SimStatsConfig.java`'s parser is last-value-wins (a `TreeMap` keyed by
   `section.key`, overwritten on each read). Since `run_parallel.sh` appends an override
   `[run]` section after the base config's `[run]` section (so the same key appears twice),
   the shell script and the Java process disagreed about where `run.log`/`games.jsonl` should
   land. Fixed: `head -1` → `tail -1` in both places, matching Java's override semantics.
2. Same greps used bare `grep | ...` inside `set -o pipefail`. When a key (here: `repeat`,
   which our new v3 configs don't set) has zero matches, `grep` exits 1, `pipefail` propagates
   that through the pipeline, and `set -e` kills the script — silently, no error message,
   because grep with no match writes nothing to stderr. Fixed: wrapped the grep in
   `{ grep ... || true; }` so an absent optional key just resolves to empty (existing
   fallback logic already handles empty `repeat`; `outputDir` still errors explicitly via
   its own emptiness check afterward).
`run_simstats.sh` also gained `FORGE_SIM_XMX` (overrides `-Xmx8g`, default unchanged) and
`FORGE_SKIP_GROOM` (skip the Cube Cobra deck-groom step — avoids every shard worker hitting
the network / clobbering the deck file concurrently).
**Smoke run results (2026-07-03):** 12-game all-Default parallel run, 2 workers × 3g:
12/12 games completed, 0 timeouts, wall time 21m20s (16:05:08→16:26:28), ~1.78 min/game
effective throughput vs ~3 min/game serial historically. All 12 gameSeeds unique across
shards (disjoint ranges confirmed), global gameIndex 0–11 correct, merged games.jsonl =
12 records. Follow-up 3-game Ultron+3xDefault run (1 worker × 4g, rotateSeats=true):
Ultron seats 0→3→2 across games, exactly per the (s+N) mod 4 design; gate.py credited
Ultron's game-2 win via profile lookup, not fixed seat. Session total 15 games (the cap).
**RSS finding:** `ps` reported ~9.3GB RSS per worker at -Xmx3g. Much of that is ZGC
multi-mapping (colored pointers map the heap multiple times, inflating apparent RSS) plus
card DB/metaspace/native — but real pressure was also visible: available RAM fell 7g→~1-2g
and ~1.2g of swap was touched during the 2-worker run. Box stayed responsive, wall times
sane. Conclusion: budget-by-Xmx alone underestimates footprint; keep workers=2 as the
practical ceiling on this 15g box until PSS is measured properly (smem) on a longer run.

### TICKET-V3-002: Seat rotation [DONE 2026-07-03]
Files: `SimStatsConfig.java`, `SimulateStats.java`.
`game.rotateSeats=true`: each game N, seat s is assigned the profile originally at index
`(s + N) mod playerCount` — a non-Default profile cycles through every seat across a run
instead of being pinned to seat 0 (see TICKET-107: seat 1 vs seat 3 win rates differed by
27pp at fixed seats in the 37-game run — that confound poisons every seat-0-pinned
measurement made to date).
The per-game **rotated** profile list is passed into `SimStatsGameContext` and recorded as
`run.aiProfiles` in that game's JSONL record — so seat-vs-profile is fully recoverable
per-game without adding a new field. Verified: `players[]` records `name`/`seat`/`won`;
`run.aiProfiles[seat]` gives the profile name for that seat in that specific game.
`tools/simstats/analyze_ultron.py` was NOT updated to be rotation-aware this session (it
still assumes `--ultron-seat` fixed at 0) — out of scope for P0.2 strictly, but flagged here
since a future session running seat-rotated data through it will get wrong numbers until
it's updated to use `run.aiProfiles` like `gate.py` does. **Deviation/TODO.**
Also added `run.seedOffset` (long, default 0): games are seeded from
`(seedOffset + local game index)`, not local index alone. The seed-mixing function
(`SimulateStats.seedForGame`) is a bijective 64-bit hash (SplitMix64-style finalizer), so
disjoint index ranges are *guaranteed* disjoint seeds — this is what TICKET-V3-001's
parallel runner uses for non-overlapping shard seed ranges.

### TICKET-V3-003: Control lane configs [DONE 2026-07-03]
Files: `configs/simstats/v3_control_default_4p.ini` (4x Default), `v3_ultron_vs_default_4p.ini`
(Ultron + 3x Default). Both: `battleboxMonarch=true`, `rotateSeats=true`, identical seed
(910123) and `bannedCards` list (Nadu Winged Wisdom, Scute Swarm, Mystic Forge),
`timeoutSeconds=1200`, `stats.enabled=true`, `sim.adaptiveWeights=false`. Same seed across
both configs gives a true paired same-seed comparison per plan §7 P0.3 / §8.

### TICKET-V3-004: gate.py statistical gate script [DONE 2026-07-03]
File: `tools/simstats/gate.py` (new, python3 stdlib only).
Reads candidate `games.jsonl` (+ optional control `games.jsonl`), computes win rate by AI
**profile** (looked up per-game via `run.aiProfiles`, so seat rotation is transparent) rather
than by fixed seat. `--seat N` supports legacy files predating `run.aiProfiles`.
Reports: games counted, timeouts (excluded from the win-rate denominator, reported
separately, never silently dropped), wins, win rate, Wilson 95% CI, exact one-sided
binomial p-value vs the 0.25 four-player null. With `--control`: per-seat win rates in the
control file, a pooled control baseline, and a two-proportion z-test candidate-vs-control
(documented caveat in `control_baseline()`: the 4 per-game seat outcomes are mutually
exclusive/dependent, not independent Bernoulli trials — pooling them for n is a pragmatic
engineering shortcut, not a peer-reviewed estimator; flagged in the script's own docstring).
`--min-games 150` (default) prints "SAMPLE TOO SMALL — NOISE" below that count per the
plan's §8 power analysis, and withholds PASS/FAIL rather than printing a misleading verdict.
Smoke-tested against the existing 25-game `battlebox_monarch_4p_ultron/games.jsonl` (both
with and without `run.aiProfiles`, exercising the `--seat` fallback) — see verification
results below.

### TICKET-V3-007: 500-game all-Default control run — baseline win-rate distribution [DONE 2026-07-04]
Run: `configs/simstats/v3_control_default_4p.ini`, 500 games, seed 910123, seat rotation on,
2 workers x 3g heap via `run_parallel.sh` (`ultron_v3_control` tmux session). Started
2026-07-03 18:13:43, finished 2026-07-04 08:13:20 — **13h59m wall time**, ~35.7 games/hour
combined (2 workers). Output: `simstats/out/v3_control_default_4p/games.jsonl` (500 records,
merged cleanly from 2 shards of 250).

**Results (via `tools/simstats/gate.py`):**
- Timeouts: 10/500 (2.0%), excluded from win-rate denominator per design. 0 errors.
- Games counted: 490. Overall win rate: **24.7%** (Wilson 95% CI: [21.1%, 28.7%]).
- Per-seat win rates — no detectable seat/turn-order bias at this sample size:

  | Seat | Win rate | 95% CI |
  |---|---|---|
  | 0 | 24.7% | [21.1%, 28.7%] |
  | 1 | 25.3% | [21.7%, 29.3%] |
  | 2 | 24.7% | [21.1%, 28.7%] |
  | 3 | 24.9% | [21.3%, 28.9%] |

**Conclusion:** the flat 25% baseline assumption (used throughout the v3 plan's power
analysis, §8) is confirmed accurate at n=500. The small-sample seat-3 disadvantage hinted at
by pre-v3 25-game runs was noise — fully washed out here. This is the trustworthy control
baseline every future Ultron candidate win rate gets compared against (paired same-seed
control runs per the plan's statistical protocol, §8).

### TICKET-V3-006: Ultron loads v2 learned state even with adaptiveWeights=false [OPEN, RISK]
Observed during the P0 smoke run of `v3_ultron_vs_default_4p.ini` (adaptiveWeights=false):
startup still logs `[ULTRON-WEIGHTS] Loaded 3 overrides` and `[ULTRON-CARD-STATS] Loaded 416
card records` from `~/.forge/ultron-learning/`. The load path is not gated on the config flag
(only the post-game *update* is). Read-only, so parallel workers are safe — but any "clean"
v3 Ultron eval run is silently contaminated by v2 learned weights (aggression≈2.6 etc.) and
per-card adjustments unless `~/.forge/ultron-learning/weights.json` and
`ultron_card_stats.json` are deleted first (see BUILD REFERENCE reset commands).
**Action for the 500-game runs and all v3 gates:** either clear those files before each run,
or gate the load on the same flag. Not fixed this session (would touch forge-ai runtime code,
out of Phase 0 scope).

### TICKET-V3-005: Pre-existing test failures found during verification [OPEN, NOT CAUSED BY V3 WORK]
8/42 `forge.ai.llm.runtime.Ultron*` unit tests fail on this branch: `UltronCombatPolicyTest`,
`UltronMainPhasePolicyTest`, `UltronRuntimeCacheInvalidationTest`,
`UltronRuntimeControllerSelectionTest` (×2), `UltronRuntimeHookInvalidationTest`,
`UltronRuntimeLandHookInvalidationTest`, `UltronRuntimeStackHookInvalidationTest` — all
"Ahead-state ..." assertions (pruning/mana-preservation expectations flipped). Verified via
a throwaway worktree at the `checkpoint: WIP from adaptive-learning sessions (pre-v3)` commit
(before any Phase 0 work) — same 8 failures present there. This is pre-existing breakage in
the WIP that predates this session; out of scope per this session's instructions (no changes
to `forge-ai/.../forge/ai/` decision logic beyond seed/rotation config). Needs a follow-up
session before Phase 1 starts — Phase 1's gate requires all unit tests green.

---

## EPIC: ULTRON-V3 / PHASE-1
**Status:** DONE (2026-07-04)
**Branch:** `ultron-v3`
**Goal:** `UltronPlayerController` owns the full decision surface, per plan §7 Phase 1. Pure
plumbing — no Ultron-specific decision logic yet. Plan doc:
`/home/william/agents/brainstorm/plans/ultron-v3-search-and-learning.md` §7 P1.1-P1.3.

### TICKET-V3-101: UltronPlayerController + AiController cleanup [DONE 2026-07-04]
Files: `forge-ai/src/main/java/forge/ai/ultron/UltronPlayerController.java` (new),
`forge-ai/src/main/java/forge/ai/AiController.java`, `forge-ai/src/main/java/forge/ai/LobbyPlayerAi.java`,
`forge-ai/src/main/java/forge/ai/llm/UltronConfig.java`.

**Mechanism found (corrects a possibly-stale reading of `ULTRON_RUNTIME_REMODEL_NOTES.md`):**
`AiController` is a per-player decision-logic helper held by `PlayerControllerAi` (field `brains`,
accessor `getAi()`) — it is not itself a `PlayerController`. `PlayerControllerAi extends
PlayerController` and overrides all ~121 decision methods (114 counted directly via `@Override`
scan of `PlayerControllerAi.java`, plus the constructor and a few non-`@Override` helper methods);
most of those overrides just delegate straight into `AiController` (e.g.
`declareAttackers`/`declareBlockers`/`chooseSpellAbilityToPlay` call `brains.xxx()`), but the bulk
of the ~121 methods are answered directly in `PlayerControllerAi` itself without touching
`AiController` at all (mana payment, target selection, scry/surveil ordering, etc.). So "the hooks
live in AiController" was correct for the specific 3 decisions the remodel notes named
(main-phase spell choice, attack declaration, stack response) but incomplete as a description of
the decision surface — the other ~110+ methods Ultron needs to eventually own live directly on
`PlayerControllerAi`, which is exactly why subclassing `PlayerControllerAi` (not `AiController`)
is the correct fix.

**What was done:**
- New `UltronPlayerController extends PlayerControllerAi`. All 114 `@Override` decision methods
  from `PlayerControllerAi` are re-overridden here (signatures extracted programmatically from
  `PlayerControllerAi.java` to guarantee exact fidelity — hand-transcribing 114 signatures by eye
  invites a subtle override mismatch that only shows up at runtime). Every override times a call
  to `super.method(...)` and records it via `UltronDecisionTelemetry` as "inherited" — Phase 1 has
  zero Ultron-authored decision logic, by design (see plan §7 P1 gate: "Ultron-with-all-inherited-
  behavior ≡ Default within noise").
- `LobbyPlayerAi.createControllerFor` now instantiates `UltronPlayerController` when
  `this.aiProfile` (checked directly — not `ai.getLobbyPlayer()`, which reads through the player's
  controller and isn't wired up yet at this call site) equals `UltronConfig.PROFILE_NAME` (made
  `public`, was package-private). `rotateProfileEachGame` timing is untouched — profile rotation
  still happens after controller creation, exactly as before.
- `AiController` stripped of all `isUltronRuntime`/`isUltronRuntimeProfile` branches: removed the
  `UltronCombatPolicy` filter from `declareAttackers`, the dual-flag (`isUltronRuntime` /
  `useUltronAdvisor`) candidate-routing block from `chooseSpellAbilityToPlayFromList` (collapsed
  back to the same "return first WillPlay candidate" loop every other profile already used), and
  the runtime-controller veto branch from `getSpellAbilityToPlay`'s stack-response path (collapsed
  to the single stock branch that used to be the `else`). 8 now-dead imports removed
  (`UltronCombatPolicy`, `UltronDecisionLog`, `UltronRuntimeController`, `UltronRuntimeDecision`,
  `UltronTableThreatSummary`, `UltronThreatModel`, `UltronTurnIntent`, `UltronTurnIntentBuilder`).
  The v2 runtime classes under `forge.ai.llm.runtime` are not deleted — orphaned per plan §9,
  retired in later stages.
- **Deviation (documented, not fixed):** left two LLM-strategic-plan hooks in `AiController`
  untouched — the plan-filter call in `declareAttackers` and the land-selection path in
  `chooseSpellAbilityToPlay`, both gated behind `UltronConfig.enabledForStrategicPlanLlm()`
  (default `false`). These are not `isUltronRuntime`/`isUltronRuntimeProfile` branches (the
  literal thing this ticket's scope named) and are off by default; flagging as residual
  non-profile-agnostic surface for a future ticket if full `AiController` purity is wanted before
  those hooks are themselves retired (LLM strategic plan is a separate, still-preserved feature
  per plan §9, not part of the runtime-flag cleanup).

### TICKET-V3-102: Decision telemetry [DONE 2026-07-04]
Files: `forge-ai/src/main/java/forge/ai/ultron/UltronDecisionTelemetry.java` (new),
`forge-gui-desktop/src/main/java/forge/view/SimulateStats.java`.
One `UltronDecisionTelemetry` instance per `UltronPlayerController` (no shared/static state —
safe across parallel sim workers and repeated games in one JVM). `record(methodName,
answeredByUltron, elapsedNanos)` is a handful of atomic-counter increments (`AtomicLong` totals +
a `ConcurrentHashMap<String, AtomicLongArray>` per-method breakdown) — no string formatting or
allocation unless a `verboseLogging` flag (off by default) is set, per the "keep it cheap for hot
loops" requirement. `toMap()` produces `{summary: {totalDecisions, answeredByUltron,
answeredByInherited, coverageRatio, totalElapsedMs}, perMethod: {...}}`.
`SimulateStats.java` embeds this under a new `ultronCoverage` JSONL key (added
`findUltronCoverage(Game)`, mirroring the existing `findUltronSimStats`/`findUltronPlayer`
pattern) — separate from the pre-existing `ultron`/`UltronSimStats` key, which only populates
when something still calls into `UltronRuntimeController` (nothing does, post-101, for a v3
Ultron game). Verified via the new unit test: after game setup + one explicit decision call,
`totalDecisions` increments by exactly 1 and `answeredByUltron` stays 0.

### TICKET-V3-103: Threat model as feature provider [DONE 2026-07-04]
File: `forge-ai/src/main/java/forge/ai/ultron/UltronPlayerController.java`
(`refreshThreatSummary()`). Thin wrapper over the existing `UltronThreatModel.analyze(Game,
Player)` → `UltronTableThreatSummary` (v2 class, unchanged). Deliberately not called from any
decision override in Phase 1 — proven callable (unit test constructs a 4-player game, calls it
twice in a row) but not consumed anywhere; Phase 2/3 wires its output into
search/value-function features per plan §5.

### TICKET-V3-104: v2 state contamination guard [DONE 2026-07-04]
No code change beyond what TICKET-V3-101 already did — the fix is architectural. `UltronWeights`
and `UltronCardStats` (`forge-ai/src/main/java/forge/ai/llm/runtime/`) both eagerly load
`~/.forge/ultron-learning/{weights.json,ultron_card_stats.json}` from a `static { load(DEFAULT_PATH); }`
block the instant either class is first touched by the JVM — independent of the `adaptiveWeights`
config flag (this is the exact mechanism behind TICKET-V3-006). Before this session, a live
Ultron game touched those classes indirectly: `AiController` routed through
`UltronRuntimeController`, which calls `UltronActionScorer`/`UltronCandidatePruner`, both of which
reference `UltronWeights.get(...)` — first touch triggers the static load regardless of any flag.
TICKET-V3-101 already severed that entire call chain (`AiController` no longer references
`UltronRuntimeController` at all), and `UltronPlayerController` was written to never import
anything from `forge.ai.llm.runtime` except the read-only `UltronThreatModel`/
`UltronTableThreatSummary` pair (P1.3), which do not touch `UltronWeights`/`UltronCardStats`. Net
effect: an Ultron v3 game's decision path never triggers either static initializer, so
`~/.forge/ultron-learning/` is never touched — v3 runs start clean of v2 learned state without
needing a new opt-in flag. Verified by a unit test that reads `UltronPlayerController.class`'s
raw bytes and asserts the constant pool contains no reference to `UltronWeights`,
`UltronCardStats`, `UltronRuntimeController`, `UltronActionScorer`, or `UltronCandidatePruner` —
deterministic (checks the compiled class's actual references) rather than relying on a runtime
side effect that JVM/test-ordering could mask. Old `~/.forge/ultron-learning/` files (if any exist
from prior v2 sessions) are left in place — TICKET-V3-006's original ask was to stop new v3 runs
from loading them, not to delete pre-existing data; deletion (if wanted for a fully clean gate
run) remains a manual/runbook step.
**Residual risk:** this guard only covers `UltronPlayerController`'s own code. If the LLM
strategic-plan hooks left in place per TICKET-V3-101's documented deviation ever get extended to
reference `UltronWeights`/`UltronCardStats` in a future change, the guard would need re-verifying
— they don't today (checked: `forge.ai.llm.*` package has zero references to either class).

### Build / test verification (2026-07-04)
- `mvn -pl forge-ai,forge-gui-desktop -am clean package -DskipTests -q` — **BUILD SUCCESS**
  (checkstyle included, not skipped; new files required trimming several unused wildcard-covered
  imports to pass).
- `mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true -Dtest="forge.ai.llm.runtime.Ultron*" -Dsurefire.failIfNoSpecifiedTests=false`
  — **34/42 pass**, identical to the TICKET-V3-005 baseline. The same 8 "Ahead-state ..." tests
  fail with the same assertions, unchanged; no regression, no accidental fix.
- New `forge-gui-desktop/src/test/java/forge/ai/ultron/UltronPlayerControllerTest.java` — 6/6
  pass: construction/wiring (Ultron profile → `UltronPlayerController`; every other profile →
  plain `PlayerControllerAi`, unaffected), telemetry (0% Ultron-authored, +1 decision recorded per
  explicit call), threat-summary callability (twice, proving it's read-only/idempotent), and the
  TICKET-V3-104 bytecode contamination-guard check.
- **Sim smoke test: DEFERRED.** At verification time, the P0.3 500-game
  `v3_control_default_4p` run was still active in tmux session `ultron_v3_control`
  (`shard_0`/`shard_1` each at ~131/250 games — 2 workers, the box's measured RAM ceiling per
  TICKET-V3-001). Per this session's instructions, no additional sim games were launched while
  both worker slots are in use. Phase 1's plumbing is verified by build + unit tests only; the
  4-6 game `v3_ultron_vs_default_4p.ini` smoke run (confirm games complete without crashing, new
  `ultronCoverage` field appears in JSONL) is still owed once a worker slot frees up.

---

## EPIC: ULTRON-V3 / PHASE-2
**Status:** IN PROGRESS (P2.1 done; P2.2 data point collected; P2.3 done; P2.4 done; P2.5 done -- attacker-only, see below; P2.6 not started)
**Branch:** `ultron-v3`
**Goal:** Simulation-based big-3 decisions per plan §7 Phase 2. Plan doc:
`/home/william/agents/brainstorm/plans/ultron-v3-search-and-learning.md` §6, §7 P2.1-P2.6.

### TICKET-V3-201: GameCopier Battlebox fidelity harness (P2.1) [DONE 2026-07-04]
Files: `forge-ai/src/main/java/forge/ai/simulation/GameCopier.java`,
`forge-gui-desktop/src/test/java/forge/ai/simulation/GameCopierBattleboxFidelityTest.java` (new).

**Verdict: GameCopier did NOT correctly copy Battlebox shared zones — confirmed the plan's #1
predicted hazard — and has now been fixed.**

**What the harness does:** builds a real 4-player Battlebox Monarch mid-game-shaped `Game`
(`GameRules(GameType.Constructed)` + `addAppliedVariant(GameType.Battlebox)`, 4 `RegisteredPlayer`s),
wires real `SharedPlayerZone` instances for Library/Command/Graveyard the same way
`Match.prepareBattleboxSharedLibrary/Command/Graveyard` do it (see
`forge.game.MatchBattleboxSharedZoneTest` for the established convention this harness follows),
populates all zones with real named cards owned by different players, adds a commander (exercises
`Player.addCommander`/`createCommanderEffect`), gives one player +1/+1 counters on a permanent,
sets monarch (moved hands once), and sets distinct life totals + turn/phase via `devModeSet`. It
then calls `GameCopier.makeCopy()` and asserts a structural snapshot (zone contents by name as a
multiset, per-player life totals, monarch holder, counter counts, commander flag/owner, and —
critically — *whether the shared zone is still the same object instance across all 4 players in
each game*) matches between original and copy.

Note on test-setup style: the fixture is built directly via Game/Player/Card APIs rather than
by driving a live turn-by-turn game loop from a real Battlebox deck (`BattleboxConfig`/land-station
decklists) — this exercises the exact same `GameCopier` code paths with far less flakiness, and
matches the existing convention `forge.game.MatchBattleboxSharedZoneTest` already uses for testing
this exact shared-zone subsystem. Documented as a deliberate choice, not a shortcut of convenience.

**Bug found (thrown or silent? SILENT — the actual failure mode the plan's risk section warned
about):** `GameCopier.makeCopy()` clones each `RegisteredPlayer`/`Player` independently and never
re-establishes `SharedPlayerZone` linkage for the copy. `Player.getZone(ZoneType)` falls back to a
private per-player zone whenever `sharedLibraryZone`/`sharedCommandZone`/`sharedGraveyardZone` is
null, and the copy's freshly-constructed `Player`s all start with those fields null. Net effect:
`addCard()`'s `zoneOwner.getZone(zone).add(newCard)` silently routed every shared-zone card into
each card's individual *owner's* now-private zone instead of one zone shared by all 4 players —
no exception, wrong game state. Verified directly: before the fix, the harness's
"is Library shared across all 4 players in the copy" check was `false` (it's `true` in the
original) while the copy's card-name contents still matched by coincidence (all library cards
happened to route to the correct-looking zone per owner in this particular fixture) — i.e. this
is exactly the "an incorrect silent copy... will NOT throw" failure mode flagged in the plan's §6.

**Fix applied (small, contained to `GameCopier.java`):** new `copySharedZones(Game)` /
`copySharedZoneIfPresent(Game, ZoneType)` private methods, called after the new `Player`s are
constructed and player-mapped but before `copyGameState()` (which is what actually invokes
`addCard()`). For each of Library/Command/Graveyard: groups the *original* players by the
identity of the `PlayerZone` instance they use for that zone type (an `IdentityHashMap`); any
group with 2+ members is treated as an actual shared zone, and a fresh `SharedPlayerZone` is
constructed for the copy and wired onto the corresponding mapped new players via the existing
public `setSharedLibraryZone`/`setSharedCommandZone`/`setSharedGraveyardZone` setters — mirroring
exactly what `Match.prepareBattleboxSharedLibrary/Command/Graveyard` do for a real game start,
minus the `BattleboxConfig`-driven population (cards are populated by the existing `addCard()`
loop right after). Non-Battlebox (2-player Constructed) copies are unaffected: every group has
size 1, so the loop body never runs and no `SharedPlayerZone` is created — this only activates
when a real shared zone existed in the original game.

**Verified:** new harness test passes 2/2 after the fix (`testGameCopierPreservesBattleboxShared-
ZonesMonarchAndCounters`, `benchmarkGameCopierThroughputOnBattleboxMidGameState`). Monarch holder,
per-player life totals, +1/+1 counter count, and commander flag/owner all matched even before the
fix — only the shared-zone-identity check failed; the fix does not touch those paths and they
remain green. Full existing `forge.ai.simulation.*` suite (`GameSimulationTest`,
`SpellAbilityPickerSimulationTest`) plus `forge.ai.ultron.UltronPlayerControllerTest` — 210/210
pass, no regression from the `GameCopier` change. Baseline `forge.ai.llm.runtime.Ultron*` suite —
34/42 pass, same 8 pre-existing "Ahead-state ..." failures as TICKET-V3-005, unchanged.

**P2.2 data point (copies/sec, single thread, same 4p Battlebox mid-game fixture, 200 iterations
after 20-iteration warm-up):** **~97-106 copies/sec** (two runs: 105.7/sec, 97.1/sec) on this box —
comfortably above the plan's §6 budget target of ≥30/sec, and above the §7 P2.5/Phase 5
stretch-goal threshold of ~50/sec too. This is a single fixed fixture (not turn-1/8/15 as the full
P2.2 ticket calls for) — a real P2.2 session should still benchmark across game-progress stages,
but this number says search-based simulation is very unlikely to be throughput-blocked on this
hardware.

**Recommendation for Phase 2 scoping:** proceed as planned. The gating risk (GameCopier fidelity)
is resolved, not just narrowed-around — Battlebox multiplayer simulation can now be built on top of
`GameCopier`/`GameSimulator` directly rather than needing the "purpose-built lightweight combat
model" fallback the plan's §10 risk-mitigation contemplated. Recommend the next Phase 2 session
start with **P2.3 (multiplayer interim evaluator)** since `GameStateEvaluator`'s 2-player-centric
TODO is the next-most load-bearing gap, now that copies of the state it evaluates are trustworthy.
A full P2.2 benchmark (turn-1/8/15 states, possibly multi-thread) remains open but is now a
nice-to-have data point rather than a gate, given the margin above 30/sec.

**Residual/out-of-scope for this session (left for later Phase 2 sessions per this session's
instructions):** did not touch `GameStateEvaluator` (P2.3), `SpellAbilityPicker`/`Plan` main-phase
routing (P2.4), combat/block enumeration (P2.5), or stack-response simulation (P2.6). Did not run
any `run_parallel.sh`/`run_simstats.sh` batch — the `v3_control_default_4p` control run
(`ultron_v3_control` tmux session, 2 workers) was active throughout this session and was left
undisturbed.

### TICKET-V3-202: Multiplayer interim evaluator (P2.3) [DONE 2026-07-04]
Files: `forge-ai/src/main/java/forge/ai/simulation/GameStateEvaluator.java`,
`forge-gui-desktop/src/test/java/forge/ai/simulation/GameStateEvaluatorMultiplayerTest.java` (new).

**What changed:** fixed the literal `// TODO: more than 2 players` gap in
`getScoreForGameStateImpl` — three changes, all interim/hand-tuned per the plan (Phase 3's learned
value function replaces this file's constants entirely; nothing here is meant to be precise).

1. **Per-opponent life term, not averaged.** The old code summed every opponent's life and divided
   by `(players.size() - 1)`, so "one opponent at 20 life, two at 5" (sum 30, avg 10) scored
   identically to "three opponents at 10 life each" (sum 30, avg 10) — the AI literally could not
   tell a concentrated table leader from an evenly matched table. Replaced with: collect each alive
   opponent's life, then combine as `0.65 * maxLife + 0.35 * avgLife` (still multiplied by the
   existing `2` weight) — the single highest-life (hardest-to-kill) opponent now dominates the term
   without fully hiding the rest of the table. 65/35 is a hand-picked split, not derived from data;
   it's an interim heuristic. Board-state threat per opponent (creature/permanent value) did *not*
   need a separate per-opponent term — the existing per-card battlefield loop already scores every
   enemy permanent individually (never averaged), so only the life computation needed fixing.
2. **Monarch scoring** (previously absent entirely). New `MONARCH_VALUE = 8` constant: `+8` if
   `aiPlayer` holds `game.getMonarch()`, `-8` if a (non-eliminated) opponent holds it, `0` if no
   monarch in play. Chosen as ~1.5x the file's existing "card in hand" unit (`5 * myCards` above:
   `5 * 1.5 = 7.5`, rounded up to `8`) — reasoning: holding Monarch guarantees a recurring extra
   draw each of your turns (worth more than one static card in hand) but it's contestable via
   combat (an opponent can take it away), so it stays far below the hundreds-scale
   board-development terms (`evalCard`/`evaluateLand`) elsewhere in the file.
3. **Dead/eliminated player handling.** `aiPlayer.getOpponents()` does not filter by `hasLost()` —
   a defeated player's stale life total was previously still summed into the (now removed)
   average. Fixed by skipping any opponent with `hasLost() == true` before collecting alive-opponent
   life, and defensively checking `!monarch.hasLost()` before scoring the Monarch term (the engine
   clears `game.getMonarch()` to `null` when the holder loses — see `Game.java`'s onLoseGame path —
   but TICKET-V3-201 already found one silent `GameCopier` state-copying bug this session's plan
   builds on, so the extra guard costs nothing). No NPE risk was found in the battlefield loop
   itself (eliminated players' permanents leave the battlefield through normal game rules), so the
   only actual bug was the life-total corruption.

**Verified:** new `GameStateEvaluatorMultiplayerTest` (3 tests, all in `PhaseType.MAIN2` with empty
battlefields so `simulateUpcomingCombatThisTurn` short-circuits and the direct scoring path is
exercised):
  - `testConcentratedOpponentThreatScoresWorseThanEvenTable` — proves the old averaging bug is
    actually fixed, not just recompiled: a table with opponent life 20/5/5 (leader) scores strictly
    worse for the AI than 10/10/10 (even), same sum/avg, and asserts the exact expected delta from
    the documented 65/35 formula so a silent constant drift would fail loudly.
  - `testAiHoldingMonarchScoresHigherThanOpponentHoldingIt` — AI-holds > no-monarch > opponent-holds,
    with the AI-vs-opponent delta asserted to equal exactly `2 * MONARCH_VALUE`.
  - `testEliminatedOpponentDoesNotCrashOrCorruptScore` — an opponent eliminated via
    `loseConditionMet(GameLossReason.LifeReachedZero, ...)` with a stale life total of 2 produces
    the *identical* score to a reference game where that opponent doesn't exist at all (i.e., fully
    excluded, not just "didn't crash").

Full `forge.ai.simulation.*` package + `forge.ai.ultron.UltronPlayerControllerTest` (TestNG groups
all classes into one `TestSuite` aggregate rather than per-class) — **215/215 pass, 0 failures, 0
errors** (breakdown: `GameCopierBattleboxFidelityTest` 2, `GameSimulationTest` 69,
`GameStateEvaluatorMultiplayerTest` 3 new, `SpellAbilityPickerSimulationTest` 135,
`UltronPlayerControllerTest` 6 — no regressions, net +3 new tests vs. the TICKET-V3-201 baseline).
Baseline `forge.ai.llm.runtime.Ultron*` suite — **34/42 pass**, identical 8 pre-existing
"Ahead-state ..." failures as TICKET-V3-005/TICKET-V3-201, unchanged (this suite doesn't touch
`forge.ai.simulation.GameStateEvaluator` at all — confirmed no other live AI path calls into this
class besides the dormant `USE_SIMULATION`-gated simulation controller; `forge.ai.llm.runtime.
UltronGameStateEvaluator` is an unrelated, separate v2 evaluator class that this ticket did not
touch). `mvn -pl forge-ai,forge-gui-desktop -am clean package -DskipTests -q` succeeds. Control run
(`v3_control_default_4p`, PIDs unchanged) confirmed still running throughout this session, left
undisturbed.

**Risks/follow-ups for the next Phase 2 session (P2.4 main-phase, P2.5 combat, P2.6 stack
response):**
  - The 65/35 opponent-weighting split and the `MONARCH_VALUE = 8` constant are both hand-picked,
    not tuned against real game outcomes — expect Phase 3's learned value function to override both
    once training data exists; don't invest further tuning effort here.
  - This evaluator still treats board-development value (`evalManaBase`, `evalCard`) as a single
    aiPlayer-vs-everyone-else subtraction rather than per-opponent — reasonable for now since P2.4's
    main-phase routing and P2.5's combat enumeration will be the actual decision-making consumers of
    per-opponent granularity; this evaluator only needed to stop the two specific gaps the plan
    named (life averaging, missing monarch).
  - Not investigated: whether `GameStateEvaluator`'s single aiPlayer-perspective scoring composes
    correctly when P2.5's block-assignment enumeration needs to score a state from a *different*
    seat's perspective mid-search (the method already takes `aiPlayer` as a parameter, so it should,
    but this session only exercised it from one seat's perspective in tests).

### TICKET-V3-203: Main-phase decisions via SpellAbilityPicker/Plan (P2.4) [DONE 2026-07-04]
Files: `forge-ai/src/main/java/forge/ai/ultron/UltronPlayerController.java`,
`forge-gui-desktop/src/test/java/forge/ai/ultron/UltronMainPhaseSimulationTest.java` (new).

**What changed:** `UltronPlayerController.chooseSpellAbilityToPlay()` no longer delegates straight
to `super`. It now calls `getAi().getSimulationPicker().chooseSpellAbilityToPlay(null)` — reusing
the exact `SpellAbilityPicker` instance `AiController`'s constructor always builds (regardless of
the legacy `useSimulation`/`AIOption.USE_SIMULATION` flag; see `AiController#simPicker`), so this
is the same object/state a 2-player lobby-flag game would have used, not a duplicate picker with
divergent `Plan` state. A `RuntimeException` from the simulation path falls back to `super`'s
inherited behavior and is recorded as `answeredBy=inherited`, so telemetry never lies about an
exception-driven fallback. This is the first of `UltronPlayerController`'s 114 overridden methods
to move off the Phase 1 baseline: coverage is now **1/114 Ultron-answered** (up from 0/114).

**Investigation into the plan's predicted single-opponent landmine (task instructions specifically
flagged `getOpponent()` singular as a thing to hunt for): NOT FOUND where expected, but a related,
real gap WAS found one layer down.** `grep -rn "\.getOpponent(" forge-ai/src/main/java/forge/ai/
simulation/` returns zero hits — `SpellAbilityPicker.java` and `Plan.java` never reference any
opponent at all (`Plan` is pure decision-sequence bookkeeping; `SpellAbilityPicker` only reasons
about the AI player's own hand/candidates and game-state scores). `GameCopier`/`GameSimulator`
already use `Player.getWeakestOpponent()`, not the singular `getOpponent()`, everywhere an opponent
reference is needed — so the specific landmine shape named in this session's instructions does not
exist in this machinery today (P2.1/P2.3's prior sessions evidently already steered clear of it, or
it was never there). **What IS a real gap, found while checking `resolve` handling in
`GameSimulator.simulateSpellAbility`:** `GameSimulator.java:227-230` (`// TODO: Support multiple
opponents.`) resolves the stack after simulating "play this spell" by assuming only the single
`getWeakestOpponent()` can respond — a stronger opponent's actual available interaction (removal,
counterspells, combat tricks) is never modeled during that one-ply main-phase lookahead. In 4-player
Battlebox this means the simulated score for "if I play X" is systematically optimistic about how
safe X is from the *other* two opponents, not just the weakest one. **Left unfixed, per this
session's instructions on deeper-redesign items:** properly modeling "which of 3 possible opponents
would actually have and use an answer" needs either a determinized per-opponent response model (this
is exactly the belief-state/determinization machinery Phase 4 already plans to build,
`UltronBeliefState`/§7 P4.2) or at minimum enumerating worst-case response across all opponents
instead of one — either is a real design decision, not a small contained fix, so it is documented
here rather than patched. **Practical impact today:** does not produce illegal or crashing output
(confirmed by this session's tests below) — it is a fidelity/optimism gap in the *score* a candidate
spell receives, not a correctness bug in the decision path. Recommend Phase 3/4 sessions treat this
TODO as a known input to the value-function/belief-state work rather than a standalone bug ticket.

**Verified — real 4p Battlebox states, not just unit-level mocking.** New
`UltronMainPhaseSimulationTest` (3 tests) builds a 4-player Battlebox-variant game with real
`SharedPlayerZone`s (library/command/graveyard), distinct per-player life totals, and a
non-Ultron-held monarch — mirroring `GameCopierBattleboxFidelityTest`'s fixture-building
convention — across 3 distinct board states:
  - `testMainPhasePlaysAvailableLandWithoutCrashing` — hand has only a land; asserts the pick is
    `answeredBy=ultron` (not a fallback) and, if non-null, is the land.
  - `testMainPhasePicksLegalPlayWithManaAndCreatureInHand` — 2 untapped Forests + Grizzly Bears in
    hand, with opponents holding their own creatures (Runeclaw Bear, Grizzly Bears) so
    `GameStateEvaluator`'s per-opponent scoring and `GameCopier`'s shared-zone copying are actually
    exercised during the simulation, not a 1-player-only board; asserts legal pick (land or the
    only castable creature) or null, never a fallback.
  - `testMainPhaseReturnsNullRatherThanCrashingWithNoLegalPlay` — empty hand/no mana; asserts a
    `null` result (pass priority) is reached via the simulation path itself, not treated as an
    exceptional/fallback condition — matching the pre-existing 2-player `USE_SIMULATION` semantics
    where `null` from the picker is a legitimate "no beneficial play" signal.

All 3 pass. Full `forge.ai.simulation.*` + `forge.ai.ultron.*` aggregate `TestSuite` —
**218/218 pass** (215 prior baseline + 3 new, 0 failures, 0 regressions). Baseline
`forge.ai.llm.runtime.Ultron*` suite — **34/42 pass**, identical 8 pre-existing "Ahead-state ..."
failures as TICKET-V3-005/201/202, unchanged (this test package doesn't touch
`forge.ai.simulation.*`/`forge.ai.ultron.*` at all). `mvn -pl forge-ai,forge-gui-desktop -am clean
package -DskipTests -q` succeeds. Control run (`ultron_v3_control` tmux session, `v3_control_
default_4p` shard_0/shard_1 PIDs 643469/643495) confirmed still running throughout this session,
untouched — no `run_parallel.sh`/`run_simstats.sh`/batch run was launched; only `mvn test` (unit
tests) ran.

**Scope note:** combat (`declareAttackers`/`declareBlockers`, P2.5) and stack-response (P2.6) paths
were explicitly not touched this session, per instructions — both still delegate straight to
`super` and remain 100% inherited. Recommended next session: **P2.5 combat** — enumerate plausible
attacker subsets (singleton/all-in/threat-model-suggested per plan §7 P2.5), simulate through
`COMBAT_DAMAGE`, score with the now-multiplayer-aware `GameStateEvaluator`. Same `GameSimulator`
stack-resolution caveat found in this session (single weakest-opponent response modeling) will
likely also matter for combat-trick simulation during block evaluation — worth checking early in
that session rather than rediscovering it.

### TICKET-V3-204: Combat via simulation (P2.5) [DONE — attacker declaration; blocker declaration completed as TICKET-V3-205, 2026-07-04]
Files: `forge-ai/src/main/java/forge/ai/ultron/UltronPlayerController.java`,
`forge-ai/src/main/java/forge/ai/ultron/UltronDecisionTelemetry.java`,
`forge-gui-desktop/src/test/java/forge/ai/ultron/UltronCombatSimulationTest.java` (new).

**Scope actually completed:** `declareAttackers` now runs a pruned-candidate simulation search
instead of delegating to `AiAttackController` via `super`. `declareBlockers` was **not** attempted
this session — the attacker-side work plus the multiplayer-combat investigation (task-mandated,
see below) filled the session; block enumeration is left for the next Phase 2 session, described
under Recommendations.

**Candidate generation (pruned, not full 2^N enumeration, per plan §7 P2.5's own "singleton ±
all-in ± threat-model-suggested sets" wording):**
1. Attack with nothing — always evaluated as the honest baseline.
2. All legal attackers vs. `AiAttackController.choosePreferredDefenderPlayer` — reuses the default
   AI's own opponent-targeting heuristic rather than reinventing it; P2.5 is about *which
   creatures* attack, not rebuilding opponent selection.
3. "Survivors only" vs. the same preferred defender — a rough evasion/toughness heuristic
   (attacker unblockable by anything the defender controls, or tougher than every creature that
   could actually block it). Deliberately not full combat math (no tricks/first-strike/multi-block
   awareness) — a pruning heuristic, not an outcome predictor; the simulation itself scores the
   real outcome.
4. All legal attackers vs. the single lowest-life alive opponent, when that differs from the
   preferred defender — a cheap "threat-model-suggested" variant.

Duplicate candidates (identical attacker-set + defender) are deduplicated before scoring, so this
session's fixtures typically evaluated 2-4 unique candidates, comfortably inside the plan's "3-6
candidates" target.

**Simulation mechanism:** for each candidate, `GameCopier` copies the game at the current (still
building, empty) `Combat` state — `GameCopier` already copies a non-null `PhaseHandler` combat
object via `Combat`'s `(Combat, IEntityMap)` copy constructor (confirmed by reading `GameCopier.
makeCopy`, line ~170) — the candidate's attacker/defender pairs are added directly to the *copy's*
`Combat` object (no controller re-entry: this is a hard requirement, see the recursion note below),
and the state is scored via `GameStateEvaluator.getScoreForGameState`, which *already* internally
drives the copy through `DECLARE_BLOCKERS` and `COMBAT_DAMAGE` via its pre-existing
`simulateUpcomingCombatThisTurn` before scoring (no new combat-advancement code was needed — this
reuses TICKET-V3-202/203-verified machinery directly). The highest-`Score.value` candidate's
assignments are applied to the real `combat` argument via `combat.addAttacker(...)`.

**Task-mandated check: does the P2.4-discovered single-weakest-opponent landmine leak into combat?
Answer: partially, and the part that matters most does NOT have the bug.** Read
`PhaseHandler.declareAttackersTurnBasedAction`/`declareBlockersTurnBasedAction` closely (not just
grepped) before writing any code, per this session's instructions. Finding:
- **Attack/block declaration itself is NOT affected.** `declareBlockersTurnBasedAction` loops
  `p = getNextPlayerAfter(p)` over every attacked defending player and calls
  `whoDeclaresBlockers.getController().declareBlockers(p, combat)` — i.e. **each defending player's
  own controller** decides its own blocks. In a 4-player Battlebox game where three different
  opponents each have creatures, attacking each of them correctly consults *that* opponent's board,
  not a single "weakest opponent" stand-in. This is the multiplayer combat correctness the plan's
  §6 risk section worried about, and it turns out to already be correct — `PhaseHandler`'s
  turn-based-action loop was never opponent-count-limited, only `GameSimulator`/`GameStateEvaluator`'s
  own helper functions were.
- **The landmine does still exist one layer down**, in the exact place TICKET-V3-203 already found
  it: `GameStateEvaluator.simulateUpcomingCombatThisTurn` drives its copy via
  `GameSimulator.resolveStack(gameCopy, aiPlayer.getWeakestOpponent())` — so any *triggered-ability
  choice* needed while combat-phase triggers resolve (not the block/attack declarations themselves,
  which are unaffected as above) is answered using only the weakest opponent's controller context.
  Same shape of gap as TICKET-V3-203's main-phase finding, correctly **not fixed here** for the
  same reason: modeling "which of N opponents would actually respond to a mid-combat trigger" is
  the belief-state/determinization work Phase 4 already plans (`UltronBeliefState`/§7 P4.2), not a
  small contained fix. Documented as a known input to that future work. Practical impact: no
  crashes/illegal states (verified by this ticket's tests); a possible narrow optimism gap in a
  candidate's score only when a mid-combat trigger specifically needed a non-weakest opponent's
  choice — most combat-relevant decisions are the block/attack declarations themselves, which this
  session confirmed are unaffected.
- **Regression-style proof, not just code-reading:** `testMultiplayerCombatConsidersNonWeakestOpponentsBlocker`
  builds a 4-player state where seat 1 is the lowest-life ("weakest") opponent with *no* creatures,
  and seat 2 (higher life, not weakest) holds the only blocker in the game. Attacking seat 2 scores
  strictly *lower* than an otherwise-identical control state where seat 2 has no blocker — proving
  seat 2's own board was actually consulted during simulated combat resolution, not silently
  dropped in favor of the weakest opponent's (empty) board.

**Recursion-safety note (found while designing the simulation mechanism, worth flagging for the
next session before attempting P2.5's blocker side):** in a self-play/mirror game where a defending
opponent is *also* running an `UltronPlayerController`, `GameCopier`'s `clonePlayer` reuses the same
`LobbyPlayerAi` (and thus the same AI profile) for the copy, so the copy's phase machinery would
construct a **fresh `UltronPlayerController`** for that opponent. Because `declareBlockers` was
*not* given simulation logic this session (still inherited/`super`), no recursion is possible today
— but the moment a future session makes `declareBlockers` simulation-based, an opponent's simulated
block decision inside *this* ticket's attacker-side simulation would itself trigger a full nested
`GameCopier`+`GameStateEvaluator` search. Bounded (one level, small candidate counts, ~100
copies/sec per TICKET-V3-201), not exponential, but worth budgeting for explicitly rather than
discovering via a slow test.

**Verified — real 4-player Battlebox states.** New `UltronCombatSimulationTest` (4 tests):
  - `testDeclareAttackersReturnsLegalSubsetWithoutCrashing` — sanity + coverage: simulation-based
    declaration doesn't crash/fall back on ordinary 4p board state; every declared attacker is
    actually controlled by the attacking player; `candidateCount`/`chosenScore` telemetry detail is
    recorded (see telemetry change below).
  - `testProfitableUnblockableAttackIsChosen` — a risk-free unblockable flyer (2-player-shaped
    fixture, to keep the profitable/bad-attack proof isolated from the multiplayer life-averaging
    interaction that the dedicated multiplayer test below exercises on purpose) is attacked with.
  - `testBadTradeAttackIsDeclined` — a 2/2 attacking into a guaranteed-available 6/4 blocker on
    every possible opponent (no benefit either way) is declined regardless of which opponent the
    preferred-defender/weakest-opponent heuristics would have picked.
  - `testMultiplayerCombatConsidersNonWeakestOpponentsBlocker` — the multiplayer-correctness proof
    described above.

Full `forge.ai.simulation.*` + `forge.ai.ultron.*` aggregate `TestSuite` — **222/222 pass** (218
prior baseline + 4 new, 0 failures, 0 regressions). Baseline `forge.ai.llm.runtime.Ultron*` suite —
**34/42 pass**, identical 8 pre-existing "Ahead-state ..." failures as TICKET-V3-005/201/202/203,
unchanged. `mvn -pl forge-ai,forge-gui-desktop -am clean package -DskipTests -q` succeeds. Coverage:
**2/114 methods now Ultron-answered** (`chooseSpellAbilityToPlay` from P2.4, `declareAttackers` from
this ticket), up from 1/114. Control run (`ultron_v3_control` tmux session, `v3_control_default_4p`
shard_0/shard_1 PIDs 643469/643495, unchanged from session start) confirmed still running throughout
this session at 422/500 combined games by session end — left completely undisturbed; only `mvn test`
(unit tests) ran, no `run_parallel.sh`/`run_simstats.sh`/batch run was launched.

**Telemetry change:** `UltronDecisionTelemetry` gained `recordDetail(String, Map<String,Object>)` /
`getLastDetail(String)` — a cheap "most-recent detail snapshot per method" (not a history), used
here to carry `declareAttackers`' `candidateCount`/`chosenScore` per the task's "if cheaply
available" instruction. Surfaced in `toMap()`'s per-method JSONL output under `lastDetail` when
present. Purely additive; does not change `record()`'s existing counters/coverage-ratio semantics.

**Not attempted this session (honest scope note):** `declareBlockers` (P2.5's stretch goal) remains
100% inherited — the attacker-side implementation plus the mandated multiplayer-combat
investigation filled the session. Recommended next-session approach for blocker declaration: reuse
the same copy-and-score mechanism (it composes directly — `Combat.addBlocker` instead of
`addAttacker`, scored the same way), enumerate a similarly small candidate set (no blocks /
block-the-biggest-threats / chump-if-lethal-is-on-the-table / clean-trade blocks) for the *actual*
attackers being faced, and budget explicitly for the recursion-safety note above before shipping it
(a quick copies/sec sanity check with a mirrored-Ultron opponent would be cheap insurance).

**Recommended next steps for Phase 2 (superseded by TICKET-V3-205 below):** ~~either (a) attempt
`declareBlockers` in a dedicated session using the plan above, or (b) treat P2.5 as functionally
complete...~~ — option (a) was taken this session.

---

### TICKET-V3-205: Block declaration via pruned simulation search (P2.5 continuation) [DONE, 2026-07-04]
Files: `forge-ai/src/main/java/forge/ai/ultron/UltronPlayerController.java`,
`forge-gui-desktop/src/test/java/forge/ai/ultron/UltronCombatSimulationTest.java` (extended, +4 tests).

**Scope:** `declareBlockers` now runs the pruned-candidate simulation search TICKET-V3-204 left as
the recommended next step, mirroring `declareAttackers`'s architecture exactly
(`GameCopier`-copy → apply candidate directly to the copy's `Combat` → `GameStateEvaluator.
getScoreForGameState` → apply winning candidate to the real `Combat`).

**Candidate generation (pruned, per the plan's own "(a) no blocks, (b) prevent lethal only, (c)
block the biggest threat(s) for value, (d) block everything blockable if clearly correct"; 2-4
unique candidates after dedup in this session's fixtures, comfortably inside the "3-5 candidates"
target):**
1. No blocks — always the honest baseline.
2. Lethal-prevention chumps — if total unblocked incoming damage from this defender's attackers
   would be lethal, greedily chump/trade the biggest attackers with the *cheapest* available legal
   blocker (lowest power+toughness) until incoming damage drops below the defender's life. A no-op
   (empty list) when not lethal — naturally deduplicates against candidate 1, no special-casing
   needed.
3. Value blocks on the biggest threat(s) — for up to the two highest-power attackers, assign a
   blocker only if it's a clean kill (blocker's power kills the attacker; blocker itself survives).
   Chumps/even-trades deliberately excluded — this candidate is "kill it for free" specifically.
4. Block everything with a favorable outcome — greedily assign a legal blocker to every attacker
   that has one, clean kills first, even trades (both die) second; attackers with no non-bad block
   stay unblocked. The "all trades favorable" case from the plan's wording.

Deliberately not full combat math (no first strike/deathtouch/multi-block/trick-awareness) — same
spirit as `declareAttackers`'s `filterLikelySurvivors`; these are pruning heuristics, the simulation
scores the real outcome.

**A real, previously-undiscovered bug found and fixed while building this:** applying a candidate's
blocks directly to the copy's `Combat` (bypassing controller re-entry, exactly like the attacker
side) leaves each `AttackingBand`'s `blocked` flag `null` — that flag is normally only set by
`PhaseHandler.declareBlockersTurnBasedAction`'s post-loop call to `Combat.
fireTriggersForUnblockedAttackers`, which never runs for a directly-mutated copy (the phase-skip
mechanism explained below means that turn-based action is never re-invoked). A null flag crashes
combat-damage assignment (`AttackingBand.isBlocked()` unboxed without a null check) deep inside
`GameStateEvaluator`'s own nested advance. Fixed by explicitly setting `combat.setBlocked(attacker,
!combat.getBlockers(attacker).isEmpty())` for every attacker in the copy right after applying a
candidate's blocks, before scoring — cheap, and correct for already-declared bands copied from
other defending players too (their `blocked`/`blockedBands` state is preserved by `GameCopier`
verbatim, confirmed by reading `Combat`'s copy constructor directly). This is the same shape of
"machinery gap that only bites once you actually drive it," not caught in TICKET-V3-204 because that
ticket never added blockers directly to a copy's `Combat`.

**The recursion question — resolved precisely, per this session's mandate, by reading the code
rather than assuming:**

- **Does `GameCopier` preserve real controller classes for simulated opponents? Yes, confirmed.**
  `GameCopier.clonePlayer` (line ~210) reuses the *exact same* `LobbyPlayerAi` instance for any
  player whose real `LobbyPlayer` is already a `LobbyPlayerAi` (true for every AI seat, Ultron
  included) — it only constructs a new one for non-AI lobby players. Since `LobbyPlayerAi.
  createControllerFor` decides `UltronPlayerController` vs. plain `PlayerControllerAi` purely from
  that (unmodified-by-cloning) instance's `aiProfile` field, every copied game builds a **fresh
  `UltronPlayerController`** for any player whose real seat runs the Ultron profile.
- **Does that fresh controller actually get invoked mid-simulation, or is it dead code in the
  copy? Confirmed invoked, by tracing the actual call path, not just the class wiring.** Both
  `declareAttackers`'s and `declareBlockers`'s candidate-scoring copy the game with
  `advanceToPhase=null`, so the copy's `PhaseHandler` phase is set via `devModeSet` (which does
  *not* run phase-begin side effects) to whatever phase the source game/copy was already sitting
  at. `GameStateEvaluator.simulateUpcomingCombatThisTurn` then makes a *second-level* copy of that
  and calls `devAdvanceToPhase(COMBAT_DAMAGE, ...)`; because `devAdvanceToPhase`'s loop treats the
  starting phase as already-current (its first action is `onPhaseEnd()` for the current phase, not
  re-running its turn-based action), the **next** phase strictly after the starting one is the
  first whose turn-based action actually executes. For a `declareAttackers` candidate (copy starts
  at `COMBAT_DECLARE_ATTACKERS`), that next phase is `COMBAT_DECLARE_BLOCKERS`, whose
  `declareBlockersTurnBasedAction` unconditionally calls `whoDeclaresBlockers.getController().
  declareBlockers(p, combat)` for every defending player — including a freshly-constructed
  `UltronPlayerController` if that seat runs the Ultron profile. Concretely: evaluating an attack
  candidate against an Ultron-controlled defender now (post-TICKET-V3-205) triggers that defender's
  full pruned-candidate block search, nested one level inside the outer attacker search.
- **Same phase-skip mechanism means a controller can never recurse into *itself*** — a
  `declareBlockers` candidate's own internal `GameStateEvaluator` call makes its own second-level
  copy starting at `COMBAT_DECLARE_BLOCKERS` (already-current), so the next phase processed is
  `COMBAT_FIRST_STRIKE_DAMAGE`/`COMBAT_DAMAGE`, never `COMBAT_DECLARE_BLOCKERS` again. This bounds
  any single controller's own nested search to exactly one level regardless of how deep the outer
  search's own machinery goes — the risk is strictly cross-controller (a *different* Ultron seat
  reached mid-combat), never self-recursion.
- **The guard, given the above is real:** `UltronPlayerController.SIMULATION_IN_PROGRESS`, a
  `ThreadLocal<Boolean>` (an instance field would not work — the nested call lands on a *different*
  controller instance, the fresh one constructed for the copy, not `this`). Set true for the
  duration of `declareBlockers`'s own simulation search (try/finally, cleared even on exception);
  checked at the top of `declareBlockers`. If a nested call arrives while already true, it skips
  the simulation search entirely and falls straight to `super.declareBlockers` (recorded as
  `answeredBy=inherited`, never lying to telemetry about the fallback). This bounds recursion to
  one level no matter how many Ultron seats a self-play game has, and stops nested-simulation cost
  from compounding across every candidate the outer search evaluates. `declareAttackers` itself
  needs no analogous guard — per the phase-skip analysis above, nothing mid-combat-simulation ever
  calls a *different* player's `declareAttackers` (only the active/turn player is ever asked to
  declare attackers, and that phase is always already-current/skipped in every nested copy this
  ticket traced).

**Verified — real 4-player Battlebox states.** `UltronCombatSimulationTest` gained 4 tests (8 total
in the file now):
  - `testDeclareBlockersReturnsLegalWithoutCrashing` — sanity + coverage: doesn't crash/fall back on
    an ordinary 4p state; every declared blocker is actually controlled by the defending player;
    `candidateCount`/`chosenScore` telemetry detail recorded.
  - `testProfitableCleanKillBlockIsChosen` — an available 6/4 blocking a 2/2 (clean kill, zero risk)
    is taken.
  - `testBadBlockIsDeclined` — a precious 2/2 declines to chump-block a 6/4 with plenty of life and
    no lethal pressure.
  - `testUltronVsUltronBlockSimulationDoesNotRecurseUnbounded` — **the mandatory recursion-safety
    proof.** 4-player Battlebox game, seats 0 *and* 1 both running `UltronPlayerController`. Seat 0
    attacks seat 1 (who has a blocker), forcing the outer `declareAttackers` candidate-scoring
    simulation to construct a fresh `UltronPlayerController` for seat 1 inside the copy and invoke
    its `declareBlockers` per the mechanism traced above. Asserts (1) the outer call completes in
    under 30s with a normal candidate count (proving the guard prevents runaway/unbounded nesting,
    not just "didn't crash"), and (2) immediately afterward, seat 1's own *real*, non-nested
    `declareBlockers` call still runs the full Ultron simulation path (`answeredBy=ultron`) —
    proving `SIMULATION_IN_PROGRESS` resets correctly and doesn't leak "stuck true" state across
    calls on the same thread.

Full `forge.ai.simulation.*` + `forge.ai.ultron.*` aggregate `TestSuite` — **226/226 pass** (222
prior baseline + 4 new, 0 failures, 0 regressions). Baseline `forge.ai.llm.runtime.Ultron*` suite —
**34/42 pass**, identical 8 pre-existing "Ahead-state ..." failures, unchanged.
`mvn -pl forge-ai,forge-gui-desktop -am clean package -DskipTests -q` succeeds. Coverage:
**3/114 methods now Ultron-answered** (`chooseSpellAbilityToPlay`, `declareAttackers`,
`declareBlockers`), up from 2/114. Control run (`ultron_v3_control` tmux session,
`v3_control_default_4p` shard_0/shard_1 PIDs 643469/643495, same PIDs as session start) confirmed
still running throughout this session (422→437/500 combined games during this session) — left
completely undisturbed; only `mvn test` ran, no batch run was launched. It had not finished by
session end, so the optional real-game Ultron-vs-Ultron smoke test via direct `SimulateStats`
invocation was not attempted (RAM headroom was also borderline: ~4-5GB free throughout, per the
task's own >2GB bar it would have been permissible once the control run finished, but it didn't).

**Recommendation for what's next:** P2.5 (combat) is now functionally complete on both sides
(attack + block declaration), verified unit-level and with a dedicated multiplayer self-play
recursion proof. Two reasonable next steps:
  (a) **P2.6 stack response** — the natural next Phase 2 item per the plan's own ordering, and it
      will need to grapple with the same weakest-opponent stack-resolution gap (`GameSimulator.
      simulateSpellAbility`'s `// TODO: Support multiple opponents.`) directly, since there's no
      "reuse the default AI's own targeting heuristic" escape hatch available the way P2.4/P2.5
      had — recommended first choice, since it's the last item needed to complete Phase 2's
      decision-surface scope before the 600-game statistical gate.
  (b) **Move toward the Phase 2 gate (≥30% over N=600 seat-rotated games, plan §7/§8)** now that
      main-phase play, attacks, and blocks are all simulation-driven — defensible if P2.6 is judged
      lower-value than getting a real statistical read on the work so far, but stack response is a
      meaningfully common decision point (counterspells, removal timing) that the gate's win-rate
      number would otherwise silently average over an unimplemented decision surface.
  Recommendation: (a) first — P2.6 is scoped small (reuses the identical GameCopier/
  GameStateEvaluator/telemetry pattern a third time) and finishes Phase 2's coverage story before
  spending 600 games' worth of compute measuring a still-partial decision surface.

---

### TICKET-V3-206: Stack response (P2.6) [DONE, 2026-07-04]
Files: `forge-ai/src/main/java/forge/ai/ultron/UltronPlayerController.java` (javadoc + telemetry
only, no new decision logic), `forge-gui-desktop/src/test/java/forge/ai/ultron/
UltronStackResponseSimulationTest.java` (new, 4 tests).

**Scope actually completed: verification + telemetry, not new decision-routing code — confirmed by
reading, not assumed.** `PhaseHandler.mainLoopStep` (`PhaseHandler.java:1078`) is the *only*
priority-pass entrypoint in the engine: `pPlayerPriority.getController().chooseSpellAbilityToPlay()`
is called identically whether it's a player's own main phase or a response window during someone
else's turn/stack. There is no separate "should I respond to the stack" override point to add.
P2.4 (TICKET-V3-203) already routed 100% of these calls through `SpellAbilityPicker`, whose
candidate generation naturally narrows to instant-speed plays when the stack is non-empty (via
`SpellAbility.isLegalAfterStack()`/timing checks in `canPlayAndPayForSim`) and always treats "pass"
as the implicit baseline (`chooseSpellAbilityToPlayImpl` only replaces `bestSa` when a candidate's
simulated score beats `origGameScore`). This session's job was therefore to *verify* that path
behaves sensibly for stack-response-shaped decisions and wire in the telemetry detail the plan
asked for — which is exactly what happened, plus one real, previously-undocumented gap found along
the way (below).

**Telemetry added:** `chooseSpellAbilityToPlay()` now records a `recordDetail` snapshot
(`stackNonEmpty`, `candidateCount`, `chosenScore`) alongside its existing `record()` call, matching
the pattern `declareAttackers`/`declareBlockers` already established in TICKET-V3-204/205.
`stackNonEmpty` is the key new signal — it lets per-game JSONL analysis distinguish main-phase
decisions from stack-response ones without any decision-routing change.

**A real, previously-undocumented gap found while building this session's verification tests
(distinct from the already-known weakest-opponent gap): responses that legally target something ON
the stack — chiefly true countermagic — can never be chosen today, full stop.** `GameCopier.
makeCopy` only preserves the actual `SpellAbilityStackInstance` queue (`game.getStack()`) when the
static `GameSimulator.COPY_STACK` flag is true, and it defaults to `false` (`GameSimulator.java:20`,
`GameCopier.java:177-178`) — that flag is only flipped on transiently inside `GameSimulator`'s own
constructor to resolve the *original* game's stack once for a baseline comparison score, never for
the copies actually used to simulate a candidate spell. So when `SpellAbilityPicker` simulates
"what if I cast Counterspell right now," the copy's `Stack` card zone still shows the opposing
spell's card (that part of `GameCopier` is unconditional), but there is no ability-stack entry for
`MultiTargetSelector` to offer `Counterspell` as a legal `TargetType$ Spell` target —
`hasPossibleTargets()` is false, no target is ever chosen, `SpellAbility.isTargetNumberValid()`
fails, `ComputerUtil.handlePlayingSpellAbility` returns `false`, and `GameSimulator.
simulateSpellAbility` unconditionally scores that candidate as `Integer.MIN_VALUE` — worse than
passing, always, regardless of how severe the countered threat actually is. **Confirmed by direct
instrumentation this session (temporary debug prints in `ComputerUtil.handlePlayingSpellAbility`
and `SpellAbilityPicker`, reverted before commit — not left in the tree), not just code-reading:** a
Counterspell candidate against an unanswered Serra Angel evaluated to exactly `MIN_VALUE` every
time; `chooseSpellAbilityToPlay()` returned `null` (declined to respond) even though countering was
obviously correct. This is a *harder* failure than the weakest-opponent gap — not a fidelity/optimism
margin, a permanent structural inability to ever counter anything — but it is **not fixed here**:
this session's constraints explicitly forbid touching `GameCopier.java`, and the real fix
(`GameCopier` unconditionally preserving the ability-stack queue, not just the zone's cards) is
squarely that file. Anything targeting the battlefield/players instead of the stack (removal,
combat tricks, burn) is unaffected and works correctly through this same path today — confirmed by
`testStackResponseKillsLethalAttackerBeforeBlockers` below.

**Verified — real 4-player Battlebox states.** New `UltronStackResponseSimulationTest` (4 tests):
  - `testStackResponseKillsLethalAttackerBeforeBlockers` — Ultron at 4 life facing an unblocked
    4-power attacker (no blockers available) holds Doom Blade; passing is lethal, killing the
    attacker survives. The pre-existing (non-Ultron-specific) `Plan`/"phase bloom" heuristic
    legitimately defers the decision to the declare-blockers priority window first (since nothing
    changes the outcome by waiting in this fixture); the test drives the phase forward to where the
    plan is waiting, exactly as the real priority loop's next pass would, and confirms Doom Blade is
    then chosen.
  - `testStackResponseDeclinesWhenNoLegalResponseExists` — stack non-empty (opponent casts
    Divination, zero board impact), Ultron's only instant has no legal target anywhere in the game
    (0 candidates) — passing is correct and reached via the simulation path, not a fallback.
  - `testCounterspellCandidateCannotBeEvaluatedDueToUncopiedStack` — regression-guarding
    documentation of the gap above: asserts *today's actual* (unfortunate) behavior — Counterspell
    against a serious, board-relevant threat still evaluates to `null` — labeled explicitly as a
    known gap to revisit/invert if `GameCopier` is ever fixed to copy the stack, not a false-positive
    "correct decline."
  - `testUltronVsUltronStackResponseDoesNotRecurseOrHang` — mandatory recursion-safety check even
    though the analysis below concludes there's no new recursion surface: a mirror game (both seats
    Ultron) with a non-empty stack completes in well under 30s.

**Recursion analysis — no new guard needed, confirmed by tracing the actual call paths (not
assumed) per this session's mandate.** Unlike `declareAttackers`/`declareBlockers` (re-entered
mid-simulation because `GameStateEvaluator.simulateUpcomingCombatThisTurn`'s `devAdvanceToPhase`
runs real combat turn-based-actions that call `getController().declareBlockers(...)` on a copy's
players — TICKET-V3-205), `chooseSpellAbilityToPlay()` is never invoked on a copied/simulated
game's players. `devAdvanceToPhase` only runs phase-transition turn-based actions (never the
interactive priority loop in `mainLoopStep`), so a copy's `chooseSpellAbilityToPlay()` is never
called that way. `GameSimulator`'s own internal recursion for "what would I play after this"
(`SpellAbilityPicker sim = new SpellAbilityPicker(simGame, aiPlayer); sim.
chooseSpellAbilityToPlay(controller)`) calls the picker object directly, never
`aiPlayer.getController()` — so it can never construct or invoke a fresh `UltronPlayerController`.
And `GameSimulator.resolveStack` (the same weakest-opponent-gap mechanism TICKET-V3-203/204/205
already documented) explicitly constructs a plain `new PlayerControllerAi(...)` for the responding
opponent rather than looking up that seat's real profile — so even that path can never reach
`UltronPlayerController`. `SIMULATION_IN_PROGRESS` is therefore correctly left unused by this
method — there is no cross-controller nesting for it to guard against here, only for the combat
overrides.

**The weakest-opponent gap (`GameSimulator.simulateSpellAbility`'s `resolveStack(simGame,
aiPlayer.getWeakestOpponent())`, TICKET-V3-203/204/205) is now directly load-bearing for
correctness, not just an optimism margin — still correctly deferred to Phase 4 per this session's
instructions, not fixed.** For P2.4's main-phase case it only affected the score of "what happens
after my own spell resolves." For stack response, the same call resolves the game state
*immediately after Ultron's own simulated response* — so a third, non-weakest opponent's
interaction (a second counterspell, a trick that changes whether Ultron's removal actually saves
it) is invisible to the simulated score. This session's decision to leave both this gap and the
newly-found stack-copy gap unfixed follows the same pattern TICKET-V3-203/204/205 established:
documented precisely, deferred to Phase 4 (or an earlier dedicated fix session for the stack-copy
one specifically, since it isn't really a "hidden information" problem the way the weakest-opponent
one is), not patched inline.

Full `forge.ai.simulation.*` + `forge.ai.ultron.*` aggregate `TestSuite` — **230/230 pass** (226
prior baseline + 4 new, 0 failures, 0 regressions). Baseline `forge.ai.llm.runtime.Ultron*` suite —
**34/42 pass**, identical 8 pre-existing "Ahead-state ..." failures, unchanged.
`mvn -pl forge-ai,forge-gui-desktop -am clean package -DskipTests -q` succeeds. Coverage note:
`chooseSpellAbilityToPlay` was already Ultron-answered since P2.4 — this session did not add a new
method to the coverage count (still 3/114: `chooseSpellAbilityToPlay`, `declareAttackers`,
`declareBlockers`), but meaningfully expanded *what* that one method's coverage represents (stack
response as well as main-phase play) and made that distinction visible via the new
`stackNonEmpty` telemetry field. Control run (`ultron_v3_control` tmux session,
`v3_control_default_4p` shard_0/shard_1, same PIDs 643469/643495 as session start) confirmed still
running throughout this session (437→461/500 combined games) — left completely undisturbed; no
`run_parallel.sh`/`run_simstats.sh`/batch run was launched this session, only `mvn test`/`mvn
package`. It had not finished by session end; RAM stayed at 3-5GB free throughout (per the task's
own >2GB bar a smoke test would have been permissible if the control run had finished, but it did
not), so no additional direct `SimulateStats` invocation was attempted.

**Phase 2 core decision-surface assessment (main-phase, attack, block, stack response all
simulation-driven): ready for the 600-game statistical gate, with one caveat worth noting rather
than blocking on.** The plan's P2.1-P2.6 scope is now functionally complete: fidelity-verified
copying (P2.1/TICKET-V3-201), copy/simulate benchmark headroom (P2.2), a multiplayer-aware interim
evaluator (P2.3/TICKET-V3-202), and all four decision points (main phase, attacks, blocks, stack
response) route through simulation with telemetry proving it (not silent fallback). The caveat: the
countermagic gap found this session means Ultron will never actually counter anything in the
600-game gate run — this understates Ultron's true ceiling (countermagic is a real, if secondary,
tool in Battlebox) but does **not** invalidate the gate as a measurement of the current
decision-surface's strength, since the gate is explicitly meant to measure what's built *now*, not a
hypothetical future version. Recommendation: proceed straight to scheduling the N=600 seat-rotated
gate run (plan §7/§8) rather than spending another session chasing the `GameCopier` stack-copy fix
first — that fix is real, valuable work, but it's better sequenced as a fast-follow after the gate
establishes a baseline number for what P2.1-P2.6 achieves today, rather than as a blocking
prerequisite. If the gate result comes back surprisingly weak, revisit this recommendation and
consider whether the counter-magic gap contributed enough to justify fixing it before Phase 3.

---

## EPIC: ULTRON-AI / CORE-RUNTIME
Fast non-LLM runtime AI layer. All under `forge-ai/src/main/java/forge/ai/llm/runtime/`.

### TICKET-001: Feature flags and config [DONE]
File: `UltronConfig.java`
Env vars for all feature toggles (ULTRON_RUNTIME_ENABLED, ULTRON_LLM_ADVISOR_ENABLED, etc.)
All default to safe values so the runtime works with zero env setup.

### TICKET-002: Core decision types [DONE]
Files: `UltronRuntimeDecision`, `UltronRuntimeRole`, `UltronScore`, `UltronDecisionLog`
Establishes the vocabulary: CHOOSE / PASS / FALLBACK / NO_DECISION roles;
AHEAD / BEHIND / STABILIZING / PRESSURING / CONTROL / COMBO_DEFENSE / DESPERATE role states.

### TICKET-003: Multiplayer threat model [DONE]
Files: `UltronThreatModel`, `UltronTableThreatSummary`, `UltronOpponentProfile`
Analyzes all opponents each turn: board value, combo threat, combat threat, commander damage,
open mana. Identifies leader, weakest, most dangerous. Rebuilt once per turn (cheap).
> HUMAN NOTE [prior session]: Commander-aware: commanderDamage >= 15 escalates lethalThreat to 80+.

### TICKET-004: Turn intent builder [DONE]
Files: `UltronTurnIntent`, `UltronTurnIntentBuilder`
Derives a cached tactical posture from the table state: role, preferred attack target, whether
to look for lethal, whether to reserve mana for counterspells, whether to avoid tapping out.
Rebuilt once per turn, invalidated when board changes materially (land plays, stack resolves).
> AGENT NOTE [2026-06-18]: PRESSURING role added when most vulnerable opponent is at <= 5 life
  and Ultron has board presence. Escalates before Desperate/Stabilizing overrides.

### TICKET-005: Stack threat analyzer [DONE]
Files: `UltronStackThreatType`, `UltronStackThreat`, `UltronStackThreatAnalyzer`
Classifies the top of stack by ApiType + card name + context. Outputs severity 0-100.
Severity 98-99 = lethal. Used by interaction policy to decide whether to counter.

### TICKET-006: Priority and interaction policies [DONE]
Files: `UltronFastPriorityPolicy`, `UltronInteractionPolicy`, `UltronManaReservation`,
       `UltronManaReservationPolicy`
9-step priority decision tree: no candidates → PASS, empty stack → defer to main-phase scorer,
own top of stack → PASS, lethal on stack → counter, board wipe when ahead → counter, etc.
Interaction policy decides whether and what to respond to on the stack.

### TICKET-007: Main-phase action scoring [DONE]
Files: `UltronActionScorer`, `UltronCandidatePruner`, `UltronTargetPriorityEvaluator`,
       `UltronGameStateEvaluator`, `UltronDecisionContext`
Scores each candidate SpellAbility with multiplayer-aware composite score:
board value, removal value, lethal pressure, leader-threat, combo-denial, tap-out risk.
Candidates pruned to maxCandidates() before scoring.
Score threshold: currently `bestScore.value > 0` to act. Low scores fall back to Forge default.
> AGENT NOTE [session-2]: Bug fixed — target.isAttacking() NPE outside combat phase.
  Added null guard: `game.getCombat() != null && target.isAttacking()`.
> HUMAN NOTE [session-2]: Ultron attack rate 5.4/game vs 8.5 for Default. Likely UltronCombatPolicy
  or score threshold too conservative. Needs investigation.
> IDEA: Lower score threshold from > 0 to > -10 or similar to make Ultron more willing to act.
  Many valid plays may score 1-5 and fall through to fallback unnecessarily.

### TICKET-008: Combat policy [DONE]
File: `UltronCombatPolicy`
Multiplayer-aware attacker filter. Considers crackback per attacker→target pair, not globally.
Crackback from other opponents (not target) counted as ambient risk. Vulnerable target at <= 5
life triggers lethal mode.
> AGENT NOTE [2026-06-18]: Per-target crackback fixed — was over-conservatively counting all
  opponents' evasive power for every attack. Now per attacker→target pair.
> OPEN QUESTION: Is the aggression threshold calibrated correctly? Sim data shows under-attacking.
  Worth auditing the threshold constants before tuning weights.

### TICKET-009: Runtime controller entry point [DONE]
File: `UltronRuntimeController`
Singleton per (Game, Player) pair stored in WeakHashMap (GC-safe). Routes decisions:
RESPONDING/stack → FastPriorityPolicy, MAIN_PHASE → scoreMainPhase, OTHER → FastPriorityPolicy.
Budget: 10ms priority, 50ms stack response, 500ms main phase.
LLM plan hints injected via `injectPlanHints()` which forces intent rebuild.
> AGENT NOTE [2026-06-19]: Added `getSimStats(game, player)` static method — no-create lookup
  for end-of-game stats access from SimulateStats.
> AGENT NOTE [2026-06-19]: Added `lastPrunedCount` and `lastChoiceScore` fields set by
  scoreMainPhase, read by recordSimDecision for accurate stats capture.

### TICKET-010: Wire into AiController [DONE + EXTENDED]
File: `AiController.java` (~line 1695-1760)
Dual-flag check: isUltronRuntime (profile only, no key) vs useUltronAdvisor (requires key+flag).
Runtime runs first; LLM strategic plan is guarded. Combat policy filter in declareAttackers.
> AGENT NOTE [2026-06-18]: `e.printStackTrace()` in FutureTask exception handler replaced with
  single-line log to reduce noise in sim output.
> AGENT NOTE [2026-06-19]: Two AiController fixes applied:
  (1) `getOrCreate` hoisted before `all.isEmpty()` early-return in `chooseSpellAbilityToPlayFromList`
      so the controller initializes even when candidate list is empty.
  (2) Stack response path now routes Ultron through its interaction policy for BOTH counterspells
      and ETB counters — previously `chooseCounterSpell(getPlayableCounters(cards))` bypassed
      Ultron entirely. Ultron now sees counterspell candidates. Validated: G4 smoke test shows
      Ultron choosing Daze to counter BOARD_WIPE (sev=85).
> AGENT NOTE [2026-06-19]: Fix (1) partial — stats still absent in loss games. See BUG-001.
  Root cause is that `runtime.choose()` is never called when `ultronCandidates` is empty,
  which happens when no spell scores above threshold. Needs follow-up (see BUG-001 options).

### TICKET-011: LLM advisor demoted to opt-in [DONE]
File: `UltronAdvisor.java`
All LLM paths now gated behind explicit env vars. `isUltronRuntimeProfile()` does profile check
only. `isLlmAdvisorEnabledFor()` requires ULTRON_LLM_ADVISOR_ENABLED=true + working API client.
Strategic plan gated on ULTRON_LLM_STRATEGIC_PLAN_ENABLED=true.

### TICKET-012: Simulation evaluator integration [DONE]
Files: `UltronGameStateEvaluator`, `UltronConfig.useSimulationEval()`
When ULTRON_USE_SIMULATION_EVAL=true, copies game and runs Forge simulation eval to get position
score. Overrides heuristic ahead/behind flags. Disabled by default (expensive — copies game state
and runs combat sim).

### TICKET-013: Unit tests [DONE]
Files: `forge-gui-desktop/src/test/java/forge/ai/llm/runtime/Ultron*Test.java`
42/42 passing. Tests cover: threat model, stack analyzer, interaction policy, fast priority policy,
main-phase policy, runtime controller selection, combat policy, cache invalidation, hook invalidation
(land, stack), AiController integration.
Run all: `mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true
  -Dtest="forge.ai.llm.runtime.Ultron*" -Dsurefire.failIfNoSpecifiedTests=false`

### TICKET-014: Targeting path verification [IDEA]
`UltronTargetPriorityEvaluator` exists and scores targets for removal. But it's unclear whether
it's wired into `PlayerControllerAi.chooseTargets()` / `chooseSingleEntityForEffect()`.
If not, Ultron may cast the right removal spell but let Forge pick the wrong target — silent
effectiveness loss with no trace in decision stats.
> ACTION: Read PlayerControllerAi and AiController target selection paths. If not wired,
  integrate UltronTargetPriorityEvaluator there.

### TICKET-116: Combat aggression calibration [DONE 2026-06-20]
**Goal:** Bring Ultron attack rate from 6.8/game to parity with Default (8.3/game).
**Root causes found and fixed:**
1. `ultronInDanger` penalty was **-30** — effectively vetoed all attacks when any opponent could
   theoretically kill Ultron. Reduced to **-10** (light signal, not a veto).
2. Crackback risk summed evasive power from **all** non-target opponents. In 4-player that doubles
   the risk estimate — only one player gets a turn before Ultron responds. Changed to `Math.max`
   (worst single retaliator).
3. AHEAD role was the **catch-all default** and set `avoidTappingOut=true`. This fires when no
   other condition matches — i.e., neutral game state — and was causing decision paralysis.
   Fixed: AHEAD → `avoidTappingOut=false`. CONTROL → `avoidTappingOut=false, lookForLethal=true`.
4. Lethal escalation threshold was **≤5 life** (from 40-life Commander). At 20-life Battlebox, ≤5
   means the opponent is already basically dead. Changed to **≤8** to trigger earlier.
5. Preferred attack target threshold: was `life <= 10`, now `life <= 13` (65% of 20).
6. **Monarch steal bonus**: +25 score when attacking the Monarch holder while Ultron lacks Monarch.
   Directly addresses the 6.1 vs 8.5 Monarch turns deficit.
**Files:** `UltronCombatPolicy.java`, `UltronTurnIntentBuilder.java`, `UltronOpponentProfile.java`

### TICKET-117: Score function miscalibration — slow engines overvalued [PLANNED]
**Signal:** Feldon of the Third Path 0% win rate (8 plays). Swiftfoot Boots 0% (6 plays).
Score paradox: LOSS mean score 25.1 vs WIN 22.9 — Ultron plays higher-value cards in losses.
**Hypothesis:** `UltronActionScorer` overvalues permanents with high raw board value (recursion
engines, defensive equipment) relative to tempo and damage-dealing plays.
**Approach:** (1) Add a "closing pressure" multiplier for cards that directly deal damage or
enable lethal (Fireball, Varragoth, etc.). (2) Reduce score for defensive equipment when
Ultron is not the leader. (3) Consider role-gating: in PRESSURING/CONTROL, devalue stax/engine
plays; in BEHIND/DESPERATE, devalue anything that doesn't create board presence fast.
**Files:** `UltronActionScorer.java`, `UltronGameStateEvaluator.java`

### TICKET-118: avoidTappingOut over-trigger [DONE 2026-06-20]
**Signal:** "preserving mana per intent" PASS = 74 in losses vs 40 in wins.
**Fix:** CONTROL role no longer sets `avoidTappingOut=true`. AHEAD (neutral) also no longer does.
Only roles where holding mana is genuinely correct (COMBO_DEFENSE) retain the flag.
`reserveCounterspellMana` now only activates when Ultron *actually has a counterspell in hand*
(was always set by role regardless of hand contents). This change also applied in CONTROL/AHEAD.
**Files:** `UltronTurnIntentBuilder.java`

### TICKET-119: Monarch tracking in table summary [DONE 2026-06-20]
`UltronTableThreatSummary` now carries `ultronHasMonarch` (bool) and `monarchHolder` (Player/null).
Populated in `analyze()` via `player.isMonarch()` and `game.getMonarch()`.
Used by: combat policy (Monarch steal bonus), intent builder (preferred attack target selection).
**Files:** `UltronTableThreatSummary.java`

### TICKET-120: Vulnerability thresholds recalibrated for 20-life [DONE 2026-06-20]
`UltronOpponentProfile` vulnerability thresholds were tuned for 40-life Commander:
- `life <= 5` → 95% (was 12.5% of 40-life, now 25% of 20-life — too late to trigger)
- `life <= 10` → 70% (was 25% of 40-life)
Fixed to 20-life Battlebox proportions:
- `life <= 8` → 95% (40% of 20-life — "kill window" opens earlier)
- `life <= 13` → 70% (65% of 20-life)
- `life <= 16` → 25% with weak board (was `life <= 20` which triggered for everyone at game start)
**Files:** `UltronOpponentProfile.java`

### TICKET-121: Late-game all-in escalation [DONE 2026-06-20]
Added turn ≥ 12 phase modifier in `UltronTurnIntentBuilder`. After turn 12, mana reserves are
cleared (`avoidTappingOut=false`, `reserveCounterspellMana=false`, `reserveRemovalMana=false`)
and `lookForLethal=true`. At turn 12+ in a 20-life format, the game is decided shortly — holding
mana for threats that may never materialize is strictly wrong.
**Files:** `UltronTurnIntentBuilder.java`

### TICKET-122: Card context evaluator — Phase B [SUPERSEDED 2026-06-21]
**Was:** `UltronCardContextEvaluator` — manually hand-coded per-card suppression rules.
5 rules: Emry (check artifacts in grave), Walking Ballista (check mana), Sneak Attack
(check high-power hand), Spore Frog (check combat imminent), Feldon (check creatures in grave).
Confirmed working; achieved 28% win rate in 25-game test on 2026-06-20.
**Superseded by:** TICKET-123. Manual per-card rules do not scale and require human diagnosis
of each bad card. The adaptive learner should discover card quality from game data.
**Action taken [2026-06-21]:** All references removed from `UltronDecisionContext` and
`UltronActionScorer`. `UltronCardContextEvaluator.java` is an orphaned untracked file —
delete manually: `rm forge-ai/src/main/java/forge/ai/llm/runtime/UltronCardContextEvaluator.java`
**Key insight:** Coalition Relic (0/4 win rate), Cultivate (0/3), Bushwhack (0/3) — these
should be penalized automatically after enough games. Manual rules just get there first.

### TICKET-123: Per-card adaptive win-rate learning [DONE 2026-06-21]
**Goal:** Replace manual card context rules with data-driven per-card win-rate tracking.
Every card Ultron plays in a game gets one (plays+1, wins+won) credit. After MIN_SAMPLE=8
plays, a score adjustment fires: `<15% WR → -20`, `<25% → -10`, `>40% → +8`, `>55% → +15`.
**Why this works:** Cards appearing in winning games correlate with win conditions. Coalition
Relic consistently appears in losses → penalty. Reflection of Kiki-Jiki consistently appears
in wins → bonus. Learning is format-specific (Battlebox) without any human-coded rules.
**Design:**
- `UltronCardStats.java` (new) — singleton with `Map<String, CardRecord(plays, wins)>`.
  O(1) `scoreAdjustment(cardName)` lookup. `record(cardsPlayed, ultronWon)` called once per
  game. Persists to `ultron_card_stats.json` next to the weights file.
- `UltronSimStats.cardsPlayed()` — new method extracting all CHOOSE'd card names from
  the decision list for the completed game.
- `UltronAdaptiveLearner.update()` — extended to call `UltronCardStats.record()` + `save()`.
  Also added `loadCardStats(weightsPath)` and updated `logCurrentWeights()` to show top cards.
- `SimulateStats.java` — calls `UltronAdaptiveLearner.loadCardStats(weightsPath)` at startup
  so persisted card knowledge carries across sim runs.
- `UltronActionScorer.score()` — `contextAdj` replaced with `learnedAdj` from
  `UltronCardStats.scoreAdjustment(host.getName())`.
- `UltronDecisionContext` — `cardContext` field and compute call fully removed.
**Convergence:** MIN_SAMPLE=8 means penalization kicks in after ~3 batches of 25 games
for Coalition Relic (appears 3-4 times/run). Expect measurable adjustment after ~75 games.
**Files:** `UltronCardStats.java` (new), `UltronSimStats.java`, `UltronAdaptiveLearner.java`,
`UltronActionScorer.java`, `UltronDecisionContext.java`, `SimulateStats.java`
> AGENT NOTE [2026-06-21]: First 25-game run in progress to confirm no regression from
  removing manual rules (no card data accumulated yet → all adjustments = 0 on first pass).
> HUMAN NOTE [2026-06-21]: The right long-term approach is adaptive learning, not per-card
  manual rules. Ultron should also eventually learn TIMING (when to play cards relative to
  phases and turn cycles) and SYNERGY (which card combinations correlate with wins).

### TICKET-124: Score function surgical additions — Phase C [DONE 2026-06-20]
**Goal:** Improve UltronActionScorer without disrupting the CMC baseline that holds board
development together. Lesson from earlier regression (16% win rate): wholesale rewrites of
the creature scoring block remove the implicit CMC-as-value-proxy that keeps creatures above
artifacts — work surgically.
**Changes made:**
- Evasive creature bonus: +15 for flying/shadow/horsemanship/fear/intimidate/menace/
  "can't be blocked". Rationale: evasive threats close games in multiplayer.
- Deathtouch bonus: +8. Deathtouch creatures trade up, deterring attackers.
- Engine penalty in aggressive roles: non-creature permanents with "whenever" or "at the
  beginning" in oracle text → -15 in PRESSURING/BEHIND/DESPERATE roles. Prevents Ultron
  from deploying 6-turn-value-engines when it needs immediate pressure.
- CMC≥3 slow non-creature penalty: `-(cmc-2)*3` in aggressive roles for non-engine artifacts.
- Card draw bonus: +20 for ApiType.Draw in BEHIND/DESPERATE roles.
**Validated:** 28% win rate in 25-game test (vs 24% Default baseline). Evasion bonus fired
47 times in 25 games. Context adjustments (Phase B) fired 14 times.
**Files:** `UltronActionScorer.java`

### TICKET-015: Ultron / Ultron-LLM profile split [IDEA]
Currently one "Ultron" profile handles both runtime and LLM paths via env vars.
Would be cleaner to have:
- `Ultron` = fast runtime only, no LLM code paths touched
- `UltronLLM` = runtime + advisor + strategic plan
Benefits: cleaner sim runs, clearer profiling, no risk of accidentally triggering LLM calls.

---

## EPIC: ULTRON-AI / SIMSTATS
Headless simulation infrastructure for empirical analysis.

### TICKET-101: SimulateStats headless runner [DONE]
File: `forge-gui-desktop/src/main/java/forge/view/SimulateStats.java`
Runs N games headlessly, writes per-game JSONL to outputDir. Config via INI file.
Launched via: `java -jar <forge.jar> simstats -config <path.ini>`
> AGENT NOTE [session-2]: Must run from `forge-gui/` working directory — `res/languages/en-US`
  resolves relative to CWD for SNAPSHOT builds.
> AGENT NOTE [session-2]: Monarch fix — must call `rules.setBattleboxMonarchEnabled()` BEFORE
  creating the Match. `Match.startGame()` overwrites the game flag from rules, clobbering
  any post-match-creation `game.setBattleboxMonarchChoice()` call.

### TICKET-102: SimStatsConfig INI parser [DONE]
File: `forge-gui-desktop/src/main/java/forge/view/SimStatsConfig.java`
INI-style config with sections: [run], [game], [stats], [sim].
Key knobs: games, seed, timeoutSeconds, outputDir, format, players, deck, aiProfiles,
battleboxMonarch, stats.enabled, stats.turnSnapshots, sim.adaptiveWeights, sim.weightsPath.

### TICKET-103: Sim config files [DONE]
Location: `configs/simstats/`
Files:
- `battlebox_monarch_4p_ultron.ini` — primary Ultron run (seat 0 Ultron, seats 1-3 Default)
- `battlebox_monarch_4p_ultron_adaptive.ini` — same but sim.adaptiveWeights=true
- `battlebox_monarch_4p.ini` — all-Default baseline (for seat-position win rate comparison)
- `battlebox_no_monarch_4p.ini` — no monarch baseline
- `battlebox_no_monarch_2p_trace*.ini` — 2-player traces
All now use absolute outputDir paths and timeoutSeconds=0.
> AGENT NOTE [session-2]: timeoutSeconds=0 disables game-level timeout. Default AI has its own
  per-decision timeout via FutureTask.get(), so infinite loops are prevented. Using 120s caused
  ~46% of games to end as timeouts, corrupting analysis.

### TICKET-104: UltronSimStats decision capture [DONE]
File: `forge-ai/src/main/java/forge/ai/llm/runtime/UltronSimStats.java`
Records one Decision per doChoose() call: turn, phase, life, stackDepth, kind (CHOOSE/PASS/
FALLBACK/NO_DECISION), chosen card name, score, scoreReason, candidates, pruned count, intent flags.
Aggregates to summary: totalDecisions, fallbackRate, meanChoiceScore, meanPruneRate, phase breakdown.
Added `computeActivations()` for adaptive learner: removalActivation, aggressionActivation, pruneActivation.

### TICKET-105: JSONL output enriched with Ultron stats [DONE]
Per-game JSONL record now includes:
- `ultron.summary` — aggregated decision stats
- `ultron.decisions[]` — per-decision snapshots
- `ultronWeights` — active weight multipliers snapshot (only when non-baseline weights active)
> AGENT NOTE [session-2]: `ultron` block missing in some games where UltronRuntimeController
  was never instantiated (Ultron eliminated before first priority pass with candidates, or
  eliminated very early). These games still counted in analysis as losses with no decision data.

### TICKET-106: analyze_ultron.py [DONE]
File: `tools/simstats/analyze_ultron.py`
Sections: run health, win/loss (with per-seat bar chart), survival, monarch, combat, game duration,
decision summary (with win/loss correlation), action analysis (top cards × win/loss, scoreReason
token frequency, score distribution), weight evolution (per-weight trajectory + outcome correlation).
Usage:
```bash
python3 tools/simstats/analyze_ultron.py \
  simstats/out/battlebox_monarch_4p_ultron/games.jsonl \
  --baseline-jsonl simstats/out/battlebox_monarch_4p_ultron_adaptive/games.jsonl \
  --weights-file ~/.forge/ultron-learning/weights.json \
  --top-cards 20
```

### TICKET-107: Sim run — Ultron baseline [DONE 2026-06-21]
200 games, seed=123456, 4-player monarch Battlebox, Ultron seat 0 vs Default seats 1-3.
No adaptive weights. Establishes Ultron's baseline win rate and decision profile.
Config: `configs/simstats/battlebox_monarch_4p_ultron.ini`
Run command (via script):
```bash
bash tools/simstats/run_simstats.sh configs/simstats/battlebox_monarch_4p_ultron.ini
```
> STATUS NOTE [2026-06-19]: 37/200 games completed before OOM crash (game 38). See BUG-004.
  Analysis on 37-game sample below. Config currently set to games=200.
  MUST add -Xmx8g to launch command; stock heap (3982 MB) insufficient for long runs.

**37-Game Analysis (2026-06-19):**
- Win rate: 10/37 = **27%** (baseline expectation 25%). Roughly at parity.
- Seat bias in Default: Seat 1 = 37.8% win rate, Seat 3 = 10.8%. Turn order variance dominates at small N.
- All 37 games have `ultron` stats block (BUG-001 fixed).

Combat/Monarch gap:

| Metric | Ultron | Default |
|--------|--------|---------|
| Attacks/game | 6.8 | 8.3 |
| Damage dealt/game | 16.8 | 19.8 |
| Monarch turns/game | 6.1 | 8.3 |

Score paradox: WIN mean score = 22.9, LOSS mean score = 25.1. Ultron plays higher-scoring
cards in losing games. Scoring function overvalues slow engines (Feldon, Ob Nixilis) vs
tempo/damage plays.

Role correlation:

| Role | Win% of decisions |
|------|:-----------------:|
| CONTROL | 45% |
| PRESSURING | 38% |
| STABILIZING | 32% |
| AHEAD | 28% |
| BEHIND | 29% |
| COMBO_DEFENSE | 28% |
| DESPERATE | 20% |

Card signals (≥5 plays): Varragoth 100% (6/6), Vraska 60% (3/5),
Feldon 0% (0/8), Swiftfoot Boots 0% (0/6). Feldon loudest loss signal.

Decision quality issues:
- Fallback rate: **80.4%** — only 1 in 5 decisions is an Ultron CHOOSE
- "preserving mana per intent" PASS: 74 in losses vs 40 in wins — over-conservative
- NO_DECISION MAIN: 68/game

**25-Game Phase A/B/C Results (2026-06-20):** win rate 7/25 = **28%** (baseline 24%).
Avg attacks: WIN=28.4 LOSS=4.0. Attack correlation is the strongest signal found —
winning games average 7× more attacks than losses. All Phase A/B/C fixes confirmed active.

**25-Game Regression (2026-06-21, adaptive per-card learning, no prior card data):** DONE.
Win rate 8/25 = **32%** (vs 28% with manual card rules). Confirmed no regression from removing
`UltronCardContextEvaluator`. Attack rate improved: Ultron 10.8/game vs Default 7.5/game.
Monarch turns: 8.2 Ultron vs 8.1 Default (parity). Score paradox resolved: WIN=35.5 > LOSS=33.7.
Adaptive weights after 25 games: aggression=2.559, removalBonus=1.269, pruneAggression=1.011.

### TICKET-108: Sim run — Ultron adaptive 250-game (attempt 1) [DONE/BLOCKED 2026-06-21]
250-game adaptive run. Ran 92/250 games before OOM crash. See BUG-006 and BUG-007.
Config: `/tmp/ultron_250game_learning.ini` (outputDir = `simstats/out/ultron_250_adaptive_learning/`).
Starting weights: aggression=2.559, removalBonus=1.269, pruneAggression=1.011 (from 25-game regression).

**92-game results (65 completed, 27 timeouts):**
- Win rate on completed games: **26.7%** (at parity with 25% FFA baseline)
- Seat equity: Ultron 26.7% = Seat 1 Default 26.7% (normalized after early variance)
- MIN_SAMPLE hits by game 92: Restoration Angel (50%), Baleful Strix (40%), Fury (67%),
  Spore Frog (67%), Wishclaw Talisman (44%), Ministrant of Obligation (44%), Scavenging Ooze (50%)
  all earned bonuses. Pyroclasm (12%), Cultivate (0%), Bushwhack (0%), Sneak Attack (0%),
  Unburial Rites (12%), Soul-Guide Lantern (0%), Hanged Executioner (0%), Backdraft Hellkite (25%)
  earned penalties. Coalition Relic (0% WR, 10 plays) — penalty active.
- Weight evolution: aggression 2.558→2.610, removalBonus 1.266→1.276

**Failure:** Games 70-92 all timed out (600s). Root causes: see BUG-006, BUG-007.
Learning state persists — card stats and weights carried into TICKET-108b.
> AGENT NOTE [2026-06-21 03:06]: Run.log co-location fix and headless guard fix applied this
  session. See BUG-005 and TICKET-S004.

### TICKET-108b: Sim run — Ultron adaptive 250-game (attempt 2, 5×50) [IN_PROGRESS 2026-06-21]
250-game adaptive run restructured as 5 batches of 50 games. Each batch gets a fresh JVM heap
(ZGC + 8g). Card stats and weights carry over between batches on disk.
Config: `configs/simstats/battlebox_monarch_4p_ultron_adaptive.ini`
outputDir: `simstats/out/battlebox_monarch_4p_ultron_adaptive/`
Banned cards: `Nadu, Winged Wisdom`, `Scute Swarm`, `Mystic Forge` (see BUG-007)
Run started 2026-06-21 ~16:28 in `tmux ultron_sim`.
```bash
bash tools/simstats/run_simstats.sh \
  configs/simstats/battlebox_monarch_4p_ultron_adaptive.ini
```
Progress: 11 games complete at 17:03 check. Pace normal (~3 min/game). No consecutive timeouts.
Confirmed: "Sim-banned cards" line appears in run.log. No Nadu/Scute/Forge log warnings.

---

## EPIC: ULTRON-AI / ADAPTIVE-WEIGHTS
Post-game weight learning system.

### TICKET-201: UltronWeights registry [DONE]
File: `forge-ai/src/main/java/forge/ai/llm/runtime/UltronWeights.java`
Singleton holding named float multipliers (default 1.0 = baseline). Override file at
~/.forge/ultron-learning/weights.json. `nudge(key, delta)` applies delta, clamps to [0.2, 5.0].
`load(path)` / `save(path)` for persistence. `resetToBaseline()` to clear all overrides.
Current named weights: SCORE_THRESHOLD, REMOVAL_BONUS, AGGRESSION, PRUNE_AGGRESSION.

### TICKET-202: UltronAdaptiveLearner [DONE — extended 2026-06-21]
File: `forge-ai/src/main/java/forge/ai/llm/runtime/UltronAdaptiveLearner.java`
Called after each completed game in adaptive mode. Computes activations from UltronSimStats,
applies nudges with learning rate 0.05, saves updated weights.
Signal: WIN=+0.75, LOSS=-0.25 (centered at 25% baseline for 4-player).
Activations: removalActivation (fraction of CHOOSE where scoreReason contains "removal"),
aggressionActivation (fraction of main-phase CHOOSE / total decisions), pruneActivation (mean prune rate).
> IDEA: Activation for removalBonus is a proxy (scoreReason text mining). Better: count
  decisions where ApiType was Destroy/ChangeZone in chosen spell. Requires passing ApiType
  through to simStats.Decision.
> AGENT NOTE [2026-06-21]: Extended — `update()` now also calls `UltronCardStats.record()` +
  `save()` after each game. `loadCardStats(weightsPath)` added for startup loading. Card stats
  file lives at `<weightsPath_dir>/ultron_card_stats.json`. `logCurrentWeights()` now also
  prints top 20 learned card records (MIN_SAMPLE filtered). See TICKET-123.

### TICKET-203: Weights wired into scorers [DONE]
- `UltronActionScorer`: removal target score multiplied by REMOVAL_BONUS, creature power
  deployment and lethal-seeker bonus multiplied by AGGRESSION.
- `UltronCandidatePruner`: max candidates = maxCandidates() / PRUNE_AGGRESSION (higher = fewer
  candidates = more aggressive pruning).

### TICKET-204: Adaptive toggle in INI + SimulateStats [DONE — extended 2026-06-21]
sim.adaptiveWeights=true/false in INI config. SimulateStats loads weights at startup,
calls learner after each completed game, embeds weight snapshot in JSONL record.
> AGENT NOTE [2026-06-21]: Now also loads card stats on startup via
  `UltronAdaptiveLearner.loadCardStats(weightsPath)`. Card stats accumulate across runs
  automatically when adaptiveWeights=true.

### TICKET-205: Score threshold as tunable weight [IDEA]
Currently `bestScore.value > 0` to act. This is a fixed threshold — not tunable by the weight
system. To make it adaptive: expose as `SCORE_THRESHOLD` weight but as an additive value
(not a multiplier, since baseline is 0 and 0 * multiplier = 0).
Implementation: change `> 0` to `> (int)UltronWeights.get(SCORE_THRESHOLD)` where default
stored value is 0.0. UltronWeights would need to support additive (not just multiplicative) keys.
> AGENT NOTE: Skipped in current implementation to avoid mixing additive and multiplicative
  weight types in the same registry. Left as IDEA for follow-up.

### TICKET-206: Expanded activation tracking [IDEA]
Current activations are proxies. Better activations would require:
- Per-decision ApiType of the chosen spell (to compute true removalActivation)
- Per-decision whether Ultron's role was AHEAD/BEHIND at decision time
- Tracking which weight category "drove" the score (contribution attribution)
This would give the adaptive learner a cleaner, less noisy signal. Add to UltronSimStats.Decision
record when ready.

---

# PROJECT: BATTLEBOX
**Status:** ACTIVE
**Branch:** `master` (merged from feature branches)
**Goal:** First-class Battlebox variant with shared zones, monarch, commanders, Planechase options.

### TICKET-B001: Core Battlebox variant [DONE]
Commit: `e9f1af1` (adding battlebox and ultron)
Files: `BattleboxConfig`, `SharedPlayerZone`, `Match.prepareBattlebox*`
Shared library, land station, graveyard zones. Lobby validation. RegisteredPlayer carries
Battlebox starting life/hand/max-hand/library-size metadata.

### TICKET-B002: Monarch option [DONE]
Commit: `9540d2a` (Add Battlebox monarch and land print handling)
`GameRules.battleboxMonarchEnabled`. `Match.startGame()` sets monarch on game from rules.
> AGENT NOTE [session-2]: Bug: if you set game.setBattleboxMonarchChoice() AFTER creating Match,
  startGame() overwrites it from rules. Fix: set rules.setBattleboxMonarchEnabled() BEFORE
  creating the Match object.

### TICKET-B003: Commander option [DONE]
Commits: `438d51c` through `9e46679`
Shared commander zone. One-commander-per-player-per-game rule enforced. AI spell evaluation
enabled for Battlebox shared commanders. Lobby checkbox for commander mode.

### TICKET-B004: Lobby UI options [DONE]
Commit: `e957ff0` (Move Battlebox options from prompts to lobby checkboxes)
Monarch, Commanders, Planechase options now in lobby UI rather than runtime prompts.
`GameRules` carries the flags; Match propagates to Game.

---

# PROJECT: SIMSTATS-INFRA
**Status:** ACTIVE (supporting Ultron analysis)
**Branch:** `simstats-counters` (merged to master)
**Goal:** Reliable headless simulation with rich per-game JSONL output for statistical analysis.

### TICKET-S001: Headless sim runner + collector [DONE]
Commits: `37da95c` (Add headless sim stats collection)
Files: `SimulateStats`, `GameStatsCollector`, `SimStatsGameContext`, `SimStatsJson`
Per-game JSONL: winnerSeat, winReason, players[], eliminations[], monarch{}, totalPlayerTurns,
elapsedMillis, completedNormally, timeout, error.

### TICKET-S002: Game noise reduction [DONE]
Session 2026-06-18.
- `PhaseHandler` stdout "Active player is no longer in game" removed
- `AiController` FutureTask exception: stack trace → single-line log
- `ChooseCardAi` bad logic warning: normalized
These logs confused analysis of errors vs normal game flow.

### TICKET-S003: Working directory requirement [DONE]
SimulateStats must run from `forge-gui/` — `res/languages/en-US.properties` resolves relative
to CWD for SNAPSHOT builds (`getAssetsDir()` returns "" for SNAPSHOT). Run commands now
explicitly `cd forge-gui` before launching the jar.

### TICKET-S005: Sim batching — repeat counter + banned cards + ZGC [DONE 2026-06-21]
**Problem:** Single-JVM 250-game runs hit a GC death spiral: LKI snapshot accumulation → long
GC pauses → timeout-cascade → OOM at game 92 (see BUG-006). Also: specific cards cause
near-infinite trigger chains that consume the full 600s timeout (see BUG-007).
**Changes:**
- `run.repeat` INI key: script loops this many JVM invocations. Each batch gets a fresh heap.
  All batches append to the same `games.jsonl` (`SimulateStats` writer now uses `APPEND` mode;
  script clears the file before batch 1). Batches share the same seed sequence (games 0-49
  repeat) — diversity comes from evolving adaptive weights between batches.
- `sim.bannedCards` INI key: comma/semicolon list of cards excluded from the deck pool at
  load time via `Deck.removeCardName()`. Deck files on disk are unchanged. `SimStatsConfig`
  exposes `getSimBannedCards()`. `SimulateStats.loadDecks()` applies the filter.
- JVM flags: `-XX:+UseZGC -XX:MaxGCPauseMillis=200` added to `run_simstats.sh`. ZGC
  concurrent collection eliminates the GC pause spikes that pushed games over the timeout.
**Files:** `tools/simstats/run_simstats.sh`, `forge-gui-desktop/.../SimStatsConfig.java`,
`forge-gui-desktop/.../SimulateStats.java`, `configs/simstats/battlebox_monarch_4p_ultron_adaptive.ini`

### TICKET-S004: Sim output co-located with run data [DONE 2026-06-21]
`tools/simstats/run_simstats.sh` now parses `run.outputDir` from the config and redirects
all sim stdout/stderr to `<outputDir>/run.log`. Previously, sim progress output went to
wherever the caller's stdout pointed (often /tmp or swallowed). Now `run.log` lives alongside
`games.jsonl` in the same output directory, keeping all run artifacts together.
The script fails with an error if `outputDir` is missing from the config (previously silently
defaulted to a relative path that could vary by CWD).
**File:** `tools/simstats/run_simstats.sh`

---

# KNOWN BUGS / INVESTIGATIONS

### BUG-001: ultron block missing in loss-game JSONL records [FIXED 2026-06-19]
Root cause: `SimulateStats.findUltronSimStats()` called `game.getPlayers()` which returns only
`ingamePlayers`. When Ultron is eliminated, it's moved to `lostPlayers` — not in `ingamePlayers`.
Fix: changed to `game.getRegisteredPlayers()` which returns the full `allPlayers` list.
Also: `recordNoDecision()` added to `UltronRuntimeController` to capture MAIN phase passes
where no candidates exist. Both fixes validated in build 06.19.22 — all 37 games in the
subsequent run have `ultron` stats blocks.

### BUG-002: Ultron under-attacking [FIXED 2026-06-20]
37-game data: Ultron 6.8 attacks/game vs Default 8.3/game.
Root causes found and fixed (see TICKET-116, TICKET-118, TICKET-119, TICKET-120, TICKET-121).
Key changes: ultronInDanger penalty -30→-10, crackback sum→max, CONTROL/AHEAD avoidTappingOut
removed, vulnerability thresholds recalibrated, Monarch steal bonus +25, turn≥12 all-in mode.
Impact to be measured in next sim run.

### BUG-005: GuiDesktop static initializer crashes in headless mode [FIXED 2026-06-21]
**Symptom:** `java -jar forge.jar simstats ...` exits with code 1 and zero output when no
display is available (`$DISPLAY` unset). Process dies before Logback opens forge.log. Stderr
also empty because `ExceptionInInitializerError` propagates before Sentry/GuiBase can redirect
System.err. Diagnosed via `-verbose:class`: last class loaded before the error was `forge.gui.GuiBase`.
**Root cause chain:**
1. `Main.main()` calls `GuiBase.setInterface(new GuiDesktop())`
2. `GuiDesktop` has `static float screenScale = initializeScreenScale()` at class load time
3. `initializeScreenScale()` calls `getDefaultScreenDevice().getDefaultConfiguration()`
4. `getDefaultScreenDevice()` throws `HeadlessException` in headless environments
5. Exception wraps in `ExceptionInInitializerError` → exits before forge.log is opened
**Why this worked before:** Prior sessions had `$DISPLAY` set (X11 forwarding or virtual display).
Session 2026-06-21 had no display → first exposure to the crash.
**Fix:** Added `GraphicsEnvironment.isHeadless()` guard in `initializeScreenScale()` — returns
`1.0f` when headless. The sim doesn't need screen scale; only the desktop UI uses it.
**File:** `forge-gui-desktop/src/main/java/forge/GuiDesktop.java` (`initializeScreenScale()`)

### BUG-006: GC death spiral — LKI accumulation → timeout cascade → OOM [FIXED 2026-06-21]
**Symptom:** In the 250-game run (attempt 1), all games from #70 onward timed out at 600s.
OOM crash at game 92: `java.lang.OutOfMemoryError: Java heap space` in `Game.setGameOver()`.
**Root cause:** Each game — especially long/complex ones — creates LKI (Last Known Information)
snapshots, game event histories, and game state graphs that accumulate in the heap across the
JVM's lifetime. With default G1GC under -Xmx8g, GC pause times grow as the heap fills.
By game 70, GC pauses consumed most of the 600s game budget even when actual game logic was
fast. This caused every game to timeout, which worsened memory pressure (timed-out games don't
clean up as completely as normally-completed ones), accelerating the spiral.
**Evidence:** Timeout games had avg 19 turns vs 41 for normal games. The game state was young
(few turns played), but wall-clock time was gone to GC.
**Fix:** (1) `run.repeat` batching: each batch of 50 games gets a fresh JVM (fresh heap).
(2) ZGC (`-XX:+UseZGC -XX:MaxGCPauseMillis=200`): concurrent collector keeps pause times
bounded regardless of heap fullness. Both applied in TICKET-S005.
**Files:** `run_simstats.sh`, `battlebox_monarch_4p_ultron_adaptive.ini`

### BUG-007: Near-infinite trigger chains — Nadu, Scute Swarm, Mystic Forge [MITIGATED 2026-06-21]
**Symptom:** Isolated early timeouts (games 3, 31, 46) plus the sustained cluster (games 70+)
contained games with very few turns (2-10) that still consumed the full 600s budget.
**Root cause candidates:**
- `Nadu, Winged Wisdom`: banned in Legacy for creating near-infinite land trigger chains.
  With this deck's 30+ fetch/dual lands and many creatures, Nadu chains are catastrophic in
  Forge's trigger resolver which has no loop guard for this pattern.
- `Scute Swarm`: exponential token creation each time a land enters. The land station in
  Battlebox makes early land drops frequent → Scute doubles repeatedly → millions of tokens.
- `Mystic Forge`: AI evaluates "can I cast the top card?" every priority pass. With the right
  top card, this becomes O(n) per priority in a priority-dense game state.
**Mitigation:** All three cards added to `sim.bannedCards` in the adaptive config. They remain
in the `BattleBox.dck` file — normal play is unaffected. Sim-only exclusion via `Deck.removeCardName()`
at load time. See TICKET-S005.
**Note:** BUG-006 (GC) likely amplified these isolated hangs into the sustained cluster. Both
fixes together (batching + ZGC + banned cards) should prevent recurrence.
**Long-term fix:** Add a trigger-loop guard in Forge's game engine for Nadu-style unbounded
chains. Out of scope for this session.

### BUG-003: Targeting path unverified [OPEN]
UltronTargetPriorityEvaluator exists but may not be wired into PlayerControllerAi target
selection. Needs investigation. See TICKET-014.

### BUG-004: OOM crash — zombie AI eval threads exhaust heap [FIXED 2026-06-19]
**Symptom:** 200-game run crashed at game 38 with `OutOfMemoryError: Java heap space` during
`Game.copyLastStateBattlefield()` (LKI snapshot copy of Hangarback Walker ETB).
**Root cause:** `AiController.timeoutReached` is `private boolean` — not volatile. The main
thread writes `timeoutReached = true` on timeout; the worker thread in the FutureTask reads it
to break the eval loop. Without `volatile`, the JIT may cache the read and the worker thread
NEVER sees the update. On Java 21, `Thread.stop()` throws `UnsupportedOperationException`, and
`future.cancel(true)` only sets the interrupt flag — it does not force exit of a tight
computation loop. Result: every AI eval timeout creates a zombie thread holding all its
card/SpellAbility references. 37 games × N timeouts = heap exhaustion.
**Evidence:** ~20+ `[AI-EVAL] TimeoutException in Game AI Eval thread: null` messages logged per
game. `forge.log` shows OOM during LKI copy in game 38.
**Fix:** `private volatile boolean timeoutReached` — ensures the worker thread sees the write
promptly and breaks the loop on the next iteration. Also add `t.join(3000)` after
`future.cancel(true)` to wait for the thread to actually die before continuing.
Also: add `-Xmx8g` to sim launch command as defensive ceiling — 3982 MB is too tight for
multi-game LKI-heavy Battlebox with 4 players.
Files: `AiController.java` (volatile flag + join), `configs/simstats/run.sh` (heap flag)

---

# GAMEPLAY EXCEPTIONS / ENGINE WARNINGS

Captured from Battlebox Monarch 4-player sim runs. Sources: run.log from prior 100-game run
(Jun 18) and 10-game smoke test (Jun 19). Log entries are stderr from the Java process.
Errors marked [PRIORITY] are relevant to Ultron decision correctness or sim data integrity.

### GEXC-001: Zone correction events [OPEN]
**Pattern:** `Correcting zone for <Card> (<id>)` in stderr during gameplay.
**Cards observed:** Mystic Forge, Brago King Eternal, Elephant Token (×2), Restoration Angel,
Samut Tyrant Smasher (×2).
**Meaning:** Forge detected a card in the wrong zone and moved it automatically. Usually a
timing issue with ETB/LTB triggers, blink effects (Brago, Restoration Angel), or token
generation (Elephant Token). Samut has a haste-granting ability that may interact with zone
tracking.
**Impact:** Likely cosmetic / self-correcting in most cases. Could cause incorrect board state
in edge cases. Not a crash. Not [PRIORITY] for Ultron unless the corrected zone is a creature
Ultron was trying to attack with or remove.
**Action:** Low priority. Worth noting if win-rate anomalies cluster around blink-heavy boards.

### GEXC-002: Unknown ChooseCard AILogic — Dauntless Bodyguard [OPEN]
**Pattern:** `[AI] Unknown ChooseCard AILogic for Dauntless Bodyguard - using BestAI fallback`
**Meaning:** The card's script specifies an AILogic keyword that has no registered handler in
the AI layer. Falls back to `BestAI` which selects generically.
**Impact:** Bodyguard's protection effect (sacrifice to protect target creature) won't be used
optimally. AI will not know when it's correct to sac Bodyguard for a key creature.
**Action:** Low priority card scripting gap. Not Ultron-specific.

### GEXC-003: TwoPilesAi missing chooseSinglePlayer override — Fact or Fiction [OPEN]
**Pattern:** `Warning: default (ie. inherited from base class) implementation of chooseSinglePlayer
is used by Fact or Fiction for forge.ai.ability.TwoPilesAi`
**Meaning:** When AI resolves Fact or Fiction, `TwoPilesAi` delegates player selection to the
base class which picks randomly or uses a generic heuristic. A proper override would choose
which pile to pick based on card value.
**Impact:** Fact or Fiction resolutions sub-optimal for all AI players including Ultron.
**Action:** Medium priority. Worth fixing if FoF is common in the Battlebox. Not Ultron-specific.

### GEXC-004: Petty Theft — activator not set [OPEN]
**Pattern:** `Petty Theft Did not have activator set in SpellAbilityRestriction.canPlay()`
**Meaning:** Card scripting bug — `SpellAbilityRestriction.canPlay()` is called without the
activating player being set on the `SpellAbility`. Forge catches and logs this but the
castability check may return incorrect results (too permissive or too restrictive).
**Impact:** AI may incorrectly evaluate whether it can cast Petty Theft in a given situation.
Could cause illegal plays or missed plays.
**Action:** Medium priority. Card scripting fix — find the Petty Theft card script and ensure
`sa.setActivatingPlayer(player)` is called before `canPlay()`.

### GEXC-005: MAIN PASS with candidates — possible over-conservatism [FIXED 2026-06-19]
**Was:** `reserveCounterspellMana=true` set based on role/game state alone, not actual hand.
Ultron held mana for a counterspell it didn't have.
**Fix:** `UltronTableThreatSummary` now populates `ultronHasCounterspell` by checking
`ZoneType.Hand` for any SpellAbility with `ApiType.Counter`. `UltronTurnIntentBuilder` gates
`reserveCounterspellMana` on `table.ultronHasCounterspell`. Also sets
`preferMain2CreatureDeployment = avoidTappingOut || reserveCounterspellMana`.
**Impact confirmed in 37-game run:** "preserving mana per intent" still appears as top PASS
reason (see TICKET-118), but frequency is lower than it was. Residual over-trigger is likely
`avoidTappingOut` being set too broadly by role — see TICKET-118.

---

# OPEN IDEAS PARKING LOT

- **Separate Ultron / UltronLLM profiles** — see TICKET-015
- **Score threshold as adaptive weight** — see TICKET-205
- **Expanded activation tracking in UltronSimStats** — see TICKET-206
- **Deep game traces for Ultron decisions** — FORGE_DEEP_GAME_TRACE=true captures full game
  events; could be enriched with Ultron decision annotations for post-hoc analysis
- **Multi-run weight sweep** — run 10x 100-game batches with different starting weight seeds,
  compare convergence. Guards against local maxima in adaptive learning.
- **Per-opponent targeting intelligence** — UltronTargetPriorityEvaluator currently scores
  targets by board threat; could also factor in political read (don't use premium removal on
  the weakest player) from UltronTableThreatSummary.
- **Phase-aware timing learning** — extend UltronSimStats.Decision to record the phase
  (MAIN1/MAIN2/END_STEP/UPKEEP) and game turn at which each card was played. Correlate
  per-card timing with win rate to discover format-specific timing rules (e.g., Vampiric Tutor
  played on opponent's end step before Ultron's draw step → draws the tutored card immediately
  in Battlebox since the shared deck doesn't reset). Requires adding `phaseType` to Decision.
- **Synergy pair tracking** — track which card *pairs* co-appear in winning games. With enough
  data (500+ games), card pairs with high co-occurrence and high win rate reveal synergies the
  heuristic scorer doesn't model. Could inform dynamic score boosts when a synergy partner is
  already on board.
- **Card script feature extraction** — res/cardsfolder/ scripts have structured ability data
  (triggers, activated abilities, X costs) not easily parsed from oracle text. A startup pass
  over Battlebox card scripts could bootstrap better initial priors before enough game data
  accumulates. Lower priority now that the learning system is in place.
- **Oracle text feature extraction as bootstrapping prior** — parse oracle text at UltronCardStats
  startup to seed initial biases: cards with "{X}" in cost and "enter" → scale with mana; cards
  with "sacrifice" + "prevent" → timing-sensitive; cards with "return target artifact" → requires
  graveyard. These priors would override the neutral baseline until MIN_SAMPLE games accumulate.

---

# BUILD REFERENCE

```bash
# Full rebuild (repo root)
mvn -pl forge-ai,forge-gui-desktop -am clean package -DskipTests -q

# Run all Ultron unit tests
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest="forge.ai.llm.runtime.Ultron*" -Dsurefire.failIfNoSpecifiedTests=false

# Run sim (script handles working directory and jar discovery)
bash tools/simstats/run_simstats.sh configs/simstats/battlebox_monarch_4p_ultron.ini

# Quick 25-game test
bash tools/simstats/run_simstats.sh /tmp/ultron_25game_test.ini

# Analyze results
python3 tools/simstats/analyze_ultron.py \
  simstats/out/battlebox_monarch_4p_ultron/games.jsonl \
  --weights-file ~/.forge/ultron-learning/weights.json \
  --top-cards 20

# Reset adaptive learning (weights + card stats)
rm -f ~/.forge/ultron-learning/weights.json
rm -f ~/.forge/ultron-learning/ultron_card_stats.json
```

---

# AGENT ORIENTATION

If you're a fresh agent session reading this:

1. **Check git status first.** `git status --short --branch`. This repo has substantial WIP.
   Do not reset or clean files without reading them first.
2. **Current active branch is `ultron-fast-ai-remodel`.** It's ahead of master with the
   full Ultron runtime AI implementation.
3. **The sim may be running.** As of 2026-06-21 a 5×50-game adaptive run is active in
   `tmux ultron_sim`. Output in `simstats/out/battlebox_monarch_4p_ultron_adaptive/`. Check
   `wc -l simstats/out/battlebox_monarch_4p_ultron_adaptive/games.jsonl` before touching anything.
   Also check `tmux has-session -t ultron_sim` — if alive, leave it alone.
   The run uses `run.repeat=5` (5 JVM-restart batches of 50 games each) with ZGC and a
   `sim.bannedCards` list (Nadu, Scute Swarm, Mystic Forge) — see TICKET-S005.
4. **Learning files** at `~/.forge/ultron-learning/` are mutable sim output — do not commit.
   `weights.json` = scalar weight multipliers. `ultron_card_stats.json` = per-card play/win
   counts. Delete both to reset adaptive learning to baseline.
5. **This file is the source of truth for project state.** AGENTS.md is orientation for the
   codebase structure. ULTRON_RUNTIME_REMODEL_NOTES.md covers the original runtime remodel
   in detail but is now partially superseded by this tracker for newer work.
6. **When you complete work on a ticket, update its status here and add an AGENT NOTE with date.**
7. **UltronCardContextEvaluator.java** is a dead orphan file (untracked, all references removed).
   Delete it: `rm forge-ai/src/main/java/forge/ai/llm/runtime/UltronCardContextEvaluator.java`
8. **Sim banned cards** (excluded from deck pool for headless sim only, not for normal play):
   `Nadu, Winged Wisdom`, `Scute Swarm`, `Mystic Forge`. Configured in
   `configs/simstats/battlebox_monarch_4p_ultron_adaptive.ini` under `sim.bannedCards`.
   See BUG-007 for rationale.
