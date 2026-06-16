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

---

## Remaining TODOs / Future Work

- **Simulation integration**: `UltronGameStateEvaluator` is currently heuristic-only; could integrate
  `forge.ai.simulation.GameStateEvaluator` for deeper lookahead when budget allows
- **Strategic plan hints**: When `ULTRON_LLM_STRATEGIC_PLAN_ENABLED=true`, plan hold/protect arrays
  could feed into `UltronTurnIntent.holdCardNames` / `protectCardNames`
- **Commander-aware combat**: `commanderValue` field exists but not fully populated
- **Expanded combat filtering**: `UltronCombatPolicy.filterAttackers` uses estimated crackback;
  a more precise per-attack-target analysis would improve aggression decisions
- **Player elimination detection**: When a player is at low life and we have the kill, intent
  should prioritize `PRESSURING` mode more aggressively
- **Test coverage for game-engine integration tests**: Current tests are pure-unit; game-integration
  tests would need a running Forge instance
