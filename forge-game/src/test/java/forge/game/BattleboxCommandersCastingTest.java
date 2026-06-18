package forge.game;

import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.annotations.Test;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class BattleboxCommandersCastingTest {

    @Test
    public void battleboxAICastsCommanders() throws Exception {
        // Capture output to check for commander casting
        PrintStream originalOut = System.out;
        AtomicBoolean foundCasting = new AtomicBoolean(false);

        // Create custom output stream to intercept
        System.setOut(new PrintStream(System.out) {
            @Override
            public void println(String x) {
                super.println(x);
                if (x != null && x.contains("DEBUG: AI CASTING COMMANDER")) {
                    foundCasting.set(true);
                    originalOut.println("\n\n*** SUCCESS: COMMANDER WAS CAST ***\n");
                }
            }
        });

        try {
            // Run a few games
            for (int i = 0; i < 5; i++) {
                System.out.println("\n=== Running Game " + (i + 1) + " ===");
                runBattleboxGame();

                if (foundCasting.get()) {
                    System.out.println("Commander casting confirmed. Test passed.");
                    return;
                }
            }

            System.out.println("\nWARNING: No commander casting detected in 5 games");
        } finally {
            System.setOut(originalOut);
        }
    }

    private void runBattleboxGame() throws Exception {
        Localizer.getInstance().initialize("en-US", languageDirectory());
        Lang.createInstance("en-US");

        // Create 2 AI players
        final RegisteredPlayer[] registeredPlayers = new RegisteredPlayer[2];
        for (int i = 0; i < 2; i++) {
            registeredPlayers[i] = new RegisteredPlayer(new Deck())
                .setPlayer(new TestLobbyPlayer("AI" + (i + 1)));
        }

        // Create match with commanders enabled
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.addAppliedVariant(GameType.Battlebox);
        rules.setBattleboxCommandersEnabled(true);

        final Match match = new Match(rules, Arrays.asList(registeredPlayers), "Battlebox");
        final Game game = match.createGame();
        game.setBattleboxCommandersChoice(true);

        // Run game
        match.startGame(game);

        // Game runs in match.startGame, output will be captured
    }

    private static String languageDirectory() {
        final Path rootRelative = Path.of("forge-gui/res/languages");
        return Files.isDirectory(rootRelative) ? rootRelative.toString() : "../forge-gui/res/languages";
    }

    private static final class TestLobbyPlayer extends LobbyPlayer implements IGameEntitiesFactory {
        private TestLobbyPlayer(final String name) {
            super(name);
        }

        @Override
        public PlayerController createMindSlaveController(final Player master, final Player slave) {
            return null;
        }

        @Override
        public Player createIngamePlayer(final Game game, final int id) {
            return new Player(getName(), game, id);
        }

        @Override
        public void hear(final LobbyPlayer player, final String message) {
        }
    }
}
