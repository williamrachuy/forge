# Forge Agent Notes

This file is a compact orientation map for Codex/agent sessions in `/home/william/github/forge`.
Use it to avoid broad rediscovery. Verify exact code before changing behavior.

## Operating Rules

- Treat this checkout as a real local workstation repo. Inspect live state before editing.
- Always check `git status --short --branch` before making changes. This repo often has substantial local Battlebox work in progress.
- Do not revert or normalize unrelated local edits. If a task overlaps dirty files, read the current file and work with the existing changes.
- Prefer `rg` and `rg --files` for lookup. Broad grep over all card scripts is noisy.
- Use `apply_patch` for manual edits.
- Avoid adding tests reflexively. Forge's contributing notes explicitly warn that agent-added tests should be limited to real regression coverage.

## Project Shape

- Maven multi-module Java project, Java 17 minimum. Local environment has Java 21 and Maven 3.9.9.
- Root POM modules: `forge-core`, `forge-game`, `forge-ai`, `forge-gui`, `forge-gui-mobile`, `forge-gui-mobile-dev`, `forge-gui-desktop`, `forge-gui-ios`, `forge-lda`, `adventure-editor`, `forge-gui-android`, `forge-installer`.
- Main desktop entrypoint: `forge-gui-desktop/src/main/java/forge/view/Main.java`.
- Desktop GUI platform adapter: `forge-gui-desktop/src/main/java/forge/GuiDesktop.java`.
- Startup singleton/model path: `Main` -> `Singletons.initializeOnce` -> `FModel.initialize` -> `StaticData`.
- Shared game/lobby app layer lives in `forge-gui`.
- Swing desktop screens/widgets live in `forge-gui-desktop`.
- Mobile/adventure LibGDX logic lives in `forge-gui-mobile`; desktop mobile-dev launcher uses `forge-gui-mobile-dev`.

## Module Responsibilities

- `forge-core`: card/deck/data primitives, card database, deck serialization, file utilities, storage abstractions.
- `forge-game`: rules engine, game state, zones, players, match flow, spell abilities, triggers, replacements, static abilities, mulligans.
- `forge-ai`: heuristic AI controller, ability-specific AI handlers, combat/blocking decisions, optional game simulation.
- `forge-gui`: shared app model, preferences, lobby/match hosting, netplay, quest/conquest/limited systems, human controller.
- `forge-gui-desktop`: Swing shell, home/match/deck editor screens, desktop deck chooser, sound, dev UI.
- `forge-gui/res`: data/content. Most `.txt` files under `cardsfolder` are card scripts, not Java code.

## High-Value Entry Points

- App startup: `forge-gui-desktop/src/main/java/forge/view/Main.java`.
- Model/data initialization: `forge-gui/src/main/java/forge/model/FModel.java`.
- Static card/set database: `forge-core/src/main/java/forge/StaticData.java`.
- Card script loading: `forge-core/src/main/java/forge/CardStorageReader.java`.
- Card DB and print lookup: `forge-core/src/main/java/forge/card/CardDb.java`.
- Deck model: `forge-core/src/main/java/forge/deck/Deck.java`.
- Deck sections: `forge-core/src/main/java/forge/deck/DeckSection.java`.
- `.dck` serializer/parser: `forge-core/src/main/java/forge/deck/io/DeckSerializer.java`.
- Deck metadata header: `forge-core/src/main/java/forge/deck/io/DeckFileHeader.java`.
- Game type definitions: `forge-game/src/main/java/forge/game/GameType.java`.
- Game rules flags: `forge-game/src/main/java/forge/game/GameRules.java`.
- Match and zone preparation: `forge-game/src/main/java/forge/game/Match.java`.
- Single-game state: `forge-game/src/main/java/forge/game/Game.java`.
- Zone movement: `forge-game/src/main/java/forge/game/GameAction.java`.
- Player state/zones/actions: `forge-game/src/main/java/forge/game/player/Player.java`.
- Registered pre-game player config: `forge-game/src/main/java/forge/game/player/RegisteredPlayer.java`.
- Lobby validation and match construction: `forge-gui/src/main/java/forge/gamemodes/match/GameLobby.java`.
- Hosted match lifecycle: `forge-gui/src/main/java/forge/gamemodes/match/HostedMatch.java`.
- Human controller/input bridge: `forge-gui/src/main/java/forge/player/PlayerControllerHuman.java`.
- Desktop match UI: `forge-gui-desktop/src/main/java/forge/screens/match/CMatchUI.java`.
- Desktop lobby UI: `forge-gui-desktop/src/main/java/forge/screens/home/VLobby.java`.
- Command-line simulation: `forge-gui-desktop/src/main/java/forge/view/SimulateMatch.java`.

## Ability/Card Script Pipeline

- Card scripts are in `forge-gui/res/cardsfolder/<letter>/*.txt`.
- Script docs start at `docs/Card-scripting-API/Card-scripting-API.md`.
- Ability strings are parsed by `forge-game/src/main/java/forge/game/ability/AbilityFactory.java`.
- API names map to effect classes in `forge-game/src/main/java/forge/game/ability/ApiType.java`.
- Effect implementations live in `forge-game/src/main/java/forge/game/ability/effects/`.
- Shared script resolution helpers are in `forge-game/src/main/java/forge/game/ability/AbilityUtils.java`.
- Triggers live in `forge-game/src/main/java/forge/game/trigger/`.
- Replacements live in `forge-game/src/main/java/forge/game/replacement/`.
- Static abilities live in `forge-game/src/main/java/forge/game/staticability/`.
- AI support for APIs lives mostly in `forge-ai/src/main/java/forge/ai/ability/` and maps through `SpellApiToAi`.

## Ultron AI Advisor

- `forge-gui/res/ai/Ultron.ai` is intentionally a baseline clone of `Default.ai`; avoid adding arbitrary keys unless `AiProps` is extended because profile loading uses `AiProps.valueOf`.
- `forge-ai/src/main/java/forge/ai/llm/` contains the Ultron-only DeepSeek advisor path.
- `AiController.chooseSpellAbilityToPlayFromList` remains the legal/playability gate. For the `Ultron` profile only, it gathers a bounded list of Forge-approved `WillPlay` candidates and asks the advisor to rerank or return `-1` to pass/hold.
- Other profiles bypass the LLM path entirely. If DeepSeek is unavailable, times out, returns invalid JSON, or selects an invalid index, Ultron falls back to Forge's first heuristic candidate.
- DeepSeek config is environment-driven: `DEEPSEEK_API_KEY`, optional `ULTRON_DEEPSEEK_BASE_URL` or `DEEPSEEK_BASE_URL`, `ULTRON_DEEPSEEK_MODEL`, `ULTRON_DEEPSEEK_THINKING`, `ULTRON_DEEPSEEK_REASONING_EFFORT`, `ULTRON_DEEPSEEK_TIMEOUT_MS`, `ULTRON_DEEPSEEK_MAX_TOKENS`, `ULTRON_DEEPSEEK_CANDIDATES`, `ULTRON_CONTEXT_MAX_TIMELINE_CHARS`, `ULTRON_DEEPSEEK_DEBUG`, and `ULTRON_LLM_ENABLED=false` to disable.
- Ultron learning is Forge-side and persistent because DeepSeek API calls are stateless. `UltronLearningStore` appends fair-visible decision telemetry, game outcomes, and compact cross-game memories; future advisor prompts retrieve relevant memories from that store. Defaults write under `${user.home}/.forge/ultron-learning`; use `ULTRON_LEARNING_DIR` to relocate, `ULTRON_LEARNING_ENABLED=false` to disable, `ULTRON_LEARNING_RECORD_STATE=false` to omit full visible-state telemetry, and tune prompt injection with `ULTRON_LEARNING_SCAN_LINES`, `ULTRON_LEARNING_MAX_PROMPT_ENTRIES`, `ULTRON_LEARNING_MAX_PROMPT_CHARS`, `ULTRON_LEARNING_MEMORY_DECISIONS_PER_GAME`, and `ULTRON_LEARNING_MAX_STATE_CHARS`.
- Ultron sends full setup/deck context only on the first advisor request for a game. Later advisor requests send compact setup context with deck metadata/section counts, prior visible progression, retrieved long-term memories, and current visible state. This reduces repeated Battlebox decklist tokens; remember that DeepSeek does not retain hidden server-side state across API calls.
- Ultron trace visibility is on by default. `UltronTraceStore` writes the most recent request/response to `${user.home}/.forge/ultron-learning/trace/latest.md` and appends all requests to `trace/transcript.md`; use `ULTRON_TRACE_DIR` to relocate, `ULTRON_TRACE_ENABLED=false` to disable, `ULTRON_TRACE_INCLUDE_PROMPT=false` or `ULTRON_TRACE_INCLUDE_RAW_RESPONSE=false` to reduce captured data, and `ULTRON_TRACE_MAX_ENTRY_CHARS` to cap each entry.
- Current official DeepSeek API details as of 2026-05-22: base URL `https://api.deepseek.com`, chat endpoint `/chat/completions`, model IDs `deepseek-v4-flash` and `deepseek-v4-pro`; legacy `deepseek-chat` and `deepseek-reasoner` are deprecated for 2026-07-24.
- Ultron defaults are tuned for capability, not speed: `deepseek-v4-pro`, `thinking.enabled`, `reasoning_effort=max`, `ULTRON_DEEPSEEK_TIMEOUT_MS=45000`, `ULTRON_DEEPSEEK_MAX_TOKENS=4096`, and a default 45s advisor wait. Override `ULTRON_DEEPSEEK_WAIT_MS` when testing faster/slower behavior.
- `forge-ai/src/main/resources/forge/ai/llm/ultron_system.md` is the mission/rules prompt loaded into every request.
- `UltronGameContext` stores a per-game, per-advising-player visible context. Every request includes the initial setup, player count, turn order, format/variant data, known deck context, prior Ultron decision checkpoints, and the current visible state. DeepSeek does not retain server-side game memory by itself.
- Visibility boundary: serialize only the advising player's visible information. Use `CardView.canBeShownTo`, `CardView.canFaceDownBeShownTo`, public zones, own visible hand, visible/revealed hand cards from `AiCardMemory.MemorySet.REVEALED_CARDS`, counts for hidden zones, and public untapped mana-source color counts. Do not export hidden opponent hand/library contents or hidden own library order.

## Deck/Data Pipeline

- User/profile deck roots are defined in `ForgeConstants`, derived from `ForgeProfileProperties`.
- Built-in resource data lives under `forge-gui/res`.
- Deck files use sections like `[metadata]`, `[Main]`, `[Sideboard]`, and variant sections.
- `Deck` preserves unknown metadata in a case-insensitive map. Mode-specific knobs should generally live in deck metadata instead of ad hoc parser branches.
- `CardPool.fromCardList` and `CardDb.CardRequest` are important when exact print, set code, collector number, art index, foil, or flags matter.
- Deck loading can defer section resolution until card data is initialized.

## Current Battlebox Map

Local work has made Battlebox a first-class variant seam. Recheck current code before changing it.

- `forge-game/src/main/java/forge/game/BattleboxConfig.java`: local config seam for Battlebox metadata.
- `forge-game/src/main/java/forge/game/zone/SharedPlayerZone.java`: shared zone view/update helper.
- `Match.prepareBattleboxSharedLibrary`, `prepareBattleboxSharedCommand`, and `prepareBattleboxSharedGraveyard`: create shared library, command land station, and graveyard.
- `GameLobby.validateBattleboxDeck`: lobby-visible Battlebox validation before match startup.
- `RegisteredPlayer` carries Battlebox starting life, starting hand, max hand, and land station.
- `Player` has shared library/command/graveyard zone references plus ownership-claim helpers.
- `GameAction` has logic to claim shared Battlebox library/graveyard cards as they move.
- `PlayerControllerHuman` permits actions from Battlebox shared graveyard and land station where appropriate.
- `DeckSection.LandStation` exists locally; older Battlebox behavior may have used sideboard as land station fallback.
- `ForgeConstants.DECK_BATTLEBOX_DIR`, `CardCollections.getBattlebox`, `DeckProxy.getAllBattleboxDecks`, desktop deck chooser, and `SimulateMatch` are involved in Battlebox deck discovery.

Known Battlebox metadata concepts in local code:

- `BattleboxStartingLife`
- `BattleboxStartingHandSize`
- `BattleboxMaxHandSize`
- `PlayerLibrarySize`
- `SeedBasicLands`
- `BattleboxLibrarySize` is treated as legacy/replaced metadata in current local code.

Prior user preference for Battlebox work:

- Prefer deck-file metadata knobs when possible.
- Invalid Battlebox setup should fail in the lobby with a clear error, not during match startup.
- If changing random sampling or deck-size semantics, preserve multiplicity from `[Main]` unless explicitly told otherwise.

## Build And Test Commands

- Fast compile for game/gui seams: `mvn -pl forge-game,forge-gui -am -DskipTests compile`.
- Desktop package path used before: `mvn -pl forge-gui-desktop -am -DskipTests package`.
- Full CI-style test command: `mvn -U -B clean test`.
- CI runs tests under Xvfb. For GUI/headless behavior, use a virtual framebuffer when needed.
- Network stress tests are under `forge-gui-desktop/src/test/java/forge/net/` and are usually gated by `-Drun.stress.tests=true`.
- Network docs: `docs/Development/Network-Testing.md`.
- AI simulation CLI docs: `docs/AI.md`.

## Search Recipes

- Module map: `find . -maxdepth 2 -type d | sort`.
- Java entrypoints: `rg -n "public static void main|class Main" forge-gui-desktop forge-gui-mobile forge-gui-android adventure-editor`.
- Ability API lookup: `rg -n "ApiName|class .*Effect|enum ApiType" forge-game/src/main/java/forge/game/ability`.
- Card script lookup: `rg -n "Card Name|SVar|SP\\$|AB\\$|DB\\$" forge-gui/res/cardsfolder`.
- Deck format and metadata lookup: `rg -n "DeckSection|DeckSerializer|DeckFileHeader|metadata|getMetadata" forge-core/src/main/java forge-gui/src/main/java`.
- Lobby startup path: `rg -n "startGame\\(|startMatch\\(|RegisteredPlayer|GameRules" forge-gui/src/main/java forge-game/src/main/java`.
- Battlebox seam: `rg -n "Battlebox|BattleboxConfig|SharedPlayerZone|LandStation|PlayerLibrarySize|SeedBasicLands" forge-core forge-game forge-gui forge-gui-desktop`.

## Common Pitfalls

- `forge-gui` is not only GUI widgets; it owns shared application model, lobby, hosted match, preferences, netplay, and human controller code.
- `forge-core` is data/core primitives, while `forge-game` is the actual rules engine despite some older docs describing the split loosely.
- Many Forge behaviors are content-scripted. Check card scripts and API docs before assuming a Java-only fix.
- Deck metadata is case-insensitive through `Deck`'s metadata map.
- `GameType.Constructed` may still be used as the base match type while variants are applied through `GameRules.appliedVariants`.
- Battlebox currently uses shared physical zones; normal assumptions about each player owning a separate library/graveyard may be wrong in that mode.
- The root worktree may be dirty with user changes. Do not run cleanup commands, `git reset`, or checkout files unless explicitly requested.
