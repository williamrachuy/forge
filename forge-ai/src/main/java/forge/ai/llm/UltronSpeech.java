package forge.ai.llm;

import org.tinylog.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class UltronSpeech {
    private static final UltronSpeech INSTANCE = new UltronSpeech();
    private static final String DEFAULT_COMMAND = "/home/william/projects/claude-speak/speak.sh";
    private static final int DEFAULT_MAX_CHARS = 500;
    private volatile boolean preferenceEnabled = true;

    private UltronSpeech() {
    }

    static UltronSpeech get() {
        return INSTANCE;
    }

    void speak(String message) {
        if (!isEnabled() || message == null || message.isBlank()) {
            return;
        }
        String spoken = trimForSpeech(message);
        Thread thread = new Thread(() -> runSpeechCommand(spoken), "Ultron speech");
        thread.setDaemon(true);
        thread.start();
    }

    void setPreferenceEnabled(boolean enabled) {
        preferenceEnabled = enabled;
    }

    private static void runSpeechCommand(String message) {
        String command = firstNonBlank(System.getenv("ULTRON_VOICE_COMMAND"), DEFAULT_COMMAND);
        String engine = System.getenv("ULTRON_VOICE_ENGINE");
        List<String> args = new ArrayList<>();
        args.add(command);
        if (engine != null && !engine.isBlank()) {
            args.add(engine.trim());
        }

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = pb.start();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(message);
                writer.newLine();
            }
        } catch (IOException ex) {
            Logger.debug(ex, "Unable to speak Ultron table talk");
        }
    }

    private static String trimForSpeech(String message) {
        String value = message.replace('\n', ' ').trim();
        int maxChars = parsePositiveInt(System.getenv("ULTRON_VOICE_MAX_CHARS"), DEFAULT_MAX_CHARS);
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 1)).trim();
    }

    private boolean isEnabled() {
        if (!preferenceEnabled) {
            return false;
        }
        String value = System.getenv("ULTRON_VOICE_ENABLED");
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "0", "false", "no", "off" -> false;
        default -> true;
        };
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
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
}
