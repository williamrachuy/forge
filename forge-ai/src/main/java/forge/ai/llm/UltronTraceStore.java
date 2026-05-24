package forge.ai.llm;

import forge.ai.AiCardMemory;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.tinylog.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

final class UltronTraceStore {
    private static final UltronTraceStore INSTANCE = new UltronTraceStore();
    private static final int DEFAULT_MAX_ENTRY_CHARS = 300000;

    private final Path traceDir;
    private final Path latestFile;
    private final Path latestChatFile;
    private final Path transcriptFile;
    private final Path chatTranscriptFile;

    private UltronTraceStore() {
        traceDir = configuredTraceDir();
        latestFile = traceDir.resolve("latest.md");
        latestChatFile = traceDir.resolve("chat-latest.md");
        transcriptFile = traceDir.resolve("transcript.md");
        chatTranscriptFile = traceDir.resolve("chat-transcript.md");
    }

    static UltronTraceStore get() {
        return INSTANCE;
    }

    void record(Game game, Player advisor, List<SpellAbility> candidates, AiCardMemory memory,
            String clientDescription, String prompt, List<UltronAdvisor.ResearchTrace> researchTrace,
            DeepSeekClient.CompletionResult completion, UltronAdvisor.Decision decision) {
        if (!isEnabled()) {
            return;
        }

        String entry = buildEntry(game, advisor, candidates, memory, clientDescription, prompt,
                researchTrace, completion, decision);
        writeLatest(entry);
        appendTranscript(entry);
    }

    void recordChat(Game game, Player advisor, Player speaker, String mode, String clientDescription,
            String prompt, DeepSeekClient.CompletionResult completion, String reply) {
        if (!isEnabled()) {
            return;
        }

        String entry = buildChatEntry(game, advisor, speaker, mode, clientDescription, prompt, completion, reply);
        writeLatestChat(entry);
        appendChatTranscript(entry);
    }

    private String buildEntry(Game game, Player advisor, List<SpellAbility> candidates, AiCardMemory memory,
            String clientDescription, String prompt, List<UltronAdvisor.ResearchTrace> researchTrace,
            DeepSeekClient.CompletionResult completion, UltronAdvisor.Decision decision) {
        StringBuilder sb = new StringBuilder(32768);
        sb.append("# Ultron DeepSeek Trace\n\n");
        sb.append("- timestamp: ").append(Instant.now()).append('\n');
        sb.append("- advisor: ").append(advisor.getName()).append('\n');
        sb.append("- turn: ").append(game.getPhaseHandler().getTurn()).append('\n');
        sb.append("- phase: ").append(game.getPhaseHandler().getPhase()).append('\n');
        sb.append("- activePlayer: ").append(game.getPhaseHandler().getPlayerTurn().getName()).append('\n');
        sb.append("- client: ").append(clientDescription).append('\n');
        sb.append("- httpStatus: ").append(completion.statusCode()).append('\n');
        sb.append("- success: ").append(completion.success()).append('\n');
        appendCompletionMetadata(sb, completion);
        if (completion.error() != null) {
            sb.append("- error: ").append(completion.error()).append('\n');
        }
        sb.append("- parsedChoice: ").append(choiceSummary(decision, advisor, memory)).append('\n');
        sb.append('\n');

        sb.append("## Candidates\n\n");
        appendCandidates(sb, advisor, candidates, memory);

        if (researchTrace != null && !researchTrace.isEmpty()) {
            sb.append("\n## Research Rounds\n\n");
            for (UltronAdvisor.ResearchTrace trace : researchTrace) {
                appendResearchTrace(sb, trace);
            }
        }

        sb.append("\n## DeepSeek Reasoning Content\n\n");
        if (isBlank(completion.reasoningContent())) {
            appendMissingReasoningMessage(sb, completion);
        } else {
            sb.append(completion.reasoningContent()).append('\n');
        }

        sb.append("\n## DeepSeek Final Content\n\n");
        if (isBlank(completion.content())) {
            appendMissingContentMessage(sb, completion);
        } else {
            fenced(sb, "json", completion.content());
        }

        if (includePrompt()) {
            sb.append("\n## Prompt Sent To DeepSeek\n\n");
            fenced(sb, "text", prompt);
        }

        if (includeRawResponse()) {
            sb.append("\n## Raw DeepSeek Response\n\n");
            if (isBlank(completion.rawResponse())) {
                sb.append("No raw response body was captured.\n");
            } else {
                fenced(sb, "json", completion.rawResponse());
            }
        }

        sb.append("\n---\n\n");
        return truncate(sb.toString(), parsePositiveInt(System.getenv("ULTRON_TRACE_MAX_ENTRY_CHARS"), DEFAULT_MAX_ENTRY_CHARS));
    }

    private static void appendResearchTrace(StringBuilder sb, UltronAdvisor.ResearchTrace trace) {
        sb.append("### Round ").append(trace.round()).append("\n\n");
        sb.append("- httpStatus: ").append(trace.completion().statusCode()).append('\n');
        sb.append("- success: ").append(trace.completion().success()).append('\n');
        appendCompletionMetadata(sb, trace.completion());
        if (trace.completion().error() != null) {
            sb.append("- error: ").append(trace.completion().error()).append('\n');
        }
        sb.append('\n');
        sb.append("#### DeepSeek Research Reasoning\n\n");
        if (isBlank(trace.completion().reasoningContent())) {
            appendMissingReasoningMessage(sb, trace.completion());
        } else {
            sb.append(trace.completion().reasoningContent()).append('\n');
        }
        sb.append("\n#### DeepSeek Research Request\n\n");
        if (isBlank(trace.completion().content())) {
            appendMissingContentMessage(sb, trace.completion());
        } else {
            fenced(sb, "json", trace.completion().content());
        }
        sb.append("\n#### Forge Tool Result\n\n");
        fenced(sb, "json", trace.toolResult());
        if (includePrompt()) {
            sb.append("\n#### Prompt Sent For This Round\n\n");
            fenced(sb, "text", trace.prompt());
        }
        sb.append('\n');
    }

    private String buildChatEntry(Game game, Player advisor, Player speaker, String mode, String clientDescription,
            String prompt, DeepSeekClient.CompletionResult completion, String reply) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("# Ultron Chat Trace\n\n");
        sb.append("- timestamp: ").append(Instant.now()).append('\n');
        sb.append("- mode: ").append(mode == null ? "chat" : mode).append('\n');
        sb.append("- advisor: ").append(advisor == null ? "" : advisor.getName()).append('\n');
        sb.append("- speaker: ").append(speaker == null ? "" : speaker.getName()).append('\n');
        sb.append("- turn: ").append(game.getPhaseHandler().getTurn()).append('\n');
        sb.append("- phase: ").append(game.getPhaseHandler().getPhase()).append('\n');
        sb.append("- activePlayer: ").append(game.getPhaseHandler().getPlayerTurn().getName()).append('\n');
        sb.append("- client: ").append(clientDescription).append('\n');
        sb.append("- httpStatus: ").append(completion.statusCode()).append('\n');
        sb.append("- success: ").append(completion.success()).append('\n');
        appendCompletionMetadata(sb, completion);
        if (completion.error() != null) {
            sb.append("- error: ").append(completion.error()).append('\n');
        }
        sb.append('\n');

        sb.append("## Ultron Reply\n\n");
        if (isBlank(reply)) {
            sb.append("No reply was produced.\n");
        } else {
            fenced(sb, "text", reply);
        }

        sb.append("\n## DeepSeek Reasoning Content\n\n");
        if (isBlank(completion.reasoningContent())) {
            appendMissingReasoningMessage(sb, completion);
        } else {
            sb.append(completion.reasoningContent()).append('\n');
        }

        sb.append("\n## DeepSeek Final Content\n\n");
        if (isBlank(completion.content())) {
            appendMissingContentMessage(sb, completion);
        } else {
            fenced(sb, "text", completion.content());
        }

        if (includePrompt()) {
            sb.append("\n## Prompt Sent To DeepSeek\n\n");
            fenced(sb, "text", prompt);
        }

        if (includeRawResponse()) {
            sb.append("\n## Raw DeepSeek Response\n\n");
            if (isBlank(completion.rawResponse())) {
                sb.append("No raw response body was captured.\n");
            } else {
                fenced(sb, "json", completion.rawResponse());
            }
        }

        sb.append("\n---\n\n");
        return truncate(sb.toString(), parsePositiveInt(System.getenv("ULTRON_TRACE_MAX_ENTRY_CHARS"), DEFAULT_MAX_ENTRY_CHARS));
    }

    private static void appendCandidates(StringBuilder sb, Player advisor, List<SpellAbility> candidates, AiCardMemory memory) {
        for (int i = 0; i < candidates.size(); i++) {
            SpellAbility candidate = candidates.get(i);
            sb.append("- ").append(i)
                    .append(": ")
                    .append(UltronGameStateSerializer.sourceName(candidate, advisor, memory));
            String api = UltronGameStateSerializer.apiName(candidate);
            if (!api.isEmpty()) {
                sb.append('/').append(api);
            }
            sb.append(" | ").append(candidate).append('\n');
        }
    }

    private static String choiceSummary(UltronAdvisor.Decision decision, Player advisor, AiCardMemory memory) {
        if (!decision.hasAdvice()) {
            return "no_advice";
        }
        if (decision.getChoiceIndex() < 0) {
            return "pass";
        }
        SpellAbility chosen = decision.getSpellAbility();
        return decision.getChoiceIndex() + " " + UltronGameStateSerializer.sourceName(chosen, advisor, memory);
    }

    private void writeLatest(String entry) {
        try {
            Files.createDirectories(traceDir);
            Files.writeString(latestFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            Logger.debug(ex, "Unable to write Ultron latest trace");
        }
    }

    private void appendTranscript(String entry) {
        try {
            Files.createDirectories(traceDir);
            Files.writeString(transcriptFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            Logger.debug(ex, "Unable to append Ultron trace transcript");
        }
    }

    private void writeLatestChat(String entry) {
        try {
            Files.createDirectories(traceDir);
            Files.writeString(latestChatFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            Logger.debug(ex, "Unable to write Ultron latest chat trace");
        }
    }

    private void appendChatTranscript(String entry) {
        try {
            Files.createDirectories(traceDir);
            Files.writeString(chatTranscriptFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            Logger.debug(ex, "Unable to append Ultron chat trace transcript");
        }
    }

    private static Path configuredTraceDir() {
        String configured = System.getenv("ULTRON_TRACE_DIR");
        if (!isBlank(configured)) {
            return Paths.get(configured).toAbsolutePath();
        }
        configured = System.getenv("ULTRON_LEARNING_DIR");
        if (!isBlank(configured)) {
            return Paths.get(configured).toAbsolutePath().resolve("trace");
        }
        String home = System.getProperty("user.home");
        if (!isBlank(home)) {
            return Paths.get(home, ".forge", "ultron-learning", "trace");
        }
        return Paths.get("ultron-learning", "trace").toAbsolutePath();
    }

    private static boolean isEnabled() {
        String value = System.getenv("ULTRON_TRACE_ENABLED");
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "0", "false", "no", "off" -> false;
        default -> true;
        };
    }

    private static boolean includePrompt() {
        String value = System.getenv("ULTRON_TRACE_INCLUDE_PROMPT");
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "0", "false", "no", "off" -> false;
        default -> true;
        };
    }

    private static boolean includeRawResponse() {
        String value = System.getenv("ULTRON_TRACE_INCLUDE_RAW_RESPONSE");
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "0", "false", "no", "off" -> false;
        default -> true;
        };
    }

    private static void fenced(StringBuilder sb, String language, String value) {
        sb.append("```").append(language).append('\n');
        sb.append(value == null ? "" : value.replace("```", "` ` `"));
        if (value != null && !value.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("```\n");
    }

    private static void appendCompletionMetadata(StringBuilder sb, DeepSeekClient.CompletionResult completion) {
        if (!isBlank(completion.providerTraceId())) {
            sb.append("- deepseekTraceId: ").append(completion.providerTraceId()).append('\n');
        }
        if (!isBlank(completion.usageSummary())) {
            sb.append("- usage: ").append(completion.usageSummary()).append('\n');
        }
        if (!isBlank(completion.finishReason())) {
            sb.append("- finishReason: ").append(completion.finishReason()).append('\n');
        }
        sb.append("- reasoningChars: ").append(lengthOf(completion.reasoningContent())).append('\n');
        sb.append("- finalChars: ").append(lengthOf(completion.content())).append('\n');
        sb.append("- rawResponseChars: ").append(lengthOf(completion.rawResponse())).append('\n');
    }

    private static void appendMissingReasoningMessage(StringBuilder sb, DeepSeekClient.CompletionResult completion) {
        if (!completion.success() && isBlank(completion.rawResponse())) {
            sb.append("No DeepSeek response body was captured; `reasoning_content` is unavailable.\n");
        } else {
            sb.append("No `reasoning_content` field was returned.\n");
        }
    }

    private static void appendMissingContentMessage(StringBuilder sb, DeepSeekClient.CompletionResult completion) {
        if (!completion.success() && isBlank(completion.rawResponse())) {
            sb.append("No DeepSeek response body was captured; final `content` is unavailable.\n");
        } else {
            sb.append("No final `content` field was returned.\n");
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 24)) + "\n[trace truncated]\n";
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

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }
}
