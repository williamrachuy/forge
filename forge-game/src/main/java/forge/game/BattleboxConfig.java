package forge.game;

import forge.StaticData;
import forge.card.CardEdition;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.Map.Entry;

public final class BattleboxConfig {
    public static final int DEFAULT_STARTING_LIFE = 20;
    public static final int DEFAULT_STARTING_HAND_SIZE = 7;
    public static final int DEFAULT_MAX_HAND_SIZE = 7;
    public static final int DEFAULT_PLAYER_LIBRARY_SIZE = 40;
    public static final boolean DEFAULT_SEED_BASIC_LANDS = true;

    public static final String STARTING_LIFE = "BattleboxStartingLife";
    public static final String STARTING_HAND_SIZE = "BattleboxStartingHandSize";
    public static final String MAX_HAND_SIZE = "BattleboxMaxHandSize";
    public static final String LEGACY_LIBRARY_SIZE = "BattleboxLibrarySize";
    public static final String PLAYER_LIBRARY_SIZE = "PlayerLibrarySize";
    public static final String SEED_BASIC_LANDS = "SeedBasicLands";
    public static final String BASIC_LANDS_SET = "BasicLandsSet";

    private final int startingLife;
    private final int startingHandSize;
    private final int maxHandSize;
    private final int playerLibrarySize;
    private final boolean seedBasicLands;
    private final Map<String, List<BasicLandOption>> basicLandOptions;

    private BattleboxConfig(final int startingLife, final int startingHandSize, final int maxHandSize,
            final int playerLibrarySize, final boolean seedBasicLands,
            final Map<String, List<BasicLandOption>> basicLandOptions) {
        this.startingLife = startingLife;
        this.startingHandSize = startingHandSize;
        this.maxHandSize = maxHandSize;
        this.playerLibrarySize = playerLibrarySize;
        this.seedBasicLands = seedBasicLands;
        this.basicLandOptions = basicLandOptions;
    }

    public static BattleboxConfig fromDeck(final Deck deck) {
        final Map<String, String> metadata = deck == null ? Map.of() : deck.getMetadata();
        final int startingLife = getInt(metadata, STARTING_LIFE, DEFAULT_STARTING_LIFE);
        final int startingHandSize = getInt(metadata, STARTING_HAND_SIZE, DEFAULT_STARTING_HAND_SIZE);
        final int maxHandSize = getInt(metadata, MAX_HAND_SIZE, DEFAULT_MAX_HAND_SIZE);
        final int playerLibrarySize = getInt(metadata, PLAYER_LIBRARY_SIZE, DEFAULT_PLAYER_LIBRARY_SIZE);
        final boolean seedBasicLands = getBoolean(metadata, SEED_BASIC_LANDS, DEFAULT_SEED_BASIC_LANDS);
        return new BattleboxConfig(startingLife, startingHandSize, maxHandSize, playerLibrarySize, seedBasicLands,
                parseBasicLandOptions(deck).options);
    }

    public int getStartingLife() {
        return startingLife;
    }

    public int getStartingHandSize() {
        return startingHandSize;
    }

    public int getMaxHandSize() {
        return maxHandSize;
    }

    public int getPlayerLibrarySize() {
        return playerLibrarySize;
    }

    public boolean shouldSeedBasicLands() {
        return seedBasicLands;
    }

    public CardPool getSharedLibrary(final Deck deck, final int playerCount) {
        if (deck == null) {
            return null;
        }
        final String librarySizeProblem = getLibrarySizeProblem(deck, playerCount);
        if (librarySizeProblem != null) {
            throw new IllegalArgumentException(librarySizeProblem);
        }
        final CardPool main = deck.getMain();

        final List<PaperCard> cards = main.toFlatList();
        Collections.shuffle(cards, MyRandom.getRandom());

        final int players = Math.max(1, playerCount);
        final int randomCardsPerPlayer = getRandomCardsPerPlayer();
        final int randomCardsNeeded = randomCardsPerPlayer * players;
        final CardPool selected = new CardPool();
        if (seedBasicLands) {
            for (int i = 0; i < players; i++) {
                addBasicLandSet(selected);
            }
        }
        for (int i = 0; i < randomCardsNeeded; i++) {
            selected.add(cards.get(i));
        }
        return selected;
    }

    public CardPool getLandStation(final Deck deck, final int playerCount) {
        final CardPool base = getLandStation(deck);
        if (base == null) {
            return null;
        }

        final CardPool station = new CardPool(base);
        for (int i = 0; i < Math.max(0, playerCount - 2); i++) {
            addBasicLandSet(station);
        }
        return station;
    }

    public static CardPool getLandStation(final Deck deck) {
        if (deck == null) {
            return null;
        }
        if (deck.has(DeckSection.LandStation)) {
            return deck.get(DeckSection.LandStation);
        }
        return deck.has(DeckSection.Sideboard) ? deck.get(DeckSection.Sideboard) : null;
    }

    public static Set<String> getLandNamesInMain(final Deck deck) {
        final Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (deck == null || !deck.has(DeckSection.Main)) {
            return names;
        }
        for (final Entry<PaperCard, Integer> entry : deck.get(DeckSection.Main)) {
            if (entry.getKey().getRules().getType().isLand()) {
                names.add(entry.getKey().getName());
            }
        }
        return names;
    }

    public static Set<String> getNonLandNamesInLandStation(final Deck deck) {
        final Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        final CardPool landStation = getLandStation(deck);
        if (landStation == null) {
            return names;
        }
        for (final Entry<PaperCard, Integer> entry : landStation) {
            if (!entry.getKey().getRules().getType().isLand()) {
                names.add(entry.getKey().getName());
            }
        }
        return names;
    }

    public static List<String> getBasicLandsSetWarnings(final Deck deck) {
        if (deck == null || !getBoolean(deck.getMetadata(), SEED_BASIC_LANDS, DEFAULT_SEED_BASIC_LANDS)) {
            return Collections.emptyList();
        }
        return parseBasicLandOptions(deck).warnings;
    }

    public static String getLibrarySizeProblem(final Deck deck, final int playerCount) {
        if (deck == null) {
            return null;
        }
        if (deck.getMetadata().containsKey(LEGACY_LIBRARY_SIZE)) {
            return LEGACY_LIBRARY_SIZE + " has been replaced by " + PLAYER_LIBRARY_SIZE
                    + ". Remove " + LEGACY_LIBRARY_SIZE + " from [metadata].";
        }

        final String playerLibrarySizeProblem = getPositiveIntProblem(deck.getMetadata(), PLAYER_LIBRARY_SIZE);
        if (playerLibrarySizeProblem != null) {
            return playerLibrarySizeProblem;
        }
        final String seedBasicLandsProblem = getBooleanProblem(deck.getMetadata(), SEED_BASIC_LANDS);
        if (seedBasicLandsProblem != null) {
            return seedBasicLandsProblem;
        }

        final BattleboxConfig config = fromDeck(deck);
        if (config.seedBasicLands && config.playerLibrarySize < MagicColor.Constant.BASIC_LANDS.size()) {
            return PLAYER_LIBRARY_SIZE + " must be at least " + MagicColor.Constant.BASIC_LANDS.size()
                    + " when " + SEED_BASIC_LANDS + " is true.";
        }

        final int players = Math.max(1, playerCount);
        final int randomCardsNeeded = config.getRandomCardsPerPlayer() * players;
        final int mainSize = deck.getMain() == null ? 0 : deck.getMain().countAll();
        if (randomCardsNeeded > mainSize) {
            return PLAYER_LIBRARY_SIZE + " and " + SEED_BASIC_LANDS + " require "
                    + randomCardsNeeded + " random cards from [Main] for " + players
                    + " players, but [Main] contains only " + mainSize + " cards.";
        }
        return null;
    }

    public static Set<String> getDuplicateNamesInMain(final Deck deck) {
        final Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        final Set<String> duplicates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (deck == null || !deck.has(DeckSection.Main)) {
            return duplicates;
        }
        for (final Entry<PaperCard, Integer> entry : deck.get(DeckSection.Main)) {
            if (entry.getValue() > 1 || !seen.add(entry.getKey().getName())) {
                duplicates.add(entry.getKey().getName());
            }
        }
        return duplicates;
    }

    private static int getInt(final Map<String, String> metadata, final String key, final int defaultValue) {
        final String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static boolean getBoolean(final Map<String, String> metadata, final String key, final boolean defaultValue) {
        final String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private int getRandomCardsPerPlayer() {
        return playerLibrarySize - (seedBasicLands ? MagicColor.Constant.BASIC_LANDS.size() : 0);
    }

    private void addBasicLandSet(final CardPool pool) {
        for (final String landName : MagicColor.Constant.BASIC_LANDS) {
            final PaperCard selectedPrint = getSeededBasicLandPrint(landName);
            if (selectedPrint == null) {
                pool.add(landName, 1);
            } else {
                pool.add(selectedPrint);
            }
        }
    }

    private PaperCard getSeededBasicLandPrint(final String landName) {
        final List<BasicLandOption> options = basicLandOptions.get(landName);
        if (options == null || options.isEmpty()) {
            return null;
        }
        final BasicLandOption option = options.get(MyRandom.getRandom().nextInt(options.size()));
        final List<PaperCard> setPrints = getBasicLandPrints(landName, option.setCode);
        if (setPrints.isEmpty()) {
            return null;
        }
        if (option.artIndices != null) {
            final List<PaperCard> requestedPrints = new ArrayList<>();
            for (final PaperCard print : setPrints) {
                if (option.artIndices.contains(print.getArtIndex())) {
                    requestedPrints.add(print);
                }
            }
            if (!requestedPrints.isEmpty()) {
                return requestedPrints.get(MyRandom.getRandom().nextInt(requestedPrints.size()));
            }
        }
        return setPrints.get(MyRandom.getRandom().nextInt(setPrints.size()));
    }

    private static ParsedBasicLandOptions parseBasicLandOptions(final Deck deck) {
        final Map<String, List<BasicLandOption>> options = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final LinkedHashSet<String> warnings = new LinkedHashSet<>();
        if (deck == null) {
            return new ParsedBasicLandOptions(options, Collections.emptyList());
        }
        final List<String> lines = deck.getUnparsedSection(BASIC_LANDS_SET);
        if (lines.isEmpty()) {
            return new ParsedBasicLandOptions(options, Collections.emptyList());
        }
        for (final String originalLine : lines) {
            final String line = originalLine == null ? "" : originalLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final String[] values = line.split("\\|", -1);
            if (values.length < 2 || values.length > 3) {
                warnings.add("Invalid [" + BASIC_LANDS_SET + "] entry (expected BasicLand|SET or BasicLand|SET|art-list): " + line);
                continue;
            }
            final String landName = canonicalBasicLandName(values[0].trim());
            if (landName == null) {
                warnings.add("Unknown basic land in [" + BASIC_LANDS_SET + "]: " + values[0].trim());
                continue;
            }
            final String setCode = values[1].trim();
            if (setCode.isEmpty()) {
                warnings.add("Missing set code in [" + BASIC_LANDS_SET + "] entry: " + line);
                continue;
            }
            final List<PaperCard> prints = getBasicLandPrints(landName, setCode);
            if (prints.isEmpty()) {
                warnings.add("No " + landName + " print found in set " + setCode + " for [" + BASIC_LANDS_SET + "]; default art will be used if selected.");
                continue;
            }
            List<Integer> artIndices = null;
            if (values.length == 3) {
                artIndices = parseArtIndices(values[2].trim());
                if (artIndices == null || artIndices.isEmpty()) {
                    warnings.add("Invalid art variant list in [" + BASIC_LANDS_SET + "] entry: " + line);
                    continue;
                }
                boolean hasRequestedPrint = false;
                boolean hasInvalidPrint = false;
                for (final Integer artIndex : artIndices) {
                    boolean found = false;
                    for (final PaperCard print : prints) {
                        if (print.getArtIndex() == artIndex) {
                            found = true;
                            hasRequestedPrint = true;
                            break;
                        }
                    }
                    hasInvalidPrint |= !found;
                }
                if (hasInvalidPrint) {
                    warnings.add("Unavailable art variant in [" + BASIC_LANDS_SET + "] entry; "
                            + (hasRequestedPrint ? "available requested variants will be used" : "a random variant from " + setCode + " will be used")
                            + ": " + line);
                }
            }
            options.computeIfAbsent(landName, ignored -> new ArrayList<>()).add(new BasicLandOption(setCode, artIndices));
        }
        for (final String landName : MagicColor.Constant.BASIC_LANDS) {
            if (!options.containsKey(landName)) {
                warnings.add("No valid [" + BASIC_LANDS_SET + "] option for " + landName + "; default art will be used.");
            }
        }
        return new ParsedBasicLandOptions(options, new ArrayList<>(warnings));
    }

    private static String canonicalBasicLandName(final String value) {
        for (final String landName : MagicColor.Constant.BASIC_LANDS) {
            if (landName.equalsIgnoreCase(value)) {
                return landName;
            }
        }
        return null;
    }

    private static List<Integer> parseArtIndices(final String value) {
        if (value.isEmpty()) {
            return null;
        }
        final LinkedHashSet<Integer> indices = new LinkedHashSet<>();
        try {
            for (final String part : value.split(",")) {
                final String[] bounds = part.trim().split("-", -1);
                if (bounds.length == 1) {
                    final int artIndex = Integer.parseInt(bounds[0].trim());
                    if (artIndex <= 0) {
                        return null;
                    }
                    indices.add(artIndex);
                } else if (bounds.length == 2) {
                    final int start = Integer.parseInt(bounds[0].trim());
                    final int end = Integer.parseInt(bounds[1].trim());
                    if (start <= 0 || end < start) {
                        return null;
                    }
                    for (int artIndex = start; artIndex <= end; artIndex++) {
                        indices.add(artIndex);
                    }
                } else {
                    return null;
                }
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return new ArrayList<>(indices);
    }

    private static List<PaperCard> getBasicLandPrints(final String landName, final String setCode) {
        final CardEdition edition = StaticData.instance().getCardEdition(setCode);
        if (edition == null) {
            return Collections.emptyList();
        }
        final String code = edition.getCode();
        final String alternateCode = edition.getCode2();
        return StaticData.instance().getCommonCards().getAllCardsNoAlt(landName, print ->
                print.getEdition().equalsIgnoreCase(code) || print.getEdition().equalsIgnoreCase(alternateCode));
    }

    private static final class BasicLandOption {
        private final String setCode;
        private final List<Integer> artIndices;

        private BasicLandOption(final String setCode, final List<Integer> artIndices) {
            this.setCode = setCode;
            this.artIndices = artIndices;
        }
    }

    private static final class ParsedBasicLandOptions {
        private final Map<String, List<BasicLandOption>> options;
        private final List<String> warnings;

        private ParsedBasicLandOptions(final Map<String, List<BasicLandOption>> options, final List<String> warnings) {
            this.options = options;
            this.warnings = warnings;
        }
    }

    private static String getPositiveIntProblem(final Map<String, String> metadata, final String key) {
        final String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (Integer.parseInt(value.trim()) > 0) {
                return null;
            }
        } catch (NumberFormatException ex) {
            // handled below
        }
        return key + " must be a positive integer: " + value;
    }

    private static String getBooleanProblem(final Map<String, String> metadata, final String key) {
        final String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        final String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return key + " must be True or False: " + value;
    }
}
