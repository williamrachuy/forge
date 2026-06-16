package forge.ai.llm.runtime;

/** Classification of a spell or ability on the stack from Ultron's perspective. */
public enum UltronStackThreatType {
    NONE,
    LOW_VALUE,
    VALUE_ENGINE,
    REMOVAL_TARGETING_ULTRON,
    REMOVAL_TARGETING_KEY_PERMANENT,
    BOARD_WIPE,
    MASS_BOUNCE,
    EXTRA_TURN,
    TUTOR,
    COMBO_PIECE,
    MASS_REANIMATION,
    GRAVEYARD_EXPLOSION,
    LETHAL_DAMAGE,
    LETHAL_LIFE_LOSS,
    GAME_WINNING_EFFECT,
    COUNTER_WAR,
    UNKNOWN_HIGH_IMPACT
}
