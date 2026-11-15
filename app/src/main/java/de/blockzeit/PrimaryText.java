package de.blockzeit;

import android.graphics.Paint;
import android.graphics.Typeface;

/** Glyph-only primary reduction; original row/fit measurements and baseline stay fixed. */
@android.annotation.TargetApi(31)
final class PrimaryText {
    static final float SCALE = .98f;
    private PrimaryText() { }
    static int topPadding(int diameter, float density, float originalFont) {
        return Math.max(0, adjustment(diameter, density, originalFont));
    }
    static int bottomPadding(int diameter, float density, float originalFont) {
        return Math.max(0, -adjustment(diameter, density, originalFont));
    }
    private static int adjustment(int diameter, float density, float originalFont) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        float original = diameter * density * originalFont;
        paint.setTextSize(original);
        Paint.FontMetricsInt before = paint.getFontMetricsInt();
        paint.setTextSize(original * SCALE);
        Paint.FontMetricsInt after = paint.getFontMetricsInt();
        int height = Math.round(.20f * diameter * density);
        int target = baseline(height, 0, 0, before);
        int padding = 0;
        // Integer font rounding can move a small fitted line either way; correct only its padding.
        if (baseline(height, 0, 0, after) > target) {
            while (padding < height && baseline(height, 0, padding, after) > target) padding++;
            return -padding;
        }
        while (padding < height && baseline(height, padding, 0, after) < target) padding++;
        return padding;
    }
    private static int baseline(int height, int top, int bottom, Paint.FontMetricsInt metrics) {
        return top + Math.max(0, (height - top - bottom - (metrics.descent - metrics.ascent)) / 2) - metrics.ascent;
    }
}
