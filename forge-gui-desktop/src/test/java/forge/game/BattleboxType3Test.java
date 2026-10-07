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

import java.util.Map.Entry;

/**
 * Battlebox Type 3 is Type 2 (basic-land station, one of each basic per player, [LandStation]
 * ignored) plus: no basic lands are ever seeded into the shared library, and the starting hand
 * defaults to 7 unless the deck's BattleboxStartingHandSize metadata says otherwise.
 */
public class BattleboxType3Test {
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
        testDeck = new Deck("Test Battlebox Type 3 Deck");
        // 200 non-land cards so any basic in the shared library must have been seeded.
        testDeck.getOrCreate(DeckSection.Main).add("Grizzly Bears", 200);
        testDeck.getOrCreate(DeckSection.LandStation).add("Island", 10);
        // Explicit, and must be overridden for Type 3.
        testDeck.getMetadata().put(BattleboxConfig.SEED_BASIC_LANDS, "true");
    }

    private static int countBasics(final CardPool pool) {
        int basics = 0;
        for (final Entry<PaperCard, Integer> entry : pool) {
            if (MagicColor.Constant.BASIC_LANDS.contains(entry.getKey().getName())) {
                basics += entry.getValue();
            }
        }
        return basics;
    }

    @Test
    public void gameRulesReportType3() {
        final GameRules base = new GameRules(GameType.Battlebox3);
        final GameRules lobby = new GameRules(GameType.Constructed);
        lobby.addAppliedVariant(GameType.Battlebox3);
        for (final GameRules rules : new GameRules[] { base, lobby }) {
            Assert.assertTrue(rules.isBattlebox());
            Assert.assertTrue(rules.isBattleboxType3());
            Assert.assertTrue(rules.usesBasicLandStation());
            Assert.assertFalse(rules.isBattleboxType2());
        }
        Assert.assertTrue(GameType.Battlebox3.isBattlebox());

        final GameRules type2 = new GameRules(GameType.Battlebox2);
        Assert.assertTrue(type2.usesBasicLandStation());
        Assert.assertFalse(type2.isBattleboxType3());
        final GameRules type1 = new GameRules(GameType.Battlebox);
        Assert.assertFalse(type1.usesBasicLandStation());
        Assert.assertFalse(type1.isBattleboxType3());
    }

    @Test
    public void type3SharedLibraryHasNoSeededBasics() {
        final int players = 4;
        final CardPool type3 = BattleboxConfig.fromDeck(testDeck, true).getSharedLibrary(testDeck, players);
        Assert.assertEquals(countBasics(type3), 0, "Type 3 must not seed basics even with SeedBasicLands=true");
        Assert.assertEquals(type3.countAll(), BattleboxConfig.DEFAULT_PLAYER_LIBRARY_SIZE * players);

        // Control: the same deck under Type 1/2 rules still seeds one basic set per player.
        final CardPool seeded = BattleboxConfig.fromDeck(testDeck).getSharedLibrary(testDeck, players);
        Assert.assertEquals(countBasics(seeded), MagicColor.Constant.BASIC_LANDS.size() * players);
        Assert.assertEquals(seeded.countAll(), BattleboxConfig.DEFAULT_PLAYER_LIBRARY_SIZE * players);
    }

    @Test
    public void type3StationHasFiveBasicsPerPlayer() {
        final BattleboxConfig config = BattleboxConfig.fromDeck(testDeck, true);
        for (int players = 2; players <= 4; players++) {
            final CardPool station = config.getLandStation(testDeck, players, true);
            Assert.assertEquals(station.countAll(), MagicColor.Constant.BASIC_LANDS.size() * players);
            Assert.assertEquals(countBasics(station), station.countAll(), "[LandStation] must be ignored");
        }
    }

    @Test
    public void type3LibrarySizeProblemRequiresFullShareFromMain() {
        // 4 players x 40 = 160 random cards from [Main]; 200 is enough.
        Assert.assertNull(BattleboxConfig.getLibrarySizeProblem(testDeck, 4, true));

        // Under Type 1/2 semantics only 35 per player are random, so 150 cards suffice there but
        // not for Type 3.
        final Deck small = new Deck("Small");
        small.getOrCreate(DeckSection.Main).add("Grizzly Bears", 150);
        Assert.assertNull(BattleboxConfig.getLibrarySizeProblem(small, 4, false));
        Assert.assertNotNull(BattleboxConfig.getLibrarySizeProblem(small, 4, true));
        Assert.assertTrue(BattleboxConfig.getLibrarySizeProblem(small, 4, true).contains("160"));

        // The "at least 5 when SeedBasicLands" check must not fire for Type 3.
        final Deck tiny = new Deck("Tiny");
        tiny.getOrCreate(DeckSection.Main).add("Grizzly Bears", 20);
        tiny.getMetadata().put(BattleboxConfig.PLAYER_LIBRARY_SIZE, "3");
        Assert.assertNotNull(BattleboxConfig.getLibrarySizeProblem(tiny, 2, false));
        Assert.assertNull(BattleboxConfig.getLibrarySizeProblem(tiny, 2, true));
    }

    @Test
    public void startingHandDefaultsToSevenAndMetadataOverrides() {
        Assert.assertEquals(BattleboxConfig.fromDeck(testDeck, true).getStartingHandSize(), 7);
        testDeck.getMetadata().put(BattleboxConfig.STARTING_HAND_SIZE, "5");
        Assert.assertEquals(BattleboxConfig.fromDeck(testDeck, true).getStartingHandSize(), 5);
    }
}
