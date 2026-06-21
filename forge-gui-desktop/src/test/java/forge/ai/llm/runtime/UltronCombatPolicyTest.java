package forge.ai.llm.runtime;

import com.google.common.collect.Lists;
import forge.ai.AITest;
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
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class UltronCombatPolicyTest extends AITest {

    @Test
    public void testLethalVulnerableTargetOutscoresLeaderEvenWithoutLookForLethal() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player leader = game.getPlayers().get(1);
        Player vulnerable = game.getPlayers().get(2);

        addCards("Grizzly Bears", 5, ultron);
        Card attacker = addCard("Hill Giant", ultron);
        addCards("Forest", 4, ultron);

        addCards("Grizzly Bears", 6, leader);
        addCards("Forest", 3, leader);

        // 8 life: triggers preferredAttackTarget (<=10) but NOT the <=5 PRESSURING escalation,
        // so lookForLethal stays false — the scoring bonus alone should prefer the vulnerable kill.
        vulnerable.setLife(8, null);
        addCard("Runeclaw Bear", vulnerable);

        UltronTableThreatSummary table = tableFor(game, ultron);
        UltronTurnIntent intent = UltronTurnIntentBuilder.build(table, game.getPhaseHandler().getTurn());

        Assert.assertFalse(intent.lookForLethal, "Ahead/control setup should not require all-in lethal mode");
        Assert.assertEquals(intent.preferredAttackTarget, vulnerable);

        int vulnerableScore = UltronCombatPolicy.scoreAttack(
                attacker, vulnerable, intent, table, table.ultronLife, 0);
        int leaderScore = UltronCombatPolicy.scoreAttack(
                attacker, leader, intent, table, table.ultronLife, 0);

        Assert.assertTrue(vulnerableScore > leaderScore,
                "A clean kill on the vulnerable player should beat generic pressure on the leader");
    }

    @Test
    public void testFilterAttackersRemovesAttackWhenCrackbackIsLethal() {
        Game game = createFourPlayerGame();
        Player ultron = game.getPlayers().get(0);
        Player opponentA = game.getPlayers().get(1);
        Player opponentB = game.getPlayers().get(2);

        // 3 life: crackback from opponentB's flyer alone (2) plus opponentA's blocker (1) equals
        // Ultron's life total, triggering the lethal-crackback removal path in per-target scoring.
        ultron.setLife(3, null);
        Card attacker = addCard("Hill Giant", ultron);
        attacker.setSickness(false);

        Card flyerA = addCard("Wind Drake", opponentA);
        Card flyerB = addCard("Wind Drake", opponentB);
        flyerA.setSickness(false);
        flyerB.setSickness(false);

        Combat combat = new Combat(ultron);
        combat.addAttacker(attacker, opponentA);

        UltronTableThreatSummary table = tableFor(game, ultron);
        UltronTurnIntent intent = UltronTurnIntentBuilder.build(table, game.getPhaseHandler().getTurn());

        UltronCombatPolicy.filterAttackers(combat, table, intent);

        Assert.assertFalse(combat.getAttackers().contains(attacker),
                "Lethal crackback risk should cause Ultron to keep the blocker home");
    }

    private UltronTableThreatSummary tableFor(Game game, Player ultron) {
        game.getAction().checkStateEffects(true);
        return UltronThreatModel.analyze(game, ultron);
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
        Match match = new Match(rules, players, "UltronCombatPolicyTest");
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
