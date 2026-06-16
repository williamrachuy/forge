package forge.ai.llm.runtime;

/** High-level strategic role Ultron adopts for the current turn. */
public enum UltronRuntimeRole {
    /** Ultron has the best board; protect the lead. */
    AHEAD,
    /** Ultron is losing ground; stabilize. */
    BEHIND,
    /** Ultron needs to reset or stop bleeding. */
    STABILIZING,
    /** Ultron can push for a kill or go wide. */
    PRESSURING,
    /** Ultron is in a controlling position, holding interaction. */
    CONTROL,
    /** A combo player is active; hold counters for key pieces. */
    COMBO_DEFENSE,
    /** Ultron is near death; all-in survival or high-risk lethal lines acceptable. */
    DESPERATE
}
