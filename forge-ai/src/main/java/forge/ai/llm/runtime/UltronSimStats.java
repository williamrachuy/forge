package forge.ai.llm.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Per-game Ultron decision stats for sim analysis.
 *
 * <p>Records one entry per call to {@code UltronRuntimeController.doChoose()}.
 * Lightweight enough to include inline in the per-game JSONL record.
 */
public final class UltronSimStats {

    public record Decision(
            int turn,
            String phase,              // MAIN | RESPOND | OTHER
            int life,
            int stackDepth,
            String role,               // UltronRuntimeRole at decision time
            String kind,               // CHOOSE | PASS | FALLBACK | NO_DECISION
            String chosen,             // card name; null unless CHOOSE
            int bestCandidateScore,    // best score computed (>0 for CHOOSE; non-zero for PASS tells us what was rejected)
            String scoreReason,        // reason string for CHOOSE; pass/threat reason for PASS
            int candidates,            // total candidates before pruning
            int pruned,                // candidates removed by pruner
            boolean avoidTappingOut,
            boolean reserveCounterspellMana
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("turn", turn);
            m.put("phase", phase);
            m.put("life", life);
            m.put("stackDepth", stackDepth);
            m.put("role", role);
            m.put("kind", kind);
            if (chosen != null) m.put("chosen", chosen);
            if (bestCandidateScore != 0) m.put("bestCandidateScore", bestCandidateScore);
            if (scoreReason != null && !scoreReason.isEmpty()) m.put("scoreReason", scoreReason);
            m.put("candidates", candidates);
            m.put("pruned", pruned);
            m.put("avoidTappingOut", avoidTappingOut);
            m.put("reserveCounterspellMana", reserveCounterspellMana);
            return m;
        }
    }

    /** Per-weight activation scores for use by {@link UltronAdaptiveLearner}. */
    public record WeightActivations(
            double removalActivation,
            double aggressionActivation,
            double pruneActivation
    ) {}

    private final List<Decision> decisions = new ArrayList<>();

    public void record(Decision d) {
        decisions.add(d);
    }

    /** All card names that were CHOOSE'd at least once this game. */
    public List<String> cardsPlayed() {
        return decisions.stream()
                .filter(d -> "CHOOSE".equals(d.kind()) && d.chosen() != null)
                .map(Decision::chosen)
                .collect(Collectors.toList());
    }

    /**
     * Compute per-weight activation scores from this game's decisions.
     *
     * <p>Activation represents how much a given weight category was exercised.
     * Range [0, 1]. Used by the adaptive learner to scale weight nudges.
     */
    public WeightActivations computeActivations() {
        int removalChoices = 0, mainChoices = 0, chooseTotal = 0;
        double prunedRateSum = 0;
        int prunedRateCount = 0;

        for (Decision d : decisions) {
            if ("CHOOSE".equals(d.kind())) {
                chooseTotal++;
                if (d.scoreReason() != null && d.scoreReason().contains("removal")) {
                    removalChoices++;
                }
                if ("MAIN".equals(d.phase())) {
                    mainChoices++;
                }
            }
            if (d.candidates() > 0) {
                prunedRateSum += (double) d.pruned() / d.candidates();
                prunedRateCount++;
            }
        }

        double removal    = chooseTotal > 0     ? (double) removalChoices / chooseTotal : 0;
        double aggression = decisions.size() > 0 ? (double) mainChoices / decisions.size() : 0;
        double pruneRate  = prunedRateCount > 0  ? prunedRateSum / prunedRateCount : 0;

        return new WeightActivations(removal, aggression, pruneRate);
    }

    public Map<String, Object> toMap() {
        int choose = 0, pass = 0, fallback = 0, noDecision = 0;
        int mainChoose = 0, respondChoose = 0, otherChoose = 0;
        long scoreSum = 0;
        int prunedSum = 0, candidateSum = 0;

        for (Decision d : decisions) {
            switch (d.kind()) {
                case "CHOOSE"      -> { choose++;      scoreSum += d.bestCandidateScore(); }
                case "PASS"        -> pass++;
                case "FALLBACK"    -> fallback++;
                case "NO_DECISION" -> noDecision++;
            }
            if ("CHOOSE".equals(d.kind())) {
                switch (d.phase()) {
                    case "MAIN"    -> mainChoose++;
                    case "RESPOND" -> respondChoose++;
                    default        -> otherChoose++;
                }
            }
            prunedSum += d.pruned();
            candidateSum += d.candidates();
        }

        int total = decisions.size();
        double meanScore    = choose > 0 ? (double) scoreSum / choose : 0.0;
        double fallbackRate = total  > 0 ? (double) (fallback + noDecision) / total : 0.0;
        double pruneRate    = candidateSum > 0 ? (double) prunedSum / candidateSum : 0.0;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDecisions", total);
        summary.put("chooseCount", choose);
        summary.put("passCount", pass);
        summary.put("fallbackCount", fallback);
        summary.put("noDecisionCount", noDecision);
        summary.put("fallbackRate", Math.round(fallbackRate * 1000.0) / 1000.0);
        summary.put("meanChoiceScore", Math.round(meanScore * 10.0) / 10.0);
        summary.put("meanPruneRate", Math.round(pruneRate * 1000.0) / 1000.0);
        summary.put("mainPhaseChoices", mainChoose);
        summary.put("respondChoices", respondChoose);
        summary.put("otherChoices", otherChoose);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("decisions", decisions.stream().map(Decision::toMap).toList());
        return result;
    }
}
