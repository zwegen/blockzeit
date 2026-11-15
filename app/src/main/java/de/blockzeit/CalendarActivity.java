package de.blockzeit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import de.blockzeit.core.CalendarAgenda;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Ordinary floating Activity, not a system overlay. All provider IO belongs to the cancelable lane. */
public final class CalendarActivity extends Activity {
    static final String ACTION_OPEN = "de.blockzeit.OPEN_CALENDAR";
    static final String EXTRA_CALENDAR_ID = "calendar_id";
    private static final int READ = 310, WRITE = 311;
    private static final String[] REPEATS = {"NONE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY"};
    private static final String[] REPEAT_LABELS = {"None", "Daily", "Weekly", "Monthly", "Yearly"};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /** Intent data, action AND immutable extras identify exactly one persisted widget/calendar pair. */
    static Intent intent(Context context, int widget, long calendar) {
        // Widget launches have no Activity caller. The manifest's document policy reuses only
        // this widget/calendar URI, never the Settings task or another calendar's draft.
        return new Intent(context, CalendarActivity.class).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(identity(widget, calendar)).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget)
                .putExtra(EXTRA_CALENDAR_ID, calendar);
    }
    static PendingIntent pendingIntent(Context context, int widget, long calendar) {
        return PendingIntent.getActivity(context, widget, intent(context, widget, calendar),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static Uri identity(int widget, long calendar) {
        return Uri.parse("blockzeit://widget/" + widget + "/calendar/" + calendar);
    }
    private CalendarEditing.Target target;
    private CalendarRepository.Request<?> request;
    private CalendarEditing.Access access;
    private CalendarEditing.Event original;
    private CalendarEditing.Preview replacementPreview;
    private CalendarAgenda.Entry lastEnded;
    private final List<CalendarAgenda.Entry> agenda = new ArrayList<>();
    private final List<View> agendaRows = new ArrayList<>();
    private LinearLayout body, content;
    private TextView status, calendarName;
    private Button add, menu, formClose, replaceAction, permissionAction, startDateButton, endDateButton, startTimeButton, endTimeButton, save;
    private EditText title, search, replacement;
    private CheckBox allDay;
    private Spinner repeat;
    private AlertDialog childDialog;
    private boolean busy, closed, resumed, restoringForm, wholeSeries, birthday, formConflict, agendaLoaded;
    private int generation;
    private boolean readPermissionDenied, writePermissionDenied;
    private int permissionProblemCode;
    // Recovery UI only, never a deferred editor/save/replacement command.
    private int permissionRecoveryCode;
    private String page = "agenda", draftTitle = "", draftRepeat = "NONE", draftZone = "", baseline = "";
    private String searchText = "", replacementText = "";
    private LocalDate startDate, endDate;
    private LocalTime startTime, endTime;
    private boolean draftAllDay;
    private long selectedId = -1, selectedBegin = -1, editingId = -1, editingBegin = -1;

    // Scope English to this window and its native menus/pickers, not the device or widget locale.
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration english = new Configuration();
        english.setLocale(Locale.ENGLISH);
        applyOverrideConfiguration(english);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setGravity(Gravity.CENTER);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes(); attributes.dimAmount = .58f;
        window.setAttributes(attributes);
        CalendarWindowStyle.window(window);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setFinishOnTouchOutside(false);
        Intent source = getIntent();
        int widget = source.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        long calendar = source.getLongExtra(EXTRA_CALENDAR_ID, -1);
        if (!ACTION_OPEN.equals(source.getAction()) || !identity(widget, calendar).equals(source.getData())) {
            finish(); return;
        }
        target = new CalendarEditing.Target(widget, calendar);
        if (!currentTarget(this, target)) { finish(); return; }
        if (state != null && state.getInt("widget", -1) == widget && state.getLong("calendar", -1) == calendar) restore(state);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(24), dp(24), dp(24)); body.setFocusableInTouchMode(true);
        ScrollView scroll = CalendarWindowStyle.scroll(this); scroll.setFillViewport(false); scroll.addView(body);
        // The floating window is screen-bounded; the complete body, including actions, can scroll with IME.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        setContentView(scroll);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); body.addView(header);
        calendarName = label(header, "Calendar"); CalendarWindowStyle.heading(calendarName, 24);
        calendarName.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        menu = button(header, "X", this::finish);
        menu.setContentDescription("Close");
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        closeParams.gravity = Gravity.TOP; menu.setLayoutParams(closeParams);
        CalendarWindowStyle.button(menu, true);
        menu.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24); menu.setPadding(0, 0, 0, 0);
        OutsideCancel.activity(this, () -> {
            if (busy || closed || childDialog != null && childDialog.isShowing()) return;
            Button close = "agenda".equals(page) ? menu : formClose;
            if (close != null && close.isEnabled()) close.performClick();
        });
        status = label(body, ""); status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        permissionAction = button(body, "Allow read access", () -> requestCalendarPermission(permissionProblemCode));
        permissionAction.setVisibility(View.GONE);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, 0); body.addView(content);
        render();
        body.requestFocus();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (target == null || body == null) return;
        if (!currentTarget(this, target)) { finish(); return; }
        replacementPreview = null; reload(); showPendingPermissionRecovery();
        getWindow().getDecorView().post(this::fitWindow);
    }
    private void fitWindow() {
        if (closed || isFinishing()) return;
        int screen = getResources().getDisplayMetrics().widthPixels;
        getWindow().setLayout(Math.min(dp(560), Math.max(dp(160), screen - dp(32))), WindowManager.LayoutParams.WRAP_CONTENT);
    }
    @Override protected void onStop() {
        resumed = false; capture(); cancelRequest(); replacementPreview = null;
        if (childDialog != null) { childDialog.dismiss(); childDialog = null; }
        super.onStop();
    }
    @Override protected void onDestroy() {
        closed = true; cancelRequest(); if (childDialog != null) childDialog.dismiss(); super.onDestroy();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Never retarget an existing editor, including an editor with an unsaved draft.
        if (target == null || !ACTION_OPEN.equals(intent.getAction()) || !identity(target.widget, target.calendar).equals(intent.getData())
                || intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) != target.widget
                || intent.getLongExtra(EXTRA_CALENDAR_ID, -1) != target.calendar) finish();
        else if (resumed) reload();
    }
    static boolean currentTarget(Context context, CalendarEditing.Target target) {
        return target.widget != AppWidgetManager.INVALID_APPWIDGET_ID && target.calendar >= 0
                && BlockzeitWidget.active(context, target.widget) && Store.calendarEnabled(context, target.widget)
                && Store.calendarId(context, target.widget) == target.calendar
                && Store.mode(context, target.widget) == de.blockzeit.core.WidgetMode.CALENDAR;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent, String text) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(16); CalendarWindowStyle.text(view);
        view.setPadding(0, dp(6), 0, dp(6)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); view.setMinimumHeight(dp(48));
        CalendarWindowStyle.button(view, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(8);
        parent.addView(view, params); view.setOnClickListener(v -> action.run()); return view;
    }
    private EditText field(LinearLayout parent, String caption, String value, int maximum) {
        return field(parent, caption, value, maximum, true);
    }
    private EditText field(LinearLayout parent, String caption, String value, int maximum, boolean showCaption) {
        if (showCaption) label(parent, caption); EditText view = new EditText(this); view.setSingleLine(true); view.setText(value);
        view.setContentDescription(caption); view.setHint(caption); view.setMinimumHeight(dp(48)); CalendarWindowStyle.text(view);
        view.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maximum)});
        parent.addView(view); return view;
    }
    private void message(String value) {
        if (status == null) return;
        status.setText(value); status.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
        permissionAction.setVisibility(View.GONE);
    }
    private void showPermissionProblem(int code) {
        permissionProblemCode = code;
        message(code == READ ? "Allow calendar access to read events."
                : "Allow write access to save. Then select the action again.");
        permissionAction.setText(code == READ ? "Allow read access" : "Allow write access");
        permissionAction.setVisibility(View.VISIBLE);
    }
    private void loadedMessage() {
        if (permissionProblemCode != 0 && permissionGranted(permissionProblemCode)) permissionProblemCode = 0;
        if (formConflict) message("The event has changed. Reload the form; the draft was not saved.");
        else if (!access.writable) message("This calendar is read-only. Events can still be read.");
        else if (permissionProblemCode != 0) showPermissionProblem(permissionProblemCode);
        else message("");
    }
    private void cancelRequest() {
        generation++; if (request != null) request.cancel(); request = null; busy = false;
    }
    private void enabledTree(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enabledTree(group.getChildAt(i), enabled);
        }
    }
    private void controls() {
        if (content == null) return;
        enabledTree(content, !busy);
        for (View row : agendaRows) row.setEnabled(!busy && access != null && access.writable);
        if (add != null) add.setEnabled(!busy && access != null && access.writable);
        if (menu != null) menu.setEnabled(!busy);
        if (formClose != null) formClose.setEnabled(!busy);
        if (replaceAction != null) replaceAction.setEnabled(!busy && access != null && access.writable);
        if (permissionAction != null) permissionAction.setEnabled(!busy);
        if (save != null) save.setEnabled(!busy && access != null && access.writable && !formConflict
                && (editingId < 0 || original != null));
        if (repeat != null && (birthday || editingId >= 0 && !wholeSeries)) repeat.setEnabled(false);
        if (allDay != null && birthday) allDay.setEnabled(false);
        if (startTimeButton != null) startTimeButton.setEnabled(!busy && !draftAllDay);
        if (endTimeButton != null) endTimeButton.setEnabled(!busy && !draftAllDay);
    }
    private <T> void execute(CalendarRepository.Work<T> operation, Consumer<T> success) {
        if (closed || !resumed || busy) return;
        if (!currentTarget(this, target)) { finish(); return; }
        final CalendarEditing.Target fixed = target;
        final int token = ++generation;
        busy = true; controls(); message("");
        request = CalendarRepository.enqueue(this, (context, cancel) -> {
            if (!currentTarget(context, fixed)) throw new CalendarRepository.Failure("The widget or calendar assignment is no longer valid");
            CalendarEditing.validate(context, fixed, cancel);
            return operation.run(context, cancel);
        }, (value, failure) -> {
            if (closed || !resumed || token != generation) return;
            request = null; busy = false;
            if (!currentTarget(this, target)) { finish(); return; }
            if (failure != null) {
                if (!CalendarRepository.canRead(this)) showPermissionProblem(READ);
                else if (permissionProblemCode == WRITE && !CalendarRepository.canWrite(this)) showPermissionProblem(WRITE);
                else message(failure.getMessage());
                controls();
            }
            else { success.accept(value); controls(); }
        });
        if (request == null) { busy = false; message(CalendarRepository.SATURATED); controls(); }
    }
    private static final class Loaded {
        CalendarEditing.Access access; List<CalendarAgenda.Entry> entries; CalendarEditing.Event event; CalendarAgenda.Entry lastEnded; String historyProblem;
    }
    private void reload() {
        capture(); cancelRequest(); replacementPreview = null; original = null; access = null;
        List<CalendarAgenda.Entry> retained = CalendarAgenda.upcoming(agenda, System.currentTimeMillis(), ZoneId.systemDefault());
        // Exhausting a cached five is not proof that no later occurrences exist.
        agendaLoaded = agendaLoaded && (agenda.isEmpty() || !retained.isEmpty());
        agenda.clear(); agenda.addAll(retained);
        if (!CalendarRepository.canRead(this)) { agenda.clear(); lastEnded = null; agendaLoaded = false; }
        message(""); render();
        if (!CalendarRepository.canRead(this)) { showPermissionProblem(READ); return; }
        CalendarRefresh.reload(this, target.calendar);
        final CalendarEditing.Target fixed = target;
        final long eventId = editingId, occurrence = editingBegin, now = System.currentTimeMillis();
        final ZoneId zone = ZoneId.systemDefault();
        final boolean overview = "agenda".equals(page);
        execute((context, cancel) -> {
            Loaded loaded = new Loaded(); loaded.access = CalendarEditing.access(context, fixed, cancel);
            loaded.entries = CalendarRepository.nextFive(context, fixed.calendar, now, zone, cancel);
            if (overview) {
                try { loaded.lastEnded = CalendarRepository.lastEnded(context, fixed.calendar, now, zone, cancel); }
                catch (CalendarRepository.Failure failure) {
                    if (!CalendarRepository.canRead(context) || cancel.isCanceled()) throw failure;
                    // Optional history must not hide the existing five or block their editors.
                    loaded.historyProblem = failure.getMessage();
                }
            }
            if (eventId >= 0) loaded.event = CalendarEditing.load(context, fixed, eventId, occurrence, cancel);
            return loaded;
        }, loaded -> {
            access = loaded.access; lastEnded = loaded.lastEnded; agendaLoaded = true; agenda.clear();
            for (CalendarAgenda.Entry entry : loaded.entries) { if (agenda.size() == 5) break; agenda.add(entry); }
            boolean selectedExists = false;
            for (CalendarAgenda.Entry entry : agenda) if (entry.id == selectedId && entry.begin == selectedBegin) selectedExists = true;
            if (!selectedExists) { selectedId = -1; selectedBegin = -1; }
            if (eventId >= 0) {
                original = loaded.event;
                formConflict = !baseline.isEmpty() && !baseline.equals(fingerprint(original));
                if (baseline.isEmpty()) baseline = fingerprint(original);
            }
            calendarName.setText(access.name); render(); loadedMessage();
            if (loaded.historyProblem != null) message(status.getText().toString()
                    + (status.getText().length() == 0 ? "" : "\n") + "Last ended event: " + loaded.historyProblem);
        });
    }
    private void render() {
        title = null; search = null; replacement = null; allDay = null; repeat = null; save = null;
        add = null; replaceAction = null; startTimeButton = null; endTimeButton = null;
        menu.setVisibility("agenda".equals(page) ? View.VISIBLE : View.GONE);
        if (formClose != null) {
            ((LinearLayout)calendarName.getParent()).removeView(formClose); formClose = null;
        }
        content.removeAllViews(); agendaRows.clear();
        if ("form".equals(page)) renderForm(); else if ("replace".equals(page)) renderReplace(); else renderAgenda();
        controls();
    }
    private void renderAgenda() {
        ZoneId zone = ZoneId.systemDefault();
        if (lastEnded != null) renderLastEnded(zone);
        List<CalendarAgenda.Entry> visible = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (CalendarAgenda.Entry entry : agenda)
            if (entry.end >= entry.begin && entry.active(now, zone)) visible.add(entry);
        if (visible.isEmpty() && agendaLoaded) label(content, "No upcoming events.");
        for (CalendarAgenda.Entry entry : visible) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            row.setMinimumHeight(dp(40)); row.setPadding(dp(4), dp(6), dp(4), dp(6));
            TextView date = new TextView(this); date.setTextSize(16); CalendarWindowStyle.text(date);
            date.setText(entry.dateText(zone)); date.setSingleLine(true);
            row.addView(date, new LinearLayout.LayoutParams(-2, -2));
            TextView eventTitle = new TextView(this); eventTitle.setTextSize(16); CalendarWindowStyle.text(eventTitle);
            eventTitle.setText(entry.title);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
            titleParams.leftMargin = dp(12); row.addView(eventTitle, titleParams);
            TextView editHint = new TextView(this); editHint.setTextSize(20); CalendarWindowStyle.text(editHint);
            editHint.setText("✎"); editHint.setSingleLine(true);
            editHint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(-2, -2);
            editParams.leftMargin = dp(12); row.addView(editHint, editParams);
            row.setContentDescription(entry.dateText(zone) + " · " + entry.title + " · Edit");
            row.setFocusable(true);
            android.util.TypedValue background = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true))
                row.setBackgroundResource(background.resourceId);
            content.addView(row, new LinearLayout.LayoutParams(-1, -2)); agendaRows.add(row);
            row.setOnClickListener(v -> { selectedId = entry.id; selectedBegin = entry.begin; editSelected(); });
            row.setEnabled(!busy && access != null && access.writable);
            View divider = new View(this);
            android.util.TypedValue line = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.listDivider, line, true)) divider.setBackgroundResource(line.resourceId);
            content.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        add = button(content, "Add", this::chooseNew);
        ((LinearLayout.LayoutParams)add.getLayoutParams()).topMargin += dp(12);
        replaceAction = button(content, "Replace text", () -> {
            page = "replace"; replacementPreview = null; render();
        });
    }
    /** Informational only: no editor identity, action listener, pencil or selection target. */
    private void renderLastEnded(ZoneId zone) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(dp(40)); row.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView date = new TextView(this); date.setTextSize(16); date.setTextColor(0xffaeb2ba);
        date.setText(lastEnded.dateText(zone)); date.setSingleLine(true);
        date.setPaintFlags(date.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        row.addView(date, new LinearLayout.LayoutParams(-2, -2));
        TextView eventTitle = new TextView(this); eventTitle.setTextSize(16); eventTitle.setTextColor(0xffaeb2ba);
        eventTitle.setText(lastEnded.title);
        eventTitle.setPaintFlags(eventTitle.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.leftMargin = dp(12); row.addView(eventTitle, params);
        row.setContentDescription(lastEnded.dateText(zone) + " · " + lastEnded.title + " · Last ended event");
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        android.util.TypedValue line = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.listDivider, line, true)) divider.setBackgroundResource(line.resourceId);
        content.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    private boolean editableCalendar() {
        if (access == null || !access.writable || !currentTarget(this, target)) {
            message("This calendar is not writable or needs to be reloaded."); return false;
        }
        return true;
    }
    private boolean permissionGranted(int code) {
        return code == READ ? CalendarRepository.canRead(this) : CalendarRepository.canWrite(this);
    }
    private String permissionName(int code) {
        return code == READ ? Manifest.permission.READ_CALENDAR : Manifest.permission.WRITE_CALENDAR;
    }
    /** Problem-only repair never remembers an editor/save command. The provider lane
     * still revalidates the immutable widget/calendar binding, permissions and ACL. */
    private void requestCalendarPermission(int code) {
        if (closed || !resumed || isFinishing()) return;
        if (!currentTarget(this, target)) { finish(); return; }
        if (code != READ && code != WRITE) return;
        if (permissionGranted(code)) { permissionProblemCode = 0; reload(); return; }
        showPermissionProblem(code);
        boolean rationale = shouldShowRequestPermissionRationale(permissionName(code));
        if ((code == READ ? readPermissionDenied : writePermissionDenied) && !rationale) {
            showPermissionSettings(code); return;
        }
        if (rationale) {
            childDialog = CalendarWindowStyle.showWithClose(new AlertDialog.Builder(this).setTitle(code == READ ? "Allow read access" : "Allow write access")
                    .setMessage(code == READ ? "Blockzeit needs read access to read the selected calendar and its events."
                            : "Blockzeit needs write access to save explicitly confirmed changes in the selected calendar. Read-only calendars remain protected.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Continue", (dialog, which) -> askCalendarPermission(code)).create());
        } else askCalendarPermission(code);
    }
    private void askCalendarPermission(int code) {
        if (closed || !resumed || isFinishing()) return;
        if (!currentTarget(this, target)) { finish(); return; }
        // Request only the individual declared permission; never assume its group partner was granted.
        showPermissionProblem(code);
        requestPermissions(new String[]{permissionName(code)}, code);
    }
    private void showPermissionSettings(int code) {
        if (closed || !resumed || isFinishing()) return;
        if (!currentTarget(this, target)) { finish(); return; }
        childDialog = CalendarWindowStyle.showWithClose(new AlertDialog.Builder(this).setTitle(code == READ ? "Allow read access" : "Allow write access")
                .setMessage("Allow access in Android app settings under Permissions → Calendar. "
                        + "Then select the desired action again. Nothing will be saved automatically.")
                .setNegativeButton("Back", null).setPositiveButton("Android settings", (dialog, which) -> {
                    if (closed || !resumed || isFinishing()) return;
                    if (!currentTarget(this, target)) { finish(); return; }
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    } catch (ActivityNotFoundException | SecurityException unavailable) {
                        message("Could not open Android app settings. Open Blockzeit in Android settings under Apps → Permissions.");
                    }
                }).create());
    }
    private void showPendingPermissionRecovery() {
        if (!resumed || permissionRecoveryCode == 0) return;
        int code = permissionRecoveryCode; permissionRecoveryCode = 0;
        if (!permissionGranted(code) && !shouldShowRequestPermissionRationale(permissionName(code))) showPermissionSettings(code);
    }
    /** A permission result NEVER resumes a pending save, replacement, or form-open operation. */
    private boolean ensureWrite() {
        if (access == null || !access.writable) { message("This calendar is not writable or needs to be reloaded."); return false; }
        if (!CalendarRepository.canRead(this)) { showPermissionProblem(READ); return false; }
        if (!CalendarRepository.canWrite(this)) {
            requestCalendarPermission(WRITE); return false;
        }
        return true;
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != READ && code != WRITE || closed || isFinishing()) return;
        if (!currentTarget(this, target)) { finish(); return; }
        boolean granted = permissionGranted(code);
        permissionProblemCode = granted ? 0 : code;
        if (code == READ) readPermissionDenied = !granted && permissions.length > 0 && results.length > 0;
        else writePermissionDenied = !granted && permissions.length > 0 && results.length > 0;
        // Package state is authoritative, including group side effects, partial grants and revocation.
        // Empty arrays mean interruption, not proof of permanent denial. No pending action is kept.
        permissionRecoveryCode = permissions.length > 0 && results.length > 0 && !permissionGranted(code)
                && !shouldShowRequestPermissionRationale(permissionName(code)) ? code : 0;
        if (resumed) reload();
        if (!granted) showPermissionProblem(!CalendarRepository.canRead(this) ? READ : code);
        showPendingPermissionRecovery();
    }
    private void editSelected() {
        if (selectedId < 0 || !editableCalendar()) return;
        final long id = selectedId, begin = selectedBegin; final CalendarEditing.Target fixed = target;
        execute((context, cancel) -> CalendarEditing.load(context, fixed, id, begin, cancel), event -> {
            if (event.recurring) {
                childDialog = CalendarWindowStyle.showWithClose(new AlertDialog.Builder(this).setTitle("Edit recurring event")
                        .setItems(new String[]{"Only this occurrence", "Entire series"}, (dialog, choice) -> openEvent(event, begin, choice == 1))
                        .setNegativeButton("Cancel", null).create());
            } else openEvent(event, begin, false);
        });
    }
    private void openEvent(CalendarEditing.Event event, long occurrence, boolean series) {
        if (closed || !resumed || !currentTarget(this, target)) return;
        final ZoneId zone;
        try { zone = ZoneId.of(event.allDay ? "UTC" : validZone(event.zone)); }
        catch (RuntimeException invalid) { message("Unknown event time zone. The event cannot be edited safely."); return; }
        original = event; editingId = event.id; editingBegin = occurrence; wholeSeries = series;
        baseline = fingerprint(event); formConflict = false; birthday = false;
        draftTitle = event.title == null ? "" : event.title; draftAllDay = event.allDay; draftRepeat = "KEEP";
        draftZone = event.allDay ? "UTC" : validZone(event.zone);
        startDate = Instant.ofEpochMilli(event.start).atZone(zone).toLocalDate();
        endDate = Instant.ofEpochMilli(event.end).atZone(zone).toLocalDate();
        if (draftAllDay) endDate = endDate.minusDays(1);
        startTime = Instant.ofEpochMilli(event.start).atZone(zone).toLocalTime();
        endTime = Instant.ofEpochMilli(event.end).atZone(zone).toLocalTime();
        page = "form"; render(); message("");
    }
    private static String validZone(String candidate) {
        // Missing provider timezone is shown explicitly as the device timezone. Invalid IDs block editing.
        if (candidate == null || candidate.isEmpty()) return ZoneId.systemDefault().getId();
        return candidate;
    }
    private void chooseNew() {
        if (!editableCalendar()) return;
        childDialog = CalendarWindowStyle.showWithClose(new AlertDialog.Builder(this).setTitle("Add")
                .setItems(new String[]{"Event", "Birthday"}, (dialog, choice) -> {
                    editingId = -1; editingBegin = -1; original = null; baseline = ""; formConflict = false; wholeSeries = false;
                    birthday = choice == 1; draftTitle = ""; draftAllDay = birthday; draftRepeat = birthday ? "YEARLY" : "NONE";
                    draftZone = birthday ? "UTC" : access.zone;
                    LocalDateTime next = LocalDateTime.now(ZoneId.of(draftZone)).plusHours(1).withMinute(0).withSecond(0).withNano(0);
                    startDate = next.toLocalDate(); endDate = birthday ? startDate : next.plusHours(1).toLocalDate();
                    startTime = next.toLocalTime(); endTime = next.plusHours(1).toLocalTime(); page = "form"; render();
                }).setNegativeButton("Cancel", null).create());
        // Only Add's two native choices: preserve row chrome/behavior, inset their text.
        android.widget.ListView choices = childDialog.getListView();
        choices.setAdapter(new android.widget.HeaderViewListAdapter(null, null, choices.getAdapter()) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                View row = super.getView(position, recycled, parent);
                android.graphics.Insets insets = row.getBackground().getOpticalInsets();
                row.setPadding(insets.left + dp(13), row.getPaddingTop(),
                        insets.right + dp(13), row.getPaddingBottom());
                return row;
            }
        });
        int gap = dp(8);
        android.view.ViewGroup.LayoutParams choiceParams = choices.getLayoutParams();
        choiceParams.height += gap - choices.getDividerHeight();
        choices.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        choices.setDividerHeight(gap);
        choices.setLayoutParams(choiceParams);
    }
    private void renderForm() {
        formTitle(editingId < 0 ? birthday ? "Add birthday" : "Add event"
                : original != null && original.recurring ? wholeSeries ? "Edit entire series" : "Edit only this occurrence" : "Edit event");
        title = field(content, "Title", draftTitle, 4096);
        allDay = new CheckBox(this); allDay.setText("All day"); allDay.setMinimumHeight(dp(48));
        allDay.setChecked(draftAllDay); CalendarWindowStyle.text(allDay); content.addView(allDay);
        allDay.setOnCheckedChangeListener((view, checked) -> { capture(); draftAllDay = checked; render(); });
        formGroup("Start");
        startDateButton = button(content, "Date · " + DATE.format(startDate), () -> pickDate(true));
        startTimeButton = button(content, "Time · " + TIME.format(startTime), () -> pickTime(true));
        InputStyle.selection(startDateButton); InputStyle.selection(startTimeButton);
        // Match the full-height value buttons while retaining the title's native optical insets.
        android.graphics.Insets titleInsets = title.getBackground().getOpticalInsets();
        title.setMinimumHeight(startDateButton.getMinimumHeight() + titleInsets.top + titleInsets.bottom);
        // Let only the title's painted border reach the full-width date/time control edges.
        LinearLayout.LayoutParams titleParams = (LinearLayout.LayoutParams) title.getLayoutParams();
        titleParams.leftMargin = -titleInsets.left;
        titleParams.rightMargin = -titleInsets.right;
        title.setLayoutParams(titleParams);
        startDateButton.setContentDescription("Start: " + DATE.format(startDate));
        startTimeButton.setContentDescription("Start: " + TIME.format(startTime));
        formGroup(draftAllDay ? "End · last day" : "End");
        endDateButton = button(content, "Date · " + DATE.format(endDate), () -> pickDate(false));
        endTimeButton = button(content, "Time · " + TIME.format(endTime), () -> pickTime(false));
        InputStyle.selection(endDateButton); InputStyle.selection(endTimeButton);
        endDateButton.setContentDescription("End: " + DATE.format(endDate));
        endTimeButton.setContentDescription("End: " + TIME.format(endTime));
        TextView zone = label(content, "Time zone · " + (draftAllDay ? "UTC" : draftZone)); zone.setTextSize(15);
        label(content, "Repeat"); repeat = new Spinner(this); repeat.setMinimumHeight(dp(48));
        List<String> labels = new ArrayList<>(); if (editingId >= 0) labels.add("Unchanged");
        java.util.Collections.addAll(labels, REPEAT_LABELS);
        repeat.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                View view = super.getView(position, recycled, parent); CalendarWindowStyle.text((TextView)view); view.setEnabled(parent.isEnabled()); return view;
            }
            @Override public View getDropDownView(int position, View recycled, android.view.ViewGroup parent) {
                View view = super.getDropDownView(position, recycled, parent); CalendarWindowStyle.text((TextView)view);
                view.setMinimumHeight(dp(48)); InputStyle.choice(view,parent); return view;
            }
        });
        repeat.setPopupBackgroundDrawable(CalendarWindowStyle.surface(this));
        repeat.setBackgroundTintList(android.content.res.ColorStateList.valueOf(InfoDialog.accentColor(this)));
        InputStyle.spinner(repeat);
        int index = 0;
        if (!"KEEP".equals(draftRepeat)) for (int i = 0; i < REPEATS.length; i++) if (REPEATS[i].equals(draftRepeat)) index = i + (editingId >= 0 ? 1 : 0);
        repeat.setSelection(index); content.addView(repeat);
        if (birthday && startDate.getMonthValue() == 2 && startDate.getDayOfMonth() == 29)
            label(content, "February 29: leap years only.");
        save = button(content, "Save", this::saveForm);
        if (editingId >= 0) button(content, "Reload form", () -> {
            if (original == null) { reload(); return; }
            openEvent(original, editingBegin, wholeSeries);
        });
    }
    private void formTitle(String text) {
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        TextView heading = label(header, text); CalendarWindowStyle.heading(heading, 18);
        heading.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        // The calendar name remains the window's first title. Put the form's existing
        // cancel action there, not beside the lower Add/Replace section heading.
        header.setMinimumHeight(dp(48));
        LinearLayout windowTitle = (LinearLayout)calendarName.getParent();
        formClose = button(windowTitle, "X", this::backToAgenda); formClose.setContentDescription("Cancel");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.gravity = Gravity.TOP; formClose.setLayoutParams(params);
        CalendarWindowStyle.closeIcon(formClose);
    }
    private void formGroup(String text) {
        TextView heading = label(content, text); CalendarWindowStyle.heading(heading, 18);
        heading.setPadding(0, dp(16), 0, 0);
    }
    private void pickDate(boolean start) {
        capture(); LocalDate current = start ? startDate : endDate;
        DatePickerDialog dialog = new DatePickerDialog(this, (picker, year, month, day) -> {
            if (start) startDate = LocalDate.of(year, month + 1, day); else endDate = LocalDate.of(year, month + 1, day);
            render();
        }, current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth());
        // Android initializes the date header from Locale.getDefault(), not its English context.
        // Public configuration dispatch refreshes those formatters without changing global locale.
        dialog.getDatePicker().dispatchConfigurationChanged(new Configuration(getResources().getConfiguration()));
        childDialog = CalendarWindowStyle.showWithClose(dialog);
    }
    private void pickTime(boolean start) {
        capture(); LocalTime current = start ? startTime : endTime;
        childDialog = CalendarWindowStyle.showWithClose(new TimePickerDialog(this, (picker, hour, minute) -> {
            if (start) startTime = LocalTime.of(hour, minute); else endTime = LocalTime.of(hour, minute); render();
        }, current.getHour(), current.getMinute(), android.text.format.DateFormat.is24HourFormat(this)));
    }
    /** Reject gaps and overlaps instead of silently applying java.time's default DST resolution. */
    static long strictInstant(LocalDate date, LocalTime time, ZoneId zone) {
        LocalDateTime local = LocalDateTime.of(date, time);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) throw new IllegalArgumentException("This time does not exist due to a daylight saving transition. Choose another time.");
        if (offsets.size() != 1) throw new IllegalArgumentException("This time is ambiguous due to a daylight saving transition. Choose another time.");
        return local.toInstant(offsets.get(0)).toEpochMilli();
    }
    private void saveForm() {
        capture(); if (!ensureWrite() || formConflict || editingId >= 0 && original == null) return;
        final CalendarEditing.Draft draft;
        try {
            if (CalendarAgenda.singleLine(draftTitle).isEmpty()) throw new IllegalArgumentException("Enter a title.");
            long start, end;
            if (draftAllDay) {
                start = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                end = endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            } else {
                ZoneId zone = ZoneId.of(draftZone);
                start = formInstant(startDate, startTime, zone, original, true);
                end = formInstant(endDate, endTime, zone, original, false);
            }
            if (end < start || end == start && (draftAllDay || original == null || original.start != original.end))
                throw new IllegalArgumentException("The end must be after the start.");
            draft = new CalendarEditing.Draft(draftTitle, start, end, draftAllDay, draftAllDay ? "UTC" : draftZone, draftRepeat);
        } catch (RuntimeException invalid) { message(invalid.getMessage()); return; }
        final CalendarEditing.Target fixed = target; final CalendarEditing.Event before = original; final boolean series = wholeSeries;
        execute((context, cancel) -> { CalendarEditing.save(context, fixed, before, draft, series, cancel); return Boolean.TRUE; }, ignored -> {
            page = "agenda"; editingId = -1; original = null; baseline = ""; render(); reload(); refreshWidget();
        });
    }
    private void renderReplace() {
        formTitle("Replace text");
        label(content, "Only event titles in the calendar.");
        search = field(content, "Search", searchText, 4096, false); replacement = field(content, "Replace with", replacementText, 4096, false);
        Button previewButton = button(content, "Create preview", this::previewReplacement);
        for (EditText input : new EditText[]{search, replacement}) {
            android.graphics.Insets inputInsets = input.getBackground().getOpticalInsets();
            input.setMinimumHeight(previewButton.getMinimumHeight() + inputInsets.top + inputInsets.bottom);
        }
        if (replacementPreview != null) {
            label(content, replacementPreview.matches.size() + " matches · " + replacementPreview.examined + " events checked");
            for (CalendarEditing.Replacement match : replacementPreview.matches) {
                label(content, "Before: " + match.before + "\nAfter: " + match.after);
            }
            if (!replacementPreview.matches.isEmpty()) button(content, "Confirm · Replace text", this::confirmReplacement);
        }
    }
    private void previewReplacement() {
        capture(); replacementPreview = null; render();
        if (!CalendarRepository.canRead(this)) {
            requestCalendarPermission(READ); return;
        }
        if (searchText.isEmpty()) { message("Enter search text."); return; }
        final String needle = searchText, after = replacementText; final CalendarEditing.Target fixed = target;
        execute((context, cancel) -> CalendarEditing.preview(context, fixed, needle, after, cancel), preview -> {
            replacementPreview = preview; render(); message("Preview – nothing replaced yet. Review all matches before confirming.");
        });
    }
    private void confirmReplacement() {
        String oldSearch = searchText, oldReplacement = replacementText; capture();
        if (!oldSearch.equals(searchText) || !oldReplacement.equals(replacementText)) {
            replacementPreview = null; render(); message("Text changed. Create a new preview."); return;
        }
        if (replacementPreview == null || replacementPreview.matches.isEmpty() || !ensureWrite()) return;
        final CalendarEditing.Preview reviewed = replacementPreview; final CalendarEditing.Target fixed = target;
        childDialog = CalendarWindowStyle.showWithClose(new AlertDialog.Builder(this).setTitle("Confirm text replacement")
                .setMessage("Change the " + reviewed.matches.size() + " displayed event titles?")
                .setNegativeButton("Cancel", null).setPositiveButton("Confirm", (dialog, which) -> {
                    replacementPreview = null; render();
                    execute((context, cancel) -> { CalendarEditing.replace(context, fixed, reviewed, cancel); return Boolean.TRUE; }, ignored -> {
                        render(); refreshWidget(); message("");
                    });
                }).create());
    }
    private void refreshWidget() {
        if (currentTarget(this, target)) {
            CalendarRefresh.request(this, target.widget); BlockzeitWidget.render(this, target.widget);
        }
    }
    private void backToAgenda() {
        capture(); cancelRequest(); page = "agenda"; editingId = -1; editingBegin = -1; original = null;
        baseline = ""; formConflict = false; replacementPreview = null; permissionProblemCode = 0; render(); reload();
    }
    @Override public void onBackPressed() { if (!"agenda".equals(page)) backToAgenda(); else super.onBackPressed(); }
    private void capture() {
        if (restoringForm) return;
        if (title != null) draftTitle = title.getText().toString();
        if (allDay != null) draftAllDay = allDay.isChecked();
        if (repeat != null) {
            int selected = repeat.getSelectedItemPosition();
            draftRepeat = editingId >= 0 && selected == 0 ? "KEEP" : REPEATS[Math.max(0, Math.min(4, selected - (editingId >= 0 ? 1 : 0)))];
        }
        if (search != null) searchText = search.getText().toString();
        if (replacement != null) replacementText = replacement.getText().toString();
    }
    /** Unchanged fields retain the actual original offset, seconds and milliseconds, even in a DST overlap. */
    static long formInstant(LocalDate date, LocalTime time, ZoneId zone, CalendarEditing.Event original, boolean start) {
        if (original != null && !original.allDay && zone.getId().equals(original.zone)) {
            long instant = start ? original.start : original.end;
            java.time.ZonedDateTime previous = Instant.ofEpochMilli(instant).atZone(zone);
            if (previous.toLocalDate().equals(date) && previous.toLocalTime().equals(time)) return instant;
        }
        return strictInstant(date, time, zone);
    }
    static String fingerprint(CalendarEditing.Event event) {
        if (event == null) return "";
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            frame(digest, Long.toString(event.start)); frame(digest, Long.toString(event.end));
            for (String key : new java.util.TreeSet<>(event.snapshot.keySet())) {
                Object value = event.snapshot.get(key);
                frame(digest, key); frame(digest, value == null ? "null" : value.getClass().getName());
                frame(digest, value == null ? "" : value.toString());
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void frame(java.security.MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        capture(); state.putInt("widget", target == null ? -1 : target.widget); state.putLong("calendar", target == null ? -1 : target.calendar);
        state.putString("page", page); state.putLong("selectedId", selectedId); state.putLong("selectedBegin", selectedBegin);
        state.putLong("editingId", editingId); state.putLong("editingBegin", editingBegin); state.putBoolean("series", wholeSeries);
        state.putBoolean("birthday", birthday); state.putBoolean("allDay", draftAllDay); state.putString("title", draftTitle);
        state.putString("repeat", draftRepeat); state.putString("zone", draftZone); state.putString("baseline", baseline);
        if (startDate != null) {
            state.putString("startDate", startDate.toString()); state.putString("endDate", endDate.toString());
            state.putString("startTime", startTime.toString()); state.putString("endTime", endTime.toString());
        }
        state.putString("search", searchText); state.putString("replacement", replacementText);
        state.putBoolean("readPermissionDenied", readPermissionDenied); state.putBoolean("writePermissionDenied", writePermissionDenied);
        state.putInt("permissionProblemCode", permissionProblemCode); state.putInt("permissionRecoveryCode", permissionRecoveryCode);
        // No Event/Preview or pending save is persisted; snapshot must be read again from the provider.
        super.onSaveInstanceState(state);
    }
    private void restore(Bundle state) {
        restoringForm = true;
        readPermissionDenied = state.getBoolean("readPermissionDenied"); writePermissionDenied = state.getBoolean("writePermissionDenied");
        int problem = state.getInt("permissionProblemCode");
        permissionProblemCode = problem == READ || problem == WRITE ? problem : 0;
        int recovery = state.getInt("permissionRecoveryCode");
        permissionRecoveryCode = recovery == READ || recovery == WRITE ? recovery : 0;
        page = state.getString("page", "agenda"); selectedId = state.getLong("selectedId", -1); selectedBegin = state.getLong("selectedBegin", -1);
        editingId = state.getLong("editingId", -1); editingBegin = state.getLong("editingBegin", -1); wholeSeries = state.getBoolean("series");
        birthday = state.getBoolean("birthday"); draftAllDay = state.getBoolean("allDay"); draftTitle = state.getString("title", "");
        draftRepeat = state.getString("repeat", "NONE"); draftZone = state.getString("zone", ZoneId.systemDefault().getId());
        baseline = state.getString("baseline", ""); searchText = state.getString("search", ""); replacementText = state.getString("replacement", "");
        if ("form".equals(page)) try {
            startDate = LocalDate.parse(state.getString("startDate", "")); endDate = LocalDate.parse(state.getString("endDate", ""));
            startTime = LocalTime.parse(state.getString("startTime", "")); endTime = LocalTime.parse(state.getString("endTime", ""));
        } catch (RuntimeException invalid) { page = "agenda"; editingId = -1; baseline = ""; }
        restoringForm = false;
    }
}
