package de.blockzeit;

import de.blockzeit.core.WeatherData;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** No keys, GPS, dependencies or BTC transport state. Each instance owns at most one request. */
public final class WeatherClient {
    static final int MAX_BYTES = 128 * 1024;
    static final long REQUEST_MILLIS = 8_000;
    private HttpURLConnection connection;
    private boolean cancelled;

    public WeatherClient() { }
    public synchronized void cancel() {
        cancelled = true;
        if (connection != null) connection.disconnect();
    }
    private synchronized void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Weather request cancelled");
    }
    public List<WeatherData.Place> search(String query) throws IOException {
        check();
        String value = query == null ? "" : query.trim();
        if (value.length() < 2) return Collections.emptyList();
        if (value.length() > 200) throw new IOException("Location query too long");
        return parsePlaces(get("https://geocoding-api.open-meteo.com/v1/search?name=" + encode(value)
                + "&count=10&format=json&language=" + encode(Locale.getDefault().getLanguage())));
    }
    public WeatherData.Sample fetch(WeatherData.Place place) throws IOException { return fetch(place, true); }
    /** Same request and current/hourly fields; only the daily horizon changes. */
    public WeatherData.Sample fetch(WeatherData.Place place, boolean extendedDaily) throws IOException {
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + Double.toString(place.latitude)
                + "&longitude=" + Double.toString(place.longitude)
                + "&current=temperature_2m,weather_code,is_day,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&hourly=precipitation_probability,weather_code,temperature_2m,rain,showers,snowfall&wind_speed_unit=kmh&timeformat=unixtime"
                + "&temperature_unit=celsius&forecast_days=" + (extendedDaily ? "5" : "2") + "&timezone=" + encode(place.timezone);
        String response = get(url);
        return parseSample(response, place, System.currentTimeMillis() / 1000);
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private String get(String address) throws IOException {
        check();
        long deadline = System.nanoTime() + REQUEST_MILLIS * 1_000_000L;
        HttpURLConnection request = (HttpURLConnection) new URL(address).openConnection();
        synchronized (this) {
            check();
            if (connection != null) throw new IOException("Weather client already in use");
            connection = request;
        }
        try {
            request.setConnectTimeout(4_000); request.setReadTimeout(4_000);
            request.setInstanceFollowRedirects(false); request.setUseCaches(false);
            request.setRequestProperty("Accept", "application/json");
            request.setRequestProperty("Accept-Encoding", "identity");
            request.setRequestProperty("User-Agent", "Blockzeit/Weather (Open-Meteo)");
            int code = request.getResponseCode();
            checkDeadline(deadline);
            if (code != 200) throw new IOException("Weather HTTP " + code);
            if (request.getContentLengthLong() > MAX_BYTES) throw new IOException("Weather response too large");
            try (InputStream input = request.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                while (true) {
                    checkDeadline(deadline);
                    // A drip-feed cannot extend the aggregate request past its remaining budget.
                    request.setReadTimeout((int) Math.max(1, Math.min(4_000,
                            (deadline - System.nanoTime()) / 1_000_000L)));
                    int count = input.read(buffer);
                    checkDeadline(deadline);
                    if (count < 0) break;
                    if (body.size() + count > MAX_BYTES) throw new IOException("Weather response too large");
                    body.write(buffer, 0, count);
                }
                return body.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            request.disconnect();
            synchronized (this) { if (connection == request) connection = null; }
        }
    }
    private void checkDeadline(long deadline) throws IOException {
        check();
        if (System.nanoTime() >= deadline) throw new IOException("Weather request deadline");
    }
    static List<WeatherData.Place> parsePlaces(String text) throws IOException {
        try {
            JSONObject root = new JSONObject(text);
            if (root.optBoolean("error", false)) throw new IOException("Location service error");
            JSONArray results = root.optJSONArray("results");
            List<WeatherData.Place> places = new ArrayList<>();
            if (results == null) {
                if (root.has("results") && !root.isNull("results")) throw new IOException("Invalid location results");
                return places;
            }
            for (int i = 0; i < Math.min(10, results.length()); i++) {
                JSONObject row = results.optJSONObject(i);
                if (row == null) continue;
                try {
                    places.add(new WeatherData.Place(row.getString("name"), row.optString("admin1", ""),
                            row.optString("country", ""), row.getString("timezone"),
                            number(row.get("latitude")), number(row.get("longitude"))));
                } catch (JSONException | IllegalArgumentException ignored) { /* Reject unusable locations. */ }
            }
            return places;
        } catch (JSONException e) { throw new IOException("Invalid location response", e); }
    }
    static WeatherData.Sample parseSample(String text, WeatherData.Place place, long now) throws IOException {
        try {
            JSONObject root = new JSONObject(text);
            if (root.optBoolean("error", false)) throw new IOException("Forecast service error");
            JSONObject current = root.getJSONObject("current");
            double temperature = number(current.get("temperature_2m"));
            if (!WeatherData.validTemperature(temperature)) throw new IOException("Invalid temperature");
            int code = integer(current.opt("weather_code"), -1);
            int light = integer(current.opt("is_day"), -1);
            if (light != 0 && light != 1) throw new IOException("Invalid daylight flag");
            String day = WeatherData.localDay(now, place.timezone);
            JSONObject currentUnits = root.optJSONObject("current_units");
            double wind = currentUnits != null && "km/h".equals(currentUnits.opt("wind_speed_10m"))
                    ? optionalNumber(current.opt("wind_speed_10m")) : Double.NaN;
            List<WeatherData.Hour> hours = new ArrayList<>();
            JSONObject hourly = root.optJSONObject("hourly"), hourlyUnits = root.optJSONObject("hourly_units");
            if (hourly != null && hourlyUnits != null && "%".equals(hourlyUnits.opt("precipitation_probability"))) {
                JSONArray times = hourly.optJSONArray("time"), probabilities = hourly.optJSONArray("precipitation_probability");
                if (times != null) for (int i = 0; i < Math.min(times.length(), WeatherData.MAX_HOURS); i++) {
                    long end = epoch(times.opt(i));
                    if (end > 0) hours.add(new WeatherData.Hour(end,
                            optionalNumber(probabilities == null ? null : probabilities.opt(i)),
                            integer("wmo code".equals(hourlyUnits.opt("weather_code"))
                                    ? hourlyValue(hourly, "weather_code", i) : null, -1),
                            hourlyNumber(hourly, hourlyUnits, "temperature_2m", "°C", i),
                            hourlyNumber(hourly, hourlyUnits, "rain", "mm", i),
                            hourlyNumber(hourly, hourlyUnits, "showers", "mm", i),
                            hourlyNumber(hourly, hourlyUnits, "snowfall", "cm", i)));
                }
            }
            List<WeatherData.Day> days = new ArrayList<>();
            JSONObject daily = root.optJSONObject("daily"), dailyUnits = root.optJSONObject("daily_units");
            // Unit-less legacy fixtures remain readable; an explicit incompatible unit is unknown.
            boolean legacyUnits = !root.has("daily_units");
            if (daily != null) {
                JSONArray dates = daily.optJSONArray("time");
                if (dates != null) for (int i = 0; i < Math.min(dates.length(), 16); i++) {
                    String date = dailyDay(dates.opt(i), place.timezone, root.opt("utc_offset_seconds"), now);
                    try {
                        double max = legacyUnits || dailyUnits != null && "°C".equals(dailyUnits.opt("temperature_2m_max"))
                                ? optionalTemperature(daily.optJSONArray("temperature_2m_max"), i) : Double.NaN;
                        double min = legacyUnits || dailyUnits != null && "°C".equals(dailyUnits.opt("temperature_2m_min"))
                                ? optionalTemperature(daily.optJSONArray("temperature_2m_min"), i) : Double.NaN;
                        JSONArray probabilities = daily.optJSONArray("precipitation_probability_max");
                        double probability = dailyUnits != null && "%".equals(dailyUnits.opt("precipitation_probability_max"))
                                ? optionalNumber(probabilities == null ? null : probabilities.opt(i)) : Double.NaN;
                        days.add(new WeatherData.Day(date, max, min, probability));
                    } catch (java.time.DateTimeException invalidDate) { /* Optional malformed label only. */ }
                }
            }
            WeatherData.Sample sample = new WeatherData.Sample(temperature, code, light == 1, now, day,
                    Double.NaN, Double.NaN, wind, hours, null, days);
            WeatherData.Day today = sample.day(day);
            return new WeatherData.Sample(temperature, code, light == 1, now, day,
                    today == null ? Double.NaN : today.maximum, today == null ? Double.NaN : today.minimum,
                    wind, hours, null, sample.days);
        } catch (JSONException | IllegalArgumentException e) { throw new IOException("Invalid forecast response", e); }
    }
    private static Object hourlyValue(JSONObject hourly, String name, int index) {
        JSONArray values = hourly.optJSONArray(name);
        return values == null ? null : values.opt(index);
    }
    private static double hourlyNumber(JSONObject hourly, JSONObject units, String name, String unit, int index) {
        return unit.equals(units.opt(name)) ? optionalNumber(hourlyValue(hourly, name, index)) : Double.NaN;
    }
    private static String dailyDay(Object value, String timezone, Object responseOffset, long now) {
        if (value instanceof String) return (String) value; // Existing ISO-date fixtures/caches.
        long seconds = epoch(value);
        if (seconds <= 0) return "";
        if (responseOffset == null || responseOffset == JSONObject.NULL)
            return WeatherData.localDay(seconds, timezone); // Legacy fixture without API offset metadata.
        double offset = optionalNumber(responseOffset);
        int selectedOffset = java.time.ZoneId.of(timezone).getRules().getOffset(java.time.Instant.ofEpochSecond(now)).getTotalSeconds();
        if (offset != Math.rint(offset) || Math.abs(offset) > 86400 || offset != selectedOffset) return "";
        // Open-Meteo's daily labels/boundaries use its fixed response offset. Applying historical
        // ZoneRules at midnight could label the spring-transition bucket as yesterday. Hourly
        // endpoints remain absolute UTC and NEVER receive this daily-calendar adjustment.
        return java.time.Instant.ofEpochSecond(seconds + (long) offset).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }
    static long epoch(Object value) {
        double seconds = optionalNumber(value);
        return seconds > 0 && seconds <= 253402300799L && seconds == Math.rint(seconds) ? (long) seconds : -1;
    }
    static double optionalNumber(Object value) {
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue())
                ? ((Number) value).doubleValue() : Double.NaN;
    }
    private static double optionalTemperature(JSONArray array, int index) {
        try { double value = number(array == null ? null : array.opt(index));
            return WeatherData.validTemperature(value) ? value : Double.NaN;
        } catch (IllegalArgumentException invalid) { return Double.NaN; }
    }
    private static double number(Object value) {
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue()))
            throw new IllegalArgumentException("Expected finite number");
        return ((Number) value).doubleValue();
    }
    static int integer(Object value, int fallback) {
        if (!(value instanceof Number)) return fallback;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number == Math.rint(number) && number >= Integer.MIN_VALUE
                && number <= Integer.MAX_VALUE ? (int) number : fallback;
    }
}
