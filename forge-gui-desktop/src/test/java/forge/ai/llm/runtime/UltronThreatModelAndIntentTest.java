package forge.ai.llm.runtime;

import com.google.common.collect.Lists;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.List;

public class UltronThreatModelAndIntentTest extends AITest {

    @Test
    public void testThreatModelIdentifiesLeaderAndMostDangerousOpponent() {
        Game game = createFourPlayerGame(true);
        Player ultron = game.getPlayers().get(0);
        Player leader = game.getPlayers().get(1);
        Player vulnerable = game.getPlayers().get(2);
        Player quiet = game.getPlayers().get(3);

        addCards("Grizzly Bears", 4, leader);
        addCard("Phyrexian Arena", leader);
        addCards("Forest", 3, leader);

        addCard("Grizzly Bears", vulnerable);
        vulnerable.setLife(8, null);

        addCards("Forest", 2, quiet);

        addCards("Runeclaw Bear", 2, ultron);
        addCards("Forest", 2, ultron);

        game.getAction().checkStateEffects(true);

        UltronTableThreatSummary table = UltronThreatModel.analyze(game, ultron);

        Assert.assertNotNull(table.leader);
        Assert.assertEquals(table.leader.player, leader, "Highest board-value opponent should be leader");
        Assert.assertNotNull(table.mostDangerousToUltron);
        Assert.assertEquals(table.mostDangerousToUltron.player, leader, "Largest combat threat should be most dangerous");
        Assert.assertNotNull(table.mostVulnerable);
        Assert.assertEquals(table.mostVulnerable.player, vulnerable, "Lowest-life weak opponent should be most vulnerable");
    }

    @Test
    public void testTurnIntentPressuresVulnerableOpponentWhenUltronIsAhead() {
        Game game = createFourPlayerGame(true);
        Player ultron = game.getPlayers().get(0);
        Player leaderLikeOpponent = game.getPlayers().get(1);
        Player vulnerable = game.getPlayers().get(2);

        addCards("Grizzly Bears", 5, ultron);
        addCard("Phyrexian Arena", ultron);
        addCards("Forest", 4, ultron);

        addCards("Runeclaw Bear", 2, leaderLikeOpponent);
        addCards("Forest", 2, leaderLikeOpponent);

        addCard("Grizzly Bears", vulnerable);
        vulnerable.setLife(7, null);

        game.getAction().checkStateEffects(true);

        UltronTableThreatSummary table = UltronThreatModel.analyze(game, ultron);
        UltronTurnIntent intent = UltronTurnIntentBuilder.build(table, game.getPhaseHandler().getTurn());

        Assert.assertEquals(intent.preferredAttackTarget, vulnerable,
                "Low-life vulnerable opponent should become preferred attack target");
        Assert.assertTrue(intent.lookForLethal || intent.role == UltronRuntimeRole.CONTROL
                        || intent.role == UltronRuntimeRole.PRESSURING,
                "Ahead Ultron should adopt an assertive role rather than defensive panic");
    }

    @Test
    public void testTurnIntentBecomesDesperateWhenBehindAndFacingLethalPressure() {
        Game game = createFourPlayerGame(true);
        Player ultron = game.getPlayers().get(0);
        Player aggressor = game.getPlayers().get(1);

        ultron.setLife(6, null);
        addCard("Grizzly Bears", ultron);

        addCards("Grizzly Bears", 5, aggressor);
        addCards("Forest", 3, aggressor);

        game.getAction().checkStateEffects(true);

        UltronTableThreatSummary table = UltronThreatModel.analyze(game, ultron);
        UltronTurnIntent intent = UltronTurnIntentBuilder.build(table, game.getPhaseHandler().getTurn());

        Assert.assertTrue(table.ultronInDanger, "Setup should put Ultron in danger");
        Assert.assertTrue(table.ultronIsBehind, "Setup should leave Ultron behind on board");
        Assert.assertEquals(intent.role, UltronRuntimeRole.DESPERATE);
        Assert.assertEquals(intent.primaryThreat, aggressor);
        Assert.assertFalse(intent.avoidTappingOut, "Desperate mode should allow tap-out stabilizing lines");
    }

    @Test
    public void testRuntimeControllerPassesWithoutCandidatesForUltronProfile() {
        Game game = createFourPlayerGame(true);
        Player ultron = game.getPlayers().get(0);
        PlayerControllerAi controller = (PlayerControllerAi) ultron.getController();

        UltronRuntimeController runtime = UltronRuntimeController.getOrCreate(
                game, ultron, controller.getAi().getCardMemory());

        UltronRuntimeDecision decision = runtime.choose(
                Collections.emptyList(), forge.ai.llm.UltronStrategicPlan.GameState.MAIN_PHASE);

        Assert.assertTrue(decision.isPass(), "Ultron runtime should pass cleanly with no candidates");
        Assert.assertEquals(decision.getReason(), "no candidates");
    }

    private Game createFourPlayerGame(boolean ultronProfile) {
        initAndCreateGame();

        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck deck = new Deck();

        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("Ultron", ultronProfile ? "Ultron" : null)));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentA", null)));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentB", null)));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentC", null)));

        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "UltronRuntimeTest");
        Game game = new Game(players, rules, match);
        Player ultron = game.getPlayers().get(0);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ultron);
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    private LobbyPlayerAi aiPlayer(String name, String profile) {
        LobbyPlayerAi ai = new LobbyPlayerAi(name, null);
        if (profile != null) {
            ai.setAiProfile(profile);
        }
        return ai;
    }
}
