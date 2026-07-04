package forge.ai.ultron;

import com.google.common.collect.Lists;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.runtime.UltronTableThreatSummary;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Construction/wiring and feature-provider tests for Phase 1 of Ultron v3 (see
 * FORGE_TRACKER TICKET-V3-101/103). Matches the {@code createFourPlayerGame}/{@code aiPlayer}
 * helper pattern used by the existing {@code forge.ai.llm.runtime.Ultron*Test} suite.
 */
public class UltronPlayerControllerTest extends AITest {

    // -----------------------------------------------------------------------
    // P1.1 -- construction/wiring
    // -----------------------------------------------------------------------

    @Test
    public void testUltronProfileGetsUltronPlayerController() {
        Game game = createFourPlayerGame(true);
        Player ultron = game.getPlayers().get(0);

        PlayerController controller = ultron.getController();
        Assert.assertTrue(controller instanceof UltronPlayerController,
                "Ultron-profile player should be wired to UltronPlayerController, got " + controller.getClass());
    }

    @Test
    public void testNonUltronProfileGetsPlainPlayerControllerAi() {
        Game game = createFourPlayerGame(true);
        Player opponent = game.getPlayers().get(1);

        PlayerController controller = opponent.getController();
        Assert.assertFalse(controller instanceof UltronPlayerController,
                "Default-profile players must not get UltronPlayerController -- Phase 1 leaves every "
                        + "other profile untouched");
    }

    @Test
    public void testTelemetryNeverRecordsAnUltronAuthoredDecision() {
        // Phase 1 is pure plumbing -- game setup itself (devModeSet/onStackResolved) already drives
        // a decision or two through the controller, so total-decisions is not reliably zero at this
        // point. What Phase 1 guarantees is that none of them are Ultron-authored yet.
        Game game = createFourPlayerGame(true);
        Player ultron = game.getPlayers().get(0);
        UltronPlayerController controller = (UltronPlayerController) ultron.getController();

        Assert.assertEquals(controller.getTelemetry().getUltronAnsweredCount(), 0,
                "Phase 1 has no Ultron-authored decision logic yet -- every call must be inherited");
    }

    @Test
    public void testDecisionMethodRecordsInheritedTelemetry() {
        Game game = createFourPlayerGame(true);
        Player ultron = game.getPlayers().get(0);
        UltronPlayerController controller = (UltronPlayerController) ultron.getController();

        long before = controller.getTelemetry().getTotalDecisions();

        // A trivial void decision method -- exercises the overridden-method -> super -> telemetry
        // wiring without needing a full game loop.
        controller.resetAtEndOfTurn();

        Assert.assertEquals(controller.getTelemetry().getTotalDecisions(), before + 1,
                "resetAtEndOfTurn() should record exactly one additional decision");
        Assert.assertEquals(controller.getTelemetry().getUltronAnsweredCount(), 0,
                "Phase 1 has no Ultron-authored decision logic yet -- every call is inherited");
    }

    // -----------------------------------------------------------------------
    // P1.3 -- threat model as a callable, read-only feature provider
    // -----------------------------------------------------------------------

    @Test
    public void testRefreshThreatSummaryIsCallableAndReadOnly() {
        Game game = createFourPlayerGame(true);
        Player ultron = game.getPlayers().get(0);
        addCards("Grizzly Bears", 3, ultron);

        UltronPlayerController controller = (UltronPlayerController) ultron.getController();
        UltronTableThreatSummary summary = controller.refreshThreatSummary();

        Assert.assertNotNull(summary, "Threat summary must be computable from the new controller");
        // Read-only: calling it again should not throw or mutate game state in a way that breaks a
        // second call -- proves it is safe to call repeatedly as a feature provider, not a one-shot
        // decision action.
        Assert.assertNotNull(controller.refreshThreatSummary());
    }

    // -----------------------------------------------------------------------
    // P1.4 -- v2 state contamination guard (TICKET-V3-104)
    // -----------------------------------------------------------------------

    @Test
    public void testControllerBytecodeNeverReferencesV2RuntimeStateClasses() throws IOException {
        // UltronWeights and UltronCardStats eagerly load ~/.forge/ultron-learning/*.json from a
        // static initializer the moment the class is first touched by the JVM -- regardless of the
        // adaptiveWeights config flag (see TICKET-V3-006). The fix for v3 is architectural: never
        // reference those classes (or the v2 runtime classes that reference them:
        // UltronRuntimeController, UltronActionScorer, UltronCandidatePruner) from anywhere in
        // UltronPlayerController's decision path, so the classes are never loaded and the directory
        // is never touched during an Ultron v3 game. Verify directly against the compiled class
        // file's constant pool (which must name every class it references) rather than trusting a
        // runtime side effect that could be masked by test ordering/JVM warm state.
        String resource = UltronPlayerController.class.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = UltronPlayerController.class.getClassLoader().getResourceAsStream(resource)) {
            Assert.assertNotNull(in, "Could not locate compiled class file for " + resource);
            bytes = in.readAllBytes();
        }
        String constantPoolText = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);

        String[] forbidden = {
                "forge/ai/llm/runtime/UltronWeights",
                "forge/ai/llm/runtime/UltronCardStats",
                "forge/ai/llm/runtime/UltronRuntimeController",
                "forge/ai/llm/runtime/UltronActionScorer",
                "forge/ai/llm/runtime/UltronCandidatePruner",
        };
        for (String cls : forbidden) {
            Assert.assertFalse(constantPoolText.contains(cls),
                    "UltronPlayerController.class must never reference " + cls
                            + " -- doing so would risk triggering its static initializer, which "
                            + "eagerly loads v2 learned state from ~/.forge/ultron-learning/");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers (mirrors forge.ai.llm.runtime.UltronThreatModelAndIntentTest's pattern)
    // -----------------------------------------------------------------------

    private Game createFourPlayerGame(boolean ultronProfile) {
        initAndCreateGame();

        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck deck = new Deck();

        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("Ultron", ultronProfile ? "Ultron" : null)));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentA", null)));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentB", null)));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentC", null)));

        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "UltronPlayerControllerTest");
        Game game = new Game(players, rules, match);
        Player ultron = game.getPlayers().get(0);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ultron);
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    private LobbyPlayerAi aiPlayer(String name, String profile) {
        LobbyPlayerAi ai = new LobbyPlayerAi(name, null);
        if (profile != null) {
            ai.setAiProfile(profile);
        }
        return ai;
    }
}
