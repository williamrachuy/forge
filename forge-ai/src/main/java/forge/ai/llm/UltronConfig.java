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
