package forge.ai.llm;

import forge.ai.AiCardMemory;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UltronAdvisor {
    private static final String ULTRON_PROFILE = "Ultron";
    private static final int DEFAULT_CANDIDATE_LIMIT = 16;
    private static final int DEFAULT_REQUEST_WAIT_MS = 45000;
    private static final int DEFAULT_CHAT_WAIT_MS = 15000;
    private static final int DEFAULT_TABLE_TALK_WAIT_MS = 8000;
    private static final int DEFAULT_CHAT_REPLY_MAX_CHARS = 600;
    private static final int DEFAULT_RESEARCH_MAX_ROUNDS = 2;
    private static final int DEFAULT_STRATEGIC_PLAN_WAIT_MS = 300000;
    private static final int DEFAULT_STRATEGIC_PLAN_MAX_TOKENS = 32768;
    private static final Pattern CHOICE_PATTERN = Pattern.compile("\"choice\"\\s*:\\s*(-?\\d+)");
    private static final Pattern RATIONALE_PATTERN = Pattern.compile("\"rationale\"\\s*:\\s*\"");
    private static final UltronAdvisor INSTANCE = new UltronAdvisor();
    private static final String SYSTEM_PROMPT = UltronPrompts.systemPrompt();
    private static final String CHAT_SYSTEM_PROMPT = UltronPrompts.chatSystemPrompt();

    private DeepSeekClient client;
    private DeepSeekClient strategicPlanClient;
    private DeepSeekClient chatClient;
    private DeepSeekClient tableTalkClient;
    private boolean clientChecked;
    private boolean strategicPlanClientChecked;
    private boolean chatClientChecked;
    private boolean tableTalkClientChecked;
    private final Map<Game, Map<Player, UltronGameContext>> contexts = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Game, Map<Player, UltronStrategicPlan>> strategicPlans = Collections.synchronizedMap(new WeakHashMap<>());
    private final List<Consumer<String>> tableTalkListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<StatusEvent>> statusListeners = new CopyOnWriteArrayList<>();

    private UltronAdvisor() {
    }

    public static UltronAdvisor get() {
        return INSTANCE;
    }

    public boolean isEnabledFor(Player player) {
        return isUltronProfile(player) && isEnvEnabled() && getClient() != null;
    }

    // -----------------------------------------------------------------------
    // New profile/enablement methods for runtime vs. LLM separation (Phase 2)
    // -----------------------------------------------------------------------

    /** True if this player's profile is Ultron (does NOT require an API key). */
    public boolean isUltronRuntimeProfile(Player player) {
        return isUltronProfile(player);
    }

    /** True if LLM advisor is explicitly enabled AND a DeepSeek client exists. */
    public boolean isLlmAdvisorEnabledFor(Player player) {
        return isUltronProfile(player) && UltronConfig.enabledForLlmAdvisor() && getClient() != null;
    }

    /** True if LLM strategic planning is explicitly enabled AND a strategic-plan client exists. */
    public boolean isLlmStrategicPlanEnabledFor(Player player) {
        return isUltronProfile(player) && UltronConfig.enabledForStrategicPlanLlm()
                && getStrategicPlanClient() != null;
    }

    /** True if chat is enabled AND a chat client exists. */
    public boolean isChatEnabledFor(Player player) {
        return isUltronProfile(player) && UltronConfig.enabledForChat() && getChatClient() != null;
    }

    /** True if table-talk is enabled AND a table-talk client exists. */
    public boolean isTableTalkEnabledFor(Player player) {
        return isUltronProfile(player) && UltronConfig.enabledForTableTalk()
                && getTableTalkClient() != null;
    }

    public boolean hasUltronPlayer(Game game) {
        return findUltronPlayer(game).isPresent();
    }

    public void addTableTalkListener(Consumer<String> listener) {
        if (listener != null) {
            tableTalkListeners.add(listener);
        }
    }

    public void removeTableTalkListener(Consumer<String> listener) {
        tableTalkListeners.remove(listener);
    }

    public void addStatusListener(Consumer<StatusEvent> listener) {
        if (listener != null) {
            statusListeners.add(listener);
        }
    }

    public void removeStatusListener(Consumer<StatusEvent> listener) {
        statusListeners.remove(listener);
    }

    public void setSpeechPreferenceEnabled(boolean enabled) {
        UltronSpeech.get().setPreferenceEnabled(enabled);
    }

    public int getCandidateLimit() {
        return parsePositiveInt(System.getenv("ULTRON_DEEPSEEK_CANDIDATES"), DEFAULT_CANDIDATE_LIMIT);
    }

    public Decision chooseSpellAbility(Game game, Player player, List<SpellAbility> candidates, AiCardMemory memory) {
        // Phase 17: guard — do not call LLM unless explicitly enabled
        if (!UltronConfig.enabledForLlmAdvisor()) {
            return Decision.noAdvice();
        }
        DeepSeekClient activeClient = getClient();
        if (activeClient == null || candidates == null || candidates.isEmpty()) {
            return Decision.noAdvice();
        }

        int candidateLimit = Math.min(candidates.size(), getCandidateLimit());
        List<SpellAbility> limitedCandidates = candidates.subList(0, candidateLimit);
        String visibleState = UltronGameStateSerializer.serialize(game, player, limitedCandidates, memory);
        UltronGameContext context = getContext(game, player);
        String longTermMemory = UltronLearningStore.get().loadRelevantMemories(game, player, limitedCandidates, memory);
        String basePrompt = context.buildPrompt(visibleState, longTermMemory);
        String prompt = basePrompt;
        StringBuilder researchTranscript = new StringBuilder(8192);
        List<ResearchTrace> researchTrace = new ArrayList<>();
        publishStatus(new StatusEvent(game, player, "advisor", true, "Ultron advisor thinking..."));
        DeepSeekClient.CompletionResult response = null;
        Decision decision = Decision.noAdvice();
        try {
            response = activeClient.completeJson(SYSTEM_PROMPT, prompt, getRequestWaitMs(game));
            decision = response.success() ? parseDecision(response.content(), limitedCandidates) : Decision.noAdvice();

            int maxResearchRounds = getResearchMaxRounds();
            for (int round = 1; response.success() && !decision.hasAdvice() && round <= maxResearchRounds; round++) {
                List<UltronResearchTools.Request> requests = UltronResearchTools.parseRequests(response.content());
                if (requests.isEmpty()) {
                    break;
                }

                UltronResearchTools.Result researchResult = UltronResearchTools.execute(game, player, context,
                        limitedCandidates, memory, requests);
                context.rememberCardReferences(researchResult.rememberedReferences());
                appendResearchTranscript(researchTranscript, round, response.content(), researchResult.json());
                researchTrace.add(new ResearchTrace(round, prompt, response, researchResult.json()));

                prompt = context.buildResearchPrompt(basePrompt, researchTranscript.toString(), round, maxResearchRounds);
                response = activeClient.completeJson(SYSTEM_PROMPT, prompt, getRequestWaitMs(game));
                decision = response.success() ? parseDecision(response.content(), limitedCandidates) : Decision.noAdvice();
            }

            context.recordDecision(game, player, limitedCandidates, memory, decision, visibleState, response);
            UltronTraceStore.get().record(game, player, limitedCandidates, memory,
                    activeClient.describeForTrace(), prompt, researchTrace, response, decision);
            if (decision.hasAdvice() && isDebugEnabled()) {
                Logger.info("Ultron advice: {}", summarizeAdvice(response.content()));
            }
            return decision;
        } finally {
            publishStatus(new StatusEvent(game, player, "advisor", false, ""));
        }
    }

    public Decision chooseFromStrategicPlan(Game game, Player player, List<SpellAbility> candidates, AiCardMemory memory,
            UltronStrategicPlan.GameState gameState) {
        if (!isLlmStrategicPlanEnabledFor(player)) {
            return Decision.noAdvice();
        }
        DeepSeekClient activeClient = getStrategicPlanClient();
        if (activeClient == null || candidates == null || candidates.isEmpty()) {
            return Decision.noAdvice();
        }

        UltronGameContext context = getContext(game, player);
        if (context.shouldBuildStrategicPlan()) {
            buildStrategicPlan(activeClient, game, player, candidates, memory, context);
        }

        UltronStrategicPlan plan = getStrategicPlan(game, player);
        return plan == null ? Decision.noAdvice() : plan.choose(gameState, candidates, player, memory);
    }

    public void analyzeOpeningHand(Game game, Player player, AiCardMemory memory) {
        DeepSeekClient activeClient = getStrategicPlanClient();
        if (activeClient == null || game == null || player == null || !isLlmStrategicPlanEnabledFor(player)) {
            return;
        }
        UltronGameContext context = getContext(game, player);
        buildStrategicPlan(activeClient, game, player, Collections.emptyList(), memory, context);
    }

    public void filterPlannedAttackers(Game game, Player player, Combat combat) {
        if (game == null || player == null || combat == null || !isLlmStrategicPlanEnabledFor(player)) {
            return;
        }
        UltronStrategicPlan plan = getStrategicPlan(game, player);
        if (plan != null) {
            plan.filterAttackers(combat);
        }
    }

    /**
     * Return the hold-interaction card names from the most recent strategic plan for
     * this player. Empty list if no plan exists or strategic plan LLM is disabled.
     */
    public List<String> getLastPlanHoldInteraction(Game game, Player player) {
        UltronStrategicPlan plan = getStrategicPlan(game, player);
        return plan != null ? plan.getHoldInteraction() : Collections.emptyList();
    }

    private void buildStrategicPlan(DeepSeekClient activeClient, Game game, Player player, List<SpellAbility> candidates,
            AiCardMemory memory, UltronGameContext context) {
        List<SpellAbility> limitedCandidates = candidates == null ? Collections.emptyList()
                : candidates.subList(0, Math.min(candidates.size(), getCandidateLimit()));
        String visibleState = UltronGameStateSerializer.serialize(game, player, limitedCandidates, memory);
        String longTermMemory = UltronLearningStore.get().loadRelevantMemories(game, player, limitedCandidates, memory);
        String prompt = context.buildStrategicPlanPrompt(visibleState, longTermMemory);
        publishStatus(new StatusEvent(game, player, "strategic_plan", true, "Ultron planning turn..."));
        DeepSeekClient.CompletionResult response = null;
        UltronStrategicPlan plan = UltronStrategicPlan.empty();
        try {
            response = activeClient.completeJson(SYSTEM_PROMPT, prompt, getStrategicPlanWaitMs(game));
            if (response.success()) {
                plan = UltronStrategicPlan.parse(response.content());
            }
            putStrategicPlan(game, player, plan);
            context.recordStrategicPlan(plan, response == null || response.content() == null ? null : summarizeAdvice(response.content()));
            UltronTraceStore.get().recordStrategicPlan(game, player, limitedCandidates, memory,
                    activeClient.describeForTrace(), prompt, response, plan);
            if (isDebugEnabled()) {
                Logger.info("Ultron strategic plan: {}", response == null ? "no response" : response.content());
            }
        } finally {
            publishStatus(new StatusEvent(game, player, "strategic_plan", false, ""));
        }
    }

    public ChatResponse chat(Game game, Player speaker, String message) {
        if (game == null) {
            return ChatResponse.failure("No active game is available.");
        }
        if (message == null || message.isBlank()) {
            return ChatResponse.failure("Empty chat message.");
        }

        Optional<Player> ultronPlayer = findUltronPlayer(game);
        if (ultronPlayer.isEmpty()) {
            return ChatResponse.failure("No Ultron player is present in this game.");
        }

        Player ultron = ultronPlayer.get();
        UltronGameContext context = getContext(game, ultron);
        String speakerName = speaker == null ? "Player" : speaker.getName();
        context.recordChat(speakerName, message);

        DeepSeekClient activeClient = getChatClient();
        if (activeClient == null || !isEnvEnabled()) {
            return ChatResponse.failure("Ultron DeepSeek client is not configured.");
        }

        String publicState = UltronGameStateSerializer.serializePublic(game, ultron, speaker);
        String prompt = context.buildChatPrompt(publicState, speakerName, message);
        publishStatus(new StatusEvent(game, ultron, "chat", true, "Ultron chat thinking..."));
        DeepSeekClient.CompletionResult response;
        String reply;
        try {
            response = activeClient.completeText(CHAT_SYSTEM_PROMPT, prompt, getChatWaitMs());
            reply = chatReplyFromResponse(response, true);
        } finally {
            publishStatus(new StatusEvent(game, ultron, "chat", false, ""));
        }

        UltronTraceStore.get().recordChat(game, ultron, speaker, "direct_chat",
                activeClient.describeForTrace(), prompt, response, reply);

        if (!reply.isBlank()) {
            context.recordChat(ultron.getName(), reply);
            UltronSpeech.get().speak(reply);
            return ChatResponse.success(reply);
        }
        return ChatResponse.failure(response.error() == null ? "Ultron did not return a chat reply." : response.error());
    }

    void commentOnPublicEvent(Game game, Player advisor, UltronGameContext context, String eventSummary) {
        DeepSeekClient activeClient = getTableTalkClient();
        if (activeClient == null || !isEnvEnabled() || !isUltronProfile(advisor)) {
            return;
        }

        String publicState = UltronGameStateSerializer.serializePublic(game, advisor, null);
        String prompt = context.buildTableTalkPrompt(publicState, eventSummary);
        Thread thread = new Thread(() -> {
            publishStatus(new StatusEvent(game, advisor, "table_talk", true, "Ultron table talk thinking..."));
            try {
                DeepSeekClient.CompletionResult response = activeClient.completeOptionalText(CHAT_SYSTEM_PROMPT,
                        prompt, getTableTalkWaitMs());
                String reply = chatReplyFromResponse(response, false);
                UltronTraceStore.get().recordChat(game, advisor, null, "proactive_table_talk",
                        activeClient.describeForTrace(), prompt, response, reply);
                if (!reply.isBlank()) {
                    context.recordChat(advisor.getName(), reply);
                    publishTableTalk(reply);
                    UltronSpeech.get().speak(reply);
                }
            } finally {
                publishStatus(new StatusEvent(game, advisor, "table_talk", false, ""));
            }
        }, "Ultron table talk");
        thread.setDaemon(true);
        thread.start();
    }

    private void publishStatus(StatusEvent event) {
        for (Consumer<StatusEvent> listener : statusListeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ex) {
                Logger.debug(ex, "Unable to publish Ultron status");
            }
        }
    }

    private void publishTableTalk(String reply) {
        for (Consumer<String> listener : tableTalkListeners) {
            try {
                listener.accept(reply);
            } catch (RuntimeException ex) {
                Logger.debug(ex, "Unable to publish Ultron table talk");
            }
        }
    }

    private static Decision parseDecision(String responseJson, List<SpellAbility> candidates) {
        Matcher matcher = CHOICE_PATTERN.matcher(responseJson);
        if (!matcher.find()) {
            Logger.debug("Ultron DeepSeek response did not contain a choice field");
            return Decision.noAdvice();
        }

        int choice;
        try {
            choice = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return Decision.noAdvice();
        }

        if (choice == -1) {
            return Decision.pass(extractRationale(responseJson));
        }
        if (choice < 0 || choice >= candidates.size()) {
            Logger.debug("Ultron DeepSeek response selected out-of-range choice {}", choice);
            return Decision.noAdvice();
        }
        return Decision.choose(candidates.get(choice), choice, extractRationale(responseJson));
    }

    private static String summarizeAdvice(String responseJson) {
        String rationale = extractRationale(responseJson);
        if (rationale == null || rationale.isBlank()) {
            return responseJson;
        }
        Matcher matcher = CHOICE_PATTERN.matcher(responseJson);
        String choice = matcher.find() ? matcher.group(1) : "?";
        return "choice=" + choice + " rationale=" + rationale;
    }

    private static String extractRationale(String responseJson) {
        Matcher matcher = RATIONALE_PATTERN.matcher(responseJson);
        if (!matcher.find()) {
            return null;
        }
        return JsonSupport.unquoteAt(responseJson, matcher.end() - 1);
    }

    private static boolean isUltronProfile(Player player) {
        if (player == null) {
            return false;
        }
        if (!(player.getLobbyPlayer() instanceof LobbyPlayerAi lobbyPlayerAi)) {
            return false;
        }
        return ULTRON_PROFILE.equalsIgnoreCase(lobbyPlayerAi.getAiProfile());
    }

    private static Optional<Player> findUltronPlayer(Game game) {
        if (game == null) {
            return Optional.empty();
        }
        for (Player player : game.getRegisteredPlayers()) {
            if (isUltronProfile(player)) {
                return Optional.of(player);
            }
        }
        for (Player player : game.getPlayers()) {
            if (isUltronProfile(player)) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    private synchronized DeepSeekClient getClient() {
        if (!clientChecked) {
            client = DeepSeekClient.fromEnvironment().orElse(null);
            clientChecked = true;
        }
        return client;
    }

    private synchronized DeepSeekClient getStrategicPlanClient() {
        if (!strategicPlanClientChecked) {
            strategicPlanClient = DeepSeekClient.fromEnvironmentWithPrefix("ULTRON_STRATEGIC_PLAN",
                    "deepseek-v4-pro", "enabled", "high", DEFAULT_STRATEGIC_PLAN_WAIT_MS,
                    DEFAULT_STRATEGIC_PLAN_MAX_TOKENS).orElse(null);
            strategicPlanClientChecked = true;
        }
        return strategicPlanClient;
    }

    private synchronized DeepSeekClient getChatClient() {
        if (!chatClientChecked) {
            chatClient = DeepSeekClient.fromEnvironmentWithPrefix("ULTRON_CHAT",
                    "deepseek-v4-flash", "disabled", "low", 30000, 512).orElse(null);
            chatClientChecked = true;
        }
        return chatClient;
    }

    private synchronized DeepSeekClient getTableTalkClient() {
        if (!tableTalkClientChecked) {
            tableTalkClient = DeepSeekClient.fromEnvironmentWithPrefix("ULTRON_TABLE_TALK",
                    "deepseek-v4-flash", "disabled", "low", 10000, 128).orElse(null);
            tableTalkClientChecked = true;
        }
        return tableTalkClient;
    }

    public synchronized void reloadClient() {
        client = DeepSeekClient.fromEnvironment().orElse(null);
        strategicPlanClient = DeepSeekClient.fromEnvironmentWithPrefix("ULTRON_STRATEGIC_PLAN",
                "deepseek-v4-pro", "enabled", "high", DEFAULT_STRATEGIC_PLAN_WAIT_MS,
                DEFAULT_STRATEGIC_PLAN_MAX_TOKENS).orElse(null);
        chatClient = DeepSeekClient.fromEnvironmentWithPrefix("ULTRON_CHAT",
                "deepseek-v4-flash", "disabled", "low", 30000, 512).orElse(null);
        tableTalkClient = DeepSeekClient.fromEnvironmentWithPrefix("ULTRON_TABLE_TALK",
                "deepseek-v4-flash", "disabled", "low", 10000, 128).orElse(null);
        clientChecked = true;
        strategicPlanClientChecked = true;
        chatClientChecked = true;
        tableTalkClientChecked = true;
    }

    private UltronGameContext getContext(Game game, Player player) {
        synchronized (contexts) {
            Map<Player, UltronGameContext> gameContexts = contexts.computeIfAbsent(game, ignored -> new HashMap<>());
            UltronGameContext context = gameContexts.get(player);
            if (context == null) {
                context = new UltronGameContext(game, player);
                game.subscribeToEvents(context);
                gameContexts.put(player, context);
            }
            return context;
        }
    }

    private UltronStrategicPlan getStrategicPlan(Game game, Player player) {
        synchronized (strategicPlans) {
            Map<Player, UltronStrategicPlan> gamePlans = strategicPlans.get(game);
            return gamePlans == null ? null : gamePlans.get(player);
        }
    }

    private void putStrategicPlan(Game game, Player player, UltronStrategicPlan plan) {
        synchronized (strategicPlans) {
            strategicPlans.computeIfAbsent(game, ignored -> new HashMap<>()).put(player, plan);
        }
    }

    private static int getRequestWaitMs(Game game) {
        String override = System.getenv("ULTRON_DEEPSEEK_WAIT_MS");
        if (override != null && !override.isBlank()) {
            return parsePositiveInt(override, DEFAULT_REQUEST_WAIT_MS);
        }
        return Math.max(DEFAULT_REQUEST_WAIT_MS, (game.getAITimeout() * 1000) - 500);
    }

    private static int getStrategicPlanWaitMs(Game game) {
        String override = System.getenv("ULTRON_STRATEGIC_PLAN_WAIT_MS");
        if (override != null && !override.isBlank()) {
            return parsePositiveInt(override, DEFAULT_STRATEGIC_PLAN_WAIT_MS);
        }
        return DEFAULT_STRATEGIC_PLAN_WAIT_MS;
    }

    private static int getChatWaitMs() {
        return parsePositiveInt(System.getenv("ULTRON_CHAT_WAIT_MS"), DEFAULT_CHAT_WAIT_MS);
    }

    private static int getTableTalkWaitMs() {
        return parsePositiveInt(System.getenv("ULTRON_TABLE_TALK_WAIT_MS"), DEFAULT_TABLE_TALK_WAIT_MS);
    }

    private static int getResearchMaxRounds() {
        return parseNonNegativeInt(System.getenv("ULTRON_RESEARCH_MAX_ROUNDS"), DEFAULT_RESEARCH_MAX_ROUNDS);
    }

    private static void appendResearchTranscript(StringBuilder sb, int round, String assistantRequest, String toolResult) {
        sb.append("<research_round index=\"").append(round).append("\">\n");
        sb.append("<assistant_tool_request>\n");
        sb.append(assistantRequest == null ? "" : assistantRequest);
        if (assistantRequest != null && !assistantRequest.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("</assistant_tool_request>\n");
        sb.append("<forge_tool_result>\n");
        sb.append(toolResult == null ? "" : toolResult);
        if (toolResult != null && !toolResult.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("</forge_tool_result>\n");
        sb.append("</research_round>\n");
    }

    static boolean isTableTalkEnabled() {
        String value = System.getenv("ULTRON_TABLE_TALK_ENABLED");
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "0", "false", "no", "off" -> false;
        default -> true;
        };
    }

    private static boolean isEnvEnabled() {
        String value = System.getenv("ULTRON_LLM_ENABLED");
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.trim().toLowerCase()) {
        case "0", "false", "no", "off" -> false;
        default -> true;
        };
    }

    private static boolean isDebugEnabled() {
        String value = System.getenv("ULTRON_DEEPSEEK_DEBUG");
        return value != null && switch (value.trim().toLowerCase()) {
        case "1", "true", "yes", "on" -> true;
        default -> false;
        };
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

    private static int parseNonNegativeInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return Math.max(0, fallback);
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 ? parsed : Math.max(0, fallback);
        } catch (NumberFormatException ex) {
            return Math.max(0, fallback);
        }
    }

    private static String sanitizeReply(String content) {
        if (content == null) {
            return "";
        }
        String reply = content.trim();
        if (reply.startsWith("```")) {
            int firstLine = reply.indexOf('\n');
            int lastFence = reply.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                reply = reply.substring(firstLine + 1, lastFence).trim();
            }
        }
        if (reply.startsWith("\"") && reply.endsWith("\"") && reply.length() > 1 && !reply.substring(1, reply.length() - 1).contains("\"")) {
            reply = reply.substring(1, reply.length() - 1);
        }
        reply = reply.replace('\r', ' ').replace('\n', ' ').trim();
        int maxChars = parsePositiveInt(System.getenv("ULTRON_CHAT_REPLY_MAX_CHARS"), DEFAULT_CHAT_REPLY_MAX_CHARS);
        if (reply.length() > maxChars) {
            reply = reply.substring(0, Math.max(0, maxChars - 1)).trim();
        }
        return reply;
    }

    private static String chatReplyFromResponse(DeepSeekClient.CompletionResult response, boolean allowRecoveryLine) {
        if (response == null) {
            return "";
        }
        if (response.success()) {
            return sanitizeReply(response.content());
        }
        if (!"Missing assistant message content".equals(response.error())) {
            return "";
        }
        String fallback = safeReasoningOnlyReply(response.reasoningContent());
        if (!fallback.isBlank()) {
            return fallback;
        }
        return allowRecoveryLine ? "I had the thought. The machine swallowed the line. Ask again." : "";
    }

    private static String safeReasoningOnlyReply(String reasoningContent) {
        String reply = sanitizeReply(reasoningContent);
        if (reply.isBlank() || reply.length() > 300 || reply.contains("\n")) {
            return "";
        }
        String lower = reply.toLowerCase(Locale.ROOT);
        if (lower.contains("we need") || lower.contains("i need") || lower.contains("looking at")
                || lower.contains("the player") || lower.contains("public state") || lower.contains("chat history")
                || lower.contains("i should") || lower.contains("i'll ")) {
            return "";
        }
        return reply;
    }

    public record ChatResponse(boolean success, String message, String error) {
        static ChatResponse success(String message) {
            return new ChatResponse(true, message, null);
        }

        static ChatResponse failure(String error) {
            return new ChatResponse(false, null, error);
        }
    }

    public record StatusEvent(Game game, Player advisor, String mode, boolean active, String message) {
    }

    public static final class Decision {
        private final boolean hasAdvice;
        private final SpellAbility spellAbility;
        private final int choiceIndex;
        private final String rationale;

        private Decision(boolean hasAdvice, SpellAbility spellAbility, int choiceIndex, String rationale) {
            this.hasAdvice = hasAdvice;
            this.spellAbility = spellAbility;
            this.choiceIndex = choiceIndex;
            this.rationale = rationale;
        }

        static Decision noAdvice() {
            return new Decision(false, null, Integer.MIN_VALUE, null);
        }

        static Decision pass(String rationale) {
            return new Decision(true, null, -1, rationale);
        }

        static Decision choose(SpellAbility spellAbility, int choiceIndex, String rationale) {
            return new Decision(true, spellAbility, choiceIndex, rationale);
        }

        public boolean hasAdvice() {
            return hasAdvice;
        }

        public SpellAbility getSpellAbility() {
            return spellAbility;
        }

        public int getChoiceIndex() {
            return choiceIndex;
        }

        public String getRationale() {
            return rationale;
        }
    }

    record ResearchTrace(int round, String prompt, DeepSeekClient.CompletionResult completion, String toolResult) {
    }
}
