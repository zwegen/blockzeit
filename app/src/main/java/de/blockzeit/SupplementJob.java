package de.blockzeit;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import de.blockzeit.core.WidgetMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional work never owns the next block minute. Three workers leave one reserved for core. */
@android.annotation.TargetApi(31)
public final class SupplementJob extends JobService {
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private Run run;
    private HistoryRun history;
    private static final class HistoryRun {
        final JobParameters params;
        final PriceHistoryData request;
        boolean stopped;
        HistoryRun(JobParameters params,PriceHistoryData request) { this.params=params;this.request=request; }
    }
    private static final class Run {
        final JobParameters params;
        final Set<String> processed = new HashSet<>();
        RefreshCycle cycle;
        boolean stopped;
        final Set<Integer> changedWidgets = java.util.concurrent.ConcurrentHashMap.newKeySet();
        android.content.SharedPreferences.OnSharedPreferenceChangeListener changes;
        Run(JobParameters params) { this.params = params; }
    }
    private boolean startHistory(JobParameters params) {
        if(history!=null||BlockzeitWidget.ids(this).length==0)return false;
        HistoryRun current=new HistoryRun(params,new PriceHistoryData(getApplicationContext(),"USD"));
        history=current;
        worker.execute(() -> {
            try { current.request.load(); } catch(Exception unavailable) { /* Durable last-good survives; bounded cache backoff owns retry. */ }
            finally { main.post(() -> {
                if(history!=current)return;
                history=null;
                if(!current.stopped)jobFinished(params,false);
                SupplementScheduler.enqueueHistory(SupplementJob.this,true);
            }); }
        });return true;
    }
    @Override public boolean onStartJob(JobParameters params) {
        if(params!=null&&params.getExtras().getBoolean("history",false))return startHistory(params);
        if (run != null || BlockzeitWidget.ids(this).length == 0 || !Scheduler.isInteractive(this)) return false;
        List<CenterRefresh.Request> requests = CenterRefresh.pending(this, WidgetMode.MARKET);
        CenterRefresh.Request manual = requests.isEmpty() ? null : requests.get(0);
        boolean centerOnly = params != null && params.getExtras().getBoolean("centerOnly", false);
        List<SupplementScheduler.Metadata> metadata = manual == null ? SupplementScheduler.pendingMetadata(this) : List.of();
        if (manual == null && centerOnly && metadata.isEmpty()) return false;
        boolean market = manual == null && !centerOnly && (params == null || params.getExtras().getBoolean("market", false))
                && SupplementScheduler.marketDue(this);
        int[] modeWidgets = params == null ? null : params.getExtras().getIntArray("modeWidgets");
        boolean priceDue = market, feesDue = market;
        if (manual == null && modeWidgets != null) {
            priceDue = false; feesDue = false;
            long now = System.currentTimeMillis() / 1000;
            for (int id : modeWidgets) {
                priceDue |= Store.marketDue(this, id, true, now);
                feesDue |= Store.marketDue(this, id, false, now);
            }
            market = priceDue || feesDue;
        }
        Run current = new Run(params);
        if (!SupplementScheduler.begin(this, current, manual, market, metadata)) return false;
        current.cycle = new RefreshCycle(worker, command -> main.post(command), new RefreshCycle.Listener() {
            @Override public void publish() {
                if (Scheduler.isInteractive(SupplementJob.this) && (manual == null || manual.matches(SupplementJob.this)))
                    if (modeWidgets != null) BlockzeitWidget.renderCachedAll(SupplementJob.this);
                    else BlockzeitWidget.renderAll(SupplementJob.this);
            }
            @Override public void finished(RefreshCycle owner, boolean cancelled) {
                if (run != current || current.cycle != owner) return;
                run = null;
                if (current.changes != null) Store.prefs(SupplementJob.this).unregisterOnSharedPreferenceChangeListener(current.changes);
                SupplementScheduler.completeMetadata(SupplementJob.this, metadata, current.processed);
                if (manual != null) manual.finish(SupplementJob.this);
                SupplementScheduler.end(current);
                if (!current.stopped) {
                    jobFinished(params, false);
                    for (int id : current.changedWidgets) SupplementScheduler.enqueueMode(SupplementJob.this, id);
                    SupplementScheduler.enqueuePending(SupplementJob.this, current.changedWidgets.isEmpty());
                }
            }
        });
        run = current;
        current.changes = (preferences, key) -> {
            // A selection change while this owner drains must not lose newly due optional demand.
            // Stable failures only drain queued handoffs; they do not create an immediate retry loop.
            if (key != null && (key.startsWith("mode.") || key.startsWith("blockEnabled.") || key.startsWith("priceEnabled."))) {
                try { current.changedWidgets.add(Integer.parseInt(key.substring(key.indexOf('.') + 1))); }
                catch (NumberFormatException invalidWidget) { /* Ignore unrelated preference keys. */ }
            }
            if (manual != null && !manual.matches(this)) main.post(current.cycle::cancel);
        };
        Store.prefs(this).registerOnSharedPreferenceChangeListener(current.changes);
        List<RefreshCycle.Lane> lanes = new ArrayList<>();
        if (manual != null || market) {
            BlockClient fees = client(current, manual), price = client(current, manual);
            if (manual != null || feesDue) lanes.add(lane(fees, fees::refreshFees));
            if (manual != null || priceDue) lanes.add(lane(price, price::refreshPrice));
        }
        if (!metadata.isEmpty()) {
            List<BlockClient> clients = new ArrayList<>();
            for (SupplementScheduler.Metadata target : metadata) clients.add(new BlockClient(getApplicationContext(),
                    new AtomicBoolean(false), current.cycle, target.manual, target));
            lanes.add(new RefreshCycle.Lane() {
                @Override public void run() {
                    for (int i = 0; i < metadata.size() && current.cycle.active(); i++) {
                        SupplementScheduler.Metadata target = metadata.get(i);
                        clients.get(i).refreshMetadata(target.tip);
                        current.processed.add(target.token);
                    }
                }
                @Override public void cancel() { for (BlockClient client : clients) client.cancel(); }
            });
        }
        current.cycle.start(lanes);
        return true;
    }
    private BlockClient client(Run owner, CenterRefresh.Request request) {
        return new BlockClient(getApplicationContext(), new AtomicBoolean(false), owner.cycle, request);
    }
    private static RefreshCycle.Lane lane(BlockClient client, Runnable work) {
        return new RefreshCycle.Lane() {
            @Override public void run() { work.run(); }
            @Override public void cancel() { client.cancel(); }
        };
    }
    @Override public boolean onStopJob(JobParameters params) {
        if(history!=null&&RefreshJob.sameExecution(history.params,params)) { history.stopped=true;history.request.cancel(); }
        if (run != null && RefreshJob.sameExecution(run.params, params)) { run.stopped = true; run.cycle.cancel(); }
        return false;
    }
    @Override public void onDestroy() {
        if(history!=null) { history.stopped=true;history.request.cancel(); }
        if (run != null) { run.stopped = true; run.cycle.cancel(); }
        worker.shutdown();
        super.onDestroy();
    }
}
