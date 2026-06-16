package forge.ai.llm.runtime;

/**
 * Represents mana that Ultron wants to hold back for interaction.
 * Used by {@link UltronManaReservationPolicy} and consulted by {@link UltronActionScorer}.
 */
public final class UltronManaReservation {

    public static final UltronManaReservation NONE =
            new UltronManaReservation(0, 0, 0, 0, 0, 0, "no reservation");

    public final int generic;
    public final int white;
    public final int blue;
    public final int black;
    public final int red;
    public final int green;
    public final String reason;

    public UltronManaReservation(int generic, int white, int blue, int black,
                                  int red, int green, String reason) {
        this.generic = generic;
        this.white = white;
        this.blue = blue;
        this.black = black;
        this.red = red;
        this.green = green;
        this.reason = reason != null ? reason : "";
    }

    /** Total colored mana reserved. */
    public int totalColored() {
        return white + blue + black + red + green;
    }

    /** Total mana reserved (colored + generic). */
    public int total() {
        return generic + totalColored();
    }

    @Override
    public String toString() {
        return "ManaReservation(generic=" + generic + " WUBRG=" + white + "/" + blue + "/"
                + black + "/" + red + "/" + green + " reason=" + reason + ")";
    }
}
