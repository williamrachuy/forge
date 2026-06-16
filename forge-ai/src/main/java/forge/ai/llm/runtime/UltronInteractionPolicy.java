package forge.ai.llm.runtime;

import forge.game.ability.ApiType;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * Decides whether Ultron should spend interaction (counterspell, removal,
 * protection) in response to a classified stack threat.
 *
 * <p>Policy rules (per plan §Phase 9):
 * <ul>
 *   <li>DO counter: lethal, game-winning, board wipe while ahead, extra turn from leader,
 *       mass reanimation from graveyard-heavy player, tutor from combo/leader,
 *       spell removing Ultron's win condition.</li>
 *   <li>DO NOT counter: ramp, cantrip, small creature, minor draw, weak player's low-impact
 *       value spell, spell that hurts the leader more than Ultron.</li>
 * </ul>
 */
public final class UltronInteractionPolicy {

    private UltronInteractionPolicy() {}

    /**
     * Choose the best answer from candidates for the given threat.
     *
     * @param ctx    current decision context
     * @param threat classified stack threat
     * @return decision to choose an answer, pass, or no-decision
     */
    public static UltronRuntimeDecision chooseAnswer(UltronDecisionContext ctx,
                                                      UltronStackThreat threat) {
        if (threat.severity < ctx.intent.interactionThreshold) {
            UltronDecisionLog.log(ctx.player, UltronDecisionLog.STACK,
                    "threat=" + threat.type + " sev=" + threat.severity
                            + " below threshold=" + ctx.intent.interactionThreshold + " -> PASS");
            return UltronRuntimeDecision.pass("threat below interaction threshold");
        }

        // Evaluate whether we should counter vs. remove vs. protect
        SpellAbility bestCounterspell = null;
        SpellAbility bestRemoval = null;
        int bestCounterScore = -1;
        int bestRemovalScore = -1;

        for (SpellAbility sa : ctx.candidates) {
            if (sa.getApi() == ApiType.Counter) {
                int score = scoreCounterspell(sa, threat, ctx);
                if (score > bestCounterScore) {
                    bestCounterScore = score;
                    bestCounterspell = sa;
                }
            } else if (isRemoval(sa.getApi())) {
                int score = scoreRemoval(sa, threat, ctx);
                if (score > bestRemovalScore) {
                    bestRemovalScore = score;
                    bestRemoval = sa;
                }
            }
        }

        // Decide based on threat type and available answers
        boolean shouldCounter = shouldCounter(threat, ctx);
        boolean shouldRemove  = shouldRemove(threat, ctx);

        if (shouldCounter && bestCounterspell != null && bestCounterScore >= 0) {
            String reason = "counter " + threat.type + "(sev=" + threat.severity + ")";
            UltronDecisionLog.log(ctx.player, UltronDecisionLog.STACK,
                    "decision=COUNTER sa=" + bestCounterspell.getHostCard().getName()
                            + " threat=" + threat.type);
            return UltronRuntimeDecision.choose(bestCounterspell, reason);
        }

        if (shouldRemove && bestRemoval != null && bestRemovalScore >= 0) {
            String reason = "remove for " + threat.type + "(sev=" + threat.severity + ")";
            UltronDecisionLog.log(ctx.player, UltronDecisionLog.STACK,
                    "decision=REMOVE sa=" + bestRemoval.getHostCard().getName()
                            + " threat=" + threat.type);
            return UltronRuntimeDecision.choose(bestRemoval, reason);
        }

        // No good answer found
        UltronDecisionLog.log(ctx.player, UltronDecisionLog.STACK,
                "no suitable answer for threat=" + threat.type + " sev=" + threat.severity + " -> PASS");
        return UltronRuntimeDecision.pass("no suitable answer for " + threat.type);
    }

    // -----------------------------------------------------------------------
    // Policy decisions
    // -----------------------------------------------------------------------

    private static boolean shouldCounter(UltronStackThreat threat, UltronDecisionContext ctx) {
        UltronTurnIntent intent = ctx.intent;
        int sev = threat.severity;

        return switch (threat.type) {
            case LETHAL_DAMAGE, LETHAL_LIFE_LOSS, GAME_WINNING_EFFECT -> true;
            case EXTRA_TURN -> sev >= intent.counterspellThreshold;
            case BOARD_WIPE -> ctx.table.ultronIsAhead && sev >= intent.counterspellThreshold;
            case MASS_REANIMATION -> sev >= intent.counterspellThreshold;
            case COMBO_PIECE, TUTOR ->
                    sev >= intent.counterspellThreshold && isFromLeaderOrComboPlayer(threat, ctx);
            case VALUE_ENGINE ->
                    sev >= intent.counterspellThreshold && isFromLeaderOrComboPlayer(threat, ctx);
            case REMOVAL_TARGETING_KEY_PERMANENT -> sev >= intent.counterspellThreshold;
            default -> sev >= 90; // only counter extremely impactful unknowns
        };
    }

    private static boolean shouldRemove(UltronStackThreat threat, UltronDecisionContext ctx) {
        int sev = threat.severity;
        return switch (threat.type) {
            case REMOVAL_TARGETING_ULTRON, REMOVAL_TARGETING_KEY_PERMANENT ->
                    sev >= ctx.intent.removalThreshold;
            case LETHAL_DAMAGE, LETHAL_LIFE_LOSS -> true;
            default -> false;
        };
    }

    private static boolean isFromLeaderOrComboPlayer(UltronStackThreat threat,
                                                       UltronDecisionContext ctx) {
        if (threat.controller == null) return false;
        UltronOpponentProfile profile = ctx.table.profileFor(threat.controller);
        return profile != null && (profile.isLeader || profile.comboThreat >= 50);
    }

    // -----------------------------------------------------------------------
    // Scoring helpers
    // -----------------------------------------------------------------------

    private static int scoreCounterspell(SpellAbility sa, UltronStackThreat threat,
                                          UltronDecisionContext ctx) {
        int score = threat.severity;
        // Prefer cheap counterspells
        int cmc = sa.getHostCard().getCMC();
        score -= cmc * 2;
        return score;
    }

    private static int scoreRemoval(SpellAbility sa, UltronStackThreat threat,
                                     UltronDecisionContext ctx) {
        int score = threat.severity - 20; // removal is less flexible than counterspell
        int cmc = sa.getHostCard().getCMC();
        score -= cmc;
        return score;
    }

    private static boolean isRemoval(ApiType api) {
        return api == ApiType.Destroy || api == ApiType.ChangeZone || api == ApiType.ChangeZone;
    }
}
