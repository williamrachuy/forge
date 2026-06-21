package forge.ai.llm.runtime;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
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
     * @param sa          the candidate
     * @param ctx         current decision context
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

        // Base development score from CMC (proxy for card quality; scales with mana cost)
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
                int rawTargetScore = UltronTargetPriorityEvaluator.removalScore(targeted, table, ctx.player);
                int targetScore = (int)(rawTargetScore * UltronWeights.get(UltronWeights.REMOVAL_BONUS));
                leaderThreat += targetScore / 2;
                board += targetScore / 3;
                reason.append("removal target score=").append(targetScore);
            }
        }

        // Permanent deployment — board presence
        if (host.isPermanent()) {
            board += cmc * 2;

            if (host.isCreature()) {
                int power = Math.max(0, host.getNetPower());
                // Base power bonus
                board += (int)(power * 2 * UltronWeights.get(UltronWeights.AGGRESSION));
                // Evasive threats are worth extra — they attack profitably in multiplayer
                if (isEvasive(host)) {
                    board += 15;
                    reason.append(" evasive+15");
                } else if (host.hasKeyword(Keyword.DEATHTOUCH)) {
                    board += 8;
                    reason.append(" deathtouch+8");
                }
            } else {
                // Non-creature permanents: penalise heavy engines in fast roles
                // (equipment, artifacts, enchantments that provide no immediate pressure)
                boolean aggressiveRole = isAggressiveRole(intent.role);
                if (aggressiveRole) {
                    String rules = host.getOracleText().toLowerCase();
                    boolean isEngine = rules.contains("whenever") || rules.contains("at the beginning");
                    if (isEngine) {
                        board -= 15;
                        reason.append(" engine-in-fast-role");
                    } else if (cmc >= 3) {
                        // Expensive non-creature, non-engine permanents don't advance the board
                        board -= (cmc - 2) * 3;
                        reason.append(" slow-noncreature");
                    }
                }
            }
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
                leaderThreat += (int)(15 * UltronWeights.get(UltronWeights.AGGRESSION));
                reason.append(" lethal-seeker");
            }
        }

        // Card draw is extremely valuable when behind — it's how you claw back
        if (api == ApiType.Draw) {
            if (intent.role == UltronRuntimeRole.BEHIND || intent.role == UltronRuntimeRole.DESPERATE) {
                hand += 20;
                reason.append(" draw-when-behind");
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

        // Learned per-card win-rate adjustment (O(1) lookup, updated after each sim game)
        int learnedAdj = UltronCardStats.scoreAdjustment(host.getName());
        if (learnedAdj != 0) reason.append(" learned=").append(learnedAdj);

        int total = board + hand + life + defense - danger + leaderThreat + combo + balance + learnedAdj;

        UltronDecisionLog.logScore(sa, total, reason.toString());

        return new UltronScore(total, board, hand, life, defense, danger, leaderThreat,
                combo, balance, reason.toString());
    }

    /** Roles where tempo matters more than engine value. */
    private static boolean isAggressiveRole(UltronRuntimeRole role) {
        return role == UltronRuntimeRole.PRESSURING
                || role == UltronRuntimeRole.DESPERATE
                || role == UltronRuntimeRole.BEHIND;
    }

    /** True if the card can attack without being blocked by most ground creatures. */
    private static boolean isEvasive(Card card) {
        return card.hasKeyword(Keyword.FLYING)
                || card.hasKeyword(Keyword.SHADOW)
                || card.hasKeyword(Keyword.HORSEMANSHIP)
                || card.hasKeyword(Keyword.FEAR)
                || card.hasKeyword(Keyword.INTIMIDATE)
                || card.hasKeyword(Keyword.MENACE)
                || card.getOracleText().toLowerCase().contains("can't be blocked");
    }

    private static boolean isHelpingLeader(SpellAbility sa, UltronTableThreatSummary table) {
        if (table.leader == null) return false;
        String defined = sa.getParam("Defined");
        if (defined == null) return false;
        return defined.contains("AllPlayers") || defined.contains("Each");
    }
}
