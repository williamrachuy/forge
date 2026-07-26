package forge.ai.nn;

import com.google.common.eventbus.Subscribe;
import forge.ai.ComputerUtil;
import forge.ai.llm.UltronConfig;
import forge.game.Game;
import forge.game.event.GameEvent;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import org.tinylog.Logger;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/**
 * TICKET-V4-006 (Ultron v4 Phase 1, P1.3): per-game training-data logger. Modeled on the
 * enable-flag/writer conventions of {@code forge.ai.llm.runtime.UltronOfflineDecisionLogger}, but
 * logs binary {@link UltronStateEncoder} feature vectors instead of prose JSONL -- see
 * {@code ULTRON_V4_NEURAL_PLAN.md} sect. 5.1.
 *
 * <p><b>Off by default, zero cost when disabled.</b> Callers (currently {@code SimulateStats})
 * must not even construct this class unless {@link #isEnabled} returns true -- there is no
 * internal short-circuit for "constructed but disabled" because the intent is that a disabled run
 * allocates nothing and subscribes to no events at all.
 *
 * <p><b>Sampling.</b> One record is captured at each of {@link #CAPTURED_PHASES} per turn (originally
 * MAIN1 only per TICKET-V4-006; broadened to MAIN1/MAIN2/both combat-declare phases by TICKET-V4-018a
 * so the corpus covers the phases where afterstates are actually scored -- see {@link
 * #CAPTURED_PHASES}'s javadoc). These remain cheap-to-detect proxies for "decision point" that do
 * not require hooking any AI decision method -- P1.3 is explicitly logging-only, no AI decision
 * logic may be touched. Deduped per (turn, phase) so each phase-entry logs at most once even if the
 * event bus re-delivers. At most {@link #MAX_RECORDS_PER_GAME} records are kept per game: turn 1 is
 * always kept, the final {@link #ALWAYS_KEEP_FINAL_TURNS} turns are always kept, and the remainder
 * of the budget is filled by uniform random sampling (reservoir-style, decided once at game end
 * since records are buffered in memory for the (typically short) duration of one game) over
 * everything in between.
 *
 * <p><b>Format.</b> Records are written as a simple self-describing binary stream, gzip-compressed,
 * appended to one file per shard directory (never a single shared path -- see {@code
 * tools/simstats/run_parallel.sh}, which already gives every worker JVM its own {@code outputDir}).
 * Every record starts with a 4-byte magic + format version so the Python reader
 * ({@code tools/nn/read_nn_states.py}) can validate framing without any external header state, and
 * so shard files can be concatenated (a valid corpus-merge operation, matching {@code games.jsonl}'s
 * own convention) without any record-boundary bookkeeping.
 *
 * <p><b>Labels are computed at game end, not truly "post-game" as a separate pass.</b> The plan
 * text describes outcome labels as appended post-game by the sim runner. Because this logger
 * buffers every candidate record in memory for the game's whole (short) duration and only writes
 * at {@link #finish}, the game's outcome is already fully known by the time anything reaches disk
 * -- so labels are baked into the same write pass rather than requiring a literal two-pass
 * file rewrite. Timeout games are discarded entirely (nothing is written), matching {@code
 * gate.py}'s denominator policy: a timeout's "winner" is noise, not a label.
 */
public final class UltronStateLogger {

    private UltronStateLogger() {}

    /** Magic + format version for each record's self-describing header. */
    static final int MAGIC = 0x554E5331; // "UNS1"
    static final int FORMAT_VERSION = 1;

    static final int MAX_RECORDS_PER_GAME = 200;
    static final int ALWAYS_KEEP_FINAL_TURNS = 3;

    /**
     * TICKET-V4-018a: the phases at which a state is captured. Previously MAIN1 only (TICKET-V4-006),
     * which left the value net trained exclusively on first-main afterstates even though it is used
     * as a depth-0 policy across ALL phases -- combat and second-main afterstates it never saw
     * (TICKET-V4-009 found 12/13 phase one-hot slots dead in the resulting corpus). This set covers
     * the phases where afterstates are actually scored during play: both main phases and both combat
     * declare steps. Stack-response priority windows are deliberately NOT included here -- detecting
     * "non-empty stack with a player holding priority" cleanly from a {@link GameEventTurnPhase}-only
     * subscription would require also hooking priority/stack events, which complicates the event-bus
     * logic for a distinct trigger type; MAIN/combat coverage is the priority per TICKET-V4-018.
     */
    static final java.util.Set<PhaseType> CAPTURED_PHASES = java.util.EnumSet.of(
            PhaseType.MAIN1, PhaseType.MAIN2,
            PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.COMBAT_DECLARE_BLOCKERS);

    /** True if NN state logging should run for this process/run. Config flag OR's with the env var. */
    public static boolean isEnabled(boolean configFlag) {
        return configFlag || UltronConfig.boolEnv("ULTRON_NN_LOGGING", false);
    }

    /**
     * Per-game collector. Construct one per {@link Game}, subscribe it via {@link
     * Game#subscribeToEvents}, and call {@link #finish} once the game is over. Never construct
     * this when {@link #isEnabled} is false -- callers gate construction itself, not just the
     * writes, so a disabled run allocates nothing.
     */
    public static final class GameCollector {

        private final Game game;
        private final List<Player> players;
        private final long gameId;
        private final Path outputFile;
        private final Random rng;

        private final List<Record> pending = new ArrayList<>();
        private final int[] eliminationTurn; // -1 = not eliminated (won or game ended first)
        private final boolean[] eliminated;
        private int lastLoggedTurn = -1;
        private PhaseType lastLoggedPhase;

        public GameCollector(Game game, long gameId, Path shardOutputDir) {
            this.game = game;
            this.players = new ArrayList<>(game.getPlayers());
            this.gameId = gameId;
            this.outputFile = shardOutputDir.resolve("nn_states.bin.gz");
            // Deterministic per-game RNG so downsampling is reproducible given a game ID, without
            // perturbing the game's own MyRandom stream (that stream drives gameplay and must not
            // be touched by an optional logging feature).
            this.rng = new Random(gameId * 0x9E3779B97F4A7C15L ^ 0xA5A5A5A5A5A5A5A5L);
            this.eliminationTurn = new int[players.size()];
            this.eliminated = new boolean[players.size()];
            java.util.Arrays.fill(eliminationTurn, -1);
        }

        @Subscribe
        public void receive(GameEvent event) {
            detectEliminations();
            if (event instanceof GameEventTurnPhase) {
                maybeCapture();
            }
        }

        private void detectEliminations() {
            int turn = game.getPhaseHandler().getTurn();
            for (int i = 0; i < players.size(); i++) {
                if (!eliminated[i] && players.get(i).hasLost()) {
                    eliminated[i] = true;
                    eliminationTurn[i] = turn;
                }
            }
        }

        private void maybeCapture() {
            PhaseType phase = game.getPhaseHandler().getPhase();
            if (!CAPTURED_PHASES.contains(phase)) {
                return;
            }
            int turn = game.getPhaseHandler().getTurn();
            // Guard against firing twice for the same (turn, phase) if the event bus re-delivers
            // -- keyed on BOTH turn and phase now that multiple phases per turn are captured, not
            // just turn alone (a turn-only key would silently drop every phase after the first).
            if (turn == lastLoggedTurn && phase == lastLoggedPhase) {
                return;
            }
            lastLoggedTurn = turn;
            lastLoggedPhase = phase;

            Player active = game.getPhaseHandler().getPlayerTurn();
            int actingSeat = players.indexOf(active);

            List<Integer> liveSeats = new ArrayList<>();
            List<float[]> vectors = new ArrayList<>();
            List<Float> scores = new ArrayList<>();
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                if (p.hasLost()) {
                    continue;
                }
                liveSeats.add(i);
                vectors.add(UltronStateEncoder.encode(game, p));
                scores.add((float) ComputerUtil.evaluateBoardPosition(null, p));
            }
            if (liveSeats.isEmpty()) {
                return;
            }

            pending.add(new Record(turn, phase.ordinal(), actingSeat, liveSeats, vectors, scores));
        }

        /**
         * Finalizes and writes this game's sampled records, discarding the whole game if it timed
         * out. Safe to call even if logging never captured anything (empty write is a no-op).
         */
        public void finish(boolean completedNormally, boolean timeout) {
            detectEliminations();
            if (timeout || !completedNormally) {
                // Discard entirely per plan sect. 5.1: a timeout's "winner" is noise, not a label.
                // A non-normal completion (crash/exception mid-game) is equally untrustworthy.
                return;
            }
            if (pending.isEmpty()) {
                return;
            }

            int gameLength = game.getPhaseHandler().getTurn();
            int[] placement = computePlacement(gameLength);

            List<Record> toWrite = downsample(pending, MAX_RECORDS_PER_GAME, ALWAYS_KEEP_FINAL_TURNS, rng);

            try {
                Files.createDirectories(outputFile.getParent());
                try (OutputStream fos = Files.newOutputStream(outputFile, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                     GZIPOutputStream gz = new GZIPOutputStream(new BufferedOutputStream(fos));
                     DataOutputStream out = new DataOutputStream(gz)) {
                    for (Record r : toWrite) {
                        writeRecord(out, r, gameLength, placement);
                    }
                }
            } catch (IOException e) {
                Logger.warn("[Ultron] Cannot write NN state log {}: {}", outputFile, e.getMessage());
            }
        }

        /**
         * Placement per seat: 1 = best. The winner (never eliminated) is rank 1; among eliminated
         * seats, a later elimination turn is a better finish (survived longer). Ties (simultaneous
         * elimination) share the same rank -- this is a simple, documented v0 rule, not the final
         * word on tie-breaking; training-time code is free to refine it since this raw
         * elimination-turn data is also logged alongside the derived rank.
         */
        private int[] computePlacement(int gameLength) {
            int n = players.size();
            int[] rank = new int[n];
            // Sort seat indices by "finish quality" descending: not-eliminated (-1) first, then by
            // elimination turn descending.
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> {
                int ea = eliminationTurn[a] < 0 ? Integer.MAX_VALUE : eliminationTurn[a];
                int eb = eliminationTurn[b] < 0 ? Integer.MAX_VALUE : eliminationTurn[b];
                return Integer.compare(eb, ea); // descending: later/never elimination first
            });
            int currentRank = 1;
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    int prevSeat = order[i - 1];
                    int curSeat = order[i];
                    int prevE = eliminationTurn[prevSeat] < 0 ? Integer.MAX_VALUE : eliminationTurn[prevSeat];
                    int curE = eliminationTurn[curSeat] < 0 ? Integer.MAX_VALUE : eliminationTurn[curSeat];
                    if (curE != prevE) {
                        currentRank = i + 1;
                    }
                }
                rank[order[i]] = currentRank;
            }
            return rank;
        }

        private void writeRecord(DataOutputStream out, Record r, int gameLength, int[] placement) throws IOException {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeLong(UltronStateEncoder.SCHEMA_HASH);
            out.writeInt(UltronStateEncoder.ENCODER_SEMANTIC_VERSION);
            out.writeLong(gameId);
            out.writeInt(r.turn);
            out.writeInt(r.phaseOrdinal);
            out.writeInt(r.actingSeat);
            out.writeInt(gameLength);
            out.writeInt(r.liveSeats.size());
            for (int i = 0; i < r.liveSeats.size(); i++) {
                int seat = r.liveSeats.get(i);
                float[] vec = r.vectors.get(i);
                out.writeInt(seat);
                out.writeInt(vec.length);
                for (float f : vec) {
                    out.writeFloat(f);
                }
                out.writeFloat(r.heuristicScores.get(i));
                out.writeInt(eliminationTurn[seat]);
                out.writeInt(placement[seat]);
            }
        }
    }

    /**
     * Keeps at most {@code cap} records: turn-1 records and the final {@code finalTurns} turns'
     * records are always kept; the remaining budget is filled by uniform random sampling over
     * everything else (simple random sample without replacement, decided once the full candidate
     * list -- and hence the game's length -- is known, which is straightforward here because
     * records are buffered per-game rather than streamed).
     */
    static List<Record> downsample(List<Record> all, int cap, int finalTurns, Random rng) {
        if (all.size() <= cap) {
            return all;
        }
        int maxTurn = all.get(all.size() - 1).turn;
        List<Record> always = new ArrayList<>();
        List<Record> rest = new ArrayList<>();
        for (Record r : all) {
            if (r.turn == 1 || r.turn > maxTurn - finalTurns) {
                always.add(r);
            } else {
                rest.add(r);
            }
        }
        if (always.size() >= cap) {
            // Even the "always keep" set exceeds the cap (short/degenerate game) -- keep all of it,
            // slightly over budget rather than dropping turn-1/final-turn data. Rare.
            return always;
        }
        int remainingBudget = cap - always.size();
        List<Record> sampled = new ArrayList<>(rest);
        java.util.Collections.shuffle(sampled, rng);
        List<Record> result = new ArrayList<>(always);
        result.addAll(sampled.subList(0, Math.min(remainingBudget, sampled.size())));
        result.sort(java.util.Comparator.comparingInt(r -> r.turn));
        return result;
    }

    /** One captured decision-point snapshot, pending downsampling/write at game end. */
    static final class Record {
        final int turn;
        final int phaseOrdinal;
        final int actingSeat;
        final List<Integer> liveSeats;
        final List<float[]> vectors;
        final List<Float> heuristicScores;

        Record(int turn, int phaseOrdinal, int actingSeat, List<Integer> liveSeats, List<float[]> vectors,
                List<Float> heuristicScores) {
            this.turn = turn;
            this.phaseOrdinal = phaseOrdinal;
            this.actingSeat = actingSeat;
            this.liveSeats = liveSeats;
            this.vectors = vectors;
            this.heuristicScores = heuristicScores;
        }
    }
}
