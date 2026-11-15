package de.blockzeit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** True timestamp-spaced, unsmoothed observations; no synthetic values. */
final class PriceChartView extends View {
    private final PriceHistoryData.Series series;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    PriceChartView(Context context, PriceHistoryData.Series series) {
        super(context); this.series=series;
        PriceHistoryData.Point first=series.points.get(0), last=series.points.get(series.points.size()-1);
        setContentDescription(series.title()+", 24 h / "+series.percent()+", "+Instant.ofEpochSecond(first.time)+" – "+Instant.ofEpochSecond(last.time));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density=getResources().getDisplayMetrics().density;
        float text=12*getResources().getDisplayMetrics().scaledDensity;
        paint.setTextSize(text); paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));
        double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
        for (PriceHistoryData.Point point:series.points) { min=Math.min(min,point.price); max=Math.max(max,point.price); }
        double padding=Math.max((max-min)*.12,max*.0001); min-=padding; max+=padding;
        NumberFormat prices=NumberFormat.getIntegerInstance(Locale.GERMANY);
        float axis=Math.max(paint.measureText(prices.format(max)),paint.measureText(prices.format(min)))+12*density;
        float left=axis, right=getWidth()-4*density, top=text+8*density, bottom=getHeight()-text-18*density;
        if (right-left<20*density || bottom<=top) return;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density*.65f); paint.setColor(0x334F5968);
        for (int i=0;i<3;i++) {
            float y=top+(bottom-top)*i/2; canvas.drawLine(left,y,right,y,paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xB3E1E3E6);
            String label=prices.format(max-(max-min)*i/2);
            canvas.drawText(label,left-12*density-paint.measureText(label),y+text*.35f,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setColor(0x334F5968);
        }
        long start=series.points.get(0).time,end=series.points.get(series.points.size()-1).time;
        Path path=new Path();
        for (int i=0;i<series.points.size();i++) {
            PriceHistoryData.Point point=series.points.get(i);
            float x=left+(right-left)*(point.time-start)/(end-start);
            float y=bottom-(float)((point.price-min)/(max-min))*(bottom-top);
            if (i==0) path.moveTo(x,y); else path.lineTo(x,y);
        }
        Path area=new Path(path); area.lineTo(right,bottom); area.lineTo(left,bottom); area.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new android.graphics.LinearGradient(0,top,0,bottom,0x12FFB65C,0x02FFB65C,android.graphics.Shader.TileMode.CLAMP));
        canvas.drawPath(area,paint); paint.setShader(null); paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFFFFB65C); paint.setStrokeWidth(1.35f*density); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(path,paint);
        paint.setStyle(Paint.Style.FILL);
        double lastPrice=series.points.get(series.points.size()-1).price;
        canvas.drawCircle(right,bottom-(float)((lastPrice-min)/(max-min))*(bottom-top),2.5f*density,paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xB3E1E3E6);
        DateTimeFormatter times=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT).withZone(ZoneId.systemDefault());
        for (int i=0;i<3;i++) {
            String label=times.format(Instant.ofEpochSecond(start+(end-start)*i/2));
            float x=left+(right-left)*i/2;
            if (i==1) x-=paint.measureText(label)/2; else if (i==2) x-=paint.measureText(label);
            canvas.drawText(label,x,getHeight()-3*density,paint);
        }
    }
}
