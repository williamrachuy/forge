package forge.ai.nn;

import com.google.common.collect.Lists;
import forge.ai.AIOption;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.SharedPlayerZone;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TICKET-V4-010 (Ultron v4 Phase 2, P2.4): smoke coverage for {@link NeuralStateEvaluator} wired
 * against a real trained model (TICKET-V4-009's artifact) on a real 4-player Battlebox-shaped
 * state, following the fixture convention established by {@code GameCopierBattleboxFidelityTest}
 * and {@code UltronMainPhaseSimulationTest}.
 *
 * <p>Uses {@link NeuralStateEvaluator}'s test-only {@code NeuralStateEvaluator(UltronValueNet)}
 * constructor rather than mutating {@code ULTRON_NN_MODEL_PATH}/{@code ULTRON_NN_EVAL} process
 * environment variables mid-JVM -- {@code UltronConfig} intentionally reads {@code System.getenv}
 * directly at call time (see its class javadoc), and there is no supported way to change that from
 * inside a running test. The "flag off => heuristic path unchanged" half of this ticket's
 * contract is covered separately by {@code SpellAbilityPickerEvaluatorSelectionTest}, which does
 * not need env mutation because the default (unset) environment already exercises that branch.
 */
public class NeuralStateEvaluatorTest extends AITest {

    private static final String MODEL_RELATIVE_PATH = "tools/nn/runs/20260724-195756/model.bin";
    private static final int NUM_PLAYERS = 4;

    /**
     * Surefire's working directory is the executing Maven module (e.g. {@code forge-gui-desktop/}),
     * not the repo root, so a plain relative path misses. Walk up from {@code user.dir} looking for
     * {@link #MODEL_RELATIVE_PATH} instead of hardcoding an absolute path (which would only work on
     * one machine/checkout).
     */
    private static Path resolveModelPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(MODEL_RELATIVE_PATH);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return Path.of(MODEL_RELATIVE_PATH).toAbsolutePath();
    }

    private static UltronValueNet loadRealModel() {
        Path modelPath = resolveModelPath();
        if (!Files.exists(modelPath)) {
            throw new SkipException("Trained model artifact not present at " + modelPath
                    + " -- skipping (see TICKET-V4-009)");
        }
        try {
            return UltronValueNet.load(modelPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load real model at " + modelPath, e);
        }
    }

    /** Minimal local stand-in so this file doesn't need a TestNG-version-specific SkipException import. */
    private static class SkipException extends RuntimeException {
        SkipException(String msg) {
            super(msg);
        }
    }

    private Game createBattleboxGame() {
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < NUM_PLAYERS; i++) {
            Deck d = new Deck();
            Set<AIOption> options = new HashSet<>();
            LobbyPlayerAi lp = new LobbyPlayerAi("p" + i, options);
            if (i == 0) {
                lp.setAiProfile("Ultron");
            }
            players.add(new RegisteredPlayer(d).setPlayer(lp));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.Battlebox);
        Match match = new Match(rules, players, "NeuralStateEvaluatorTest");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;

        Player host = game.getPlayers().get(0);
        SharedPlayerZone sharedLibrary = new SharedPlayerZone(ZoneType.Library, host);
        SharedPlayerZone sharedCommand = new SharedPlayerZone(ZoneType.Command, host);
        SharedPlayerZone sharedGraveyard = new SharedPlayerZone(ZoneType.Graveyard, host);
        for (Player p : game.getPlayers()) {
            sharedLibrary.addPlayer(p);
            sharedCommand.addPlayer(p);
            sharedGraveyard.addPlayer(p);
            p.setSharedLibraryZone(sharedLibrary);
            p.setSharedCommandZone(sharedCommand);
            p.setSharedGraveyardZone(sharedGraveyard);
        }

        game.getPlayers().get(0).setLife(20, null);
        game.getPlayers().get(1).setLife(15, null);
        game.getPlayers().get(2).setLife(9, null);
        game.getPlayers().get(3).setLife(18, null);
        game.setBattleboxMonarchChoice(true);
        game.setMonarch(game.getPlayers().get(2));

        return game;
    }

    private void setMainPhase(Game game, Player active) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, active);
        game.getPhaseHandler().onStackResolved();
        game.getAction().checkStateEffects(true);
    }

    @Test
    public void producesLegalScoreOnRealBattleboxState() {
        initAndCreateGame();
        UltronValueNet net;
        try {
            net = loadRealModel();
        } catch (SkipException e) {
            System.err.println("SKIPPED: " + e.getMessage());
            return;
        }

        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);
        addCard("Grizzly Bears", ultron);
        Card oppCreature = addCard("Runeclaw Bear", game.getPlayers().get(1));
        oppCreature.setSickness(false);
        setMainPhase(game, ultron);

        NeuralStateEvaluator evaluator = new NeuralStateEvaluator(net);
        Score score = evaluator.getScoreForGameState(game, ultron);

        Assert.assertNotNull(score);
        Assert.assertTrue(score.value > Integer.MIN_VALUE && score.value < Integer.MAX_VALUE,
                "Non-terminal state must not produce the terminal short-circuit sentinel values, got " + score.value);
        Assert.assertTrue(score.value >= 0 && score.value <= 100_000,
                "value must be a rounded win-probability-times-100000 in [0, 100000], got " + score.value);
        Assert.assertTrue(score.summonSickValue >= 0 && score.summonSickValue <= 100_000,
                "summonSickValue must also be in [0, 100000], got " + score.summonSickValue);
    }

    @Test
    public void summonSickMaskingChangesTheEncodedVectorWhenBoardHasSummonSickCreatures() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);

        // Freshly-added creature defaults to summon sick.
        Card sickBear = addCard("Grizzly Bears", ultron);
        Assert.assertTrue(sickBear.isSick(), "Fixture bug: freshly-added creature must be summon sick");
        setMainPhase(game, ultron);

        float[] unmasked = UltronStateEncoder.encode(game, ultron, false);
        float[] masked = UltronStateEncoder.encode(game, ultron, true);

        Assert.assertEquals(unmasked.length, masked.length, "Masking must not change the vector schema/length");
        Assert.assertFalse(java.util.Arrays.equals(unmasked, masked),
                "Masking the only creature on the board out of self battlefield pooling must change the encoded vector");
    }

    @Test
    public void summonSickMaskIsPhaseConditionalNotUnconditional() {
        // TICKET-V4-015 regression pin. The V4-010 implementation masked summon-sick creatures
        // out of the summonSickValue pass UNCONDITIONALLY; combined with SpellAbilityPicker's
        // `bestSaValue.summonSickValue <= origGameScore.summonSickValue -> bestSa = null` gate,
        // that made "cast a creature" lose to "pass" at EVERY phase (masked afterstate = hand card
        // spent, creature invisible), producing the fully passive 0/12 loss streak of the
        // TICKET-V4-014 15-game run. The fix mirrors GameStateEvaluator's semantics exactly: mask
        // only when the phase is before MAIN2. This test pins both halves:
        //   MAIN1  -> masked pass differs from the plain value (deferral signal active);
        //   MAIN2  -> summonSickValue == value (sick creatures count fully; a fresh creature on
        //             the battlefield must never be scored as invisible in second main).
        initAndCreateGame();
        UltronValueNet net;
        try {
            net = loadRealModel();
        } catch (SkipException e) {
            System.err.println("SKIPPED: " + e.getMessage());
            return;
        }

        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);
        Card sickBear = addCard("Grizzly Bears", ultron);
        Assert.assertTrue(sickBear.isSick(), "Fixture bug: freshly-added creature must be summon sick");
        NeuralStateEvaluator evaluator = new NeuralStateEvaluator(net);

        setMainPhase(game, ultron); // MAIN1
        Score main1 = evaluator.getScoreForGameState(game, ultron);
        Assert.assertNotEquals(main1.summonSickValue, main1.value,
                "MAIN1 with a summon-sick creature: the masked summonSickValue pass must actually "
                + "differ from the unmasked value (mask active before MAIN2)");

        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ultron);
        game.getPhaseHandler().onStackResolved();
        game.getAction().checkStateEffects(true);
        Score main2 = evaluator.getScoreForGameState(game, ultron);
        Assert.assertEquals(main2.summonSickValue, main2.value,
                "MAIN2 with a summon-sick creature: summonSickValue must equal value (mask must NOT "
                + "apply at/after MAIN2 -- unconditional masking is the exact bug that made Ultron "
                + "never cast creatures, TICKET-V4-014/015)");
    }

    @Test
    public void summonSickMaskingIsANoOpWhenNoSummonSickCreaturesArePresent() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);

        Card bear = addCard("Grizzly Bears", ultron);
        bear.setSickness(false);
        setMainPhase(game, ultron);

        float[] unmasked = UltronStateEncoder.encode(game, ultron, false);
        float[] masked = UltronStateEncoder.encode(game, ultron, true);

        Assert.assertTrue(java.util.Arrays.equals(unmasked, masked),
                "With no summon-sick self creatures on the board, masking must be a byte-identical no-op");
    }

    @Test
    public void terminalStateShortCircuitsExactlyLikeGameStateEvaluator() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);
        Player opp = game.getPlayers().get(1);

        for (Player p : game.getPlayers()) {
            if (!p.equals(ultron)) {
                p.setLife(0, null);
            }
        }
        game.getAction().checkStateEffects(true);
        Assert.assertTrue(game.isGameOver(), "Fixture bug: game must be over after every opponent hits 0 life");

        // A NeuralStateEvaluator must handle the terminal short-circuit correctly regardless of
        // model state -- it is resolved before any forward pass / model load is attempted.
        NeuralStateEvaluator evaluator = new NeuralStateEvaluator();
        Score score = evaluator.getScoreForGameState(game, ultron);
        Assert.assertEquals(score.value, Integer.MAX_VALUE, "Ultron is the winner -- must get the MAX_VALUE sentinel");

        Score oppScore = evaluator.getScoreForGameState(game, opp);
        Assert.assertEquals(oppScore.value, Integer.MIN_VALUE, "A losing player must get the MIN_VALUE sentinel");
    }
}
