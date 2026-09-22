package forge.gamemodes.net;

import forge.card.CardStateName;
import forge.game.GameType;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.trackable.TrackableProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A short hash of the enums whose names or ordinals travel over the wire. Delta packets key
 * properties by {@link TrackableProperty} name, lobby state carries {@link GameType} names, and
 * checksums / card-state ids encode {@link ZoneType}, {@link PhaseType} and {@link CardStateName}
 * by ordinal - so two builds can only play together when these match exactly.
 * <p>
 * Unlike {@code BuildInfo.getVersionString()}, which carries a per-build counter, the fingerprint
 * is identical for any two builds of the same code, whichever machine built them.
 */
public final class WireFingerprint {
    private static final String VALUE = compute();

    private WireFingerprint() {
    }

    public static String get() {
        return VALUE;
    }

    private static String compute() {
        final StringBuilder sb = new StringBuilder();
        append(sb, TrackableProperty.values());
        append(sb, GameType.values());
        append(sb, ZoneType.values());
        append(sb, PhaseType.values());
        append(sb, CardStateName.values());
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // mandated on every JVM
        }
    }

    private static void append(final StringBuilder sb, final Enum<?>[] values) {
        for (final Enum<?> e : values) {
            sb.append(e.name()).append(',');
        }
        sb.append('|');
    }
}
