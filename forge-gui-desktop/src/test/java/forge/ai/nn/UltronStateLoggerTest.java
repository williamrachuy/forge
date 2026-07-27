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
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.io.DataInputStream;

/**
 * TICKET-V4-006 (Ultron v4 Phase 1, P1.3): {@link UltronStateLogger} tests.
 *
 * <p>The Java-side round-trip check here reads back the same file with a Java {@link
 * DataInputStream} mirroring {@code UltronStateLogger.GameCollector#writeRecord}'s exact framing,
 * pinning the byte layout. The cross-language half of the round-trip (Java writes -> Python reads
 * -> values match) is proven separately with {@code tools/nn/read_nn_states.py} against a file
 * this test leaves on disk at {@link #FIXTURE_OUTPUT_DIR} -- see that script's module docstring
 * for the exact invocation used to verify it, and FORGE_TRACKER.md TICKET-V4-006 for the recorded
 * output of that run.
 */
public class UltronStateLoggerTest extends AITest {

    /** Deliberately NOT deleted after the test -- tools/nn/read_nn_states.py is run against this
     *  file manually to prove the Python reader parses real Java-written output. */
    static final Path FIXTURE_OUTPUT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "ultron_nn_state_fixture");

    private Game createBattleboxGame(int numPlayers) {
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < numPlayers; i++) {
            Deck d = new Deck();
            Set<AIOption> options = new HashSet<>();
            players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("p" + i, options)));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.Battlebox);
        Match match = new Match(rules, players, "UltronStateLoggerTest");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        return game;
    }

    @Test
    public void testDisabledByDefault() {
        Assert.assertFalse(UltronStateLogger.isEnabled(false),
                "Logging must be off unless the config flag or ULTRON_NN_LOGGING env var enables it");
    }

    @Test
    public void testConfigFlagEnables() {
        Assert.assertTrue(UltronStateLogger.isEnabled(true));
    }

    // -----------------------------------------------------------------------
    // TICKET-V4-020: stats.nnLoggingPhases selector (main1 / priority).
    // -----------------------------------------------------------------------

    @Test
    public void testResolvePhaseModeDefaultsToPriority() {
        Assert.assertEquals(UltronStateLogger.resolvePhaseMode(null), UltronStateLogger.PhaseMode.PRIORITY,
                "Unset config value must preserve today's (pre-knob) behavior: PRIORITY");
        Assert.assertEquals(UltronStateLogger.resolvePhaseMode(""), UltronStateLogger.PhaseMode.PRIORITY);
        Assert.assertEquals(UltronStateLogger.resolvePhaseMode("   "), UltronStateLogger.PhaseMode.PRIORITY);
        Assert.assertEquals(UltronStateLogger.resolvePhaseMode("bogus"), UltronStateLogger.PhaseMode.PRIORITY,
                "Unrecognized value must fall back to PRIORITY, not throw");
    }

    @Test
    public void testResolvePhaseModeRecognizesMain1() {
        Assert.assertEquals(UltronStateLogger.resolvePhaseMode("main1"), UltronStateLogger.PhaseMode.MAIN1);
        Assert.assertEquals(UltronStateLogger.resolvePhaseMode("MAIN1"), UltronStateLogger.PhaseMode.MAIN1,
                "Selector must be case-insensitive");
        Assert.assertEquals(UltronStateLogger.resolvePhaseMode(" Main1 "), UltronStateLogger.PhaseMode.MAIN1,
                "Selector must tolerate surrounding whitespace");
    }

    @Test
    public void testResolvePhaseModeRecognizesPriority() {
        Assert.assertEquals(UltronStateLogger.resolvePhaseMode("priority"), UltronStateLogger.PhaseMode.PRIORITY);
        Assert.assertEquals(UltronStateLogger.resolvePhaseMode("PRIORITY"), UltronStateLogger.PhaseMode.PRIORITY);
    }

    @Test
    public void testMain1ModeSkipsMain2PhaseTransition() throws IOException {
        initAndCreateGame();
        Game game = createBattleboxGame(2);
        List<Player> p = game.getPlayers();
        addCard("Forest", p.get(0));
        addCard("Island", p.get(1));

        Path dir = Files.createTempDirectory("ultron-nn-main1-mode-test");
        long gameId = 555L;
        UltronStateLogger.GameCollector collector =
                new UltronStateLogger.GameCollector(game, gameId, dir, UltronStateLogger.PhaseMode.MAIN1);
        game.subscribeToEvents(collector);

        // MAIN1 mode: only MAIN1 phase-transitions log, and the priority-window trigger never
        // fires at all -- this reproduces V0's original TICKET-V4-006 corpus exactly.
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p.get(0), false, 1);
        game.getAction().checkStateEffects(true);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p.get(0), false, 1);
        game.getAction().checkStateEffects(true);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, p.get(0), false, 1);
        game.getAction().checkStateEffects(true);

        collector.finish(true, false);

        Path out = dir.resolve("nn_states.bin.gz");
        Assert.assertTrue(Files.exists(out), "MAIN1-mode game with one MAIN1 entry must still write a file");
        List<ParsedRecord> records = readAll(out);
        Assert.assertEquals(records.size(), 1,
                "MAIN1 mode must log only the MAIN1 transition, not MAIN2 or COMBAT_DECLARE_ATTACKERS "
                        + "(which the default PRIORITY mode's CAPTURED_PHASES would also capture)");
        Assert.assertEquals(records.get(0).phaseOrdinal, PhaseType.MAIN1.ordinal());
    }

    @Test
    public void testTimeoutGameDiscardedEntirely() throws IOException {
        initAndCreateGame();
        Game game = createBattleboxGame(2);
        List<Player> p = game.getPlayers();
        addCard("Forest", p.get(0));
        addCard("Island", p.get(1));

        Path dir = Files.createTempDirectory("ultron-nn-discard-test");
        UltronStateLogger.GameCollector collector = new UltronStateLogger.GameCollector(game, 12345L, dir);
        game.subscribeToEvents(collector);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p.get(0), false, 1);
        game.getAction().checkStateEffects(true);

        collector.finish(false, true); // timeout=true -> must discard

        Path out = dir.resolve("nn_states.bin.gz");
        Assert.assertFalse(Files.exists(out), "A timed-out game must write nothing at all");
    }

    @Test
    public void testWritesParseableRecordsAndRoundTripsInJava() throws IOException {
        initAndCreateGame();
        Game game = createBattleboxGame(2);
        List<Player> p = game.getPlayers();
        addCard("Forest", p.get(0));
        addCard("Mountain", p.get(0));
        addCard("Grizzly Bears", p.get(0));
        addCard("Island", p.get(1));
        addCard("Swamp", p.get(1));
        p.get(0).setLife(18, null);
        p.get(1).setLife(20, null);

        Files.createDirectories(FIXTURE_OUTPUT_DIR);
        Path existing = FIXTURE_OUTPUT_DIR.resolve("nn_states.bin.gz");
        Files.deleteIfExists(existing);

        long gameId = 987654321L;
        UltronStateLogger.GameCollector collector =
                new UltronStateLogger.GameCollector(game, gameId, FIXTURE_OUTPUT_DIR);
        game.subscribeToEvents(collector);

        // Turn 1: p0 active, MAIN1.
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p.get(0), false, 1);
        game.getAction().checkStateEffects(true);
        // Turn 2: p1 active, MAIN1.
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p.get(1), false, 2);
        game.getAction().checkStateEffects(true);
        // Turn 3: p0 active again, MAIN1 -- also a non-MAIN1 phase must NOT produce a record.
        game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, p.get(0), false, 3);
        game.getAction().checkStateEffects(true);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p.get(0), false, 3);
        game.getAction().checkStateEffects(true);

        collector.finish(true, false);

        Assert.assertTrue(Files.exists(existing), "Non-timeout game with captured records must write a file");

        List<ParsedRecord> records = readAll(existing);
        Assert.assertEquals(records.size(), 3, "One record per MAIN1 phase entered (turns 1, 2, 3)");

        ParsedRecord r0 = records.get(0);
        Assert.assertEquals(r0.magic, UltronStateLogger.MAGIC);
        Assert.assertEquals(r0.formatVersion, UltronStateLogger.FORMAT_VERSION);
        Assert.assertEquals(r0.schemaHash, UltronStateEncoder.SCHEMA_HASH);
        Assert.assertEquals(r0.semanticVersion, UltronStateEncoder.ENCODER_SEMANTIC_VERSION);
        Assert.assertEquals(r0.gameId, gameId);
        Assert.assertEquals(r0.turn, 1);
        Assert.assertEquals(r0.actingSeat, 0);
        Assert.assertEquals(r0.numPlayers, 2, "Both seats alive -- two per-perspective vectors expected");
        for (SeatBlock sb : r0.seats) {
            Assert.assertEquals(sb.vector.length, UltronStateEncoder.VECTOR_LENGTH);
            for (float f : sb.vector) {
                Assert.assertFalse(Float.isNaN(f));
                Assert.assertFalse(Float.isInfinite(f));
            }
        }
        // Both seats survive to the (short, artificial) end of this fixture game -- neither seat
        // conceded, so both should show eliminationTurn == -1 and share placement rank 1.
        for (ParsedRecord r : records) {
            for (SeatBlock sb : r.seats) {
                Assert.assertEquals(sb.eliminationTurn, -1);
                Assert.assertEquals(sb.placement, 1);
            }
        }

        ParsedRecord r2 = records.get(2);
        Assert.assertEquals(r2.turn, 3);
        Assert.assertEquals(r2.actingSeat, 0);
        Assert.assertEquals(r2.gameLength, 3);

        System.out.println("UltronStateLoggerTest: wrote+round-tripped " + records.size()
                + " record(s) to " + existing + " (also used for the Python-reader cross-check, "
                + "see tools/nn/read_nn_states.py)");
    }

    // -----------------------------------------------------------------------
    // Minimal Java-side reader mirroring the exact write format, for in-JVM round-trip pinning.
    // -----------------------------------------------------------------------

    private static List<ParsedRecord> readAll(Path gzFile) throws IOException {
        List<ParsedRecord> out = new java.util.ArrayList<>();
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(gzFile)))) {
            while (true) {
                ParsedRecord r;
                try {
                    r = readOne(in);
                } catch (java.io.EOFException eof) {
                    break;
                }
                out.add(r);
            }
        }
        return out;
    }

    private static ParsedRecord readOne(DataInputStream in) throws IOException {
        ParsedRecord r = new ParsedRecord();
        r.magic = in.readInt();
        r.formatVersion = in.readInt();
        r.schemaHash = in.readLong();
        r.semanticVersion = in.readInt();
        r.gameId = in.readLong();
        r.turn = in.readInt();
        r.phaseOrdinal = in.readInt();
        r.actingSeat = in.readInt();
        r.gameLength = in.readInt();
        r.numPlayers = in.readInt();
        r.seats = new SeatBlock[r.numPlayers];
        for (int i = 0; i < r.numPlayers; i++) {
            SeatBlock sb = new SeatBlock();
            sb.seat = in.readInt();
            int len = in.readInt();
            sb.vector = new float[len];
            for (int j = 0; j < len; j++) {
                sb.vector[j] = in.readFloat();
            }
            sb.heuristicScore = in.readFloat();
            sb.eliminationTurn = in.readInt();
            sb.placement = in.readInt();
            r.seats[i] = sb;
        }
        return r;
    }

    private static final class ParsedRecord {
        int magic, formatVersion, semanticVersion, turn, phaseOrdinal, actingSeat, gameLength, numPlayers;
        long schemaHash, gameId;
        SeatBlock[] seats;
    }

    private static final class SeatBlock {
        int seat;
        float[] vector;
        float heuristicScore;
        int eliminationTurn;
        int placement;
    }
}
