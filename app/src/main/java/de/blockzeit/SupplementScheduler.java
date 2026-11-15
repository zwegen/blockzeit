package de.blockzeit;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import de.blockzeit.core.Block;
import de.blockzeit.core.DayMath;
import java.time.ZoneId;
import de.blockzeit.core.MarketData;
import de.blockzeit.core.Pool;
import de.blockzeit.core.WidgetMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Independent bounded optional work. No alarm, clock offset, or retry loop. */
final class SupplementScheduler {
    static final int JOB_ID = 0x535550;
    static final int HISTORY_JOB_ID = 0x485354;
    private static final String METADATA = "supplementMetadata";
    private static Object owner;
    private SupplementScheduler() { }

    static final class Metadata {
        final String token;
        final Block tip;
        final long expiresAt;
        final CenterRefresh.Request manual;
        Metadata(String token, Block tip, CenterRefresh.Request manual, long expiresAt) {
            this.token = token; this.tip = tip; this.manual = manual; this.expiresAt = expiresAt;
        }
        boolean matches(Context c) {
            if (System.currentTimeMillis() >= expiresAt || !Store.anySelected(c, WidgetMode.BLOCK)
                    || manual != null && !manual.matches(c)) return false;
            Store.Snapshot snapshot = Store.load(c);
            long now = System.currentTimeMillis() / 1000;
            if (!snapshot.fresh(now, DayMath.at(now, ZoneId.systemDefault())) || !tip.hash.equals(snapshot.blocks.get(0).hash)
                    || tip.height != snapshot.blocks.get(0).height) return false;
            synchronized (ProviderState.LOCK) {
                for (Metadata next : read(c)) if (token.equals(next.token)) return true;
            }
            return false;
        }
    }
    private static List<Metadata> read(Context c) {
        List<Metadata> result = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(Store.prefs(c).getString(METADATA, "[]"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                Block block = new Block(row.getInt("height"), row.getLong("timestamp"), row.getString("hash"), row.getString("previous"));
                CenterRefresh.Request manual = row.has("manualToken") ? new CenterRefresh.Request(row.getInt("id"),
                        WidgetMode.BLOCK, row.getString("manualToken")) : null;
                result.add(new Metadata(row.getString("token"), block, manual, row.getLong("expiresAt")));
            }
        } catch (JSONException | IllegalArgumentException invalid) { result.clear(); }
        return result;
    }
    private static String encode(List<Metadata> rows) throws JSONException {
        JSONArray encoded = new JSONArray();
        for (Metadata row : rows) {
            JSONObject value = new JSONObject().put("token", row.token).put("expiresAt", row.expiresAt).put("height", row.tip.height)
                    .put("timestamp", row.tip.timestamp).put("hash", row.tip.hash).put("previous", row.tip.previous);
            if (row.manual != null) value.put("id", row.manual.id).put("manualToken", row.manual.token);
            encoded.put(value);
        }
        return encoded.toString();
    }
    /** Called inside the verified snapshot's short transaction, never starts HTTP. */
    static void metadata(Context c, Block tip, Pool supplied, CenterRefresh.Request manual, long remainingMillis) throws JSONException {
        if (supplied != null && supplied.matches(tip)) Store.savePool(c, tip, supplied);
        String previous = Store.prefs(c).getString(METADATA, "[]");
        String previousClaim = manual == null ? null : Store.prefs(c).getString(CenterRefresh.CLAIM + manual.id, null);
        List<Metadata> pending = read(c);
        // One newest automatic target; explicit per-widget tokens keep their own completion owner.
        pending.removeIf(row -> row.manual == null || manual != null && row.manual.token.equals(manual.token));
        pending.add(new Metadata(UUID.randomUUID().toString(), tip, manual, System.currentTimeMillis() + Math.max(0, remainingMillis)));
        android.content.SharedPreferences.Editor edit = Store.prefs(c).edit().putString(METADATA, encode(pending));
        if (manual != null) edit.putString(CenterRefresh.CLAIM + manual.id, manual.token);
        if (!edit.commit()) {
            // A failed disk commit can already be visible in memory. Restore only this transaction,
            // preserving previous queued work and leaving the core request unclaimed.
            android.content.SharedPreferences.Editor rollback = Store.prefs(c).edit().putString(METADATA, previous);
            if (manual != null) {
                if (previousClaim == null) rollback.remove(CenterRefresh.CLAIM + manual.id);
                else rollback.putString(CenterRefresh.CLAIM + manual.id, previousClaim);
            }
            rollback.apply();
        }
    }
    static List<Metadata> pendingMetadata(Context c) {
        synchronized (ProviderState.LOCK) { return read(c); }
    }
    static void completeMetadata(Context c, List<Metadata> completed, Set<String> processed) {
        synchronized (ProviderState.LOCK) {
            List<Metadata> pending = read(c);
            pending.removeIf(row -> processed.contains(row.token));
            try { Store.prefs(c).edit().putString(METADATA, encode(pending)).apply(); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        for (Metadata row : completed) if (processed.contains(row.token) && row.manual != null) row.manual.finish(c);
    }
    /** Minute/wake dispatch also checks optional due work on an unchanged, verified tip.
     * Pool keeps its existing retry policy; hashrate is admitted only for an unattempted entry/tip event. */
    private static void prepareMetadata(Context c) {
        if (!Store.anySelected(c, WidgetMode.BLOCK)) return;
        synchronized (ProviderState.LOCK) {
            Store.Snapshot snapshot = Store.load(c);
            long now = System.currentTimeMillis() / 1000;
            if (!snapshot.fresh(now, DayMath.at(now, ZoneId.systemDefault()))) return;
            android.content.SharedPreferences prefs = Store.prefs(c);
            Block tip = snapshot.blocks.get(0);
            boolean pool = snapshot.pool == null && Pool.due(now, tip, prefs.getString("poolAttemptHash", ""),
                    prefs.getInt("poolAttemptHeight", -1), prefs.getLong("poolAttempt", 0), prefs.getLong("retryAfter", 0));
            boolean rate = Store.hashrateDue(c, tip, now);
            if (!pool && !rate) return;
            for (Metadata row : read(c)) if (row.matches(c)) return;
            try { metadata(c, tip, null, null, 110_000); }
            catch (JSONException | IllegalStateException optionalFailure) { /* Try on a later normal dispatch. */ }
        }
    }
    static boolean marketDue(Context c) {
        if (!Store.anySelected(c, WidgetMode.MARKET)) return false;
        android.content.SharedPreferences prefs = Store.prefs(c);
        long now = System.currentTimeMillis() / 1000, retry = prefs.getLong("retryAfter", 0);
        return MarketData.due(false, now, prefs.getLong("feesAttempt", 0), retry)
                || Scheduler.priceDue(c);
    }
    static synchronized boolean begin(Context c, Object nextOwner, CenterRefresh.Request manual, boolean market, List<Metadata> metadata) {
        if (owner != null || BlockzeitWidget.ids(c).length == 0 || !Scheduler.isInteractive(c)) return false;
        boolean cooldown;
        synchronized (ProviderState.LOCK) {
            cooldown = Store.prefs(c).getLong("retryAfter", 0) > System.currentTimeMillis() / 1000;
            if (!cooldown && (manual != null ? !manual.matches(c) : !market && metadata.isEmpty())) return false;
        }
        if (cooldown) { if (manual != null) manual.finish(c); return false; }
        owner = nextOwner;
        return true;
    }
    static synchronized void end(Object ending) { if (owner == ending) owner = null; }
    static synchronized void enqueue(Context c) { dispatch(c, true, false, false); }
    static synchronized void enqueueManual(Context c) { dispatch(c, false, true, false); }
    static synchronized void enqueuePending(Context c, boolean replaceFinished) { dispatch(c, false, false, replaceFinished); }
    static synchronized void enqueueAfterSwitch(Context c) { dispatch(c, true, false, true); }
    static synchronized void enqueueMode(Context c, int id) {
        if (owner != null || !BlockzeitWidget.active(c, id) || !Scheduler.isInteractive(c)
                || Store.prefs(c).getLong("retryAfter", 0) > System.currentTimeMillis() / 1000) return;
        int mode = Store.mode(c, id);
        long now = System.currentTimeMillis() / 1000;
        boolean market = mode == WidgetMode.MARKET && (Store.marketDue(c, id, true, now) || Store.marketDue(c, id, false, now));
        if (mode == WidgetMode.BLOCK) prepareMetadata(c);
        if (!market && (mode != WidgetMode.BLOCK || pendingMetadata(c).isEmpty())) return;
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        JobInfo pending = jobs.getPendingJob(JOB_ID);
        // Retain native wider work. Multiple destination widgets share one queued acquisition.
        if (pending != null && !pending.getExtras().containsKey("modeWidgets")) return;
        java.util.Set<Integer> widgets = new java.util.LinkedHashSet<>();
        if (pending != null) for (int widget : pending.getExtras().getIntArray("modeWidgets")) widgets.add(widget);
        widgets.add(id);
        PersistableBundle extras = new PersistableBundle();
        extras.putIntArray("modeWidgets", widgets.stream().mapToInt(Integer::intValue).toArray());
        extras.putBoolean("market", market || pending != null && pending.getExtras().getBoolean("market", false));
        extras.putString("generation", UUID.randomUUID().toString());
        jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, SupplementJob.class))
                .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }
    private static void dispatch(Context c, boolean automatic, boolean manualOnly, boolean replaceFinished) {
        if (owner != null || BlockzeitWidget.ids(c).length == 0 || !Scheduler.isInteractive(c)) return;
        if (Store.prefs(c).getLong("retryAfter", 0) > System.currentTimeMillis() / 1000) return;
        boolean manual = !CenterRefresh.pending(c, WidgetMode.MARKET).isEmpty();
        boolean market = automatic && marketDue(c);
        if (automatic) prepareMetadata(c);
        boolean metadata = !manualOnly && !pendingMetadata(c).isEmpty();
        if (!manual && !market && !metadata) return;
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        JobInfo pending = jobs.getPendingJob(JOB_ID);
        if (!replaceFinished && pending != null) {
            // Upgrade queued metadata/stale center-only work when automatic market data is due.
            // Keep wider work and live explicit center-only requests; owner is guarded above.
            boolean pendingManual = pending.getExtras().getBoolean("centerOnly", false);
            if (!market || pending.getExtras().getBoolean("market", false) || manual && pendingManual) return;
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("market", market);
        extras.putBoolean("centerOnly", manualOnly);
        extras.putString("generation", UUID.randomUUID().toString());
        jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, SupplementJob.class))
                .setExtras(extras).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }
    /** Independent history slot in this service: no selected-mode/screen gate or live-feed lanes. */
    static synchronized void enqueueHistory(Context c) { enqueueHistory(c,false); }
    static synchronized void enqueueHistory(Context c,boolean finished) {
        JobScheduler jobs=c.getSystemService(JobScheduler.class);
        if(BlockzeitWidget.ids(c).length==0) { jobs.cancel(HISTORY_JOB_ID);return; }
        long now=System.currentTimeMillis()/1000;
        long next=Math.max(PriceHistoryCache.next(c,now),Store.prefs(c).getLong("retryAfter",0));
        long planned=Math.max(now,next),delay=planned-now;
        long latency=delay>Long.MAX_VALUE/1000?Long.MAX_VALUE:delay*1000;
        JobInfo pending=jobs.getPendingJob(HISTORY_JOB_ID);
        if(!finished&&pending!=null) {
            long previous=pending.getExtras().getLong("historyAt",0);
            // Compare absolute targets, not the original relative latency: repeated ensure calls
            // must retain an earlier/already due (possibly running) generation. Legacy jobs have no target.
            if(previous>0?previous<=planned:pending.getMinLatencyMillis()<=latency)return;
        }
        PersistableBundle extras=new PersistableBundle();
        extras.putBoolean("history",true);extras.putString("generation",UUID.randomUUID().toString());extras.putLong("historyAt",planned);
        // One publication-boundary job, not a periodic poll. Android/Doze can deliver it later.
        jobs.schedule(new JobInfo.Builder(HISTORY_JOB_ID,new ComponentName(c,SupplementJob.class))
                .setExtras(extras).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(latency).build());
    }
    static void cancelHistory(Context c) { c.getSystemService(JobScheduler.class).cancel(HISTORY_JOB_ID); }
    static void cancel(Context c) {
        synchronized (ProviderState.LOCK) { Store.prefs(c).edit().remove(METADATA).apply(); }
        c.getSystemService(JobScheduler.class).cancel(JOB_ID);
    }
}
