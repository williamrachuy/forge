package forge.ai.llm.runtime;

import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lightweight game-state evaluator for Ultron's position.
 * Returns a numeric score estimating Ultron's relative position (higher = better).
 * No simulation, no LLM.
 */
public final class UltronGameStateEvaluator {

    private UltronGameStateEvaluator() {}

    /**
     * Evaluate Ultron's current position.
     *
     * @param table current table summary
     * @return score in arbitrary units; higher is better for Ultron
     */
    public static int evaluate(UltronTableThreatSummary table) {
        if (table == null) return 0;

        int score = 0;

        // Ultron's resources
        score += table.ultronLife * 2;
        score += table.ultronHandSize * 4;
        score += table.ultronBoardValue * 2;

        // Penalise danger
        if (table.ultronInDanger) score -= 50;

        // Bonus for being ahead
        if (table.ultronIsAhead)  score += 30;
        if (table.ultronIsBehind) score -= 20;

        // Penalise strong opponents
        for (UltronOpponentProfile opp : table.opponents) {
            score -= opp.boardValue / 2;
            if (opp.isLeader) score -= 20;
        }

        return score;
    }

    /**
     * Rough estimate of how much a given card name / CMC improves Ultron's score.
     * Used by {@link UltronActionScorer} to add a development bonus.
     */
    public static int developmentBonus(String cardName, int cmc) {
        // Simple CMC-based proxy — ramp more in early turns, value later
        return cmc * 5;
    }
}
