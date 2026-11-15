package de.blockzeit;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;
import de.blockzeit.core.WeatherCode;
import de.blockzeit.core.WeatherCode.Category;
import de.blockzeit.core.WeatherCode.Condition;

/** Original bundled vector line art, drawn directly on transparent widget canvases. */
public final class WeatherIcon {
    private static final int WHITE = 0xffe6e8eb;
    private static final int BLUE = 0xff81bfda;
    private static final int YELLOW = 0xffe4c778;
    private static final int MUTED = 0xffa5afb9;

    private WeatherIcon() { }

    /**
     * Fits an undistorted icon inside bounds without changing the caller's canvas state.
     * isDay must come from the API's is_day; unknown/missing codes draw a neutral question mark.
     */
    public static void draw(Canvas canvas, RectF bounds, int code, boolean isDay) {
        if (canvas == null || bounds == null || bounds.isEmpty()
                || !Float.isFinite(bounds.left) || !Float.isFinite(bounds.top)
                || !Float.isFinite(bounds.right) || !Float.isFinite(bounds.bottom)) return;
        float side = Math.min(bounds.width(), bounds.height());
        if (!Float.isFinite(side)) return;
        int saved = canvas.save();
        try {
            canvas.clipRect(bounds);
            canvas.translate(bounds.left + (bounds.width() - side) / 2,
                    bounds.top + (bounds.height() - side) / 2);
            canvas.scale(side / 100f, side / 100f);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.2f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            Condition condition = WeatherCode.classify(code);
            Category category = condition.category;
            if (category == Category.UNKNOWN) {
                unknown(canvas, paint);
                return;
            }
            if (category == Category.CLEAR) {
                celestial(canvas, paint, 50, 47, 18, isDay);
                return;
            }
            Path cloud = cloud();
            boolean accent = category == Category.MAINLY_CLEAR || category == Category.PARTLY_CLOUDY
                    || category == Category.RAIN_SHOWERS || category == Category.SNOW_SHOWERS;
            if (category == Category.MAINLY_CLEAR) {
                Matrix transform = new Matrix();
                transform.setScale(.72f, .72f);
                cloud.transform(transform);
                cloud.offset(0, 22);
                int layer = canvas.save();
                canvas.clipOutPath(cloud);
                celestial(canvas, paint, 58, 35, 16, isDay);
                canvas.restoreToCount(layer);
                paint.setColor(WHITE);
                canvas.drawPath(cloud, paint);
                return;
            }
            if (accent) {
                int layer = canvas.save();
                canvas.clipOutPath(cloud);
                celestial(canvas, paint, 70, 26, 10, isDay);
                canvas.restoreToCount(layer);
            }
            paint.setColor(WHITE);
            canvas.drawPath(cloud, paint);
            int marks = condition.severity.marks;
            switch (category) {
                case FOG:
                case RIME_FOG:
                    paint.setColor(MUTED);
                    canvas.drawLine(20, 70, 72, 70, paint);
                    canvas.drawLine(28, 80, 80, 80, paint);
                    if (category == Category.RIME_FOG) snowflake(canvas, paint, 80, 68, 5);
                    break;
                case DRIZZLE:
                case FREEZING_DRIZZLE:
                    drizzle(canvas, paint, marks);
                    if (category == Category.FREEZING_DRIZZLE) snowflake(canvas, paint, 76, 79, 5);
                    break;
                case RAIN:
                case RAIN_SHOWERS:
                case FREEZING_RAIN:
                    rain(canvas, paint, marks);
                    if (category == Category.FREEZING_RAIN) snowflake(canvas, paint, 78, 80, 5);
                    break;
                case SNOW:
                case SNOW_SHOWERS:
                    paint.setColor(WHITE);
                    for (int i = 0; i < marks; i++) snowflake(canvas, paint, markX(i, marks), 76, 5);
                    break;
                case SNOW_GRAINS:
                    paint.setColor(WHITE);
                    for (int i = 0; i < 3; i++) diamond(canvas, paint, 31 + i * 18, 75, 2.8f);
                    break;
                case THUNDERSTORM:
                case HAIL_THUNDERSTORM:
                    boolean heavy = marks >= 3;
                    bolt(canvas, paint, heavy ? 33 : 44, 64);
                    if (heavy) bolt(canvas, paint, 58, 64);
                    if (category == Category.HAIL_THUNDERSTORM) {
                        paint.setColor(BLUE);
                        int hail = heavy ? 3 : 2;
                        for (int i = 0; i < hail; i++) diamond(canvas, paint, markX(i, hail), 91, 3);
                    }
                    break;
                default: break; // Cloud cover: no precipitation or invented sunlight.
            }
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private static Path cloud() {
        Path path = new Path();
        path.moveTo(24, 60);
        path.cubicTo(8, 60, 9, 40, 25, 39);
        path.cubicTo(24, 23, 49, 20, 57, 35);
        path.cubicTo(66, 27, 81, 34, 79, 43);
        path.cubicTo(95, 44, 92, 60, 78, 60);
        path.close();
        return path;
    }

    private static void celestial(Canvas canvas, Paint paint, float x, float y, float radius, boolean day) {
        paint.setColor(day ? YELLOW : BLUE);
        if (day) {
            canvas.drawCircle(x, y, radius * .64f, paint);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
                canvas.drawLine(x + dx * radius, y + dy * radius,
                        x + dx * radius * 1.28f, y + dy * radius * 1.28f, paint);
            }
        } else {
            // Closed crescent outline: no eraser, opaque disc, emoji, font or background assumption.
            Path moon = new Path();
            moon.moveTo(x + radius * .35f, y - radius);
            moon.cubicTo(x - radius * 1.25f, y - radius, x - radius * 1.25f, y + radius, x, y + radius);
            moon.cubicTo(x + radius * .6f, y + radius, x + radius * .9f, y + radius * .5f,
                    x + radius * .9f, y + radius * .25f);
            moon.cubicTo(x - radius * .3f, y + radius * .45f, x - radius * .5f, y - radius * .55f,
                    x + radius * .35f, y - radius);
            moon.close();
            canvas.drawPath(moon, paint);
        }
    }

    private static float markX(int index, int count) { return 50 + (index - (count - 1) / 2f) * 15; }

    private static void drizzle(Canvas canvas, Paint paint, int severity) {
        paint.setColor(BLUE);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < severity; i++) {
            float x = markX(i, severity);
            canvas.drawCircle(x, 70, 1.9f, paint);
            canvas.drawCircle(x - 3, 80, 1.9f, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
    }

    private static void rain(Canvas canvas, Paint paint, int severity) {
        paint.setColor(BLUE);
        for (int i = 0; i < severity; i++) {
            float x = markX(i, severity);
            canvas.drawLine(x + 3, 69, x - 2, 81, paint);
        }
    }

    private static void snowflake(Canvas canvas, Paint paint, float x, float y, float radius) {
        paint.setColor(BLUE);
        for (int i = 0; i < 3; i++) {
            double angle = i * Math.PI / 3;
            float dx = (float) Math.cos(angle) * radius, dy = (float) Math.sin(angle) * radius;
            canvas.drawLine(x - dx, y - dy, x + dx, y + dy, paint);
        }
    }

    private static void diamond(Canvas canvas, Paint paint, float x, float y, float radius) {
        Path diamond = new Path();
        diamond.moveTo(x, y - radius);
        diamond.lineTo(x + radius, y);
        diamond.lineTo(x, y + radius);
        diamond.lineTo(x - radius, y);
        diamond.close();
        canvas.drawPath(diamond, paint);
    }

    private static void bolt(Canvas canvas, Paint paint, float x, float y) {
        paint.setColor(YELLOW);
        Path bolt = new Path();
        bolt.moveTo(x + 9, y);
        bolt.lineTo(x, y + 12);
        bolt.lineTo(x + 8, y + 12);
        bolt.lineTo(x + 2, y + 22);
        canvas.drawPath(bolt, paint);
    }

    private static void unknown(Canvas canvas, Paint paint) {
        paint.setColor(MUTED);
        canvas.drawCircle(50, 48, 25, paint);
        Path question = new Path();
        question.moveTo(42, 41);
        question.cubicTo(42, 30, 62, 30, 60, 42);
        question.cubicTo(59, 48, 50, 47, 50, 54);
        canvas.drawPath(question, paint);
        canvas.drawPoint(50, 63, paint);
    }
}
