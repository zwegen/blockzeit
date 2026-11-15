package de.blockzeit;

import de.blockzeit.R;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import de.blockzeit.core.Block;
import de.blockzeit.core.DayMath;
import de.blockzeit.core.History;
import de.blockzeit.core.Hashrate;
import de.blockzeit.core.MarketData;
import de.blockzeit.core.Pool;
import de.blockzeit.core.RetryAfter;
import de.blockzeit.core.WidgetMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@android.annotation.TargetApi(31)
final class BlockClient {
    // Public mainnet Esplora API. No keys, accounts, identifiers or wallet information.
    static final String BASE = "https://mempool.space/api";
    private static final String HASHRATE_PATH = "/v1/mining/hashrate/1m";
    private static final String PRICE_PATH = "/v1/prices", FEES_PATH = "/v1/fees/recommended";
    private static final int MAX_RESPONSE = 128 * 1024;
    static final class RateLimited extends IOException {
        final long until;
        RateLimited(long until) { super("Public service rate limit"); this.until = until; }
    }
    // A display-off skip is not a service/network failure.
    static final class DisplayOff extends IOException { }
    private final Context context;
    private final long deadline = android.os.SystemClock.elapsedRealtime() + 110_000;
    private final AtomicBoolean cancelled;
    private final RefreshCycle cycle;
    private final CenterRefresh.Request manual;
    private final SupplementScheduler.Metadata metadata;
    private int requests;
    private boolean marketReserved;
    private Pool pagePool;
    private volatile HttpURLConnection active;
    BlockClient(Context context, AtomicBoolean cancelled, RefreshCycle cycle) {
        this(context, cancelled, cycle, null);
    }
    BlockClient(Context context, AtomicBoolean cancelled, RefreshCycle cycle, CenterRefresh.Request manual) {
        this(context, cancelled, cycle, manual, null);
    }
    BlockClient(Context context, AtomicBoolean cancelled, RefreshCycle cycle, CenterRefresh.Request manual,
                SupplementScheduler.Metadata metadata) {
        this.metadata = metadata;
        this.manual = manual;
        this.context = context.getApplicationContext();
        this.cancelled = cancelled;
        this.cycle = cycle;
    }
    void cancel() { cancelled.set(true); HttpURLConnection c = active; if (c != null) c.disconnect(); }
    private void check() throws IOException {
        if (!Scheduler.isInteractive(context) || BlockzeitWidget.ids(context).length == 0) throw new DisplayOff();
        if (manual != null && !manual.matches(context)) throw new DisplayOff();
        if (metadata != null && !metadata.matches(context)) throw new DisplayOff();
        if (cancelled.get() || !cycle.active() || Thread.currentThread().isInterrupted()
                || android.os.SystemClock.elapsedRealtime() >= deadline) throw new IOException("Refresh interrupted/limited");
    }
    private boolean publishable() {
        return !cancelled.get() && (manual == null || manual.matches(context))
                && (metadata == null || metadata.matches(context));
    }
    private void checkPath(String path) throws IOException {
        check();
        if ((PRICE_PATH.equals(path) || FEES_PATH.equals(path))
                && !(marketReserved ? Store.anyPriceEnabled(context) : Store.anySelected(context, WidgetMode.MARKET))) throw new DisplayOff();
        if (HASHRATE_PATH.equals(path) || path.startsWith("/v1/block/")) {
            long now = System.currentTimeMillis() / 1000;
            Store.Snapshot snapshot = Store.load(context);
            if (!Store.anySelected(context, WidgetMode.BLOCK)
                    || !snapshot.fresh(now, DayMath.at(now, ZoneId.systemDefault()))
                    || path.startsWith("/v1/block/") && !path.equals("/v1/block/" + snapshot.blocks.get(0).hash))
                throw new DisplayOff();
        }
    }
    private String get(String path) throws IOException {
        checkPath(path);
        if (++requests > 66) throw new IOException("Request limit");
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        active = connection;
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setInstanceFollowRedirects(false);
        if (manual != null) connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json, text/plain");
        connection.setRequestProperty("User-Agent", "Blockzeit/0.1");
        try {
            // Recheck at the actual I/O boundary, including between paginated requests.
            checkPath(path);
            // Reserve only at the real I/O boundary, not on a toggle, queued job or skipped refresh.
            if ((PRICE_PATH.equals(path) || FEES_PATH.equals(path)) && !beginMarketAttempt(path)) return null;
            if (HASHRATE_PATH.equals(path) && !beginHashrateAttempt()) return null;
            if (path.startsWith("/v1/block/") && !beginPoolAttempt(path)) return null;
            if (Store.prefs(context).getLong("retryAfter", 0) > System.currentTimeMillis() / 1000)
                throw new IOException("Service cooldown");
            checkPath(path);
            int status = connection.getResponseCode();
            String retryAfter = connection.getHeaderField("Retry-After");
            if (status == 429 || (status == 503 && retryAfter != null)) {
                throw new RateLimited(RetryAfter.until(retryAfter, System.currentTimeMillis() / 1000));
            }
            if (status != 200) throw new IOException("Public service unavailable");
            if (connection.getContentLengthLong() > MAX_RESPONSE) throw new IOException("Response limit");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkPath(path);
                    if (out.size() + read > MAX_RESPONSE) throw new IOException("Response limit");
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name()).trim();
            }
        } finally { connection.disconnect(); active = null; }
    }
    private List<Block> page(String path) throws IOException, JSONException {
        JSONArray json = new JSONArray(get(path));
        if (json.length() < 1 || json.length() > 15) throw new IOException("Invalid block page");
        List<Block> result = new ArrayList<>();
        for (int i = 0; i < json.length(); i++) {
            JSONObject row = json.getJSONObject(i);
            History.append(result, Store.fromJson(row));
            // The existing /api/blocks path is Esplora, not /api/v1/blocks. Reuse extras if supplied.
            if (i == 0 && "/blocks".equals(path)) pagePool = Store.poolFromJson(row, result.get(0));
        }
        return result;
    }
    void refresh(Context context) throws IOException, JSONException {
        long started = System.currentTimeMillis() / 1000;
        if (Store.prefs(context).getLong("retryAfter", 0) > started) throw new IOException("Service cooldown");
        DayMath.Day day = DayMath.at(started, ZoneId.systemDefault());
        Store.Snapshot old = Store.load(context);
        String currentTip = get("/blocks/tip/hash");
        if (!Block.isHash(currentTip)) throw new IOException("Invalid block tip");
        // Validated unchanged ancestry needs no block-page or optional metadata request. Age alone
        // does not invalidate an intact same-day chain: this successful tip check renews freshness.
        if (!old.blocks.isEmpty() && currentTip.equals(old.blocks.get(0).hash)
                && old.checked <= started && day.key.equals(old.dayKey)
                && History.hasBoundary(old.blocks, day.start)) {
            checkTime(started, day);
            check();
            cycle.<IOException, JSONException>commit(() -> { check(); Store.save(context, old.blocks, started, day); }, this::publishable); // Same atomic validated snapshot replacement.
            return;
        }
        Map<Integer, Integer> positions = new HashMap<>();
        for (int i = 0; i < old.blocks.size(); i++) positions.put(old.blocks.get(i).height, i);
        List<Block> result = new ArrayList<>();
        List<Block> incoming = page("/blocks");
        Block tip = incoming.get(0);
        if (!tip.hash.equals(currentTip)) {
            // A page may lag the tip endpoint or race a new block/reorg. Confirm once; being a
            // canonical ancestor alone must never refresh a page known not to be the latest tip.
            String confirmedTip = get("/blocks/tip/hash");
            if (!Block.isHash(confirmedTip) || !tip.hash.equals(confirmedTip))
                throw new IOException("Inconsistent block tip");
        }
        boolean boundary = false;
        while (!boundary) {
            for (Block block : incoming) {
                check();
                History.append(result, block);
                Integer pos = positions.get(block.height);
                if (pos != null && old.blocks.get(pos).hash.equals(block.hash)) {
                    // Same hash anchors the cached ancestry; orphaned suffixes never get counted.
                    for (int j = pos + 1; j < old.blocks.size(); j++) {
                        if (History.hasBoundary(result, day.start)) break;
                        History.append(result, old.blocks.get(j));
                    }
                }
                if (History.hasBoundary(result, day.start)) { boundary = true; break; }
                // Cached ancestry may have advanced us beyond this response page.
                if (result.get(result.size() - 1).height < block.height) break;
            }
            if (!boundary) {
                int nextHeight = result.get(result.size() - 1).height - 1;
                if (nextHeight < 0 || result.size() >= History.MAX_HEADERS) throw new IOException("Incomplete day history");
                incoming = page("/blocks/" + nextHeight);
            }
        }
        // Check the pinned tip remains on the canonical chain after pagination (also reorgs mid-fetch).
        if (!tip.hash.equals(get("/block-height/" + tip.height))) throw new IOException("Chain changed during refresh");
        checkTime(started, day);
        check();
        boolean committed = cycle.<IOException, JSONException>commit(() -> {
            check(); Store.save(context, result, started, day);
            try { SupplementScheduler.metadata(context, tip, pagePool, manual, deadline - android.os.SystemClock.elapsedRealtime()); }
            catch (JSONException | IllegalStateException optionalFailure) {
                // Verified headers remain successful even if optional attribution/handoff cannot be persisted.
            }
        }, this::publishable);
        if (committed) SupplementScheduler.enqueuePending(context, false);
    }
    /** Runs for a verified handoff or independently due optional work on a fresh cached tip. */
    void refreshMetadata(Block tip) {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> {
            if (metadata != null && !metadata.matches(context)) cancel();
        };
        Store.prefs(context).registerOnSharedPreferenceChangeListener(changes);
        try { refreshPool(context, tip); refreshHashrate(); }
        finally { Store.prefs(context).unregisterOnSharedPreferenceChangeListener(changes); }
    }
    private static void checkTime(long started, DayMath.Day day) throws IOException {
        long ended = System.currentTimeMillis() / 1000;
        // Reject a clock rollback during I/O instead of committing a future-dated acquisition.
        if (ended < started || !day.key.equals(DayMath.at(ended, ZoneId.systemDefault()).key))
            throw new IOException("Local time changed");
    }
    private boolean validatedNetwork() {
        ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = network == null ? null : network.getNetworkCapabilities(network.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    private boolean beginHashrateAttempt() throws IOException {
        checkPath(HASHRATE_PATH);
        if (!validatedNetwork()) return false;
        AtomicBoolean reserved = new AtomicBoolean();
        cycle.guard(() -> {
            android.content.SharedPreferences prefs = Store.prefs(context);
            long now = System.currentTimeMillis() / 1000;
            checkPath(HASHRATE_PATH);
            String event = Store.hashrateEvent(context, Store.load(context).blocks.get(0));
            if (!Hashrate.due(now, event, prefs.getString("hashrateAttemptEvent", ""), prefs.getLong("retryAfter", 0))) return;
            checkPath(HASHRATE_PATH);
            reserved.set(prefs.edit().putLong("hashrateAttempt", now).putString("hashrateAttemptEvent", event).commit());
        });
        return reserved.get();
    }
    private void refreshHashrate() {
        try {
            String response = get(HASHRATE_PATH);
            if (response == null) return;
            double rate = Hashrate.number(new JSONObject(response).get("currentHashrate"));
            cycle.<IOException, JSONException>commit(() -> { check(); Store.saveHashrate(context, rate, System.currentTimeMillis() / 1000); }, this::publishable);
        } catch (IOException | JSONException | IllegalArgumentException e) {
            // Optional failure preserves verified headers/pool and the last valid numeric rate.
            if (e instanceof RateLimited) recordCooldown((RateLimited) e);
        }
    }
    private boolean beginMarketAttempt(String path) throws IOException {
        checkPath(path);
        if (!validatedNetwork()) return false;
        AtomicBoolean reserved = new AtomicBoolean();
        // All four clients share only this short persisted-reservation guard, never their I/O.
        cycle.guard(() -> {
            android.content.SharedPreferences prefs = Store.prefs(context);
            long now = System.currentTimeMillis() / 1000;
            boolean price = PRICE_PATH.equals(path);
            if (!Store.anySelected(context, WidgetMode.MARKET)) return;
            String key = price ? "priceAttempt" : "feesAttempt";
            boolean forced = manual != null && manual.mode == de.blockzeit.core.WidgetMode.MARKET
                    && (price || FEES_PATH.equals(path));
            if (prefs.getLong("retryAfter", 0) > now
                    || !forced && !MarketData.due(price, now, prefs.getLong(key, 0), prefs.getLong("retryAfter", 0))) return;
            checkPath(path);
            reserved.set(prefs.edit().putLong(key, now).commit());
        });
        marketReserved = reserved.get();
        return marketReserved;
    }
    void refreshFees() { refreshMarketSample(FEES_PATH); }
    void refreshPrice() {
        // Automatic work already admitted to I/O survives center switches and fills the shared cache.
        // Disabled feeds and explicit manual tokens are still rejected by checkPath/check.
        if (Store.anySelected(context, WidgetMode.MARKET)) refreshMarketSample(PRICE_PATH);
    }
    private void refreshMarketSample(String path) {
        try {
            String response = get(path);
            if (response == null) return;
            JSONObject data = new JSONObject(response);
            long checked = System.currentTimeMillis() / 1000;
            cycle.<IOException, JSONException>commit(() -> {
                checkPath(path);
                if (PRICE_PATH.equals(path)) Store.savePrice(context, data, checked);
                else Store.saveFees(context, data.get("hourFee"), data.get("halfHourFee"), data.get("fastestFee"), checked);
            }, this::publishable);
        } catch (IOException | JSONException | IllegalArgumentException e) {
            // Malformed/missing/offline/cancelled samples keep all last-valid records untouched.
            if (e instanceof RateLimited) recordCooldown((RateLimited) e);
        }
    }
    private void recordCooldown(RateLimited e) {
        cycle.guard(() -> {
            android.content.SharedPreferences prefs = Store.prefs(context);
            prefs.edit().putLong("retryAfter", Math.max(e.until, prefs.getLong("retryAfter", 0))).apply();
        });
    }
    void recordBlockFailure(Exception e) {
        cycle.commit(() -> {
            if (cancelled.get() || e instanceof DisplayOff) return;
            if (e instanceof RateLimited) {
                recordCooldown((RateLimited) e);
                Store.prefs(context).edit().putString("error", context.getString(R.string.error_rate_limited)).apply();
            } else if (Scheduler.isInteractive(context)) {
                Store.prefs(context).edit().putString("error", context.getString(R.string.error_unavailable)).apply();
            }
        }, this::publishable);
    }
    private boolean beginPoolAttempt(String path) throws IOException {
        checkPath(path);
        if (!validatedNetwork()) return false;
        AtomicBoolean reserved = new AtomicBoolean();
        cycle.guard(() -> {
            checkPath(path);
            Store.Snapshot snapshot = Store.load(context);
            Block tip = snapshot.blocks.get(0);
            android.content.SharedPreferences prefs = Store.prefs(context);
            long now = System.currentTimeMillis() / 1000;
            if (snapshot.pool != null || !Pool.due(now, tip, prefs.getString("poolAttemptHash", ""),
                    prefs.getInt("poolAttemptHeight", -1), prefs.getLong("poolAttempt", 0), prefs.getLong("retryAfter", 0))) return;
            reserved.set(prefs.edit().putLong("poolAttempt", now).putString("poolAttemptHash", tip.hash)
                    .putInt("poolAttemptHeight", tip.height).commit());
        });
        return reserved.get();
    }
    private void refreshPool(Context context, Block tip) {
        long now = System.currentTimeMillis() / 1000;
        android.content.SharedPreferences prefs = Store.prefs(context);
        try {
            check();
            if (pagePool != null && pagePool.matches(tip)) {
                cycle.<IOException, JSONException>commit(() -> { check(); Store.savePool(context, tip, pagePool); }, this::publishable);
                return;
            }
            // Successful attribution is immutable for this hash. Missing/failed attempts are throttled per block.
            if (Store.load(context).pool != null || !Pool.due(now, tip,
                    prefs.getString("poolAttemptHash", ""), prefs.getInt("poolAttemptHeight", -1),
                    prefs.getLong("poolAttempt", 0), prefs.getLong("retryAfter", 0))) return;
            // Mempool extended block endpoint, pinned to the verified/displayed hash; at most one optional request.
            String response = get("/v1/block/" + tip.hash);
            if (response == null) return;
            Pool pool = Store.poolFromJson(new JSONObject(response), tip);
            check();
            cycle.<IOException, JSONException>commit(() -> { check(); Store.savePool(context, tip, pool); }, this::publishable);
        } catch (IOException | JSONException | IllegalArgumentException e) {
            // Optional attribution never overwrites verified headers or their freshness/error state.
            // A real service cooldown applies to ALL further requests, even if the display just went off.
            if (e instanceof RateLimited) recordCooldown((RateLimited) e);
        }
    }
}
