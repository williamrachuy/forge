package forge.ai.simulation;

import com.google.common.collect.Lists;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TICKET-V3-202 (Ultron v3 Phase 2, P2.3): multiplayer interim evaluator.
 *
 * Covers the three behaviors {@link GameStateEvaluator#getScoreForGameState} is supposed to have
 * after the fix: (a) a table with one clear "leader" opponent (life concentrated in one player)
 * scores differently -- and worse for the AI -- than an evenly-matched table with the same total
 * opponent life, proving the per-opponent weighting actually changes behavior rather than just
 * compiling in place of the old sum-then-average code; (b) the AI holding the Monarch scores
 * higher than an otherwise-identical state where an opponent holds it; (c) an eliminated opponent
 * does not crash the evaluator and does not contribute its stale life total to the score.
 *
 * All fixtures are built in {@link PhaseType#MAIN2} with empty battlefields, so
 * {@code GameStateEvaluator}'s upcoming-combat simulation short-circuits (no creatures in play for
 * the player on the turn) and {@code getScoreForGameState} exercises the non-combat scoring path
 * directly -- see {@code simulateUpcomingCombatThisTurn}'s early-outs.
 */
public class GameStateEvaluatorMultiplayerTest extends SimulationTest {

    private static final int NUM_PLAYERS = 4;

    private Game createFourPlayerGame() {
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < NUM_PLAYERS; i++) {
            Deck d = new Deck();
            Set<AIOption> options = new HashSet<>();
            if (i == 0) {
                options.add(AIOption.USE_SIMULATION);
            }
            players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("p" + i, options)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "GameStateEvaluatorMultiplayerTest");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;

        // MAIN2 for the current player is after COMBAT_DAMAGE, so
        // GameStateEvaluator.simulateUpcomingCombatThisTurn() short-circuits and we exercise the
        // direct (non-combat-simulated) scoring path.
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getPlayers().get(0), false, 5);
        game.getAction().checkStateEffects(true);
        return game;
    }

    @Test
    public void testConcentratedOpponentThreatScoresWorseThanEvenTable() {
        initAndCreateGame();
        GameStateEvaluator evaluator = new GameStateEvaluator();

        // Evenly matched table: opponent life 10/10/10 (sum 30, avg 10).
        Game evenGame = createFourPlayerGame();
        Player evenAi = evenGame.getPlayers().get(0);
        evenAi.setLife(15, null);
        evenGame.getPlayers().get(1).setLife(10, null);
        evenGame.getPlayers().get(2).setLife(10, null);
        evenGame.getPlayers().get(3).setLife(10, null);
        int evenScore = evaluator.getScoreForGameState(evenGame, evenAi).value;

        // Concentrated table: one clear leader at 20 life, two weak opponents at 5 (same sum 30,
        // same average 10). The old sum-then-average code (`opponentLife / (players - 1)`) scored
        // these two tables identically. The fixed per-opponent-weighted code must not.
        Game leaderGame = createFourPlayerGame();
        Player leaderAi = leaderGame.getPlayers().get(0);
        leaderAi.setLife(15, null);
        leaderGame.getPlayers().get(1).setLife(20, null);
        leaderGame.getPlayers().get(2).setLife(5, null);
        leaderGame.getPlayers().get(3).setLife(5, null);
        int leaderScore = evaluator.getScoreForGameState(leaderGame, leaderAi).value;

        Assert.assertNotEquals(leaderScore, evenScore,
                "Concentrated-leader table and evenly-matched table (same opponent life sum/avg) "
                        + "scored identically -- per-opponent weighting isn't actually changing behavior");
        Assert.assertTrue(leaderScore < evenScore,
                "A table with one high-life 'leader' opponent should score worse for the AI than an "
                        + "evenly matched table with the same total opponent life, since the leader is "
                        + "harder to kill and now dominates the weighted term (expected leaderScore="
                        + leaderScore + " < evenScore=" + evenScore + ")");

        // Sanity: confirm the expected magnitude matches the documented 65/35 weighting
        // (evenWeighted = 10, leaderWeighted = 0.65*20 + 0.35*10 = 16.5) so this test would fail
        // loudly if the formula's constants drift silently.
        int expectedDiff = (int) Math.round(2 * 16.5) - (int) Math.round(2 * 10.0);
        Assert.assertEquals(evenScore - leaderScore, expectedDiff,
                "Score delta between the two tables doesn't match the documented 65/35 weighted-life formula");
    }

    @Test
    public void testAiHoldingMonarchScoresHigherThanOpponentHoldingIt() {
        initAndCreateGame();
        GameStateEvaluator evaluator = new GameStateEvaluator();

        Game aiMonarchGame = createFourPlayerGame();
        Player aiPlayerA = aiMonarchGame.getPlayers().get(0);
        for (Player p : aiMonarchGame.getPlayers()) {
            p.setLife(15, null);
        }
        aiMonarchGame.setMonarch(aiPlayerA);
        int aiMonarchScore = evaluator.getScoreForGameState(aiMonarchGame, aiPlayerA).value;

        Game oppMonarchGame = createFourPlayerGame();
        Player aiPlayerB = oppMonarchGame.getPlayers().get(0);
        for (Player p : oppMonarchGame.getPlayers()) {
            p.setLife(15, null);
        }
        oppMonarchGame.setMonarch(oppMonarchGame.getPlayers().get(1));
        int oppMonarchScore = evaluator.getScoreForGameState(oppMonarchGame, aiPlayerB).value;

        Game noMonarchGame = createFourPlayerGame();
        Player aiPlayerC = noMonarchGame.getPlayers().get(0);
        for (Player p : noMonarchGame.getPlayers()) {
            p.setLife(15, null);
        }
        int noMonarchScore = evaluator.getScoreForGameState(noMonarchGame, aiPlayerC).value;

        Assert.assertTrue(aiMonarchScore > noMonarchScore,
                "AI holding the Monarch should score higher than an otherwise-identical state with no Monarch in play");
        Assert.assertTrue(noMonarchScore > oppMonarchScore,
                "An opponent holding the Monarch should score lower than no Monarch in play");
        Assert.assertEquals(aiMonarchScore - oppMonarchScore, 2 * GameStateEvaluator.MONARCH_VALUE,
                "AI-holds-Monarch vs opponent-holds-Monarch delta should be exactly 2x MONARCH_VALUE (+8/-8)");
    }

    @Test
    public void testEliminatedOpponentDoesNotCrashOrCorruptScore() {
        initAndCreateGame();
        GameStateEvaluator evaluator = new GameStateEvaluator();

        // Reference game: only the 2 surviving opponents at life 10 each (no 3rd/eliminated
        // opponent present at all), matching what the alive-only weighting should produce.
        Game referenceGame = createFourPlayerGame();
        Player referenceAi = referenceGame.getPlayers().get(0);
        referenceAi.setLife(15, null);
        referenceGame.getPlayers().get(1).setLife(10, null);
        referenceGame.getPlayers().get(2).setLife(10, null);
        referenceGame.getPlayers().get(3).setLife(10, null);
        int referenceScoreAllAlive = evaluator.getScoreForGameState(referenceGame, referenceAi).value;

        // Test game: same as reference, except player 3 is eliminated with a stale/leftover life
        // total that must NOT be counted as a live threat.
        Game eliminatedGame = createFourPlayerGame();
        Player eliminatedAi = eliminatedGame.getPlayers().get(0);
        eliminatedAi.setLife(15, null);
        eliminatedGame.getPlayers().get(1).setLife(10, null);
        eliminatedGame.getPlayers().get(2).setLife(10, null);
        Player eliminatedOpponent = eliminatedGame.getPlayers().get(3);
        eliminatedOpponent.setLife(2, null);
        boolean lossApplied = eliminatedOpponent.loseConditionMet(GameLossReason.LifeReachedZero, null);
        Assert.assertTrue(lossApplied, "Test setup bug: eliminated player's loss condition didn't apply");
        Assert.assertTrue(eliminatedOpponent.hasLost(), "Test setup bug: eliminated player should report hasLost() == true");

        int scoreNoCrash;
        try {
            scoreNoCrash = evaluator.getScoreForGameState(eliminatedGame, eliminatedAi).value;
        } catch (Exception e) {
            throw new AssertionError("GameStateEvaluator threw when scoring a state with an eliminated opponent", e);
        }

        Assert.assertEquals(scoreNoCrash, referenceScoreAllAlive,
                "Eliminated opponent's stale life total corrupted the score -- expected it to be excluded "
                        + "entirely, matching a table where only the 2 surviving opponents are present");
    }
}
