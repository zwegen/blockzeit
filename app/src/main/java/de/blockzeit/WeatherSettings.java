package de.blockzeit;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.blockzeit.core.WeatherData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small settings extension. Search results are drafts, committed only by the existing Save button. */
final class WeatherSettings {
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView placeText, placeDetails, message;
    private final LinearLayout selected;
    private final EditText query;
    private final Button search, clear;
    private SettingsActionPicker resultPicker;
    private final LinearLayout results;
    private final Map<Integer, WeatherData.Place> drafts = new HashMap<>();
    private int widget, generation;
    private WeatherClient client;
    private boolean closed, enabled = true;

    WeatherSettings(Context context, LinearLayout layout) {
        this.context = context;
        selected = new LinearLayout(context); selected.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(12 * context.getResources().getDisplayMetrics().density);
        int vertical = Math.round(8 * context.getResources().getDisplayMetrics().density);
        selected.setPadding(inset, vertical, inset, vertical); InputStyle.field(selected);
        placeText = label(""); placeText.setTextSize(17); placeText.setPadding(0, 0, 0, 0);
        selected.addView(placeText);
        placeDetails = label(""); placeDetails.setTextSize(17);
        placeDetails.setSingleLine(true); placeDetails.setEllipsize(android.text.TextUtils.TruncateAt.END);
        placeDetails.setPadding(0, 0, 0, 0); selected.addView(placeDetails);
        SettingsControls.add(layout, selected);
        query = new EditText(context);
        query.setSingleLine(true); SettingsControls.input(query); query.setHint(R.string.weather_query);
        query.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        SettingsControls.add(layout, query);
        matchQueryEdges(selected);
        search = new Button(context); search.setText(R.string.weather_search); SettingsControls.button(search); SettingsControls.add(layout, search);
        message = label(""); message.setVisibility(View.GONE); layout.addView(message);
        message.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                message.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(android.text.Editable text) { }
        });
        results = new LinearLayout(context); results.setOrientation(LinearLayout.VERTICAL); layout.addView(results);
        results.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { cancel(); }
        });
        search.setOnClickListener(v -> { if (client != null) cancel(); else search(); });
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { cancel(); }
            @Override public void afterTextChanged(android.text.Editable text) { }
        });
        clear = new Button(context); clear.setText(R.string.weather_clear); SettingsControls.button(clear); SettingsControls.add(layout, clear);
        clear.setOnClickListener(v -> { cancel(); drafts.put(widget, null); showPlace(); });
        showPlace();
        // These three explicit controls share the current calendar chooser's 48dp painted height.
        // Set geometry before the first measure. Changing it from onLayout can miss that measure,
        // leaving the old 48px outer size until an unrelated section/layout invalidation.
        for (View control : new View[]{query, search, clear}) matchCalendarHeight(control);
        matchGapAbove(selected, query);
        matchGapAbove(query, search);
        matchGapAbove(search, clear);
    }
    // These background-free boundaries lack the native field's horizontal painted insets.
    // Keep the query/buttons unchanged; inset only this card and the weather result launcher.
    private void matchQueryEdges(View control) {
        android.graphics.Insets reference = query.getBackground().getOpticalInsets();
        android.graphics.Insets own = control.getBackground().getOpticalInsets();
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) control.getLayoutParams();
        params.leftMargin = reference.left - own.left; params.rightMargin = reference.right - own.right;
        control.setLayoutParams(params);
    }
    private void matchGapAbove(View upper, View lower) {
        LinearLayout.LayoutParams before = (LinearLayout.LayoutParams) upper.getLayoutParams();
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) lower.getLayoutParams();
        params.topMargin = Math.round(8 * context.getResources().getDisplayMetrics().density)
                - before.bottomMargin - paintedInsets(upper).bottom - paintedInsets(lower).top;
        lower.setLayoutParams(params);
    }
    private android.graphics.Insets paintedInsets(View control) {
        android.graphics.drawable.Drawable background = control.getBackground();
        android.graphics.Insets optical = background.getOpticalInsets();
        if (!(background instanceof InputStyle.Boundary) && background.getConstantState() != null) {
            // Native buttons have painted insets despite zero opticalInsets. Inspect an isolated
            // copy at a known extent; never modify the live background's bounds or read stale size.
            int extent = Math.round(48 * context.getResources().getDisplayMetrics().density);
            android.graphics.drawable.Drawable probe = background.getConstantState().newDrawable(context.getResources()).mutate();
            probe.setState(background.getState()); probe.setBounds(0, 0, extent, extent);
            android.graphics.Outline outline = new android.graphics.Outline(); probe.getOutline(outline);
            android.graphics.Rect painted = new android.graphics.Rect();
            if (outline.getRect(painted) && painted.height() > 0)
                return android.graphics.Insets.of(painted.left, painted.top, extent - painted.right, extent - painted.bottom);
        }
        return optical;
    }
    private void matchCalendarHeight(View control) {
        int visible = Math.round(48 * context.getResources().getDisplayMetrics().density);
        android.graphics.Insets insets = paintedInsets(control);
        int height = visible + insets.top + insets.bottom;
        android.view.ViewGroup.LayoutParams params = control.getLayoutParams();
        if (params.height != height) { params.height = height; control.setLayoutParams(params); }
    }
    private TextView label(String value) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(15);
        SettingsControls.foreground(view);
        int padding = Math.round(6 * context.getResources().getDisplayMetrics().density);
        view.setPadding(0, padding, 0, padding); return view;
    }
    void select(int id) {
        if (widget == id) return;
        cancel(); widget = id;
        if (!drafts.containsKey(id)) drafts.put(id, WeatherStore.place(context, id));
        query.setText(""); showPlace();
    }
    // Only a fresh configure page seeds this draft; instance-state restoration takes precedence.
    void seedDraft(int id, WeatherData.Place place) {
        drafts.put(id, place);
        if (widget == id) showPlace();
    }
    private void showPlace() {
        WeatherData.Place place = drafts.get(widget);
        placeText.setSingleLine(place != null);
        placeText.setEllipsize(place == null ? null : android.text.TextUtils.TruncateAt.END);
        if (place == null) placeText.setText(R.string.settings_weather_no_place);
        else {
            android.text.SpannableString text = new android.text.SpannableString(place.name);
            text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, place.name.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            placeText.setText(text);
        }
        // Bind metadata directly; never parse the display label or introduce empty separators.
        String details = "";
        if (place != null) {
            details = place.region;
            if (!place.country.isEmpty()) details += (details.isEmpty() ? "" : " · ") + place.country;
        }
        placeDetails.setText(details);
        placeDetails.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        InputStyle.borderColor(selected, place == null ? InputStyle.BORDER : 0xff6f9d78);
        placeText.setStateDescription(place == null ? null : context.getString(R.string.weather_selected_location));
        clear.setVisibility(place == null ? View.GONE : View.VISIBLE);
    }
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) cancel();
    }
    private void search() {
        if (!enabled || closed) return;
        String term = query.getText().toString().trim();
        if (term.length() < 2) { message.setText(R.string.weather_query_short); return; }
        cancel();
        final int token = generation, target = widget;
        final WeatherClient owner = new WeatherClient(); client = owner;
        search.setText(R.string.weather_cancel); message.setText(R.string.weather_searching);
        worker.execute(() -> {
            List<WeatherData.Place> found;
            try { found = owner.search(term); }
            catch (Exception failure) {
                main.post(() -> {
                    if (!accept(token, target, owner)) return;
                    client = null; search.setText(R.string.weather_search); message.setText(R.string.weather_search_error);
                }); return;
            }
            main.post(() -> {
                if (!accept(token, target, owner)) return;
                client = null; search.setText(R.string.weather_search);
                message.setText(found.isEmpty() ? R.string.weather_no_results : R.string.weather_choose);
                if (!found.isEmpty()) {
                    resultPicker = new SettingsActionPicker(context, context.getString(R.string.weather_choose),
                            found.stream().map(WeatherData.Place::label).collect(java.util.stream.Collectors.toList()));
                    SettingsControls.add(results, resultPicker); matchQueryEdges(resultPicker);
                    // Binding/restoring the stable field never selects a place: only a popup row click does.
                    resultPicker.styleSelectionPopup();
                    resultPicker.onAction(position -> {
                        if (!enabled || closed || widget != target || token != generation) return;
                        drafts.put(target, found.get(position)); cancel(); showPlace();
                    });
                }
            });
        });
    }
    private boolean accept(int token, int target, WeatherClient owner) {
        return enabled && !closed && token == generation && widget == target && client == owner;
    }
    void cancel() {
        generation++;
        if (client != null) client.cancel();
        client = null; search.setText(R.string.weather_search);
        if (resultPicker != null) { resultPicker.dismiss(); resultPicker = null; }
        results.removeAllViews(); message.setText("");
    }
    void save(int id) {
        if (drafts.containsKey(id)) WeatherStore.setPlace(context, id, drafts.get(id));
    }
    void close() { closed = true; cancel(); worker.shutdownNow(); }
    // Preserve a selected draft through configuration recreation without retaining an Activity/HTTP task.
    void saveState(Bundle state) {
        for (Map.Entry<Integer, WeatherData.Place> entry : drafts.entrySet()) {
            Bundle row = new Bundle(); WeatherData.Place place = entry.getValue();
            if (place != null) {
                row.putString("name", place.name); row.putString("region", place.region); row.putString("country", place.country);
                row.putString("timezone", place.timezone); row.putDouble("latitude", place.latitude); row.putDouble("longitude", place.longitude);
            }
            state.putBundle("weather." + entry.getKey(), row);
        }
    }
    void restoreState(Bundle state) {
        if (state == null) return;
        for (String key : state.keySet()) if (key.startsWith("weather.")) {
            try {
                int id = Integer.parseInt(key.substring(8)); Bundle row = state.getBundle(key);
                drafts.put(id, row == null || !row.containsKey("name") ? null :
                        new WeatherData.Place(row.getString("name"), row.getString("region"), row.getString("country"),
                                row.getString("timezone"), row.getDouble("latitude"), row.getDouble("longitude")));
            } catch (IllegalArgumentException invalid) { /* Ignore an invalid saved draft, never invent a location. */ }
        }
    }
}
