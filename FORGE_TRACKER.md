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

## EPIC: ULTRON-V4 / NEURAL-EVAL
**Status:** PROPOSED (2026-07-23)
**Plan doc:** `ULTRON_V4_NEURAL_PLAN.md` (in-repo, root — the v3 plan doc in `~/agents/` was lost;
plans live in the repo from now on).
**Thesis:** keep v3's search skeleton, replace `GameStateEvaluator`'s hand-tuned constants with a
small learned value network (TD-Gammon-style afterstate evaluation over the fixed 666-card
Battlebox pool), trained via expert iteration bootstrapped from cheap all-Default logged games.
The NN eval also removes the per-evaluation `GameCopier`+combat-sim inner layer — it is part of
the TICKET-V3-207 performance fix, not just a quality play.
**Phases:** P0 unblock simulator (V3-207 SharedPlayerZone fan-out fix + GameCopier stack copy);
P1 Java state encoder + logging; P2 bootstrap value net + N=600 gate at ≥30%; P3 expert-iteration
loop + policy-prior pruning; P4 commander/planechase/interactive. Full details, risks, and gates
in the plan doc. No ticket work has started.

**Execution model (decided 2026-07-24):** ticket-sized Sonnet/Opus sessions, one ticket per
session, tracker as the handoff medium. Fable reserved for phase-gate reviews and for escalation
when a ticket stalls twice. Sim runs and NN training happen in tmux outside sessions, never
inside one.

### TICKET-V4-001: P0.1 — SharedPlayerZone view-update fan-out fix for simulation copies [DONE, GATE NOT MET — see 2026-07-24 update below]

The single highest-leverage change in the v4 plan. Diagnosis is already complete — see
TICKET-V3-207's "UPDATE (2026-07-05, ~04:00)" section: a live mid-game jstack caught
`SharedPlayerZone.onChanged()` fanning out `player.updateZoneForView(this)` to all 4 sharing
players on every single `Zone.add` during `GameCopier.copyGameState()` — pure UI view-model
bookkeeping, provably wasted on headless simulation copies that are scored once and discarded,
multiplied by every card in every shared zone on every copy, scaling with graveyard/zone growth
as the game progresses (matches the "stays expensive all game" symptom exactly).

**Kickoff prompt for the implementing session (paste verbatim):**

> Read `ULTRON_V4_NEURAL_PLAN.md` §6 Phase 0 and `FORGE_TRACKER.md` TICKET-V3-207 in full
> (especially the final "UPDATE (2026-07-05, ~04:00)" section and the "ORCHESTRATOR SUMMARY"),
> then execute **TICKET-V4-001 only**. Scope: make simulation-copy `Zone` mutations skip
> per-player view updates in `SharedPlayerZone.onChanged()` (and check whether base
> `PlayerZone.onChanged()` deserves the same guard). Requirements:
> 1. Find the engine's existing way to distinguish a `GameCopier`-produced simulation `Game`
>    from a real one and use it — do not invent a new flag unless none exists (look at how
>    `Game`/`Match` are constructed in `GameCopier.makeCopy` vs `Match.startGame`; grep for
>    existing "simulation" state on `Game` first).
> 2. Real games must be unaffected: `PlayerControllerHuman` GUI view updates and the Default
>    AI path must behave identically. Run the full `forge.game.*` shared-zone tests
>    (`MatchBattleboxSharedZoneTest`) plus `forge.ai.simulation.*` + `forge.ai.ultron.*`
>    aggregate suite (baseline 234/234) and `forge.ai.llm.runtime.Ultron*` (baseline 34/42,
>    8 pre-existing failures — do not chase them).
> 3. Verify with the established live-jstack method, not unit tests alone: build with
>    `mvn -pl forge-ai,forge-gui-desktop -am clean package -DskipTests -q`, run
>    `FORGE_SIM_XMX=6g bash tools/simstats/run_simstats.sh` against a copy of the
>    TICKET-V3-207 repro config (single JVM, `pgrep -f jar-with-dependencies`), take jstack
>    samples at ~5/10/15 min, and confirm (a) the `SharedPlayerZone.onChanged` →
>    `updateZoneForView` stack shape no longer appears, and (b) per-decision telemetry cost
>    drops materially vs the session-6 numbers recorded in TICKET-V3-207 (~941ms early-game,
>    ~3.1-3.4s late-game per `chooseSpellAbilityToPlay`).
> 4. The ticket's gate (and TICKET-V3-207's standing bar): a real Ultron-vs-3xDefault
>    Battlebox Monarch game reaching **natural completion** within a 900s timeout. If reached,
>    update BOTH this ticket and TICKET-V3-207 (which this fix likely resolves or nearly
>    resolves) with the numbers. If not reached, record exactly what improved and what the
>    new dominant cost is (fresh jstack evidence), and stop — do not start P0.2/P0.3.
> 5. Run in tmux, watch it live, never background-and-forget a sim JVM (TICKET-V3-207's
>    6-hour-zombie lesson). Update the tracker before ending the session either way.

## UPDATE (2026-07-24) — fix implemented and verified; gate NOT reached; new bottleneck identified

**Status changed to [DONE, GATE NOT MET]** — the fan-out fix itself is correct, real, and
verified regression-free, but it alone does not clear TICKET-V3-207's completion bar. Per this
ticket's own instructions, stopping here rather than starting P0.2/P0.3 unilaterally.

**What was implemented:** no existing real-vs-copy discriminator was found on `Game` (checked
`getMaingame()` — unused by `GameCopier`/`GameSnapshot`, both construct via the 3-arg `Game`
constructor with `maingame0=null`; checked `LobbyPlayerAi.useSimulation` — that means "this AI
player uses simulation to decide," the opposite direction, set per-player not per-Game). Added a
new minimal flag, following the existing `dangerouslySetTimestamp`-style post-construction-mutation
convention already used by `GameCopier`:
- `Game.isSimulationCopy()` / `Game.dangerouslyMarkAsSimulationCopy()` (`Game.java`).
- Called from `GameCopier.makeCopy()` right after `new Game(...)`, and from `GameSnapshot.makeCopy()`
  (the `EXPERIMENTAL_RESTORE_SNAPSHOT` path, currently disabled by default, marked too for
  consistency — same shape of copy, same reasoning).
- `PlayerZone.onChanged()` and `SharedPlayerZone.onChanged()` both check `game.isSimulationCopy()`
  and skip the `player.updateZoneForView(this)` fan-out when true. `PlayerZone`'s hand-sort logic
  runs regardless (it's real game-state mutation, not view bookkeeping).

**Regression testing:** `MatchBattleboxSharedZoneTest` 15/15. `forge.ai.simulation.*` +
`forge.ai.ultron.*` (by explicit class name, since `-Dtest='forge.ai.simulation.*'` package-glob
syntax silently matched 0 tests under Maven/Surefire on this setup — use explicit class-name
lists) 234/234, matching baseline exactly. `forge.ai.llm.runtime.Ultron*` 34/42, the same 8
pre-existing failures as baseline — no new failures anywhere.

**Live-jstack verification:** ran the standing Phase 2 gate config
(`configs/simstats/v3_ultron_vs_default_4p.ini`) shape as a single game,
`configs/simstats/v4_001_gate_single_game.ini` (games=1, timeoutSeconds=900, same
aiProfiles/format/bannedCards), in tmux, `FORGE_SIM_XMX=6g`, jstack samples at 300s and 601s.

*(a) Confirmed fixed:* `grep -c "SharedPlayerZone\|updateZoneForView"` across both samples = 0.
The specific stack shape from the 2026-07-05 finding (`SharedPlayerZone.onChanged` →
`Player.updateZoneForView` → `PlayerView.updateZone`/`updateFlashback` →
`Player.getCardsActivatableInExternalZones`) does not appear anywhere in either sample.

*(b) Per-decision cost, from `games.jsonl`'s `ultronCoverage.perMethod`:* `chooseSpellAbilityToPlay`
309 calls / 238333.79ms = **~771ms mean**, down from session-6's baseline of ~941ms early-game and
~3.1-3.4s late-game per the same method — a real, material drop, consistent with the fan-out
theory (its cost scaled with shared-zone size, which grows as the game progresses; removing it
should disproportionately help *late*-game decisions, which is exactly the shape of improvement
here).

**(c) Gate NOT reached.** `games.jsonl`: `"completedNormally":false,"timeout":true,
"elapsedMillis":901517`. 50 player-turns / 12 completed table rounds happened before the 900s kill;
2 of 4 players eliminated (turn 27, turn 48), the remaining two still undecided at kill time
(`"winReason":"Draw"` is the timeout-kill artifact, not a real result). So: **materially faster,
still not fast enough to finish in 900s** on this seed/config.

**New dominant cost, from fresh jstack evidence (fan-out fix already in effect — this is what's
left):** two distinct hot spots, neither touching `SharedPlayerZone`:
1. At 300s, the *live simulation* worker thread (`Ultron-Sim-chooseSpellAbilityToPlay`, a
   `GameCopier`-produced copy) was caught in `Card.updateReplacementEffects` →
   `CardState.getReplacementEffects` → `ReplacementHandler.getReplacementList` →
   `Game.forEachCardInGame`, called from `Player.cantWin`/`hasWon` →
   `GameAction.checkGameOverCondition` → `checkStateEffects`, itself called from
   `GameSimulator.resolveStack`'s state-effects pass during `GameStateEvaluator
   .simulateUpcomingCombatThisTurn`. I.e. every state-based-action check inside a simulated combat
   resolution re-walks every card in the copied game to rebuild replacement-effect lists just to
   answer "has anyone won/can anyone win" — cost scales with total card count in the copy, called
   repeatedly per state check, not just once per copy.
2. At 601s, the thread stuck for 617s of the run (`pool-3-thread-1`, 48.9s of CPU time in this one
   sample alone) was a **real, non-simulated** decision — no `GameCopier`/`GameSimulator` in its
   stack at all — in `AiController.getSpellAbilityToPlay` → `Card.getAllPossibleAbilities` →
   `SpellAbility.canPlay` → `GameActionUtil.getOptionalCostValues` → `Card.getStaticAbilities` →
   `CardState.getStaticAbilities` → `Card.updateStaticAbilities` → `CardState$LandTraitChanges
   .applyStaticAbility` → `Card.hasRemoveIntrinsic` (a `Stream.anyMatch` scan). This is the
   inherited `AiController`/Default-profile candidate-enumeration path (not Ultron's own decision
   path — Ultron/seat 0 was already eliminated by turn 27, so most late-game decisions after that
   are the 3 surviving Default profiles using the shared inherited controller) recomputing a
   card's full static-ability set on every `canPlay()` check for every candidate ability, on every
   priority pass, as board complexity (permanent count) grows late-game.

**Where this leaves the plan:** hypothesis 1 from the 2026-07-05 orchestrator summary (real
card-object construction/parsing cost in `GameCopier`) is still untested and still plausible as an
additional contributor, but the two stacks actually caught here point somewhere more specific and
more actionable: **static-ability/replacement-effect recomputation cost that scales with total
card/permanent count**, hit both inside simulation copies (replacement-effect list rebuild per
state check) and in the real inherited-AI candidate path (static-ability rebuild per canPlay
check). This reads like a caching opportunity (memoize per-card static/replacement-effect lists,
invalidate on the actual triggers that change them, instead of recomputing from scratch on every
check) rather than a copy-count or fan-out problem — a different shape of fix than P0.1/P0.2/P0.3
as currently scoped in `ULTRON_V4_NEURAL_PLAN.md` §6. This needs scoping as its own ticket (working
title: static-ability/replacement-effect caching) before further Phase 0 work — **not started
here**, per this ticket's own stop condition.

**Do not re-diagnose the fan-out theory** — it's fixed and confirmed absent from both samples.
**Do not read `chooseSpellAbilityToPlay`'s improved mean as "P0 done"** — the full-game gate is
the bar, and it wasn't met. Config used for this run: `configs/simstats/v4_001_gate_single_game.ini`
(new file, checked in). Raw jstack samples were captured to a job-scoped scratch dir that no
longer exists on disk by the time this is read; the stack traces above are transcribed in full.

> ORCHESTRATOR NOTE [2026-07-24]: reviewed the above against the actual diff and working tree
> before committing it as `0146c6f082`. The fix is correct and safe — `GameSnapshot.restoreGameState()`
> writes back into the real (unmarked) `Game`, so live-game and human-GUI view updates are
> unaffected; the flag only ever suppresses view bookkeeping on copies. Three qualifications on
> the conclusions above, for whoever picks this up:
> 1. **The ~771ms mean is weaker evidence than it reads.** Ultron was eliminated on turn 27 of 50,
>    so all 309 measured `chooseSpellAbilityToPlay` calls came from the early/mid game — the metric
>    structurally cannot see the late-game decisions where the fan-out cost was worst, which is
>    also where the claimed "disproportionately helps late-game" effect would show up. The load-bearing
>    proof that the fix works is the jstack absence of the diagnosed stack shape, not this number.
> 2. **"Still not fast enough" is an N=1 conclusion.** The all-Default control run (TICKET-V3-007)
>    had its own 10/500 = 2.0% timeout tail, so a single 900s timeout does not establish systemic
>    slowness — that seed may simply be a tail game. TICKET-V4-002 (below) exists to settle this
>    before anyone commits to more perf work.
> 3. **Do not scope the static-ability/replacement-effect caching ticket yet.** Hot spot 1
>    (replacement-effect rebuilds inside `GameStateEvaluator.simulateUpcomingCombatThisTurn`) lives
>    in exactly the code path the v4 learned evaluator *deletes* — see `ULTRON_V4_NEURAL_PLAN.md` §1
>    Claim 3, the NN eval replaces the eval-layer combat sim entirely. Optimizing code that Phase 2
>    removes is effort pointed backwards. Hot spot 2 is in the Default AI's own inherited path, which
>    demonstrably completes 490/500 control games, so it is unlikely to be the binding constraint.
>    Engine-wide static/replacement-effect caching is correctness-sensitive surgery (stale rule caches
>    produce wrong game rules); it needs a real justification, which TICKET-V4-002's timeout rate
>    either provides or removes.

### TICKET-V4-002: 10-game smoke run — is the timeout systemic or a tail? [IN_PROGRESS 2026-07-24]

Settles the N=1 question above with the cheapest possible experiment before any further Phase 0
performance work is scoped. Config `configs/simstats/v4_002_smoke_10game.ini`: 10 games,
`timeoutSeconds=900`, Ultron + 3x Default, Battlebox Monarch, `rotateSeats=true`, same
`baseSeed=910123` and `bannedCards` as `v3_ultron_vs_default_4p.ini` so results stay paired with
the existing 500-game all-Default control. Deliverable is one number: **games completing naturally
vs timing out, out of 10** — compared against the control's 2.0% baseline timeout rate.
Decision rule agreed in advance: a timeout rate in the control's neighbourhood means Phase 0 is
done and Phase 1 (encoder) starts; a substantially worse rate justifies scoping the caching ticket.

> PRE-REGISTERED CAVEAT [2026-07-24, written before any game finished — not a post-hoc excuse]:
> this run caps at `timeoutSeconds=900`, but the TICKET-V3-007 control it is compared against ran
> at **1200**. The comparison is therefore **asymmetric in the conservative direction**, and the
> two verdicts must be read differently:
> - **Low timeout rate → strong evidence.** Passing under a stricter cap implies passing at 1200s.
>   Take the result at face value and move to Phase 1.
> - **High timeout rate → ambiguous, do NOT conclude systemic slowness directly.** Some timed-out
>   games may have completed between 900s and 1200s. Before concluding anything, check each
>   timed-out game's `elapsedMillis`, `totalPlayerTurns`, and how many players were still alive at
>   the kill — a game killed at 900s with 3 players eliminated was nearly done; one at 900s with
>   4 alive and 15 turns played is genuinely stuck. Only the latter shape justifies the caching ticket.
> 900s was inherited from TICKET-V4-001's own gate bar and the run was already in flight when this
> was noticed; re-running at 1200s is cheap (~75 min) if the result lands ambiguous.

**Attempt 1 (2026-07-24 01:27, 2 workers x 4g): INVALID — both shards OOM'd. Results discarded,
not analyzed.** Output preserved at `simstats/out/v4_002_smoke_10game_INVALID_4g_oom/` for
reference. Each shard completed exactly 1 game (both hit the 900s timeout, at 29 and 23 player
turns) and then died of `java.lang.OutOfMemoryError` — 5 OOM records in shard_0's log, 2 in
shard_1's. **The 2/2 timeout figure from this attempt must NOT be read as evidence of anything:**
a heap thrashing at its ceiling produces GC pressure that slows every decision, so the timeouts
are confounded by the very defect that killed the run. Orchestrator error — 4g was chosen to
leave headroom for a Forge GUI instance the user had open, despite TICKET-V3-207 session 6
having already verified 6g as the working size and 3g as OOM-prone. Sharding itself was correct
(shard_0 `games=5 seedOffset=0`, shard_1 `games=5 seedOffset=5`).

**The OOM stack is itself a finding, and it corroborates TICKET-V4-001's hot spot 2:**
```
GameAction.checkStaticAbilities  <- ReplacementHandler.getReplacementList
  <- AiController.chooseBestLandToPlay <- chooseDefaultLandAbility
  <- AiController.chooseSpellAbilityToPlay  <- PlayerControllerAi.chooseSpellAbilityToPlay
  <- UltronPlayerController.chooseSpellAbilityToPlay   (inherited-fallback path)
```
i.e. the static-ability/replacement-effect recomputation that TICKET-V4-001 caught as a *CPU*
hot spot is also allocation-heavy enough to exhaust a 4g heap — in the **inherited Default-AI
path**, reached because Ultron's own simulation decision had already timed out and fallen back.
Note the contrast that makes this specific rather than generic: the all-Default control run
(TICKET-V3-007) completed 500 games at a *tighter* 3g heap without this. What differs is game
shape — Ultron games run long with large late-game boards (29/23/50 turns across the three
observed), and this cost scales with permanent count.

**Attempt 2 (2026-07-24 02:00, 2 workers x 6g): DEFERRED, killed ~9 min in with 0 games recorded.**
Not a failure — deprioritized by a scope change (see TICKET-V4-003). Output dir preserved as
`simstats/out/v4_002_smoke_10game_DEFERRED_4p/`. The 4-player timeout-rate question this ticket
exists to answer is **still open and still worth answering**, but it no longer gates anything:
training moves to 1v1 first, so the caching-ticket decision it was meant to inform is deferred with
it. Re-run this config unchanged when the box is free and 4-player work becomes current again.

### TICKET-V4-003: 1v1 Monarch as the bootstrap training/measurement lane [IN_PROGRESS 2026-07-24]

**William's call, and it is well-aimed at the measured evidence:** train on 1v1 Monarch Battlebox
first to avoid board inflation. TICKET-V4-001 (jstack) and V4-002 attempt 1 (the 4g OOM) both point
at static-ability/replacement-effect recomputation scaling with **permanent count** as the dominant
remaining cost. A 2-player board is roughly half the size, so this attacks the cost driver directly
instead of waiting on correctness-sensitive engine caching. It also raises games/hour, and sample
throughput is the binding constraint on the entire v4 plan (`ULTRON_V4_NEURAL_PLAN.md` §2).

Config: `configs/simstats/v4_003_smoke_1v1_monarch.ini` — `players=2`,
`aiProfiles=Ultron, Default`, `battleboxMonarch=true`, `rotateSeats=true` (cycles Ultron between
seats so play/draw advantage doesn't confound), `timeoutSeconds=900`, 10 games, same
`seed=910123` and `bannedCards` as every other lane. Verified against `SimStatsConfig`: the plural
`game.aiProfiles` key takes precedence over singular `game.aiProfile`, and 2-player Battlebox is an
established config shape (`battlebox_no_monarch_2p_trace.ini`). Launched 02:09 in tmux `v4_003_1v1`,
2 workers x 6g.

**Two traps recorded before any data arrives:**
1. **The 1v1 null hypothesis is 50%, not 25%.** Stage A results are NOT comparable to the
   TICKET-V3-007 all-Default 4-player control (24.7%). A 30% win rate would be a disaster here and a
   success in 4p; `gate.py` must be pointed at the right null for this lane.
2. **1v1 cannot teach what 4p FFA is made of** — multi-opponent threat triage, politics, target
   selection have no 1v1 analogue. This is Stage A of a two-stage curriculum
   (`ULTRON_V4_NEURAL_PLAN.md` §5.3 as revised); a 1v1-trained net must not be shipped into a
   4-player lane and called done. The encoder/value-head design already supports the transfer
   (1v1 encodes as a 4-player game with two seats flagged eliminated), so no architecture change is
   needed — but Stage B fine-tuning is mandatory, not optional.

**RESULT (2026-07-24 02:31): run OOM'd at 6g — but produced the single most important data point
this project has recorded.** 2/10 games completed before both shards died. Raw counts:

| shard | games | 40s per-decision timeouts | `SIM_WORKER_BUSY` "still draining" fallbacks | OOM |
|-------|-------|---------------------------|---------------------------------------------|-----|
| 0     | 1     | 5                         | **85**                                      | yes |
| 1     | 1     | 3                         | **147**                                     | yes |

**A real Ultron game completed naturally for the first time in this project's history:** 161
seconds, 14 player turns, 1v1 Monarch (Ultron seat 0, lost to Default seat 1). Every prior
attempt across TICKET-V3-207 sessions 2-6 and TICKET-V4-001 ended in timeout, OOM, or hang. This
validates the 1v1 direction concretely — when a decision does not blow up, a 1v1 game finishes in
under 3 minutes. The other game hit the 900s timeout at 28 turns.

**Diagnosis — the failure is structural in the v3 simulation architecture, and no heap size fixes
it.** The `SIM_WORKER_BUSY` counts are the tell. The sequence:
1. One `chooseSpellAbilityToPlay` exceeds its 40s per-decision budget.
2. The TICKET-V3-207 session-6 backstop abandons the decision — but **cannot stop the thread**
   (`Thread.stop()` is gone; the `GameCopier`/`GameSimulator`/`SpellAbilityPicker` call chain has
   no cooperative interrupt checkpoint). The worker keeps running *and keeps allocating deep-copy
   trees*.
3. The `SIM_WORKER_BUSY` gate correctly refuses to start a second worker while one is draining —
   so **85-147 consecutive subsequent decisions fall back to the inherited Default AI**. Ultron
   stops being Ultron for long stretches of the game.
4. The abandoned worker's retained object graph cannot be collected while it is still running, so
   the heap fills and the JVM OOMs.
This is a **liveness/retention defect, not a heap-sizing problem** — which finally explains the
otherwise baffling empirical record: OOM at 3g, 4g, 6g **and** 8g across sessions. An unbounded
abandoned allocator exhausts any ceiling. The session-6 timeout backstop did not create this
(without it, a single decision ate the entire game budget) but it converted a hang into a leak.

**Strategic consequence — this does NOT block the v4 plan, and the plan should not wait on it.**
`ULTRON_V4_NEURAL_PLAN.md` §6 P2.1 already specifies that the **bootstrap corpus comes from
all-Default games**, which never invoke Ultron's simulation search at all. The critical path
(P1 encoder → P2.1 corpus → P2.2 train → P2.3/4 integrate) is therefore fully unblocked by this
failure. Do **not** spend sessions trying to make the v3 copy-per-candidate architecture survive
long games — that is the architecture v4 replaces. Revisit only if Phase 2's NN-eval integration
turns out not to reduce per-decision cost enough (plan §1 Claim 3), and note the honest caveat
there: the NN eval removes the *evaluation*-layer copies, but `SpellAbilityPicker` still copies
once per candidate, so it reduces rather than eliminates copy pressure.

### TICKET-V4-004: All-Default 1v1 corpus-generator validation [DONE 2026-07-24]
Config `configs/simstats/v4_004_default_1v1_corpus.ini` — 20 games, all-Default 1v1 Monarch,
2 workers x 4g (Default AI has never needed more; the control lane ran 500 games at 3g).
Purpose: confirm the bootstrap-corpus lane completes reliably, and measure games/hour, before
committing days of wall-clock to a full corpus. Not an Ultron measurement lane.

**Result: 20/20 completed naturally. 0 timeouts, 0 OOM, 0 errors.** The bootstrap-corpus lane is
green. Elapsed per game: min 3s, **median 13s**, max 49s. Player turns: min 13, median 17, max 28
(mean 18.4). Throughput **~237 games/hour/worker** — roughly 474/hr at 2 workers, ~11,000/day.
Measured *under contention* (the TICKET-V4-005 encoder session was running Maven builds on the
same box throughout), so this is a floor, not a ceiling.

**Game-legitimacy sanity check (done because a 3-second game invites suspicion of a degenerate
result — it is not degenerate):** all 20 ended `AllOpponentsLost` (real kills, no draws/errors);
winner seats split exactly 10/10, so no detectable play/draw bias at N=20; mean 21.9 spells cast
per game across both players; 18/20 games had *both* players cast >=5 spells. Even the fastest
3s game ran 14 player turns with 11 spells cast and ended with a player at -1 life. These are
real games, just short — 1v1 Battlebox is roughly 9 turns per player.

**Plan impact (`ULTRON_V4_NEURAL_PLAN.md` §2 updated):** the plan was written around ~500-1000
games/day from the 4-player lane. The 1v1 lane is ~10x that, which drops the P2.1 bootstrap corpus
from "3-5 days" to a few hours and makes a substantially larger corpus and more ExIt iterations
affordable. Samples/game is lower than 4p (shorter games, 2 perspectives not 4 — call it ~200/game
vs ~800), but samples/day still rises nearly an order of magnitude. **Explicitly NOT relaxed:** the
§5.4 promotion gate. Cheap games make it tempting to run many gates and promote the best-looking
one, which is p-hacking; the rule stands as written.

### TICKET-V4-005: State encoder (P1.1 + P1.2) [DONE 2026-07-24]
Dispatched to an implementation session. `UltronCardFeatureTable` + `UltronStateEncoder` in
`forge.ai.nn`, per plan §4.1. **Design call made at dispatch: no card-ID embeddings in v0** — the
plan's 16-dim ID embedding conflicts with emitting a fixed-length `float[]` (you cannot pool
embeddings the encoder does not hold), so v0 uses handcrafted card features only and exposes the
vocab ID separately so a later ticket can add embeddings without regenerating the corpus.
Required invariant: 1v1 must encode identically to a 4-player game with two seats flagged
eliminated, so a Stage A net transfers to Stage B without an architecture change.

**Implementation summary.**

Files: `forge-ai/src/main/java/forge/ai/nn/UltronCardFeatureTable.java` (new),
`forge-ai/src/main/java/forge/ai/nn/UltronStateEncoder.java` (new),
`forge-ai/src/main/java/forge/ai/llm/runtime/UltronStackThreatAnalyzer.java` (additive-only edit:
four new public static `isXxxApi(ApiType)` predicates factored out of the existing `classify()`
switch's own dispatch groupings — `classify()` itself is untouched, so the analyzer's behavior is
provably unchanged; `UltronStackThreatAnalyzerTest` still passes). Tests:
`forge-gui-desktop/src/test/java/forge/ai/nn/UltronCardFeatureTableTest.java` (14 tests),
`forge-gui-desktop/src/test/java/forge/ai/nn/UltronStateEncoderTest.java` (5 tests).

**P1.1 — `UltronCardFeatureTable`.** Static `Map<String, float[]>` built lazily (double-checked
lock) from `StaticData.instance().getCommonCards().getUniqueCards()`. `CARD_FEATURE_DIM = 48`:
mana value (1, ÷10), color identity (5: W/U/B/R/G), card types (8: creature/land/instant/sorcery/
artifact/enchantment/planeswalker/battle), power/toughness (2, ÷10, zero for noncreatures), 25
tracked keyword flags (flying, deathtouch, lifelink, haste, trample, ward, flash, vigilance,
menace, reach, first strike, double strike, defender, hexproof, indestructible, infect, wither,
exalted, myriad, affinity, convoke, flashback, cycling, prowess, shroud), 6 role flags (removal,
counterspell, board wipe, card draw, ramp, token maker), 1 legendary flag. Role flags for
removal/counterspell/board-wipe/card-draw reuse the four new `UltronStackThreatAnalyzer` predicates
(`isRemovalApi`→`Destroy`, `isCounterspellApi`→`Counter`, `isBoardWipeApi`→`DestroyAll`/`DamageAll`,
`isDrawApi`→`Draw`) exactly as instructed — no second classifier was written for those four. Ramp
and token-maker have no analyzer analog (not stack *threats*) and are classified fresh here:
ramp = has a `Mana`/`ManaReflected`-api ability **and is not itself a land** (every land has a
baseline Mana ability — without the land exclusion, Forest/Plains/etc. were incorrectly flagged as
ramp; caught by the golden test, fixed); token maker = has a `Token`-api ability. ApiTypes are
collected by walking each of the card's `SpellAbility`s plus their `getSubAbility()` chains.
**Nontrivial finding:** `Card.fromPaperCard(pc, null)` (null owner) returns cards with an EMPTY
`getSpellAbilities()` for spells — Doom Blade/Counterspell/Wrath of God/Sol Ring's mana ability all
came back with zero abilities parsed until a real `Player`+`Game` owner was supplied. Fixed by
building one throwaway 2-player `Game` once (`buildDummyOwner()`, never played) and using its
seat-0 `Player` as the owner for every `Card.fromPaperCard` call during table construction. This
cost real debugging time; worth flagging for anyone else instantiating cards outside a live game.
Unknown card names return an all-zero vector (not null); `getVocabId(name)` returns a stable
1-based sorted-order integer per unique name, 0 (`UNK_VOCAB_ID`) for unknown — logged but unused by
pooling in v0, per the no-embeddings decision above.

**P1.2 — `UltronStateEncoder`.** `encode(Game, Player) -> float[]`, perspective-relative: self
block first, then exactly 3 opponent blocks in turn order (rotated to start after self), then one
global block. **Vector length = 1908 floats, fixed regardless of board size** (proven by test:
empty 4p board and a ~60-card-across-the-table board both produce length 1908). This is over the
plan's soft "~1000-1500" target — the choice was to keep full sum+max+count pooling fidelity
across six zones (battlefield creatures, battlefield noncreatures, hand, graveyard, exile,
command) rather than trim zones/pooling width to hit the budget; flagging as a deviation a future
session can revisit if training throughput demands a smaller input. **Schema hash:**
`UltronStateEncoder.SCHEMA_HASH` / `SCHEMA_HASH_HEX = "c411b2af58e8404b"` — FNV-1a 64-bit over a
canonical string of every named segment's offset+size (not Java's `String.hashCode()`, so it stays
portable if a Python-side check ever wants to recompute it independently). Changing any offset,
size, or the keyword/role ordering changes the hash.

Layout (self block 615 floats; each of 3 opponent blocks 423 floats; global block 24 floats;
615 + 3×423 + 24 = 1908): self = battlefield-creatures pool (109) + battlefield-noncreatures pool
(109) + hand pool (97, full knowledge) + graveyard pool (97) + exile pool (97) + command-zone pool
(97) + land-color counts (6) + life/poison/energy (3). Opponent = same battlefield pools (109+109)
+ hand **count only** (1) + graveyard pool (97) + command-zone pool (97) + land-color counts (6) +
life/poison/energy (3) + eliminated flag (1). Pooling = elementwise sum + elementwise max + count,
so `pool(dim) = 2*dim + 1`; battlefield cards get 6 dynamic floats appended before pooling (tapped,
summon-sick, damage/toughness ratio ÷2, +1/+1 counters ÷5, -1/-1 counters ÷5, has-attachment) so
`BATTLEFIELD_CARD_DIM = 54` vs `CARD_DIM = 48` for the non-battlefield zones. Global block: turn
÷50 (1), phase one-hot (13, `PhaseType.values().length`), active-player relative slot one-hot
(4: self/opp1/opp2/opp3), monarch holder relative slot one-hot+none (5), players-remaining ÷4 (1).
**Eliminated opponents get a pure zero block + `eliminated=1`** — their zones are never read at
all, which is what makes the transfer guarantee below hold exactly, not approximately.

**Known simplifications (documented, not silently dropped):** land mana-color-production is
approximated by basic-land subtype name match only (`Plains`→W, etc.); any nonbasic land, however
much fixing it actually does, counts as "other/colorless." Commander damage (per-opponent, from
plan §4.1's global scalars) and a Planechase current-plane-ID slot are **not implemented** — no
Commander/Planechase-damage fixture existed to drive them and the ticket's Battlebox-first scope
didn't need them; a later ticket adding Commander/Planechase training data must add these before
relying on the vector for those variants. Stack (top-3 entries) is also not encoded — Phase 1's
fixtures are all pre-priority board states; this is a real gap for any future in-priority-window
training signal and should be called out explicitly if P1.3's logger starts capturing mid-stack
states.

**Tests (19 total, all passing):**
- `UltronCardFeatureTableTest` (14): full-vector golden pins for Forest, Plains, Grizzly Bears,
  Serra Angel, Lightning Bolt (proves DealDamage burn is deliberately NOT the removal flag),
  Doom Blade (removal), Counterspell (counterspell), Wrath of God (board wipe), Divination (card
  draw), Sol Ring (ramp via Mana api), Raise the Alarm (token maker), Llanowar Elves (creature +
  ramp simultaneously — the case most likely to break if ramp detection is ever narrowed to
  "noncreature only"); unknown-card zero-vector handling; vocab ID stability/distinctness.
- `UltronStateEncoderTest` (5): vector length constant on an empty vs. large (~60-card) board;
  no-NaN/no-Infinity + printed min/max/mean sanity report over 50 randomized states × 4 seats = 200
  perspective-samples (report: 1420/1908 features constant-zero at this sample size — expected,
  most are rare keyword/role flags over a ~17-card pool, not evidence of a bug); perspective
  invariance (same game, seat 0 vs. seat 1, verified via each pool's count slot — self/opponent
  ordering rotates correctly and wraps); **the transfer-guarantee test** — a real 2-player game and
  a hand-built 4-player game with seats 2/3 `concede()`d before any zone population, given
  byte-identical content on seats 0/1, produce `Assert.assertEquals(v4p, v2p)` (exact float-array
  equality, not just "close"). That test deliberately uses plain Constructed fixtures (no
  `SharedPlayerZone`) rather than Battlebox — Battlebox's shared graveyard/library/command zones
  return the same underlying collection to every player regardless of seat count, which would mask
  rather than exercise the per-seat parity this test exists to prove; the length/perspective tests
  above use the standard Battlebox+`SharedPlayerZone` fixture convention.

**Regression baselines: unaffected, both reproduced exactly.**
`forge.ai.simulation.*` + `forge.ai.ultron.*`: **234/234** (`mvn test -pl forge-gui-desktop -am
-Dtest=<10 explicit class names> -Dsurefire.failIfNoSpecifiedTests=false`). `forge.ai.llm.runtime.
Ultron*`: **34/42**, the same 8 pre-existing "Ahead-state" failures as before this session (not
chased, per instructions) — `UltronStackThreatAnalyzerTest` itself is in the 34 that pass, so the
additive predicate extraction is confirmed behavior-preserving.

**Build note:** `-Dtest=forge.ai.simulation.*` package-glob silently matched 0 tests here too
(confirms the existing tracker warning); explicit comma-separated class lists were required and
`-Dsurefire.failIfNoSpecifiedTests=false` was needed on top of `-DfailIfNoTests=false` or Maven
treats a 0-match on a single upstream module (e.g. `forge-core`, which has none of these classes)
as a hard failure during the `-am` build chain.

**What the next session needs to know:**
1. P1.3 (`UltronStateLogger` + `SimulateStats` outcome back-fill, `stats.nnLogging` config key) and
   P1.4 (encoder microbenchmark, plan wants <1ms/state — not measured yet, but 200
   encode()-calls-with-fresh-Game-construction across the sanity-report test ran in a few seconds
   total including JVM/card-DB warmup, so the encoder itself is very unlikely to be the bottleneck)
   are NOT started.
2. The 1908-length vector is bigger than the plan's soft ~1000-1500 target — a deliberate tradeoff,
   not an oversight, but worth a conscious decision (trim zones vs. accept it) before P2's net
   sizing locks in an input width.
3. Card-ID embeddings are NOT in this vector by design (see the no-embeddings note above) — a
   future ticket adding them needs `getVocabId()` plus a new logging column, not a re-encode.
4. Commander damage and Planechase plane-ID are reserved-but-unimplemented; stack encoding is
   entirely absent. None of these matter for the Stage A (1v1 Battlebox Monarch) bootstrap lane
   TICKET-V4-003/004 are running, but they are real gaps for Commander/Planechase variants later.

### TICKET-V4-006: Land mana-color fix, semantic schema versioning, state logger (P1.3) [Task A/B DONE, Task C DONE 2026-07-24]

**Task A — land mana-color production fix.** `UltronStateEncoder.writeLandColorCounts()` (the
per-player land-colour-count block; `UltronCardFeatureTable` itself never had land-colour logic —
the dispatch brief's attribution of the bug to that class was inaccurate, the real and only
offender was the encoder's land block) previously matched only the five basic land names
(`Plains`→W, etc.) and counted every other land, however much fixing it actually did, as
"other/colorless." Measured against the Battlebox pool: **60 of 80 lands have no basic land
subtype at all** — every Ravnica karoo, every Theros temple, every shockland — and were silently
reporting zero color production.

Fix: `writeLandColorCounts()` now walks `Card.getManaAbilities()` and, for each `AbilityManaPart`,
calls `mp.mana(m)` (after `m.setActivatingPlayer(c.getController())`) and splits the result on
whitespace, exactly mirroring `GameStateEvaluator.evaluateLand()`'s established pattern
(forge-ai/.../simulation/GameStateEvaluator.java:~306) rather than inventing a new one. Per land,
each distinct color token increments that color's slot by 1 (deduped per land, so a two-color
land increments both slots by exactly 1, same as if it were two basics); `"Any"` increments all
five color slots (same convention as `evaluateLand`'s `colors_produced.contains("Any")` special
case); anything else (`"C"`, generic-mana tokens) buckets into the existing "other/colorless"
slot. New helper `markProducedColor(boolean[], String)`.

**What moved:** the `SELF_LAND_COLORS`/`OPP_LAND_COLORS` 6-float block's *values* for any land
without a basic-land-named subtype. Golden-value shift, by land shape: Azorius Chancery (karoo,
`Produced$ W U`) — was `[0,0,0,0,0,0.1]` (counted as "other"), now `[0.1,0.1,0,0,0,0]` (W+U).
Temple of Enlightenment (`Produced$ Combo W U`, `isComboMana()`→`getComboColors`) — same shift,
was all-other, now W+U. Hallowed Fountain (shockland, `Types:Land Plains Island`, gets its mana
ability from the basic land subtypes it has despite no `Basic` supertype — real MTG rule 305.6,
which Forge's engine already implements dynamically) — same shift, W+U. Basics (Forest→G, etc.)
and colorless utility lands (Wastes→`Produced$ C`→"other") are **unchanged** — they were already
correct under the old subtype-name-match approximation, since it happened to be exact for those
two cases.

**No offset moved** — same 6-float slot at the same position in both self and opponent blocks.
This is exactly why Task B exists: a pure layout hash would not have moved for this change.

Test: `UltronStateEncoderTest#testLandColorProductionFromManaAbilitiesNotSubtypeNames` (karoo,
temple, shockland, basic, one per player, checked via `SELF_LAND_COLORS_OFFSET`) and
`#testColorlessUtilityLandProducesOnlyOtherSlot` (Wastes). Both new, both pass.

**Task B — semantic schema versioning.** Added `UltronStateEncoder.ENCODER_SEMANTIC_VERSION`
(int, starts at 2 — 1 would have been V4-005's original release), folded into
`computeSchemaHash()`'s input string ahead of every other field, with a doc comment stating
explicitly that *any change to how a feature is computed, not just to the layout*, must bump it.
Bumped to 2 as part of this ticket's Task A. **New `SCHEMA_HASH_HEX` = `330703df11234a17`**
(was `c411b2af58e8404b` per TICKET-V4-005 — confirmed via a temporary `System.out.println` in a
test run, then reverted; nothing in the test suite pins the hash as a literal so no golden-file
update was needed there). No model or training log exists yet to migrate — this is the first time
the hash has ever moved, so there's nothing downstream to break.

**Task C — `UltronStateLogger` (P1.3).** New `forge.ai.nn.UltronStateLogger` (forge-ai module),
modeled on `UltronOfflineDecisionLogger`'s enable-flag/writer conventions but logging binary
`UltronStateEncoder` feature vectors instead of prose JSONL.

*Enable flag:* off by default. `UltronStateLogger.isEnabled(configFlag)` OR's a new
`SimStatsConfig.isNnLoggingEnabled()` (`stats.nnLogging`, default `false`) with the
`ULTRON_NN_LOGGING` env var, matching `UltronOfflineDecisionLogger`'s `ULTRON_OFFLINE_DECISION_
LOGGING` convention. **Zero-cost-when-disabled is structural, not a runtime branch**: `SimulateStats`
only constructs `UltronStateLogger.GameCollector` and subscribes it to the game's event bus when
`isEnabled()` is true (`forge-gui-desktop/.../view/SimulateStats.java`, mirroring the existing
`collector`-nullable pattern for `stats.enabled`) — a disabled run allocates nothing and the event
bus has one fewer subscriber, not a subscriber that no-ops. No throughput measurement was taken
against the ~237 games/hour/worker baseline (would need a real N-game run, out of scope for this
session), but the "don't even construct it" design means there is nothing to measure a regression
against when off.

*Sampling:* one record captured at each player's `MAIN1` phase entry each turn (a cheap,
decision-logic-free proxy for "decision point" — P1.3 is logging-only, no AI method is hooked, per
the session's hard constraint). Capped at `MAX_RECORDS_PER_GAME = 200`: turn 1 and the final
`ALWAYS_KEEP_FINAL_TURNS = 3` turns' records are always kept; everything else is downsampled by
uniform random sampling once the game's full record list is known (records buffer in memory for
one game's duration, so this is decided in one pass at `finish()`, not true streaming reservoir
sampling — a documented simplification, fine for typical Battlebox-length games, worth revisiting
if a training run ever produces multi-hundred-turn games that bloat memory).

*Format:* self-describing binary records (magic `0x554E5331`/"UNS1" + format version 1, so shard
files are concatenable the same way `games.jsonl` shards are), gzip-compressed, one growing
`nn_states.bin.gz` file per shard's `outputDir` — never one shared path across worker JVMs, since
`outputDir` is already per-shard via `run_parallel.sh`. Per record: schema hash + semantic version,
game ID (reused from the already-unique-per-game `gameSeed`, no new counter needed), turn, phase
ordinal, acting seat, game length, then one block per living player: seat, vector length +
`UltronStateEncoder.encode()` output (1908 floats today), raw `ComputerUtil.evaluateBoardPosition
(null, player)` heuristic score, elimination turn (-1 if never eliminated), and a placement rank
(1 = best; the winner or still-alive-at-end player is rank 1, eliminated seats rank by elimination
turn descending, ties share a rank — a documented v0 tie-break, refinable at training time since
the raw elimination turn is logged alongside it). **Record size at today's `VECTOR_LENGTH`=1908:
`36 + numPlayers × (7640)`** bytes pre-gzip (header 36B; each seat block = 4+4+1908×4+4+4+4 =
7640B) — e.g. a 2-player record is ~15.3 KB pre-gzip.

*Labels:* computed at `finish()`, not via a literal second file-rewrite pass — because records are
buffered in memory for the whole (short) game and only written once the game's outcome is fully
known, "post-game append" and "single write pass with correct labels already in it" are
equivalent here, and the latter is simpler. **Timeout games are discarded entirely** — `finish()`
returns without writing anything if `timeout` or `!completedNormally`, per plan §5.1's "a
timeout's winner is noise" policy (matches `gate.py`'s denominator handling).

*Python reader:* `tools/nn/read_nn_states.py` (new), a plain-struct reader mirroring the exact
big-endian layout `DataOutputStream` writes (Java writes all primitives, including floats via
`Float.floatToIntBits`, big-endian). **Round-trip proof performed this session:**
`UltronStateLoggerTest#testWritesParseableRecordsAndRoundTripsInJava` builds a real 2-player
Battlebox fixture (Forest/Mountain/Grizzly Bears vs. Island/Swamp, life 18/20), drives it through
three `MAIN1` entries (turns 1, 2, 3; seats 0, 1, 0), and writes+asserts via a second, independent
Java-side reader (pinning the byte layout in-JVM). The file is deliberately left on disk
(`$TMPDIR/ultron_nn_state_fixture/nn_states.bin.gz`, not deleted by the test) and
`read_nn_states.py` was run against that exact file:
```
/tmp/ultron_nn_state_fixture/nn_states.bin.gz: 3 record(s)
  schema_hash=0x330703df11234a17 semantic_version=2 format_version=1
  first record: game_id=987654321 turn=1 phase_ordinal=3 acting_seat=0 game_length=3 num_seats=2
    seat=0 vector_len=1908 heuristic_score=137.0000 elimination_turn=-1 placement=1
    seat=1 vector_len=1908 heuristic_score=63.0000 elimination_turn=-1 placement=1
```
Per-record turn/acting-seat sequence across all three records (1/seat0, 2/seat1, 3/seat0),
`schema_hash`, `semantic_version`, `game_id`, `vector_len`, and elimination/placement all matched
the Java test's own assertions exactly — the first attempt at the Python struct format had a real
bug (`gameId` mis-typed as a 32-bit field instead of 64-bit, corrupting every read after it), which
this exact round-trip check caught immediately (`EOFError` on the first vector read) and the fix
was a one-line format-string correction. That bug is worth flagging: it's the kind of
off-by-field-width mistake that silently produces plausible-looking garbage if the two sides are
never actually cross-checked against the same real file, rather than each side's own synthetic
test data.

**Not done / explicitly out of scope this session:**
- No outcome-label wiring beyond what `finish()` already computes per-game — there is no
  cross-game aggregation, no train/val split tooling, and no corpus-scale run. That is Phase 2's
  job (P2.1), not P1.3's.
- No throughput/regression measurement of the corpus generator with logging enabled vs. disabled
  (see zero-cost note above for why the disabled case doesn't need one; the *enabled* case's
  actual overhead — vector encoding + gzip write cost per sampled turn — has not been measured and
  should be before committing to it for a multi-day P2.1 run).
- Placement-rank tie-breaking (simultaneous elimination) is a simple documented v0 rule, not
  validated against the plan's exact "soft distribution" language (`1st→[.70,.15,.10,.05]`) — that
  conversion is explicitly a training-time concern per the plan text, not something this logger
  needs to bake in, but flagging it so a training-side ticket doesn't assume more than is here.
- Reservoir sampling is "buffer whole game, sample once at finish()," not true single-pass
  streaming reservoir sampling — fine at current game lengths, a real limitation if game length
  variance grows a lot in later phases (very long Commander-style games, if ever logged).

**Test results (this session, full detail):**
- New/changed: `UltronStateEncoderTest` (+2 tests, 7 total in that class now),
  `UltronStateLoggerTest` (new, 4 tests) — `forge.ai.nn.*` altogether: **25/25**
  (`UltronCardFeatureTableTest` 14 + `UltronStateEncoderTest` 7 + `UltronStateLoggerTest` 4).
- Full regression baseline re-run (13 explicit classes: the 6
  `forge.ai.simulation.*` + 5 `forge.ai.ultron.*` classes from TICKET-V4-005 plus both `forge.ai.
  nn.*` classes, now 3 with `UltronStateLoggerTest` added): **259/259**
  (`mvn -pl forge-gui-desktop -am test -Dtest=<13 explicit class names>
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false`). Package-glob `-Dtest=forge.ai.
  simulation.*`/`forge.ai.nn.*` still silently matches 0 tests on this setup — explicit class
  names required, confirming the standing tracker warning yet again.
- `forge.ai.llm.runtime.Ultron*` was **not** re-run this session (nothing in that package was
  touched; TICKET-V4-005 already reproduced its known 34/42 baseline with the same 8 pre-existing
  "Ahead-state" failures).

**What the next session needs to know:**
1. Task A/B are complete and tested; the new hash `330703df11234a17` / semantic version `2` is
   live now — any Phase 2 corpus generation should start from this encoder, not the V4-005
   original.
2. P1.4 (encoder microbenchmark, <1ms/state target) is still not done — same status as
   TICKET-V4-005 left it.
3. The Phase 1 gate ("a 20-game logged run produces parseable data; a Python notebook round-trips
   it; per-feature sanity report") is **not fully met**: the round-trip is proven on a 3-record
   synthetic fixture, not a real 20-game `SimulateStats` run with `stats.nnLogging=true`. That run
   has not been attempted this session (no `.ini` config with the new key was created or executed)
   — doing that, and measuring real per-record/per-game overhead with logging on, is the
   immediate next step before calling Phase 1 fully closed.
4. `stats.nnLogging` is wired into `SimStatsConfig`/`SimulateStats` but has never been exercised
   through an actual `.ini` config file end-to-end — only through direct `GameCollector`
   construction in tests. Worth a real dry run before trusting it in a long unattended P2.1 job.

> HARDWARE CORRECTION [2026-07-24]: TICKET-V3-001's RAM budget (`nproc`=4, 15 GB total, hence
> workers=2) is **stale** — the box now measures **31 GB RAM / 8 cores**, with ~11 GB `available`
> at the time of writing. The practical parallel-run ceiling is roughly double what that ticket's
> formula computes, which materially changes the wall-clock cost of the planned N=600 gate runs.
> Re-measure with `free -g`/`nproc` before sizing any run rather than trusting the old formula, and
> subtract any Forge GUI instance the user has open (one `--battlebox-test` JVM at -Xmx4096m was
> running during this session and was deliberately left undisturbed).

---

### TICKET-V4-008: Trainer, architecture sizing, Java inference + parity test (P2.2/P2.3, partial) [Task A DONE, Task C DONE, Task B DONE (analysis only, no code change), 2026-07-24]

Session ran concurrently with the `v4_007_bootstrap_corpus` corpus-generation run (2 JVMs, ~19h,
left completely undisturbed — no `mvn clean`, no `-T` parallel builds, single-threaded `mvn
package`/`test` invocations only). **Did not train V0** — the only data available was the
379-record/758-perspective-sample/20-game smoke set from TICKET-V4-006 (`simstats/out/
v4_006_logged_dryrun/shard_{0,1}/nn_states.bin.gz`), which the dispatch brief correctly flagged
as far too small for a real model; every number below from that dataset is labelled a smoke-test
result, not a V0 result, and `--smoke-label` in the trainer CLI forces that label into
`metrics.json` so a future session can't mistake a smoke run for a real one.

**Task A — trainer (`tools/nn/train.py`, new, ~470 lines).** Reuses `read_nn_states.read_records`
for all parsing (no second parser). Key design decisions, documented at length in the module's
own docstring so a future session doesn't have to re-derive them:

- **Perspective-relative composite value target.** For a sample captured at absolute seat `s` in
  an `n`-player game, relative slot `i` (0=self, 1..3=opponents) maps to absolute seat
  `(s+i) % n` for `i < n`; slots `i >= n` are structurally masked (mirrors the encoder's own
  zero-block padding for 1v1). A slot is ALSO masked if that seat was already eliminated as of
  this specific record (absent from the record's `seats` list) — input and target masking are
  self-consistent by construction, since the encoder already zeroes that same slot's input block.
  Target = `alpha * placement_credit + (1-alpha) * U(s)`, both renormalized over exactly the
  unmasked slots: `placement_credit` uses `RANK_CREDIT = [0.70, 0.15, 0.10, 0.05]` (the plan's own
  §5.1 example numbers) indexed by each slot's final placement rank; `U(s)` is a softmax of the
  raw `heuristic_score` values across unmasked slots at that record ("table share"). Loss is
  soft-target cross-entropy with masked-out logits driven to `-inf` before softmax so the model
  is never asked to place probability mass on a slot whose input is already zeroed.
- **Split by game ID, not by state** (`split_game_ids`), with a dedicated `--self-test` that
  asserts no game straddles train/val and no game is lost — this was written and run FIRST, before
  any model code, per the brief's warning that a state-level split is "the single easiest way to
  fool yourself here." `python3 tools/nn/train.py --self-test` passes.
- **Aux heads implemented:** own-placement (4-way classification of self's final rank) and
  game-length bucket (8-way, fixed edges `[10,14,17,20,24,30,40]`, heuristic and undocumented
  against real data since the smoke set is only 20 games). **Aux head NOT implemented:** the
  plan's third aux head, table-share-2-turns-later — it requires cross-referencing a different
  record 2 turns ahead in the same game's stream, which this session deprioritized under the
  stated A→C→B priority order. Flagging explicitly rather than silently dropping it; a future
  session adding it needs to index records by `(game_id, turn)` within `build_samples`.
- Timeout games need no filtering here — `UltronStateLogger.GameCollector#finish()` already
  discards them before ever writing a record (TICKET-V4-006), so they're never in the input files.
- Deterministic seed (`torch.manual_seed` + `random.seed`); every run writes
  `runs/<timestamp>/{config.json, metrics.json, model.bin, parity_vectors.bin,
  parity_python_probs.bin}`.
- **Environment:** `tools/nn/.venv` created, CPU-only wheel installed
  (`pip install torch --index-url https://download.pytorch.org/whl/cpu` → **torch 2.13.0+cpu**,
  `torch.cuda.is_available()` is `False`, confirmed no CUDA deps pulled), plus `numpy` (needed for
  the parity-fixture export, not otherwise used). Install was ~869 MB, took several minutes on a
  quiet-except-for-the-corpus-run box.
- **Smoke-test run performed** (proves the pipeline runs end-to-end, nothing more):
  `tools/nn/.venv/bin/python3 tools/nn/train.py --data shard_0/nn_states.bin.gz
  shard_1/nn_states.bin.gz --epochs 15 --hidden1 128 --hidden2 64 --alpha 0.5 --val-frac 0.2
  --seed 1234 --smoke-label "..."`. Split: 614 train samples (16 games) / 144 val samples
  (4 games). Early-stopped at epoch 13 (patience 5). **These numbers are meaningless as a
  quality signal** (16 training games) and are reported only to prove the trainer doesn't crash
  and produces a loadable model: `val_value_logloss` bottomed around 0.66, `val_winner_accuracy`
  fluctuated 0.54–0.69 — with 16 games' worth of games-to-be-overfit that is noise, not signal.
  **Do not cite these numbers as V0 quality in any later report.**

**Task B — architecture sizing (analysis only; no plan/code change committed).** Parameter counts
(export-time, i.e. after the two aux heads are dropped; VECTOR_LENGTH=1908, value head width 4):

| hidden1 → hidden2 | export params | train params (incl. both aux heads) | params / 300K samples* |
|---|---|---|---|
| 512 → 256 (plan §4.2's numbers) | 1,111,300 | 1,114,384 | 3.70 |
| 256 → 128 | 522,884 | 524,432 | 1.74 |
| 128 → 64 (used for this session's smoke run) | 253,252 | 254,032 | 0.84 |
| 64 → 32 | 124,580 | 124,976 | 0.42 |

*300K samples = the task brief's estimate for the real corpus (~8,000 games × ~38
perspective-samples/game per the TICKET-V4-006 verification's measured yield).

**Feature occupancy, measured on the smoke dataset (758 perspective-samples, 20 games):**
605 of 1908 features (31.7%) are EVER nonzero anywhere in the dataset; 1,303 (68.3%) are always
zero. **This number overstates true dead-feature count and should not be used to trim the vector
as-is** — two artifacts specific to the smoke corpus inflate it:
1. It is 1v1 data only, so 2 of the 3 opponent blocks (846 of 1908 floats) are permanently
   padded-eliminated by construction — that's expected and will look completely different in a 4p
   corpus.
2. `UltronStateLogger` only samples at `MAIN1` phase entry (documented in TICKET-V4-006), so only
   1 of the 13 one-hot phase-type slots is EVER set — 12 more structurally-dead floats that are a
   sampling artifact, not a real dead feature.
   Even accounting for both of the above, restricting to the "live" 1,062 floats (self block +
   real opponent block + global block) still shows only 603 ever-nonzero (**57% occupancy** on the
   part of the vector that's actually exercised in 1v1), consistent with the tracker's prior
   finding of median 117/1908 (~6%) nonzero per individual sample — most of that 6% comes from a
   fairly narrow, repeating subset of features (card-role/keyword flags for a small, fixed
   Battlebox card pool), not a spread across the whole vector. **Recommendation: re-measure
   occupancy on the real 4p corpus before trimming anything** — the padded-block and phase-only-
   sampling effects specifically will not apply there, and 4p data will exercise features (e.g.
   `OPP_ELIMINATED` transitions mid-game, more phase diversity if sampling is ever widened) this
   1v1 smoke set structurally cannot.

**Recommendation: hidden1=256, hidden2=128 (≈523K export params, ≈1.7 params per real-corpus
sample) for the V0 training run, not the plan's 512→256.** Reasoning: (a) 512-wide first layer
alone is 977K parameters against a ~300K-sample corpus — a params/sample ratio (3.7) high enough
to invite memorization rather than generalization on a corpus this size, independent of the
sparsity finding; (b) the median-117-of-1908-nonzero sparsity (TICKET-V4-006 finding, reconfirmed
this session) means most of a 512-wide first layer's units are dotting against a near-all-zero
vector for a typical sample — capacity that's disproportionately expensive per unit of live
signal; (c) 256→128 is still meaningfully large model capacity (half of every plan-spec dimension)
and is a defensible middle ground versus jumping straight to something as small as 64→32, which
plan §7's own risk table anticipates might be too small ("net could be scaled up... if held-out
loss says it is underfitting"). **This session did NOT change the plan or write the recommendation
into any code default** — `train.py --hidden1/--hidden2` default to 256/128 as the CLI defaults
(matching this recommendation), but the actual smoke-test run above used `--hidden1 128 --hidden2
64` for a faster demo pass; nothing about that run should be read as retracting the 256/128
recommendation for the real V0 corpus run. Whoever runs V0 training should treat 256/128 as a
starting point, watch held-out `val_value_logloss` for overfitting (16 games of the smoke set
already showed visible fitting-then-plateau within 13 epochs; with ~7,000 training games instead
of 16 this will look completely different and needs to be re-observed) and adjust up/down from
there — this is an estimate grounded in parameter/sample ratios and measured sparsity, not a
tuned result (no tuning was possible on 20 games).

**Task C — Java inference + model artifact + PARITY TEST.**

*Export format* (`tools/nn/train.py:export_model`, `.bin`, big-endian throughout — same convention
as `UltronStateLogger`'s `DataOutputStream` writes): header
`magic(int32,0x554E5332="UNS2") formatVersion(int32,1) schemaHash(int64) semanticVersion(int32)
inputDim(int32) hidden1(int32) hidden2(int32) numValueSlots(int32,4)`, then float32 weights in
this exact order: `fc1.weight[hidden1,inputDim] fc1.bias[hidden1] ln1.weight[hidden1]
ln1.bias[hidden1] fc2.weight[hidden2,hidden1] fc2.bias[hidden2] ln2.weight[hidden2]
ln2.bias[hidden2] valueHead.weight[4,hidden2] valueHead.bias[4]`. Aux heads
(`placement_head`, `length_head`) are dropped at export — train-time only, per plan §4.2.

*`forge.ai.nn.UltronValueNet`* (new, `forge-ai/src/main/java/forge/ai/nn/`, 260 lines): loads the
`.bin` via a `DataInputStream`, **refuses to load** (throws `IOException` with an explicit message)
if `schemaHash != UltronStateEncoder.SCHEMA_HASH` or `semanticVersion !=
UltronStateEncoder.ENCODER_SEMANTIC_VERSION` — checked BEFORE any weight bytes are read. Forward
pass: `Linear→ReLU→LayerNorm→Linear→ReLU→LayerNorm→Linear→softmax`, plain `float` arithmetic
throughout (no double accumulation), PyTorch-default LayerNorm epsilon (`1e-5`) and biased
(population, divide-by-N) variance — both called out explicitly in the class javadoc as the most
likely parity-break points if either side is ever edited independently. **Not wired into any AI
decision path** — no `StateEvaluator`, no `UltronPlayerController`/`GameStateEvaluator` changes,
per the dispatch brief's explicit instruction that this is a later ticket.

*THE PARITY TEST* (`forge-gui-desktop/src/test/java/forge/ai/nn/UltronValueNetParityTest.java`,
new, extends `AITest` for the same Localizer/FModel-init reason `UltronStateEncoderTest` does —
`UltronStateEncoder`'s static `SCHEMA_HASH` computation touches `PhaseType`, which needs the
Localizer loaded first, and skipping that produces an opaque `ExceptionInInitializerError` the
first time this was run without it). Fixture: `train.py`'s smoke-test run above also wrote
`parity_vectors.bin` (100 REAL logged vectors, drawn straight from the same training corpus, not
synthetic) and `parity_python_probs.bin` (the actual trained `nn.Module`'s softmax output on those
same 100 vectors — not a reimplementation). Test loads `model.bin` through the real
`UltronValueNet.load(Path)` path (schema-check included) and asserts every one of the 100×4
outputs matches the Python reference to <1e-5.

**Result: PASS. Max absolute deviation = 2.384185791015625e-7 (record 22, slot 3) — over 40× under
the 1e-5 tolerance**, on the very first run (no LayerNorm-epsilon or weight-ordering bug needed
chasing this time — the class javadoc's explicit warnings about those two failure modes appear to
have been enough to avoid them by construction, rather than needing a debug pass). Two additional
tests added and passing: `testRefusesToLoadOnSchemaHashMismatch` and
`testRefusesToLoadOnSemanticVersionMismatch`, both using a synthetic (deliberately-wrong-header)
fixture — the "not synthetic vectors" rule is specifically about the parity claim, not about unit
tests for the loader's own guard clauses.

Run command (for reproducing): `mvn -pl forge-gui-desktop -am test -Dtest=UltronValueNetParityTest
-Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
-Dultron.parity.dir=<runs/timestamp dir> -Dcheckstyle.skip=true -q`. The test SKIPS (not fails) if
neither `-Dultron.parity.dir` nor `ULTRON_PARITY_DIR` is set — there is deliberately no synthetic
fallback for the parity claim itself.

**Build hygiene:** used `mvn -pl forge-ai,forge-gui-desktop -am test-compile -DskipTests
-Dcheckstyle.skip=true -q` to compile, then targeted `-Dtest=UltronValueNetParityTest` runs — no
`mvn clean`, no `-T` parallel flag, single JVM at a time, box was otherwise carrying the two
corpus-generation JVMs the whole session (`v4_007_bootstrap_corpus`, left untouched).

**Not done / explicitly out of scope this session (honest partial reporting):**
1. **No V0 model was trained.** The real corpus (`v4_007_bootstrap_corpus`, ~8,000 games) was
   still running at session end; this is Task A's own explicit instruction, not an oversight.
2. **The table-share-2-turns-later aux head is not implemented** (see Task A section above) —
   the trainer trains only 2 of the plan's 3 aux heads.
3. **The `alpha` anneal schedule is not implemented** — `train.py` takes a single fixed `--alpha`
   per run; annealing across successive corpora/ExIt iterations (plan §5.1) is a Phase 3 concern,
   not exercised or even scaffolded here beyond the flag existing.
4. **Feature-occupancy numbers above are 1v1-smoke-set-only** and explicitly flagged as likely
   overstating true dead-feature count for the real (eventually 4p) corpus — see the caveats
   under Task B. Do not use them to trim the vector without re-measuring on real 4p data.
5. **No calibration-by-game-stage numbers are reported** (the trainer computes them —
   `calibration_by_stage` in `metrics.json` — but on 20 games they are not meaningful and are
   omitted from this write-up for the same reason the headline smoke numbers are).
6. **P1.4 (encoder <1ms/state microbenchmark) is still not done** — carried over from
   TICKET-V4-005/006, still nobody's done it.

**What the next session needs to do to train V0 on the real corpus:**
1. Wait for `v4_007_bootstrap_corpus` to finish (~19h from its start; check the tmux session).
2. Merge/point `--data` at all shard `nn_states.bin.gz` files from that run.
3. Run `tools/nn/.venv/bin/python3 tools/nn/train.py --data <all shards> --hidden1 256 --hidden2
   128 --alpha 0.5 --epochs 30 --seed <pick one, keep it> ` (no `--smoke-label` this time — that
   flag exists specifically so a real run's `metrics.json` is NOT mistaken for another smoke test).
4. Watch `val_value_logloss` for early stopping behavior and re-derive the 256/128 sizing call
   against real held-out loss — Task B's recommendation is a params/sample-ratio estimate, not a
   tuned result, and should be revisited once real numbers exist.
5. Re-run the parity test against the new run's `runs/<timestamp>/` directory
   (`-Dultron.parity.dir=...`) — schema hash/semantic version will match automatically as long as
   the encoder hasn't changed since TICKET-V4-006 (currently `330703df11234a17` / version 2).
6. Re-measure feature occupancy on the real (4p, once available) corpus before ever acting on
   this session's 1v1-smoke occupancy numbers.
7. `StateEvaluator`/`NeuralStateEvaluator` wiring (plan §4.4, P2.4) is NOT started — still fully
   out of scope per this ticket's own instructions, next session's job.

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

### TICKET-V3-207: OOM crash — uncached AiDeckStatistics.fromPlayer() in nested simulation [BLOCKING, NEEDS-FOLLOWUP 2026-07-04]

> AGENT NOTE [2026-07-24]: The `SharedPlayerZone.onChanged()` fan-out named in this ticket's
> "UPDATE (2026-07-05, ~04:00)" section is fixed — see TICKET-V4-001's 2026-07-24 update (in the
> `EPIC: ULTRON-V4` section above) for the implementation, regression testing, and fresh jstack
> evidence. It materially improved per-decision cost (`chooseSpellAbilityToPlay` mean dropped from
> the ~941ms-3.4s range recorded below to ~771ms) but did **not** clear this ticket's standing bar
> — a 900s single-game run still hit timeout without natural completion. Two new dominant costs
> were identified (both card static-ability/replacement-effect recomputation, scaling with card/
> permanent count — not the fan-out, not GameCopier call count) — full detail in TICKET-V4-001.
> This ticket remains open; do not re-diagnose the fan-out, it's closed.

**Session 2 update (2026-07-04, this session):** Implemented and unit-test-verified the
`AiDeckStatistics` cache described below (see "Fix implemented, session 2" at the bottom of
this ticket) — it is correct and does eliminate the specific uncached full-deck-reparse
pattern this ticket names. **A real-game smoke test with the fix applied still OOM'd**, and
the crash is in a *different* allocation path (`GameCopier.copyGameState` → `Zone.add` →
`Game.fireEvent` → Guava `EventBus.post`, not `AiDeckStatistics.fromPlayer()`), and it also
recurred on the very first decision of game 1, not "after N candidates compound." This means
the original root-cause diagnosis below was incomplete: `AiDeckStatistics.fromPlayer()` was
*a* source of combinatorial allocation, not the only one — the sheer volume of full
`GameCopier.makeCopy()` deep-clones triggered by `SpellAbilityPicker.formulatePlanWithPhase()`'s
recursive candidate search appears to be a comparably large (or larger) contributor, and needs
its own investigation. **Ticket remains BLOCKING — do NOT proceed to the deferred Phase 1
smoke test or the Phase 2 statistical gate.** Full details in "Session 2 findings" below.
**Discovered by:** the deferred Phase 1/2 real-game smoke test (`v3_ultron_smoke`, 6 games,
2 workers) — the exact scenario unit tests on synthetic states cannot exercise. This is why
that smoke test was mandatory before any statistical gate run; it caught what 230/230 unit
tests missed.

**Symptom:** both shards crashed with `java.lang.OutOfMemoryError: Java heap space` at
`-Xmx3g`. Shard 0 crashed almost immediately, inside recursive spell-sequence planning.
Shard 1's Game 1 first **timed out at the full 1200s budget**, then a separate decision threw
a `NullPointerException` (correctly caught and logged as a fallback to inherited behavior —
the existing safety net in `UltronPlayerController` worked as designed), and shortly after
that the JVM OOM'd. **Both crashed JVMs then sat stuck at ~99% CPU for over 6 hours**
(discovered and killed by the orchestrator, not self-terminating) — the OOM did not cleanly
exit the process, silently wasting significant wall-clock time before anyone noticed.

**Root cause:** `GameStateEvaluator.getScoreForGameStateImpl()` calls
`AiDeckStatistics.fromPlayer(aiPlayer)` (`forge-ai/src/main/java/forge/ai/AiDeckStatistics.java`)
as part of `evalManaBase()`. `fromPlayer()`/`fromDeck()` re-parses **every card in the deck
from scratch** via `Card.fromPaperCard()` → `CardFactory.getCard()` → full card-script/trigger
parsing — expensive, and with zero caching/memoization. This was tolerable in Forge's
original 2-player simulation design (one evaluation call per real decision point). Ultron's
new nested-simulation architecture defeats that assumption: main-phase planning
(`SpellAbilityPicker.formulatePlanWithPhase`, P2.4) recursively sequences multiple simulated
spells, and each step's evaluation (P2.3) triggers `simulateUpcomingCombatThisTurn`
(P2.5's combat-in-eval hook), which itself calls `declareAttackers` →
`chooseAttackPlanViaSimulation` → `scoreAttackCandidate` → `GameStateEvaluator.getScoreForGameState`
→ `AiDeckStatistics.fromPlayer()` **again**. Each layer of simulation nesting multiplies calls
to this uncached, expensive full-deck-reparse — combinatorial allocation blowup, heap
exhaustion.

**Why unit tests missed it:** the P2.1–P2.6 unit tests construct small, targeted synthetic
game states to prove specific behavioral claims (attack profitable, block declined, etc.) —
they exercise the decision *logic* but never run enough real turns/decisions in sequence for
the uncached-reparse cost to compound. Real games, with real decks, across real turns, are
what exposed this.

**Status: BLOCKING.** No further sim runs (smoke, gate, or otherwise) should be launched
against the current `UltronPlayerController` decision surface until this is fixed — every
real game currently at risk of either timing out at 1200s or OOM-crashing its JVM, which
would corrupt any statistical gate run's data (timeouts are already excluded from win-rate
by `gate.py`, but a crashed shard loses all its games, not just the one in flight, and
silently wastes compute exactly as it did here for 6+ hours unattended).

**Recommended fix (next session):** cache `AiDeckStatistics` per (player, decision-point) —
the player's deck composition does not change mid-simulation-tree for a single real decision,
so it should be computed at most once per real (non-copied) top-level call and reused across
every recursive simulated sub-call within that decision, rather than recomputed by identity
of the copied `Player` object each time. Needs care: `GameCopier` copies produce new `Player`
objects per candidate, so a naive per-Player-identity cache would still miss — the cache key
needs to be something stable across copies (e.g. keyed by original player + game turn number,
or computed once and threaded through the call chain as a parameter rather than recomputed
inside `GameStateEvaluator`). Also worth auditing whether `AiDeckStatistics` is called anywhere
else inside a hot simulation path with the same issue.

**Also needed:** a hard sub-process watchdog for any future orchestrator-launched sim run —
this session's smoke test had no wall-clock backstop independent of the harness's own
per-command monitoring, and a hung/OOM'd JVM burned CPU for 6+ hours before a human-initiated
check caught it. `run_parallel.sh`/its caller should verify liveness (e.g. periodic `wc -l` on
the output file actually advancing) and kill+report on a stall, not just wait indefinitely.

**Fix implemented, session 2 (2026-07-04):** Added an `equals()`/`hashCode()`-keyed
`ConcurrentHashMap<Deck, AiDeckStatistics>` cache inside `AiDeckStatistics` itself
(`forge-ai/src/main/java/forge/ai/AiDeckStatistics.java`), populated in `fromDeck()`. Key
design points (also documented in-code):
- **Not identity-keyed.** An identity (`IdentityHashMap`) cache was tried first and is
  *wrong*: `RegisteredPlayer`'s constructor unconditionally calls `restoreDeck()`, which does
  `currentDeck = originalDeck.copyTo(...)` — a brand-new `Deck` object every time a
  `RegisteredPlayer` is constructed, including every `GameCopier#clonePlayer()` call. Deck
  object identity is therefore NOT preserved across simulation copies (verified by a failing
  unit test before this was caught and fixed). `Deck#equals()` does a full deep
  content comparison (name + every `DeckSection`'s `CardPool`, see `Deck.java`), and
  `copyTo()` preserves content exactly, so an equals-keyed map hits correctly across the
  simulation tree without risk of aliasing two different decks that happen to share a name.
- The `deck.isEmpty()` fallback path in `fromPlayer()` (synthetic/test states with no
  registered deck, reads live hand/library) is intentionally NOT cached — that data changes
  turn to turn.
- Added `AiDeckStatistics.{resetInstrumentationCounters, getCallCount, getComputeCount,
  clearCacheForTests}` — test-only counters proving cache-hit behavior, not used by
  production code paths.
- New test `forge-gui-desktop/src/test/java/forge/ai/simulation/AiDeckStatisticsCacheTest.java`
  (3 tests, all passing): proves (a) cached and uncached computation return numerically
  identical `AiDeckStatistics` — pure performance fix, no behavior change; (b) 25 repeated
  calls against the same `Deck` object compute exactly once; (c) — the case that matters —
  11 `RegisteredPlayer`/`Player` instances all backed by content-equal (but object-distinct,
  mirroring `GameCopier`) `Deck`s still compute exactly once.
- Audited all callers: the only `AiDeckStatistics.fromPlayer`/`fromDeck` call anywhere in
  `forge-ai/src/main/java/forge/ai/simulation/` or `forge-ai/src/main/java/forge/ai/ultron/`
  is `GameStateEvaluator.getScoreForGameStateImpl()` line 185 (`evalManaBase()`'s input).
  `ComputerUtilMana.java` calls `AiDeckStatistics.fromCards()` (a different, cheaper method
  that operates on an already-materialized hand, not a full deck reparse) — out of scope,
  left untouched.
- Baseline regression check: `forge.ai.simulation.*` + `forge.ai.ultron.*` = 233/233 passing
  (230 baseline + 3 new), `forge.ai.llm.runtime.Ultron*` = 34/42 passing — both exactly match
  the documented pre-existing baselines, zero regressions.

**Session 2 findings — real-game smoke test still OOMs:** Ran
`tools/simstats/run_simstats.sh` directly (single JVM, watched live, NOT via
`run_parallel.sh`) against a 3-game config (`Ultron, Default, Default, Default`, Battlebox
Monarch, same `bannedCards` list as `configs/simstats/v3_ultron_vs_default_4p.ini`,
`-Xmx3g` to match the original crash) at `/home/william/agents/scratchpad/v3_ticket207_verify.ini`.
Game 1 OOM'd `java.lang.OutOfMemoryError: Java heap space` **during its very first
`chooseSpellAbilityToPlay()` call** (`PhaseHandler.startFirstTurn` → `mainGameLoop` →
`mainLoopStep`), well before the "many candidates × nesting depth compounding over a full
game" scenario this ticket originally described. Stack:
```
forge.ai.simulation.GameCopier.addCard(GameCopier.java:495)
forge.ai.simulation.GameCopier.copyGameState(GameCopier.java:290)
forge.ai.simulation.GameCopier.makeCopy(GameCopier.java:130)
forge.ai.simulation.GameSimulator.<init>(GameSimulator.java:33)
forge.ai.simulation.SpellAbilityPicker.evaluateSa(SpellAbilityPicker.java:358)
forge.ai.simulation.SpellAbilityPicker.chooseSpellAbilityToPlayImpl(SpellAbilityPicker.java:172)
forge.ai.simulation.SpellAbilityPicker.formulatePlanWithPhase(SpellAbilityPicker.java:110)
forge.ai.simulation.SpellAbilityPicker.createNewPlan(SpellAbilityPicker.java:131)
forge.ai.simulation.SpellAbilityPicker.chooseSpellAbilityToPlay(SpellAbilityPicker.java:104)
forge.ai.ultron.UltronPlayerController.chooseSpellAbilityToPlay(UltronPlayerController.java:1282)
```
i.e. the OOM fired while allocating during a `GameCopier.makeCopy()` deep-clone itself
(`Zone.add` → `Game.fireEvent` → Guava `EventBus.post` → `ThreadLocal` allocation), not
inside `AiDeckStatistics`. As predicted by this ticket's own prior note ("the OOM did not
cleanly exit the process"), the JVM then hung indefinitely: `jstack` showed the main thread
stuck in `WAITING` inside `forge.error.BugReportDialog.show()` → AWT `Dialog.show()` →
`Object.wait()` on an `AWTTreeLock` — the uncaught-exception handler tried to pop a GUI
dialog in this headless sim environment and deadlocked forever. Killed manually
(`kill -9`) after confirming via `ps`/`jstack` it was permanently stuck, ~13 minutes into the
run, rather than letting it burn CPU unattended.

**What this means:** the `AiDeckStatistics` cache fix is real, correct, and worth keeping —
it removes a genuine combinatorial-allocation source proven by the unit tests above — but it
is **not sufficient** to resolve the crash this ticket was opened for. The dominant
contributor in this run was the cost/volume of `GameCopier.makeCopy()`'s full deep-copy of
game state (all zones, all cards, shared Battlebox zones per TICKET-V3-201) being invoked
repeatedly by `SpellAbilityPicker`'s recursive candidate search, independent of what each
copy is then used to evaluate. Not yet investigated (next session should pick this up):
- How many `GameCopier.makeCopy()` calls actually happen for one `chooseSpellAbilityToPlay()`
  decision at the start of a real 4-player Battlebox game (add a call counter analogous to
  `AiDeckStatistics`'s, to `GameCopier.makeCopy()` or `GameSimulator`'s constructor).
  `SpellAbilityPicker.formulatePlanWithPhase`'s recursion depth/breadth against a full
  starting hand + shared Battlebox library is a plausible combinatorial source independent of
  `AiDeckStatistics`.
- Whether `-Xmx3g` is simply too tight for 4-player Battlebox's shared-zone deep-copy cost
  regardless of the above (the production default in `run_simstats.sh` is `-Xmx8g`;
  `run_parallel.sh` computes a RAM-budget-based per-worker heap that could be as low as `2g`).
  Worth a controlled A/B: same config at `-Xmx8g` to see whether it's a hard leak (OOMs
  regardless of heap) or a tight-heap-only problem (completes fine at 8g) — not run this
  session due to time already spent confirming the 3g failure and the priority of reporting
  this honestly rather than continuing to iterate unsupervised.
- The uncaught-exception-handler-hangs-forever-in-headless-mode bug
  (`ExceptionHandler.uncaughtException` → `BugReporter.reportException` →
  `GuiDesktop.showBugReportDialog` → AWT `Dialog.show()`) is itself worth a fix independent of
  this ticket: any OOM or uncaught exception in a headless sim run currently hangs the JVM
  forever instead of exiting, which is exactly the "burned 6+ hours" failure mode this ticket
  already flagged once. Consider forcing headless/non-interactive error handling
  (`GraphicsEnvironment.isHeadless()` check, or a config flag) in `SimulateStats`'s entry
  point so a crash exits promptly instead of hanging.

**Status: still BLOCKING.** Do not run the deferred Phase 1 smoke test or the Phase 2
600-game statistical gate until the `GameCopier`/recursive-candidate-search allocation volume
is characterized and addressed (or proven benign at production `-Xmx8g`).

**Session 3 update (2026-07-04): headless-hang fix landed; heap-sizing diagnostic supports
hypothesis (b) — real allocation problem, not a heap-sizing problem. Ticket remains BLOCKING.**

**(1) Headless-crash-hang fix (independent of the OOM root cause, landed and verified):**
`ExceptionHandler.uncaughtException`/`.handle()` (`forge-gui/src/main/java/forge/error/ExceptionHandler.java`)
now check `GraphicsEnvironment.isHeadless()` before calling `BugReporter.reportException(ex)`.
When headless, a new `handleHeadlessCrash()` prints the full stack trace to stderr (already
tee'd to the active per-process log file by `registerErrorHandling()`'s `MultiplexOutputStream`)
and calls `System.exit(1)` instead of letting `BugReporter` reach
`GuiDesktop.showBugReportDialog` → `BugReportDialog.show()`, which blocks forever on
`Object.wait()` against the AWT tree lock with no display and no user to dismiss it — the exact
mechanism behind both multi-hour silent hangs this ticket has now hit. Same pattern precedent as
BUG-005's `GraphicsEnvironment.isHeadless()` guard in `GuiDesktop.initializeScreenScale()`.
Verified with a standalone harness (`HeadlessCrashTest`, not committed — ad hoc, run from
`/tmp`): initializes `GuiBase`/`ExceptionHandler` exactly like `Main.main()`, throws an
uncaught `OutOfMemoryError` on a background thread named `sim-worker-thread` (mirroring the
real OOM's thread), and confirms under `-Djava.awt.headless=true` with a bounded 30s `timeout`
that the JVM logs the full stack trace and exits with code 1 in under a second — no hang. Before
this fix, the equivalent scenario (session 2) required a human to `jstack`/`kill -9` a wedged
JVM after 13+ minutes; now it self-terminates immediately. This fix does **not** cover every
possible hang mode (see finding 2's JVM-signal-dispatch case below) but eliminates the specific,
previously-proven `BugReportDialog` mechanism entirely, for any uncaught exception on any thread.

**(2) Heap-sizing diagnostic — hypothesis (b) (real leak/inefficiency) is supported, not
hypothesis (a) (just needs more heap):** Re-ran the identical 3-game
Ultron-vs-3xDefault config (`/home/william/agents/scratchpad/v3_ticket207_verify.ini`, same
seed 910123, same `bannedCards`) at `-Xmx8g` — 2.67x the `-Xmx3g` that OOM'd in session 2, and
matching `run_simstats.sh`'s own production default heap size. Single JVM, watched live, no
parallel workers. **Result: the JVM still exhausted its 8g heap before completing even game 1**
(`games.jsonl` remained at 0 lines for the entire run) and ended up in a state markedly worse
than session 2's clean `OutOfMemoryError`: the log shows only
`OpenJDK 64-Bit Server VM warning: Exception java.lang.OutOfMemoryError occurred dispatching
signal SIGTERM to handler - the VM may need to be forcibly terminated` — meaning the heap was so
completely exhausted that the JVM couldn't even service a `SIGTERM` cleanly (this is a
JVM-internal signal-dispatch failure, a level below anything an application `UncaughtExceptionHandler`
can intercept, so finding (1)'s fix does not — and structurally cannot — catch this particular
failure mode; it required a manual `kill -9`, confirmed via `jstack` failing to attach within
10.5s and `ps` showing the process alive 26+ minutes past the point the outer `timeout 1500`
wrapper had already given up waiting on it — `timeout` only signals its direct child, the
`run_simstats.sh` bash script, and does not forward the signal to the java grandchild, which
was silently orphaned and kept running/thrashing well past the intended 25-minute bound. No
kernel OOM-killer entries appeared in `dmesg`/`journalctl -k` for this window, and `MemAvailable`
never fully bottomed out to zero — so this was a Java-heap-space exhaustion within the JVM's own
`-Xmx8g` ceiling, not the host running out of physical RAM. **This is the key evidence against
hypothesis (a):** if the problem were merely "Ultron's nested `GameCopier` search needs more
than 3g," 8g (nearly 3x) should have given ample headroom to clear 3 short Battlebox games,
especially since the control lane (500 games, all-Default, no Ultron) completed cleanly at the
*tighter* `-Xmx3g`. Instead, 8g wasn't enough to finish even one game's first decision cleanly.
**This points to hypothesis (b): `GameCopier.makeCopy()`'s call volume or its copies' lifecycle
(not being released/GC'd promptly) is the dominant cost, and no reasonable heap size will fix it
without also bounding the call count** — an unbounded or excessively wide `SpellAbilityPicker`
recursive candidate search is the leading suspect, consistent with session 2's stack trace
showing the OOM firing from inside `GameCopier.copyGameState`'s own allocation, not from
whatever the copy was being used to evaluate.
**Caveat on environmental confound:** this diagnostic ran on a workstation with several other
concurrent agent sessions active (visible in `ps aux` throughout: opencode, multiple `claude`
processes), and system-wide `MemAvailable` was thin (2.6-3.4 GB) for parts of the run. The
kernel-level evidence above (no `dmesg`/`journalctl` OOM-killer hits) argues this was a genuine
Java-heap exhaustion rather than host-level memory starvation, but a fully idle-machine rerun
would be the cleanest possible confirmation and is recommended before fully closing this out.
**Instrumentation for a concrete "N copies per decision" count (the next planned step if
hypothesis (b) held) was not added this session** — the 8g run itself already demonstrated the
heap fills before even one clean game completes, which is sufficient evidence for the
hypothesis-(a)-vs-(b) question this session was scoped to answer; a call-count instrumentation
pass is left for the follow-up session that takes on the `GameCopier` allocation-volume
characterization/redesign this ticket has been deferring.
**Regression baseline unaffected:** `forge.ai.simulation.*` + `forge.ai.ultron.*` = 233/233
(unchanged), `forge.ai.llm.runtime.Ultron*` = 34/42 (unchanged) — the `ExceptionHandler` change
is isolated to uncaught-exception delivery and touches no simulation/AI logic.

**Status: still BLOCKING — hypothesis (b) supported, not resolved by heap sizing.** Do
**not** proceed to the deferred Phase 1 smoke test or the Phase 2 600-game statistical gate at
any heap size tested so far. This is now NEEDS-FOLLOWUP: a future session should (a) add the
`GameCopier.makeCopy()` call-count instrumentation described in session 2's findings and compare
against the P2.4-P2.6 candidate-pruning design targets (3-6 main-phase / 3-6 attack / 3-5 block
candidates), and (b) consider whether copies are being retained/leaked rather than promptly
GC'd. The headless-hang fix (finding 1) is DONE and safe to keep regardless of how the
GameCopier investigation resolves — it is a strict safety improvement with no dependency on the
OOM root cause.

**Session 4 (uncommitted at start of session 5, now confirmed real and worth keeping): recursion-guard
root-cause fix.** `declareAttackers` had **no** `SIMULATION_IN_PROGRESS` guard at all (unlike
`declareBlockers`), so every candidate scored anywhere in the decision tree — main-phase candidates
in `SpellAbilityPicker`, attack candidates, block candidates — paid for a full nested attack-candidate
search of its own via `GameStateEvaluator.getScoreForGameState` → `simulateUpcomingCombatThisTurn` →
`declareAttackers` on the copy. Fixed with a guard matching `declareBlockers`'s existing one, plus a
breadth cap (`MAX_LOOKAHEAD_CANDIDATES=6`) on `SpellAbilityPicker`'s recursive lookahead branch, a
depth cap (`setMaxRecursionDepth(1)`, Ultron-only) via `UltronPlayerController.chooseSpellAbilityToPlay()`,
and eliminating redundant `origGameScore` recomputation by threading the caller's already-computed
score through `GameSimulator`'s new 5-arg constructor. Verified via instrumentation
(`UltronGameCopierCallCountTest`, synthetic unit test): `GameCopier.makeCopy()` calls dropped from 975
to 21 for one main-phase decision. This fix is real, was independently re-read and confirmed correct
this session (touches only `GameCopier.java` comments-adjacent, `GameSimulator.java`,
`GameStateEvaluator.java`, `SimulationController.java`, `SpellAbilityPicker.java`,
`UltronPlayerController.java` — all still uncommitted in the working tree), and should be committed
once the session-5 finding below is also addressed or explicitly deferred.

**Session 5 update (2026-07-05): a NEW, more specific problem — real games now avoid OOM but hit
the full 1200s timeout with ZERO recorded Ultron decisions. Live jstack evidence obtained. Ticket
remains BLOCKING, but the diagnosis has changed materially: this is not a hang/deadlock, and is not
a setup-phase issue — it is the FIRST Ultron decision of the game alone taking well over 120 seconds
of genuine, progressing CPU-bound work to evaluate, and the per-decision `aiDecisionTimeoutSeconds`
mechanism does not apply to Ultron's decision path at all.**

**Methodology:** built the tree with session 4's uncommitted fix applied (`mvn -pl forge-ai,forge-gui-desktop
-am clean package -DskipTests -q`, succeeded). Created a fast-iteration config
(`/home/william/agents/scratchpad/v3_ticket207_jstack.ini`, `timeoutSeconds=120`, `games=1`,
`-Xmx6g` via `FORGE_SIM_XMX=6g`, otherwise identical to `v3_ultron_vs_default_4p.ini`: Ultron vs
3x Default, Battlebox Monarch, same `bannedCards`). Launched via `tools/simstats/run_simstats.sh`,
found the JVM pid via `pgrep -fa jar-with-dependencies.jar`, and captured **live** `jstack <pid>`
dumps at 30s, 60s, and 90s wall-clock — while the process was still running, not post-mortem.

**Result: confirmed no OOM** (`-Xmx6g`, exit code 0, clean run, no `OutOfMemoryError`) but the
single game timed out at the configured 120s (`"completedNormally": false, "timeout": true,
"elapsedMillis": 122856`) with **`ultron.totalDecisions == 0`** in `games.jsonl` — i.e. the exact
"zero decisions" symptom this session was dispatched to investigate reproduces reliably and fast
(2 minutes, not 20).

**Thread dump findings — same top-level frame across all 3 samples, but the actual work inside it
changes every time (progressing, not stuck):**
- All three dumps show the busy thread (`pool-3-thread-1`, the `TimeLimitedCodeBlock`/`FutureTask`
  worker that runs the whole game — see `SimulateStats.java:137`) pegged at effectively 100% CPU
  (31s → 43s → 48s of CPU time across the 30s/60s/90s samples), and all three are still inside the
  **very first** `UltronPlayerController.chooseSpellAbilityToPlay()` call of the entire game
  (`UltronPlayerController.java:1356` → `PhaseHandler.startFirstTurn` → `mainGameLoop` →
  `mainLoopStep`) — i.e. after 90+ seconds of pure CPU work, Ultron has not yet returned even its
  FIRST real decision.
- **30s dump:** stuck constructing a `GameCopier` deep copy — `GameCopier.copyGameState` →
  `addCard` → `createCardCopy` → `Card.fromPaperCard` → `CardFactory.getCard` → full
  card-script/trigger parsing (`AbilityFactory.getAbility` → `new SpellAbility` → `new
  SpellAbilityView` → `TrackableObject.<init>`), called from `SpellAbilityPicker.evaluateSa` →
  `chooseSpellAbilityToPlayImpl` → the depth-1 recursive lookahead branch → another
  `GameSimulator.<init>` → `makeCopy`.
- **60s dump:** different work entirely — resolving a card's stack effect
  (`RearrangeTopOfLibraryEffect.resolve` → `orderMoveToZoneList` → `ComputerUtil.scryWillMoveCardToBottomOfLibrary`
  → `ComputerUtilCard.evaluateCreatureList` → `CreatureEvaluator.evaluateCreature`) inside
  `GameSimulator.resolveStack`, same top-level `evaluateSa`/`chooseSpellAbilityToPlayImpl` frame.
- **90s dump:** yet different work — a nested nested combat prediction:
  `GameSimulator.<init>` → `ensureGameCopyScoreMatches` → `GameStateEvaluator.getScoreForGameState`
  → `simulateUpcomingCombatThisTurn` → `PhaseHandler.devAdvanceToPhase` → (base, non-Ultron)
  `AiController.declareAttackers` → `AiAttackController.notNeededAsBlockers` →
  `ComputerUtil.predictNextCombatsRemainingLife` → `AiBlockController.assignBlockersForCombat` →
  `ComputerUtilCombat.lifeInDanger`/`predictExtraPoisonWithDamage`/`predictDamageTo`. Notably this
  is `forge.ai.AiController.declareAttackers`, **not** `UltronPlayerController.declareAttackers` —
  the session 4 recursion guard is doing its job here (this copy's attacker is a `Default`-profile
  player, so there is no Ultron-vs-Ultron recursion to guard against in this instance), but the base
  combat-prediction machinery it falls back to is itself expensive and unavoidable.
- **Diagnosis: genuinely slow, progressing through expensive real work — NOT a hang, deadlock, or
  infinite loop**, and **NOT a setup-phase issue** (mulligan/deck-loading/`Match`/`Game`
  construction all completed well before these samples; every sample is already inside the first
  in-game AI decision). The three samples show three *different* stack frames, all descending from
  the same top-level call — consistent with real, varied, CPU-bound work happening across many
  sequential `GameCopier`/`GameSimulator` constructions and stack resolutions, just far more of it
  than fits in a 120s (or even a much longer) budget for this one decision.

**A second, distinct multiplier identified — not touched by session 4's fix, and not itself a
recursion/candidate-count problem:** `GameSimulator`'s constructor unconditionally calls
`ensureGameCopyScoreMatches(origGame, origAiPlayer)` whenever `advanceToPhase == null` (the common
leaf-candidate-evaluation path used by every real spell/ability candidate, for every AI profile, not
Ultron-specific). This method is a **debug sanity-check assertion** (`GameStateEvaluator.setDebugging(true)`,
then throws `RuntimeException("Game copy error...")` on mismatch) that recomputes
`eval.getScoreForGameState(simGame, aiPlayer)` from scratch on every single `GameSimulator`
construction — and that recomputation itself runs `simulateUpcomingCombatThisTurn`, a full nested
combat simulation (exactly the call chain seen in the 90s dump above). Session 4's fix correctly
eliminated the redundant *original*-game score recomputation (via the new `precomputedOrigScore`
5-arg constructor parameter) but left this second, unconditional *copy*-game score check completely
untouched — it is pre-existing code (confirmed via `git log`/`git diff`: not modified by the
uncommitted session 4 changes), used by every AI profile's simulation path, not gated behind any
flag or test-only condition. For a base/`Default`-profile AI with a small, shallow decision loop
this was presumably cheap enough to be invisible; for Ultron's depth-1/breadth-6 recursive search
against a real ~580-card Battlebox pool, this doubles the cost of every leaf-level candidate
evaluation and adds a full extra combat-prediction pass each time. **This is a real, specific,
well-characterized additional cost source that session 4's fix did not address — not a guess.**

**Also confirmed (code read, not jstack-derived): the `aiDecisionTimeoutSeconds=60` /
`FutureTask`-based per-decision timeout in `AiController.chooseSpellAbilityToPlayFromList()`
(`forge-ai/src/main/java/forge/ai/AiController.java:1668-1788`) does *not* apply to Ultron's
decision path at all.** It wraps only the base AI's simple candidate-loop method
(`chooseSpellAbilityToPlayFromList`), which `UltronPlayerController.chooseSpellAbilityToPlay()` /
`declareAttackers()` / `declareBlockers()` never call — Ultron's simulation-based decisions
(`SpellAbilityPicker`, `chooseAttackPlanViaSimulation`, `chooseBlockPlanViaSimulation`) have **no
per-decision wall-clock bound of their own**. The *only* backstop for a slow Ultron decision is the
whole-game `timeoutSeconds` (1200s in production), which is why a single pathologically expensive
decision can consume the entire game budget and still record zero decisions — there is no
intermediate circuit breaker between "instant" and "burn the whole game timeout."

**Not attempted this session (correctly out of scope per the session's own guardrails):** disabling
or gating `ensureGameCopyScoreMatches` behind a flag, and/or adding an Ultron-specific per-decision
timeout/circuit-breaker (falling back to inherited behavior on a slow decision the same way the
`RuntimeException` fallback already works for errors). Both are plausible, contained fixes, but
`ensureGameCopyScoreMatches` is shared by every AI profile (not Ultron-only) and is a genuine
correctness safety net (catches real `GameCopier` copy bugs) — disabling it needs its own dedicated
verification (confirm no real copy-divergence bugs are currently being masked, re-run the full
`forge.ai.simulation.*`/`forge.ai.ultron.*` suite, and re-run a real game to confirm decisions get
recorded) rather than a same-session guess-and-commit. Recommended as the concrete next step for a
follow-up session, in this order: (1) gate `ensureGameCopyScoreMatches` behind a cheap
debug/test-only flag (default off in production `SimulateStats` runs) and re-run this exact
`v3_ticket207_jstack.ini` config to see how much it changes the first-decision latency; (2) if
still too slow, add a per-decision wall-clock timeout inside `UltronPlayerController`'s three
guarded entry points (mirroring `AiController`'s existing `FutureTask` pattern) so a slow decision
degrades to `answeredBy=inherited` instead of eating the whole game's timeout budget.

**Status: still BLOCKING — session 4's recursion-guard fix is real, necessary, and confirmed
correct, but real Battlebox games still cannot complete even one decision inside a 120s budget.**
Do not commit session 4's fix in isolation as "resolves TICKET-V3-207" — it fixes the exponential
recursive-multiplication bug it targeted (confirmed by the 975→21 synthetic call-count test) but a
real game with a real ~580-card Battlebox pool is still far too slow, for the newly-characterized
reason above (unconditional double-evaluation-with-combat-simulation per leaf candidate, no
per-decision timeout backstop). No further sim runs (smoke, gate, or otherwise) should be launched
until at least the `ensureGameCopyScoreMatches` gating and/or an Ultron per-decision timeout is
added and a real game is confirmed to record actual decisions within its timeout.

**Session 6 update (2026-07-05): both recommended fixes landed and verified — the "zero decisions /
crash / hang" failure mode is gone, but full-game completion within the production 1200s budget is
still NOT achieved. Ticket remains BLOCKING for the Phase 1 smoke test and Phase 2 statistical gate;
this session's fix is real and worth keeping but does not fully resolve the ticket.**

**(1) Task A — `ensureGameCopyScoreMatches` gating (`GameSimulator.java`):** added
`GameSimulator.VERIFY_GAME_COPY` (`public static boolean`, default
`Boolean.getBoolean("forge.sim.verifyGameCopy")`, so it's off by default in every real
`SimulateStats` run and every AI profile, not just Ultron), plus a `setVerifyGameCopy(boolean)`
setter for tests. The constructor's `if (advanceToPhase == null) { ensureGameCopyScoreMatches(...); }`
became `if (advanceToPhase == null && VERIFY_GAME_COPY) { ... }`. Matches
`SimulationController.DEBUG`'s existing plain-static-boolean gating convention in this same package,
with a system property added for easy re-enabling without a recompile if `GameCopier` fidelity is
ever suspect again. Audited both call sites/tests that could depend on this firing
(`GameCopierBattleboxFidelityTest`, `SpellAbilityPickerSimulationTest`,
`UltronGameCopierCallCountTest`) — none assert on the `RuntimeException` this check throws or on an
exact `GameCopier.makeCopy()` call count, so gating it off by default required no test changes.

**(2) Task B — per-decision timeout backstop (`UltronPlayerController.java`):** new
`UltronConfig.maxSimDecisionTimeoutSeconds()` (env `ULTRON_SIM_DECISION_TIMEOUT_SECONDS`, default
40), a private `runWithDecisionTimeout(String, Callable<T>)` helper mirroring `AiController`'s
existing `FutureTask`-based per-decision timeout, and its use in all three guarded methods
(`chooseSpellAbilityToPlay`, `declareAttackers`, `declareBlockers`): each method's own simulation
search now runs on a dedicated `Thread`/`FutureTask`, bounded by `future.get(timeoutSeconds,
SECONDS)`; on timeout, a new `UltronDecisionTimeoutException` (a `RuntimeException`) is thrown and
falls into each method's *existing* `catch (RuntimeException)` fallback block — so a timeout and a
thrown exception now share one fallback/telemetry path, recorded honestly as `answeredBy=inherited`.
`SIMULATION_IN_PROGRESS` (the `ThreadLocal` recursion guard) is deliberately set/cleared *inside* the
worker thread's own work, not by the calling thread, since nested recursive calls (combat-lookahead
reentering `declareAttackers`/`declareBlockers` during scoring) happen synchronously on whichever
thread is actually running the search.
**Thread-safety hazard found and mitigated (task-mandated check, not skipped):** `GameSimulator.
debugPrint`/`debugLines` are JVM-global `static` fields, and `getAi().getSimulationPicker()` returns
a single shared, non-thread-safe `SpellAbilityPicker` instance per player. Since a timed-out worker
cannot be forcibly stopped (`Thread.stop()` is gone; the deep `GameCopier`/`GameSimulator`/
`SpellAbilityPicker` call chain has no cooperative interrupt checkpoint the way `AiController`'s
simple per-candidate loop does), an abandoned worker can keep running in the background — and a
*second* Ultron decision spawning its own worker while the first was still draining would race on
that shared mutable state. Fixed with a JVM-wide `AtomicBoolean SIM_WORKER_BUSY` compare-and-set
gate: at most one Ultron simulation worker may run at a time, across every player and all three
guarded methods; if a prior timed-out worker is still draining, the next decision skips spawning a
second worker entirely and falls back immediately (safe degrade, never a second concurrent writer).
This was not just a theoretical concern — the verification run below shows it actually firing for
real, protecting real games.

**Verification — the fast (120s, 1 game) `v3_ticket207_jstack.ini` config, rerun at
`/home/william/github/forge/simstats/out/v3_ticket207_jstack_s6/`** (`-Xmx6g`, same seed/banned-cards
as before): exit 0, clean, no `OutOfMemoryError`. **`ultron.totalDecisions` went from session 5's 0 to
2226** (`answeredByUltron=106`, `answeredByInherited=2120`, `coverageRatio=0.048`). `chooseSpellAbilityToPlay`
was called **114 times** for a combined 107.3s of the 121.2s wall-clock game (~941ms/call average) —
93% of those calls (106/114) were answered directly by Ultron's own simulation, not a fallback.
`declareAttackers`/`declareBlockers` were never reached this game (combat never occurred before the
120s cutoff). The per-decision timeout fired exactly **once** (`grep -c "exceeded its" run.log` = 1);
while that one worker drained in the background, **6** subsequent decisions correctly hit the
`SIM_WORKER_BUSY` gate and fell back immediately (`grep -c "still draining" run.log` = 6) rather than
spawning concurrent workers — direct, real-run proof the concurrency mitigation works, not just a
theoretical one. One final `InterruptedException` fallback fired at the very end, when the whole-game
120s timeout interrupted the main thread while it was parked in `future.get()` — expected, handled
cleanly, `games.jsonl` still recorded a clean `"timeout": true` record (not a crash).

**Longer verification — production `timeoutSeconds=1200`, games=2** (a dedicated scratch config,
`/home/william/agents/scratchpad/v3_ticket207_s6_fullrun.ini`, same seed/banned-cards/`-Xmx6g`,
output at `simstats/out/v3_ticket207_s6_fullrun/`), watched live via `jstack`/process polling, not
backgrounded-and-forgotten: **both games still hit the full 1200s timeout without completing** (game
0: `elapsedMillis=1201816`; game 1: `elapsedMillis=1204928`; both `"completedNormally": false`).
**But both now show massive real progress, not a hang:** game 0 recorded **4799 total decisions**
(`chooseSpellAbilityToPlay` called 247 times, 165 answered by Ultron across all methods combined;
`declareBlockers` was reached once — `candidateCount=2`, `chosenScore=-952` — proving combat
simulation itself works end-to-end in a real game, not just in unit tests); game 1 recorded **1197
total decisions** (`chooseSpellAbilityToPlay` called 163 times). Across both games: 10 per-decision
timeouts fired, 86 `SIM_WORKER_BUSY`-gate fallbacks, **zero** `OutOfMemoryError`s, both processes
exited 0 cleanly. **The catch: `chooseSpellAbilityToPlay`'s average cost is still ~3.1-3.4
seconds/call in these full-length games** (834.5s / 247 calls in game 0, 511.0s / 163 calls in game
1) — several times higher than the fast run's early-game ~941ms average, consistent with per-decision
cost scaling up as the board/zones fill with more cards over a real game's length. At that per-decision
rate, completing a full multi-turn Battlebox game (which needs many more than ~250 main-phase
decisions across 4 players) within a 1200s budget is not happening; the 40s-per-decision backstop
correctly prevents any *single* decision from consuming the whole budget, but does not fix the
aggregate cost of hundreds of still-multi-second decisions.

**Honest verdict: this session's two fixes are real, verified, and worth keeping — they eliminate the
crash (OOM), the hang (unbounded single-decision cost), and the "zero decisions" freeze session 5
found — but do NOT by themselves make a real Battlebox game complete within the production 1200s
timeout.** Per this ticket's own standing rule (sessions 2 and 3 both correctly declined to declare
victory prematurely), this is reported honestly rather than rounded up. **Do not commit this session's
changes as "resolves TICKET-V3-207."** They are staged, uncommitted, and correct — a future session
should build on them directly (do not re-diagnose from scratch) rather than reverting them.

**Recommended next steps for a follow-up session, in priority order:**
1. Profile `chooseSpellAbilityToPlay`'s per-call cost directly in a mid-to-late-game real Battlebox
   state (not just the early-game state this session's fast config exercises) — `GameCopier.makeCopy()`
   cost is known to scale with total cards in all zones (shared Battlebox zones are large), so a likely
   next lever is capping `SpellAbilityPicker`'s candidate breadth further for Ultron specifically (it is
   already depth-1 per session 4; breadth is still whatever `getCandidateSpellsAndAbilities()` returns
   naturally, unbounded by `MAX_LOOKAHEAD_CANDIDATES=6` only for the *recursive lookahead* branch, not
   the top-level candidate list itself — worth checking).
2. Consider whether `GameCopier.makeCopy()` itself has further avoidable cost for Battlebox's large
   shared zones specifically (e.g. avoiding a full deep-copy of zones that a given candidate's
   evaluation doesn't actually need to look at) — a bigger, riskier change than gating a debug check,
   correctly out of scope for this session.
3. Re-evaluate whether `timeoutSeconds=1200` is itself the right production budget for 4-player
   Battlebox specifically (as opposed to whatever budget the original 2-player-centric assumption
   used), separately from further speed work — but raising the timeout alone does not fix the
   underlying throughput problem, and a much larger per-game timeout multiplied across a 500-600 game
   statistical gate could itself become impractically expensive in wall-clock/compute terms.
4. Only once a real game is observed to *complete* (not just make progress) within a reasonable
   budget should the deferred Phase 1 smoke test or Phase 2 600-game gate be attempted — launching
   either now, with both of this session's own two test games timing out, would likely burn the
   gate's entire compute budget on excluded timeouts for near-zero win-rate signal (mirroring the
   TICKET-V3-001 control run's 14-hour wall time, but for runs that don't even produce usable data).

**Status: still BLOCKING for the Phase 1 smoke test and Phase 2 600-game statistical gate.** The
crash/hang/zero-decision failure modes this ticket was opened for are resolved and the fix is ready
to merge; the broader "a real Battlebox game completes in reasonable time" goal implicit in reaching
Phase 2 is not yet met and needs at least one more follow-up session per the recommendations above.

---

## UPDATE (2026-07-05, ~04:00) — likely root cause found via live jstack, NOT YET FIXED

**The planned automated mid-game jstack sampler (see below) had a bug and produced nothing** — its
own `pgrep` pattern matched its own shell process alongside the java PID, so `jstack` got called
with two PIDs concatenated into one bad argument and failed silently on all 4 scheduled samples
(`/tmp/v3_midgame_jstacks.txt` is worthless — don't waste time reading it). A single **manual**
`jstack` was grabbed instead, by hand, at **24.5 minutes into a real running game**
(`/tmp/v3_midgame_manual_jstack.txt`, PID 3444460, same `v3_ticket207_longtimeout.ini` config)
— the first-ever mid/late-game sample this ticket has collected.

**Finding:** a lingering, abandoned Ultron simulation worker thread (`"Ultron-Sim-
chooseSpellAbilityToPlay" #917`, already 457 seconds old — i.e. it had long since blown past its
own 40s timeout and was one of the "still draining in the background" workers the session-6 log
warnings describe) was caught, at the moment of the dump, stuck in:

```
Player.getAllOtherPlayers
  <- Player.getCardsActivatableInExternalZones
  <- Player.getCardsIn
  <- PlayerView.updateFlashback
  <- PlayerView.updateZone
  <- Player.updateZoneForView
  <- SharedPlayerZone.onChanged          <-- THE SUSPECT
  <- Zone.add
  <- GameCopier.addCard
  <- GameCopier.copyGameState
  <- GameCopier.makeCopy
  <- GameSimulator.<init>
  <- SpellAbilityPicker.evaluateSa/chooseSpellAbilityToPlayImpl/formulatePlanWithPhase/createNewPlan/chooseSpellAbilityToPlay
  <- UltronPlayerController.chooseSpellAbilityToPlay (via runWithDecisionTimeout's FutureTask)
```

Read `SharedPlayerZone.java` directly to confirm: `onChanged()` is overridden from the base
`PlayerZone` to loop over **every player sharing the zone** and call `player.updateZoneForView(this)`
on **each one, unconditionally, on every single card add** — no simulation/headless guard, no
batching. The base (non-shared) `PlayerZone.onChanged()` presumably only updates one player's view
per add; Battlebox's `SharedPlayerZone` (this fork's own invention, `TICKET-B001`/`TICKET-V3-201`)
multiplies that by the number of sharing players (4 in a standard Battlebox game) for the shared
Library/Command/Graveyard zones specifically.

**Why this is a strong lead, not just another guess:** `updateZoneForView`/`PlayerView` is UI
view-model bookkeeping — it exists to give a GUI something to render. During a `GameCopier.makeCopy()`
headless simulation copy, there is no GUI and the copied game is discarded after one evaluation —
every bit of that work is provably wasted. And the cost is structural, not incidental: it fires once
per `Zone.add()` call, multiplied by every card in every shared zone being reconstructed during
`copyGameState()`, multiplied again by the number of sharing players for `SharedPlayerZone`
specifically. This scales with (a) shared-zone size, which *grows* as a real game progresses
(graveyard accumulates, library cards move around) — consistent with the observed "cost doesn't
taper off, stays elevated all game" symptom — and (b) is unique to Battlebox's shared-zone
architecture, consistent with the all-Default control run (P0, no Ultron, but ALSO no simulation-
copying at all for Default profile) completing 500 games cleanly with no analogous slowdown, and
with the ORIGINAL 2-player-centric `GameCopier`/`SpellAbilityPicker` code never having hit this
because vanilla PlayerZone never fans out to multiple players per add.

**This supersedes hypothesis 1 in the section below** (generic "GameCopier per-copy cost may be
dominated by real card re-parsing") — that was an untested guess; this is a directly-observed stack
trace pointing at a specific, named, previously-uninstrumented method. It doesn't rule out that
card re-parsing ALSO costs something, but this view-update fan-out looks like the more likely
dominant term given how squarely it explains the "stays expensive throughout the whole game,
scales with shared-zone size" symptom specifically.

**NOT fixed this session — for Fable to assess and fix, per William's instruction to only collect
data tonight.** A plausible fix shape (untested, use judgment): skip/no-op `updateZoneForView`
calls entirely when the `Game` being mutated is a `GameCopier`-produced simulation copy (there is
likely already a way to tell — `Game` has state distinguishing real vs. copied/simulated instances
used elsewhere in this codebase; find it rather than inventing a new flag) — since no human/GUI
will ever observe a simulation copy's view state. Verify by re-running the same live-jstack method
on a real game post-fix and confirming (a) this specific stack shape no longer appears in samples
taken at various points through a full game, and (b) a real game actually reaches completion within
a reasonable timeout. This fix, if correct, is likely small and contained (per this ticket's
established "instrument first, small targeted fix, re-verify with a real game" discipline) but
touches `Zone`/`SharedPlayerZone`/`Player` — code shared with every other AI profile and the human
player path — so verify no regression to `PlayerControllerHuman`'s actual GUI rendering (a real human
game, not a sim copy, must still get its view updates).

Raw evidence file: `/home/william/agents/scratchpad/v3_ticket207_longtimeout.ini` (repro config),
`/tmp/v3_midgame_manual_jstack.txt` (the full 267-line dump this finding came from, on this
machine — copy it into the repo or re-capture if starting a fresh session elsewhere).

---

## ORCHESTRATOR SUMMARY FOR NEXT SESSION (2026-07-05, ~03:30) — read this first

Commit `91900a047f` on `ultron-v3` merges the session-4 recursion-guard fix with the session-6
debug-check-gating + per-decision-timeout-backstop fix into one commit. **This commit is real and
should NOT be reverted or redone** — it fixed three independently-verified bugs (recursion blowup,
redundant debug re-evaluation, missing timeout backstop) and took `totalDecisions` in a fast 120s
real-game test from 0 to 2226 (93% Ultron-answered). Test suite: 234/234 (`forge.ai.simulation.*` +
`forge.ai.ultron.*`), 34/42 (`forge.ai.llm.runtime.Ultron*`, unchanged pre-existing baseline).

**What is still broken, precisely:** two independent full-length real games — one at
`timeoutSeconds=1200`, one at `timeoutSeconds=2400` (40 minutes) — both hit their timeout without
the game reaching natural completion. This is NOT a timeout-budget mismatch (tested and rejected):
per-decision cost stays elevated (multi-second, periodically hitting the 40s per-decision cap)
*throughout the whole game*, not just in the opening turns. Whatever is expensive is expensive at
every stage of a real ~580-card Battlebox game, not something that tapers off once early setup
costs are paid.

**Diagnostic method that worked, use it again:** live `jstack <pid>` on the actual running JVM
while it's mid-decision, NOT post-mortem log/stack-trace analysis after a crash or timeout kill.
Every real finding in sessions 5-6 came from this. Config for fast iteration:
`/home/william/agents/scratchpad/v3_ticket207_jstack.ini` (120s timeout) or
`v3_ticket207_longtimeout.ini` (2400s, for late-game sampling). Launch with
`FORGE_SIM_XMX=6g bash tools/simstats/run_simstats.sh <config>` (foreground/nohup'd, NOT
`run_parallel.sh` — you want one JVM you can `jstack` directly), find its pid via
`pgrep -f "jar-with-dependencies.jar simstats"`, and `jstack <pid>` at whatever intervals you need.
**A mid/late-game thread-dump sample (10+ minutes into a real game) was never captured before this
note was written** — all prior jstack evidence (session 5) was from the first ~90 seconds of the
very first decision only. A background sampler was started at ~03:33 on 2026-07-05
(`/tmp/v3_midgame_jstacks.txt`, samples at ~5/10/15/20 min into a real `v3_ticket207_longtimeout.ini`
run, PID tracked via `/tmp/v3_jstack_loop.log`) — **check that file first**, it may already contain
the exact evidence needed to diagnose the sustained-cost root cause before spending time
re-deriving it.

**Hypotheses not yet tested, in likely-usefulness order:**
1. `GameCopier.makeCopy()`'s *per-call cost itself* (not call count, already fixed) may be
   dominated by real card-object construction/parsing on Battlebox's ~580-card shared library —
   i.e. every single copy, even just 21 of them, might cost multiple seconds each on real full
   decks vs. near-zero on the tiny synthetic test fixture. If true, the fix would mirror
   `AiDeckStatistics`'s content-keyed cache: cache/reuse parsed `Card` objects for shared-zone
   cards across copies instead of re-parsing `PaperCard` → `Card` from scratch every time. This
   was the leading hypothesis when this note was written but UNTESTED — no one has yet measured a
   single `GameCopier.makeCopy()` call's wall-clock cost on a real Battlebox game state.
2. Per-decision candidate count may grow with board complexity (more permanents/cards in play
   later in the game → more candidates → more copies+evaluations per decision) — if so, a
   turn-number- or board-complexity-aware candidate cap (tighter than the flat
   `MAX_LOOKAHEAD_CANDIDATES=6`/depth-1 caps already in place) might be needed specifically for
   late-game states.
3. `AiDeckStatistics`'s content-keyed cache (session 2/3 fix, `Deck.equals()`-based) may not
   actually be hitting as expected on REAL decks if something about real gameplay produces
   deck-content mutations between calls that the synthetic test never exercised (e.g. actual
   card movement between library/hand/battlefield/graveyard changing zone *contents* used in the
   equals comparison, even if total deck identity is conceptually the same) — worth directly
   instrumenting cache hit/miss counts on a real run, not just trusting the synthetic-test proof.
4. Consider whether full deep-copy-per-candidate simulation is fundamentally the wrong
   granularity for real decision throughput on this hardware/engine, vs. a cheaper interim
   heuristic (this would be a scope conversation with William, not a unilateral code decision —
   he was presented this framing at ~03:14 on 2026-07-05 and deferred full assessment to Fable).

**Do not repeat these dead ends:** heap size is not the problem (tested 3g/6g/8g — 8g and 6g both
avoid OOM but neither fixes completion; 3g still OOMs even post-recursion-fix, confirming heap
alone was never the story). Extending `timeoutSeconds` alone is not the fix (tested 1200s → 2400s,
both timed out). The recursion-guard and debug-check-gating fixes ARE real and correct — don't
re-diagnose or re-fix problems 1-3 from this ticket's earlier sessions, they're closed; only the
sustained-cost-throughout-the-game problem remains open.

**Configs available:** `configs/simstats/v3_ultron_vs_default_4p.ini` (the real Phase 2 gate
config, still at `timeoutSeconds=1200` — NOT yet updated, since the 2400s hypothesis was rejected;
do not bump this until the real fix is found and verified). `configs/simstats/v3_control_default_4p.ini`
(the all-Default control, unaffected by any of this — it completed 500 games cleanly at 3g/1200s,
proving this is entirely specific to Ultron's own decision path, not the engine generally).

**Do not commit anything as resolving TICKET-V3-207 until a real full game (not a synthetic test,
not a fast 120s partial run) is observed to reach natural completion** — that is the actual bar,
per the discipline this ticket's sessions have (mostly) maintained throughout.

---

### TICKET-V4-006 VERIFICATION (orchestrator, 2026-07-24 04:00) — logger proven end-to-end

The implementing session honestly flagged "no real end-to-end `.ini` dry run, no throughput
measurement with logging enabled." Both are now closed, plus one build defect found on the way
(see BUILD TRAP below — the first attempt produced **zero** log files because the shaded jar
predated the logger; config and code were correct throughout).

**Paired measurement** (`configs/simstats/v4_006_logged_dryrun.ini` — byte-identical to
`v4_004_default_1v1_corpus.ini` except `nnLogging=true`; same seed, same 20 games, same box):
20/20 completed, 0 timeouts, 0 OOM. **215 games/hr/worker with logging vs 272.5 without on the
same quiet box — roughly a 21% cost.** Acceptable: at 2 workers that is still ~430 games/hr,
~10,000/day. Note the unlogged 272.5 figure is itself higher than TICKET-V4-004's 236.8 because
that baseline was measured under contention from a concurrent Maven build; 272.5-vs-215 is the
honest apples-to-apples pair.

**Data verified with `tools/nn/read_nn_states.py` against real game output (not a fixture):**
379 records across 20 games, 758 perspective-samples. `schema_hash=0x330703df11234a17`,
`semantic_version=2`, `vector_len=1908` — all matching the current encoder. Placement labels
exactly balanced (379 firsts, 379 seconds — every 1v1 game has one winner and one loser).
Elimination turn populated correctly (`-1` for the survivor, the real turn for the loser).
**Turn coverage is complete** — a 19-turn game logs turns 1..19 with no gaps. Heuristic board
scores diverge meaningfully between seats and grow over a game (105 → 314), i.e. the U(s) anchor
input carries real signal rather than a constant.

**Two findings that shape Phase 2 (neither blocking):**
1. **Sample yield is ~38 perspective-samples/game, not the ~200 assumed in plan §2's revision** —
   the logger writes one record per turn (all observed records are `phase_ordinal=3`), and 1v1
   games are ~19 turns. So ~10,000 games/day yields **~390K samples/day**, not ~2M. For a
   ~1.1M-parameter net (1908×512 alone is 977K), that implies either a multi-day corpus, denser
   sampling (2-3 phases/turn — cheap, but adjacent states are highly correlated so the marginal
   value is low), or a narrower first layer. **Recommendation: size the net to the data via
   held-out loss rather than assuming 512; a 256-wide first layer halves parameters to ~570K.**
2. **The vector is sparse: median 117 of 1908 features nonzero (~6%).** Expected — pooled flags
   over small boards — but it reinforces (1): a 1908-wide input into 512 units, mostly zeros, is a
   lot of parameters per unit of signal. Worth measuring feature occupancy on the full corpus
   before fixing the architecture.

# WHERE THINGS STAND — ULTRON-V4, end of 2026-07-24 session (read this first)

**Phase 1 is complete and independently verified. Phase 2 is mid-flight, blocked only on a
corpus that is generating right now.**

**Running unattended:** tmux session `v4_007_corpus` — 8000-game Stage A bootstrap corpus
(`configs/simstats/v4_007_bootstrap_corpus.ini`), all-Default 1v1 Monarch, 2 workers x 4g,
started 04:04 on 2026-07-24, observed rate ~488 games/hr combined, **ETA ~16 hours (~20:00)**.
Output: `simstats/out/v4_007_bootstrap_corpus/shard_{0,1}/` — `games.jsonl` plus
`nn_states.bin.gz` (~4.7 KB/game, projecting ~38 MB total). Expected yield ~300K
perspective-samples. Check progress with `wc -l simstats/out/v4_007_bootstrap_corpus/shard_*/games.jsonl`.
If the JVMs are gone and the game count is well short of 8000, check the shard `run.log`s for
`OutOfMemoryError` before assuming success — though note this is the all-Default lane, which has
never OOM'd (TICKET-V4-003's failure is specific to Ultron's simulation search).

**Next session, in order:**
1. **Re-measure feature occupancy on the real corpus** before fixing the architecture. TICKET-V4-008's
   31.7% figure came from the smoke set and is knowingly overstated (1v1 padding leaves two opponent
   blocks permanently zero; MAIN1-only sampling kills 12 of 13 phase one-hot slots). Do not trim the
   vector or pick a width off that number.
2. **Train V0** (`tools/nn/train.py`, venv at `tools/nn/.venv`, torch 2.13.0+cpu). Recommended
   architecture 256->128 (523K params) rather than the plan's 512->256 (1.11M) — justify against the
   re-measured occupancy. Split by game ID (already enforced, with a self-test).
3. **Re-run the parity test against the newly trained model** — it is not optional and it is not a
   one-time check. It validates *a specific model's* weights round-trip into Java, so it must be
   re-run for every model that will ever be deployed:
   `mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true -Dtest=forge.ai.nn.UltronValueNetParityTest
   -Dultron.parity.dir=tools/nn/runs/<timestamp> -Dsurefire.failIfNoSpecifiedTests=false`
4. **Only then** wire `StateEvaluator`/`NeuralStateEvaluator` (plan §4.4) — deliberately untouched so
   far. Remember `summonSickValue`: `SpellAbilityPicker.java:226` compares it, not `value`, so the
   neural evaluator needs the second masked forward pass described in §4.4.
5. Gate per plan §5.4. **The 1v1 null hypothesis is 50%, not 25%** — see TICKET-V4-003's trap list.

**Do not skip the rebuild step before any sim run** — see BUILD TRAP immediately below. It has
already silently invalidated one verification run this session.

**Verified state at handoff:** 275/275 tests pass (that count includes the parity test actually
running, not skipping). Commits this session: `0146c6f082` (SharedPlayerZone sim-copy fix),
`571c322d8e` (encoder), `c76df31bc9` (land colors + semantic hash + logger), `f3eff69209`
(logger verification + corpus config), `8b9ca8dd2a` (trainer + Java inference + parity test),
plus tracker/plan docs. Nothing is wired into any AI decision path yet, so `master`-bound behaviour
is unchanged.

---

### TICKET-V4-009: Train V0 on the real bootstrap corpus, verify, stop (P2.2 continued) [2026-07-24]

**Scope discipline:** trained and verified V0 only. Nothing wired into any AI decision path —
no `StateEvaluator`/`NeuralStateEvaluator`, no `UltronPlayerController` change. `master`-bound
and non-`ULTRON_NN_EVAL` behavior is unchanged. Per the honesty rule this section enforces on
itself: **a trained model with a decent validation number is not a working AI.** Whether the net
helps is unknown until the Phase-2 gate (`gate.py`, N=600 vs Default, plan §5.4) runs, which is a
later ticket.

**Step 1 — feature occupancy, re-measured on the real corpus** (all 8,000 games,
`simstats/out/v4_007_bootstrap_corpus/shard_{0,1}/nn_states.bin.gz`, 142,229 records /
284,458 seat-block vectors / 7,996 games — the 4 timeout games are never written to disk at all,
per `UltronStateLogger.GameCollector#finish()`, so no explicit filtering was needed):
**673 of 1,908 features (35.3%) are ever nonzero; 1,235 (64.7%) are always zero.** Close to
TICKET-V4-008's smoke-set figure of 605/1,908 (31.7%), and for the reason that number predicted:
this is a real-data confirmation, not a smoke-set artifact fix. Checked both hypothesized causes
directly against the real corpus:
- **1v1 padding**: `phase_ordinal` distribution across all 142,229 records is `{3: 142229}` —
  every single record is 1v1 (`num_players` distribution `{2: 142229}`), exactly like the smoke
  set. The two structurally-padded opponent blocks (846 of 1,908 floats, `OPP_BLOCK_SIZE=423`
  each) are almost entirely dead: opp2 block has exactly 1 ever-nonzero float, opp3 has exactly 1
  (both are the `OPP_ELIMINATED` flag, which is always 1 for a padded seat — everything else in
  those two blocks is permanently zero by construction).
- **Single-phase sampling**: still true on the real corpus too — `GLOBAL_PHASE` is a 13-wide
  one-hot and only 1 of the 13 slots is ever set across all 284,458 seat-blocks. `UltronStateLogger`
  samples only at `MAIN1` entry; the real 8,000-game corpus does not change that, because it's the
  same 1v1-Default-vs-Default bootstrap logging config as the smoke run, just more games of it.
- Restricting to the "live" 1,062 floats (self block + the one real opponent block + global block,
  excluding the two structurally-dead padding blocks): **671/1,062 (63.2%) ever-nonzero** — up
  from the smoke set's 57% (603/1,062), consistent with more games surfacing more of the live
  card-feature/keyword slots, but the same shape.
- **Conclusion — this is not a smoke-set artifact, it is a property of the bootstrap corpus
  itself** (1v1-only, MAIN1-only sampling). Re-measuring on "more of the same corpus" does not
  fix it; only a corpus with 4p games (opponent-block occupancy) and/or wider phase sampling would.
  **Did not trim the input vector** — with ~65% of the vector permanently dead for structural
  reasons that are about to change (a future 4p/wider-sampling corpus will exercise those exact
  dead blocks), trimming now would just have to be undone. Recommendation for whoever generates
  the next corpus: widen `UltronStateLogger` sampling beyond MAIN1-only if there's any interest in
  the network ever conditioning on phase, and get 4p games into the mix before the padding-block
  deadness is treated as permanent.

**Step 2 — trained V0.** `tools/nn/train.py --data shard_0/nn_states.bin.gz shard_1/nn_states.bin.gz
--epochs 30 --hidden1 256 --hidden2 128 --alpha 0.5 --val-frac 0.15 --seed 1234` (all other flags
left at plan-recommended defaults). Ran `--self-test` first (split-by-game-id regression test):
**PASS**.
- **Architecture: 256→128, 524,432 params (matches TICKET-V4-008's export-param count of
  522,884 plus the small delta from PyTorch's exact `nn.LayerNorm`/`nn.Linear` param accounting —
  524,432 is the trainer's own live count, export-time strips the aux heads down to the same
  layout TICKET-V4-008 sized).** Kept the TICKET-V4-008 recommendation as-is rather than narrowing
  the first layer: the occupancy re-measurement in Step 1 says the dead 65% is a real-corpus-shape
  fact this specific corpus will outgrow (4p, wider sampling), not a permanent property of the
  encoder — narrowing now would bake in a limitation the next corpus is expected to remove, and
  256→128 (1.74 effective-params/sample against the ~284K real sample count) was already the
  conservative end of TICKET-V4-008's params-vs-corpus-size argument.
- **α = 0.5** (plan §5.1 default, as instructed — no annealing, single fixed value, no prior
  Ultron-in-the-loop games exist yet to anneal toward).
- **Split by game ID**, enforced by the trainer's own assertion path: 241,680 train samples
  (6,797 games) / 42,778 val samples (1,199 games). No game straddles the split (self-test
  above + this is the only split path in the trainer — there is no state-level split code left
  to accidentally hit).
- **Training**: early-stopped at epoch 12 (patience 5), best checkpoint at epoch 7 (lowest
  `val_value_logloss`). Held-out `val_value_logloss` (composite-target cross-entropy, the
  quantity early-stopping actually watches): **0.5094**.
- **Winner-prediction accuracy — reported both ways, deliberately, because they disagree and the
  gap is itself the finding:**
  - The trainer's own `val_winner_accuracy` (argmax of model output vs. argmax of the
    α-blended composite *target*, i.e. how well the net reproduces `0.5·placement + 0.5·U(s)`,
    not how well it predicts who actually won): **91.7%**. This number is what `metrics.json`
    reports and is easy to over-read — it is partly an easier target than raw outcome because
    `U(s)` (the heuristic board-share anchor) is itself a same-turn function of a state that
    correlates strongly with the state the net is looking at.
  - **The metric the ticket actually asked for — argmax(model) vs. the REAL eventual game winner
    (placement rank 1), computed separately by reloading the exported `model.bin` and checking
    against raw placement, independent of the α-blend**: **69.5%** overall on the 42,778 held-out
    (by-game) samples. Against the correct 1v1 null hypothesis of **50%** (not 25% — this is 1v1
    bootstrap data), that is real signal, clearly above chance, but well short of "solved."
- **Calibration by game stage** (turn / game_length; both the trainer's composite-target loss AND
  the true-winner accuracy, since they tell different parts of the story):
  | stage | n (val samples) | composite val_value_logloss | true-winner accuracy | mean P(actual winner) |
  |---|---|---|---|---|
  | early (<33% through game) | 12,688 | 0.596 | **53.6%** | 0.525 |
  | mid (33–66%) | 14,310 | 0.523 | 67.5% | 0.622 |
  | late (>66%) | 15,780 | 0.428 | 84.0% | 0.759 |
  **Early-game accuracy (53.6%) is barely above the 50% null hypothesis — say so plainly, per the
  honesty rule.** The net is doing most of its "winning" by reading late-game board states that
  are close to self-evidently decided, not by extracting early-game signal a heuristic couldn't
  already see. That's a real and useful finding: V0 has *not* demonstrated it can predict outcomes
  from early/ambiguous states, which is exactly the regime an eval function needs to be good in to
  help search. Whether it's still useful *relative to the hand-tuned heuristic* specifically
  (rather than in absolute terms) is unknown and is not something these offline numbers can answer
  — only the gate can.
- Deterministic seed (1234) throughout (`torch.manual_seed` + `random.seed`).
- Model path: **`tools/nn/runs/20260724-195756/`** (`config.json`, `metrics.json`, `model.bin`,
  `parity_vectors.bin`, `parity_python_probs.bin`). Gitignored, left in the working tree, nothing
  committed.

**Step 3 — parity test, re-run against THIS model.**
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=forge.ai.nn.UltronValueNetParityTest -Dultron.parity.dir=/home/william/github/forge/tools/nn/runs/20260724-195756
```
**PASS — max absolute deviation 3.576e-7 at record 90, slot 0 (tolerance 1e-5).** One trap hit and
recorded for the next session: `-Dultron.parity.dir` must be an **absolute path**. Surefire forks
the test JVM with its working directory at the `forge-gui-desktop` module dir, not the repo root,
so a relative `tools/nn/runs/<ts>` (as the ticket's own example command shows it) resolves to
`forge-gui-desktop/tools/nn/runs/<ts>` and the test fails on a missing-file assertion before it
ever gets to comparing outputs — not a parity bug, a path bug. 3/3 tests passed once corrected.

**What the next session needs:**
1. Wire `StateEvaluator`/`NeuralStateEvaluator` per plan §4.4, off by default, gated behind
   `ULTRON_NN_EVAL` through `UltronPlayerController`'s three guarded entry points (unchanged from
   how TICKET-V4-008 left the plan). Remember the **`summonSickValue` second masked forward
   pass** — `SpellAbilityPicker.java:226` compares `summonSickValue`, not `value`; the neural
   evaluator needs to run the forward pass twice, second time with the acting player's summon-sick
   creatures masked out of the battlefield pooling, exactly as §4.4 specifies. Map
   `p_win[self] ∈ [0,1]` to the integer `Score` scale via `round(p * 100_000)`; keep the existing
   terminal-state `MAX_VALUE`/`MIN_VALUE` short-circuit untouched.
2. **Then** gate per plan §5.4: N≥300 minimum, headline Phase-2 gate at N=600, ≥30% win rate vs.
   all-Default, one-sided p<0.05, seat-rotated same-seed paired run via `run_parallel.sh` +
   `gate.py`. **The null hypothesis is 50% for 1v1, not 25%** — this ticket's data is 1v1, but
   check what game mode the gate itself runs before reusing that number verbatim.
2b. Before trusting any gate result: re-read this ticket's early-game accuracy finding (53.6%,
   barely above chance) as a live risk — if the net is only reliably better than noise once a
   game is already mostly decided, it may not help search-time decisions made early in a game,
   even if it clears the gate on aggregate win rate.
3. If a future session widens `UltronStateLogger` sampling (more phases) or gets 4p games into
   the corpus, **re-run Step 1's occupancy measurement** — this ticket's 35.3%/64.7% split is
   expected to change substantially and should not be assumed stable across corpus generations.

---

### TICKET-V4-010: Wire NeuralStateEvaluator into the simulation search [IN_PROGRESS 2026-07-24]

Dispatched. Plan §4.4. **Highest clobber-risk ticket so far** — the evaluator lives inside
`GameSimulator` (`GameSimulator.java:95: eval = new GameStateEvaluator()`), which is shared by
every AI profile, so the wiring must leave the Default AI byte-identical. Integration surface
verified before dispatch:
- `GameStateEvaluator.getScoreForGameState(Game, Player)` is called at `GameSimulator.java:101,116,
  127,132,302`. That is the single method the interface abstracts.
- `SpellAbilityPicker.java:414` constructs `new GameSimulator(controller, game, player, phase,
  origGameScore)` — the only external construction site; the picker knows its `player`.
- **Default AI safety:** Default runs `useSimulation=false` (`AiController.java`), so it uses the
  fast heuristic path, never `SpellAbilityPicker`/`GameSimulator`, in these sim runs. Only Ultron
  drives `getSimulationPicker()`. So gating the neural evaluator behind Ultron-intent + a config
  flag cannot alter Default behavior — but this assumption must be preserved and stated, not
  silently relied on.
- `Score` carries `value` AND `summonSickValue`; `SpellAbilityPicker.java:226` compares the latter
  to implement "hold creatures in MAIN1." The neural evaluator must populate both (§4.4's second
  masked forward pass), which needs an encoder variant that zeroes summon-sick own creatures
  before battlefield pooling.
- Model artifact from TICKET-V4-009: `tools/nn/runs/20260724-195756/model.bin`
  (256->128, schema `330703df11234a17`, semver 2), parity-verified at 3.58e-7.

**Explicitly deferred to the orchestrator (NOT the implementing session): launching the win-rate
gate.** The implementing session wires + proves-in-a-smoke only. The gate is a multi-hour compute
commitment and is run only after the wiring's integrity is confirmed.

### TICKET-V4-011: Bound per-decision search so the V4-003 abandoned-worker leak can't OOM the gate [IN_PROGRESS 2026-07-24]

**User decision (2026-07-24): fix the leak before running the gate — a trustworthy win-rate
number is worth one more session; a biased one is what sank v2.** V4-010's smoke proved the neural
eval lets Ultron games *complete* (40s/246s) but game 3 still OOM'd: neural eval made evaluation
cheap, yet `SpellAbilityPicker` still `GameCopier.makeCopy()`s per candidate, so a complex-board
decision can exceed the 40s per-decision budget, get abandoned by `runWithDecisionTimeout`'s
FutureTask, and keep allocating (TICKET-V4-003's root cause: "no cooperative interrupt checkpoint
deep inside the search"). The abandoned worker's retained graph can't be GC'd → OOM at any heap.

**Goal:** no Ultron decision can trigger that leak. Two complementary levers, both Ultron-gated and
Default-safe:
1. **Cooperative deadline checkpoint (the root-cause fix):** the search checks a deadline between
   candidates and returns best-so-far when exceeded, so the worker finishes on its own thread
   instead of being abandoned mid-allocation. This is what V4-003 said was missing.
2. **Top-level candidate breadth cap for Ultron:** V3-207 session 6 noted the top-level candidate
   list is unbounded (only the recursive lookahead branch is capped at 6). Cap it with a cheap
   pre-ranking so typical worst-case decision time stays well under budget.

**Verify:** the exact 3-game `v4_010_smoke_nn_1v1_monarch.ini` config that OOM'd on game 3 must now
complete all games with `ULTRON_NN_EVAL=true`, 0 OOM. Flag-OFF and non-Ultron paths byte-identical
(278/278 stays green). Do NOT run the full gate — orchestrator runs it after confirming no OOM.

### TICKET-V4-013: Shallow+cheap — route combat scoring through the neural eval, cap breadth [IN_PROGRESS 2026-07-24]

**User decision (2026-07-24): after V4-011's deadline fix reduced but did not eliminate the OOM,
go "shallow + cheap" rather than keep patching deadlines onto an expensive search.** Make every
decision cheap enough that it never approaches the 40s budget → no abandonment → no leak. This is
the TD-Gammon design the plan is built on anyway (good value function + shallow search; deep search
was never the point).

**Root cost sink, confirmed by code read:** V4-010 wired the neural evaluator ONLY into
`SpellAbilityPicker` (main phase). Combat scoring — `UltronPlayerController.scoreAttackCandidate`
(line ~839) and `scoreBlockCandidate` (line ~1350) — still calls `new GameStateEvaluator().
getScoreForGameState(...)`, the HEURISTIC, which internally runs `simulateUpcomingCombatThisTurn`
(a SECOND GameCopier + combat advance). So each combat candidate pays a double game-copy of the
expensive heuristic. On a large late-game board that is what blew the 40s budget and OOM'd
V4-011's smoke (the crash was in `declareAttackers`).

**Fix (shallow + cheap):**
1. Route `scoreAttackCandidate`/`scoreBlockCandidate` through the resolved `StateEvaluator` (neural
   when Ultron+`ULTRON_NN_EVAL`+model, else heuristic), same selection as main phase. To keep
   correct post-combat semantics, advance the candidate's OWN copy through combat damage, then
   neural-eval that state (a cheap forward pass, NO extra copy) — eliminating the heuristic's
   internal second copy. Combat candidate counts are already small (2-4, per V3-204/205), so this
   makes combat decisions cheap.
2. Hard-cap main-phase top-level candidates small (`ULTRON_SIM_MAX_TOP_LEVEL_CANDIDATES`, set ~4
   for the gate) so no main-phase decision is expensive either.
3. Cheap insurance: extend the V4-011 deadline/best-so-far safety to the combat search too, so a
   pathological huge-board copy still can't blow 40s and leak.
Default-safe as always (neural path gated to Ultron+flag; flag-off byte-identical, 300/300 stays
green). Verify with the SAME 3-game smoke that OOM'd: all 3 complete, 0 OOM, and record per-game
time (must be sane for a 300-game gate). Only after that does the orchestrator run the gate.

### TICKET-V4-013 RESULT (2026-07-25): FAILED — combat now uses the neural eval, but real games still OOM/wedge

The combat-scoring change is real and correct (uncommitted in the working tree): `scoreAttackCandidate`/
`scoreBlockCandidate` now resolve the same triple-gated `StateEvaluator` as main phase and advance the
candidate copy to `COMBAT_END` (heuristic's own `simulateUpcomingCombatThisTurn` short-circuits, no
double-advance). But the 3-game smoke did NOT pass:
- game 0: 94s, completed.
- game 1: **906s, timed out** (did not finish within the 900s budget).
- game 2: ran **~85 min wall, ~52 min with ZERO log output, RSS ballooned to 24.5 GB** (heap cap 8g —
  swapping), CPU pinned — the V4-003 death spiral (abandoned worker OOMs, JVM thrashes in GC/swap
  instead of dying cleanly). Killed manually. **Orchestrator process note: a PID-`kill -0` watcher does
  NOT catch this — the JVM never exits, it wedges. A liveness watcher must check games.jsonl / log
  mtime ADVANCING, not just process-alive. This wasted ~1h unattended before a manual check caught it —
  the exact failure mode TICKET-V4-207 warned about, repeated.**

**Verdict after three cost-reduction attempts (V4-010 neural eval, V4-011 main-phase deadline, V4-013
neural combat eval): the copy-per-candidate simulation architecture cannot reliably complete a real
full-length Battlebox game, even with cheap neural scoring.** Each fix removed one cost source and the
next full-length game found another (main phase → combat → cumulative per-game allocation + shared-zone
growth). The neural VALUE FUNCTION itself is trained and works (V4-009: 64.9% held-out winner accuracy,
parity-verified). What does not work is Ultron's copy-heavy SEARCH. This is now a strategy decision for
the human, not another patch — see the options below. **Do not dispatch a fourth cost-patch without an
explicit decision.**

Candidate directions (for the human to weigh; not yet chosen):
1. **Value-only, (almost) no search:** have Ultron pick moves by neural-evaluating the handful of legal
   afterstates directly (1 cheap copy per legal move, no recursive GameSimulator, no per-candidate
   combat re-sim). This is the purest TD-Gammon design and structurally cannot hit the copy-explosion.
   Biggest departure from the current code; likely the real fix.
2. **Hard per-decision copy budget:** a global counter that hard-caps total GameCopier.makeCopy() calls
   per decision (e.g. 20) and returns best-so-far when hit — a blunt instrument that bounds cost
   regardless of which search path is running. Less invasive than (1); cruder.
3. **Accept it and stop:** the value network is a real, verified artifact; declare Phase 2 partially
   done and revisit search later.

**Independent corroboration (2026-07-25, second session, same HEAD, uncommitted per the same
"leave in working tree" instruction):** implemented the identical fix from scratch before
discovering this section already existed at HEAD — `resolveCombatEvaluator`/
`advanceCopyThroughCombatDamage` in `UltronPlayerController.java`, wired into both
`scoreAttackCandidate`/`scoreBlockCandidate`, plus the same Part 3 combat-search deadline
insurance. Confirmed correct in isolation: full regression **271/271** (18 explicit classes,
`forge.ai.simulation.*`+`forge.ai.ultron.*`+`forge.ai.nn.*` — the "300/300" figure in this
ticket's own opening stub does not match any run found this session; 271 is what the current test
tree actually contains) and `forge.ai.llm.runtime.Ultron*` **34/42** (same 8 pre-existing
"Ahead-state" failures), both green. Re-ran the same `v4_010_smoke_nn_1v1_monarch.ini` smoke
independently (same seed 910123) and got the **same qualitative failure**, with two new pieces of
evidence worth recording:
- **Combat itself is confirmed fixed:** zero `declareAttackers candidate search hit its deadline`/
  `declareBlockers candidate search hit its deadline` log lines the whole run (Part 3's insurance
  never even needed to fire), and the eventual `OutOfMemoryError` stack trace bottoms out in
  `Match.startGame → prepareBattleboxSharedLibrary → Card.fromPaperCard` (game 3's *setup*, before
  any Ultron decision runs) — not anywhere in `declareAttackers`/`GameStateEvaluator`, unlike the
  V4-011 baseline crash this ticket set out to fix. The combat-scoring cost sink is genuinely gone.
- **The leak has moved to (or was always partly in) main phase.** Game 1 (94.1s) was clean. Game 2
  hit its whole-game 900s budget (`905630 ms timeout`) after 3 cooperative "deadline exceeded ...
  returning best-so-far" catches (V4-011's mechanism working as designed) but also 2 *hard*
  "exceeded its 40s per-decision timeout; abandoning this decision" events — meaning at least twice
  a SINGLE top-level `chooseSpellAbilityToPlay` candidate itself ran long enough to blow the whole
  40s budget before the between-candidate deadline checkpoint ever got a chance to catch it
  (`deadline exceeded after evaluating 0/1 top-level candidates` — zero candidates completed).
  Those 2 abandoned worker threads have no way to be stopped (V4-003's mechanism) and keep
  allocating in the background indefinitely; game 3's unrelated card-loading allocation is what
  finally tipped the shared 8g heap over, not anything in game 3's own Ultron decisions. This is
  consistent with — and adds a concrete mechanism to — the "cumulative per-game allocation" theory
  in the verdict above, and points specifically at candidate _construction_ cost (most likely
  `GameCopier.makeCopy()` itself on this board size, independent of which evaluator scores the
  result) inside a single main-phase candidate, not at scoring.
- Net: **agrees with the verdict above.** Do not dispatch a further patch without the human's
  decision among the three directions listed; this session did not attempt options 1-3 and made no
  commits (per instructions). Combat-side code left uncommitted in the working tree, same as before.

### TICKET-V4-014: Version A — flat afterstate NN decisions + HARD per-decision copy budget [DONE 2026-07-25]

**Human decision (2026-07-25): build Version A — the NN judges every option directly, rip out the
crash-prone recursive simulation for Ultron.** After V4-010/011/013 (three cost-reduction patches)
all failed to complete a real game, the diagnosis is definitive: three independent cost sources
multiply, and soft deadlines can't bound them because they can't interrupt a copy in flight.

**The three cost sources (confirmed by code read of `SpellAbilityPicker.evaluateSa` +
`SimulationController`):**
1. **Lookahead recursion** — Ultron runs `setMaxRecursionDepth(1)` (UltronPlayerController:1622); each
   top-level candidate spawns a nested `MAX_LOOKAHEAD_CANDIDATES=6` search.
2. **Target/mode fan-out** — `evaluateSa`'s `do-while(choicesIterator.advance())` builds a fresh
   `GameSimulator` (full `GameCopier.makeCopy`) per target/mode combination of a SINGLE candidate. A
   spell with many legal targets = many copies for one candidate. This is the "single candidate blew
   the 40s budget" finding V4-013 hit; no breadth cap or between-candidate deadline touches it.
3. **Top-level candidate count** — already capped (V4-011).

**Version A design:**
- **depth-0 for Ultron** (`setMaxRecursionDepth(0)`): no lookahead. Each candidate scored by its
  immediate afterstate only. VERIFY depth-0 still scores each candidate's afterstate (the afterstate
  eval happens in `GameSimulator.simulateSpellAbility` → `eval.getScoreForGameState`, which is
  separate from the recursion — so depth-0 should give exactly "flat afterstate scoring, no
  lookahead"; confirm, don't assume).
- **HARD per-decision `GameCopier.makeCopy` budget** (the key new mechanism, and the robust belt the
  previous 3 attempts lacked): a per-decision counter, Ultron-only, default ~18. Checked BEFORE each
  new `GameSimulator`/`makeCopy` across ALL paths (main-phase target fan-out, combat candidates). When
  exhausted, the loops stop and return best-so-far. This hard-bounds total work AND allocation
  regardless of which cost source is exploding — a soft deadline can't interrupt an in-flight copy or
  fan-out; a copy COUNT checked before allocating is a true hard bound. Because decisions are now
  genuinely bounded (~18 copies, complete in seconds), the 40s FutureTask timeout should never fire,
  so no worker is abandoned, so no leak.
- Reuse (all done): NN eval main-phase (V4-010) + combat (V4-013 checkpoint), top-level breadth cap
  (V4-011), the deadline as a secondary backstop.
- Ultron-gated + Default byte-identical (regression 271/271 stays green).

**The bar (and the anti-wedge lesson from 2026-07-25's 1h wedge):** 3-game smoke completes ALL 3, 0
OOM, and NO single game exceeds ~5 min. Verification MUST use a progress-based watchdog (games.jsonl /
log mtime ADVANCING) with a hard per-game kill — a PID-alive watcher does NOT catch a GC-thrash wedge.
Orchestrator verifies independently before any gate.

### TICKET-V4-014 RESULT (2026-07-25): PASSED — all three prior failure modes are gone

**Change 1 (depth-0) verification — confirmed by code read, not assumed.**
`GameSimulator.simulateSpellAbility` (`GameSimulator.java:357`) computes `Score score =
eval.getScoreForGameState(simGame, aiPlayer)` **unconditionally**, and only AFTER that does it check
`controller.shouldRecurse()` (line 364) to decide whether to push a nested search. `shouldRecurse()`
(`SimulationController.java:78-80`) is `bestScore.value != MAX_VALUE && getRecursionDepth() <
maxDepth`; with `maxDepth=0` and `getRecursionDepth()==0` at the top of a decision, this is `0 < 0 ==
false` on every call, so recursion never fires — but the afterstate score above it always does. This
confirms the design's claim exactly: depth 0 yields "score each top-level candidate's own immediate
afterstate, pick the best" (flat, one-ply), not "no evaluation at all." Implemented as
`UltronConfig.maxSimRecursionDepth()` (default 0, env `ULTRON_SIM_MAX_RECURSION_DEPTH`, `intEnv`'s
"0 is falsy" guard doesn't fit here so it reads the env var directly), wired into
`UltronPlayerController.chooseSpellAbilityToPlay()`'s existing
`__picker.setMaxRecursionDepth(...)` call site (was hardcoded `1`, from TICKET-V3-207 session 4).

**Change 2 (hard copy budget) — implementation.** `UltronConfig` gained a `ThreadLocal<int[]>`
`[used, max]` per-decision counter (`SIM_COPY_BUDGET`) plus `resetSimCopyBudget()`/
`resetSimCopyBudget(int)` (test overload)/`clearSimCopyBudget()`/`tryConsumeSimCopyBudget()`
(check-and-increment atomically, the hard gate immediately before every real copy)/
`simCopyBudgetExceeded()` (peek-only, for between-candidate checkpoints)/`getSimCopyBudgetUsed()`.
`maxSimCopiesPerDecision()` defaults to 18, env `ULTRON_SIM_MAX_COPIES_PER_DECISION`. Wired at every
copy site identified in the diagnosis:
- `UltronPlayerController`'s three decision entry points (`chooseSpellAbilityToPlay`,
  `declareAttackers`, `declareBlockers`) call `UltronConfig.resetSimCopyBudget()` right after
  `SIMULATION_IN_PROGRESS.set(TRUE)` inside the `runWithDecisionTimeout` worker, and
  `UltronConfig.clearSimCopyBudget()` in the matching `finally` (alongside
  `SIMULATION_IN_PROGRESS.set(FALSE)`) — so the budget covers the whole decision tree on that worker
  thread, including any recursion, and can never leak into a later decision.
- `SpellAbilityPicker.chooseSpellAbilityToPlayImpl`'s top-level candidate loop: a
  `simCopyBudgetExceeded()` peek between candidates (mirrors the existing V4-011 deadline
  checkpoint), sets `lastSearchHitCopyBudget` and breaks with best-so-far.
- `SpellAbilityPicker.evaluateSa`'s `do-while(choicesIterator.advance())` target/mode fan-out (cost
  source #2, the one no prior patch touched): `tryConsumeSimCopyBudget()` immediately before `new
  GameSimulator(...)`, the actual hard gate — breaks the fan-out for this candidate, keeping
  whatever `bestScore` it already found.
- `UltronPlayerController.chooseAttackPlanViaSimulation`/`chooseBlockPlanViaSimulation`'s candidate
  loops: a `simCopyBudgetExceeded()` peek between candidates (mirrors the existing V4-013 deadline
  checkpoint; first candidate always scored regardless, matching that same contract), plus
  `scoreAttackCandidate`/`scoreBlockCandidate` each call `tryConsumeSimCopyBudget()` immediately
  before their own `copier.makeCopy(...)`.
Non-Ultron/no-budget-active paths: `SIM_COPY_BUDGET.get() == null` (the default, and the state for
every caller that never calls `resetSimCopyBudget()`) makes `tryConsumeSimCopyBudget()` always
return `true` and `simCopyBudgetExceeded()` always return `false` — unlimited, byte-identical.

**Regression: 274/274** (`mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true -Dtest=
AiDeckStatisticsCacheTest,GameCopierBattleboxFidelityTest,GameSimulationTest,
GameStateEvaluatorMultiplayerTest,NeuralStateEvaluatorTest,SpellAbilityPickerCopyBudgetTest,
SpellAbilityPickerDeadlineTest,SpellAbilityPickerEvaluatorSelectionTest,
SpellAbilityPickerSimulationTest,UltronCardFeatureTableTest,UltronCombatSimulationTest,
UltronGameCopierCallCountTest,UltronMainPhaseSimulationTest,UltronPlayerControllerTest,
UltronStackResponseSimulationTest,UltronStateEncoderTest,UltronStateLoggerTest,
UltronValueNetParityTest -Dsurefire.failIfNoSpecifiedTests=false -Dultron.parity.dir=
/home/william/github/forge/tools/nn/runs/20260724-195756`) — the same 18-class, 271-test baseline
plus 3 new tests in `SpellAbilityPickerCopyBudgetTest` (a tiny-budget-stops-the-search-and-returns-a-
legal-choice test, a no-budget-set-matches-generous-budget parity test, and direct counter-mechanics
coverage). `forge.ai.llm.runtime.Ultron*`: **34/42**, same 8 pre-existing "Ahead-state" failures
(unrelated to this ticket, unchanged from every prior session's baseline).

**Smoke (the bar this ticket exists to clear): PASSED cleanly, no caveats.** Rebuilt the shaded jar
(`mvn -pl forge-ai,forge-gui-desktop -am package -DskipTests -Dcheckstyle.skip=true -q`) and verified
`UltronConfig.class`/`UltronPlayerController.class`/`SpellAbilityPicker.class` inside it carry fresh
timestamps and the new method names (`maxSimCopiesPerDecision`, `resetSimCopyBudget`,
`tryConsumeSimCopyBudget`, `maxSimRecursionDepth`) before running anything — see BUILD TRAP. Ran the
exact config that failed three times before (`configs/simstats/v4_010_smoke_nn_1v1_monarch.ini`,
seed 910123, `ULTRON_NN_EVAL=true ULTRON_NN_MODEL_PATH=.../20260724-195756/model.bin
ULTRON_SIM_MAX_TOP_LEVEL_CANDIDATES=4`) in tmux under a progress-based watchdog (polling
`games.jsonl` line count + `run.log` mtime every 15s; would `jstack`+`kill -9` on >180s log-mtime
stall or >360s single-game wall, wrapped in an outer `timeout 1500`) — the watchdog never had to
intervene:

| game | completedNormally | timeout | elapsed | player turns |
|------|--------------------|---------|---------|---------------|
| 0    | true               | false   | 28.0s   | 16            |
| 1    | true               | false   | 16.8s   | 19            |
| 2    | true               | false   | 7.2s    | 14            |

**0 `OutOfMemoryError`, 0 copy-budget-hit log lines, 0 deadline-hit log lines, 0 40s-per-decision
abandonment log lines, 0 errors of any kind in `run.log` (201 lines total, all either startup noise
or the expected V4-011 top-level-breadth-cap warnings, e.g. "top-level candidate breadth capped 11
-> 4").** Every game finished in single-digit-to-thirty seconds, roughly two orders of magnitude
faster than V4-013's 906s-timeout/85-minute-wedge failure on the identical config and seed, and the
hard copy budget (default 18) was never even exhausted once across all three games — meaning depth-0
+ the top-level breadth cap already keep real decisions comfortably under budget in this lane; the
budget exists as a true ceiling for the pathological tail, not as something normal play leans on.
This is the first time in the project's history (V4-003 → V4-010 → V4-011 → V4-013 → V4-014) that
this exact smoke config has passed clean. Orchestrator should still run the full gate independently
per standing instructions, but the structural blocker this whole chain of tickets was chasing is
resolved: Ultron's decisions are now genuinely, hard-boundedly cheap.

### TICKET-V4-014 — 15-game reproducibility run: COMPLETION mostly fixed, but Ultron PLAYS PASSIVELY and lost 0/12 [2026-07-25]

Ran 15 games, seed 44556677 (distinct from the 3-game verify), Ultron(NN, depth-0, copy-budget) vs
Default, 1v1 Monarch, under a **progress-based watchdog** (log-mtime + per-game wall cap).

**Completion — largely fixed, watchdog works:** 13/15 recorded, 12 completed cleanly (9.4-42.6s,
median 16.8s), 1 timed out at 361s, and shard_0 wedged on its last game — the progress-watchdog
KILLED it at 225s (vs the 1h unattended thrash on 2026-07-25 earlier). So depth-0 fixed *most* games
but ~1-2 of 15 still hit a pathological slow/wedge state; the copy budget did not prevent it. The
watchdog is now the reliable backstop. Not a clean 15/15, but no more silent hour-long wedges.

**THE CRITICAL FINDING — Ultron lost every completed game, 0/12 (1v1 null=50%, p≈0.0002 — NOT noise).**
Per-game activity diagnostic (spellsCast / landsPlayed / attacksDeclared):
- **attacksDeclared = 0 in 11 of 12 games.** Ultron essentially never attacks.
- **spellsCast = 0-5 (mostly 0-3); Default casts 5-15.** Ultron barely develops.
- Ultron plays lands fine (3-9) but ends every game at negative life while Default sits at 16-24.
Ultron is DURDLING: playing lands, casting almost nothing, never attacking, losing passively.

**Leading hypothesis (strong): depth-0 removed the lookahead the value function NEEDS to see delayed
payoff.** With no lookahead, Ultron scores only the IMMEDIATE afterstate. A just-cast creature is
summon-sick — and the summon-sick masking (V4-010's second pass, which zeroes summon-sick own
creatures to implement "don't pre-combat-cast for nothing") makes a freshly-played creature score
~0 benefit RIGHT NOW. So "play creature" scores ≤ "pass" every time → Ultron never develops. Same
for attacks: attacking taps your creatures and exposes you; without lookahead to value the damage
dealt, the immediate post-attack state looks strictly worse → never attack. **The lookahead that
caused the OOM is the same lookahead that made the AI play sensibly. Removing it fixed the crash and
broke the play.** This is a fundamental tension, not a bug to patch.

**Implication for the plan:** pure-afterstate depth-0 with THIS value function does not work. Options
(for a rested human decision — do NOT auto-dispatch):
1. **Cheap shallow lookahead** — depth-1 but hard-bounded by the copy budget (which we now have) so it
   can't OOM. Restores the payoff-sightedness while keeping the crash bound. Most likely fix; test
   whether budget=18 keeps depth-1 completing.
2. **Fix the value function for afterstate use** — the net was trained to PREDICT outcomes on Default's
   states, not to rank a player's own immediate afterstates. Retrain with the deferred "future table
   share" aux head / TD targets so immediate afterstates reflect delayed value; and/or drop the
   summon-sick masking for the neural path (let the net judge summon-sick creatures directly).
3. Both.
The value network itself is fine as a *predictor* (64.9% held-out); the failure is using it as a
depth-0 *policy*. Diagnosis first, next session, rested.

### TICKET-V4-015: The durdle root cause — unconditional summon-sick mask (a SPEC bug, now fixed) [IN_PROGRESS 2026-07-25]

**Root cause of V4-014's 0/12 passive loss streak, found by code read — and it was a spec error in
`ULTRON_V4_NEURAL_PLAN.md` §4.4 itself (orchestrator's own), faithfully implemented by V4-010:**
`NeuralStateEvaluator` computed `summonSickValue` from a masked pass **unconditionally**, but the
heuristic it imitates (`GameStateEvaluator:219`) masks only `gamePhase.isBefore(MAIN2)`. With the
unconditional mask, a "cast creature" afterstate always shows: hand card gone + creature invisible
→ `summonSickValue` strictly below the pre-cast state → `SpellAbilityPicker:226` nulls the play —
**at every phase, forever**. Ultron could never develop a board; with no board it could never
attack (confirming evidence: game 11, the only game with real spell activity, is also the only game
with attacks). The 2 a.m. "depth-0 removed the lookahead the value function needs" hypothesis was
**wrong** — the failure was mechanical, not architectural.

**Fix (3 lines + docs + regression pin):** mask only before MAIN2; at MAIN2+ `summonSickValue =
value`, matching heuristic semantics exactly ("defer creatures to MAIN2", not "never cast").
Plan §4.4 corrected. New test `summonSickMaskIsPhaseConditionalNotUnconditional` pins both halves.

**Pre-registered decision rule for the 15-game re-run (same seed 44556677, same config — written
BEFORE seeing results):**
- Activity normalizes (median Ultron spells ≥5, attacks in ≥6 of completed games) → mechanical fix
  confirmed; the win rate is then the first honest read of the value net as a depth-0 policy.
- Activity normal + ≥3 wins of ~12 → promising; proceed to gate prep + quality work (multi-phase
  corpus, retrain) as improvement, not rescue.
- Activity normal + 0-1 wins → net quality/distribution is the binding constraint (trained only on
  MAIN1 states of Default-vs-Default games; asked to rank afterstates it has never seen). Next step
  is Fix B: multi-phase logging + retrain — BEFORE any gate.
- Activity still dead → diagnosis wrong again → stop, instrument per-decision scores, no more fixes
  without data.

**RESULT (2026-07-25 ~02:30, same seed 44556677, only the mask fix changed): activity fully
normalized AND Ultron went 5/10 — dead even with Default.** 12/15 games recorded (10 completed,
2 timeouts at the tight 360s cap, 1 wedge killed by the watchdog at 212s stall), 0 OOM. Median
Ultron spells: **9** (was 0-3); games with attacks: **7/10** (was 1/12). Wins are emphatic, not
flukes: game 2 = 17 spells / 19 attacks / opponent at -16; game 8 won at 25 life. Per-game median
53.7s (real games run longer than durdles — both players actually fight). **The diagnosis held:
one missing phase condition was the entire durdle.** N=10 is far too small to claim parity — the
Wilson interval on 5/10 spans roughly [24%, 76%] — but V0 (zero self-play iterations, trained
only on Default-vs-Default MAIN1 snapshots) playing recognizable, aggressive, winning Magic is
the strongest result in this project's history. Proceeding to the N=300 gate per the
pre-registered rule.

### TICKET-V4-016: THE GATE — N=300, Ultron(NN) vs Default, 1v1 Monarch, null=0.50 [IN_PROGRESS 2026-07-25]

Design (wedge-resilient, no duplicated seeds):
- **6 rounds × 50 games** (2 shards × 25 each), each round a fresh `run_parallel` with a
  round-distinct seed (20260726..20260731 — all distinct from the training corpus seed 20260724 and
  every measurement lane). `repeat` batching is NOT used: each batch would re-run identical
  (seedOffset + index) seeds → duplicate games in games.jsonl → corrupt gate stats. (Also found and
  fixed while designing this: `run_simstats.sh`'s batch loop read `batch_exit=$?` after a bare
  `java` under `set -e`, so any non-zero batch killed the wrapper and the "continue to next batch"
  logic was dead code — latent since TICKET-101. Fixed with `|| batch_exit=$?`.)
- Per-round progress watchdog (log-mtime stall >240s → kill that round's JVMs, move on) bounds any
  wedge to ≤ one round's remainder. Expected total ~4-5h at ~100s/game avg incl. tails.
- `timeoutSeconds=900` (the smoke's 360s cap caused at least part of its 2 timeouts; median
  completed game is 54s, max 159s).
- Gate stats: `gate.py <combined games.jsonl> --profile Ultron --null 0.5 --min-games 150`.
  **The null is 0.50** (1v1), not the 4-player 0.25. Pass per plan §5.4 = beats null at p<0.05;
  the honest expectation for V0 is "somewhere around parity," and the gate's job is to measure it,
  not to flatter it.

### TICKET-V4-016 RESULT (2026-07-25 ~05:20): V0 baseline = 25.5% [15.3, 39.5] at N=47 — V0 loses ~2:1 to Default. Harness cut short by monster-game OOMs; diagnosis complete.

**Substantive result (the headline):** across 47 counted games (12W/35L, 3 timeouts excluded),
Ultron(NN/V0) won **25.5%**, Wilson 95% CI **[15.3%, 39.5%]** — the upper bound is well below the
50% null. Combined with the smoke (17W/57 ≈ 30%): **V0 loses roughly 2:1 to Default. The 5/10
smoke was small-sample luck, exactly what its [24%, 76%] interval warned.** This is NOT a failed
project — it is the first statistically honest baseline Ultron has ever had, produced by a model
trained only on Default-vs-Default MAIN1 snapshots with zero self-play iterations. The plan
(§6 P3) always located the strength gains in the improvement loop, not V0.

**Why only 47 of 300:** every round ended early. Full diagnosis, each step verified:
1. Rounds recorded 5-12 games each. Round 3 died to **OutOfMemoryError in both shards** (3+2 OOMs);
   the other rounds' "wedges" were the same monster-game state caught by the round watchdog first.
2. **Not a cross-game leak:** round_3/shard_0's games ran 39/61/25/40/136s with no slowdown trend —
   then game 5 hit a 40s decision timeout at 03:19, OOM at 03:25. A SINGLE pathological game.
3. **Not the copy budget failing to arm:** verified the ThreadLocal reset runs inside the
   runWithDecisionTimeout callable (worker thread) — the budget is live. It caps copy COUNT; the
   monster is ONE copy/resolve cascade allocating gigabytes on a specific board state (~1 game in
   8-10). The 40s abandonment then leaks it (V4-003 mechanism) until OOM.
4. The gate's `timeoutSeconds=900` (chosen so slow-but-honest games could finish) made exposure
   WORSE than the smoke's 360s — more monster-seconds per game before the game-level kill.

**Next-session tickets (do these BEFORE any further gate):**
- **V4-017 (identify the monster):** add the active candidate/spell name + board size to the 40s
  per-decision-timeout WARN (one line), rerun ~30 games at 360s, identify the offending card/state,
  then apply the established BUG-007 remedy (`sim.bannedCards`) or a targeted fix. Do not attempt
  a general "bound single-copy allocation" — the card-ban precedent is cheap and proven.
- **V4-018 (Fix B, the actual improvement loop):** multi-phase + afterstate logging in
  `UltronStateLogger` (currently MAIN1-only — 12/13 phase slots dead in training), regenerate a
  corpus INCLUDING Ultron-in-the-loop games (they complete now), retrain V1 with the future-table-
  share aux head and TD targets, parity-test V1, THEN gate V1 at 360s with the round harness.
- Gate harness itself worked as designed (wedge cost bounded per round, no duplicate seeds, correct
  0.50 null); keep it, set `timeoutSeconds=360` in the template.

### TICKET-V4-017 COMPLETE DIAGNOSIS (2026-07-25): the "monster" is GameCopier card RE-PARSING the shared library, not a card. Keyword.values() fixed (partial); P0.2 card-caching is the real fix.

**Root cause, nailed by OOM stack + board census (not guessed):** the OOM stack is
`Keyword.values() ← smartValueOf ← CardFactory.getCard ← GameCopier card construction`. Every
`GameCopier.makeCopy()` re-parses cards from scratch via `CardFactory.getCard` (PaperCard→Card,
full script/keyword/trigger parse). In **Battlebox the shared library is large (hundreds of
cards) and is deep-copied+reparsed on EVERY copy regardless of board state** — which is why the
timeouts hit at *any* board size, including a captured **turn=1, perms=0** timeout (empty board,
yet a single decision OOM'd: the cost is the shared-library reparse, board-independent and
constant). This is the same cost the 2026-07-05 V3-207 orchestrator note flagged as untested
"hypothesis 1" — now confirmed by stack trace. It is NOT a specific card (censuses showed no
dominant card; tiny and large boards both OOM) so `sim.bannedCards` is the WRONG remedy here.

**Fix 1 (landed, verified, KEPT): `Keyword.smartValueOf` O(1) cached lookup.** It iterated
`Keyword.values()` per call, and `Enum.values()` clones its ~300-element array every invocation —
called for every keyword on every card parsed. Replaced with a one-time `HashMap` (first-in-enum-
order wins, identical semantics). 217/217 tests green. Real-run effect: OOM log lines dropped
**6 → 1** on the identical 30-game diagnostic. Engine-wide speedup (helps all card parsing, every
AI), so worth keeping regardless — **but it did NOT fully fix the monster**: the run still died at
4 games with 1 OOM + a wedge. Keyword was one hot spot in the parse, not the whole cost.

**Fix 2 (the REAL fix, NOT done — next ticket, Sonnet-appropriate): stop re-parsing the shared
library per copy (plan P0.2 card-object caching).** Two shapes, in order of preference:
  (a) **Cache parsed `Card` objects in `GameCopier`** keyed by paper-card identity, reused across
      copies within a decision (library cards don't change between a decision's candidate copies).
      Safe-ish, contained to `GameCopier`.
  (b) **Don't deep-copy the hidden shared library at all** — it's face-down; the encoder uses only
      its COUNT, and most decisions can't legally see library contents. Copying+reparsing hundreds
      of hidden cards per simulation is near-pure waste for the neural-eval path. Bigger win, but
      correctness-sensitive (mill/tutor/cast-from-top DO read the library) — needs care and its own
      tests. Recommend (a) first (lower risk), measure, then consider (b).
  Verify with THIS exact `configs/simstats/v4_017_monster_diag_1v1.ini` 30-game run: 0 OOM, ≥25/30
  complete, no wedge. The board-census logging (committed) will confirm no residual monster.

**Budget note:** this monster hunt consumed significant Fable orchestration. Fix 2 is well-specified
implementation — hand it to a Sonnet session. The 25% V0 baseline (V4-016) already stands as the
project's first honest result; the monster is an efficiency blocker for V4-018 data generation
(it tanked the gate to ~16% yield), not a blocker on the result itself.

### TICKET-V4-019: Kill the reparse monster via per-copy PRUNE_HIDDEN_INFO (Ultron-NN-gated) [IN_PROGRESS 2026-07-25]

**The machinery already exists** — `GameCopier.java:359 PRUNE_HIDDEN_INFO = false` (a `static final`
flag, currently off). When true, `createCardCopy` replaces any card the simulating player can't see
(`!c.getView().canBeShownTo(aiPlayer)` — which includes the entire hidden shared Battlebox library)
with the cheap `hidden_info_card` placeholder INSTEAD of `Card.fromPaperCard` (the expensive full
reparse the V4-017 OOM stack blamed). GameCopier.java:380 has a standing TODO confirming
`fromPaperCard` "accounts for the vast majority of GameCopier execution time." Enabling pruning for
the reparse-heavy path is the real monster fix (supersedes the V4-017 "cache Card objects" option (a):
this reuses tested machinery, less code).

**Why it can't just be flipped:** `PRUNE_HIDDEN_INFO` is `static final` and global — flipping it
changes EVERY `GameCopier` copy, including the Default AI's own `useSimulation` path and all existing
`forge.ai.simulation.*` tests, and would violate the Default-byte-identical invariant. It must be
**per-copy and gated to the Ultron neural-eval path**, where pruning hidden cards is provably safe:
the neural encoder uses only the library's COUNT, never its contents (`UltronStateEncoder` encodes
`shared library = count only`), so replacing hidden library cards with placeholders cannot change the
afterstate score.

**Task:** thread a `pruneHiddenInfo` boolean through `GameCopier.makeCopy` / its constructor (mirror
how V4-010 threaded the `StateEvaluator` — an optional param defaulting to today's behavior), set true
ONLY when the copy is made for an Ultron NN-eval decision (same triple-gate:
`nnEvalEnabled() && isUltronPlayer && NeuralStateEvaluator.isAvailable()`), false everywhere else.
Default/heuristic/tests: `PRUNE_HIDDEN_INFO` effectively stays false → byte-identical.

**Known correctness limit (document, don't block):** pruning hidden info is a determinization — it
mis-simulates effects that READ hidden zones (mill / tutor / scry / cast-from-top) during the
afterstate resolution, since they'd see placeholders. Acceptable for V0's afterstate value path (rare,
and the fallback is just an imperfect score on those specific spells); note it for the future
belief-state work (plan §7). Do NOT prune the aiPlayer's OWN hand/visible cards — only genuinely
hidden-to-them cards, which the existing `canBeShownTo` check already handles.

**Verify:** (1) monster diag `configs/simstats/v4_017_monster_diag_1v1.ini`, 30 games, NN eval on →
**0 OOM, ≥25/30 complete, no wedge** (before: 4 games / 6 OOM / wedge; after V4-017 Keyword fix:
4 games / 1 OOM / wedge; this should finish clean). (2) Full regression 273/273 green with flag off
(Default byte-identical). (3) Report throughput — pruning should also speed up completed games.
Progress-based watchdog (log-mtime > 450s → kill), NEVER a bare PID/self-matching pattern.

### TICKET-V4-019 RESULT (2026-07-25): OOM ELIMINATED; residual slow-decision hang remains (containable). Monster is now a TAX, not a run-killer.

Per-copy Ultron-NN-gated hidden-info pruning landed (commit `1da0e70a97`), Default-safe (232/232).
On the identical 30-game monster diagnostic: **OOM 6 → 0** (Keyword fix took it 6→1; pruning →0).
Completed games ran 30-165s (median 42s), normal speed.

**BUT the run still wedged at game 5** — clean 360s whole-game timeout, then a drain-hang: log shows
`chooseSpellAbilityToPlay exceeded its 40s per-decision timeout` → `declareAttackers: a prior
timed-out simulation worker is still draining`. This is the V4-003 root, **minus the OOM**: a single
pathological decision runs >40s, its worker is abandoned but cannot be cooperatively stopped, and it
hangs the JVM by pegging a core forever. Pruning fixed allocation, not the un-stoppable worker.

**Net: three fixes (Keyword cache, hidden-info pruning, earlier depth-0/copy-budget) turned an
OOM-crash-that-kills-the-whole-run into a slow-decision hang the round harness can CONTAIN** (kill
the wedged round's JVM via progress-watchdog, continue). The V4-016 gate already proved the round
harness survives this. So the monster is now a compute TAX (~1/8 games lost + occasional round
remainder), not a blocker on progress.

**DECISION PENDING (human, 2026-07-25):** (a) accept the tax and proceed to V4-018 (multi-phase
corpus + retrain V1 — the actual iteration loop where 25% starts moving), the round harness handling
the residual hang; or (b) one more focused session on cooperative cancellation to truly kill the
hang (deepest, least-cracked part of V4-003). Orchestrator recommends (a): the big win (no OOM) is
banked, the tax is survivable, and V4-018 is where the project's goal actually advances. Fixes to
KEEP regardless: Keyword cache (engine-wide speedup), hidden-info pruning (0 OOM), depth-0/copy-budget.

**For the V4-018 session, use the round harness** (`tools/simstats/run_gate_v4_016.sh` pattern:
rounds × distinct seeds, per-round progress watchdog killing on >450s stall) for ALL data
generation and gating — never a single long run that one hang can kill.

### TICKET-V4-018: Iteration 1 — V1 value net (multi-phase + in-the-loop corpus + retrain) [IN_PROGRESS 2026-07-25]

**Human decision (2026-07-25): proceed with the iteration loop (plan §6 Phase 3 / §5.1).** V0's 25%
has two identified, fixable causes, both about training DATA quality, not the architecture:
1. **MAIN1-only training data.** `UltronStateLogger.java:130` hard-filters `if (phase != MAIN1)` —
   the corpus contains ONLY first-main states, so the value net never trained on the combat /
   second-main / stack afterstates it is asked to score as a depth-0 policy (V4-009 found 12/13 phase
   one-hot slots dead). This is the leading suspect for the 25%.
2. **Off-policy data.** V0 trained only on Default-vs-Default games. Ultron's own game states (now
   reachable — games complete post-V4-019) are the ExIt signal the plan §5.3 calls for.

**Sequenced sub-steps (orchestrator drives; Sonnet for code):**
- **V4-018a (logger, Sonnet — DISPATCHED):** capture states at the phases where afterstates are
  actually scored — MAIN1, MAIN2, COMBAT_DECLARE_ATTACKERS, COMBAT_DECLARE_BLOCKERS (and stack-
  response priority if cheap) — not MAIN1 only. Dedup per (turn, phase). Verify a short logged run
  shows diverse `phaseOrdinal`s. This is the prerequisite for a richer corpus.
- **V4-018a-ext (logger, priority-based capture — William's call 2026-07-25):** the fixed 4-phase
  set (MAIN1/MAIN2/both combat) still MISSES instant-speed decisions — a counterspell held for a
  spell on the stack, a flash creature on the opponent's end step, removal in response. The NN is a
  priority-time decision-maker and must train on every priority window WHERE IT HAS PLAYABLE
  CANDIDATES, not just sorcery-speed phases. Fix: subscribe to `GameEventPlayerPriority` (turn,
  phase, priority — fires at every priority window), log the state when the priority player has ≥1
  playable candidate (a real decision, not a forced pass), deduped per (turn, phase, stack
  signature) so a distinct decision context logs once and volume stays bounded (~plan §5.1's
  ~200/game, NOT thousands). This SUBSUMES the 4-phase capture (main/combat are priority windows
  too) and adds instant-speed coverage. Corpus generation (V4-018b) waits on this — no point
  generating a corpus blind to instant timing. (The v4_018b run was launched then stopped at 0
  games when this gap was raised — zero compute wasted.)
- **V4-018b (corpus, orchestrator, tmux — no tokens):** ROUND HARNESS (per V4-019's note —
  `run_gate_v4_016.sh` pattern, per-round >450s-stall watchdog, distinct seeds), mixed population:
  Ultron(V0/NN) vs Default AND all-Default, 1v1 Monarch, nnLogging on, ~1500-2000 games. The residual
  hang is a contained tax here. Distinct seeds from every prior lane.
- **V4-018b DONE (2026-07-25):** corpus generated clean — 1500 all-Default 1v1 Monarch games, 0 OOM,
  **297,734 records / 595,468 perspective-samples (~2x V0), 12 distinct phases** including the
  instant-speed windows (upkeep 30K, end-of-turn 31K, all combat sub-phases). Multi-phase +
  priority-capture logger (V4-018a/a-ext) worked end-to-end. schema `330703df11234a17` (V0-compatible).
  Merged: `simstats/out/v4_018b_v1_corpus_default/nn_states_merged.bin.gz`.
- **V4-018c DONE (2026-07-25, Sonnet):** trainer memory fix + V1 retrain, V0's EXACT settings
  (256->128, α=0.5, seed 1234) on the new corpus, isolating the DATA effect (multi-phase +
  instant-speed) as a clean single-variable experiment.
  - **Bug (verified before fixing):** `train.py` (v0) did `records.extend(read_records(p))`,
    materializing every `Record` (each seat's 1908-float vector as a Python list, ~28 bytes/float)
    plus a second full copy in `Sample` objects plus a third transient copy in
    `torch.tensor([s.vector for s in samples])`. Fine for V0's smaller corpus, OOMs on V1's ~2x
    corpus (would need ~30 GB).
  - **Fix:** two-pass streaming load into preallocated numpy (`build_game_tables` counts N + builds
    per-game tables without retaining vectors; `build_dataset` re-scans and writes each sample
    directly into `np.float32` arrays; torch tensors built via `torch.from_numpy`, never
    `torch.tensor([python list of lists])`). By-game train/val split preserved, now operating on a
    parallel `game_id` numpy array. `--self-test` still PASSES (no game straddles train/val).
    Peak RSS measured during the real V1 run: **~1.3 GB during pass 1, well under 26 GB free** (vs.
    the ~30 GB the old code would have needed) — full run completed without ever approaching the box
    limit.
  - **V1 training run** (`tools/nn/.venv/bin/python3 tools/nn/train.py --data
    simstats/out/v4_018b_v1_corpus_default/shard_{0,1}/nn_states.bin.gz --hidden1 256 --hidden2 128
    --alpha 0.5 --seed 1234 --val-frac 0.15 --out-dir tools/nn/runs`): loaded 297,734 records /
    595,468 perspective-samples / 1500 games correctly; split 506,614 train (1275 games) / 88,854 val
    (225 games); 524,432-param model; early-stopped at epoch 8 (patience 5).
  - **Held-out (by-game) winner-prediction accuracy: 91.9% (0.9192)** vs **V0's 64.9%** — a large
    jump, consistent with the MAIN1-only-blind-spot theory (V0 never saw combat/instant-speed
    states at all). Composite val log-loss (value head): **0.4838** (val_placement_logloss 1.175,
    val_length_logloss 4.305 — aux heads, dropped at export).
  - **Per-phase-ordinal accuracy breakdown (NEW metric, val set)** — the whole point of V1: does it
    predict non-MAIN1 states competently? All 11 populated phase ordinals land in a **tight 91–94%
    band** (ordinal 1: 91.2% n=9074; ordinal 7: 93.7% n=378; ordinal 11: 92.8% n=9362; etc.) — no
    phase is meaningfully worse than the others, i.e. the model is NOT just "good at MAIN1 and
    guessing elsewhere." Early/mid/late-game log-loss calibration also improves through the game
    (early 0.586 -> mid 0.516 -> late 0.425), as expected (less uncertainty as games resolve).
  - **Parity test:** `mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true
    -Dsurefire.failIfNoSpecifiedTests=false -Dtest=forge.ai.nn.UltronValueNetParityTest
    -Dultron.parity.dir=tools/nn/runs/20260725-203035` — **3/3 tests PASS, max abs deviation
    4.77e-7** (tolerance 1e-5).
  - **Model path:** `tools/nn/runs/20260725-203035/model.bin` (config.json, metrics.json,
    parity_vectors.bin, parity_python_probs.bin alongside it).
  - Changed file: `tools/nn/train.py` (memory-fix refactor; math/target-construction/export format
    unchanged, verified via parity test). Not committed (per session constraints) — left in working
    tree for review.
  - **Next: V4-018d** — gate V1 (round harness, N=300, vs Default AND vs V0, promote only if it
    beats V0). NOT run in this session (explicitly out of scope).
- ~~**V4-018c (retrain V1, Sonnet):**~~ (superseded by the single-variable plan above) trainer gains the deferred future-table-share aux head (§5.1
  label 3) + TD(λ≈0.9) targets (now bootstrappable from V0) + the multi-phase data; train V1, parity-
  test it (`UltronValueNetParityTest` with the new `.bin`), report held-out accuracy + calibration.
- **V4-018d (gate V1, orchestrator):** round harness, N=300, `gate.py --null 0.5`, vs Default AND vs
  V0 (promote only if it beats V0 — plan §5.4). The correct 1v1 null is 0.50.

> ORCHESTRATOR CORRECTION [2026-07-25]: the V4-018c agent's "held-out accuracy 91.9% vs V0's 64.9% —
> large improvement" is a METRIC MISMATCH, not a real result. 91.9% is the trainer's built-in
> `val_winner_accuracy`, measured against the alpha-blended COMPOSITE target (inflated — the U(s)
> term correlates with the input). V0's SAME inflated metric was 91.7%, so on a like-for-like basis
> V1 ≈ V0 there (no gain). V0's 64.9% was the INDEPENDENTLY-computed TRUE-winner accuracy (argmax vs
> actual game winner) — a different, honest metric the agent did not recompute for V1. The real
> offline signal is MODEST: composite val log-loss 0.484 vs V0's 0.509 (same metric, lower=better),
> plus V1 now scores all 11-12 phases competently (V0 was MAIN1-only). Offline metrics are
> diagnostics; the win-rate gate (V4-018d) is the verdict. Do not repeat the 91.9%-vs-64.9% framing.

### TICKET-V4-018d RESULT (2026-07-26): V1 gated 8.2% — WORSE than V0's 25.5% — BUT the comparison is CONFOUNDED (orchestrator error). Disambiguation gate launched (V4-018e).

**Headline:** V1 (multi-phase + instant-speed corpus) won **6/73 = 8.2%** [Wilson 3.8%, 16.8%] vs
Default, 1v1, null 0.50. V0 was 25.5% [15.3%, 39.5%]. CIs barely overlap → a real drop, not noise,
even at N=73 (24/97 timeouts excluded — the residual hang tax; gate.py flags SAMPLE-TOO-SMALL for
the vs-50% test, but the vs-V0 drop is the meaningful signal). V1 is NOT durdling — median 7 spells
cast, attacks in 52/73 games — it plays actively and still loses ~92%.

**CONFOUND (my error, stated plainly): two variables changed between V0's gate and V1's gate, not
one.** V0's 25.5% gate (V4-016) ran on the pre-pruning runtime. V1's 8.2% gate ran with the V4-019
hidden-info pruning ON (it landed *after* V0's gate). Pruning replaces the opponent's hidden hand +
shared library with placeholders inside every simulation copy — which plausibly wrecks combat/
interaction judgment (Ultron simulates an opponent that has no tricks → overvalues attacks →
"attacks a lot, loses anyway," exactly the observed activity profile). **So the 8.2% CANNOT be
attributed to the multi-phase data.** Every prior validation of the approach (V0's 25%, the durdle-
fix 5/10 smoke) was also pre-pruning — V4-018d is the FIRST win-rate gate ever run with pruning on.
Strong possibility: **the OOM fix (pruning) traded crashes for bad play.**

**Disambiguation — V4-018e (LAUNCHED):** re-gate V0's model on the CURRENT (pruning) runtime, same
harness/seeds-distinct. Holds the runtime constant, isolates the model:
- V0-with-pruning ≈ 8% too → **pruning is the villain**; the placeholder approach corrupts
  simulation; abandon it for the real-card-caching monster fix (V4-017 option (a), previously
  deprioritized), and re-gate both models cleanly.
- V0-with-pruning ≈ 25% → the multi-phase **model** is genuinely worse; the blind-spot theory is
  wrong and adding instant-speed/off-distribution decision points hurt (the value fn acts badly at
  the new decision points it can't judge well).
Either outcome is decisive and reorders the roadmap. Do not draw conclusions from V4-018d until
V4-018e lands.

### TICKET-V4-018e RESULT (2026-07-26): confound CLEARED. Pruning is fine; the multi-phase MODEL is genuinely much worse. V0-with-pruning 37.3% is the BEST result ever.

**Clean same-runtime comparison (both models on the current pruning runtime):**
| model | training data | win rate vs Default (1v1, null 50%) | n |
|---|---|---|---|
| **V0** | MAIN1-only | **37.3% [26.1, 50.0]** | 59 |
| **V1** | multi-phase + instant-speed | **8.2% [3.8, 16.8]** | 73 |
CIs do not overlap → **decisive: the multi-phase training data made the model dramatically worse.**
(V0 pre-pruning was 25.5% [15.3,39.5]; V0 with pruning 37.3% — overlapping CIs, so pruning is
neutral-to-positive, NOT the villain. My V4-018d confound worry was correct to raise and is now
ruled out by data.)

**Two findings, one positive, one a valuable negative:**
1. **POSITIVE: V0-with-pruning = 37.3% is the strongest Ultron gate in project history** (all N are
   small, but this is the best point estimate and it completes runs without OOM). V0 (MAIN1-trained
   value net + pruning + depth-0 + copy-budget) is the current best deployable model.
2. **NEGATIVE (clean, decisive): naively training a plain value net on all-phase + instant-speed
   states, then acting on it, hurts badly.** Leading explanation, and it CONFIRMS the prediction of
   `ULTRON_THEORY_OF_MIND_STUDY.md`: V0 (MAIN1-only) is confident about main-phase development and,
   being off-distribution at instant speed, tends to PASS at instant-speed windows — which is
   *usually correct*. V1, trained to have confident opinions at instant-speed/off-phase windows but
   WITHOUT the belief/opponent-model machinery those decisions require, acts confidently-wrong there
   (bad instant-speed plays; overvalued attacks — its activity profile was 7 spells/game, attacks
   52/73, losing 92%). **V0's accidental instant-speed passivity was a feature, not a bug.** The ToM
   study argued a plain value net cannot judge instant-speed/bluffing decisions; this gate is the
   empirical confirmation — expanding the *decision surface* without expanding *judgment* is
   net-negative.

**Roadmap correction (supersedes the V4-018 "multi-phase fixes the blind spot" thesis):**
- **Keep V0 as the deployed/best model.** Do NOT ship V1. The multi-phase VALUE-NET-as-policy
  experiment is a clean negative — retire it.
- The multi-phase/instant-speed LOGGER is NOT wasted — it is the substrate for future belief/ToM
  work (study §3.3). But multi-phase data is not a value-net training improvement on its own.
- **The real levers (per gate evidence + ToM study), in order:** (1) value-function QUALITY via
  self-play / expert iteration against stronger/mixed opponents (not more phase coverage); (2) a
  stochastic policy + league play for the first emergent deception (study Tier 1); (3) belief/ToM
  machinery BEFORE the AI should act confidently at instant-speed windows — until then, restricting
  Ultron to sorcery-speed-ish decisions (V0's effective behavior) is better than acting everywhere.
- **Open sub-question (one cheap future experiment):** is V1 worse because the value FUNCTION
  degraded (even at main phase) or only because it now ACTS badly at instant speed? Test: run V1 but
  gate its decisions to MAIN1/MAIN2 only; if that recovers ~37%, it's the acting, not the function.

### TICKET-V4-021: Timeout-bias audit — the residual hang is an EARLY-GAME bug, not a late-game cost cliff [DONE 2026-07-26]

**Why this was run:** ~25% of all games in every gate are discarded as timeouts. That exclusion had
never been checked for bias, and every win-rate this project reports is computed on the surviving
75%. Audit script: `timeout_audit.py` (scratchpad), reading the final board state that
`games.jsonl` records *even for timed-out games* (`timeout:true` rows carry full `players[]` data).

**Result 1 — the 37.3% headline is not badly biased.** In the 20 discarded V0 games, Ultron's
position at the cutoff is essentially even: life 20 vs 20 (median), permanents 6 vs 5.5, hand 4 vs 4.
Ultron was ahead on life in 3/20, behind in 6/20, tied in 11/20. These are neutral early positions,
not disguised losses. Absolute bounds on the headline: 27.8% (if every discard were a loss) to 53.2%
(if every one were a win); the evidence says the truth sits near the reported 37.3%. **Conclusion:
keep using the completed-games win rate, but always report the discard rate alongside it.**

**Result 2 (the important one) — THE HANG IS NOT WHAT V4-019 ASSUMED IT WAS.**

| | completed games | timed-out games |
|---|---|---|
| median player turns | 17 | **10** (V0) / **6** (V1) |
| median elapsed | 47.5s | 362s (the ceiling) |
| median life at end | — | **20 vs 20** |

Timed-out games die **early**, with life totals untouched and near-empty boards. V4-019 (and
V3-207 before it) framed the residual hang as a cost that *scales with permanent count* — a
late-game monster. **That is wrong.** Across 28 captured `exceeded its 40s per-decision timeout`
board censuses, the turn distribution is
`[1,1,1,1,2,3,3,5,5,6,7,8,8,11,11,12,12,12,12,12,12,12,13,13,15,18,18,19]` — **four of them on
turn 1**, including this one:

> `turn=1 perms=1 Ai(1)-BattleBox=[Zuran Orb] Ai(2)-BattleBox=[]`

**A 40-second decision on turn 1 with one permanent in play cannot be a state-size problem.** There
is nothing there to simulate. This is a pathological blowup triggered by *something specific*, and
it is therefore a bug with a bounded, cheap repro — not an inherent cost of the approach.

**Where it is NOT:** `ComputerUtilAbility.getAvailableCards` (ComputerUtilAbility.java:68) is
bounded — hand + graveyard + **top card only** of each library + command/exile/battlefield. On turn 1
that is ~8 cards. So the candidate *count* is tiny and cannot explain 40s.

**Leading hypothesis (needs a jstack to confirm):** the cost is inside the per-candidate
`canPlayAndPayForSim(sa)` loop at `SpellAbilityPicker.java:234-249`, i.e. mana-payment feasibility
search. Two circumstantial supports: (a) the cards most present at a pathological decision are the
ten **shocklands** (Steam Vents 22, Stomping Ground 18, Temple Garden 17, Breeding Pool 17,
Overgrown Tomb 16, Blood Crypt 15, Watery Grave 13, Sacred Foundry 13, Godless Shrine 12, Hallowed
Fountain 10) — dual lands multiply the mana-payment search space combinatorially; (b) **Zuran Orb
appears in 9 of 28**, far above its base rate as a single copy in a shared pool, and it grants a
*free, repeatable* sacrifice ability — a classic enumerator trap.

**Note the architectural detail that makes this fixable:** V4-011's top-level breadth cap
(`maxTopLevelCandidates`, SpellAbilityPicker.java:295) is applied **after** the
`getCandidateSpellsAndAbilities()` loop has already paid `canPlayAndPayForSim` on every candidate.
The existing cap therefore does not bound this cost at all.

**Why fixing it is worth more than it looks:** it is ~25% of all sim compute on every run this
project will ever do, plus it removes the selection-bias asterisk from every gate. Filed as
TICKET-V4-022 (diagnose-then-fix), gated on the V4-020 corpus run finishing so the two do not
contend for the box or race on the shaded jar.

> **CORRECTION [2026-07-27, same session]: the hypothesis in the two paragraphs above is WRONG.**
> `canPlayAndPayForSim` / mana-payment enumeration is NOT the cause, and the shockland
> co-occurrence list is base rate, not signal (shocklands are in nearly every Battlebox board, so
> of course they dominate any co-occurrence count — I read noise as evidence). The *observations*
> in V4-021 stand — the timeouts really are early-game, life really is 20-20, four really are on
> turn 1 — but the explanation was wrong. See TICKET-V4-022 below for what the machine actually
> said when measured. The early-game/low-turn pattern is explained far better by a process-level
> property (heap saturation) than by any board-level one, which is exactly what the turn-1 case
> should have suggested.

### TICKET-V4-022: The "monster" is OBJECT RETENTION, not compute. It was sedated in V4-019, never cured. [DIAGNOSED 2026-07-27]

**Method:** read-only instrumentation of the two *live* V4-020 corpus JVMs — `jstack`, `jcmd
GC.heap_info`, `jcmd GC.class_histogram`, `jcmd GC.heap_dump`, and per-thread CPU accounting from
`/proc/<pid>/task/*/stat`. No build, no new run, no repro needed: the running corpus job was itself
the repro. (Method note for future sessions: when a hang is suspected, measure the JVM before
theorising about the algorithm. This diagnosis took ~15 minutes against a live process and
overturned two prior tickets' worth of assumption.)

**Measurements, both JVMs, consistent:**

| probe | result |
|---|---|
| `GC.heap_info` | **6144M used / 6144M capacity — 100% of max heap** |
| per-thread CPU, 60s window | **GC share 100%.** `XWorker#0`/`XWorker#1` each 100% of a core; **every application thread 0.0s CPU** |
| `GC.class_histogram` | **150,553 `forge.game.card.Card`**; 153,618 `CardState`; 4.14M Guava `TreeBasedTable`; 2.16M `HashBasedTable`; 1.92 GB in `[Ljava.lang.Object;` |
| `GC.heap_dump -all=false` (live only, post-full-GC) | **6.4 GB still reachable** |

**What this means.** A 1v1 Battlebox game has a live set well under a thousand `Card` objects.
**150K retained `Card`s is ~200 game-copies' worth of state held strongly reachable.** A live-only
heap dump — which runs a full GC first — still weighs 6.4 GB, so this is genuine **retention**, not
garbage awaiting collection. The JVM is not slow because the search is expensive; it is slow because
it is in a GC death spiral, with four cores across two JVMs burning **100%** on collection and the
application making no progress at all.

**This reframes the entire V3-207 → V4-017 → V4-019 line.** What has been called a "slow-decision
hang," a "compute TAX," and "the monster" is a memory-retention bug in the Ultron simulation path.
V4-019's hidden-info pruning cut allocation enough to stop the *hard OOM* (6 → 0, real and worth
keeping) but the heap still fills; ZGC then keeps the process barely alive instead of killing it, so
the failure mode changed from "crash" to "crawl." **The monster was sedated, not cured** — and the
symptom rename made it look like progress. Every downstream number is affected: the ~25% timeout
rate, the 40s decisions, the low throughput, and the turn-1 hangs (a process pegged in GC will stall
a trivial turn-1 decision exactly as readily as a complex turn-18 one — which is *why* turn number
doesn't correlate).

**Prime suspects for who holds the references (for the fix session to confirm against the dump):**
1. ~~**The NN state logger** (`UltronStateLogger`)~~ — **CLEARED by inspection, 2026-07-27.** It does
   buffer records until game end (`GameCollector.pending`), but `Record` holds only `int` fields,
   `List<Integer>`, `List<float[]>` and `List<Float>` — encoded vectors, **no `Card`/`CardState`/
   `Game` references** (UltronStateLogger.java:459-468). At ~8KB/record even an uncapped `pending`
   is tens of MB, not 6.4 GB. `GameCollector` holds one `Game` + one `players` list, which is
   correct and bounded. The logger is not the holder; do not spend time here.
2. **Abandoned "still draining" simulation workers** holding their `GameCopier` object graphs — the
   V4-003/V4-019 drain-hang, re-read as a retention problem rather than a CPU problem.
3. Any static/global registry keyed by card or game (id→Card maps, trigger/replacement registries)
   that a `GameCopier` copy registers into but never unregisters.

**Artifact:** `diag/v4022_heap.hprof` (6.4 GB, live objects only, from PID 822246 mid-corpus-run).
**This is the whole point of capturing it: the fix session does NOT need to reproduce anything.**
Open it in Eclipse MAT / VisualVM and run a dominator-tree + "path to GC root" on
`forge.game.card.Card`. That single query should name the holder outright.
`diag/` must be gitignored — do not commit a 6.4 GB binary.

**Consequence for V4-020 (V2 expert iteration): STOPPED MID-FLIGHT and re-sequenced behind this
ticket.** Throughput had degraded to ~28 games/hour, so its 5h budget would have produced ~140 games
≈ 5K records with a ~750-record val split — too thin to compare logloss against V0 meaningfully. And
because the retention bug throttles *every* future data-generation round, fixing it first is
strictly better than spending five hours on an inconclusive corpus. The partial corpus is at
`simstats/out/v4_020_v2_onpolicy_corpus/` (round_1 only, 9 games). All V4-020 code work is committed
and reusable.

**Standing correction to project sequencing:** V4-021 called throughput "the binding constraint on
expert iteration" and that is right, but the constraint is not the 25% timeout rate — it is that the
simulation path retains memory without bound. Fix the retention and the timeout rate, the 40s
decisions, and the games/hour should all move together.

---

## TICKET-V4-022 — ROOT CAUSE FOUND (2026-07-27): a self-defeating `WeakHashMap` in `UltronRuntimeController`

**`forge-ai/src/main/java/forge/ai/llm/runtime/UltronRuntimeController.java:32-33`**

```java
// Weak map so instances are GC'd when games end
private static final Map<Game, Map<Player, UltronRuntimeController>> INSTANCES = new WeakHashMap<>();
```
and line 36:
```java
private final Game game;   // the VALUE holds a strong reference to the KEY
```

**`WeakHashMap` holds its keys weakly but its VALUES strongly.** Each value here
(`UltronRuntimeController`, created in `getOrCreate` at line 60-66) stores `this.game = game` — a
**strong reference back to its own key**. Chain:

> `INSTANCES` (static field = permanent GC root) → *strongly* holds the
> `Map<Player, UltronRuntimeController>` value → *strongly* holds each `UltronRuntimeController` →
> *strongly* holds its `game` field → **the weak key is permanently strongly-reachable and the entry
> can never be evicted.**

It is a strong map wearing a weak map's clothes. The comment on line 32 states an intent that the
code on line 36 defeats. **Every `GameCopier` simulation copy is pinned forever**, and with it the
whole graph hanging off that `Game`: players, cards, `CardView`/`CardStateView`/`SpellAbilityView`
(each ~2 KB of `EnumMap` over 252 `TrackableProperty` constants), `Tracker`, spell abilities.

**Live measurement, single 1v1 NN-eval JVM (`jcmd GC.class_histogram`, live objects only):**

| time | live `forge.game.Game` | ZHeap |
|---|---|---|
| 01:24:44 | 351 | 4952M / 6144M |
| 01:25:57 | **590** | 5752M / 6144M |
| 01:27:13 | (histogram did not complete — JVM saturated) | **6144M / 6144M** |

**~200 leaked game copies per minute, monotonic, to heap exhaustion in under three minutes.**
Corroborating counts at the 590-Game sample, all exactly 1:1 or 2:1 with leaked games: `Match` 596,
`Tracker` 596, `PhaseHandler` 596, `GameAction` 596, **`UltronRuntimeController` 596**,
`UltronPlayerController` 596, `AiController` 1192, `SpellAbilityPicker` 1192 (2 players × 596).

**This explains every symptom in the V3-207 → V4-017 → V4-019 → V4-021 chain**, and it is a
**positive feedback loop**: more copies → fuller heap → slower decisions → more 40s timeouts → more
abandoned decisions → still more copies. It is Ultron-only (the Default AI never calls
`getOrCreate`), which is why this never showed up on the Default control runs, and why "the monster"
looked like an inherent cost of simulation rather than a bug.

**Hypotheses ruled out along the way (recorded so nobody re-runs them):**
- ~~mana-payment enumeration / `canPlayAndPayForSim`~~ — wrong (V4-021's correction above).
- ~~Shockland or Zuran Orb correlation~~ — base rate, not signal.
- ~~`UltronStateLogger` buffering~~ — cleared by inspection; `Record` holds only encoded floats.
- ~~Copies sharing the real game's `Tracker`~~ — `GameCopier` never touches Tracker; `Game.java:149`
  gives every copy its own.
- ~~Abandoned "still draining" sim worker threads acting as GC roots~~ — measured: **1** live
  `Ultron-Sim` thread out of 34 total while 596 games were retained. Not the holder.
- ~~Lazy `CardView` construction as the fix~~ — **not viable as stated**: `Card.java:410-411` builds
  `currentState = new CardState(view.getCurrentState(), this)`, so `CardState` (real game state) is
  constructed *from* the view. Views are entangled with state, not a detachable GUI shadow. The
  view/`EnumMap` bloat is a real secondary cost but it is a *consequence* of retaining 596 games,
  not the cause. **Fix the retention first and re-measure before touching the view layer.**

**Method note worth keeping:** the answer came from `jdk.OldObjectSample` JFR events (allocation
stacks of objects that survived) plus `jcmd GC.class_histogram` growth sampling — roughly 20 minutes
of measurement. A hand-rolled reverse-reference scan over a 6.4 GB hprof ran for over an hour and
could not have worked: in this object graph the top referrers of `Card` are the card's *own*
satellites (`AbilitySub.hostCard`, `Trigger*.hostCard`, `Keyword.hostCard`), a self-referential
cycle. **Reverse edges cannot answer "what keeps this alive" — only paths to a GC root can.**
Reach for JFR old-object sampling and histogram deltas before writing a heap parser.

### TICKET-V4-020: V2 = expert-iteration round 1 (on-policy corpus + fine-tune) [PAUSED — blocked on V4-022]

**Goal (unchanged, resumes once V4-022 lands):** regenerate the training corpus ON-POLICY — Ultron
running the V0 model vs Default, 1v1 Monarch — and adapt V0 to it, isolating the state DISTRIBUTION
as the single variable versus V0's off-policy all-Default corpus (`v4_007_bootstrap_corpus`,
142,229 records / 7,996 games).

**What was built this session, all committed and reusable as-is:**
1. **Logger phase-mode knob** (`forge-ai/src/main/java/forge/ai/nn/UltronStateLogger.java` +
   `forge-gui-desktop/src/main/java/forge/view/SimStatsConfig.java` +
   `.../forge/view/SimulateStats.java` + `UltronStateLoggerTest.java`): new `stats.nnLoggingPhases`
   ini key (`main1` | `priority`, default `priority` = today's V4-018a/a-ext behavior unchanged).
   `main1` reproduces V0's original TICKET-V4-006 capture exactly (phase-transition trigger only,
   restricted to `MAIN1`, the priority-window trigger disabled entirely) so a corpus-generation
   experiment can hold capture strategy constant. `UltronStateLogger.PhaseMode` enum +
   `resolvePhaseMode(String)` selector, unit-tested (5 new tests: default-is-priority, case/whitespace
   tolerance on `main1`, unrecognized-value fallback, and a behavioral test proving MAIN1 mode skips
   MAIN2/combat phase-transitions that PRIORITY mode would capture). Verified in a real 3-game jar
   smoke run: 17 records/game, single phase ordinal (MAIN1), schema hash `0x330703df11234a17` exact
   match — all three of V4-020's smoke-gate criteria passed before any full run was attempted.
2. **Trainer fine-tuning support** (`tools/nn/train.py`): `--init-from <model.bin>` (initializes
   trunk + value_head from a previously-exported model, asserting exact hidden1/hidden2/input_dim/
   schema_hash/semantic_version match via a new `ModelShapeMismatchError` — fails loudly rather than
   silently loading onto a mismatched architecture), `--eval-only` (loads `--init-from` and reports
   its metrics on the current `--data`'s val split without training — the honest same-val-set
   baseline tool), and `--aux-weight` (default 0.25, unchanged from-scratch behavior). All three
   verified end-to-end against the smoke corpus, including a deliberate hidden1/hidden2 mismatch
   correctly raising `ModelShapeMismatchError` before touching any weight.
3. **Config + harness**: `configs/simstats/v4_020_v2_onpolicy_corpus.ini` (Ultron/V0-model vs
   Default, 1v1 Monarch, seat-rotated, `nnLogging=true` + `nnLoggingPhases=main1`, pruning/depth-0/
   copy-budget unchanged — the exact runtime V0's 37.3% was measured on) and
   `tools/simstats/run_v4_020_corpus.sh` (round harness modeled on `run_gate_v4_016.sh` with two
   fixes: a wall-clock budget that stops launching new rounds once exhausted rather than trusting a
   pre-computed game-count target, and a watchdog that kills only `comm==java` descendants of the
   round's own process tree — never a `pgrep -f <string>` pattern, which has self-matched its own
   shell twice on this project per the standing rule).

**Three confounds caught in sequence, each before it could contaminate a result (orchestrator
review, live during the run) — recording the pattern since it is now 3-for-3 on this ticket alone:**
1. **Corpus-volume confound.** Measured throughput (see below) meant a from-scratch V2 on the 5h
   corpus would train on ~2-5% of V0's record count and lose to V0 on data volume alone, telling us
   nothing about the state-distribution variable. Fix: V2 = **V0 fine-tuned** (`--init-from`), not
   retrained from scratch — lr=1e-4 (vs V0's 1e-3), alpha/batch/weight-decay/val-frac/seed unchanged,
   epochs capped at 15/patience 3 (adapting, not converging from nothing).
2. **Random-aux-head confound.** `placement_head`/`length_head` are never exported to `.bin` (aux,
   train-time-only, dropped at export per plan sect. 4.2), so on any `--init-from` run they are
   ALWAYS freshly random while the trunk is pretrained. At the default `AUX_WEIGHT=0.25`, a random
   head's large early cross-entropy gradient flows back through the shared trunk and can degrade the
   pretrained representation for reasons having nothing to do with the on-policy data — indistinguishable
   from the real effect in a gate. Fix: `--aux-weight` flag; plan was primary run at `--aux-weight 0.0`
   (value head/trunk only — does not change the deployed model.bin, which never included the aux
   heads anyway) with `--aux-weight 0.25` kept as a control to confirm the hazard was real. **Not
   run** — corpus generation was stopped (confound 3) before any fine-tune was attempted.
3. **Object-retention confound (fatal to this run, not to the code above).** See TICKET-V4-022: the
   corpus JVMs were retaining ~6.4 GB of live objects (150K `Card`/`CardState` instances in a 1v1
   game whose live set should be under 1,000) and effectively doing no useful work under sustained
   GC pressure. Throughput measured across the run: **~36 games/hour early (round 1, first 13.5 min:
   7 games), degrading to ~28 games/hour** as retention accumulated — well below the ~98/hr this
   ticket's original corpus-math checkpoint assumed. At 28-36 games/hour the 5-hour budget would have
   bought ~140-180 games (≈5-6.5K records, ~750-1000-record val split) — too thin to compare logloss
   against V0 with any confidence, and the retention bug throttles every future expert-iteration
   round, so fixing it first (V4-022) is strictly better than banking an inconclusive corpus now.

**Partial corpus preserved, not discarded:** `simstats/out/v4_020_v2_onpolicy_corpus/round_1/`
(shard_0: 5 games, shard_1: 4 games — 9 games total, MAIN1-only records, schema-valid) plus
`corpus.log`. Small enough to be worth keeping as a first slice of the eventual real corpus
(`train.py --data` already accepts multiple paths, so this concatenates for free) rather than
reason to distrust the harness — the harness and config are correct; the runtime underneath them
was not ready.

**Regression check before this pause:** `forge.ai.simulation.*` + `forge.ai.ultron.*` +
`forge.ai.nn.*` (18 explicit classes, package-glob `-Dtest=forge.ai.simulation.*` silently matches 0
per the standing build note) = **275/275 pass, 1 skipped** (pre-existing). `python3 tools/nn/train.py
--self-test` = PASS. Default AI path byte-identical (nothing in this session touches a non-Ultron,
non-nn code path).

**Resume plan (do not start until V4-022's fix lands and is verified):** re-launch
`run_v4_020_corpus.sh` fresh (do not extend the partial round_1 output in place — new seeds, same
merge-by-concatenation convention as every other corpus in this project) at whatever throughput the
retention fix restores; re-run the corpus-math checkpoint with the NEW throughput number before
committing to a wall-clock budget; then `--eval-only` (V0 baseline on the new val split) followed by
the two `--aux-weight` fine-tune variants (0.0 primary, 0.25 control) exactly as scoped above.

# BUILD TRAP: `mvn test` does NOT rebuild the jar the simulator runs

**Recorded 2026-07-24 after it silently invalidated a verification run — read this before running
any sim after a code change.**

`tools/simstats/run_simstats.sh` executes
`forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar`. That shaded jar is
produced by the **`package`** phase (assembly plugin). `mvn test` compiles classes into
`target/classes` and runs tests against them, but **never regenerates the shaded jar**. So:

> A sim run launched after `mvn test` — even one where every test passes — runs whatever code was
> in the jar at the last `package`. New classes are simply absent.

**How it bit us (TICKET-V4-006):** the NN state logger was written, unit-tested (272/272 green),
committed, and a 20-game logged verification run was launched with `stats.nnLogging=true` correctly
set and correctly propagated into the generated `shard.ini`. The run completed 20/20 with zero
errors and produced **no log files whatsoever**. The jar was timestamped 24 minutes before the
logger source file existed. Nothing errored, nothing warned — a missing class in a code path gated
behind a disabled-by-default flag simply does nothing. Had this not been checked, the "generate the
real corpus" step would have burned hours producing an empty dataset, and the natural suspicion
would have fallen on the logger's correctness rather than the build.

**Rule:** before any sim run that is supposed to exercise newly-written code, run
`mvn -pl forge-ai,forge-gui-desktop -am package -DskipTests -Dcheckstyle.skip=true -q` and verify
the jar actually contains what you expect:
`unzip -l forge-gui-desktop/target/*jar-with-dependencies.jar | grep <your/package/path>`.
Cheap, and it converts a silent-empty-output failure into a five-second check.

(Related, from TICKET-V4-005/006's own instructions: sessions are told not to run `mvn clean`
while a sim run holds the jar open. That guidance stands — but note it does not mean "skip
`package`"; a non-clean `package` is both safe and required.)

---

# MULTI-NODE COMPUTE (added 2026-07-27)

Sim workloads can run across more than one machine. **Full policy and rationale:
`MULTI_NODE.md`.**

**Use `tools/simstats/forge.sh` — the orchestration layer.** It preflights and self-remediates
(syncs commits, waits for rebuilds, copies the gitignored model), then verifies rather than trusts:
`generate` confirms every node actually loaded the network, `collect` confirms no two nodes shared
a seed range. Compact PASS/FAIL output, meaningful exit codes, so orchestration state does not have
to live in an operator's head.

    bash tools/simstats/forge.sh doctor | preflight <cfg> <model> | generate <cfg> <games> <run> <model>
                                        | status <run> | wait <run> | collect <run> | stopall

`tools/simstats/forge_nodes.sh` is the plumbing underneath (status/sync/push-model/run/offload/
collect/stop); reach for it only for something the orchestrator does not cover.

Roster: `tools/simstats/nodes.conf` — currently **dell-xps15** (primary, 8 cores / 31GB, 2x6g) and
**asus-vivopc** (generation, 4 cores / 15GB, 1x6g, ~3x slower; RAM-limited to one worker).

**The three rules that matter, in short:**
1. **Generation may be offloaded; win-rate GATES may not.** A gate is a measurement and its baseline
   was taken on specific hardware. Timeouts are wall-clock bounded (`timeoutSeconds=360`), so a
   slower node genuinely times out more often, and timeout exclusion biases win rate — that is
   TICKET-V4-021's whole finding. A gate may run on a secondary node only if its baseline did too.
2. **Every node needs a disjoint seed range** or parallel nodes generate the *same games*, inflating
   corpus size and narrowing confidence intervals on duplicated samples. `offload` spaces nodes
   10,000,000 apart automatically; hand-launched runs own this themselves.
3. **The BUILD TRAP is per node.** Each machine has its own shaded jar. `sync <node>` pushes the
   commit *and* rebuilds; pushing alone is not enough.

`run` refuses to launch when a node's HEAD differs from local, so a corpus cannot be silently
half-generated by older code.

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

### TICKET-V4-022 RESULT (2026-07-27): FIXED. The monster is dead — 30/30 games, timeout rate 25% → 3.3%, throughput ~5x.

**The fix (commit `07541530c2`):** one guard in `UltronRuntimeController.getOrCreate()` — simulation
copies are never registered in the static `INSTANCES` map. See the ROOT CAUSE section above for why
the `WeakHashMap` could never evict (values hold their own keys strongly).

**Verification on the ORIGINAL monster diagnostic** (`configs/simstats/v4_017_monster_diag_1v1.ini`,
30 games, 1v1 Battlebox Monarch, NN eval on, 2 workers × 6g ZGC) — the same config that has been
this project's standing torture test since V4-017:

| run | games | OOM | outcome | median game |
|---|---|---|---|---|
| V4-017 original | 4 | 6 | wedged | — |
| + Keyword cache (V4-017) | 4 | 1 | wedged | — |
| + hidden-info pruning (V4-019) | 5 | 0 | **wedged at game 5** | 42s |
| **+ V4-022 (this fix)** | **30/30** | **0** | **ran to completion** | **22.1s** |

**Full V4-022 numbers:** 30 games logged, **29 completed normally, 1 timeout (3.3%)**, 0 errors,
**0 OutOfMemory**, **0 `exceeded its 40s per-decision timeout` events**, **0 `still draining`
events**. Elapsed per game: min 5.0s, median 22.1s, max 360s (the single timeout). Wall clock
**11m33s for 30 games on 2 workers ≈ 156 games/hour**.

**Against the pre-fix baselines:**
- **Game-timeout rate: ~25% → 3.3%** (V4-021 measured 20/79 and 24/97 on the two gates).
- **Throughput: ~28–36 games/hour → ~156 games/hour (~5x).**
- **Median game: 42s → 22.1s.**
- **The 40s decision timeout and the "prior timed-out worker is still draining" drain-hang — the
  V4-003 root that survived V4-017 and V4-019 — did not occur once in 30 games.** They were
  symptoms of heap starvation, not of an uncancellable worker. No cooperative-cancellation work is
  needed; that ticket can be closed as overtaken by events.

**What this retires.** The "monster" (V3-207 → V4-017 → V4-019 → V4-021) is closed. It was never a
compute cost, a combinatorial explosion, or an uncancellable thread — it was one static map entry
per simulation copy. Every fix layered on top of it (Keyword cache, hidden-info pruning, depth-0,
copy budget) remains valuable and stays; but the thing that made Ultron unrunnable was three lines.

**What is now unblocked.** Throughput was the binding constraint on the entire post-V0 roadmap
(V4-021). At ~156 games/hour a 500-game on-policy corpus is ~3.2 hours instead of ~18, which makes
**TICKET-V4-020 (V2 expert iteration) affordable for the first time** — and with it depth-1 search
and, later, the Tier-1 stochastic-policy/league work from `ULTRON_THEORY_OF_MIND_STUDY.md`. V4-020
should be resumed with a corpus target set by record count, not by a wall-clock budget.

**Caveat kept in view:** the residual real-game retention (TICKET-V4-023) is unfixed and bounded
(~15 games per shard JVM). It did not affect this run and does not block V4-020.

### TICKET-V4-023: Real-game entries in `UltronRuntimeController.INSTANCES` are still never evicted [OPEN, LOW PRIORITY]

V4-022 stopped simulation copies from entering the static registry, which removed the unbounded
leak. **Real games still register and are still never evicted**, for the same reason: the value
holds a strong reference to its own key, so `WeakHashMap`'s weak keys never clear.

**Bounded, not urgent:** ~15 real games per sim shard JVM (~150–200MB), bounded by process lifetime.
A long-lived interactive GUI session is the case where it could matter.

**Why it was not fixed in V4-022:** a correct fix requires the value to stop reaching the key at
all. Dropping `UltronRuntimeController.game` is not sufficient — the `player` field resurrects the
same path, since `Player` holds `Game` strongly. Both would have to become weak references (or the
registry replaced with per-controller ownership plus an explicit end-of-game eviction hook). That is
real surgery on a class shared with the interactive GUI path, and it was not worth the risk on the
back of the V4-022 win. Note that `getSimStats(game, player)` (SimulateStats.java:268) reads this
map and must keep working.

### TICKET-V4-020 RESULT (2026-07-27): V2 BEATS V0 by +9.2pp — expert iteration works. First real model gain in the project.

**V2 = V0 fine-tuned on an on-policy corpus.** Single variable: the state distribution.

| model | training data | win rate vs Default (1v1 Monarch, null 50%) | n | timeouts |
|---|---|---|---|---|
| **V0** | off-policy (Default-vs-Default corpus, 284,458 records) | **28.5%** [25.9, 31.3] | 1083 | 1.5% |
| **V2** | V0 warm-start + on-policy corpus (20,027 records) | **37.7%** [34.7, 40.8] | 970 | 1.7% |

**+9.2 pp, two-proportion z = 4.430, one-sided p = 4.7e-06.** Wilson intervals do not overlap.
Both runs: Ultron vs Default, 1v1 Battlebox Monarch, seat-rotated, NN eval, cap 4, post-V4-022
runtime, on the same commit era. Model: `tools/nn/runs/20260727-130315/model.bin` (aux-weight 0.0).

**Held-out logloss, both scored on the SAME on-policy validation split** (5,954 samples / 162 games):
V0 **0.5322** -> V2 **0.5209**. Note V0 scored 0.509 on its own all-Default validation data but
0.532 on on-policy states — **direct confirmation of the off-policy premise**: the value function was
measurably worse at judging exactly the states its own policy reaches, which is the entire reason
this ticket existed.

**A modest logloss gain (2.1% relative) produced a large win-rate gain (+9.2pp).** Worth remembering
next time a small held-out delta looks unpromising — for an argmax policy, what matters is the
*ordering* of afterstates near the decision boundary, not average calibration.

**The aux-head hazard was real in mechanism but immaterial in effect.** Primary (`--aux-weight 0.0`,
0.5209) vs control (`--aux-weight 0.25`, 0.5214) — essentially identical. The orchestrator predicted
randomly-initialised aux heads would corrupt the warm-started trunk; running the control settled it
for ~10 minutes of CPU. Prediction was overstated; keep `0.0` anyway since the aux heads are dropped
at export regardless.

**Honest caveats:**
- **V2 still loses to Default** (37.7% vs a 50% null; exact binomial p vs 50% = 1.0). This is a real
  improvement, not parity. Do not report it as "Ultron beats Default".
- Not a paired same-seed comparison. V0's baseline comes from the V4-020 corpus-generation run
  (`nnLogging=true`), V2's from a dedicated 20-round gate (logging off). Configs are otherwise
  identical and logging does not affect decisions, but this is two independent samples, not a
  paired design.
- The old **37.3% [26.1, 50.0] n=59** V0 figure (V4-018e) is superseded. At n=1083 V0 is 28.5%;
  the n=59 result was an optimistic small-sample draw on the pre-V4-022 runtime where ~25% of games
  were discarded as timeouts. **Cite 28.5%, not 37.3%, as V0's strength.**

**Tooling bug fixed in passing:** `gate.py`'s `exact_binomial_sf` raised `OverflowError` at n=1083 —
it multiplied `math.comb(n,k)` (an exact int with hundreds of digits) by floats. Rewritten in log
space via `lgamma`, verified against known values. We only started hitting this because V4-022 made
thousand-game samples routine.

**Next levers, in order (unchanged in shape, now with evidence behind them):**
1. **Round 2 of expert iteration** — regenerate on-policy with V2 driving, fine-tune again. This is
   now a proven-productive loop, and at ~186 games/hour (+asus) a round is affordable. Watch for
   diminishing returns; log the per-round delta.
2. **Depth-1 search** — forced off by the monster, which is now dead. Increases *judgment* rather
   than decision surface, which is the axis V4-018 showed matters.
3. **Tier-1 stochastic policy + league play** (`ULTRON_THEORY_OF_MIND_STUDY.md`) — the first step
   toward anything resembling deception, and the point at which a deterministic argmax stops being
   the ceiling.

### TICKET-V4-025 RESULT (2026-07-28): round-1's gain is ~65% distributional, ~35% volume. Expert iteration validated, but the headline was overstated.

**Why this was run.** V2 = V0 fine-tuned on 20,027 ON-POLICY records, and gated +9.2pp over V0
(37.7% vs 28.5%). That was reported as proof that on-policy data works. But V2 also simply saw
**20K more records than V0**, and no control was ever run to separate the two. The claim had a hole
in it.

**Method (cheap by design: ~4 min CPU, no new games).** Fine-tune V0 on a **size-matched 20K slice
of the original all-Default corpus** (`v4_007`), byte-identical hyperparameters to V2's fine-tune
(`--init-from V0 --aux-weight 0.0 --lr 1e-4 --epochs 15 --patience 3 --alpha 0.5 --seed 1234`), then
score it on the **same on-policy validation split** V0 and V2 were scored on (5,954 samples / 162
games). Size-matching required a new `train.py --max-records` (deterministic truncation, identical
across both streaming passes).

| model | fine-tuned on | on-policy val logloss | Δ vs V0 |
|---|---|---|---|
| V0 | — | 0.5322 | — |
| **control** | 20K **all-Default** | **0.5282** | **−0.0040** |
| V2 | 20K **on-policy** | 0.5209 | −0.0113 |

**On-policy records are worth ~2.8x more per record** for on-policy performance. But **~35% of
round 1's logloss improvement is reproduced by training on all-Default data** — i.e. by volume/more
optimisation, not by distribution.

**Conclusions:**
1. **Expert iteration is real and directionally validated.** The distribution effect is the larger
   share and cannot be had by simply training longer on old data.
2. **The round-1 headline was overstated.** "+9.2pp proves on-policy data works" should have been
   "+9.2pp, of which an unmeasured fraction is volume." Now measured.
3. **Corpus SIZE is a genuine confound between rounds.** Round 2 will land near 14.4K records
   (crash + asus washout) against round 1's 20,027 — a 28% shortfall, in a regime where volume
   demonstrably moves the metric. A 420-game top-up is queued (`jobs_v4_026.tsv`) to restore parity
   before V3 is trained. **Do not compare round 2 to round 1 on an unmatched corpus.**

**Caveat, stated plainly:** this measures held-out LOGLOSS, not win rate. The control was not gated.
A logloss decomposition need not map linearly onto win-rate decomposition — round 1 itself showed a
2.1% relative logloss gain producing +9.2pp. Treat the 65/35 split as the best cheap estimate
available, not as a win-rate attribution.

**Script:** `tools/nn/run_control_v4_025.sh` (rerunnable; documents its own read-out thresholds).

### TICKET-V4-027 (2026-07-28): play-quality diagnostics — Ultron loses on TEMPO, and the encoder cannot see tempo

**Why.** This project has evaluated exclusively on scalar win rate, while every game logs 28
per-player stats that were never used. V1's "durdling" was diagnosed by a human reading logs. The
MTG RL benchmark literature (arXiv 2605.06066) states plainly that scalar win rate hides the
diagnostic structure that matters. Tool: `tools/simstats/play_quality.py`.

**Method.** PAIRED per-game deltas: Ultron-minus-opponent computed *within each game*, so game
length, draw luck and board explosions cancel. A per-model average would confound all three.

**Result — V2 gate, n=970 completed games (Ultron minus Default):**

| axis | metric | delta |
|---|---|---|
| TEMPO | spells cast / turn | **−0.14** |
| CARDS | cards drawn | −0.46 |
| CARDS | **cards left in hand at end** | **+0.42** |
| AGGRO | attacks declared | −1.15 |
| AGGRO | combat damage dealt | −6.71 |
| DEF | combat damage taken | +6.71 |
| BOARD | permanents / creatures / power | −2.17 / −1.53 / −5.35 |

**Ultron casts fewer spells per turn AND ends with more cards in hand.** That is mana inefficiency
stated two ways — it is holding cards it never deploys. Mana efficiency *is* tempo, mechanically,
and tempo is one of the two resources Magic is played on (the other being card advantage; see
Wizards' "Tempo & Card Advantage: A Delicate Balance"). **Ultron is losing the tempo game.**

**Won-vs-lost within V2 (366 won / 604 lost) — the non-tautological rows only.** Damage and blocks
are near-tautological (the winner dealt 20 damage by definition), so ignore those. What is *not*
tautological:

| metric | won | lost | gap |
|---|---|---|---|
| spells / turn | +0.01 | **−0.23** | +0.25 |
| cards drawn | +3.18 | **−2.66** | +5.84 |
| abilities activated | −0.11 | −0.89 | +0.78 |

In games it wins, Ultron matches Default's spell rate. In games it loses, it is systematically
*less active* — fewer spells, fewer cards, fewer activations. It does not lose by making one bad
attack; it loses by quietly under-using its resources for the whole game.

**The connection that matters.** `UltronStateEncoder` has **no mana-available, no untapped-mana and
no mana-spent feature anywhere** — only land colour counts and a raw normalised turn number. There
are also **no temporal/delta features at all**, so rate-of-accumulation is unrepresentable, and rate
is precisely the signal multiplayer threat assessment keys on. **We are asking the network to learn
a game whose central currency it cannot perceive, and it plays exactly like an agent that cannot
perceive it.** No training-loop refinement (TD targets, more iteration rounds, self-play) recovers
information that was never in the input.

**Standing rule from here:** every gate reports play-quality deltas alongside win rate. A model that
wins for the wrong reasons, or loses in a new way, is information we have been discarding.

### TICKET-V4-024 STOPPED (2026-07-28): round-2 corpus halted mid-flight; Phase-E work was blocking Phase-A work

**Decision:** stopped the round-2 corpus generation to free the box for the V4-026 gates. Not a
failure — a priority correction that follows directly from the 2026-07-28 plan revision, which
demotes expert-iteration rounds to **Phase E** and promotes the free runtime-knob gates to
**Phase A**. Letting a Phase-E job block Phase-A jobs for nine hours was the wrong trade.

**Data banked (nothing lost):** 244 games (`v4_024_v3_corpus_b`) + 228 (`v4_024_v3_corpus`, dell's
pre-crash partial) + 79 (asus, final) = **551 games / 7,357 records**. Available whenever round 2
becomes the priority again; it will need a top-up to reach parity with round 1's 20,027.

**Why it was stopped rather than left to finish — a finding worth keeping.** Throughput had
collapsed to **57 games/hour** against dell's recorded 119, with **both heaps pinned at
6144M/6144M** and one OOM (stack: `TimeLimitedCodeBlock.runWithTimeout` → `FutureTask.get`, i.e.
surfaced on main while awaiting a timed decision). Remaining work would have taken ~9 more hours.

**This is NOT a V4-022 sibling.** Checked: `UltronStateLogger` holds no static collections, and
`GameCollector` subscribes to each game's own event bus, which dies with the game. No unbounded
retention.

**But there IS an unexplained gap that matters for Phase B/C.** The V2 gate ran **987 games at 175
games/hour on the same box with the same 6g heaps and never saturated** (observed 4074–5690M). The
only material difference between that run and this one is **`nnLogging=true`**. So corpus
generation with logging appears to roughly triple memory pressure and halve throughput, for reasons
not yet accounted for by the logger's own data structures (a `Record` is float arrays only; ~13
records/game at MAIN1).

**Open question, to answer BEFORE Phase C generates new corpora:** why does enabling the state
logger saturate a 6g heap? Phase C's encoder v3 will require regenerating corpora from scratch, and
doing that at 57 games/hour instead of 175 would cost days. Candidate causes to check: per-decision
encoder allocation (a fresh `float[1908]` per seat per captured decision, plus the `GameCopier`
copies each decision already makes), `GameCollector.pending` growth in long games (uncapped until
`finish()` downsamples), and Guava `EventBus` subscriber overhead per game.

### TICKET-V4-023 RESULT (2026-07-28): FIXED. One retained Game per game played → one, total.

**The residual came due.** V4-022 stopped simulation *copies* entering `UltronRuntimeController`'s
static registry. Real games still entered and were never evicted, filed as "bounded, ~15 games per
shard JVM (~150–200MB)". **That estimate silently assumed the round harness restarting a JVM every
25 games.** The queue harness I later wrote (`forge_nodes.sh run`) launches a *single long run* —
1000 games across 2 shards = **500 games per JVM** — so the same code retained 500 `Game` object
graphs.

**Measured on the live breadth10 gate before the fix: 12 games played → 10 live `forge.game.Game`,
10 `Match`, 9 controllers.** One retained per game, heap climbing ~80MB/game. It would have
saturated near game 80 and produced a timeout-biased gate — exactly the V4-021 bias — so the gate
was stopped at 12 games and its partial discarded.

**This also explains V4-024's collapse**, previously logged as unexplained: 57 games/hour with both
heaps pinned at 6144M, versus the V2 gate's 175 games/hour that never saturated. It was never
`nnLogging`; it was that the V2 gate ran the *round harness* (25 games/JVM) while the corpus ran
376 games/JVM. Same leak, hidden by restarts.

**Fix:** `UltronRuntimeController.forget(Game)` + a call at game end in `SimulateStats`, placed
*after* `findUltronSimStats`/`findUltronCoverage`, which read from that registry.

Explicit eviction rather than genuinely-weak keys: the value transitively holds its own key
(`game`, and `player` → `player.getGame()`), so weak keys can never clear while the value lives.
Both fields would have to become weak — real surgery on a class shared with the interactive GUI
path. A caller that knows the game is over is smaller and safer, and the sim harness knows exactly
that.

**Verified under load, same config, same 6g heap:**

| | games played | live `forge.game.Game` | heap |
|---|---|---|---|
| before | 12 | **10** | 1306M and climbing |
| after | **21** | **1** | 1170M, steady/falling |

Tests: `UltronRuntimeSimCopyRetentionTest` + `UltronValueNetParityTest` green (6 run, 0 failures,
1 pre-existing skip).

**Lesson worth keeping:** a bound that holds only because of an unrelated harness detail is not a
bound. "~15 per JVM" was true of the harness in use at the time and became false the moment the
harness changed, with no code change and no warning.
