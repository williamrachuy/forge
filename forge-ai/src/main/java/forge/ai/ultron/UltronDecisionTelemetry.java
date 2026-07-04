package forge.ai.ultron;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per-controller-instance decision coverage telemetry for {@link UltronPlayerController}.
 *
 * <p>Phase 1 (plumbing only, see FORGE_TRACKER TICKET-V3-102): {@code UltronPlayerController}
 * owns the full ~121-method decision surface, but does not yet make any Ultron-specific
 * choices — every override simply times and records a call to {@code super}, then delegates.
 * So every decision recorded here is {@code answeredBy=inherited} today. That 0%-Ultron-authored
 * baseline is the correct starting point for Phase 2/3 to measure improvement against; a future
 * phase will start recording {@code answeredBy=ultron} once real decision logic exists.
 *
 * <p>One instance per {@code UltronPlayerController}, i.e. per (game, player) pair — no static
 * shared state, so parallel sim workers and repeated single-JVM games never leak counts across
 * games (each game constructs a fresh controller via {@code LobbyPlayerAi.createControllerFor}).
 *
 * <p>Kept intentionally cheap for hot loops: recording is a handful of counter increments with
 * no string formatting or allocation. Per-decision human-readable logging (method name +
 * elapsed ms) only happens when {@code verboseLogging} is enabled, guarded so the cost of
 * building the message is never paid when logging is off.
 */
public final class UltronDecisionTelemetry {

    /** Set true (e.g. via a debug flag) to also print one line per decision. Off by default — hot loop. */
    private final boolean verboseLogging;

    private final AtomicLong totalDecisions = new AtomicLong();
    private final AtomicLong ultronAnsweredCount = new AtomicLong();
    private final AtomicLong inheritedAnsweredCount = new AtomicLong();
    private final AtomicLong totalElapsedNanos = new AtomicLong();

    /** Per-method call counts, keyed by method name — cheap map since call sites are a fixed ~114-entry set. */
    private final Map<String, AtomicLongArray> perMethodCounts = new java.util.concurrent.ConcurrentHashMap<>();
    // index 0 = count, index 1 = elapsedNanos (avoids boxing a small record per method)
    private static final int IDX_COUNT = 0;
    private static final int IDX_NANOS = 1;

    /**
     * P2.5 (TICKET-V3-204) — most-recent per-method decision detail (e.g. candidate count, chosen
     * simulation score) for methods where that's cheaply available. Deliberately only keeps the
     * latest call's detail, not a history — this is a coverage/debugging aid for per-game JSONL
     * summaries and tests, not a full decision log (that's {@code UltronThreatModel}/future
     * per-decision featurizer territory per the plan's §5 Telemetry section).
     */
    private final Map<String, Map<String, Object>> lastDetailByMethod = new java.util.concurrent.ConcurrentHashMap<>();

    public UltronDecisionTelemetry() {
        this(false);
    }

    public UltronDecisionTelemetry(boolean verboseLogging) {
        this.verboseLogging = verboseLogging;
    }

    /** Record a decision that fell through to inherited (stock AiController/PlayerControllerAi) behavior. */
    public void record(String methodName, long elapsedNanos) {
        record(methodName, false, elapsedNanos);
    }

    /**
     * Record a decision.
     *
     * @param methodName   the overridden {@code PlayerController} method name
     * @param answeredByUltron true if Ultron-specific logic produced the answer; false if it fell
     *                         through to inherited default-AI behavior (always false in Phase 1)
     * @param elapsedNanos wall time spent in the call, including the delegated super call
     */
    public void record(String methodName, boolean answeredByUltron, long elapsedNanos) {
        totalDecisions.incrementAndGet();
        totalElapsedNanos.addAndGet(elapsedNanos);
        if (answeredByUltron) {
            ultronAnsweredCount.incrementAndGet();
        } else {
            inheritedAnsweredCount.incrementAndGet();
        }
        AtomicLongArray slot = perMethodCounts.computeIfAbsent(methodName, k -> new AtomicLongArray(2));
        slot.incrementAndGet(IDX_COUNT);
        slot.addAndGet(IDX_NANOS, elapsedNanos);

        if (verboseLogging) {
            logVerbose(methodName, answeredByUltron, elapsedNanos);
        }
    }

    private void logVerbose(String methodName, boolean answeredByUltron, long elapsedNanos) {
        System.out.println("[ULTRON-DECISION] " + methodName
                + " answeredBy=" + (answeredByUltron ? "ultron" : "inherited")
                + " ms=" + (elapsedNanos / 1_000_000.0));
    }

    /** Coverage summary for embedding in per-game JSONL records (see SimulateStats.java). */
    public Map<String, Object> toMap() {
        long total = totalDecisions.get();
        long ultronCount = ultronAnsweredCount.get();
        long inheritedCount = inheritedAnsweredCount.get();
        double coverageRatio = total > 0 ? (double) ultronCount / total : 0.0;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalDecisions", total);
        summary.put("answeredByUltron", ultronCount);
        summary.put("answeredByInherited", inheritedCount);
        summary.put("coverageRatio", Math.round(coverageRatio * 1000.0) / 1000.0);
        summary.put("totalElapsedMs", Math.round(totalElapsedNanos.get() / 1_000_000.0 * 100.0) / 100.0);

        Map<String, Object> perMethod = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLongArray> e : perMethodCounts.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", e.getValue().get(IDX_COUNT));
            m.put("elapsedMs", Math.round(e.getValue().get(IDX_NANOS) / 1_000_000.0 * 100.0) / 100.0);
            Map<String, Object> detail = lastDetailByMethod.get(e.getKey());
            if (detail != null) {
                m.put("lastDetail", detail);
            }
            perMethod.put(e.getKey(), m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("perMethod", perMethod);
        return result;
    }

    /** Records the latest cheaply-available decision detail (e.g. candidate count/score) for a method. */
    public void recordDetail(String methodName, Map<String, Object> details) {
        lastDetailByMethod.put(methodName, details);
    }

    /** The most recently recorded detail map for a method, or null if none was ever recorded. */
    public Map<String, Object> getLastDetail(String methodName) {
        return lastDetailByMethod.get(methodName);
    }

    public long getTotalDecisions() {
        return totalDecisions.get();
    }

    public long getUltronAnsweredCount() {
        return ultronAnsweredCount.get();
    }
}
