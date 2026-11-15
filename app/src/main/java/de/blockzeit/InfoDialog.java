package de.blockzeit;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

/** Read-only app information: never reads or writes a settings draft. */
final class InfoDialog extends Dialog {
    private final int accent;

    InfoDialog(Context context) {
        super(context, android.R.style.Theme_Material_Dialog_NoActionBar);
        // The dialog overlay has its own accent; retain the host app's orange instead.
        accent = accentColor(context);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setCancelable(true);
        setCanceledOnTouchOutside(false);
        LinearLayout root = new LinearLayout(getContext()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int limit = Math.round(getResources().getDisplayMetrics().heightPixels * .85f);
                if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
                    limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getContext().getString(R.string.app_name), 24);
        title.setTypeface(null, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton close = new ImageButton(getContext());
        close.setImageResource(R.drawable.ic_info_close);
        close.setContentDescription(getContext().getString(R.string.settings_info_close));
        styleAccentButton(close, accent);
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        LinearLayout content = createContent(getContext());

        // Only the body scrolls: the title and close target remain reachable at large font scales.
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        setContentView(root);
        OutsideCancel.dialog(this, close::performClick);

        Window window = getWindow();
        if (window != null) {
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(Color.rgb(40, 44, 52));
            surface.setCornerRadius(dp(20));
            window.setBackgroundDrawable(surface);
            window.getDecorView().setClipToOutline(true);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setLayout(Math.min(Math.round(getContext().getResources().getDisplayMetrics().widthPixels * .92f),
                    dp(560)), WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
    }

    /** Retained compatibility dialog body; its original layout is independent of settings. */
    static LinearLayout createContent(Context host) {
        Context context = contentContext(host);
        LinearLayout content = column(context);
        addInfo(content, context);
        TextView licenses = text(context, context.getString(R.string.settings_info_licenses), 15);
        licenses.setPadding(0, dp(context, 16), 0, 0);
        licenses.setLinksClickable(false);
        content.addView(licenses);
        addLicenseNotices(content, context);
        content.addView(createFooter(context));
        return content;
    }

    static LinearLayout createInfoContent(Context host) {
        Context context = contentContext(host);
        LinearLayout content = column(context);
        addInfo(content, context);
        return content;
    }

    static LinearLayout createLicensesContent(Context host) {
        Context context = contentContext(host);
        LinearLayout content = column(context);
        addLicenseNotices(content, context);
        return content;
    }

    /** The existing three notices in one naturally wrapping metadata-sized line. */
    static TextView createLicenseLine(Context host) {
        Context context = contentContext(host);
        TextView notices = text(context, "", 12);
        notices.setText(new SpannableStringBuilder(Html.fromHtml(
                context.getString(R.string.settings_info_sources).replace(" · ", " • "),
                Html.FROM_HTML_MODE_LEGACY)).insert(0, "\n").append(" • ")
                .append(context.getString(R.string.settings_info_app_license)));
        notices.setMovementMethod(LinkMovementMethod.getInstance());
        return notices;
    }

    private static void addInfo(LinearLayout content, Context context) {
        TextView ring = text(context, context.getString(R.string.settings_info_ring_title) + "\n"
                + context.getString(R.string.settings_info_ring), 16);
        ring.setPadding(0, 0, 0, dp(context, 16));
        content.addView(ring);
        content.addView(text(context, context.getString(R.string.settings_info_operation), 15));
    }

    private static void addLicenseNotices(LinearLayout content, Context context) {
        TextView sources = text(context, "", 15);
        sources.setText(Html.fromHtml(context.getString(R.string.settings_info_sources), Html.FROM_HTML_MODE_LEGACY));
        sources.setMovementMethod(LinkMovementMethod.getInstance());
        content.addView(sources);
        TextView appLicense = text(context, context.getString(R.string.settings_info_app_license), 15);
        appLicense.setLinksClickable(false);
        content.addView(appLicense);
    }

    /** Shared metadata row; the caller owns its placement and visibility. */
    static LinearLayout createFooter(Context host) {
        Context context = contentContext(host);
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(context, 20), 0, 0);
        footer.addView(text(context, context.getString(R.string.settings_info_version, installedVersion(context)), 12),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView github = text(context, "GitHub", 12);
        github.setTextColor(github.getLinkTextColors());
        github.setPaintFlags(github.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        github.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/zwegen/blockzeit"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { context.startActivity(intent); }
            catch (ActivityNotFoundException | SecurityException ignored) { }
        });
        github.setLinksClickable(false);
        footer.addView(github);
        return footer;
    }

    private static Context contentContext(Context host) {
        // Retain the original body typography and provider-link colors, not the settings accent.
        return new ContextThemeWrapper(host, android.R.style.Theme_Material_Dialog_NoActionBar);
    }

    private static LinearLayout column(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        return content;
    }

    static int accentColor(Context context) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true);
        return value.data;
    }

    static void styleAccentButton(ImageButton button, int accent) {
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor(accent);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), fill, null));
        button.setImageTintList(ColorStateList.valueOf(Color.rgb(23, 26, 31)));
    }

    private static String installedVersion(Context context) {
        try {
            String version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return version == null ? "" : version;
        } catch (PackageManager.NameNotFoundException unavailable) {
            return "";
        }
    }

    private TextView text(String value, int size) { return text(getContext(), value, size); }

    private static TextView text(Context context, String value, int size) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.rgb(225, 227, 230));
        return text;
    }

    private int dp(int value) { return dp(getContext(), value); }
    private static int dp(Context context, int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
}
