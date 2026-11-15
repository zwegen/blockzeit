package de.blockzeit;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import de.blockzeit.core.WeatherData;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Three bounded weather workers, no per-widget task queue, and a hard aggregate cancellation timer. */
public final class WeatherJob extends JobService {
    static final long BUDGET_MILLIS = 40_000;
    private static final int WORKERS = 3;
    private static final AtomicReference<Run> ACTIVE = new AtomicReference<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Run owner;

    static boolean running() { return ACTIVE.get() != null; }
    static void cancelWidget(int id) { Run run = ACTIVE.get(); if (run != null) run.cancelWidget(id); }
    static void reconcile(Context context) { Run run = ACTIVE.get(); if (run != null) run.reconcile(context); }
    static void cancelActive() { Run run = ACTIVE.get(); if (run != null) run.stop(false); }

    @Override public boolean onStartJob(JobParameters parameters) {
        if (owner != null || !Scheduler.isInteractive(this)) return false;
        List<CenterRefresh.Request> manual = CenterRefresh.pending(this, true);
        List<WeatherStore.Selection> due;
        if (manual.isEmpty()) {
            if (parameters != null && parameters.getExtras().getBoolean("centerOnly", false)) return false;
            long now = System.currentTimeMillis() / 1000;
            due = WeatherScheduler.due(this, now);
            int[] widgets = parameters == null ? null : parameters.getExtras().getIntArray("modeWidgets");
            if (widgets != null) due.removeIf(selection -> java.util.Arrays.stream(widgets).noneMatch(id -> id == selection.id)
                    || !WeatherScheduler.modeDue(this, selection.id, now));
        } else {
            due = new java.util.ArrayList<>();
            for (CenterRefresh.Request request : manual) {
                WeatherStore.Selection selection = WeatherStore.selection(this, request.id);
                if (selection != null && WeatherStore.matches(this, selection)) due.add(selection);
                else request.finish(this);
            }
        }
        if (due.isEmpty()) return false;
        Run run = new Run(parameters, due, manual);
        if (!ACTIVE.compareAndSet(null, run)) return false;
        owner = run;
        run.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        Run run = owner;
        if (run != null) run.stop(true);
        return false; // Persisted per-location attempts survive process/job restarts.
    }
    @Override public void onDestroy() {
        Run run = owner;
        if (run != null) run.stop(true);
        super.onDestroy();
    }
    private final class Run {
        final JobParameters parameters;
        final List<WeatherStore.Selection> selections;
        final Map<Integer, CenterRefresh.Request> manual = new java.util.HashMap<>();
        final Set<CenterRefresh.Request> attemptedManual = ConcurrentHashMap.newKeySet();
        final Map<Integer, Request> requests = new ConcurrentHashMap<>();
        final AtomicInteger next = new AtomicInteger(), remaining = new AtomicInteger();
        final ExecutorService workers = new ThreadPoolExecutor(WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORKERS));
        final long deadline = System.nanoTime() + BUDGET_MILLIS * 1_000_000L;
        final SharedPreferences.OnSharedPreferenceChangeListener changes = (preferences, key) -> {
            reconcile(WeatherJob.this);
        };
        final Runnable timeout = () -> stop(false);
        volatile boolean cancelled, androidStopped;
        boolean completed;
        Run(JobParameters parameters, List<WeatherStore.Selection> selections, List<CenterRefresh.Request> manual) {
            this.parameters = parameters; this.selections = selections;
            for (CenterRefresh.Request request : manual) this.manual.put(request.id, request);
        }
        void start() {
            Store.prefs(WeatherJob.this).registerOnSharedPreferenceChangeListener(changes);
            WeatherStore.prefs(WeatherJob.this).registerOnSharedPreferenceChangeListener(changes);
            main.postDelayed(timeout, BUDGET_MILLIS);
            int count = Math.min(WORKERS, selections.size());
            remaining.set(count);
            for (int i = 0; i < count; i++) workers.execute(() -> {
                try { work(); }
                finally { if (remaining.decrementAndGet() == 0) main.post(() -> finish(false)); }
            });
        }
        void work() {
            while (!cancelled && !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                int index = next.getAndIncrement();
                if (index >= selections.size()) return;
                WeatherStore.Selection selection = selections.get(index);
                Request request = new Request(selection, manual.get(selection.id));
                synchronized (this) {
                    if (cancelled) return;
                    requests.put(selection.id, request);
                }
                boolean attempted = false;
                try {
                    if (request.manual == null ? !WeatherStore.reserve(WeatherJob.this, selection, System.currentTimeMillis() / 1000)
                            : !WeatherStore.reserveManual(WeatherJob.this, selection, request.manual, System.currentTimeMillis() / 1000)) continue;
                    attempted = true;
                    if (request.manual != null) attemptedManual.add(request.manual);
                    WeatherScheduler.advance(WeatherJob.this, selection.id);
                    boolean extendedDaily = WeatherStore.dailyDue(WeatherJob.this, selection, System.currentTimeMillis() / 1000);
                    WeatherData.Sample sample = request.client.fetch(selection.place, extendedDaily);
                    synchronized (this) {
                        if (cancelled || request.cancelled || !request.matches(WeatherJob.this) || System.nanoTime() >= deadline) continue;
                        if (WeatherStore.publish(WeatherJob.this, selection, sample, extendedDaily)) main.post(() -> {
                            if (!cancelled && !request.cancelled && request.matches(WeatherJob.this)
                                    && Scheduler.isInteractive(WeatherJob.this)) {
                                if (parameters != null && parameters.getExtras().containsKey("modeWidgets")) BlockzeitWidget.renderCachedAll(WeatherJob.this);
                                else BlockzeitWidget.renderAll(WeatherJob.this);
                            }
                        });
                    }
                } catch (IOException | RuntimeException ignored) { /* Preserve this widget's last valid sample. */ }
                finally {
                    requests.remove(selection.id, request); request.client.cancel();
                    if (attempted) WeatherStore.finishAttempt(WeatherJob.this, selection);
                }
            }
        }
        void cancelWidget(int id) {
            Request request = requests.get(id);
            if (request != null) request.cancel();
        }
        void reconcile(Context context) {
            for (Request request : requests.values()) {
                if (!request.matches(context)) request.cancel();
            }
        }
        void stop(boolean stoppedByAndroid) {
            synchronized (this) {
                androidStopped |= stoppedByAndroid;
                cancelled = true;
                for (Request request : requests.values()) request.cancel();
            }
            workers.shutdown(); // Drain all wrappers before releasing global admission.
            // Each disconnected worker reaches its finally and posts finish after the last wrapper.
            if (remaining.get() == 0) main.post(() -> finish(androidStopped));
        }
        void finish(boolean androidStopped) {
            if (completed) return;
            completed = true;
            main.removeCallbacks(timeout);
            Store.prefs(WeatherJob.this).unregisterOnSharedPreferenceChangeListener(changes);
            WeatherStore.prefs(WeatherJob.this).unregisterOnSharedPreferenceChangeListener(changes);
            workers.shutdownNow();
            ACTIVE.compareAndSet(this, null);
            if (owner == this) owner = null;
            if (!androidStopped && !this.androidStopped) jobFinished(parameters, false);
            // A budget/cancellation can leave selected targets unattempted. Keep their one-shot
            // tokens for the next admitted run; finish only the exact tokens that reserved I/O.
            for (CenterRefresh.Request request : attemptedManual) request.finish(WeatherJob.this);
            WeatherScheduler.enqueueManual(WeatherJob.this, true);
            WeatherScheduler.enqueuePendingModes(WeatherJob.this);
        }
    }
    private static final class Request {
        final WeatherStore.Selection selection;
        final CenterRefresh.Request manual;
        final WeatherClient client = new WeatherClient();
        volatile boolean cancelled;
        Request(WeatherStore.Selection selection, CenterRefresh.Request manual) { this.selection = selection; this.manual = manual; }
        boolean matches(Context context) { return WeatherStore.matchesPlace(context, selection) && (manual == null || manual.matches(context)); }
        void cancel() { cancelled = true; client.cancel(); }
    }
}
