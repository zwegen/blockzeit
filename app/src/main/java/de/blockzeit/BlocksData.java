package de.blockzeit;

import android.content.Context;
import android.os.SystemClock;
import de.blockzeit.core.Block;
import de.blockzeit.core.Pool;
import de.blockzeit.core.RetryAfter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** One bounded foreground-window request. Does not update widget caches or schedule refreshes. */
final class BlocksData {
    static final String PATH = "/v1/blocks";
    static final int MAX_RESPONSE = 128 * 1024;
    static final String UNKNOWN_POOL = "Unknown";
    static final class Entry {
        final Block block;
        final String pool;
        final Block predecessor;
        Entry(Block block, String pool, Block predecessor) {
            this.block = block; this.pool = pool; this.predecessor = predecessor;
        }
        String durationText() {
            return predecessor == null ? "—" : formatDuration(block.timestamp, predecessor.timestamp);
        }
    }
    /** Validate existing block/pool conventions, then select the newest six distinct heights/hashes. */
    static List<Entry> parse(String text) throws IOException, JSONException {
        if (text == null || text.length() > MAX_RESPONSE) throw new IOException("Response limit");
        JSONArray rows = new JSONArray(text);
        List<Entry> valid = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            try {
                Block block = Store.fromJson(row);
                Pool pool = Store.poolFromJson(row, block);
                valid.add(new Entry(block, pool == null ? UNKNOWN_POOL : pool.name, null));
            } catch (JSONException | IllegalArgumentException invalid) { /* Skip only invalid rows. */ }
        }
        if (valid.isEmpty() && rows.length() != 0) throw new IOException("Invalid block page");
        valid.sort(Comparator.comparingInt((Entry entry) -> entry.block.height).reversed());
        List<Entry> distinct = new ArrayList<>();
        Map<Integer, Block> byHeight = new HashMap<>();
        Set<Integer> heights = new HashSet<>(); Set<String> hashes = new HashSet<>();
        for (Entry entry : valid) {
            if (heights.contains(entry.block.height) || hashes.contains(entry.block.hash)) continue;
            heights.add(entry.block.height); hashes.add(entry.block.hash); distinct.add(entry);
            byHeight.put(entry.block.height, entry.block);
        }
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < Math.min(6, distinct.size()); i++) {
            Entry entry = distinct.get(i);
            result.add(new Entry(entry.block, entry.pool, byHeight.get(entry.block.height - 1)));
        }
        return result;
    }
    /** Adjacent miner timestamps approximate duration, not wall-clock age or measured mining time. */
    static String formatDuration(long timestamp, long previousTimestamp) {
        if (timestamp < 0 || previousTimestamp < 0) return "—";
        try {
            long seconds = Math.subtractExact(timestamp, previousTimestamp);
            return seconds < 0 ? "00:00 min" : String.format(java.util.Locale.ROOT, "%02d:%02d min", seconds / 60, seconds % 60);
        } catch (ArithmeticException overflow) { return "—"; }
    }

    private final Context context;
    private final int widget;
    private final long deadline = SystemClock.elapsedRealtime() + 15_000;
    private volatile boolean cancelled;
    private volatile HttpURLConnection active;
    BlocksData(Context context, int widget) { this.context = context.getApplicationContext(); this.widget = widget; }
    void cancel() {
        cancelled = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }
    private int remaining() throws IOException {
        long left = deadline - SystemClock.elapsedRealtime();
        if (cancelled || Thread.currentThread().isInterrupted() || left <= 0 || !BlocksActivity.currentTarget(context, widget))
            throw new IOException("Request interrupted/limited");
        return (int) Math.min(7000, left);
    }
    List<Entry> load() throws IOException, JSONException {
        remaining();
        if (Store.prefs(context).getLong("retryAfter", 0) > System.currentTimeMillis() / 1000)
            throw new IOException("Service cooldown");
        HttpURLConnection connection = (HttpURLConnection) new URL(BlockClient.BASE + PATH).openConnection();
        active = connection;
        try {
            connection.setConnectTimeout(remaining()); connection.setReadTimeout(remaining());
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json, text/plain");
            connection.setRequestProperty("User-Agent", "Blockzeit/0.1");
            remaining();
            int status = connection.getResponseCode();
            remaining();
            String retryAfter = connection.getHeaderField("Retry-After");
            if (status == 429 || status == 503 && retryAfter != null) {
                throw new BlockClient.RateLimited(RetryAfter.until(retryAfter, System.currentTimeMillis() / 1000));
            }
            if (status != 200) throw new IOException("Public service unavailable");
            if (connection.getContentLengthLong() > MAX_RESPONSE) throw new IOException("Response limit");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                while (true) {
                    connection.setReadTimeout(remaining());
                    int read = input.read(buffer);
                    if (read == -1) break;
                    if (out.size() + read > MAX_RESPONSE) throw new IOException("Response limit");
                    out.write(buffer, 0, read);
                }
                remaining();
                List<Entry> result = parse(out.toString(StandardCharsets.UTF_8.name()).trim());
                remaining();
                return result;
            }
        } finally { active = null; connection.disconnect(); }
    }
}
