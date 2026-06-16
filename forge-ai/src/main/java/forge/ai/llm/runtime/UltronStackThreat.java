package forge.ai.llm.runtime;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Classification and severity assessment of a spell or ability on the stack.
 * Produced by {@link UltronStackThreatAnalyzer}.
 *
 * <p>Severity scale 0–100:
 * <ul>
 *   <li>95–100 — Ultron loses or an opponent wins if this resolves</li>
 *   <li>80–94  — board wipe while ahead, extra turn, mass reanimation from leader</li>
 *   <li>50–79  — strong value engine, removal on good Ultron permanent</li>
 *   <li>below 50 — ramp, cantrip, small creature, weak-player value</li>
 * </ul>
 */
public final class UltronStackThreat {

    public static final UltronStackThreat NONE =
            new UltronStackThreat(UltronStackThreatType.NONE, 0, null, null, "no threat");

    public final UltronStackThreatType type;
    public final int severity;               // 0-100
    public final Player controller;
    public final SpellAbility ability;
    public final String reason;

    public UltronStackThreat(UltronStackThreatType type, int severity,
                              Player controller, SpellAbility ability, String reason) {
        this.type = type;
        this.severity = Math.max(0, Math.min(100, severity));
        this.controller = controller;
        this.ability = ability;
        this.reason = reason != null ? reason : "";
    }

    /** True if this threat is serious enough to justify spending interaction. */
    public boolean isActionable(int interactionThreshold) {
        return severity >= interactionThreshold;
    }

    @Override
    public String toString() {
        String ctrl = controller != null ? controller.getName() : "?";
        String card = (ability != null && ability.getHostCard() != null)
                ? ability.getHostCard().getName() : "?";
        return type + "(sev=" + severity + " caster=" + ctrl + " card=" + card + ")";
    }
}
