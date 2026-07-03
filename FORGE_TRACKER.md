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
