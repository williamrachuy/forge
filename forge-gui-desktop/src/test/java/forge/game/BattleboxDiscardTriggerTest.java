package forge.game;

import forge.GuiDesktop;
import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Whenever you discard a card" must mean the controller of the trigger source, not any player.
 * Battlebox shares the graveyard, and a discarded card lands there, so a shared-zone check that
 * treats every shared-zone card as "yours" makes every player's Currency Converter fire on every
 * discard in the game (Frantic Search being the obvious offender).
 */
public class BattleboxDiscardTriggerTest {
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
    public void currencyConverterOnlyTriggersOnItsOwnControllersDiscard() throws Exception {
        final Game game = newBattleboxGame();
        // Seat 0 owns every unclaimed shared-library card, so put the artifact there: that is the
        // seat most likely to wrongly match a shared-zone card.
        final Player owner = game.getPlayers().get(0);
        final Player discarder = game.getPlayers().get(2);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, discarder, false, 1);

        final Card converter = putOnBattlefield(game, "Currency Converter", owner);
        final Trigger discardTrigger = discardTriggerOf(converter);

        final Card discarded = putInHand(game, "Grizzly Bears", discarder);
        Assert.assertSame(discarded.getOwner(), discarder, "test setup: the discarder should own the card");

        discarder.discard(discarded, null, false, AbilityKey.newMap());
        Assert.assertTrue(discarded.isInZone(ZoneType.Graveyard),
                "test setup: the discarded card should be in the (shared) graveyard");

        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(discarder);
        runParams.put(AbilityKey.Card, discarded);

        Assert.assertFalse(discardTrigger.performTest(runParams),
                "Currency Converter controlled by " + owner + " must not trigger when " + discarder
                        + " discards — 'whenever YOU discard a card'");
    }

    @Test
    public void currencyConverterStillTriggersOnItsOwnControllersDiscard() throws Exception {
        final Game game = newBattleboxGame();
        final Player owner = game.getPlayers().get(2);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, owner, false, 1);

        final Card converter = putOnBattlefield(game, "Currency Converter", owner);
        final Trigger discardTrigger = discardTriggerOf(converter);

        final Card discarded = putInHand(game, "Grizzly Bears", owner);
        owner.discard(discarded, null, false, AbilityKey.newMap());

        final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(owner);
        runParams.put(AbilityKey.Card, discarded);

        Assert.assertTrue(discardTrigger.performTest(runParams),
                "Currency Converter must still trigger when its own controller discards");
    }

    /** The shared library is genuinely communal — an unclaimed card there counts as anyone's. */
    @Test
    public void unclaimedSharedLibraryCardsStillCountAsYours() throws Exception {
        final Game game = newBattleboxGame();
        final Player host = game.getPlayers().get(0);
        final Player other = game.getPlayers().get(2);

        Card libraryCard = null;
        for (final Card c : other.getZone(ZoneType.Library).getCards(false)) {
            if (c.getOwner() == host) {
                libraryCard = c;
                break;
            }
        }
        Assert.assertNotNull(libraryCard, "test setup: expected an unclaimed shared-library card");
        Assert.assertTrue(other.isBattleboxSharedLibraryCard(libraryCard), "test setup");

        final Card source = putOnBattlefield(game, "Currency Converter", other);
        Assert.assertTrue(libraryCard.isValid("Card.YouCtrl", other, source, null),
                "an unclaimed card in the shared library must count as controlled by any player");
    }

    // ---------------------------------------------------------------- helpers

    private static Trigger discardTriggerOf(final Card c) {
        for (final Trigger t : c.getTriggers()) {
            if (t.getMode() == TriggerType.Discarded) {
                return t;
            }
        }
        throw new AssertionError("no Discarded trigger on " + c);
    }

    private static Card putInHand(final Game game, final String name, final Player owner) {
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
        final Card c = Card.fromPaperCard(paper, owner);
        return game.getAction().moveTo(ZoneType.Hand, c, null, null);
    }

    private static Card putOnBattlefield(final Game game, final String name, final Player controller) {
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
        final Card c = Card.fromPaperCard(paper, controller);
        return game.getAction().moveTo(ZoneType.Battlefield, c, null, null);
    }

    private static Game newBattleboxGame() throws Exception {
        final File deckFile = new File(ForgeConstants.DECK_BATTLEBOX_DIR + "BattleBox.dck");
        if (!deckFile.exists()) {
            throw new SkipException("No Battlebox deck at " + deckFile);
        }
        final Deck deck = DeckSerializer.fromFile(deckFile);

        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final BattleboxConfig config = BattleboxConfig.fromDeck(deck);
            final RegisteredPlayer rp = new RegisteredPlayer(deck);
            rp.setStartingLife(config.getStartingLife());
            rp.setStartingHand(config.getStartingHandSize());
            rp.setMaxHand(config.getMaxHandSize());
            rp.setPlayer(new LobbyPlayerAi("p" + (i + 1), null));
            players.add(rp);
        }

        final GameRules rules = new GameRules(GameType.Battlebox);
        rules.addAppliedVariant(GameType.Battlebox);
        final Match match = new Match(rules, players, "Battlebox");
        final Game game = match.createGame();
        game.setBattleboxMonarchChoiceMade(true);

        final Method prepareAllZones = Match.class.getDeclaredMethod("prepareAllZones", Game.class);
        prepareAllZones.setAccessible(true);
        prepareAllZones.invoke(match, game);
        return game;
    }
}
