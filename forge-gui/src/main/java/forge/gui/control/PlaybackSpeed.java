package forge.gui.control;

import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

public enum PlaybackSpeed {
    SLOW(3),
    NORMAL(1),
    FAST(.1);

    private double modifier = 1;

    PlaybackSpeed(double modifier) {
        this.modifier = modifier;
    }

    public long applyModifier(long milliseconds) {
        if (this == FAST) {
            final double multiplier = getFastMultiplier();
            if (multiplier <= 0) {
                return 0;
            }
            return (long) (milliseconds / multiplier);
        }
        return (long) (this.modifier * milliseconds);
    }

    public String nextSpeedText() {
        switch(this) {
            case NORMAL:
                return getFastSpeedText();
            case FAST:
                return "1/3x speed";
            default:
                return "1x speed";
        }
    }

    public PlaybackSpeed nextSpeed() {
        switch(this) {
            case NORMAL:
                return PlaybackSpeed.FAST;
            case FAST:
                return PlaybackSpeed.SLOW;
            default:
                return PlaybackSpeed.NORMAL;
        }
    }

    private static String getFastSpeedText() {
        final double multiplier = getFastMultiplier();
        if (multiplier <= 0) {
            return "Full throttle";
        }
        return formatMultiplier(multiplier) + "x speed";
    }

    private static double getFastMultiplier() {
        try {
            final String value = FModel.getPreferences().getPref(FPref.MATCH_PLAYBACK_FAST_SPEED).trim();
            if ("Full throttle".equalsIgnoreCase(value)) {
                return 0;
            }
            return Math.max(0, Double.parseDouble(value));
        } catch (final Exception e) {
            return 10;
        }
    }

    private static String formatMultiplier(final double multiplier) {
        if (multiplier == Math.rint(multiplier)) {
            return Long.toString((long) multiplier);
        }
        return Double.toString(multiplier);
    }
}
