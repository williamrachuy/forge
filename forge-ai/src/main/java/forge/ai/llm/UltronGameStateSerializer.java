package forge.ai.llm;

import forge.ai.AiCardMemory;
import forge.ai.ComputerUtilMana;
import forge.card.CardRules;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UltronGameStateSerializer {
    private static final int DEFAULT_ORACLE_MAX_CHARS = 1200;
    private static final int DEFAULT_SCRIPT_MAX_CHARS = 2400;
    private static final int DEFAULT_SCRIPT_MAX_ENTRIES = 12;
    private static final int DEFAULT_CARD_DETAILS_MAX_CARDS = 0;
    private static final int DEFAULT_ZONE_MAX_VISIBLE_CARDS = 32;
    private static final int DEFAULT_BATTLEFIELD_MAX_VISIBLE_CARDS = 80;
    private static final int DEFAULT_PUBLIC_ZONE_MAX_VISIBLE_CARDS = 10;
    private static final int DEFAULT_PUBLIC_BATTLEFIELD_MAX_VISIBLE_CARDS = 24;
    private static final ZoneType[] SUMMARY_ZONES = new ZoneType[] {
            ZoneType.Battlefield,
            ZoneType.Hand,
            ZoneType.Graveyard,
            ZoneType.Exile,
            ZoneType.Command,
            ZoneType.Library
    };

    private UltronGameStateSerializer() {
    }

    static String serialize(Game game, Player advisor, List<SpellAbility> candidates, AiCardMemory memory) {
        StringBuilder sb = new StringBuilder(16384);
        PhaseHandler phase = game.getPhaseHandler();

        sb.append('{');
        field(sb, "schema", "forge-ultron-visible-state-v1");
        comma(sb);
        field(sb, "visibility", "Only includes cards visible to the advising player plus cards remembered as revealed in hand.");
        comma(sb);
        name(sb, "turn");
        sb.append(phase.getTurn());
        comma(sb);
        field(sb, "phase", phase.getPhase().toString());
        comma(sb);
        field(sb, "activePlayer", phase.getPlayerTurn().getName());
        comma(sb);
        field(sb, "advisingPlayer", advisor.getName());
        comma(sb);
        name(sb, "players");
        sb.append('[');
        boolean first = true;
        for (Player player : gameParticipants(game)) {
            if (!first) {
                sb.append(',');
            }
            appendPlayer(sb, player, advisor, memory);
            first = false;
        }
        sb.append(']');
        comma(sb);
        name(sb, "stack");
        appendStack(sb, game, advisor, memory);
        comma(sb);
        name(sb, "candidates");
        appendCandidates(sb, advisor, candidates, memory);
        comma(sb);
        name(sb, "cardDetails");
        appendDecisionCardDetails(sb, game, advisor, candidates, memory);
        sb.append('}');
        return sb.toString();
    }

    static String serializePublic(Game game, Player ultron, Player speaker) {
        StringBuilder sb = new StringBuilder(12000);
        PhaseHandler phase = game.getPhaseHandler();

        sb.append('{');
        field(sb, "schema", "forge-ultron-public-table-talk-state-v1");
        comma(sb);
        field(sb, "visibility", "Public table state only. Hidden hands, libraries, and face-down information are omitted.");
        comma(sb);
        name(sb, "turn");
        sb.append(phase.getTurn());
        comma(sb);
        field(sb, "phase", phase.getPhase().toString());
        comma(sb);
        field(sb, "activePlayer", phase.getPlayerTurn().getName());
        comma(sb);
        field(sb, "ultronPlayer", ultron == null ? "" : ultron.getName());
        comma(sb);
        field(sb, "speaker", speaker == null ? "" : speaker.getName());
        comma(sb);
        name(sb, "players");
        sb.append('[');
        boolean first = true;
        for (Player player : gameParticipants(game)) {
            if (!first) {
                sb.append(',');
            }
            appendPublicPlayer(sb, player, ultron);
            first = false;
        }
        sb.append(']');
        comma(sb);
        name(sb, "stack");
        appendPublicStack(sb, game);
        sb.append('}');
        return sb.toString();
    }

    static String summarizeDecisionPoint(Game game, Player advisor, List<SpellAbility> candidates, AiCardMemory memory) {
        StringBuilder sb = new StringBuilder(2048);
        PhaseHandler phase = game.getPhaseHandler();
        sb.append("turn=").append(phase.getTurn());
        sb.append(" phase=").append(phase.getPhase());
        sb.append(" active=").append(phase.getPlayerTurn().getName());
        sb.append(" advisor=").append(advisor.getName());
        sb.append(" players=[");
        boolean firstPlayer = true;
        for (Player player : gameParticipants(game)) {
            if (!firstPlayer) {
                sb.append("; ");
            }
            sb.append(player.getName())
                    .append(" life=").append(player.getLife())
                    .append(" handVisible=").append(visibleCount(player.getCardsIn(ZoneType.Hand), ZoneType.Hand, advisor, memory))
                    .append(" handHidden=").append(hiddenCount(player.getCardsIn(ZoneType.Hand), ZoneType.Hand, advisor, memory))
                    .append(" battlefield=").append(player.getCardsIn(ZoneType.Battlefield).size())
                    .append(" graveyard=").append(player.getCardsIn(ZoneType.Graveyard).size())
                    .append(" libraryKnown=").append(visibleCount(player.getCardsIn(ZoneType.Library), ZoneType.Library, advisor, memory))
                    .append(" libraryHidden=").append(hiddenCount(player.getCardsIn(ZoneType.Library), ZoneType.Library, advisor, memory));
            firstPlayer = false;
        }
        sb.append("] candidates=[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            SpellAbility sa = candidates.get(i);
            sb.append(i).append(':').append(sourceName(sa, advisor, memory));
            if (!apiName(sa).isEmpty()) {
                sb.append('/').append(apiName(sa));
            }
        }
        sb.append(']');
        return sb.toString();
    }

    static String sourceName(SpellAbility spellAbility, Player advisor, AiCardMemory memory) {
        Card host = spellAbility == null ? null : spellAbility.getHostCard();
        ZoneType zone = host == null || host.getZone() == null ? null : host.getZone().getZoneType();
        return host == null ? "" : visibleCardName(host, zone, advisor, memory);
    }

    static String apiName(SpellAbility spellAbility) {
        return spellAbility == null || spellAbility.getApi() == null ? "" : spellAbility.getApi().name();
    }

    private static void appendPlayer(StringBuilder sb, Player player, Player advisor, AiCardMemory memory) {
        sb.append('{');
        field(sb, "name", player.getName());
        comma(sb);
        field(sb, "role", roleFor(player, advisor));
        comma(sb);
        field(sb, "gameStatus", gameStatus(player));
        comma(sb);
        name(sb, "life");
        sb.append(player.getLife());
        comma(sb);
        name(sb, "poison");
        sb.append(player.getPoisonCounters());
        comma(sb);
        name(sb, "landsPlayedThisTurn");
        sb.append(player.getLandsPlayedThisTurn());
        comma(sb);
        name(sb, "maxHandSize");
        sb.append(player.getMaxHandSize());
        comma(sb);
        name(sb, "visibleUntappedManaSourcesByColor");
        appendVisibleManaSources(sb, player, advisor, memory);
        comma(sb);
        name(sb, "zones");
        sb.append('{');
        for (int i = 0; i < SUMMARY_ZONES.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            ZoneType zone = SUMMARY_ZONES[i];
            name(sb, zone.name().toLowerCase());
            appendZone(sb, player.getCardsIn(zone), zone, advisor, memory);
        }
        sb.append('}');
        sb.append('}');
    }

    private static void appendPublicPlayer(StringBuilder sb, Player player, Player ultron) {
        sb.append('{');
        field(sb, "name", player.getName());
        comma(sb);
        field(sb, "roleRelativeToUltron", publicRoleFor(player, ultron));
        comma(sb);
        field(sb, "gameStatus", gameStatus(player));
        comma(sb);
        name(sb, "life");
        sb.append(player.getLife());
        comma(sb);
        name(sb, "poison");
        sb.append(player.getPoisonCounters());
        comma(sb);
        name(sb, "landsPlayedThisTurn");
        sb.append(player.getLandsPlayedThisTurn());
        comma(sb);
        name(sb, "maxHandSize");
        sb.append(player.getMaxHandSize());
        comma(sb);
        name(sb, "publicUntappedManaSourcesByColor");
        appendPublicManaSources(sb, player);
        comma(sb);
        name(sb, "zones");
        sb.append('{');
        for (int i = 0; i < SUMMARY_ZONES.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            ZoneType zone = SUMMARY_ZONES[i];
            name(sb, zone.name().toLowerCase());
            appendPublicZone(sb, player.getCardsIn(zone), zone);
        }
        sb.append('}');
        sb.append('}');
    }

    private static void appendZone(StringBuilder sb, CardCollectionView cards, ZoneType zone, Player advisor, AiCardMemory memory) {
        int hiddenCount = 0;
        int visibleCount = 0;
        int visibleOmitted = 0;
        int maxVisible = getMaxVisibleCardsForZone(zone, false);

        sb.append('{');
        name(sb, "visible");
        sb.append('[');
        boolean first = true;
        for (Card card : cards) {
            if (!canShowCardName(card, zone, advisor, memory)) {
                hiddenCount++;
                continue;
            }
            if (visibleCount >= maxVisible) {
                visibleOmitted++;
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            appendCard(sb, card, zone, advisor, memory);
            first = false;
            visibleCount++;
        }
        sb.append(']');
        comma(sb);
        name(sb, "visibleOmitted");
        sb.append(visibleOmitted);
        comma(sb);
        name(sb, "hiddenCount");
        sb.append(hiddenCount);
        comma(sb);
        name(sb, "totalCount");
        sb.append(cards.size());
        sb.append('}');
    }

    private static void appendCard(StringBuilder sb, Card card, ZoneType zone, Player advisor, AiCardMemory memory) {
        sb.append('{');
        name(sb, "id");
        sb.append(card.getId());
        comma(sb);
        field(sb, "name", visibleCardName(card, zone, advisor, memory));
        comma(sb);
        field(sb, "controller", card.getController() == null ? "" : card.getController().getName());
        comma(sb);
        field(sb, "owner", card.getOwner() == null ? "" : card.getOwner().getName());
        comma(sb);
        field(sb, "zone", zone.name());
        comma(sb);
        field(sb, "type", card.getType().toString());
        comma(sb);
        field(sb, "manaCost", card.getManaCost() == null ? "" : card.getManaCost().toString());
        comma(sb);
        name(sb, "tapped");
        sb.append(card.isTapped());
        if (card.isCreature()) {
            comma(sb);
            name(sb, "power");
            sb.append(card.getNetPower());
            comma(sb);
            name(sb, "toughness");
            sb.append(card.getNetToughness());
        }
        if (card.isPlaneswalker()) {
            comma(sb);
            name(sb, "loyalty");
            sb.append(card.getCurrentLoyalty());
        }
        String keywords = card.getKeywordKey();
        if (keywords != null && !keywords.isEmpty()) {
            comma(sb);
            field(sb, "keywords", keywords);
        }
        if (isRememberedRevealedInHand(card, zone, memory) && !card.getView().canBeShownTo(advisor.getView())) {
            comma(sb);
            name(sb, "knownFromPriorReveal");
            sb.append(true);
        }
        sb.append('}');
    }

    private static void appendPublicZone(StringBuilder sb, CardCollectionView cards, ZoneType zone) {
        int hiddenCount = 0;
        int visibleCount = 0;
        int visibleOmitted = 0;
        int maxVisible = getMaxVisibleCardsForZone(zone, true);

        sb.append('{');
        name(sb, "visible");
        sb.append('[');
        boolean first = true;
        for (Card card : cards) {
            if (!canShowPublicCardName(card, zone)) {
                hiddenCount++;
                continue;
            }
            if (visibleCount >= maxVisible) {
                visibleOmitted++;
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            appendPublicCard(sb, card, zone);
            first = false;
            visibleCount++;
        }
        sb.append(']');
        comma(sb);
        name(sb, "visibleOmitted");
        sb.append(visibleOmitted);
        comma(sb);
        name(sb, "hiddenCount");
        sb.append(hiddenCount);
        comma(sb);
        name(sb, "totalCount");
        sb.append(cards.size());
        sb.append('}');
    }

    private static void appendPublicCard(StringBuilder sb, Card card, ZoneType zone) {
        sb.append('{');
        name(sb, "id");
        sb.append(card.getId());
        comma(sb);
        field(sb, "name", card.getName());
        comma(sb);
        field(sb, "controller", card.getController() == null ? "" : card.getController().getName());
        comma(sb);
        field(sb, "owner", card.getOwner() == null ? "" : card.getOwner().getName());
        comma(sb);
        field(sb, "zone", zone.name());
        comma(sb);
        field(sb, "type", card.getType().toString());
        comma(sb);
        field(sb, "manaCost", card.getManaCost() == null ? "" : card.getManaCost().toString());
        comma(sb);
        name(sb, "tapped");
        sb.append(card.isTapped());
        if (card.isCreature()) {
            comma(sb);
            name(sb, "power");
            sb.append(card.getNetPower());
            comma(sb);
            name(sb, "toughness");
            sb.append(card.getNetToughness());
        }
        if (card.isPlaneswalker()) {
            comma(sb);
            name(sb, "loyalty");
            sb.append(card.getCurrentLoyalty());
        }
        String keywords = card.getKeywordKey();
        if (keywords != null && !keywords.isEmpty()) {
            comma(sb);
            field(sb, "keywords", keywords);
        }
        sb.append('}');
    }

    private static void appendVisibleManaSources(StringBuilder sb, Player player, Player advisor, AiCardMemory memory) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("W", 0);
        counts.put("U", 0);
        counts.put("B", 0);
        counts.put("R", 0);
        counts.put("G", 0);
        counts.put("C", 0);

        for (Card source : ComputerUtilMana.getAvailableManaSources(player, true)) {
            ZoneType zone = source.getZone() == null ? null : source.getZone().getZoneType();
            if (zone == ZoneType.Hand && !player.equals(advisor)) {
                continue;
            }
            if (zone != null && !canShowCardName(source, zone, advisor, memory)) {
                continue;
            }

            Map<String, Boolean> sourceProduces = new LinkedHashMap<>();
            for (String color : counts.keySet()) {
                sourceProduces.put(color, false);
            }
            for (SpellAbility manaAbility : ComputerUtilMana.getAIPlayableMana(source)) {
                manaAbility.setActivatingPlayer(player);
                if (!manaAbility.canPlay() || !manaAbility.checkRestrictions(player)) {
                    continue;
                }
                if (manaAbility.canProduce("W")) {
                    sourceProduces.put("W", true);
                }
                if (manaAbility.canProduce("U")) {
                    sourceProduces.put("U", true);
                }
                if (manaAbility.canProduce("B")) {
                    sourceProduces.put("B", true);
                }
                if (manaAbility.canProduce("R")) {
                    sourceProduces.put("R", true);
                }
                if (manaAbility.canProduce("G")) {
                    sourceProduces.put("G", true);
                }
                if (manaAbility.canProduce("C")) {
                    sourceProduces.put("C", true);
                }
                for (byte color : MagicColor.WUBRG) {
                    if (manaAbility.canProduce(MagicColor.toShortString(color))) {
                        sourceProduces.put(MagicColor.toShortString(color), true);
                    }
                }
            }
            for (Map.Entry<String, Boolean> entry : sourceProduces.entrySet()) {
                if (entry.getValue()) {
                    counts.put(entry.getKey(), counts.get(entry.getKey()) + 1);
                }
            }
        }

        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            name(sb, entry.getKey());
            sb.append(entry.getValue());
            first = false;
        }
        sb.append('}');
    }

    private static void appendPublicManaSources(StringBuilder sb, Player player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("W", 0);
        counts.put("U", 0);
        counts.put("B", 0);
        counts.put("R", 0);
        counts.put("G", 0);
        counts.put("C", 0);

        for (Card source : ComputerUtilMana.getAvailableManaSources(player, true)) {
            ZoneType zone = source.getZone() == null ? null : source.getZone().getZoneType();
            if (!canShowPublicCardName(source, zone)) {
                continue;
            }

            Map<String, Boolean> sourceProduces = new LinkedHashMap<>();
            for (String color : counts.keySet()) {
                sourceProduces.put(color, false);
            }
            for (SpellAbility manaAbility : ComputerUtilMana.getAIPlayableMana(source)) {
                manaAbility.setActivatingPlayer(player);
                if (!manaAbility.canPlay() || !manaAbility.checkRestrictions(player)) {
                    continue;
                }
                if (manaAbility.canProduce("W")) {
                    sourceProduces.put("W", true);
                }
                if (manaAbility.canProduce("U")) {
                    sourceProduces.put("U", true);
                }
                if (manaAbility.canProduce("B")) {
                    sourceProduces.put("B", true);
                }
                if (manaAbility.canProduce("R")) {
                    sourceProduces.put("R", true);
                }
                if (manaAbility.canProduce("G")) {
                    sourceProduces.put("G", true);
                }
                if (manaAbility.canProduce("C")) {
                    sourceProduces.put("C", true);
                }
                for (byte color : MagicColor.WUBRG) {
                    if (manaAbility.canProduce(MagicColor.toShortString(color))) {
                        sourceProduces.put(MagicColor.toShortString(color), true);
                    }
                }
            }
            for (Map.Entry<String, Boolean> entry : sourceProduces.entrySet()) {
                if (entry.getValue()) {
                    counts.put(entry.getKey(), counts.get(entry.getKey()) + 1);
                }
            }
        }

        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            name(sb, entry.getKey());
            sb.append(entry.getValue());
            first = false;
        }
        sb.append('}');
    }

    private static void appendStack(StringBuilder sb, Game game, Player advisor, AiCardMemory memory) {
        sb.append('[');
        boolean first = true;
        for (SpellAbilityStackInstance stackInstance : game.getStack()) {
            if (!first) {
                sb.append(',');
            }
            SpellAbility sa = stackInstance.getSpellAbility();
            Card host = sa.getHostCard();
            ZoneType zone = host == null || host.getZone() == null ? null : host.getZone().getZoneType();
            boolean showHost = host != null && canShowCardName(host, zone, advisor, memory);
            sb.append('{');
            if (showHost) {
                name(sb, "sourceId");
                sb.append(host.getId());
                comma(sb);
            }
            field(sb, "source", host == null ? "" : visibleCardName(host, zone, advisor, memory));
            comma(sb);
            field(sb, "controller", sa.getActivatingPlayer() == null ? "" : sa.getActivatingPlayer().getName());
            comma(sb);
            field(sb, "api", sa.getApi() == null ? "" : sa.getApi().name());
            comma(sb);
            field(sb, "text", sa.toString());
            sb.append('}');
            first = false;
        }
        sb.append(']');
    }

    private static void appendPublicStack(StringBuilder sb, Game game) {
        sb.append('[');
        boolean first = true;
        for (SpellAbilityStackInstance stackInstance : game.getStack()) {
            if (!first) {
                sb.append(',');
            }
            SpellAbility sa = stackInstance.getSpellAbility();
            Card host = sa.getHostCard();
            sb.append('{');
            if (host != null && !host.isFaceDown()) {
                name(sb, "sourceId");
                sb.append(host.getId());
                comma(sb);
            }
            field(sb, "source", host == null || host.isFaceDown() ? "Hidden card" : host.getName());
            comma(sb);
            field(sb, "controller", sa.getActivatingPlayer() == null ? "" : sa.getActivatingPlayer().getName());
            comma(sb);
            field(sb, "api", sa.getApi() == null ? "" : sa.getApi().name());
            comma(sb);
            field(sb, "text", sa.toString());
            sb.append('}');
            first = false;
        }
        sb.append(']');
    }

    private static void appendCandidates(StringBuilder sb, Player advisor, List<SpellAbility> candidates, AiCardMemory memory) {
        sb.append('[');
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SpellAbility sa = candidates.get(i);
            Card host = sa.getHostCard();
            ZoneType zone = host == null || host.getZone() == null ? null : host.getZone().getZoneType();

            sb.append('{');
            name(sb, "index");
            sb.append(i);
            comma(sb);
            if (host != null && canShowCardName(host, zone, advisor, memory)) {
                name(sb, "sourceId");
                sb.append(host.getId());
                comma(sb);
            }
            field(sb, "source", host == null ? "" : visibleCardName(host, zone, advisor, memory));
            comma(sb);
            field(sb, "sourceZone", zone == null ? "" : zone.name());
            comma(sb);
            field(sb, "api", sa.getApi() == null ? "" : sa.getApi().name());
            comma(sb);
            field(sb, "cost", sa.getPayCosts() == null ? "" : sa.getPayCosts().toString());
            comma(sb);
            field(sb, "text", sa.toString());
            sb.append('}');
        }
        sb.append(']');
    }

    private static void appendDecisionCardDetails(StringBuilder sb, Game game, Player advisor,
            List<SpellAbility> candidates, AiCardMemory memory) {
        Map<String, Card> cards = new LinkedHashMap<>();
        if (candidates != null) {
            for (SpellAbility sa : candidates) {
                addVisibleDetailCard(cards, sa == null ? null : sa.getHostCard(), advisor, memory);
            }
        }
        for (SpellAbilityStackInstance stackInstance : game.getStack()) {
            SpellAbility sa = stackInstance.getSpellAbility();
            addVisibleDetailCard(cards, sa == null ? null : sa.getHostCard(), advisor, memory);
        }
        for (Player player : gameParticipants(game)) {
            for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
                if (!card.isLand()) {
                    addVisibleDetailCard(cards, card, advisor, memory);
                }
            }
        }

        int maxCards = parseNonNegativeInt(System.getenv("ULTRON_CARD_DETAILS_MAX_CARDS"), DEFAULT_CARD_DETAILS_MAX_CARDS);
        int emitted = 0;
        sb.append('{');
        field(sb, "policy", maxCards == 0
                ? "automatic card reference preload disabled; use research tool requests for oracle/script details"
                : "candidate hosts, stack hosts, and visible nonland battlefield permanents; capped and deduplicated");
        comma(sb);
        name(sb, "cards");
        sb.append('[');
        boolean first = true;
        for (Card card : cards.values()) {
            if (emitted >= maxCards) {
                break;
            }
            if (!first) {
                sb.append(',');
            }
            appendCardDetail(sb, card);
            first = false;
            emitted++;
        }
        sb.append(']');
        comma(sb);
        name(sb, "omittedCount");
        sb.append(Math.max(0, cards.size() - emitted));
        sb.append('}');
    }

    private static void addVisibleDetailCard(Map<String, Card> cards, Card card, Player advisor, AiCardMemory memory) {
        if (card == null) {
            return;
        }
        ZoneType zone = card.getZone() == null ? null : card.getZone().getZoneType();
        if (!canShowCardName(card, zone, advisor, memory)) {
            return;
        }
        cards.putIfAbsent(cardDetailKey(card), card);
    }

    private static String cardDetailKey(Card card) {
        return card.getId() + ":" + card.getName();
    }

    private static List<Player> gameParticipants(Game game) {
        List<Player> players = new ArrayList<>();
        for (Player player : game.getRegisteredPlayers()) {
            addIfAbsent(players, player);
        }
        for (Player player : game.getPlayers()) {
            addIfAbsent(players, player);
        }
        return players;
    }

    private static void addIfAbsent(List<Player> players, Player player) {
        if (player != null && !players.contains(player)) {
            players.add(player);
        }
    }

    private static String gameStatus(Player player) {
        PlayerOutcome outcome = player.getOutcome();
        if (outcome == null) {
            return "active";
        }
        if (outcome.lossState == null) {
            return "won";
        }
        return "lost:" + outcome.lossState.name();
    }

    static void appendCardDetail(StringBuilder sb, Card card) {
        ZoneType zone = card.getZone() == null ? null : card.getZone().getZoneType();
        sb.append('{');
        name(sb, "id");
        sb.append(card.getId());
        comma(sb);
        field(sb, "name", card.getName());
        comma(sb);
        field(sb, "controller", card.getController() == null ? "" : card.getController().getName());
        comma(sb);
        field(sb, "owner", card.getOwner() == null ? "" : card.getOwner().getName());
        comma(sb);
        field(sb, "zone", zone == null ? "" : zone.name());
        comma(sb);
        field(sb, "type", card.getType().toString());
        comma(sb);
        field(sb, "manaCost", card.getManaCost() == null ? "" : card.getManaCost().toString());
        appendCardReferenceFields(sb, card);
        sb.append('}');
    }

    static void appendCardRulesDetail(StringBuilder sb, String cardName, CardRules rules) {
        sb.append('{');
        field(sb, "name", cardName);
        if (rules != null) {
            comma(sb);
            field(sb, "type", rules.getType().toString());
            comma(sb);
            field(sb, "manaCost", rules.getManaCost() == null ? "" : rules.getManaCost().toString());
            appendCardReferenceFields(sb, rules);
        }
        sb.append('}');
    }

    private static void appendCardReferenceFields(StringBuilder sb, Card card) {
        if (card == null) {
            return;
        }
        if (isEnvEnabled("ULTRON_INCLUDE_ORACLE_TEXT", true)) {
            String oracleText = currentOracleText(card);
            if (!isBlank(oracleText)) {
                comma(sb);
                field(sb, "oracleText", truncate(normalizeRulesText(oracleText),
                        parsePositiveInt(System.getenv("ULTRON_ORACLE_MAX_CHARS"), DEFAULT_ORACLE_MAX_CHARS)));
            }
        }
        if (isEnvEnabled("ULTRON_INCLUDE_CARD_SCRIPT", true)) {
            String script = forgeScriptText(card.getRules());
            if (!isBlank(script)) {
                comma(sb);
                field(sb, "forgeScript", script);
            }
        }
    }

    private static void appendCardReferenceFields(StringBuilder sb, CardRules rules) {
        if (rules == null) {
            return;
        }
        if (isEnvEnabled("ULTRON_INCLUDE_ORACLE_TEXT", true)) {
            String oracleText = rules.getOracleText();
            if (!isBlank(oracleText)) {
                comma(sb);
                field(sb, "oracleText", truncate(normalizeRulesText(oracleText),
                        parsePositiveInt(System.getenv("ULTRON_ORACLE_MAX_CHARS"), DEFAULT_ORACLE_MAX_CHARS)));
            }
        }
        if (isEnvEnabled("ULTRON_INCLUDE_CARD_SCRIPT", true)) {
            String script = forgeScriptText(rules);
            if (!isBlank(script)) {
                comma(sb);
                field(sb, "forgeScript", script);
            }
        }
    }

    private static String currentOracleText(Card card) {
        String text = card.getOracleText();
        if (!isBlank(text)) {
            return text;
        }
        CardRules rules = card.getRules();
        return rules == null ? "" : rules.getOracleText();
    }

    private static String forgeScriptText(CardRules rules) {
        if (rules == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder(2048);
        if (!isBlank(rules.getPath())) {
            sb.append("scriptPath=").append(rules.getPath()).append('\n');
        }
        sb.append("splitType=").append(rules.getSplitType()).append('\n');
        for (ICardFace face : rules.getAllFaces()) {
            appendFaceScript(sb, face);
        }
        return truncate(sb.toString().trim(),
                parsePositiveInt(System.getenv("ULTRON_CARD_SCRIPT_MAX_CHARS"), DEFAULT_SCRIPT_MAX_CHARS));
    }

    private static void appendFaceScript(StringBuilder sb, ICardFace face) {
        if (face == null) {
            return;
        }
        sb.append("[face ").append(face.getName()).append("]\n");
        sb.append("Type=").append(face.getType()).append('\n');
        sb.append("ManaCost=").append(face.getManaCost()).append('\n');
        if (!isBlank(face.getPower()) || !isBlank(face.getToughness())) {
            sb.append("PT=").append(nullToEmpty(face.getPower())).append('/').append(nullToEmpty(face.getToughness())).append('\n');
        }
        if (!isBlank(face.getInitialLoyalty())) {
            sb.append("Loyalty=").append(face.getInitialLoyalty()).append('\n');
        }
        if (!isBlank(face.getDefense())) {
            sb.append("Defense=").append(face.getDefense()).append('\n');
        }
        if (!isBlank(face.getNonAbilityText())) {
            sb.append("NonAbilityText=").append(normalizeRulesText(face.getNonAbilityText())).append('\n');
        }
        appendScriptEntries(sb, "K", face.getKeywords());
        appendScriptEntries(sb, "A", face.getAbilities());
        appendScriptEntries(sb, "S", face.getStaticAbilities());
        appendScriptEntries(sb, "T", face.getTriggers());
        appendScriptEntries(sb, "R", face.getReplacements());
        appendScriptVariables(sb, face.getVariables());
    }

    private static void appendScriptEntries(StringBuilder sb, String label, Iterable<String> values) {
        if (values == null) {
            return;
        }
        int maxEntries = parsePositiveInt(System.getenv("ULTRON_CARD_SCRIPT_MAX_ENTRIES"), DEFAULT_SCRIPT_MAX_ENTRIES);
        int count = 0;
        for (String value : values) {
            if (isBlank(value)) {
                continue;
            }
            if (count >= maxEntries) {
                sb.append(label).append("=... [more entries omitted]\n");
                return;
            }
            sb.append(label).append('=').append(normalizeRulesText(value)).append('\n');
            count++;
        }
    }

    private static void appendScriptVariables(StringBuilder sb, Iterable<Map.Entry<String, String>> values) {
        if (values == null) {
            return;
        }
        int maxEntries = parsePositiveInt(System.getenv("ULTRON_CARD_SCRIPT_MAX_ENTRIES"), DEFAULT_SCRIPT_MAX_ENTRIES);
        int count = 0;
        for (Map.Entry<String, String> entry : values) {
            if (entry == null || isBlank(entry.getKey()) || isBlank(entry.getValue())) {
                continue;
            }
            if (count >= maxEntries) {
                sb.append("SVar=... [more entries omitted]\n");
                return;
            }
            sb.append("SVar ").append(entry.getKey()).append('=').append(normalizeRulesText(entry.getValue())).append('\n');
            count++;
        }
    }

    private static int visibleCount(CardCollectionView cards, ZoneType zone, Player advisor, AiCardMemory memory) {
        int result = 0;
        for (Card card : cards) {
            if (canShowCardName(card, zone, advisor, memory)) {
                result++;
            }
        }
        return result;
    }

    private static int hiddenCount(CardCollectionView cards, ZoneType zone, Player advisor, AiCardMemory memory) {
        return cards.size() - visibleCount(cards, zone, advisor, memory);
    }

    static boolean canShowCardName(Card card, ZoneType zone, Player advisor, AiCardMemory memory) {
        if (card == null) {
            return false;
        }
        if (isRememberedRevealedInHand(card, zone, memory)) {
            return true;
        }
        if (!card.getView().canBeShownTo(advisor.getView())) {
            return false;
        }
        return !card.isFaceDown() || card.getView().canFaceDownBeShownTo(advisor.getView());
    }

    static String visibleCardName(Card card, ZoneType zone, Player advisor, AiCardMemory memory) {
        return canShowCardName(card, zone, advisor, memory) ? card.getName() : "Hidden card";
    }

    private static boolean canShowPublicCardName(Card card, ZoneType zone) {
        if (card == null || zone == null) {
            return false;
        }
        if (card.isFaceDown()) {
            return false;
        }
        return switch (zone) {
        case Battlefield, Graveyard, Exile, Command -> true;
        default -> false;
        };
    }

    private static int getMaxVisibleCardsForZone(ZoneType zone, boolean publicState) {
        if (zone == ZoneType.Battlefield) {
            return parsePositiveInt(
                    System.getenv(publicState ? "ULTRON_PUBLIC_BATTLEFIELD_MAX_VISIBLE_CARDS" : "ULTRON_BATTLEFIELD_MAX_VISIBLE_CARDS"),
                    publicState ? DEFAULT_PUBLIC_BATTLEFIELD_MAX_VISIBLE_CARDS : DEFAULT_BATTLEFIELD_MAX_VISIBLE_CARDS);
        }
        return parsePositiveInt(
                System.getenv(publicState ? "ULTRON_PUBLIC_ZONE_MAX_VISIBLE_CARDS" : "ULTRON_ZONE_MAX_VISIBLE_CARDS"),
                publicState ? DEFAULT_PUBLIC_ZONE_MAX_VISIBLE_CARDS : DEFAULT_ZONE_MAX_VISIBLE_CARDS);
    }

    private static boolean isRememberedRevealedInHand(Card card, ZoneType zone, AiCardMemory memory) {
        return memory != null
                && zone == ZoneType.Hand
                && memory.isRememberedCard(card, AiCardMemory.MemorySet.REVEALED_CARDS);
    }

    private static String roleFor(Player player, Player advisor) {
        if (player.equals(advisor)) {
            return "self";
        }
        if (player.isOpponentOf(advisor)) {
            return "opponent";
        }
        return "ally";
    }

    private static String publicRoleFor(Player player, Player ultron) {
        if (ultron != null && player.equals(ultron)) {
            return "ultron";
        }
        if (ultron != null && player.isOpponentOf(ultron)) {
            return "opponent";
        }
        return "ally_or_unknown";
    }

    private static void field(StringBuilder sb, String name, String value) {
        name(sb, name);
        sb.append(JsonSupport.quote(value));
    }

    private static void name(StringBuilder sb, String name) {
        sb.append(JsonSupport.quote(name)).append(':');
    }

    private static void comma(StringBuilder sb) {
        sb.append(',');
    }

    private static String normalizeRulesText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\n", "\n").replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 24)).trim() + "\n[card text truncated]";
    }

    private static boolean isEnvEnabled(String name, boolean fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase()) {
        case "0", "false", "no", "off" -> false;
        default -> true;
        };
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int parseNonNegativeInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return Math.max(0, fallback);
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 ? parsed : Math.max(0, fallback);
        } catch (NumberFormatException ex) {
            return Math.max(0, fallback);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
