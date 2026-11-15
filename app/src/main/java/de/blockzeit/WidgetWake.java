package de.blockzeit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/** Best-effort all-mode minute ticks/wake catch-up; the exact PendingIntent survives process death. */
@android.annotation.TargetApi(31)
final class WidgetWake {
    @android.annotation.SuppressLint("StaticFieldLeak") // Application context only, never an Activity/Service.
    private static Context registered;
    private static final BroadcastReceiver WAKE = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                CenterRefresh.clear(context);
                context.getSystemService(android.app.job.JobScheduler.class).cancel(Scheduler.JOB_ID);
                SupplementScheduler.cancel(context);
                WeatherScheduler.cancel(context);
                Scheduler.cancelLegacyJobs(context);
                return; // JobService disconnects all lanes; keep the existing minute/wake receiver.
            }
            if (Intent.ACTION_TIME_TICK.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)) {
                // Rearm independently of display/network errors; also recover on wake/time changes.
                // Repeated ticks/wake/toggles keep the same NEXT minute boundary, not a rolling delay.
                Scheduler.ensure(context);
                try { BlockzeitWidget.renderAll(context); }
                finally { Scheduler.catchUp(context); } // Bounded asynchronous acquisition, separate from redraw.
            }
        }
    };
    private WidgetWake() { }
    static synchronized void watch(Context context) {
        if (registered != null) return;
        Context app = context.getApplicationContext();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_TIME_TICK);
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(WAKE, filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(WAKE, filter);
            registered = app;
        } catch (RuntimeException ignored) {
            // Registration is best effort; Exact alarm (when permitted) remains independent of this process-local receiver.
        }
    }
    static synchronized void stop() {
        if (registered == null) return;
        try { registered.unregisterReceiver(WAKE); }
        catch (IllegalArgumentException ignored) { /* Already detached by the framework. */ }
        finally { registered = null; }
    }
}
