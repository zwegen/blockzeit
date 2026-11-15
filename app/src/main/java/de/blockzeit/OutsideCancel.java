package de.blockzeit;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

/** Outside taps are a UI cancel action, never an implicit save or a generic Back action. */
final class OutsideCancel {
    private OutsideCancel() { }

    /** Install after content creation. The action must recheck busy state where applicable. */
    static void activity(Activity activity, Runnable cancel) {
        activity.setFinishOnTouchOutside(false);
        window(activity.getWindow(), cancel);
    }

    /** Keeps Back and existing cancel/dismiss callbacks unchanged. */
    static void dialog(Dialog dialog, Runnable cancel) {
        dialog.setCanceledOnTouchOutside(false);
        window(dialog.getWindow(), cancel);
    }

    /** Reuse the actual installed negative button, including native picker cancellation. */
    static void negative(AlertDialog dialog) {
        dialog(dialog, () -> {
            Button button = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (button != null && button.isEnabled()) button.performClick();
        });
    }

    private static void window(Window window, Runnable cancel) {
        if (window == null) return;
        // A modal input target owns the whole gesture. Closing it must not tap a widget,
        // a parent form or a second window behind it. Do not request outside observation.
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        window.getDecorView().setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_OUTSIDE || outside(view, event)) {
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_OUTSIDE) cancel.run();
                return true;
            }
            return false;
        });
    }

    private static boolean outside(View view, MotionEvent event) {
        return event.getX() < 0 || event.getY() < 0
                || event.getX() >= view.getWidth() || event.getY() >= view.getHeight();
    }
}
