package forge.ai.llm;

import com.google.common.eventbus.Subscribe;
import forge.ai.AiCardMemory;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.BattleboxConfig;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.card.CardView;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardAttachment;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventCardSacrificed;
import forge.game.event.GameEventCombatEnded;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventPlayerPoisoned;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
import forge.item.PaperCard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

final class UltronGameContext {
    private static final int DEFAULT_TIMELINE_MAX_CHARS = 8000;
    private static final int DEFAULT_CHAT_MAX_CHARS = 4000;
    private static final int DEFAULT_RECENT_EVENT_MAX_CHARS = 3000;
    private static final int DEFAULT_REFERENCE_CACHE_MAX_CHARS = 8000;
    private static final int DEFAULT_TABLE_TALK_MAX_PER_GAME = 3;
    private static final int DEFAULT_TABLE_TALK_COOLDOWN_MS = 90000;

    private final String advisorSessionId = UUID.randomUUID().toString();
    private final Game game;
    private final Player advisor;
    private final String fullInitialContext;
    private final String recurringContext;
    private final String chatContext;
    private final Deque<String> timeline = new ArrayDeque<>();
    private final Deque<String> recentEvents = new ArrayDeque<>();
    private final Deque<String> chatTimeline = new ArrayDeque<>();
    private final LinkedHashMap<String, String> referenceCache = new LinkedHashMap<>();
    private final List<UltronLearningStore.DecisionRecord> decisionRecords = new ArrayList<>();
    private int timelineChars;
    private int recentEventChars;
    private int chatTimelineChars;
    private int referenceCacheChars;
    private int decisionCount;
    private int tableTalkCount;
    private long lastTableTalkMillis;
    private boolean fullInitialContextSent;
    private boolean outcomeRecorded;
    private String lastAssessmentEvents = "";

    UltronGameContext(Game game, Player advisor) {
        this.game = game;
        this.advisor = advisor;
        this.fullInitialContext = buildInitialContext(game, advisor, true);
        this.recurringContext = buildInitialContext(game, advisor, false);
        this.chatContext = buildChatContext(game, advisor);
    }

    synchronized String buildPrompt(String currentVisibleState, String longTermMemory) {
        boolean includeFullContext = !fullInitialContextSent;
        String setupContext = includeFullContext ? fullInitialContext : recurringContext;
        fullInitialContextSent = true;

        String recentEventText = drainRecentEventsForPrompt();
        StringBuilder sb = new StringBuilder(setupContext.length() + currentVisibleState.length()
                + timelineChars + recentEventText.length() + (longTermMemory == null ? 0 : longTermMemory.length()) + 2048);
        sb.append("Use the mission instructions, initial game context, prior visible progression, and current visible state.\n");
        sb.append("Return either a final decision JSON object or a visible-safe research tool request JSON object.\n\n");
        sb.append("<initial_game_context>\n");
        sb.append(setupContext);
        sb.append("\n</initial_game_context>\n\n");
        sb.append("<long_term_learning>\n");
        if (longTermMemory == null || longTermMemory.isBlank()) {
            sb.append("No stored cross-game memories retrieved.\n");
        } else {
            sb.append("These are Forge-side memories from prior games with the same visibility restrictions. ");
            sb.append("Treat them as fallible strategic heuristics, not rules text or hidden information.\n");
            sb.append(longTermMemory);
        }
        sb.append("</long_term_learning>\n\n");
        appendReferenceCacheIfPresent(sb);
        appendChatHistoryIfPresent(sb);
        sb.append("<recent_visible_events_since_last_assessment>\n");
        sb.append(recentEventText);
        sb.append("</recent_visible_events_since_last_assessment>\n\n");
        sb.append("<prior_visible_progression>\n");
        if (timeline.isEmpty()) {
            sb.append("No prior Ultron advisor checkpoints.\n");
        } else {
            for (String entry : timeline) {
                sb.append(entry).append('\n');
            }
        }
        sb.append("</prior_visible_progression>\n\n");
        sb.append("<current_visible_state>\n");
        sb.append(currentVisibleState);
        sb.append("\n</current_visible_state>\n");
        return sb.toString();
    }

    synchronized String buildResearchPrompt(String basePrompt, String researchTranscript, int round, int maxRounds) {
        StringBuilder sb = new StringBuilder(basePrompt.length()
                + (researchTranscript == null ? 0 : researchTranscript.length()) + 1024);
        sb.append(basePrompt);
        sb.append("\n\n<forge_research_results>\n");
        if (researchTranscript == null || researchTranscript.isBlank()) {
            sb.append("No research results have been returned yet.\n");
        } else {
            sb.append(researchTranscript);
            if (!researchTranscript.endsWith("\n")) {
                sb.append('\n');
            }
        }
        sb.append("</forge_research_results>\n\n");
        sb.append("Research round ").append(round).append(" of ").append(maxRounds).append(" is complete. ");
        sb.append("Return the final JSON choice if you have enough information. ");
        sb.append("If another research round remains and a specific visible-safe probe is still necessary, return toolRequests only.\n");
        return sb.toString();
    }

    synchronized void rememberCardReferences(Map<String, String> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        int maxChars = parsePositiveInt(System.getenv("ULTRON_REFERENCE_CACHE_MAX_CHARS"), DEFAULT_REFERENCE_CACHE_MAX_CHARS);
        for (Entry<String, String> entry : references.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            String existing = referenceCache.remove(entry.getKey());
            if (existing != null) {
                referenceCacheChars -= existing.length() + 1;
            }
            String value = entry.getValue().replace('\n', ' ').replace('\r', ' ').trim();
            referenceCache.put(entry.getKey(), value);
            referenceCacheChars += value.length() + 1;
        }
        while (referenceCacheChars > maxChars && !referenceCache.isEmpty()) {
            Entry<String, String> oldest = referenceCache.entrySet().iterator().next();
            referenceCacheChars -= oldest.getValue().length() + 1;
            referenceCache.remove(oldest.getKey());
        }
    }

    synchronized void recordAgentMemory(String kind, String text) {
        UltronLearningStore.get().recordAgentNote(advisorSessionId, game, advisor, kind, text);
    }

    synchronized String buildChatPrompt(String publicState, String speakerName, String message) {
        StringBuilder sb = new StringBuilder(chatContext.length() + publicState.length() + chatTimelineChars + 2048);
        sb.append("Respond as Ultron to an in-game chat message.\n");
        sb.append("This prompt intentionally omits private hidden zones. Do not infer private hand contents beyond public information.\n\n");
        sb.append("<game_context>\n");
        sb.append(chatContext);
        sb.append("\n</game_context>\n\n");
        appendChatHistoryIfPresent(sb);
        sb.append("<current_public_state>\n");
        sb.append(publicState);
        sb.append("\n</current_public_state>\n\n");
        sb.append("<incoming_message>\n");
        sb.append(speakerName == null || speakerName.isBlank() ? "Player" : speakerName);
        sb.append(": ");
        sb.append(message == null ? "" : message);
        sb.append("\n</incoming_message>\n");
        return sb.toString();
    }

    synchronized String buildTableTalkPrompt(String publicState, String eventSummary) {
        StringBuilder sb = new StringBuilder(chatContext.length() + publicState.length() + chatTimelineChars + 2048);
        sb.append("Decide whether Ultron should say one short line about this public game event.\n");
        sb.append("If there is nothing interesting to say, return an empty message.\n");
        sb.append("This prompt intentionally omits private hidden zones. Do not infer private hand contents beyond public information.\n\n");
        sb.append("<game_context>\n");
        sb.append(chatContext);
        sb.append("\n</game_context>\n\n");
        appendChatHistoryIfPresent(sb);
        sb.append("<current_public_state>\n");
        sb.append(publicState);
        sb.append("\n</current_public_state>\n\n");
        sb.append("<public_event>\n");
        sb.append(eventSummary == null ? "" : eventSummary);
        sb.append("\n</public_event>\n");
        return sb.toString();
    }

    synchronized void recordChat(String speakerName, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String speaker = speakerName == null || speakerName.isBlank() ? "Player" : speakerName.trim();
        appendChatTimeline(speaker + ": " + message.replace('\n', ' ').trim());
    }

    synchronized void recordDecision(Game game, Player advisor, List<SpellAbility> candidates,
            AiCardMemory memory, UltronAdvisor.Decision decision, String visibleState,
            DeepSeekClient.CompletionResult completion) {
        decisionCount++;
        String summary = UltronGameStateSerializer.summarizeDecisionPoint(game, advisor, candidates, memory);
        StringBuilder sb = new StringBuilder(2048);
        sb.append(decisionCount).append(". ");
        sb.append(summary);
        sb.append(" advisor_choice=");
        String choiceType;
        if (!decision.hasAdvice()) {
            sb.append("no_advice");
            choiceType = "no_advice";
        } else if (decision.getChoiceIndex() < 0) {
            sb.append("pass");
            choiceType = "pass";
        } else {
            sb.append(decision.getChoiceIndex());
            choiceType = "choose";
        }
        if (decision.getRationale() != null && !decision.getRationale().isBlank()) {
            sb.append(" rationale=").append(oneLine(truncate(decision.getRationale(), 500)));
        }
        if (!isBlank(lastAssessmentEvents) && !lastAssessmentEvents.startsWith("No visible events")) {
            sb.append(" recent_events=").append(oneLine(truncate(lastAssessmentEvents, 600)));
        }
        appendTimeline(sb.toString());

        PhaseHandler phase = game.getPhaseHandler();
        SpellAbility chosen = decision.getSpellAbility();
        UltronLearningStore.DecisionRecord record = new UltronLearningStore.DecisionRecord(
                decisionCount,
                phase.getTurn(),
                phase.getPhase().toString(),
                phase.getPlayerTurn().getName(),
                choiceType,
                decision.getChoiceIndex(),
                chosen == null ? "" : UltronGameStateSerializer.sourceName(chosen, advisor, memory),
                chosen == null ? "" : UltronGameStateSerializer.apiName(chosen),
                decision.getRationale(),
                completion == null ? "" : completion.content(),
                completion == null ? "" : completion.reasoningContent(),
                lastAssessmentEvents,
                summary);
        decisionRecords.add(record);
        UltronLearningStore.get().recordDecision(advisorSessionId, game, advisor, candidates, memory, record, visibleState);
    }

    @Subscribe
    public synchronized void receive(GameEventGameOutcome outcome) {
        if (outcomeRecorded) {
            return;
        }
        outcomeRecorded = true;
        UltronLearningStore.get().recordOutcome(advisorSessionId, game, advisor, outcome, new ArrayList<>(decisionRecords));
    }

    @Subscribe
    public void receive(GameEventSpellAbilityCast event) {
        appendVisibleEvent("cast", eventSummary(event));
        if (!UltronAdvisor.isTableTalkEnabled() || event == null || event.si() == null) {
            return;
        }
        PlayerView activatingPlayerView = event.si().getActivatingPlayer();
        if (activatingPlayerView == null) {
            return;
        }
        Player activatingPlayer = game.getPlayer(activatingPlayerView);
        if (activatingPlayer == null || activatingPlayer.equals(advisor)) {
            return;
        }
        if (!reserveTableTalkSlot()) {
            return;
        }
        UltronAdvisor.get().commentOnPublicEvent(game, advisor, this, event.toString());
    }

    @Subscribe
    public synchronized void receive(GameEventSpellResolved event) {
        appendVisibleEvent("resolve", eventSummary(event));
    }

    @Subscribe
    public synchronized void receive(GameEventLandPlayed event) {
        appendVisibleEvent("land", eventSummary(event));
    }

    @Subscribe
    public synchronized void receive(GameEventCardChangeZone event) {
        String summary = zoneChangeSummary(event);
        if (!isBlank(summary)) {
            appendVisibleEvent("zone", summary);
        }
    }

    @Subscribe
    public synchronized void receive(GameEventCardSacrificed event) {
        if (canShow(event.card())) {
            appendVisibleEvent("sacrifice", eventSummary(event));
        }
    }

    @Subscribe
    public synchronized void receive(GameEventCardAttachment event) {
        if (canShow(event.equipment())) {
            appendVisibleEvent("attach", eventSummary(event));
        }
    }

    @Subscribe
    public synchronized void receive(GameEventAttackersDeclared event) {
        appendVisibleEvent("attack", eventSummary(event));
    }

    @Subscribe
    public synchronized void receive(GameEventBlockersDeclared event) {
        appendVisibleEvent("block", eventSummary(event));
    }

    @Subscribe
    public synchronized void receive(GameEventCombatEnded event) {
        appendVisibleEvent("combat", eventSummary(event));
    }

    @Subscribe
    public synchronized void receive(GameEventPlayerDamaged event) {
        appendVisibleEvent("damage", eventSummary(event));
    }

    @Subscribe
    public synchronized void receive(GameEventPlayerLivesChanged event) {
        appendVisibleEvent("life", eventSummary(event));
    }

    @Subscribe
    public synchronized void receive(GameEventPlayerPoisoned event) {
        appendVisibleEvent("poison", event.receiver() + " poison " + event.oldValue() + " -> "
                + (event.oldValue() + event.amount()) + " from " + event.source());
    }

    @Subscribe
    public synchronized void receive(GameEventCardCounters event) {
        if (canShow(event.card())) {
            appendVisibleEvent("counter", eventSummary(event));
        }
    }

    @Subscribe
    public synchronized void receive(GameEventTurnPhase event) {
        appendVisibleEvent("phase", eventSummary(event));
    }

    private synchronized boolean reserveTableTalkSlot() {
        int maxPerGame = parsePositiveInt(System.getenv("ULTRON_TABLE_TALK_MAX_PER_GAME"), DEFAULT_TABLE_TALK_MAX_PER_GAME);
        if (tableTalkCount >= maxPerGame) {
            return false;
        }
        long now = System.currentTimeMillis();
        int cooldownMs = parsePositiveInt(System.getenv("ULTRON_TABLE_TALK_COOLDOWN_MS"), DEFAULT_TABLE_TALK_COOLDOWN_MS);
        if (lastTableTalkMillis > 0 && now - lastTableTalkMillis < cooldownMs) {
            return false;
        }
        tableTalkCount++;
        lastTableTalkMillis = now;
        return true;
    }

    private void appendTimeline(String entry) {
        int maxChars = parsePositiveInt(System.getenv("ULTRON_CONTEXT_MAX_TIMELINE_CHARS"), DEFAULT_TIMELINE_MAX_CHARS);
        timeline.addLast(entry);
        timelineChars += entry.length() + 1;
        while (timelineChars > maxChars && !timeline.isEmpty()) {
            String removed = timeline.removeFirst();
            timelineChars -= removed.length() + 1;
        }
    }

    private String drainRecentEventsForPrompt() {
        if (recentEvents.isEmpty()) {
            lastAssessmentEvents = "No visible events since previous Ultron advisor assessment.\n";
            return lastAssessmentEvents;
        }
        StringBuilder sb = new StringBuilder(recentEventChars + 64);
        for (String event : recentEvents) {
            sb.append(event).append('\n');
        }
        lastAssessmentEvents = sb.toString();
        recentEvents.clear();
        recentEventChars = 0;
        return lastAssessmentEvents;
    }

    private void appendVisibleEvent(String kind, String detail) {
        if (isBlank(detail)) {
            return;
        }
        String entry = "turn=" + game.getPhaseHandler().getTurn()
                + " phase=" + game.getPhaseHandler().getPhase()
                + " kind=" + kind
                + " event=" + oneLine(detail);
        int maxChars = parsePositiveInt(System.getenv("ULTRON_RECENT_EVENTS_MAX_CHARS"), DEFAULT_RECENT_EVENT_MAX_CHARS);
        recentEvents.addLast(entry);
        recentEventChars += entry.length() + 1;
        while (recentEventChars > maxChars && !recentEvents.isEmpty()) {
            String removed = recentEvents.removeFirst();
            recentEventChars -= removed.length() + 1;
        }
    }

    private String zoneChangeSummary(GameEventCardChangeZone event) {
        if (event == null) {
            return "";
        }
        ZoneType from = event.from() == null ? null : event.from().zoneType();
        ZoneType to = event.to() == null ? null : event.to().zoneType();
        boolean showCard = canShow(event.card());
        if (!showCard && !isPublicZone(from) && !isPublicZone(to) && !zoneBelongsToAdvisor(event.from()) && !zoneBelongsToAdvisor(event.to())) {
            return "";
        }
        String card = showCard ? String.valueOf(event.card()) : "hidden card";
        String owner = zoneOwner(event.to());
        if (isBlank(owner)) {
            owner = zoneOwner(event.from());
        }
        return owner + " moved " + card + " " + zoneName(from) + " -> " + zoneName(to);
    }

    private boolean canShow(CardView card) {
        return card != null && card.canBeShownTo(advisor.getView());
    }

    private boolean zoneBelongsToAdvisor(ZoneView zone) {
        return zone != null && zone.player() != null && advisor.getView().equals(zone.player());
    }

    private static boolean isPublicZone(ZoneType zone) {
        return switch (zone) {
        case Battlefield, Graveyard, Command, Stack, Ante, Flashback, Junkyard -> true;
        case Exile -> true;
        default -> false;
        };
    }

    private static String zoneOwner(ZoneView zone) {
        return zone == null || zone.player() == null ? "" : zone.player().getName();
    }

    private static String zoneName(ZoneType zone) {
        return zone == null ? "none" : zone.name();
    }

    private static String eventSummary(Object event) {
        return event == null ? "" : event.toString();
    }

    private void appendChatHistoryIfPresent(StringBuilder sb) {
        if (chatTimeline.isEmpty()) {
            return;
        }
        sb.append("<table_chat_history>\n");
        for (String entry : chatTimeline) {
            sb.append(entry).append('\n');
        }
        sb.append("</table_chat_history>\n\n");
    }

    private void appendReferenceCacheIfPresent(StringBuilder sb) {
        if (referenceCache.isEmpty()) {
            return;
        }
        sb.append("<known_card_reference_cache>\n");
        sb.append("Compact stable card text references already researched this game; current zone/controller state is in current_visible_state. ");
        sb.append("Ask for card_reference again if full oracle/script text is needed now.\n");
        for (String entry : referenceCache.values()) {
            sb.append("- ").append(entry).append('\n');
        }
        sb.append("</known_card_reference_cache>\n\n");
    }

    private void appendChatTimeline(String entry) {
        int maxChars = parsePositiveInt(System.getenv("ULTRON_CHAT_CONTEXT_MAX_CHARS"), DEFAULT_CHAT_MAX_CHARS);
        chatTimeline.addLast(entry);
        chatTimelineChars += entry.length() + 1;
        while (chatTimelineChars > maxChars && !chatTimeline.isEmpty()) {
            String removed = chatTimeline.removeFirst();
            chatTimelineChars -= removed.length() + 1;
        }
    }

    private static String buildInitialContext(Game game, Player advisor, boolean includeFullDecklists) {
        StringBuilder sb = new StringBuilder(32768);
        GameRules rules = game.getRules();
        boolean battlebox = rules.hasAppliedVariant(GameType.Battlebox) || rules.getGameType() == GameType.Battlebox;

        sb.append("contextScope=").append(includeFullDecklists ? "full_first_advisor_request" : "recurring_compact_request").append('\n');
        if (!includeFullDecklists) {
            sb.append("fullDecklistsOmitted=true\n");
            sb.append("Full decklists were sent only in the first advisor request for this game to reduce token usage.\n");
        }
        sb.append("format=").append(rules.getGameType().getEnglishName()).append('\n');
        sb.append("deckFormat=").append(rules.getGameType().getDeckFormat()).append('\n');
        sb.append("appliedVariants=").append(appliedVariants(rules)).append('\n');
        sb.append("players=").append(game.getPlayers().size()).append('\n');
        sb.append("advisingPlayer=").append(advisor.getName()).append('\n');
        sb.append("firstKnownActivePlayer=").append(game.getPhaseHandler().getPlayerTurn().getName()).append('\n');
        sb.append("turnOrder=");
        for (int i = 0; i < game.getRegisteredPlayers().size(); i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            Player player = game.getRegisteredPlayers().get(i);
            sb.append(player.getName());
        }
        sb.append('\n');
        sb.append("poisonCountersToLose=").append(rules.getPoisonCountersToLose()).append('\n');
        sb.append("gamesPerMatch=").append(rules.getGamesPerMatch()).append('\n');
        sb.append("battleboxSharedLibrary=").append(battlebox).append('\n');

        sb.append("\n<players_setup>\n");
        for (Player player : game.getRegisteredPlayers()) {
            appendPlayerSetup(sb, player, advisor, battlebox);
        }
        sb.append("</players_setup>\n");

        if (battlebox) {
            appendBattleboxContext(sb, game, includeFullDecklists);
        } else {
            sb.append("\n<known_deck_context>\n");
            sb.append("Known decklist is limited to the advising player's registered deck.\n");
            appendDeck(sb, "advisingPlayerDeck", advisor.getRegisteredPlayer().getDeck(), includeFullDecklists);
            sb.append("</known_deck_context>\n");
        }
        return sb.toString();
    }

    private static String buildChatContext(Game game, Player advisor) {
        StringBuilder sb = new StringBuilder(2048);
        GameRules rules = game.getRules();
        boolean battlebox = rules.hasAppliedVariant(GameType.Battlebox) || rules.getGameType() == GameType.Battlebox;

        sb.append("contextScope=chat_compact_request\n");
        sb.append("format=").append(rules.getGameType().getEnglishName()).append('\n');
        sb.append("appliedVariants=").append(appliedVariants(rules)).append('\n');
        sb.append("players=").append(game.getPlayers().size()).append('\n');
        sb.append("ultronPlayer=").append(advisor.getName()).append('\n');
        sb.append("battleboxSharedLibrary=").append(battlebox).append('\n');
        sb.append("turnOrder=");
        for (int i = 0; i < game.getRegisteredPlayers().size(); i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            Player player = game.getRegisteredPlayers().get(i);
            sb.append(player.getName());
        }
        sb.append('\n');
        if (battlebox) {
            sb.append("battleboxSummary=shared spell library, shared command-zone land station, no hidden decklist needed for chat.\n");
        } else {
            sb.append("deckVisibility=Ultron may discuss only public information and its own known deck context.\n");
        }
        return sb.toString();
    }

    private static void appendPlayerSetup(StringBuilder sb, Player player, Player advisor, boolean battlebox) {
        RegisteredPlayer registered = player.getRegisteredPlayer();
        sb.append("- name=").append(player.getName());
        sb.append(", startingLife=").append(registered.getStartingLife());
        sb.append(", startingHand=").append(registered.getStartingHand());
        sb.append(", maxHand=").append(registered.getMaxHand());
        sb.append(", team=").append(registered.getTeamNumber());
        if (battlebox) {
            sb.append(", deckRole=battlebox_shared_source_or_participant");
        } else if (player.equals(advisor)) {
            sb.append(", deckName=").append(registered.getDeck() == null ? "" : registered.getDeck().getName());
        } else {
            sb.append(", deckName=unknown");
        }
        sb.append('\n');
    }

    private static void appendBattleboxContext(StringBuilder sb, Game game, boolean includeFullDecklist) {
        if (game.getMatch() == null || game.getMatch().getPlayers().isEmpty()) {
            return;
        }

        Deck sourceDeck = game.getMatch().getPlayers().get(0).getDeck();
        BattleboxConfig config = BattleboxConfig.fromDeck(sourceDeck);
        sb.append("\n<battlebox_context>\n");
        sb.append("This local Battlebox implementation uses one shared spell library for all players.\n");
        sb.append("The shared spell library is generated from the first registered player's Battlebox deck.\n");
        sb.append("The land station is shared in the command zone.\n");
        sb.append("startingLife=").append(config.getStartingLife()).append('\n');
        sb.append("startingHandSize=").append(config.getStartingHandSize()).append('\n');
        sb.append("maxHandSize=").append(config.getMaxHandSize()).append('\n');
        sb.append("playerLibrarySize=").append(config.getPlayerLibrarySize()).append('\n');
        sb.append("seedBasicLands=").append(config.shouldSeedBasicLands()).append('\n');
        appendDeck(sb, "battleboxSourceDeck", sourceDeck, includeFullDecklist);
        sb.append("</battlebox_context>\n");
    }

    private static void appendDeck(StringBuilder sb, String label, Deck deck, boolean includeCards) {
        sb.append('<').append(label).append(">\n");
        if (deck == null) {
            sb.append("missing\n");
            sb.append("</").append(label).append(">\n");
            return;
        }
        sb.append("name=").append(deck.getName()).append('\n');
        if (!deck.getMetadata().isEmpty()) {
            sb.append("metadata=");
            boolean first = true;
            for (Entry<String, String> entry : deck.getMetadata().entrySet()) {
                if (!first) {
                    sb.append("; ");
                }
                sb.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            sb.append('\n');
        }
        for (Entry<DeckSection, CardPool> section : deck) {
            CardPool pool = section.getValue();
            if (pool == null || pool.isEmpty()) {
                continue;
            }
            sb.append('[').append(section.getKey().name()).append("] count=").append(pool.countAll()).append('\n');
            if (includeCards) {
                for (Entry<PaperCard, Integer> card : pool) {
                    sb.append(card.getValue()).append(' ').append(card.getKey().getName()).append('\n');
                }
            }
        }
        sb.append("</").append(label).append(">\n");
    }

    private static String appliedVariants(GameRules rules) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (GameType type : GameType.values()) {
            if (!rules.hasAppliedVariant(type)) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            sb.append(type.name());
            first = false;
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 16)) + " [truncated]";
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
