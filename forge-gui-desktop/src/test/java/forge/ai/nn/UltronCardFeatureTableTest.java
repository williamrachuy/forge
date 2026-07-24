package forge.ai.nn;

import forge.ai.AITest;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * TICKET-V4-005 (Ultron v4 Phase 1, P1.1): golden-file pinning for {@link UltronCardFeatureTable}.
 *
 * <p>Twelve named cards chosen to cover every segment of the vector layout (mana value, all 5
 * colors, all card types this table distinguishes, power/toughness, a spread of keywords, and all
 * six role flags exactly once each) plus the two basic-land "everything zero except type+legendary"
 * baseline cases. Each expected vector below was captured from a real run of {@link
 * UltronCardFeatureTable#extractFeatures} against the loaded card DB and is asserted element-by-
 * element (not just a handful of "interesting" slots) specifically so any future change to feature
 * extraction -- intentional or not -- shows up as a concrete, readable diff here instead of silently
 * drifting.
 */
public class UltronCardFeatureTableTest extends AITest {

    private static final float EPS = 1e-6f;

    private static void assertVector(String cardName, float[] expected) {
        float[] actual = UltronCardFeatureTable.getFeatures(cardName);
        Assert.assertEquals(actual.length, UltronCardFeatureTable.CARD_FEATURE_DIM,
                cardName + ": vector length");
        for (int i = 0; i < expected.length; i++) {
            Assert.assertEquals(actual[i], expected[i], EPS,
                    cardName + ": mismatch at index " + i + " (see UltronCardFeatureTable's segment "
                            + "offset constants to identify which feature this is)");
        }
    }

    @Test
    public void testForest() {
        initAndCreateGame();
        // Basic land: no CMC/color/keywords/roles; only the "land" type bit is set.
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.TYPE_OFFSET + 1] = 1f; // land
        assertVector("Forest", expected);
    }

    @Test
    public void testPlains() {
        initAndCreateGame();
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.TYPE_OFFSET + 1] = 1f; // land
        assertVector("Plains", expected);
    }

    @Test
    public void testGrizzlyBearsVanillaCreature() {
        initAndCreateGame();
        // CMC 2, green, creature, 2/2, no keywords, no roles, not legendary.
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.MANA_VALUE_OFFSET] = 0.2f;
        expected[UltronCardFeatureTable.COLOR_OFFSET + 4] = 1f; // green
        expected[UltronCardFeatureTable.TYPE_OFFSET] = 1f; // creature
        expected[UltronCardFeatureTable.PT_OFFSET] = 0.2f;
        expected[UltronCardFeatureTable.PT_OFFSET + 1] = 0.2f;
        assertVector("Grizzly Bears", expected);
    }

    @Test
    public void testSerraAngelFlyingVigilance() {
        initAndCreateGame();
        // CMC 5, white, creature, 4/4, flying + vigilance, not legendary.
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.MANA_VALUE_OFFSET] = 0.5f;
        expected[UltronCardFeatureTable.COLOR_OFFSET] = 1f; // white
        expected[UltronCardFeatureTable.TYPE_OFFSET] = 1f; // creature
        expected[UltronCardFeatureTable.PT_OFFSET] = 0.4f;
        expected[UltronCardFeatureTable.PT_OFFSET + 1] = 0.4f;
        expected[UltronCardFeatureTable.KEYWORD_OFFSET] = 1f; // flying (index 0)
        expected[UltronCardFeatureTable.KEYWORD_OFFSET + 7] = 1f; // vigilance (index 7)
        assertVector("Serra Angel", expected);
    }

    @Test
    public void testLightningBoltIsNotClassifiedAsRemoval() {
        initAndCreateGame();
        // DealDamage-based burn is NOT one of the 6 tracked role flags (removal is Destroy-api only,
        // reusing UltronStackThreatAnalyzer.isRemovalApi) -- documented, not a bug. CMC 1, red, instant.
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.MANA_VALUE_OFFSET] = 0.1f;
        expected[UltronCardFeatureTable.COLOR_OFFSET + 3] = 1f; // red
        expected[UltronCardFeatureTable.TYPE_OFFSET + 2] = 1f; // instant
        assertVector("Lightning Bolt", expected);
    }

    @Test
    public void testDoomBladeRemovalRole() {
        initAndCreateGame();
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.MANA_VALUE_OFFSET] = 0.2f;
        expected[UltronCardFeatureTable.COLOR_OFFSET + 2] = 1f; // black
        expected[UltronCardFeatureTable.TYPE_OFFSET + 2] = 1f; // instant
        expected[UltronCardFeatureTable.ROLE_OFFSET] = 1f; // removal
        assertVector("Doom Blade", expected);
    }

    @Test
    public void testCounterspellCounterspellRole() {
        initAndCreateGame();
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.MANA_VALUE_OFFSET] = 0.2f;
        expected[UltronCardFeatureTable.COLOR_OFFSET + 1] = 1f; // blue
        expected[UltronCardFeatureTable.TYPE_OFFSET + 2] = 1f; // instant
        expected[UltronCardFeatureTable.ROLE_OFFSET + 1] = 1f; // counterspell
        assertVector("Counterspell", expected);
    }

    @Test
    public void testWrathOfGodBoardWipeRole() {
        initAndCreateGame();
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.MANA_VALUE_OFFSET] = 0.4f;
        expected[UltronCardFeatureTable.COLOR_OFFSET] = 1f; // white
        expected[UltronCardFeatureTable.TYPE_OFFSET + 3] = 1f; // sorcery
        expected[UltronCardFeatureTable.ROLE_OFFSET + 2] = 1f; // board wipe
        assertVector("Wrath of God", expected);
    }

    @Test
    public void testDivinationCardDrawRole() {
        initAndCreateGame();
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.MANA_VALUE_OFFSET] = 0.3f;
        expected[UltronCardFeatureTable.COLOR_OFFSET + 1] = 1f; // blue
        expected[UltronCardFeatureTable.TYPE_OFFSET + 3] = 1f; // sorcery
        expected[UltronCardFeatureTable.ROLE_OFFSET + 3] = 1f; // card draw
        assertVector("Divination", expected);
    }

    @Test
    public void testSolRingRampRole() {
        initAndCreateGame();
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.MANA_VALUE_OFFSET] = 0.1f;
        // Sol Ring is colorless -- no color bits set.
        expected[UltronCardFeatureTable.TYPE_OFFSET + 4] = 1f; // artifact
        expected[UltronCardFeatureTable.ROLE_OFFSET + 4] = 1f; // ramp (Mana api)
        assertVector("Sol Ring", expected);
    }

    @Test
    public void testRaiseTheAlarmTokenMakerRole() {
        initAndCreateGame();
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.MANA_VALUE_OFFSET] = 0.2f;
        expected[UltronCardFeatureTable.COLOR_OFFSET] = 1f; // white
        expected[UltronCardFeatureTable.TYPE_OFFSET + 2] = 1f; // instant
        expected[UltronCardFeatureTable.ROLE_OFFSET + 5] = 1f; // token maker
        assertVector("Raise the Alarm", expected);
    }

    @Test
    public void testLlanowarElvesCreatureRamp() {
        initAndCreateGame();
        // A creature that is ALSO ramp (mana ability): both the creature-type bit and the ramp role
        // flag must be set simultaneously -- this is the case most likely to break if ramp detection
        // is ever narrowed to "noncreature only".
        float[] expected = new float[UltronCardFeatureTable.CARD_FEATURE_DIM];
        expected[UltronCardFeatureTable.MANA_VALUE_OFFSET] = 0.1f;
        expected[UltronCardFeatureTable.COLOR_OFFSET + 4] = 1f; // green
        expected[UltronCardFeatureTable.TYPE_OFFSET] = 1f; // creature
        expected[UltronCardFeatureTable.PT_OFFSET] = 0.1f;
        expected[UltronCardFeatureTable.PT_OFFSET + 1] = 0.1f;
        expected[UltronCardFeatureTable.ROLE_OFFSET + 4] = 1f; // ramp
        assertVector("Llanowar Elves", expected);
    }

    @Test
    public void testUnknownCardNameReturnsZeroVectorNotNull() {
        initAndCreateGame();
        float[] v = UltronCardFeatureTable.getFeatures("This Card Does Not Exist In Any Set XYZ");
        Assert.assertNotNull(v);
        Assert.assertEquals(v.length, UltronCardFeatureTable.CARD_FEATURE_DIM);
        for (float f : v) {
            Assert.assertEquals(f, 0f, EPS, "Unknown card must degrade to an all-zero vector");
        }
        Assert.assertEquals(UltronCardFeatureTable.getVocabId("This Card Does Not Exist In Any Set XYZ"),
                UltronCardFeatureTable.UNK_VOCAB_ID);
    }

    @Test
    public void testVocabIdsAreStableAndDistinctForKnownCards() {
        initAndCreateGame();
        int forestId = UltronCardFeatureTable.getVocabId("Forest");
        int plainsId = UltronCardFeatureTable.getVocabId("Plains");
        Assert.assertNotEquals(forestId, UltronCardFeatureTable.UNK_VOCAB_ID);
        Assert.assertNotEquals(plainsId, UltronCardFeatureTable.UNK_VOCAB_ID);
        Assert.assertNotEquals(forestId, plainsId);
        // Stability within a run: repeated lookups return the same id.
        Assert.assertEquals(UltronCardFeatureTable.getVocabId("Forest"), forestId);
    }
}
