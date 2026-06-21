package forge.ai.llm.runtime;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-card win-rate learning table for Ultron.
 *
 * <p>Tracks (plays, wins) per card name across sim games. After MIN_SAMPLE plays,
 * applies a score adjustment at runtime: cards that appear in winning games get
 * a bonus; cards that appear consistently in losing games get a penalty.
 *
 * <p>This replaces the manually hand-coded UltronCardContextEvaluator rules.
 * Coalition Relic, Cultivate, Bushwhack etc. will be penalized automatically
 * after enough games; Feldon, Reflection of Kiki-Jiki will be boosted.
 *
 * <p>Stats are persisted to a JSON file alongside the weights file and updated
 * after each sim game (when adaptiveWeights=true). Score lookup is O(1).
 */
public final class UltronCardStats {

    /** Minimum number of plays before applying any score adjustment. */
    private static final int MIN_SAMPLE = 8;

    private static final int BONUS_STRONG  =  15;  // win rate > 0.55
    private static final int BONUS_WEAK    =   8;  // win rate > 0.40
    private static final int PENALTY_WEAK  = -10;  // win rate < 0.25
    private static final int PENALTY_STRONG = -20; // win rate < 0.15

    record CardRecord(int plays, int wins) {
        double winRate() { return plays > 0 ? (double) wins / plays : 0.0; }
        CardRecord withPlay(boolean won) {
            return new CardRecord(plays + 1, wins + (won ? 1 : 0));
        }
    }

    private static UltronCardStats INSTANCE = new UltronCardStats(new LinkedHashMap<>());

    private final Map<String, CardRecord> records;

    private UltronCardStats(Map<String, CardRecord> records) {
        this.records = records;
    }

    /**
     * Record which cards were played this game and whether Ultron won.
     * Each distinct card name gets one play credit; duplicates within a game are collapsed.
     */
    public static synchronized void record(List<String> cardsPlayed, boolean ultronWon) {
        if (cardsPlayed == null || cardsPlayed.isEmpty()) return;
        Map<String, CardRecord> next = new LinkedHashMap<>(INSTANCE.records);
        for (String name : cardsPlayed.stream().distinct().toList()) {
            CardRecord existing = next.getOrDefault(name, new CardRecord(0, 0));
            next.put(name, existing.withPlay(ultronWon));
        }
        INSTANCE = new UltronCardStats(next);
    }

    /**
     * Score adjustment for a card based on historical win rate.
     * Returns 0 if insufficient data (fewer than MIN_SAMPLE plays).
     */
    public static int scoreAdjustment(String cardName) {
        if (cardName == null) return 0;
        CardRecord rec = INSTANCE.records.get(cardName);
        if (rec == null || rec.plays() < MIN_SAMPLE) return 0;
        double wr = rec.winRate();
        if (wr < 0.15) return PENALTY_STRONG;
        if (wr < 0.25) return PENALTY_WEAK;
        if (wr > 0.55) return BONUS_STRONG;
        if (wr > 0.40) return BONUS_WEAK;
        return 0;
    }

    // ---------------------------------------------------------------------------
    // File I/O
    // ---------------------------------------------------------------------------

    public static synchronized void load(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, CardRecord> loaded = parseJson(r);
            INSTANCE = new UltronCardStats(loaded);
            System.out.println("[ULTRON-CARD-STATS] Loaded " + loaded.size() + " card records from " + path);
        } catch (Exception ex) {
            System.err.println("[ULTRON-CARD-STATS] Failed to load " + path + ": " + ex.getMessage());
        }
    }

    public static synchronized void save(Path path) {
        if (path == null) return;
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                w.write(toJson(INSTANCE.records));
            }
        } catch (IOException ex) {
            System.err.println("[ULTRON-CARD-STATS] Failed to save " + path + ": " + ex.getMessage());
        }
    }

    /** Print cards with MIN_SAMPLE+ plays, sorted by win rate descending. */
    public static void logTopCards(int limit) {
        INSTANCE.records.entrySet().stream()
                .filter(e -> e.getValue().plays() >= MIN_SAMPLE)
                .sorted((a, b) -> Double.compare(b.getValue().winRate(), a.getValue().winRate()))
                .limit(limit)
                .forEach(e -> {
                    CardRecord r = e.getValue();
                    System.out.printf("[ULTRON-CARD-STATS]  %-40s plays=%3d wins=%3d wr=%.2f%n",
                            e.getKey(), r.plays(), r.wins(), r.winRate());
                });
    }

    // ---------------------------------------------------------------------------
    // JSON serialization
    // ---------------------------------------------------------------------------

    private static String toJson(Map<String, CardRecord> records) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, CardRecord> e : records.entrySet()) {
            if (!first) sb.append(",\n");
            CardRecord r = e.getValue();
            sb.append("  \"").append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\""))
              .append("\": {\"plays\": ").append(r.plays())
              .append(", \"wins\": ").append(r.wins()).append("}");
            first = false;
        }
        sb.append("\n}");
        return sb.toString();
    }

    // Parses: { "Card Name": {"plays": N, "wins": M}, ... }
    private static Map<String, CardRecord> parseJson(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = r.read()) != -1) sb.append((char) ch);
        Map<String, CardRecord> result = new LinkedHashMap<>();
        String text = sb.toString().trim();
        if (!text.startsWith("{")) return result;
        text = text.substring(1, text.lastIndexOf('}')).trim();
        int i = 0;
        while (i < text.length()) {
            int nameStart = text.indexOf('"', i);
            if (nameStart < 0) break;
            int nameEnd = findClosingQuote(text, nameStart + 1);
            if (nameEnd < 0) break;
            String name = unescapeJson(text.substring(nameStart + 1, nameEnd));
            int objStart = text.indexOf('{', nameEnd);
            if (objStart < 0) break;
            int objEnd = text.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = text.substring(objStart + 1, objEnd);
            int plays = extractInt(obj, "plays");
            int wins  = extractInt(obj, "wins");
            if (plays >= 0 && wins >= 0) {
                result.put(name, new CardRecord(plays, wins));
            }
            i = objEnd + 1;
        }
        return result;
    }

    private static int findClosingQuote(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '\\') { i++; continue; }
            if (text.charAt(i) == '"') return i;
        }
        return -1;
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int extractInt(String obj, String key) {
        int idx = obj.indexOf('"' + key + '"');
        if (idx < 0) idx = obj.indexOf(key);
        if (idx < 0) return -1;
        int colon = obj.indexOf(':', idx);
        if (colon < 0) return -1;
        String rest = obj.substring(colon + 1).trim();
        int end = 0;
        while (end < rest.length() && (Character.isDigit(rest.charAt(end)) || rest.charAt(end) == '-')) end++;
        if (end == 0) return -1;
        try {
            return Integer.parseInt(rest.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
