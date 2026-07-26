package forge.ai.nn;

import com.google.common.eventbus.Subscribe;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.llm.UltronConfig;
import forge.game.Game;
import forge.game.card.CardCollectionView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import org.tinylog.Logger;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
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
 * <p><b>Sampling.</b> TICKET-V4-018a-ext: the primary trigger is now {@link GameEventPlayerPriority},
 * which {@code PhaseHandler.mainLoopStep} fires at EVERY priority window -- sorcery-speed main
 * phases, combat steps, opponent's turn, upkeep/end-of-turn, and in response to anything already on
 * the stack. A state is captured for that window only when the player about to receive priority has
 * at least one playable candidate (see {@link #hasPlayableCandidate}) -- i.e. a real decision, not a
 * forced pass -- so the corpus now includes instant-speed decision points (a held counterspell, a
 * flash creature on the opponent's end step, removal in response to a spell) that the original
 * phase-transition-only trigger could never see. The original {@link #CAPTURED_PHASES}
 * phase-transition trigger (TICKET-V4-018a: MAIN1/MAIN2/both combat-declare phases, broadened from
 * MAIN1-only per TICKET-V4-006) is KEPT as a safety-net second trigger rather than retired -- it
 * fires unconditionally (no playability filter) at phase entry, which guarantees at least one record
 * per sorcery-speed phase even on the rare turn where nobody has anything playable at that instant
 * (e.g. an empty hand with no activatable permanents). Both triggers share ONE dedup key so they
 * never double-log the common case where a phase-transition and the immediately-following priority
 * event describe the identical instant (phase entry, empty stack, active player about to act).
 *
 * <p><b>Dedup key: (turn, phase, decision-maker seat, stack signature).</b> Priority fires many times
 * per turn (every pass, both players, every stack push/pop) -- without dedup this would explode into
 * thousands of near-duplicate records per game. The key captures what actually changes the decision:
 * a new spell/ability hitting the stack changes the signature (new loggable context); repeated
 * priority passes over an unchanged stack by the same player do not re-log. The stack signature is
 * the ordered list of {@link SpellAbilityStackInstance} ids currently on {@link
 * Game#getStack()} (or the literal string {@code "empty"}) -- cheap (no re-encoding), and changes
 * exactly when the stack's actual content changes. The seat is included (not just turn+phase+stack
 * per the original ticket phrasing) because both players can hold priority over the identical
 * (turn, phase, stack) triple in sequence -- e.g. active player passes with nothing to do, then the
 * non-active player gets priority over the SAME stack state but faces a DIFFERENT decision (their own
 * hand/board); collapsing them under one key would silently drop the second player's decision.
 *
 * <p><b>Playability check ({@link #hasPlayableCandidate}).</b> Reuses the same production candidate
 * enumeration {@code AiController.getSpellAbilities}-style code already runs every priority pass --
 * {@link ComputerUtilAbility#getAvailableCards} (hand/graveyard/battlefield/exile/command/top-of-
 * library) followed by {@link ComputerUtilAbility#getSpellAbilities} to expand each card into its
 * candidate {@link SpellAbility} objects -- then filters to {@code sa.canPlay()}. {@code canPlay()}
 * checks zone + timing legality (sorcery-speed-only cards are excluded unless it actually is a legal
 * sorcery-speed window; instants/flash/activated abilities pass at any priority window) WITHOUT
 * checking full mana affordability. Mana-affordability helpers ({@code ComputerUtilMana},
 * {@code ComputerUtilCost.canPayCost}) were deliberately NOT used even though they exist and are
 * "more correct" -- they can consult {@code MyRandom.getRandom()} for AI heuristics (mana-reservation
 * chance rolls), and this class's own contract (see the {@code rng} field below) is that logging must
 * never perturb the game's own random stream. {@code sa.canPlay()} touches no RNG. The accepted
 * consequence is a cheap proxy, not perfect legality: a timing-legal-but-unaffordable spell still
 * counts as "playable" (slight over-capture, never under-capture of real decisions) -- acceptable
 * per this ticket's spec, which explicitly allows a cheap proxy over a divergent legality engine.
 *
 * <p>These remain cheap-to-detect proxies for "decision point" that do not require hooking any AI
 * decision method -- P1.3 is explicitly logging-only, no AI decision logic may be touched. At most
 * {@link #MAX_RECORDS_PER_GAME} records are kept per game: turn 1 is
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
        // TICKET-V4-018a-ext: unified dedup across both triggers (phase-transition + priority). Key
        // is "turn:phaseOrdinal:seat:stackSignature" -- see class javadoc for why the seat is
        // included. A HashSet is fine memory-wise: it is bounded by the number of distinct decision
        // contexts in one (short) game, not by raw event-bus firing count.
        private final Set<String> loggedKeys = new HashSet<>();

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
                maybeCapturePhaseTransition();
            } else if (event instanceof GameEventPlayerPriority priorityEvent) {
                maybeCapturePriority(priorityEvent);
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

        /** Safety-net trigger: unconditionally logs at entry to one of {@link #CAPTURED_PHASES}. */
        private void maybeCapturePhaseTransition() {
            PhaseType phase = game.getPhaseHandler().getPhase();
            if (!CAPTURED_PHASES.contains(phase)) {
                return;
            }
            int turn = game.getPhaseHandler().getTurn();
            Player active = game.getPhaseHandler().getPlayerTurn();
            int actingSeat = players.indexOf(active);
            capture(turn, phase, actingSeat);
        }

        /**
         * Primary trigger: fires at EVERY priority window (TICKET-V4-018a-ext). Logs only when the
         * player about to act has a real decision -- see {@link #hasPlayableCandidate} -- so passing
         * with nothing to do never produces a record.
         */
        private void maybeCapturePriority(GameEventPlayerPriority event) {
            Player priorityPlayer = game.getPlayer(event.priority());
            if (priorityPlayer == null || priorityPlayer.hasLost()) {
                return;
            }
            if (!hasPlayableCandidate(priorityPlayer)) {
                return;
            }
            int turn = game.getPhaseHandler().getTurn();
            int actingSeat = players.indexOf(priorityPlayer);
            capture(turn, event.phase(), actingSeat);
        }

        /** Shared capture path for both triggers -- see class javadoc for the dedup key. */
        private void capture(int turn, PhaseType phase, int actingSeat) {
            String key = turn + ":" + phase.ordinal() + ":" + actingSeat + ":" + stackSignature();
            if (!loggedKeys.add(key)) {
                return; // already logged this exact decision context
            }

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

        /** Ordered stack-instance-id signature, or {@code "empty"} -- see class javadoc. */
        private String stackSignature() {
            if (game.getStack().isEmpty()) {
                return "empty";
            }
            StringBuilder sb = new StringBuilder();
            for (SpellAbilityStackInstance si : game.getStack()) {
                sb.append(si.getId()).append(',');
            }
            return sb.toString();
        }

        /**
         * Cheap "does this player have a real decision right now" check -- see class javadoc for why
         * {@code sa.canPlay()} (zone + timing legality, no mana-affordability, no RNG) was chosen
         * over the heavier mana-affordability helpers.
         */
        private boolean hasPlayableCandidate(Player player) {
            CardCollectionView available = ComputerUtilAbility.getAvailableCards(game, player);
            for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(available, player)) {
                if (sa.canPlay()) {
                    return true;
                }
            }
            return false;
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
