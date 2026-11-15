package de.blockzeit;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.WrapperListAdapter;
import android.widget.ScrollView;
import android.widget.TextView;

/** Calendar-only chrome, copied from the real InfoDialog surface/text/accent mechanism.
 * No settings theme, content, listeners or provider logic is changed here. */
final class CalendarWindowStyle {
    static final int SURFACE = Color.rgb(40, 44, 52);
    static final int TEXT = Color.rgb(225, 227, 230);
    static final int INK = Color.rgb(23, 26, 31);
    private CalendarWindowStyle() {}

    static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
    static GradientDrawable surface(Context context) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(SURFACE); fill.setCornerRadius(dp(context, 20)); return fill;
    }
    static void window(Window window) {
        window.setBackgroundDrawable(surface(window.getContext()));
        window.getDecorView().setClipToOutline(true);
    }
    static void text(TextView view) {
        view.setTextColor(TEXT); view.setHintTextColor(ColorStateList.valueOf(0xB3E1E3E6));
        if (view instanceof EditText input) {
            view.setBackgroundTintList(ColorStateList.valueOf(InfoDialog.accentColor(view.getContext())));
            InputStyle.textInput(input);
        }
        if (view instanceof CompoundButton) {
            ((CompoundButton)view).setButtonTintList(ColorStateList.valueOf(InfoDialog.accentColor(view.getContext())));
            InputStyle.field(view);
        }
    }
    static void heading(TextView view, int size) {
        text(view); view.setTextSize(size); view.setTypeface(null, Typeface.BOLD); view.setAccessibilityHeading(true);
    }
    static void button(Button view, boolean icon) {
        Context context = view.getContext();
        GradientDrawable fill = new GradientDrawable();
        if (icon) fill.setShape(GradientDrawable.OVAL); else fill.setCornerRadius(dp(context, 20));
        int accent = InfoDialog.accentColor(context);
        fill.setColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0x553F434B, accent}));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), fill, null));
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0x99E1E3E6, INK}));
        view.setAllCaps(false); view.setTextSize(16); view.setMinimumHeight(dp(context,48));
        view.setPadding(dp(context,16), dp(context,10), dp(context,16), dp(context,10));
    }
    /** Same 85%-screen cap as InfoDialog, also respecting the available resized/IME viewport. */
    static ScrollView scroll(Context context) {
        return new ScrollView(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int limit = Math.round(getResources().getDisplayMetrics().heightPixels * .85f);
                if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
                    limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
    }
    static <T extends AlertDialog> T show(T dialog) {
        dialog.show();
        OutsideCancel.negative(dialog);
        Window window = dialog.getWindow();
        if (window == null) return dialog;
        window(window);
        window.setGravity(Gravity.CENTER);
        window.setLayout(Math.min(dp(dialog.getContext(),560),
                Math.round(dialog.getContext().getResources().getDisplayMetrics().widthPixels * .92f)), WindowManager.LayoutParams.WRAP_CONTENT);
        tintTree(window.getDecorView());
        InputStyle.pickerInputs(window.getDecorView());
        window.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(
                () -> InputStyle.pickerInputs(window.getDecorView()));
        // AlertDialogLayout assumes zero horizontal padding when measuring custom panels.
        // An outer envelope supplies Info's 24dp inset without pushing picker content offscreen.
        View panel = named(dialog, "parentPanel");
        if (panel != null) { panel.setBackground(null); panel.setPadding(0,0,0,0); }
        for (String name : new String[]{"topPanel", "title_template", "contentPanel", "buttonPanel"}) {
            View view = named(dialog,name);
            if (view != null) view.setPadding(0, name.equals("contentPanel") ? dp(dialog.getContext(),16)
                    : 0,0,0);
        }
        ListView list = dialog.getListView();
        if (list != null && list.getAdapter() != null) {
            list.setPadding(0,0,0,0);
            list.setAdapter(new StyledChoices(list.getAdapter()));
            // ListView otherwise measures only its first row under an outer ScrollView.
            // These calendar choice lists contain two actions; preserve both real tap targets.
            fitChoices(list,Math.max(1,window.getAttributes().width-dp(dialog.getContext(),48)));
            list.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom) -> fitChoices(list,Math.max(1,right-left)));
        }
        View title = named(dialog,"alertTitle");
        if (title instanceof TextView) heading((TextView)title,24);
        View message = window.findViewById(android.R.id.message);
        if (message instanceof TextView) { ((TextView)message).setTextSize(16); message.setPadding(0,0,0,0); }
        LinearLayout actions = new LinearLayout(dialog.getContext()); actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0,dp(dialog.getContext(),12),0,0);
        // Keep the actual installed buttons/listeners/IDs. Native time-picker inline footers
        // otherwise reserve too little room for scaled labels plus the keyboard-mode toggle.
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button action = dialog.getButton(which);
            if (action != null && action.getVisibility() == View.VISIBLE) {
                ((ViewGroup)action.getParent()).removeView(action); button(action,false);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(dialog.getContext(),8);
                actions.addView(action,params);
            }
        }
        View oldActions = named(dialog,"buttonPanel"); if (oldActions != null) oldActions.setVisibility(View.GONE);
        if (panel != null && panel.getParent() instanceof ViewGroup) {
            ViewGroup host=(ViewGroup)panel.getParent(); int index=host.indexOfChild(panel);
            ViewGroup.LayoutParams params=panel.getLayoutParams(); host.removeView(panel);
            LinearLayout envelope=new LinearLayout(dialog.getContext()); envelope.setOrientation(LinearLayout.VERTICAL);
            envelope.setPadding(dp(dialog.getContext(),24),dp(dialog.getContext(),24),dp(dialog.getContext(),24),dp(dialog.getContext(),24));
            envelope.addView(panel,new LinearLayout.LayoutParams(-1,-2));
            if (actions.getChildCount()>0) envelope.addView(actions,new LinearLayout.LayoutParams(-1,-2));
            ScrollView scroll=scroll(dialog.getContext()); scroll.setFillViewport(false); scroll.addView(envelope);
            scroll.setOnApplyWindowInsetsListener((view,insets) -> {
                android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom); return insets;
            });
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            host.addView(scroll,index,params);
        }
        // Header backgrounds have public scoped style counterparts; these optional IDs also
        // cover already-inflated native views in the source-only host harness.
        for (String name : new String[]{"date_picker_header", "time_header"}) {
            View header=named(dialog,name); if (header!=null) header.setBackgroundColor(SURFACE);
        }
        return dialog;
    }
    /** Opt-in for CalendarActivity only. Keep the installed negative action/listener and
     * the actual native title (including DatePickerDialog's dynamic title updates). */
    static <T extends AlertDialog> T showWithClose(T dialog) {
        show(dialog);
        Button close = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        View panel = named(dialog, "parentPanel");
        if (close == null || panel == null || !(panel.getParent() instanceof LinearLayout envelope)) return dialog;
        CharSequence description = close.getText();
        ViewGroup oldActions = (ViewGroup)close.getParent(); oldActions.removeView(close);
        close.setText("X"); close.setContentDescription(description);
        closeIcon(close);
        LinearLayout header = new LinearLayout(dialog.getContext()); header.setGravity(Gravity.CENTER_VERTICAL);
        View top = named(dialog, "topPanel");
        View nativeTitle = named(dialog, "alertTitle");
        TextView title;
        if (top != null && top.getVisibility() == View.VISIBLE && nativeTitle instanceof TextView label) {
            title = label; ((ViewGroup)title.getParent()).removeView(title);
        } else {
            title = new TextView(dialog.getContext());
            // Native picker headers and their selected-date/time information remain untouched.
            title.setText(dialog instanceof android.app.DatePickerDialog ? "Date"
                    : dialog instanceof android.app.TimePickerDialog ? "Time" : "");
            heading(title, 24);
        }
        if (top != null) top.setVisibility(View.GONE);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(dialog.getContext(),48), dp(dialog.getContext(),48));
        closeParams.gravity = Gravity.TOP; header.addView(close, closeParams);
        envelope.addView(header, 0, new LinearLayout.LayoutParams(-1, -2));
        // No empty footer gap for choice dialogs whose only action moved into the title.
        if (oldActions.getChildCount() == 0) oldActions.setVisibility(View.GONE);
        close.setFocusableInTouchMode(true); close.requestFocus();
        return dialog;
    }
    /** Calendar's existing 48dp round X; icon size intentionally does not scale with text. */
    static void closeIcon(Button close) {
        button(close, true);
        close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24);
        close.setPadding(0, 0, 0, 0);
    }
    private static void fitChoices(ListView list, int width) {
        ListAdapter adapter=list.getAdapter(); int height=list.getPaddingTop()+list.getPaddingBottom();
        for (int i=0;i<adapter.getCount();i++) {
            View row=adapter.getView(i,null,list);
            row.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            height+=row.getMeasuredHeight();
        }
        height+=Math.max(0,adapter.getCount()-1)*list.getDividerHeight();
        ViewGroup.LayoutParams params=list.getLayoutParams();
        if (params.height!=height) { params.height=height; list.setLayoutParams(params); }
    }
    /** Choice rows are inflated/recycled after show(), so tinting only the initial tree misses them. */
    private static final class StyledChoices extends BaseAdapter implements WrapperListAdapter {
        private final ListAdapter wrapped;
        StyledChoices(ListAdapter wrapped) { this.wrapped = wrapped; }
        @Override public ListAdapter getWrappedAdapter() { return wrapped; }
        @Override public int getCount() { return wrapped.getCount(); }
        @Override public Object getItem(int position) { return wrapped.getItem(position); }
        @Override public long getItemId(int position) { return wrapped.getItemId(position); }
        @Override public boolean hasStableIds() { return wrapped.hasStableIds(); }
        @Override public boolean areAllItemsEnabled() { return wrapped.areAllItemsEnabled(); }
        @Override public boolean isEnabled(int position) { return wrapped.isEnabled(position); }
        @Override public int getItemViewType(int position) { return wrapped.getItemViewType(position); }
        @Override public int getViewTypeCount() { return wrapped.getViewTypeCount(); }
        @Override public boolean isEmpty() { return wrapped.isEmpty(); }
        @Override public void registerDataSetObserver(android.database.DataSetObserver observer) { wrapped.registerDataSetObserver(observer); }
        @Override public void unregisterDataSetObserver(android.database.DataSetObserver observer) { wrapped.unregisterDataSetObserver(observer); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            View row = wrapped.getView(position,recycled,parent); tintTree(row);
            row.setMinimumHeight(dp(row.getContext(),48)); row.setPadding(0,dp(row.getContext(),12),0,dp(row.getContext(),12));
            if (row instanceof TextView) ((TextView)row).setTextSize(16);
            InputStyle.choice(row,parent);
            return row;
        }
    }
    private static View named(AlertDialog dialog, String name) {
        int id = dialog.getContext().getResources().getIdentifier(name,"id","android");
        return id == 0 ? null : dialog.findViewById(id);
    }
    private static void tintTree(View view) {
        if (view instanceof TextView) {
            TextView label=(TextView)view; boolean stateful=label.getTextColors().isStateful(); text(label);
            if (stateful) label.setTextColor(new ColorStateList(
                    new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_activated},new int[]{android.R.attr.state_selected},new int[]{}},
                    new int[]{0x99E1E3E6,InfoDialog.accentColor(view.getContext()),InfoDialog.accentColor(view.getContext()),TEXT}));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) tintTree(group.getChildAt(i));
        }
    }
}
