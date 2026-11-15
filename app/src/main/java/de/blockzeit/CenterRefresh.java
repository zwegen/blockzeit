package de.blockzeit;

import android.content.Context;
import de.blockzeit.core.WidgetMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Durable, per-widget one-shot intent; never a mode selection or an automatic freshness flag. */
final class CenterRefresh {
    private static final String PREFIX = "centerRefresh.";
    static final String CLAIM = "centerSupplement.";
    private CenterRefresh() { }
    static final class Request {
        final int id, mode;
        final String token;
        Request(int id, int mode, String token) { this.id = id; this.mode = mode; this.token = token; }
        boolean matches(Context c) {
            return mode != WidgetMode.BLOCK && token.equals(Store.prefs(c).getString(PREFIX + id, "")) && BlockzeitWidget.active(c, id)
                    && WidgetMode.read(Store.prefs(c).getAll().get("mode." + id)) == mode
                    && WidgetMode.enabled(mode, Store.clockEnabled(c, id), Store.blockEnabled(c, id),
                            Store.priceEnabled(c, id), Store.weatherEnabled(c, id), Store.calendarEnabled(c, id));
        }
        boolean handedOff(Context c) { return token.equals(Store.prefs(c).getString(CLAIM + id, "")); }
        void finish(Context c) {
            synchronized (CenterRefresh.class) {
                if (token.equals(Store.prefs(c).getString(PREFIX + id, ""))) invalidate(c, id);
            }
        }
    }
    static void request(Context c, int id, int displayedMode) {
        // Retired BLOCK center broadcasts must never admit manual block/supplement work.
        if (displayedMode == WidgetMode.BLOCK) return;
        if (!Scheduler.isInteractive(c) || !BlockzeitWidget.active(c, id)) return;
        // Retired/unknown center intents never admit work; clear only invalid transient requests.
        if (displayedMode < WidgetMode.BLOCK || displayedMode > WidgetMode.WEATHER) { read(c, id); return; }
        int mode = Store.mode(c, id);
        if (mode != displayedMode || mode == WidgetMode.CLOCK || mode == WidgetMode.WEATHER && WeatherStore.place(c, id) == null) return;
        synchronized (CenterRefresh.class) {
            Request old = read(c, id);
            if (old == null || !old.matches(c)) Store.prefs(c).edit()
                    .putString(PREFIX + id, mode + ":" + UUID.randomUUID()).apply();
        }
        if (mode == WidgetMode.WEATHER) WeatherScheduler.enqueueManual(c);
        else Scheduler.enqueueManual(c);
    }
    private static Request read(Context c, int id) {
        String token = Store.prefs(c).getString(PREFIX + id, "");
        if (token.isEmpty()) return null;
        if (token.length() < 3 || token.charAt(1) != ':') { invalidate(c, id); return null; }
        int mode = token.charAt(0) - '0';
        if (mode < WidgetMode.BLOCK || mode > WidgetMode.WEATHER) { invalidate(c, id); return null; }
        return new Request(id, mode, token);
    }
    static synchronized List<Request> pending(Context c, boolean weather) {
        int[] ids = BlockzeitWidget.ids(c); Arrays.sort(ids);
        List<Request> result = new ArrayList<>();
        for (int id : ids) {
            Request request = read(c, id);
            if (request == null) continue;
            if (!request.matches(c)) { request.finish(c); continue; }
            if ((request.mode == WidgetMode.WEATHER) == weather) result.add(request);
        }
        return result;
    }
    static List<Request> pending(Context c, int mode) {
        List<Request> result = new ArrayList<>();
        for (Request request : pending(c, false)) if (request.mode == mode && !request.handedOff(c)) result.add(request);
        return result;
    }
    static void invalidate(Context c, int id) { Store.prefs(c).edit().remove(PREFIX + id).remove(CLAIM + id).apply(); }
    static void clear(Context c) {
        android.content.SharedPreferences.Editor edit = Store.prefs(c).edit();
        for (String key : Store.prefs(c).getAll().keySet()) if (key.startsWith(PREFIX) || key.startsWith(CLAIM)) edit.remove(key);
        edit.apply();
    }
}
