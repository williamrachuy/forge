package forge.ai.llm.runtime;

import forge.ai.llm.UltronConfig;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.tinylog.Logger;

/**
 * Lightweight structured logging for Ultron runtime decisions.
 * Entirely disabled unless {@code ULTRON_DECISION_LOGGING=true}.
 * Does not depend on DeepSeek; does not write prompt traces.
 */
public final class UltronDecisionLog {

    // Log categories
    public static final String PRIORITY = "PRIORITY";
    public static final String STACK    = "STACK";
    public static final String MAIN     = "MAIN";
    public static final String COMBAT   = "COMBAT";
    public static final String TARGET   = "TARGET";
    public static final String INTENT   = "INTENT";
    public static final String THREAT   = "THREAT";
    public static final String MANA     = "MANA";
    public static final String FALLBACK = "FALLBACK";
    public static final String TIMING   = "TIMING";
    public static final String ERROR    = "ERROR";
    public static final String SCORE    = "SCORE";

    private UltronDecisionLog() {}

    public static void log(Player player, String category, String message) {
        if (!UltronConfig.enabledForDecisionLogging()) return;
        Logger.info("[ULTRON-RUNTIME][{}] player={} {}",
                category, player == null ? "?" : player.getName(), message);
    }

    public static void timing(Player player, String category, long elapsedMs, String decision) {
        if (!UltronConfig.enabledForDecisionLogging()) return;
        Logger.info("[ULTRON-RUNTIME][{}] player={} elapsedMs={} decision={}",
                category, player == null ? "?" : player.getName(), elapsedMs, decision);
    }

    public static void fallback(Player player, String reason) {
        if (!UltronConfig.enabledForDecisionLogging()) return;
        Logger.info("[ULTRON-RUNTIME][FALLBACK] player={} reason={}",
                player == null ? "?" : player.getName(), reason);
    }

    public static void error(Player player, String where, RuntimeException ex) {
        Logger.warn("[ULTRON-RUNTIME][ERROR] player={} where={} error={}",
                player == null ? "?" : player.getName(), where,
                ex == null ? "null" : ex.getMessage());
    }

    public static void logScore(SpellAbility sa, int score, String breakdown) {
        if (!UltronConfig.enabledForDecisionLogging()) return;
        String name = sa != null && sa.getHostCard() != null ? sa.getHostCard().getName() : "null";
        Logger.info("[ULTRON-RUNTIME][SCORE] card={} score={} breakdown={}", name, score, breakdown);
    }

    public static void logTurnIntent(UltronTurnIntent intent) {
        if (!UltronConfig.enabledForDecisionLogging()) return;
        Logger.info("[ULTRON-RUNTIME][INTENT] {}",
                intent != null ? intent.reason : "null intent");
    }
}
