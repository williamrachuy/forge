package forge.ai.llm.runtime;

import com.google.common.collect.Lists;
import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.UltronStrategicPlan;
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
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class UltronRuntimeHookInvalidationTest extends AITest {

    @Test
    public void testPlayNoStackInvalidatesUltronRuntimeCache() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponentA = game.getPlayers().get(1);
        Player opponentB = game.getPlayers().get(2);

        addCards("Grizzly Bears", 7, ultron);
        addCards("Forest", 4, ultron);
        addCard("Runeclaw Bear", opponentA);

        SpellAbility filler = candidateFromHand("Savannah Lions", ultron);
        UltronRuntimeController runtime = UltronRuntimeController.getOrCreate(
                game, ultron, new AiCardMemory());

        UltronRuntimeDecision firstDecision = runtime.choose(
                List.of(filler), UltronStrategicPlan.GameState.MAIN_PHASE);
        Assert.assertTrue(firstDecision.isPass(),
                "Ahead-state filler should be pruned before the board changes");

        ultron.setLife(6, null);
        clearBattlefield(ultron);
        Card forest = addCard("Forest", ultron);
        addCards("Grizzly Bears", 5, opponentA);
        addCards("Wind Drake", 2, opponentB);
        game.getAction().checkStateEffects(true);

        SpellAbility manaAbility = forest.getManaAbilities().get(0);
        manaAbility.setActivatingPlayer(ultron);
        Assert.assertTrue(ComputerUtil.playNoStack(ultron, manaAbility, game, false),
                "Mana ability should resolve and trigger runtime invalidation");

        UltronRuntimeDecision secondDecision = runtime.choose(
                List.of(filler), UltronStrategicPlan.GameState.MAIN_PHASE);

        Assert.assertTrue(secondDecision.hasChoice(),
                "A real AI action hook should refresh cached table state for the next decision");
        Assert.assertEquals(secondDecision.getSpellAbility(), filler);
    }

    private void clearBattlefield(Player player) {
        var battlefield = List.copyOf(player.getCardsIn(ZoneType.Battlefield));
        for (var card : battlefield) {
            card.getZone().remove(card);
        }
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
        Match match = new Match(rules, players, "UltronRuntimeHookInvalidationTest");
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
