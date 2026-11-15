package de.blockzeit;

import android.content.Context;
import android.content.SharedPreferences;
import de.blockzeit.core.WeatherData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Weather owns a separate preferences file, admission gate and per-widget location generation. */
public final class WeatherStore {
    static final long DAILY_INTERVAL_SECONDS = 3 * 60 * 60;
    private WeatherStore() { }
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("weather", Context.MODE_PRIVATE); }
    static final class Selection {
        final int id;
        final String token;
        final long generation;
        final WeatherData.Place place;
        Selection(int id, String token, WeatherData.Place place, long generation) {
            this.id = id; this.token = token; this.place = place; this.generation = generation;
        }
    }
    public static synchronized WeatherData.Place place(Context context, int id) {
        Selection selection = selection(context, id);
        return selection == null ? null : selection.place;
    }
    static synchronized Selection selection(Context context, int id) {
        try {
            String token = prefs(context).getString("token." + id, "");
            if (token.isEmpty()) return null;
            JSONObject row = new JSONObject(prefs(context).getString("place." + id, ""));
            return new Selection(id, token, new WeatherData.Place(row.getString("name"), row.getString("region"),
                    row.getString("country"), row.getString("timezone"), row.getDouble("latitude"), row.getDouble("longitude")),
                    Store.prefs(context).getLong("weatherGeneration." + id, 0));
        } catch (JSONException | IllegalArgumentException e) { return null; }
    }
    public static void setPlace(Context context, int id, WeatherData.Place place) {
        if (place == null) { remove(context, id); return; }
        final String encoded;
        try {
            encoded = new JSONObject().put("name", place.name).put("region", place.region)
                    .put("country", place.country).put("timezone", place.timezone)
                    .put("latitude", place.latitude).put("longitude", place.longitude).toString();
        } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
        synchronized (WeatherStore.class) {
            SharedPreferences preferences = prefs(context);
            if (encoded.equals(preferences.getString("place." + id, "")) && selection(context, id) != null) return;
            CenterRefresh.invalidate(context, id);
            if (!preferences.edit().putString("place." + id, encoded).putString("token." + id, UUID.randomUUID().toString())
                    .remove("sample." + id).remove("attempt." + id).commit())
                throw new IllegalStateException("Could not save weather location");
        }
        WeatherJob.cancelWidget(id);
        WeatherScheduler.enqueue(context);
    }
    public static void remove(Context context, int id) {
        CenterRefresh.invalidate(context, id);
        synchronized (WeatherStore.class) {
            if (!prefs(context).edit().remove("place." + id).remove("token." + id)
                    .remove("sample." + id).remove("attempt." + id).commit())
                throw new IllegalStateException("Could not remove weather location");
        }
        WeatherJob.cancelWidget(id);
        WeatherScheduler.enqueue(context);
    }
    public static synchronized WeatherData.Sample sample(Context context, int id) {
        Selection selection = selection(context, id);
        if (selection == null) return null;
        try {
            JSONObject row = new JSONObject(prefs(context).getString("sample." + id, ""));
            if (!selection.token.equals(row.getString("token"))) return null;
            List<WeatherData.Hour> hours = new ArrayList<>();
            JSONArray cachedHours = row.optJSONArray("hours");
            if (cachedHours != null) for (int i = 0; i < Math.min(cachedHours.length(), WeatherData.MAX_HOURS); i++) {
                JSONObject hour = cachedHours.optJSONObject(i);
                if (hour == null) continue;
                long end = WeatherClient.epoch(hour.opt("endsAt"));
                if (end > 0) hours.add(new WeatherData.Hour(end, WeatherClient.optionalNumber(hour.opt("probability")),
                        WeatherClient.integer(hour.opt("code"), -1), WeatherClient.optionalNumber(hour.opt("temperature")),
                        WeatherClient.optionalNumber(hour.opt("rain")), WeatherClient.optionalNumber(hour.opt("showers")),
                        WeatherClient.optionalNumber(hour.opt("snowfall"))));
            }
            WeatherData.Sample sample = new WeatherData.Sample(row.getDouble("temperature"), row.getInt("code"),
                    row.getBoolean("isDay"), row.getLong("fetchedAt"), row.getString("day"),
                    row.optDouble("maximum", Double.NaN), row.optDouble("minimum", Double.NaN),
                    WeatherClient.optionalNumber(row.opt("windSpeed")), hours, decodeHour(row.optJSONObject("retainedHour")), decodeDays(row.optJSONArray("days")));
            return sample;
        } catch (JSONException | RuntimeException e) { return null; }
    }
    /** Pre-overview cache only: preserve a known same-date today without inventing future days.
     * Retained legacy fields carry their own source date; sample.day alone is not sufficient. */
    static synchronized WeatherData.Day legacyToday(Context context, int id, String date) {
        WeatherData.Sample cached = sample(context, id);
        if (cached == null || !cached.day.equals(date)) return null;
        try {
            JSONObject row = new JSONObject(prefs(context).getString("sample." + id, ""));
            // An explicit dated forecast, even missing/corrupt, must not be replaced by legacy values.
            if (row.has("days")) return null;
            double max = date.equals(row.optString("maximumSourceDay", cached.day)) ? cached.maximum : Double.NaN;
            double min = date.equals(row.optString("minimumSourceDay", cached.day)) ? cached.minimum : Double.NaN;
            return new WeatherData.Day(date, max, min);
        } catch (JSONException invalid) { return null; }
    }
    private static String dailyIdentity(WeatherData.Place place) {
        return Double.toString(place.latitude) + "," + Double.toString(place.longitude) + "," + place.timezone;
    }
    /** Only choose a horizon for an already admitted regular request. Never schedules or fetches. */
    static synchronized boolean dailyDue(Context context, Selection selection, long now) {
        WeatherData.Sample cached = sample(context, selection.id);
        if (cached == null) return true;
        try {
            JSONObject row = new JSONObject(prefs(context).getString("sample." + selection.id, ""));
            long loaded = WeatherClient.epoch(row.opt("daysFetchedAt"));
            if (!selection.token.equals(row.optString("token", ""))
                    || !dailyIdentity(selection.place).equals(row.optString("daysPlace", ""))
                    || loaded <= 0 || loaded > now || now - loaded >= DAILY_INTERVAL_SECONDS) return true;
            String today = WeatherData.localDay(now, selection.place.timezone);
            return !completeDays(cached, today);
        } catch (JSONException | RuntimeException invalid) { return true; }
    }
    private static boolean completeDays(WeatherData.Sample sample, String firstDay) {
        java.time.LocalDate first = java.time.LocalDate.parse(firstDay);
        for (int offset = 0; offset < WeatherData.MAX_DAYS; offset++) {
            WeatherData.Day day = sample.day(first.plusDays(offset).toString());
            // Legacy temperature-only caches must refresh on the next admitted regular request.
            if (day == null || !WeatherData.validTemperature(day.maximum) || !WeatherData.validTemperature(day.minimum)
                    || !WeatherData.validProbability(day.precipitationProbabilityMax)) return false;
        }
        return true;
    }
    static boolean active(Context context, int id) {
        if (!Store.weatherEnabled(context, id) || (Store.mode(context, id) != 3
                && !Store.prefs(context).getBoolean("weatherRefresh." + id, false))) return false;
        for (int existing : BlockzeitWidget.ids(context)) if (existing == id) return true;
        return false;
    }
    static synchronized boolean matches(Context context, Selection selection) {
        return active(context, selection.id) && matchesPlace(context, selection);
    }
    /** Mode-independent identity for an already admitted automatic request. */
    static synchronized boolean matchesPlace(Context context, Selection selection) {
        return Store.weatherEnabled(context, selection.id) && BlockzeitWidget.active(context, selection.id)
                && selection.generation == Store.prefs(context).getLong("weatherGeneration." + selection.id, 0)
                && selection.token.equals(prefs(context).getString("token." + selection.id, ""));
    }
    static synchronized void finishAttempt(Context context, Selection selection) {
        if (matches(context, selection)) Store.prefs(context).edit().remove("weatherRefresh." + selection.id).apply();
    }
    static synchronized boolean due(Context context, Selection selection, long now) {
        if (!matches(context, selection)) return false;
        long attempted = prefs(context).getLong("attempt." + selection.id, 0);
        // A clock rollback must not bypass the weather interval or create an early automatic retry.
        return attempted <= 0 || now - attempted >= WeatherData.INTERVAL_SECONDS;
    }
    static synchronized boolean reserve(Context context, Selection selection, long now) {
        return due(context, selection, now) && prefs(context).edit().putLong("attempt." + selection.id, now).commit();
    }
    static synchronized boolean reserveManual(Context context, Selection selection, CenterRefresh.Request request, long now) {
        return request.matches(context) && matches(context, selection)
                && prefs(context).edit().putLong("attempt." + selection.id, now).commit();
    }
    private static List<WeatherData.Day> decodeDays(JSONArray rows) {
        List<WeatherData.Day> days = new ArrayList<>();
        if (rows != null) for (int i = 0; i < Math.min(rows.length(), 16); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !(row.opt("date") instanceof String)) continue;
            try {
                days.add(new WeatherData.Day((String) row.opt("date"),
                        WeatherClient.optionalNumber(row.opt("maximum")), WeatherClient.optionalNumber(row.opt("minimum")),
                        WeatherClient.optionalNumber(row.opt("precipitationProbabilityMax"))));
            } catch (java.time.DateTimeException invalidDate) { /* Keep the remaining optional dates. */ }
        }
        return days;
    }
    private static JSONArray mergeDays(WeatherData.Sample sample, WeatherData.Sample previous, boolean extendedDaily) throws JSONException {
        JSONArray days = new JSONArray();
        java.time.LocalDate first = java.time.LocalDate.parse(sample.day);
        for (int i = 0; i < WeatherData.MAX_DAYS; i++) {
            String date = first.plusDays(i).toString();
            WeatherData.Day next = extendedDaily || i == 0 ? sample.day(date) : null, old = previous == null ? null : previous.day(date);
            if (next == null && old == null) continue;
            double max = next != null && Double.isFinite(next.maximum) ? next.maximum : old == null ? Double.NaN : old.maximum;
            double min = next != null && Double.isFinite(next.minimum) ? next.minimum : old == null ? Double.NaN : old.minimum;
            double probability = next != null && Double.isFinite(next.precipitationProbabilityMax)
                    ? next.precipitationProbabilityMax : old == null ? Double.NaN : old.precipitationProbabilityMax;
            WeatherData.Day merged = new WeatherData.Day(date, max, min, probability);
            JSONObject row = new JSONObject().put("date", date);
            if (Double.isFinite(merged.maximum)) row.put("maximum", merged.maximum);
            if (Double.isFinite(merged.minimum)) row.put("minimum", merged.minimum);
            if (Double.isFinite(merged.precipitationProbabilityMax)) row.put("precipitationProbabilityMax", merged.precipitationProbabilityMax);
            days.put(row);
        }
        return days;
    }
    private static WeatherData.Hour decodeHour(JSONObject row) {
        if (row == null) return null;
        long end = WeatherClient.epoch(row.opt("endsAt"));
        return end <= 0 ? null : new WeatherData.Hour(end, WeatherClient.optionalNumber(row.opt("probability")),
                WeatherClient.integer(row.opt("code"), -1), WeatherClient.optionalNumber(row.opt("temperature")),
                WeatherClient.optionalNumber(row.opt("rain")), WeatherClient.optionalNumber(row.opt("showers")),
                WeatherClient.optionalNumber(row.opt("snowfall")));
    }
    private static WeatherData.Hour retainHourFields(WeatherData.Hour next, WeatherData.Hour previous) {
        if (previous == null || next.endsAt != previous.endsAt) return next;
        return new WeatherData.Hour(next.endsAt,
                Double.isFinite(next.probability) ? next.probability : previous.probability,
                next.code != -1 ? next.code : previous.code,
                Double.isFinite(next.temperature) ? next.temperature : previous.temperature,
                Double.isFinite(next.rain) ? next.rain : previous.rain,
                Double.isFinite(next.showers) ? next.showers : previous.showers,
                Double.isFinite(next.snowfall) ? next.snowfall : previous.snowfall);
    }
    private static JSONObject encodeHour(WeatherData.Hour hour) throws JSONException {
        JSONObject row = new JSONObject().put("endsAt", hour.endsAt);
        if (Double.isFinite(hour.probability)) row.put("probability", hour.probability);
        if (hour.code != -1) row.put("code", hour.code);
        if (Double.isFinite(hour.temperature)) row.put("temperature", hour.temperature);
        if (Double.isFinite(hour.rain)) row.put("rain", hour.rain);
        if (Double.isFinite(hour.showers)) row.put("showers", hour.showers);
        if (Double.isFinite(hour.snowfall)) row.put("snowfall", hour.snowfall);
        return row;
    }
    static synchronized boolean publish(Context context, Selection selection, WeatherData.Sample sample) {
        return publish(context, selection, sample, true);
    }
    static synchronized boolean publish(Context context, Selection selection, WeatherData.Sample sample, boolean extendedDaily) {
        if (!matchesPlace(context, selection) || sample == null) return false;
        try {
            WeatherData.Sample previous = sample(context, selection.id);
            JSONObject old;
            try { old = new JSONObject(prefs(context).getString("sample." + selection.id, "")); }
            catch (JSONException invalid) { old = new JSONObject(); }
            JSONObject row = new JSONObject().put("token", selection.token).put("temperature", sample.temperature)
                    .put("code", sample.code != -1 || previous == null ? sample.code : previous.code)
                    .put("isDay", sample.code != -1 || previous == null ? sample.isDay : previous.isDay)
                    .put("fetchedAt", sample.fetchedAt).put("day", sample.day);
            boolean validCode = sample.code != -1 || previous == null;
            row.put("codeSourceDay", validCode ? sample.day : old.optString("codeSourceDay", previous.day));
            row.put("codeFetchedAt", validCode ? sample.fetchedAt : old.optLong("codeFetchedAt", previous.fetchedAt));
            double[] values = {sample.maximum, sample.minimum, sample.windSpeed};
            double[] prior = previous == null ? new double[]{Double.NaN, Double.NaN, Double.NaN}
                    : new double[]{previous.maximum, previous.minimum, previous.windSpeed};
            String[] keys = {"maximum", "minimum", "windSpeed"};
            for (int i = 0; i < keys.length; i++) {
                boolean valid = Double.isFinite(values[i]);
                double value = valid ? values[i] : prior[i];
                if (!Double.isFinite(value)) continue;
                row.put(keys[i], value);
                // Keep source identity internally; a new temperature must not freshen retained fields.
                String source = keys[i] + "SourceDay", acquired = keys[i] + "FetchedAt";
                row.put(source, valid ? sample.day : old.optString(source, previous.day));
                row.put(acquired, valid ? sample.fetchedAt : old.optLong(acquired, previous.fetchedAt));
            }
            // Dated forecasts retain only the same date within this sample's five-day window.
            // Do not seed this list from legacy max/min: those intentionally retain older widget values.
            row.put("days", mergeDays(sample, previous, extendedDaily));
            String identity = dailyIdentity(selection.place);
            if (extendedDaily && completeDays(sample, sample.day)) {
                // Only an actual complete five-day result advances this successful-load clock.
                row.put("daysFetchedAt", sample.fetchedAt).put("daysPlace", identity);
            } else if (identity.equals(old.optString("daysPlace", ""))) {
                long loaded = WeatherClient.epoch(old.opt("daysFetchedAt"));
                if (loaded > 0) row.put("daysFetchedAt", loaded).put("daysPlace", identity);
            }
            JSONArray hours = new JSONArray();
            List<WeatherData.Hour> merged = new ArrayList<>();
            WeatherData.Hour priorHour = previous == null ? null : previous.displayHour(sample.fetchedAt);
            for (WeatherData.Hour hour : sample.hours) {
                if (previous != null) for (WeatherData.Hour oldHour : previous.hours)
                    if (oldHour.endsAt == hour.endsAt) { hour = retainHourFields(hour, oldHour); break; }
                hour = retainHourFields(hour, priorHour);
                merged.add(hour); hours.put(encodeHour(hour));
            }
            row.put("hours", hours);
            WeatherData.Sample display = new WeatherData.Sample(sample.temperature, sample.code, sample.isDay,
                    sample.fetchedAt, sample.day, sample.maximum, sample.minimum, sample.windSpeed, merged, priorHour);
            WeatherData.Hour retained = display.displayHour(sample.fetchedAt);
            if (retained != null) row.put("retainedHour", encodeHour(retained));
            return prefs(context).edit().putString("sample." + selection.id, row.toString()).commit();
        } catch (JSONException e) { return false; }
    }
}
