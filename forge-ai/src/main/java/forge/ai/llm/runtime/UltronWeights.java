package forge.ai.llm.runtime;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime-tunable weight multipliers for Ultron's scoring functions.
 *
 * <p>Each weight defaults to 1.0 (no change from baseline). When adaptive
 * learning is enabled, multipliers are persisted to a JSON file and loaded
 * on startup. The baseline Java constants in the scorers are never modified.
 *
 * <p>Usage: {@code UltronWeights.get(UltronWeights.SCORE_THRESHOLD)}
 */
public final class UltronWeights {

    // -----------------------------------------------------------------------
    // Named weight keys
    // -----------------------------------------------------------------------

    /** Minimum score for Ultron to act instead of falling back to Forge default. */
    public static final String SCORE_THRESHOLD = "scoreThreshold";

    /** Multiplier on removal spell scores in the action scorer. */
    public static final String REMOVAL_BONUS = "removalBonus";

    /** Multiplier on attack-related action scores (aggression pressure). */
    public static final String AGGRESSION = "aggression";

    /** Multiplier on the candidate prune threshold (higher = prune more aggressively). */
    public static final String PRUNE_AGGRESSION = "pruneAggression";

    // -----------------------------------------------------------------------
    // Baseline values — these represent neutral (1.0 multiplier) positions.
    // The actual hardcoded constants in the scorers are the true baselines.
    // -----------------------------------------------------------------------

    private static final Map<String, Double> DEFAULTS = Map.of(
            SCORE_THRESHOLD, 1.0,
            REMOVAL_BONUS,   1.0,
            AGGRESSION,      1.0,
            PRUNE_AGGRESSION, 1.0
    );

    /** Bounds: multipliers are clamped to [MIN, MAX] to prevent runaway learning. */
    public static final double MIN_MULTIPLIER = 0.2;
    public static final double MAX_MULTIPLIER = 5.0;

    // -----------------------------------------------------------------------
    // Singleton
    // -----------------------------------------------------------------------

    private static final Path DEFAULT_PATH =
            Path.of(System.getProperty("user.home"), ".forge", "ultron-learning", "weights.json");

    private static UltronWeights INSTANCE = new UltronWeights(new LinkedHashMap<>());

    static {
        load(DEFAULT_PATH);
    }

    private final Map<String, Double> multipliers;

    private UltronWeights(Map<String, Double> multipliers) {
        this.multipliers = multipliers;
    }

    /** Returns the current multiplier for the named weight (1.0 if not overridden). */
    public static double get(String key) {
        return INSTANCE.multipliers.getOrDefault(key, DEFAULTS.getOrDefault(key, 1.0));
    }

    /** All current multipliers (keys that differ from 1.0). */
    public static Map<String, Double> all() {
        return Map.copyOf(INSTANCE.multipliers);
    }

    // -----------------------------------------------------------------------
    // File I/O
    // -----------------------------------------------------------------------

    /**
     * Load multipliers from the given file path.
     * Missing or malformed file silently falls back to defaults.
     */
    public static synchronized void load(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, Double> loaded = parseJson(r);
            Map<String, Double> validated = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : loaded.entrySet()) {
                if (DEFAULTS.containsKey(e.getKey())) {
                    validated.put(e.getKey(), clamp(e.getValue()));
                }
            }
            INSTANCE = new UltronWeights(validated);
            System.out.println("[ULTRON-WEIGHTS] Loaded " + validated.size() + " overrides from " + path);
        } catch (Exception ex) {
            System.err.println("[ULTRON-WEIGHTS] Failed to load " + path + ": " + ex.getMessage());
        }
    }

    /**
     * Persist current multipliers to the given file path.
     * Only writes keys that differ meaningfully from 1.0.
     */
    public static synchronized void save(Path path) {
        if (path == null) return;
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                w.write(toJson(INSTANCE.multipliers));
            }
        } catch (IOException ex) {
            System.err.println("[ULTRON-WEIGHTS] Failed to save " + path + ": " + ex.getMessage());
        }
    }

    /** Apply a nudge to a named weight. Returns the new value. */
    public static synchronized double nudge(String key, double delta) {
        double current = get(key);
        double updated = clamp(current + delta);
        Map<String, Double> next = new LinkedHashMap<>(INSTANCE.multipliers);
        next.put(key, updated);
        INSTANCE = new UltronWeights(next);
        return updated;
    }

    /** Reset all overrides back to 1.0. */
    public static synchronized void resetToBaseline() {
        INSTANCE = new UltronWeights(new LinkedHashMap<>());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    static double clamp(double value) {
        return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, value));
    }

    private static String toJson(Map<String, Double> map) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, Double> e : map.entrySet()) {
            if (!first) sb.append(",\n");
            sb.append("  \"").append(e.getKey()).append("\": ").append(String.format("%.6f", e.getValue()));
            first = false;
        }
        sb.append("\n}");
        return sb.toString();
    }

    // Minimal JSON parser — only handles flat string→number objects.
    private static Map<String, Double> parseJson(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = r.read()) != -1) sb.append((char) ch);
        Map<String, Double> result = new LinkedHashMap<>();
        String text = sb.toString().trim();
        if (!text.startsWith("{")) return result;
        text = text.substring(1, text.lastIndexOf('}')).trim();
        for (String pair : text.split(",")) {
            pair = pair.trim();
            int colon = pair.indexOf(':');
            if (colon < 0) continue;
            String key = pair.substring(0, colon).trim().replace("\"", "");
            String val = pair.substring(colon + 1).trim();
            try {
                result.put(key, Double.parseDouble(val));
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }
}
