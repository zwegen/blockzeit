package de.blockzeit.core;

import java.util.Locale;

/** Network estimate with retained display and entry/tip-event admission, never age-driven polling. */
public final class Hashrate {
    public static final long FRESH_SECONDS = 60 * 60; // Diagnostic quality only, not a query/display interval.
    private Hashrate() { }

    /** Accept a JSON number only, never a quoted/missing value or a non-finite rate. */
    public static double number(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Missing/nonnumeric hashrate");
        double rate = ((Number) value).doubleValue();
        if (!Double.isFinite(rate) || rate <= 0) throw new IllegalArgumentException("Invalid hashrate");
        return rate;
    }
    public static boolean fresh(double rate, long checked, long now) {
        return Double.isFinite(rate) && rate > 0 && checked > 0 && checked <= now
                && now - checked <= FRESH_SECONDS;
    }
    public static String text(double rate, long checked, long now, Locale locale) {
        // Diagnostic freshness does not control querying or the lifetime of a validated display value.
        // A clock correction or failed event-driven attempt must not blank a valid network estimate.
        return Double.isFinite(rate) && rate > 0 && checked > 0
                ? String.format(locale, "%.0f EH/s", rate / 1e18) : "— EH/s";
    }
    /** Each entry/tip event permits one real attempt, including failures. Time alone never retries. */
    public static boolean due(long now, String event, String attemptedEvent, long retryAfter) {
        return now > 0 && now >= retryAfter && event != null && !event.isEmpty() && !event.equals(attemptedEvent);
    }
}
