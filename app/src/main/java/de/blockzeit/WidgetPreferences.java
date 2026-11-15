package de.blockzeit;

import android.content.Context;
import android.content.SharedPreferences;
import de.blockzeit.core.MarketData;
import de.blockzeit.core.WeatherData;
import de.blockzeit.core.WidgetMode;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** App-owned last explicit Save, independent of widget IDs. Never contains acquisition state. */
final class WidgetPreferences {
    private static final String TEMPLATE = "template", MIGRATED = "legacyChecked", KNOWN = "known.";
    private static final String[] SETTINGS = {"opacity.", "elapsedDaysColor.", "remainingDaysColor.", "primaryTextColor.", "clockEnabled.", "blockEnabled.",
            "currency.", "priceEnabled.", "weatherEnabled.", "calendarEnabled.", "calendarId."};
    private WidgetPreferences() { }
    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("widget_preferences", Context.MODE_PRIVATE);
    }
    static final class Values {
        final int opacity, elapsed, remaining, primary, mode;
        final String currency;
        final boolean price, weather, calendar, clock, block;
        final long calendarId;
        final WeatherData.Place place;
        Values(int opacity, int elapsed, int remaining, String currency, boolean price, boolean weather,
                int mode, WeatherData.Place place) {
            this(opacity, elapsed, remaining, currency, price, weather, mode, place, false, -1);
        }
        Values(int opacity, int elapsed, int remaining, String currency, boolean price, boolean weather,
                int mode, WeatherData.Place place, boolean calendar, long calendarId) {
            this(opacity, elapsed, remaining, currency, price, weather, mode, place, calendar, calendarId,
                    true, true, RingRenderer.GREEN);
        }
        Values(int opacity, int elapsed, int remaining, String currency, boolean price, boolean weather,
                int mode, WeatherData.Place place, boolean calendar, long calendarId, boolean clock, boolean block, int primary) {
            this.clock = WidgetMode.clockAllowed(clock, block); this.block = block; this.primary = primary;
            this.calendar = calendar; this.calendarId = calendarId;
            this.opacity = opacity; this.elapsed = elapsed; this.remaining = remaining;
            this.currency = MarketData.currency(currency); this.price = price; this.weather = weather;
            this.mode = WidgetMode.normalize(mode, this.clock, block, price, weather, calendar); this.place = place;
        }
        String encode() {
            try {
                JSONObject row = new JSONObject().put("version", 1).put("opacity", opacity)
                        .put("elapsed", elapsed).put("remaining", remaining).put("currency", currency)
                        .put("price", price).put("weather", weather).put("mode", mode)
                        .put("calendar", calendar).put("calendarId", calendarId)
                        .put("clock", clock).put("block", block).put("primary", primary);
                JSONObject location = place == null ? null : new JSONObject().put("name", place.name)
                        .put("region", place.region).put("country", place.country).put("timezone", place.timezone)
                        .put("latitude", place.latitude).put("longitude", place.longitude);
                return row.put("place", location == null ? JSONObject.NULL : location).toString();
            } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
        }
        static Values decode(String encoded) {
            if (encoded == null || encoded.length() > 8192) return null;
            try {
                JSONObject row = new JSONObject(encoded);
                int opacity = row.getInt("opacity");
                if (row.getInt("version") != 1 || opacity < 0 || opacity > 100 || !row.has("place")) return null;
                JSONObject location = row.isNull("place") ? null : row.getJSONObject("place");
                WeatherData.Place place = location == null ? null : new WeatherData.Place(location.getString("name"),
                        location.getString("region"), location.getString("country"), location.getString("timezone"),
                        location.getDouble("latitude"), location.getDouble("longitude"));
                return new Values(opacity, row.getInt("elapsed"), row.getInt("remaining"), row.getString("currency"),
                        row.getBoolean("price"), row.getBoolean("weather"), row.getInt("mode"), place, row.optBoolean("calendar", false), row.optLong("calendarId", -1),
                        row.optBoolean("clock", true), row.optBoolean("block", true), row.optInt("primary", RingRenderer.GREEN));
            } catch (JSONException | IllegalArgumentException invalid) { return null; }
        }
    }
    private static Values capture(Context context, int id) {
        boolean price = Store.priceEnabled(context, id), weather = Store.weatherEnabled(context, id);
        // Unlike Store.mode(), this read does not migrate/normalize the existing widget in storage.
        int mode = WidgetMode.normalize(Store.prefs(context).getAll().get("mode." + id), Store.clockEnabled(context, id), Store.blockEnabled(context, id), price, weather, Store.calendarEnabled(context, id));
        return new Values(Store.opacity(context, id), Store.elapsedDaysColor(context, id),
                Store.remainingDaysColor(context, id), Store.currency(context, id), price, weather,
                mode, WeatherStore.place(context, id), Store.calendarEnabled(context, id), Store.calendarId(context, id),
                Store.clockEnabled(context, id), Store.blockEnabled(context, id), Store.primaryTextColor(context, id));
    }
    private static Set<Integer> ids(Context context, Integer removing) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int id : BlockzeitWidget.ids(context)) ids.add(id);
        if (removing != null) ids.add(removing); // The launcher may already exclude a deleted ID.
        return ids;
    }
    private static boolean saved(Context context, int id) {
        SharedPreferences data = Store.prefs(context);
        for (String key : SETTINGS) if (data.contains(key + id)) return true;
        return WeatherStore.place(context, id) != null;
    }
    @SuppressWarnings("unchecked")
    private static boolean persist(Context context, SharedPreferences.Editor editor) {
        SharedPreferences preferences = prefs(context);
        Map<String, ?> previous = preferences.getAll();
        if (editor.commit()) return true;
        // Android updates memory even when commit reports a disk failure. Restore the previous
        // view too, so a later deletion cannot mistake that unconfirmed template for a durable one.
        SharedPreferences.Editor rollback = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : previous.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) rollback.putString(key, (String) value);
            else if (value instanceof Boolean) rollback.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) rollback.putInt(key, (Integer) value);
            else if (value instanceof Long) rollback.putLong(key, (Long) value);
            else if (value instanceof Float) rollback.putFloat(key, (Float) value);
            else if (value instanceof Set) rollback.putStringSet(key, (Set<String>) value);
        }
        rollback.apply();
        return false;
    }
    private static boolean template(Context context, Values values, Set<Integer> existing) {
        SharedPreferences.Editor edit = prefs(context).edit().putString(TEMPLATE, values.encode()).putBoolean(MIGRATED, true);
        // Protect even default-only legacy widgets: missing setting keys do not make an old ID new.
        for (int id : existing) edit.putBoolean(KNOWN + id, true);
        return persist(context, edit);
    }
    static synchronized boolean migrate(Context context) { return migrate(context, null); }
    private static boolean migrate(Context context, Integer removing) {
        SharedPreferences preferences = prefs(context);
        if (preferences.getBoolean(MIGRATED, false) || preferences.contains(TEMPLATE)) return true;
        Set<Integer> existing = ids(context, removing);
        Set<Integer> candidates = new LinkedHashSet<>(existing);
        if (removing != null) {
            // A batched onDeleted may already have removed every ID from AppWidgetManager.
            // Count the other not-yet-cleared configurations too, without promoting orphaned data.
            for (String key : Store.prefs(context).getAll().keySet())
                for (String prefix : SETTINGS) addStoredId(candidates, key, prefix);
            for (String key : WeatherStore.prefs(context).getAll().keySet()) addStoredId(candidates, key, "place.");
        }
        Integer sole = null;
        int count = 0;
        for (int id : candidates) if (saved(context, id)) { sole = id; count++; }
        // Multiple historical Saves have no timestamp. Wait for an explicit Save or deletion fallback.
        if (count == 1 && existing.contains(sole)) return template(context, capture(context, sole), existing);
        return persist(context, preferences.edit().putBoolean(MIGRATED, true));
    }
    private static void addStoredId(Set<Integer> ids, String key, String prefix) {
        if (!key.startsWith(prefix)) return;
        try { ids.add(Integer.parseInt(key.substring(prefix.length()))); }
        catch (NumberFormatException invalid) { /* Not an app widget setting key. */ }
    }
    static synchronized Values forNewWidget(Context context, int id) {
        if (prefs(context).getBoolean(KNOWN + id, false) || saved(context, id)
                || Store.prefs(context).contains("mode." + id)) return null;
        return Values.decode(prefs(context).getString(TEMPLATE, ""));
    }
    static synchronized boolean save(Context context, int id) {
        Set<Integer> existing = Values.decode(prefs(context).getString(TEMPLATE, "")) == null
                ? ids(context, null) : new LinkedHashSet<>();
        existing.add(id);
        return template(context, capture(context, id), existing);
    }
    static synchronized boolean beforeRemove(Context context, int id) {
        if (!migrate(context, id)) return false;
        if (Values.decode(prefs(context).getString(TEMPLATE, "")) == null) {
            // Deterministic migration fallback: remember this actual deleted widget, not a
            // guessed historical last Save. A valid existing template is never overwritten here.
            if (!template(context, capture(context, id), ids(context, id))) return false;
        }
        return persist(context, prefs(context).edit().remove(KNOWN + id));
    }
}
