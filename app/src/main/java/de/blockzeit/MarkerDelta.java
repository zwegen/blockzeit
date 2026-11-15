package de.blockzeit;

import de.blockzeit.core.DayMath;

/** Small horizontal label, directly inward on the displayed marker's radial axis. */
final class MarkerDelta {
    private MarkerDelta() { }
    static String text(int actual, DayMath.Day displayedDay, boolean known) {
        if (!known) return "";
        long delta = (long) actual - displayedDay.expected;
        return delta > 0 ? "+" + delta : delta < 0 ? "−" + -delta : "0";
    }
    static float[] center(double fraction, float markerInner, float inkWidth, float inkHeight, float gap) {
        double angle = Math.toRadians(fraction * 360 - 90);
        double x = Math.cos(angle), y = Math.sin(angle);
        // Fixed 1dp radial edge gap. Text stays horizontal; natural ink size is not a collision policy.
        double radius = markerInner - gap - (Math.abs(x) * inkWidth + Math.abs(y) * inkHeight) / 2;
        return new float[]{500 + (float) (x * radius), 500 + (float) (y * radius)};
    }
}
