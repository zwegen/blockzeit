package de.blockzeit;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only block list with the existing calendar window chrome and unchanged agenda row geometry. */
public final class BlocksActivity extends Activity {
    static final String ACTION_OPEN = "de.blockzeit.OPEN_BLOCKS";
    static Intent intent(Context context, int widget) {
        // Keep this widget's floating window out of Settings, reusing only its document URI.
        return new Intent(context, BlocksActivity.class).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setData(identity(widget))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget);
    }
    static PendingIntent pendingIntent(Context context, int widget) {
        return PendingIntent.getActivity(context, widget, intent(context, widget),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static Uri identity(int widget) { return Uri.parse("blockzeit://widget/" + widget + "/blocks"); }
    static boolean currentTarget(Context context, int widget) {
        return widget != AppWidgetManager.INVALID_APPWIDGET_ID && BlockzeitWidget.active(context, widget)
                && Store.blockEnabled(context, widget) && Store.mode(context, widget) == WidgetMode.BLOCK;
    }
    private boolean matches(Intent source) {
        return source != null && ACTION_OPEN.equals(source.getAction()) && identity(widget).equals(source.getData())
                && source.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) == widget;
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private int widget = AppWidgetManager.INVALID_APPWIDGET_ID, generation;
    private boolean resumed, completed;
    private LinearLayout body, content;
    private TextView status;
    private BlocksData request;
    private Thread worker;
    private List<BlocksData.Entry> loaded;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration english = new Configuration(); english.setLocale(Locale.ENGLISH); applyOverrideConfiguration(english);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent source = getIntent();
        if (source != null) widget = source.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (!matches(source) || !currentTarget(this, widget)) { finish(); return; }
        Window window = getWindow(); window.setGravity(Gravity.CENTER);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes(); attributes.dimAmount = .58f; window.setAttributes(attributes);
        CalendarWindowStyle.window(window);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setFinishOnTouchOutside(false);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(24), dp(24), dp(24)); body.setFocusableInTouchMode(true);
        ScrollView scroll = CalendarWindowStyle.scroll(this); scroll.setFillViewport(false); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        setContentView(scroll);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); body.addView(header);
        TextView title = label(header, getString(R.string.blocks_title)); CalendarWindowStyle.heading(title, 24);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(this); close.setText("X"); close.setContentDescription(getString(R.string.blocks_close));
        close.setOnClickListener(view -> finish()); header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        CalendarWindowStyle.button(close, true);
        close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24); close.setPadding(0, 0, 0, 0);
        OutsideCancel.activity(this, close::performClick);
        status = label(body, getString(R.string.blocks_loading));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, 0); body.addView(content);
        if (state != null && state.getInt("widget", -1) == widget && state.getBoolean("opened", false)) {
            completed = true; // Recreating this opening never starts a second network attempt.
            String saved = state.getString("blocks");
            if (saved != null) try { loaded = BlocksData.parse(saved); render(loaded); }
            catch (IOException | JSONException invalid) { status.setText(R.string.blocks_unavailable); }
            else status.setText(R.string.blocks_unavailable);
        }
        body.requestFocus();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("widget", widget); state.putBoolean("opened", completed || request != null);
        if (loaded != null) try {
            JSONArray rows = new JSONArray();
            for (BlocksData.Entry entry : loaded) rows.put(Store.toJson(entry.block).put("extras",
                    new JSONObject().put("pool", new JSONObject().put("name", entry.pool))));
            // Keep the off-screen predecessor too: recreation must retain the sixth duration.
            for (BlocksData.Entry entry : loaded) if (entry.predecessor != null) rows.put(Store.toJson(entry.predecessor));
            state.putString("blocks", rows.toString());
        } catch (JSONException invalid) { /* Restored window shows an error, never retries implicitly. */ }
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (body == null || isFinishing()) return;
        if (!currentTarget(this, widget)) { finish(); return; }
        if (!completed && request == null) load();
        body.post(this::fitWindow);
    }
    private void fitWindow() {
        if (!resumed || isFinishing() || isDestroyed()) return;
        int screen = getResources().getDisplayMetrics().widthPixels;
        getWindow().setLayout(Math.min(dp(560), Math.max(dp(160), screen - dp(32))), WindowManager.LayoutParams.WRAP_CONTENT);
    }
    private void load() {
        final int expected = ++generation;
        final BlocksData fixed = new BlocksData(this, widget); request = fixed;
        status.setText(R.string.blocks_loading); status.setVisibility(View.VISIBLE);
        worker = new Thread(() -> {
            List<BlocksData.Entry> entries = null; Exception failure = null;
            try { entries = fixed.load(); } catch (IOException | JSONException | RuntimeException error) { failure = error; }
            final List<BlocksData.Entry> result = entries; final Exception problem = failure;
            main.post(() -> {
                if (!resumed || isFinishing() || isDestroyed() || expected != generation || request != fixed) return;
                if (!currentTarget(this, widget)) { finish(); return; }
                request = null; worker = null; completed = true;
                if (problem instanceof BlockClient.RateLimited) {
                    long until = ((BlockClient.RateLimited) problem).until;
                    Store.prefs(this).edit().putLong("retryAfter", Math.max(until, Store.prefs(this).getLong("retryAfter", 0))).apply();
                }
                if (problem != null) { status.setText(R.string.blocks_unavailable); status.setVisibility(View.VISIBLE); }
                else { loaded = result; render(result); }
                fitWindow();
            });
        }, "Blockzeit-block-window");
        worker.start();
    }
    private void render(List<BlocksData.Entry> entries) {
        content.removeAllViews();
        status.setText(entries.isEmpty() ? getString(R.string.blocks_empty) : "");
        status.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        for (BlocksData.Entry entry : entries) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            row.setMinimumHeight(dp(40)); row.setPadding(dp(4), dp(6), dp(4), dp(6));
            TextView height = new TextView(this); height.setTextSize(16); CalendarWindowStyle.text(height);
            height.setText(Integer.toString(entry.block.height)); height.setSingleLine(true);
            row.addView(height, new LinearLayout.LayoutParams(-2, -2));
            TextView duration = new TextView(this); duration.setTextSize(16); CalendarWindowStyle.text(duration);
            duration.setText(entry.durationText()); duration.setSingleLine(true);
            LinearLayout.LayoutParams durationParams = new LinearLayout.LayoutParams(-2, -2); durationParams.leftMargin = dp(12);
            row.addView(duration, durationParams);
            TextView pool = new TextView(this); pool.setTextSize(16); CalendarWindowStyle.text(pool); pool.setText(entry.pool);
            pool.setSingleLine(true); pool.setEllipsize(TextUtils.TruncateAt.END); pool.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f); params.leftMargin = dp(12);
            row.addView(pool, params); content.addView(row, new LinearLayout.LayoutParams(-1, -2));
            View divider = new View(this); android.util.TypedValue line = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.listDivider, line, true)) divider.setBackgroundResource(line.resourceId);
            content.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }
    @Override protected void onNewIntent(Intent source) {
        super.onNewIntent(source);
        // A stale or foreign identity cannot retarget a visible window or start another query.
        if (!matches(source) || !currentTarget(this, widget)) finish();
    }
    private void cancelRequest() {
        ++generation;
        if (request != null) { request.cancel(); request = null; }
        if (worker != null) { worker.interrupt(); worker = null; }
        main.removeCallbacksAndMessages(null);
    }
    @Override protected void onStop() {
        resumed = false;
        if (request != null) {
            completed = true; // One attempt per opening, including interrupted stop/resume cycles.
            status.setText(R.string.blocks_unavailable); status.setVisibility(View.VISIBLE);
        }
        cancelRequest(); super.onStop();
    }
    @Override protected void onDestroy() { resumed = false; cancelRequest(); super.onDestroy(); }
    private int dp(int value) { return CalendarWindowStyle.dp(this, value); }
    private TextView label(LinearLayout parent, String text) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(16); CalendarWindowStyle.text(view);
        view.setPadding(0, dp(6), 0, dp(6)); parent.addView(view); return view;
    }
}
