package forge.ai.simulation;

import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.google.common.collect.HashMultiset;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.SharedPlayerZone;
import forge.game.zone.ZoneType;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TICKET-V3-201 (Ultron v3 Phase 2, P2.1): GameCopier Battlebox fidelity harness.
 *
 * Builds a 4-player Battlebox Monarch mid-game state with real shared zones
 * (shared library, shared command/land-station zone, shared graveyard), a
 * commander, monarch, counters, and distinct per-player life totals, then
 * copies it with {@link GameCopier#makeCopy()} and asserts the copy is a
 * faithful structural clone -- not just "didn't throw."
 *
 * Test-setup note: this harness constructs the mid-game state directly via
 * the Game/Player/Card APIs (real named cards via {@link forge.ai.AITest#addCardToZone},
 * shared zones wired the same way {@code forge.game.MatchBattleboxSharedZoneTest}
 * does it in forge-game) rather than driving a live 4-player game loop to that
 * state. Rationale: this ticket is about GameCopier's structural fidelity
 * (does it preserve zone sharing, monarch, counters, commander state), not
 * about turn sequencing/AI decision correctness -- and a hand-built mid-game
 * snapshot exercises the exact same GameCopier code paths a live game would,
 * with far less flakiness. See FORGE_TRACKER.md TICKET-V3-201 for the verdict.
 */
public class GameCopierBattleboxFidelityTest extends SimulationTest {

    private static final int NUM_PLAYERS = 4;

    private Game createBattleboxGame() {
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < NUM_PLAYERS; i++) {
            Deck d = new Deck();
            Set<AIOption> options = new HashSet<>();
            if (i == 0) {
                options.add(AIOption.USE_SIMULATION);
            }
            players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("p" + i, options)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.Battlebox);
        Match match = new Match(rules, players, "BattleboxFidelityTest");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);

        // Wire real SharedPlayerZone instances the same way Match.prepareAllZones()
        // does for a real Battlebox game (see Match.prepareBattleboxSharedLibrary/
        // prepareBattleboxSharedCommand/prepareBattleboxSharedGraveyard).
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

    /**
     * Populates the shared/personal zones with real named cards, sets a
     * commander, gives monarch to a non-host player, sets distinct life
     * totals, puts a +1/+1-countered creature on the battlefield, and
     * advances phase/turn -- approximating a mid-game 4p Battlebox Monarch
     * state without needing to drive the full turn engine.
     */
    private void populateMidGameState(Game game) {
        List<Player> p = game.getPlayers();

        // Shared library: cards moved through it end up here from all 4 players' perspective.
        addCardToZone("Forest", p.get(0), ZoneType.Library);
        addCardToZone("Island", p.get(1), ZoneType.Library);
        addCardToZone("Plains", p.get(2), ZoneType.Library);
        addCardToZone("Swamp", p.get(3), ZoneType.Library);

        // Shared command zone / land station: lands owned by different players sitting in the
        // shared pool, exactly the Battlebox land-station pattern.
        addCardToZone("Mountain", p.get(0), ZoneType.Command);
        addCardToZone("Wastes", p.get(2), ZoneType.Command);

        // Shared graveyard: cards from multiple owners.
        addCardToZone("Lightning Bolt", p.get(1), ZoneType.Graveyard);
        addCardToZone("Doom Blade", p.get(3), ZoneType.Graveyard);

        // Personal hands, distinct per player.
        addCardToZone("Giant Growth", p.get(0), ZoneType.Hand);
        addCardToZone("Counterspell", p.get(1), ZoneType.Hand);

        // A countered permanent on the battlefield (P2.1 assertion (d)).
        Card bear = addCard("Runeclaw Bear", p.get(2));
        bear.setSickness(false);
        bear.addCounterInternal(CounterEnumType.P1P1, 3, p.get(2), false, null, null);

        // A commander for p.get(1), sitting in the shared command zone as per Battlebox rules.
        Card commander = addCardToZone("Kalitas, Traitor of Ghet", p.get(1), ZoneType.Command);
        commander.setCommander(true);
        p.get(1).addCommander(commander);

        // Monarch has "changed hands": start with host, then move to p.get(2).
        game.setBattleboxMonarchChoice(true);
        game.setMonarch(p.get(0));
        game.setMonarch(p.get(2));

        // Distinct life totals per player.
        p.get(0).setLife(17, null);
        p.get(1).setLife(20, null);
        p.get(2).setLife(9, null);
        p.get(3).setLife(14, null);

        // Mid-game turn/phase state.
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p.get(1), false, 7);
        game.getAction().checkStateEffects(true);
    }

    /** Canonical structural snapshot of a game, independent of object identity. */
    private static class Snapshot {
        // zone -> multiset of "name" (card names in that zone across the whole game, deduped by
        // the engine's own shared-zone-aware getCardsIn()).
        java.util.Map<ZoneType, Multiset<String>> zoneContents = new java.util.HashMap<>();
        // per-player life totals, ordered by player id.
        List<Integer> lifeTotals = new ArrayList<>();
        // is the monarch's *name* (by RegisteredPlayer name, stable across copy) recorded.
        String monarchName;
        // for each of the 3 shared zone types: do all NUM_PLAYERS players share the identical
        // zone object (by reference, within this game)?
        boolean libraryIsSharedAcrossAllPlayers;
        boolean commandIsSharedAcrossAllPlayers;
        boolean graveyardIsSharedAcrossAllPlayers;
        // name+counters of the countered permanent, found by name.
        int bearP1P1Counters = -1;
        // commander flag + owner name for the commander card, found by name.
        boolean commanderFlagSet;
        String commanderOwnerName;
    }

    private static final ZoneType[] SNAPSHOT_ZONES = new ZoneType[] {
        ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Library,
        ZoneType.Exile, ZoneType.Command,
    };

    private Snapshot takeSnapshot(Game game) {
        Snapshot snap = new Snapshot();

        for (ZoneType zone : SNAPSHOT_ZONES) {
            Multiset<String> names = HashMultiset.create();
            CardCollectionView cards = game.getCardsIn(zone);
            for (Card c : cards) {
                names.add(c.getName());
            }
            snap.zoneContents.put(zone, names);
        }

        List<Player> players = game.getPlayers();
        for (Player p : players) {
            snap.lifeTotals.add(p.getLife());
        }

        snap.monarchName = game.getMonarch() == null ? null : game.getMonarch().getLobbyPlayer().getName();

        snap.libraryIsSharedAcrossAllPlayers = allSameZone(players, ZoneType.Library);
        snap.commandIsSharedAcrossAllPlayers = allSameZone(players, ZoneType.Command);
        snap.graveyardIsSharedAcrossAllPlayers = allSameZone(players, ZoneType.Graveyard);

        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Runeclaw Bear")) {
                snap.bearP1P1Counters = c.getCounters(CounterEnumType.P1P1);
            }
        }
        for (Card c : game.getCardsIn(ZoneType.Command)) {
            if (c.getName().equals("Kalitas, Traitor of Ghet")) {
                snap.commanderFlagSet = c.isCommander();
                snap.commanderOwnerName = c.getOwner().getLobbyPlayer().getName();
            }
        }

        return snap;
    }

    private static List<String> sortedCounts(Multiset<String> multiset) {
        List<String> entries = new ArrayList<>();
        for (Multiset.Entry<String> e : multiset.entrySet()) {
            entries.add(e.getElement() + "x" + e.getCount());
        }
        java.util.Collections.sort(entries);
        return entries;
    }

    private static boolean allSameZone(List<Player> players, ZoneType zoneType) {
        Object first = players.get(0).getZone(zoneType);
        for (Player p : players) {
            if (p.getZone(zoneType) != first) {
                return false;
            }
        }
        return true;
    }

    private void assertSnapshotsMatch(Snapshot orig, Snapshot copy) {
        for (ZoneType zone : SNAPSHOT_ZONES) {
            // Multiset.equals() is content-based (order-independent multiset equality), which is
            // what we want here -- TestNG's Assert.assertEquals(Iterable, Iterable) instead does a
            // *positional* element-by-element comparison, which spuriously fails on HashMultiset's
            // undefined iteration order even when the two multisets' contents are identical.
            Multiset<String> origZone = orig.zoneContents.get(zone);
            Multiset<String> copyZone = copy.zoneContents.get(zone);
            Assert.assertTrue(copyZone.equals(origZone),
                    "Zone contents mismatch for " + zone + ": expected " + sortedCounts(origZone)
                            + " but found " + sortedCounts(copyZone));
        }
        Assert.assertEquals(copy.lifeTotals, orig.lifeTotals, "Life totals mismatch");
        Assert.assertEquals(copy.monarchName, orig.monarchName, "Monarch holder mismatch");

        Assert.assertTrue(orig.libraryIsSharedAcrossAllPlayers, "Original library was not actually shared -- test setup bug");
        Assert.assertTrue(orig.commandIsSharedAcrossAllPlayers, "Original command zone was not actually shared -- test setup bug");
        Assert.assertTrue(orig.graveyardIsSharedAcrossAllPlayers, "Original graveyard was not actually shared -- test setup bug");

        Assert.assertEquals(copy.libraryIsSharedAcrossAllPlayers, orig.libraryIsSharedAcrossAllPlayers,
                "Copy's shared LIBRARY zone is not shared across all 4 players (likely copied as a distinct/duplicated zone per player)");
        Assert.assertEquals(copy.commandIsSharedAcrossAllPlayers, orig.commandIsSharedAcrossAllPlayers,
                "Copy's shared COMMAND (land station) zone is not shared across all 4 players");
        Assert.assertEquals(copy.graveyardIsSharedAcrossAllPlayers, orig.graveyardIsSharedAcrossAllPlayers,
                "Copy's shared GRAVEYARD zone is not shared across all 4 players");

        Assert.assertEquals(copy.bearP1P1Counters, orig.bearP1P1Counters, "+1/+1 counter count mismatch on Runeclaw Bear");
        Assert.assertEquals(copy.commanderFlagSet, orig.commanderFlagSet, "Commander flag mismatch");
        Assert.assertEquals(copy.commanderOwnerName, orig.commanderOwnerName, "Commander owner mismatch");
    }

    @Test
    public void testGameCopierPreservesBattleboxSharedZonesMonarchAndCounters() {
        initAndCreateGame(); // ensures FModel/card db is initialized before we use real card names
        Game game = createBattleboxGame();
        populateMidGameState(game);

        Snapshot origSnapshot = takeSnapshot(game);

        // Sanity: the fixture itself must actually contain what we think it does before we can
        // trust a "match" verdict below.
        Assert.assertEquals(origSnapshot.zoneContents.get(ZoneType.Library).size(), 4);
        // 2 land-station lands + 1 commander + 1 engine-internal "Commander Effect" DetachedCardEffect
        // that Player.addCommander()/createCommanderEffect() always adds to the command zone.
        Assert.assertEquals(origSnapshot.zoneContents.get(ZoneType.Command).size(), 4);
        Assert.assertEquals(origSnapshot.zoneContents.get(ZoneType.Graveyard).size(), 2);
        Assert.assertEquals(origSnapshot.bearP1P1Counters, 3);
        Assert.assertTrue(origSnapshot.commanderFlagSet);
        Assert.assertEquals(origSnapshot.monarchName, "p2");

        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy();

        Snapshot copySnapshot = takeSnapshot(copy);

        assertSnapshotsMatch(origSnapshot, copySnapshot);
    }

    @Test
    public void benchmarkGameCopierThroughputOnBattleboxMidGameState() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        populateMidGameState(game);

        // warm-up (JIT, class loading) -- not measured.
        for (int i = 0; i < 20; i++) {
            new GameCopier(game).makeCopy();
        }

        int iterations = 200;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            new GameCopier(game).makeCopy();
        }
        long elapsedNanos = System.nanoTime() - start;
        double seconds = elapsedNanos / 1_000_000_000.0;
        double copiesPerSecond = iterations / seconds;

        System.out.println(String.format(
                "[TICKET-V3-201/P2.2 data point] GameCopier.makeCopy() on a 4p Battlebox mid-game "
                        + "fixture: %d copies in %.3fs => %.1f copies/sec (single thread)",
                iterations, seconds, copiesPerSecond));

        Assert.assertTrue(copiesPerSecond > 0);
    }
}
