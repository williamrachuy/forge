package forge.ai.llm;

import forge.ai.LobbyPlayerAi;
import forge.game.player.Player;

/**
 * Central feature-flag configuration for Ultron.
 *
 * <p>Runtime gameplay is enabled by default and requires no DeepSeek API key.
 * LLM advisor and strategic-plan calls are opt-in via explicit env vars.
 *
 * <p>All methods read env vars at call time so changes propagate without
 * restart in integration-test environments.
 */
public final class UltronConfig {

    public static final String PROFILE_NAME = "Ultron";

    private UltronConfig() {}

    // -----------------------------------------------------------------------
    // Profile detection
    // -----------------------------------------------------------------------

    /** True if this player is using the Ultron AI profile. */
    public static boolean isUltronPlayer(Player player) {
        if (player == null) return false;
        if (!(player.getLobbyPlayer() instanceof LobbyPlayerAi lpa)) return false;
        return PROFILE_NAME.equalsIgnoreCase(lpa.getAiProfile());
    }

    // -----------------------------------------------------------------------
    // Feature flags
    // -----------------------------------------------------------------------

    /** Fast heuristic runtime — enabled by default. */
    public static boolean enabledForRuntime() {
        return boolEnv("ULTRON_RUNTIME_ENABLED", true);
    }

    /** Blocking LLM gameplay advisor — disabled by default. */
    public static boolean enabledForLlmAdvisor() {
        return boolEnv("ULTRON_LLM_ADVISOR_ENABLED", false);
    }

    /** LLM strategic-plan generation — disabled by default. */
    public static boolean enabledForStrategicPlanLlm() {
        return boolEnv("ULTRON_LLM_STRATEGIC_PLAN_ENABLED", false);
    }

    /** Chat — enabled by default (does not affect gameplay). */
    public static boolean enabledForChat() {
        return boolEnv("ULTRON_CHAT_ENABLED", true);
    }

    /** Table-talk — enabled by default (does not affect gameplay). */
    public static boolean enabledForTableTalk() {
        return boolEnv("ULTRON_TABLE_TALK_ENABLED", true);
    }

    /**
     * Runtime decision logging — disabled by default.
     * Enable with {@code ULTRON_DECISION_LOGGING=true}.
     */
    public static boolean enabledForDecisionLogging() {
        return boolEnv("ULTRON_DECISION_LOGGING", false);
    }

    /**
     * Use Forge simulation evaluator for more accurate ahead/behind detection.
     * Disabled by default because it copies the game and simulates combat.
     * Enable with {@code ULTRON_USE_SIMULATION_EVAL=true}.
     */
    public static boolean useSimulationEval() {
        return boolEnv("ULTRON_USE_SIMULATION_EVAL", false);
    }

    /**
     * TICKET-V4-010 (Ultron v4 Phase 2, P2.4): use the learned {@code NeuralStateEvaluator} inside
     * simulation search instead of the hand-tuned {@code GameStateEvaluator} heuristic. Disabled by
     * default -- this is the single flag that keeps the Default AI (and Ultron itself, until
     * explicitly opted in) byte-identical to pre-neural behavior. Even when true, the neural
     * evaluator is only actually used for a given decision if (a) the simulating player is
     * Ultron-profiled and (b) a model loaded successfully at {@link #nnModelPath()}; otherwise the
     * simulation falls back to the heuristic evaluator exactly as before. Enable with
     * {@code ULTRON_NN_EVAL=true}.
     */
    public static boolean nnEvalEnabled() {
        return boolEnv("ULTRON_NN_EVAL", false);
    }

    /**
     * TICKET-V4-010: filesystem path to the trained {@code UltronValueNet} model artifact (see
     * {@code forge.ai.nn.UltronValueNet}'s binary format javadoc). {@code null} if unset -- callers
     * must treat a null/blank path as "no model configured" and fall back cleanly, never crash.
     * Set with {@code ULTRON_NN_MODEL_PATH=/path/to/model.bin}.
     */
    public static String nnModelPath() {
        String value = System.getenv("ULTRON_NN_MODEL_PATH");
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    // -----------------------------------------------------------------------
    // Timing budgets
    // -----------------------------------------------------------------------

    /** Max ms for an ordinary priority pass (default 10). */
    public static int maxRuntimePriorityMs() {
        return intEnv("ULTRON_RUNTIME_MAX_PRIORITY_MS", 10);
    }

    /** Max ms for a stack-response decision (default 50). */
    public static int maxRuntimeStackMs() {
        return intEnv("ULTRON_RUNTIME_MAX_STACK_MS", 50);
    }

    /** Max ms for main-phase candidate scoring (default 500). */
    public static int maxRuntimeMainPhaseMs() {
        return intEnv("ULTRON_RUNTIME_MAX_MAIN_PHASE_MS", 500);
    }

    /** Max candidates to score before stopping (default 32). */
    public static int maxCandidates() {
        return intEnv("ULTRON_RUNTIME_MAX_CANDIDATES", 32);
    }

    /** Max LLM strategic plans per game (default 1). */
    public static int maxLlmStrategicPlansPerGame() {
        return intEnv("ULTRON_LLM_MAX_STRATEGIC_PLANS_PER_GAME", 1);
    }

    /** Min turns between LLM strategic plan refreshes (default 4). */
    public static int minTurnsBetweenLlmPlans() {
        return intEnv("ULTRON_LLM_MIN_TURNS_BETWEEN_PLANS", 4);
    }

    /**
     * Max LLM strategic plan builds per player turn (default 2: initial plan + 1 anchor-triggered re-plan).
     * Prevents runaway LLM calls when anchors are repeatedly disrupted.
     */
    public static int maxLlmStrategicPlansPerTurn() {
        return intEnv("ULTRON_LLM_MAX_PLANS_PER_TURN", 2);
    }

    /**
     * TICKET-V3-207 (Ultron v3, session 6): max wall-clock seconds one of Ultron's three
     * simulation-based decisions ({@code chooseSpellAbilityToPlay}, {@code declareAttackers},
     * {@code declareBlockers}) is allowed to run before it is abandoned and falls back to
     * inherited ({@code PlayerControllerAi}/{@code AiController}) behavior for that single
     * decision. Session 5's live jstack evidence showed a single decision genuinely progressing
     * through expensive work for 90+ seconds with no backstop of its own short of the whole-game
     * {@code timeoutSeconds} budget (1200s in production configs, as low as 60-120s in
     * fast-iteration diagnostic configs) -- this is the per-decision circuit breaker that was
     * missing. Default 40s: comfortably above the cost of a normal (even Battlebox-sized) decision
     * post session-6 fixes, comfortably below every real config's whole-game timeout, so a single
     * pathologically slow decision can no longer consume an entire game's timeout budget
     * uncontrolled. Configurable via {@code ULTRON_SIM_DECISION_TIMEOUT_SECONDS} for tests/tuning.
     */
    public static int maxSimDecisionTimeoutSeconds() {
        return intEnv("ULTRON_SIM_DECISION_TIMEOUT_SECONDS", 40);
    }

    /**
     * TICKET-V4-011: lever 2 of the abandoned-worker-OOM fix (see FORGE_TRACKER TICKET-V4-011 and
     * TICKET-V4-003's diagnosis). {@code SpellAbilityPicker}'s top-level candidate list (the real,
     * once-per-decision list -- distinct from {@code MAX_LOOKAHEAD_CANDIDATES}, which only bounds
     * the recursive hypothetical-future-turn branch) is otherwise unbounded: on a complex board it
     * can run 10-20+ candidates, each paying a full {@code GameCopier.makeCopy()}, which is what let
     * a single decision blow past {@link #maxSimDecisionTimeoutSeconds()} in the first place. This
     * caps how many top-level candidates {@code UltronPlayerController} lets the picker simulate,
     * selected by a cheap pre-ranking (reuses {@code ComputerUtilAbility.saEvaluator} -- the same
     * comparator {@code AiController} already sorts its own candidate list with -- rather than
     * spending a real simulation just to rank candidates). Default 14: generous enough that it
     * essentially never bites a normal Battlebox hand (session data has not observed >12 legal
     * top-level candidates in one decision), but bounds the pathological-board tail this ticket
     * exists to fix. Only {@code UltronPlayerController} ever calls {@code
     * SpellAbilityPicker#setMaxTopLevelCandidates} with this value -- every other caller of {@code
     * SpellAbilityPicker} (Default AI's own {@code USE_SIMULATION} path in {@code AiController},
     * every existing {@code forge.ai.simulation.*} test) leaves it {@code null}/unset and is
     * therefore unaffected. Configurable via {@code ULTRON_SIM_MAX_TOP_LEVEL_CANDIDATES}.
     */
    public static int maxSimTopLevelCandidates() {
        return intEnv("ULTRON_SIM_MAX_TOP_LEVEL_CANDIDATES", 14);
    }

    /**
     * TICKET-V4-014 (Version A, change 1): recursive lookahead depth {@code
     * UltronPlayerController#chooseSpellAbilityToPlay()} sets on the picker's {@code
     * SimulationController} (via {@code SpellAbilityPicker#setMaxRecursionDepth}). Default 0 -- no
     * lookahead recursion at all; each top-level candidate is scored by its own immediate afterstate
     * only (verified by code read: {@code GameSimulator.simulateSpellAbility}'s {@code
     * eval.getScoreForGameState} call happens unconditionally, before the {@code
     * controller.shouldRecurse()} check that gates recursion -- so depth 0 yields "flat afterstate
     * scoring, no lookahead", not "no evaluation at all"; see FORGE_TRACKER TICKET-V4-014). This
     * removes cost source #1 of the three that made V4-010/011/013's soft-deadline patches
     * insufficient: a depth-1 (or deeper) search multiplies candidate count by {@code
     * MAX_LOOKAHEAD_CANDIDATES} at every recursion level, which the hard copy budget below cannot
     * distinguish from useful work -- it would just make the budget exhaust faster on the SAME
     * top-level candidate instead of being spent across more of them. Configurable (not hardcoded
     * 0) via {@code ULTRON_SIM_MAX_RECURSION_DEPTH} for tuning; {@code intEnv}'s existing "value > 0
     * else default" guard does not fit a legitimate 0 default, so this reads the env var directly
     * rather than through {@code intEnv}.
     */
    public static int maxSimRecursionDepth() {
        String value = System.getenv("ULTRON_SIM_MAX_RECURSION_DEPTH");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int v = Integer.parseInt(value.trim());
            return v >= 0 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // TICKET-V4-014 (Version A, change 2): hard per-decision GameCopier.makeCopy() copy budget
    // -----------------------------------------------------------------------

    /**
     * TICKET-V4-014: the key new mechanism the prior three cost-reduction attempts (V4-010/011/013)
     * lacked -- a HARD ceiling, checked BEFORE each simulation copy is allocated, on how many
     * {@code GameCopier.makeCopy()}-backed simulation copies ONE Ultron decision may spend, across
     * every path that spends them ({@code SpellAbilityPicker#evaluateSa}'s target/mode fan-out,
     * {@code UltronPlayerController}'s combat candidate scoring, and any recursive lookahead the
     * fan-out triggers). Unlike {@code SpellAbilityPicker#deadlineMillis} (a soft wall-clock check
     * that cannot interrupt a copy already in flight, or the several copies a single pathological
     * candidate's target fan-out can trigger before the next checkpoint), a copy COUNT checked
     * before allocating is a true hard bound: the (N+1)th copy is never made at all. Default 18 --
     * generous enough that a normal decision's candidate count/target fan-out never approaches it,
     * small enough that even a fully-exhausted budget completes in low single-digit seconds.
     * Configurable via {@code ULTRON_SIM_MAX_COPIES_PER_DECISION}.
     */
    public static int maxSimCopiesPerDecision() {
        return intEnv("ULTRON_SIM_MAX_COPIES_PER_DECISION", 18);
    }

    /**
     * TICKET-V4-014: per-thread [used, max] counter. {@code null} (the default, and the state for
     * every non-Ultron caller and every existing test that never calls {@link
     * #resetSimCopyBudget()}) means "budget tracking inactive on this thread" -- {@link
     * #tryConsumeSimCopyBudget()} and {@link #simCopyBudgetExceeded()} then always report
     * "unlimited"/"not exceeded", byte-identical to before this mechanism existed. A plain {@code
     * ThreadLocal} (rather than a JVM-wide counter) is correct because {@code
     * UltronPlayerController#runWithDecisionTimeout} runs at most one Ultron simulation-decision
     * worker at a time (see {@code SIMULATION_IN_PROGRESS}'s javadoc) and always on a single
     * dedicated worker thread per decision -- recursion within that decision (lookahead, or a
     * combat candidate's own nested scoring) runs synchronously on the SAME thread, so a
     * thread-scoped counter correctly accumulates across the whole decision tree, not just one
     * call site.
     */
    private static final ThreadLocal<int[]> SIM_COPY_BUDGET = new ThreadLocal<>();

    /**
     * Activates copy-budget tracking for the current thread's decision, using {@link
     * #maxSimCopiesPerDecision()} as the cap. Called once, at the very start of each of Ultron's
     * three simulation-based decisions ({@code chooseSpellAbilityToPlay}, {@code declareAttackers},
     * {@code declareBlockers}) -- see those methods for the matching {@link #clearSimCopyBudget()}
     * in a {@code finally}.
     */
    public static void resetSimCopyBudget() {
        resetSimCopyBudget(maxSimCopiesPerDecision());
    }

    /**
     * As {@link #resetSimCopyBudget()}, but with an explicit cap instead of {@link
     * #maxSimCopiesPerDecision()}'s configured default. Public in the same spirit as {@code
     * GameCopier#resetMakeCopyCallCount}/{@code getMakeCopyCallCount} -- test-support surface, not
     * gated behind a flag, so any JUnit test can force a small deterministic budget without an env
     * var (which cannot be mutated mid-JVM). Production code (the three decision entry points in
     * {@code UltronPlayerController}) always calls the no-arg overload.
     */
    public static void resetSimCopyBudget(int max) {
        SIM_COPY_BUDGET.set(new int[] { 0, Math.max(max, 0) });
    }

    /**
     * Deactivates copy-budget tracking for the current thread, back to the default "unlimited"
     * state. Called in a {@code finally} at the end of each of Ultron's three simulation-based
     * decisions so a stale budget from one decision can never leak into the next (or into a
     * non-Ultron caller sharing the thread pool).
     */
    public static void clearSimCopyBudget() {
        SIM_COPY_BUDGET.remove();
    }

    /**
     * Attempts to spend one unit of the current thread's per-decision copy budget. Returns {@code
     * true} (and increments the used count) if a copy may proceed; {@code false} if the budget is
     * already exhausted, in which case the caller must NOT make the copy. When no budget is active
     * on this thread (the default -- {@link #resetSimCopyBudget()} was never called, or {@link
     * #clearSimCopyBudget()} already ran), always returns {@code true}: unlimited, unchanged
     * behavior for every non-Ultron caller.
     */
    public static boolean tryConsumeSimCopyBudget() {
        int[] state = SIM_COPY_BUDGET.get();
        if (state == null) {
            return true;
        }
        if (state[0] >= state[1]) {
            return false;
        }
        state[0]++;
        return true;
    }

    /**
     * Peek-only check (does not consume): whether the current thread's copy budget is already
     * exhausted. Used by the between-candidate checkpoints (mirroring {@code SpellAbilityPicker}'s
     * {@code deadlineExceeded()} pattern) to stop a loop before even attempting its next candidate,
     * in addition to {@link #tryConsumeSimCopyBudget()}'s hard check immediately before each actual
     * copy. Always {@code false} when no budget is active on this thread.
     */
    public static boolean simCopyBudgetExceeded() {
        int[] state = SIM_COPY_BUDGET.get();
        return state != null && state[0] >= state[1];
    }

    /** Test/logging introspection: copies consumed so far on this thread's active budget (0 if inactive). */
    public static int getSimCopyBudgetUsed() {
        int[] state = SIM_COPY_BUDGET.get();
        return state == null ? 0 : state[0];
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public static boolean boolEnv(String key, boolean defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return defaultValue;
        return switch (value.trim().toLowerCase()) {
            case "1", "true", "yes", "on"  -> true;
            case "0", "false", "no", "off" -> false;
            default -> defaultValue;
        };
    }

    static int intEnv(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            int v = Integer.parseInt(value.trim());
            return v > 0 ? v : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
