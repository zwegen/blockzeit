package de.blockzeit;

import android.content.Context;
import android.widget.ListPopupWindow;
import android.widget.Spinner;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

/** A stable selection-style command field. Only a user's popup row click dispatches an action. */
final class SettingsActionPicker extends Spinner {
    private final ListPopupWindow popup;
    private IntConsumer action;
    private final android.widget.ListAdapter popupChoices;
    SettingsActionPicker(Context context, String label, List<String> actions) {
        // Disable Spinner's dropdown-drag forwarding: this field owns the command popup.
        // MODE_DIALOG changes only the unused native popup, not the themed field drawable.
        super(context, Spinner.MODE_DIALOG);
        SettingsControls.spinner(this);
        setAdapter(SettingsControls.choices(context, Collections.singletonList(label)));
        setContentDescription(label);
        popup = new ListPopupWindow(context);
        popup.setAnchorView(this);
        popup.setModal(true);
        popupChoices = SettingsControls.choices(context, actions);
        popup.setAdapter(popupChoices);
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            if (isEnabled() && action != null) action.accept(position);
        });
    }
    /** Opt in only result selections; unrelated command menus keep their existing style. */
    void styleSelectionPopup() {
        popup.setBackgroundDrawable(InputStyle.popupBackground(this,popup.getBackground()));
        // ListPopupWindow binds getView, not getDropDownView. Style every new/recycled
        // row while retaining the native selector and action-only dispatch.
        popup.setAdapter(new InputStyle.NativeChoices(popupChoices));
    }
    void onAction(IntConsumer action) { this.action = action; }
    @Override public boolean performClick() {
        if (!isEnabled()) return false;
        popup.setWidth(getWidth());
        popup.show();
        return true;
    }
    void dismiss() { popup.dismiss(); }
    @Override protected void onDetachedFromWindow() { dismiss(); super.onDetachedFromWindow(); }
}
