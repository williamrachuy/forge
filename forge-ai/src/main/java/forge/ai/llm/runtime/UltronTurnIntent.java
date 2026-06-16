package forge.ai.llm.runtime;

import forge.game.player.Player;

import java.util.Set;

/**
 * High-level tactical intent for Ultron during the current turn.
 * Built once per turn (or after significant board events) by {@link UltronTurnIntentBuilder}.
 * Reused across many priority passes — never block on LLM to produce this.
 */
public final class UltronTurnIntent {

    public final int turn;
    public final UltronRuntimeRole role;
    public final Player primaryThreat;           // opponent posing the greatest danger
    public final Player preferredAttackTarget;
    public final int interactionThreshold;       // min severity to react to stack threats
    public final int counterspellThreshold;      // min severity to spend a counterspell
    public final int removalThreshold;           // min severity to spend removal
    public final boolean reserveCounterspellMana;
    public final boolean reserveRemovalMana;
    public final boolean reserveProtectionMana;
    public final boolean avoidTappingOut;
    public final boolean lookForLethal;
    public final boolean holdBoardWipe;
    public final boolean preferMain2CreatureDeployment;
    public final Set<String> holdCardNames;      // card names to hold from LLM plan hints
    public final Set<String> protectCardNames;   // card names to protect from LLM plan hints
    public final String reason;                  // logged explanation

    UltronTurnIntent(Builder b) {
        turn = b.turn;
        role = b.role;
        primaryThreat = b.primaryThreat;
        preferredAttackTarget = b.preferredAttackTarget;
        interactionThreshold = b.interactionThreshold;
        counterspellThreshold = b.counterspellThreshold;
        removalThreshold = b.removalThreshold;
        reserveCounterspellMana = b.reserveCounterspellMana;
        reserveRemovalMana = b.reserveRemovalMana;
        reserveProtectionMana = b.reserveProtectionMana;
        avoidTappingOut = b.avoidTappingOut;
        lookForLethal = b.lookForLethal;
        holdBoardWipe = b.holdBoardWipe;
        preferMain2CreatureDeployment = b.preferMain2CreatureDeployment;
        holdCardNames = b.holdCardNames != null ? Set.copyOf(b.holdCardNames) : Set.of();
        protectCardNames = b.protectCardNames != null ? Set.copyOf(b.protectCardNames) : Set.of();
        reason = b.reason != null ? b.reason : "";
    }

    static final class Builder {
        int turn;
        UltronRuntimeRole role = UltronRuntimeRole.AHEAD;
        Player primaryThreat;
        Player preferredAttackTarget;
        int interactionThreshold = 50;
        int counterspellThreshold = 70;
        int removalThreshold = 60;
        boolean reserveCounterspellMana;
        boolean reserveRemovalMana;
        boolean reserveProtectionMana;
        boolean avoidTappingOut;
        boolean lookForLethal;
        boolean holdBoardWipe;
        boolean preferMain2CreatureDeployment;
        Set<String> holdCardNames;
        Set<String> protectCardNames;
        String reason;
    }
}
