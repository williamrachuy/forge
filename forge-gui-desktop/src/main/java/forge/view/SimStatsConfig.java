package forge.view;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import forge.game.GameType;
import forge.util.WordUtil;

final class SimStatsConfig {
    private final Path source;
    private final Map<String, String> values;
    private final String hash;

    private SimStatsConfig(final Path source, final Map<String, String> values) {
        this.source = source;
        this.values = values;
        this.hash = computeHash(values);
    }

    static SimStatsConfig load(final Path source) throws IOException {
        final Map<String, String> values = new TreeMap<>();
        String section = "";
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                    continue;
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1).trim();
                    continue;
                }
                final int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    throw new IOException("Invalid simstats config line " + lineNumber + ": " + line);
                }
                final String key = trimmed.substring(0, eq).trim();
                final String value = trimmed.substring(eq + 1).trim();
                values.put((section.isEmpty() ? key : section + "." + key), value);
            }
        }
        return new SimStatsConfig(source, values);
    }

    Path getSource() {
        return source;
    }

    String getHash() {
        return hash;
    }

    String getRunName() {
        return get("run.name", stripExtension(source.getFileName().toString()));
    }

    int getGames() {
        return getInt("run.games", 1);
    }

    long getSeed() {
        return getLong("run.seed", System.currentTimeMillis());
    }

    int getTimeoutSeconds() {
        return getInt("run.timeoutSeconds", 120);
    }

    Path getOutputDir() {
        return Path.of(get("run.outputDir", "simstats/out/" + getRunName()));
    }

    GameType getFormat() {
        return GameType.valueOf(WordUtil.capitalize(get("game.format", "Battlebox")));
    }

    int getPlayers() {
        return getInt("game.players", 2);
    }

    List<String> getDeckNames() {
        final String decks = get("game.decks", null);
        if (decks != null) {
            return splitList(decks);
        }
        final String deck = get("game.deck", null);
        if (deck == null || deck.isBlank()) {
            throw new IllegalArgumentException("Missing required config value game.deck or game.decks");
        }
        return List.of(deck);
    }

    List<String> getAiProfiles() {
        final List<String> explicit = splitList(get("game.aiProfiles", ""));
        if (!explicit.isEmpty()) {
            return explicit;
        }
        final String profile = get("game.aiProfile", "Default");
        final List<String> profiles = new ArrayList<>();
        for (int i = 0; i < getPlayers(); i++) {
            profiles.add(profile);
        }
        return profiles;
    }

    Boolean getBattleboxMonarch() {
        final String value = get("game.battleboxMonarch", null);
        return value == null ? null : Boolean.parseBoolean(value);
    }

    boolean isStatsEnabled() {
        return getBoolean("stats.enabled", true);
    }

    boolean isTurnSnapshotsEnabled() {
        return getBoolean("stats.turnSnapshots", true);
    }

    private String get(final String key, final String fallback) {
        final String value = values.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private int getInt(final String key, final int fallback) {
        final String value = get(key, null);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private long getLong(final String key, final long fallback) {
        final String value = get(key, null);
        return value == null ? fallback : Long.parseLong(value);
    }

    private boolean getBoolean(final String key, final boolean fallback) {
        final String value = get(key, null);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static List<String> splitList(final String value) {
        final List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (final String entry : value.split("[,;]")) {
            final String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String stripExtension(final String value) {
        final int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static String computeHash(final Map<String, String> values) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            values.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).forEach(entry -> {
                update(digest, entry.getKey());
                update(digest, "=");
                update(digest, entry.getValue());
                update(digest, "\n");
            });
            final byte[] bytes = digest.digest();
            final StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8 && i < bytes.length; i++) {
                out.append(String.format(Locale.ROOT, "%02x", bytes[i]));
            }
            return out.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(final MessageDigest digest, final String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
