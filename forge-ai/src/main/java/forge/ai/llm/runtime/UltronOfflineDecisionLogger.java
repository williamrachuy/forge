package forge.ai.llm.runtime;

import forge.ai.llm.UltronConfig;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import org.tinylog.Logger;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * Optional offline JSONL decision log for future policy training.
 * Enabled via {@code ULTRON_OFFLINE_DECISION_LOGGING=true}.
 * Log path: {@code ULTRON_OFFLINE_DECISION_LOG_PATH} (default: /tmp/ultron_decisions.jsonl).
 * Does not slow gameplay noticeably when disabled.
 */
public final class UltronOfflineDecisionLogger {

    private static final String DEFAULT_PATH = "/tmp/ultron_decisions.jsonl";
    private static PrintWriter writer;
    private static boolean initialized;

    private UltronOfflineDecisionLogger() {}

    /** Log a single decision record. No-op unless offline logging is enabled. */
    public static void log(UltronDecisionContext ctx, UltronRuntimeDecision decision,
                            List<SpellAbility> candidates, int chosenIndex) {
        if (!isEnabled()) return;
        PrintWriter w = getWriter();
        if (w == null) return;

        PhaseType phase = ctx.phase;
        Player active = ctx.activePlayer;
        UltronTurnIntent intent = ctx.intent;

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"turn\":").append(ctx.game.getPhaseHandler().getTurn()).append(",");
        sb.append("\"phase\":\"").append(phase).append("\",");
        sb.append("\"player\":\"").append(ctx.player.getName()).append("\",");
        sb.append("\"activePlayer\":\"").append(active != null ? active.getName() : "?").append("\",");
        sb.append("\"stackEmpty\":").append(ctx.stackEmpty).append(",");
        sb.append("\"role\":\"").append(intent != null ? intent.role : "?").append("\",");

        if (ctx.table.leader != null) {
            sb.append("\"leader\":\"").append(ctx.table.leader.player.getName()).append("\",");
        }
        if (intent != null && intent.primaryThreat != null) {
            sb.append("\"primaryThreat\":\"").append(intent.primaryThreat.getName()).append("\",");
        }

        sb.append("\"candidateCount\":").append(candidates.size()).append(",");
        sb.append("\"chosenIndex\":").append(chosenIndex).append(",");

        if (decision.hasChoice() && decision.getSpellAbility() != null) {
            SpellAbility sa = decision.getSpellAbility();
            sb.append("\"chosenCard\":\"").append(escape(sa.getHostCard().getName())).append("\",");
            sb.append("\"chosenApi\":\"").append(sa.getApi()).append("\",");
        }

        sb.append("\"kind\":\"").append(decision.getKind()).append("\",");
        sb.append("\"reason\":\"").append(escape(decision.getReason())).append("\"");
        sb.append("}");

        synchronized (UltronOfflineDecisionLogger.class) {
            w.println(sb);
            w.flush();
        }
    }

    private static boolean isEnabled() {
        return UltronConfig.boolEnv("ULTRON_OFFLINE_DECISION_LOGGING", false);
    }

    private static synchronized PrintWriter getWriter() {
        if (!initialized) {
            initialized = true;
            String path = System.getenv("ULTRON_OFFLINE_DECISION_LOG_PATH");
            if (path == null || path.isBlank()) path = DEFAULT_PATH;
            try {
                writer = new PrintWriter(new BufferedWriter(new FileWriter(path, true)));
                Logger.info("[Ultron] Offline decision log: {}", path);
            } catch (IOException e) {
                Logger.warn("[Ultron] Cannot open offline decision log {}: {}", path, e.getMessage());
            }
        }
        return writer;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
