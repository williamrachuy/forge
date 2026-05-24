package forge.ai.llm;

import forge.ai.AiCardMemory;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.event.GameEventGameOutcome;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.tinylog.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class UltronLearningStore {
    private static final UltronLearningStore INSTANCE = new UltronLearningStore();
    private static final int DEFAULT_SCAN_LINES = 2000;
    private static final int DEFAULT_PROMPT_MAX_ENTRIES = 8;
    private static final int DEFAULT_PROMPT_MAX_CHARS = 3000;
    private static final int DEFAULT_MEMORY_DECISIONS_PER_GAME = 8;
    private static final int DEFAULT_MAX_STATE_CHARS = 200000;
    private static final Pattern WORD_SPLIT = Pattern.compile("[^A-Za-z0-9]+");

    private final Path root;
    private final Path decisionTelemetryFile;
    private final Path outcomeTelemetryFile;
    private final Path memoryJsonlFile;
    private final Path memoryMarkdownFile;

    private UltronLearningStore() {
        root = configuredRoot();
        decisionTelemetryFile = root.resolve("telemetry").resolve("decisions.jsonl");
        outcomeTelemetryFile = root.resolve("telemetry").resolve("outcomes.jsonl");
        memoryJsonlFile = root.resolve("memory").resolve("ultron_memories.jsonl");
        memoryMarkdownFile = root.resolve("memory").resolve("ultron_memory.md");
    }

    static UltronLearningStore get() {
        return INSTANCE;
    }

    String loadRelevantMemories(Game game, Player advisor, List<SpellAbility> candidates, AiCardMemory memory) {
        if (!isEnabled() || !Files.isRegularFile(memoryJsonlFile)) {
            return "";
        }

        List<String> lines = readLastLines(memoryJsonlFile, parsePositiveInt(System.getenv("ULTRON_LEARNING_SCAN_LINES"), DEFAULT_SCAN_LINES));
        if (lines.isEmpty()) {
            return "";
        }

        Set<String> tokens = searchTokens(game, advisor, candidates, memory);
        List<ScoredMemory> scored = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            scored.add(new ScoredMemory(line, score(line, tokens), i));
        }
        scored.sort((left, right) -> {
            int scoreCompare = Integer.compare(right.score(), left.score());
            return scoreCompare != 0 ? scoreCompare : Integer.compare(right.sequence(), left.sequence());
        });

        int maxEntries = parsePositiveInt(System.getenv("ULTRON_LEARNING_MAX_PROMPT_ENTRIES"), DEFAULT_PROMPT_MAX_ENTRIES);
        int maxChars = parsePositiveInt(System.getenv("ULTRON_LEARNING_MAX_PROMPT_CHARS"), DEFAULT_PROMPT_MAX_CHARS);
        StringBuilder sb = new StringBuilder(Math.min(maxChars, 4096));
        int included = 0;
        for (ScoredMemory item : scored) {
            if (included >= maxEntries) {
                break;
            }
            if (item.score() <= 0 && included > 0) {
                break;
            }
            if (!includeFallbackOnlyMemories() && isFallbackOnlyMemory(item.line())) {
                continue;
            }

            String text = extractJsonString(item.line(), "text");
            if (isBlank(text)) {
                text = item.line();
            }
            String entry = "- " + oneLine(text) + '\n';
            if (sb.length() + entry.length() > maxChars) {
                break;
            }
            sb.append(entry);
            included++;
        }

        return sb.toString();
    }

    void recordDecision(String advisorSessionId, Game game, Player advisor, List<SpellAbility> candidates,
            AiCardMemory memory, DecisionRecord record, String visibleState) {
        if (!isEnabled()) {
            return;
        }

        StringBuilder sb = new StringBuilder(8192 + (visibleState == null ? 0 : Math.min(visibleState.length(), 16384)));
        sb.append('{');
        stringField(sb, "schema", "forge-ultron-learning-decision-v1");
        comma(sb);
        stringField(sb, "kind", "decision");
        comma(sb);
        commonFields(sb, advisorSessionId, game, advisor);
        comma(sb);
        numberField(sb, "decisionNumber", record.decisionNumber());
        comma(sb);
        numberField(sb, "turn", record.turn());
        comma(sb);
        stringField(sb, "phase", record.phase());
        comma(sb);
        stringField(sb, "activePlayer", record.activePlayer());
        comma(sb);
        stringField(sb, "choiceType", record.choiceType());
        comma(sb);
        numberField(sb, "choiceIndex", record.choiceIndex());
        comma(sb);
        stringField(sb, "chosenSource", record.source());
        comma(sb);
        stringField(sb, "chosenApi", record.api());
        comma(sb);
        stringField(sb, "rationale", record.rationale());
        comma(sb);
        stringField(sb, "modelFinalContent", truncate(record.modelFinalContent(), 4000));
        comma(sb);
        stringField(sb, "modelReasoningContent", truncate(record.modelReasoningContent(), 8000));
        comma(sb);
        stringField(sb, "recentVisibleEvents", truncate(record.recentVisibleEvents(), 3000));
        comma(sb);
        stringField(sb, "summary", record.summary());
        comma(sb);
        name(sb, "candidates");
        appendCandidates(sb, advisor, candidates, memory);
        if (recordVisibleState()) {
            comma(sb);
            stringField(sb, "visibleState", truncate(visibleState,
                    parsePositiveInt(System.getenv("ULTRON_LEARNING_MAX_STATE_CHARS"), DEFAULT_MAX_STATE_CHARS)));
        }
        sb.append('}');
        appendLine(decisionTelemetryFile, sb.toString());
    }

    void recordOutcome(String advisorSessionId, Game game, Player advisor, GameEventGameOutcome outcome,
            List<DecisionRecord> decisions) {
        if (!isEnabled()) {
            return;
        }

        String result = resultFor(advisor, outcome);
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        stringField(sb, "schema", "forge-ultron-learning-outcome-v1");
        comma(sb);
        stringField(sb, "kind", "outcome");
        comma(sb);
        commonFields(sb, advisorSessionId, game, advisor);
        comma(sb);
        stringField(sb, "result", result);
        comma(sb);
        stringField(sb, "winningPlayer", outcome.winningPlayerName());
        comma(sb);
        numberField(sb, "lastTurn", outcome.lastTurnNumber());
        comma(sb);
        stringField(sb, "matchSummary", outcome.matchSummary());
        comma(sb);
        numberField(sb, "decisionCount", decisions.size());
        comma(sb);
        name(sb, "outcomeStrings");
        appendStrings(sb, outcome.outcomeStrings());
        sb.append('}');
        appendLine(outcomeTelemetryFile, sb.toString());

        writeMemories(advisorSessionId, game, advisor, outcome, result, decisions);
    }

    void recordAgentNote(String advisorSessionId, Game game, Player advisor, String kind, String text) {
        if (!isEnabled() || isBlank(text)) {
            return;
        }
        String noteKind = isBlank(kind) ? "agent_note" : kind.trim();
        String noteText = "format=" + game.getRules().getGameType().name()
                + " advisor=" + advisor.getName()
                + " turn=" + game.getPhaseHandler().getTurn()
                + " phase=" + game.getPhaseHandler().getPhase()
                + " note=" + oneLine(text);
        appendMemory(advisorSessionId, game, advisor, noteKind, "in_game_note", "", "", "",
                truncate(noteText, 2500));
        appendRaw(memoryMarkdownFile, "## " + Instant.now() + " " + advisorSessionId + " " + noteKind + '\n'
                + noteText + "\n\n");
    }

    private void writeMemories(String advisorSessionId, Game game, Player advisor, GameEventGameOutcome outcome,
            String result, List<DecisionRecord> decisions) {
        String format = game.getRules().getGameType().name();
        List<DecisionRecord> selected = selectMemoryDecisions(decisions);
        String keyChoices = summarizeChoices(selected);
        if ("none".equals(keyChoices) && !includeFallbackOnlyMemories()) {
            return;
        }

        String summaryText = buildStrategicMemoryText(format, advisor, outcome, result, decisions, selected, keyChoices);

        appendMemory(advisorSessionId, game, advisor, "game_summary", result, "", "", "", summaryText);
        appendMarkdownSummary(advisorSessionId, summaryText, selected);

        for (DecisionRecord decision : selected) {
            if ("no_advice".equals(decision.choiceType())) {
                continue;
            }
            String action = "pass".equals(decision.choiceType())
                    ? "passed or held priority"
                    : "chose " + decision.source() + actionApiSuffix(decision.api());
            String text = "Learning from a " + format + " game: Ultron " + action
                    + " on turn " + decision.turn() + " during " + decision.phase()
                    + "; the game result was " + result + ". Strategic rationale: "
                    + nullToEmpty(decision.rationale())
                    + recentEventsSentence(decision)
                    + ". Reuse this only when the candidate, public board pressure, and phase context are similar.";
            appendMemory(advisorSessionId, game, advisor, "decision_result", result,
                    decision.source(), decision.api(), decision.phase(), truncate(text, 2500));
        }
    }

    private void appendMemory(String advisorSessionId, Game game, Player advisor, String kind, String result,
            String card, String api, String phase, String text) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        stringField(sb, "schema", "forge-ultron-memory-v1");
        comma(sb);
        stringField(sb, "kind", kind);
        comma(sb);
        commonFields(sb, advisorSessionId, game, advisor);
        comma(sb);
        stringField(sb, "result", result);
        comma(sb);
        stringField(sb, "card", card);
        comma(sb);
        stringField(sb, "api", api);
        comma(sb);
        stringField(sb, "phase", phase);
        comma(sb);
        stringField(sb, "text", text);
        sb.append('}');
        appendLine(memoryJsonlFile, sb.toString());
    }

    private static String buildStrategicMemoryText(String format, Player advisor, GameEventGameOutcome outcome, String result,
            List<DecisionRecord> decisions, String keyChoices) {
        return buildStrategicMemoryText(format, advisor, outcome, result, decisions, selectAdvisedDecisions(decisions), keyChoices);
    }

    private static String buildStrategicMemoryText(String format, Player advisor, GameEventGameOutcome outcome, String result,
            List<DecisionRecord> decisions, List<DecisionRecord> selected, String keyChoices) {
        int noAdviceCount = 0;
        for (DecisionRecord decision : decisions) {
            if ("no_advice".equals(decision.choiceType())) {
                noAdviceCount++;
            }
        }

        StringBuilder sb = new StringBuilder(1600);
        String resultVerb = switch (result) {
        case "win" -> "won";
        case "loss" -> "lost";
        default -> "finished without a known winner";
        };
        sb.append("Game lesson: In ").append(format)
                .append(", Ultron ").append(resultVerb)
                .append(" as ").append(advisor.getName())
                .append(" on turn ").append(outcome.lastTurnNumber()).append(". ");
        if (!"none".equals(keyChoices)) {
            sb.append("Model-backed turning points: ");
            appendDecisionLessons(sb, selected);
            sb.append(' ');
        }
        sb.append("Reliability note: outcome was ").append(result)
                .append(", advisor calls=").append(decisions.size())
                .append(", fallback/no-advice calls=").append(noAdviceCount)
                .append(". Treat this as strategic experience, not a rules source.");
        return oneLine(sb.toString());
    }

    private void appendMarkdownSummary(String advisorSessionId, String summaryText, List<DecisionRecord> decisions) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("## Game Lesson - ").append(Instant.now()).append(" - ").append(advisorSessionId).append('\n');
        sb.append(summaryText).append('\n');
        for (DecisionRecord decision : decisions) {
            if ("no_advice".equals(decision.choiceType())) {
                continue;
            }
            sb.append("- T").append(decision.turn())
                    .append(' ').append(decision.phase())
                    .append(": ").append("pass".equals(decision.choiceType())
                            ? "held/passed"
                            : "chose " + nullToEmpty(decision.source()) + actionApiSuffix(decision.api()))
                    .append(". Why: ").append(oneLine(nullToEmpty(decision.rationale())))
                    .append('\n');
        }
        sb.append('\n');
        appendRaw(memoryMarkdownFile, sb.toString());
    }

    private List<DecisionRecord> selectMemoryDecisions(List<DecisionRecord> decisions) {
        if (decisions.isEmpty()) {
            return Collections.emptyList();
        }
        int max = parsePositiveInt(System.getenv("ULTRON_LEARNING_MEMORY_DECISIONS_PER_GAME"), DEFAULT_MEMORY_DECISIONS_PER_GAME);
        int start = Math.max(0, decisions.size() - max);
        return new ArrayList<>(decisions.subList(start, decisions.size()));
    }

    private static List<DecisionRecord> selectAdvisedDecisions(List<DecisionRecord> decisions) {
        List<DecisionRecord> advised = new ArrayList<>();
        for (DecisionRecord decision : decisions) {
            if (!"no_advice".equals(decision.choiceType())) {
                advised.add(decision);
            }
        }
        return advised;
    }

    private static void appendDecisionLessons(StringBuilder sb, List<DecisionRecord> decisions) {
        int included = 0;
        for (DecisionRecord decision : decisions) {
            if ("no_advice".equals(decision.choiceType())) {
                continue;
            }
            if (included > 0) {
                sb.append(" | ");
            }
            sb.append("T").append(decision.turn()).append('/').append(decision.phase()).append(' ');
            if ("pass".equals(decision.choiceType())) {
                sb.append("held/passed");
            } else {
                sb.append("chose ").append(nullToEmpty(decision.source())).append(actionApiSuffix(decision.api()));
            }
            if (!isBlank(decision.rationale())) {
                sb.append(" because ").append(oneLine(decision.rationale()));
            }
            if (!isBlank(decision.recentVisibleEvents()) && !decision.recentVisibleEvents().startsWith("No visible events")) {
                sb.append(" after ").append(oneLine(truncate(decision.recentVisibleEvents(), 280)));
            }
            included++;
            if (included >= 4) {
                break;
            }
        }
    }

    private static String summarizeChoices(List<DecisionRecord> decisions) {
        if (decisions.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (DecisionRecord decision : decisions) {
            if ("no_advice".equals(decision.choiceType())) {
                continue;
            }
            if (!first) {
                sb.append(" | ");
            }
            sb.append("T").append(decision.turn())
                    .append('/').append(decision.phase())
                    .append(':').append(decision.choiceType());
            if (!isBlank(decision.source())) {
                sb.append(' ').append(decision.source());
            }
            if (!isBlank(decision.api())) {
                sb.append('/').append(decision.api());
            }
            first = false;
        }
        return first ? "none" : sb.toString();
    }

    private static String resultFor(Player advisor, GameEventGameOutcome outcome) {
        if (isBlank(outcome.winningPlayerName())) {
            return "draw_or_unknown";
        }
        return outcome.winningPlayerName().equals(advisor.getName()) ? "win" : "loss";
    }

    private static void appendCandidates(StringBuilder sb, Player advisor, List<SpellAbility> candidates, AiCardMemory memory) {
        sb.append('[');
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SpellAbility candidate = candidates.get(i);
            sb.append('{');
            numberField(sb, "index", i);
            comma(sb);
            stringField(sb, "source", UltronGameStateSerializer.sourceName(candidate, advisor, memory));
            comma(sb);
            stringField(sb, "api", UltronGameStateSerializer.apiName(candidate));
            comma(sb);
            stringField(sb, "text", candidate.toString());
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendStrings(StringBuilder sb, List<String> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(JsonSupport.quote(values.get(i)));
        }
        sb.append(']');
    }

    private static void commonFields(StringBuilder sb, String advisorSessionId, Game game, Player advisor) {
        stringField(sb, "timestamp", Instant.now().toString());
        comma(sb);
        stringField(sb, "advisorSessionId", advisorSessionId);
        comma(sb);
        stringField(sb, "advisor", advisor.getName());
        comma(sb);
        stringField(sb, "format", game.getRules().getGameType().name());
        comma(sb);
        stringField(sb, "appliedVariants", appliedVariants(game.getRules()));
    }

    private Set<String> searchTokens(Game game, Player advisor, List<SpellAbility> candidates, AiCardMemory memory) {
        Set<String> tokens = new LinkedHashSet<>();
        addToken(tokens, game.getRules().getGameType().name());
        for (SpellAbility candidate : candidates) {
            addToken(tokens, UltronGameStateSerializer.sourceName(candidate, advisor, memory));
            addToken(tokens, UltronGameStateSerializer.apiName(candidate));
        }
        for (Player player : game.getPlayers()) {
            addVisibleZoneTokens(tokens, player.getCardsIn(ZoneType.Battlefield), ZoneType.Battlefield, advisor, memory);
            addVisibleZoneTokens(tokens, player.getCardsIn(ZoneType.Command), ZoneType.Command, advisor, memory);
            addVisibleZoneTokens(tokens, player.getCardsIn(ZoneType.Graveyard), ZoneType.Graveyard, advisor, memory);
        }
        return tokens;
    }

    private static void addVisibleZoneTokens(Set<String> tokens, Iterable<Card> cards, ZoneType zone, Player advisor, AiCardMemory memory) {
        for (Card card : cards) {
            if (UltronGameStateSerializer.canShowCardName(card, zone, advisor, memory)) {
                addToken(tokens, card.getName());
            }
        }
    }

    private static void addToken(Set<String> tokens, String value) {
        if (isBlank(value) || "Hidden card".equals(value)) {
            return;
        }
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        if (normalized.length() >= 3) {
            tokens.add(normalized);
        }
        for (String part : WORD_SPLIT.split(normalized)) {
            if (part.length() >= 4) {
                tokens.add(part);
            }
        }
    }

    private static int score(String line, Set<String> tokens) {
        String lower = line.toLowerCase(Locale.ROOT);
        int result = 0;
        for (String token : tokens) {
            if (!lower.contains(token)) {
                continue;
            }
            result += token.indexOf(' ') >= 0 ? 6 : 2;
        }
        return result;
    }

    private static List<String> readLastLines(Path path, int maxLines) {
        ArrayDeque<String> lines = new ArrayDeque<>(maxLines);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() == maxLines) {
                    lines.removeFirst();
                }
                lines.addLast(line);
            }
        } catch (IOException ex) {
            Logger.debug(ex, "Unable to read Ultron learning memories");
        }
        return new ArrayList<>(lines);
    }

    private static String extractJsonString(String json, String fieldName) {
        String needle = JsonSupport.quote(fieldName);
        int fieldIndex = json.indexOf(needle);
        if (fieldIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', fieldIndex + needle.length());
        if (colonIndex < 0) {
            return null;
        }
        int valueIndex = colonIndex + 1;
        while (valueIndex < json.length() && Character.isWhitespace(json.charAt(valueIndex))) {
            valueIndex++;
        }
        if (valueIndex >= json.length() || json.charAt(valueIndex) != '"') {
            return null;
        }
        return JsonSupport.unquoteAt(json, valueIndex);
    }

    private static void appendLine(Path path, String line) {
        appendRaw(path, line + '\n');
    }

    private static void appendRaw(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            Logger.debug(ex, "Unable to write Ultron learning data");
        }
    }

    private static Path configuredRoot() {
        String configured = System.getenv("ULTRON_LEARNING_DIR");
        if (!isBlank(configured)) {
            return Paths.get(configured).toAbsolutePath();
        }
        String home = System.getProperty("user.home");
        if (!isBlank(home)) {
            return Paths.get(home, ".forge", "ultron-learning");
        }
        return Paths.get("ultron-learning").toAbsolutePath();
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

    private static boolean isEnabled() {
        String value = System.getenv("ULTRON_LEARNING_ENABLED");
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "0", "false", "no", "off" -> false;
        default -> true;
        };
    }

    private static boolean includeFallbackOnlyMemories() {
        String value = System.getenv("ULTRON_LEARNING_INCLUDE_FALLBACK_ONLY");
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "1", "true", "yes", "on" -> true;
        default -> false;
        };
    }

    private static boolean isFallbackOnlyMemory(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("no model-backed choices were recorded")
                || lower.contains("keychoices=none")
                || lower.contains("\"keychoices\":\"none\"");
    }

    private static boolean recordVisibleState() {
        String value = System.getenv("ULTRON_LEARNING_RECORD_STATE");
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "0", "false", "no", "off" -> false;
        default -> true;
        };
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static void stringField(StringBuilder sb, String name, String value) {
        name(sb, name);
        sb.append(JsonSupport.quote(value));
    }

    private static void numberField(StringBuilder sb, String name, int value) {
        name(sb, name);
        sb.append(value);
    }

    private static void name(StringBuilder sb, String name) {
        sb.append(JsonSupport.quote(name)).append(':');
    }

    private static void comma(StringBuilder sb) {
        sb.append(',');
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 24)) + " ... [truncated]";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String recentEventsSentence(DecisionRecord decision) {
        if (decision == null || isBlank(decision.recentVisibleEvents())
                || decision.recentVisibleEvents().startsWith("No visible events")) {
            return "";
        }
        return ". Visible lead-up: " + oneLine(truncate(decision.recentVisibleEvents(), 500));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String actionApiSuffix(String api) {
        return isBlank(api) ? "" : "/" + api;
    }

    record DecisionRecord(int decisionNumber, int turn, String phase, String activePlayer, String choiceType,
            int choiceIndex, String source, String api, String rationale, String modelFinalContent,
            String modelReasoningContent, String recentVisibleEvents, String summary) {
    }

    private record ScoredMemory(String line, int score, int sequence) {
    }
}
