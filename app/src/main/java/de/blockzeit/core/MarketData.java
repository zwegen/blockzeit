package de.blockzeit.core;

import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Optional currency/fee/mempool samples and shared request gates. Failures never manufacture a value. */
public final class MarketData {
    public static final long PRICE_SECONDS = 5 * 60;
    public static final long SAMPLE_SECONDS = 2 * 60;
    private MarketData() { }
    public static double price(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Missing/nonnumeric price");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < .5) throw new IllegalArgumentException("Invalid price");
        return number;
    }
    public static String priceText(Object value) {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.GERMANY);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(price(value));
    }
    /** Unknown/missing preferences retain the pre-currency USD behavior on upgrade. */
    public static String currency(Object stored) { return "EUR".equals(stored) ? "EUR" : "USD"; }
    public static String symbol(String currency) { return "EUR".equals(currency) ? "€" : "$"; }
    /** Official /api/mempool vsize is total virtual BYTES, not usage/weight/backlog. */
    public static long vsize(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Missing/nonnumeric vsize");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < 0 || number >= 0x1p63 || number != Math.rint(number))
            throw new IllegalArgumentException("Invalid vsize");
        return ((Number) value).longValue();
    }
    public static String mempoolText(Object value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.GERMANY);
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(java.math.BigDecimal.valueOf(vsize(value), 6)) + " vMB";
    }
    private static int fee(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Missing/nonnumeric fee");
        double number = ((Number) value).doubleValue();
        // /fees/recommended returns whole sat/vB; do not turn malformed/fractional values into zero.
        if (!Double.isFinite(number) || number < 1 || number > Integer.MAX_VALUE || number != Math.rint(number))
            throw new IllegalArgumentException("Invalid recommended fee");
        return (int) number;
    }
    public static String feesText(Object low, Object medium, Object high) {
        int l = fee(low), m = fee(medium), h = fee(high);
        if (l > m || m > h) throw new IllegalArgumentException("Unordered recommended fees");
        return l + "/" + m + "/" + h + " sat/vB";
    }
    /** Persist ATTEMPTS before I/O, including failures; a clock rollback cannot bypass either gate. */
    public static boolean due(boolean price, long now, long attempted, long retryAfter) {
        if (now < retryAfter || now <= 0) return false;
        if (attempted <= 0) return true;
        if (now < attempted) return false;
        return now - attempted >= (price ? PRICE_SECONDS : SAMPLE_SECONDS);
    }
}
