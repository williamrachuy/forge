package forge.ai.simulation;

import forge.ai.llm.UltronConfig;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TICKET-V4-014 (Version A, change 2 -- the key new mechanism the prior three cost-reduction
 * attempts, V4-010/011/013, lacked): unit coverage for the HARD per-decision {@code
 * GameCopier.makeCopy()} budget -- {@code UltronConfig.resetSimCopyBudget}/{@code
 * tryConsumeSimCopyBudget}/{@code simCopyBudgetExceeded}, wired into {@code SpellAbilityPicker}'s
 * top-level candidate loop ({@code chooseSpellAbilityToPlayImpl}) and target/mode fan-out loop
 * ({@code evaluateSa}).
 *
 * <p>Fixture mirrors {@code SpellAbilityPickerDeadlineTest#testTightDeadlineMidSearchNeverCorruptsResult}:
 * a multi-candidate board (several lands playable, plus Tatyova, Benthic Druid) so a tiny copy
 * budget has real candidates to be interrupted between/within, not just a single trivial one.
 */
public class SpellAbilityPickerCopyBudgetTest extends SimulationTest {

    private Player buildMultiCandidateFixture(Game game) {
        Player p = game.getPlayers().get(1);

        addCards("Island", 2, p);
        addCards("Forest", 3, p);
        addCardToZone("Tatyova, Benthic Druid", p, ZoneType.Hand);
        addCardToZone("Forest", p, ZoneType.Hand);
        addCardToZone("Forest", p, ZoneType.Library);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);
        return p;
    }

    /**
     * The core claim of the hard budget: a cap of 1 copy must stop the search well before it
     * would otherwise finish (this fixture's baseline, proven by the "no budget" test below, spends
     * more than 1 simulation), the picker must record that the budget fired, and the picker must
     * still return a well-formed answer -- either a legitimate "nothing evaluated within budget"
     * {@code null}, or one of the fixture's real legal candidates -- never a crash or a malformed
     * pick. {@code UltronConfig.resetSimCopyBudget(int)}/{@code clearSimCopyBudget()} bracket the
     * call the same way {@code UltronPlayerController}'s three decision entry points do in
     * production, except with an explicit tiny cap (test-only overload) instead of the configured
     * default, since the default (18) is far too generous to deterministically interrupt this small
     * a fixture.
     */
    @Test
    public void testTinyCopyBudgetStopsSearchAndReturnsLegalChoice() {
        Game game = initAndCreateGame();
        Player p = buildMultiCandidateFixture(game);

        SpellAbilityPicker picker = new SpellAbilityPicker(game, p);
        UltronConfig.resetSimCopyBudget(1);
        SpellAbility sa;
        try {
            sa = picker.chooseSpellAbilityToPlay(null);
        } finally {
            UltronConfig.clearSimCopyBudget();
        }

        AssertJUnit.assertTrue("A budget of 1 copy must fire on this multi-candidate fixture",
                picker.wasCopyBudgetExceededForTesting());
        AssertJUnit.assertTrue("At most the budget's worth of GameSimulator constructions may have "
                        + "happened -- this is the hard-ceiling guarantee",
                picker.getNumSimulations() <= 1);
        if (sa != null) {
            // Must be one of the fixture's own legal top-level candidates, not a corrupted pick.
            Card host = sa.getHostCard();
            AssertJUnit.assertTrue("Chosen ability's host card must be a real card from this player's "
                            + "own hand/battlefield fixture",
                    host.getController() == p || host.getOwner() == p);
        }
    }

    /**
     * "With no budget set the behavior is unchanged": every non-Ultron caller (and Ultron with the
     * neural-eval-independent copy budget never activated on this thread, e.g. every existing test)
     * never calls {@code UltronConfig.resetSimCopyBudget()}, so {@code tryConsumeSimCopyBudget()}/
     * {@code simCopyBudgetExceeded()} always report "unlimited"/"not exceeded". Proves that state
     * produces the exact same pick as an explicitly generous budget, and that the budget-exceeded
     * flag never fires spuriously.
     */
    @Test
    public void testNoCopyBudgetSetMatchesGenerousBudget() {
        Game gameNoBudget = initAndCreateGame();
        Player pNoBudget = buildMultiCandidateFixture(gameNoBudget);
        SpellAbilityPicker pickerNoBudget = new SpellAbilityPicker(gameNoBudget, pNoBudget);
        // Deliberately do NOT call UltronConfig.resetSimCopyBudget() -- this is the default,
        // "inactive on this thread" state every non-Ultron caller leaves it in.
        SpellAbility saNoBudget = pickerNoBudget.chooseSpellAbilityToPlay(null);

        AssertJUnit.assertFalse("Default (inactive) copy budget must never be reported as exceeded",
                pickerNoBudget.wasCopyBudgetExceededForTesting());
        AssertJUnit.assertTrue("Baseline fixture must spend more than 1 simulation, so the tiny-budget "
                        + "test above is actually exercising an interruption, not a no-op",
                pickerNoBudget.getNumSimulations() > 1);

        Game gameGenerous = initAndCreateGame();
        Player pGenerous = buildMultiCandidateFixture(gameGenerous);
        SpellAbilityPicker pickerGenerous = new SpellAbilityPicker(gameGenerous, pGenerous);
        UltronConfig.resetSimCopyBudget(1000);
        SpellAbility saGenerous;
        try {
            saGenerous = pickerGenerous.chooseSpellAbilityToPlay(null);
        } finally {
            UltronConfig.clearSimCopyBudget();
        }

        AssertJUnit.assertFalse("A budget that can't realistically be reached in this test must never fire",
                pickerGenerous.wasCopyBudgetExceededForTesting());
        AssertJUnit.assertNotNull(saNoBudget);
        AssertJUnit.assertNotNull(saGenerous);
        AssertJUnit.assertEquals("Setting a budget that never fires must not change which candidate is "
                        + "picked, compared to never activating budget tracking at all",
                saNoBudget.getHostCard().getName(), saGenerous.getHostCard().getName());
    }

    /**
     * Direct unit coverage of {@link UltronConfig}'s counter mechanics, independent of {@code
     * SpellAbilityPicker} -- the "checked before allocating is a true ceiling" contract: the
     * (budget+1)th {@code tryConsumeSimCopyBudget()} call must return {@code false} without having
     * incremented past the cap, and {@code simCopyBudgetExceeded()} must agree.
     */
    @Test
    public void testCopyBudgetCounterMechanics() {
        UltronConfig.resetSimCopyBudget(3);
        try {
            AssertJUnit.assertFalse(UltronConfig.simCopyBudgetExceeded());
            AssertJUnit.assertTrue(UltronConfig.tryConsumeSimCopyBudget());
            AssertJUnit.assertTrue(UltronConfig.tryConsumeSimCopyBudget());
            AssertJUnit.assertTrue(UltronConfig.tryConsumeSimCopyBudget());
            AssertJUnit.assertEquals(3, UltronConfig.getSimCopyBudgetUsed());
            AssertJUnit.assertTrue(UltronConfig.simCopyBudgetExceeded());
            AssertJUnit.assertFalse("A 4th consume attempt past a budget of 3 must be refused",
                    UltronConfig.tryConsumeSimCopyBudget());
            AssertJUnit.assertEquals("A refused consume must not have incremented the counter",
                    3, UltronConfig.getSimCopyBudgetUsed());
        } finally {
            UltronConfig.clearSimCopyBudget();
        }

        AssertJUnit.assertFalse("Cleared budget must report inactive (never exceeded)",
                UltronConfig.simCopyBudgetExceeded());
        AssertJUnit.assertTrue("Cleared budget must be unlimited",
                UltronConfig.tryConsumeSimCopyBudget());
        AssertJUnit.assertEquals(0, UltronConfig.getSimCopyBudgetUsed());
    }
}
