package forge.ai.llm.runtime;

import com.google.common.collect.Lists;
import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class UltronRuntimeControllerSelectionTest extends AITest {

    @Test
    public void testPrunerSkipsOneDropFillerOnSmallAheadList() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);

        addCards("Grizzly Bears", 7, ultron);
        addCards("Forest", 4, ultron);
        addCard("Runeclaw Bear", opponent);

        SpellAbility filler = candidateFromHand("Savannah Lions", ultron);
        UltronDecisionContext ctx = contextFor(game, ultron, List.of(filler));

        List<SpellAbility> pruned = UltronCandidatePruner.prune(List.of(filler), ctx);

        Assert.assertTrue(ctx.intent.avoidTappingOut, "Ahead-state setup should preserve mana");
        Assert.assertEquals(ctx.intent.role, UltronRuntimeRole.CONTROL);
        Assert.assertTrue(pruned.isEmpty(), "Small candidate lists should still honor filler pruning");
    }

    @Test
    public void testRuntimeControllerPassesWhenOnlyPrunedFillerRemains() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);

        addCards("Grizzly Bears", 7, ultron);
        addCards("Forest", 4, ultron);
        addCard("Runeclaw Bear", opponent);

        SpellAbility filler = candidateFromHand("Savannah Lions", ultron);
        UltronRuntimeController runtime = UltronRuntimeController.getOrCreate(
                game, ultron, new AiCardMemory());

        UltronRuntimeDecision decision = runtime.choose(
                List.of(filler), forge.ai.llm.UltronStrategicPlan.GameState.MAIN_PHASE);

        Assert.assertTrue(decision.isPass(), "Runtime should preserve mana instead of deploying filler");
        Assert.assertEquals(decision.getReason(), "all candidates pruned by runtime policy");
    }

    private UltronDecisionContext contextFor(Game game, Player ultron, List<SpellAbility> candidates) {
        game.getAction().checkStateEffects(true);
        UltronTableThreatSummary table = UltronThreatModel.analyze(game, ultron);
        UltronTurnIntent intent = UltronTurnIntentBuilder.build(table, game.getPhaseHandler().getTurn());
        return new UltronDecisionContext(
                game,
                ultron,
                new AiCardMemory(),
                candidates,
                table,
                intent,
                System.nanoTime() + 1_000_000_000L
        );
    }

    private SpellAbility candidateFromHand(String cardName, Player player) {
        SpellAbility sa = addCardToZone(cardName, player, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(player);
        return sa;
    }

    private Game createFourPlayerGame() {
        initAndCreateGame();

        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck deck = new Deck();

        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("Ultron", "Ultron")));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentA", null)));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentB", null)));
        players.add(new RegisteredPlayer(deck).setPlayer(aiPlayer("OpponentC", null)));

        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "UltronRuntimeSelectionTest");
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
