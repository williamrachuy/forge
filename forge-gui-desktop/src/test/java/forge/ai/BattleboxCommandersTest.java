package forge.ai;

import com.google.common.collect.Lists;
import forge.GuiDesktop;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.item.IPaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

public class BattleboxCommandersTest {
    private static boolean initialized = false;

    @BeforeClass
    public static void init() {
        if (!initialized) {
            GuiBase.setInterface(new GuiDesktop());
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            initialized = true;
        }
    }

    @Test
    public void testAICanSeeBattleboxCommanders() {
        // Create a battlebox game
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d1 = new Deck();
        players.add(new RegisteredPlayer(d1).setPlayer(new LobbyPlayerAi("ai", null)));
        players.add(new RegisteredPlayer(d1).setPlayer(new LobbyPlayerAi("opponent", null)));

        GameRules rules = new GameRules(GameType.Constructed);
        rules.setBattleboxCommandersEnabled(true);
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);

        // Enable commanders
        game.setBattleboxCommandersChoice(true);
        Player aiPlayer = game.getPlayers().get(0);

        // Add commanders directly to the command zone
        Card commander1 = addCard("Grizzly Bears", aiPlayer, game);
        Card commander2 = addCard("Hill Giant", aiPlayer, game);

        // Move them to command zone using the zone directly
        PlayerZone commandZone = aiPlayer.getZone(ZoneType.Command);
        commandZone.add(commander1);
        commandZone.add(commander2);

        // Set game phase and state
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, aiPlayer);
        game.getPhaseHandler().onStackResolved();

        // Verify commanders are in command zone
        Iterable<Card> commandZoneCards = game.getCardsIn(ZoneType.Command);
        int count = 0;
        System.out.println("DEBUG: Command zone has commanders:");
        for (Card c : commandZoneCards) {
            System.out.println("  - " + c.getName());
            count++;
        }
        Assert.assertTrue(count >= 2, "Should have at least 2 commanders in command zone");

        // Check if AI can see them as shared command cards
        for (Card c : commandZoneCards) {
            boolean isShared = aiPlayer.isBattleboxSharedCommandCard(c);
            System.out.println("DEBUG: " + c.getName() + " is shared command card for AI: " + isShared);
            Assert.assertTrue(isShared, c.getName() + " should be a shared command card for the AI");
        }

        // Verify spell abilities are returned
        for (Card c : commandZoneCards) {
            java.util.List<SpellAbility> abilities = c.getAllPossibleAbilities(aiPlayer, false);
            System.out.println("DEBUG: " + c.getName() + " returned " + abilities.size() + " abilities for AI");
            Assert.assertFalse(abilities.isEmpty(), c.getName() + " should have castable spell abilities");
        }

        System.out.println("✓ AI can see and get spell abilities for battlebox commanders");
    }

    private static Card addCard(String cardName, Player player, Game game) {
        IPaperCard paper = StaticData.instance().getCommonCards().getCard(cardName);
        if (paper == null) {
            paper = StaticData.instance().getCommonCards().getCard(cardName + " Token");
        }
        Assert.assertNotNull(paper, "Card " + cardName + " not found");
        Card c = CardFactory.getCard(paper, player, 0, game);
        player.getZone(ZoneType.Hand).add(c);
        return c;
    }
}
