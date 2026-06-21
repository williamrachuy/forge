package forge.ai.llm.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Post-game adaptive weight update for Ultron.
 *
 * <p>After each completed game, this class computes per-weight "activation" scores
 * from the game's {@link UltronSimStats}, combines them with a win/loss signal,
 * and applies small nudges to {@link UltronWeights}. The updated weights are
 * immediately persisted so the next game picks them up.
 *
 * <p>Learning rate is intentionally small (5%) so the system converges slowly
 * and doesn't over-fit to any single game. Weights are clamped to [0.2, 5.0]
 * regardless, so runaway drift is impossible.
 *
 * <p>This is only called when {@code sim.adaptiveWeights=true} in the run config.
 * Disabling the flag (or deleting the weights file) reverts Ultron to baseline.
 */
public final class UltronAdaptiveLearner {

    /** Nudge magnitude per game as a fraction of the multiplier range. */
    private static final double LEARNING_RATE = 0.05;

    /** Signal when Ultron wins (above 1/4 baseline). */
    private static final double WIN_SIGNAL  =  0.75;

    /** Signal when Ultron loses (any placement 2-4). */
    private static final double LOSS_SIGNAL = -0.25;

    private UltronAdaptiveLearner() {}

    /**
     * Update weights based on this game's outcome and decision profile.
     *
     * @param stats       Ultron's decision stats for this game
     * @param ultronWon   true if Ultron was the winning player
     * @param weightsPath file to read/update (the mutable override file)
     */
    public static void update(UltronSimStats stats, boolean ultronWon, Path weightsPath) {
        UltronSimStats.WeightActivations act = stats.computeActivations();
        double signal = ultronWon ? WIN_SIGNAL : LOSS_SIGNAL;

        // Removal bonus: nudge proportional to how often Ultron made removal decisions.
        // If removal decisions are frequent in winning games → increase; in losses → decrease.
        double removalNudge = LEARNING_RATE * signal * act.removalActivation();
        double prevRemoval  = UltronWeights.get(UltronWeights.REMOVAL_BONUS);
        double newRemoval   = UltronWeights.nudge(UltronWeights.REMOVAL_BONUS, removalNudge);

        // Aggression: nudge proportional to main-phase choice rate.
        // High main-phase play in winning games → reinforce aggression.
        double aggrNudge    = LEARNING_RATE * signal * act.aggressionActivation();
        double prevAggr     = UltronWeights.get(UltronWeights.AGGRESSION);
        double newAggr      = UltronWeights.nudge(UltronWeights.AGGRESSION, aggrNudge);

        // Prune aggression: nudge proportional to mean prune rate.
        // High pruning in winning games → reinforce aggressive pruning.
        double pruneNudge   = LEARNING_RATE * signal * act.pruneActivation();
        double prevPrune    = UltronWeights.get(UltronWeights.PRUNE_AGGRESSION);
        double newPrune     = UltronWeights.nudge(UltronWeights.PRUNE_AGGRESSION, pruneNudge);

        System.out.printf(
                "[ULTRON-ADAPTIVE] outcome=%s signal=%.2f  "
                + "removal %.4f→%.4f (Δ%.4f)  "
                + "aggression %.4f→%.4f (Δ%.4f)  "
                + "prune %.4f→%.4f (Δ%.4f)%n",
                ultronWon ? "WIN" : "LOSS", signal,
                prevRemoval,  newRemoval,  removalNudge,
                prevAggr,     newAggr,     aggrNudge,
                prevPrune,    newPrune,    pruneNudge);

        UltronWeights.save(weightsPath);

        // Per-card win-rate tracking — discovered knowledge replaces manual rules
        List<String> cardsPlayed = stats.cardsPlayed();
        if (!cardsPlayed.isEmpty()) {
            UltronCardStats.record(cardsPlayed, ultronWon);
            Path cardStatsPath = weightsPath.resolveSibling("ultron_card_stats.json");
            UltronCardStats.save(cardStatsPath);
        }
    }

    /** Log current weights and top learned card stats. */
    public static void logCurrentWeights() {
        Map<String, Double> all = UltronWeights.all();
        if (all.isEmpty()) {
            System.out.println("[ULTRON-ADAPTIVE] All weights at baseline (1.0)");
        } else {
            System.out.println("[ULTRON-ADAPTIVE] Current weight overrides:");
            all.forEach((k, v) -> System.out.printf("  %-24s = %.6f%n", k, v));
        }
        UltronCardStats.logTopCards(20);
    }

    /** Load card stats from the sibling file next to the weights file. */
    public static void loadCardStats(Path weightsPath) {
        if (weightsPath == null) return;
        Path cardStatsPath = weightsPath.resolveSibling("ultron_card_stats.json");
        UltronCardStats.load(cardStatsPath);
    }
}
