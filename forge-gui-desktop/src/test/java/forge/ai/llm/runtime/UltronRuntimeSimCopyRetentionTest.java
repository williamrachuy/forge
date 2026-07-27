package forge.ai.llm.runtime;

import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.game.Game;
import forge.game.player.Player;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * TICKET-V4-022 regression test.
 *
 * <p>{@code UltronRuntimeController.INSTANCES} is a {@code static} (therefore permanently
 * GC-rooted) {@link java.util.WeakHashMap}, but {@code WeakHashMap} holds keys weakly and
 * <b>values strongly</b>, and each value holds a strong reference back to its own {@link Game}
 * key. Entries can therefore never be evicted. Before the fix, every {@code GameCopier}
 * simulation copy that reached {@code getOrCreate} was pinned for the life of the JVM along with
 * its whole object graph — measured at ~200 leaked copies per minute, exhausting a 6 GB heap in
 * under three minutes and causing the 40s decision timeouts this project spent four tickets
 * misdiagnosing as compute cost.
 *
 * <p>These tests pin the invariant in both directions: simulation copies must never grow the
 * registry, and real games must still register exactly as before (the GUI's {@code getSimStats}
 * lookup depends on it).
 */
public class UltronRuntimeSimCopyRetentionTest extends AITest {

    @Test
    public void testSimulationCopiesAreNeverRegisteredInTheStaticRegistry() {
        final int before = UltronRuntimeController.registeredGameCountForTesting();

        // Stand up several distinct "simulation copies" and ask each for a controller, which is
        // exactly what ComputerUtil.invalidateUltronRuntime does on the copy path.
        for (int i = 0; i < 5; i++) {
            Game copy = initAndCreateGame();
            copy.dangerouslyMarkAsSimulationCopy();
            Assert.assertTrue(copy.isSimulationCopy(), "test setup: copy should be marked");

            Player ai = copy.getPlayers().get(0);
            UltronRuntimeController ctrl =
                    UltronRuntimeController.getOrCreate(copy, ai, new AiCardMemory());

            Assert.assertNotNull(ctrl,
                    "getOrCreate must still hand back a usable controller for simulation copies; "
                            + "callers rely on a non-null result");
        }

        final int after = UltronRuntimeController.registeredGameCountForTesting();
        Assert.assertTrue(after <= before,
                "Simulation copies must never be registered in the static INSTANCES map "
                        + "(TICKET-V4-022: entries are unevictable and pin the whole game graph). "
                        + "Registered game count went from " + before + " to " + after);
    }

    @Test
    public void testRealGamesAreStillRegistered() {
        Game real = initAndCreateGame();
        Assert.assertFalse(real.isSimulationCopy(), "test setup: real game must not be a copy");

        Player ai = real.getPlayers().get(0);
        UltronRuntimeController first =
                UltronRuntimeController.getOrCreate(real, ai, new AiCardMemory());
        UltronRuntimeController second =
                UltronRuntimeController.getOrCreate(real, ai, new AiCardMemory());

        Assert.assertSame(first, second,
                "Real games must keep the cached-per-(game,player) contract — the fix must not "
                        + "change behavior off the simulation-copy path");
    }

    @Test
    public void testSimulationCopiesGetFreshUnsharedControllers() {
        Game copy = initAndCreateGame();
        copy.dangerouslyMarkAsSimulationCopy();
        Player ai = copy.getPlayers().get(0);

        UltronRuntimeController first =
                UltronRuntimeController.getOrCreate(copy, ai, new AiCardMemory());
        UltronRuntimeController second =
                UltronRuntimeController.getOrCreate(copy, ai, new AiCardMemory());

        Assert.assertNotSame(first, second,
                "Unregistered simulation-copy controllers are expected to be fresh per call; "
                        + "this documents the deliberate behavior change (a copy is scored once "
                        + "and discarded, so per-copy caching buys nothing)");
    }
}
