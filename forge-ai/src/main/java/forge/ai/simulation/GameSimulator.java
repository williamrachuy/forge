package forge.ai.simulation;


import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.util.collect.FCollectionView;

import java.util.*;

public class GameSimulator {
    public static boolean COPY_STACK = false;

    /**
     * TICKET-V3-207 (Ultron v3, session 6): gates {@link #ensureGameCopyScoreMatches}, a debug-only
     * consistency assertion (recomputes a full {@code GameStateEvaluator.getScoreForGameState} on the
     * freshly-made copy -- including its own nested {@code simulateUpcomingCombatThisTurn} combat
     * prediction -- and throws if it disagrees with the original game's score) that legitimately
     * caught a real {@code GameCopier} fidelity bug once (TICKET-V3-201's Battlebox shared-zone bug)
     * and is worth KEEPING for development/testing, but was running unconditionally on every single
     * leaf-candidate {@code GameSimulator} construction, for every AI profile that uses simulation
     * (not Ultron-specific). Session 5's live jstack evidence caught this directly firing mid-decision
     * (the 90s dump: {@code GameSimulator.<init>} -> {@code ensureGameCopyScoreMatches} -> ... ->
     * {@code simulateUpcomingCombatThisTurn}) -- a real, substantial, previously-uncharacterized cost
     * multiplier on top of session 4's already-fixed recursion issue: for Ultron's depth-1/breadth-6
     * recursive search against a real Battlebox pool, this doubled the cost of every leaf-level
     * candidate evaluation and added a full extra nested combat-prediction pass each time.
     *
     * <p>Default OFF for real simulated gameplay (matches {@code SimulationController.DEBUG}'s
     * plain-static-boolean gating convention elsewhere in this package), with a system property for
     * easily re-enabling it for development/testing without a recompile -- if {@code GameCopier}
     * fidelity is ever suspect again, set {@code -Dforge.sim.verifyGameCopy=true}. The public setter
     * exists so tests can flip this at runtime (e.g. via a {@code try/finally} around a single
     * assertion) without relying on a JVM-wide system property.
     */
    public static boolean VERIFY_GAME_COPY = Boolean.getBoolean("forge.sim.verifyGameCopy");

    public static void setVerifyGameCopy(boolean verify) {
        VERIFY_GAME_COPY = verify;
    }
    final private SimulationController controller;
    private GameCopier copier;
    private Game simGame;
    private Player aiPlayer;
    private GameStateEvaluator eval;
    private List<String> origLines;
    private Score origScore;
    private SpellAbilityChoicesIterator interceptor;

    public GameSimulator(SimulationController controller, Game origGame, Player origAiPlayer, PhaseType advanceToPhase) {
        this(controller, origGame, origAiPlayer, advanceToPhase, null);
    }

    /**
     * TICKET-V3-207 (Ultron v3, session 4 root-cause fix): {@code precomputedOrigScore}, when
     * non-null, is used verbatim instead of recomputing {@code eval.getScoreForGameState(origGame,
     * origAiPlayer)} here. Every candidate SA evaluated by {@code SpellAbilityPicker.
     * chooseSpellAbilityToPlayImpl}'s loop constructs its own {@code GameSimulator} against the
     * SAME (unmodified-by-sibling-candidates) {@code origGame}/{@code origAiPlayer} pair -- the
     * caller has ALREADY computed this exact score once (it's the {@code origGameScore} parameter
     * threaded through {@code chooseSpellAbilityToPlayImpl}/{@code evaluateSa}), so recomputing it
     * from scratch per candidate was pure duplicated work: each recomputation itself runs
     * {@code GameStateEvaluator.getScoreForGameState}, which (via {@code
     * simulateUpcomingCombatThisTurn}) can trigger its own {@code GameCopier.makeCopy()} -- and, if
     * the copy's active player is Ultron-controlled with legal attackers, a full nested {@code
     * declareAttackers}/{@code declareBlockers} candidate search of its own. With B candidates per
     * decision node, this alone was an unconditional B-fold multiplier on top of {@code
     * SimulationController}'s existing depth-3 recursive branching (B + B^2 + B^3 GameSimulator
     * constructions), independently confirmed by instrumented counts in {@code
     * UltronGameCopierCallCountTest} (975 {@code GameCopier.makeCopy()} calls for a single
     * main-phase decision with only 4 hand candidates and minimal board state -- see FORGE_TRACKER
     * TICKET-V3-207). {@code getScoreForOrigGame()}'s test-visible contract (equal to
     * {@code eval.getScoreForGameState(origGame, origAiPlayer)}) is preserved exactly: {@code
     * GameStateEvaluator} is a pure function of game state, and {@code origGame} is not mutated
     * between when the caller computed {@code origGameScore} and this constructor running --
     * passing the already-known value in is a decision-quality-neutral performance fix, not a
     * behavior change. {@code null} (the existing test helper's/legacy 4-arg constructor's path)
     * preserves the original recompute-here behavior exactly, for callers that have no
     * already-known score to hand in.
     */
    public GameSimulator(SimulationController controller, Game origGame, Player origAiPlayer, PhaseType advanceToPhase, Score precomputedOrigScore) {
        this.controller = controller;
        copier = new GameCopier(origGame);
        simGame = copier.makeCopy(advanceToPhase, origAiPlayer);

        aiPlayer = (Player) copier.find(origAiPlayer);
        eval = new GameStateEvaluator();

        origLines = new ArrayList<>();
        debugLines = origLines;

        debugPrint = false;
        origScore = precomputedOrigScore != null ? precomputedOrigScore : eval.getScoreForGameState(origGame, origAiPlayer);

        if (advanceToPhase == null && VERIFY_GAME_COPY) {
            ensureGameCopyScoreMatches(origGame, origAiPlayer);
        }

        // If the stack on the original game is not empty, resolve it
        // first and get the updated eval score, since this is what we'll
        // want to compare to the eval score after simulating.
        if (COPY_STACK && !origGame.getStackZone().isEmpty()) {
            origLines = new ArrayList<>();
            debugLines = origLines;
            Game copyOrigGame = copier.makeCopy();
            Player copyOrigAiPlayer = copyOrigGame.getPlayers().get(1);
            resolveStack(copyOrigGame, copyOrigGame.getPlayers().get(0));
            origScore = eval.getScoreForGameState(copyOrigGame, copyOrigAiPlayer);
        }

        debugPrint = false;
        debugLines = null;
    }

    private void ensureGameCopyScoreMatches(Game origGame, Player origAiPlayer) {
        eval.setDebugging(true);
        List<String> simLines = new ArrayList<>();
        debugLines = simLines;
        Score simScore = eval.getScoreForGameState(simGame, aiPlayer);
        if (!simScore.equals(origScore)) {
            // Re-eval orig with debug printing.
            origLines = new ArrayList<>();
            debugLines = origLines;
            eval.getScoreForGameState(origGame, origAiPlayer);
            // Print debug info.
            printDiff(origLines, simLines);
            // make sure it gets printed
            System.out.flush();
            throw new RuntimeException("Game copy error. See diff output above for details.");
        }
        eval.setDebugging(false);
    }

    public void setInterceptor(SpellAbilityChoicesIterator interceptor) {
        this.interceptor = interceptor;
        ((PlayerControllerAi) aiPlayer.getController()).getAi().getSimulationPicker().setInterceptor(interceptor);
    }

    private void printDiff(List<String> lines1, List<String> lines2) {
        int i = 0;
        int j = 0;
        Collections.sort(lines1);
        Collections.sort(lines2);
        while (i < lines1.size() && j < lines2.size()) {
            String left = lines1.get(i);
            String right = lines2.get(j);
            int cmp = left.compareTo(right);
            if (cmp == 0) {
                i++; j++;
            } else if (cmp < 0) {
                System.out.println("-" + left);
                i++;
            } else { 
                System.out.println("+"  + right);
                j++;
            }
        }
        while (i < lines1.size()) {
            System.out.println("-" + lines1.get(i++));
        }
        while (j < lines2.size()) {
            System.out.println("+" + lines2.get(j++));
        }
    }

    public static boolean debugPrint;
    public static List<String> debugLines;
    public static void debugPrint(String str) {
        if (debugPrint) {
            System.out.println(str);
        }
        if (debugLines != null) {
            debugLines.add(str);
        }
    }

    private SpellAbility findSaInSimGame(final SpellAbility sa) {
        // is already an ability from sim game
        if (sa.getHostCard().getGame().equals(this.simGame)) {
            return sa;
        }
        Card origHostCard = sa.getHostCard();
        Card hostCard = (Card) copier.find(origHostCard);
        String desc = sa.getDescription();
        FCollectionView<SpellAbility> candidates = hostCard.getSpellAbilities();

        SpellAbility result = saMatcher(candidates, desc);
        for (SpellAbility cSa : candidates) {
            if (result != null) {
                break;
            }
            result = saMatcher(GameActionUtil.getAlternativeCosts(cSa, aiPlayer, true), desc);
        }

        return result;
    }

    private SpellAbility saMatcher(Iterable<SpellAbility> candidates, String desc) {
        // first pass for accuracy (spells with alternative costs)
        for (SpellAbility cSa : candidates) {
            if (desc.equals(cSa.getDescription())) {
                return cSa;
            }
        }
        // fall back for safety
        for (SpellAbility cSa : candidates) {
            if (desc.startsWith(cSa.getDescription())) {
                return cSa;
            }
        }
        return null;
    }

    public Score simulateSpellAbility(SpellAbility origSa) {
        return simulateSpellAbility(origSa, this.eval, true);
    }
    public Score simulateSpellAbility(SpellAbility origSa, boolean resolve) {
        return simulateSpellAbility(origSa, this.eval, resolve);
    }
    public Score simulateSpellAbility(SpellAbility origSa, GameStateEvaluator eval, boolean resolve) {
        SpellAbility sa;
        if (origSa.isLandAbility()) {
            Card hostCard = (Card) copier.find(origSa.getHostCard());
            if (origSa.canPlay()) {
                aiPlayer.playLand(hostCard, origSa);
            } else {
                System.err.println("Simulation: Couldn't play land! " + origSa);
            }
            sa = origSa;
        } else {
            // TODO: optimize: prune identical SA (e.g. two of the same card in hand)
            sa = findSaInSimGame(origSa);
            if (sa == null) {
                System.err.println("Simulation: SA not found! " + origSa + " / " + origSa.getClass());
                return new Score(Integer.MIN_VALUE);
            }

            debugPrint("Found SA " + sa + " on host card " + sa.getHostCard() + " with owner:"+ sa.getHostCard().getOwner());
            sa.setActivatingPlayer(aiPlayer);
            SpellAbility origSaOrSubSa = origSa;
            SpellAbility saOrSubSa = sa;
            do {
                if (origSaOrSubSa.usesTargeting()) {
                    final boolean divided = origSaOrSubSa.isDividedAsYouChoose();
                    for (final GameObject o : origSaOrSubSa.getTargets()) {
                        final GameObject target = copier.find(o);
                        saOrSubSa.getTargets().add(target);
                        if (divided) {
                            saOrSubSa.addDividedAllocation(target, origSaOrSubSa.getDividedValue(o));
                        }
                    }
                }
                origSaOrSubSa = origSaOrSubSa.getSubAbility();
                saOrSubSa = saOrSubSa.getSubAbility();
            } while (saOrSubSa != null);

            if (debugPrint && !sa.getAllTargetChoices().isEmpty()) {
                debugPrint("Targets: ");
                for (TargetChoices target : sa.getAllTargetChoices()) {
                    System.out.print(target);
                }
                System.out.println();
            }
            final SpellAbility playingSa = sa;
            // Is this right?
            simGame.copyLastState();
            boolean success = ComputerUtil.handlePlayingSpellAbility(aiPlayer, sa, () -> {
                if (interceptor != null) {
                    interceptor.announceX(playingSa);
                    interceptor.chooseTargets(playingSa, GameSimulator.this);
                }
            });
            if (!success) {
                return new Score(Integer.MIN_VALUE);
            }
        }

        if (resolve) {
            // TODO: Support multiple opponents.
            Player opponent = aiPlayer.getWeakestOpponent();
            resolveStack(simGame, opponent);
        }

        // TODO: If this is during combat, before blockers are declared,
        // we should simulate how combat will resolve and evaluate that
        // state instead!
        List<String> simLines = null;
        if (debugPrint) {
            debugPrint("SimGame:");
            simLines = new ArrayList<>();
            debugLines = simLines;
            debugPrint = false;
        }
        Score score = eval.getScoreForGameState(simGame, aiPlayer);
        if (simLines != null) {
            debugLines = null;
            debugPrint = true;
            printDiff(origLines, simLines);
        }
        controller.possiblyCacheResult(score, origSa);
        if (controller.shouldRecurse() && !simGame.isGameOver()) {
            controller.push(sa, score, this);
            SpellAbilityPicker sim = new SpellAbilityPicker(simGame, aiPlayer);
            SpellAbility nextSa = sim.chooseSpellAbilityToPlay(controller);
            if (nextSa != null) {
                score = sim.getScoreForChosenAbility();
            }
            controller.pop(score, nextSa);
        }

        return score;
    }

    public static void resolveStack(final Game game, final Player opponent) {
        // TODO: This needs to set an AI controller for all opponents, in case of multiplayer.
        PlayerControllerAi sim = new PlayerControllerAi(game, opponent, opponent.getLobbyPlayer());
        sim.setUseSimulation(true);
        opponent.runWithController(() -> {
            final Set<Card> allAffectedCards = new HashSet<>();
            game.getAction().checkStateEffects(false, allAffectedCards);
            game.getStack().addAllTriggeredAbilitiesToStack();
            while (!game.getStack().isEmpty() && !game.isGameOver()) {
                debugPrint("Resolving:" + game.getStack().peekAbility());

                // Resolve the top effect on the stack.
                game.getStack().resolveStack();

                // Evaluate state based effects as a result of resolving stack.
                // Note: Needs to happen after resolve stack rather than at the
                // top of the loop to ensure state effects are evaluated after the
                // last resolved effect
                game.getAction().checkStateEffects(false, allAffectedCards);

                // Add any triggers additional triggers as a result of the above.
                // Must be below state effects, since legendary rule is evaluated
                // as part of state effects and trigger come afterward. (e.g. to
                // correctly handle two Dark Depths - one having no counters).
                game.getStack().addAllTriggeredAbilitiesToStack();

                // Continue until stack is empty.
            }
        }, sim);
    }

    public Game getSimulatedGameState() {
        return simGame;
    }

    public Score getScoreForOrigGame() {
        return origScore;
    }

    public GameCopier getGameCopier() {
        return copier;
    }
}
