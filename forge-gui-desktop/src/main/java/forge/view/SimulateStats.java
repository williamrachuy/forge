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

import forge.ai.llm.UltronConfig;
import forge.ai.llm.runtime.UltronAdaptiveLearner;
import forge.ai.llm.runtime.UltronRuntimeController;
import forge.ai.llm.runtime.UltronSimStats;
import forge.ai.llm.runtime.UltronWeights;
import forge.ai.ultron.UltronDecisionTelemetry;
import forge.ai.ultron.UltronPlayerController;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
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
        final List<String> bannedCards = config.getSimBannedCards();
        if (!bannedCards.isEmpty()) {
            System.out.println("Sim-banned cards (excluded from deck pool this run): " + bannedCards);
        }

        final boolean adaptiveWeights = config.isAdaptiveWeightsEnabled();
        final java.nio.file.Path weightsPath = config.getWeightsPath();
        if (adaptiveWeights) {
            UltronWeights.load(weightsPath);
            UltronAdaptiveLearner.loadCardStats(weightsPath);
            System.out.println("Adaptive weights ENABLED — override file: " + weightsPath);
            UltronAdaptiveLearner.logCurrentWeights();
        }

        final long baseSeed = config.getSeed();
        final long seedOffset = config.getSeedOffset();
        final boolean rotateSeats = config.isRotateSeatsEnabled();
        System.out.println("Base seed: " + baseSeed + (seedOffset != 0 ? " (seedOffset=" + seedOffset + ")" : ""));
        if (rotateSeats) {
            System.out.println("Seat rotation ENABLED — aiProfiles rotate by (gameIndex mod playerCount) each game");
        }

        final Random originalRandom = MyRandom.getRandom();
        try (BufferedWriter writer = config.isStatsEnabled()
                ? Files.newBufferedWriter(gamesJsonl, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                : null) {
            for (int i = 0; i < config.getGames(); i++) {
                final long globalIndex = seedOffset + i;
                final long gameSeed = seedForGame(baseSeed, globalIndex);
                MyRandom.setRandom(new Random(gameSeed));
                final GameRules rules = new GameRules(format);
                rules.setAppliedVariants(EnumSet.of(format));
                rules.setSimTimeout(config.getTimeoutSeconds());

                if (format == GameType.Battlebox && config.getBattleboxMonarch() != null) {
                    rules.setBattleboxMonarchEnabled(config.getBattleboxMonarch());
                }
                final List<String> gameAiProfiles = rotateSeats
                        ? rotateProfiles(aiProfiles, globalIndex)
                        : aiProfiles;
                final Match match = new Match(rules, registeredPlayers(decks, gameAiProfiles, format, playerCount),
                        config.getRunName());
                final Game game = match.createGame();
                // Give AI decisions a generous budget in headless sim — the 5s default causes
                // timeout storms on complex boards, wasting seconds per decision and piling up
                // background threads. 60s lets the eval finish; the game-level timeout (if set)
                // still bounds total game length.
                game.AI_TIMEOUT = config.getAiDecisionTimeoutSeconds();

                final SimStatsGameContext context = new SimStatsGameContext(config.getRunName(), (int) globalIndex,
                        baseSeed, gameSeed, config.getHash(), format, playerCount, deckNames, gameAiProfiles,
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
                    final java.util.Map<String, Object> record =
                            collector.finish(completedNormally, timeout, error, elapsed);
                    final UltronSimStats ultronStats = findUltronSimStats(game);
                    if (ultronStats != null) {
                        record.put("ultron", ultronStats.toMap());
                        // Snapshot the active weight multipliers so analysis can track evolution
                        final java.util.Map<String, Double> wts = UltronWeights.all();
                        if (!wts.isEmpty()) {
                            record.put("ultronWeights", wts);
                        }
                    }
                    // P1.2 (TICKET-V3-102): UltronPlayerController's own decision-coverage telemetry.
                    // Independent of the legacy UltronSimStats above -- that pipeline only populates
                    // when something still routes through UltronRuntimeController, which Phase 1's
                    // UltronPlayerController deliberately never does. This is the coverage signal
                    // that actually reflects the new controller: total decisions and the split
                    // between Ultron-authored and inherited-default answers.
                    final UltronDecisionTelemetry ultronCoverage = findUltronCoverage(game);
                    if (ultronCoverage != null) {
                        record.put("ultronCoverage", ultronCoverage.toMap());
                    }
                    writer.write(SimStatsJson.toJson(record));
                    writer.newLine();
                    writer.flush();

                    if (adaptiveWeights && ultronStats != null && completedNormally) {
                        final Player ultronPlayer = findUltronPlayer(game);
                        final boolean ultronWon = ultronPlayer != null && ultronPlayer.hasWon();
                        UltronAdaptiveLearner.update(ultronStats, ultronWon, weightsPath);
                    }
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
        final List<String> bannedCards = config.getSimBannedCards();
        final List<Deck> decks = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            final String requested = requestedDecks.get(requestedDecks.size() == 1 ? 0 : i);
            final Deck deck = SimulateMatch.deckFromCommandLineParameter(requested, format);
            if (deck == null) {
                throw new IllegalArgumentException("Could not load deck: " + requested);
            }
            for (final String banned : bannedCards) {
                // removeCardName removes one copy; loop to clear all copies
                while (deck.removeCardName(banned) != null) { /* remove all */ }
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

    private static UltronSimStats findUltronSimStats(final Game game) {
        for (final Player player : game.getRegisteredPlayers()) {
            if (UltronConfig.isUltronPlayer(player)) {
                return UltronRuntimeController.getSimStats(game, player);
            }
        }
        return null;
    }

    private static UltronDecisionTelemetry findUltronCoverage(final Game game) {
        for (final Player player : game.getRegisteredPlayers()) {
            if (UltronConfig.isUltronPlayer(player)
                    && player.getController() instanceof UltronPlayerController upc) {
                return upc.getTelemetry();
            }
        }
        return null;
    }

    private static Player findUltronPlayer(final Game game) {
        for (final Player player : game.getRegisteredPlayers()) {
            if (UltronConfig.isUltronPlayer(player)) {
                return player;
            }
        }
        return null;
    }

    private static long seedForGame(final long baseSeed, final long gameIndex) {
        long value = baseSeed + 0x9e3779b97f4a7c15L * (gameIndex + 1L);
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    /**
     * Rotates the seat-to-profile assignment for game {@code globalIndex}: seat s gets the
     * profile originally at index (s + globalIndex) mod count. Over a run of N >= playerCount
     * games this cycles every profile through every seat, eliminating seat-position confound
     * (see FORGE_TRACKER TICKET-107: seat 1 vs seat 3 win rates differed by 27pp at fixed seats).
     */
    private static List<String> rotateProfiles(final List<String> profiles, final long globalIndex) {
        final int count = profiles.size();
        if (count == 0) {
            return profiles;
        }
        final int shift = (int) Math.floorMod(globalIndex, (long) count);
        final List<String> rotated = new ArrayList<>(count);
        for (int seat = 0; seat < count; seat++) {
            rotated.add(profiles.get((seat + shift) % count));
        }
        return rotated;
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
