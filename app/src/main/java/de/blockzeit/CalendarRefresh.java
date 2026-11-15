package de.blockzeit;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import de.blockzeit.core.CalendarAgenda;
import de.blockzeit.core.WidgetMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Event-driven, process-local instance windows shared by calendar ID. No polling or wakeups. */
final class CalendarRefresh {
    static final class State {
        final List<CalendarAgenda.Entry> entries; final String message;
        private final Attempt coverage;
        State(List<CalendarAgenda.Entry> entries, String message) { this(entries, message, null); }
        private State(List<CalendarAgenda.Entry> entries, String message, Attempt coverage) {
            this.entries = entries; this.message = message; this.coverage = coverage;
        }
        private boolean usable(long now, ZoneId zone) {
            // A completed empty window is data too, but only within its original coverage.
            return coverage != null && coverage.covers(now, zone)
                    || !CalendarAgenda.upcoming(entries, now, zone).isEmpty();
        }
    }
    private static final Map<Long, State> CACHE = new HashMap<>();
    private static final Map<Long, Attempt> ATTEMPTS = new HashMap<>();
    private static final Set<Long> RUNNING = new HashSet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ContentObserver observer;
    private static Context observed;

    /** Matches Repository.instances' finite 370-day range. Two days protect local all-day
     * ordering at its UTC edge. Keep ALL occurrences, not only the currently visible five.
     * A tick only refilters these entries; it never advances the provider window. */
    private static final class Attempt {
        final long from, until;
        final ZoneId zone;
        String failure; // Settled refresh error, independent of retained last-good entries.
        Attempt(long now, ZoneId zone) {
            from = now; until = now + 368L * 86400000; this.zone = zone;
        }
        boolean covers(long now, ZoneId current) { return zone.equals(current) && now >= from && now < until; }
    }
    /** Refresh admission/generation is separate from last-good display data.
     * In-flight invalidated results must not republish; ticks still do not retry failures. */
    static synchronized void invalidate(long calendar) { ATTEMPTS.remove(calendar); }

    static synchronized void stop() {
        if (observer != null) observed.getContentResolver().unregisterContentObserver(observer);
        observer = null; observed = null;
        CACHE.clear(); ATTEMPTS.clear();
        // Retain RUNNING until each callback settles; never overlap an invalidated query.
    }
    private static Set<Long> selected(Context context, int requestingWidget) {
        Set<Long> result = new HashSet<>();
        for (int widget : BlockzeitWidget.ids(context)) if (Store.calendarEnabled(context, widget)) {
            long calendar = Store.calendarId(context, widget); if (calendar >= 0) result.add(calendar);
        }
        if (requestingWidget != 0 && Store.calendarEnabled(context, requestingWidget)) {
            long calendar = Store.calendarId(context, requestingWidget); if (calendar >= 0) result.add(calendar);
        }
        return result;
    }
    private static void observe(Context context, Set<Long> selected) {
        if (selected.isEmpty()) { stop(); return; }
        CACHE.keySet().retainAll(selected); ATTEMPTS.keySet().retainAll(selected);
        if (observer != null) return;
        Context app = context.getApplicationContext();
        ContentObserver listening = new ContentObserver(MAIN) {
            @Override public void onChange(boolean selfChange) { onChange(selfChange, null); }
            @Override public void onChange(boolean selfChange, Uri uri) {
                synchronized (CalendarRefresh.class) {
                    if (observer != this) return; // Ignore callbacks queued before unregistration.
                    ATTEMPTS.clear();
                }
                // CalendarProvider notifications may name events/instances, not a calendar ID.
                // Invalidate all selected windows, but load only currently displayed calendars.
                refreshVisible(app);
            }
        };
        try {
            app.getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, listening);
            observed = app; observer = listening;
        } catch (SecurityException denied) { stop(); }
    }
    private static void refreshVisible(Context app) {
        for (int widget : BlockzeitWidget.ids(app)) if (Store.mode(app, widget) == WidgetMode.CALENDAR) {
            request(app, widget); BlockzeitWidget.render(app, widget);
        }
    }
    /** Existing explicit reload actions refresh the widget cache too, including hidden modes. */
    static synchronized void reload(Context context, long calendar) {
        invalidate(calendar);
        if (calendar < 0 || !CalendarRepository.canRead(context)) return;
        observe(context, selected(context, 0));
        for (int widget : BlockzeitWidget.ids(context)) if (Store.calendarEnabled(context, widget)
                && Store.calendarId(context, widget) == calendar) {
            requestCalendar(context, widget, calendar, System.currentTimeMillis(), ZoneId.systemDefault());
        }
    }
    static synchronized State state(Context c, int id) {
        return state(c, id, System.currentTimeMillis(), ZoneId.systemDefault());
    }
    static synchronized State state(Context c, int id, long now, ZoneId zone) {
        if (!CalendarRepository.canRead(c)) { stop(); return new State(Collections.emptyList(), "Kalenderzugriff erlauben"); }
        long calendar = Store.calendarId(c, id);
        if (calendar < 0) return new State(Collections.emptyList(), "Kalender auswählen");
        State cached = CACHE.get(calendar);
        if (cached != null && (cached.coverage == null || cached.usable(now, zone))) return cached;
        Attempt attempt = ATTEMPTS.get(calendar);
        return new State(Collections.emptyList(), attempt != null && attempt.failure != null
                ? attempt.failure : "Kalender wird geladen …");
    }
    static void request(Context context, int id) {
        request(context, id, System.currentTimeMillis(), ZoneId.systemDefault());
    }
    /** Explicit clock input keeps coverage transitions testable without scheduling timers. */
    static synchronized void request(Context context, int id, long now, ZoneId zone) {
        if (!CalendarRepository.canRead(context)) { stop(); return; }
        observe(context, selected(context, id));
        if (Store.mode(context, id) != WidgetMode.CALENDAR) return;
        long calendar = Store.calendarId(context, id);
        if (calendar >= 0) requestCalendar(context, id, calendar, now, zone);
    }
    private static void requestCalendar(Context context, int id, long calendar, long now, ZoneId zone) {
        Attempt previous = ATTEMPTS.get(calendar);
        if (RUNNING.contains(calendar)) {
            if (previous != null && !previous.covers(now, zone)) invalidate(calendar);
            return;
        }
        if (previous != null && previous.covers(now, zone)) return;
        Attempt attempt = new Attempt(now, zone);
        ATTEMPTS.put(calendar, attempt); RUNNING.add(calendar);
        State cached = CACHE.get(calendar);
        if (cached != null && !cached.usable(now, zone)) CACHE.remove(calendar);
        Context app = context.getApplicationContext();
        CalendarRepository.Request<List<CalendarAgenda.Entry>> request = CalendarRepository.enqueue(app,
                (source, cancel) -> CalendarRepository.instances(source, calendar, now, zone, cancel), (entries, failure) -> {
                    boolean current, retry;
                    synchronized (CalendarRefresh.class) {
                        RUNNING.remove(calendar); retry = observer != null;
                        current = ATTEMPTS.get(calendar) == attempt && CalendarRepository.canRead(app);
                        if (current) {
                            String end = Instant.ofEpochMilli(attempt.until).atZone(zone).toLocalDate()
                                    .format(DateTimeFormatter.ofPattern("dd.MM.yy"));
                            if (failure == null) CACHE.put(calendar, new State(entries, "Keine Termine (bis " + end + ")", attempt));
                            else retainOrFail(calendar, attempt, failure.getMessage());
                        }
                    }
                    if (!current && retry) {
                        // An invalidation during I/O must cause one new read, not a stale publish.
                        // Re-check selection/permission, including if the initiating widget changed.
                        if (Store.calendarId(app, id) == calendar) request(app, id);
                        for (int widget : BlockzeitWidget.ids(app)) if (Store.calendarId(app, widget) == calendar) request(app, widget);
                    }
                    for (int widget : BlockzeitWidget.ids(app)) if (Store.mode(app, widget) == WidgetMode.CALENDAR
                            && Store.calendarId(app, widget) == calendar) BlockzeitWidget.render(app, widget);
                });
        if (request == null) {
            RUNNING.remove(calendar);
            retainOrFail(calendar, attempt, CalendarRepository.SATURATED);
        }
    }
    private static void retainOrFail(long calendar, Attempt attempt, String failure) {
        attempt.failure = failure;
        State cached = CACHE.get(calendar);
        if (cached == null || !cached.usable(attempt.from, attempt.zone))
            CACHE.put(calendar, new State(Collections.emptyList(), failure));
    }
}
