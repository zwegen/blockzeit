package de.blockzeit;

import android.content.Context;
import android.widget.ListPopupWindow;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

/** Value selection with the same opened surface and anchor policy as SettingsActionPicker.
 * The Spinner still owns the selected value, IDs, selected view and selection callbacks.
 */
final class SettingsSelectionSpinner extends Spinner {
    private final ListPopupWindow popup;

    SettingsSelectionSpinner(Context context) {
        // Own the opened list rather than inheriting the native content-width/overlap policy.
        // Like SettingsActionPicker, MODE_DIALOG disables the unused dropdown drag forwarder
        // without replacing the themed closed-field drawable or its metrics.
        super(context, Spinner.MODE_DIALOG);
        popup = new ListPopupWindow(context);
        popup.setAnchorView(this);
        popup.setModal(true);
        popup.setBackgroundDrawable(InputStyle.popupBackground(this, popup.getBackground()));
        if (getAdapter() != null) popup.setAdapter(new InputStyle.NativeChoices(getAdapter()));
        popup.setOnItemClickListener((parent, view, position, id) -> {
            setSelection(position);
            popup.dismiss();
        });
    }

    @Override public void setAdapter(SpinnerAdapter adapter) {
        super.setAdapter(adapter);
        // Spinner's constructor can call this before our popup exists. Keep later calendar
        // reloads on the original adapter and observer path, including while the list is open.
        if (popup != null) popup.setAdapter(adapter == null ? null : new InputStyle.NativeChoices(adapter));
    }

    @Override public boolean performClick() {
        if (!isEnabled()) return false;
        popup.setWidth(getWidth());
        popup.show();
        popup.setSelection(getSelectedItemPosition());
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        popup.dismiss();
        super.onDetachedFromWindow();
    }
}
