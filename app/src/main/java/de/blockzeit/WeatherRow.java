package de.blockzeit;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.RemoteViews;

/** Weather-only bitmap: center the temperature itself, with a small icon physically to its right. */
final class WeatherRow {
    private static final float BASE_FONT = .1944f, FIT_ICON_CAP = .46f, ICON_SCALE = 1.204f, ICON_CANVAS_SCALE = 2.408f, GAP = .015f;
    private WeatherRow() { }
    static Paint textPaint(float pixels) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setColor(Color.WHITE); paint.setTextSize(pixels); return paint;
    }
    private static String primary(String text) { return text.startsWith("≈ ") ? text.substring(2) : text; }
    private static String marker(String text) { return text.startsWith("≈ ") ? "≈" : ""; }
    private static RectF ink(Paint paint, String text) {
        Path outline = new Path(); paint.getTextPath(text, 0, text.length(), 0, 0, outline);
        RectF bounds = new RectF(); outline.computeBounds(bounds, true); return bounds;
    }
    private static float extent(Paint paint, String text, float gap) {
        float extra = Math.max(1, ink(paint, "H").height() * FIT_ICON_CAP) + gap;
        String mark = marker(text);
        if (!mark.isEmpty()) extra = Math.max(extra, ink(paint, mark).width() + gap);
        // Preserve the exact 0.46 temperature fit; icon growth uses transparent padding, not a smaller font.
        return ink(paint, primary(text)).width() + 2 * extra;
    }
    static float font(int diameter, float density, String text) {
        float pixels = diameter * density;
        Paint paint = textPaint(pixels * BASE_FONT * PrimaryText.SCALE);
        float available = Math.max(1, Math.round(pixels * .64f) - Math.max(1, density) * 2);
        if (extent(paint, text, pixels * GAP) <= available) return BASE_FONT;
        float lower = 0, upper = BASE_FONT;
        for (int i = 0; i < 18; i++) {
            float candidate = (lower + upper) / 2;
            paint.setTextSize(pixels * candidate * PrimaryText.SCALE);
            if (extent(paint, text, pixels * GAP) <= available) lower = candidate;
            else upper = candidate;
        }
        return lower;
    }
    static final class Geometry {
        final Paint paint, minusPaint;
        final String temperature, marker, number, minus;
        final float textLeft, numberLeft, minusLeft, minusBaseline, markerLeft, baseline, titleTop;
        final RectF textBounds, markerBounds, iconBounds;
        Geometry(int diameter, float density, String text, int width, int height, float footerFont) {
            paint = textPaint(diameter * density * font(diameter, density, text) * PrimaryText.SCALE);
            temperature = primary(text); marker = marker(text);
            boolean negative = temperature.length() > 1 && (temperature.charAt(0) == '−' || temperature.charAt(0) == '-')
                    && Character.isDigit(temperature.charAt(1));
            minus = negative ? temperature.substring(0, 1) : "";
            number = negative ? temperature.substring(1) : temperature;
            // Same actual pixel font as row 2, not a fixed relative-size approximation.
            minusPaint = WeatherFooter.paint(diameter * density * footerFont);
            minusPaint.setColor(Color.WHITE);
            RectF numberInk = ink(paint, number), minusInk = ink(minusPaint, minus);
            float numberOffset = 0, minusOffsetY = 0;
            RectF ink = new RectF(numberInk), mark = ink(paint, marker);
            if (negative) {
                RectF originalMinus = ink(paint, minus);
                float originalGap = paint.measureText(minus) + numberInk.left - originalMinus.right;
                numberOffset = minusInk.right + originalGap - numberInk.left;
                minusOffsetY = numberInk.centerY() - minusInk.centerY();
                ink.offset(numberOffset, 0);
                ink.union(minusInk.left, minusInk.top + minusOffsetY, minusInk.right, minusInk.bottom + minusOffsetY);
            }
            // Center the visible temperature ink independently of icon and optional stale prefix.
            textLeft = width / 2f - (ink.left + ink.right) / 2f;
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            float originalBaseline = Math.max(0, (height - (metrics.descent - metrics.ascent)) / 2) - metrics.ascent;
            // Preserve the existing baseline unless native fallback/hinting would clip glyphs.
            // Use actual glyph outlines, not advance/group bounds; inset their antialiased raster by one pixel.
            float top = marker.isEmpty() ? ink.top : Math.min(ink.top, mark.top);
            float bottom = marker.isEmpty() ? ink.bottom : Math.max(ink.bottom, mark.bottom);
            baseline = Math.min(height - 1 - bottom, Math.max(1 - top, originalBaseline));
            numberLeft = textLeft + numberOffset;
            minusLeft = textLeft;
            // Align the sign's visible midline with the number, never a superscript/subscript.
            minusBaseline = baseline + minusOffsetY;
            textBounds = new RectF(textLeft + ink.left, baseline + ink.top, textLeft + ink.right, baseline + ink.bottom);
            float gap = diameter * density * GAP;
            markerLeft = textBounds.left - gap - mark.right;
            markerBounds = new RectF(markerLeft + mark.left, baseline + mark.top, markerLeft + mark.right, baseline + mark.bottom);
            float icon = Math.max(1, ink(paint, "H").height() * FIT_ICON_CAP) * ICON_SCALE;
            float iconTop = textBounds.centerY() - icon / 2;
            iconBounds = new RectF(textBounds.right + gap, iconTop, textBounds.right + gap + icon, iconTop + icon);
            // Preserve the previous transparent/layout envelope; a smaller icon must not move the title.
            float layoutIcon = Math.max(1, ink(paint, "H").height() * FIT_ICON_CAP) * ICON_CANVAS_SCALE;
            titleTop = Math.min(textBounds.top, textBounds.centerY() - layoutIcon / 2);
        }
    }
    static int width(int diameter, float density, String text) {
        Paint paint = textPaint(diameter * density * font(diameter, density, text) * PrimaryText.SCALE);
        float previousIcon = Math.max(1, ink(paint, "H").height() * FIT_ICON_CAP);
        // Symmetric integer padding preserves the old center/parity and all primary pixels.
        int padding = (int) Math.ceil(previousIcon * (ICON_CANVAS_SCALE - 1));
        return Math.max(1, Math.round(diameter * density * .64f)) + 2 * padding;
    }
    static Geometry geometry(int diameter, float density, String text) {
        return geometry(diameter, density, text, WeatherFooter.font(diameter, density, "", ""));
    }
    static Geometry geometry(int diameter, float density, String text, float footerFont) {
        return new Geometry(diameter, density, text, width(diameter, density, text),
                Math.max(1, Math.round(diameter * density * .20f)), footerFont);
    }
    static void glyphs(Canvas canvas, String text, float left, float baseline, Paint paint) {
        // Draw the same native outlines used for fitting, avoiding drawText's separate hint/advance rounding.
        Path outline = new Path(); paint.getTextPath(text, 0, text.length(), left, baseline, outline);
        canvas.drawPath(outline, paint);
    }
    static void bind(RemoteViews views, int id, int diameter, float density, String text, int code, boolean isDay) {
        bind(views, id, diameter, density, text, code, isDay, WeatherFooter.font(diameter, density, "", ""));
    }
    static void bind(RemoteViews views, int id, int diameter, float density, String text, int code, boolean isDay, float footerFont) {
        Bitmap image = draw(diameter, density, text, code, isDay, footerFont);
        views.setViewLayoutWidth(id, image.getWidth(), TypedValue.COMPLEX_UNIT_PX);
        views.setImageViewBitmap(id, image);
    }
    static Bitmap draw(int diameter, float density, String text, int code, boolean isDay) {
        return draw(diameter, density, text, code, isDay, WeatherFooter.font(diameter, density, "", ""));
    }
    static void temperature(Canvas canvas, Geometry geometry) {
        glyphs(canvas, geometry.number, geometry.numberLeft, geometry.baseline, geometry.paint);
        if (!geometry.minus.isEmpty()) glyphs(canvas, geometry.minus, geometry.minusLeft, geometry.minusBaseline, geometry.minusPaint);
    }
    static Bitmap draw(int diameter, float density, String text, int code, boolean isDay, float footerFont) {
        int width = width(diameter, density, text);
        int height = Math.max(1, Math.round(diameter * density * .20f));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        // Pixel-sized RemoteViews bounds must not be reinterpreted through bitmap density.
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas = new Canvas(bitmap);
        Geometry geometry = geometry(diameter, density, text, footerFont);
        int primaryWidth = Math.max(1, Math.round(diameter * density * .64f));
        Geometry primary = new Geometry(diameter, density, text, primaryWidth, height, footerFont);
        Bitmap layer = Bitmap.createBitmap(primaryWidth, height, Bitmap.Config.ARGB_8888);
        layer.setDensity(Bitmap.DENSITY_NONE);
        Canvas original = new Canvas(layer);
        temperature(original, primary);
        if (!primary.marker.isEmpty()) glyphs(original, primary.marker, primary.markerLeft, primary.baseline, primary.paint);
        // Keep the exact original raster phase as well as its font; integer-copy the old primary layer.
        Paint copy = new Paint(); copy.setFilterBitmap(false);
        canvas.drawBitmap(layer, (width - primaryWidth) / 2, 0, copy);
        layer.recycle();
        WeatherIcon.draw(canvas, geometry.iconBounds, code, isDay);
        return bitmap;
    }
}
