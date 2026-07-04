package forge.ai.ultron;

import com.google.common.collect.Lists;
import forge.ai.AIOption;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.GameStateEvaluator;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.SharedPlayerZone;
import forge.game.zone.ZoneType;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TICKET-V3-204 (Ultron v3 Phase 2, P2.5): attack declaration via a pruned-candidate simulation
 * search. Builds real 4-player Battlebox mid-game-shaped states (same convention as
 * {@code GameCopierBattleboxFidelityTest}/{@code UltronMainPhaseSimulationTest}) with actual
 * creatures on multiple players' boards and exercises
 * {@link UltronPlayerController#declareAttackers(Player, Combat)} directly, the same way
 * {@code SpellAbilityPickerSimulationTest} exercises combat-phase state without driving a full
 * turn loop.
 */
public class UltronCombatSimulationTest extends AITest {

    private static final int NUM_PLAYERS = 4;

    private Game createBattleboxGame() {
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < NUM_PLAYERS; i++) {
            Deck d = new Deck();
            Set<AIOption> options = new HashSet<>();
            LobbyPlayerAi lp = new LobbyPlayerAi("p" + i, options);
            if (i == 0) {
                lp.setAiProfile("Ultron");
            }
            players.add(new RegisteredPlayer(d).setPlayer(lp));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.Battlebox);
        Match match = new Match(rules, players, "UltronCombatSimulationTest");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;

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

        game.getPlayers().get(0).setLife(20, null);
        game.getPlayers().get(1).setLife(15, null);
        game.getPlayers().get(2).setLife(9, null);
        game.getPlayers().get(3).setLife(18, null);

        return game;
    }

    /**
     * A 2-player-shaped variant of the same fixture convention, used only for the
     * profitable/bad-attack proof tests below. With a single alive opponent, the interim
     * multiplayer evaluator's {@code 0.65*maxLife + 0.35*avgLife} term (TICKET-V3-202) collapses
     * to exactly that opponent's life, so a life-total delta from an attack always shows up in the
     * score without being diluted by averaging across other, unaffected opponents' life totals --
     * that dilution effect is itself proven/handled by {@code testMultiplayerCombatConsidersNonWeakestOpponentsBlocker}
     * below, which is a 4-player test by design; this helper keeps the profitable/bad-attack tests
     * focused on the attack-vs-no-attack decision itself.
     */
    private Game createTwoPlayerBattleboxGame() {
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < 2; i++) {
            Deck d = new Deck();
            Set<AIOption> options = new HashSet<>();
            LobbyPlayerAi lp = new LobbyPlayerAi("p" + i, options);
            if (i == 0) {
                lp.setAiProfile("Ultron");
            }
            players.add(new RegisteredPlayer(d).setPlayer(lp));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.Battlebox);
        Match match = new Match(rules, players, "UltronCombatSimulationTest2p");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;

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
        game.getPlayers().get(0).setLife(20, null);
        game.getPlayers().get(1).setLife(20, null);
        return game;
    }

    /**
     * Same fixture convention as {@link #createBattleboxGame()}, but with a second seat (seat 1)
     * also wired to {@code UltronPlayerController} -- used by
     * {@link #testUltronVsUltronBlockSimulationDoesNotRecurseUnbounded()}, the mandatory
     * recursion-safety proof for TICKET-V3-205.
     */
    private Game createBattleboxGameWithTwoUltronSeats() {
        List<RegisteredPlayer> players = Lists.newArrayList();
        for (int i = 0; i < NUM_PLAYERS; i++) {
            Deck d = new Deck();
            Set<AIOption> options = new HashSet<>();
            LobbyPlayerAi lp = new LobbyPlayerAi("p" + i, options);
            if (i == 0 || i == 1) {
                lp.setAiProfile("Ultron");
            }
            players.add(new RegisteredPlayer(d).setPlayer(lp));
        }
        GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.Battlebox);
        Match match = new Match(rules, players, "UltronCombatSimulationTestMirror");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;

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

        game.getPlayers().get(0).setLife(20, null);
        game.getPlayers().get(1).setLife(20, null);
        game.getPlayers().get(2).setLife(20, null);
        game.getPlayers().get(3).setLife(20, null);

        return game;
    }

    private UltronPlayerController ultronControllerFor(Game game) {
        Player ultron = game.getPlayers().get(0);
        Assert.assertTrue(ultron.getController() instanceof UltronPlayerController,
                "Fixture bug: seat 0 must be wired to UltronPlayerController for this test to mean anything");
        return (UltronPlayerController) ultron.getController();
    }

    private UltronPlayerController ultronControllerForSeat(Game game, int seat) {
        Player p = game.getPlayers().get(seat);
        Assert.assertTrue(p.getController() instanceof UltronPlayerController,
                "Fixture bug: seat " + seat + " must be wired to UltronPlayerController for this test to mean anything");
        return (UltronPlayerController) p.getController();
    }

    /** Mirrors the {@code declareBlockersTurnBasedAction} invariant: attackers already declared,
     *  combat sitting at {@code COMBAT_DECLARE_BLOCKERS} before the defender's controller is asked. */
    private Combat enterDeclareBlockers(Game game, Player attacker, List<Pair<Card, Player>> attacksAgainst) {
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, attacker);
        Combat combat = new Combat(attacker);
        for (Pair<Card, Player> assignment : attacksAgainst) {
            combat.addAttacker(assignment.getLeft(), assignment.getRight());
        }
        game.getPhaseHandler().setCombat(combat);
        combat.initConstraints();
        game.getAction().checkStateEffects(true);
        return combat;
    }

    /** Mirrors the {@code declareAttackersTurnBasedAction} invariant: combat exists and is
     *  {@code initConstraints()}-ed before the controller's {@code declareAttackers} is invoked. */
    private Combat enterDeclareAttackers(Game game, Player attacker) {
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        Combat combat = new Combat(attacker);
        game.getPhaseHandler().setCombat(combat);
        combat.initConstraints();
        game.getAction().checkStateEffects(true);
        return combat;
    }

    @Test
    public void testDeclareAttackersReturnsLegalSubsetWithoutCrashing() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);

        Card bears = addCard("Grizzly Bears", ultron);
        bears.setSickness(false);
        Card oppCreature = addCard("Runeclaw Bear", game.getPlayers().get(1));
        oppCreature.setSickness(false);

        Combat combat = enterDeclareAttackers(game, ultron);
        UltronPlayerController controller = ultronControllerFor(game);
        long before = controller.getTelemetry().getUltronAnsweredCount();

        controller.declareAttackers(ultron, combat);

        Assert.assertEquals(controller.getTelemetry().getUltronAnsweredCount(), before + 1,
                "Simulation-based attack declaration must not throw/fall back for a simple 4-player state");
        for (Card attackerCard : combat.getAttackers()) {
            Assert.assertEquals(attackerCard.getController(), ultron,
                    "Every declared attacker must actually be controlled by the attacking player");
        }
        Object detail = controller.getTelemetry().getLastDetail("declareAttackers");
        Assert.assertNotNull(detail, "candidate-count/chosen-score detail should be recorded (P2.5 telemetry requirement)");
    }

    @Test
    public void testProfitableUnblockableAttackIsChosen() {
        initAndCreateGame();
        Game game = createTwoPlayerBattleboxGame();
        Player ultron = game.getPlayers().get(0);

        // Wind Drake is a 2/2 flyer -- the lone opponent has no flying/reach, so it is
        // unblockable in practice and there is no meaningful counter-attack risk (opponent has no
        // creatures at all). This should be a clearly profitable attack.
        Card drake = addCard("Wind Drake", ultron);
        drake.setSickness(false);

        Combat combat = enterDeclareAttackers(game, ultron);
        UltronPlayerController controller = ultronControllerFor(game);

        controller.declareAttackers(ultron, combat);

        Assert.assertTrue(combat.getAttackers().contains(drake),
                "An unblockable, risk-free attacker should be attacked with -- simulation should score attacking higher than not");
    }

    @Test
    public void testBadTradeAttackIsDeclined() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);

        // A 2/2 Grizzly Bears attacking into a table where every opponent has a 6/4 Craw Wurm
        // available to block is a bad attack no matter which opponent ends up targeted -- the
        // Bears die for zero benefit. This should be declined regardless of which candidate
        // defender the simulation's preferred-defender/weakest-opponent heuristics pick.
        Card bears = addCard("Grizzly Bears", ultron);
        bears.setSickness(false);
        for (int seat = 1; seat < NUM_PLAYERS; seat++) {
            Card wurm = addCard("Craw Wurm", game.getPlayers().get(seat));
            wurm.setSickness(false);
        }

        Combat combat = enterDeclareAttackers(game, ultron);
        UltronPlayerController controller = ultronControllerFor(game);

        controller.declareAttackers(ultron, combat);

        Assert.assertFalse(combat.getAttackers().contains(bears),
                "A guaranteed bad trade (2/2 into an available 6/4 blocker, no benefit) should be declined");
    }

    /**
     * Directly targets the task-mandated check: does multiplayer combat simulation correctly use
     * a *non-weakest* opponent's own creatures as blockers, or does it silently only consider the
     * single weakest opponent (the shape of gap TICKET-V3-203 found in stack resolution)?
     *
     * <p>Seat 1 is the lowest-life (weakest-by-life) opponent and has NO creatures. Seat 2 is a
     * higher-life opponent that owns the only blocker in the game. If
     * {@code GameStateEvaluator.simulateUpcomingCombatThisTurn} (via
     * {@code declareBlockersTurnBasedAction}'s per-defending-player controller loop) only ever
     * consulted the weakest opponent (seat 1, bootless here) instead of the actually-attacked
     * player (seat 2), Ultron's attacker would incorrectly sail through unblocked and score
     * higher than the "don't attack" baseline. Because it is in fact correctly blocked and dies,
     * the attacking-state score must come out *lower* than the baseline where seat 2 has no
     * blocker at all -- proving seat 2's own creature was considered, not silently dropped in
     * favor of the weakest opponent.
     */
    @Test
    public void testMultiplayerCombatConsidersNonWeakestOpponentsBlocker() {
        initAndCreateGame();
        Game gameWithBlocker = createBattleboxGame();
        Player ultronWithBlocker = gameWithBlocker.getPlayers().get(0);
        Card bearsWithBlocker = addCard("Grizzly Bears", ultronWithBlocker);
        bearsWithBlocker.setSickness(false);
        Player seat2WithBlocker = gameWithBlocker.getPlayers().get(2);
        Card wurm = addCard("Craw Wurm", seat2WithBlocker);
        wurm.setSickness(false);

        gameWithBlocker.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ultronWithBlocker);
        Combat combatWithBlocker = new Combat(ultronWithBlocker);
        combatWithBlocker.addAttacker(bearsWithBlocker, seat2WithBlocker);
        gameWithBlocker.getPhaseHandler().setCombat(combatWithBlocker);
        gameWithBlocker.getAction().checkStateEffects(true);

        GameStateEvaluator.Score scoreWithBlocker =
                new GameStateEvaluator().getScoreForGameState(gameWithBlocker, ultronWithBlocker);

        // Control: identical setup, but seat 2 has no blocker -- Bears connects unblocked.
        initAndCreateGame();
        Game gameNoBlocker = createBattleboxGame();
        Player ultronNoBlocker = gameNoBlocker.getPlayers().get(0);
        Card bearsNoBlocker = addCard("Grizzly Bears", ultronNoBlocker);
        bearsNoBlocker.setSickness(false);
        Player seat2NoBlocker = gameNoBlocker.getPlayers().get(2);

        gameNoBlocker.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ultronNoBlocker);
        Combat combatNoBlocker = new Combat(ultronNoBlocker);
        combatNoBlocker.addAttacker(bearsNoBlocker, seat2NoBlocker);
        gameNoBlocker.getPhaseHandler().setCombat(combatNoBlocker);
        gameNoBlocker.getAction().checkStateEffects(true);

        GameStateEvaluator.Score scoreNoBlocker =
                new GameStateEvaluator().getScoreForGameState(gameNoBlocker, ultronNoBlocker);

        Assert.assertTrue(scoreWithBlocker.value < scoreNoBlocker.value,
                "Seat 2's own blocker (not the weakest-by-life seat 1) must be considered during simulated combat -- "
                        + "if only the weakest opponent's board were consulted, the attack would score identically "
                        + "whether or not seat 2 actually has a blocker. scoreWithBlocker=" + scoreWithBlocker
                        + " scoreNoBlocker=" + scoreNoBlocker);
    }

    // ------------------------------------------------------------------------------------------
    // TICKET-V3-205 (P2.5 continuation): declareBlockers via pruned-candidate simulation search.
    // ------------------------------------------------------------------------------------------

    @Test
    public void testDeclareBlockersReturnsLegalWithoutCrashing() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0); // seat 0 is Ultron -- the defender under test

        Card oppAttacker = addCard("Grizzly Bears", attacker);
        oppAttacker.setSickness(false);
        Card ultronBlocker = addCard("Runeclaw Bear", defender);
        ultronBlocker.setSickness(false);

        Combat combat = enterDeclareBlockers(game, attacker, Lists.newArrayList(ImmutablePair.of(oppAttacker, defender)));
        UltronPlayerController controller = ultronControllerFor(game);
        long before = controller.getTelemetry().getUltronAnsweredCount();

        controller.declareBlockers(defender, combat);

        Assert.assertEquals(controller.getTelemetry().getUltronAnsweredCount(), before + 1,
                "Simulation-based block declaration must not throw/fall back for a simple 4-player state");
        for (Card blockerCard : combat.getAllBlockers()) {
            Assert.assertEquals(blockerCard.getController(), defender,
                    "Every declared blocker must actually be controlled by the defending player");
        }
        Object detail = controller.getTelemetry().getLastDetail("declareBlockers");
        Assert.assertNotNull(detail, "candidate-count/chosen-score detail should be recorded (P2.5/P2.5-continuation telemetry requirement)");
    }

    @Test
    public void testProfitableCleanKillBlockIsChosen() {
        initAndCreateGame();
        Game game = createTwoPlayerBattleboxGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);

        // A vanilla 2/2 attacking into an available 6/4 blocker is a clean, risk-free kill for the
        // defender -- the blocker survives untouched and the attacker dies. This should be blocked.
        Card oppAttacker = addCard("Grizzly Bears", attacker);
        oppAttacker.setSickness(false);
        Card ultronBlocker = addCard("Craw Wurm", defender);
        ultronBlocker.setSickness(false);

        Combat combat = enterDeclareBlockers(game, attacker, Lists.newArrayList(ImmutablePair.of(oppAttacker, defender)));
        UltronPlayerController controller = ultronControllerFor(game);

        controller.declareBlockers(defender, combat);

        // Note: Combat.isBlocked() only reflects the per-band "blocked" flag, which is normally
        // set by PhaseHandler.declareBlockersTurnBasedAction's post-loop call to
        // Combat.fireTriggersForUnblockedAttackers once every defending player in a real game has
        // declared -- this unit test calls the controller directly, bypassing that wrapper, so the
        // flag is never (re)computed here. Assert on the actual blocker assignment instead, exactly
        // like testDeclareAttackersReturnsLegalSubsetWithoutCrashing/testBadTradeAttackIsDeclined
        // assert on combat.getAttackers() rather than a similarly turn-based-action-computed flag.
        Assert.assertFalse(combat.getBlockers(oppAttacker).isEmpty(),
                "A guaranteed clean kill (available 6/4 blocking a 2/2, no risk) should be taken");
        Assert.assertTrue(combat.getBlockers(oppAttacker).contains(ultronBlocker),
                "The Craw Wurm should be the one doing the blocking");
    }

    @Test
    public void testBadBlockIsDeclined() {
        initAndCreateGame();
        Game game = createTwoPlayerBattleboxGame();
        Player attacker = game.getPlayers().get(1);
        Player defender = game.getPlayers().get(0);
        defender.setLife(20, null); // plenty of life -- no lethal-prevention pressure

        // A huge 6/4 attacker versus the defender's only creature, a precious 2/2 with nothing to
        // gain from blocking (dies for nothing, doesn't even scratch the attacker, and there is no
        // lethal-damage concern at 20 life against a single 6-power attacker). Should be declined.
        Card oppAttacker = addCard("Craw Wurm", attacker);
        oppAttacker.setSickness(false);
        Card ultronCreature = addCard("Grizzly Bears", defender);
        ultronCreature.setSickness(false);

        Combat combat = enterDeclareBlockers(game, attacker, Lists.newArrayList(ImmutablePair.of(oppAttacker, defender)));
        UltronPlayerController controller = ultronControllerFor(game);

        controller.declareBlockers(defender, combat);

        Assert.assertTrue(combat.getBlockers(oppAttacker).isEmpty(),
                "A guaranteed bad block (2/2 dies to a 6/4 for zero benefit, no lethal pressure) should be declined");
    }

    /**
     * The mandatory recursion-safety proof for TICKET-V3-205: a 4-player Battlebox game where
     * seats 0 AND 1 both run {@code UltronPlayerController} (self-play/mirror shape). Seat 0
     * attacks seat 1, who has a blocker available -- forcing {@code declareAttackers}' own
     * candidate-scoring simulation to advance a copied game through {@code COMBAT_DECLARE_BLOCKERS},
     * which (per the recursion-safety javadoc on {@link UltronPlayerController#declareBlockers})
     * constructs a *fresh* {@code UltronPlayerController} for the copied seat-1 defender and calls
     * its {@code declareBlockers} -- without the {@code SIMULATION_IN_PROGRESS} thread-local guard,
     * that nested call would itself launch a full pruned-candidate search (its own {@code GameCopier}
     * + {@code GameStateEvaluator} passes per candidate), nested one level inside the outer search.
     *
     * <p>This test proves two things: (1) the outer {@code declareAttackers} call completes without
     * hanging/stack-overflowing/exploding in candidate count (the guard is working -- nested calls
     * fall back to cheap inherited behavior rather than recursing), and (2) the thread-local guard
     * is correctly reset afterward -- seat 1's *own*, real (non-nested) {@code declareBlockers} call,
     * made directly against the real combat right after, still runs the full Ultron simulation path
     * (answeredBy=ultron), proving {@code SIMULATION_IN_PROGRESS} does not leak "stuck true" state
     * across calls on the same thread.
     */
    @Test
    public void testUltronVsUltronBlockSimulationDoesNotRecurseUnbounded() {
        initAndCreateGame();
        Game game = createBattleboxGameWithTwoUltronSeats();
        Player seat0 = game.getPlayers().get(0);
        Player seat1 = game.getPlayers().get(1);

        Card seat0Attacker = addCard("Grizzly Bears", seat0);
        seat0Attacker.setSickness(false);
        Card seat1Blocker = addCard("Runeclaw Bear", seat1);
        seat1Blocker.setSickness(false);

        Combat combat = enterDeclareAttackers(game, seat0);
        UltronPlayerController seat0Controller = ultronControllerForSeat(game, 0);

        long startNanos = System.nanoTime();
        seat0Controller.declareAttackers(seat0, combat);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        Assert.assertTrue(elapsedMs < 30_000,
                "Ultron-vs-Ultron attack simulation (which nests a nested-controller declareBlockers "
                        + "call per FORGE_TRACKER TICKET-V3-205) must complete quickly, not hang/recurse "
                        + "unboundedly. elapsedMs=" + elapsedMs);
        Object attackDetail = seat0Controller.getTelemetry().getLastDetail("declareAttackers");
        Assert.assertNotNull(attackDetail, "Outer declareAttackers must still complete and record its own detail");

        // The guard must not leak "stuck true" state: seat 1's own real declareBlockers call (made
        // directly, not nested inside another controller's simulation) must still run the full
        // Ultron simulation path afterward on the same thread.
        Player seat2 = game.getPlayers().get(2);
        Card seat1Attacker = addCard("Craw Wurm", seat2);
        seat1Attacker.setSickness(false);
        Combat realCombat = enterDeclareBlockers(game, seat2, Lists.newArrayList(ImmutablePair.of(seat1Attacker, seat1)));
        UltronPlayerController seat1Controller = ultronControllerForSeat(game, 1);
        long beforeReal = seat1Controller.getTelemetry().getUltronAnsweredCount();

        seat1Controller.declareBlockers(seat1, realCombat);

        Assert.assertEquals(seat1Controller.getTelemetry().getUltronAnsweredCount(), beforeReal + 1,
                "After the outer Ultron-vs-Ultron simulation completes, the SIMULATION_IN_PROGRESS "
                        + "thread-local guard must be reset to false -- seat 1's own real declareBlockers "
                        + "call must still run the full simulation path, not be permanently guarded off.");
    }
}
