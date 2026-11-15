package de.blockzeit;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.icu.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import de.blockzeit.core.CalendarAgenda;

/** Same condensed face as the existing three center positions; no global typography changes. */
final class CalendarText {
    static final class Fit {
        final String text; final float pixels;
        Fit(String text, float pixels) { this.text = text; this.pixels = pixels; }
    }
    static Fit fit(String value, float width, float maximum, float minimum, boolean bold) {
        return fit(value, width, maximum, minimum, bold, Float.POSITIVE_INFINITY);
    }
    static Fit fit(String value, float width, float maximum, float minimum, boolean bold, float height) {
        String line = CalendarAgenda.singleLine(value);
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-condensed", bold ? Typeface.BOLD : Typeface.NORMAL));
        float low = minimum, high = Math.max(minimum, maximum);
        paint.setTextSize(high);
        if (!fits(paint, line, width, height)) {
            for (int i = 0; i < 18; i++) {
                float candidate = (low + high) / 2; paint.setTextSize(candidate);
                if (fits(paint, line, width, height)) low = candidate; else high = candidate;
            }
            paint.setTextSize(low);
        }
        // Actual final hinted pixel metrics, not character counts. Never shrink below footer size.
        String text = line;
        if (!fits(paint, line, width, height)) {
            final String ellipsis = "…";
            text = "";
            if (fits(paint, ellipsis, width, height)) {
                // Grapheme boundaries preserve surrogate pairs, combining marks and joined emoji.
                BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
                iterator.setText(line);
                List<Integer> boundaries = new ArrayList<>();
                for (int edge = iterator.first(); edge != BreakIterator.DONE; edge = iterator.next()) boundaries.add(edge);
                int left = 0, right = boundaries.size() - 1, best = 0;
                while (left <= right) {
                    int middle = (left + right) >>> 1;
                    String candidate = line.substring(0, boundaries.get(middle)) + ellipsis;
                    if (fits(paint, candidate, width, height)) { best = boundaries.get(middle); left = middle + 1; }
                    else right = middle - 1;
                }
                text = line.substring(0, best) + ellipsis;
            }
        }
        return new Fit(text, paint.getTextSize());
    }
    /** Only surviving date characters are blue; an ellipsis and the event title stay white. */
    static CharSequence following(Fit fitted, String date) {
        SpannableString styled = new SpannableString(fitted.text);
        int dateEnd = 0;
        while (dateEnd < date.length() && dateEnd < fitted.text.length()
                && date.charAt(dateEnd) == fitted.text.charAt(dateEnd)) dateEnd++;
        if (dateEnd > 0) styled.setSpan(new ForegroundColorSpan(RingRenderer.BLUE), 0, dateEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }
    private static boolean fits(Paint paint, String text, float width, float height) {
        Rect ink = new Rect(); paint.getTextBounds(text, 0, text.length(), ink);
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        return measured(paint, text) <= width && Math.max(ink.height(), metrics.descent - metrics.ascent) <= height;
    }
    private static float measured(Paint paint, String text) {
        Rect ink = new Rect(); paint.getTextBounds(text, 0, text.length(), ink);
        return Math.max(paint.measureText(text), ink.width());
    }
}
