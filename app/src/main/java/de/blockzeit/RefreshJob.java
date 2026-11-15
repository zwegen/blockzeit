package de.blockzeit;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only coherent block acquisition owns this job; optional network work has its own admission. */
@android.annotation.TargetApi(31)
public final class RefreshJob extends JobService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Run run;
    private static final class Run {
        final JobParameters params;
        RefreshCycle cycle;
        boolean stopped;
        android.content.SharedPreferences.OnSharedPreferenceChangeListener changes;
        Run(JobParameters params) { this.params = params; }
    }
    @Override public boolean onStartJob(JobParameters params) {
        if (run != null || BlockzeitWidget.ids(this).length == 0 || !Scheduler.isInteractive(this)) return false;
        List<CenterRefresh.Request> pending = CenterRefresh.pending(this, de.blockzeit.core.WidgetMode.BLOCK);
        CenterRefresh.Request manual = pending.isEmpty() ? null : pending.get(0);
        if (manual == null && params != null && params.getExtras().getBoolean("centerOnly", false)) return false;
        if (manual == null && params != null && params.getExtras().getBoolean("freshness", false)
                && !Scheduler.staleBlocks(this, System.currentTimeMillis() / 1000)) return false;
        Run current = new Run(params);
        if (manual != null ? !Scheduler.beginManual(this, manual, current) : !Scheduler.beginBlocks(this, current)) return false;
        current.cycle = new RefreshCycle(worker, command -> main.post(command), new RefreshCycle.Listener() {
            @Override public void publish() {
                if (Scheduler.isInteractive(RefreshJob.this) && (manual == null || manual.matches(RefreshJob.this)))
                    if (params != null && params.getExtras().getBoolean("freshness", false)) BlockzeitWidget.renderCachedAll(RefreshJob.this);
                    else BlockzeitWidget.renderAll(RefreshJob.this);
            }
            @Override public void finished(RefreshCycle owner, boolean cancelled) {
                if (run != current || current.cycle != owner) return;
                run = null;
                if (current.changes != null) Store.prefs(RefreshJob.this).unregisterOnSharedPreferenceChangeListener(current.changes);
                if (manual != null && (cancelled || !manual.handedOff(RefreshJob.this))) manual.finish(RefreshJob.this);
                Scheduler.endBlocks(current);
                if (!current.stopped) {
                    jobFinished(params, false);
                    Scheduler.enqueueManual(RefreshJob.this, true);
                    SupplementScheduler.enqueuePending(RefreshJob.this, false);
                }
            }
        });
        run = current;
        if (manual != null) {
            current.changes = (preferences, key) -> { if (!manual.matches(this)) main.post(current.cycle::cancel); };
            Store.prefs(this).registerOnSharedPreferenceChangeListener(current.changes);
        }
        BlockClient blocks = new BlockClient(getApplicationContext(), new AtomicBoolean(false), current.cycle, manual);
        current.cycle.start(List.of(new RefreshCycle.Lane() {
            @Override public void run() {
                try { blocks.refresh(getApplicationContext()); }
                catch (Exception e) { blocks.recordBlockFailure(e); }
            }
            @Override public void cancel() { blocks.cancel(); }
        }));
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        if (run != null && sameExecution(run.params, params)) { run.stopped = true; run.cycle.cancel(); }
        return false;
    }
    static boolean sameExecution(JobParameters expected, JobParameters actual) {
        if (expected == actual) return true;
        if (expected == null || actual == null || expected.getJobId() != actual.getJobId()) return false;
        String token = expected.getExtras().getString("generation", "");
        return !token.isEmpty() && token.equals(actual.getExtras().getString("generation", ""));
    }
    @Override public void onDestroy() {
        if (run != null) { run.stopped = true; run.cycle.cancel(); }
        worker.shutdown(); // Cancelled wrappers drain before their own admission is released.
        super.onDestroy();
    }
}
