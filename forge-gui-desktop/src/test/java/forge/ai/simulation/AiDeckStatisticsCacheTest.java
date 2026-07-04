package forge.ai.simulation;

import com.google.common.collect.Lists;

import forge.ai.AIOption;
import forge.ai.AiDeckStatistics;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TICKET-V3-207: proves the {@link AiDeckStatistics} identity-keyed cache (a) returns
 * mathematically identical results to the uncached computation for the same deck, and (b)
 * actually collapses repeated calls against the same Deck object down to a single expensive
 * "compute" -- the exact property that was missing and caused OOM/timeout crashes when
 * Ultron's nested simulation tree called {@code GameStateEvaluator.getScoreForGameState()} ->
 * {@code AiDeckStatistics.fromPlayer()} many times per single real decision.
 */
public class AiDeckStatisticsCacheTest extends SimulationTest {

    private Deck buildRealDeck(String name) {
        Deck deck = new Deck(name);
        // A handful of real named cards -- enough of a mix (lands + colored spells of
        // different CMC) to exercise every branch of AiDeckStatistics.fromCards().
        deck.getMain().add("Mountain", 8);
        deck.getMain().add("Island", 8);
        deck.getMain().add("Lightning Bolt", 4);
        deck.getMain().add("Counterspell", 4);
        deck.getMain().add("Shivan Dragon", 2);
        return deck;
    }

    private Player createPlayerWithDeck(Game unused, Deck deck, String name) {
        // Build a minimal standalone game/player pair backed by the given Deck instance.
        List<RegisteredPlayer> players = Lists.newArrayList();
        Set<AIOption> options = new HashSet<>();
        RegisteredPlayer rp = new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi(name, options));
        players.add(rp);
        // second, unrelated player so Match/Game construction is valid
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi(name + "-opp", null)));
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "AiDeckStatisticsCacheTest-" + name);
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        return game.getPlayers().get(0);
    }

    @Test
    public void fromDeckCacheReturnsIdenticalValuesToUncachedComputation() {
        initAndCreateGame(); // ensures FModel/card database is initialized
        AiDeckStatistics.clearCacheForTests();
        AiDeckStatistics.resetInstrumentationCounters();

        Deck deckA = buildRealDeck("CacheTestDeckA");
        Player playerA = createPlayerWithDeck(null, deckA, "playerA");

        AiDeckStatistics first = AiDeckStatistics.fromPlayer(playerA);

        // A second, DISTINCT Deck object with identical content -- this must NOT hit the
        // cache (different identity), proving the cache doesn't spuriously alias decks, and
        // must produce numerically identical stats to the first (proving the cache doesn't
        // change the computed value -- this is a pure performance fix).
        Deck deckB = buildRealDeck("CacheTestDeckB");
        Player playerB = createPlayerWithDeck(null, deckB, "playerB");
        AiDeckStatistics second = AiDeckStatistics.fromPlayer(playerB);

        Assert.assertEquals(second.averageCMC, first.averageCMC, 0.0001,
                "cache must not change the computed averageCMC");
        Assert.assertEquals(second.maxCost, first.maxCost);
        Assert.assertEquals(second.maxColoredCost, first.maxColoredCost);
        Assert.assertEquals(second.numLands, first.numLands);
        Assert.assertEquals(second.maxPips, first.maxPips);

        // Both distinct decks required their own compute (identity cache correctly did not
        // alias them), so compute count should be 2 so far.
        Assert.assertEquals(AiDeckStatistics.getComputeCount(), 2,
                "two distinct Deck objects must each be computed once");
    }

    @Test
    public void repeatedCallsAgainstSameDeckObjectComputeOnlyOnce() {
        initAndCreateGame();
        AiDeckStatistics.clearCacheForTests();
        AiDeckStatistics.resetInstrumentationCounters();

        Deck deck = buildRealDeck("CacheTestDeckShared");
        Player player = createPlayerWithDeck(null, deck, "playerShared");

        final int callsToSimulate = 25; // stand-in for main-phase candidates x combat-eval nesting
        AiDeckStatistics last = null;
        for (int i = 0; i < callsToSimulate; i++) {
            AiDeckStatistics stats = AiDeckStatistics.fromPlayer(player);
            if (last != null) {
                // same object identity returned every time from the cache
                Assert.assertSame(stats, last, "cache should return the exact same instance");
            }
            last = stats;
        }

        Assert.assertEquals(AiDeckStatistics.getCallCount(), callsToSimulate,
                "every invocation should be counted");
        Assert.assertEquals(AiDeckStatistics.getComputeCount(), 1,
                "TICKET-V3-207: repeated evaluation of the same real Deck object across a "
                        + "nested simulation tree must only pay the expensive full-deck reparse once");
    }

    @Test
    public void copiedPlayerWithEquivalentDeckStillHitsCache() {
        // Mirrors what forge.ai.simulation.GameCopier#clonePlayer() actually does:
        // `new RegisteredPlayer(p.getDeck())`. RegisteredPlayer's constructor unconditionally
        // calls restoreDeck(), which does `currentDeck = originalDeck.copyTo(...)` -- so this
        // does NOT preserve Deck object identity; every simulated copy gets its own new Deck
        // instance. It DOES preserve Deck content (name + card pools), which is what
        // Deck#equals()/hashCode() key on, so the equals()-keyed cache must still hit even
        // though every "copy" here constructs a structurally-equal-but-not-same Deck object,
        // exactly as GameCopier does across a real simulation tree.
        initAndCreateGame();
        AiDeckStatistics.clearCacheForTests();
        AiDeckStatistics.resetInstrumentationCounters();

        Deck sharedDeck = buildRealDeck("CacheTestDeckCopied");
        Player originalPlayer = createPlayerWithDeck(null, sharedDeck, "original");
        AiDeckStatistics originalStats = AiDeckStatistics.fromPlayer(originalPlayer);

        // Simulate several "GameCopier copies" of the same real player: new RegisteredPlayer
        // constructed from the same source Deck, which -- like GameCopier -- mints a fresh
        // (but content-equal) Deck object each time via restoreDeck()/copyTo().
        for (int i = 0; i < 10; i++) {
            Player copiedPlayer = createPlayerWithDeck(null, sharedDeck, "copy" + i);
            AiDeckStatistics copiedStats = AiDeckStatistics.fromPlayer(copiedPlayer);
            Assert.assertNotSame(copiedPlayer.getRegisteredPlayer().getDeck(), sharedDeck,
                    "sanity check: RegisteredPlayer must mint a new Deck instance, proving "
                            + "an identity-keyed cache would NOT have worked here");
            Assert.assertSame(copiedStats, originalStats,
                    "a copied Player backed by a content-equal Deck must hit the cache");
        }

        Assert.assertEquals(AiDeckStatistics.getComputeCount(), 1,
                "one decklist shared across 11 Player instances (1 original + 10 simulated "
                        + "copies), despite each getting its own Deck object, must still only "
                        + "be computed once");
    }
}
