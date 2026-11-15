package de.blockzeit;

import de.blockzeit.R;
import android.content.Context;
import android.content.SharedPreferences;
import de.blockzeit.core.Block;
import de.blockzeit.core.WidgetMode;
import de.blockzeit.core.DayMath;
import de.blockzeit.core.History;
import de.blockzeit.core.Hashrate;
import de.blockzeit.core.MarketData;
import de.blockzeit.core.Pool;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

@android.annotation.TargetApi(31)
final class Store {
    static final long FRESH_SECONDS = 15 * 60;
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("blockzeit", Context.MODE_PRIVATE); }
    static int opacity(Context c, int id) { return prefs(c).getInt("opacity." + id, 94); }
    static int elapsedDaysColor(Context c, int id) { return yearColor(c, "elapsedDaysColor.", id, YearColors.ELAPSED); }
    static int primaryTextColor(Context c, int id) { return yearColor(c, "primaryTextColor.", id, RingRenderer.GREEN); }
    static int remainingDaysColor(Context c, int id) { return yearColor(c, "remainingDaysColor.", id, YearColors.REMAINING); }
    private static int yearColor(Context c, String key, int id, int fallback) {
        Object stored = prefs(c).getAll().get(key + id);
        return stored instanceof Integer ? (Integer) stored : fallback;
    }
    static int mode(Context c, int id) {
        // Repair an impossible persisted pair without touching any optional or retired setting.
        if (!prefs(c).getBoolean("clockEnabled." + id, true) && !blockEnabled(c, id))
            prefs(c).edit().putBoolean("clockEnabled." + id, true).apply();
        Object stored = prefs(c).getAll().get("mode." + id);
        int mode = WidgetMode.normalize(stored, clockEnabled(c, id), blockEnabled(c, id), priceEnabled(c, id), weatherEnabled(c, id), calendarEnabled(c, id));
        if (stored instanceof Boolean || stored instanceof Integer && !stored.equals(mode)) setMode(c, id, mode);
        return mode;
    }
    static boolean blockEnabled(Context c, int id) { return prefs(c).getBoolean("blockEnabled." + id, true); }
    static boolean clockEnabled(Context c, int id) {
        return WidgetMode.clockAllowed(prefs(c).getBoolean("clockEnabled." + id, true), blockEnabled(c, id));
    }
    static void setBaseModes(Context c, int id, boolean clock, boolean block) {
        clock = WidgetMode.clockAllowed(clock, block);
        if (clockEnabled(c, id) != clock || blockEnabled(c, id) != block) CenterRefresh.invalidate(c, id);
        int next = WidgetMode.normalize(prefs(c).getAll().get("mode." + id),
                clock, block, priceEnabled(c, id), weatherEnabled(c, id), calendarEnabled(c, id));
        applyMode(c, id, next, prefs(c).edit().putBoolean("clockEnabled." + id, clock).putBoolean("blockEnabled." + id, block));
    }
    static boolean priceEnabled(Context c, int id) { return prefs(c).getBoolean("priceEnabled." + id, true); }
    static boolean weatherEnabled(Context c, int id) { return prefs(c).getBoolean("weatherEnabled." + id, true); }
    static boolean calendarEnabled(Context c, int id) { return prefs(c).getBoolean("calendarEnabled." + id, false); }
    static long calendarId(Context c, int id) { return prefs(c).getLong("calendarId." + id, -1); }
    static void setCalendar(Context c, int id, boolean enabled, long calendar) {
        prefs(c).edit().putBoolean("calendarEnabled." + id, enabled).putLong("calendarId." + id, calendar).apply();
        setMode(c, id, mode(c, id));
    }
    static boolean anyPriceEnabled(Context c) {
        for (int id : BlockzeitWidget.ids(c)) if (priceEnabled(c, id)) return true;
        return false;
    }
    /** Shared optional feeds follow selected centers, not merely enabled choices in the cycle. */
    static boolean anySelected(Context c, int selected) {
        for (int id : BlockzeitWidget.ids(c)) if (mode(c, id) == selected) return true;
        return false;
    }
    static int nextMode(Context c, int id) {
        return WidgetMode.next(mode(c, id), clockEnabled(c, id), blockEnabled(c, id), priceEnabled(c, id), weatherEnabled(c, id), calendarEnabled(c, id));
    }
    static int previousMode(Context c, int id) {
        return WidgetMode.previous(mode(c, id), clockEnabled(c, id), blockEnabled(c, id), priceEnabled(c, id), weatherEnabled(c, id), calendarEnabled(c, id));
    }
    static void setMode(Context c, int id, int mode) {
        if (WidgetMode.read(prefs(c).getAll().get("mode." + id)) != mode) CenterRefresh.invalidate(c, id);
        applyMode(c, id, WidgetMode.normalize(mode, clockEnabled(c, id), blockEnabled(c, id),
                priceEnabled(c, id), weatherEnabled(c, id), calendarEnabled(c, id)), prefs(c).edit());
    }
    static void setModes(Context c, int id, boolean price, boolean weather) {
        boolean wasWeather = weatherEnabled(c, id);
        if (priceEnabled(c, id) != price || wasWeather != weather) CenterRefresh.invalidate(c, id);
        SharedPreferences.Editor edit = prefs(c).edit().putBoolean("priceEnabled." + id, price)
                .putBoolean("weatherEnabled." + id, weather);
        int next = WidgetMode.normalize(prefs(c).getAll().get("mode." + id), clockEnabled(c, id), blockEnabled(c, id), price, weather, calendarEnabled(c, id));
        if (wasWeather != weather) {
            edit.putLong("weatherGeneration." + id, prefs(c).getLong("weatherGeneration." + id, 0) + 1);
            de.blockzeit.core.WeatherData.Sample sample = WeatherStore.sample(c, id);
            edit.putBoolean("weatherRefresh." + id, weather && (sample == null || sample.stale(System.currentTimeMillis() / 1000)));
        }
        applyMode(c, id, next, edit);
        WeatherScheduler.enqueue(c); // Reconcile/cancel active requests immediately, including off-mode enable refresh.
    }
    /** Aggregate BLOCK entry: the first selected consumer opens one durable demand generation.
     * Joining an already selected BLOCK shares its event; reads/restores/wake never rotate it. */
    private static void applyMode(Context c, int id, int next, SharedPreferences.Editor edit) {
        synchronized (ProviderState.LOCK) {
            boolean wasBlock = WidgetMode.read(prefs(c).getAll().get("mode." + id)) == WidgetMode.BLOCK && blockEnabled(c, id);
            if (next == WidgetMode.BLOCK && !wasBlock && BlockzeitWidget.active(c, id)) {
                boolean shared = false;
                for (int other : BlockzeitWidget.ids(c)) if (other != id && blockEnabled(c, other)
                        && WidgetMode.read(prefs(c).getAll().get("mode." + other)) == WidgetMode.BLOCK) { shared = true; break; }
                if (!shared) edit.putString("hashrateEntry", java.util.UUID.randomUUID().toString());
            }
            edit.putInt("mode." + id, next).apply();
        }
    }
    private static void rememberHashrateTip(Context c, Block tip, SharedPreferences.Editor edit) {
        SharedPreferences preferences = prefs(c);
        String previous = preferences.getString("hashrateTipHash", "");
        if (previous.isEmpty()) {
            Snapshot cached = load(c);
            if (!cached.blocks.isEmpty()) previous = cached.blocks.get(0).hash;
        }
        // Track observed transitions, not just the last attempted hash: A -> B -> A is a
        // genuine reorg event even when B was coalesced before optional I/O could begin.
        if (!tip.hash.equals(previous)) edit.putString("hashrateTipGeneration", java.util.UUID.randomUUID().toString());
        edit.putString("hashrateTipHash", tip.hash);
        if (!previous.isEmpty() && preferences.getString("hashrateEntry", "").isEmpty()
                && preferences.getString("hashrateBaseline", "").isEmpty()) {
            edit.putString("hashrateBaseline", previous)
                    .putString("hashrateBaselineGeneration", preferences.getString("hashrateTipGeneration", ""));
        }
    }
    /** Event identity, not a clock interval. Must be evaluated under ProviderState.LOCK.
     * An already-selected legacy installation establishes a baseline without inventing an entry. */
    static String hashrateEvent(Context c, Block tip) {
        SharedPreferences preferences = prefs(c);
        String entry = preferences.getString("hashrateEntry", "");
        String generation = preferences.getString("hashrateTipGeneration", "");
        if (entry.isEmpty() && preferences.getString("hashrateAttemptEvent", "").isEmpty()) {
            String baseline = preferences.getString("hashrateBaseline", "");
            if (baseline.isEmpty()) {
                preferences.edit().putString("hashrateBaseline", tip.hash)
                        .putString("hashrateBaselineGeneration", generation).commit();
                return "";
            }
            if (baseline.equals(tip.hash) && generation.equals(preferences.getString("hashrateBaselineGeneration", ""))) return "";
        }
        return entry + ":" + generation + ":" + tip.hash;
    }
    static boolean hashrateDue(Context c, Block tip, long now) {
        synchronized (ProviderState.LOCK) {
            return Hashrate.due(now, hashrateEvent(c, tip), prefs(c).getString("hashrateAttemptEvent", ""),
                    prefs(c).getLong("retryAfter", 0));
        }
    }
    static boolean blockMode(Context c, int id) { return mode(c, id) == WidgetMode.BLOCK; }
    static boolean remove(Context c, int id) {
        if (!WidgetPreferences.beforeRemove(c, id)) return false; // Retain originals until retention is durable.
        CenterRefresh.invalidate(c, id);
        prefs(c).edit().remove("opacity." + id).remove("mode." + id).remove("currency." + id).remove("ringFrame." + id)
                .remove("elapsedDaysColor." + id).remove("remainingDaysColor." + id).remove("primaryTextColor." + id)
                .remove("clockEnabled." + id).remove("blockEnabled." + id)
                .remove("priceEnabled." + id).remove("weatherEnabled." + id)
                .remove("calendarEnabled." + id).remove("calendarId." + id)
                .remove("weatherGeneration." + id).remove("weatherRefresh." + id).apply();
        return true;
    }
    static RingFrame ringFrame(Context c, int id) {
        Object raw = prefs(c).getAll().get("ringFrame." + id);
        return raw instanceof String ? RingFrame.restore((String) raw, FRESH_SECONDS) : null;
    }
    static void rememberRing(Context c, int id, RingFrame frame) {
        if (frame == null) return; // Never erase last-good display state on an acquisition failure.
        String encoded = frame.encode();
        if (!encoded.equals(prefs(c).getAll().get("ringFrame." + id))) {
            prefs(c).edit().putString("ringFrame." + id, encoded).apply();
        }
    }
    /** Translate only these two app-owned legacy diagnostics at display time; never rewrite saved data. */
    static String errorText(Context c) {
        String raw = prefs(c).getString("error", "");
        if (raw.equals("Öffentlicher Dienst begrenzt Anfragen; Abruf pausiert.")) {
            return c.getString(R.string.error_rate_limited);
        }
        if (raw.equals("Datenabruf derzeit nicht verfügbar")) {
            return c.getString(R.string.error_unavailable);
        }
        return raw;
    }
    static Block fromJson(JSONObject json) throws JSONException {
        return new Block(Block.parseHeight(json.get("height").toString()),
                Block.parseTimestamp(json.get("timestamp").toString()), json.getString("id"),
                json.optString("previousblockhash", ""));
    }
    static JSONObject toJson(Block b) throws JSONException {
        return new JSONObject().put("height", b.height).put("timestamp", b.timestamp)
                .put("id", b.hash).put("previousblockhash", b.previous);
    }
    static String hashrateText(Context c, long now) {
        try {
            JSONObject data = new JSONObject(prefs(c).getString("hashrate", ""));
            return Hashrate.text(Hashrate.number(data.get("value")), data.getLong("checked"), now,
                    java.util.Locale.getDefault());
        } catch (JSONException | IllegalArgumentException e) {
            return "— EH/s";
        }
    }
    static void saveHashrate(Context c, double rate, long checked) throws JSONException {
        JSONObject data = new JSONObject().put("value", Hashrate.number(rate)).put("checked", checked);
        prefs(c).edit().putString("hashrate", data.toString()).apply();
    }
    static String currency(Context c, int id) { return MarketData.currency(prefs(c).getAll().get("currency." + id)); }
    static void setCurrency(Context c, int id, String currency) {
        if (!currency(c, id).equals(MarketData.currency(currency))) CenterRefresh.invalidate(c, id);
        prefs(c).edit().putString("currency." + id, MarketData.currency(currency)).apply();
    }
    static String priceText(Context c, int id) {
        try {
            JSONObject data = new JSONObject(prefs(c).getString("price", ""));
            return MarketData.priceText(data.get(currency(c, id)));
        } catch (JSONException | IllegalArgumentException e) { return "—"; }
    }
    /** One immutable native quote keeps the displayed price and comparison on the same cache read. */
    static final class PriceSample {
        final String text;
        final Double current;
        PriceSample(Double price,boolean validTime) {
            text=price==null?"—":MarketData.priceText(price);current=validTime?price:null;
        }
    }
    static PriceSample priceSample(Context c,String currency,long now) {
        if(!"USD".equals(currency)&&!"EUR".equals(currency))return new PriceSample(null,false);
        try {
            JSONObject data=new JSONObject(prefs(c).getString("price",""));
            double price=MarketData.price(data.get(currency));
            Object stamp=data.has(currency+"Checked")?data.opt(currency+"Checked"):data.opt("checked");
            boolean validTime=false;
            if(stamp instanceof Number) {
                double checked=((Number)stamp).doubleValue();
                validTime=Double.isFinite(checked)&&checked>0&&checked==Math.floor(checked)&&checked<=now;
            }
            return new PriceSample(price,validTime);
        }catch(JSONException|IllegalArgumentException invalid){return new PriceSample(null,false);}
    }
    /** One immutable read pairs the validated numbers and their persisted, per-slot trend flags. */
    static final class FeeSample {
        final int low, medium, high;
        final String text;
        final boolean lowIncreased, mediumIncreased, highIncreased;
        FeeSample(JSONObject data) throws JSONException {
            text = MarketData.feesText(data.get("hourFee"), data.get("halfHourFee"), data.get("fastestFee"));
            low = ((Number) data.get("hourFee")).intValue();
            medium = ((Number) data.get("halfHourFee")).intValue();
            high = ((Number) data.get("fastestFee")).intValue();
            // 0.39 has valid numbers but no flags: retain that baseline and display green.
            lowIncreased = data.optBoolean("lowIncreased", false);
            mediumIncreased = data.optBoolean("mediumIncreased", false);
            highIncreased = data.optBoolean("highIncreased", false);
        }
    }
    static FeeSample fees(Context c) {
        try { return new FeeSample(new JSONObject(prefs(c).getString("fees", ""))); }
        catch (JSONException | IllegalArgumentException e) { return null; }
    }
    /** Successful data age is independent of the persisted attempt/retry floor. */
    static boolean marketStale(Context c, int id, boolean price, long now) {
        try {
            JSONObject data = new JSONObject(prefs(c).getString(price ? "price" : "fees", ""));
            String currency = currency(c, id);
            if (price) MarketData.price(data.get(currency));
            else new FeeSample(data);
            long checked = data.getLong(price && data.has(currency + "Checked") ? currency + "Checked" : "checked");
            return checked > now || MarketData.due(price, now, checked, 0);
        } catch (JSONException | IllegalArgumentException missing) { return true; }
    }
    static boolean marketDue(Context c, int id, boolean price, long now) {
        return BlockzeitWidget.active(c, id) && mode(c, id) == WidgetMode.MARKET
                && marketStale(c, id, price, now)
                && MarketData.due(price, now, prefs(c).getLong(price ? "priceAttempt" : "feesAttempt", 0),
                        prefs(c).getLong("retryAfter", 0));
    }
    static String feesText(Context c) {
        FeeSample sample = fees(c);
        return sample == null ? "— / — / — sat/vB" : sample.text;
    }
    static String mempoolText(Context c) {
        try {
            JSONObject data = new JSONObject(prefs(c).getString("mempool", ""));
            return MarketData.mempoolText(data.get("vsize"));
        } catch (JSONException | IllegalArgumentException e) { return "— vMB"; }
    }
    static void saveMempool(Context c, Object vsize, long checked) throws JSONException {
        long value = MarketData.vsize(vsize);
        JSONObject data = new JSONObject().put("vsize", value).put("checked", checked);
        prefs(c).edit().putString("mempool", data.toString()).apply();
    }
    static void savePrice(Context c, JSONObject sample, long checked) throws JSONException {
        JSONObject data;
        try { data = new JSONObject(prefs(c).getString("price", "")); }
        catch (JSONException e) { data = new JSONObject(); }
        boolean valid = false;
        // A missing/bad currency must not suppress its valid sibling or erase either last-good value.
        // Retaining the same USD key also reads the original USD-only cache without migration.
        for (String currency : new String[]{"USD", "EUR"}) {
            try {
                double value = MarketData.price(sample.opt(currency));
                data.put(currency, value).put(currency + "Checked", checked);
                valid = true;
            } catch (IllegalArgumentException ignored) { /* Preserve this currency's prior sample. */ }
        }
        if (!valid) throw new IllegalArgumentException("No valid currency price");
        data.put("checked", checked);
        prefs(c).edit().putString("price", data.toString()).apply();
    }
    static void saveFees(Context c, Object low, Object medium, Object high, long checked) throws JSONException {
        MarketData.feesText(low, medium, high); // Validate the entire sample before replacing last-good data.
        FeeSample previous = fees(c);
        JSONObject data = new JSONObject().put("hourFee", low).put("halfHourFee", medium)
                .put("fastestFee", high).put("checked", checked)
                .put("lowIncreased", previous != null && ((Number) low).intValue() > previous.low)
                .put("mediumIncreased", previous != null && ((Number) medium).intValue() > previous.medium)
                .put("highIncreased", previous != null && ((Number) high).intValue() > previous.high);
        // One atomic cache replacement; errors/partial samples never reach this point.
        prefs(c).edit().putString("fees", data.toString()).apply();
    }
    static Pool poolFromJson(JSONObject data, Block block) {
        try {
            // Both keys are mandatory even for a hash-addressed response; never trust height alone.
            if (Block.parseHeight(data.get("height").toString()) != block.height
                    || !block.hash.equals(data.getString("id"))) return null;
            JSONObject extras = data.optJSONObject("extras");
            JSONObject pool = extras == null ? null : extras.optJSONObject("pool");
            if (pool == null || "unknown".equalsIgnoreCase(pool.optString("slug", ""))) return null;
            return Pool.of(block.height, block.hash, pool.opt("name"));
        } catch (JSONException | IllegalArgumentException e) { return null; }
    }
    private static Pool loadPool(Context c, Block block) {
        String raw = prefs(c).getString("pool", "");
        if (raw.length() > 512) return null;
        try {
            JSONObject data = new JSONObject(raw);
            Pool pool = Pool.of(Block.parseHeight(data.get("height").toString()),
                    data.getString("hash"), data.opt("name"));
            return pool != null && (block == null || pool.matches(block)) ? pool : null;
        } catch (JSONException | IllegalArgumentException e) { return null; }
    }
    static String poolText(Snapshot snapshot) {
        // Display retention is separate from current-tip acquisition eligibility. Never rebind the
        // cached attribution: its original hash/height remain stored until valid new metadata arrives.
        return snapshot.retainedPool == null ? Pool.UNKNOWN : snapshot.retainedPool.name;
    }
    static void savePool(Context c, Block block, Pool pool) throws JSONException {
        // A late optional result must not be attached to a newer snapshot or same-height reorg.
        Snapshot current = load(c);
        if (current.blocks.isEmpty() || pool == null || !pool.matches(block)
                || !pool.matches(current.blocks.get(0))) return;
        JSONObject data = new JSONObject().put("height", pool.height).put("hash", pool.hash).put("name", pool.name);
        prefs(c).edit().putString("pool", data.toString()).apply();
    }
    static final class Snapshot {
        final List<Block> blocks;
        final long checked;
        final String dayKey;
        final Pool pool, retainedPool;
        Snapshot(List<Block> blocks, long checked, String dayKey) {
            this(blocks, checked, dayKey, null);
        }
        Snapshot(List<Block> blocks, long checked, String dayKey, Pool pool) {
            this(blocks, checked, dayKey, pool, pool);
        }
        Snapshot(List<Block> blocks, long checked, String dayKey, Pool pool, Pool retainedPool) {
            this.blocks = blocks; this.checked = checked; this.dayKey = dayKey; this.pool = pool;
            this.retainedPool = retainedPool;
        }
        /** Valid progress for the live local dial, independent of acquisition age. */
        boolean displayable(long now, DayMath.Day day) {
            return !blocks.isEmpty() && day.key.equals(dayKey) && checked <= now
                    && History.hasBoundary(blocks, day.start);
        }
        boolean fresh(long now, DayMath.Day day) {
            return displayable(now, day) && now - checked <= FRESH_SECONDS;
        }
    }
    static Snapshot load(Context c) {
        String raw = prefs(c).getString("snapshot", "");
        List<Block> blocks = new ArrayList<>();
        if (raw.length() > 200_000) return new Snapshot(blocks, 0, "");
        try {
            JSONObject data = new JSONObject(raw);
            JSONArray rows = data.getJSONArray("headers");
            if (rows.length() > History.MAX_HEADERS) throw new JSONException("Cache limit");
            for (int i = 0; i < rows.length(); i++) History.append(blocks, fromJson(rows.getJSONObject(i)));
            return new Snapshot(blocks, Block.parseTimestamp(data.get("checked").toString()), data.getString("day"),
                    blocks.isEmpty() ? null : loadPool(c, blocks.get(0)), loadPool(c, null));
        } catch (JSONException | IllegalArgumentException e) {
            return new Snapshot(new ArrayList<>(), 0, "");
        }
    }
    static void save(Context c, List<Block> chain, long checked, DayMath.Day day) throws JSONException {
        JSONArray headers = new JSONArray();
        for (Block block : chain) headers.put(toJson(block));
        JSONObject data = new JSONObject().put("headers", headers).put("checked", checked)
                .put("day", day.key).put("midnight", day.start)
                .put("dailyCount", History.count(chain, day));
        // One atomic preference replacement: failed/incomplete fetches never replace good data.
        SharedPreferences.Editor edit = prefs(c).edit().putString("snapshot", data.toString())
                .remove("error"); // A parallel lane may have just established a global cooldown.
        if (!chain.isEmpty()) rememberHashrateTip(c, chain.get(0), edit);
        // Keep the last-good attribution with its ORIGINAL source identity while the authoritative
        // headers advance. Snapshot.pool stays null until attribution matches that new verified tip.
        if (!edit.commit()) {
            throw new IllegalStateException("Could not persist snapshot");
        }
    }
}
