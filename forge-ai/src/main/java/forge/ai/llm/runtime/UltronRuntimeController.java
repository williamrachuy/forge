package forge.ai.llm.runtime;

import forge.ai.AiCardMemory;
import forge.ai.llm.UltronConfig;
import forge.ai.llm.UltronStrategicPlan;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Central runtime controller for Ultron's fast non-LLM gameplay AI.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Owns the per-player cached turn intent</li>
 *   <li>Builds decision context cheaply</li>
 *   <li>Dispatches to FastPriorityPolicy or ActionScorer depending on game state</li>
 *   <li>Never calls DeepSeek, UltronAdvisor.chooseSpellAbility,
 *       or chooseFromStrategicPlan</li>
 * </ul>
 *
 * <p>Lifecycle: one instance per (game, player) pair; obtained via
 * {@link #getOrCreate(Game, Player, AiCardMemory)}.
 */
public final class UltronRuntimeController {

    // Weak map so instances are GC'd when games end
    private static final Map<Game, Map<Player, UltronRuntimeController>> INSTANCES =
            new WeakHashMap<>();

    private final Game game;
    private final Player player;
    private final AiCardMemory memory;

    private UltronTurnIntent currentIntent;
    private int currentIntentTurn = -1;
    private UltronTableThreatSummary lastTable;
    private int lastTableTurn = -1;
    // Set by scoreMainPhase before returning, read by doChoose for sim stats
    private int lastPrunedCount = 0;
    private int lastChoiceScore = 0;
    private Set<String> pendingHoldNames = Set.of();
    private Set<String> pendingProtectNames = Set.of();

    private final UltronSimStats simStats = new UltronSimStats();

    private UltronRuntimeController(Game game, Player player, AiCardMemory memory) {
        this.game = game;
        this.player = player;
        this.memory = memory;
    }

    /** Get or create the controller for this game/player pair. Thread-safe. */
    public static synchronized UltronRuntimeController getOrCreate(Game game, Player player,
                                                                    AiCardMemory memory) {
        Map<Player, UltronRuntimeController> byPlayer =
                INSTANCES.computeIfAbsent(game, k -> new java.util.HashMap<>());
        return byPlayer.computeIfAbsent(player,
                p -> new UltronRuntimeController(game, p, memory));
    }

    /** Returns the sim stats for this controller, or null if no instance exists. */
    public static synchronized UltronSimStats getSimStats(Game game, Player player) {
        Map<Player, UltronRuntimeController> byPlayer = INSTANCES.get(game);
        if (byPlayer == null) return null;
        UltronRuntimeController ctrl = byPlayer.get(player);
        return ctrl == null ? null : ctrl.simStats;
    }

    // -----------------------------------------------------------------------
    // Main decision entry point
    // -----------------------------------------------------------------------

    /**
     * Choose a SpellAbility from candidates or pass priority.
     *
     * @param candidates  Forge-validated, pre-sorted candidate list
     * @param gameState   MAIN_PHASE, RESPONDING, or OTHER
     * @return runtime decision (never blocks on LLM)
     */
    public UltronRuntimeDecision choose(List<SpellAbility> candidates,
                                         UltronStrategicPlan.GameState gameState) {
        if (!UltronConfig.enabledForRuntime() || !UltronConfig.isUltronPlayer(player)) {
            return UltronRuntimeDecision.noDecision("runtime not enabled or not ultron profile");
        }

        if (candidates == null || candidates.isEmpty()) {
            return UltronRuntimeDecision.pass("no candidates");
        }

        try {
            return doChoose(candidates, gameState);
        } catch (RuntimeException ex) {
            UltronDecisionLog.error(player, "RuntimeController.choose", ex);
            return UltronRuntimeDecision.fallback("exception: " + ex.getMessage());
        }
    }

    private UltronRuntimeDecision doChoose(List<SpellAbility> candidates,
                                            UltronStrategicPlan.GameState gameState) {
        long start = System.nanoTime();

        // Build/reuse table summary
        UltronTableThreatSummary table = getOrRebuildTable();

        // Build/reuse turn intent
        UltronTurnIntent intent = getOrRebuildIntent(table);

        UltronDecisionLog.logTurnIntent(intent);

        // Build decision context
        long budgetNanos = budgetNanosFor(gameState);
        UltronDecisionContext ctx = new UltronDecisionContext(
                game, player, memory, candidates, table, intent,
                System.nanoTime() + budgetNanos);

        UltronRuntimeDecision decision;

        if (gameState == UltronStrategicPlan.GameState.RESPONDING || !ctx.stackEmpty) {
            // Stack / priority response path
            decision = UltronFastPriorityPolicy.choose(ctx);

        } else if (gameState == UltronStrategicPlan.GameState.MAIN_PHASE) {
            // Main-phase action scoring
            decision = scoreMainPhase(ctx);

        } else {
            // OTHER (combat, upkeep, draw, etc.) — fast pass
            decision = UltronFastPriorityPolicy.choose(ctx);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        UltronDecisionLog.timing(player, UltronDecisionLog.TIMING, elapsedMs, decision.toString());

        // Timeout fallback
        if (ctx.isOverDeadline()) {
            UltronDecisionLog.log(player, UltronDecisionLog.TIMING,
                    "budget exceeded " + elapsedMs + "ms -> fallback");
            decision = UltronRuntimeDecision.fallback("over deadline");
        }

        recordSimDecision(decision, ctx, gameState);
        return decision;
    }

    // -----------------------------------------------------------------------
    // Main-phase scoring
    // -----------------------------------------------------------------------

    private UltronRuntimeDecision scoreMainPhase(UltronDecisionContext ctx) {
        UltronManaReservation reservation = UltronManaReservationPolicy.compute(ctx);
        List<SpellAbility> pruned = UltronCandidatePruner.prune(ctx.candidates, ctx);
        lastPrunedCount = ctx.candidates.size() - pruned.size();
        lastChoiceScore = 0;

        if (pruned.isEmpty()) {
            if (ctx.intent.avoidTappingOut || ctx.intent.reserveCounterspellMana) {
                return UltronRuntimeDecision.pass("all candidates pruned by runtime policy");
            }
            return UltronRuntimeDecision.fallback("all candidates pruned by runtime policy");
        }

        SpellAbility bestSa = null;
        UltronScore bestScore = null;

        for (SpellAbility sa : pruned) {
            if (ctx.isOverDeadline()) break;
            // Skip cards the learner has conclusively identified as losing plays
            if (UltronCardStats.isHardVetoed(sa.getHostCard().getName())) continue;
            UltronScore score = UltronActionScorer.score(sa, ctx, reservation);
            if (bestScore == null || score.value > bestScore.value) {
                bestScore = score;
                bestSa = sa;
            }
        }

        // Always capture best score seen — even for PASS/FALLBACK this tells us what was rejected.
        if (bestScore != null) lastChoiceScore = bestScore.value;

        // Threshold 0: only choose when the scorer is confident the play is correct.
        // Negatively-scored candidates (e.g. engines in fast roles, penalized cards) fall
        // back to Default AI rather than being forced — the scorer's negative signal is
        // real and should be respected.
        if (bestSa != null && bestScore != null && bestScore.value > 0) {
            String reason = "main-phase score=" + bestScore.value + " " + bestScore.reason;
            UltronDecisionLog.log(player, UltronDecisionLog.MAIN,
                    "selected=" + bestSa.getHostCard().getName() + " " + reason);
            return UltronRuntimeDecision.choose(bestSa, reason);
        }

        // If intent says avoid tapping out and nothing scores well, pass to preserve mana
        if (ctx.intent.avoidTappingOut && bestScore != null && bestScore.value <= 5) {
            return UltronRuntimeDecision.pass("preserving mana per intent");
        }

        // Fall through to Forge default ordering
        return UltronRuntimeDecision.fallback("no strongly-scored candidate");
    }

    // -----------------------------------------------------------------------
    // Sim stats recording
    // -----------------------------------------------------------------------

    private void recordSimDecision(UltronRuntimeDecision decision, UltronDecisionContext ctx,
                                   UltronStrategicPlan.GameState gameState) {
        String phase = switch (gameState) {
            case MAIN_PHASE -> "MAIN";
            case RESPONDING -> "RESPOND";
            default -> "OTHER";
        };
        String kind = switch (decision.getKind()) {
            case CHOOSE      -> "CHOOSE";
            case PASS        -> "PASS";
            case FALLBACK    -> "FALLBACK";
            case NO_DECISION -> "NO_DECISION";
        };
        String chosenName = null;
        if (decision.getKind() == UltronRuntimeDecision.Kind.CHOOSE && decision.getSpellAbility() != null) {
            chosenName = decision.getSpellAbility().getHostCard().getName();
        }
        // Capture reason for all kinds: CHOOSE → why this card; PASS → why passed or threat severity.
        String scoreReason = decision.getReason().isEmpty() ? null : decision.getReason();
        simStats.record(new UltronSimStats.Decision(
                game.getPhaseHandler().getTurn(),
                phase,
                player.getLife(),
                game.getStack().size(),
                ctx.intent.role.toString(),
                kind,
                chosenName,
                lastChoiceScore,
                scoreReason,
                ctx.candidates.size(),
                lastPrunedCount,
                ctx.intent.avoidTappingOut,
                ctx.intent.reserveCounterspellMana
        ));
        // Reset for next call
        lastPrunedCount = 0;
        lastChoiceScore = 0;
    }

    /**
     * Record a NO_DECISION entry when no candidates scored above threshold.
     * Called from AiController when ultronCandidates is empty but the controller
     * is initialized — ensures loss-game stats blocks are populated.
     */
    public void recordNoDecision(UltronStrategicPlan.GameState gameState) {
        String phase = switch (gameState) {
            case MAIN_PHASE -> "MAIN";
            case RESPONDING -> "RESPOND";
            default -> "OTHER";
        };
        UltronTableThreatSummary table = getOrRebuildTable();
        UltronTurnIntent intent = getOrRebuildIntent(table);
        simStats.record(new UltronSimStats.Decision(
                game.getPhaseHandler().getTurn(),
                phase,
                player.getLife(),
                game.getStack().size(),
                intent.role.toString(),
                "NO_DECISION",
                null, 0, null,
                0, 0,
                intent.avoidTappingOut,
                intent.reserveCounterspellMana
        ));
    }

    // -----------------------------------------------------------------------
    // Intent and table cache management
    // -----------------------------------------------------------------------

    private UltronTableThreatSummary getOrRebuildTable() {
        int turn = game.getPhaseHandler().getTurn();
        // Rebuild every turn (cheap enough)
        if (lastTable == null || lastTableTurn != turn) {
            lastTable = UltronThreatModel.analyze(game, player);
            lastTableTurn = turn;
        }
        return lastTable;
    }

    private UltronTurnIntent getOrRebuildIntent(UltronTableThreatSummary table) {
        int turn = game.getPhaseHandler().getTurn();
        if (currentIntent == null || currentIntentTurn != turn) {
            currentIntent = UltronTurnIntentBuilder.build(table, turn,
                    pendingHoldNames, pendingProtectNames);
            currentIntentTurn = turn;
            UltronDecisionLog.log(player, UltronDecisionLog.INTENT,
                    "rebuilt intent: " + currentIntent.reason);
        }
        return currentIntent;
    }

    /** Invalidate cached turn-state — call when the board changes materially. */
    public void invalidateIntent() {
        currentIntent = null;
        currentIntentTurn = -1;
        lastTable = null;
        lastTableTurn = -1;
    }

    /**
     * Inject strategic-plan hold/protect hints from the LLM.
     * Forces an intent rebuild so the hints take effect on the next decision.
     */
    public synchronized void injectPlanHints(Set<String> holdNames, Set<String> protectNames) {
        pendingHoldNames    = holdNames    != null ? Set.copyOf(holdNames)    : Set.of();
        pendingProtectNames = protectNames != null ? Set.copyOf(protectNames) : Set.of();
        currentIntent = null;   // force rebuild with new hints
        currentIntentTurn = -1;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private long budgetNanosFor(UltronStrategicPlan.GameState gameState) {
        int ms = switch (gameState) {
            case MAIN_PHASE  -> UltronConfig.maxRuntimeMainPhaseMs();
            case RESPONDING  -> UltronConfig.maxRuntimeStackMs();
            default          -> UltronConfig.maxRuntimePriorityMs();
        };
        return ms * 1_000_000L;
    }
}
