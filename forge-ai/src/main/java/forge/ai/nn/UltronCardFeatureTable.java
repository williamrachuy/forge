package forge.ai.nn;

import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.card.ColorSet;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.ai.llm.runtime.UltronStackThreatAnalyzer;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TICKET-V4-005 (Ultron v4 Phase 1, P1.1): static, per-card-name feature table built once from the
 * Forge card DB. {@link UltronStateEncoder} pools these vectors (sum + max, plus counts) over each
 * zone rather than embedding individual cards -- see {@code ULTRON_V4_NEURAL_PLAN.md} sect. 4.1.
 *
 * <p><b>No card-ID embedding in this vector.</b> The plan's sect. 4.1 mentions a learned 16-dim
 * card-ID embedding concatenated at train time. That is deliberately NOT part of this class's output:
 * a fixed-length {@code float[]} cannot carry an embedding the encoder does not have, and pooling
 * (sum/max) requires every card to already be a plain vector before it happens. Instead, {@link
 * #getVocabId(String)} exposes a stable integer ID per unique card name so a future ticket can log
 * IDs alongside these features and add a real embedding table without regenerating the training
 * corpus. This is a documented deviation from the plan text, not an oversight -- see FORGE_TRACKER.md
 * TICKET-V4-005.
 *
 * <p><b>Role flags reuse {@link UltronStackThreatAnalyzer}.</b> Rather than writing a second,
 * divergent oracle-text/ApiType classifier, the removal/counterspell/board-wipe/card-draw role flags
 * call the {@code isXxxApi(ApiType)} predicates that analyzer exposes (added additively in this
 * ticket, its own {@code classify()} switch is unchanged). Ramp and token-maker have no existing
 * analyzer concept (they are not stack *threats*), so they are classified fresh here directly against
 * {@link ApiType}.
 */
public final class UltronCardFeatureTable {

    private UltronCardFeatureTable() {}

    // -----------------------------------------------------------------------
    // Vector layout. Keep segment order/sizes in sync with buildFeatures() and
    // with any golden-file test that indexes into specific slots.
    // -----------------------------------------------------------------------

    public static final int MANA_VALUE_OFFSET = 0;
    public static final int MANA_VALUE_SIZE = 1;

    public static final int COLOR_OFFSET = MANA_VALUE_OFFSET + MANA_VALUE_SIZE;
    public static final int COLOR_SIZE = 5; // W, U, B, R, G

    public static final int TYPE_OFFSET = COLOR_OFFSET + COLOR_SIZE;
    public static final int TYPE_SIZE = 8; // creature, land, instant, sorcery, artifact, enchantment, planeswalker, battle

    public static final int PT_OFFSET = TYPE_OFFSET + TYPE_SIZE;
    public static final int PT_SIZE = 2; // power, toughness (0 for non-creatures)

    public static final int KEYWORD_OFFSET = PT_OFFSET + PT_SIZE;
    public static final int KEYWORD_SIZE = 25;

    public static final int ROLE_OFFSET = KEYWORD_OFFSET + KEYWORD_SIZE;
    public static final int ROLE_SIZE = 6; // removal, counterspell, boardWipe, cardDraw, ramp, tokenMaker

    public static final int LEGENDARY_OFFSET = ROLE_OFFSET + ROLE_SIZE;
    public static final int LEGENDARY_SIZE = 1;

    /** Total length of one card's feature vector (~48 floats per plan sect. 4.1). */
    public static final int CARD_FEATURE_DIM = LEGENDARY_OFFSET + LEGENDARY_SIZE;

    // Fixed order for the 25 keyword flags. Chosen for combat/interaction relevance; not exhaustive
    // over Forge's full Keyword enum (that list is 200+ and mostly irrelevant at this granularity).
    private static final Keyword[] TRACKED_KEYWORDS = {
            Keyword.FLYING, Keyword.DEATHTOUCH, Keyword.LIFELINK, Keyword.HASTE, Keyword.TRAMPLE,
            Keyword.WARD, Keyword.FLASH, Keyword.VIGILANCE, Keyword.MENACE, Keyword.REACH,
            Keyword.FIRST_STRIKE, Keyword.DOUBLE_STRIKE, Keyword.DEFENDER, Keyword.HEXPROOF,
            Keyword.INDESTRUCTIBLE, Keyword.INFECT, Keyword.WITHER, Keyword.EXALTED, Keyword.MYRIAD,
            Keyword.AFFINITY, Keyword.CONVOKE, Keyword.FLASHBACK, Keyword.CYCLING, Keyword.PROWESS,
            Keyword.SHROUD,
    };

    static {
        if (TRACKED_KEYWORDS.length != KEYWORD_SIZE) {
            throw new IllegalStateException("TRACKED_KEYWORDS length " + TRACKED_KEYWORDS.length
                    + " must match KEYWORD_SIZE " + KEYWORD_SIZE);
        }
    }

    /** Reserved vocab ID for cards not present in the table at build time (new pool cards, tokens, etc). */
    public static final int UNK_VOCAB_ID = 0;

    private static volatile Map<String, float[]> featuresByName;
    private static volatile Map<String, Integer> vocabIdByName;

    /**
     * Lazily builds (once) and returns the card-name -> feature-vector table from the currently
     * loaded card DB ({@code StaticData.instance().getCommonCards().getUniqueCards()}).
     */
    public static Map<String, float[]> table() {
        Map<String, float[]> local = featuresByName;
        if (local == null) {
            synchronized (UltronCardFeatureTable.class) {
                if (featuresByName == null) {
                    buildTable();
                }
                local = featuresByName;
            }
        }
        return local;
    }

    /**
     * Feature vector for a named card. Returns a zero vector (not null) for unknown names so callers
     * pooling over hidden/unloaded cards degrade gracefully instead of crashing.
     */
    public static float[] getFeatures(String cardName) {
        Map<String, float[]> t = table();
        float[] v = t.get(cardName);
        return v != null ? v : new float[CARD_FEATURE_DIM];
    }

    /**
     * Stable integer vocab ID for a card name (1-based; 0 = {@link #UNK_VOCAB_ID}). IDs are assigned
     * in sorted-name order at table-build time, so they are stable across JVM runs as long as the
     * card DB's set of unique names doesn't change. Not used by {@link #getFeatures}/pooling in v0 --
     * exposed for a future card-ID-embedding ticket to log alongside these vectors.
     */
    public static int getVocabId(String cardName) {
        Map<String, Integer> ids = vocabIdByName;
        if (ids == null) {
            table(); // triggers build
            ids = vocabIdByName;
        }
        Integer id = ids.get(cardName);
        return id != null ? id : UNK_VOCAB_ID;
    }

    public static float[] extractFeatures(Card card) {
        float[] v = new float[CARD_FEATURE_DIM];
        if (card == null) {
            return v;
        }

        v[MANA_VALUE_OFFSET] = card.getCMC() / 10f;

        ColorSet colors = card.getColor();
        if (colors != null) {
            if (colors.hasWhite()) v[COLOR_OFFSET] = 1f;
            if (colors.hasBlue())  v[COLOR_OFFSET + 1] = 1f;
            if (colors.hasBlack()) v[COLOR_OFFSET + 2] = 1f;
            if (colors.hasRed())   v[COLOR_OFFSET + 3] = 1f;
            if (colors.hasGreen()) v[COLOR_OFFSET + 4] = 1f;
        }

        if (card.isCreature())     v[TYPE_OFFSET] = 1f;
        if (card.isLand())         v[TYPE_OFFSET + 1] = 1f;
        if (card.isInstant())      v[TYPE_OFFSET + 2] = 1f;
        if (card.isSorcery())      v[TYPE_OFFSET + 3] = 1f;
        if (card.isArtifact())     v[TYPE_OFFSET + 4] = 1f;
        if (card.isEnchantment())  v[TYPE_OFFSET + 5] = 1f;
        if (card.isPlaneswalker()) v[TYPE_OFFSET + 6] = 1f;
        if (card.isBattle())       v[TYPE_OFFSET + 7] = 1f;

        if (card.isCreature()) {
            v[PT_OFFSET] = card.getNetPower() / 10f;
            v[PT_OFFSET + 1] = card.getNetToughness() / 10f;
        }

        for (int i = 0; i < TRACKED_KEYWORDS.length; i++) {
            if (card.hasKeyword(TRACKED_KEYWORDS[i])) {
                v[KEYWORD_OFFSET + i] = 1f;
            }
        }

        Set<ApiType> apis = collectApiTypes(card);
        boolean removal = false, counterspell = false, boardWipe = false, cardDraw = false, ramp = false, tokenMaker = false;
        for (ApiType api : apis) {
            removal      |= UltronStackThreatAnalyzer.isRemovalApi(api);
            counterspell |= UltronStackThreatAnalyzer.isCounterspellApi(api);
            boardWipe    |= UltronStackThreatAnalyzer.isBoardWipeApi(api);
            cardDraw     |= UltronStackThreatAnalyzer.isDrawApi(api);
            // No existing analyzer concept for these two (they are not stack *threats*) -- classified
            // fresh here against ApiType directly.
            // Exclude lands: virtually every land has a baseline Mana-api ability (that's just what
            // a land does), so counting it as "ramp" would flag the entire land base. Ramp here
            // means an above-curve mana source: mana rocks/dorks/rituals on nonland permanents/spells.
            ramp         |= (api == ApiType.Mana || api == ApiType.ManaReflected) && !card.isLand();
            tokenMaker   |= (api == ApiType.Token);
        }
        v[ROLE_OFFSET]     = removal ? 1f : 0f;
        v[ROLE_OFFSET + 1] = counterspell ? 1f : 0f;
        v[ROLE_OFFSET + 2] = boardWipe ? 1f : 0f;
        v[ROLE_OFFSET + 3] = cardDraw ? 1f : 0f;
        v[ROLE_OFFSET + 4] = ramp ? 1f : 0f;
        v[ROLE_OFFSET + 5] = tokenMaker ? 1f : 0f;

        v[LEGENDARY_OFFSET] = card.getType().isLegendary() ? 1f : 0f;

        return v;
    }

    /** Walks every top-level spell ability of the card plus each one's sub-ability chain, collecting ApiTypes. */
    private static Set<ApiType> collectApiTypes(Card card) {
        Set<ApiType> apis = new HashSet<>();
        Iterable<SpellAbility> sas = card.getSpellAbilities();
        if (sas == null) {
            return apis;
        }
        for (SpellAbility root : sas) {
            SpellAbility sa = root;
            int guard = 0; // defensive: sub-ability chains are a linked list, guard against any cycle
            while (sa != null && guard++ < 64) {
                if (sa.getApi() != null) {
                    apis.add(sa.getApi());
                }
                sa = sa.getSubAbility();
            }
        }
        return apis;
    }

    private static synchronized void buildTable() {
        Map<String, float[]> features = new HashMap<>();
        Map<String, Integer> vocab = new HashMap<>();

        // Card.fromPaperCard(pc, null) does NOT fully parse a card's SpellAbility list (activated/
        // mana abilities and even the primary spell ability of instants/sorceries come back empty
        // with a null owner -- confirmed empirically while building this table). A throwaway
        // Game/Player is built once here purely so CardFactory has an owner+game context to parse
        // scripts against; nothing about this dummy game is ever played.
        Player owner = buildDummyOwner();

        Collection<PaperCard> uniqueCards = StaticData.instance().getCommonCards().getUniqueCards();
        List<String> names = new ArrayList<>();
        for (PaperCard pc : uniqueCards) {
            names.add(pc.getName());
        }
        names.sort(String::compareTo);

        Map<String, PaperCard> byName = new HashMap<>();
        for (PaperCard pc : uniqueCards) {
            byName.putIfAbsent(pc.getName(), pc);
        }

        int nextId = 1; // 0 reserved for UNK
        for (String name : names) {
            vocab.put(name, nextId++);
            PaperCard pc = byName.get(name);
            try {
                Card card = Card.fromPaperCard(pc, owner);
                features.put(name, extractFeatures(card));
            } catch (RuntimeException e) {
                // A handful of scripted cards can throw when instantiated (e.g. static abilities
                // reaching for game state at construction time). Degrade to a zero vector rather
                // than failing DB-wide table construction.
                features.put(name, new float[CARD_FEATURE_DIM]);
            }
        }

        vocabIdByName = vocab;
        featuresByName = features;
    }

    private static Player buildDummyOwner() {
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("nn-table-p0", null)));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("nn-table-p1", null)));
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "UltronCardFeatureTableBuild");
        Game dummyGame = new Game(players, rules, match);
        return dummyGame.getPlayers().get(0);
    }
}
