package de.blockzeit;

import de.blockzeit.R;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.widget.Toast;

/** Explicit PendingIntent target, then safely resolve an installed clock/alarm application. */
@android.annotation.TargetApi(31)
public final class ClockActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent[] candidates = {
                new Intent(AlarmClock.ACTION_SHOW_ALARMS),
                new Intent(AlarmClock.ACTION_SHOW_TIMERS)
        };
        boolean opened = false;
        for (Intent candidate : candidates) {
            // Launch directly: package visibility is not a reliable launchability check.
            try { startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); opened = true; break; }
            catch (ActivityNotFoundException | SecurityException ignored) { /* Try next safe clock intent. */ }
        }
        if (!opened) Toast.makeText(this, R.string.clock_missing, Toast.LENGTH_SHORT).show();
        finish();
    }
}
