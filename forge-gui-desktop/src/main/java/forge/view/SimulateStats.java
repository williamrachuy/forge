package forge.view;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.game.stats.GameStatsCollector;
import forge.game.stats.SimStatsGameContext;
import forge.game.stats.SimStatsJson;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

public final class SimulateStats {
    private SimulateStats() {
    }

    public static void simulate(final String[] args) {
        final Path configPath = configPath(args);
        if (configPath == null) {
            argumentHelp();
            return;
        }

        try {
            FModel.initialize(null, null);
            run(SimStatsConfig.load(configPath));
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    private static void run(final SimStatsConfig config) throws Exception {
        final GameType format = config.getFormat();
        final int playerCount = config.getPlayers();
        final List<Deck> decks = loadDecks(config, format, playerCount);
        final List<String> deckNames = decks.stream().map(Deck::getName).toList();
        final List<String> aiProfiles = expandProfiles(config.getAiProfiles(), playerCount);
        final Path outputDir = config.getOutputDir();
        final Path gamesJsonl = outputDir.resolve("games.jsonl");

        Files.createDirectories(outputDir);
        System.out.println("SimStats mode");
        System.out.println("Config: " + config.getSource());
        System.out.println("Run: " + config.getRunName() + " games=" + config.getGames() + " players=" + playerCount
                + " format=" + format);

        final Random originalRandom = MyRandom.getRandom();
        try (BufferedWriter writer = config.isStatsEnabled()
                ? Files.newBufferedWriter(gamesJsonl, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                : null) {
            for (int i = 0; i < config.getGames(); i++) {
                final long gameSeed = seedForGame(config.getSeed(), i);
                MyRandom.setRandom(new Random(gameSeed));
                final GameRules rules = new GameRules(format);
                rules.setAppliedVariants(EnumSet.of(format));
                rules.setSimTimeout(config.getTimeoutSeconds());

                final Match match = new Match(rules, registeredPlayers(decks, aiProfiles, format, playerCount),
                        config.getRunName());
                final Game game = match.createGame();
                if (format == GameType.Battlebox && config.getBattleboxMonarch() != null) {
                    game.setBattleboxMonarchChoice(config.getBattleboxMonarch());
                }

                final SimStatsGameContext context = new SimStatsGameContext(config.getRunName(), i, config.getSeed(),
                        gameSeed, config.getHash(), format, playerCount, deckNames, aiProfiles,
                        config.getBattleboxMonarch());
                final GameStatsCollector collector = config.isStatsEnabled()
                        ? new GameStatsCollector(game, context, config.isTurnSnapshotsEnabled())
                        : null;
                if (collector != null) {
                    game.subscribeToEvents(collector);
                }

                final long started = System.currentTimeMillis();
                boolean timeout = false;
                String error = null;
                try {
                    if (rules.getSimTimeout() <= 0) {
                        match.startGame(game);
                    } else {
                        TimeLimitedCodeBlock.runWithTimeout(() -> match.startGame(game), rules.getSimTimeout(),
                                TimeUnit.SECONDS);
                    }
                } catch (final TimeoutException e) {
                    timeout = true;
                } catch (final Exception | StackOverflowError e) {
                    error = e.getClass().getName() + ": " + e.getMessage();
                    e.printStackTrace();
                } finally {
                    if (!game.isGameOver()) {
                        game.setGameOver(GameEndReason.Draw);
                    }
                }
                final long elapsed = System.currentTimeMillis() - started;
                final boolean completedNormally = !timeout && error == null;

                if (collector != null) {
                    writer.write(SimStatsJson.toJson(collector.finish(completedNormally, timeout, error, elapsed)));
                    writer.newLine();
                    writer.flush();
                }

                System.out.printf("Game %d/%d finished in %d ms%s%s%n", i + 1, config.getGames(), elapsed,
                        timeout ? " timeout" : "", error == null ? "" : " error");
            }
        } finally {
            MyRandom.setRandom(originalRandom);
        }

        if (config.isStatsEnabled()) {
            System.out.println("Raw stats: " + gamesJsonl);
        } else {
            System.out.println("Stats disabled; no raw stats written.");
        }
    }

    private static List<Deck> loadDecks(final SimStatsConfig config, final GameType format, final int playerCount) {
        final List<String> requestedDecks = config.getDeckNames();
        if (requestedDecks.size() != 1 && requestedDecks.size() != playerCount) {
            throw new IllegalArgumentException("Configured deck count must be 1 or equal game.players");
        }
        final List<Deck> decks = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            final String requested = requestedDecks.get(requestedDecks.size() == 1 ? 0 : i);
            final Deck deck = SimulateMatch.deckFromCommandLineParameter(requested, format);
            if (deck == null) {
                throw new IllegalArgumentException("Could not load deck: " + requested);
            }
            decks.add(deck);
        }
        return decks;
    }

    private static List<RegisteredPlayer> registeredPlayers(final List<Deck> decks, final List<String> aiProfiles,
            final GameType format, final int playerCount) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            final Deck deck = decks.get(i);
            final RegisteredPlayer registeredPlayer = SimulateMatch.registeredPlayerForDeck(deck, format);
            final String name = "Ai(" + (i + 1) + ")-" + deck.getName();
            registeredPlayer.setPlayer(GamePlayerUtil.createAiPlayer(name, i, 0, null, aiProfiles.get(i)));
            players.add(registeredPlayer);
        }
        return players;
    }

    private static List<String> expandProfiles(final List<String> profiles, final int playerCount) {
        if (profiles.isEmpty()) {
            return List.of("Default");
        }
        final List<String> result = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            result.add(profiles.get(profiles.size() == 1 ? 0 : i % profiles.size()));
        }
        return result;
    }

    private static long seedForGame(final long baseSeed, final int gameIndex) {
        long value = baseSeed + 0x9e3779b97f4a7c15L * (gameIndex + 1L);
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static Path configPath(final String[] args) {
        for (int i = 1; i < args.length; i++) {
            if ("-config".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                return Path.of(args[i + 1]);
            }
        }
        return null;
    }

    private static void argumentHelp() {
        System.out.println("Syntax: forge.exe simstats -config <path-to-simstats.ini>");
        System.out.println("\tsimstats - headless batch simulation with raw JSONL stats output");
        System.out.println("\t-config - INI-style run configuration");
    }
}
