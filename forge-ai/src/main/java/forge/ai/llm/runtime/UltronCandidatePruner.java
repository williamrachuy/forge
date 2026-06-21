package forge.ai.llm.runtime;

import forge.ai.llm.UltronConfig;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.List;

/**
 * Trims the full Forge candidate list to the top N for Ultron runtime scoring.
 * Candidates are pre-sorted by the Forge evaluator; this class applies Ultron-specific
 * quick filters before handing to {@link UltronActionScorer}.
 */
public final class UltronCandidatePruner {

    private UltronCandidatePruner() {}

    /**
     * Prune candidates to at most {@code UltronConfig.maxCandidates()} entries.
     * Applies quick filters to eliminate obviously irrelevant candidates first.
     *
     * @param all pre-sorted Forge candidates (best first per saEvaluator)
     * @param ctx current decision context
     * @return pruned list
     */
    public static List<SpellAbility> prune(List<SpellAbility> all, UltronDecisionContext ctx) {
        // pruneAggression > 1 → consider fewer candidates (faster, less thorough)
        // pruneAggression < 1 → consider more candidates (slower, more thorough)
        int max = Math.max(5,
                (int)(UltronConfig.maxCandidates() / UltronWeights.get(UltronWeights.PRUNE_AGGRESSION)));
        int targetSize = Math.min(max, all.size());
        List<SpellAbility> result = new ArrayList<>(targetSize);
        for (SpellAbility sa : all) {
            if (result.size() >= max) break;
            if (!shouldSkip(sa, ctx)) result.add(sa);
        }

        // If filtering removed too many from a crowded list, top up from original ordering.
        // Small lists should remain pruned; otherwise the policy is effectively bypassed.
        if (all.size() > max && result.size() < max / 2) {
            for (SpellAbility sa : all) {
                if (result.size() >= max) break;
                if (!result.contains(sa)) result.add(sa);
            }
        }

        return result;
    }

    /**
     * Quick pre-filter: skip obviously bad candidates.
     */
    private static boolean shouldSkip(SpellAbility sa, UltronDecisionContext ctx) {
        if (sa.getHostCard() == null) return true;

        // Skip very low-CMC filler when ahead and could hold for better play
        // (but don't skip lands, activations, or 0-cost spells — those may be important)
        UltronTurnIntent intent = ctx.intent;
        int cmc = sa.getHostCard().getCMC();

        if (intent.role == UltronRuntimeRole.CONTROL || intent.role == UltronRuntimeRole.AHEAD) {
            // Skip very marginal plays — 1-drop creatures in late game when ahead
            if (cmc == 1 && sa.isSpell() && sa.getHostCard().isCreature()
                    && ctx.table.ultronBoardValue > 40) {
                return true;
            }
        }

        return false;
    }
}
