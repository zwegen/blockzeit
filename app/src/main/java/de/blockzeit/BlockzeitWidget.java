package de.blockzeit;

import de.blockzeit.R;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import de.blockzeit.core.DayMath;
import de.blockzeit.core.WidgetMode;
import de.blockzeit.core.History;
import de.blockzeit.core.TextGeometry;
import de.blockzeit.core.MarketData;
import de.blockzeit.core.PriceChange;
import de.blockzeit.core.WeatherData;
import de.blockzeit.core.WeatherCode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@android.annotation.TargetApi(31)
public final class BlockzeitWidget extends AppWidgetProvider {
    static final String CENTER_REFRESH = "de.blockzeit.CENTER_REFRESH";
    static final String TOGGLE = "de.blockzeit.TOGGLE";
    static final String PREVIOUS = "de.blockzeit.PREVIOUS";
    static int[] ids(Context c) {
        return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, BlockzeitWidget.class));
    }
    static boolean active(Context c, int id) {
        for (int active : ids(c)) if (active == id) return true;
        return false;
    }
    @Override public void onUpdate(Context c, AppWidgetManager manager, int[] ids) {
        Scheduler.ensure(c);
        try { renderAll(c); }
        finally { Scheduler.enqueue(c); }
    }
    @Override public void onEnabled(Context c) { Scheduler.ensure(c); }
    @Override public void onDisabled(Context c) { CalendarRefresh.stop(); Scheduler.cancel(c); }
    @Override public void onDeleted(Context c, int[] ids) {
        for (int id : ids) {
            if (Store.remove(c, id)) WeatherStore.remove(c, id);
            else WeatherJob.cancelWidget(id); // Retention failed: keep settings/place, but stop obsolete work.
            geometryPrefs(c).edit().remove(Integer.toString(id)).apply();
        }
    }
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        render(c, id, m, options);
    }
    @Override public void onReceive(Context c, Intent intent) {
        if (CENTER_REFRESH.equals(intent.getAction())) {
            CenterRefresh.request(c, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
                    intent.getIntExtra("mode", WidgetMode.CLOCK));
        } else if (TOGGLE.equals(intent.getAction()) || PREVIOUS.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (active(c, id)) {
                int mode = PREVIOUS.equals(intent.getAction()) ? Store.previousMode(c, id) : Store.nextMode(c, id);
                Store.setMode(c, id, mode);
                Scheduler.ensure(c);
                try { renderAfterModeSwitch(c, id); }
                finally { Scheduler.enqueueMode(c, id); }
            }
        } else super.onReceive(c, intent);
    }
    static void renderAll(Context c) { for (int id : ids(c)) render(c, id); }
    static void renderCachedAll(Context c) { for (int id : ids(c)) renderAfterModeSwitch(c, id); }
    public static void onForeground(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        Scheduler.ensure(c);
        Scheduler.catchUp(c);
    }
    static PendingIntent toggle(Context c, int id) {
        Intent intent = new Intent(c, BlockzeitWidget.class).setAction(TOGGLE)
                .setData(Uri.parse("blockzeit://widget/" + id + "/toggle"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getBroadcast(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static PendingIntent previous(Context c, int id) {
        Intent intent = new Intent(c, BlockzeitWidget.class).setAction(PREVIOUS)
                .setData(Uri.parse("blockzeit://widget/" + id + "/previous"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getBroadcast(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static PendingIntent centerRefresh(Context c, int id) {
        return centerRefresh(c, id, Store.mode(c, id));
    }
    private static PendingIntent centerRefresh(Context c, int id, int mode) {
        Intent intent = new Intent(c, BlockzeitWidget.class).setAction(CENTER_REFRESH)
                .setData(Uri.parse("blockzeit://widget/" + id + "/refresh/" + mode))
                .putExtra("mode", mode).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getBroadcast(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static PendingIntent settings(Context c, int id) {
        // Normal settings launch, not ACTION_APPWIDGET_CONFIGURE / widget-add flow.
        Intent intent = new Intent(c, SettingsActivity.class).setAction(Intent.ACTION_MAIN)
                .setData(Uri.parse("blockzeit://widget/" + id + "/settings"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getActivity(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static PendingIntent calendar(Context c, int id) {
        return CalendarActivity.pendingIntent(c, id, Store.calendarId(c, id));
    }
    static PendingIntent clock(Context c, int id, boolean alarm) {
        Intent intent = new Intent(c, ClockActivity.class)
                .setData(Uri.parse("blockzeit://widget/" + id + (alarm ? "/alarm" : "/clock")))
                .putExtra("alarm", alarm);
        return PendingIntent.getActivity(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static void render(Context c, int id) {
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        render(c, id, manager, manager.getAppWidgetOptions(id));
    }
    static void renderAfterModeSwitch(Context c, int id) {
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        render(c, id, manager, manager.getAppWidgetOptions(id), false);
    }
    private static SharedPreferences geometryPrefs(Context c) {
        // Geometry is independent of user settings and survives provider process recreation.
        return c.getSharedPreferences("widget_geometry", Context.MODE_PRIVATE);
    }
    private static WidgetSizing reportedGeometry(Bundle options) {
        if (options == null) return null;
        return WidgetSizing.valid(optionDimension(options, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                optionDimension(options, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                optionDimension(options, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
                optionDimension(options, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT));
    }
    private static int optionDimension(Bundle options, String key) {
        Object value = options.get(key);
        return value instanceof Integer ? (Integer) value : 0;
    }
    private static void render(Context c, int id, AppWidgetManager manager, Bundle options) {
        render(c, id, manager, options, true);
    }
    private static void render(Context c, int id, AppWidgetManager manager, Bundle options, boolean acquireCalendar) {
        // Mode switches publish the cache only; calendar acquisition retains its native triggers.
        if (!Scheduler.isInteractive(c)) return;
        if (acquireCalendar) CalendarRefresh.request(c, id);
        WidgetSizing reported = reportedGeometry(options);
        WidgetSizing geometry = WidgetSizing.resolve(reported,
                WidgetSizing.restore(geometryPrefs(c).getString(Integer.toString(id), "")));
        float density = c.getResources().getDisplayMetrics().density;
        long now = System.currentTimeMillis() / 1000;
        DayMath.Day day = DayMath.at(now, ZoneId.systemDefault());
        Store.Snapshot snapshot = Store.load(c);
        boolean known = snapshot.displayable(now, day);
        int actual = known ? History.count(snapshot.blocks, day) : 0;
        // Keep publication history for migration/rollback protection, not as the live dial clock.
        // Frame acquisition-age validation stays separate from current-day display eligibility.
        RingFrame frame = RingFrame.select(Store.ringFrame(c, id), now, ZoneId.systemDefault(), snapshot.fresh(now, day),
                actual, snapshot.checked, snapshot.dayKey, snapshot.blocks.isEmpty() ? "" : snapshot.blocks.get(0).hash);
        RemoteViews portrait = renderSize(c, id, geometry.portraitDiameter(), density, now, day, snapshot, known, actual);
        // The host selects its own orientation, even when the provider context is stale on wake.
        RemoteViews views = geometry.landscapeDiameter() == geometry.portraitDiameter() ? portrait
                : new RemoteViews(renderSize(c, id, geometry.landscapeDiameter(), density, now, day, snapshot, known, actual), portrait);
        manager.updateAppWidget(id, views);
        // Invalid/incomplete reports never overwrite the last successfully published geometry.
        if (reported != null) geometryPrefs(c).edit().putString(Integer.toString(id), reported.encode()).apply();
        // Remember only a frame whose RemoteViews publication succeeded (all widget modes).
        Store.rememberRing(c, id, frame);
    }
    private static RemoteViews renderSize(Context c, int id, int diameter, float density, long now,
            DayMath.Day day, Store.Snapshot snapshot, boolean known, int actual) {
        int mode = Store.mode(c, id);
        boolean block = mode == WidgetMode.BLOCK;
        boolean market = mode == WidgetMode.MARKET;
        boolean weather = mode == WidgetMode.WEATHER;
        boolean calendar = mode == WidgetMode.CALENDAR;
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget);
        views.setTextViewText(R.id.bb_widget_pool, ""); // Also clears a previously applied calendar date span.
        views.setTextColor(R.id.bb_widget_pool, calendar ? android.graphics.Color.WHITE : RingRenderer.ORANGE);
        views.setViewLayoutWidth(R.id.bb_widget_dial, diameter, TypedValue.COMPLEX_UNIT_DIP);
        views.setViewLayoutHeight(R.id.bb_widget_dial, diameter, TypedValue.COMPLEX_UNIT_DIP);
        // Split only the existing background receiver, below every existing data-row target.
        views.setViewLayoutWidth(R.id.bb_widget_previous, (Math.round(diameter * density) + 1) / 2,
                TypedValue.COMPLEX_UNIT_PX);
        bindOuterTouch(views, c, id, Math.round(diameter * density));
        // Keep the existing per-row sizing and native/live TextClocks; translate the full ink group below.
        for (int item : new int[]{R.id.bb_widget_date, R.id.bb_widget_pool, R.id.bb_widget_alarm, R.id.bb_widget_period, R.id.bb_widget_currency, R.id.bb_widget_fees}) textSize(views, item, diameter, .053f, density);
        views.setViewVisibility(R.id.bb_widget_clock_row, block || market || weather || calendar ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.bb_widget_period, block || market || weather || calendar ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.bb_widget_clock, block || market || weather || calendar ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.bb_widget_date, block || market || weather || calendar ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.bb_widget_height, block || calendar ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.bb_widget_pool, block || calendar ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.bb_widget_price_row, market ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.bb_widget_fees, market ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.bb_widget_weather, weather ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.bb_widget_weather_row, weather ? View.VISIBLE : View.GONE);
        WeatherData.Place weatherPlace = weather ? WeatherStore.place(c, id) : null;
        WeatherData.Sample weatherSample = weatherPlace == null ? null : WeatherStore.sample(c, id);
        String temperature = weatherTemperature(weatherSample);
        // Reuse the existing location TextView as the optional title; never add a second location row.
        views.setViewVisibility(R.id.bb_widget_weather_place, !weather || weatherPlace != null ? View.VISIBLE : View.GONE);
        String titleText = block ? "Blocktime" : market ? "BTC Price"
                : weather ? weatherPlace == null ? "" : weatherPlace.name : clockYear(now, ZoneId.systemDefault());
        views.setTextViewText(R.id.bb_widget_weather_place, titleText);
        String weatherTimezone = weatherPlace == null ? "UTC" : weatherPlace.timezone;
        String weatherRange = WeatherFooter.ranges(weatherSample, now, weatherTimezone);
        String weatherWind = WeatherFooter.wind(weatherSample);
        String weatherRain = WeatherFooter.rain(weatherSample, now);
        String weatherMetric = WeatherFooter.geometryMetric(weatherSample, now);
        boolean weatherRainSelected = WeatherFooter.rainSelected(weatherSample, now);
        String blockText = snapshot.blocks.isEmpty() ? "—" : blockHeight(snapshot.blocks.get(0).height);
        views.setTextViewText(R.id.bb_widget_height, blockText);
        String status = status(c, snapshot);
        views.setContentDescription(R.id.bb_widget_ring, c.getString(R.string.widget_description) + " " + status);
        views.setContentDescription(R.id.bb_widget_height, status);
        String footerSample;
        String footerLabel = null, footerSuffix = "";
        String currency = Store.currency(c, id);
        PriceDisplay quote = market ? PriceDisplay.read(c, currency, now) : null;
        String priceText = market ? quote.price : Store.priceSample(c, currency, now).text;
        String suffix = " " + MarketData.symbol(currency);
        if (calendar) {
            footerSample = ClockDate.sample(ClockDate.pattern(), now * 1000L); // Fixed existing clock/date positions, independent of event/title length.
            views.setViewVisibility(R.id.bb_widget_alarm, View.GONE);
        } else if (market) {
            Store.FeeSample sample = Store.fees(c);
            String fees = sample == null ? "— / — / — sat/vB" : sample.text;
            PriceChange change = quote.change;
            footerSample = fees + " • " + change.text;
            views.setTextViewText(R.id.bb_widget_price, priceText);
            views.setTextViewText(R.id.bb_widget_currency, suffix);
            views.setTextViewText(R.id.bb_widget_fees, marketFooter(fees, footerSample, change));
            views.setContentDescription(R.id.bb_widget_price, "BTC/" + currency + " " + priceText + suffix + "; " + footerSample);
            views.setViewVisibility(R.id.bb_widget_alarm, View.GONE);
        } else if (weather) {
            footerSample = weatherRange + weatherMetric;
            views.setTextViewText(R.id.bb_widget_weather_range, WeatherFooter.styledRanges(weatherRange));
            views.setViewVisibility(R.id.bb_widget_alarm, View.GONE);
            String weatherDescription = weatherPlace == null ? c.getString(R.string.weather_no_place) : weatherPlace.label();
            weatherDescription += "; " + temperature + " Celsius; " + WeatherFooter.description(weatherSample, now, weatherTimezone);
            if (weatherSample != null) {
                weatherDescription += "; " + WeatherCode.description(weatherSample.code, weatherSample.isDay);
                if (weatherSample.stale(now)) weatherDescription += "; " + c.getString(R.string.weather_stale);
            }
            views.setContentDescription(R.id.bb_widget_weather, weatherDescription);
            views.setContentDescription(R.id.bb_widget_weather_row, weatherDescription);
        } else if (block) {
            String pool = Store.poolText(snapshot);
            String rate = " · " + Store.hashrateText(c, now);
            footerSample = pool + rate;
            footerLabel = pool; footerSuffix = rate;
            // Weighted/ellipsized pool yields space; the separate unweighted rate keeps its unit visible.
            views.setTextViewText(R.id.bb_widget_pool, pool);
            views.setTextViewText(R.id.bb_widget_alarm, rate);
            views.setViewVisibility(R.id.bb_widget_alarm, View.VISIBLE);
        } else {
            AlarmManager alarms = c.getSystemService(AlarmManager.class);
            AlarmManager.AlarmClockInfo next = alarms.getNextAlarmClock();
            String alarm = next == null ? "" : " · ⏰︎ " + ClockDate.alarm(c, next.getTriggerTime());
            // Supply BOTH patterns: native TextClock observes the phone's time-format setting live.
            views.setCharSequence(R.id.bb_widget_clock, "setFormat12Hour", ClockDate.numericPattern());
            views.setCharSequence(R.id.bb_widget_clock, "setFormat24Hour", ClockDate.timePattern(true));
            views.setCharSequence(R.id.bb_widget_period, "setFormat12Hour", " a");
            // Native TextClock falls back only on null, not empty: 24h has no suffix footprint.
            views.setCharSequence(R.id.bb_widget_period, "setFormat24Hour", "");
            String datePattern = ClockDate.pattern();
            views.setCharSequence(R.id.bb_widget_date, "setFormat12Hour", datePattern);
            views.setCharSequence(R.id.bb_widget_date, "setFormat24Hour", datePattern);
            views.setTextColor(R.id.bb_widget_date, RingRenderer.BLUE);
            footerLabel = ClockDate.sample(datePattern, now * 1000L); footerSuffix = alarm;
            footerSample = footerLabel + alarm;
            views.setTextViewText(R.id.bb_widget_alarm, alarm);
            // Remove the whole suffix when absent; the native date remains centered and live.
            views.setViewVisibility(R.id.bb_widget_alarm, next == null ? View.GONE : View.VISIBLE);
        }
        // Measure visible ink, including the current localized footer, rather than merely halving empty boxes.
        float blockFont = fittedBlockFont(diameter, density, blockText);
        // Only primary glyphs shrink; retain old fitted row/footer measurements and baselines.
        views.setViewPadding(R.id.bb_widget_clock, 0, PrimaryText.topPadding(diameter, density, .1944f), 0, PrimaryText.bottomPadding(diameter, density, .1944f));
        views.setViewPadding(R.id.bb_widget_height, 0, PrimaryText.topPadding(diameter, density, blockFont), 0, PrimaryText.bottomPadding(diameter, density, blockFont));
        float[] rowCenters = footerCenter(diameter, density, block ? blockText : "0123456789:", footerSample, block ? blockFont : .1944f);
        layout(views, R.id.bb_widget_clock_row, diameter, rowCenters[0], .20f);
        textSize(views, R.id.bb_widget_clock, diameter, .1944f, density);
        geometry(views, R.id.bb_widget_height, diameter, rowCenters[0], .20f, blockFont, density);
        layout(views, R.id.bb_widget_footer, diameter, rowCenters[1], .08f);
        float titlePrimaryTop = block ? titleTextTop(diameter, density, rowCenters[0], blockText, blockFont, "", .053f)
                : titleTextTop(diameter, density, rowCenters[0], "0123456789:", .1944f, "", .053f);
        float titleFont = .053f; // Match the active third line, including its existing fit.
        float primaryFont = block ? blockFont : .1944f;
        String primaryText = block ? blockText : "0123456789:";
        String primarySuffix = "";
        int primaryTopPadding = PrimaryText.topPadding(diameter, density, primaryFont);
        int primaryBottomPadding = PrimaryText.bottomPadding(diameter, density, primaryFont);
        if (market) {
            float footerFont = fittedMarketFooterFont(diameter, density, footerSample);
            titleFont = footerFont;
            // Uniformly scale the WHOLE second line only on overflow; suffix stays the same small size.
            textSize(views, R.id.bb_widget_fees, diameter, footerFont, density);
            textSize(views, R.id.bb_widget_currency, diameter, footerFont, density);
            float priceFont = fittedPriceFont(diameter, density, priceText, suffix, footerFont);
            float[] priceCenters = footerCenter(diameter, density, priceText, footerSample, priceFont, footerFont);
            rowCenters = priceCenters;
            primaryFont = priceFont; primaryText = priceText; primarySuffix = suffix;
            primaryTopPadding = PrimaryText.topPadding(diameter, density, priceFont);
            primaryBottomPadding = PrimaryText.bottomPadding(diameter, density, priceFont);
            views.setViewPadding(R.id.bb_widget_price, 0, PrimaryText.topPadding(diameter, density, priceFont), 0, PrimaryText.bottomPadding(diameter, density, priceFont));
            layout(views, R.id.bb_widget_price_row, diameter, priceCenters[0], .20f);
            textSize(views, R.id.bb_widget_price, diameter, priceFont, density);
            layout(views, R.id.bb_widget_footer, diameter, priceCenters[1], .08f);
            titlePrimaryTop = titleTextTop(diameter, density, priceCenters[0], priceText, priceFont, suffix, footerFont);
        }
        if (weather) {
            float footerFont = WeatherFooter.font(diameter, density, weatherRange,
                    weatherRainSelected ? weatherRain : weatherWind);
            // Fit only the selected compact metric; retain all other row geometry.
            float geometryFont = WeatherFooter.font(diameter, density, weatherRange, weatherMetric);
            titleFont = geometryFont;
            float weatherFont = WeatherRow.font(diameter, density, temperature);
            float[] weatherCenters = footerCenter(diameter, density, temperature, weatherRange + weatherMetric, weatherFont, geometryFont);
            rowCenters = weatherCenters;
            layout(views, R.id.bb_widget_weather, diameter, weatherCenters[0], .20f);
            WeatherRow.bind(views, R.id.bb_widget_weather, diameter, density, temperature,
                    weatherSample == null ? -1 : weatherSample.code, weatherSample != null && weatherSample.isDay, geometryFont);
            layout(views, R.id.bb_widget_footer, diameter, weatherCenters[1], .08f);
            WeatherRow.Geometry ink = WeatherRow.geometry(diameter, density, temperature, geometryFont);
            float firstInk = ink.titleTop; // Keep the existing title position while the weather icon shrinks.
            if (!ink.marker.isEmpty()) firstInk = Math.min(firstInk, ink.markerBounds.top);
            titlePrimaryTop = Math.round(diameter * density * (weatherCenters[0] - .10f)) + firstInk;
            textSize(views, R.id.bb_widget_weather_range, diameter, footerFont, density);
            // A plain container with one selected page: no native flipping or timer.
            views.removeAllViews(R.id.bb_widget_weather_metrics);
            views.addView(R.id.bb_widget_weather_metrics,
                    WeatherFooter.metricViews(c.getPackageName(), diameter, density, footerFont,
                            weatherRainSelected ? weatherRain : weatherWind, weatherRainSelected, weatherSample, now));
        }
        float[] title = widgetTitleGeometry(diameter, density, titlePrimaryTop, titleFont);
        layout(views, R.id.bb_widget_weather_place, diameter, title[0], title[1]);
        views.setViewLayoutWidth(R.id.bb_widget_weather_place, title[2], TypedValue.COMPLEX_UNIT_DIP);
        textSize(views, R.id.bb_widget_weather_place, diameter, titleFont, density);
        views.setTextColor(R.id.bb_widget_weather_place, Store.primaryTextColor(c, id));
        float calendarPrimaryPixels = 0;
        if (calendar) {
            CalendarRefresh.State agenda = CalendarRefresh.state(c, id, now * 1000, ZoneId.systemDefault());
            java.util.List<de.blockzeit.core.CalendarAgenda.Entry> entries =
                    de.blockzeit.core.CalendarAgenda.upcoming(agenda.entries, now * 1000, ZoneId.systemDefault());
            String heading = entries.isEmpty() ? "Kalender" : entries.get(0).when(now * 1000, ZoneId.systemDefault());
            String main = entries.isEmpty() ? agenda.message : entries.get(0).title;
            String followingDate = entries.size() < 2 ? "" : entries.get(1).dateText(ZoneId.systemDefault());
            String following = entries.size() < 2 ? "—" : entries.get(1).following(ZoneId.systemDefault());
            // Keep the old row anchors and fitter; center their final visible strings together below.
            float footerPixels = diameter * density * titleFont;
            CalendarText.Fit top = CalendarText.fit(heading, title[2] * density - density, footerPixels, footerPixels, false);
            CalendarText.Fit primary = CalendarText.fit(main, diameter * density * .64f - density,
                    diameter * density * .1944f * PrimaryText.SCALE, footerPixels, true, diameter * density * .20f - 2 * density);
            CalendarText.Fit bottom = CalendarText.fit(following, diameter * density * .64f - density,
                    footerPixels, footerPixels, false);
            views.setViewPadding(R.id.bb_widget_height, 0, 0, 0, 0);
            views.setTextViewTextSize(R.id.bb_widget_height, TypedValue.COMPLEX_UNIT_PX, primary.pixels);
            calendarPrimaryPixels = primary.pixels;
            views.setTextViewText(R.id.bb_widget_weather_place, top.text);
            views.setTextViewText(R.id.bb_widget_height, primary.text);
            views.setTextViewText(R.id.bb_widget_pool, CalendarText.following(bottom, followingDate));
            views.setContentDescription(R.id.bb_widget_height, heading + "; " + main + "; " + following);
            titleText = top.text; primaryText = primary.text; footerSample = bottom.text;
            primaryFont = primary.pixels / (diameter * density * PrimaryText.SCALE);
            primaryTopPadding = 0; primaryBottomPadding = 0;
        }
        // Only a common, whole-physical-pixel translation: all old baselines, row sizes,
        // relative ink gaps, horizontal fitting and existing click owners stay unchanged.
        float pixels = diameter * density;
        int primaryTop = Math.round(pixels * (rowCenters[0] - .10f));
        int footerTop = Math.round(pixels * (rowCenters[1] - .04f));
        int titleTop = Math.round(pixels * (title[0] - title[1] / 2f));
        TextPaint titlePaint = new TextPaint(WeatherFooter.paint(pixels * titleFont));
        String visibleTitle = TextUtils.ellipsize(titleText, titlePaint,
                Math.round(title[2] * density), TextUtils.TruncateAt.END, false, null).toString();
        float[] titleInk = widgetLineInk(visibleTitle, pixels * titleFont, false,
                Math.round(pixels * title[1]), 0, 0, titleTop);
        float[] primaryInk = widgetLineInk(primaryText, pixels * primaryFont * PrimaryText.SCALE, true,
                Math.round(pixels * .20f), primaryTopPadding, primaryBottomPadding, primaryTop);
        float[] suffixInk = widgetInkAtBaseline(primarySuffix, pixels * titleFont, false, primaryInk[2]);
        if (weather) {
            WeatherRow.Geometry ink = WeatherRow.geometry(diameter, density, temperature, titleFont);
            primaryInk = new float[]{primaryTop + Math.min(ink.textBounds.top, ink.iconBounds.top),
                    primaryTop + Math.max(ink.textBounds.bottom, ink.iconBounds.bottom)};
            if (!ink.marker.isEmpty()) {
                primaryInk[0] = Math.min(primaryInk[0], primaryTop + ink.markerBounds.top);
                primaryInk[1] = Math.max(primaryInk[1], primaryTop + ink.markerBounds.bottom);
            }
        }
        String visibleFooter = footerSample;
        if (footerLabel != null) {
            // The existing weighted date/pool yields to the unweighted alarm/rate.
            // Measure only the glyphs that survive its unchanged END ellipsis.
            int suffixWidth = (int) Math.ceil(titlePaint.measureText(footerSuffix));
            visibleFooter = TextUtils.ellipsize(footerLabel, titlePaint,
                    Math.max(0, Math.round(pixels) - suffixWidth), TextUtils.TruncateAt.END, false, null) + footerSuffix;
        }
        float[] footerInk = widgetLineInk(visibleFooter, pixels * titleFont, false,
                Math.round(pixels * .08f), 0, 0, footerTop);
        int shift = widgetTextBlockShift(pixels, titleInk, primaryInk, suffixInk, footerInk);
        // Move the existing row containers (and their click targets), never the ring or a new overlay.
        for (int item : new int[]{R.id.bb_widget_clock_row, R.id.bb_widget_height,
                R.id.bb_widget_price_row, R.id.bb_widget_weather})
            views.setViewLayoutMargin(item, RemoteViews.MARGIN_TOP, primaryTop + shift, TypedValue.COMPLEX_UNIT_PX);
        views.setViewLayoutMargin(R.id.bb_widget_footer, RemoteViews.MARGIN_TOP, footerTop + shift, TypedValue.COMPLEX_UNIT_PX);
        views.setViewLayoutMargin(R.id.bb_widget_weather_place, RemoteViews.MARGIN_TOP, titleTop + shift, TypedValue.COMPLEX_UNIT_PX);
        if (calendar) {
            // Deliberate middle-row-only exception AFTER the unchanged block translation.
            // The glyph test switches one fixed nominal-size nudge on/off, never recenters it.
            int nudge = calendarMiddleNudge(primaryText, calendarPrimaryPixels,
                    pixels * .1944f * PrimaryText.SCALE);
            if (nudge != 0) views.setViewLayoutMargin(R.id.bb_widget_height, RemoteViews.MARGIN_TOP,
                    primaryTop + shift + nudge, TypedValue.COMPLEX_UNIT_PX);
        }
        views.setOnClickPendingIntent(R.id.bb_widget_ring, toggle(c, id));
        views.setOnClickPendingIntent(R.id.bb_widget_previous, previous(c, id));
        PendingIntent refreshIntent = block ? BlocksActivity.pendingIntent(c, id)
                : market ? PriceChartActivity.pendingIntent(c, id) : centerRefresh(c, id, mode);
        views.setOnClickPendingIntent(R.id.bb_widget_height, refreshIntent);
        views.setOnClickPendingIntent(R.id.bb_widget_pool, refreshIntent);
        views.setOnClickPendingIntent(R.id.bb_widget_price, refreshIntent);
        views.setOnClickPendingIntent(R.id.bb_widget_currency, refreshIntent);
        views.setOnClickPendingIntent(R.id.bb_widget_fees, refreshIntent);
        PendingIntent weatherIntent = weather ? WeatherOverviewActivity.pendingIntent(c, id) : refreshIntent;
        views.setOnClickPendingIntent(R.id.bb_widget_weather, weatherIntent);
        views.setOnClickPendingIntent(R.id.bb_widget_weather_row, weatherIntent);
        views.setOnClickPendingIntent(R.id.bb_widget_weather_place, null); // Clear calendar-only action on mode changes.
        if (calendar) {
            PendingIntent calendarIntent = calendar(c, id);
            views.setOnClickPendingIntent(R.id.bb_widget_weather_place, calendarIntent);
            views.setOnClickPendingIntent(R.id.bb_widget_height, calendarIntent);
            views.setOnClickPendingIntent(R.id.bb_widget_pool, calendarIntent);
        }
        views.setOnClickPendingIntent(R.id.bb_widget_clock, clock(c, id, false));
        views.setOnClickPendingIntent(R.id.bb_widget_clock_row, clock(c, id, false));
        views.setOnClickPendingIntent(R.id.bb_widget_period, clock(c, id, false));
        views.setOnClickPendingIntent(R.id.bb_widget_date, clock(c, id, false));
        // Replace the shared row binding on every mode switch.
        views.setOnClickPendingIntent(R.id.bb_widget_alarm, block ? refreshIntent : clock(c, id, true));
        // Keep the dimmable background behind the data; draw the ring above it.
        // Base mode controls the ring, not the selected center. Reuse the exact renderer:
        // civil-time completed slots have neither a block deficit/overflow nor stale-data tint.
        boolean blockRing = Store.blockEnabled(c, id);
        Bitmap bitmap = RingRenderer.draw(Math.round(diameter * density), diameter, 0,
                blockRing ? actual : day.expected, day, !blockRing || known,
                java.time.Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate(),
                Store.elapsedDaysColor(c, id), Store.remainingDaysColor(c, id));
        views.setInt(R.id.bb_widget_ring, "setImageAlpha",
                Math.round(Math.max(0, Math.min(100, Store.opacity(c, id))) * 2.55f));
        views.setImageViewBitmap(R.id.bb_widget_ring_foreground, bitmap);
        return views;
    }
    /** Touch-only mask: preserve every visible view and its original inner PendingIntent.
     * The opening is a centered square, 55% of the rounded physical dial diameter.
     * Integer centering leaves at most a one-pixel difference between opposite insets. */
    private static void bindOuterTouch(RemoteViews views, Context c, int id, int pixels) {
        int inner = Math.round(pixels * .55f);
        int start = (pixels - inner) / 2, end = start + inner;
        int middle = (pixels + 1) / 2; // Same physical seam as the original previous target.
        PendingIntent back = previous(c, id), forward = toggle(c, id);
        touchRect(views, R.id.bb_widget_touch_left, 0, 0, start, pixels, back);
        touchRect(views, R.id.bb_widget_touch_right, end, 0, pixels - end, pixels, forward);
        touchRect(views, R.id.bb_widget_touch_top_left, start, 0, middle - start, start, back);
        touchRect(views, R.id.bb_widget_touch_top_right, middle, 0, end - middle, start, forward);
        touchRect(views, R.id.bb_widget_touch_bottom_left, start, end, middle - start, pixels - end, back);
        touchRect(views, R.id.bb_widget_touch_bottom_right, middle, end, end - middle, pixels - end, forward);
    }
    private static void touchRect(RemoteViews views, int target, int left, int top,
            int width, int height, PendingIntent action) {
        views.setViewLayoutWidth(target, width, TypedValue.COMPLEX_UNIT_PX);
        views.setViewLayoutHeight(target, height, TypedValue.COMPLEX_UNIT_PX);
        views.setViewLayoutMargin(target, RemoteViews.MARGIN_LEFT, left, TypedValue.COMPLEX_UNIT_PX);
        views.setViewLayoutMargin(target, RemoteViews.MARGIN_TOP, top, TypedValue.COMPLEX_UNIT_PX);
        views.setOnClickPendingIntent(target, action);
    }
    // Bounds use the same rounded height/center gravity as a single-line TextView without
    // font padding. Keep the baseline too, for baseline-aligned price/currency suffixes.
    static float[] widgetLineInk(String text, float fontPixels, boolean bold, int height,
            int paddingTop, int paddingBottom, int top) {
        Paint paint = WeatherFooter.paint(fontPixels);
        if (bold) paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        int baseline = top + paddingTop + Math.max(0,
                (height - paddingTop - paddingBottom - (metrics.descent - metrics.ascent)) / 2) - metrics.ascent;
        return widgetInkAtBaseline(text, fontPixels, bold, baseline);
    }
    private static float[] widgetInkAtBaseline(String text, float fontPixels, boolean bold, float baseline) {
        if (text.isEmpty()) return new float[]{Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, baseline};
        Paint paint = WeatherFooter.paint(fontPixels);
        if (bold) paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        Rect bounds = new Rect(); paint.getTextBounds(text, 0, text.length(), bounds);
        return new float[]{baseline + bounds.top, baseline + bounds.bottom, baseline};
    }
    static int widgetTextBlockShift(float pixels, float[]... lines) {
        float top = Float.POSITIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (float[] line : lines) { top = Math.min(top, line[0]); bottom = Math.max(bottom, line[1]); }
        return Math.round(Math.round(pixels) / 2f - (top + bottom) / 2f);
    }
    /** Final CalendarText.fit output only: hidden/truncated glyphs cannot veto the nudge. */
    static int calendarMiddleNudge(String visible, float fittedPixels, float nominalPixels) {
        // At tiny fitted sizes integer bounds round BOTH overshoot and a real descender
        // to 1px. Resolve the same final glyphs/face at >=64px; no displayed size changes.
        Paint paint = WeatherFooter.paint(Math.max(64f, fittedPixels));
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        Rect ink = new Rect(), roundBaseline = new Rect();
        paint.getTextBounds(visible, 0, visible.length(), ink);
        // Round glyphs normally overshoot the baseline; that is not a descender.
        // Measure the actual face (including fallback), not a character whitelist or word height.
        paint.getTextBounds("Oo", 0, 2, roundBaseline);
        if (ink.isEmpty() || ink.bottom > roundBaseline.bottom) return 0;
        return Math.max(1, Math.round(nominalPixels * .04f));
    }
    static String blockHeight(int height) {
        return String.format(Locale.GERMANY, "%,d", height);
    }
    static String clockYear(long epochSecond, ZoneId zone) {
        return DateTimeFormatter.ofPattern("uuuu", Locale.ROOT).withZone(zone)
                .format(java.time.Instant.ofEpochSecond(epochSecond));
    }
    // Native TextView ink top, including the unchanged primary baseline correction and suffix.
    private static float titleTextTop(int diameter, float density, float center, String text,
            float font, String suffix, float suffixFont) {
        float pixels = diameter * density;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextSize(pixels * font * PrimaryText.SCALE);
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        int top = PrimaryText.topPadding(diameter, density, font), bottom = PrimaryText.bottomPadding(diameter, density, font);
        int baseline = top + Math.max(0, (Math.round(pixels * .20f) - top - bottom - (metrics.descent - metrics.ascent)) / 2) - metrics.ascent;
        Rect ink = new Rect(); paint.getTextBounds(text, 0, text.length(), ink);
        int first = ink.top;
        if (!suffix.isEmpty()) {
            paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL)); paint.setTextSize(pixels * suffixFont);
            paint.getTextBounds(suffix, 0, suffix.length(), ink); first = Math.min(first, ink.top);
        }
        return Math.round(pixels * (center - .10f)) + baseline + first;
    }
    // Place one regular .04d title above the actual primary ink, never shift either data row.
    static float[] widgetTitleGeometry(int diameter, float density, float primaryInkTop) {
        float pixels = diameter * density;
        Paint paint = WeatherFooter.paint(pixels * .04f);
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        int height = Math.max(Math.round(pixels * .06f), metrics.descent - metrics.ascent);
        int gap = (int) Math.ceil(TextGeometry.gapPx(diameter, density)) + 1;
        int top = (int) Math.floor(primaryInkTop) - gap - height;
        // Existing data lines have a .64d horizontal safe budget. Keep the title box
        // inside that same .32d-radius content circle at its actual vertical position.
        float vertical = Math.max(Math.abs(top - pixels / 2f), Math.abs(top + height - pixels / 2f));
        float radius = pixels * .32f;
        float chord = 2f * (float) Math.sqrt(Math.max(0, radius * radius - vertical * vertical));
        float width = Math.max(1, (float) Math.floor(Math.min(pixels * .64f, chord) - 2f * density));
        return new float[]{(top + height / 2f) / pixels, height / pixels, width / density};
    }
    // Expand only the title box around its existing center for the larger font metrics.
    // The primary/footer, ring geometry and click routing remain untouched.
    static float[] widgetTitleGeometry(int diameter, float density, float primaryInkTop, float font) {
        float[] original = widgetTitleGeometry(diameter, density, primaryInkTop);
        float pixels = diameter * density;
        Paint.FontMetricsInt metrics = WeatherFooter.paint(pixels * font).getFontMetricsInt();
        int originalHeight = Math.round(original[1] * pixels);
        int extra = Math.max(0, metrics.descent - metrics.ascent - originalHeight);
        // Grow equally by whole pixels above/below: even the rasterized center stays fixed.
        int height = originalHeight + 2 * ((extra + 1) / 2);
        float vertical = Math.abs(original[0] * pixels - pixels / 2f) + height / 2f;
        float radius = pixels * .32f;
        float chord = 2f * (float) Math.sqrt(Math.max(0, radius * radius - vertical * vertical));
        float width = Math.max(1, (float) Math.floor(Math.min(pixels * .64f, chord) - 2f * density));
        return new float[]{original[0], height / pixels, width / density};
    }
    static String weatherTemperature(WeatherData.Sample sample) {
        return sample == null ? "—°" : weatherDegrees(sample.temperature);
    }
    static String weatherDegrees(double temperature) {
        String value = WeatherData.temperature(temperature).replace("°C", "°");
        return value.endsWith("°") ? value : value + "°";
    }
    private static float fittedBlockFont(int diameter, float density, String text) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextSize(diameter * density * .1944f);
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        float width = Math.max(paint.measureText(text), ink.width());
        // Keep glyphs inside the dial, including its longer time marker; allow 1dp for raster rounding.
        return TextGeometry.fittedFont(.1944f, width, diameter * density * .64f - density);
    }
    static CharSequence marketFooter(String fees, String fullLine, PriceChange change) {
        SpannableString styled = new SpannableString(fullLine);
        styled.setSpan(new ForegroundColorSpan(RingRenderer.ORANGE), 0, fees.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start=fees.length()+" • ".length();
        int direction=change==null?0:change.direction;
        styled.setSpan(new ForegroundColorSpan(direction>0?RingRenderer.GREEN:direction<0?0xffef5350:RingRenderer.BLUE),
                start, fullLine.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }
    private static float fittedMarketFooterFont(int diameter, float density, String text) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        paint.setTextSize(diameter * density * .053f);
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        float available = diameter * density * .64f - density;
        float font = TextGeometry.fittedFont(.053f, Math.max(paint.measureText(text), ink.width()), available);
        return fitHintedFont(paint, text, font, diameter * density, available);
    }
    private static float fittedPriceFont(int diameter, float density, String text, String currencySuffix, float footerFont) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        paint.setTextSize(diameter * density * footerFont);
        // Price digits are centered as in the native clock. Reserve suffix space on BOTH sides
        // so their right-hand currency sign stays inside the same .64d safe primary bounds.
        float suffix = paint.measureText(currencySuffix);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextSize(diameter * density * .1944f);
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        float available = diameter * density * .64f - density - 2 * suffix;
        float font = TextGeometry.fittedFont(.1944f, Math.max(paint.measureText(text), ink.width()), available);
        return fitHintedFont(paint, text, font, diameter * density * PrimaryText.SCALE, available);
    }
    private static float fitHintedFont(Paint paint, String text, float font, float pixelScale, float available) {
        // Tiny hinted glyph advances are not perfectly proportional. Keep the original fit if it
        // works; otherwise shrink this entire text uniformly using its actual final pixel metrics.
        Rect ink = new Rect();
        paint.setTextSize(pixelScale * font);
        paint.getTextBounds(text, 0, text.length(), ink);
        if (Math.max(paint.measureText(text), ink.width()) <= available) return font;
        float lower = 0, upper = font;
        for (int i = 0; i < 16; i++) {
            float candidate = (lower + upper) / 2;
            paint.setTextSize(pixelScale * candidate);
            paint.getTextBounds(text, 0, text.length(), ink);
            if (Math.max(paint.measureText(text), ink.width()) <= available) lower = candidate;
            else upper = candidate;
        }
        return lower;
    }
    private static float[] footerCenter(int diameter, float density, String primary, String footer, float primaryFont) {
        return footerCenter(diameter, density, primary, footer, primaryFont, .053f);
    }
    private static float[] footerCenter(int diameter, float density, String primary, String footer, float primaryFont, float footerFont) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Rect ink = new Rect();
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextSize(diameter * density * primaryFont);
        Paint.FontMetricsInt main = paint.getFontMetricsInt();
        // Clock gets a stable digit envelope while it ticks; block mode measures the actual value (also unknown/stale).
        paint.getTextBounds(primary, 0, primary.length(), ink);
        int mainTop = ink.top;
        int mainBottom = ink.bottom;
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        paint.setTextSize(diameter * density * footerFont);
        Paint.FontMetricsInt secondary = paint.getFontMetricsInt();
        paint.getTextBounds(footer, 0, footer.length(), ink);
        float footerPosition = TextGeometry.footerCenter(diameter, density, main.ascent, main.descent, mainBottom,
                secondary.ascent, secondary.descent, ink.top) + 3f / diameter;
        return TextGeometry.centeredRows(diameter, density, main.ascent, main.descent, mainTop,
                secondary.ascent, secondary.descent, ink.bottom, footerPosition);
    }
    private static void geometry(RemoteViews v, int id, int d, float center, float h, float font, float density) {
        layout(v, id, d, center, h);
        textSize(v, id, d, font, density);
    }
    private static void layout(RemoteViews v, int id, int d, float center, float h) {
        v.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, d * (center - h / 2f), TypedValue.COMPLEX_UNIT_DIP);
        v.setViewLayoutHeight(id, d * h, TypedValue.COMPLEX_UNIT_DIP);
    }
    private static void textSize(RemoteViews v, int id, int d, float font, float density) {
        float pixels = d * density * font;
        // Applied AFTER fittedBlockFont: even width-clamped seven-digit values get the full 2%.
        if (id == R.id.bb_widget_clock || id == R.id.bb_widget_height || id == R.id.bb_widget_price) pixels *= PrimaryText.SCALE;
        v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, pixels);
    }
    static String status(Context c, Store.Snapshot snapshot) {
        if (snapshot.blocks.isEmpty()) return c.getString(R.string.unknown);
        String time = DateTimeFormatter.ofPattern("d. MMM HH:mm", Locale.getDefault())
                .format(java.time.Instant.ofEpochSecond(snapshot.checked).atZone(ZoneId.systemDefault()));
        return c.getString(R.string.cached, Integer.toString(snapshot.blocks.get(0).height), time);
    }
}
