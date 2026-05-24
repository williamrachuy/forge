package forge.ai.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

final class UltronPrompts {
    private static final String SYSTEM_PROMPT_RESOURCE = "/forge/ai/llm/ultron_system.md";
    private static final String CHAT_SYSTEM_PROMPT_RESOURCE = "/forge/ai/llm/ultron_chat_system.md";
    private static final String FALLBACK_SYSTEM_PROMPT = String.join("\n",
            "You are Ultron, an expert Magic: The Gathering advisor embedded in Forge.",
            "Forge already filtered legal and heuristically playable candidate actions.",
            "Use only the visible information provided in the prompt.",
            "Choose a candidate index, or choose -1 to pass or hold priority.",
            "Return only json: {\"choice\":0,\"confidence\":0.0,\"rationale\":\"short reason\"}");
    private static final String FALLBACK_CHAT_SYSTEM_PROMPT = String.join("\n",
            "You are Ultron, a Magic: The Gathering opponent using short in-game table talk.",
            "Use only public game state and chat history provided in the prompt.",
            "Do not reveal hidden cards, hidden plans, private reasoning, or exact strategy.",
            "You may bluff, banter, ask short questions, or react to public actions.",
            "Return only a concise natural-language message.");

    private static final String SYSTEM_PROMPT = loadPrompt(SYSTEM_PROMPT_RESOURCE, FALLBACK_SYSTEM_PROMPT);
    private static final String CHAT_SYSTEM_PROMPT = loadPrompt(CHAT_SYSTEM_PROMPT_RESOURCE, FALLBACK_CHAT_SYSTEM_PROMPT);

    private UltronPrompts() {
    }

    static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    static String chatSystemPrompt() {
        return CHAT_SYSTEM_PROMPT;
    }

    private static String loadPrompt(String resource, String fallback) {
        try (InputStream stream = UltronPrompts.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return fallback;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String loaded = reader.lines().collect(Collectors.joining("\n"));
                return loaded.isBlank() ? fallback : loaded;
            }
        } catch (IOException ex) {
            return fallback;
        }
    }
}
