# Ultron Runtime Remodel Notes

## Branch
`ultron-fast-ai-remodel`

## Plan Source
`~/downloads/Forge Ultron Remodel Plan.pdf`

## Mission
Remodel existing Ultron LLM advisor into a fast non-LLM runtime AI for four-player free-for-all
games. LLM infrastructure preserved but demoted to optional support layer.

---

## Files Inspected

| File | Notes |
|------|-------|
| `forge-ai/src/main/java/forge/ai/AiController.java` | Main integration point — chooseSpellAbilityToPlayFromList, declareAttackers |
| `forge-ai/src/main/java/forge/ai/llm/UltronAdvisor.java` | Singleton; owns DeepSeek clients, contexts, strategic plans |
| `forge-ai/src/main/java/forge/ai/llm/UltronStrategicPlan.java` | JSON plan parser; GameState enum used by controller |
| `forge-ai/src/main/java/forge/ai/llm/UltronGameContext.java` | Per-game context, prompt builder |
| `forge-ai/src/main/java/forge/ai/llm/DeepSeekClient.java` | HTTP client for DeepSeek API |
| `forge-game/src/main/java/forge/game/zone/MagicStack.java` | Stack API: peek(), peekAbility(), isEmpty() |
| `forge-game/src/main/java/forge/game/ability/ApiType.java` | Enum of all effect types |
| `forge-game/src/main/java/forge/game/card/CounterEnumType.java` | Counter type enum (not forge.game.counter) |
| `forge-game/src/main/java/forge/game/player/Player.java` | getOpponents(), getWeakestOpponent(), getCardsIn() |

---

## Integration Points Modified

### `AiController.java`
- Added imports: `UltronConfig`, `UltronCombatPolicy`, `UltronDecisionLog`, `UltronRuntimeController`,
  `UltronRuntimeDecision`, `UltronTableThreatSummary`, `UltronThreatModel`, `UltronTurnIntent`,
  `UltronTurnIntentBuilder`
- **`chooseSpellAbilityToPlayFromList`**: replaced single `useUltronAdvisor` flag with dual check:
  - `isUltronRuntime` = profile check only (no API key required)
  - `useUltronAdvisor` = `isLlmAdvisorEnabledFor(player)` (requires key + explicit flag)
  - Candidate collection now happens for runtime OR LLM (max from `UltronConfig.maxCandidates()`)
  - Runtime controller runs first; LLM strategic plan is guarded by `enabledForStrategicPlanLlm()`
- **Land play section** (~line 1389): LLM strategic-plan land choice now gated behind
  `UltronConfig.enabledForStrategicPlanLlm()`
- **`declareAttackers`**: Ultron runtime combat policy filter runs before optional LLM plan filter

### `UltronAdvisor.java`
- Added five new public methods:
  - `isUltronRuntimeProfile(Player)` — profile check, no API key needed
  - `isLlmAdvisorEnabledFor(Player)` — requires `ULTRON_LLM_ADVISOR_ENABLED=true` + client
  - `isLlmStrategicPlanEnabledFor(Player)` — requires `ULTRON_LLM_STRATEGIC_PLAN_ENABLED=true` + client
  - `isChatEnabledFor(Player)` — requires `ULTRON_CHAT_ENABLED=true` + client
  - `isTableTalkEnabledFor(Player)` — requires `ULTRON_TABLE_TALK_ENABLED=true` + client
- Added guard to `chooseSpellAbility` — returns `noAdvice()` unless `ULTRON_LLM_ADVISOR_ENABLED=true`
- Added guard to `chooseFromStrategicPlan`, `analyzeOpeningHand`, and `filterPlannedAttackers` so
  they only run when `ULTRON_LLM_STRATEGIC_PLAN_ENABLED=true`
- Existing `isEnabledFor(Player)` preserved for backward compatibility

---

## New Files Created

### `forge-ai/src/main/java/forge/ai/llm/UltronConfig.java`
Central feature flags. All env vars with documented defaults.

### `forge-ai/src/main/java/forge/ai/llm/runtime/` package
| Class | Purpose |
|-------|---------|
| `UltronRuntimeDecision` | Decision result: CHOOSE / PASS / NO_DECISION / FALLBACK |
| `UltronRuntimeRole` | Role enum: AHEAD / BEHIND / STABILIZING / PRESSURING / CONTROL / COMBO_DEFENSE / DESPERATE |
| `UltronDecisionLog` | Structured logging (disabled unless `ULTRON_DECISION_LOGGING=true`) |
| `UltronOpponentProfile` | Per-opponent snapshot: life, boardValue, comboThreat, combatThreat, etc. |
| `UltronTableThreatSummary` | Full table analysis: leader, weakest, mostDangerous, ultron position |
| `UltronThreatModel` | Entry point for `analyze(Game, Player)` → `UltronTableThreatSummary` |
| `UltronStackThreatType` | Enum of all stack threat classifications |
| `UltronStackThreat` | Typed threat with severity 0-100 |
| `UltronStackThreatAnalyzer` | Classifies top-of-stack ability by API type + name + context |
| `UltronTurnIntent` | Cached tactical intent: role, thresholds, mana reservation flags |
| `UltronTurnIntentBuilder` | Derives intent from table summary; no LLM |
| `UltronDecisionContext` | Snapshot of game state for one decision point |
| `UltronFastPriorityPolicy` | 9-step priority decision tree; < 10ms |
| `UltronInteractionPolicy` | Decides whether/what to counter or remove |
| `UltronManaReservation` | Mana hold specification |
| `UltronManaReservationPolicy` | Computes mana reservation from hand/intent |
| `UltronScore` | Composite action score |
| `UltronGameStateEvaluator` | Lightweight position evaluator |
| `UltronActionScorer` | Main-phase candidate scoring with multiplayer context |
| `UltronCandidatePruner` | Trims candidate list to `maxCandidates()` |
| `UltronTargetPriorityEvaluator` | Removal/benefit target scoring |
| `UltronCombatPolicy` | Multiplayer-aware attacker filter |
| `UltronRuntimeController` | Main entry point; `getOrCreate(game, player, memory)` |
| `UltronOfflineDecisionLogger` | Optional JSONL training data logger |

---

## Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `ULTRON_RUNTIME_ENABLED` | `true` | Enable fast runtime AI |
| `ULTRON_LLM_ADVISOR_ENABLED` | `false` | Enable blocking LLM gameplay advisor |
| `ULTRON_LLM_STRATEGIC_PLAN_ENABLED` | `false` | Enable LLM strategic plan |
| `ULTRON_CHAT_ENABLED` | `true` | Enable chat (does not affect gameplay) |
| `ULTRON_TABLE_TALK_ENABLED` | `true` | Enable table talk |
| `ULTRON_DECISION_LOGGING` | `false` | Enable structured decision logging |
| `ULTRON_RUNTIME_MAX_PRIORITY_MS` | `10` | Max ms for priority pass |
| `ULTRON_RUNTIME_MAX_STACK_MS` | `50` | Max ms for stack response |
| `ULTRON_RUNTIME_MAX_MAIN_PHASE_MS` | `500` | Max ms for main-phase scoring |
| `ULTRON_RUNTIME_MAX_CANDIDATES` | `32` | Max candidates scored |
| `ULTRON_OFFLINE_DECISION_LOGGING` | `false` | Enable JSONL decision log |
| `ULTRON_OFFLINE_DECISION_LOG_PATH` | `/tmp/ultron_decisions.jsonl` | Log output path |

---

## Compile Command
```
mvn compile -pl forge-ai -am -Dcheckstyle.skip=true
```
Result: **BUILD SUCCESS**

Note: Checkstyle has 18k pre-existing violations in the repo (AiController alone
triggers many). Our new files are clean; checkstyle failures are pre-existing.

---

## Test Command
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **14/14 PASSED**

Additional runtime coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **18/18 PASSED**

Interaction-policy coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronInteractionPolicyTest,forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **23/23 PASSED**

Fast-priority coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronFastPriorityPolicyTest,forge.ai.llm.runtime.UltronInteractionPolicyTest,forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **28/28 PASSED**

Main-phase policy coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronMainPhasePolicyTest,forge.ai.llm.runtime.UltronFastPriorityPolicyTest,forge.ai.llm.runtime.UltronInteractionPolicyTest,forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **32/32 PASSED**

Runtime-selection coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronRuntimeControllerSelectionTest,forge.ai.llm.runtime.UltronMainPhasePolicyTest,forge.ai.llm.runtime.UltronFastPriorityPolicyTest,forge.ai.llm.runtime.UltronInteractionPolicyTest,forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **34/34 PASSED**

Combat-policy coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronCombatPolicyTest,forge.ai.llm.runtime.UltronRuntimeControllerSelectionTest,forge.ai.llm.runtime.UltronMainPhasePolicyTest,forge.ai.llm.runtime.UltronFastPriorityPolicyTest,forge.ai.llm.runtime.UltronInteractionPolicyTest,forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **36/36 PASSED**

Runtime-cache invalidation coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronRuntimeCacheInvalidationTest,forge.ai.llm.runtime.UltronCombatPolicyTest,forge.ai.llm.runtime.UltronRuntimeControllerSelectionTest,forge.ai.llm.runtime.UltronMainPhasePolicyTest,forge.ai.llm.runtime.UltronFastPriorityPolicyTest,forge.ai.llm.runtime.UltronInteractionPolicyTest,forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **37/37 PASSED**

Runtime-hook invalidation coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronRuntimeHookInvalidationTest,forge.ai.llm.runtime.UltronRuntimeCacheInvalidationTest,forge.ai.llm.runtime.UltronCombatPolicyTest,forge.ai.llm.runtime.UltronRuntimeControllerSelectionTest,forge.ai.llm.runtime.UltronMainPhasePolicyTest,forge.ai.llm.runtime.UltronFastPriorityPolicyTest,forge.ai.llm.runtime.UltronInteractionPolicyTest,forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **38/38 PASSED**

Land-hook invalidation coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronRuntimeLandHookInvalidationTest,forge.ai.llm.runtime.UltronRuntimeHookInvalidationTest,forge.ai.llm.runtime.UltronRuntimeCacheInvalidationTest,forge.ai.llm.runtime.UltronCombatPolicyTest,forge.ai.llm.runtime.UltronRuntimeControllerSelectionTest,forge.ai.llm.runtime.UltronMainPhasePolicyTest,forge.ai.llm.runtime.UltronFastPriorityPolicyTest,forge.ai.llm.runtime.UltronInteractionPolicyTest,forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **39/39 PASSED**

Stack-hook invalidation coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronRuntimeStackHookInvalidationTest,forge.ai.llm.runtime.UltronRuntimeLandHookInvalidationTest,forge.ai.llm.runtime.UltronRuntimeHookInvalidationTest,forge.ai.llm.runtime.UltronRuntimeCacheInvalidationTest,forge.ai.llm.runtime.UltronCombatPolicyTest,forge.ai.llm.runtime.UltronRuntimeControllerSelectionTest,forge.ai.llm.runtime.UltronMainPhasePolicyTest,forge.ai.llm.runtime.UltronFastPriorityPolicyTest,forge.ai.llm.runtime.UltronInteractionPolicyTest,forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **40/40 PASSED**

AiController integration coverage:
```
mvn test -pl forge-gui-desktop -am -Dcheckstyle.skip=true \
  -Dtest=forge.ai.llm.runtime.UltronAiControllerIntegrationTest,forge.ai.llm.runtime.UltronRuntimeStackHookInvalidationTest,forge.ai.llm.runtime.UltronRuntimeLandHookInvalidationTest,forge.ai.llm.runtime.UltronRuntimeHookInvalidationTest,forge.ai.llm.runtime.UltronRuntimeCacheInvalidationTest,forge.ai.llm.runtime.UltronCombatPolicyTest,forge.ai.llm.runtime.UltronRuntimeControllerSelectionTest,forge.ai.llm.runtime.UltronMainPhasePolicyTest,forge.ai.llm.runtime.UltronFastPriorityPolicyTest,forge.ai.llm.runtime.UltronInteractionPolicyTest,forge.ai.llm.runtime.UltronThreatModelAndIntentTest,forge.ai.llm.runtime.UltronStackThreatAnalyzerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **42/42 PASSED**

---

## API Quirks Found During Implementation

- `ApiType.Exile` does not exist — exile is handled via `ApiType.ChangeZone`
- `Keyword.UNBLOCKABLE` does not exist — "can't be blocked" is detected via oracle text
- `SpellAbility.isInstant()` does not exist — check `sa.getHostCard().isInstant()`
- `SpellAbility.hasKeyword(String)` does not exist — check `sa.getHostCard().hasKeyword(Keyword.FLASH)`
- Counter types live in `forge.game.card.CounterEnumType`, not `forge.game.counter.CounterEnumType`
- Card oracle text: `c.getOracleText()` (not `c.getText()`)
- `UltronConfig.boolEnv()` needs to be `public` for access from `runtime` subpackage

---

## Preserved Systems (Unchanged)
- `UltronAdvisor` — chat, table-talk, traces, learning, state serialization
- `UltronGameContext` — prompt builder
- `UltronStrategicPlan` — JSON plan parser (still usable when flag enabled)
- `UltronGameStateSerializer` — large JSON serializer (not called at runtime)
- `UltronLearningStore`, `UltronTraceStore`, `UltronResearchTools`
- `UltronPrompts`, `UltronSpeech`, `DeepSeekClient`
- All Forge safety filters: `canPlayAndPayFor`, `saEvaluator`, `sortCreatureSpells`, etc.

---

## Behavioral Scenarios Covered

1. **No LLM during normal gameplay** — `ULTRON_LLM_ADVISOR_ENABLED` defaults false; runtime runs without API key
2. **Pass trivial priority** — `UltronFastPriorityPolicy` step 1: no candidates → PASS
3. **Respond to lethal** — `UltronStackThreatAnalyzer` classifies lethal at sev=98-99; interaction policy counters it
4. **Allow board wipe when behind** — severity formula returns ~35 when `ultronIsAhead=false`; below threshold → PASS
5. **Counter board wipe when ahead** — severity ~85 when `ultronIsAhead=true`; above threshold → counter
6. **Table leader identification** — `UltronTableThreatSummary` marks highest `boardValue` opponent as leader
7. **Attack most vulnerable** — `UltronTurnIntent.preferredAttackTarget` = opponent with `life <= 10`
8. **Main-phase scoring** — `UltronActionScorer` penalizes tapping out when ahead
9. **Leader / danger / vulnerability table reads** — covered by `UltronThreatModelAndIntentTest`
10. **Desperate-mode role selection** — covered by `UltronThreatModelAndIntentTest`
11. **Counter lethal with live candidate spell** — covered by `UltronInteractionPolicyTest`
12. **Ignore low-value stack noise** — covered by `UltronInteractionPolicyTest`
13. **Do not overreact to weak non-leader combo noise** — covered by `UltronInteractionPolicyTest`
14. **Treat board wipes differently when ahead vs behind** — covered by `UltronInteractionPolicyTest`
15. **Pass immediately with no candidates** — covered by `UltronFastPriorityPolicyTest`
16. **Defer own main-phase empty-stack decisions to main-phase scoring** — covered by `UltronFastPriorityPolicyTest`
17. **Allow opponent-end-step instant-speed opportunities instead of auto-pass** — covered by `UltronFastPriorityPolicyTest`
18. **Pass when Ultron already controls the top of the stack** — covered by `UltronFastPriorityPolicyTest`
19. **Pass on weak-opponent low-value stack spells through full priority policy** — covered by `UltronFastPriorityPolicyTest`
20. **Reserve counterspell mana when ahead** — covered by `UltronMainPhasePolicyTest`
21. **Drop mana reservation in desperate mode** — covered by `UltronMainPhasePolicyTest`
22. **Prefer board development over main-phase counterspell deployment when ahead** — covered by `UltronMainPhasePolicyTest`
23. **Relax ahead-state tap-out penalties while stabilizing** — covered by `UltronMainPhasePolicyTest`
24. **Honor filler-pruning even when the candidate list is small** — covered by `UltronRuntimeControllerSelectionTest`
25. **Pass instead of deploying pruned filler when runtime policy rejects every main-phase option** — covered by `UltronRuntimeControllerSelectionTest`
26. **Prefer a clean kill on the vulnerable target over generic leader pressure even outside explicit lethal mode** — covered by `UltronCombatPolicyTest`
27. **Remove attacks when crackback would be lethal** — covered by `UltronCombatPolicyTest`
28. **Invalidate cached table state along with turn intent after a same-turn board swing** — covered by `UltronRuntimeCacheInvalidationTest`
29. **Refresh Ultron runtime cache from a real AI `playNoStack` action hook** — covered by `UltronRuntimeHookInvalidationTest`
30. **Refresh Ultron runtime cache after a real AI land play through `playChosenSpellAbility`** — covered by `UltronRuntimeLandHookInvalidationTest`
31. **Refresh Ultron runtime cache after a real AI stack-based spell play through `handlePlayingSpellAbility`** — covered by `UltronRuntimeStackHookInvalidationTest`

---

## Completed TODOs (Phase 13 follow-up)

All five remaining TODOs from the original remodel plan have been addressed:

- **Commander-aware combat**: `UltronOpponentProfile.analyze()` signature changed from
  `analyze(Player opp, int ultronLife)` to `analyze(Player opp, Player ultron)`. Now populates
  `commanderValue` from commander damage already dealt to Ultron (`ultron.getCommanderDamage()`)
  and commander power on the opponent's battlefield (`c.isCommander()`). Commander damage >= 15
  escalates `lethalThreatToUltron` to at least 80. `commanderValue/5` added to `boardValue`.

- **Per-target crackback in combat filtering**: `UltronCombatPolicy.filterAttackers()` now computes
  crackback per attacker→target pair. Crackback from *other* opponents (not the one being attacked)
  is counted as ambient future retaliation; the target's own untapped power contributes blocker risk
  (`targetProfile.untappedPower / 2`). Removes the over-conservative "count all opponents' evasive
  power for every attack" behavior.

- **Player elimination PRESSURING escalation**: `UltronTurnIntentBuilder.build()` now checks after
  role determination: if the most vulnerable opponent is at ≤5 life and Ultron has any board
  presence, role escalates to `PRESSURING`, `preferredAttackTarget` is set to that player, and
  `lookForLethal = true`. Desperate/Stabilizing override takes priority.

- **Strategic plan hints**: `UltronStrategicPlan.getHoldInteraction()` added. `UltronAdvisor` exposes
  `getLastPlanHoldInteraction(Game, Player)`. `UltronRuntimeController` accepts
  `injectPlanHints(Set<String>, Set<String>)` which forces an intent rebuild incorporating the LLM
  plan's hold names. `AiController` calls this after `chooseFromStrategicPlan()` so subsequent
  runtime decisions in the same game reflect the strategic plan's interaction intent.
  `UltronTurnIntentBuilder.build()` accepts hold/protect name sets (backward-compatible no-arg overload
  preserved).

- **Simulation integration**: `UltronGameStateEvaluator.evaluateWithSimulation(Game, Player)` calls
  `forge.ai.simulation.GameStateEvaluator.getScoreForGameState()`. `UltronTableThreatSummary.analyze()`
  now accepts `Game` (passed through from `UltronThreatModel`). When `ULTRON_USE_SIMULATION_EVAL=true`,
  the simulation score overrides the heuristic `ultronIsAhead`/`ultronIsBehind` flags. Disabled by
  default to preserve existing performance; enable for heavier but more accurate position assessment.
  `UltronConfig.useSimulationEval()` flag added.

## Remaining TODOs / Future Work

- **Test coverage for game-engine integration tests**: Current tests are pure-unit; game-integration
  tests would need a running Forge instance
