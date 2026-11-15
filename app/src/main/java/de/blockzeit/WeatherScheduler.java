package de.blockzeit;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Independent weather admission: no block minute, Retry-After, BTC lane or BTC deadline. */
public final class WeatherScheduler {
    static final int JOB_ID = 0x574541;
    private static final java.util.Set<Integer> pendingModes = new java.util.LinkedHashSet<>();
    private WeatherScheduler() { }
    static List<WeatherStore.Selection> due(Context context, long now) {
        int[] ids = BlockzeitWidget.ids(context);
        Arrays.sort(ids);
        List<WeatherStore.Selection> due = new ArrayList<>();
        if (ids.length == 0) return due;
        int cursor = Math.floorMod(WeatherStore.prefs(context).getInt("cursor", 0), ids.length);
        for (int offset = 0; offset < ids.length; offset++) {
            int id = ids[(cursor + offset) % ids.length];
            WeatherStore.Selection selection = WeatherStore.selection(context, id);
            if (selection != null && WeatherStore.due(context, selection, now)) due.add(selection);
        }
        return due;
    }
    static void advance(Context context, int id) {
        int[] ids = BlockzeitWidget.ids(context);
        Arrays.sort(ids);
        for (int i = 0; i < ids.length; i++) if (ids[i] == id) {
            WeatherStore.prefs(context).edit().putInt("cursor", (i + 1) % ids.length).apply();
            return;
        }
    }
    public static void enqueue(Context context) {
        WeatherJob.reconcile(context);
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        boolean configured = false;
        for (int id : BlockzeitWidget.ids(context)) {
            if (WeatherStore.active(context, id) && WeatherStore.place(context, id) != null) { configured = true; break; }
        }
        if (!Scheduler.isInteractive(context)) { cancel(context); return; }
        if (!configured) {
            // No new consumers: let already admitted automatic I/O finish into its place cache.
            if (!WeatherJob.running()) cancel(context);
            return;
        }
        if (!CenterRefresh.pending(context, true).isEmpty()) { enqueueManual(context); return; }
        if (WeatherJob.running() || due(context, System.currentTimeMillis() / 1000).isEmpty()) return;
        if (jobs.getPendingJob(JOB_ID) == null) jobs.schedule(new JobInfo.Builder(JOB_ID,
                new ComponentName(context, WeatherJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }
    static boolean modeDue(Context context, int id, long now) {
        WeatherStore.Selection selection = WeatherStore.selection(context, id);
        de.blockzeit.core.WeatherData.Sample sample = WeatherStore.sample(context, id);
        return Store.mode(context, id) == de.blockzeit.core.WidgetMode.WEATHER && selection != null
                && (sample == null || sample.stale(now)) && WeatherStore.due(context, selection, now);
    }
    static synchronized void enqueueMode(Context context, int id) {
        if (!Scheduler.isInteractive(context) || !modeDue(context, id, System.currentTimeMillis() / 1000)) return;
        if (WeatherJob.running()) { pendingModes.add(id); return; }
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        JobInfo pending = jobs.getPendingJob(JOB_ID);
        if (pending != null && !pending.getExtras().containsKey("modeWidgets")) return;
        java.util.Set<Integer> widgets = new java.util.LinkedHashSet<>();
        if (pending != null) for (int widget : pending.getExtras().getIntArray("modeWidgets")) widgets.add(widget);
        widgets.add(id);
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putIntArray("modeWidgets", widgets.stream().mapToInt(Integer::intValue).toArray());
        jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, WeatherJob.class))
                .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }
    static synchronized void enqueuePendingModes(Context context) {
        int[] ids = pendingModes.stream().mapToInt(Integer::intValue).toArray();
        pendingModes.clear();
        for (int id : ids) enqueueMode(context, id);
    }
    static void enqueueManual(Context context) {
        enqueueManual(context, false);
    }
    static void enqueueManual(Context context, boolean replaceFinished) {
        WeatherJob.reconcile(context);
        if (!Scheduler.isInteractive(context) || WeatherJob.running() || CenterRefresh.pending(context, true).isEmpty()) return;
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (!replaceFinished && jobs.getPendingJob(JOB_ID) != null) return;
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("centerOnly", true);
        jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, WeatherJob.class))
                .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }
    public static synchronized void cancel(Context context) {
        pendingModes.clear();
        WeatherJob.cancelActive();
        context.getSystemService(JobScheduler.class).cancel(JOB_ID);
    }
}
