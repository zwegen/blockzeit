package de.blockzeit;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Calendars;
import de.blockzeit.core.WidgetMode;
import java.time.*;
import java.util.*;

/** Explicit-save operations only. All callers use the existing bounded provider lane. */
final class CalendarEditing {
    static final int MAX_BULK_ROWS = 256, MAX_TITLE = 4096, MAX_PREVIEW_CHARS = 65536;
    static final String STALE = "The calendar or event has changed. Please reload.";
    static final String COMPLEX = "This recurrence configuration cannot be changed safely here. The entire series title can still be edited.";
    static final class Target {
        final int widget; final long calendar;
        Target(int widget, long calendar) { this.widget = widget; this.calendar = calendar; }
    }
    static final class Access {
        final boolean writable; final String name, zone; final int level;
        Access(boolean writable, String name, String zone, int level) {
            this.writable = writable; this.name = name; this.zone = zone; this.level = level;
        }
    }
    static void validate(Context c, Target t, CancellationSignal cancel) throws CalendarRepository.Failure {
        cancel.throwIfCanceled();
        boolean bound = false;
        for (int id : BlockzeitWidget.ids(c)) if (id == t.widget) bound = true;
        if (!bound || t.calendar < 0 || !Store.calendarEnabled(c, t.widget)
                || Store.calendarId(c, t.widget) != t.calendar || Store.mode(c, t.widget) != WidgetMode.CALENDAR)
            throw fail("The widget or calendar selection has changed. Reopen this window.");
        if (!CalendarRepository.canRead(c)) throw fail("Allow calendar access");
    }
    static Access access(Context c, Target t, CancellationSignal cancel) throws CalendarRepository.Failure {
        validate(c, t, cancel);
        try (Cursor r = query(c, ContentUris.withAppendedId(Calendars.CONTENT_URI, t.calendar),
                new String[]{Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_TIME_ZONE, Calendars.CALENDAR_ACCESS_LEVEL}, null, null, cancel)) {
            if (!r.moveToFirst() || r.getLong(0) != t.calendar) throw fail("The calendar no longer exists");
            String zone = r.getString(2);
            // Never invent a replacement zone for an invalid provider zone.
            if (zone == null || zone.isEmpty()) zone = ZoneId.systemDefault().getId();
            try { ZoneId.of(zone); } catch (DateTimeException invalid) { throw fail("Unknown calendar time zone"); }
            int level = r.getInt(3);
            return new Access(level >= Calendars.CAL_ACCESS_CONTRIBUTOR, r.getString(1), zone, level);
        }
    }
    private static Access writing(Context c, Target t, CancellationSignal cancel) throws CalendarRepository.Failure {
        Access a = access(c, t, cancel);
        if (!a.writable) throw fail("This calendar is read-only.");
        if (!CalendarRepository.canWrite(c)) throw fail("Allow calendar write access");
        return a;
    }
    private static final String[] FIELDS = {Events._ID, Events.CALENDAR_ID, Events.TITLE,
            Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE,
            Events.ALL_DAY, Events.DURATION, Events.RRULE, Events.RDATE, Events.EXDATE, Events.EXRULE,
            Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
            Events.STATUS, Events.DELETED, Events.ORIGINAL_SYNC_ID, Events._SYNC_ID};
    static final class Event {
        final long id, calendar, start, end, original, occurrence;
        final String title, zone, rrule, rdate, exdate, duration;
        final boolean allDay, recurring, exception;
        final ContentValues snapshot;
        Event(ContentValues row, long begin, long finish) throws CalendarRepository.Failure {
            snapshot = new ContentValues(row); id = number(row, Events._ID); calendar = number(row, Events.CALENDAR_ID);
            title = row.getAsString(Events.TITLE); zone = row.getAsString(Events.EVENT_TIMEZONE);
            rrule = row.getAsString(Events.RRULE); rdate = row.getAsString(Events.RDATE);
            exdate = row.getAsString(Events.EXDATE); duration = row.getAsString(Events.DURATION);
            recurring = present(rrule) || present(rdate); exception = row.getAsLong(Events.ORIGINAL_ID) != null || present(row.getAsString(Events.ORIGINAL_SYNC_ID));
            original = number(row, Events.ORIGINAL_ID); allDay = number(row, Events.ALL_DAY) != 0;
            occurrence = begin; start = begin; end = finish;
            if (!allDay) try { ZoneId.of(zone); } catch (RuntimeException invalid) { throw fail("Unknown event time zone"); }
        }
    }
    static Event load(Context c, Target t, long id, long begin, CancellationSignal cancel) throws CalendarRepository.Failure {
        access(c, t, cancel);
        ContentValues values = eventRow(c, t, id, cancel);
        long[] instance = occurrence(c, t, id, begin, cancel);
        return new Event(values, instance[0], instance[1]);
    }
    private static ContentValues eventRow(Context c, Target t, long id, CancellationSignal cancel) throws CalendarRepository.Failure {
        try (Cursor r = query(c, ContentUris.withAppendedId(Events.CONTENT_URI, id), FIELDS,
                Events.CALENDAR_ID + "=?", new String[]{Long.toString(t.calendar)}, cancel)) {
            if (!r.moveToFirst()) throw fail(STALE);
            ContentValues row = values(r, FIELDS);
            if (number(row, Events.CALENDAR_ID) != t.calendar || number(row, Events.DELETED) != 0
                    || number(row, Events.STATUS) == Events.STATUS_CANCELED) throw fail("The event is cancelled or no longer exists.");
            if (r.moveToNext()) throw fail(STALE);
            return row;
        }
    }
    private static long[] occurrence(Context c, Target t, long id, long begin, CancellationSignal cancel) throws CalendarRepository.Failure {
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, begin); ContentUris.appendId(b, Math.addExact(begin, 1));
        // AOSP joins Instances and view_events, both with _id. Since this projection
        // contains no _id alias, the Events helper's "_id ASC" would be ambiguous.
        try (Cursor r = CalendarRepository.query(c, b.build(), new String[]{CalendarContract.Instances.BEGIN, CalendarContract.Instances.END},
                CalendarContract.Instances.CALENDAR_ID + "=? AND " + CalendarContract.Instances.EVENT_ID + "=? AND "
                        + CalendarContract.Instances.BEGIN + "=? AND (" + Events.STATUS + " IS NULL OR " + Events.STATUS + "!=?)",
                new String[]{Long.toString(t.calendar), Long.toString(id), Long.toString(begin), Integer.toString(Events.STATUS_CANCELED)},
                CalendarContract.Instances.BEGIN + " ASC", cancel)) {
            if (!r.moveToFirst() || r.getLong(0) != begin) throw fail("This occurrence no longer exists. Please reload.");
            long[] result = {r.getLong(0), r.getLong(1)};
            if (r.moveToNext()) throw fail(STALE);
            return result;
        }
    }
    static final class Draft {
        final String title, zone, repeat; final long start, end; final boolean allDay;
        Draft(String title, long start, long end, boolean allDay, String zone, String repeat) {
            this.title = title; this.start = start; this.end = end; this.allDay = allDay; this.zone = zone; this.repeat = repeat;
        }
    }
    static void save(Context c, Target t, Event old, Draft d, boolean wholeSeries, CancellationSignal cancel) throws CalendarRepository.Failure {
        Access a = writing(c, t, cancel); checkDraft(d);
        if (old == null) precision(d);
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(calendarAssert(t, a));
        if (old == null) {
            ContentValues v = new ContentValues(); v.put(Events.CALENDAR_ID, t.calendar);
            v.put(Events.TITLE, d.title); dates(v, d, rule(d.repeat));
            ops.add(ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(v).build());
        } else {
            if (old.calendar != t.calendar) throw fail(STALE);
            ContentValues fresh = eventRow(c, t, old.id, cancel);
            if (!old.snapshot.equals(fresh)) throw fail(STALE);
            long[] actual = occurrence(c, t, old.id, old.occurrence, cancel);
            if (old.start != actual[0] || old.end != actual[1]) throw fail(STALE);
            ops.add(assertRow(old.id, fresh));
            boolean times = d.start != old.start || d.end != old.end || d.allDay != old.allDay
                    || !Objects.equals(d.zone, old.allDay ? "UTC" : old.zone);
            boolean repeat = !"KEEP".equals(d.repeat);
            if (times || repeat) precision(d);
            String endZone = old.snapshot.getAsString(Events.EVENT_END_TIMEZONE);
            if (times && present(endZone) && !Objects.equals(endZone, old.zone) && !old.allDay)
                throw fail("Different end time zone: time changes are blocked here; the title can still be edited.");
            ContentValues v = new ContentValues();
            if (!Objects.equals(d.title, old.title)) v.put(Events.TITLE, d.title);
            if (old.exception && wholeSeries) throw fail("This occurrence is already a recurrence exception. Select the series separately.");
            if (old.recurring && !wholeSeries) {
                // Public exception URI clones provider metadata AND attendee/reminder tables in AOSP.
                // It requires RRULE; RDATE-only series and existing duplicates are rejected, never guessed.
                if (!present(old.rrule) || present(old.rdate) || present(old.exdate)
                        || present(old.snapshot.getAsString(Events.EXRULE)) || repeat) throw fail(COMPLEX);
                noExceptions(c, t, old, old.occurrence, false, cancel);
                ops.add(noExceptionAssert(t, old, old.occurrence, false));
                if (v.size() == 0 && !times) return;
                v.put(Events.ORIGINAL_INSTANCE_TIME, old.occurrence);
                v.put(Events.DTSTART, d.start); v.put(Events.ALL_DAY, d.allDay ? 1 : 0);
                v.put(Events.EVENT_TIMEZONE, d.allDay ? "UTC" : d.zone);
                // Exception URI derives DTEND from DURATION; supplying DTEND is explicitly illegal.
                v.put(Events.DURATION, !times && present(old.duration) ? old.duration : duration(d.start, d.end, d.allDay));
                ops.add(ContentProviderOperation.newInsert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, old.id)).withValues(v).build());
            } else {
                if (old.recurring && (times || repeat)) {
                    // Do not shift exclusions/overrides or discard a rich recurrence rule.
                    if (!simple(old) || d.allDay != old.allDay || !Objects.equals(d.zone, old.allDay ? "UTC" : old.zone)) throw fail(COMPLEX);
                    String nextRule = repeat ? rule(d.repeat) : old.rrule;
                    if (!present(nextRule)) throw fail("An existing series cannot be converted to a single event here.");
                    if (repeat) v.put(Events.RRULE, nextRule);
                    noExceptions(c, t, old, 0, true, cancel); ops.add(noExceptionAssert(t, old, 0, true));
                    ZoneId z = old.allDay ? ZoneOffset.UTC : ZoneId.of(old.zone);
                    LocalDateTime was = Instant.ofEpochMilli(old.start).atZone(z).toLocalDateTime();
                    LocalDateTime next = Instant.ofEpochMilli(d.start).atZone(z).toLocalDateTime();
                    LocalDateTime master = Instant.ofEpochMilli(number(fresh, Events.DTSTART)).atZone(z).toLocalDateTime();
                    if (d.start != old.start && !master.toLocalTime().equals(was.toLocalTime()))
                        throw fail("This occurrence was shifted by a daylight saving transition. Change the start for only this occurrence.");
                    LocalDateTime moved;
                    try {
                        LocalDate date;
                        if (old.rrule.startsWith("FREQ=MONTHLY")) {
                            long months = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(was), YearMonth.from(next));
                            date = YearMonth.from(master).plusMonths(months).atDay(next.getDayOfMonth());
                        } else if (old.rrule.startsWith("FREQ=YEARLY")) {
                            date = LocalDate.of(Math.addExact(master.getYear(), next.getYear() - was.getYear()), next.getMonth(), next.getDayOfMonth());
                        } else {
                            date = master.toLocalDate().plusDays(java.time.temporal.ChronoUnit.DAYS.between(was.toLocalDate(), next.toLocalDate()));
                        }
                        moved = LocalDateTime.of(date, master.toLocalTime()).plus(Duration.between(was.toLocalTime(), next.toLocalTime()));
                    } catch (DateTimeException | ArithmeticException invalid) {
                        throw fail("The changed series date does not exist at the series start. Change only this occurrence.");
                    }
                    if (z.getRules().getValidOffsets(moved).size() != 1) throw fail("The series start falls in a daylight saving transition. Changes are blocked.");
                    v.put(Events.DTSTART, moved.atZone(z).toInstant().toEpochMilli());
                    v.put(Events.DURATION, !times && present(old.duration) ? old.duration : duration(d.start, d.end, d.allDay));
                    v.putNull(Events.DTEND);
                } else if (times || repeat) {
                    if (old.exception && repeat) throw fail("The repeat setting of a recurrence exception cannot be changed here.");
                    dates(v, d, "KEEP".equals(d.repeat) ? old.rrule : rule(d.repeat));
                }
                if (v.size() == 0) return;
                // AOSP permits selections on events, not events/<id>. Keep both identity guards.
                ops.add(ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                        .withSelection(Events._ID + "=? AND " + Events.CALENDAR_ID + "=?",
                                new String[]{Long.toString(old.id), Long.toString(t.calendar)})
                        .withValues(v).withExpectedCount(1).build());
            }
        }
        batch(c, t, ops, cancel);
    }
    private static boolean simple(Event e) {
        return e.rrule != null && e.rrule.matches("FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;INTERVAL=[1-9][0-9]*)?;?")
                && !present(e.rdate) && !present(e.exdate) && !present(e.snapshot.getAsString(Events.EXRULE));
    }
    private static String rule(String repeat) throws CalendarRepository.Failure {
        if ("NONE".equals(repeat)) return null;
        if (Arrays.asList("DAILY", "WEEKLY", "MONTHLY", "YEARLY").contains(repeat)) return "FREQ=" + repeat;
        throw fail("Unknown repeat setting");
    }
    private static void checkDraft(Draft d) throws CalendarRepository.Failure {
        if (d.title == null || d.title.trim().isEmpty() || d.title.length() > MAX_TITLE) throw fail("Title required (at most 4096 characters).");
        if (d.end < d.start || d.allDay && d.end == d.start) throw fail("End must be after the start (exclusive for all-day events).");
        long length;
        try { length = Math.subtractExact(d.end, d.start); }
        catch (ArithmeticException overflow) { throw fail("Event duration is too long."); }
        if (length > 3660L * 86400000) throw fail("Event duration is too long.");

        try { ZoneId.of(d.zone); } catch (RuntimeException bad) { throw fail("Unknown time zone"); }
        if (d.allDay && (Math.floorMod(d.start, 86400000L) != 0 || Math.floorMod(d.end, 86400000L) != 0 || !"UTC".equals(d.zone)))
            throw fail("All-day events require UTC date boundaries and an exclusive end date.");
    }
    private static void precision(Draft d) throws CalendarRepository.Failure {
        if (Math.floorMod(d.start, 1000) != 0 || Math.floorMod(d.end, 1000) != 0)
            throw fail("Fractional seconds cannot be changed losslessly here. Title-only changes remain possible.");
    }
    private static void dates(ContentValues v, Draft d, String rrule) {
        v.put(Events.DTSTART, d.start); v.put(Events.ALL_DAY, d.allDay ? 1 : 0);
        v.put(Events.EVENT_TIMEZONE, d.allDay ? "UTC" : d.zone);
        v.put(Events.EVENT_END_TIMEZONE, d.allDay ? "UTC" : d.zone);
        v.put(Events.RRULE, rrule);
        if (present(rrule)) { v.putNull(Events.DTEND); v.put(Events.DURATION, duration(d.start, d.end, d.allDay)); }
        else { v.put(Events.DTEND, d.end); v.putNull(Events.DURATION); }
    }
    private static String duration(long start, long end, boolean allDay) {
        return allDay ? "P" + ((end - start) / 86400000) + "D" : "PT" + ((end - start) / 1000) + "S";
    }
    private static String exceptionWhere(Event event, boolean all) {
        String reference = Events.ORIGINAL_ID + "=?";
        if (present(event.snapshot.getAsString(Events._SYNC_ID))) reference += " OR " + Events.ORIGINAL_SYNC_ID + "=?";
        return Events.CALENDAR_ID + "=? AND (" + reference + ")" + (all ? "" : " AND " + Events.ORIGINAL_INSTANCE_TIME + "=?");
    }
    private static String[] exceptionArgs(Target t, Event event, long begin, boolean all) {
        List<String> args = new ArrayList<>(); args.add(Long.toString(t.calendar)); args.add(Long.toString(event.id));
        String sync = event.snapshot.getAsString(Events._SYNC_ID); if (present(sync)) args.add(sync);
        if (!all) args.add(Long.toString(begin));
        return args.toArray(new String[0]);
    }
    private static void noExceptions(Context c, Target t, Event event, long begin, boolean all, CancellationSignal cancel) throws CalendarRepository.Failure {
        try (Cursor r = query(c, Events.CONTENT_URI, new String[]{Events._ID}, exceptionWhere(event, all), exceptionArgs(t, event, begin, all), cancel)) {
            if (r.moveToFirst()) throw fail("A recurrence exception exists (possibly cancelled). Please reload; changes are blocked here.");
        }
    }
    private static ContentProviderOperation noExceptionAssert(Target t, Event event, long begin, boolean all) {
        return ContentProviderOperation.newAssertQuery(Events.CONTENT_URI).withSelection(exceptionWhere(event, all), exceptionArgs(t, event, begin, all)).withExpectedCount(0).build();
    }
    static final class Replacement {
        final long id; final String before, after; final ContentValues snapshot;
        Replacement(ContentValues row, String after) { id = number(row, Events._ID); before = row.getAsString(Events.TITLE); this.after = after; snapshot = new ContentValues(row); }
    }
    static final class Preview {
        final List<Replacement> matches; final int examined;
        final List<ContentValues> rows; final Target target; final String search, replacement;
        Preview(Target target, String search, String replacement, List<ContentValues> rows, List<Replacement> matches) {
            this.target = target; this.search = search; this.replacement = replacement;
            this.rows = Collections.unmodifiableList(rows); this.matches = Collections.unmodifiableList(matches); examined = rows.size();
        }
    }
    private static final String[] BULK_FIELDS = {Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DELETED};
    private static final String BULK_WHERE = Events.CALENDAR_ID + "=? AND " + Events.DELETED + "=0";
    static Preview preview(Context c, Target t, String search, String replacement, CancellationSignal cancel) throws CalendarRepository.Failure {
        access(c, t, cancel);
        if (search == null || search.isEmpty()) throw fail("Search text must not be empty.");
        if (replacement == null || search.length() > MAX_TITLE || replacement.length() > MAX_TITLE) throw fail("Search text or replacement is too long.");
        if (search.equals(replacement)) throw fail("Search text and replacement are identical. No change needed.");
        List<ContentValues> rows = new ArrayList<>(); List<Replacement> matches = new ArrayList<>(); int chars = 0;
        try (Cursor r = query(c, Events.CONTENT_URI, BULK_FIELDS, BULK_WHERE, new String[]{Long.toString(t.calendar)}, cancel)) {
            while (r.moveToNext()) {
                if (rows.size() == MAX_BULK_ROWS) throw fail("More than 256 calendar entries: full preview cancelled. Nothing changed.");
                ContentValues row = values(r, BULK_FIELDS);
                if (number(row, Events.CALENDAR_ID) != t.calendar || number(row, Events.DELETED) != 0) throw fail(STALE);
                String title = row.getAsString(Events.TITLE);
                if (title != null && title.length() > MAX_TITLE) throw fail("Title too long: full preview cancelled.");
                rows.add(row);
                if (title == null) continue;
                chars += title.length();
                if (title.contains(search)) {
                    // Literal String.replace; never regex. Expansion is bounded before constructing it.
                    long hits = 0; for (int at = 0; (at = title.indexOf(search, at)) >= 0; at += search.length()) hits++;
                    long length = title.length() + hits * ((long)replacement.length() - search.length());
                    if (length > MAX_TITLE) throw fail("Replaced title too long: full preview cancelled.");
                    String after = title.replace(search, replacement); chars += after.length();
                    if (!after.equals(title)) matches.add(new Replacement(row, after));
                }
                if (chars > MAX_PREVIEW_CHARS) throw fail("Preview too large: fully cancelled. Nothing changed.");
            }
        }
        return new Preview(t, search, replacement, rows, matches);
    }
    static void replace(Context c, Target t, Preview p, CancellationSignal cancel) throws CalendarRepository.Failure {
        Access a = writing(c, t, cancel);
        if (p == null || p.target.widget != t.widget || p.target.calendar != t.calendar) throw fail(STALE);
        Preview fresh = preview(c, t, p.search, p.replacement, cancel);
        if (!fresh.rows.equals(p.rows)) throw fail("Preview is stale. Please search again.");
        if (p.matches.isEmpty()) return;
        ArrayList<ContentProviderOperation> ops = new ArrayList<>(); ops.add(calendarAssert(t, a));
        ops.add(ContentProviderOperation.newAssertQuery(Events.CONTENT_URI).withSelection(BULK_WHERE, new String[]{Long.toString(t.calendar)}).withExpectedCount(p.examined).build());
        // All preconditions precede every mutation. Providers decide transaction support.
        for (ContentValues row : p.rows) ops.add(assertRow(number(row, Events._ID), row));
        for (Replacement match : p.matches) {
            ContentValues v = new ContentValues(); v.put(Events.TITLE, match.after);
            ops.add(ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                    .withSelection(Events._ID + "=? AND " + Events.CALENDAR_ID + "=?",
                            new String[]{Long.toString(match.id), Long.toString(t.calendar)})
                    .withValues(v).withExpectedCount(1).build());
        }
        batch(c, t, ops, cancel);
    }
    private static ContentProviderOperation calendarAssert(Target t, Access a) {
        return ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(Calendars.CONTENT_URI, t.calendar))
                .withValue(Calendars._ID, t.calendar).withValue(Calendars.CALENDAR_ACCESS_LEVEL, a.level).withExpectedCount(1).build();
    }
    private static ContentProviderOperation assertRow(long id, ContentValues row) {
        return ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(Events.CONTENT_URI, id)).withValues(row).withExpectedCount(1).build();
    }
    private static void batch(Context c, Target t, ArrayList<ContentProviderOperation> ops, CancellationSignal cancel) throws CalendarRepository.Failure {
        writing(c, t, cancel); // Last fresh widget, permissions and calendar check before entering non-cancellable provider batch.
        cancel.throwIfCanceled();
        try {
            ContentProviderResult[] result = c.getContentResolver().applyBatch(CalendarContract.AUTHORITY, ops);
            if (result == null || result.length != ops.size()) throw fail("Save result unclear. Reload the calendar; do not retry automatically.");
            for (int i = 0; i < result.length; i++) {
                if (ops.get(i).isInsert() && (result[i].uri == null || ContentUris.parseId(result[i].uri) <= 0)
                        || ops.get(i).isUpdate() && !Integer.valueOf(1).equals(result[i].count))
                    throw fail("Save result unclear. Reload the calendar; do not retry automatically.");
            }
        } catch (android.os.RemoteException | android.content.OperationApplicationException | RuntimeException error) {
            throw fail("Save failed or preview is stale. This provider may apply partial changes. Reload the calendar; do not retry automatically.");
        } finally {
            CalendarRefresh.invalidate(t.calendar);
        }
    }
    private static Cursor query(Context c, Uri uri, String[] projection, String where, String[] args, CancellationSignal cancel) throws CalendarRepository.Failure {
        return CalendarRepository.query(c, uri, projection, where, args, Events._ID + " ASC", cancel);
    }
    private static ContentValues values(Cursor r, String[] fields) {
        ContentValues v = new ContentValues();
        for (int i = 0; i < fields.length; i++) {
            if (r.isNull(i)) v.putNull(fields[i]);
            else if (r.getType(i) == Cursor.FIELD_TYPE_INTEGER) v.put(fields[i], r.getLong(i));
            else v.put(fields[i], r.getString(i));
        }
        return v;
    }
    private static long number(ContentValues v, String key) { Long n = v.getAsLong(key); return n == null ? 0 : n; }
    private static boolean present(String s) { return s != null && !s.isEmpty(); }
    private static CalendarRepository.Failure fail(String message) { return new CalendarRepository.Failure(message); }
}
