package forge.ai.llm;

import forge.ai.AiCardMemory;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UltronStrategicPlan {
    private static final Pattern PLAN_ITEM_PATTERN = Pattern.compile("\\{[^{}]*\"card\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"[^{}]*}");
    private static final Pattern JSON_STRING_PATTERN = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern RATIONALE_PATTERN = Pattern.compile("\"rationale\"\\s*:\\s*\"");

    private final List<PlanItem> plannedActions;
    private final List<String> attackers;
    private final List<String> defenders;
    private final List<String> holdInteraction;
    private final String rationale;

    private UltronStrategicPlan(List<PlanItem> plannedActions, List<String> attackers, List<String> defenders,
            List<String> holdInteraction, String rationale) {
        this.plannedActions = plannedActions;
        this.attackers = attackers;
        this.defenders = defenders;
        this.holdInteraction = holdInteraction;
        this.rationale = rationale;
    }

    static UltronStrategicPlan empty() {
        return new UltronStrategicPlan(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null);
    }

    static UltronStrategicPlan parse(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return empty();
        }

        List<PlanItem> items = new ArrayList<>();
        Matcher itemMatcher = PLAN_ITEM_PATTERN.matcher(responseJson);
        while (itemMatcher.find()) {
            String itemJson = itemMatcher.group();
            String card = stringField(itemJson, "card");
            if (isBlank(card)) {
                continue;
            }
            items.add(new PlanItem(card, stringField(itemJson, "api"), stringField(itemJson, "timing")));
        }

        List<String> attackers = stringArrayField(responseJson, "attackers");
        List<String> defenders = stringArrayField(responseJson, "defenders");
        List<String> hold = stringArrayField(responseJson, "holdInteraction");

        return new UltronStrategicPlan(items, attackers, defenders, hold, extractRationale(responseJson));
    }

    UltronAdvisor.Decision choose(GameState gameState, List<SpellAbility> candidates, Player advisor, AiCardMemory memory) {
        if (candidates == null || candidates.isEmpty()) {
            return UltronAdvisor.Decision.noAdvice();
        }

        SpellAbility heldInteraction = chooseHeldInteraction(candidates, advisor, memory);
        if (heldInteraction != null && gameState == GameState.RESPONDING) {
            return UltronAdvisor.Decision.choose(heldInteraction, candidates.indexOf(heldInteraction),
                    rationaleOr("planned held interaction"));
        }

        Iterator<PlanItem> iterator = plannedActions.iterator();
        while (iterator.hasNext()) {
            PlanItem item = iterator.next();
            SpellAbility match = firstMatchingCandidate(item, candidates, advisor, memory);
            if (match != null) {
                iterator.remove();
                return UltronAdvisor.Decision.choose(match, candidates.indexOf(match),
                        rationaleOr("following strategic plan: " + item.card()));
            }
        }
        return UltronAdvisor.Decision.noAdvice();
    }

    boolean isEmpty() {
        return plannedActions.isEmpty() && attackers.isEmpty() && defenders.isEmpty() && holdInteraction.isEmpty();
    }

    String summary() {
        return "actions=" + names(plannedActions) + ", attackers=" + attackers
                + ", defenders=" + defenders + ", holdInteraction=" + holdInteraction;
    }

    public void filterAttackers(Combat combat) {
        if (combat == null || attackers.isEmpty()) {
            return;
        }
        for (Card attacker : combat.getAttackers().threadSafeIterable()) {
            if (!containsCardName(attackers, attacker.getName())) {
                combat.removeFromCombat(attacker);
            }
        }
    }

    private SpellAbility chooseHeldInteraction(List<SpellAbility> candidates, Player advisor, AiCardMemory memory) {
        for (String cardName : holdInteraction) {
            for (SpellAbility candidate : candidates) {
                if (cardName.equalsIgnoreCase(UltronGameStateSerializer.sourceName(candidate, advisor, memory))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static SpellAbility firstMatchingCandidate(PlanItem item, List<SpellAbility> candidates, Player advisor, AiCardMemory memory) {
        for (SpellAbility candidate : candidates) {
            if (!item.matches(candidate, advisor, memory)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private String rationaleOr(String fallback) {
        return isBlank(rationale) ? fallback : rationale;
    }

    private static String stringField(String json, String fieldName) {
        String needle = JsonSupport.quote(fieldName);
        int fieldIndex = json.indexOf(needle);
        if (fieldIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', fieldIndex + needle.length());
        if (colonIndex < 0) {
            return null;
        }
        int quoteIndex = json.indexOf('"', colonIndex + 1);
        if (quoteIndex < 0) {
            return null;
        }
        return JsonSupport.unquoteAt(json, quoteIndex);
    }

    private static List<String> stringArrayField(String json, String fieldName) {
        String needle = JsonSupport.quote(fieldName);
        int fieldIndex = json.indexOf(needle);
        if (fieldIndex < 0) {
            return new ArrayList<>();
        }
        int arrayStart = json.indexOf('[', fieldIndex + needle.length());
        if (arrayStart < 0) {
            return new ArrayList<>();
        }
        int arrayEnd = json.indexOf(']', arrayStart + 1);
        if (arrayEnd < 0) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        Matcher stringMatcher = JSON_STRING_PATTERN.matcher(json.substring(arrayStart + 1, arrayEnd));
        while (stringMatcher.find()) {
            String value = JsonSupport.unquoteAt(stringMatcher.group(), 0);
            if (!isBlank(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static boolean containsCardName(List<String> names, String cardName) {
        for (String name : names) {
            if (name.equalsIgnoreCase(cardName)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> names(List<PlanItem> items) {
        List<String> result = new ArrayList<>();
        for (PlanItem item : items) {
            result.add(item.api() == null || item.api().isBlank() ? item.card() : item.card() + "/" + item.api());
        }
        return result;
    }

    private static String extractRationale(String responseJson) {
        Matcher matcher = RATIONALE_PATTERN.matcher(responseJson);
        if (!matcher.find()) {
            return null;
        }
        return JsonSupport.unquoteAt(responseJson, matcher.end() - 1);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public enum GameState {
        MAIN_PHASE,
        RESPONDING,
        OTHER
    }

    private record PlanItem(String card, String api, String timing) {
        boolean matches(SpellAbility candidate, Player advisor, AiCardMemory memory) {
            if (!card.equalsIgnoreCase(UltronGameStateSerializer.sourceName(candidate, advisor, memory))) {
                return false;
            }
            if (isBlank(api)) {
                return true;
            }
            String candidateApi = UltronGameStateSerializer.apiName(candidate);
            return api.equalsIgnoreCase(candidateApi)
                    || normalize(api).equals(normalize(candidateApi));
        }

        private static String normalize(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }
    }
}
