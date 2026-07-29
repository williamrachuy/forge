package forge.ai.nn;

import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * TICKET-V4-005 (Ultron v4 Phase 1, P1.2): {@code Game} + perspective {@code Player} -> fixed-length
 * {@code float[]}. Pure Java, no model, no training -- see {@code ULTRON_V4_NEURAL_PLAN.md} sect. 4.1.
 *
 * <p><b>Perspective-relative.</b> The evaluated player's own data always occupies the "self" block
 * first; the remaining seats follow in turn order (the order {@link Game#getPlayers()} already
 * returns them in, rotated to start right after self) as three "opponent" blocks. One network can
 * therefore serve every seat.
 *
 * <p><b>1v1 == 4p with two seats eliminated.</b> This encoder ALWAYS emits exactly three opponent
 * blocks. A game with fewer than four real {@link Player} objects (a genuine 1v1) pads the missing
 * seats with a zero opponent block + {@code eliminated=1}, identically to how a real 4-player game
 * encodes a seat whose {@link Player#hasLost()} is true. This equivalence is required, not
 * incidental -- {@code UltronStateEncoderTest} pins it directly (a hand-built 2-player game and a
 * hand-built 4-player game with the same two active seats but the other two marked lost must produce
 * byte-identical vectors). See design decision 2 in the TICKET-V4-005 write-up in FORGE_TRACKER.md.
 *
 * <p><b>No card-ID embeddings here.</b> Per-card content comes entirely from {@link
 * UltronCardFeatureTable#extractFeatures(Card)}; see that class's javadoc for why.
 */
public final class UltronStateEncoder {

    private UltronStateEncoder() {}

    // -----------------------------------------------------------------------
    // Card-level building blocks
    // -----------------------------------------------------------------------

    /** Static per-card feature dim (mana value, colors, types, P/T, keywords, role flags, legendary). */
    public static final int CARD_DIM = UltronCardFeatureTable.CARD_FEATURE_DIM;

    /** Per-card dynamic battlefield state appended before pooling battlefield zones. */
    public static final int DYNAMIC_DIM = 6; // tapped, sick, damageRatio, +1/+1 (scaled), -1/-1 (scaled), hasAttachment
    public static final int BATTLEFIELD_CARD_DIM = CARD_DIM + DYNAMIC_DIM;

    /** sum(dim) + max(dim) + count(1) for a pooled zone of the given per-card vector width. */
    private static int poolSize(int cardDim) {
        return 2 * cardDim + 1;
    }

    private static final int BF_POOL_SIZE = poolSize(BATTLEFIELD_CARD_DIM);   // battlefield creatures / noncreatures
    private static final int CARD_POOL_SIZE = poolSize(CARD_DIM);              // graveyard / exile / commander

    /**
     * TICKET-V4-029 (encoder v3): the SELF HAND is no longer pooled.
     *
     * <p>Pooling a zone to sum+max+count is a defensible compromise for the battlefield, which is
     * unbounded. A hand is bounded at ~7. Compressing it threw away which cards you hold for no
     * space saving worth having: 7 slots x 48 floats = 336 against the pooled 97, i.e. +239 floats
     * on a ~1900-float vector.
     *
     * <p>This is what lets the network answer "I drew a bomb, should I hold mana for it?" at all.
     * With a pooled hand, drawing a 6-drop registered only as "hand mana-value total went up by 6";
     * the specific card was gone. Note this is an OBSERVABILITY fix, not a memory one -- a
     * state-value function re-derives its plan every decision, so it needs the card to be visible in
     * the state, not remembered.
     *
     * <p>Slots are filled in a canonical order (mana value, then colour, then type) so the network
     * is not spending capacity relearning permutation invariance. Overflow beyond {@link #HAND_SLOTS}
     * is dropped, which is why the pooled summary is KEPT alongside: it still carries the totals for
     * an oversized hand.
     */
    public static final int HAND_SLOTS = 7;
    private static final int HAND_SLOTS_SIZE = HAND_SLOTS * CARD_DIM;

    private static final int LAND_COLOR_COUNTS_SIZE = 6; // W, U, B, R, G, colorless/other

    /**
     * TICKET-V4-029 (encoder v3) — the TEMPO block. Magic is played on two resources, tempo and card
     * advantage, and which dominates depends on game stage. Mana efficiency *is* tempo, mechanically.
     * Encoder v2 had no mana feature of any kind: only total land colours and a normalised turn
     * number. Measured consequence (TICKET-V4-027, n=970): Ultron casts 0.14 fewer spells per turn
     * than Default while ending with 0.42 MORE cards in hand -- it holds cards it never deploys, and
     * in 4-player that widens to -0.42 and +1.19. It plays exactly like something that cannot see
     * the currency it is losing, because it could not.
     *
     * <p>Critically this is an AFTERSTATE feature: casting a spell lowers available mana, so with
     * this block the network can finally learn what leaving mana unspent is worth -- negative when
     * developing, positive when holding up interaction.
     */
    private static final int MANA_AVAILABLE_SIZE = 1;      // ComputerUtilMana.getAvailableManaEstimate
    private static final int MANA_UNTAPPED_COLORS_SIZE = 6; // untapped sources by W,U,B,R,G,C
    private static final int MANA_LANDS_PLAYED_SIZE = 1;   // land drop made this turn -- direct tempo
    private static final int MANA_BLOCK_SIZE =
            MANA_AVAILABLE_SIZE + MANA_UNTAPPED_COLORS_SIZE + MANA_LANDS_PLAYED_SIZE;
    private static final int LIFE_POISON_ENERGY_SIZE = 3;

    // -----------------------------------------------------------------------
    // Self block layout
    // -----------------------------------------------------------------------

    public static final int SELF_BF_CREATURES_OFFSET = 0;
    public static final int SELF_BF_NONCREATURES_OFFSET = SELF_BF_CREATURES_OFFSET + BF_POOL_SIZE;
    public static final int SELF_HAND_OFFSET = SELF_BF_NONCREATURES_OFFSET + BF_POOL_SIZE;
    public static final int SELF_HAND_SLOTS_OFFSET = SELF_HAND_OFFSET + CARD_POOL_SIZE;
    public static final int SELF_GRAVEYARD_OFFSET = SELF_HAND_SLOTS_OFFSET + HAND_SLOTS_SIZE;
    public static final int SELF_EXILE_OFFSET = SELF_GRAVEYARD_OFFSET + CARD_POOL_SIZE;
    public static final int SELF_COMMAND_OFFSET = SELF_EXILE_OFFSET + CARD_POOL_SIZE;
    public static final int SELF_LAND_COLORS_OFFSET = SELF_COMMAND_OFFSET + CARD_POOL_SIZE;
    public static final int SELF_MANA_OFFSET = SELF_LAND_COLORS_OFFSET + LAND_COLOR_COUNTS_SIZE;
    public static final int SELF_SCALARS_OFFSET = SELF_MANA_OFFSET + MANA_BLOCK_SIZE;
    public static final int SELF_BLOCK_SIZE = SELF_SCALARS_OFFSET + LIFE_POISON_ENERGY_SIZE;

    // -----------------------------------------------------------------------
    // Opponent block layout (hand is count-only; no exile per plan sect. 4.1)
    // -----------------------------------------------------------------------

    public static final int OPP_BF_CREATURES_OFFSET = 0;
    public static final int OPP_BF_NONCREATURES_OFFSET = OPP_BF_CREATURES_OFFSET + BF_POOL_SIZE;
    public static final int OPP_HAND_COUNT_OFFSET = OPP_BF_NONCREATURES_OFFSET + BF_POOL_SIZE;
    public static final int OPP_HAND_COUNT_SIZE = 1;
    public static final int OPP_GRAVEYARD_OFFSET = OPP_HAND_COUNT_OFFSET + OPP_HAND_COUNT_SIZE;
    public static final int OPP_COMMAND_OFFSET = OPP_GRAVEYARD_OFFSET + CARD_POOL_SIZE;
    public static final int OPP_LAND_COLORS_OFFSET = OPP_COMMAND_OFFSET + CARD_POOL_SIZE;
    public static final int OPP_MANA_OFFSET = OPP_LAND_COLORS_OFFSET + LAND_COLOR_COUNTS_SIZE;
    public static final int OPP_SCALARS_OFFSET = OPP_MANA_OFFSET + MANA_BLOCK_SIZE;
    public static final int OPP_ELIMINATED_OFFSET = OPP_SCALARS_OFFSET + LIFE_POISON_ENERGY_SIZE;
    public static final int OPP_BLOCK_SIZE = OPP_ELIMINATED_OFFSET + 1;

    public static final int NUM_OPPONENTS = 3;

    // -----------------------------------------------------------------------
    // Global block layout
    // -----------------------------------------------------------------------

    public static final int GLOBAL_TURN_OFFSET = 0;
    public static final int GLOBAL_TURN_SIZE = 1;
    public static final int GLOBAL_PHASE_OFFSET = GLOBAL_TURN_OFFSET + GLOBAL_TURN_SIZE;
    public static final int GLOBAL_PHASE_SIZE = PhaseType.values().length;
    public static final int GLOBAL_ACTIVE_SLOT_OFFSET = GLOBAL_PHASE_OFFSET + GLOBAL_PHASE_SIZE;
    public static final int GLOBAL_ACTIVE_SLOT_SIZE = 1 + NUM_OPPONENTS; // self, opp1..3
    public static final int GLOBAL_MONARCH_SLOT_OFFSET = GLOBAL_ACTIVE_SLOT_OFFSET + GLOBAL_ACTIVE_SLOT_SIZE;
    public static final int GLOBAL_MONARCH_SLOT_SIZE = 1 + NUM_OPPONENTS + 1; // self, opp1..3, none
    public static final int GLOBAL_PLAYERS_REMAINING_OFFSET = GLOBAL_MONARCH_SLOT_OFFSET + GLOBAL_MONARCH_SLOT_SIZE;
    public static final int GLOBAL_PLAYERS_REMAINING_SIZE = 1;
    public static final int GLOBAL_BLOCK_SIZE = GLOBAL_PLAYERS_REMAINING_OFFSET + GLOBAL_PLAYERS_REMAINING_SIZE;

    // -----------------------------------------------------------------------
    // Whole-vector layout: self, opp1, opp2, opp3, global
    // -----------------------------------------------------------------------

    public static final int SELF_OFFSET = 0;
    public static final int OPP_BASE_OFFSET = SELF_OFFSET + SELF_BLOCK_SIZE;
    public static final int GLOBAL_OFFSET = OPP_BASE_OFFSET + NUM_OPPONENTS * OPP_BLOCK_SIZE;

    /** Total fixed length of every vector this encoder produces. */
    public static final int VECTOR_LENGTH = GLOBAL_OFFSET + GLOBAL_BLOCK_SIZE;

    /**
     * TICKET-V4-006: bumped whenever a feature's *meaning* changes without moving any offset or
     * size -- e.g. the land mana-color-production fix in this ticket (basic-subtype matching ->
     * actual mana-ability walk) changes what {@code SELF_LAND_COLORS}/{@code OPP_LAND_COLORS}
     * *contain* for the exact same 6 floats at the exact same offset. A pure layout hash (offsets
     * and sizes only) would not move for that kind of change, which is precisely the failure mode
     * that lets a model trained on old semantics silently load against new encodings and produce
     * garbage. Any change to HOW a feature is computed -- not just to the vector layout -- must
     * bump this constant.
     */
    public static final int ENCODER_SEMANTIC_VERSION = 3; // 3 = +tempo/mana block (TICKET-V4-029)

    /**
     * Stable hash of the feature layout AND semantics, per plan sect. 4.3: model files and
     * training logs carry this so a model trained on one layout/semantics refuses to load against
     * another. Computed once from a canonical description of every named segment's offset/size
     * plus {@link #ENCODER_SEMANTIC_VERSION} (FNV-1a 64-bit over UTF-8 bytes -- deterministic
     * across JVMs/processes, unlike relying on incidental class/field ordering).
     */
    public static final long SCHEMA_HASH = computeSchemaHash();
    public static final String SCHEMA_HASH_HEX = Long.toHexString(SCHEMA_HASH);

    private static long computeSchemaHash() {
        StringBuilder sb = new StringBuilder();
        sb.append("ENCODER_SEMANTIC_VERSION=").append(ENCODER_SEMANTIC_VERSION).append(';');
        sb.append("CARD_DIM=").append(CARD_DIM).append(';');
        sb.append("BATTLEFIELD_CARD_DIM=").append(BATTLEFIELD_CARD_DIM).append(';');
        sb.append("SELF_BF_CREATURES@").append(SELF_BF_CREATURES_OFFSET).append('/').append(BF_POOL_SIZE).append(';');
        sb.append("SELF_BF_NONCREATURES@").append(SELF_BF_NONCREATURES_OFFSET).append('/').append(BF_POOL_SIZE).append(';');
        sb.append("SELF_HAND@").append(SELF_HAND_OFFSET).append('/').append(CARD_POOL_SIZE).append(';');
        sb.append("SELF_HAND_SLOTS@").append(SELF_HAND_SLOTS_OFFSET).append('/').append(HAND_SLOTS_SIZE).append(';');
        sb.append("SELF_GRAVEYARD@").append(SELF_GRAVEYARD_OFFSET).append('/').append(CARD_POOL_SIZE).append(';');
        sb.append("SELF_EXILE@").append(SELF_EXILE_OFFSET).append('/').append(CARD_POOL_SIZE).append(';');
        sb.append("SELF_COMMAND@").append(SELF_COMMAND_OFFSET).append('/').append(CARD_POOL_SIZE).append(';');
        sb.append("SELF_LAND_COLORS@").append(SELF_LAND_COLORS_OFFSET).append('/').append(LAND_COLOR_COUNTS_SIZE).append(';');
        sb.append("SELF_MANA@").append(SELF_MANA_OFFSET).append('/').append(MANA_BLOCK_SIZE).append(';');
        sb.append("SELF_SCALARS@").append(SELF_SCALARS_OFFSET).append('/').append(LIFE_POISON_ENERGY_SIZE).append(';');
        sb.append("SELF_BLOCK_SIZE=").append(SELF_BLOCK_SIZE).append(';');
        sb.append("OPP_BF_CREATURES@").append(OPP_BF_CREATURES_OFFSET).append('/').append(BF_POOL_SIZE).append(';');
        sb.append("OPP_BF_NONCREATURES@").append(OPP_BF_NONCREATURES_OFFSET).append('/').append(BF_POOL_SIZE).append(';');
        sb.append("OPP_HAND_COUNT@").append(OPP_HAND_COUNT_OFFSET).append('/').append(OPP_HAND_COUNT_SIZE).append(';');
        sb.append("OPP_GRAVEYARD@").append(OPP_GRAVEYARD_OFFSET).append('/').append(CARD_POOL_SIZE).append(';');
        sb.append("OPP_COMMAND@").append(OPP_COMMAND_OFFSET).append('/').append(CARD_POOL_SIZE).append(';');
        sb.append("OPP_LAND_COLORS@").append(OPP_LAND_COLORS_OFFSET).append('/').append(LAND_COLOR_COUNTS_SIZE).append(';');
        sb.append("OPP_MANA@").append(OPP_MANA_OFFSET).append('/').append(MANA_BLOCK_SIZE).append(';');
        sb.append("OPP_SCALARS@").append(OPP_SCALARS_OFFSET).append('/').append(LIFE_POISON_ENERGY_SIZE).append(';');
        sb.append("OPP_ELIMINATED@").append(OPP_ELIMINATED_OFFSET).append('/').append(1).append(';');
        sb.append("OPP_BLOCK_SIZE=").append(OPP_BLOCK_SIZE).append(';');
        sb.append("NUM_OPPONENTS=").append(NUM_OPPONENTS).append(';');
        sb.append("GLOBAL_TURN@").append(GLOBAL_TURN_OFFSET).append('/').append(GLOBAL_TURN_SIZE).append(';');
        sb.append("GLOBAL_PHASE@").append(GLOBAL_PHASE_OFFSET).append('/').append(GLOBAL_PHASE_SIZE).append(';');
        sb.append("GLOBAL_ACTIVE_SLOT@").append(GLOBAL_ACTIVE_SLOT_OFFSET).append('/').append(GLOBAL_ACTIVE_SLOT_SIZE).append(';');
        sb.append("GLOBAL_MONARCH_SLOT@").append(GLOBAL_MONARCH_SLOT_OFFSET).append('/').append(GLOBAL_MONARCH_SLOT_SIZE).append(';');
        sb.append("GLOBAL_PLAYERS_REMAINING@").append(GLOBAL_PLAYERS_REMAINING_OFFSET).append('/').append(GLOBAL_PLAYERS_REMAINING_SIZE).append(';');
        sb.append("GLOBAL_BLOCK_SIZE=").append(GLOBAL_BLOCK_SIZE).append(';');
        sb.append("VECTOR_LENGTH=").append(VECTOR_LENGTH).append(';');
        // Keyword/role ordering matters for the hash too -- a reordering of TRACKED_KEYWORDS or the
        // role-flag block is a schema change even though sizes don't move.
        sb.append("CARD_SCHEMA_HASH=").append(UltronCardFeatureTable.CARD_FEATURE_DIM).append(';');

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L; // FNV-1a 64-bit offset basis
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L; // FNV-1a 64-bit prime
        }
        return hash;
    }

    // -----------------------------------------------------------------------
    // Encoding entry point
    // -----------------------------------------------------------------------

    /** Encodes {@code game} from {@code self}'s perspective into a fresh fixed-length {@code float[]}. */
    public static float[] encode(Game game, Player self) {
        return encode(game, self, false);
    }

    /**
     * As {@link #encode(Game, Player)}, but when {@code maskOwnSummonSick} is true, {@code self}'s
     * own summon-sick creatures are excluded entirely from the self battlefield-creature pooling
     * (TICKET-V4-010, plan sect. 4.4). This is the encoder-side half of {@code
     * NeuralStateEvaluator}'s second forward pass, which populates {@code Score.summonSickValue}
     * the same way {@code GameStateEvaluator} does for the heuristic path -- without this, a
     * neural-evaluated Ultron would happily main-phase-cast summon-sick creatures for no board
     * benefit instead of holding them for a post-combat decision.
     *
     * <p>Same feature layout as {@link #encode(Game, Player)} -- this is a caller choice about
     * which board state to encode (a subset of the same battlefield, same slots, same semantics),
     * not a change to what a feature slot means, so it does not bump {@link
     * #ENCODER_SEMANTIC_VERSION} or change {@link #SCHEMA_HASH}. Opponent blocks and every other
     * self-block field (hand, graveyard, exile, mana base, scalars) are unaffected; only self's
     * battlefield-creature pooling changes.
     */
    public static float[] encode(Game game, Player self, boolean maskOwnSummonSick) {
        float[] out = new float[VECTOR_LENGTH];
        if (game == null || self == null) {
            return out;
        }

        List<Player> seats = orderedRealOpponents(game, self);

        writeSelfBlock(out, SELF_OFFSET, self, maskOwnSummonSick);

        for (int i = 0; i < NUM_OPPONENTS; i++) {
            int offset = OPP_BASE_OFFSET + i * OPP_BLOCK_SIZE;
            Player opp = i < seats.size() ? seats.get(i) : null;
            boolean eliminated = (opp == null) || opp.hasLost();
            if (eliminated) {
                out[offset + OPP_ELIMINATED_OFFSET] = 1f;
                // rest of the block stays zero -- "zero block + eliminated flag" per plan sect. 4.1
            } else {
                writeOpponentBlock(out, offset, opp);
            }
        }

        writeGlobalBlock(out, GLOBAL_OFFSET, game, self, seats);

        return out;
    }

    /**
     * Real (non-self) players in turn order starting right after self, NOT padded to length 3.
     * Package-visibility-plus (public): this is the canonical seat-order definition used to
     * assign opponent slots 1..{@link #NUM_OPPONENTS} in the encoded vector, and {@code
     * NeuralStateEvaluator} (TICKET-V4-010) needs the exact same ordering to know which value-head
     * softmax slot corresponds to which live/eliminated seat when masking win probability to
     * living seats -- reimplementing this logic separately would risk it silently drifting out of
     * sync with the encoder.
     */
    public static List<Player> orderedRealOpponents(Game game, Player self) {
        List<Player> all = game.getPlayers();
        List<Player> ordered = new ArrayList<>();
        int selfIdx = all.indexOf(self);
        int n = all.size();
        if (selfIdx < 0 || n == 0) {
            return ordered;
        }
        for (int step = 1; step < n; step++) {
            ordered.add(all.get((selfIdx + step) % n));
        }
        return ordered;
    }

    // -----------------------------------------------------------------------
    // Self block
    // -----------------------------------------------------------------------

    private static void writeSelfBlock(float[] out, int base, Player self, boolean maskOwnSummonSick) {
        CardCollectionView battlefield = self.getCardsIn(ZoneType.Battlefield);
        List<Card> creatures = new ArrayList<>();
        List<Card> noncreatures = new ArrayList<>();
        for (Card c : battlefield) {
            if (c.isCreature()) {
                if (maskOwnSummonSick && c.isSick()) {
                    continue; // TICKET-V4-010: excluded from self battlefield-creature pooling
                }
                creatures.add(c);
            } else {
                noncreatures.add(c);
            }
        }
        poolBattlefield(out, base + SELF_BF_CREATURES_OFFSET, creatures);
        poolBattlefield(out, base + SELF_BF_NONCREATURES_OFFSET, noncreatures);
        poolCards(out, base + SELF_HAND_OFFSET, self.getCardsIn(ZoneType.Hand));
        writeHandSlots(out, base + SELF_HAND_SLOTS_OFFSET, self.getCardsIn(ZoneType.Hand));
        poolCards(out, base + SELF_GRAVEYARD_OFFSET, self.getCardsIn(ZoneType.Graveyard));
        poolCards(out, base + SELF_EXILE_OFFSET, self.getCardsIn(ZoneType.Exile));
        poolCards(out, base + SELF_COMMAND_OFFSET, self.getCardsIn(ZoneType.Command));
        writeLandColorCounts(out, base + SELF_LAND_COLORS_OFFSET, battlefield);
        writeManaBlock(out, base + SELF_MANA_OFFSET, self, battlefield);
        writeScalars(out, base + SELF_SCALARS_OFFSET, self);
    }

    // -----------------------------------------------------------------------
    // Opponent block
    // -----------------------------------------------------------------------

    private static void writeOpponentBlock(float[] out, int base, Player opp) {
        CardCollectionView battlefield = opp.getCardsIn(ZoneType.Battlefield);
        List<Card> creatures = new ArrayList<>();
        List<Card> noncreatures = new ArrayList<>();
        for (Card c : battlefield) {
            if (c.isCreature()) {
                creatures.add(c);
            } else {
                noncreatures.add(c);
            }
        }
        poolBattlefield(out, base + OPP_BF_CREATURES_OFFSET, creatures);
        poolBattlefield(out, base + OPP_BF_NONCREATURES_OFFSET, noncreatures);
        out[base + OPP_HAND_COUNT_OFFSET] = opp.getCardsIn(ZoneType.Hand).size() / 20f;
        poolCards(out, base + OPP_GRAVEYARD_OFFSET, opp.getCardsIn(ZoneType.Graveyard));
        poolCards(out, base + OPP_COMMAND_OFFSET, opp.getCardsIn(ZoneType.Command));
        writeLandColorCounts(out, base + OPP_LAND_COLORS_OFFSET, battlefield);
        writeManaBlock(out, base + OPP_MANA_OFFSET, opp, battlefield);
        writeScalars(out, base + OPP_SCALARS_OFFSET, opp);
        out[base + OPP_ELIMINATED_OFFSET] = 0f;
    }

    // -----------------------------------------------------------------------
    // Shared per-player helpers
    // -----------------------------------------------------------------------

    private static void writeScalars(float[] out, int base, Player p) {
        out[base] = p.getLife() / 20f;
        out[base + 1] = p.getCounters(CounterEnumType.POISON) / 10f;
        out[base + 2] = p.getCounters(CounterEnumType.ENERGY) / 10f;
    }

    /**
     * Land-color-production counts, one increment per distinct color a land can actually produce
     * (deduped per land, so a karoo/temple that taps for "W U" increments both the W and U slots
     * by exactly 1, same as it would count a Plains + an Island). Derived from the card's real
     * mana abilities -- {@link Card#getManaAbilities()} + {@link AbilityManaPart#mana(SpellAbility)}
     * -- not from basic land subtype name matching. This mirrors the established pattern in
     * {@code GameStateEvaluator.evaluateLand()} (forge-ai/.../simulation/GameStateEvaluator.java),
     * which already solves this exact problem for the heuristic evaluator.
     *
     * <p>TICKET-V4-006: replaces the prior v1 approximation, which matched only the five basic
     * land names (Plains/Island/Swamp/Mountain/Forest) and silently counted every nonbasic land --
     * every Ravnica karoo, every Theros temple, every shockland, every fetchable dual -- as
     * "other/colorless." Measured against the Battlebox pool: 60 of 80 lands have no basic land
     * subtype at all and were previously reporting zero color production. See
     * {@link #ENCODER_SEMANTIC_VERSION}.
     */
    /**
     * TICKET-V4-029 (encoder v3): the TEMPO block — see {@link #MANA_BLOCK_SIZE}.
     *
     * <p>Layout: [0] available-mana estimate, [1..6] UNTAPPED mana sources by W,U,B,R,G,other,
     * [7] lands played this turn. The untapped counts deliberately mirror
     * {@link #writeLandColorCounts}'s per-source dedup (a dual that taps for W or U increments both)
     * but count only sources that can actually be used right now — which is the difference between
     * "what colours could I ever produce" (already encoded) and "what can I spend this instant".
     *
     * <p>Applied to opponents too: untapped permanents are public information, and "can that player
     * respond?" is exactly what a human reads off an opponent's untapped mana.
     */
    /**
     * TICKET-V4-029: write up to {@link #HAND_SLOTS} individual hand cards, canonically ordered so
     * the same hand always encodes identically regardless of draw order. See {@link #HAND_SLOTS}.
     */
    private static void writeHandSlots(float[] out, int base, Iterable<Card> hand) {
        List<Card> cards = new ArrayList<>();
        for (Card c : hand) {
            cards.add(c);
        }
        cards.sort((a, b) -> {
            int cmp = Integer.compare(a.getCMC(), b.getCMC());
            if (cmp != 0) {
                return cmp;
            }
            cmp = a.getName().compareTo(b.getName());
            return cmp;
        });
        int n = Math.min(cards.size(), HAND_SLOTS);
        for (int i = 0; i < n; i++) {
            float[] f = UltronCardFeatureTable.getFeatures(cards.get(i).getName());
            System.arraycopy(f, 0, out, base + i * CARD_DIM, Math.min(f.length, CARD_DIM));
        }
    }

    private static void writeManaBlock(float[] out, int base, Player p, Iterable<Card> battlefield) {
        out[base] = ComputerUtilMana.getAvailableManaEstimate(p) / 20f;
        for (Card c : battlefield) {
            if (c.isTapped() || c.getManaAbilities().isEmpty()) {
                continue;
            }
            boolean[] produced = new boolean[LAND_COLOR_COUNTS_SIZE];
            for (SpellAbility m : c.getManaAbilities()) {
                m.setActivatingPlayer(c.getController());
                for (AbilityManaPart mp : m.getAllManaParts()) {
                    String colorsStr = mp.mana(m);
                    if (colorsStr == null || colorsStr.isEmpty()) {
                        continue;
                    }
                    for (String token : colorsStr.split(" ")) {
                        markProducedColor(produced, token);
                    }
                }
            }
            for (int i = 0; i < LAND_COLOR_COUNTS_SIZE; i++) {
                if (produced[i]) {
                    out[base + MANA_AVAILABLE_SIZE + i] += 1f;
                }
            }
        }
        for (int i = 0; i < LAND_COLOR_COUNTS_SIZE; i++) {
            out[base + MANA_AVAILABLE_SIZE + i] /= 10f;
        }
        out[base + MANA_AVAILABLE_SIZE + MANA_UNTAPPED_COLORS_SIZE] = p.getLandsPlayedThisTurn() / 2f;
    }

    private static void writeLandColorCounts(float[] out, int base, Iterable<Card> battlefield) {
        for (Card c : battlefield) {
            if (!c.isLand()) {
                continue;
            }
            boolean[] produced = new boolean[LAND_COLOR_COUNTS_SIZE]; // W,U,B,R,G,other -- per-land dedup set
            for (SpellAbility m : c.getManaAbilities()) {
                m.setActivatingPlayer(c.getController());
                for (AbilityManaPart mp : m.getAllManaParts()) {
                    String colorsStr = mp.mana(m);
                    if (colorsStr == null || colorsStr.isEmpty()) {
                        continue;
                    }
                    for (String token : colorsStr.split(" ")) {
                        markProducedColor(produced, token);
                    }
                }
            }
            for (int i = 0; i < LAND_COLOR_COUNTS_SIZE; i++) {
                if (produced[i]) {
                    out[base + i] += 1f;
                }
            }
        }
        for (int i = 0; i < LAND_COLOR_COUNTS_SIZE; i++) {
            out[base + i] = out[base + i] / 10f; // scale down raw counts
        }
    }

    /** Marks the W/U/B/R/G slots (or "other" for colorless/generic/unknown tokens) a mana token covers. */
    private static void markProducedColor(boolean[] produced, String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        switch (token) {
            case "W": produced[0] = true; break;
            case "U": produced[1] = true; break;
            case "B": produced[2] = true; break;
            case "R": produced[3] = true; break;
            case "G": produced[4] = true; break;
            case "Any":
                // Can filter/produce any color -- counts toward all five colors, same convention as
                // GameStateEvaluator.evaluateLand()'s colors_produced.contains("Any") special case.
                produced[0] = true;
                produced[1] = true;
                produced[2] = true;
                produced[3] = true;
                produced[4] = true;
                break;
            default:
                // "C" (colorless), generic-mana tokens ("1", etc.), or anything else not one of
                // W/U/B/R/G/Any -- bucket into "other/colorless".
                produced[5] = true;
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Global block
    // -----------------------------------------------------------------------

    private static void writeGlobalBlock(float[] out, int base, Game game, Player self, List<Player> seats) {
        out[base + GLOBAL_TURN_OFFSET] = game.getPhaseHandler().getTurn() / 50f;

        PhaseType phase = game.getPhaseHandler().getPhase();
        if (phase != null) {
            out[base + GLOBAL_PHASE_OFFSET + phase.ordinal()] = 1f;
        }

        Player active = game.getPhaseHandler().getPlayerTurn();
        int activeSlot = relativeSlot(self, seats, active);
        if (activeSlot >= 0) {
            out[base + GLOBAL_ACTIVE_SLOT_OFFSET + activeSlot] = 1f;
        }

        Player monarch = game.getMonarch();
        int monarchSlot = monarch == null ? GLOBAL_MONARCH_SLOT_SIZE - 1 : relativeSlot(self, seats, monarch);
        if (monarchSlot < 0) {
            monarchSlot = GLOBAL_MONARCH_SLOT_SIZE - 1; // monarch held by someone outside self/opponents view -- treat as none
        }
        out[base + GLOBAL_MONARCH_SLOT_OFFSET + monarchSlot] = 1f;

        int remaining = 0;
        if (!self.hasLost()) {
            remaining++;
        }
        for (Player opp : seats) {
            if (!opp.hasLost()) {
                remaining++;
            }
        }
        out[base + GLOBAL_PLAYERS_REMAINING_OFFSET] = remaining / 4f;
    }

    /** 0 = self, 1..NUM_OPPONENTS = seats.get(i-1); -1 if player is null or not found. */
    private static int relativeSlot(Player self, List<Player> seats, Player player) {
        if (player == null) {
            return -1;
        }
        if (player.equals(self)) {
            return 0;
        }
        int idx = seats.indexOf(player);
        if (idx < 0 || idx >= NUM_OPPONENTS) {
            return -1;
        }
        return idx + 1;
    }

    // -----------------------------------------------------------------------
    // Pooling
    // -----------------------------------------------------------------------

    private static void poolCards(float[] out, int base, Iterable<Card> cards) {
        int dim = CARD_DIM;
        float[] max = new float[dim];
        int count = 0;
        for (Card c : cards) {
            float[] f = UltronCardFeatureTable.getFeatures(c.getName());
            for (int i = 0; i < dim; i++) {
                out[base + i] += f[i];
                if (count == 0 || f[i] > max[i]) {
                    max[i] = f[i];
                }
            }
            count++;
        }
        System.arraycopy(max, 0, out, base + dim, dim);
        out[base + 2 * dim] = count / 20f;
    }

    private static void poolBattlefield(float[] out, int base, Iterable<Card> cards) {
        int dim = BATTLEFIELD_CARD_DIM;
        float[] max = new float[dim];
        int count = 0;
        for (Card c : cards) {
            float[] f = battlefieldCardVector(c);
            for (int i = 0; i < dim; i++) {
                out[base + i] += f[i];
                if (count == 0 || f[i] > max[i]) {
                    max[i] = f[i];
                }
            }
            count++;
        }
        System.arraycopy(max, 0, out, base + dim, dim);
        out[base + 2 * dim] = count / 20f;
    }

    private static float[] battlefieldCardVector(Card c) {
        float[] base = UltronCardFeatureTable.getFeatures(c.getName());
        float[] v = new float[BATTLEFIELD_CARD_DIM];
        System.arraycopy(base, 0, v, 0, CARD_DIM);
        v[CARD_DIM] = c.isTapped() ? 1f : 0f;
        v[CARD_DIM + 1] = c.isSick() ? 1f : 0f;
        int toughness = Math.max(1, c.getNetToughness());
        float damageRatio = Math.min(2f, c.getDamage() / (float) toughness);
        v[CARD_DIM + 2] = damageRatio / 2f;
        v[CARD_DIM + 3] = Math.min(5, c.getCounters(CounterEnumType.P1P1)) / 5f;
        v[CARD_DIM + 4] = Math.min(5, c.getCounters(CounterEnumType.M1M1)) / 5f;
        v[CARD_DIM + 5] = c.getAttachedCards().isEmpty() ? 0f : 1f;
        return v;
    }
}
