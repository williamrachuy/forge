package forge.ai;

import forge.card.CardRules;
import forge.card.CardType;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AiDeckStatistics {

    // TICKET-V3-207: fromDeck()/fromPlayer() used to re-parse every card in the deck from
    // scratch (via Card.fromPaperCard() -> CardFactory.getCard(), full card-script/trigger
    // parsing) on EVERY call, with zero caching. Ultron's nested-simulation architecture
    // (main-phase spell sequencing re-evaluating via GameStateEvaluator, which itself
    // recurses into combat simulation and re-evaluates again) calls
    // GameStateEvaluator.getScoreForGameState() -> AiDeckStatistics.fromPlayer() many times
    // per single real decision, so the uncached reparse cost multiplied combinatorially and
    // caused verified OOM crashes / 1200s timeouts in real games.
    //
    // Fix: cache the computed stats keyed by Deck.equals()/hashCode(). IMPORTANT: this is
    // deliberately NOT an identity (IdentityHashMap) cache — an earlier draft of this fix
    // assumed forge.ai.simulation.GameCopier#clonePlayer()'s
    // `new RegisteredPlayer(p.getDeck())` would preserve the same Deck object reference
    // across every simulated copy, but that's false: RegisteredPlayer's constructor
    // unconditionally calls restoreDeck(), which does
    // `currentDeck = originalDeck.copyTo(originalDeck.getName())` — a fresh Deck object is
    // minted every single time a RegisteredPlayer is constructed, including inside
    // GameCopier. So player.getRegisteredPlayer().getDeck() returns a DIFFERENT Deck instance
    // for every simulated copy, and an identity-keyed cache would never hit across the
    // simulation tree (verified by a failing unit test before this was caught).
    //
    // What IS stable across those copies is Deck content: Deck#equals() does a full deep
    // comparison (name + every DeckSection's CardPool contents, see Deck.java), not just a
    // name check, so two Deck instances are equal() if and only if they represent the same
    // decklist. copyTo() preserves name and content exactly, so every simulated copy's Deck
    // is equal() (though never same()) to the real player's Deck, and a standard
    // equals()/hashCode()-keyed map hits correctly across the whole simulation tree. This also
    // means two genuinely different decks that happen to share a name will NOT collide (they
    // won't be equal() unless their contents match too), so this is safe even in a 4-player
    // game where multiple AI profiles could share a deck name.
    //
    // This is correct (not just fast) because:
    //   1. A given Deck's card composition never changes during a game/simulation run in this
    //      format (Battlebox has no in-game deck-modification effects that would change these
    //      aggregate stats; tutoring moves cards between zones, it doesn't alter the deck's
    //      card pool).
    //   2. forge.view.SimulateStats (and SimulateMatch) build the Deck list ONCE per process
    //      and reuse the same Deck objects across every game in a multi-game run, so this
    //      cache also amortizes across games within one JVM, not just within one decision.
    //   3. Memory is bounded: at most one entry per distinct decklist encountered in the
    //      process (a handful, matching player count/roster), not per Player/copy.
    //
    // The `deck.isEmpty()` fallback path in fromPlayer() (synthetic test/match states with no
    // registered deck) reads the player's actual current hand/library contents, which DO
    // change turn to turn — that path is intentionally NOT cached.
    private static final Map<Deck, AiDeckStatistics> DECK_STATS_CACHE = new ConcurrentHashMap<>();

    // Instrumentation for tests/benchmarks to prove the expensive path (full deck reparse) is
    // no longer called combinatorially. callCount = every fromDeck() invocation (cache hit or
    // miss); computeCount = only the expensive uncached reparse. Package-visible reset/read
    // helpers below are used by forge.ai.simulation/ultron tests, not by production code.
    private static volatile int callCount = 0;
    private static volatile int computeCount = 0;

    public static void resetInstrumentationCounters() {
        callCount = 0;
        computeCount = 0;
    }

    public static int getCallCount() {
        return callCount;
    }

    public static int getComputeCount() {
        return computeCount;
    }

    public static void clearCacheForTests() {
        DECK_STATS_CACHE.clear();
    }

    public float averageCMC = 0;
    // TODO implement this. Use a numerically stable algorithm from
    // https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance#Weighted_incremental_algorithm
    public float stddevCMC = 0;
    public int maxCost = 0;
    public int maxColoredCost = 0;

    // in WUBRGC order from ManaCost.getColorShardCounts()
    public int[] maxPips = null;
    // public int[] numSources = new int[6];
    public int numLands = 0;
    public AiDeckStatistics(float averageCMC, float stddevCMC, int maxCost, int maxColoredCost, int[] maxPips, int numLands) {
        this.averageCMC = averageCMC;
        this.stddevCMC = stddevCMC;
        this.maxCost = maxCost;
        this.maxColoredCost = maxColoredCost;
        this.maxPips = maxPips;
        this.numLands = numLands;
    }

    public static AiDeckStatistics fromCards(Iterable<Card> cards) {
        int totalCMC = 0;
        int totalCount = 0;
        int numLands = 0;
        int maxCost = 0;
        int[] maxPips = new int[6];
        int maxColoredCost = 0;
        for (Card c : cards) {
            CardRules rules = c.getRules();
            if (rules == null) {
                System.err.println(c + " CardRules is null" + (c.isToken() ? "/token" : "."));
                continue;
            }
            CardType type = rules.getType();
            if (type.isLand()) {
                numLands += 1;
            } else {
                int cost = rules.getManaCost().getCMC();
                // TODO use alternate casting costs for this, free spells will usually be cast for free
                maxCost = Math.max(maxCost, cost);
                totalCMC += cost;
                totalCount++;
                int[] pips = rules.getManaCost().getColorShardCounts();
                int colored_pips = 0;
                for (int i = 0; i < pips.length; i++) {
                    maxPips[i] = Math.max(maxPips[i], pips[i]);
                    if (i < 5) {
                        colored_pips += pips[i];
                    }
                }
                maxColoredCost = Math.max(maxColoredCost, colored_pips);
            }

            // TODO implement the number of mana sources
            // find the sources
            // What about non-mana-ability mana sources?
            // fetchlands, ramp spells, etc
        }

        return new AiDeckStatistics(totalCount == 0 ? 0 : totalCMC / (float)totalCount,
                0, // TODO use https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance
                maxCost,
                maxColoredCost,
                maxPips,
                numLands
                );
    }

    public static AiDeckStatistics fromDeck(Deck deck, Player player) {
        // TICKET-V3-207: serve from the identity-keyed cache when possible; see the class
        // Javadoc/comment above DECK_STATS_CACHE for why this is safe across GameCopier copies
        // and across games in the same process.
        callCount++;
        AiDeckStatistics cached = DECK_STATS_CACHE.get(deck);
        if (cached != null) {
            return cached;
        }
        computeCount++;

        List<Card> cardlist = new ArrayList<>();
        for (final Map.Entry<DeckSection, CardPool> deckEntry : deck) {
            switch (deckEntry.getKey()) {
                case Main:
                case Commander:
                    for (final Map.Entry<PaperCard, Integer> poolEntry : deckEntry.getValue()) {
                        Card card = Card.fromPaperCard(poolEntry.getKey(), player);
                        cardlist.add(card);
                    }
                    break;
                default:
                    break; //ignore other sections
            }
        }

        AiDeckStatistics computed = fromCards(cardlist);
        DECK_STATS_CACHE.put(deck, computed);
        return computed;
    }

    public static AiDeckStatistics fromPlayer(Player player) {
        Deck deck = player.getRegisteredPlayer().getDeck();
        if (deck.isEmpty()) {
            // we're in a test or some weird match, search through the hand and library and build the decklist
            List<Card> cardlist = new ArrayList<>();
            for (Card c : player.getAllCards()) {
                if (c.getPaperCard() == null) {
                    continue;
                }
                cardlist.add(c);
            }

            return fromCards(cardlist);
        }

        return fromDeck(deck, player);
    }

}
