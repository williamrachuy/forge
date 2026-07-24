package forge.ai.nn;

import com.google.common.collect.Lists;
import forge.ai.AIOption;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
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
import forge.game.zone.SharedPlayerZone;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * TICKET-V4-005 (Ultron v4 Phase 1, P1.2): {@link UltronStateEncoder} tests.
 *
 * <p>Fixture-building follows the convention established by {@code GameCopierBattleboxFidelityTest}
 * and {@code UltronMainPhaseSimulationTest}: real Game/Player/Card objects built directly via the
 * engine APIs (not a driven turn loop), with real {@link SharedPlayerZone}s for the 4-player
 * Battlebox fixtures.
 *
 * <p>The eliminated-seat/transfer-guarantee test deliberately uses a PLAIN (non-Battlebox,
 * non-shared-zone) fixture instead -- see that test's comment for why sharing zones would actually
 * weaken the parity check it exists to prove.
 */
public class UltronStateEncoderTest extends AITest {

    private static final int NUM_PLAYERS = 4;

    // -----------------------------------------------------------------------
    // Fixture builders
    // -----------------------------------------------------------------------

    private Game createBattleboxGame(int numPlayers) {
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < numPlayers; i++) {
            Deck d = new Deck();
            Set<AIOption> options = new HashSet<>();
            players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("p" + i, options)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.Battlebox);
        Match match = new Match(rules, players, "UltronStateEncoderTest");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);

        Player host = game.getPlayers().get(0);
        SharedPlayerZone sharedLibrary = new SharedPlayerZone(ZoneType.Library, host);
        SharedPlayerZone sharedCommand = new SharedPlayerZone(ZoneType.Command, host);
        SharedPlayerZone sharedGraveyard = new SharedPlayerZone(ZoneType.Graveyard, host);
        for (Player p : game.getPlayers()) {
            sharedLibrary.addPlayer(p);
            sharedCommand.addPlayer(p);
            sharedGraveyard.addPlayer(p);
            p.setSharedLibraryZone(sharedLibrary);
            p.setSharedCommandZone(sharedCommand);
            p.setSharedGraveyardZone(sharedGraveyard);
        }
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        return game;
    }

    /** Plain Constructed game, no Battlebox variant, no shared zones -- every zone is personal. */
    private Game createPlainGame(int numPlayers) {
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < numPlayers; i++) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("p" + i, null)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "UltronStateEncoderTest-plain");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        return game;
    }

    // -----------------------------------------------------------------------
    // Vector length invariance
    // -----------------------------------------------------------------------

    @Test
    public void testVectorLengthConstantEmptyBoard() {
        initAndCreateGame();
        Game game = createBattleboxGame(NUM_PLAYERS);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        game.getAction().checkStateEffects(true);

        float[] v = UltronStateEncoder.encode(game, game.getPlayers().get(0));
        Assert.assertEquals(v.length, UltronStateEncoder.VECTOR_LENGTH);
    }

    @Test
    public void testVectorLengthConstantLargeBoard() {
        initAndCreateGame();
        Game game = createBattleboxGame(NUM_PLAYERS);
        List<Player> p = game.getPlayers();

        String[] creaturePool = {"Grizzly Bears", "Serra Angel", "Llanowar Elves", "Runeclaw Bear"};
        String[] noncreaturePool = {"Sol Ring", "Howling Mine", "Rhystic Study"};
        String[] landPool = {"Forest", "Island", "Swamp", "Mountain", "Plains"};

        for (Player pl : p) {
            for (int i = 0; i < 6; i++) {
                addCard(creaturePool[i % creaturePool.length], pl);
            }
            for (int i = 0; i < 3; i++) {
                addCard(noncreaturePool[i % noncreaturePool.length], pl);
            }
            for (int i = 0; i < 8; i++) {
                addCard(landPool[i % landPool.length], pl);
            }
            for (int i = 0; i < 5; i++) {
                addCardToZone("Lightning Bolt", pl, ZoneType.Hand);
            }
            for (int i = 0; i < 4; i++) {
                addCardToZone("Doom Blade", pl, ZoneType.Graveyard);
            }
            addCardToZone("Counterspell", pl, ZoneType.Exile);
        }

        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p.get(1), false, 12);
        game.getAction().checkStateEffects(true);

        float[] v = UltronStateEncoder.encode(game, p.get(0));
        Assert.assertEquals(v.length, UltronStateEncoder.VECTOR_LENGTH,
                "Vector length must not depend on how many cards are on the board");
    }

    // -----------------------------------------------------------------------
    // No NaN/Infinity + sanity report
    // -----------------------------------------------------------------------

    @Test
    public void testNoNaNOrInfinityAndSanityReport() {
        initAndCreateGame();
        String[] cardPool = {
                "Grizzly Bears", "Serra Angel", "Llanowar Elves", "Sol Ring", "Howling Mine",
                "Forest", "Island", "Swamp", "Mountain", "Plains", "Lightning Bolt", "Doom Blade",
                "Counterspell", "Wrath of God", "Divination", "Raise the Alarm", "Runeclaw Bear",
        };
        Random rng = new Random(910123L);

        int n = 50;
        float[] min = null, max = null, sum = null;
        int dim = UltronStateEncoder.VECTOR_LENGTH;
        min = new float[dim];
        max = new float[dim];
        sum = new float[dim];
        java.util.Arrays.fill(min, Float.POSITIVE_INFINITY);
        java.util.Arrays.fill(max, Float.NEGATIVE_INFINITY);

        for (int state = 0; state < n; state++) {
            Game game = createBattleboxGame(NUM_PLAYERS);
            List<Player> p = game.getPlayers();
            for (Player pl : p) {
                int bfCount = rng.nextInt(8);
                for (int i = 0; i < bfCount; i++) {
                    Card c = addCard(cardPool[rng.nextInt(cardPool.length)], pl);
                    if (rng.nextBoolean()) {
                        c.setTapped(true);
                    }
                }
                int handCount = rng.nextInt(6);
                for (int i = 0; i < handCount; i++) {
                    addCardToZone(cardPool[rng.nextInt(cardPool.length)], pl, ZoneType.Hand);
                }
                int gyCount = rng.nextInt(5);
                for (int i = 0; i < gyCount; i++) {
                    addCardToZone(cardPool[rng.nextInt(cardPool.length)], pl, ZoneType.Graveyard);
                }
                pl.setLife(1 + rng.nextInt(30), null);
                if (rng.nextInt(5) == 0) {
                    pl.concede();
                }
            }
            PhaseType[] phases = PhaseType.values();
            game.getPhaseHandler().devModeSet(phases[rng.nextInt(phases.length)], p.get(rng.nextInt(p.size())),
                    false, 1 + rng.nextInt(20));
            game.getAction().checkStateEffects(true);

            for (Player self : p) {
                float[] v = UltronStateEncoder.encode(game, self);
                Assert.assertEquals(v.length, UltronStateEncoder.VECTOR_LENGTH);
                for (int i = 0; i < dim; i++) {
                    float f = v[i];
                    Assert.assertFalse(Float.isNaN(f), "NaN at index " + i + " in state " + state);
                    Assert.assertFalse(Float.isInfinite(f), "Infinity at index " + i + " in state " + state);
                    if (f < min[i]) min[i] = f;
                    if (f > max[i]) max[i] = f;
                    sum[i] += f;
                }
            }
        }

        int totalSamples = n * NUM_PLAYERS;
        int deadFeatures = 0;
        StringBuilder report = new StringBuilder();
        report.append("UltronStateEncoder sanity report over ").append(totalSamples)
                .append(" perspective-samples (").append(n).append(" states x ").append(NUM_PLAYERS)
                .append(" seats), vector length ").append(dim).append(":\n");
        for (int i = 0; i < dim; i++) {
            float mean = sum[i] / totalSamples;
            if (min[i] == max[i]) {
                deadFeatures++;
            }
            if (i < 20 || min[i] != max[i]) {
                report.append(String.format("  [%4d] min=%.4f max=%.4f mean=%.4f%s%n",
                        i, min[i], max[i], mean, (min[i] == max[i] ? "  (DEAD)" : "")));
            }
        }
        report.append("Dead (constant) features: ").append(deadFeatures).append(" / ").append(dim).append('\n');
        System.out.println(report);

        // Not a hard assertion on dead-feature count (many slots are legitimately near-always-zero,
        // e.g. rare keywords/roles, in a 50-state sample over a ~17-card pool) -- this test's job is
        // to print the report so a human can eyeball it, per the ticket's sanity-check requirement.
    }

    // -----------------------------------------------------------------------
    // Perspective invariance
    // -----------------------------------------------------------------------

    @Test
    public void testPerspectiveInvarianceSelfBlockAndOpponentOrdering() {
        initAndCreateGame();
        Game game = createBattleboxGame(NUM_PLAYERS);
        List<Player> p = game.getPlayers();

        // Distinct, easily-countable battlefield creature counts per seat.
        addCards("Grizzly Bears", 1, p.get(0));
        addCards("Grizzly Bears", 2, p.get(1));
        addCards("Grizzly Bears", 3, p.get(2));
        addCards("Grizzly Bears", 4, p.get(3));

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p.get(0));
        game.getAction().checkStateEffects(true);

        int creaturesCountIdx = UltronStateEncoder.SELF_BF_CREATURES_OFFSET + 2 * UltronStateEncoder.BATTLEFIELD_CARD_DIM;

        // From seat 0's perspective: self=p0(1 creature), opp1=p1(2), opp2=p2(3), opp3=p3(4).
        float[] v0 = UltronStateEncoder.encode(game, p.get(0));
        assertPoolCount(v0, UltronStateEncoder.SELF_OFFSET, creaturesCountIdx, 1);
        assertPoolCount(v0, UltronStateEncoder.OPP_BASE_OFFSET + 0 * UltronStateEncoder.OPP_BLOCK_SIZE,
                UltronStateEncoder.OPP_BF_CREATURES_OFFSET + 2 * UltronStateEncoder.BATTLEFIELD_CARD_DIM, 2);
        assertPoolCount(v0, UltronStateEncoder.OPP_BASE_OFFSET + 1 * UltronStateEncoder.OPP_BLOCK_SIZE,
                UltronStateEncoder.OPP_BF_CREATURES_OFFSET + 2 * UltronStateEncoder.BATTLEFIELD_CARD_DIM, 3);
        assertPoolCount(v0, UltronStateEncoder.OPP_BASE_OFFSET + 2 * UltronStateEncoder.OPP_BLOCK_SIZE,
                UltronStateEncoder.OPP_BF_CREATURES_OFFSET + 2 * UltronStateEncoder.BATTLEFIELD_CARD_DIM, 4);

        // From seat 1's perspective: self=p1(2), opp1=p2(3), opp2=p3(4), opp3=p0(1) -- turn order wraps.
        float[] v1 = UltronStateEncoder.encode(game, p.get(1));
        assertPoolCount(v1, UltronStateEncoder.SELF_OFFSET, creaturesCountIdx, 2);
        assertPoolCount(v1, UltronStateEncoder.OPP_BASE_OFFSET + 0 * UltronStateEncoder.OPP_BLOCK_SIZE,
                UltronStateEncoder.OPP_BF_CREATURES_OFFSET + 2 * UltronStateEncoder.BATTLEFIELD_CARD_DIM, 3);
        assertPoolCount(v1, UltronStateEncoder.OPP_BASE_OFFSET + 1 * UltronStateEncoder.OPP_BLOCK_SIZE,
                UltronStateEncoder.OPP_BF_CREATURES_OFFSET + 2 * UltronStateEncoder.BATTLEFIELD_CARD_DIM, 4);
        assertPoolCount(v1, UltronStateEncoder.OPP_BASE_OFFSET + 2 * UltronStateEncoder.OPP_BLOCK_SIZE,
                UltronStateEncoder.OPP_BF_CREATURES_OFFSET + 2 * UltronStateEncoder.BATTLEFIELD_CARD_DIM, 1);
    }

    private void assertPoolCount(float[] v, int blockOffset, int countIdxWithinBlock, int expectedCount) {
        float actual = v[blockOffset + countIdxWithinBlock] * 20f; // pool count is stored scaled /20
        Assert.assertEquals(Math.round(actual), expectedCount,
                "Pooled creature count mismatch at block offset " + blockOffset);
    }

    // -----------------------------------------------------------------------
    // Land mana-color production (TICKET-V4-006)
    // -----------------------------------------------------------------------

    /**
     * TICKET-V4-006: land color production must come from the card's real mana abilities, not
     * from matching basic land subtype names. Measured against the Battlebox pool: 60 of 80 lands
     * (every karoo, every temple, every shockland) have no basic land subtype at all and were
     * previously encoded as producing zero colors. Pins the fix for one land of each shape:
     * karoo (Azorius Chancery -> W+U), temple (Temple of Enlightenment -> W+U, "Combo" produced
     * string), shockland (Hallowed Fountain -> W+U via basic land subtypes Plains+Island), basic
     * (Forest -> G), and a colorless utility land (Wastes -> "other" slot only).
     */
    @Test
    public void testLandColorProductionFromManaAbilitiesNotSubtypeNames() {
        initAndCreateGame();
        Game game = createBattleboxGame(NUM_PLAYERS);
        List<Player> p = game.getPlayers();

        addCard("Azorius Chancery", p.get(0));
        addCard("Temple of Enlightenment", p.get(1));
        addCard("Hallowed Fountain", p.get(2));
        addCard("Forest", p.get(3));

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p.get(0));
        game.getAction().checkStateEffects(true);

        assertLandColors(UltronStateEncoder.encode(game, p.get(0)), UltronStateEncoder.SELF_LAND_COLORS_OFFSET,
                "Azorius Chancery (karoo)", true, true, false, false, false, false);
        assertLandColors(UltronStateEncoder.encode(game, p.get(1)), UltronStateEncoder.SELF_LAND_COLORS_OFFSET,
                "Temple of Enlightenment", true, true, false, false, false, false);
        assertLandColors(UltronStateEncoder.encode(game, p.get(2)), UltronStateEncoder.SELF_LAND_COLORS_OFFSET,
                "Hallowed Fountain (shockland)", true, true, false, false, false, false);
        assertLandColors(UltronStateEncoder.encode(game, p.get(3)), UltronStateEncoder.SELF_LAND_COLORS_OFFSET,
                "Forest (basic)", false, false, false, false, true, false);
    }

    @Test
    public void testColorlessUtilityLandProducesOnlyOtherSlot() {
        initAndCreateGame();
        Game game = createBattleboxGame(NUM_PLAYERS);
        List<Player> p = game.getPlayers();

        addCard("Wastes", p.get(0));

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p.get(0));
        game.getAction().checkStateEffects(true);

        assertLandColors(UltronStateEncoder.encode(game, p.get(0)), UltronStateEncoder.SELF_LAND_COLORS_OFFSET,
                "Wastes (colorless)", false, false, false, false, false, true);
    }

    /** W,U,B,R,G,other order, matching {@code writeLandColorCounts}'s slot layout. */
    private void assertLandColors(float[] v, int base, String label,
            boolean w, boolean u, boolean b, boolean r, boolean g, boolean other) {
        boolean[] expected = {w, u, b, r, g, other};
        String[] names = {"W", "U", "B", "R", "G", "other"};
        for (int i = 0; i < expected.length; i++) {
            float actual = v[base + i];
            float expectedVal = expected[i] ? 0.1f : 0f; // one land, scaled /10
            Assert.assertEquals(actual, expectedVal, 1e-6f,
                    label + ": land-color slot [" + names[i] + "] mismatch");
        }
    }

    // -----------------------------------------------------------------------
    // Eliminated-player masking / 1v1-as-4p transfer guarantee
    // -----------------------------------------------------------------------

    /**
     * The core transfer guarantee from plan sect. 4.1 / design decision 2: a real 2-player game and
     * a real 4-player game where the other two seats are already eliminated must encode BYTE-
     * IDENTICALLY for the two active seats. Uses plain (non-Battlebox, non-shared-zone) fixtures
     * deliberately -- Battlebox's shared graveyard/library/command zones return the same underlying
     * collection for every player regardless of seat count, so they wouldn't meaningfully exercise
     * this per-seat parity; personal zones do.
     */
    @Test
    public void testEliminatedSeatsMatchGenuineTwoPlayerGame() {
        initAndCreateGame();

        // (a) genuine 2-player game.
        Game game2p = createPlainGame(2);
        List<Player> p2 = game2p.getPlayers();
        populateActiveSeat(p2.get(0), "Grizzly Bears", "Lightning Bolt", 20);
        populateActiveSeat(p2.get(1), "Serra Angel", "Counterspell", 15);
        game2p.getPhaseHandler().devModeSet(PhaseType.MAIN1, p2.get(0), false, 3);
        game2p.getAction().checkStateEffects(true);
        float[] v2p = UltronStateEncoder.encode(game2p, p2.get(0));

        // (b) 4-player game, seats 2 and 3 immediately eliminated (concede) before any zone
        // population -- seats 0 and 1 get IDENTICAL content to (a).
        Game game4p = createPlainGame(4);
        List<Player> p4 = game4p.getPlayers();
        p4.get(2).concede();
        p4.get(3).concede();
        populateActiveSeat(p4.get(0), "Grizzly Bears", "Lightning Bolt", 20);
        populateActiveSeat(p4.get(1), "Serra Angel", "Counterspell", 15);
        game4p.getPhaseHandler().devModeSet(PhaseType.MAIN1, p4.get(0), false, 3);
        game4p.getAction().checkStateEffects(true);
        float[] v4p = UltronStateEncoder.encode(game4p, p4.get(0));

        Assert.assertEquals(v4p.length, v2p.length);
        Assert.assertEquals(v4p, v2p,
                "A real 2-player game and a 4-player game with the other two seats already "
                        + "eliminated must encode byte-identically for the shared active seats "
                        + "(TICKET-V4-005 design decision 2 / transfer guarantee)");

        // And explicitly: opp2/opp3 blocks in both are all-zero-plus-eliminated-flag.
        for (int oppIdx : new int[]{1, 2}) { // opp slots 1 and 2 (0-based -> "opp2"/"opp3")
            int base = UltronStateEncoder.OPP_BASE_OFFSET + oppIdx * UltronStateEncoder.OPP_BLOCK_SIZE;
            Assert.assertEquals(v2p[base + UltronStateEncoder.OPP_ELIMINATED_OFFSET], 1f);
            Assert.assertEquals(v4p[base + UltronStateEncoder.OPP_ELIMINATED_OFFSET], 1f);
            for (int i = 0; i < UltronStateEncoder.OPP_BLOCK_SIZE; i++) {
                if (i == UltronStateEncoder.OPP_ELIMINATED_OFFSET) continue;
                Assert.assertEquals(v2p[base + i], 0f, "2p fixture: non-eliminated-flag byte should be zero");
                Assert.assertEquals(v4p[base + i], 0f, "4p fixture: non-eliminated-flag byte should be zero");
            }
        }
    }

    private void populateActiveSeat(Player pl, String creatureName, String handCardName, int life) {
        addCard(creatureName, pl);
        addCardToZone(handCardName, pl, ZoneType.Hand);
        pl.setLife(life, null);
    }
}
