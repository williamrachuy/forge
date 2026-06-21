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
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class UltronAiControllerIntegrationTest extends AITest {

    @Test
    public void testAiControllerReturnsNullWhenRuntimePasses() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);

        addCards("Grizzly Bears", 7, ultron);
        addCards("Forest", 4, ultron);
        addCard("Runeclaw Bear", opponent);
        addCardToZone("Llanowar Elves", ultron, ZoneType.Hand);

        List<SpellAbility> chosen = ((PlayerControllerAi) ultron.getController()).getAi().chooseSpellAbilityToPlay();

        Assert.assertNull(chosen, "Runtime pass should propagate through AiController as no chosen spell");
    }

    @Test
    public void testAiControllerReturnsRuntimeChoiceWhenBehind() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponentA = game.getPlayers().get(1);

        ultron.setLife(6, null);
        addCards("Plains", 4, ultron);
        addCards("Grizzly Bears", 5, opponentA);
        addCardToZone("Wrath of God", ultron, ZoneType.Hand);

        List<SpellAbility> chosen = ((PlayerControllerAi) ultron.getController()).getAi().chooseSpellAbilityToPlay();

        Assert.assertNotNull(chosen);
        Assert.assertEquals(chosen.size(), 1);
        Assert.assertEquals(chosen.get(0).getHostCard().getName(), "Wrath of God");
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
        Match match = new Match(rules, players, "UltronAiControllerIntegrationTest");
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
