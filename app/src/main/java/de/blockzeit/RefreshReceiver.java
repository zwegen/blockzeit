package de.blockzeit;

import de.blockzeit.R;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

@android.annotation.TargetApi(31)
public final class RefreshReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (BlockzeitWidget.ids(c).length == 0) { Scheduler.cancel(c); return; }
        // Only local UI and scheduler work here; networking lives entirely in JobService.
        Scheduler.ensure(c); // Rearm next minute BEFORE local redraw or async acquisition can fail.
        try { BlockzeitWidget.renderAll(c); }
        finally { Scheduler.enqueue(c); }
    }
}
