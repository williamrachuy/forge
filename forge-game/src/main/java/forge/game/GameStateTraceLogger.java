package forge.game;

import forge.game.card.Card;
import forge.game.event.Event;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;

final class GameStateTraceLogger {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int MAX_ZONE_CARDS = Integer.getInteger("forge.deepGameTrace.maxZoneCards", 40);

    private final Game game;
    private final BufferedWriter writer;

    private GameStateTraceLogger(final Game game0, final BufferedWriter writer0) {
        game = game0;
        writer = writer0;
        trace("trace-start gameId=" + game.getId() + " title=" + game.getMatch().getTitle()
                + " format=" + game.getRules().getGameType());
    }

    static GameStateTraceLogger create(final Game game) {
        if (!isEnabled()) {
            return null;
        }
        try {
            final Path dir = getTraceDirectory();
            Files.createDirectories(dir);
            final Path file = dir.resolve("game-" + game.getId() + "-" + FILE_TIMESTAMP.format(LocalDateTime.now()) + ".log");
            return new GameStateTraceLogger(game, Files.newBufferedWriter(file, StandardCharsets.UTF_8));
        } catch (final IOException ex) {
            System.err.println("Unable to create Forge deep game trace: " + ex.getMessage());
            return null;
        }
    }

    void event(final Event event) {
        trace("event " + event.getClass().getSimpleName() + " " + safe(event));
    }

    void trace(final String message) {
        try {
            writer.write(LOG_TIMESTAMP.format(LocalDateTime.now()));
            writer.write(" ");
            writer.write(message);
            writer.newLine();
            writer.write("  ");
            writer.write(snapshot());
            writer.newLine();
            writer.flush();
        } catch (final IOException ex) {
            System.err.println("Unable to write Forge deep game trace: " + ex.getMessage());
        }
    }

    private String snapshot() {
        final StringBuilder sb = new StringBuilder();
        final PhaseHandler phase = game.getPhaseHandler();
        sb.append("state turn=").append(phase.getTurn())
                .append(" phase=").append(phase.getPhase())
                .append(" playerTurn=").append(phase.getPlayerTurn())
                .append(" priority=").append(phase.getPriorityPlayer())
                .append(" monarch=").append(game.getMonarch())
                .append(" battleboxMonarchChoiceMade=").append(game.isBattleboxMonarchChoiceMade())
                .append(" battleboxMonarchEnabled=").append(game.isBattleboxMonarchEnabled())
                .append(" stack=").append(stackSummary());
        for (final Player player : game.getPlayers()) {
            sb.append(" | ").append(playerSummary(player));
        }
        return sb.toString();
    }

    private String stackSummary() {
        final StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (final SpellAbilityStackInstance si : game.getStack()) {
            joiner.add(si.toString());
        }
        return joiner.toString();
    }

    private String playerSummary(final Player player) {
        return player + "{life=" + player.getLife()
                + ",hand=" + player.getZone(ZoneType.Hand).size()
                + ",library=" + player.getZone(ZoneType.Library).size()
                + ",graveyard=" + zoneCards(player, ZoneType.Graveyard)
                + ",battlefield=" + zoneCards(player, ZoneType.Battlefield)
                + "}";
    }

    private String zoneCards(final Player player, final ZoneType zone) {
        final StringJoiner joiner = new StringJoiner(",", "[", "]");
        int count = 0;
        for (final Card card : player.getZone(zone)) {
            if (count++ >= MAX_ZONE_CARDS) {
                joiner.add("...+" + (player.getZone(zone).size() - MAX_ZONE_CARDS));
                break;
            }
            joiner.add(card.getName() + "#" + card.getId() + "/" + card.getController());
        }
        return joiner.toString();
    }

    private static boolean isEnabled() {
        final String configured = System.getProperty("forge.deepGameTrace");
        if (configured != null && !configured.isBlank()) {
            return isTruthy(configured);
        }
        final String env = System.getenv("FORGE_DEEP_GAME_TRACE");
        if (env != null && !env.isBlank()) {
            return isTruthy(env);
        }
        return hasConfiguredTraceDirectory();
    }

    private static Path getTraceDirectory() {
        final String configured = System.getProperty("forge.deepGameTraceDir");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        final String env = System.getenv("FORGE_DEEP_GAME_TRACE_DIR");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("user.home"), ".forge", "deep-game-trace");
    }

    private static boolean isTruthy(final String value) {
        return value != null && ("1".equals(value.trim()) || "true".equalsIgnoreCase(value.trim())
                || "yes".equalsIgnoreCase(value.trim()) || "on".equalsIgnoreCase(value.trim()));
    }

    private static boolean hasConfiguredTraceDirectory() {
        final String configured = System.getProperty("forge.deepGameTraceDir");
        if (configured != null && !configured.isBlank()) {
            return true;
        }
        final String env = System.getenv("FORGE_DEEP_GAME_TRACE_DIR");
        return env != null && !env.isBlank();
    }

    private static String safe(final Object value) {
        try {
            return String.valueOf(value);
        } catch (final RuntimeException ex) {
            return "<toString failed: " + ex.getClass().getSimpleName() + ">";
        }
    }
}
