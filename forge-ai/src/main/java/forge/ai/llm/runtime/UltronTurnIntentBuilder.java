package forge.ai.llm.runtime;

import java.util.Set;

/**
 * Builds {@link UltronTurnIntent} from a {@link UltronTableThreatSummary}.
 * No LLM, no HTTP, no deep simulation — must complete in microseconds.
 */
public final class UltronTurnIntentBuilder {

    private UltronTurnIntentBuilder() {}

    /** Derive intent from the current table summary, turn number, and optional plan hints. */
    public static UltronTurnIntent build(UltronTableThreatSummary table, int turn,
                                          Set<String> holdCardNames, Set<String> protectCardNames) {
        UltronTurnIntent.Builder b = new UltronTurnIntent.Builder();
        b.turn = turn;

        // Primary threat: immediate danger first, then leader
        if (table.mostDangerousToUltron != null && table.mostDangerousToUltron.canLikelyKillUltronSoon) {
            b.primaryThreat = table.mostDangerousToUltron.player;
        } else if (table.leader != null) {
            b.primaryThreat = table.leader.player;
        } else if (table.mostDangerousToUltron != null) {
            b.primaryThreat = table.mostDangerousToUltron.player;
        }

        // Preferred attack target: killable player first, then monarch (card-draw denial), then leader
        if (table.mostVulnerable != null && table.mostVulnerable.life <= 13) {
            b.preferredAttackTarget = table.mostVulnerable.player;
        } else if (table.monarchHolder != null && !table.ultronHasMonarch) {
            b.preferredAttackTarget = table.monarchHolder;
        } else if (table.leader != null) {
            b.preferredAttackTarget = table.leader.player;
        }

        // Role determination
        boolean anyComboThreat = table.opponents.stream().anyMatch(p -> p.comboThreat >= 50);
        if (table.ultronInDanger && table.ultronIsBehind) {
            b.role = UltronRuntimeRole.DESPERATE;
        } else if (table.ultronInDanger) {
            b.role = UltronRuntimeRole.STABILIZING;
        } else if (anyComboThreat) {
            b.role = UltronRuntimeRole.COMBO_DEFENSE;
        } else if (table.ultronIsAhead && table.leader != null) {
            b.role = UltronRuntimeRole.PRESSURING;
        } else if (table.ultronIsAhead) {
            b.role = UltronRuntimeRole.CONTROL;
        } else if (table.ultronIsBehind) {
            b.role = UltronRuntimeRole.BEHIND;
        } else {
            b.role = UltronRuntimeRole.AHEAD;
        }

        // Apply role-specific intent settings
        switch (b.role) {
            case CONTROL -> {
                // Ultron is the board leader — press the advantage, look for eliminations
                b.interactionThreshold    = 50;
                b.counterspellThreshold   = 65;
                b.removalThreshold        = 55;
                b.reserveCounterspellMana = table.ultronHasCounterspell;
                b.reserveRemovalMana      = false;
                b.avoidTappingOut         = false;
                b.lookForLethal           = true;
                b.holdBoardWipe           = false;
            }
            case AHEAD -> {
                // Neutral: no clear leader, no clear threat — develop and pressure
                b.interactionThreshold    = 55;
                b.counterspellThreshold   = 70;
                b.removalThreshold        = 65;
                b.reserveCounterspellMana = table.ultronHasCounterspell;
                b.reserveRemovalMana      = false;
                b.avoidTappingOut         = false;
                b.holdBoardWipe           = false;
            }
            case PRESSURING -> {
                b.interactionThreshold    = 55;
                b.counterspellThreshold   = 70;
                b.removalThreshold        = 60;
                b.reserveCounterspellMana = true;
                b.reserveRemovalMana      = false;
                b.avoidTappingOut         = false;
                b.lookForLethal           = true;
            }
            case STABILIZING -> {
                b.interactionThreshold    = 60;
                b.counterspellThreshold   = 75;
                b.removalThreshold        = 65;
                b.reserveCounterspellMana = true;
                b.reserveRemovalMana      = false;
                b.avoidTappingOut         = false;
                b.holdBoardWipe           = false;
            }
            case BEHIND -> {
                b.interactionThreshold    = 75;
                b.counterspellThreshold   = 85;
                b.removalThreshold        = 80;
                b.reserveCounterspellMana = false;
                b.reserveRemovalMana      = false;
                b.avoidTappingOut         = false;
                b.holdBoardWipe           = true;
            }
            case COMBO_DEFENSE -> {
                b.interactionThreshold    = 45;
                b.counterspellThreshold   = 55;
                b.removalThreshold        = 55;
                b.reserveCounterspellMana = true;
                b.reserveRemovalMana      = true;
                b.avoidTappingOut         = true;
            }
            case DESPERATE -> {
                b.interactionThreshold    = 90;
                b.counterspellThreshold   = 90;
                b.removalThreshold        = 90;
                b.reserveCounterspellMana = false;
                b.reserveRemovalMana      = false;
                b.avoidTappingOut         = false;
                b.lookForLethal           = true;
            }
        }

        // Escalate to PRESSURING when we can kill a player (elimination priority)
        if (b.role != UltronRuntimeRole.DESPERATE && b.role != UltronRuntimeRole.STABILIZING) {
            if (table.mostVulnerable != null && table.mostVulnerable.life <= 8
                    && table.ultronBoardValue > 0) {
                b.role = UltronRuntimeRole.PRESSURING;
                b.preferredAttackTarget = table.mostVulnerable.player;
                b.lookForLethal = true;
                b.avoidTappingOut = false;
                b.reserveCounterspellMana = false;
            }
        }

        // Late game: go all-in — mana reserves are irrelevant when the game must end
        if (turn >= 12) {
            b.avoidTappingOut = false;
            b.reserveCounterspellMana = false;
            b.reserveRemovalMana = false;
            b.lookForLethal = true;
        }

        // Don't hold mana for a counterspell we don't actually have in hand.
        if (b.reserveCounterspellMana && !table.ultronHasCounterspell) {
            b.reserveCounterspellMana = false;
        }

        b.preferMain2CreatureDeployment = b.avoidTappingOut || b.reserveCounterspellMana;
        b.holdCardNames    = (holdCardNames != null && !holdCardNames.isEmpty()) ? holdCardNames : Set.of();
        b.protectCardNames = (protectCardNames != null && !protectCardNames.isEmpty()) ? protectCardNames : Set.of();
        b.reason = buildReason(b, table);

        return new UltronTurnIntent(b);
    }

    /** Derive intent with no plan hints (backward-compatible overload). */
    public static UltronTurnIntent build(UltronTableThreatSummary table, int turn) {
        return build(table, turn, Set.of(), Set.of());
    }

    private static String buildReason(UltronTurnIntent.Builder b, UltronTableThreatSummary table) {
        StringBuilder sb = new StringBuilder("role=").append(b.role);
        if (table.leader != null) sb.append(" leader=").append(table.leader.player.getName());
        if (b.primaryThreat != null) sb.append(" threat=").append(b.primaryThreat.getName());
        sb.append(" ultronBV=").append(table.ultronBoardValue);
        if (table.ultronInDanger)  sb.append(" IN_DANGER");
        if (table.ultronIsAhead)   sb.append(" AHEAD");
        if (table.ultronIsBehind)  sb.append(" BEHIND");
        return sb.toString();
    }
}
