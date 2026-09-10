package forge.game;

import forge.GuiDesktop;
import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
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

/**
 * "Exile ... until CARDNAME leaves the battlefield" (Banishing Light) must give the permanent back
 * to its owner when the enchantment goes away.
 */
public class BanishingLightReturnTest {
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
    public void constructedReturnsTheExiledPermanent() throws Exception {
        for (final ZoneType exit : new ZoneType[] {ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand, ZoneType.Library}) {
            assertReturnsPermanent(newGame(false), exit, false);
        }
    }

    @Test
    public void battleboxReturnsTheExiledPermanent() throws Exception {
        for (final ZoneType exit : new ZoneType[] {ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand, ZoneType.Library}) {
            assertReturnsPermanent(newGame(true), exit, false);
        }
    }

    @Test
    public void battleboxReturnsAPermanentClaimedFromTheSharedLibrary() throws Exception {
        for (final ZoneType exit : new ZoneType[] {ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand, ZoneType.Library}) {
            assertReturnsPermanent(newGame(true), exit, true);
        }
    }

    private static void assertReturnsPermanent(final Game game, final ZoneType exit, final boolean fromSharedLibrary) {
        // Deliberately asymmetric: neither player is seat 0, which in Battlebox owns every card
        // in the shared library before it is claimed.
        final Player victim = game.getPlayers().get(2);
        final Player exiler = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, exiler, false, 1);

        final Card creature = fromSharedLibrary
                ? claimFromSharedLibrary(game, "Grizzly Bears", victim)
                : putOnBattlefield(game, "Grizzly Bears", victim);
        final Card light = putOnBattlefield(game, "Banishing Light", exiler);
        game.getAction().checkStateEffects(true);

        System.out.println("before: creature zone=" + zoneOf(creature)
                + " owner=" + creature.getOwner() + " controller=" + creature.getController()
                + " ts=" + creature.getGameTimestamp());

        final SpellAbility exile = AbilityFactory.getAbility(light, "TrigExile");
        exile.setActivatingPlayer(exiler);
        exile.resetTargets();
        exile.getTargets().add(creature);
        AbilityUtils.resolve(exile);
        game.getAction().checkStateEffects(true);

        final Card exiled = game.getCardState(creature, null);
        System.out.println("exiled: zone=" + zoneOf(exiled)
                + " owner=" + exiled.getOwner() + " controller=" + exiled.getController()
                + " ts=" + exiled.getGameTimestamp());
        Assert.assertTrue(exiled.isInZone(ZoneType.Exile), "the permanent should be exiled");

        // The enchantment leaves the battlefield.
        game.getAction().moveTo(exit, light, null, null);
        game.getAction().checkStateEffects(true);

        final Card returned = game.getCardState(exiled, null);
        System.out.println("after:  zone=" + zoneOf(returned)
                + " owner=" + returned.getOwner() + " controller=" + returned.getController()
                + " ts=" + returned.getGameTimestamp());

        final String where = " [enchantment left to " + exit + ", sharedLibraryCard=" + fromSharedLibrary + "]";
        Assert.assertTrue(returned.isInZone(ZoneType.Battlefield),
                "the permanent must come back when the enchantment leaves, but it is in "
                        + zoneOf(returned) + where);
        Assert.assertSame(returned.getController(), victim,
                "the permanent must come back under its owner's control" + where);
        Assert.assertTrue(victim.getCardsIn(ZoneType.Battlefield).contains(returned),
                "the permanent must be on its owner's battlefield" + where);
    }

    // ---------------------------------------------------------------- helpers

    private static String zoneOf(final Card c) {
        return c == null || c.getZone() == null ? "null" : c.getZone().getZoneType().toString();
    }

    /** Mimics a Battlebox draw: the card is created owned by the shared-library host, then claimed
     *  by the player who drew it, then played. */
    private static Card claimFromSharedLibrary(final Game game, final String name, final Player drawer) {
        final Player host = game.getPlayers().get(0);
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
        final Card c = Card.fromPaperCard(paper, host);
        drawer.getZone(ZoneType.Library).add(c);
        drawer.claimBattleboxSharedLibraryCard(c);
        return game.getAction().moveTo(ZoneType.Battlefield, c, null, null);
    }

    private static Card putOnBattlefield(final Game game, final String name, final Player controller) {
        final PaperCard paper = StaticData.instance().getCommonCards().getUniqueByName(name);
        final Card c = Card.fromPaperCard(paper, controller);
        return game.getAction().moveTo(ZoneType.Battlefield, c, null, null);
    }

    private static Game newGame(final boolean battlebox) throws Exception {
        final List<RegisteredPlayer> players = new ArrayList<>();
        Deck deck = new Deck("empty");
        if (battlebox) {
            final File deckFile = new File(ForgeConstants.DECK_BATTLEBOX_DIR + "BattleBox.dck");
            if (!deckFile.exists()) {
                throw new SkipException("No Battlebox deck at " + deckFile);
            }
            deck = DeckSerializer.fromFile(deckFile);
        }
        for (int i = 0; i < 4; i++) {
            final RegisteredPlayer rp = new RegisteredPlayer(deck);
            rp.setPlayer(new LobbyPlayerAi("p" + (i + 1), null));
            players.add(rp);
        }

        final GameRules rules = new GameRules(battlebox ? GameType.Battlebox : GameType.Constructed);
        if (battlebox) {
            rules.addAppliedVariant(GameType.Battlebox);
        }
        final Match match = new Match(rules, players, battlebox ? "Battlebox" : "Constructed");
        final Game game = match.createGame();
        if (battlebox) {
            game.setBattleboxMonarchChoiceMade(true);
            final Method prepareAllZones = Match.class.getDeclaredMethod("prepareAllZones", Game.class);
            prepareAllZones.setAccessible(true);
            prepareAllZones.invoke(match, game);
        }
        return game;
    }
}
