package forge.ai.llm;

import forge.StaticData;
import forge.ai.AiCardMemory;
import forge.card.CardRules;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UltronResearchTools {
    private static final int DEFAULT_MAX_REQUESTS = 8;
    private static final int DEFAULT_MAX_CARD_REFERENCES = 80;
    private static final int DEFAULT_MAX_ZONE_CARDS = 96;
    private static final int DEFAULT_MAX_DATABASE_REFERENCES = 40;
    private static final int DEFAULT_MAX_RESULT_CHARS = 140000;
    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d+");
    private static final ZoneType[] SEARCH_ZONES = new ZoneType[] {
            ZoneType.Battlefield,
            ZoneType.Hand,
            ZoneType.Graveyard,
            ZoneType.Exile,
            ZoneType.Command,
            ZoneType.Library
    };

    private UltronResearchTools() {
    }

    static List<Request> parseRequests(String responseJson) {
        if (isBlank(responseJson)) {
            return Collections.emptyList();
        }

        String requestsJson = extractArray(responseJson, "toolRequests");
        if (requestsJson == null) {
            requestsJson = extractArray(responseJson, "researchRequests");
        }
        if (requestsJson == null) {
            return Collections.emptyList();
        }

        int maxRequests = parsePositiveInt(System.getenv("ULTRON_RESEARCH_MAX_REQUESTS"), DEFAULT_MAX_REQUESTS);
        List<Request> result = new ArrayList<>();
        for (String object : splitTopLevelObjects(requestsJson)) {
            if (result.size() >= maxRequests) {
                break;
            }
            String tool = normalizeToolName(extractString(object, "tool"));
            if (tool.isEmpty()) {
                continue;
            }
            result.add(new Request(
                    tool,
                    extractIntArray(object, "cardIds"),
                    extractIntArray(object, "candidateIndexes"),
                    extractStringArray(object, "cardNames"),
                    extractString(object, "player"),
                    extractString(object, "zone"),
                    extractInt(object, "limit", 0),
                    extractString(object, "kind"),
                    extractString(object, "text"),
                    extractInt(object, "populationSize", 0),
                    extractInt(object, "successCount", -1),
                    extractInt(object, "draws", -1),
                    extractInt(object, "atLeast", -1),
                    extractInt(object, "exactly", -1)));
        }
        return result;
    }

    static Result execute(Game game, Player advisor, UltronGameContext context, List<SpellAbility> candidates,
            AiCardMemory memory, List<Request> requests) {
        VisibleIndex visibleIndex = buildVisibleIndex(game, advisor, candidates, memory);
        Budget budget = new Budget(
                parsePositiveInt(System.getenv("ULTRON_RESEARCH_MAX_CARD_REFERENCES"), DEFAULT_MAX_CARD_REFERENCES),
                parsePositiveInt(System.getenv("ULTRON_RESEARCH_MAX_DATABASE_REFERENCES"), DEFAULT_MAX_DATABASE_REFERENCES),
                parsePositiveInt(System.getenv("ULTRON_RESEARCH_MAX_RESULT_CHARS"), DEFAULT_MAX_RESULT_CHARS));
        Map<String, String> rememberedReferences = new LinkedHashMap<>();

        StringBuilder sb = new StringBuilder(32768);
        sb.append('{');
        field(sb, "schema", "forge-ultron-visible-research-results-v1");
        comma(sb);
        field(sb, "visibility", "Only visible/current card data and public card database definitions are returned. Hidden game state is never exposed.");
        comma(sb);
        name(sb, "results");
        sb.append('[');
        boolean first = true;
        for (Request request : requests) {
            if (!first) {
                sb.append(',');
            }
            appendRequestResult(sb, game, advisor, context, candidates, memory, visibleIndex,
                    request, budget, rememberedReferences);
            first = false;
        }
        sb.append(']');
        comma(sb);
        name(sb, "budget");
        sb.append('{');
        numberField(sb, "cardReferencesUsed", budget.cardReferencesUsed);
        comma(sb);
        numberField(sb, "cardReferencesMax", budget.maxCardReferences);
        comma(sb);
        numberField(sb, "databaseReferencesUsed", budget.databaseReferencesUsed);
        comma(sb);
        numberField(sb, "databaseReferencesMax", budget.maxDatabaseReferences);
        comma(sb);
        numberField(sb, "maxResultChars", budget.maxResultChars);
        sb.append('}');
        sb.append('}');

        return new Result(sb.toString(), rememberedReferences);
    }

    private static void appendRequestResult(StringBuilder sb, Game game, Player advisor, UltronGameContext context,
            List<SpellAbility> candidates, AiCardMemory memory, VisibleIndex visibleIndex, Request request, Budget budget,
            Map<String, String> rememberedReferences) {
        sb.append('{');
        field(sb, "tool", request.tool());
        comma(sb);
        switch (request.tool()) {
        case "card_reference":
            appendCardReferenceResult(sb, advisor, memory, candidates, visibleIndex, request, budget, rememberedReferences);
            break;
        case "candidate_detail":
            appendCandidateDetailResult(sb, advisor, memory, candidates, request, budget, rememberedReferences);
            break;
        case "zone_detail":
            appendZoneDetailResult(sb, game, advisor, memory, request, budget, rememberedReferences);
            break;
        case "graveyard_reference":
            appendGraveyardReferenceResult(sb, game, advisor, memory, request, budget, rememberedReferences);
            break;
        case "stack_detail":
            appendStackDetailResult(sb, game, advisor, memory, budget, rememberedReferences);
            break;
        case "card_database":
            appendCardDatabaseResult(sb, request, budget, rememberedReferences);
            break;
        case "board_state":
            appendBoardStateResult(sb, game, advisor, candidates, memory, budget);
            break;
        case "probability":
            appendProbabilityResult(sb, request);
            break;
        case "commit_memory":
            appendCommitMemoryResult(sb, context, request);
            break;
        default:
            field(sb, "status", "error");
            comma(sb);
            field(sb, "error", "Unknown tool. Use card_reference, candidate_detail, zone_detail, graveyard_reference, stack_detail, card_database, board_state, probability, or commit_memory.");
            break;
        }
        sb.append('}');
    }

    private static void appendCardReferenceResult(StringBuilder sb, Player advisor, AiCardMemory memory,
            List<SpellAbility> candidates, VisibleIndex visibleIndex, Request request, Budget budget,
            Map<String, String> rememberedReferences) {
        LinkedHashMap<String, Card> selected = new LinkedHashMap<>();
        for (Integer candidateIndex : request.candidateIndexes()) {
            Card card = candidateHost(candidates, candidateIndex);
            addVisibleCard(selected, card, advisor, memory);
        }
        for (Integer cardId : request.cardIds()) {
            Card card = visibleIndex.byId.get(cardId);
            if (card != null) {
                selected.putIfAbsent(cardKey(card), card);
            }
        }
        for (String cardName : request.cardNames()) {
            for (Card card : visibleIndex.byName.getOrDefault(normalizeName(cardName), Collections.emptyList())) {
                selected.putIfAbsent(cardKey(card), card);
            }
        }

        field(sb, "status", "ok");
        comma(sb);
        appendCardsArray(sb, "cards", selected.values(), 0, budget, rememberedReferences);
    }

    private static void appendCandidateDetailResult(StringBuilder sb, Player advisor, AiCardMemory memory,
            List<SpellAbility> candidates, Request request, Budget budget, Map<String, String> rememberedReferences) {
        LinkedHashMap<String, Card> selected = new LinkedHashMap<>();
        if (request.candidateIndexes().isEmpty()) {
            for (int i = 0; i < candidates.size(); i++) {
                addVisibleCard(selected, candidateHost(candidates, i), advisor, memory);
            }
        } else {
            for (Integer candidateIndex : request.candidateIndexes()) {
                addVisibleCard(selected, candidateHost(candidates, candidateIndex), advisor, memory);
            }
        }

        field(sb, "status", "ok");
        comma(sb);
        name(sb, "candidates");
        sb.append('[');
        boolean first = true;
        for (int i = 0; i < candidates.size(); i++) {
            if (!request.candidateIndexes().isEmpty() && !request.candidateIndexes().contains(i)) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            SpellAbility sa = candidates.get(i);
            sb.append('{');
            numberField(sb, "index", i);
            comma(sb);
            field(sb, "source", UltronGameStateSerializer.sourceName(sa, advisor, memory));
            comma(sb);
            field(sb, "api", UltronGameStateSerializer.apiName(sa));
            comma(sb);
            field(sb, "cost", sa.getPayCosts() == null ? "" : sa.getPayCosts().toString());
            comma(sb);
            field(sb, "text", sa.toString());
            sb.append('}');
            first = false;
        }
        sb.append(']');
        comma(sb);
        appendCardsArray(sb, "cardReferences", selected.values(), 0, budget, rememberedReferences);
    }

    private static void appendZoneDetailResult(StringBuilder sb, Game game, Player advisor, AiCardMemory memory,
            Request request, Budget budget, Map<String, String> rememberedReferences) {
        Player target = findPlayer(game, request.player());
        ZoneType zone = zoneFromRequest(request.zone());
        if (target == null || zone == null) {
            field(sb, "status", "error");
            comma(sb);
            field(sb, "error", "zone_detail requires a valid player and zone.");
            return;
        }

        int visibleCount = 0;
        int hiddenCount = 0;
        List<Card> visibleCards = new ArrayList<>();
        int limit = request.limit() > 0 ? request.limit()
                : parsePositiveInt(System.getenv("ULTRON_RESEARCH_MAX_ZONE_CARDS"), DEFAULT_MAX_ZONE_CARDS);
        for (Card card : target.getCardsIn(zone)) {
            if (!UltronGameStateSerializer.canShowCardName(card, zone, advisor, memory)) {
                hiddenCount++;
                continue;
            }
            visibleCount++;
            if (visibleCards.size() < limit) {
                visibleCards.add(card);
            }
        }

        field(sb, "status", "ok");
        comma(sb);
        field(sb, "player", target.getName());
        comma(sb);
        field(sb, "zone", zone.name());
        comma(sb);
        numberField(sb, "visibleCount", visibleCount);
        comma(sb);
        numberField(sb, "hiddenCount", hiddenCount);
        comma(sb);
        numberField(sb, "totalCount", target.getCardsIn(zone).size());
        comma(sb);
        numberField(sb, "visibleOmitted", Math.max(0, visibleCount - visibleCards.size()));
        comma(sb);
        appendCardsArray(sb, "cards", visibleCards, 0, budget, rememberedReferences);
    }

    private static void appendGraveyardReferenceResult(StringBuilder sb, Game game, Player advisor, AiCardMemory memory,
            Request request, Budget budget, Map<String, String> rememberedReferences) {
        int limit = request.limit() > 0 ? request.limit()
                : parsePositiveInt(System.getenv("ULTRON_RESEARCH_MAX_ZONE_CARDS"), DEFAULT_MAX_ZONE_CARDS);
        List<Card> selected = new ArrayList<>();
        int visibleRelevant = 0;
        for (Player player : game.getPlayers()) {
            visibleRelevant += collectZonePlayReferences(selected, player.getCardsIn(ZoneType.Graveyard),
                    ZoneType.Graveyard, advisor, memory, limit);
            visibleRelevant += collectZonePlayReferences(selected, player.getCardsIn(ZoneType.Exile),
                    ZoneType.Exile, advisor, memory, limit);
        }

        field(sb, "status", "ok");
        comma(sb);
        field(sb, "policy", "visible graveyard/exile cards with flashback, retrace, escape, jump-start, disturb, aftermath, adventure, unearth, or active may-play hints");
        comma(sb);
        numberField(sb, "visibleRelevantCount", visibleRelevant);
        comma(sb);
        numberField(sb, "visibleOmitted", Math.max(0, visibleRelevant - selected.size()));
        comma(sb);
        appendCardsArray(sb, "cards", selected, 0, budget, rememberedReferences);
    }

    private static void appendStackDetailResult(StringBuilder sb, Game game, Player advisor, AiCardMemory memory,
            Budget budget, Map<String, String> rememberedReferences) {
        List<Card> selected = new ArrayList<>();
        field(sb, "status", "ok");
        comma(sb);
        name(sb, "stack");
        sb.append('[');
        boolean first = true;
        for (SpellAbilityStackInstance stackInstance : game.getStack()) {
            if (!first) {
                sb.append(',');
            }
            SpellAbility sa = stackInstance.getSpellAbility();
            Card host = sa.getHostCard();
            ZoneType zone = host == null || host.getZone() == null ? null : host.getZone().getZoneType();
            boolean showHost = host != null && UltronGameStateSerializer.canShowCardName(host, zone, advisor, memory);
            sb.append('{');
            if (showHost) {
                numberField(sb, "sourceId", host.getId());
                comma(sb);
                selected.add(host);
            }
            field(sb, "source", host == null ? "" : UltronGameStateSerializer.visibleCardName(host, zone, advisor, memory));
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
        comma(sb);
        appendCardsArray(sb, "cardReferences", selected, 0, budget, rememberedReferences);
    }

    private static void appendCardDatabaseResult(StringBuilder sb, Request request, Budget budget,
            Map<String, String> rememberedReferences) {
        field(sb, "status", "ok");
        comma(sb);
        field(sb, "note", "Public Forge card database reference only. This is not evidence that the card is present in any hidden zone.");
        comma(sb);
        name(sb, "cards");
        sb.append('[');
        boolean first = true;
        int omitted = 0;
        for (String cardName : request.cardNames()) {
            CardRules rules = findRules(cardName);
            if (rules == null) {
                continue;
            }
            StringBuilder detail = new StringBuilder(4096);
            UltronGameStateSerializer.appendCardRulesDetail(detail, rules.getName(), rules);
            if (!budget.allowDatabaseDetail(sb.length() + detail.length())) {
                omitted++;
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            sb.append(detail);
            budget.databaseReferencesUsed++;
            rememberedReferences.put("db:" + rules.getName(), compactRulesSummary(rules));
            first = false;
        }
        sb.append(']');
        comma(sb);
        numberField(sb, "omittedCount", omitted);
    }

    private static void appendBoardStateResult(StringBuilder sb, Game game, Player advisor,
            List<SpellAbility> candidates, AiCardMemory memory, Budget budget) {
        String visibleState = UltronGameStateSerializer.serialize(game, advisor, candidates, memory);
        field(sb, "status", "ok");
        comma(sb);
        field(sb, "state", truncate(visibleState, budget.maxResultChars));
    }

    private static void appendProbabilityResult(StringBuilder sb, Request request) {
        if (request.populationSize() <= 0 || request.successCount() < 0 || request.draws() < 0
                || request.successCount() > request.populationSize() || request.draws() > request.populationSize()) {
            field(sb, "status", "error");
            comma(sb);
            field(sb, "error", "probability requires populationSize, successCount, and draws with valid hypergeometric bounds.");
            return;
        }

        int exactly = request.exactly();
        int threshold = exactly >= 0 ? exactly : Math.max(1, request.atLeast());
        double probability = exactly >= 0
                ? hypergeometricExactly(request.populationSize(), request.successCount(), request.draws(), threshold)
                : hypergeometricAtLeast(request.populationSize(), request.successCount(), request.draws(), threshold);

        field(sb, "status", "ok");
        comma(sb);
        field(sb, "distribution", "hypergeometric");
        comma(sb);
        numberField(sb, "populationSize", request.populationSize());
        comma(sb);
        numberField(sb, "successCount", request.successCount());
        comma(sb);
        numberField(sb, "draws", request.draws());
        comma(sb);
        field(sb, "mode", exactly >= 0 ? "exactly" : "atLeast");
        comma(sb);
        numberField(sb, "threshold", threshold);
        comma(sb);
        name(sb, "probability");
        sb.append(String.format(Locale.ROOT, "%.8f", probability));
        comma(sb);
        name(sb, "percent");
        sb.append(String.format(Locale.ROOT, "%.4f", probability * 100.0));
    }

    private static void appendCommitMemoryResult(StringBuilder sb, UltronGameContext context, Request request) {
        if (context == null || isBlank(request.text())) {
            field(sb, "status", "error");
            comma(sb);
            field(sb, "error", "commit_memory requires non-empty text.");
            return;
        }
        String kind = isBlank(request.kind()) ? "agent_note" : request.kind();
        context.recordAgentMemory(kind, request.text());
        field(sb, "status", "ok");
        comma(sb);
        field(sb, "kind", kind);
    }

    private static void appendCardsArray(StringBuilder sb, String fieldName, Iterable<Card> cards, int requestedLimit,
            Budget budget, Map<String, String> rememberedReferences) {
        int emitted = 0;
        int omitted = 0;
        int limit = requestedLimit > 0 ? requestedLimit : Integer.MAX_VALUE;
        name(sb, fieldName);
        sb.append('[');
        boolean first = true;
        for (Card card : cards) {
            if (card == null || emitted >= limit) {
                omitted++;
                continue;
            }
            StringBuilder detail = new StringBuilder(4096);
            UltronGameStateSerializer.appendCardDetail(detail, card);
            if (!budget.allowCardDetail(sb.length() + detail.length())) {
                omitted++;
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            sb.append(detail);
            budget.cardReferencesUsed++;
            rememberedReferences.put(cardKey(card), compactCardSummary(card));
            emitted++;
            first = false;
        }
        sb.append(']');
        comma(sb);
        numberField(sb, "omittedCount", omitted);
    }

    private static VisibleIndex buildVisibleIndex(Game game, Player advisor, List<SpellAbility> candidates, AiCardMemory memory) {
        VisibleIndex index = new VisibleIndex();
        for (SpellAbility candidate : candidates) {
            addVisibleToIndex(index, candidate == null ? null : candidate.getHostCard(), advisor, memory);
        }
        for (SpellAbilityStackInstance stackInstance : game.getStack()) {
            SpellAbility sa = stackInstance.getSpellAbility();
            addVisibleToIndex(index, sa == null ? null : sa.getHostCard(), advisor, memory);
        }
        for (Player player : game.getPlayers()) {
            for (ZoneType zone : SEARCH_ZONES) {
                for (Card card : player.getCardsIn(zone)) {
                    addVisibleToIndex(index, card, advisor, memory);
                }
            }
        }
        return index;
    }

    private static void addVisibleToIndex(VisibleIndex index, Card card, Player advisor, AiCardMemory memory) {
        if (card == null) {
            return;
        }
        ZoneType zone = card.getZone() == null ? null : card.getZone().getZoneType();
        if (!UltronGameStateSerializer.canShowCardName(card, zone, advisor, memory)) {
            return;
        }
        index.byId.putIfAbsent(card.getId(), card);
        index.byName.computeIfAbsent(normalizeName(card.getName()), ignored -> new ArrayList<>()).add(card);
    }

    private static void addVisibleCard(LinkedHashMap<String, Card> selected, Card card, Player advisor, AiCardMemory memory) {
        if (card == null) {
            return;
        }
        ZoneType zone = card.getZone() == null ? null : card.getZone().getZoneType();
        if (UltronGameStateSerializer.canShowCardName(card, zone, advisor, memory)) {
            selected.putIfAbsent(cardKey(card), card);
        }
    }

    private static int collectZonePlayReferences(List<Card> selected, Iterable<Card> cards, ZoneType zone,
            Player advisor, AiCardMemory memory, int limit) {
        int relevant = 0;
        for (Card card : cards) {
            if (!UltronGameStateSerializer.canShowCardName(card, zone, advisor, memory) || !hasZonePlayHint(card)) {
                continue;
            }
            relevant++;
            if (selected.size() < limit) {
                selected.add(card);
            }
        }
        return relevant;
    }

    private static boolean hasZonePlayHint(Card card) {
        if (card == null) {
            return false;
        }
        if (!card.getMayPlay().isEmpty()) {
            return true;
        }
        String haystack = (card.getKeywordKey() + "\n" + card.getOracleText()).toLowerCase(Locale.ROOT);
        return haystack.contains("flashback")
                || haystack.contains("retrace")
                || haystack.contains("escape")
                || haystack.contains("jump-start")
                || haystack.contains("disturb")
                || haystack.contains("aftermath")
                || haystack.contains("adventure")
                || haystack.contains("unearth");
    }

    private static Card candidateHost(List<SpellAbility> candidates, Integer candidateIndex) {
        if (candidateIndex == null || candidateIndex < 0 || candidateIndex >= candidates.size()) {
            return null;
        }
        SpellAbility sa = candidates.get(candidateIndex);
        return sa == null ? null : sa.getHostCard();
    }

    private static Player findPlayer(Game game, String playerName) {
        if (game == null || isBlank(playerName)) {
            return null;
        }
        for (Player player : game.getPlayers()) {
            if (player.getName().equalsIgnoreCase(playerName.trim())) {
                return player;
            }
        }
        return null;
    }

    private static ZoneType zoneFromRequest(String zoneName) {
        if (isBlank(zoneName)) {
            return null;
        }
        try {
            return ZoneType.smartValueOf(zoneName);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static CardRules findRules(String cardName) {
        if (isBlank(cardName) || StaticData.instance() == null) {
            return null;
        }
        PaperCard card = StaticData.instance().getCommonCards().getCard(cardName.trim());
        if (card == null && StaticData.instance().getVariantCards() != null) {
            card = StaticData.instance().getVariantCards().getCard(cardName.trim());
        }
        return card == null ? null : card.getRules();
    }

    private static String compactCardSummary(Card card) {
        return "card name=" + card.getName()
                + " type=" + card.getType()
                + " manaCost=" + (card.getManaCost() == null ? "" : card.getManaCost())
                + " oracle=" + truncate(oneLine(card.getOracleText()), 600);
    }

    private static String compactRulesSummary(CardRules rules) {
        return "database name=" + rules.getName()
                + " type=" + rules.getType()
                + " manaCost=" + (rules.getManaCost() == null ? "" : rules.getManaCost())
                + " oracle=" + truncate(oneLine(rules.getOracleText()), 600);
    }

    private static String cardKey(Card card) {
        return "card:" + normalizeName(card.getName());
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeToolName(String tool) {
        return tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String extractArray(String json, String fieldName) {
        int fieldIndex = json.indexOf(JsonSupport.quote(fieldName));
        if (fieldIndex < 0) {
            return null;
        }
        int colon = json.indexOf(':', fieldIndex);
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('[', colon);
        if (start < 0) {
            return null;
        }
        int end = findMatching(json, start, '[', ']');
        return end < 0 ? null : json.substring(start, end + 1);
    }

    private static List<String> splitTopLevelObjects(String jsonArray) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            if (jsonArray.charAt(i) != '{') {
                continue;
            }
            int end = findMatching(jsonArray, i, '{', '}');
            if (end < 0) {
                break;
            }
            result.add(jsonArray.substring(i, end + 1));
            i = end;
        }
        return result;
    }

    private static int findMatching(String value, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String extractString(String json, String fieldName) {
        int valueIndex = valueStart(json, fieldName);
        if (valueIndex < 0 || json.charAt(valueIndex) != '"') {
            return "";
        }
        String result = JsonSupport.unquoteAt(json, valueIndex);
        return result == null ? "" : result;
    }

    private static int extractInt(String json, String fieldName, int fallback) {
        int valueIndex = valueStart(json, fieldName);
        if (valueIndex < 0) {
            return fallback;
        }
        Matcher matcher = INTEGER_PATTERN.matcher(json.substring(valueIndex));
        return matcher.lookingAt() ? Integer.parseInt(matcher.group()) : fallback;
    }

    private static List<Integer> extractIntArray(String json, String fieldName) {
        String array = extractArray(json, fieldName);
        if (array == null) {
            return Collections.emptyList();
        }
        List<Integer> result = new ArrayList<>();
        Matcher matcher = INTEGER_PATTERN.matcher(array);
        while (matcher.find()) {
            result.add(Integer.parseInt(matcher.group()));
        }
        return result;
    }

    private static List<String> extractStringArray(String json, String fieldName) {
        String array = extractArray(json, fieldName);
        if (array == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            if (array.charAt(i) != '"') {
                continue;
            }
            String value = JsonSupport.unquoteAt(array, i);
            if (value != null) {
                result.add(value);
            }
            i = nextStringEnd(array, i);
            if (i < 0) {
                break;
            }
        }
        return result;
    }

    private static int nextStringEnd(String value, int quoteIndex) {
        boolean escaped = false;
        for (int i = quoteIndex + 1; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int valueStart(String json, String fieldName) {
        int fieldIndex = json.indexOf(JsonSupport.quote(fieldName));
        if (fieldIndex < 0) {
            return -1;
        }
        int colon = json.indexOf(':', fieldIndex);
        if (colon < 0) {
            return -1;
        }
        int valueIndex = colon + 1;
        while (valueIndex < json.length() && Character.isWhitespace(json.charAt(valueIndex))) {
            valueIndex++;
        }
        return valueIndex < json.length() ? valueIndex : -1;
    }

    private static void field(StringBuilder sb, String name, String value) {
        name(sb, name);
        sb.append(JsonSupport.quote(value));
    }

    private static void numberField(StringBuilder sb, String name, int value) {
        name(sb, name);
        sb.append(value);
    }

    private static void name(StringBuilder sb, String name) {
        sb.append(JsonSupport.quote(name)).append(':');
    }

    private static void comma(StringBuilder sb) {
        sb.append(',');
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 18)).trim() + " [truncated]";
    }

    private static double hypergeometricAtLeast(int populationSize, int successCount, int draws, int threshold) {
        int maxSuccesses = Math.min(successCount, draws);
        double result = 0.0;
        for (int k = Math.max(0, threshold); k <= maxSuccesses; k++) {
            result += hypergeometricExactly(populationSize, successCount, draws, k);
        }
        return Math.min(1.0, Math.max(0.0, result));
    }

    private static double hypergeometricExactly(int populationSize, int successCount, int draws, int successesDrawn) {
        int failures = populationSize - successCount;
        if (successesDrawn < 0 || successesDrawn > successCount || successesDrawn > draws
                || draws - successesDrawn > failures) {
            return 0.0;
        }
        return Math.exp(logCombination(successCount, successesDrawn)
                + logCombination(failures, draws - successesDrawn)
                - logCombination(populationSize, draws));
    }

    private static double logCombination(int n, int k) {
        if (k < 0 || k > n) {
            return Double.NEGATIVE_INFINITY;
        }
        int m = Math.min(k, n - k);
        double result = 0.0;
        for (int i = 1; i <= m; i++) {
            result += Math.log(n - m + i) - Math.log(i);
        }
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    record Request(String tool, List<Integer> cardIds, List<Integer> candidateIndexes, List<String> cardNames,
            String player, String zone, int limit, String kind, String text, int populationSize, int successCount,
            int draws, int atLeast, int exactly) {
    }

    record Result(String json, Map<String, String> rememberedReferences) {
    }

    private static final class VisibleIndex {
        private final Map<Integer, Card> byId = new LinkedHashMap<>();
        private final Map<String, List<Card>> byName = new LinkedHashMap<>();
    }

    private static final class Budget {
        private final int maxCardReferences;
        private final int maxDatabaseReferences;
        private final int maxResultChars;
        private int cardReferencesUsed;
        private int databaseReferencesUsed;

        private Budget(int maxCardReferences, int maxDatabaseReferences, int maxResultChars) {
            this.maxCardReferences = maxCardReferences;
            this.maxDatabaseReferences = maxDatabaseReferences;
            this.maxResultChars = maxResultChars;
        }

        private boolean allowCardDetail(int projectedChars) {
            return cardReferencesUsed < maxCardReferences && projectedChars < maxResultChars;
        }

        private boolean allowDatabaseDetail(int projectedChars) {
            return databaseReferencesUsed < maxDatabaseReferences && projectedChars < maxResultChars;
        }
    }
}
