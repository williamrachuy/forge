package forge.ai.ultron;

import com.google.common.collect.Lists;
import forge.ai.AIOption;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TICKET-V3-203 (Ultron v3 Phase 2, P2.4): main-phase decisions via
 * {@code SpellAbilityPicker}/{@code Plan}.
 *
 * <p>Builds a real 4-player Battlebox mid-game-shaped state (Battlebox variant + real
 * {@code SharedPlayerZone}s, following the {@code GameCopierBattleboxFidelityTest}/
 * {@code UltronPlayerControllerTest} conventions) and exercises
 * {@link UltronPlayerController#chooseSpellAbilityToPlay()} across a few distinct board states to
 * check: it never crashes, it never silently falls back to inherited behavior for these
 * straightforward states (proven via {@link UltronDecisionTelemetry#getUltronAnsweredCount()}),
 * and any non-null pick is the legal candidate available (not a bugged/impossible choice).
 */
public class UltronMainPhaseSimulationTest extends AITest {

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
        Match match = new Match(rules, players, "UltronMainPhaseSimulationTest");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;

        // Wire real shared zones the same way Match.prepareBattleboxSharedLibrary/Command/
        // Graveyard do for a real game start -- see GameCopierBattleboxFidelityTest.
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

        // Distinct per-player life totals + monarch on a non-Ultron player, mimicking a real
        // mid-game 4p Battlebox Monarch table rather than a fresh/symmetric start.
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
    public void testMainPhasePlaysAvailableLandWithoutCrashing() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);

        addCardToZone("Forest", ultron, ZoneType.Hand);
        setMainPhase(game, ultron);

        UltronPlayerController controller = ultronControllerFor(game);
        long before = controller.getTelemetry().getUltronAnsweredCount();

        List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();

        Assert.assertEquals(controller.getTelemetry().getUltronAnsweredCount(), before + 1,
                "Simulation-based main-phase pick must not throw/fall back for a trivial land-drop state");
        if (chosen != null) {
            Assert.assertFalse(chosen.isEmpty(), "A non-null pick must contain at least one SpellAbility");
            SpellAbility sa = chosen.get(0);
            Assert.assertEquals(sa.getHostCard().getName(), "Forest",
                    "The only legal candidate in this state is the land drop -- any non-null pick must be it");
        }
    }

    @Test
    public void testMainPhasePicksLegalPlayWithManaAndCreatureInHand() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);

        // Two untapped Forests on the battlefield + a 1G creature in hand -- an affordable,
        // clearly-beneficial play the simulation-based picker should be able to find without
        // crashing on 4-player-specific state (opponents have their own creatures/life below).
        addCard("Forest", ultron);
        addCard("Forest", ultron);
        addCardToZone("Grizzly Bears", ultron, ZoneType.Hand);

        // Give opponents some board presence so the state isn't a trivial 1-player-only board --
        // this is what actually exercises GameStateEvaluator's per-opponent multiplayer scoring
        // (TICKET-V3-202) and GameCopier's shared-zone copying (TICKET-V3-201) during simulation.
        Card oppCreatureB = addCard("Runeclaw Bear", game.getPlayers().get(1));
        oppCreatureB.setSickness(false);
        Card oppCreatureC = addCard("Grizzly Bears", game.getPlayers().get(2));
        oppCreatureC.setSickness(false);

        setMainPhase(game, ultron);

        UltronPlayerController controller = ultronControllerFor(game);
        long before = controller.getTelemetry().getUltronAnsweredCount();

        List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();

        Assert.assertEquals(controller.getTelemetry().getUltronAnsweredCount(), before + 1,
                "Simulation-based main-phase pick must not throw/fall back with real 4-player board presence");
        if (chosen != null) {
            Assert.assertFalse(chosen.isEmpty());
            SpellAbility sa = chosen.get(0);
            String hostName = sa.getHostCard().getName();
            Assert.assertTrue(hostName.equals("Grizzly Bears") || sa.isLandAbility(),
                    "Ultron only has a land-drop and Grizzly Bears available -- got " + hostName);
        }
    }

    @Test
    public void testMainPhaseReturnsNullRatherThanCrashingWithNoLegalPlay() {
        initAndCreateGame();
        Game game = createBattleboxGame();
        Player ultron = game.getPlayers().get(0);

        // Empty hand, no mana, nothing to do -- the picker's "no beneficial play" null return is a
        // legitimate pass-priority signal (matches the pre-existing 2-player USE_SIMULATION
        // semantics in AiController#chooseSpellAbilityToPlay), not an error condition.
        setMainPhase(game, ultron);

        UltronPlayerController controller = ultronControllerFor(game);
        long before = controller.getTelemetry().getUltronAnsweredCount();

        List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();

        Assert.assertEquals(controller.getTelemetry().getUltronAnsweredCount(), before + 1,
                "An empty hand is not an exceptional state -- must resolve via the simulation path, not fallback");
        Assert.assertNull(chosen, "No legal candidate exists in this state; null (pass) is the only sensible answer");
    }
}
