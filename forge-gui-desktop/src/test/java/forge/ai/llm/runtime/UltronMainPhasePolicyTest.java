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

public class UltronMainPhasePolicyTest extends AITest {

    @Test
    public void testManaReservationHoldsCounterspellManaWhenAhead() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);

        addCards("Grizzly Bears", 5, ultron);
        addCards("Forest", 4, ultron);
        addCard("Runeclaw Bear", opponent);
        candidateFromHand("Counterspell", ultron);

        UltronDecisionContext ctx = contextFor(game, ultron, List.of());
        UltronManaReservation reservation = UltronManaReservationPolicy.compute(ctx);

        Assert.assertEquals(reservation.blue, 1);
        Assert.assertEquals(reservation.generic, 1);
        Assert.assertEquals(reservation.total(), 2);
    }

    @Test
    public void testManaReservationDropsWhenDesperate() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        Player opponentB = game.getPlayers().get(2);

        ultron.setLife(6, null);
        addCard("Grizzly Bears", ultron);
        addCards("Grizzly Bears", 5, opponent);
        addCards("Forest", 3, opponent);
        addCards("Runeclaw Bear", 2, opponentB);
        candidateFromHand("Counterspell", ultron);

        UltronDecisionContext ctx = contextFor(game, ultron, List.of());
        Assert.assertEquals(ctx.intent.role, UltronRuntimeRole.DESPERATE,
                "This setup should place Ultron in desperate mode");
        UltronManaReservation reservation = UltronManaReservationPolicy.compute(ctx);

        Assert.assertEquals(reservation.total(), 0, "Desperate mode should not hold back mana");
    }

    @Test
    public void testMainPhaseCounterspellScoresBelowBoardDevelopmentWhenAhead() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);

        addCards("Grizzly Bears", 5, ultron);
        addCards("Forest", 4, ultron);
        addCard("Runeclaw Bear", opponent);

        SpellAbility counterspell = candidateFromHand("Counterspell", ultron);
        SpellAbility bear = candidateFromHand("Runeclaw Bear", ultron);

        UltronDecisionContext ctx = contextFor(game, ultron, List.of(counterspell, bear));
        UltronManaReservation reservation = UltronManaReservationPolicy.compute(ctx);

        UltronScore counterScore = UltronActionScorer.score(counterspell, ctx, reservation);
        UltronScore bearScore = UltronActionScorer.score(bear, ctx, reservation);

        Assert.assertTrue(bearScore.value > counterScore.value,
                "Main-phase board development should beat holding a counterspell as the chosen action");
    }

    @Test
    public void testTapOutRiskPenaltyIsHigherWhenAheadThanWhenStabilizing() {
        Game aheadGame = createFourPlayerGame();
        Player aheadUltron = aheadGame.getPlayers().get(0);
        Player aheadOpponent = aheadGame.getPlayers().get(1);

        addCards("Grizzly Bears", 5, aheadUltron);
        addCards("Forest", 4, aheadUltron);
        addCard("Runeclaw Bear", aheadOpponent);
        candidateFromHand("Counterspell", aheadUltron);
        SpellAbility hillGiantAhead = candidateFromHand("Hill Giant", aheadUltron);

        UltronDecisionContext aheadCtx = contextFor(aheadGame, aheadUltron, List.of(hillGiantAhead));
        UltronManaReservation aheadReservation = UltronManaReservationPolicy.compute(aheadCtx);
        UltronScore aheadScore = UltronActionScorer.score(hillGiantAhead, aheadCtx, aheadReservation);

        Game desperateGame = createFourPlayerGame();
        Player desperateUltron = desperateGame.getPlayers().get(0);
        Player desperateOpponent = desperateGame.getPlayers().get(1);
        Player desperateOpponentB = desperateGame.getPlayers().get(2);

        desperateUltron.setLife(6, null);
        addCard("Grizzly Bears", desperateUltron);
        addCards("Forest", 4, desperateUltron);
        addCards("Grizzly Bears", 5, desperateOpponent);
        addCards("Forest", 3, desperateOpponent);
        addCards("Runeclaw Bear", 2, desperateOpponentB);
        candidateFromHand("Counterspell", desperateUltron);
        SpellAbility hillGiantDesperate = candidateFromHand("Hill Giant", desperateUltron);

        UltronDecisionContext desperateCtx = contextFor(desperateGame, desperateUltron, List.of(hillGiantDesperate));
        Assert.assertEquals(desperateCtx.intent.role, UltronRuntimeRole.STABILIZING,
                "This setup should place Ultron in a pressure role without the ahead-state tap-out penalty");
        UltronManaReservation desperateReservation = UltronManaReservationPolicy.compute(desperateCtx);
        UltronScore desperateScore = UltronActionScorer.score(hillGiantDesperate, desperateCtx, desperateReservation);

        Assert.assertTrue(aheadScore.reason.contains("tap-out-risk"),
                "Ahead scoring should record the tap-out penalty");
        Assert.assertTrue(desperateScore.value > aheadScore.value,
                "Stabilizing mode should relax the tap-out penalty for the same stabilizing-sized play");
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
        Match match = new Match(rules, players, "UltronMainPhaseTest");
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
