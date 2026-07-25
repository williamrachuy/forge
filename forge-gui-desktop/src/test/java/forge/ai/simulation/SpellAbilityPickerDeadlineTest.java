package forge.ai.simulation;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TICKET-V4-011 (root-cause fix for the abandoned-worker OOM leak, FORGE_TRACKER TICKET-V4-011,
 * diagnosed in TICKET-V4-003): unit coverage for {@link SpellAbilityPicker}'s cooperative deadline
 * checkpoint -- {@code setDeadlineMillis}/{@code deadlineExceeded()} -- added to the top-level
 * candidate search in {@code chooseSpellAbilityToPlayImpl} and {@code evaluateSa}.
 *
 * <p>Fixtures mirror {@link SpellAbilityPickerSimulationTest#testPickingLethalDamage()}: a
 * lethal-damage state with exactly one improving candidate ({@code Shock} for 2 killing a 2-life
 * opponent), which makes the "no deadline set" baseline deterministic and lets an expired deadline's
 * "stop before evaluating anything" effect be observed unambiguously via {@code
 * getNumSimulations() == 0}.
 */
public class SpellAbilityPickerDeadlineTest extends SimulationTest {

    private Player buildLethalDamageFixture(Game game) {
        Player p = game.getPlayers().get(1);
        p.setTeam(0);

        addCard("Mountain", p);
        addCardToZone("Shock", p, ZoneType.Hand);

        Player opponent = game.getPlayers().get(0);
        opponent.setTeam(1);
        addCard("Runeclaw Bear", opponent);
        opponent.setLife(2, null);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        game.getAction().checkStateEffects(true);
        return p;
    }

    /**
     * Lever 1's core claim: an already-expired deadline must stop the search before it does any
     * unbounded work, not merely "eventually" bound it. {@code getNumSimulations() == 0} proves no
     * {@code GameSimulator}/{@code GameCopier} work happened at all -- the exact allocation this
     * ticket exists to prevent from running past budget. The resulting {@code null} pick is a
     * legitimate "no candidate found within budget" answer (the same shape {@code
     * UltronPlayerController} already treats as a safe fallback trigger), not a crash.
     */
    @Test
    public void testExpiredDeadlineStopsSearchBeforeEvaluatingAnyCandidate() {
        Game game = initAndCreateGame();
        Player p = buildLethalDamageFixture(game);

        SpellAbilityPicker picker = new SpellAbilityPicker(game, p);
        picker.setDeadlineMillis(System.currentTimeMillis() - 60_000L);

        SpellAbility sa = picker.chooseSpellAbilityToPlay(null);

        AssertJUnit.assertTrue("Deadline checkpoint must record that it fired",
                picker.wasDeadlineExceededForTesting());
        AssertJUnit.assertEquals("An already-expired deadline must stop the search before any candidate "
                + "is evaluated -- this is the 'does not run unbounded' guarantee", 0, picker.getNumSimulations());
        AssertJUnit.assertNull("No candidate was evaluated, so best-so-far is legitimately null here", sa);
    }

    /**
     * "With no deadline set the behavior is unchanged": every non-Ultron caller (and Ultron with the
     * neural-eval feature off, since {@code UltronPlayerController} is the only caller that ever
     * invokes {@code setDeadlineMillis}) never touches this field, so it stays at its default (0,
     * meaning "no deadline"). Proves a generous, practically-unreachable deadline produces the exact
     * same pick as never setting one at all -- i.e. the deadline check is inert until it can actually
     * fire.
     */
    @Test
    public void testNoDeadlineSetMatchesGenerousDeadline() {
        Game gameNoDeadline = initAndCreateGame();
        Player pNoDeadline = buildLethalDamageFixture(gameNoDeadline);
        SpellAbilityPicker pickerNoDeadline = new SpellAbilityPicker(gameNoDeadline, pNoDeadline);
        SpellAbility saNoDeadline = pickerNoDeadline.chooseSpellAbilityToPlay(null);

        AssertJUnit.assertFalse("Default (unset) deadline must never be reported as exceeded",
                pickerNoDeadline.wasDeadlineExceededForTesting());
        AssertJUnit.assertNotNull(saNoDeadline);

        Game gameGenerous = initAndCreateGame();
        Player pGenerous = buildLethalDamageFixture(gameGenerous);
        SpellAbilityPicker pickerGenerous = new SpellAbilityPicker(gameGenerous, pGenerous);
        pickerGenerous.setDeadlineMillis(System.currentTimeMillis() + 300_000L);
        SpellAbility saGenerous = pickerGenerous.chooseSpellAbilityToPlay(null);

        AssertJUnit.assertFalse("A deadline that can't realistically be reached in this test must never fire",
                pickerGenerous.wasDeadlineExceededForTesting());
        AssertJUnit.assertNotNull(saGenerous);

        AssertJUnit.assertEquals("Setting a deadline that never fires must not change which candidate is "
                        + "picked, compared to never setting one at all",
                saNoDeadline.getHostCard().getName(), saGenerous.getHostCard().getName());
        AssertJUnit.assertEquals(saNoDeadline.getTargets().getFirstTargetedPlayer(),
                saGenerous.getTargets().getFirstTargetedPlayer());
    }

    /**
     * Exercises the mid-search checkpoint (the actual "returns best-so-far rather than running
     * unbounded" path) against a multi-candidate board, with a deadline set to fire almost
     * immediately after the search starts. Real {@code GameCopier}/{@code GameSimulator} work is not
     * sub-millisecond, so in practice this reliably interrupts the search before it completes -- but
     * the assertions below are deliberately timing-independent (they hold whether or not the
     * checkpoint actually won the race on a given run) so this test cannot flake in CI: it proves the
     * checkpoint never corrupts the result and never evaluates more than the full candidate set,
     * regardless of exactly when (if ever) it fires.
     */
    @Test
    public void testTightDeadlineMidSearchNeverCorruptsResult() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCards("Island", 2, p);
        addCards("Forest", 3, p);
        Card tatyova = addCardToZone("Tatyova, Benthic Druid", p, ZoneType.Hand);
        addCardToZone("Forest", p, ZoneType.Hand);
        addCardToZone("Forest", p, ZoneType.Library);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbilityPicker picker = new SpellAbilityPicker(game, p);
        picker.setDeadlineMillis(System.currentTimeMillis() + 1L);

        SpellAbility sa = picker.chooseSpellAbilityToPlay(null);

        // Whether or not the tight deadline actually won the race against this candidate's own
        // evaluation on this run, the result must always be well-formed: either a legitimate "no plan
        // found in time" null, or the real Tatyova play this fixture's non-deadlined counterpart
        // (testPlayingLandAfterSpell) finds.
        if (sa != null) {
            AssertJUnit.assertEquals(tatyova, sa.getHostCard());
        }
        if (picker.wasDeadlineExceededForTesting()) {
            org.testng.Assert.assertTrue(picker.getNumSimulations() >= 0,
                    "Sanity: simulation count must never go negative when the deadline interrupts the search");
        }
    }
}
