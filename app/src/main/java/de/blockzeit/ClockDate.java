package de.blockzeit;

import android.text.format.DateFormat;
import android.content.Context;
import java.util.Locale;

/** Native Clock date/time patterns and alarm ink measurement share regional formatting. */
final class ClockDate {
    private ClockDate() { }
    static String pattern() {
        // A skeleton preserves locale order/punctuation; MMM requests an abbreviated month, not a substring.
        return DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM");
    }
    static String timePattern(boolean twentyFourHour) {
        return DateFormat.getBestDateTimePattern(Locale.getDefault(), twentyFourHour ? "Hm" : "hma");
    }
    static String numericPattern() {
        return withoutDayPeriod(timePattern(false));
    }
    static String withoutDayPeriod(String pattern) {
        StringBuilder number = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '\'') {
                number.append(ch);
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'') number.append(pattern.charAt(++i));
                else quoted = !quoted;
            } else if (quoted || ch != 'a') number.append(ch);
        }
        return number.toString().replaceAll("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$", "");
    }
    static String alarm(Context context, long millis) {
        return sample(timePattern(DateFormat.is24HourFormat(context)), millis);
    }
    static String sample(String pattern, long millis) {
        return DateFormat.format(pattern, millis).toString();
    }
}
