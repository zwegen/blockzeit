package de.blockzeit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/** Native special-access flow only: no widget layout changes or persistent notification. */
@android.annotation.TargetApi(31)
final class ExactAlarmAccess {
    private boolean offered;

    void onResume(Activity activity) {
        // ALWAYS reschedule after return/regrant, even if this Activity already explained access.
        BlockzeitWidget.onForeground(activity);
        if (offered || BlockzeitWidget.ids(activity).length == 0 || Scheduler.exactAllowed(activity)) return;
        offered = true; // Do not reopen the dialog in a loop after denial/return from Settings.
        AlertDialog explanation = new AlertDialog.Builder(activity)
                .setTitle(R.string.exact_alarm_title)
                .setMessage(R.string.exact_alarm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.exact_alarm_open, (dialog, which) -> open(activity))
                .show();
        OutsideCancel.negative(explanation);
    }

    private static void open(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, R.string.exact_alarm_missing, Toast.LENGTH_LONG).show();
        }
    }
}
