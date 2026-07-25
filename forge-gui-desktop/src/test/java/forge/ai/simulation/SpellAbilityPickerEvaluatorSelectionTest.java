package forge.ai.simulation;

import com.google.common.collect.Lists;
import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TICKET-V4-010 (Ultron v4 Phase 2, P2.4): pins the gating half of the "default behavior is
 * byte-identical with the neural path disabled" invariant. {@code ULTRON_NN_EVAL} is unset in this
 * (and every) test process, so {@code UltronConfig.nnEvalEnabled()} is false -- {@link
 * SpellAbilityPicker#selectEvaluator} must therefore resolve to a plain {@link GameStateEvaluator}
 * for both an Ultron-profiled player and a Default-profiled player. This is the same code path
 * every other {@code forge.ai.simulation.*}/{@code forge.ai.ultron.*} test already exercises
 * indirectly (see FORGE_TRACKER TICKET-V4-010's regression numbers) -- this test just makes the
 * assertion explicit and directly observable via the package-private {@code
 * getEvaluatorForTesting()} seam, rather than only inferring it from "nothing crashed."
 */
public class SpellAbilityPickerEvaluatorSelectionTest extends SimulationTest {

    private Game createGame(boolean firstPlayerIsUltron) {
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d = new Deck();
        Set<AIOption> options = new HashSet<>();
        LobbyPlayerAi lp0 = new LobbyPlayerAi("p0", options);
        if (firstPlayerIsUltron) {
            lp0.setAiProfile("Ultron");
        }
        players.add(new RegisteredPlayer(d).setPlayer(lp0));
        players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("p1", options)));
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "SpellAbilityPickerEvaluatorSelectionTest");
        Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        return game;
    }

    @Test
    public void resolvesHeuristicEvaluatorForUltronPlayerWhenFlagIsUnset() {
        initAndCreateGame();
        Game game = createGame(true);
        Player ultron = game.getPlayers().get(0);
        Assert.assertTrue(forge.ai.llm.UltronConfig.isUltronPlayer(ultron), "Fixture bug: p0 must be Ultron-profiled");
        Assert.assertFalse(forge.ai.llm.UltronConfig.nnEvalEnabled(),
                "Test environment must not have ULTRON_NN_EVAL set -- this test proves the default-off gating");

        SpellAbilityPicker picker = new SpellAbilityPicker(game, ultron);
        Assert.assertTrue(picker.getEvaluatorForTesting() instanceof GameStateEvaluator,
                "With ULTRON_NN_EVAL unset (the default), even an Ultron-profiled player must get the heuristic evaluator");
        Assert.assertFalse(picker.getEvaluatorForTesting() instanceof forge.ai.nn.NeuralStateEvaluator);
    }

    @Test
    public void resolvesHeuristicEvaluatorForDefaultPlayerRegardless() {
        initAndCreateGame();
        Game game = createGame(false);
        Player defaultAi = game.getPlayers().get(0);
        Assert.assertFalse(forge.ai.llm.UltronConfig.isUltronPlayer(defaultAi));

        SpellAbilityPicker picker = new SpellAbilityPicker(game, defaultAi);
        Assert.assertTrue(picker.getEvaluatorForTesting() instanceof GameStateEvaluator,
                "A Default (non-Ultron) profile must always get the heuristic evaluator, flag or no flag");
    }
}
