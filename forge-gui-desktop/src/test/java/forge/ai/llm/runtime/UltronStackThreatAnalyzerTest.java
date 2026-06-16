package forge.ai.llm.runtime;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Unit tests for UltronStackThreatAnalyzer and related classes.
 * These tests exercise the threat model logic without requiring network access,
 * a game engine, or DEEPSEEK_API_KEY.
 */
public class UltronStackThreatAnalyzerTest {

    // -----------------------------------------------------------------------
    // UltronRuntimeDecision
    // -----------------------------------------------------------------------

    @Test
    public void testPassDecision() {
        UltronRuntimeDecision d = UltronRuntimeDecision.pass("test");
        Assert.assertTrue(d.isPass());
        Assert.assertFalse(d.hasChoice());
        Assert.assertFalse(d.shouldFallback());
        Assert.assertNull(d.getSpellAbility());
        Assert.assertEquals(d.getReason(), "test");
    }

    @Test
    public void testNoDecision() {
        UltronRuntimeDecision d = UltronRuntimeDecision.noDecision("no op");
        Assert.assertFalse(d.isPass());
        Assert.assertFalse(d.hasChoice());
        Assert.assertTrue(d.shouldFallback());
    }

    @Test
    public void testFallbackDecision() {
        UltronRuntimeDecision d = UltronRuntimeDecision.fallback("fallback reason");
        Assert.assertFalse(d.isPass());
        Assert.assertFalse(d.hasChoice());
        Assert.assertTrue(d.shouldFallback());
        Assert.assertEquals(d.getReason(), "fallback reason");
    }

    @Test
    public void testChooseNullConvertedToPass() {
        UltronRuntimeDecision d = UltronRuntimeDecision.choose(null, "null sa");
        Assert.assertTrue(d.isPass(), "choose(null) should become PASS");
    }

    // -----------------------------------------------------------------------
    // UltronStackThreatType
    // -----------------------------------------------------------------------

    @Test
    public void testStackThreatTypeValues() {
        // Verify all expected enum values exist
        Assert.assertNotNull(UltronStackThreatType.NONE);
        Assert.assertNotNull(UltronStackThreatType.LOW_VALUE);
        Assert.assertNotNull(UltronStackThreatType.BOARD_WIPE);
        Assert.assertNotNull(UltronStackThreatType.EXTRA_TURN);
        Assert.assertNotNull(UltronStackThreatType.LETHAL_DAMAGE);
        Assert.assertNotNull(UltronStackThreatType.LETHAL_LIFE_LOSS);
        Assert.assertNotNull(UltronStackThreatType.GAME_WINNING_EFFECT);
        Assert.assertNotNull(UltronStackThreatType.COMBO_PIECE);
        Assert.assertNotNull(UltronStackThreatType.MASS_REANIMATION);
        Assert.assertNotNull(UltronStackThreatType.VALUE_ENGINE);
    }

    // -----------------------------------------------------------------------
    // UltronStackThreat
    // -----------------------------------------------------------------------

    @Test
    public void testNoneThreatNotActionable() {
        UltronStackThreat threat = UltronStackThreat.NONE;
        Assert.assertFalse(threat.isActionable(50), "NONE (sev=0) should not be actionable at threshold 50");
        Assert.assertFalse(threat.isActionable(1), "NONE (sev=0) should not be actionable at threshold 1");
    }

    @Test
    public void testHighSeverityThreatActionable() {
        UltronStackThreat threat = new UltronStackThreat(
                UltronStackThreatType.LETHAL_DAMAGE, 99, null, null, "test");
        Assert.assertTrue(threat.isActionable(50));
        Assert.assertTrue(threat.isActionable(95));
        Assert.assertFalse(threat.isActionable(100));
    }

    @Test
    public void testSeverityClampedTo100() {
        UltronStackThreat threat = new UltronStackThreat(
                UltronStackThreatType.GAME_WINNING_EFFECT, 999, null, null, "over");
        Assert.assertEquals(threat.severity, 100);
    }

    @Test
    public void testSeverityClampedToZero() {
        UltronStackThreat threat = new UltronStackThreat(
                UltronStackThreatType.NONE, -50, null, null, "negative");
        Assert.assertEquals(threat.severity, 0);
    }

    // -----------------------------------------------------------------------
    // UltronRuntimeRole
    // -----------------------------------------------------------------------

    @Test
    public void testRoleValuesExist() {
        Assert.assertNotNull(UltronRuntimeRole.AHEAD);
        Assert.assertNotNull(UltronRuntimeRole.BEHIND);
        Assert.assertNotNull(UltronRuntimeRole.STABILIZING);
        Assert.assertNotNull(UltronRuntimeRole.PRESSURING);
        Assert.assertNotNull(UltronRuntimeRole.CONTROL);
        Assert.assertNotNull(UltronRuntimeRole.COMBO_DEFENSE);
        Assert.assertNotNull(UltronRuntimeRole.DESPERATE);
    }

    // -----------------------------------------------------------------------
    // UltronScore
    // -----------------------------------------------------------------------

    @Test
    public void testScoreComparison() {
        UltronScore low  = new UltronScore(10, 0, 0, 0, 0, 0, 0, 0, 0, "low");
        UltronScore high = new UltronScore(80, 0, 0, 0, 0, 0, 0, 0, 0, "high");
        Assert.assertTrue(low.compareTo(high) < 0);
        Assert.assertTrue(high.compareTo(low) > 0);
        Assert.assertEquals(low.compareTo(low), 0);
    }

    @Test
    public void testZeroScore() {
        Assert.assertEquals(UltronScore.ZERO.value, 0);
    }

    // -----------------------------------------------------------------------
    // UltronManaReservation
    // -----------------------------------------------------------------------

    @Test
    public void testNoReservation() {
        Assert.assertEquals(UltronManaReservation.NONE.total(), 0);
    }

    @Test
    public void testReservationTotal() {
        UltronManaReservation r = new UltronManaReservation(1, 0, 1, 0, 0, 0, "1U");
        Assert.assertEquals(r.total(), 2);
        Assert.assertEquals(r.totalColored(), 1);
    }
}
