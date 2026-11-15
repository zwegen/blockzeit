package de.blockzeit;

import de.blockzeit.R;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.Build;
import de.blockzeit.core.DayMath;
import java.time.ZoneId;

@android.annotation.TargetApi(31)
final class Scheduler {
    static final int JOB_ID = 0x424c4b;
    static final String REFRESH = "de.blockzeit.REFRESH";
    static final long INTERVAL = 3 * 60 * 1000L; // Network job backoff only, NOT a redraw timer.
    private static final long MINUTE = 60_000L;
    private static Object blockOwner;
    private Scheduler() { }
    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 0, new Intent(c, RefreshReceiver.class).setAction(REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    /** Strictly NEXT civil-minute boundary, including when called exactly on a boundary. */
    static long nextMinute(long now) { return now - Math.floorMod(now, MINUTE) + MINUTE; }
    static boolean exactAllowed(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    static void ensure(Context c) {
        if (BlockzeitWidget.ids(c).length == 0) { cancel(c); return; }
        cancelLegacyJobs(c);
        SupplementScheduler.enqueueHistory(c);
        // TIME_TICK/wake remain best effort when permission is denied or revoked.
        WidgetWake.watch(c);
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        if (!exactAllowed(c)) { alarms.cancel(alarm(c)); return; }
        try {
            // Persistent explicit PendingIntent survives process death. Repeated ensure/toggle calls
            // target the SAME next wall-clock boundary, never now + an interval. RTC is NON-waking:
            // no per-minute Doze wakeups, while-idle exemption, foreground service or retry loop.
            alarms.setExact(AlarmManager.RTC, nextMinute(System.currentTimeMillis()), alarm(c));
        } catch (SecurityException revoked) {
            // Grant can disappear between checking and arming. Do not claim an inexact fallback
            // is exact; only process-local system broadcasts remain until access is granted.
            alarms.cancel(alarm(c));
        }
    }
    static boolean isInteractive(Context c) {
        PowerManager power = c.getSystemService(PowerManager.class);
        return power != null && power.isInteractive();
    }
    static synchronized void enqueue(Context c) {
        WeatherScheduler.enqueue(c);
        SupplementScheduler.enqueue(c);
        if (!CenterRefresh.pending(c, de.blockzeit.core.WidgetMode.BLOCK).isEmpty()) { enqueueManual(c); return; }
        enqueueBlocks(c, false);
    }
    /** Only the destination widget contributes new demand; the block ring shares its native lane. */
    static synchronized void enqueueMode(Context c, int id) {
        if (!BlockzeitWidget.active(c, id) || !isInteractive(c)) return;
        int mode = Store.mode(c, id);
        if (Store.blockEnabled(c, id)) enqueueBlocks(c, true);
        if (mode == de.blockzeit.core.WidgetMode.BLOCK || mode == de.blockzeit.core.WidgetMode.MARKET)
            SupplementScheduler.enqueueMode(c, id);
        if (mode == de.blockzeit.core.WidgetMode.WEATHER) WeatherScheduler.enqueueMode(c, id);
        if (mode == de.blockzeit.core.WidgetMode.CALENDAR) CalendarRefresh.request(c, id);
    }
    private static void enqueueBlocks(Context c, boolean freshness) {
        if (freshness && !staleBlocks(c, System.currentTimeMillis() / 1000)) return;
        if (!anyBlocksEnabled(c) || !isInteractive(c) || blockOwner != null
                || !needsCatchUp(c, System.currentTimeMillis() / 1000)) return;
        schedule(c, false, false, freshness);
    }
    private static void schedule(Context c, boolean manual, boolean replaceFinished) {
        schedule(c, manual, replaceFinished, false);
    }
    private static void schedule(Context c, boolean manual, boolean replaceFinished, boolean freshness) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        if (!replaceFinished && jobs.getPendingJob(JOB_ID) != null) return;
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("centerOnly", manual);
        extras.putBoolean("freshness", freshness);
        extras.putString("generation", java.util.UUID.randomUUID().toString());
        jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, RefreshJob.class))
                .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(INTERVAL, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
    static synchronized void enqueueManual(Context c) { enqueueManual(c, false); }
    static synchronized void enqueueManual(Context c, boolean replaceFinished) {
        SupplementScheduler.enqueueManual(c);
        if (blockOwner != null || !isInteractive(c)
                || CenterRefresh.pending(c, de.blockzeit.core.WidgetMode.BLOCK).isEmpty()) return;
        schedule(c, true, replaceFinished);
    }
    static synchronized boolean beginManual(Context c, CenterRefresh.Request request, Object owner) {
        if (blockOwner != null || !isInteractive(c) || request.mode != de.blockzeit.core.WidgetMode.BLOCK
                || !request.matches(c) || request.handedOff(c)) return false;
        boolean cooldown;
        synchronized (ProviderState.LOCK) {
            long now = System.currentTimeMillis() / 1000;
            cooldown = Store.prefs(c).getLong("retryAfter", 0) > now;
            if (!cooldown && !Store.prefs(c).edit().putLong("blockAttempt", now)
                    .putString("blockAttemptDay", DayMath.at(now, ZoneId.systemDefault()).key).commit()) return false;
        }
        if (cooldown) { request.finish(c); return false; }
        blockOwner = owner;
        return true;
    }
    /** Every acquisition entrypoint shares the same minute gate; local rendering never waits for it. */
    static void catchUp(Context c) { enqueue(c); }
    static boolean needsCatchUp(Context c, long now) {
        android.content.SharedPreferences prefs = Store.prefs(c);
        if (prefs.getLong("retryAfter", 0) > now) return false;
        long attempted = prefs.getLong("blockAttempt", 0);
        String day = DayMath.at(now, ZoneId.systemDefault()).key;
        // One attempt per civil minute across ticks/wake/alarms/widgets. The 45s floor tolerates
        // minute-boundary delivery jitter without admitting a second request a second later.
        return blockIntervalPassed(now, attempted, prefs.getString("blockAttemptDay", ""), day);
    }
    private static boolean blockIntervalPassed(long now, long checked, String checkedDay, String day) {
        return checked <= 0 || checked > now || !day.equals(checkedDay)
                || (now / 60 != checked / 60 && now - checked >= 45);
    }
    static boolean staleBlocks(Context c, long now) {
        Store.Snapshot snapshot = Store.load(c);
        DayMath.Day day = DayMath.at(now, ZoneId.systemDefault());
        return !snapshot.displayable(now, day) || blockIntervalPassed(now, snapshot.checked, snapshot.dayKey, day.key);
    }
    private static boolean anyBlocksEnabled(Context c) {
        for (int id : BlockzeitWidget.ids(c)) if (Store.blockEnabled(c, id)) return true;
        return false;
    }
    static synchronized boolean beginBlocks(Context c, Object owner) {
        long now = System.currentTimeMillis() / 1000;
        if (blockOwner != null || !anyBlocksEnabled(c) || !isInteractive(c)) return false;
        synchronized (ProviderState.LOCK) {
            if (!needsCatchUp(c, now)) return false;
            String day = DayMath.at(now, ZoneId.systemDefault()).key;
            // Reserve immediately before actual work, never while waiting for connectivity.
            if (!Store.prefs(c).edit().putLong("blockAttempt", now).putString("blockAttemptDay", day).commit()) return false;
        }
        blockOwner = owner;
        return true;
    }
    static boolean priceDue(Context c) {
        android.content.SharedPreferences prefs = Store.prefs(c);
        return Store.anySelected(c, de.blockzeit.core.WidgetMode.MARKET) && de.blockzeit.core.MarketData.due(true, System.currentTimeMillis() / 1000,
                prefs.getLong("priceAttempt", 0), prefs.getLong("retryAfter", 0));
    }
    static synchronized void endBlocks(Object owner) { if (blockOwner == owner) blockOwner = null; }
    static boolean blocksDue(Context c) {
        return Store.prefs(c).getLong("retryAfter", 0) <= System.currentTimeMillis() / 1000;
    }
    /** Retired optional-device job ID: cancel old scheduled work without reading/deleting its preferences. */
    static void cancelLegacyJobs(Context c) {
        c.getSystemService(JobScheduler.class).cancel(0x4d494e);
    }
    static void cancel(Context c) {
        CenterRefresh.clear(c);
        SupplementScheduler.cancel(c);
        SupplementScheduler.cancelHistory(c);
        WeatherScheduler.cancel(c);
        cancelLegacyJobs(c);
        c.getSystemService(AlarmManager.class).cancel(alarm(c));
        c.getSystemService(JobScheduler.class).cancel(JOB_ID);
        WidgetWake.stop();
    }
}
