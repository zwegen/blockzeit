package de.blockzeit;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.util.TypedValue;
import android.widget.RemoteViews;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import de.blockzeit.core.WeatherData;

/** Compact extrema plus a single static metric: rain strictly above 42%, otherwise wind. */
final class WeatherFooter {
    static final int HIGH_COLOR = RingRenderer.ORANGE, LOW_COLOR = RingRenderer.BLUE;
    static final float BASE_FONT = .053f, ICON_EM = .8f;
    // Keep the existing compact separator between the extrema and the single active metric.
    private static final String DOT = "\u200a·\u200a";
    private WeatherFooter() { }
    static String ranges(WeatherData.Sample sample, long now, String timezone) {
        boolean known = sample != null;
        return "↑" + BlockzeitWidget.weatherDegrees(known ? sample.maximum : Double.NaN)
                + " ↓" + BlockzeitWidget.weatherDegrees(known ? sample.minimum : Double.NaN) + DOT;
    }
    static String wind(WeatherData.Sample sample) {
        return WeatherData.windText(sample == null ? Double.NaN : sample.windSpeed) + "km/h";
    }
    static String rain(WeatherData.Sample sample, long now) {
        return WeatherData.probabilityText(sample == null ? Double.NaN : sample.displayProbability(now)) + "%";
    }
    static boolean rainSelected(WeatherData.Sample sample, long now) {
        double probability = sample == null ? Double.NaN : sample.displayProbability(now);
        return WeatherData.validProbability(probability) && probability > 42;
    }
    // Keep the other two rows and their existing anchors exactly as before this footer change.
    static String geometryMetric(WeatherData.Sample sample, long now) {
        return showRain(sample, now) ? rain(sample, now) : wind(sample);
    }
    // Legacy geometry reference only, never the displayed-metric threshold.
    static boolean showRain(WeatherData.Sample sample, long now) {
        double probability = sample == null ? Double.NaN : sample.displayProbability(now);
        return WeatherData.validProbability(probability) && probability > 50;
    }
    static CharSequence styledRanges(String ranges) {
        SpannableString styled = new SpannableString(ranges);
        int first = ranges.indexOf(" ↓"), second = ranges.indexOf(DOT);
        if (first >= 0 && second > first) {
            styled.setSpan(new ForegroundColorSpan(HIGH_COLOR), 0, first, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new ForegroundColorSpan(LOW_COLOR), first + 1, second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }
    static String description(WeatherData.Sample sample, long now, String timezone) {
        return ranges(sample, now, timezone) + (rainSelected(sample, now)
                ? "Regenwahrscheinlichkeit kommendes Stundenintervall " + rain(sample, now) : "Wind " + wind(sample));
    }
    static Paint paint(float pixels) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL)); paint.setTextSize(pixels);
        return paint;
    }
    static int iconPixels(int diameter, float density, float font) {
        return Math.max(1, Math.round(diameter * density * font * ICON_EM));
    }
    static float width(int diameter, float density, float font, String ranges, String metric) {
        Paint paint = paint(diameter * density * font); Rect ink = new Rect();
        float width = iconPixels(diameter, density, font);
        for (String text : new String[]{ranges, metric}) {
            paint.getTextBounds(text, 0, text.length(), ink);
            // Native wrap_content TextView widths round each part independently.
            width += (float) Math.ceil(Math.max(paint.measureText(text), ink.width()));
        }
        return width;
    }
    static float font(int diameter, float density, String ranges, String metric) {
        float available = diameter * density * .64f - density;
        if (width(diameter, density, BASE_FONT, ranges, metric) <= available) return BASE_FONT;
        float lower = 0, upper = BASE_FONT;
        for (int i = 0; i < 18; i++) {
            float candidate = (lower + upper) / 2;
            if (width(diameter, density, candidate, ranges, metric) <= available) lower = candidate;
            else upper = candidate;
        }
        return lower;
    }
    static WeatherMetricIcon.Kind precipitationIcon(WeatherData.Sample sample, long now) {
        WeatherData.Precipitation type = WeatherData.precipitation(sample == null ? null : sample.displayHour(now));
        switch (type) {
            case SNOW: return WeatherMetricIcon.Kind.SNOW;
            case MIXED: return WeatherMetricIcon.Kind.MIXED;
            default: return WeatherMetricIcon.Kind.DROP;
        }
    }
    static RemoteViews metricViews(String packageName, int diameter, float density, float font,
            String text, boolean rain, WeatherData.Sample sample, long now) {
        RemoteViews page = new RemoteViews(packageName, rain ? R.layout.weather_metric_rain : R.layout.weather_metric_wind);
        int textId = rain ? R.id.bb_widget_weather_rain : R.id.bb_widget_weather_wind;
        int iconId = rain ? R.id.bb_widget_weather_drop_icon : R.id.bb_widget_weather_wind_icon;
        page.setTextViewText(textId, text);
        page.setTextViewTextSize(textId, TypedValue.COMPLEX_UNIT_PX, diameter * density * font);
        int pixels = iconPixels(diameter, density, font);
        page.setViewLayoutWidth(iconId, pixels, TypedValue.COMPLEX_UNIT_PX);
        page.setViewLayoutHeight(iconId, pixels, TypedValue.COMPLEX_UNIT_PX);
        page.setImageViewBitmap(iconId, WeatherMetricIcon.draw(pixels,
                rain ? precipitationIcon(sample, now) : WeatherMetricIcon.Kind.WIND));
        return page;
    }

}
