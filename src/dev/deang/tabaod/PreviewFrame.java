package dev.deang.tabaod;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Shows its child (the AOD stage) laid out at the real screen size, scaled down to fit.
 * Laying out at full size keeps text, positions and widget sizes exactly as on the AOD;
 * only the picture is scaled. Touches never reach the child: the whole frame is one button.
 */
final class PreviewFrame extends FrameLayout {
    private final float radius;
    private int fullW = 1, fullH = 1;
    private float scale = 1f;

    PreviewFrame(Context c, Ui ui) {
        super(c);
        radius = ui.dp(22);
        setBackgroundColor(Color.BLACK);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        setClipToOutline(true);
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(radius);
        border.setStroke(Math.max(1, ui.dp(1)), Ui.LINE);
        setForeground(border);
    }

    private void readScreen() {
        Rect r = getContext().getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        fullW = Math.max(1, r.width());
        fullH = Math.max(1, r.height());
    }

    @Override protected void onMeasure(int ws, int hs) {
        readScreen();
        int aw = MeasureSpec.getSize(ws), ah = MeasureSpec.getSize(hs);
        boolean freeH = MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED || ah == 0;
        scale = freeH ? aw / (float) fullW : Math.min(aw / (float) fullW, ah / (float) fullH);
        setMeasuredDimension(Math.round(fullW * scale), Math.round(fullH * scale));
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(fullW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(fullH, MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            v.layout(0, 0, fullW, fullH);
            v.setPivotX(0);
            v.setPivotY(0);
            v.setScaleX(scale);
            v.setScaleY(scale);
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        return true;
    }
}
