package de.blockzeit;

import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Adapter;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SpinnerAdapter;
import android.widget.WrapperListAdapter;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TimePicker;

/** App-owned input chrome only. The original drawable still owns native interaction and metrics. */
final class InputStyle {
    static final int FILL = 0xff343941, BORDER = 0xff878b92;
    private InputStyle() { }

    static void field(View view) {
        if (view.getBackground() instanceof Boundary) return;
        // setBackground may copy drawable padding and intrinsic minima. Preserve all existing values,
        // including relative padding, rather than compensating with layout changes or new wrappers.
        int left=view.getPaddingLeft(), top=view.getPaddingTop(), right=view.getPaddingRight(), bottom=view.getPaddingBottom();
        int start=view.getPaddingStart(), end=view.getPaddingEnd(); boolean relative=view.isPaddingRelative();
        int minWidth=view.getMinimumWidth(), minHeight=view.getMinimumHeight();
        view.setBackground(new Boundary(view, view.getBackground()));
        if (relative) view.setPaddingRelative(start,top,end,bottom); else view.setPadding(left,top,right,bottom);
        view.setMinimumWidth(minWidth); view.setMinimumHeight(minHeight);
    }

    /** Stroke-only override on this field instance; native chrome, fills and metrics stay intact. */
    static void borderColor(View view, int color) {
        field(view);
        Boundary boundary=(Boundary)view.getBackground(); boundary.borderColor=color; boundary.invalidateSelf();
    }

    /** App-owned text only: 12dp clear space from the inner 1dp frame, not the view edge. */
    static void textInput(EditText input) {
        field(input);
        Insets insets=input.getBackground().getOpticalInsets();
        float density=input.getResources().getDisplayMetrics().density;
        int gap=Math.round(12*density + density);
        int left=insets.left+gap, right=insets.right+gap;
        if (input.isPaddingRelative()) {
            boolean rtl=input.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            input.setPaddingRelative(rtl?right:left,input.getPaddingTop(),rtl?left:right,input.getPaddingBottom());
        } else input.setPadding(left,input.getPaddingTop(),right,input.getPaddingBottom());
    }

    /** Color editors use the native floating-dialog surface, not the darker Activity surface. */
    static void dialogField(View view) {
        field(view);
        TypedArray colors=view.getContext().obtainStyledAttributes(new int[]{android.R.attr.colorBackgroundFloating});
        int surface;
        try { surface=colors.getColor(0,0xff424242); } finally { colors.recycle(); }
        int red=android.graphics.Color.red(surface), green=android.graphics.Color.green(surface), blue=android.graphics.Color.blue(surface);
        int fill=android.graphics.Color.rgb(red+(255-red)/16,green+(255-green)/16,blue+(255-blue)/16);
        Boundary boundary=(Boundary)view.getBackground(); boundary.fills=surfaceColors(view,fill); boundary.invalidateSelf();
    }

    /** A value/choice launcher is an input, unlike Save, Search, Reset, Reload or Cancel. */
    static void selection(Button view) {
        view.setBackgroundTintList(surfaceColors(view));
        TypedArray colors=view.getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try { view.setTextColor(colors.getColorStateList(0)); } finally { colors.recycle(); }
        field(view);
    }

    static void spinner(Spinner view) {
        field(view);
        view.setPopupBackgroundDrawable(popupBackground(view,view.getPopupBackground()));
    }

    /** Shared opened surface for native spinners and selection-style command lists. */
    static Drawable popupBackground(View anchor, Drawable popup) {
        if (popup instanceof Boundary) return popup;
        if (popup!=null) { popup=popup.mutate(); popup.setTintList(surfaceColors(anchor)); }
        return new Boundary(anchor,popup);
    }

    /** Both adapter paths call this, also for rows inflated/recycled after an asynchronous update. */
    static void choice(View view) {
        field(view);
        Boundary boundary=(Boundary)view.getBackground();
        boundary.fills=choiceColors(view); boundary.invalidateSelf();
    }
    static void choice(View view, ViewGroup parent) {
        choice(view);
        // AbsListView normally draws its selector UNDER children. Filled rows must not hide it.
        if (parent instanceof ListView list) list.setDrawSelectorOnTop(true);
    }

    /** Native picker internals can be created later (keyboard/calendar year mode). No action buttons. */
    static void pickerInputs(View view) {
        if (view instanceof EditText || view instanceof CompoundButton || view instanceof NumberPicker
                || view instanceof DatePicker || view instanceof TimePicker || view instanceof SeekBar) field(view);
        // These framework controls are TextViews, not EditTexts; do not frame unrelated labels.
        if (view.getClass().getName().equals("com.android.internal.widget.NumericTextView")) field(view);
        if (view instanceof Spinner spinner) {
            spinner(spinner);
            SpinnerAdapter adapter=spinner.getAdapter();
            if (adapter!=null && !(adapter instanceof NativeChoices)) {
                int selected=spinner.getSelectedItemPosition();
                spinner.setAdapter(new NativeChoices(adapter));
                if (selected>=0) spinner.setSelection(selected);
            }
        }
        if (view instanceof ListView list && view.getClass().getName().equals("android.widget.YearPickerView")) {
            ListAdapter adapter=list.getAdapter();
            if (adapter!=null && !(adapter instanceof NativeChoices)) {
                // Keep the framework's own adapter, year mapping, click listener and stable IDs.
                int first=list.getFirstVisiblePosition(); View child=list.getChildAt(0);
                int top=child==null?0:child.getTop();
                list.setAdapter(new NativeChoices(adapter));
                list.setSelectionFromTop(first,top);
            }
            list.setDrawSelectorOnTop(true);
            for (int i=0;i<list.getChildCount();i++) choice(list.getChildAt(i),list);
        }
        if (view instanceof ViewGroup group)
            for (int i=0;i<group.getChildCount();i++) pickerInputs(group.getChildAt(i));
    }


    /** Presentation-only adapter: keep native row binding/recycling and directly forward observers.
     * The native delegate still retains and updates its original adapter (including year activation).
     */
    static final class NativeChoices extends BaseAdapter implements SpinnerAdapter, WrapperListAdapter {
        private final Adapter wrapped;
        NativeChoices(Adapter wrapped) { this.wrapped=wrapped; }
        @Override public ListAdapter getWrappedAdapter() { return wrapped instanceof ListAdapter list?list:null; }
        @Override public int getCount() { return wrapped.getCount(); }
        @Override public Object getItem(int position) { return wrapped.getItem(position); }
        @Override public long getItemId(int position) { return wrapped.getItemId(position); }
        @Override public boolean hasStableIds() { return wrapped.hasStableIds(); }
        @Override public boolean isEmpty() { return wrapped.isEmpty(); }
        @Override public int getItemViewType(int position) { return wrapped.getItemViewType(position); }
        @Override public int getViewTypeCount() { return wrapped.getViewTypeCount(); }
        @Override public boolean areAllItemsEnabled() { return !(wrapped instanceof ListAdapter list) || list.areAllItemsEnabled(); }
        @Override public boolean isEnabled(int position) { return !(wrapped instanceof ListAdapter list) || list.isEnabled(position); }
        @Override public void registerDataSetObserver(android.database.DataSetObserver observer) { wrapped.registerDataSetObserver(observer); }
        @Override public void unregisterDataSetObserver(android.database.DataSetObserver observer) { wrapped.unregisterDataSetObserver(observer); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            View row=wrapped.getView(position,recycled,parent); choice(row,parent); return row;
        }
        @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
            View row=((SpinnerAdapter)wrapped).getDropDownView(position,recycled,parent); choice(row,parent); return row;
        }
    }
    private static ColorStateList choiceColors(View view) {
        boolean bright=light(view);
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_pressed},new int[]{android.R.attr.state_focused},
                new int[]{android.R.attr.state_activated},new int[]{android.R.attr.state_selected},new int[]{}},
                bright?new int[]{0xffeeeeee,0xffc5cbd3,0xffd1d7df,0xffdbe0e6,0xffdbe0e6,0xfff5f5f5}
                      :new int[]{0xff30353d,0xff4c5867,0xff465362,0xff424e5d,0xff424e5d,FILL});
    }

    private static boolean light(View view) {
        TypedArray colors=view.getContext().obtainStyledAttributes(new int[]{android.R.attr.isLightTheme});
        try { return colors.getBoolean(0,false); } finally { colors.recycle(); }
    }
    private static ColorStateList surfaceColors(View view) { return surfaceColors(view,FILL); }
    private static ColorStateList surfaceColors(View view,int fill) {
        int disabled=android.graphics.Color.rgb(Math.max(0,android.graphics.Color.red(fill)-4),Math.max(0,android.graphics.Color.green(fill)-4),Math.max(0,android.graphics.Color.blue(fill)-4));
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
                new int[]{light(view)?0xffeeeeee:disabled,light(view)?0xfff5f5f5:fill});
    }

    static final class Boundary extends DrawableWrapper {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect=new RectF();
        private final float stroke, radius;
        private ColorStateList fills;
        private int alpha=255, borderColor=BORDER;
        Boundary(View view, Drawable original) {
            super(original==null ? new ColorDrawable(android.graphics.Color.TRANSPARENT) : original);
            stroke=view.getResources().getDisplayMetrics().density;
            radius=4*stroke; fills=surfaceColors(view);
        }
        @Override public void setAlpha(int value) { alpha=value; super.setAlpha(value); invalidateSelf(); }
        @Override public int getAlpha() { return alpha; }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { super.onStateChange(state); invalidateSelf(); return true; }
        @Override public int getMinimumWidth() { return getDrawable().getMinimumWidth(); }
        @Override public int getMinimumHeight() { return getDrawable().getMinimumHeight(); }
        // A wrapper's inherited ConstantState would clone only the native drawable and lose the frame.
        @Override public ConstantState getConstantState() { return null; }
        @Override public void draw(Canvas canvas) {
            Rect bounds=getBounds(); Insets inset=getOpticalInsets();
            rect.set(bounds.left+inset.left+stroke/2,bounds.top+inset.top+stroke/2,
                    bounds.right-inset.right-stroke/2,bounds.bottom-inset.bottom-stroke/2);
            paint.setStyle(Paint.Style.FILL); paint.setColor(fills.getColorForState(getState(),fills.getDefaultColor()));
            paint.setAlpha(getAlpha()); canvas.drawRoundRect(rect,radius,radius,paint);
            super.draw(canvas); // Native underline, arrow, ripple, checkbox/track remain real drawables.
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(borderColor);
            paint.setAlpha(getAlpha()); canvas.drawRoundRect(rect,radius,radius,paint);
        }
    }
}
