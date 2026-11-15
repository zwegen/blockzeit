package de.blockzeit;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.animation.PathInterpolator;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Switch;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** Native settings: widget-specific appearance/modes/location. */
public final class SettingsActivity extends Activity {
    private int selected = AppWidgetManager.INVALID_APPWIDGET_ID;
    private SeekBar opacity;
    private Spinner currency;
    private YearColorPicker elapsedDays, remainingDays, primaryLine;
    private Switch clockMode, blockMode, priceMode, weatherMode;
    private boolean bindingBaseModes;
    private final Map<String, LinearLayout> expandableSections = new LinkedHashMap<>();
    private String expandedSection;
    private ValueAnimator sectionAnimator;
    private TextView value, status;
    private WeatherSettings weather;
    private CalendarSettings calendar;
    private boolean configuring, settingsSaveFailed;
    private WidgetPreferences.Values initialSettings;
    private final ExactAlarmAccess exactAlarmAccess = new ExactAlarmAccess();
    // Keep this settings page (including native dialog actions) in English without changing
    // the system/default locale used by widget formatting or location search results.
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration english = new Configuration();
        english.setLocale(Locale.ENGLISH);
        applyOverrideConfiguration(english);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Scheduler.cancelLegacyJobs(this);
        setResult(RESULT_CANCELED);
        int requested = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        configuring = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(getIntent().getAction());
        if (configuring && !BlockzeitWidget.active(this, requested)) { finish(); return; }
        boolean migrated = WidgetPreferences.migrate(this);
        settingsSaveFailed = !migrated || state != null && state.getBoolean("settingsSaveFailed", false);
        initialSettings = state != null ? WidgetPreferences.Values.decode(state.getString("initialSettings", ""))
                : configuring ? WidgetPreferences.forNewWidget(this, requested) : null;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24); layout.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        // The decor owns system-bar insets; resize the whole page above the keyboard.
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(page);
        LinearLayout titleRow = new LinearLayout(this); titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL); layout.addView(titleRow);
        TextView title = text(getString(R.string.app_name), 28);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_info_close);
        close.setContentDescription("Close settings");
        InfoDialog.styleAccentButton(close, InfoDialog.accentColor(this));
        close.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK));
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        close.setOnClickListener(v -> onBackPressed());
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        OutsideCancel.activity(this, () -> {
            if (calendar == null || !calendar.blocksOutsideCancel()) close.performClick();
        });
        int[] ids = configuring ? new int[]{requested} : BlockzeitWidget.ids(this);
        if (ids.length == 0) { layout.addView(text(getString(R.string.no_widgets), 17)); addInfoSection(layout, state); return; }
        selected = ids[0];
        int remembered = state == null ? requested : state.getInt("selected", requested);
        for (int id : ids) if (id == remembered) selected = id;
        LinearLayout widget = collapsibleSection(layout, "Widget", state);
        if (ids.length > 1) {
            widget.addView(text(getString(R.string.widget_select), 15));
            Spinner selector = new Spinner(this);
            List<String> labels = new ArrayList<>();
            for (int id : ids) labels.add("Blockzeit · " + id);
            selector.setAdapter(SettingsControls.choices(this, labels));
            selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long itemId) {
                    selected = ids[pos]; showSelection();
                }
                @Override public void onNothingSelected(AdapterView<?> p) { }
            });
            for (int i = 0; i < ids.length; i++) if (ids[i] == selected) selector.setSelection(i);
            SettingsControls.spinner(selector);
            SettingsControls.add(widget, selector);
        }
        clockMode = new Switch(this); clockMode.setText("Clock mode"); addMode(widget, clockMode);
        blockMode = new Switch(this); blockMode.setText("Block mode"); addMode(widget, blockMode);
        clockMode.setOnCheckedChangeListener((button, enabled) -> updateBaseToggles());
        blockMode.setOnCheckedChangeListener((button, enabled) -> updateBaseToggles());
        priceMode = new Switch(this); priceMode.setText(R.string.price_mode_enabled); addMode(widget, priceMode);
        weatherMode = new Switch(this); weatherMode.setText(R.string.weather_mode_enabled); addMode(widget, weatherMode);
        Switch calendarMode = new Switch(this); calendarMode.setText(R.string.calendar_mode); addMode(widget, calendarMode);
        LinearLayout appearance = collapsibleSection(layout, "Appearance", state);
        FrameLayout opacityField = new FrameLayout(this);
        value = text(getString(R.string.opacity_value, 100), 10);
        value.setPadding(0, 0, 0, 0);
        value.setIncludeFontPadding(false); value.setSingleLine(true);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        value.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        opacity = new SeekBar(this); opacity.setMax(100); opacity.setMinimumHeight(dp(48)); InputStyle.field(opacity);
        opacity.setId(View.generateViewId()); value.setLabelFor(opacity.getId());
        opacity.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        // Keep room for the caption without shifting the native track and thumb off center.
        int captionBand = value.getMeasuredHeight() + dp(4);
        opacityField.setMinimumHeight(Math.max(dp(48), opacity.getMeasuredHeight() + captionBand));
        opacityField.addView(opacity, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams captionPosition = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        captionPosition.setMarginEnd(dp(4)); captionPosition.bottomMargin = dp(3);
        opacityField.addView(value, captionPosition);
        LinearLayout.LayoutParams opacitySpacing = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, opacityField.getMinimumHeight());
        // Match the color fields' painted 8dp gaps, retaining at least a 48dp slider hit area.
        opacitySpacing.bottomMargin = dp(4);
        appearance.addView(opacityField, opacitySpacing);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(getString(R.string.opacity_value, progress));
                opacity.setContentDescription(value.getText());
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        elapsedDays = new YearColorPicker(this, appearance, "Elapsed days", YearColors.ELAPSED);
        remainingDays = new YearColorPicker(this, appearance, "Remaining days", YearColors.REMAINING);
        primaryLine = new YearColorPicker(this, appearance, "Primary data line", RingRenderer.GREEN);
        LinearLayout price = collapsibleSection(layout, "Price", state);
        currency = new SettingsSelectionSpinner(this); SettingsControls.spinner(currency);
        currency.setAdapter(SettingsControls.choices(this, java.util.Arrays.asList("USD ($)", "EUR (€)")));
        SettingsControls.add(price, currency);
        weather = new WeatherSettings(this, collapsibleSection(layout, "Weather", state));
        weatherMode.setOnCheckedChangeListener((button, enabled) -> weather.setEnabled(enabled));
        weather.restoreState(state);
        LinearLayout calendarContent = collapsibleSection(layout, "Calendar", state);
        calendar = new CalendarSettings(this, calendarContent, calendarMode);
        calendar.restoreState(state);
        status = text("", 14); status.setVisibility(View.GONE); layout.addView(status);
        addInfoSection(layout, state);
        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(pad, 0, pad, pad);
        page.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        Button save = new Button(this); save.setText(R.string.save); SettingsControls.button(save); CalendarWindowStyle.button(save, false); SettingsControls.add(footer, save);
        save.setOnClickListener(v -> {
            if (!BlockzeitWidget.active(this, selected)) { finish(); return; }
            int previousMode = Store.mode(this, selected);
            Store.prefs(this).edit().putInt("opacity." + selected, opacity.getProgress())
                    .putInt("elapsedDaysColor." + selected, elapsedDays.color())
                    .putInt("remainingDaysColor." + selected, remainingDays.color())
                    .putInt("primaryTextColor." + selected, primaryLine.color()).apply();
            Store.setBaseModes(this, selected, clockMode.isChecked(), blockMode.isChecked());
            Store.setCurrency(this, selected, currency.getSelectedItemPosition() == 1 ? "EUR" : "USD");
            // Disable first so changing the place cannot admit a request for a disabled mode.
            if (!weatherMode.isChecked()) Store.setModes(this, selected, priceMode.isChecked(), false);
            weather.save(selected);
            calendar.save(selected);
            // Reenable only after saving the final place/cache, not against the previous place's sample.
            Store.setModes(this, selected, priceMode.isChecked(), weatherMode.isChecked());
            weather.setEnabled(weatherMode.isChecked());
            if (initialSettings != null) {
                Store.setMode(this, selected, initialSettings.mode);
            }
            if (!WidgetPreferences.save(this, selected)) {
                settingsSaveFailed = true;
                showStatus();
                return; // Keep drafts/the page and RESULT_CANCELED available for retry.
            }
            initialSettings = null;
            settingsSaveFailed = false;
            showStatus();
            if (previousMode != Store.mode(this, selected)) {
                BlockzeitWidget.renderAfterModeSwitch(this, selected);
                Scheduler.ensure(this); Scheduler.enqueueMode(this, selected);
            } else {
                BlockzeitWidget.render(this, selected);
                Scheduler.ensure(this); Scheduler.enqueue(this);
            }
            if (configuring) {
                setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, selected));
                finish();
            }
        });
        showSelection();
        if (initialSettings != null && state == null) {
            opacity.setProgress(initialSettings.opacity);
            elapsedDays.setColor(initialSettings.elapsed);
            remainingDays.setColor(initialSettings.remaining);
            primaryLine.setColor(initialSettings.primary);
            setBaseDraft(initialSettings.clock, initialSettings.block);
            currency.setSelection(initialSettings.currency.equals("EUR") ? 1 : 0);
            priceMode.setChecked(initialSettings.price);
            weatherMode.setChecked(initialSettings.weather);
            weather.seedDraft(selected, initialSettings.place);
            calendar.seedDraft(selected, initialSettings.calendar, initialSettings.calendarId);
        }
        if (state != null) {
            opacity.setProgress(state.getInt("opacity", opacity.getProgress()));
            elapsedDays.restoreState(state, "elapsedDays");
            remainingDays.restoreState(state, "remainingDays");
            primaryLine.restoreState(state, "primaryLine");
            setBaseDraft(state.getBoolean("clockMode", clockMode.isChecked()),
                    state.getBoolean("blockMode", blockMode.isChecked()));
            currency.setSelection(state.getInt("currency", currency.getSelectedItemPosition()));
            priceMode.setChecked(state.getBoolean("priceMode", priceMode.isChecked()));
            weatherMode.setChecked(state.getBoolean("weatherMode", weatherMode.isChecked()));
        }
    }
    @Override public void onResume() {
        super.onResume();
        exactAlarmAccess.onResume(this);
        if (calendar != null) calendar.resume();
        if (opacity != null) showStatus();
    }
    @Override public void onSaveInstanceState(Bundle state) {
        state.putInt("selected", selected);
        state.putBoolean("settingsSaveFailed", settingsSaveFailed);
        if (initialSettings != null) state.putString("initialSettings", initialSettings.encode());
        if (opacity != null) state.putInt("opacity", opacity.getProgress());
        if (elapsedDays != null) elapsedDays.saveState(state, "elapsedDays");
        if (remainingDays != null) remainingDays.saveState(state, "remainingDays");
        if (primaryLine != null) primaryLine.saveState(state, "primaryLine");
        if (clockMode != null) state.putBoolean("clockMode", clockMode.isChecked());
        if (blockMode != null) state.putBoolean("blockMode", blockMode.isChecked());
        if (currency != null) state.putInt("currency", currency.getSelectedItemPosition());
        if (priceMode != null) state.putBoolean("priceMode", priceMode.isChecked());
        if (weatherMode != null) state.putBoolean("weatherMode", weatherMode.isChecked());
        if (weather != null) weather.saveState(state);
        if (calendar != null) calendar.saveState(state);
        for (Map.Entry<String, LinearLayout> entry : expandableSections.entrySet())
            state.putBoolean("section." + entry.getKey(), isSectionExpanded(entry.getKey()));
        super.onSaveInstanceState(state);
    }
    @Override public void onStop() {
        settleSections();
        if (weather != null) weather.cancel();
        super.onStop();
    }
    @Override public void onDestroy() {
        settleSections();
        if (elapsedDays != null) elapsedDays.dismiss();
        if (remainingDays != null) remainingDays.dismiss();
        if (primaryLine != null) primaryLine.dismiss();
        if (weather != null) weather.close();
        if (calendar != null) calendar.close();
        super.onDestroy();
    }
    private void showSelection() {
        if (opacity == null) return;
        opacity.setProgress(Store.opacity(this, selected));
        elapsedDays.setColor(Store.elapsedDaysColor(this, selected));
        remainingDays.setColor(Store.remainingDaysColor(this, selected));
        primaryLine.setColor(Store.primaryTextColor(this, selected));
        setBaseDraft(Store.clockEnabled(this, selected), Store.blockEnabled(this, selected));
        currency.setSelection(Store.currency(this, selected).equals("EUR") ? 1 : 0);
        value.setText(getString(R.string.opacity_value, opacity.getProgress()));
        opacity.setContentDescription(value.getText());
        priceMode.setChecked(Store.priceEnabled(this, selected));
        weatherMode.setChecked(Store.weatherEnabled(this, selected));
        if (weather != null) { weather.select(selected); weather.setEnabled(weatherMode.isChecked()); }
        if (calendar != null) calendar.select(selected);
        showStatus();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (calendar != null) calendar.activityResult(request, result, data);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (calendar != null) calendar.permissionResult(request);
    }
    private void setBaseDraft(boolean clock, boolean block) {
        bindingBaseModes = true;
        clockMode.setChecked(de.blockzeit.core.WidgetMode.clockAllowed(clock, block));
        blockMode.setChecked(block);
        bindingBaseModes = false;
        updateBaseToggles();
    }
    private void updateBaseToggles() {
        if (bindingBaseModes) return;
        if (!clockMode.isChecked() && !blockMode.isChecked()) {
            setBaseDraft(true, false);
            return;
        }
        // The last active base mode is unavailable to switch off; enabling the other unlocks it.
        clockMode.setEnabled(blockMode.isChecked());
        blockMode.setEnabled(clockMode.isChecked());
    }
    private void showStatus() {
        if (status == null) return;
        // This page is explicitly English (attachBaseContext), including native dialog actions.
        String error = settingsSaveFailed ? "Could not save settings. Please try again." : Store.errorText(this);
        status.setText(error);
        status.setVisibility(error.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private LinearLayout section(LinearLayout parent, String title, boolean first) {
        LinearLayout group = new LinearLayout(this); group.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(first ? 16 : 24);
        parent.addView(group, params);
        TextView heading = text(title, 20);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(0, dp(6), 0, dp(6));
        heading.setAccessibilityHeading(true);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(48));
        header.addView(heading, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        group.addView(header);
        return group;
    }
    private LinearLayout collapsibleSection(LinearLayout parent, String title, Bundle state) {
        boolean first = expandableSections.isEmpty();
        LinearLayout group = section(parent, title, first);
        LinearLayout header = (LinearLayout) group.getChildAt(0);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        // Inset only expanded content; keep the outer heading at its original edges.
        content.setPadding(dp(8), content.getPaddingTop(), dp(8), content.getPaddingBottom());
        // Normalize old multi-open states to the first section in display order.
        boolean expanded = expandedSection == null
                && state != null && state.getBoolean("section." + title, false);
        if (expanded) expandedSection = title;
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams spacing = (LinearLayout.LayoutParams) group.getLayoutParams();
        // Opening a panel must not move its heading away from the collapsed baseline.
        spacing.topMargin = dp(first ? 16 : 8);
        group.setLayoutParams(spacing);
        group.addView(content);
        expandableSections.put(title, content);
        TextView arrow = text(expanded ? "▾" : "▸", 20);
        arrow.setGravity(Gravity.CENTER); arrow.setPadding(0, 0, 0, 0);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(arrow, new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.setContentDescription(title);
        header.setFocusable(true);
        header.setStateDescription(expanded ? "Expanded" : "Collapsed");
        header.setOnClickListener(v -> {
            expandedSection = title.equals(expandedSection) ? null : title;
            animateSections();
        });
        return content;
    }
    private boolean isSectionExpanded(String title) {
        return title.equals(expandedSection);
    }
    private void cancelSectionAnimator() {
        ValueAnimator previous = sectionAnimator;
        sectionAnimator = null; // A cancelled animation must not settle an obsolete selection.
        if (previous != null) previous.cancel();
    }
    private void settleSections() {
        cancelSectionAnimator();
        for (Map.Entry<String, LinearLayout> entry : expandableSections.entrySet()) {
            LinearLayout panel = entry.getValue();
            boolean open = isSectionExpanded(entry.getKey());
            panel.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
            panel.setVisibility(open ? View.VISIBLE : View.GONE);
            panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            panel.requestLayout();
        }
    }
    private void animateSections() {
        cancelSectionAnimator();
        boolean animate = ValueAnimator.areAnimatorsEnabled();
        for (LinearLayout panel : expandableSections.values())
            animate &= ((View) panel.getParent()).isLaidOut();
        List<LinearLayout> panels = new ArrayList<>();
        List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
        for (Map.Entry<String, LinearLayout> entry : expandableSections.entrySet()) {
            LinearLayout panel = entry.getValue();
            boolean open = isSectionExpanded(entry.getKey());
            LinearLayout panelHeader = (LinearLayout) ((LinearLayout) panel.getParent()).getChildAt(0);
            ((TextView) panelHeader.getChildAt(1)).setText(open ? "▾" : "▸");
            panelHeader.setStateDescription(open ? "Expanded" : "Collapsed");
            // Closing content can remain painted during the transition, but is no longer exposed.
            panel.setImportantForAccessibility(open ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (!open && panel.getVisibility() == View.GONE) continue;
            if (!animate) continue;
            int start = panel.getVisibility() == View.GONE ? 0
                    : panel.getLayoutParams().height >= 0 ? panel.getLayoutParams().height : panel.getHeight();
            int end = 0;
            if (open) {
                LinearLayout group = (LinearLayout) panel.getParent();
                int width = group.getWidth() - group.getPaddingLeft() - group.getPaddingRight();
                panel.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                end = panel.getMeasuredHeight();
            }
            panels.add(panel); starts.add(start); ends.add(end);
            panel.getLayoutParams().height = start;
            panel.setVisibility(View.VISIBLE);
            panel.requestLayout();
        }
        if (panels.isEmpty()) { settleSections(); return; }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        sectionAnimator = animator;
        animator.setDuration(190);
        animator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        animator.addUpdateListener(frame -> {
            float fraction = (Float) frame.getAnimatedValue();
            for (int i = 0; i < panels.size(); i++) {
                LinearLayout panel = panels.get(i);
                panel.getLayoutParams().height = Math.round(starts.get(i) + (ends.get(i) - starts.get(i)) * fraction);
                panel.requestLayout();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (sectionAnimator == animation) settleSections();
            }
        });
        animator.start();
    }
    private void addInfoSection(LinearLayout layout, Bundle state) {
        LinearLayout info = collapsibleSection(layout, getString(R.string.settings_info), state);
        info.addView(InfoDialog.createInfoContent(this));
        info.addView(InfoDialog.createLicenseLine(this));
        info.addView(InfoDialog.createFooter(this));
    }
    private void addMode(LinearLayout parent, Switch mode) {
        mode.setMinimumHeight(dp(48)); mode.setPadding(0, dp(8), 0, dp(8));
        parent.addView(mode, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        SettingsControls.foreground(view); view.setPadding(0, dp(10), 0, dp(10));
        return view;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
