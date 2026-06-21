package forge.ai.llm.runtime;

import forge.game.Game;
import forge.game.player.Player;

/**
 * Entry point for building the full Ultron table threat model.
 * Delegates to {@link UltronTableThreatSummary} for per-opponent analysis.
 */
public final class UltronThreatModel {

    private UltronThreatModel() {}

    /**
     * Build a complete table threat summary for the given Ultron player.
     * Fast — inspects Forge objects directly, no LLM, no large serialization.
     */
    public static UltronTableThreatSummary analyze(Game game, Player ultron) {
        return UltronTableThreatSummary.analyze(game, ultron);
    }
}
