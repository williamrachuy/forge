package forge.ai.ultron;

import com.google.common.collect.Lists;
import forge.ai.AIOption;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.GameStateEvaluator;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.SharedPlayerZone;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.tinylog.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TICKET-V3-207 (Ultron v3, session 4): call-count instrumentation harness.
 *
 * <p>This is not a correctness test (it makes no behavioral assertions about which spell/attack
 * gets chosen) -- it exists purely to get REAL numbers for the open question this ticket's
 * session 2/3 findings left unanswered: "for ONE real decision, how many times does
 * {@code GameCopier.makeCopy()} actually get invoked, and how does that compare to the plan's
 * intended candidate-pruning design (3-6 main-phase/attack candidates, 3-5 block candidates)?"
 *
 * <p>Builds a 4-player Battlebox mid-game-shaped state (same fixture conventions as
 * {@code UltronMainPhaseSimulationTest}) with Ultron holding several distinct affordable candidate
 * plays (multiple lands + multiple creatures) AND creatures already on the battlefield (so
 * {@code GameStateEvaluator.simulateUpcomingCombatThisTurn}'s combat-sim-in-eval path is actually
 * exercised, not skipped via its "no creatures in play" early-out), then calls
 * {@code UltronPlayerController.chooseSpellAbilityToPlay()} exactly ONCE and reports the resulting
 * {@code GameCopier.makeCopy()} / {@code GameStateEvaluator.getScoreForGameState()} call counts.
 *
 * <p>Deliberately printed via {@code System.err} (not just tinylog) so the numbers are visible in
 * plain `mvn test` console output without needing to dig through a log file -- this test's entire
 * purpose is to be read by a human/agent diagnosing TICKET-V3-207, not to assert a pass/fail bound
 * (an assertion here would just be re-encoding a guess at the "right" number, which is exactly what
 * the ticket says not to do -- get the real number first).
 */
public class UltronGameCopierCallCountTest extends AITest {

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
        Match match = new Match(rules, players, "UltronGameCopierCallCountTest");
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
        game.setBattleboxMonarchChoice(true);
        game.setMonarch(game.getPlayers().get(2));

        return game;
    }

    private UltronPlayerController ultronControllerFor(Game game) {
        Player ultron = game.getPlayers().get(0);
        Assert.assertTrue(ultron.getController() instanceof UltronPlayerController,
                "Fixture bug: seat 0 must be wired to UltronPlayerController for this test to mean anything");
        return (UltronPlayerController) ultron.getController();
    }

    private void setMainPhase(Game game, Player active) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, active);
        game.getPhaseHandler().onStackResolved();
        game.getAction().checkStateEffects(true);
    }

    @Test
    public void measureCopyCallsForOneMainPhaseDecisionWithBoardPresence() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);

        // Ultron's own board: enough mana to have several distinct affordable candidates, plus
        // an existing non-summoning-sick creature so combat-sim-in-eval is actually exercised
        // (GameStateEvaluator.simulateUpcomingCombatThisTurn skips entirely if the active player
        // has no creatures in play).
        addCard("Forest", ultron);
        addCard("Forest", ultron);
        addCard("Forest", ultron);
        addCard("Mountain", ultron);
        Card existingAttacker = addCard("Grizzly Bears", ultron);
        existingAttacker.setSickness(false);

        // Several distinct affordable candidate spells/lands in hand -- a realistic mid-game hand
        // shape, not an artificially narrow 1-candidate fixture.
        addCardToZone("Forest", ultron, ZoneType.Hand);
        addCardToZone("Runeclaw Bear", ultron, ZoneType.Hand);
        addCardToZone("Grizzly Bears", ultron, ZoneType.Hand);
        addCardToZone("Raging Goblin", ultron, ZoneType.Hand);

        // Opponent board presence so multiplayer scoring/combat is non-trivial.
        Card oppCreatureB = addCard("Runeclaw Bear", game.getPlayers().get(1));
        oppCreatureB.setSickness(false);
        Card oppCreatureC = addCard("Grizzly Bears", game.getPlayers().get(2));
        oppCreatureC.setSickness(false);

        setMainPhase(game, ultron);

        UltronPlayerController controller = ultronControllerFor(game);

        GameCopier.resetMakeCopyCallCount();
        GameStateEvaluator.resetGetScoreForGameStateCallCount();

        long wallStart = System.currentTimeMillis();
        List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();
        long wallMs = System.currentTimeMillis() - wallStart;

        long copyCalls = GameCopier.getMakeCopyCallCount();
        long scoreCalls = GameStateEvaluator.getGetScoreForGameStateCallCount();

        String report = "[TICKET-V3-207] ONE chooseSpellAbilityToPlay() decision (4 hand candidates, "
                + "1 existing non-sick attacker, 2 opponents with creatures) => "
                + "GameCopier.makeCopy() calls=" + copyCalls
                + ", GameStateEvaluator.getScoreForGameState() calls=" + scoreCalls
                + ", wall-clock=" + wallMs + "ms"
                + ", picked=" + (chosen == null ? "null (pass)" : chosen.get(0).getHostCard().getName());
        System.err.println(report);
        Logger.info(report);

        // Sanity only: the decision must complete and return something processable (null is a
        // legitimate "pass" answer). No bound asserted on copyCalls/scoreCalls here by design --
        // see class javadoc.
        Assert.assertTrue(copyCalls > 0, "Expected at least one GameCopier.makeCopy() call for a "
                + "decision with board presence and available plays");
    }
}
