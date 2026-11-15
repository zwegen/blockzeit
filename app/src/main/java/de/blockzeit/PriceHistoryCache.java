package de.blockzeit;

import android.content.Context;
import android.content.SharedPreferences;
import de.blockzeit.core.MarketData;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One small, durable native-currency archive, separate from the widget's live-price store. */
final class PriceHistoryCache {
    static final long HOUR=3600, PUBLICATION_DELAY=300;
    private static final String FILE="price-history-v1";
    interface Fetch { String get(Long timestamp) throws IOException; }
    private PriceHistoryCache() { }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(FILE,Context.MODE_PRIVATE); }
    static long target(long now) { return Math.floorDiv(now-PUBLICATION_DELAY,HOUR)*HOUR; }
    static long nextPublication(long now) { return target(now)+HOUR+PUBLICATION_DELAY; }
    static final class Row {
        final long time;
        final Double usd,eur;
        Row(long time,Double usd,Double eur) { this.time=time;this.usd=usd;this.eur=eur; }
        Double value(String currency) { return "EUR".equals(currency)?eur:usd; }
    }
    private static Double price(Object raw) {
        try { return MarketData.price(raw); } catch (IllegalArgumentException invalid) { return null; }
    }
    /** No nearest-earlier or off-hour observation may masquerade as the requested hour. */
    static TreeMap<Long,Row> decode(String text,long now,Long exact) throws IOException,JSONException {
        if(text==null || text.length()>PriceHistoryData.MAX_RESPONSE)throw new IOException("History limit");
        JSONArray rows=new JSONObject(text).getJSONArray("prices");TreeMap<Long,Row> result=new TreeMap<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            Object raw=row.opt("time");if(!(raw instanceof Number))continue;
            double number=((Number)raw).doubleValue();
            if(!Double.isFinite(number)||number<=0||number!=Math.floor(number)||number>now)continue;
            long time=((Number)raw).longValue();
            if(time%HOUR!=0 || exact!=null && time!=exact || time<target(now)-PriceHistoryData.DAY-PriceHistoryData.MAX_AGE)continue;
            Double usd=price(row.opt("USD")),eur=price(row.opt("EUR"));if(usd==null&&eur==null)continue;
            Row before=result.get(time);
            if(before!=null) {
                if(before.usd!=null&&usd!=null&&!before.usd.equals(usd)||before.eur!=null&&eur!=null&&!before.eur.equals(eur))
                    throw new IOException("Conflicting history");
                usd=usd==null?before.usd:usd;eur=eur==null?before.eur:eur;
            }
            result.put(time,new Row(time,usd,eur));
        }return result;
    }
    private static TreeMap<Long,Row> read(Context c,long now) {
        try { return decode(prefs(c).getString("rows","{\"prices\":[]}"),now,null); }
        catch(IOException|JSONException invalid) { return new TreeMap<>(); }
    }
    private static String encode(TreeMap<Long,Row> rows) throws JSONException {
        JSONArray list=new JSONArray();for(Row row:rows.values()) {
            JSONObject item=new JSONObject().put("time",row.time);
            if(row.usd!=null)item.put("USD",row.usd);if(row.eur!=null)item.put("EUR",row.eur);list.put(item);
        }return new JSONObject().put("prices",list).toString();
    }
    private static PriceHistoryData.Series complete(TreeMap<Long,Row> rows,String currency) {
        for(Row end:rows.descendingMap().values()) {
            Row reference=rows.get(end.time-PriceHistoryData.DAY);
            if(end.value(currency)==null||reference==null||reference.value(currency)==null)continue;
            List<PriceHistoryData.Point> points=new ArrayList<>();long previous=reference.time;boolean gap=false;
            for(Row row:rows.subMap(reference.time,true,end.time,true).values()) if(row.value(currency)!=null) {
                if(row.time-previous>PriceHistoryData.MAX_GAP)gap=true;
                points.add(new PriceHistoryData.Point(row.time,row.value(currency)));previous=row.time;
            }
            if(!gap&&points.size()>=13) {
                PriceHistoryData.Series series=new PriceHistoryData.Series(currency,points);
                if(Double.isFinite(series.change()))return series;
            }
        }return null;
    }
    static PriceHistoryData.Series view(Context c,String currency,long now) {
        if(!"USD".equals(currency)&&!"EUR".equals(currency))return null;
        try {
            String saved=prefs(c).getString("good."+currency,null);if(saved==null)return null;
            // Validate relative to the observation, not wall-clock freshness: offline must retain last-good data.
            JSONObject json=new JSONObject(saved);long end=json.getLong("end");
            if(end>now||end%HOUR!=0)return null;
            PriceHistoryData.Series good=PriceHistoryData.parse(saved,currency,end);
            if(good.points.get(good.points.size()-1).time!=end)return null;
            for(PriceHistoryData.Point point:good.points)if(point.time%HOUR!=0)return null;
            List<PriceHistoryData.Point> visible=new ArrayList<>();
            for(PriceHistoryData.Point point:good.points)if(point.time>=now-PriceHistoryData.DAY)visible.add(point);
            return visible.size()>=2?new PriceHistoryData.Series(currency,visible,good.reference):good;
        }catch(IOException|JSONException invalid){return null;}
    }
    /** Nearest validated native-currency hour to wall-clock now-24h, never an arbitrary old chart. */
    static Double reference(Context c,String currency,long now) {
        if(!"USD".equals(currency)&&!"EUR".equals(currency)||now<=PriceHistoryData.DAY)return null;
        long target=now-PriceHistoryData.DAY,best=HOUR+1;
        Double value=null;
        for(Row row:read(c,now).values()) {
            long distance=Math.abs(row.time-target);
            if(row.value(currency)!=null&&distance<=HOUR&&distance<=best) { best=distance;value=row.value(currency); }
        }return value;
    }
    private static boolean missingReference(Context c,long now) {
        boolean usd=false,eur=false;
        for(int id:BlockzeitWidget.ids(c)) if(Store.mode(c,id)==de.blockzeit.core.WidgetMode.MARKET) {
            if("EUR".equals(Store.currency(c,id)))eur=true;else usd=true;
        }
        return usd&&reference(c,"USD",now)==null||eur&&reference(c,"EUR",now)==null;
    }
    private static long referenceNext(Context c,long now) {
        long next=prefs(c).getLong("referenceNext",0);
        if(next>now+4*HOUR||prefs(c).getLong("referenceAttempt",0)>now)next=now;
        // Failed normal maintenance owns a shared pause, including consumers selected after its attempt.
        return prefs(c).getInt("failures",0)>0?Math.max(next,maintenanceNext(c,now)):next;
    }
    private static long maintenanceNext(Context c,long now) {
        long next=prefs(c).getLong("next",0);
        // Clock rollback cannot suppress maintenance for days; all hour arithmetic is UTC, not civil time.
        if(next>now+4*HOUR || prefs(c).getLong("attempt",0)>now)next=now;
        return next;
    }
    static long next(Context c,long now) {
        long next=maintenanceNext(c,now);
        if(missingReference(c,now))next=Math.min(next,referenceNext(c,now));
        return Math.max(next,prefs(c).getLong("blockedUntil",0));
    }
    static boolean due(Context c,long now) { return next(c,now)<=now; }
    private static boolean filled(TreeMap<Long,Row> rows,long now) {
        for(long t=target(now)-PriceHistoryData.DAY;t<=target(now);t+=HOUR) {
            Row row=rows.get(t);if(row==null||row.usd==null||row.eur==null)return false;
        }
        TreeMap<Long,Row> expected=new TreeMap<>(rows.headMap(target(now),true));
        for(String currency:new String[]{"USD","EUR"}) {
            PriceHistoryData.Series series=complete(expected,currency);
            if(series==null||series.points.get(series.points.size()-1).time!=target(now))return false;
        }return true;
    }
    /** Called only by the shared round. Each accepted response is committed before the next HTTP call. */
    static void refresh(Context c,long now,Fetch fetch,BooleanSupplier active) throws IOException,JSONException {
        if(!due(c,now)||!active.getAsBoolean())return;
        TreeMap<Long,Row> rows=read(c,now);SharedPreferences state=prefs(c);
        if(maintenanceNext(c,now)>now&&missingReference(c,now)&&referenceNext(c,now)<=now) {
            // Join the existing shared owner: one exact hour for all currencies/widgets, no full-history reload.
            long timestamp=Math.floorDiv(now-PriceHistoryData.DAY+HOUR/2,HOUR)*HOUR;
            if(!state.edit().putLong("referenceAttempt",now).putLong("referenceNext",now+1800).commit())
                throw new IOException("Reference reservation failed");
            try { merge(c,rows,decode(fetch.get(timestamp),now,timestamp),now,active); }
            catch(BlockClient.RateLimited limited) {
                state.edit().putLong("blockedUntil",limited.until).commit();throw limited;
            }
        }
        // Normal chart maintenance retains its own cadence and only fills gaps not already saved above.
        if(maintenanceNext(c,now)>now||!active.getAsBoolean())return;
        int failures=(int)Math.min(3,Math.max(0,state.getInt("failures",0)));
        long retry=now+Math.min(4*HOUR,1800L<<failures);
        SharedPreferences.Editor reservation=state.edit().putLong("attempt",now).putLong("next",retry).putInt("failures",failures+1);
        // The normal round also attempts the reference. Its failures/cancellation must not open an immediate retry lane.
        if(missingReference(c,now))reservation.putLong("referenceAttempt",now).putLong("referenceNext",retry);
        if(!reservation.commit())throw new IOException("History reservation failed");
        try {
            if(rows.isEmpty()&&view(c,"USD",now)==null&&view(c,"EUR",now)==null) {
                merge(c,rows,decode(fetch.get(null),now,null),now,active);
            } else {
                for(long t=target(now)-PriceHistoryData.DAY;t<=target(now)&&active.getAsBoolean();t+=HOUR) {
                    Row row=rows.get(t);if(row!=null&&row.usd!=null&&row.eur!=null)continue;
                    TreeMap<Long,Row> incoming=decode(fetch.get(t),now,t);
                    merge(c,rows,incoming,now,active);
                    // A not-yet-published newest hour ends this round; never relabel the previous one.
                    if(t==target(now)&&incoming.isEmpty())break;
                }
            }
            if(active.getAsBoolean()&&filled(rows,now))state.edit().putLong("next",nextPublication(now)).putInt("failures",0).commit();
        } catch(BlockClient.RateLimited limited) {
            state.edit().putLong("next",Math.max(retry,limited.until)).putLong("blockedUntil",limited.until).commit();throw limited;
        }
    }
    private static void merge(Context c,TreeMap<Long,Row> rows,TreeMap<Long,Row> incoming,long now,BooleanSupplier active) throws IOException,JSONException {
        synchronized(PriceHistoryData.LOCK) {
            if(!active.getAsBoolean())throw new IOException("History stopped");
            for(Row row:incoming.values()) {
                Row previous=rows.get(row.time);
                // Keep already validated currency columns when the provider returns a partial row.
                rows.put(row.time,new Row(row.time,row.usd!=null?row.usd:previous==null?null:previous.usd,
                        row.eur!=null?row.eur:previous==null?null:previous.eur));
            }
            // Validate the whole replacement before publishing last-good. A finite price can still
            // overflow a 24h ratio. Leave that native column missing so a later bounded retry repairs it.
            for(Long time:incoming.keySet()) {
                Row row=rows.get(time),reference=rows.get(time-PriceHistoryData.DAY);
                if(reference==null)continue;
                Double usd=row.usd,eur=row.eur;
                if(usd!=null&&reference.usd!=null&&!Double.isFinite((usd/reference.usd-1)*100))usd=null;
                if(eur!=null&&reference.eur!=null&&!Double.isFinite((eur/reference.eur-1)*100))eur=null;
                rows.put(time,new Row(time,usd,eur));
            }
            SharedPreferences.Editor edit=prefs(c).edit().putString("rows",encode(rows));
            for(String currency:new String[]{"USD","EUR"}) {
                PriceHistoryData.Series series=complete(rows,currency);
                if(series!=null)edit.putString("good."+currency,series.json());
            }
            if(!active.getAsBoolean()||!edit.commit())throw new IOException("History commit stopped/failed");
        }
    }
}
