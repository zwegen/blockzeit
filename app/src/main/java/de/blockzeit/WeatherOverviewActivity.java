package de.blockzeit;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import de.blockzeit.core.WeatherData;
import de.blockzeit.core.WidgetMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/** Read-only weather opening. Uses only the existing cache: no fetch, scheduler or timer. */
public final class WeatherOverviewActivity extends Activity {
    static final String ACTION_OPEN = "de.blockzeit.OPEN_WEATHER";
    static Intent intent(Context context, int widget) {
        return new Intent(context, WeatherOverviewActivity.class).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setData(identity(widget))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget);
    }
    static PendingIntent pendingIntent(Context context, int widget) {
        return PendingIntent.getActivity(context, widget, intent(context, widget),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static Uri identity(int widget) { return Uri.parse("blockzeit://widget/" + widget + "/weather"); }
    static boolean currentTarget(Context context, int widget) {
        return widget != AppWidgetManager.INVALID_APPWIDGET_ID && BlockzeitWidget.active(context, widget)
                && Store.weatherEnabled(context, widget) && Store.mode(context, widget) == WidgetMode.WEATHER;
    }
    private int widget = AppWidgetManager.INVALID_APPWIDGET_ID;
    private LinearLayout body;
    private TextView title;
    private long now;
    private WeatherData.Sample sample;
    private String timezone;
    private static final int HIGH = RingRenderer.ORANGE, LOW = 0xff81bfda, MUTED = 0xffb4bac2;
    private boolean observingCache;
    private final Runnable cacheChanged = () -> {
        if (!observingCache || body == null || isFinishing() || isDestroyed()) return;
        if (!currentTarget(this, widget)) { finish(); return; }
        render(); fitWindow();
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener cacheListener = (preferences, key) -> {
        if (!observingCache || body == null) return;
        if (key == null || key.equals("sample." + widget) || key.equals("place." + widget) || key.equals("token." + widget)) {
            // A completed existing weather job may arrive while this cache-only window is open.
            // Coalesce that notification on the UI queue; no fetch, timer or polling is started.
            body.removeCallbacks(cacheChanged); body.post(cacheChanged);
        }
    };


    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration english = new Configuration(); english.fontScale = 0;
        english.setLocale(Locale.ENGLISH); applyOverrideConfiguration(english);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent source = getIntent();
        if (source != null) widget = source.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        if (source == null || !ACTION_OPEN.equals(source.getAction()) || !identity(widget).equals(source.getData())
                || !currentTarget(this, widget)) { finish(); return; }
        Window window = getWindow(); window.setGravity(Gravity.CENTER);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes(); attributes.dimAmount = .58f; window.setAttributes(attributes);
        CalendarWindowStyle.window(window);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(24), dp(24), dp(24)); body.setFocusableInTouchMode(true);
        ScrollView scroll = CalendarWindowStyle.scroll(this); scroll.setFillViewport(false); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        setContentView(scroll);
        OutsideCancel.activity(this, this::finish);
        render(); body.requestFocus();
    }
    @Override protected void onResume() {
        super.onResume();
        if (body == null || isFinishing()) return;
        if (!currentTarget(this, widget)) { finish(); return; }
        if (!observingCache) {
            observingCache = true;
            WeatherStore.prefs(this).registerOnSharedPreferenceChangeListener(cacheListener);
        }
        render(); body.post(this::fitWindow);
    }
    @Override protected void onPause() {
        observingCache = false;
        WeatherStore.prefs(this).unregisterOnSharedPreferenceChangeListener(cacheListener);
        if (body != null) body.removeCallbacks(cacheChanged);
        super.onPause();
    }
    private void fitWindow() {
        if (isFinishing() || isDestroyed()) return;
        int screen = getResources().getDisplayMetrics().widthPixels;
        getWindow().setLayout(Math.min(dp(560), Math.max(dp(160), screen - dp(32))), WindowManager.LayoutParams.WRAP_CONTENT);
    }
    private void render() {
        body.removeAllViews(); now = System.currentTimeMillis() / 1000;
        WeatherData.Place place = WeatherStore.place(this, widget); sample = place == null ? null : WeatherStore.sample(this, widget);
        timezone = place == null ? "UTC" : place.timezone;
        LocalDate today = Instant.ofEpochSecond(now).atZone(ZoneId.of(timezone)).toLocalDate();
        LinearLayout header = row(body); title = label(header, place == null ? "Weather" : place.name, 24);
        CalendarWindowStyle.heading(title, 24); title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(this); close.setText("X"); close.setContentDescription("Close weather");
        close.setOnClickListener(view -> finish()); header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        CalendarWindowStyle.closeIcon(close);
        LinearLayout todaySection = column(); body.addView(todaySection, new LinearLayout.LayoutParams(-1, -2));
        TextView todayLabel = label(todaySection, "Today", 16); CalendarWindowStyle.heading(todayLabel, 16);
        todayLabel.setTag("today-label"); todayLabel.setGravity(Gravity.CENTER);
        todayLabel.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        AdaptiveRow current = new AdaptiveRow(this, 240); current.setTag("current-row"); current.setBaselineAligned(false);
        todaySection.addView(current, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout readingBlock = column(); readingBlock.setTag("current-reading"); current.addView(readingBlock);
        LinearLayout reading = row(readingBlock); reading.setGravity(Gravity.CENTER_VERTICAL); reading.setBaselineAligned(false);
        View icon = new CurrentWeatherIcon(this, sample == null ? -1 : sample.code, sample != null && sample.isDay);
        icon.setTag("current-icon");
        icon.setContentDescription(sample == null ? "Weather unavailable" : WeatherOverviewFormat.description(sample.code, sample.isDay));
        reading.addView(icon, new LinearLayout.LayoutParams(dp(85), dp(85)));
        TextView temperature = new CurrentTemperature(this);
        temperature.setText(WeatherOverviewFormat.degrees(sample == null ? Double.NaN : sample.temperature));
        temperature.setTextSize(36); CalendarWindowStyle.text(temperature); temperature.setTextDirection(View.TEXT_DIRECTION_LTR);
        reading.addView(temperature, new LinearLayout.LayoutParams(-2, -2));
        temperature.setTag("current-temperature"); temperature.setPadding(dp(12), 0, dp(12), 0); temperature.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout summary = column(); summary.setTag("current-summary"); summary.setGravity(Gravity.RIGHT); current.addView(summary);
        WeatherData.Day daily = sample == null ? null : sample.day(today.toString());
        if (daily == null) daily = WeatherStore.legacyToday(this, widget, today.toString());
        double maximum = daily == null ? Double.NaN : daily.maximum, minimum = daily == null ? Double.NaN : daily.minimum;
        LinearLayout extrema = row(summary); extrema.setTag("current-extrema"); extrema.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); extrema.setBaselineAligned(false);
        colored(extrema, WeatherOverviewFormat.degrees(maximum), HIGH, 17);
        colored(extrema, " / ", MUTED, 17);
        colored(extrema, WeatherOverviewFormat.degrees(minimum), LOW, 17);
        extrema.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        summary.setMinimumWidth(extrema.getMeasuredWidth());
        // Reuse exactly the front widget's cached value, retained-hour selector and symbol semantics.
        // Keep description, wind and rain in one centered line below the current reading.
        // Only an overflowing accessibility/narrow row scrolls; text never shrinks or wraps.
        HorizontalScrollView metricsScroll = new HorizontalScrollView(this); metricsScroll.setFillViewport(true);
        todaySection.addView(metricsScroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout metrics = row(null); metrics.setGravity(Gravity.CENTER); metrics.setBaselineAligned(false); metrics.setTag("weather-metrics");
        metricsScroll.addView(metrics, new HorizontalScrollView.LayoutParams(-1, -2));
        LinearLayout group = row(null); group.setTag("weather-metrics-group"); group.setBaselineAligned(false);
        metrics.addView(group, new LinearLayout.LayoutParams(-2, -2));
        TextView condition = label(group, sample == null ? "Weather unavailable" : WeatherOverviewFormat.description(sample.code, sample.isDay), 16);
        condition.setTag("condition"); condition.setGravity(Gravity.CENTER_HORIZONTAL); condition.setSingleLine(true);
        TextView conditionSeparator = label(group, "·", 16); conditionSeparator.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams conditionSeparatorParams = new LinearLayout.LayoutParams(-2, -2);
        conditionSeparatorParams.leftMargin = dp(8); conditionSeparatorParams.rightMargin = dp(8); conditionSeparator.setLayoutParams(conditionSeparatorParams);
        LinearLayout wind = row(null); wind.setTag("wind-column"); wind.setBaselineAligned(false);
        group.addView(wind, new LinearLayout.LayoutParams(-2, -2));
        inlineMetric(wind, WeatherFooter.wind(sample), WeatherMetricIcon.Kind.WIND, "Wind", "wind-icon", "wind-value");
        TextView separator = label(group, "·", 16); separator.setTag("weather-metrics-separator"); separator.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(-2, -2);
        separatorParams.leftMargin = dp(8); separatorParams.rightMargin = dp(8); separator.setLayoutParams(separatorParams);
        LinearLayout precipitation = row(null); precipitation.setTag("precipitation-column"); precipitation.setBaselineAligned(false);
        group.addView(precipitation, new LinearLayout.LayoutParams(-2, -2));
        String percent = WeatherOverviewFormat.precipitationPercent(sample, now);
        WeatherMetricIcon.Kind kind = WeatherFooter.precipitationIcon(sample, now);
        inlineMetric(precipitation, percent, kind, WeatherOverviewFormat.precipitationDescription(kind, percent), "precipitation-icon", "precipitation-value");
        divider();
        dailyForecast(today);
    }
    private void dailyForecast(LocalDate today) {
        String[][] values = new String[5][2]; values[0] = new String[]{"Max", "Min"};
        for (int offset = 1; offset <= 4; offset++) {
            WeatherData.Day day = sample == null ? null : sample.day(today.plusDays(offset).toString());
            values[offset] = new String[]{WeatherOverviewFormat.degrees(day == null ? Double.NaN : day.maximum),
                    WeatherOverviewFormat.degrees(day == null ? Double.NaN : day.minimum)};
        }
        int[] widths = new int[2];
        TextView probe = new TextView(this); probe.setTextSize(16);
        for (int row = 0; row < values.length; row++) for (int col = 0; col < 2; col++) {
            String value = values[row][col];
            int signSpace = row > 0 && col == 1 && positiveMinimum(value) ? Math.round(probe.getPaint().measureText("−")) : 0;
            widths[col] = Math.max(widths[col], (int)Math.ceil(probe.getPaint().measureText(value)) + signSpace + dp(2));
        }
        // Keep English day names whole at narrow widths/large font. Scroll the table as one
        // unit, so its headers and right-aligned values never drift into different columns.
        int labelWidth = 0;
        for (int i = 0; i < 5; i++) {
            probe.setTextSize(i == 0 ? 16 : 17);
            probe.setTypeface(null, i == 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            labelWidth = Math.max(labelWidth, (int)Math.ceil(probe.getPaint().measureText(i == 0 ? "Forecast" : WeatherOverviewFormat.day(today, i))));
        }
        probe.setTextSize(16); probe.setTypeface(null, android.graphics.Typeface.NORMAL);
        int separatorWidth = (int)Math.ceil(probe.getPaint().measureText("/")) + dp(8);
        // Fixed percentage width keeps the new right anchor stable for 0%, 100% and unknowns.
        probe.setTextSize(17);
        int probabilityWidth = (int)Math.ceil(probe.getPaint().measureText("100%")) + dp(2);
        HorizontalScrollView scroll = new HorizontalScrollView(this); scroll.setFillViewport(true);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        final int minimumTableWidth = labelWidth + probabilityWidth + dp(8) + widths[0] + widths[1] + separatorWidth + dp(8);
        LinearLayout table = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                // Use only spare label space: keep long day names and temperature anchors intact.
                int available = View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.UNSPECIFIED
                        ? minimumTableWidth : View.MeasureSpec.getSize(widthSpec);
                int shift = Math.min(dp(20), Math.max(0, available - minimumTableWidth));
                for (int index = 0; index < getChildCount(); index++) {
                    LinearLayout forecastRow = (LinearLayout)getChildAt(index);
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)forecastRow.getChildAt(1).getLayoutParams();
                    params.rightMargin = dp(8) + shift;
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        table.setOrientation(LinearLayout.VERTICAL); table.setMinimumWidth(minimumTableWidth);
        scroll.addView(table, new HorizontalScrollView.LayoutParams(-1, -2));
        for (int i = 0; i < 5; i++) {
            LinearLayout row = row(table); row.setTag(i == 0 ? "daily-header" : "daily-" + i);
            TextView name = label(row, i == 0 ? "Forecast" : WeatherOverviewFormat.day(today, i), i == 0 ? 16 : 17);
            if (i == 0) CalendarWindowStyle.heading(name, 16);
            name.setSingleLine(true);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1)); name.setPadding(0, dp(3), dp(6), dp(3));
            WeatherData.Day day = sample == null ? null : sample.day(today.plusDays(i).toString());
            String percent = i == 0 ? "" : WeatherOverviewFormat.dailyPrecipitation(day);
            View probability;
            if (i == 0) {
                ImageView symbol = new ImageView(this);
                int pixels = Math.max(1, Math.round(probe.getTextSize() * WeatherFooter.ICON_EM));
                symbol.setImageBitmap(WeatherMetricIcon.draw(pixels, WeatherMetricIcon.Kind.DROP, LOW));
                symbol.setScaleType(ImageView.ScaleType.CENTER);
                row.addView(symbol); probability = symbol;
            } else {
                TextView value = label(row, percent, 17);
                value.setSingleLine(true); value.setGravity(Gravity.RIGHT);
                probability = value;
            }
            probability.setTag("daily-probability-" + i);
            probability.setPadding(0, dp(3), 0, dp(3));
            probability.setContentDescription((i == 0 ? "" : WeatherOverviewFormat.day(today, i) + ": ")
                    + "Highest hourly precipitation probability" + (i == 0 ? "" : " " + ("—".equals(percent) ? "unavailable" : percent)));
            LinearLayout.LayoutParams probabilityParams = new LinearLayout.LayoutParams(probabilityWidth, -2);
            probabilityParams.rightMargin = dp(8); probability.setLayoutParams(probabilityParams);
            for (int col = 0; col < 2; col++) {
                if (col == 1) {
                    TextView slash = label(row, "/", 16); slash.setTextColor(MUTED); slash.setGravity(Gravity.CENTER);
                    slash.setLayoutParams(new LinearLayout.LayoutParams(separatorWidth, -2));
                }
                TextView value = label(row, values[i][col], 16); value.setTextColor(i == 0 ? MUTED : col == 0 ? HIGH : LOW);
                if (i > 0 && col == 1) {
                    value.setText(minimumText(values[i][col]));
                    // The reserved sign slot must never turn a positive number into a spoken minus.
                    value.setContentDescription(values[i][col]);
                }
                value.setGravity(col == 0 ? Gravity.RIGHT : Gravity.LEFT);
                value.setLayoutParams(new LinearLayout.LayoutParams(widths[col], -2));
            }
        }
    }
    private void inlineMetric(LinearLayout cell, String text, WeatherMetricIcon.Kind kind, String description, String iconTag, String valueTag) {
        TextView value = new TextView(this); value.setText(text); value.setTextSize(16); CalendarWindowStyle.text(value);
        value.setTextDirection(View.TEXT_DIRECTION_LTR); value.setGravity(Gravity.CENTER_VERTICAL); value.setSingleLine(true); value.setTag(valueTag);
        int pixels = Math.max(1, Math.round(value.getTextSize() * WeatherFooter.ICON_EM));
        ImageView icon = new ImageView(this); icon.setTag(iconTag); icon.setContentDescription(description);
        icon.setImageBitmap(WeatherMetricIcon.draw(pixels, kind, LOW));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(pixels, pixels); iconParams.rightMargin = dp(6);
        cell.addView(icon, iconParams); cell.addView(value, new LinearLayout.LayoutParams(-2, -2));
    }
    private static boolean positiveMinimum(String value) { return !"—".equals(value) && !value.startsWith("−"); }
    private static CharSequence minimumText(String value) {
        if (!positiveMinimum(value)) return value;
        // Object replacement is a presentation-only, measured sign slot: no fake minus or spaces in the number.
        SpannableString result = new SpannableString("\ufffc" + value);
        result.setSpan(new MinimumSignSpace(), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }
    private static final class MinimumSignSpace extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            if (metrics != null) paint.getFontMetricsInt(metrics);
            return Math.round(paint.measureText("−"));
        }
        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int baseline, int bottom, Paint paint) { /* Deliberately transparent; digits retain the original paint/baseline. */ }
    }
    /** Overview-only optical alignment: the shared 100-unit artwork has asymmetric empty space. */
    private static final class CurrentWeatherIcon extends View {
        private final int code;
        private final boolean day;
        private Bitmap artwork;
        private int inkOffsetY;
        CurrentWeatherIcon(Context context, int code, boolean day) { super(context); this.code = code; this.day = day; }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (width <= 0 || height <= 0) return;
            artwork = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            WeatherIcon.draw(new Canvas(artwork), new RectF(0, 0, width, height), code, day);
            // Scan only when this immutable state/size is created, never during onDraw.
            int[] pixels = new int[width * height]; artwork.getPixels(pixels, 0, width, 0, 0, width, height);
            int top = height, bottom = -1;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                if ((pixels[y * width + x] >>> 24) > 32) { top = Math.min(top, y); bottom = y; }
            }
            inkOffsetY = bottom < top ? 0 : Math.round((height - top - bottom - 1) / 2f);
        }
        @Override protected void onDraw(Canvas canvas) {
            if (artwork != null) canvas.drawBitmap(artwork, 0, inkOffsetY, null);
        }
    }
    /** Center current temperature/range contours, not the font's ascent/descent or padded line box. */
    private static final class CurrentTemperature extends TextView {
        private final Rect ink = new Rect();
        CurrentTemperature(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            String value = getText().toString(); getPaint().getTextBounds(value, 0, value.length(), ink);
            int saved = canvas.save();
            canvas.translate(0, Math.round(getHeight() / 2f - getBaseline() - ink.exactCenterY()));
            super.onDraw(canvas); canvas.restoreToCount(saved);
        }
    }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.HORIZONTAL); result.setGravity(Gravity.CENTER_VERTICAL);
        if (parent != null) parent.addView(result, new LinearLayout.LayoutParams(-1, -2)); return result;
    }
    private TextView label(LinearLayout parent, String text, int size) {
        TextView label = new TextView(this); label.setText(text); label.setTextSize(size); CalendarWindowStyle.text(label);
        label.setTextDirection(View.TEXT_DIRECTION_LTR); parent.addView(label, new LinearLayout.LayoutParams(-2, -2)); return label;
    }
    private void colored(LinearLayout parent, String text, int color, int size) {
        // Current Max/Min share the number/icon contour center, including each degree sign.
        TextView value = new CurrentTemperature(this); value.setText(text); value.setTextSize(size);
        CalendarWindowStyle.text(value); value.setTextColor(color); value.setTextDirection(View.TEXT_DIRECTION_LTR);
        parent.addView(value, new LinearLayout.LayoutParams(-2, -2));
    }
    private void divider() {
        View line = new View(this); line.setBackgroundColor(0xff464d57);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1)); params.topMargin = dp(10); params.bottomMargin = dp(10); body.addView(line, params);
    }
    private int dp(int value) { return CalendarWindowStyle.dp(this, value); }

    /** Reflows rather than shrinking text. Vertical ScrollView owns any extra height. */
    private static final class AdaptiveRow extends LinearLayout {
        private final int breakpoint;
        AdaptiveRow(Context context, int breakpoint) { super(context); this.breakpoint = breakpoint; setGravity(Gravity.CENTER_VERTICAL); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = View.MeasureSpec.getSize(widthSpec);
            getChildAt(0).measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int required = getChildAt(0).getMeasuredWidth() + getChildAt(1).getMinimumWidth() + CalendarWindowStyle.dp(getContext(), 14);
            boolean stacked = available < Math.max(required, CalendarWindowStyle.dp(getContext(), Math.round(breakpoint * getResources().getConfiguration().fontScale)));
            setOrientation(stacked ? VERTICAL : HORIZONTAL);
            for (int i = 0; i < getChildCount(); i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(stacked ? -1 : i == 0 ? -2 : 0, -2, stacked || i == 0 ? 0 : 1);
                params.topMargin = stacked && i > 0 ? CalendarWindowStyle.dp(getContext(), 6) : 0;
                params.rightMargin = !stacked && i == 0 ? CalendarWindowStyle.dp(getContext(), 14) : 0;
                getChildAt(i).setLayoutParams(params);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
