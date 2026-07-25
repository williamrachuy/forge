package forge.ai.simulation;

import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.player.Player;

/**
 * TICKET-V4-010 (Ultron v4 Phase 2, P2.4): abstraction over "how do I score a game state for a
 * given player" so {@link GameSimulator} can be handed either the existing hand-tuned heuristic
 * ({@link GameStateEvaluator}) or a learned evaluator ({@code forge.ai.nn.NeuralStateEvaluator})
 * without any change to the simulation/candidate-comparison machinery that consumes the result.
 * See {@code ULTRON_V4_NEURAL_PLAN.md} sect. 4.4.
 *
 * <p>{@link GameStateEvaluator} implements this directly (no behavior change, purely a type
 * declaration) and remains the default for every non-Ultron AI profile and for every Ultron
 * decision when the neural evaluator is disabled or unavailable.
 */
public interface StateEvaluator {
    Score getScoreForGameState(Game game, Player aiPlayer);
}
