package dev.deang.tabaod;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * Layout editor: drag the clock, the date/battery line, the notification icons and widgets;
 * pinch to resize the clock or a widget; double-tap an item to put it back; long-press a widget
 * to remove it. Save stores the layout.
 */
public class EditorActivity extends Activity {
    static final String EXTRA_ADD = "add_widget";
    /** Element ids: AodView's own (0..2), and widgets from WIDGET onwards. */
    private static final int WIDGET = 100;
    private static final int REQ_BIND = 1, REQ_CONFIGURE = 2;

    private AodStage stage;
    private int active = -1;
    private float lastX, lastY;
    private ScaleGestureDetector pinch;
    private GestureDetector taps;
    private int pendingId = -1;
    private AppWidgetProviderInfo pendingInfo;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        stage = new AodStage(this, true);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText("Drag to move  ·  Pinch to resize  ·  Double-tap to reset  ·  Long-press a widget to remove it");
        hint.setTextColor(Color.argb(200, 255, 255, 255));
        hint.setTextSize(14);
        hint.setPadding(32, 16, 32, 16);
        hint.setBackground(pill(Color.argb(160, 30, 30, 32)));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = 40;
        root.addView(hint, hp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(16, 8, 16, 8);
        bar.setBackground(pill(Color.argb(200, 30, 30, 32)));
        bar.addView(button("Add widget", v -> pickWidget()));
        bar.addView(button("Reset", v -> {
            Prefs.resetLayout(this);
            stage.aod.reload();
            Toast.makeText(this, "Clock and items back to the automatic layout", Toast.LENGTH_SHORT).show();
        }));
        bar.addView(button("Cancel", v -> finish()));
        bar.addView(button("Save", v -> {
            stage.aod.saveLayout();
            stage.widgets.save();
            Toast.makeText(this, "Layout saved", Toast.LENGTH_SHORT).show();
            finish();
        }));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = 48;
        root.addView(bar, bp);

        setContentView(root);
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.hide(WindowInsets.Type.systemBars());
            ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (active >= WIDGET) {
                    stage.widgets.scaleBy(active - WIDGET, d.getScaleFactor());
                } else {
                    select(AodView.EL_CLOCK);
                    stage.aod.scaleClock(d.getScaleFactor());
                }
                return true;
            }
        });
        taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                int el = hitTest(e.getX(), e.getY());
                if (el >= 0 && el < WIDGET) {
                    stage.aod.resetElement(el);
                    select(el);
                }
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                int el = hitTest(e.getX(), e.getY());
                if (el < WIDGET) return;
                int i = el - WIDGET;
                new AlertDialog.Builder(EditorActivity.this)
                        .setTitle("Remove " + stage.widgets.label(i) + "?")
                        .setPositiveButton("Remove", (d, w) -> { stage.widgets.remove(i); active = -1; })
                        .setNegativeButton("Keep", null)
                        .show();
            }
        });
        stage.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            taps.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    select(hitTest(e.getX(), e.getY()));
                    lastX = e.getX();
                    lastY = e.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount() == 1 && !pinch.isInProgress() && active >= 0) {
                        float ddx = e.getX() - lastX, ddy = e.getY() - lastY;
                        if (active >= WIDGET) stage.widgets.moveBy(active - WIDGET, ddx, ddy);
                        else stage.aod.moveBy(active, ddx, ddy);
                    }
                    lastX = e.getX(0);
                    lastY = e.getY(0);
                    break;
                case MotionEvent.ACTION_POINTER_UP: {
                    // Continue dragging from the finger that stays down.
                    int keep = e.getActionIndex() == 0 ? 1 : 0;
                    lastX = e.getX(keep);
                    lastY = e.getY(keep);
                    break;
                }
                default:
                    break;
            }
            return true;
        });

        if (getIntent().getBooleanExtra(EXTRA_ADD, false)) stage.post(this::pickWidget);
    }

    /** Small items first, then widgets (they sit on top), then the clock. */
    private int hitTest(float x, float y) {
        // The selected item wins, so overlapping items can still be dragged apart.
        if (active >= WIDGET && stage.widgets.contains(active - WIDGET, x, y)) return active;
        if (active >= 0 && active < WIDGET && stage.aod.contains(active, x, y)) return active;
        int el = stage.aod.hitTest(x, y);
        if (el == AodView.EL_ICONS || el == AodView.EL_INFO) return el;
        int w = stage.widgets.hitTest(x, y);
        if (w >= 0) return WIDGET + w;
        return el;
    }

    private void select(int el) {
        active = el;
        stage.aod.select(el >= 0 && el < WIDGET ? el : -1);
        stage.widgets.select(el >= WIDGET ? el - WIDGET : -1);
    }

    // --- adding a widget: pick, bind, configure ---

    private void pickWidget() {
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        PackageManager pm = getPackageManager();
        List<AppWidgetProviderInfo> list = new ArrayList<>(awm.getInstalledProviders());
        List<String> labels = new ArrayList<>();
        for (AppWidgetProviderInfo info : list) labels.add(appLabel(pm, info) + "  ·  " + info.loadLabel(pm));
        // Sort both lists together by label.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) order.add(i);
        order.sort((a, c) -> labels.get(a).compareToIgnoreCase(labels.get(c)));
        String[] names = new String[order.size()];
        AppWidgetProviderInfo[] infos = new AppWidgetProviderInfo[order.size()];
        for (int i = 0; i < order.size(); i++) {
            names[i] = labels.get(order.get(i));
            infos[i] = list.get(order.get(i));
        }
        if (names.length == 0) {
            Toast.makeText(this, "No widgets installed", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Add a widget")
                .setItems(names, (d, i) -> bind(infos[i]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void bind(AppWidgetProviderInfo info) {
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        pendingInfo = info;
        pendingId = WidgetStore.host(this).allocateAppWidgetId();
        if (awm.bindAppWidgetIdIfAllowed(pendingId, info.getProfile(), info.provider, null)) {
            configure();
            return;
        }
        // Not allowed yet: grant it with root, then retry; ask the user only as a last resort.
        new Thread(() -> {
            Root.run("cmd appwidget grantbind --package " + getPackageName() + " --user 0");
            runOnUiThread(() -> {
                if (awm.bindAppWidgetIdIfAllowed(pendingId, info.getProfile(), info.provider, null)) {
                    configure();
                    return;
                }
                Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
                try {
                    startActivityForResult(i, REQ_BIND);
                } catch (ActivityNotFoundException e) {
                    cancelPending("Couldn't get permission to add widgets");
                }
            });
        }).start();
    }

    private void configure() {
        boolean optional = (pendingInfo.widgetFeatures
                & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0;
        if (pendingInfo.configure != null && !optional) {
            try {
                WidgetStore.host(this).startAppWidgetConfigureActivityForResult(
                        this, pendingId, 0, REQ_CONFIGURE, null);
                return;
            } catch (Exception e) {
                // Some configure screens can't be opened by other apps: add it with defaults.
            }
        }
        addPending();
    }

    private void addPending() {
        stage.widgets.add(pendingId, pendingInfo, stage.aod.contentBottom());
        select(WIDGET + stage.widgets.count() - 1);
        pendingId = -1;
        Toast.makeText(this, "Widget added. Drag it into place, then Save.", Toast.LENGTH_LONG).show();
    }

    private void cancelPending(String msg) {
        if (pendingId != -1) WidgetStore.delete(this, pendingId);
        pendingId = -1;
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (pendingId == -1) return;
        if (result != RESULT_OK) {
            cancelPending(null);
            return;
        }
        if (req == REQ_BIND) configure();
        else if (req == REQ_CONFIGURE) addPending();
    }

    private static String appLabel(PackageManager pm, AppWidgetProviderInfo info) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(info.provider.getPackageName(), 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return info.provider.getPackageName();
        }
    }

    private Button button(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(l);
        return b;
    }

    private static GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(200);
        return d;
    }
}
