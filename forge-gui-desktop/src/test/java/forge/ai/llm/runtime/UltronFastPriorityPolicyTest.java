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

import java.util.Collections;
import java.util.List;

public class UltronFastPriorityPolicyTest extends AITest {

    @Test
    public void testPassesWhenNoCandidatesExist() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);

        UltronRuntimeDecision decision = UltronFastPriorityPolicy.choose(
                contextFor(game, ultron, Collections.emptyList()));

        Assert.assertTrue(decision.isPass());
        Assert.assertEquals(decision.getReason(), "no candidates");
    }

    @Test
    public void testReturnsNoDecisionOnUltronMainPhaseWithEmptyStack() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        SpellAbility candidate = candidateFromHand("Counterspell", ultron);

        UltronRuntimeDecision decision = UltronFastPriorityPolicy.choose(
                contextFor(game, ultron, List.of(candidate)));

        Assert.assertTrue(decision.shouldFallback(), "Main-phase empty stack should defer to main-phase scorer");
        Assert.assertFalse(decision.isPass());
        Assert.assertEquals(decision.getReason(), "main phase — defer to action scorer");
    }

    @Test
    public void testReturnsNoDecisionForOpponentEndStepInstantOpportunity() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opponent);
        game.getPhaseHandler().onStackResolved();

        SpellAbility candidate = candidateFromHand("Think Twice", ultron);

        UltronRuntimeDecision decision = UltronFastPriorityPolicy.choose(
                contextFor(game, ultron, List.of(candidate)));

        Assert.assertTrue(decision.shouldFallback(), "Opponent end step with instant should not auto-pass");
        Assert.assertEquals(decision.getReason(), "end-step instant-speed action opportunity");
    }

    @Test
    public void testPassesWhenUltronControlsTopOfStack() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);

        SpellAbility stackSpell = candidateFromHand("Grizzly Bears", ultron);
        game.getStack().addAndUnfreeze(stackSpell);

        SpellAbility candidate = candidateFromHand("Counterspell", ultron);
        UltronRuntimeDecision decision = UltronFastPriorityPolicy.choose(
                contextFor(game, ultron, List.of(candidate)));

        Assert.assertTrue(decision.isPass());
        Assert.assertEquals(decision.getReason(), "ultron controls stack top");
    }

    @Test
    public void testPassesOnLowValueSpellFromWeakOpponent() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player weakOpponent = game.getPlayers().get(3);

        SpellAbility topSpell = candidateFromHand("Divination", weakOpponent);
        game.getStack().addAndUnfreeze(topSpell);

        SpellAbility counterspell = candidateFromHand("Counterspell", ultron);
        UltronRuntimeDecision decision = UltronFastPriorityPolicy.choose(
                contextFor(game, ultron, List.of(counterspell)));

        Assert.assertTrue(decision.isPass(), "Minor draw spell from weak opponent should not be countered");
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
        Match match = new Match(rules, players, "UltronPriorityTest");
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
