package de.blockzeit.core;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** HTTP Retry-After delta-seconds/date parsing; never shorten a server-requested delay. */
@android.annotation.TargetApi(26)
public final class RetryAfter {
    private RetryAfter() { }
    public static long until(String header, long now) {
        long minimum = add(now, 300);
        if (header == null) return add(now, 3600);
        String value = header.trim();
        if (value.matches("[0-9]+")) {
            try { return Math.max(minimum, add(now, Long.parseLong(value))); }
            catch (NumberFormatException e) { return Long.MAX_VALUE; }
        }
        try {
            long date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
            return Math.max(minimum, date);
        } catch (DateTimeParseException e) { return add(now, 3600); }
    }
    private static long add(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException e) { return Long.MAX_VALUE; }
    }
}
