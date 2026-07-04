package forge.ai.ultron;

import com.google.common.collect.Lists;
import forge.ai.AIOption;
import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
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
import forge.game.spellability.SpellAbility;
import forge.game.zone.SharedPlayerZone;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TICKET-V3-206 (Ultron v3 Phase 2, P2.6): stack response.
 *
 * <p><b>Finding #1 (see {@code UltronPlayerController#chooseSpellAbilityToPlay()}'s updated
 * javadoc for the full trace): P2.6 required no new decision-routing code.</b> Forge has exactly
 * one priority-pass entrypoint ({@code PhaseHandler.mainLoopStep} calling
 * {@code controller.chooseSpellAbilityToPlay()}), used identically whether the stack is empty
 * (main-phase play) or not (stack response). P2.4 (TICKET-V3-203) already routed 100% of these
 * calls through the simulation-based {@code SpellAbilityPicker}, whose candidate generation
 * naturally narrows to instant-speed responses when the stack is non-empty (via
 * {@code SpellAbility#isLegalAfterStack()} timing checks) and always treats "pass" as the
 * implicit baseline.
 *
 * <p><b>Finding #2 (discovered building this session's verification tests, not previously
 * documented by TICKET-V3-203/204/205): responses that target something ON the stack --
 * chiefly true countermagic -- cannot currently be evaluated correctly, for a reason distinct
 * from the already-documented "weakest opponent" gap.</b> {@code GameCopier.makeCopy} only
 * copies the game's actual {@code SpellAbilityStackInstance} queue
 * ({@code game.getStack()}) when the static flag {@code GameSimulator.COPY_STACK} is true, and
 * it defaults to {@code false} (see {@code GameSimulator.java:20} and
 * {@code GameCopier.java:177-178}). {@code GameSimulator}'s own constructor only flips it on
 * transiently for a narrow "resolve the stack once up front to get a comparable baseline score"
 * case, never for the copies made to actually simulate playing a candidate spell ability. So when
 * {@code SpellAbilityPicker} simulates "what if I cast Counterspell right now," the copy it scores
 * against has an <em>empty</em> ability stack -- the opposing spell still shows up as a card in
 * the {@code Stack} card zone (that part of {@code GameCopier} is unconditional), but there is no
 * {@code SpellAbilityStackInstance} for {@code MultiTargetSelector} to find as a legal
 * {@code TargetType$ Spell} target. {@code hasPossibleTargets()} returns false, no target is ever
 * selected, {@code SpellAbility#isTargetNumberValid()} fails, and
 * {@code ComputerUtil.handlePlayingSpellAbility} returns {@code false} --
 * {@code GameSimulator.simulateSpellAbility} then returns {@code Score(Integer.MIN_VALUE)} for
 * that candidate unconditionally, regardless of how big a threat the spell on the stack actually
 * is. Net effect: <b>Ultron can never choose to counter anything via this path today</b> -- not a
 * narrow optimism margin like the weakest-opponent gap, a hard "always evaluates to worse than
 * passing." {@link #testCounterspellCandidateCannotBeEvaluatedDueToUncopiedStack()} documents this
 * precisely so it isn't silently rediscovered (or silently "fixed" by a future session assuming
 * it's the same as the already-known gap). Per this session's explicit constraint not to touch
 * {@code GameCopier.java}/{@code GameStateEvaluator.java} scoring logic, this is <b>not fixed
 * here</b> -- {@code GameCopier} would need to unconditionally preserve the
 * {@code SpellAbilityStackInstance} queue (not just the {@code Stack} zone's cards) for stack-
 * targeting responses to ever be evaluable, which is squarely "touch GameCopier," out of this
 * session's scope. Recommended as Phase 4 (or an earlier, dedicated) prerequisite -- countermagic
 * is common enough in Battlebox that leaving it permanently un-evaluable would cap Ultron's
 * ceiling on its own, independent of the belief-state work Phase 4 already plans.
 *
 * <p>Everything that targets the battlefield/players (not the stack itself) -- removal, combat
 * tricks, burn -- works correctly through this same path today, as
 * {@link #testStackResponseKillsLethalAttackerBeforeBlockers()} demonstrates.
 */
public class UltronStackResponseSimulationTest extends AITest {

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
        return finishBattleboxSetup(players, "UltronStackResponseSimulationTest");
    }

    /** Both seat 0 and seat 1 run {@code UltronPlayerController} -- used by the recursion-safety
     *  smoke test, mirroring TICKET-V3-205's convention for combat. */
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
        return finishBattleboxSetup(players, "UltronStackResponseSimulationTestMirror");
    }

    private Game finishBattleboxSetup(List<RegisteredPlayer> players, String name) {
        GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.Battlebox);
        Match match = new Match(rules, players, name);
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
        for (Player p : game.getPlayers()) {
            p.setLife(20, null);
        }
        return game;
    }

    private UltronPlayerController ultronControllerForSeat(Game game, int seat) {
        Player p = game.getPlayers().get(seat);
        Assert.assertTrue(p.getController() instanceof UltronPlayerController,
                "Fixture bug: seat " + seat + " must be wired to UltronPlayerController for this test to mean anything");
        return (UltronPlayerController) p.getController();
    }

    private void setMainPhase(Game game, Player active) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, active);
        game.getPhaseHandler().onStackResolved();
        game.getAction().checkStateEffects(true);
    }

    /** Puts {@code cardName} on the stack as {@code caster}'s spell, paying real costs via the
     *  same AI helper the engine itself uses ({@code ComputerUtil.playStack}) -- caster needs
     *  legal untapped mana sources on the battlefield already, same as a real cast. */
    private SpellAbility putOnStack(Game game, String cardName, Player caster) {
        Card card = addCardToZone(cardName, caster, ZoneType.Hand);
        SpellAbility sa = card.getFirstSpellAbility();
        Assert.assertNotNull(sa, "Fixture bug: " + cardName + " has no castable spell ability");
        boolean played = ComputerUtil.playStack(sa, caster, game);
        Assert.assertTrue(played, "Fixture bug: " + cardName + " failed to go on the stack (check mana sources)");
        Assert.assertFalse(game.getStack().isEmpty(), "Fixture bug: stack should be non-empty after putOnStack");
        return sa;
    }

    /**
     * <b>Positive case:</b> an unblocked attacker whose damage is exactly lethal (Ultron has no
     * blockers available) and an instant-speed removal spell that can kill it before combat
     * damage. This is the clearest, most decisive "should I respond" judgment available: passing
     * loses the game outright, responding wins it outright, so the simulated scores differ by the
     * evaluator's full life-or-death range (see {@code GameStateEvaluator#getScoreForGameOver}).
     *
     * <p>The {@code SpellAbilityPicker}/{@code Plan} machinery (pre-existing, not Ultron-specific)
     * has its own "hold instants until it's actually necessary" heuristic
     * ({@code SpellAbilityPicker#createNewPlan}'s "phase bloom" check): since casting Doom Blade
     * now scores identically to casting it after blockers are declared (there are none to change
     * the outcome here), it correctly defers rather than committing early -- a legitimate call in
     * general (blocks can change what's worth killing) even though it doesn't matter in this exact
     * fixture. The test drives the phase forward to where the plan expects to act, exactly as the
     * real {@code PhaseHandler.mainLoopStep} priority loop would on the very next window.
     */
    @Test
    public void testStackResponseKillsLethalAttackerBeforeBlockers() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);

        // No blockers available to Ultron -- a 4-power attacker unblocked is exactly lethal at
        // 4 life, so passing (or waiting too long) means losing the game.
        ultron.setLife(4, null);
        addCard("Swamp", ultron);
        addCard("Swamp", ultron);
        addCardToZone("Doom Blade", ultron, ZoneType.Hand);

        Card attacker = addCard("Serra Angel", opponent);
        attacker.setSickness(false);

        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, opponent);
        Combat combat = new Combat(opponent);
        combat.addAttacker(attacker, ultron);
        game.getPhaseHandler().setCombat(combat);
        combat.initConstraints();
        game.getAction().checkStateEffects(true);

        UltronPlayerController controller = ultronControllerForSeat(game, 0);
        long before = controller.getTelemetry().getUltronAnsweredCount();

        List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();
        long expectedAnswered = before + 1;
        if (chosen == null) {
            // Legitimate "wait until declare blockers" plan decision (see javadoc above) -- advance
            // to the phase the plan is actually waiting for and ask again, exactly as the real
            // priority loop would on its very next pass. Both calls are genuine simulation-based
            // answers (the first one's answer was "not yet"), so both count toward the tally.
            game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, opponent, false);
            combat.setBlocked(attacker, false);
            game.getAction().checkStateEffects(true);
            chosen = controller.chooseSpellAbilityToPlay();
            expectedAnswered = before + 2;
        }

        Assert.assertEquals(controller.getTelemetry().getUltronAnsweredCount(), expectedAnswered,
                "Instant-speed response before/around blockers must resolve via the simulation path");
        Assert.assertNotNull(chosen, "Passing here is lethal -- Doom Blade on the sole attacker must be chosen");
        Assert.assertEquals(chosen.get(0).getHostCard().getName(), "Doom Blade",
                "Killing the only attacker to survive an otherwise-lethal combat should be the clear best answer");

        Map<String, Object> detail = controller.getTelemetry().getLastDetail("chooseSpellAbilityToPlay");
        Assert.assertNotNull(detail, "P2.6 telemetry requirement: stackNonEmpty/candidateCount/chosenScore detail");
    }

    /**
     * <b>Sanity/decline case:</b> stack is non-empty (opponent casts a card-draw spell with zero
     * board impact by itself) but Ultron has no beneficial instant-speed response available at all
     * (its only instant, Doom Blade, has no legal creature target anywhere in the game) --
     * passing is the only sane answer, and must be reached via the simulation path (zero
     * candidates makes the search itself trivial), not a fallback.
     */
    @Test
    public void testStackResponseDeclinesWhenNoLegalResponseExists() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);

        addCard("Swamp", ultron);
        addCard("Swamp", ultron);
        addCardToZone("Doom Blade", ultron, ZoneType.Hand);

        addCard("Island", opponent);
        addCard("Island", opponent);
        addCard("Island", opponent);

        setMainPhase(game, opponent);
        // No creatures exist anywhere in this game -- Doom Blade has no legal target, so it is
        // never even a candidate; Divination itself has no board impact. Passing is correct.
        putOnStack(game, "Divination", opponent);

        UltronPlayerController controller = ultronControllerForSeat(game, 0);
        long before = controller.getTelemetry().getUltronAnsweredCount();

        List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();

        Assert.assertEquals(controller.getTelemetry().getUltronAnsweredCount(), before + 1,
                "Declining to respond is still a simulation-based answer, not a fallback");
        Assert.assertNull(chosen, "No legal, beneficial instant-speed response exists -- passing (null) is correct");

        Map<String, Object> detail = controller.getTelemetry().getLastDetail("chooseSpellAbilityToPlay");
        Assert.assertEquals(detail.get("stackNonEmpty"), Boolean.TRUE,
                "Telemetry must record that this decision was made with a non-empty stack");
        Assert.assertEquals(detail.get("candidateCount"), 0,
                "Doom Blade has no legal creature target anywhere -- it should not even be a candidate");
    }

    /**
     * Documents Finding #2 from this class's javadoc precisely, as a regression-guarding test
     * rather than a silent gap: even against a serious, board-relevant threat (an unanswered
     * Serra Angel), with an affordable, legal-in-principle Counterspell in hand, the existing
     * simulation path returns {@code null} (declines to respond) today -- not because countering
     * is a bad idea, but because {@code GameCopier} does not copy the actual
     * {@code SpellAbilityStackInstance} queue by default, so the simulated copy has nothing on its
     * stack for Counterspell to legally target. If a future session fixes the underlying
     * {@code GameCopier} gap (out of scope for this session, see the class javadoc), this test
     * should be revisited/inverted to assert the correct "counters the threat" behavior instead.
     */
    @Test
    public void testCounterspellCandidateCannotBeEvaluatedDueToUncopiedStack() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);

        addCard("Island", ultron);
        addCard("Island", ultron);
        addCardToZone("Counterspell", ultron, ZoneType.Hand);

        addCard("Plains", opponent);
        addCard("Plains", opponent);
        addCard("Plains", opponent);
        addCard("Plains", opponent);
        addCard("Plains", opponent);

        setMainPhase(game, opponent);
        putOnStack(game, "Serra Angel", opponent);

        UltronPlayerController controller = ultronControllerForSeat(game, 0);
        long before = controller.getTelemetry().getUltronAnsweredCount();

        List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();

        Assert.assertEquals(controller.getTelemetry().getUltronAnsweredCount(), before + 1,
                "Even the (currently mis-scored) Counterspell candidate must resolve via the simulation "
                        + "path without throwing");
        Assert.assertNull(chosen, "KNOWN GAP (see class javadoc, Finding #2): Counterspell can never be "
                + "chosen today because GameCopier does not copy the SpellAbilityStackInstance queue by "
                + "default, so the simulated copy has no stack object for it to target -- this is true "
                + "regardless of how threatening the countered spell actually is, not a considered decline");
    }

    /**
     * Recursion-safety smoke test (task-mandated check, even though the analysis in
     * {@code UltronPlayerController#chooseSpellAbilityToPlay()}'s javadoc concludes there is no new
     * recursion surface here): a mirror game with two Ultron seats and a non-empty stack must not
     * hang or crash. Unlike {@code declareAttackers}/{@code declareBlockers}, this method is never
     * invoked on a copied game's players (simulation drives combat via {@code devAdvanceToPhase}
     * and resolves stacks via a hardcoded plain {@code PlayerControllerAi}, never
     * {@code getController()} on a copy) -- so this is expected to simply complete quickly, not to
     * exercise a guard.
     */
    @Test
    public void testUltronVsUltronStackResponseDoesNotRecurseOrHang() {
        initAndCreateGame();
        Game game = createBattleboxGameWithTwoUltronSeats();
        Player ultron = game.getPlayers().get(0);
        Player otherUltron = game.getPlayers().get(1);

        addCard("Island", ultron);
        addCard("Island", ultron);
        addCardToZone("Counterspell", ultron, ZoneType.Hand);

        addCard("Plains", otherUltron);
        addCard("Plains", otherUltron);
        addCard("Plains", otherUltron);
        addCard("Plains", otherUltron);
        addCard("Plains", otherUltron);

        setMainPhase(game, otherUltron);
        putOnStack(game, "Serra Angel", otherUltron);

        UltronPlayerController controller = ultronControllerForSeat(game, 0);

        long start = System.currentTimeMillis();
        List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();
        long elapsedMs = System.currentTimeMillis() - start;

        Assert.assertTrue(elapsedMs < 30_000,
                "Stack-response decision against another Ultron seat must complete quickly (no unbounded "
                        + "recursion) -- took " + elapsedMs + "ms");
        // No assertion on the specific answer -- this test only proves the call terminates sanely.
        Assert.assertTrue(chosen == null || !chosen.isEmpty());
    }
}
