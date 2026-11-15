package de.blockzeit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** One appearance draft. Dialog Apply updates the draft; only the page's Save persists it. */
final class YearColorPicker {
    private final Activity activity;
    private final String title;
    private final int defaultColor;
    private final Button button;
    private int color;
    private AlertDialog dialog;
    private EditText hex;

    YearColorPicker(Activity activity, LinearLayout parent, String title) {
        this(activity, parent, title, "Remaining days".equals(title) ? YearColors.REMAINING : YearColors.ELAPSED);
    }
    YearColorPicker(Activity activity, LinearLayout parent, String title, int defaultColor) {
        this.activity = activity;
        this.title = title;
        this.defaultColor = defaultColor;
        button = new Button(activity);
        button.setText(title);
        SettingsControls.button(button);
        InputStyle.selection(button);
        SettingsControls.add(parent, button);
        button.setOnClickListener(v -> show(null));
    }
    int color() { return color; }
    void setColor(int value) {
        dismiss();
        color = value;
        GradientDrawable swatch = new GradientDrawable();
        swatch.setColor(previewColor(color));
        swatch.setStroke(dp(1), 0xffa0a3a8);
        swatch.setCornerRadius(dp(3));
        swatch.setBounds(0, 0, dp(28), dp(28));
        button.setCompoundDrawablesRelative(swatch, null, null, null);
        button.setCompoundDrawablePadding(dp(12));
        button.setContentDescription(title + ", " + YearColors.hex(color) + ", Edit color");
    }
    void dismiss() {
        if (dialog != null) { dialog.dismiss(); dialog = null; hex = null; }
    }
    void saveState(Bundle state, String key) {
        state.putInt(key, color);
        if (dialog != null && dialog.isShowing()) state.putString(key + ".hex", hex.getText().toString());
    }
    void restoreState(Bundle state, String key) {
        setColor(state.getInt(key, color));
        if (state.containsKey(key + ".hex")) show(state.getString(key + ".hex"));
    }
    private void show(String restored) {
        if (dialog != null && dialog.isShowing()) return;
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), dp(8));
        body.setFocusableInTouchMode(true);
        TextView preview = new TextView(activity);
        preview.setGravity(android.view.Gravity.CENTER);
        preview.setTextSize(17);
        preview.setMinHeight(dp(56));
        body.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        TextView label = label("Hex (RRGGBB or AARRGGBB)");
        body.addView(label);
        hex = new EditText(activity);
        hex.setSingleLine(true);
        SettingsControls.input(hex);
        InputStyle.dialogField(hex);
        // Keep the shared action minimum visible inside the native EditText insets.
        android.graphics.Insets hexInsets = hex.getBackground().getOpticalInsets();
        hex.setMinimumHeight(hex.getMinimumHeight() + hexInsets.top + hexInsets.bottom);
        hex.setHint("#AARRGGBB");
        hex.setContentDescription(title + " hex color");
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        hex.setSelectAllOnFocus(true);
        label.setLabelFor(android.view.View.generateViewId());
        hex.setId(label.getLabelFor());
        SettingsControls.add(body, hex);
        SeekBar[] channels = new SeekBar[3];
        TextView[] labels = new TextView[3];
        String[] names = {"Red", "Green", "Blue"};
        int[] candidate = {color};
        Runnable update = () -> {
            int c = candidate[0], background = previewColor(c);
            preview.setBackgroundColor(background);
            preview.setTextColor(luminance(background) > .179 ? Color.BLACK : Color.WHITE);
            preview.setText(YearColors.hex(c));
            preview.setContentDescription(title + " preview, " + YearColors.hex(c));
            int[] values = {Color.red(c), Color.green(c), Color.blue(c)};
            for (int i = 0; i < 3; i++) {
                channels[i].setProgress(values[i]);
                labels[i].setText(names[i] + ": " + values[i]);
            }
        };
        for (int i = 0; i < 3; i++) {
            final int channel = i;
            labels[i] = label(names[i]); body.addView(labels[i]);
            channels[i] = new SeekBar(activity); channels[i].setMax(255);
            channels[i].setMinimumHeight(dp(48));
            InputStyle.dialogField(channels[i]);
            channels[i].setContentDescription(title + " " + names[i]);
            body.addView(channels[i], new LinearLayout.LayoutParams(-1, dp(48)));
            channels[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    int shift = (2 - channel) * 8;
                    candidate[0] = (candidate[0] & ~(255 << shift)) | (progress << shift);
                    hex.setText(YearColors.hex(candidate[0]));
                    hex.setError(null);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
        }
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                Integer parsed = YearColors.parse(s.toString());
                if (parsed != null) { candidate[0] = parsed; hex.setError(null); update.run(); }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        hex.setText(restored == null ? YearColors.hex(color) : restored);
        update.run();
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(24), dp(8), dp(24), 0);
        TextView headingTitle = new TextView(activity);
        headingTitle.setText(title);
        headingTitle.setTextSize(20);
        SettingsControls.foreground(headingTitle);
        headingTitle.setAccessibilityHeading(true);
        heading.addView(headingTitle, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton reset = new ImageButton(activity, null, android.R.attr.borderlessButtonStyle);
        reset.setImageResource(android.R.drawable.ic_menu_revert);
        reset.setContentDescription("Reset color");
        reset.setMinimumWidth(dp(48));
        reset.setMinimumHeight(dp(48));
        reset.setPadding(dp(12), dp(12), dp(12), dp(12));
        TypedArray tint = activity.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try { reset.setImageTintList(tint.getColorStateList(0)); }
        finally { tint.recycle(); }
        reset.setOnClickListener(v -> {
            hex.setText(YearColors.hex(defaultColor));
            hex.setError(null);
        });
        heading.addView(reset, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog = new AlertDialog.Builder(activity).setTitle(title).setCustomTitle(heading).setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Apply", null).create();
        dialog.setOnShowListener(ignored -> {
            SettingsControls.button(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            SettingsControls.button(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Integer parsed = YearColors.parse(hex.getText().toString());
                if (parsed == null) {
                    hex.setError("Enter 6 or 8 hexadecimal digits");
                    hex.requestFocus();
                    return;
                }
                setColor(parsed);
            });
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        OutsideCancel.negative(dialog);
        body.requestFocus();
    }
    private TextView label(String text) {
        TextView label = new TextView(activity);
        label.setText(text); label.setTextSize(15); SettingsControls.foreground(label);
        label.setPadding(0, dp(8), 0, 0);
        return label;
    }
    // Show ARGB on the ring's dark background, so transparent colors also have a useful preview.
    private static int previewColor(int color) {
        int alpha = Color.alpha(color);
        return Color.rgb((Color.red(color) * alpha + 28 * (255 - alpha) + 127) / 255,
                (Color.green(color) * alpha + 31 * (255 - alpha) + 127) / 255,
                (Color.blue(color) * alpha + 36 * (255 - alpha) + 127) / 255);
    }
    private static double luminance(int color) {
        return .2126 * linear(Color.red(color)) + .7152 * linear(Color.green(color)) + .0722 * linear(Color.blue(color));
    }
    private static double linear(int value) {
        double c = value / 255.0;
        return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
    }
    private int dp(int value) { return Math.round(activity.getResources().getDisplayMetrics().density * value); }
}
