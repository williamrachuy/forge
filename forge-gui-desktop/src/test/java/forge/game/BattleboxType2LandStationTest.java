package forge.game;

import forge.GuiDesktop;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

/**
 * Battlebox Type 2 stocks the shared command-zone land station with one of each basic land type
 * per player, taken from the deck's [BasicLandsSet] prints, and ignores the [LandStation] section
 * entirely. Type 1 behaviour must be unchanged.
 */
public class BattleboxType2LandStationTest {
    private static boolean initialized = false;

    private Deck testDeck;

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

    @BeforeMethod
    public void setUp() {
        testDeck = new Deck("Test Battlebox Type 2 Deck");
        testDeck.getOrCreate(DeckSection.Main).add("Forest", 20);
        testDeck.getOrCreate(DeckSection.Main).add("Mountain", 20);
        testDeck.getOrCreate(DeckSection.Main).add("Swamp", 20);

        // Present on purpose: Type 2 must ignore it.
        testDeck.getOrCreate(DeckSection.LandStation).add("Forest", 10);
        testDeck.getOrCreate(DeckSection.LandStation).add("Island", 10);
    }

    private static Map<String, Integer> countsByName(final CardPool pool) {
        final Map<String, Integer> counts = new TreeMap<>();
        for (final Entry<PaperCard, Integer> entry : pool) {
            counts.merge(entry.getKey().getName(), entry.getValue(), Integer::sum);
        }
        return counts;
    }

    @Test
    public void type2StationHasOneOfEachBasicPerPlayer() {
        final BattleboxConfig config = BattleboxConfig.fromDeck(testDeck);
        for (int players = 1; players <= 6; players++) {
            final CardPool station = config.getLandStation(testDeck, players, true);
            Assert.assertNotNull(station, "Type 2 station should never be null");
            Assert.assertEquals(station.countAll(), MagicColor.Constant.BASIC_LANDS.size() * players,
                    "Type 2 station size for " + players + " players");

            final Map<String, Integer> counts = countsByName(station);
            Assert.assertEquals(counts.size(), MagicColor.Constant.BASIC_LANDS.size(),
                    "Type 2 station should hold only the five basic land types");
            for (final String basic : MagicColor.Constant.BASIC_LANDS) {
                Assert.assertEquals(counts.getOrDefault(basic, 0).intValue(), players,
                        players + " players should get " + players + "x " + basic);
            }
        }
    }

    @Test
    public void type2IgnoresLandStationSection() {
        final BattleboxConfig config = BattleboxConfig.fromDeck(testDeck);
        // The [LandStation] section holds 20 cards; Type 2 for 2 players holds 10.
        Assert.assertEquals(config.getLandStation(testDeck, 2, true).countAll(), 10);
    }

    @Test
    public void type2WorksWithoutALandStationSection() {
        final Deck deck = new Deck("No LandStation");
        deck.getOrCreate(DeckSection.Main).add("Forest", 20);
        final CardPool station = BattleboxConfig.fromDeck(deck).getLandStation(deck, 3, true);
        Assert.assertNotNull(station, "Type 2 does not need a [LandStation] section");
        Assert.assertEquals(station.countAll(), 15);
    }

    @Test
    public void type1StationIsUnchanged() {
        final BattleboxConfig config = BattleboxConfig.fromDeck(testDeck);
        Assert.assertEquals(config.getLandStation(testDeck, 2).countAll(), 20,
                "Type 1 at two players is exactly the [LandStation] section");
        Assert.assertEquals(config.getLandStation(testDeck, 4).countAll(), 30,
                "Type 1 adds one basic-land set per player beyond the second");
        Assert.assertEquals(config.getLandStation(testDeck, 4, false).countAll(), 30,
                "Explicit type2=false matches the two-arg overload");
    }

    @Test
    public void gameRulesReportTheBattleboxType() {
        final GameRules type1 = new GameRules(GameType.Battlebox);
        Assert.assertTrue(type1.isBattlebox());
        Assert.assertFalse(type1.isBattleboxType2());

        final GameRules type2 = new GameRules(GameType.Battlebox2);
        Assert.assertTrue(type2.isBattlebox());
        Assert.assertTrue(type2.isBattleboxType2());

        final GameRules lobbyType2 = new GameRules(GameType.Constructed);
        lobbyType2.addAppliedVariant(GameType.Battlebox2);
        Assert.assertTrue(lobbyType2.isBattlebox());
        Assert.assertTrue(lobbyType2.isBattleboxType2());

        final GameRules constructed = new GameRules(GameType.Constructed);
        Assert.assertFalse(constructed.isBattlebox());
        Assert.assertFalse(constructed.isBattleboxType2());
    }
}
