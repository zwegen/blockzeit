package de.blockzeit;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import de.blockzeit.core.CalendarAgenda;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One process-wide lane for every provider operation; no private event database or background job. */
final class CalendarRepository {
    static final int MAX_PENDING = 16;
    static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING), new ThreadPoolExecutor.AbortPolicy());
    static { WORKER.allowCoreThreadTimeOut(true); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final int MAX_ROWS = 512;
    static final String OVERFLOW = "Too many events (more than 512)";
    static final String SATURATED = "Calendar storage busy · try again later";

    interface Work<T> { T run(Context app, CancellationSignal cancel) throws Failure; }
    interface Reply<T> { void finish(T value, Failure failure); }

    /** Queue entries own only application context + non-UI work. cancel() detaches the UI reply,
     * removes queued work and posted publication, and cancels a running provider query. */
    static final class Request<T> implements Runnable {
        private final Context app;
        private final CancellationSignal cancel = new CancellationSignal();
        private Work<T> work;
        private Reply<T> reply;
        private boolean cancelled;
        private T value;
        private Failure failure;
        private final Runnable publication = this::publish;
        Request(Context context, Work<T> work, Reply<T> reply) {
            app = context.getApplicationContext(); this.work = work; this.reply = reply;
        }
        void cancel() {
            synchronized (this) {
                cancelled = true; work = null; reply = null; value = null; failure = null;
                WORKER.remove(this); MAIN.removeCallbacks(publication);
            }
            cancel.cancel();
        }
        @Override public void run() {
            Work<T> operation;
            synchronized (this) {
                if (cancelled) return;
                operation = work; work = null;
            }
            T completed = null; Failure error = null;
            try { cancel.throwIfCanceled(); completed = operation.run(app, cancel); }
            catch (Failure failed) { error = failed; }
            catch (RuntimeException unavailable) { error = readFailure(unavailable); }
            synchronized (this) {
                if (cancelled) return;
                value = completed; failure = error; MAIN.post(publication);
            }
        }
        private void publish() {
            Reply<T> callback; T completed; Failure error;
            synchronized (this) {
                callback = reply; completed = value; error = failure;
                reply = null; value = null; failure = null;
            }
            if (callback != null) callback.finish(completed, error);
        }
    }
    /** No caller-runs fallback: even saturation must never put provider IO on the UI thread. */
    static <T> Request<T> enqueue(Context app, Work<T> work, Reply<T> reply) {
        Request<T> request = new Request<>(app, work, reply);
        try { WORKER.execute(request); return request; }
        catch (RejectedExecutionException full) { request.cancel(); return null; }
    }
    static final class Calendar {
        final long id; final String name;
        Calendar(long id, String name) { this.id = id; this.name = CalendarAgenda.singleLine(name == null ? "Calendar" : name); }
    }
    static boolean canRead(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean canWrite(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }
    static final class Failure extends Exception {
        Failure(String message) { super(message); }
    }
    // Cursor work can fail after ContentResolver.query has returned. Preserve permission
    // and cancellation semantics there too; never include provider text or event data.
    private static Failure readFailure(RuntimeException error) {
        if (error instanceof SecurityException) return new Failure("Allow calendar access");
        if (error instanceof android.os.OperationCanceledException)
            return new Failure("Calendar query cancelled or timed out. Please reload.");
        return new Failure("Calendar storage is unavailable");
    }
    @android.annotation.SuppressLint("Recycle") // Ownership transfers to CursorWrapper; every caller uses try-with-resources.
    static Cursor query(Context c, Uri uri, String[] columns, String where, String[] args, String order, CancellationSignal cancel) throws Failure {
        Runnable timeout = cancel::cancel;
        MAIN.postDelayed(timeout, 5000);
        try {
            cancel.throwIfCanceled();
            Cursor result = c.getContentResolver().query(uri, columns, where, args, order, cancel);
            if (result == null) throw new Failure("Calendar storage is unavailable");
            return new CursorWrapper(result) {
                @Override public boolean moveToPosition(int position) { cancel.throwIfCanceled(); return super.moveToPosition(position); }
                @Override public boolean moveToFirst() { cancel.throwIfCanceled(); return super.moveToFirst(); }
                @Override public boolean moveToNext() { cancel.throwIfCanceled(); return super.moveToNext(); }
                @Override public void close() { try { super.close(); } finally { MAIN.removeCallbacks(timeout); } }
            };
        } catch (SecurityException denied) { MAIN.removeCallbacks(timeout); throw new Failure("Allow calendar access"); }
        catch (RuntimeException unavailable) { MAIN.removeCallbacks(timeout); throw readFailure(unavailable); }
        catch (Failure failure) { MAIN.removeCallbacks(timeout); throw failure; }
    }
    static List<Calendar> calendars(Context c) throws Failure { return calendars(c, new CancellationSignal()); }
    static List<Calendar> calendars(Context c, CancellationSignal cancel) throws Failure {
        if (!canRead(c)) throw new Failure("Allow calendar access");
        List<Calendar> result = new ArrayList<>();
        try (Cursor rows = query(c, CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME},
                null, null, CalendarContract.Calendars._ID + " ASC", cancel)) {
            while (result.size() < MAX_ROWS && rows.moveToNext()) result.add(new Calendar(rows.getLong(0), rows.getString(1)));
        } catch (RuntimeException failure) { throw readFailure(failure); }
        return result;
    }
    private static boolean exists(Context c, long id, CancellationSignal cancel) throws Failure {
        try (Cursor row = query(c, ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
                new String[]{CalendarContract.Calendars._ID}, null, null, null, cancel)) { return row.moveToFirst(); }
        catch (RuntimeException unavailable) { throw readFailure(unavailable); }
    }
    static List<CalendarAgenda.Entry> instances(Context c, long id, long now, ZoneId zone) throws Failure {
        return instances(c, id, now, zone, new CancellationSignal());
    }
    static List<CalendarAgenda.Entry> instances(Context c, long id, long now, ZoneId zone, CancellationSignal cancel) throws Failure {
        if (!canRead(c)) throw new Failure("Allow calendar access");
        if (!exists(c, id, cancel)) throw new Failure("The calendar no longer exists");
        return instancesUntil(c, id, now, zone, now + 370L * 86400000, cancel);
    }
    /** Editor agenda: widen only until five real occurrences are known, not five masters.
     * Each range and cursor is finite; never claim a partial bounded search is complete. */
    static List<CalendarAgenda.Entry> nextFive(Context c, long id, long now, ZoneId zone, CancellationSignal cancel) throws Failure {
        if (!canRead(c)) throw new Failure("Allow calendar access");
        if (!exists(c, id, cancel)) throw new Failure("The calendar no longer exists");
        long[] days = {7, 31, 370, 5 * 366, 25 * 366, 125 * 366};
        List<CalendarAgenda.Entry> result = new ArrayList<>(); long end = now;
        for (long span : days) {
            end = now + span * 86400000;
            result = instancesUntil(c, id, now, zone, end, cancel);
            if (result.size() >= 5) {
                CalendarAgenda.Entry fifth = result.get(4);
                long effective = fifth.allDay ? fifth.date(zone).atStartOfDay(zone).toInstant().toEpochMilli() : fifth.begin;
                // An all-day UTC date outside the provider interval may start locally up to
                // 26 hours earlier. Keep two days of margin before calling these the next five.
                if (effective < end - 2 * 86400000L) return new ArrayList<>(result.subList(0, 5));
            }
        }
        // LAST_DATE is provider-computed, including recurrence. Missing/infinite or later
        // bounds cannot prove that fewer than five exist; fail openly, without partial results.
        int count = 0;
        try (Cursor rows = query(c, CalendarContract.Events.CONTENT_URI,
                new String[]{CalendarContract.Events.DTSTART, CalendarContract.Events.LAST_DATE,
                        CalendarContract.Events.RRULE, CalendarContract.Events.RDATE},
                CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events.DELETED + "=0 AND ("
                        + CalendarContract.Events.STATUS + " IS NULL OR " + CalendarContract.Events.STATUS + "!=?)",
                new String[]{Long.toString(id), Integer.toString(CalendarContract.Events.STATUS_CANCELED)},
                CalendarContract.Events._ID + " ASC", cancel)) {
            while (rows.moveToNext()) {
                if (++count > MAX_ROWS) throw new Failure(OVERFLOW);
                boolean recurrence = rows.getString(2) != null && !rows.getString(2).isEmpty()
                        || rows.getString(3) != null && !rows.getString(3).isEmpty();
                if (rows.getLong(0) >= end || recurrence && (rows.isNull(1) || rows.getLong(1) >= end))
                    throw new Failure("The next five occurrences could not be fully determined within the bounded search (125 years).");
            }
        }
        return new ArrayList<>(result.subList(0, Math.min(5, result.size())));
    }
    /** Overview-only history. Instances, not event masters, preserve recurring exceptions.
     * Search backwards by END (a long event may have begun before the interval). */
    static CalendarAgenda.Entry lastEnded(Context c, long id, long now, ZoneId zone, CancellationSignal cancel) throws Failure {
        if (!canRead(c)) throw new Failure("Allow calendar access");
        if (!exists(c, id, cancel)) throw new Failure("The calendar no longer exists");
        long todayUtc = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long margin = 2 * 86400000L;
        long lower = now;
        for (long days : new long[]{7, 31, 370, 5 * 366, 25 * 366, 125 * 366}) {
            lower = now - days * 86400000L;
            Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(uri, lower - margin);
            ContentUris.appendId(uri, Math.max(now, todayUtc));
            CalendarAgenda.Entry best = null;
            try (Cursor rows = query(c, uri.build(), new String[]{CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.ALL_DAY}, CalendarContract.Instances.CALENDAR_ID + "=? AND "
                    + CalendarContract.Events.DELETED + "=0 AND (" + CalendarContract.Instances.STATUS + " IS NULL OR "
                    + CalendarContract.Instances.STATUS + "!=?) AND " + CalendarContract.Instances.END + ">=? AND (("
                    + CalendarContract.Instances.ALL_DAY + "=1 AND " + CalendarContract.Instances.END + "<=?) OR ("
                    + CalendarContract.Instances.ALL_DAY + "=0 AND " + CalendarContract.Instances.END + "<=?))",
                    new String[]{Long.toString(id), Integer.toString(CalendarContract.Events.STATUS_CANCELED),
                            Long.toString(lower - margin), Long.toString(todayUtc), Long.toString(now)},
                    CalendarContract.Instances.END + " DESC, " + CalendarContract.Instances.BEGIN + " DESC, "
                            + CalendarContract.Instances.EVENT_ID + " ASC", cancel)) {
                int count = 0;
                while (rows.moveToNext()) {
                    // END is provider-sorted. Even the largest UTC/local all-day offset
                    // cannot overtake best beyond this margin; no need to scan older rows.
                    if (best != null && rows.getLong(2) < best.endedAt(zone) - margin) break;
                    if (++count > MAX_ROWS) throw new Failure(OVERFLOW);
                    String title = rows.getString(3);
                    if (title != null && title.length() > 4096) title = title.substring(0, 4096);
                    CalendarAgenda.Entry entry = new CalendarAgenda.Entry(rows.getLong(0), rows.getLong(1), rows.getLong(2), title, rows.getInt(4) != 0);
                    if (entry.ended(now, zone) && (best == null || entry.endedAt(zone) > best.endedAt(zone)
                            || entry.endedAt(zone) == best.endedAt(zone) && (entry.begin > best.begin
                            || entry.begin == best.begin && entry.id < best.id))) best = entry;
                }
            } catch (RuntimeException failure) { throw readFailure(failure); }
            if (best != null && best.endedAt(zone) >= lower) return best;
        }
        // No silent "none" if records predate the finite occurrence horizon.
        try (Cursor first = query(c, CalendarContract.Events.CONTENT_URI,
                new String[]{CalendarContract.Events.DTSTART}, CalendarContract.Events.CALENDAR_ID + "=? AND "
                        + CalendarContract.Events.DELETED + "=0 AND (" + CalendarContract.Events.STATUS + " IS NULL OR "
                        + CalendarContract.Events.STATUS + "!=?)", new String[]{Long.toString(id),
                        Integer.toString(CalendarContract.Events.STATUS_CANCELED)}, CalendarContract.Events.DTSTART + " ASC", cancel)) {
            if (first.moveToFirst() && first.getLong(0) < lower + margin)
                throw new Failure("The last ended occurrence could not be fully determined within the bounded search (125 years).");
        } catch (RuntimeException failure) { throw readFailure(failure); }
        return null;
    }
    private static List<CalendarAgenda.Entry> instancesUntil(Context c, long id, long now, ZoneId zone, long end, CancellationSignal cancel) throws Failure {
        // Include today's UTC date boundary even west of UTC. The provider expands recurring events.
        long todayUtc = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long start = Math.min(now, todayUtc);
        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, start); ContentUris.appendId(builder, end);
        List<CalendarAgenda.Entry> result = new ArrayList<>();
        try (Cursor rows = query(c, builder.build(), new String[]{CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.ALL_DAY}, CalendarContract.Instances.CALENDAR_ID + "=? AND "
                + "(" + CalendarContract.Instances.STATUS + " IS NULL OR " + CalendarContract.Instances.STATUS + "!=?) AND (("
                + CalendarContract.Instances.ALL_DAY + "=1 AND " + CalendarContract.Instances.END + ">?) OR ("
                + CalendarContract.Instances.ALL_DAY + "=0 AND (" + CalendarContract.Instances.END + ">? OR ("
                + CalendarContract.Instances.BEGIN + "=" + CalendarContract.Instances.END + " AND "
                + CalendarContract.Instances.BEGIN + ">=?))))", new String[]{Long.toString(id),
                Integer.toString(CalendarContract.Events.STATUS_CANCELED), Long.toString(todayUtc), Long.toString(now), Long.toString(now)},
                CalendarContract.Instances.BEGIN + " ASC, " + CalendarContract.Instances.EVENT_ID + " ASC", cancel)) {
            for (int count = 0; count < MAX_ROWS && rows.moveToNext(); count++) {
                String title = rows.getString(3);
                if (title != null && title.length() > 4096) title = title.substring(0, 4096);
                result.add(new CalendarAgenda.Entry(rows.getLong(0), rows.getLong(1), rows.getLong(2), title, rows.getInt(4) != 0));
            }
            // UTC provider order is not local all-day order. Even one omitted row could be
            // the true first/second occurrence; never publish a seemingly valid truncated agenda.
            if (result.size() == MAX_ROWS && rows.moveToNext()) throw new Failure(OVERFLOW);
        } catch (RuntimeException failure) { throw readFailure(failure); }
        return CalendarAgenda.upcoming(result, now, zone);
    }
    static long createLocal(Context c, String name) throws Failure { return createLocal(c, name, new CancellationSignal()); }
    static long createLocal(Context c, String name, CancellationSignal cancel) throws Failure {
        if (!canWrite(c)) throw new Failure("Allow calendar write access");
        String account = "Blockzeit";
        Uri uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build();
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, account);
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        values.put(CalendarContract.Calendars.NAME, java.util.UUID.randomUUID().toString());
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name);
        values.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xff29b6f6);
        values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, account);
        values.put(CalendarContract.Calendars.VISIBLE, 1);
        values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        values.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().getId());
        try {
            // insert() has no CancellationSignal API. Once entered, its durable side effect
            // cannot be undone by closing settings; queued/never-started inserts are removed.
            cancel.throwIfCanceled();
            Uri created = c.getContentResolver().insert(uri, values);
            if (created == null) throw new Failure("Could not create the local calendar");
            return ContentUris.parseId(created);
        } catch (RuntimeException failure) { throw new Failure("Could not create the local calendar"); }
    }
}
