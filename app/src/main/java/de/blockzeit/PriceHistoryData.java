package de.blockzeit;

import android.content.Context;
import android.os.SystemClock;
import de.blockzeit.core.MarketData;
import de.blockzeit.core.RetryAfter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared bounded historical acquisition. Never writes the widget live-price cache. */
final class PriceHistoryData {
    static final String PATH = "/v1/historical-price";
    static final int MAX_RESPONSE = 4 * 1024 * 1024;
    static final long DAY = 86400, MAX_AGE = 7200, MAX_GAP = 7200;
    static final class Point {
        final long time;
        final double price;
        Point(long time, double price) { this.time = time; this.price = price; }
    }
    static final class Series {
        final String currency;
        final List<Point> points;
        final Point reference;
        Series(String currency, List<Point> points) {
            this(currency,points,points.get(0));
        }
        Series(String currency,List<Point> points,Point reference) {
            this.currency = currency; this.points = Collections.unmodifiableList(new ArrayList<>(points)); this.reference=reference;
        }
        double change() { return (points.get(points.size()-1).price / reference.price - 1) * 100; }
        String title() { return MarketData.priceText(points.get(points.size()-1).price) + " " + MarketData.symbol(currency); }
        String percent() {
            // Suppress negative/positive zero after rounding; color follows the displayed number.
            java.math.BigDecimal first=java.math.BigDecimal.valueOf(reference.price);
            java.math.BigDecimal last=java.math.BigDecimal.valueOf(points.get(points.size()-1).price);
            java.math.BigDecimal rounded=last.subtract(first).multiply(java.math.BigDecimal.valueOf(100))
                    .divide(first,2,java.math.RoundingMode.HALF_UP);
            return (rounded.signum()>0 ? "+" : "") + rounded.toPlainString() + " %";
        }
        int direction() { String text = percent(); return text.startsWith("-") ? -1 : text.startsWith("+") ? 1 : 0; }
        String json() throws JSONException {
            JSONArray rows = new JSONArray();
            for (Point point : points) rows.put(new JSONObject().put("time",point.time).put(currency,point.price));
            return new JSONObject().put("prices",rows).put("end",points.get(points.size()-1).time).toString();
        }
    }
    /** Use only native API currency columns, never exchangeRates or another currency as a fallback. */
    static Series parse(String text, String currency, long now) throws IOException, JSONException {
        if (!"USD".equals(currency) && !"EUR".equals(currency)) throw new IOException("Unsupported currency");
        if (text == null || text.length() > MAX_RESPONSE) throw new IOException("Response limit");
        JSONArray rows = new JSONObject(text).getJSONArray("prices");
        TreeMap<Long, Double> sorted = new TreeMap<>();
        for (int i=0; i<rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            Object rawTime = row.opt("time");
            if (!(rawTime instanceof Number)) continue;
            double numeric = ((Number)rawTime).doubleValue();
            if (!Double.isFinite(numeric) || numeric <= 0 || numeric != Math.floor(numeric) || numeric > now) continue;
            long time = ((Number)rawTime).longValue();
            // Old archive rows are irrelevant to this opening; retain one extra day for boundary checks.
            if (time < now - 2 * DAY - MAX_AGE) continue;
            try {
                double value = MarketData.price(row.opt(currency));
                Double previous = sorted.putIfAbsent(time, value);
                // Conflicting duplicates cannot silently choose an arbitrary price.
                if (previous != null && Double.compare(previous,value) != 0) throw new IOException("Conflicting history");
            } catch (IllegalArgumentException invalid) { /* Invalid currency rows cannot substitute sibling values. */ }
        }
        if (sorted.isEmpty()) throw new IOException("No currency history");
        long end = sorted.lastKey(), start = end - DAY;
        if (now - end > MAX_AGE || !sorted.containsKey(start)) throw new IOException("Incomplete 24 h history");
        List<Point> points = new ArrayList<>(); long previous = start;
        for (java.util.Map.Entry<Long,Double> row : sorted.tailMap(start).entrySet()) {
            if (row.getKey() - previous > MAX_GAP) throw new IOException("History gap");
            points.add(new Point(row.getKey(),row.getValue())); previous = row.getKey();
        }
        if (points.size() < 13) throw new IOException("Insufficient history");
        double change = (points.get(points.size()-1).price / points.get(0).price - 1) * 100;
        if (!Double.isFinite(change)) throw new IOException("Invalid history range");
        return new Series(currency,points);
    }

    static final Object LOCK=new Object();
    private static Round round;
    private static final class Round {
        final Context context;
        final java.util.Set<PriceHistoryData> clients=new java.util.HashSet<>();
        final long deadline=SystemClock.elapsedRealtime()+110_000;
        volatile HttpURLConnection active;
        boolean stopped,done;
        Exception failure;
        Round(Context context) { this.context=context; }
        boolean active() {
            synchronized(LOCK) { return !stopped && !clients.isEmpty() && BlockzeitWidget.ids(context).length>0
                    && SystemClock.elapsedRealtime()<deadline; }
        }
        int remaining() throws IOException {
            if(!active()||Thread.currentThread().isInterrupted())throw new IOException("History interrupted/limited");
            return (int)Math.min(7000,Math.max(1,deadline-SystemClock.elapsedRealtime()));
        }
        String get(Long timestamp) throws IOException {
            remaining();
            if(Store.prefs(context).getLong("retryAfter",0)>System.currentTimeMillis()/1000)throw new IOException("Service cooldown");
            // EUR filter retains native EUR and USD together (verified upstream contract); avoid unrelated archive columns.
            HttpURLConnection connection=(HttpURLConnection)new URL(BlockClient.BASE+PATH+"?currency=EUR"+(timestamp==null?"":"&timestamp="+timestamp)).openConnection();
            synchronized(LOCK) { remaining();active=connection; }
            try {
                connection.setConnectTimeout(remaining());connection.setReadTimeout(remaining());connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","Blockzeit/0.1");
                remaining();int status=connection.getResponseCode();remaining();
                String retry=connection.getHeaderField("Retry-After");
                if(status==429||status==503&&retry!=null)throw new BlockClient.RateLimited(RetryAfter.until(retry,System.currentTimeMillis()/1000));
                if(status!=200)throw new IOException("Public service unavailable");
                if(connection.getContentLengthLong()>MAX_RESPONSE)throw new IOException("Response limit");
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192];for(;;) {
                        connection.setReadTimeout(remaining());int count=input.read(buffer);if(count==-1)break;
                        if(out.size()+count>MAX_RESPONSE)throw new IOException("Response limit");out.write(buffer,0,count);
                    }remaining();return out.toString(StandardCharsets.UTF_8.name());
                }
            }finally { synchronized(LOCK){if(active==connection)active=null;} connection.disconnect(); }
        }
        void run() {
            try { PriceHistoryCache.refresh(context,System.currentTimeMillis()/1000,this::get,this::active); }
            catch(Exception error) { failure=error; }
            finally {
                boolean publish=active();
                synchronized(LOCK) { done=true;if(round==this)round=null;LOCK.notifyAll(); }
                // Historical reference writes must repaint PRICE even if the chart is closed.
                if(publish)new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if(!stopped&&BlockzeitWidget.ids(context).length>0)BlockzeitWidget.renderAll(context);
                });
                SupplementScheduler.enqueueHistory(context);
            }
        }
    }
    private final Context context;
    private final String currency;
    private final long deadline=SystemClock.elapsedRealtime()+115_000;
    private volatile boolean cancelled;
    private Round joined;
    PriceHistoryData(Context context,int widget,String currency) { this(context,currency); }
    PriceHistoryData(Context context,String currency) { this.context=context.getApplicationContext();this.currency=currency; }
    void cancel() {
        HttpURLConnection disconnect=null;
        synchronized(LOCK) {
            cancelled=true;
            if(joined!=null) {
                joined.clients.remove(this);
                if(joined.clients.isEmpty()&&!joined.done) { joined.stopped=true;disconnect=joined.active; }
            }LOCK.notifyAll();
        }if(disconnect!=null)disconnect.disconnect();
    }
    Series load() throws IOException,JSONException {
        Round waiting;
        synchronized(LOCK) {
            check();long now=System.currentTimeMillis()/1000;
            if(round==null&&!PriceHistoryCache.due(context,now))return PriceHistoryCache.view(context,currency,now);
            if(round!=null&&round.stopped)throw new IOException("Previous history round draining");
            boolean start=round==null;if(start)round=new Round(context);
            waiting=round;joined=waiting;waiting.clients.add(this);
            if(start)new Thread(waiting::run,"Blockzeit-history-shared").start();
            try {
                while(!waiting.done) { check();LOCK.wait(Math.max(1,Math.min(1000,deadline-SystemClock.elapsedRealtime()))); }
                check();
            }catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IOException("History interrupted",interrupted); }
            finally { waiting.clients.remove(this);joined=null; }
        }
        Series result=PriceHistoryCache.view(context,currency,System.currentTimeMillis()/1000);
        if(result==null&&waiting.failure!=null) {
            if(waiting.failure instanceof IOException)throw (IOException)waiting.failure;
            if(waiting.failure instanceof JSONException)throw (JSONException)waiting.failure;
            throw new IOException("History unavailable",waiting.failure);
        }return result;
    }
    private void check() throws IOException {
        if(cancelled||Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>=deadline||BlockzeitWidget.ids(context).length==0)
            throw new IOException("History interrupted/limited");
    }
}
