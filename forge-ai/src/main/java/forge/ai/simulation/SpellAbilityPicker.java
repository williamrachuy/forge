package forge.ai.simulation;

import forge.ai.*;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.ability.LearnAi;
import forge.ai.llm.UltronConfig;
import forge.ai.nn.NeuralStateEvaluator;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityCondition;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import forge.util.TextUtil;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class SpellAbilityPicker {
    // TICKET-V3-207 (Ultron v3, session 4): candidate-breadth cap applied only to the RECURSIVE
    // (hypothetical-future-turn) lookahead branch of chooseSpellAbilityToPlay(SimulationController)
    // -- see that method's inline comment for the full rationale. Chosen at the upper end of the
    // plan's own stated "3-6 main-phase/attack candidates" pruning design (not below it).
    private static final int MAX_LOOKAHEAD_CANDIDATES = 6;

    private Game game;
    private Player player;
    private Score bestScore;
    private boolean printOutput = false;
    private SpellAbilityChoicesIterator interceptor;

    private Plan plan;
    private int numSimulations;

    // TICKET-V3-207 (Ultron v3, session 4): null means "use SimulationController's own shared
    // default (3)" -- the pre-existing behavior for every non-Ultron caller of this class. Set via
    // setMaxRecursionDepth() specifically for Ultron's Battlebox usage (UltronPlayerController),
    // where the combination of a 3-ply-deep recursive search and Battlebox's shared-zone
    // architecture (every GameCopier.makeCopy() anywhere in the tree re-parses the entire shared
    // zones, including a several-hundred-card shared Library, from scratch) made the existing
    // MAX_LOOKAHEAD_CANDIDATES breadth cap alone insufficient -- a real 3-game smoke test at
    // -Xmx3g still OOM'd. See SimulationController's matching constructor javadoc.
    private Integer maxRecursionDepth;

    /**
     * TICKET-V4-011 (root-cause fix for the abandoned-worker OOM leak, FORGE_TRACKER TICKET-V4-011,
     * diagnosed in TICKET-V4-003): optional cooperative deadline, in epoch millis, for the top-level
     * candidate search below. {@code 0} (the default, and the value every non-Ultron caller and
     * every existing test leaves it at) means "no deadline" -- {@link #deadlineExceeded()} is then
     * always {@code false} and the search loops run to completion exactly as they always have,
     * byte-identical control flow to before this field existed. Only {@code
     * UltronPlayerController#chooseSpellAbilityToPlay()} ever calls {@link #setDeadlineMillis}, to a
     * safe margin below {@code UltronConfig#maxSimDecisionTimeoutSeconds()} -- see that call site's
     * comment for why. The point of checking this cooperatively, inside the search, rather than
     * relying solely on {@code UltronPlayerController}'s outer {@code FutureTask} timeout: a
     * checkpoint here lets the search finish ON ITS OWN WORKER THREAD within budget, returning a
     * real best-so-far candidate, instead of being abandoned mid-allocation by the outer timeout --
     * which is exactly the mechanism TICKET-V4-003 identified as the leak's root cause ("no
     * cooperative interrupt checkpoint deep inside the search").
     */
    private volatile long deadlineMillis;

    /**
     * TICKET-V4-011: set true whenever a search this instance ran most recently stopped early
     * because {@link #deadlineExceeded()} fired, reset to false at the start of every top-level
     * {@link #chooseSpellAbilityToPlay(SimulationController)} call. Package-private -- test/logging
     * introspection only, mirroring {@link #getEvaluatorForTesting()}'s seam.
     */
    private boolean lastSearchHitDeadline;

    /**
     * TICKET-V4-011 (lever 2, breadth cap for Ultron): optional cap on how many top-level candidates
     * {@link #chooseSpellAbilityToPlayImpl} will simulate. {@code null} (the default, and the value
     * every non-Ultron caller and every existing test leaves it at) means "no cap" -- unchanged
     * behavior. Only {@code UltronPlayerController} sets this, to {@code
     * UltronConfig#maxSimTopLevelCandidates()}. See {@link #capTopLevelCandidates} for the
     * pre-ranking used to choose which candidates survive the cap.
     */
    private Integer maxTopLevelCandidates;

    /**
     * TICKET-V4-010 (Ultron v4 Phase 2, P2.4): the {@link StateEvaluator} this picker's decisions
     * are scored with -- {@link GameStateEvaluator} (the heuristic) for every non-Ultron profile
     * and for Ultron whenever the neural path is disabled or unavailable; {@link
     * NeuralStateEvaluator} only when {@code ULTRON_NN_EVAL=true} AND {@link #player} is
     * Ultron-profiled AND a model loaded successfully. This is the injection point plan sect. 4.4
     * calls for "threading the choice from SpellAbilityPicker (which knows its player) over a bare
     * global flag" -- a hypothetical Default-profile simulation caller (there is none today, see
     * FORGE_TRACKER TICKET-V4-010) would resolve {@code isUltronPlayer(player)} false and get the
     * exact same heuristic evaluator as always.
     *
     * <p><b>Resolved lazily, NOT in the constructor.</b> {@code AiController}'s constructor
     * unconditionally builds a {@code SpellAbilityPicker} (its {@code simPicker} field) for every
     * AI-controlled player, and that construction happens from inside {@code
     * LobbyPlayerAi.createControllerFor} -- BEFORE {@code Player.setFirstController(...)} runs.
     * {@code UltronConfig.isUltronPlayer} calls {@code Player.getLobbyPlayer()}, which itself
     * delegates to {@code getController().getLobbyPlayer()}: calling it eagerly in this
     * constructor NPEs on {@code getController() == null} for every single AI player, every
     * profile, unconditionally -- this was caught by TICKET-V4-010's own smoke run (a real
     * {@code SimulateStats} game construction), not by any unit test, because every unit test in
     * this suite happens to construct its {@code SpellAbilityPicker} fixtures directly against an
     * already-fully-wired {@code Player}, well after {@code Game} construction completes -- the
     * exact ordering that does NOT reproduce the bug. Resolving lazily on first actual use (i.e.
     * once a real decision is being made, long after controller wiring is done) sidesteps this
     * bootstrapping hazard entirely and is also strictly cheaper for the common (never-simulates)
     * case, since {@link #chooseSpellAbilityToPlay} is not guaranteed to be called for every
     * picker that gets constructed.
     */
    private StateEvaluator eval;

    public SpellAbilityPicker(Game game, Player player) {
        this.game = game;
        this.player = player;
    }

    private StateEvaluator eval() {
        if (eval == null) {
            eval = selectEvaluator(player);
        }
        return eval;
    }

    private static StateEvaluator selectEvaluator(Player player) {
        if (UltronConfig.nnEvalEnabled() && UltronConfig.isUltronPlayer(player) && NeuralStateEvaluator.isAvailable()) {
            return new NeuralStateEvaluator();
        }
        return new GameStateEvaluator();
    }

    /** Test-only introspection of which {@link StateEvaluator} this picker resolves to. */
    StateEvaluator getEvaluatorForTesting() {
        return eval();
    }

    public void setInterceptor(SpellAbilityChoicesIterator in) {
        this.interceptor = in;
    }

    public void setMaxRecursionDepth(int maxRecursionDepth) {
        this.maxRecursionDepth = maxRecursionDepth;
    }

    /**
     * TICKET-V4-011: see {@link #deadlineMillis}'s javadoc. Pass {@code 0} to clear (the default,
     * "no deadline" state) -- callers that set a deadline for one decision should clear it
     * afterwards, since the underlying {@code SpellAbilityPicker} instance is reused across
     * decisions ({@code AiController} constructs exactly one per player).
     */
    public void setDeadlineMillis(long deadlineMillis) {
        this.deadlineMillis = deadlineMillis;
    }

    private boolean deadlineExceeded() {
        return deadlineMillis > 0 && System.currentTimeMillis() > deadlineMillis;
    }

    /** TICKET-V4-011: test/logging introspection only -- see {@link #lastSearchHitDeadline}. */
    boolean wasDeadlineExceededForTesting() {
        return lastSearchHitDeadline;
    }

    /**
     * TICKET-V4-011 (lever 2): see {@link #maxTopLevelCandidates}'s javadoc. Pass {@code null} to
     * clear (the default, "no cap" state).
     */
    public void setMaxTopLevelCandidates(Integer maxTopLevelCandidates) {
        this.maxTopLevelCandidates = maxTopLevelCandidates;
    }

    /**
     * TICKET-V4-011 (lever 2): cheap pre-ranking used to decide which top-level candidates survive
     * the breadth cap, when one is set. Reuses {@code ComputerUtilAbility.saEvaluator} -- the exact
     * comparator {@code AiController} (line ~744) already sorts its own non-simulation candidate
     * list with ("put best spells first") -- rather than inventing a new heuristic or spending a
     * real simulation just to rank candidates, which would defeat the point of capping (the cost
     * this lever exists to avoid is per-candidate {@code GameCopier.makeCopy()}, and a full
     * simulation pays exactly that cost). Deliberately not {@link #MAX_LOOKAHEAD_CANDIDATES}'s
     * plain {@code subList} truncation: the top-level candidate list here is NOT pre-sorted by
     * desirability the way the recursive branch's caller sometimes is, so truncating without ranking
     * first would drop candidates arbitrarily rather than by any notion of quality.
     */
    private List<SpellAbility> capTopLevelCandidates(List<SpellAbility> candidateSAs, int cap) {
        List<SpellAbility> ranked = new ArrayList<>(candidateSAs);
        ranked.sort(ComputerUtilAbility.saEvaluator);
        List<SpellAbility> capped = new ArrayList<>(ranked.subList(0, cap));
        Logger.warn("[SpellAbilityPicker] TICKET-V4-011: top-level candidate breadth capped "
                + candidateSAs.size() + " -> " + cap + " via ComputerUtilAbility.saEvaluator "
                + "pre-ranking (Ultron-only bound on pathological-board decision cost)");
        return capped;
    }

    private void print(String str) {
        if (printOutput) {
            System.out.println(str);
        }
    }

    private void printPhaseInfo() {
        String phaseStr = game.getPhaseHandler().getPhase().toString();
        if (game.getPhaseHandler().getPlayerTurn() != player) {
            phaseStr = "opponent " + phaseStr;
        }
        print("---- choose ability  (phase = " + phaseStr + ")");
    }

    public List<SpellAbility> getCandidateSpellsAndAbilities() {
        CardCollection cards = ComputerUtilAbility.getAvailableCards(game, player);
        cards = ComputerUtilCard.dedupeCards(cards);
        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, player);
        List<SpellAbility> candidateSAs = ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player);
        int writeIndex = 0;
        for (SpellAbility sa : candidateSAs) {
            if (sa.isManaAbility()) {
                continue;
            }
            sa.setActivatingPlayer(player);

            AiPlayDecision opinion = canPlayAndPayForSim(sa);
            // print("  " + opinion + ": " + sa);
            // PhaseHandler ph = game.getPhaseHandler();
            // System.out.printf("Ai thinks '%s' of %s -> %s @ %s %s >>> \n", opinion, sa.getHostCard(), sa, Lang.getPossesive(ph.getPlayerTurn().getName()), ph.getPhase());

            if (opinion != AiPlayDecision.WillPlay)
                continue;
            candidateSAs.set(writeIndex, sa);
            writeIndex++;
        }
        candidateSAs.subList(writeIndex, candidateSAs.size()).clear();
        return candidateSAs;
    }

    public SpellAbility chooseSpellAbilityToPlay(SimulationController controller) {
        //printOutput = controller == null;

        // Pass if top of stack is owned by me.
        if (!game.getStack().isEmpty() && game.getStack().peekAbility().getActivatingPlayer().equals(player)) {
            return null;
        }

        Score origGameScore = eval().getScoreForGameState(game, player);
        List<SpellAbility> candidateSAs = getCandidateSpellsAndAbilities();
        if (controller != null) {
            // This is a recursion during a higher-level simulation. Just return the head of the best
            // sequence directly, no need to create a Plan object.
            //
            // TICKET-V3-207 (Ultron v3, session 4) root-cause fix: cap the candidate breadth
            // considered at this RECURSIVE (hypothetical-future-turn) node to
            // MAX_LOOKAHEAD_CANDIDATES. The real, top-level decision (the controller == null path
            // below, called once per actual chooseSpellAbilityToPlay() from the game engine) keeps
            // its full, unpruned candidate list -- this cap only bounds how many candidate
            // sequences get explored several simulated turns deep, which is exactly the axis the
            // plan's own "3-6 main-phase/attack candidates" design intended to prune everywhere,
            // but this pre-existing (originally 2-player-era, reused as-is by P2.4) recursive
            // planner never capped. Instrumented counts (UltronGameCopierCallCountTest,
            // FORGE_TRACKER TICKET-V3-207) showed this recursive search combined with
            // SimulationController's depth-3 lookahead and an unpruned real-hand-sized candidate
            // list (10-20+ legal plays in a real Battlebox game, vs. this synthetic test's 4) is
            // genuinely exponential (B + B^2 + B^3 GameSimulator constructions) and was still
            // reproducing the OOM in a real 3-game smoke test even after the combat-lookahead
            // recursion guard fix in UltronPlayerController -- confirming this is a SECOND,
            // independent multiplier, not an alternative explanation for the same one.
            if (candidateSAs.size() > MAX_LOOKAHEAD_CANDIDATES) {
                candidateSAs = candidateSAs.subList(0, MAX_LOOKAHEAD_CANDIDATES);
            }
            return chooseSpellAbilityToPlayImpl(controller, candidateSAs, origGameScore, null);
        }

        // TICKET-V4-011: reset per-decision deadline-hit tracking, and apply lever 2's breadth cap
        // (a no-op whenever maxTopLevelCandidates is unset, i.e. every non-Ultron caller and every
        // existing test) before this decision's own top-level search below.
        lastSearchHitDeadline = false;
        if (maxTopLevelCandidates != null && candidateSAs.size() > maxTopLevelCandidates) {
            candidateSAs = capTopLevelCandidates(candidateSAs, maxTopLevelCandidates);
        }

        printPhaseInfo();
        SpellAbility sa = getPlannedSpellAbility(origGameScore, candidateSAs);
        if (sa != null) {
            return sa;
        }
        createNewPlan(origGameScore, candidateSAs);
        return getPlannedSpellAbility(origGameScore, candidateSAs);
    }

    private Plan formulatePlanWithPhase(Score origGameScore, List<SpellAbility> candidateSAs, PhaseType phase) {
        SimulationController controller = maxRecursionDepth != null
                ? new SimulationController(origGameScore, maxRecursionDepth)
                : new SimulationController(origGameScore);
        SpellAbility sa = chooseSpellAbilityToPlayImpl(controller, candidateSAs, origGameScore, phase);
        if (sa != null) {
            return controller.getBestPlan();
        }
        return null;
    }

    private void printPlan(Plan plan, String intro) {
        if (plan == null) {
            print(intro + ": no plan!");
        }
        print(intro +" plan with score " + plan.getFinalScore() + ":");
        int i = 0;
        for (Plan.Decision d : plan.getDecisions()) {
            print(++i + ". " + d);
        }
    }

    private void createNewPlan(Score origGameScore, List<SpellAbility> candidateSAs) {
        plan = null;

        Plan bestPlan = formulatePlanWithPhase(origGameScore, candidateSAs, null);
        if (bestPlan == null) {
            print("No good plan at this time");
            return;
        }

        PhaseType currentPhase = game.getPhaseHandler().getPhase();
        if (currentPhase.isBefore(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
            List<SpellAbility> candidateSAs2 = new ArrayList<>();
            for (SpellAbility sa : candidateSAs) {
                if (!SpellAbilityAi.isSorcerySpeed(sa, player)) {
                    if (printOutput) {
                        System.err.println("Not sorcery: " + sa);
                    }
                    candidateSAs2.add(sa);
                }
            }
            if (!candidateSAs2.isEmpty()) {
                if (printOutput) {
                    System.err.println("Formula plan with phase bloom");
                }
                Plan afterBlockersPlan = formulatePlanWithPhase(origGameScore, candidateSAs2, PhaseType.COMBAT_DECLARE_BLOCKERS);
                if (afterBlockersPlan != null && afterBlockersPlan.getFinalScore().value >= bestPlan.getFinalScore().value) {
                    printPlan(afterBlockersPlan, "After blockers");
                    print("Deciding to wait until after declare blockers.");
                    return;
                }
            }
        }

        printPlan(bestPlan, "Current phase (" + currentPhase + ")");
        plan = bestPlan;
    }

    private SpellAbility chooseSpellAbilityToPlayImpl(SimulationController controller, List<SpellAbility> candidateSAs, Score origGameScore, PhaseType phase) {
        long startTime = System.currentTimeMillis();

        SpellAbility bestSa = null;
        Score bestSaValue = origGameScore;
        print("Evaluating... (orig score = " + origGameScore +  ")");
        for (int i = 0; i < candidateSAs.size(); i++) {
            // TICKET-V4-011 (lever 1, root-cause fix): cooperative deadline checkpoint between
            // top-level candidates. Only ever true when a caller (UltronPlayerController) has set a
            // deadline for this decision -- see deadlineMillis's javadoc for the full "no deadline
            // set = unchanged" argument. Returning the best candidate found so far here is what lets
            // this search finish on its own worker thread within budget instead of being abandoned
            // mid-allocation by the outer per-decision timeout.
            if (deadlineExceeded()) {
                lastSearchHitDeadline = true;
                Logger.warn("[SpellAbilityPicker] TICKET-V4-011: deadline exceeded after evaluating "
                        + i + "/" + candidateSAs.size() + " top-level candidates; returning best-so-far "
                        + "instead of continuing the search");
                break;
            }
            Score value = evaluateSa(controller, phase, candidateSAs, i, origGameScore);
            if (value.value > bestSaValue.value) {
                bestSaValue = value;
                bestSa = candidateSAs.get(i);
            }
        }

        // To make the AI hold-off on playing creatures in MAIN1 if they give no other benefits,
        // check the score for the bestSA while counting summon sick creatures for 0.
        // Do it here on the best SA, rather than for all evaluations, so that if the best SA
        // is indeed a creature spell, we don't pick something else to play now and then have
        // no mana to play the truly best SA post-combat.
        if (bestSa != null && bestSaValue.summonSickValue <= origGameScore.summonSickValue) {
            bestSa = null;
        }

        long execTime = System.currentTimeMillis() - startTime;
        print("BEST: " + abilityToString(bestSa) + " SCORE: " + bestSaValue.summonSickValue + " TIME: " + execTime);
        this.bestScore = bestSaValue;
        return bestSa;
    }

    public boolean hasActivePlan() {
        return plan != null && plan.hasNextDecision();
    }

    public Plan getPlan() {
        return plan;
    }

    private void printPlannedActionFailure(Plan.Decision decision, String cause) {
        print("Failed to continue planned action (" + decision.saRef + "). Cause:");
        print("  " + cause + "!");
        plan = null;
    }

    private SpellAbility getPlannedSpellAbility(Score origGameScore, List<SpellAbility> availableSAs) {
        if (!hasActivePlan()) {
            plan = null;
            return null;
        }
        PhaseType startPhase = plan.getStartPhase();
        if (startPhase != null && game.getPhaseHandler().getPhase().isBefore(startPhase)) {
            print("Waiting until phase " + startPhase + " to proceed with the plan.");
            return null;
        }
        Plan.Decision decision = plan.selectNextDecision();
        if (!decision.initialScore.equals(origGameScore)) {
            printPlannedActionFailure(decision, "Unexpected game score (" + decision.initialScore + " vs. expected " + origGameScore + ")");
            return null;
        }
        SpellAbility sa = decision.saRef.findReferencedAbility(availableSAs);
        if (sa == null) {
            printPlannedActionFailure(decision, "Couldn't find spell/ability!");
            return null;
        }
        // If modes != null, targeting will be done in chooseModeForAbility().
        if (decision.modes == null && decision.targets != null) {
            MultiTargetSelector selector = new MultiTargetSelector(sa, null);
            if (!selector.selectTargets(decision.targets)) {
                printPlannedActionFailure(decision, "Bad targets");
                return null;
            }
        }
        if (decision.xMana != null) {
            sa.setXManaCostPaid(decision.xMana);
        }
        print("Planned decision " + plan.getNextDecisionIndex() + ": " + decision);
        return sa;
    }

    public Score getScoreForChosenAbility() {
        return bestScore;
    }

    public static String abilityToString(SpellAbility sa) {
        return abilityToString(sa, true);
    }
    public static String abilityToString(SpellAbility sa, boolean withTargets) {
        StringBuilder saString = new StringBuilder("N/A");
        if (sa != null) {
            saString = new StringBuilder(sa.toString());
            String cardName = sa.getHostCard().getName();
            if (!cardName.isEmpty()) {
                saString = new StringBuilder(TextUtil.fastReplace(saString.toString(), cardName, "<$>"));
            }
            if (saString.length() > 40) {
                saString = new StringBuilder(saString.substring(0, 40) + "...");
            }
            if (withTargets) {
                SpellAbility saOrSubSa = sa;
                do {
                    if (saOrSubSa.usesTargeting()) {
                        saString.append(" (targets: ").append(saOrSubSa.getTargets()).append(")");
                    }
                    saOrSubSa = saOrSubSa.getSubAbility();
                } while (saOrSubSa != null);
            }
            saString.insert(0, sa.getHostCard() + " -> ");
        }
        return saString.toString();
    }

    private boolean shouldWaitForLater(final SpellAbility sa) {
        final PhaseType phase = game.getPhaseHandler().getPhase();
        final boolean isEarlyPhase = phase == PhaseType.UNTAP || phase == PhaseType.UPKEEP || phase == PhaseType.DRAW;

        // Until the AI can be made smarter, hold off playing instants until MAIN1,
        // so that they can be compared to sorcery-speed spells. Else, the AI is too
        // eager to play them.
        if (isEarlyPhase) {
            // Only hold off if this spell can actually be played in MAIN1.
            final SpellAbilityCondition conditions = sa.getConditions();
            if (conditions == null) {
                return true;
            }
            Set<PhaseType> phases = conditions.getPhases();
            return phases.isEmpty() || phases.contains(PhaseType.MAIN1);
        }

        return false;
    }

    private boolean atLeastOneConditionMet(SpellAbility saOrSubSa) {
        do {
            SpellAbilityCondition conditions = saOrSubSa.getConditions();
            if (conditions == null || conditions.areMet(saOrSubSa)) {
                return true;
            }
            saOrSubSa = saOrSubSa.getSubAbility();
        } while (saOrSubSa != null);
        return false;
    }

    private AiPlayDecision canPlayAndPayForSim(final SpellAbility sa) {
        if (!sa.checkRestrictions(sa.getHostCard(), player)) {
            return AiPlayDecision.CantPlaySa;
        }

        if (sa.isLandAbility()) {
            return AiPlayDecision.WillPlay;
        }
        if (!sa.isLegalAfterStack()) {
            return AiPlayDecision.CantPlaySa;
        }
        if (!sa.canPlay()) {
            return AiPlayDecision.CantPlaySa;
        }

        // Note: Can't just check condition on the top ability, because it may have
        // sub-abilities without conditions (e.g. wild slash's main ability has a
        // main ability with conditions but the burn sub-ability has none).
        if (!atLeastOneConditionMet(sa)) {
            return AiPlayDecision.CantPlaySa;
        }

        if (!ComputerUtilCost.canPayCost(sa, player, sa.isTrigger())) {
            return AiPlayDecision.CantAfford;
        }
        if (!ComputerUtilAbility.isFullyTargetable(sa)) {
            return AiPlayDecision.TargetingFailed;
        }
        if (shouldWaitForLater(sa)) {
            return AiPlayDecision.AnotherTime;
        }

        return AiPlayDecision.WillPlay;
    }

    public Score evaluateSa(final SimulationController controller, PhaseType phase, List<SpellAbility> saList, int saIndex) {
        return evaluateSa(controller, phase, saList, saIndex, null);
    }

    /**
     * TICKET-V3-207 root-cause fix: {@code origGameScore}, when non-null, is the caller's
     * already-computed {@code eval.getScoreForGameState(game, player)} for this exact (game,
     * player) pair -- every candidate in {@code chooseSpellAbilityToPlayImpl}'s loop shares the
     * same unmodified {@code game}/{@code player}, so recomputing this per-candidate inside {@code
     * GameSimulator}'s constructor was pure duplicated work (see {@code GameSimulator}'s
     * 5-arg-constructor javadoc for the full multiplier analysis). {@code null} preserves the
     * original recompute-it-yourself behavior for the {@code SpellAbilityPickerSimulationTest}
     * caller, which does not have (and should not need) an equivalent already-known value.
     */
    public Score evaluateSa(final SimulationController controller, PhaseType phase, List<SpellAbility> saList, int saIndex, Score origGameScore) {
        controller.evaluateSpellAbility(saList, saIndex);
        SpellAbility sa = saList.get(saIndex);

        // Use a deterministic random seed when evaluating different choices of a spell ability.
        // This is needed as otherwise random effects may result in a different number of choices
        // each iteration, which will break the logic in SpellAbilityChoicesIterator.
        Random origRandom = MyRandom.getRandom();
        long randomSeedToUse = origRandom.nextLong();

        Score bestScore = new Score(Integer.MIN_VALUE);
        final SpellAbilityChoicesIterator choicesIterator = new SpellAbilityChoicesIterator(controller);
        Score lastScore;
        do {
            // TICKET-V4-011 (lever 1): also check the deadline right before each GameSimulator
            // construction -- a single candidate's own multi-choice fan-out (different
            // targets/modes) can itself construct several GameSimulators, each paying a full
            // GameCopier.makeCopy(). Breaking here mid-candidate is safe: bestScore already holds
            // whatever this candidate's best choice scored so far (or MIN_VALUE if none yet), which
            // the caller's loop treats like any other candidate score.
            if (deadlineExceeded()) {
                lastSearchHitDeadline = true;
                break;
            }
            // TODO: MyRandom should be an instance on the game object, so that we could do
            // simulations in parallel without messing up global state.
            MyRandom.setRandom(new Random(randomSeedToUse));
            GameSimulator simulator = new GameSimulator(controller, game, player, phase, origGameScore, eval());
            simulator.setInterceptor(choicesIterator);
            // I feel like something here is making a wrong assumption about what the target is
            lastScore = simulator.simulateSpellAbility(sa);
            numSimulations++;
            if (lastScore.value > bestScore.value) {
                bestScore = lastScore;
            }
        } while (choicesIterator.advance(lastScore));
        controller.doneEvaluating(bestScore);
        MyRandom.setRandom(origRandom);
        return bestScore;
    }

    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> choices, int min, int num, boolean allowRepeat) {
        if (interceptor != null) {
            return interceptor.chooseModesForAbility(sa, choices, min, num, allowRepeat);
        }
        if (plan != null && plan.getSelectedDecision() != null && plan.getSelectedDecision().modes != null) {
            Plan.Decision decision = plan.getSelectedDecision();
            // TODO: Validate that there's no discrepancies between choices and modes?
            List<AbilitySub> plannedModes = SpellAbilityChoicesIterator.getModeCombination(choices, decision.modes);
            if (plan.getSelectedDecision().targets != null) {
                MultiTargetSelector selector = new MultiTargetSelector(sa, plannedModes);
                if (!selector.selectTargets(decision.targets)) {
                    printPlannedActionFailure(decision, "Bad targets for modes");
                    return null;
                }
            }
            return plannedModes;
        }
        return null;
    }

    private Card getPlannedChoice(CardCollection fetchList) {
        // TODO: Make the below more robust?
        if (plan != null && plan.getSelectedDecision() != null) {
            String choice = plan.getSelectedDecisionNextChoice();
            for (Card c : fetchList) {
                if (c.getName().equals(choice)) {
                    print("  Planned choice: " + c);
                    return c;
                }
            }
            print("Failed to use planned choice (" + choice + "). Not found!");
        }
        return null;
    }

    public Card chooseCardToHiddenOriginChangeZone(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, Player player2, Player decider) {
        if (fetchList.size() >= 2) {
            if (interceptor != null) {
                return interceptor.chooseCard(fetchList);
            }
            Card card = getPlannedChoice(fetchList);
            if (card != null) {
                plan.advanceNextChoice();
                return card;
            }
        }
        if (sa.getApi() == ApiType.Learn) {
            return LearnAi.chooseCardToLearn(fetchList, decider, sa);
        } else {
            return ChangeZoneAi.chooseCardToHiddenOriginChangeZone(destination, origin, sa, fetchList, player2, decider);
        }
    }

    public CardCollectionView chooseSacrificeType(String type, SpellAbility ability, final boolean effect, int amount, final CardCollectionView exclude) {
        if (amount == 1) {
            Card source = ability.getHostCard();
            CardCollection cardList = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), type.split(";"), source.getController(), source, ability);
            cardList = CardLists.filter(cardList, CardPredicates.canBeSacrificedBy(ability, effect));
            if (cardList.size() >= 2) {
                if (interceptor != null) {
                    return new CardCollection(interceptor.chooseCard(cardList));
                }
                Card card = getPlannedChoice(cardList);
                if (card != null) {
                    plan.advanceNextChoice();
                    return new CardCollection(card);
                }
            }
        }
        return ComputerUtil.chooseSacrificeType(player, type, ability, ability.getTargetCard(), effect, amount, exclude);
    }

    public int getNumSimulations() {
        return numSimulations;
    }
}
