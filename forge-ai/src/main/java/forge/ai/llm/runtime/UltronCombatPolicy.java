package forge.ai.llm.runtime;

import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;

import java.util.List;

/**
 * Multiplayer-aware combat policy for Ultron.
 *
 * <p>Decision priorities (per plan §Phase 13):
 * <ol>
 *   <li>Kill or nearly-kill a vulnerable player if feasible</li>
 *   <li>Attack the leader when profitable and safe</li>
 *   <li>Preserve blockers if crackback could kill Ultron</li>
 *   <li>Do not attack weak player when that only helps the leader</li>
 *   <li>Do not make suicidal attacks</li>
 * </ol>
 *
 * <p>Note: This does not fully rewrite combat. It provides scoring and filtering
 * that integrates with AiAttackController via {@code UltronAdvisor.filterPlannedAttackers}.
 */
public final class UltronCombatPolicy {

    private UltronCombatPolicy() {}

    /**
     * Score a potential attack on a given player.
     * Higher = more desirable attack target.
     *
     * @param attacker       the attacking creature
     * @param target         the player being attacked
     * @param intent         current turn intent
     * @param table          table summary
     * @param ultronLife     Ultron's current life total
     * @param crackbackRisk  estimated damage coming back at Ultron if we attack
     */
    public static int scoreAttack(Card attacker, Player target, UltronTurnIntent intent,
                                   UltronTableThreatSummary table, int ultronLife,
                                   int crackbackRisk) {
        if (attacker == null || target == null) return 0;

        int score = 0;
        UltronOpponentProfile profile = table.profileFor(target);

        if (profile == null) return 5; // unknown opponent, mild default

        int attackerPower = Math.max(0, attacker.getNetPower());

        // Lethal opportunity: prioritize finishing a vulnerable player
        if (profile.life <= attackerPower && intent.lookForLethal) {
            score += 80;
        } else if (profile.vulnerability >= 70) {
            score += 40;
        }

        // Attacking the leader is generally good
        if (profile.isLeader) score += 30;

        // Don't attack a player who will die and only benefit the leader
        if (profile.vulnerability >= 80 && table.leader != null
                && !target.equals(table.leader.player)) {
            // Finishing this player may help the leader
            score -= 15;
        }

        // Crackback risk: if we attack and get hit back for lethal, abort
        if (crackbackRisk >= ultronLife) {
            score -= 100;
        } else if (crackbackRisk >= ultronLife * 2 / 3) {
            score -= 50;
        }

        // Avoid attacking if Ultron is in danger and needs blockers
        if (table.ultronInDanger) {
            score -= 30;
        }

        return score;
    }

    /**
     * Filter out attackers Ultron's runtime policy considers suboptimal.
     * Modifies the combat object in place (removes attackers below threshold).
     *
     * @param combat current combat
     * @param table  current table summary
     * @param intent current turn intent
     */
    public static void filterAttackers(Combat combat, UltronTableThreatSummary table,
                                        UltronTurnIntent intent) {
        if (combat == null || table == null) return;

        int ultronLife = table.ultronLife;

        // Estimate total crackback from all dangerous opponents
        int crackbackRisk = 0;
        for (UltronOpponentProfile opp : table.opponents) {
            crackbackRisk += opp.evasivePower;
        }

        List<Card> toRemove = new java.util.ArrayList<>();
        for (Card attacker : combat.getAttackers()) {
            Player target = combat.getDefendingPlayerRelatedTo(attacker);
            if (target == null) continue;
            int attackScore = scoreAttack(attacker, target, intent, table, ultronLife, crackbackRisk);
            if (attackScore < -20) {
                toRemove.add(attacker);
            }
        }

        for (Card attacker : toRemove) {
            combat.removeFromCombat(attacker);
            UltronDecisionLog.log(table.ultron, UltronDecisionLog.COMBAT,
                    "removed attacker=" + attacker.getName() + " (low attack score)");
        }
    }
}
