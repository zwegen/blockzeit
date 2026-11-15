package de.blockzeit;

import android.Manifest;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import de.blockzeit.core.CalendarAgenda;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** English settings, like the existing page. Provider writes require explicit confirmation. */
final class CalendarSettings {
    static final int READ_REQUEST = 210, WRITE_REQUEST = 211;
    private final SettingsActivity activity;
    private final Switch enabled;
    private final Spinner chooser;
    private final SettingsActionPicker manage, transfer;
    private final Button access, reload, create;
    private final EditText name;
    private final TextView message;
    private final Map<Integer, Long> drafts = new HashMap<>();
    private final Map<Integer, Boolean> modes = new HashMap<>();
    private List<CalendarRepository.Calendar> available = new ArrayList<>();
    private final List<Long> choices = new ArrayList<>();
    private int widget, generation;
    private boolean busy, closed, binding;
    private String pendingName;
    private int pendingWidget;
    private CalendarRepository.Request<?> request;
    private AlertDialog creationDialog, nameDialog;
    private final CalendarImportSettings importer;
    private final CalendarExportSettings exporter;
    CalendarSettings(SettingsActivity activity, LinearLayout layout, Switch enabled) {
        this.activity = activity;
        this.enabled = enabled;
        // Commands use explicit popup item clicks, never selection/bind callbacks.
        manage = new SettingsActionPicker(activity, "Manage calendars", java.util.Arrays.asList(
                "Create new calendar…", "Allow access", "Reload calendars"));
        manage.styleSelectionPopup();
        SettingsControls.add(layout, manage);
        access = button(null, "Allow access");
        reload = button(null, "Reload calendars");
        chooser = new SettingsSelectionSpinner(activity); SettingsControls.spinner(chooser); SettingsControls.add(layout, chooser);
        chooser.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!binding && position < choices.size()) {
                    drafts.put(widget, choices.get(position));
                    if (importer != null) importer.selectionChanged();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        transfer = new SettingsActionPicker(activity, "Import / Export", java.util.Arrays.asList(
                "Import calendar…", "Export calendar…"));
        transfer.styleSelectionPopup();
        SettingsControls.add(layout, transfer);
        message = label(layout, "Calendar access is optional.");
        message.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                message.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(android.text.Editable text) { }
        });
        name = new EditText(activity); SettingsControls.input(name); name.setHint("New local calendar name"); name.setSingleLine(true);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        create = button(null, "Create local calendar…");
        for (View control : new View[]{manage, chooser, transfer}) matchSaveBounds(control);
        access.setOnClickListener(v -> {
            if (busy || closed) return;
            if (!CalendarRepository.canRead(activity)) activity.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, READ_REQUEST);
            else message.setText("Calendar access is already allowed.");
        });
        reload.setOnClickListener(v -> {
            if (busy || closed) return;
            CalendarRefresh.reload(activity, drafts.getOrDefault(widget, -1L));
            load();
        });
        importer = new CalendarImportSettings(activity, layout, () -> drafts.getOrDefault(widget, -1L), () -> widget);
        layout.removeView(importer.importButton());
        exporter = new CalendarExportSettings(activity, layout, () -> drafts.getOrDefault(widget, -1L));
        layout.removeView(exporter.exportButton());
        manage.onAction(position -> {
            switch (position) {
                case 0: askName(); break;
                case 1: access.performClick(); break;
                case 2: reload.performClick(); break;
            }
        });
        transfer.onAction(position -> {
            if (position == 0) importer.importButton().performClick(); else exporter.exportButton().performClick();
        });
        create.setOnClickListener(v -> {
            String title = CalendarAgenda.singleLine(name.getText().toString());
            if (title.isEmpty()) { message.setText(R.string.calendar_name_required); return; }
            int target = widget;
            creationDialog = new AlertDialog.Builder(activity).setTitle("Create local calendar?")
                    .setMessage("Create “" + title + "” in Android's calendar storage? This persists even if settings are cancelled. Existing calendars and events are unchanged.")
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton("Create", (dialog, which) -> {
                        if (closed) return;
                        pendingName = title; pendingWidget = target;
                        if (!CalendarRepository.canWrite(activity)) activity.requestPermissions(new String[]{Manifest.permission.WRITE_CALENDAR}, WRITE_REQUEST);
                        else create();
                    }).show();
            OutsideCancel.negative(creationDialog);
        });
    }
    private void askName() {
        if (busy || closed || nameDialog != null && nameDialog.isShowing()) return;
        LinearLayout body = new LinearLayout(activity);
        int padding = dp(24); body.setPadding(padding, 0, padding, 0);
        body.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        nameDialog = new AlertDialog.Builder(activity).setTitle("Create new calendar")
                .setView(body).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Continue", null).create();
        nameDialog.setOnDismissListener(dialog -> body.removeView(name));
        nameDialog.setOnShowListener(dialog -> nameDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (CalendarAgenda.singleLine(name.getText().toString()).isEmpty()) {
                name.setError(activity.getString(R.string.calendar_name_required)); return;
            }
            name.setError(null); nameDialog.dismiss(); create.performClick();
        }));
        nameDialog.show();
        OutsideCancel.negative(nameDialog);
    }
    /** Save has a 48dp painted minimum with no native empty border insets. */
    private void matchSaveBounds(View control) {
        int visible = dp(48);
        android.graphics.drawable.Drawable background = control.getBackground();
        android.graphics.Insets insets = background.getOpticalInsets();
        if (!(background instanceof InputStyle.Boundary) && background.getConstantState() != null) {
            // Native button paint is inset despite zero opticalInsets. Never probe live bounds.
            android.graphics.drawable.Drawable probe = background.getConstantState()
                    .newDrawable(activity.getResources()).mutate();
            probe.setState(background.getState()); probe.setBounds(0, 0, visible, visible);
            android.graphics.Outline outline = new android.graphics.Outline(); probe.getOutline(outline);
            android.graphics.Rect painted = new android.graphics.Rect();
            if (outline.getRect(painted) && !painted.isEmpty())
                insets = android.graphics.Insets.of(painted.left, painted.top,
                        visible - painted.right, visible - painted.bottom);
        }
        // Set before first measure; retain native WRAP_CONTENT and all content padding.
        control.setMinimumHeight(visible + insets.top + insets.bottom);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) control.getLayoutParams();
        params.leftMargin = -insets.left; params.rightMargin = -insets.right;
        control.setLayoutParams(params);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout layout, String text) {
        TextView view = new TextView(activity); view.setText(text); view.setTextSize(15); SettingsControls.foreground(view); view.setPadding(0, dp(6), 0, dp(6)); layout.addView(view); return view;
    }
    private Button button(LinearLayout layout, String text) {
        Button button = new Button(activity); button.setText(text); SettingsControls.button(button); if (layout != null) SettingsControls.add(layout, button); return button;
    }
    private void capture() { if (widget != 0) modes.put(widget, enabled.isChecked()); }
    void select(int id) {
        if (widget == id) return;
        capture(); generation++; widget = id;
        drafts.putIfAbsent(id, Store.calendarId(activity, id)); modes.putIfAbsent(id, Store.calendarEnabled(activity, id));
        enabled.setChecked(modes.get(id)); bind(); importer.selectionChanged();
        if (CalendarRepository.canRead(activity) && !busy) load();
    }
    void seedDraft(int id, boolean mode, long calendar) {
        drafts.put(id, calendar); modes.put(id, mode);
        if (widget == id) { enabled.setChecked(mode); bind(); }
    }
    private void bind() {
        binding = true; choices.clear(); List<String> labels = new ArrayList<>();
        choices.add(-1L); labels.add("No calendar selected");
        long selected = drafts.getOrDefault(widget, -1L);
        for (CalendarRepository.Calendar calendar : available) { choices.add(calendar.id); labels.add(calendar.name + " · " + calendar.id); }
        if (selected >= 0 && !choices.contains(selected)) { choices.add(selected); labels.add("Saved calendar (unavailable / not loaded) · " + selected); }
        chooser.setAdapter(SettingsControls.choices(activity, labels));
        chooser.setSelection(Math.max(0, choices.indexOf(selected))); binding = false;
    }
    private void busy(boolean value) {
        busy = value; access.setEnabled(!value); reload.setEnabled(!value); create.setEnabled(!value); manage.setEnabled(!value); chooser.setEnabled(!value);
    }
    private <T> boolean execute(CalendarRepository.Work<T> operation, java.util.function.Consumer<T> apply) {
        return execute(operation, apply, false);
    }
    private <T> boolean execute(CalendarRepository.Work<T> operation, java.util.function.Consumer<T> apply, boolean modelResult) {
        if (busy || closed) return false;
        busy(true); message.setText(R.string.calendar_busy);
        int token = generation;
        request = CalendarRepository.enqueue(activity, operation, (value, failure) -> {
            request = null;
            if (closed) return;
            busy(false);
            boolean current = modelResult || token == generation;
            if (current) {
                if (failure == null) apply.accept(value);
                else if (CalendarRepository.OVERFLOW.equals(failure.getMessage())) {
                    message.setText(R.string.calendar_too_many);
                } else message.setText(error(failure.getMessage()));
            }
            // Preserve the confirmed target while a list or permission result is pending.
            if (pendingName != null && CalendarRepository.canWrite(activity)) create();
            else if (!current && CalendarRepository.canRead(activity)) load();
        });
        if (request != null) return true;
        busy(false); message.setText(R.string.calendar_queue_busy);
        return false;
    }
    private void load() {
        if (busy || closed) return;
        if (!CalendarRepository.canRead(activity)) {
            message.setText("Choose Allow access before reloading calendars.");
            return;
        }
        execute(CalendarRepository::calendars, calendars -> {
            available = calendars; bind();
            message.setText(calendars.isEmpty() ? "No calendars found. You may explicitly create a local calendar." : "");
        });
    }
    private void create() {
        if (busy || closed) return;
        String requested = pendingName;
        int target = pendingWidget;
        if (requested == null || target == 0) { message.setText(R.string.calendar_create_retry); return; }
        // Work captures only immutable request data; the cancelable reply owns the Activity.
        boolean accepted = execute((context, cancel) -> CalendarRepository.createLocal(context, requested, cancel), id -> {
            drafts.put(target, id); available.add(new CalendarRepository.Calendar(id, requested)); bind();
            message.setText(widget == target ? activity.getString(R.string.calendar_creating_done)
                    : activity.getString(R.string.calendar_created_for_widget, target)); name.setText("");
        }, true);
        if (accepted) { pendingName = null; pendingWidget = 0; }
    }
    private int error(String message) {
        switch (message) {
            case "Allow calendar access": return R.string.calendar_read_required;
            case "Allow calendar write access": return R.string.calendar_write_required;
            case "The calendar no longer exists": return R.string.calendar_deleted;
            case "Could not create the local calendar": return R.string.calendar_create_unavailable;
            default: return R.string.calendar_provider_unavailable;
        }
    }
    void permissionResult(int request) {
        importer.permissionResult(request);
        exporter.permissionResult(request);
        if (request == READ_REQUEST) {
            if (CalendarRepository.canRead(activity)) load(); else message.setText(R.string.calendar_read_denied);
        } else if (request == WRITE_REQUEST) {
            if (CalendarRepository.canWrite(activity)) create();
            else { pendingName = null; pendingWidget = 0; message.setText(R.string.calendar_write_denied); }
        }
    }
    void save(int id) {
        capture(); long calendar = drafts.getOrDefault(id, -1L);
        boolean changed = Store.calendarId(activity, id) != calendar;
        Store.setCalendar(activity, id, modes.getOrDefault(id, false), calendar);
        if (changed) CalendarRefresh.reload(activity, calendar);
    }
    void activityResult(int request, int result, android.content.Intent data) { importer.activityResult(request, result, data); exporter.activityResult(request, result, data); }
    void resume() { importer.resume(); }
    void saveState(Bundle state) {
        importer.saveState(state);
        exporter.saveState(state);
        capture(); Bundle data = new Bundle();
        for (int id : drafts.keySet()) { data.putLong("id." + id, drafts.get(id)); data.putBoolean("mode." + id, modes.getOrDefault(id, false)); }
        state.putBundle("calendarDrafts", data); state.putString("calendarName", name.getText().toString()); state.putString("calendarPendingName", pendingName); state.putInt("calendarPendingWidget", pendingWidget);
    }
    void restoreState(Bundle state) {
        if (state == null) return;
        importer.restoreState(state);
        exporter.restoreState(state);
        Bundle data = state.getBundle("calendarDrafts");
        if (data != null) for (String key : data.keySet()) if (key.startsWith("id.")) {
            int id = Integer.parseInt(key.substring(3)); drafts.put(id, data.getLong(key)); modes.put(id, data.getBoolean("mode." + id));
        }
        name.setText(state.getString("calendarName", "")); pendingName = state.getString("calendarPendingName"); pendingWidget = state.getInt("calendarPendingWidget", 0);
        if (pendingWidget == 0) pendingName = null; // Old/incomplete state cannot choose a different widget.
    }
    boolean blocksOutsideCancel() { return busy || importer.blocksOutsideCancel() || exporter.blocksOutsideCancel(); }
    void close() {
        importer.close();
        exporter.close();
        closed = true; generation++; pendingName = null; pendingWidget = 0;
        if (request != null) { request.cancel(); request = null; }
        manage.dismiss(); transfer.dismiss();
        if (nameDialog != null) nameDialog.dismiss();
        if (creationDialog != null) creationDialog.dismiss();
    }
}
