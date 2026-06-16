package forge.ai.llm.runtime;

import forge.game.spellability.SpellAbility;

/**
 * Result of a UltronRuntimeController evaluation.
 *
 * <ul>
 *   <li>{@link Kind#CHOOSE} — play this SpellAbility</li>
 *   <li>{@link Kind#PASS} — explicitly pass priority, return null to Forge</li>
 *   <li>{@link Kind#NO_DECISION} — runtime has no opinion; fall back to Forge default</li>
 *   <li>{@link Kind#FALLBACK} — runtime defers to existing Forge candidate ordering</li>
 * </ul>
 */
public final class UltronRuntimeDecision {

    public enum Kind {
        CHOOSE,
        PASS,
        NO_DECISION,
        FALLBACK
    }

    private final Kind kind;
    private final SpellAbility spellAbility;
    private final String reason;

    private UltronRuntimeDecision(Kind kind, SpellAbility sa, String reason) {
        this.kind = kind;
        this.spellAbility = sa;
        this.reason = reason != null ? reason : "";
    }

    // -----------------------------------------------------------------------
    // Static factories
    // -----------------------------------------------------------------------

    public static UltronRuntimeDecision choose(SpellAbility sa, String reason) {
        if (sa == null) return pass("choose called with null sa — converting to pass");
        return new UltronRuntimeDecision(Kind.CHOOSE, sa, reason);
    }

    public static UltronRuntimeDecision pass(String reason) {
        return new UltronRuntimeDecision(Kind.PASS, null, reason);
    }

    public static UltronRuntimeDecision noDecision(String reason) {
        return new UltronRuntimeDecision(Kind.NO_DECISION, null, reason);
    }

    public static UltronRuntimeDecision fallback(String reason) {
        return new UltronRuntimeDecision(Kind.FALLBACK, null, reason);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public boolean hasChoice()      { return kind == Kind.CHOOSE; }
    public boolean isPass()         { return kind == Kind.PASS; }
    public boolean shouldFallback() { return kind == Kind.FALLBACK || kind == Kind.NO_DECISION; }

    public Kind getKind()                { return kind; }
    public SpellAbility getSpellAbility(){ return spellAbility; }
    public String getReason()           { return reason; }

    @Override
    public String toString() {
        if (kind == Kind.CHOOSE && spellAbility != null) {
            return "CHOOSE[" + spellAbility.getHostCard().getName() + "] " + reason;
        }
        return kind.name() + " " + reason;
    }
}
