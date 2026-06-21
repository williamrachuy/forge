package forge.ai.llm.runtime;

import com.google.common.collect.Lists;
import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class UltronInteractionPolicyTest extends AITest {

    @Test
    public void testCountersLethalThreatWhenCounterspellIsAvailable() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player attacker = game.getPlayers().get(1);

        SpellAbility counterspell = candidateFromHand("Counterspell", ultron);
        UltronDecisionContext ctx = contextFor(game, ultron, List.of(counterspell));

        UltronStackThreat threat = new UltronStackThreat(
                UltronStackThreatType.LETHAL_DAMAGE, 99, attacker, null, "lethal burn");

        UltronRuntimeDecision decision = UltronInteractionPolicy.chooseAnswer(ctx, threat);

        Assert.assertTrue(decision.hasChoice(), "Lethal threat should be answered");
        Assert.assertEquals(decision.getSpellAbility().getHostCard().getName(), "Counterspell");
    }

    @Test
    public void testPassesOnLowValueThreatEvenWithCounterspellInHand() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player weakOpponent = game.getPlayers().get(3);

        SpellAbility counterspell = candidateFromHand("Counterspell", ultron);
        UltronDecisionContext ctx = contextFor(game, ultron, List.of(counterspell));

        UltronStackThreat threat = new UltronStackThreat(
                UltronStackThreatType.LOW_VALUE, 20, weakOpponent, null, "minor ramp");

        UltronRuntimeDecision decision = UltronInteractionPolicy.chooseAnswer(ctx, threat);

        Assert.assertTrue(decision.isPass(), "Low-value threat should not draw a counterspell");
    }

    @Test
    public void testDoesNotCounterComboPieceFromWeakNonLeaderPlayer() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player leader = game.getPlayers().get(1);
        Player weakComboPlayer = game.getPlayers().get(3);

        addCards("Grizzly Bears", 5, leader);
        addCard("Phyrexian Arena", leader);
        addCards("Forest", 3, leader);

        addCard("Grizzly Bears", weakComboPlayer);
        weakComboPlayer.setLife(11, null);

        SpellAbility counterspell = candidateFromHand("Counterspell", ultron);
        UltronDecisionContext ctx = contextFor(game, ultron, List.of(counterspell));

        UltronStackThreat threat = new UltronStackThreat(
                UltronStackThreatType.COMBO_PIECE, 80, weakComboPlayer, null, "suspicious but not leader");

        UltronRuntimeDecision decision = UltronInteractionPolicy.chooseAnswer(ctx, threat);

        Assert.assertTrue(decision.isPass(), "Weak non-leader combo piece should not force a counter");
    }

    @Test
    public void testCountersBoardWipeWhenUltronIsAhead() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);

        addCards("Grizzly Bears", 6, ultron);
        addCards("Forest", 4, ultron);
        addCard("Runeclaw Bear", opponent);

        SpellAbility counterspell = candidateFromHand("Counterspell", ultron);
        UltronDecisionContext ctx = contextFor(game, ultron, List.of(counterspell));

        UltronStackThreat threat = new UltronStackThreat(
                UltronStackThreatType.BOARD_WIPE, 85, opponent, null, "wrath while ahead");

        UltronRuntimeDecision decision = UltronInteractionPolicy.chooseAnswer(ctx, threat);

        Assert.assertTrue(decision.hasChoice(), "Ultron should protect its winning board");
        Assert.assertEquals(decision.getSpellAbility().getHostCard().getName(), "Counterspell");
    }

    @Test
    public void testPassesBoardWipeWhenUltronIsBehind() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);

        ultron.setLife(8, null);
        addCard("Grizzly Bears", ultron);
        addCards("Grizzly Bears", 5, opponent);
        addCards("Forest", 3, opponent);

        SpellAbility counterspell = candidateFromHand("Counterspell", ultron);
        UltronDecisionContext ctx = contextFor(game, ultron, List.of(counterspell));

        UltronStackThreat threat = new UltronStackThreat(
                UltronStackThreatType.BOARD_WIPE, 35, opponent, null, "wrath helps reset losing board");

        UltronRuntimeDecision decision = UltronInteractionPolicy.chooseAnswer(ctx, threat);

        Assert.assertTrue(decision.isPass(), "Ultron should usually welcome the reset when behind");
    }

    private UltronDecisionContext contextFor(Game game, Player ultron, List<SpellAbility> candidates) {
        game.getAction().checkStateEffects(true);
        UltronTableThreatSummary table = UltronThreatModel.analyze(game, ultron);
        UltronTurnIntent intent = UltronTurnIntentBuilder.build(table, game.getPhaseHandler().getTurn());
        return new UltronDecisionContext(
                game,
                ultron,
                new AiCardMemory(),
                candidates,
                table,
                intent,
                System.nanoTime() + 1_000_000_000L
        );
    }

    private SpellAbility candidateFromHand(String cardName, Player player) {
        SpellAbility sa = addCardToZone(cardName, player, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(player);
        return sa;
    }

    private Game createFourPlayerGame() {
        initAndCreateGame();

        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck deck = new Deck();

        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("Ultron", "Ultron")));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentA", null)));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentB", null)));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentC", null)));

        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "UltronInteractionTest");
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
