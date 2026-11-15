package de.blockzeit;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import de.blockzeit.core.WidgetMode;

/** PRICE middle-tap window; immediately displays the durable historical cache, never a live-price mutation. */
public final class PriceChartActivity extends Activity {
    static final String ACTION_OPEN="de.blockzeit.OPEN_PRICE_CHART";
    static Intent intent(Context context,int widget) {
        return new Intent(context,PriceChartActivity.class).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setData(identity(widget))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,widget);
    }
    static PendingIntent pendingIntent(Context context,int widget) {
        return PendingIntent.getActivity(context,widget,intent(context,widget),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static Uri identity(int widget) { return Uri.parse("blockzeit://widget/"+widget+"/price-chart"); }
    static boolean currentTarget(Context context,int widget,String currency) {
        return widget!=AppWidgetManager.INVALID_APPWIDGET_ID && BlockzeitWidget.active(context,widget)
                && Store.priceEnabled(context,widget) && Store.mode(context,widget)==WidgetMode.MARKET
                && Store.currency(context,widget).equals(currency);
    }
    private boolean matches(Intent source) {
        return source!=null && ACTION_OPEN.equals(source.getAction()) && identity(widget).equals(source.getData())
                && source.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1)==widget;
    }
    /** Retained only across configuration changes; the worker never holds an Activity context. */
    private static final class Opening {
        final int widget;
        final String currency;
        Opening(int widget,String currency) { this.widget=widget; this.currency=currency; }
        final Handler main=new Handler(Looper.getMainLooper());
        PriceHistoryData request;
        Thread worker;
        PriceHistoryData.Series series;
        Runnable listener;
        boolean opened,completed;
        void start(Context context,int widget,String currency) {
            opened=true; request=new PriceHistoryData(context,widget,currency);
            PriceHistoryData fixed=request;
            worker=new Thread(() -> {
                PriceHistoryData.Series result=null; Exception failure=null;
                try { result=fixed.load(); } catch (Exception error) { failure=error; }
                PriceHistoryData.Series loaded=result; Exception problem=failure;
                main.post(() -> {
                    if (request!=fixed) return;
                    request=null; worker=null; completed=true;
                    if (problem instanceof BlockClient.RateLimited) {
                        long until=((BlockClient.RateLimited)problem).until;
                        Store.prefs(context).edit().putLong("retryAfter",Math.max(until,Store.prefs(context).getLong("retryAfter",0))).apply();
                    }
                    if (loaded!=null) series=loaded;
                    if (listener!=null) listener.run();
                });
            },"Blockzeit-price-chart"); worker.start();
        }
        void cancel() {
            listener=null;
            if (request!=null) { request.cancel(); request=null; completed=true; }
            if (worker!=null) { worker.interrupt(); worker=null; }
            main.removeCallbacksAndMessages(null);
        }
    }
    private int widget=AppWidgetManager.INVALID_APPWIDGET_ID;
    private String currency;
    private Opening opening;
    private LinearLayout body,content;
    private TextView title,subtitle,status;
    private boolean resumed;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent source=getIntent(); if (source!=null) widget=source.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1);
        currency=Store.currency(this,widget);
        if (!matches(source) || !currentTarget(this,widget,currency)) { finish(); return; }
        Object retained=getLastNonConfigurationInstance();
        opening=retained instanceof Opening ? (Opening)retained : new Opening(widget,currency);
        if (opening.widget!=widget || !opening.currency.equals(currency)) { opening.cancel(); finish(); return; }
        if (retained==null) opening.series=PriceHistoryCache.view(this,currency,System.currentTimeMillis()/1000);
        Window window=getWindow(); window.setGravity(Gravity.CENTER);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes=window.getAttributes(); attributes.dimAmount=.58f; window.setAttributes(attributes);
        CalendarWindowStyle.window(window);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setFinishOnTouchOutside(false);
        body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(24),dp(24),dp(24),dp(24));
        body.setFocusableInTouchMode(true);
        ScrollView scroll=CalendarWindowStyle.scroll(this); scroll.setFillViewport(false); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view,insets) -> {
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom); return insets;
        }); setContentView(scroll);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); body.addView(header);
        title=label(header,"—"); CalendarWindowStyle.heading(title,24);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button close=new Button(this); close.setText("X"); close.setContentDescription(getString(R.string.blocks_close));
        close.setOnClickListener(view -> finish()); header.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));
        CalendarWindowStyle.closeIcon(close);
        OutsideCancel.activity(this, close::performClick);
        subtitle=label(body,"24 h / —"); subtitle.setTextSize(14);
        status=label(body,"Loading price history…"); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0,dp(12),0,0); body.addView(content);
        render(); body.requestFocus();
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true;
        if (body==null || isFinishing()) return;
        if (!currentTarget(this,widget,currency)) { finish(); return; }
        PriceHistoryData.Series cached=PriceHistoryCache.view(this,currency,System.currentTimeMillis()/1000);
        if (cached!=null) opening.series=cached;
        if (opening.worker==null) opening.opened=false;
        opening.listener=() -> {
            if (!resumed || isFinishing() || isDestroyed()) return;
            if (!currentTarget(this,widget,currency)) { finish(); return; }
            render(); fitWindow();
        };
        if (!opening.opened) opening.start(getApplicationContext(),widget,currency);
        render(); body.post(this::fitWindow);
    }
    private void render() {
        content.removeAllViews();
        PriceDisplay quote=PriceDisplay.read(this,currency,System.currentTimeMillis()/1000);
        title.setText(quote.title);
        SpannableString change=new SpannableString("24 h / "+quote.change.text);
        int direction=quote.change.direction;
        if (direction!=0) change.setSpan(new ForegroundColorSpan(direction<0 ? 0xFFEF5350 : 0xFF66BB6A),7,change.length()-2,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        subtitle.setText(change);
        PriceHistoryData.Series series=opening.series;
        if (series==null) {
            status.setText(opening.completed ? "Price history is currently unavailable." : "Loading price history…");
            status.setVisibility(View.VISIBLE); return;
        }
        status.setVisibility(View.GONE);
        content.addView(new PriceChartView(this,series),new LinearLayout.LayoutParams(-1,dp(220)));
    }
    private void fitWindow() {
        if (!resumed || isFinishing() || isDestroyed()) return;
        int screen=getResources().getDisplayMetrics().widthPixels;
        getWindow().setLayout(Math.min(dp(560),Math.max(dp(160),screen-dp(32))),WindowManager.LayoutParams.WRAP_CONTENT);
    }
    @Override public Object onRetainNonConfigurationInstance() { return opening; }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("widget",widget); state.putString("currency",currency);
        if (opening!=null) {
            state.putBoolean("opened",opening.opened);
            if (opening.series!=null) try { state.putString("series",opening.series.json()); } catch (org.json.JSONException invalid) { /* Display unavailable after restore. */ }
        } super.onSaveInstanceState(state);
    }
    @Override protected void onNewIntent(Intent source) {
        super.onNewIntent(source);
        if (!matches(source) || !currentTarget(this,widget,currency)) finish();
    }
    @Override protected void onStop() {
        resumed=false;
        if (opening!=null) { opening.listener=null; if (!isChangingConfigurations()) opening.cancel(); }
        super.onStop();
    }
    @Override protected void onDestroy() {
        resumed=false;
        if (opening!=null && !isChangingConfigurations()) opening.cancel();
        super.onDestroy();
    }
    private int dp(int value) { return CalendarWindowStyle.dp(this,value); }
    private TextView label(LinearLayout parent,String text) {
        TextView view=new TextView(this); view.setText(text); view.setTextSize(16); CalendarWindowStyle.text(view);
        view.setPadding(0,dp(6),0,dp(6)); parent.addView(view); return view;
    }
}
