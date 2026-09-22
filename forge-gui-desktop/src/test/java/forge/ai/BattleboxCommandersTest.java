package forge.ai;

import com.google.common.collect.Lists;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.deck.DeckSection;
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
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
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
    public void testAICanSeeBattleboxCommanders() throws Exception {
        final Game game = newBattleboxGameWithCommanders();
        final Player aiPlayer = game.getPlayers().get(0);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, aiPlayer);
        game.getPhaseHandler().onStackResolved();

        // The commander pool is built into the shared command zone by Match.prepareAllZones.
        final List<Card> commanders = Lists.newArrayList();
        for (final Card c : aiPlayer.getZone(ZoneType.Command).getCards(false)) {
            if (!c.isLand() && !c.isImmutable()) {
                commanders.add(c);
            }
        }
        final List<String> names = Lists.newArrayList();
        for (final Card c : commanders) {
            names.add(c.getName());
        }
        Assert.assertTrue(names.contains("Grizzly Bears") && names.contains("Hill Giant"),
                "shared command zone should hold the deck's [Commanders] pool, found " + names);

        for (final Card c : commanders) {
            Assert.assertTrue(aiPlayer.isBattleboxSharedCommandCard(c),
                    c.getName() + " should be a shared command card for the AI");
            final List<SpellAbility> abilities = c.getAllPossibleAbilities(aiPlayer, false);
            Assert.assertFalse(abilities.isEmpty(), c.getName() + " should have castable spell abilities");
        }
    }

    /** A real Battlebox game (commanders on) built from an in-test deck, so it runs in CI. */
    private static Game newBattleboxGameWithCommanders() throws Exception {
        final Deck deck = new Deck("Battlebox Commanders Test");
        deck.getOrCreate(DeckSection.Main).add("Grizzly Bears", 40);
        deck.getOrCreate(DeckSection.Main).add("Hill Giant", 40);
        deck.getOrCreate(DeckSection.LandStation).add("Forest", 5);
        deck.getOrCreate(DeckSection.LandStation).add("Mountain", 5);
        deck.getOrCreate(DeckSection.Commander).add("Grizzly Bears", 1);
        deck.getOrCreate(DeckSection.Commander).add("Hill Giant", 1);

        final List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < 2; i++) {
            players.add(new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi(i == 0 ? "ai" : "opponent", null)));
        }
        final GameRules rules = new GameRules(GameType.Battlebox);
        rules.addAppliedVariant(GameType.Battlebox);
        rules.setBattleboxCommandersEnabled(true);
        final Match match = new Match(rules, players, "Test");
        final Game game = match.createGame();
        game.setBattleboxCommandersChoice(true);

        final Method prepareAllZones = Match.class.getDeclaredMethod("prepareAllZones", Game.class);
        prepareAllZones.setAccessible(true);
        prepareAllZones.invoke(match, game);
        return game;
    }

}
