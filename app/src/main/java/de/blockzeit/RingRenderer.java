package de.blockzeit;

import de.blockzeit.R;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import de.blockzeit.core.DayMath;
import java.util.Locale;
import java.time.LocalDate;

@android.annotation.TargetApi(31)
final class RingRenderer {
    static final int ORANGE = 0xffff9800, GREEN = 0xff11db66, BLUE = 0xff29b6f6;
    static final int YEAR_WHITE = 0xffffffff;
    private RingRenderer() { }
    static Bitmap draw(int pixels, int diameterDp, int opacity, int actual, DayMath.Day day, boolean known, LocalDate date) {
        return draw(pixels, diameterDp, opacity, actual, day, known, date, YearColors.ELAPSED, YearColors.REMAINING);
    }
    static Bitmap draw(int pixels, int diameterDp, int opacity, int actual, DayMath.Day day, boolean known,
                       LocalDate date, int elapsedColor, int remainingColor) {
        // At most 448²×4 = 802,816 bytes, one bitmap per RemoteViews update.
        int side = Math.max(160, Math.min(448, pixels));
        Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(side / 1000f, side / 1000f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(Math.round(Math.max(0, Math.min(100, opacity)) * 2.55f), 28, 31, 36));
        canvas.drawCircle(500, 500, 496, p);
        // Existing baseline geometry, before the shared outward translation below.
        final float dialInset = 12f;
        // Retain the diameter-dependent +10dp baseline; never scale the tick lengths.
        final float tickOutset = 5000f / diameterDp;
        p.setStrokeCap(Paint.Cap.BUTT);
        // Translate both rings equally, never scale their strokes or the bitmap/background.
        // Apply exactly half of the previous enlargement; retain the original tick sizes.
        // The unchanged maximum radius 492 still bounds both rings without clipping.
        final float ringOutset = .5f * (492f - (410 - dialInset + tickOutset + 1000f / diameterDp + 18.5f));
        final float blockOuter = 410 - dialInset + tickOutset + ringOutset;
        // Retain the former year arc's 1dp clear gap outside the block ticks.
        final float yearInner = blockOuter + 1000f / diameterDp;
        p.setStrokeWidth(2f);
        LocalDate first = date.withDayOfYear(1);
        int days = date.lengthOfYear();
        for (int i = 0; i < days; i++) {
            // One tick per actual calendar date; a month start replaces, never adds a tick.
            LocalDate tickDate = first.plusDays(i);
            p.setColor(yearDayColor(i + 1, date.getDayOfYear(), elapsedColor, remainingColor));
            // Month starts extend outward to exactly twice the ordinary daily length.
            float length = (410 - 386) / 2f * (tickDate.getDayOfMonth() == 1 ? 2 : 1);
            line(canvas, p, i * 360.0 / days, yearInner, yearInner + length);
        }
        for (int i = 0; i < DayMath.SLOTS; i++) {
            DayMath.Slot slot = DayMath.dialSlot(i, actual, day, known);
            p.setColor(slotColor(slot));
            p.setStrokeWidth(i % 6 == 0 ? 5.3f : 4.4f);
            line(canvas, p, i * 2.5, blockOuter - (i % 6 == 0 ? 37 : 24), blockOuter);
        }
        p.setColor(0xfff1f2f3);
        // The shared native Clock year is regular sans-serif-condensed at .04d (not medium).
        p.setTypeface(WeatherFooter.paint(40).getTypeface());
        p.setTextSize(40); // 40/1000 × displayed diameter, also when the bitmap is capped.
        p.setTextAlign(Paint.Align.LEFT);
        Rect ink = new Rect();
        // Two source-bitmap pixels keep antialiasing apart, even on the smallest raster.
        final float safeRadius = blockOuter - 37 - Math.max(4f, 2000f / side);
        for (int hour = 0; hour < 24; hour += 2) {
            String label = String.format(Locale.ROOT, "%02d", hour);
            p.getTextBounds(label, 0, label.length(), ink);
            double radians = Math.toRadians(hour * 15 - 90);
            float x = (float) Math.cos(radians), y = (float) Math.sin(radians);
            float halfWidth = ink.width() / 2f, halfHeight = ink.height() / 2f;
            float projection = Math.abs(x) * halfWidth + Math.abs(y) * halfHeight;
            // Farthest actual ink-box corner sits just inside the longest block-tick tips.
            // Unlike a fixed center radius this also protects 06/18 and diagonal labels.
            float radius = (float) Math.sqrt(safeRadius * safeRadius - halfWidth * halfWidth
                    - halfHeight * halfHeight + projection * projection) - projection;
            canvas.drawText(label, 500 + x * radius - ink.exactCenterX(),
                    500 + y * radius - ink.exactCenterY(), p);
        }
        // Upright initials at the midpoint of each actual calendar-month interval.
        // Same regular typeface as the hours; month initials are 25% smaller and white.
        p.setColor(YEAR_WHITE);
        p.setTextSize(30);
        String[] months = {"J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"};
        final float rasterPixel = 1000f / side;
        for (int month = 1; month <= 12; month++) {
            LocalDate start = first.withMonth(month);
            double middle = start.getDayOfYear() - 1 + start.lengthOfMonth() / 2.0;
            double angle = Math.toRadians(middle * 360 / days - 90);
            double x = Math.cos(angle), y = Math.sin(angle);
            String label = months[month - 1];
            p.getTextBounds(label, 0, label.length(), ink);
            double halfWidth = ink.width() / 2.0, halfHeight = ink.height() / 2.0;
            double projection = Math.abs(x) * halfWidth + Math.abs(y) * halfHeight;
            // Native antialiasing needs room beyond the integer glyph bounds. Prefer
            // three raster pixels outside ordinary ticks, bounded by the widget edge;
            // the 160dp launcher minimum still retains one completely empty pixel.
            double target = yearInner + 12 + 3 * rasterPixel;
            double radius = projection + Math.sqrt(target * target - halfWidth * halfWidth
                    - halfHeight * halfHeight + projection * projection);
            double edge = 500 - 1.5 * rasterPixel;
            radius = Math.min(radius, Math.min((edge - halfWidth) / Math.abs(x),
                    (edge - halfHeight) / Math.abs(y)));
            canvas.drawText(label, (float) (500 + x * radius - ink.exactCenterX()),
                    (float) (500 + y * radius - ink.exactCenterY()), p);
        }
        return bitmap;
    }
    private static void line(Canvas c, Paint p, double degrees, float inner, float outer) {
        double angle = Math.toRadians(degrees - 90);
        float x = (float) Math.cos(angle), y = (float) Math.sin(angle);
        c.drawLine(500 + x * inner, 500 + y * inner, 500 + x * outer, 500 + y * outer, p);
    }
    static int markerColor(int actual, int expected, boolean known) {
        if (!known || actual == expected) return Color.WHITE;
        return actual > expected ? GREEN : Color.RED;
    }

    static int yearDayColor(int ordinal, int today) {
        return yearDayColor(ordinal, today, YearColors.ELAPSED, YearColors.REMAINING);
    }
    static int yearDayColor(int ordinal, int today, int elapsedColor, int remainingColor) {
        return ordinal <= today ? elapsedColor : remainingColor;
    }

    private static int slotColor(DayMath.Slot slot) {
        return slot == DayMath.Slot.ORANGE ? ORANGE : slot == DayMath.Slot.GREEN ? GREEN
                : slot == DayMath.Slot.BLUE ? BLUE : 0xffe6e8eb;
    }

}
