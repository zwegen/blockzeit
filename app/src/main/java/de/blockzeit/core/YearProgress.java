package de.blockzeit.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Elapsed fraction of the current device-local calendar year, independent of block frames. */
public final class YearProgress {
    private YearProgress() { }
    public static double fraction(long epochSeconds, ZoneId zone) {
        LocalDate local = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate();
        LocalDate first = local.withDayOfYear(1);
        long start = first.atStartOfDay(zone).toEpochSecond();
        long end = first.plusYears(1).atStartOfDay(zone).toEpochSecond();
        return (epochSeconds - start) / (double) (end - start);
    }
}
