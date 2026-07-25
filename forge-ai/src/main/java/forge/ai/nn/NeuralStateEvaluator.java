package forge.ai.nn;

import forge.ai.llm.UltronConfig;
import forge.ai.simulation.GameStateEvaluator;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.ai.simulation.StateEvaluator;
import forge.game.Game;
import forge.game.player.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TICKET-V4-010 (Ultron v4 Phase 2, P2.4): learned {@link StateEvaluator}, wiring the trained
 * {@link UltronValueNet} into simulation search in place of {@link GameStateEvaluator}'s hand-tuned
 * heuristic. See {@code ULTRON_V4_NEURAL_PLAN.md} sect. 4.4.
 *
 * <p><b>Package placement.</b> Lives in {@code forge.ai.nn} (not {@code forge.ai.simulation})
 * because every dependency it has -- {@link UltronStateEncoder}, {@link UltronValueNet}, {@link
 * UltronCardFeatureTable} -- already lives here; the only thing it needs from {@code
 * forge.ai.simulation} is the {@link StateEvaluator} contract and the {@link Score} value type it
 * returns, a single clean dependency direction (nn depends on simulation's types, not the reverse).
 * This keeps {@code forge.ai.simulation} itself free of any neural-net-specific imports, so a
 * reader of that package never needs to know the learned evaluator exists unless they follow the
 * {@link StateEvaluator} interface out to an implementation.
 *
 * <p><b>No GameCopier, no combat simulation.</b> Unlike {@link GameStateEvaluator#getScoreForGameState},
 * this class encodes the state exactly as handed to it and never calls {@code
 * simulateUpcomingCombatThisTurn} or constructs a {@code GameCopier} -- that copy+combat-sim inner
 * layer is precisely what the learned evaluator replaces (plan sect. 1, Claim 3). Terminal states
 * keep the identical {@code MAX_VALUE}/{@code MIN_VALUE} short-circuit as the heuristic evaluator
 * (copied verbatim from {@code GameStateEvaluator.getScoreForGameOver}), since a finished game
 * needs no forward pass.
 *
 * <p><b>Model loading.</b> Loaded once, lazily, from {@link UltronConfig#nnModelPath()}. {@link
 * UltronValueNet#load} already refuses to load a model whose schema hash / semantic version does
 * not match the running {@link UltronStateEncoder}; any load failure (missing file, IO error,
 * schema mismatch) is caught here, logged, and surfaces as {@link #isAvailable()} returning false
 * -- never a crash. Callers (currently only {@code SpellAbilityPicker}) are expected to check
 * {@link #isAvailable()} before selecting this evaluator over the heuristic one; {@link
 * #getScoreForGameState} additionally falls back to a fresh {@link GameStateEvaluator} on its own
 * if it is ever invoked with no model loaded, as a second line of defense against ever crashing a
 * decision.
 */
public final class NeuralStateEvaluator implements StateEvaluator {

    private static final Logger LOG = Logger.getLogger(NeuralStateEvaluator.class.getName());

    private static volatile boolean loadAttempted = false;
    private static volatile UltronValueNet model = null;

    /** Instance-level override for {@link #explicitModel}; null means "use the static holder". */
    private final UltronValueNet explicitModel;

    /** Production constructor: uses the lazily-loaded static model (see {@link #isAvailable()}). */
    public NeuralStateEvaluator() {
        this.explicitModel = null;
    }

    /**
     * Test/tooling constructor: uses {@code net} directly instead of the static, env-driven
     * holder. Lets tests exercise a real trained model deterministically without mutating process
     * environment variables (there is no supported way to do that mid-JVM for {@link
     * UltronConfig}'s {@code System.getenv} reads, by design -- see that class's javadoc on reading
     * env at call time). Production code (see {@code SpellAbilityPicker.selectEvaluator}) always
     * uses the no-arg constructor.
     */
    public NeuralStateEvaluator(UltronValueNet net) {
        this.explicitModel = net;
    }

    private static synchronized void ensureLoadAttempted() {
        if (loadAttempted) {
            return;
        }
        loadAttempted = true;
        String path = UltronConfig.nnModelPath();
        if (path == null) {
            LOG.info("NeuralStateEvaluator: ULTRON_NN_MODEL_PATH not set -- neural eval unavailable, "
                    + "callers will fall back to the heuristic evaluator.");
            return;
        }
        try {
            model = UltronValueNet.load(Path.of(path));
            LOG.info("NeuralStateEvaluator: loaded model from " + path
                    + " (schema " + Long.toHexString(model.getSchemaHash())
                    + ", semver " + model.getSemanticVersion()
                    + ", inputDim " + model.getInputDim() + ")");
        } catch (IOException | RuntimeException e) {
            // Covers file-not-found, corrupt file, and UltronValueNet's own schema/semver refusal
            // (which throws IOException) -- all of these must disable cleanly, never crash a game.
            LOG.log(Level.WARNING, "NeuralStateEvaluator: failed to load model at " + path
                    + " -- falling back to the heuristic evaluator.", e);
            model = null;
        }
    }

    /**
     * True if {@link UltronConfig#nnModelPath()} is set AND that model loaded successfully against
     * the running encoder's schema. Triggers the one-time load attempt if not already done. Safe
     * to call from any thread at any time.
     */
    public static boolean isAvailable() {
        ensureLoadAttempted();
        return model != null;
    }

    @Override
    public Score getScoreForGameState(Game game, Player aiPlayer) {
        if (game.isGameOver()) {
            return getScoreForGameOver(game, aiPlayer);
        }

        UltronValueNet net = explicitModel;
        if (net == null) {
            ensureLoadAttempted();
            net = model;
        }
        if (net == null) {
            // Defense in depth: a well-behaved caller checks isAvailable() before ever selecting
            // this evaluator, but if this is somehow reached anyway, degrade to the heuristic
            // rather than NPE mid-decision.
            return new GameStateEvaluator().getScoreForGameState(game, aiPlayer);
        }

        float[] input = UltronStateEncoder.encode(game, aiPlayer, false);
        int value = Math.round(winProbability(net, input, game, aiPlayer) * 100_000f);

        // Second forward pass with aiPlayer's own summon-sick creatures masked out of battlefield
        // pooling -- the neural-eval analogue of GameStateEvaluator's summonSickScore, which
        // SpellAbilityPicker.chooseSpellAbilityToPlayImpl uses to hold creatures in MAIN1 when they
        // give no benefit beyond just being cast. Two forward passes are still ~1000x cheaper than
        // the heuristic path's copy+combat-sim (plan sect. 4.4).
        float[] ssInput = UltronStateEncoder.encode(game, aiPlayer, true);
        int summonSickValue = Math.round(winProbability(net, ssInput, game, aiPlayer) * 100_000f);

        return new Score(value, summonSickValue);
    }

    /** Mirrors {@code GameStateEvaluator.getScoreForGameOver} exactly -- see that method. */
    private Score getScoreForGameOver(Game game, Player aiPlayer) {
        if (game.getOutcome().getWinningTeam() == aiPlayer.getTeam()
                || game.getOutcome().isWinner(aiPlayer.getRegisteredPlayer())) {
            return new Score(Integer.MAX_VALUE);
        }
        return new Score(Integer.MIN_VALUE);
    }

    /**
     * Forward pass -> softmax over (self, opp1, opp2, opp3) -> mask out probability mass on
     * eliminated seats -> renormalize -> return {@code p_win[self]}. Masking to living seats
     * matters because {@link UltronStateEncoder} zero-fills eliminated opponent blocks rather than
     * omitting them (the encoder's "1v1 == 4p with two seats eliminated" convention), so the raw
     * softmax can assign nonzero mass to a dead seat; leaving that mass in would understate every
     * living player's true (renormalized) win probability, most visibly in 1v1/3-alive states.
     */
    private static float winProbability(UltronValueNet net, float[] input, Game game, Player self) {
        float[] probs = net.forward(input);
        float rawSelfProb = probs[0];
        List<Player> seats = UltronStateEncoder.orderedRealOpponents(game, self);

        boolean[] alive = new boolean[UltronValueNet.NUM_VALUE_SLOTS];
        alive[0] = !self.hasLost();
        for (int i = 1; i < UltronValueNet.NUM_VALUE_SLOTS; i++) {
            Player opp = (i - 1) < seats.size() ? seats.get(i - 1) : null;
            alive[i] = opp != null && !opp.hasLost();
        }

        float sum = 0f;
        for (int i = 0; i < probs.length; i++) {
            if (!alive[i]) {
                probs[i] = 0f;
            }
            sum += probs[i];
        }
        if (sum <= 0f) {
            // Degenerate: self is (still, per the isGameOver() short-circuit above) alive, so this
            // should not happen in practice. Fall back to the raw (unmasked) self slot rather than
            // divide by zero.
            return rawSelfProb;
        }
        return probs[0] / sum;
    }
}
