package forge.game;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class BattleboxCommandersTest {
    private Deck testDeck;

    @BeforeMethod
    public void setUp() {
        testDeck = new Deck("Test Battlebox Deck");

        // Add lands to main
        testDeck.getOrCreate(DeckSection.Main).add("Forest", 20);
        testDeck.getOrCreate(DeckSection.Main).add("Mountain", 20);
        testDeck.getOrCreate(DeckSection.Main).add("Swamp", 20);

        // Add land station
        testDeck.getOrCreate(DeckSection.LandStation).add("Forest", 5);
        testDeck.getOrCreate(DeckSection.LandStation).add("Mountain", 5);
        testDeck.getOrCreate(DeckSection.LandStation).add("Swamp", 5);
        testDeck.getOrCreate(DeckSection.LandStation).add("Island", 5);
        testDeck.getOrCreate(DeckSection.LandStation).add("Plains", 5);

        // Add commanders - the critical test
        testDeck.getOrCreate(DeckSection.Commander).add("Grizzly Bears", 1);
        testDeck.getOrCreate(DeckSection.Commander).add("Hill Giant", 1);
        testDeck.getOrCreate(DeckSection.Commander).add("Giant Spider", 1);
    }

    @Test
    public void testDeckHasCommanderSection() {
        // Verify the deck actually has a Commander section
        Assert.assertTrue(testDeck.has(DeckSection.Commander), "Deck should have Commander section");
    }

    @Test
    public void testCommanderSectionIsNotEmpty() {
        // Verify commanders were added
        CardPool commanders = testDeck.get(DeckSection.Commander);
        Assert.assertNotNull(commanders, "Commander section should not be null");
        Assert.assertEquals(commanders.countAll(), 3, "Should have 3 commanders");
    }

    @Test
    public void testBattleboxConfigGetsCommanders() {
        // Test that BattleboxConfig.getCommanders() actually retrieves them
        CardPool commanders = BattleboxConfig.getCommanders(testDeck);
        Assert.assertNotNull(commanders, "BattleboxConfig.getCommanders() should return commanders");
        Assert.assertEquals(commanders.countAll(), 3, "Should find 3 commanders");
    }

    @Test
    public void testBattleboxConfigGetsLandStation() {
        // Verify land station is also retrievable (sanity check)
        CardPool landStation = BattleboxConfig.getLandStation(testDeck);
        Assert.assertNotNull(landStation, "Land station should be found");
        Assert.assertEquals(landStation.countAll(), 25, "Should have 25 land station cards");
    }

    @Test
    public void testCommandersAndLandStationBothExist() {
        // The critical test: both should exist in the same deck
        CardPool landStation = BattleboxConfig.getLandStation(testDeck);
        CardPool commanders = BattleboxConfig.getCommanders(testDeck);

        Assert.assertNotNull(landStation, "Land station should exist");
        Assert.assertNotNull(commanders, "Commanders should exist");
        Assert.assertFalse(landStation.isEmpty(), "Land station should not be empty");
        Assert.assertFalse(commanders.isEmpty(), "Commanders should not be empty");
    }

    @Test
    public void testNullDeckReturnsNull() {
        // Edge case: null deck
        Assert.assertNull(BattleboxConfig.getCommanders(null), "null deck should return null commanders");
        Assert.assertNull(BattleboxConfig.getLandStation(null), "null deck should return null land station");
    }

    @Test
    public void testMissingCommanderSectionReturnsNull() {
        // Edge case: deck with no Commander section
        Deck deckWithoutCommanders = new Deck("No Commanders");
        deckWithoutCommanders.getOrCreate(DeckSection.Main).add("Forest", 60);

        Assert.assertNull(BattleboxConfig.getCommanders(deckWithoutCommanders),
                   "Deck without Commander section should return null");
    }
}
