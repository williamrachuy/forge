package forge.ai.llm.runtime;

/**
 * Composite score for an Ultron action candidate.
 * Higher is better. Comparable so candidates can be sorted.
 */
public final class UltronScore implements Comparable<UltronScore> {

    public static final UltronScore ZERO = new UltronScore(0, 0, 0, 0, 0, 0, 0, 0, 0, "zero");

    public final int value;
    public final int ultronBoard;    // board presence gained
    public final int ultronHand;     // card advantage component
    public final int ultronLife;     // life delta component
    public final int ultronDefense;  // defensive value
    public final int dangerToUltron; // danger increase penalty (negative contribution)
    public final int leaderThreat;   // how much this reduces the leader's advantage
    public final int comboThreat;    // how much this reduces combo threat
    public final int tableBalance;   // how balanced the table remains (fairness heuristic)
    public final String reason;

    public UltronScore(int value, int ultronBoard, int ultronHand, int ultronLife,
                        int ultronDefense, int dangerToUltron, int leaderThreat,
                        int comboThreat, int tableBalance, String reason) {
        this.value = value;
        this.ultronBoard = ultronBoard;
        this.ultronHand = ultronHand;
        this.ultronLife = ultronLife;
        this.ultronDefense = ultronDefense;
        this.dangerToUltron = dangerToUltron;
        this.leaderThreat = leaderThreat;
        this.comboThreat = comboThreat;
        this.tableBalance = tableBalance;
        this.reason = reason != null ? reason : "";
    }

    @Override
    public int compareTo(UltronScore other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "Score(" + value + " board=" + ultronBoard + " defense=" + ultronDefense
                + " leaderThreat=" + leaderThreat + " reason=" + reason + ")";
    }
}
