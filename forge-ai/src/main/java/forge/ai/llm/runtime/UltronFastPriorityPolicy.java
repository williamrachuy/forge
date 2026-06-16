package forge.ai.llm.runtime;

import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;

/**
 * Fast priority-pass decision engine for Ultron.
 *
 * <p>Decision tree (per plan §Phase 7):
 * <ol>
 *   <li>If no candidates → PASS</li>
 *   <li>If stack top is Ultron's own spell → PASS (unless obvious response)</li>
 *   <li>If stack empty and not Ultron's turn → PASS (unless end-step / must-use)</li>
 *   <li>If stack empty and Ultron's turn → NO_DECISION (main-phase policy decides)</li>
 *   <li>If stack not empty → classify threat with {@link UltronStackThreatAnalyzer}</li>
 *   <li>If threat below threshold → PASS</li>
 *   <li>If threat above threshold → consult {@link UltronInteractionPolicy}</li>
 *   <li>If good answer → CHOOSE it</li>
 *   <li>Otherwise → PASS</li>
 * </ol>
 *
 * <p>Hard timing requirement: normal priority pass &lt; 10 ms. No LLM, no HTTP.
 */
public final class UltronFastPriorityPolicy {

    private UltronFastPriorityPolicy() {}

    /** Main entry point — returns a decision for the current priority window. */
    public static UltronRuntimeDecision choose(UltronDecisionContext ctx) {
        long start = System.nanoTime();

        try {
            return doChoose(ctx);
        } catch (RuntimeException ex) {
            UltronDecisionLog.error(ctx.player, "FastPriorityPolicy", ex);
            return UltronRuntimeDecision.pass("exception in priority policy");
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            UltronDecisionLog.timing(ctx.player, UltronDecisionLog.PRIORITY, elapsedMs,
                    "priority-pass");
        }
    }

    private static UltronRuntimeDecision doChoose(UltronDecisionContext ctx) {
        // Step 1: no candidates → pass
        if (ctx.candidates.isEmpty()) {
            return UltronRuntimeDecision.pass("no candidates");
        }

        // Step 2: Ultron's own spell on top of stack → usually pass
        if (!ctx.stackEmpty && ctx.topStackControlledBySelf) {
            // Could still respond with copy / protection, but don't by default
            return UltronRuntimeDecision.pass("ultron controls stack top");
        }

        // Step 3: stack empty, not Ultron's turn
        if (ctx.stackEmpty && !ctx.isPlayerTurn) {
            // Allow end-step instant-speed action if it's the last end-step before Ultron's turn
            if (isOpponentEndStep(ctx) && hasInstantSpeedAction(ctx)) {
                return UltronRuntimeDecision.noDecision("end-step instant-speed action opportunity");
            }
            return UltronRuntimeDecision.pass("stack empty, opponent's turn");
        }

        // Step 4: stack empty, Ultron's turn → main-phase policy handles this
        if (ctx.stackEmpty && ctx.isPlayerTurn) {
            return UltronRuntimeDecision.noDecision("main phase — defer to action scorer");
        }

        // Step 5: stack not empty — classify the top threat
        UltronStackThreat threat = UltronStackThreatAnalyzer.classify(
                ctx.topStackAbility, ctx.player, ctx.table);

        UltronDecisionLog.log(ctx.player, UltronDecisionLog.STACK,
                "top=" + (ctx.topStackAbility != null ? ctx.topStackAbility.getHostCard().getName() : "?")
                        + " threat=" + threat);

        // Step 6: threat below threshold → pass
        if (!threat.isActionable(ctx.intent.interactionThreshold)) {
            return UltronRuntimeDecision.pass("threat below threshold: " + threat.type
                    + "(sev=" + threat.severity + ")");
        }

        // Step 7-9: consult interaction policy
        return UltronInteractionPolicy.chooseAnswer(ctx, threat);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** True if we're in an opponent's end step just before Ultron's turn. */
    private static boolean isOpponentEndStep(UltronDecisionContext ctx) {
        if (ctx.phase != PhaseType.END_OF_TURN) return false;
        // Check if the next turn is Ultron's — approximate: active player is an opponent
        return !ctx.activePlayer.equals(ctx.player);
    }

    /** True if any candidate is an instant-speed action worth taking at end step. */
    private static boolean hasInstantSpeedAction(UltronDecisionContext ctx) {
        for (SpellAbility sa : ctx.candidates) {
            forge.game.card.Card host = sa.getHostCard();
            if (host != null && (host.isInstant() || host.hasKeyword(forge.game.keyword.Keyword.FLASH))) {
                return true;
            }
        }
        return false;
    }
}
