package de.blockzeit.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Civil wall-clock target and marker share the same fixed 24-hour dial. */
@android.annotation.TargetApi(26)
public final class DayMath {
    private DayMath() { }
    public static final int SLOTS = 144;
    public static final class Day {
        public final long start, end;
        public final String key;
        public final int expected;
        public final double dialFraction;
        Day(long start, long end, String key, int expected, double fraction) {
            this.start = start; this.end = end; this.key = key;
            this.expected = expected; this.dialFraction = fraction;
        }
    }
    public static Day at(long epochSeconds, ZoneId zone) {
        ZonedDateTime local = Instant.ofEpochSecond(epochSeconds).atZone(zone);
        LocalDate date = local.toLocalDate();
        long start = date.atStartOfDay(zone).toEpochSecond();
        long end = date.plusDays(1).atStartOfDay(zone).toEpochSecond();
        int wallSeconds = local.toLocalTime().toSecondOfDay();
        int expected = wallSeconds / 600;
        double fraction = wallSeconds / 86400.0;
        return new Day(start, end, date + "@" + zone.getId(), expected, fraction);
    }
    public enum Slot { DARK, ORANGE, GREEN, BLUE }
    /** Ring tick strictly preceding the civil-time marker, including exact-tick and midnight wrap. */
    public static int previousDialSlot(double dialFraction) {
        int seconds = (int) Math.round(dialFraction * 86400);
        return Math.floorMod((seconds + 599) / 600 - 1, SLOTS);
    }
    /** Physical ticks mark completed intervals: block 1 at 00:10, block 144 at 00/24:00. */
    public static Slot dialSlot(int index, int actual, Day day, boolean known) {
        if (index < 0 || index >= SLOTS) throw new IllegalArgumentException("slot");
        return slot(index == 0 ? SLOTS - 1 : index - 1, actual, day.expected, known);
    }
    public static Slot slot(int index, int actual, int expected, boolean known) {
        if (index < 0 || index >= SLOTS) throw new IllegalArgumentException("slot");
        if (!known) return Slot.DARK;
        if (actual < 0 || expected < 0) throw new IllegalArgumentException("count");
        if (index < Math.min(actual, expected)) return Slot.ORANGE;
        if (index < actual) return Slot.GREEN;
        if (index < expected) return Slot.BLUE;
        return Slot.DARK;
    }
    public static String signed(int actual, int expected) {
        int delta = actual - expected;
        return (delta >= 0 ? "+" : "") + delta;
    }
}
