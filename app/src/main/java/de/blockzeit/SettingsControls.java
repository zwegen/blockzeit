package de.blockzeit;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.List;

/** Presentation only for the settings form; native states, metrics and listeners stay intact. */
final class SettingsControls {
    private SettingsControls() { }

    static void button(Button button) {
        text(button);
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setMaxLines(Integer.MAX_VALUE);
        button.setEllipsize(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(button.getContext(), 16), dp(button.getContext(), 8),
                dp(button.getContext(), 16), dp(button.getContext(), 8));
    }
    static void input(EditText input) {
        text(input);
        input.setHintTextColor(colors(input.getContext(), android.R.attr.textColorHint));
        input.setPadding(dp(input.getContext(), 12), dp(input.getContext(), 8),
                dp(input.getContext(), 12), dp(input.getContext(), 8));
        InputStyle.textInput(input);
    }
    private static void text(TextView view) {
        view.setTextSize(16);
        foreground(view);
        view.setMinHeight(dp(view.getContext(), 48));
        view.setMinimumHeight(dp(view.getContext(), 48));
    }
    static void foreground(TextView view) {
        view.setTextColor(colors(view.getContext(), android.R.attr.textColorPrimary));
    }
    static void add(LinearLayout parent, View control) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = params.bottomMargin = dp(parent.getContext(), 4);
        parent.addView(control, params);
    }
    static void spinner(Spinner spinner) {
        spinner.setMinimumHeight(dp(spinner.getContext(), 48));
        InputStyle.spinner(spinner);
    }
    static ArrayAdapter<String> choices(Context context, List<String> labels) {
        return new ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, labels) {
            private View style(View view) {
                TextView label = (TextView) view;
                text(label);
                // Both the selected field and popup must grow for long calendar/location labels.
                label.setSingleLine(false);
                label.setMaxLines(Integer.MAX_VALUE);
                label.setEllipsize(null);
                // The framework dropdown resource carries a fixed list-item height.
                // Keep its native widget but let wrapped text determine the row height.
                ViewGroup.LayoutParams params = label.getLayoutParams();
                if (params != null) { params.height = ViewGroup.LayoutParams.WRAP_CONTENT; label.setLayoutParams(params); }
                label.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
                return label;
            }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View view = style(super.getView(position, recycled, parent));
                view.setEnabled(parent.isEnabled());
                return view;
            }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                View view = style(super.getDropDownView(position, recycled, parent));
                InputStyle.choice(view,parent);
                return view;
            }
        };
    }
    private static ColorStateList colors(Context context, int attribute) {
        TypedArray values = context.obtainStyledAttributes(new int[]{attribute});
        try { return values.getColorStateList(0); }
        finally { values.recycle(); }
    }
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
