package forge.ai.llm.runtime;

import forge.game.card.Card;
import forge.game.player.Player;

/**
 * Scores potential targets for Ultron's removal, protection, and beneficial effects.
 * Higher score = higher priority target for removal; lower = lower priority.
 *
 * <p>Scoring guidelines (per plan §Phase 12):
 * <ul>
 *   <li>Highest: visible combo piece, engine controlled by leader, lethal attacker</li>
 *   <li>Medium: efficient threat from non-leader, blocker preventing Ultron lethal</li>
 *   <li>Low: weak player's irrelevant creature, token, creature restraining leader</li>
 * </ul>
 */
public final class UltronTargetPriorityEvaluator {

    private UltronTargetPriorityEvaluator() {}

    /**
     * Score a card as a removal target (higher = remove this).
     *
     * @param target  the card being targeted
     * @param table   current table summary (may be null)
     * @param ultron  Ultron player
     */
    public static int removalScore(Card target, UltronTableThreatSummary table, Player ultron) {
        if (target == null) return 0;

        int score = 0;
        Player controller = target.getController();

        // Base: CMC + power
        score += target.getCMC() * 5 + Math.max(0, target.getNetPower()) * 3;

        // Bonuses for dangerous controllers
        if (table != null) {
            UltronOpponentProfile profile = table.profileFor(controller);
            if (profile != null) {
                if (profile.isLeader)              score += 30;
                if (profile.comboThreat >= 70)     score += 25;
                if (profile.canLikelyKillUltronSoon) score += 40;
            }
        }

        // Non-creature permanents (engines) are high priority
        if (!target.isCreature()) {
            score += 20;
            // Artifacts and enchantments are likely engines
            if (target.isArtifact() || target.isEnchantment()) score += 10;
        }

        // Planeswalkers near ultimate are very dangerous
        if (target.isPlaneswalker() && target.getCounters(
                forge.game.card.CounterEnumType.LOYALTY) >= 7) {
            score += 25;
        }

        // Tokens are low priority
        if (target.isToken()) score -= 30;

        // Creature targeting Ultron = high priority
        if (target.getGame().getCombat() != null && target.isAttacking() && target.getController() != ultron) score += 20;

        // Creature that is restraining the leader (don't remove — helps leader)
        if (table != null && table.leader != null) {
            boolean attackingLeader = false;
            for (var ga : target.getGame().getCombat() != null
                    ? target.getGame().getCombat().getAttackers() : java.util.List.<Card>of()) {
                if (ga.equals(target)) { attackingLeader = true; break; }
            }
            if (attackingLeader && target.getController().isOpponentOf(table.leader.player)) {
                score -= 20; // this creature is pressuring the leader — don't remove it
            }
        }

        return Math.max(0, score);
    }
}
