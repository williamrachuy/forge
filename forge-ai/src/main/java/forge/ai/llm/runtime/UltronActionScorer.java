package forge.ai.llm.runtime;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/**
 * Scores main-phase candidate spell abilities with multiplayer context.
 *
 * <p>High score: develops board while preserving interaction, removes leader's engine,
 * stabilizes against lethal, creates repeatable value, advances lethal pressure.
 * <p>Low score: taps out while ahead with counterspell available, spends premium removal on
 * weak player, overextends into likely wipe, helps leader indirectly.
 */
public final class UltronActionScorer {

    private UltronActionScorer() {}

    /**
     * Score a candidate SpellAbility for main-phase selection.
     *
     * @param sa      the candidate
     * @param ctx     current decision context
     * @param reservation mana reserved for interaction
     * @return scoring result
     */
    public static UltronScore score(SpellAbility sa, UltronDecisionContext ctx,
                                     UltronManaReservation reservation) {
        if (sa == null) return UltronScore.ZERO;

        Card host = sa.getHostCard();
        if (host == null) return UltronScore.ZERO;

        UltronTurnIntent intent = ctx.intent;
        UltronTableThreatSummary table = ctx.table;

        int board = 0, hand = 0, life = 0, defense = 0;
        int danger = 0, leaderThreat = 0, combo = 0, balance = 0;
        StringBuilder reason = new StringBuilder();

        int cmc = host.getCMC();
        ApiType api = sa.getApi();

        // Base development score from CMC
        board += UltronGameStateEvaluator.developmentBonus(host.getName(), cmc);

        // Interaction candidates score lower in main phase (should save for stack)
        if (api == ApiType.Counter) {
            if (intent.reserveCounterspellMana && !ctx.stackEmpty) {
                board -= 40;
                reason.append("penalize: counterspell in main with stack active");
            } else if (intent.reserveCounterspellMana && ctx.stackEmpty) {
                board -= 25;
                reason.append("penalize: counterspell in main while reserving mana");
            }
        }

        // Removal scoring — depends on targets and intent
        if (api == ApiType.Destroy || api == ApiType.ChangeZone) {
            Card targeted = sa.getTargets() != null ? sa.getTargets().getFirstTargetedCard() : null;
            if (targeted != null) {
                int targetScore = UltronTargetPriorityEvaluator.removalScore(targeted, table, ctx.player);
                leaderThreat += targetScore / 2;
                board += targetScore / 3;
                reason.append("removal target score=").append(targetScore);
            }
        }

        // Permanent deployment — add board presence
        if (host.isPermanent()) {
            board += cmc * 2;
            if (host.isCreature()) board += Math.max(0, host.getNetPower()) * 2;
        }

        // Avoid tapping out when we should hold interaction
        if (intent.avoidTappingOut) {
            int availableMana = table.ultronOpenManaEstimate;
            int reservedMana  = reservation.total();
            if (cmc >= availableMana - reservedMana) {
                danger += 20;
                reason.append(" tap-out-risk");
            }
        }

        // Bonus for advancing lethal
        if (intent.lookForLethal) {
            if (api == ApiType.DamageAll || api == ApiType.DealDamage || api == ApiType.Draw) {
                leaderThreat += 15;
                reason.append(" lethal-seeker");
            }
        }

        // Board-wipe avoidance — don't overextend into likely wipe
        if (intent.role == UltronRuntimeRole.BEHIND || intent.role == UltronRuntimeRole.STABILIZING) {
            if (host.isCreature() && table.opponents.stream().anyMatch(p -> p.boardValue > table.ultronBoardValue)) {
                defense += 5;
            }
        }

        // Combo threat reduction bonus
        if (table.opponents.stream().anyMatch(p -> p.comboThreat >= 50)) {
            if (api == ApiType.Destroy || api == ApiType.ChangeZone || api == ApiType.Counter) {
                combo += 15;
            }
        }

        // Penalty: helping the leader
        if (isHelpingLeader(sa, table)) {
            leaderThreat -= 25;
            reason.append(" helps-leader-penalty");
        }

        int total = board + hand + life + defense - danger + leaderThreat + combo + balance;

        UltronDecisionLog.logScore(sa, total, reason.toString());

        return new UltronScore(total, board, hand, life, defense, danger, leaderThreat,
                combo, balance, reason.toString());
    }

    private static boolean isHelpingLeader(SpellAbility sa, UltronTableThreatSummary table) {
        if (table.leader == null) return false;
        // Rough check: does this ability's effect benefit the leader?
        // A GainLife for all, or a ramp, or similar — too complex to detect precisely.
        // Keep simple: beneficial effect with "Each player" defined that helps everyone.
        String defined = sa.getParam("Defined");
        if (defined == null) return false;
        return defined.contains("AllPlayers") || defined.contains("Each");
    }
}
