package dev.deang.tabaod;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** The full AOD picture: the drawn clock/info/icons, with the widget layer on top. */
public class AodStage extends FrameLayout {
    final AodView aod;
    final WidgetLayer widgets;
    private final boolean editing;

    public AodStage(Context c, boolean editing) {
        super(c);
        this.editing = editing;
        aod = new AodView(c);
        aod.setEditing(editing);
        widgets = new WidgetLayer(c);
        widgets.setEditing(editing);
        addView(aod, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(widgets, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        reloadWidgets();
    }

    public void reload() {
        aod.reload();
        reloadWidgets();
    }

    public void reloadWidgets() {
        widgets.reload();
        widgets.setBackdrop(aod.wallpaperBitmap(), aod.wallpaperPct());
    }

    /** Undo the burn-in shift, so the lock screen takes over without a jump. */
    public void resetShift() {
        aod.resetShift();
        widgets.setShift(0, 0);
    }

    public void tick() {
        aod.tick();
        widgets.setShift(aod.shiftX(), aod.shiftY());
        widgets.refreshColours();
        widgets.invalidate();
    }

    public void setWake(float progress) {
        aod.setWake(progress);
    }

    /** The pop-up card is drawn by AodView, so hide the widgets while it shows. */
    public void showPopup(NotifStore.Item item) {
        aod.showPopup(item);
        widgets.setVisibility(INVISIBLE);
    }

    public void hidePopup() {
        aod.hidePopup();
        widgets.setVisibility(VISIBLE);
    }

    /** In the editor, touches go to the editor, never to the widgets themselves. */
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        return editing || super.onInterceptTouchEvent(e);
    }
}
