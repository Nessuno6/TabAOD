package dev.deang.tabaod;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.Display;
import java.lang.reflect.Method;

/**
 * The AOD itself: a doze "dream" that dims the display and shows AodView.
 * It rests in DOZE_SUSPEND (static image) and briefly switches to DOZE to redraw:
 * once a minute for the clock, and while a notification pop-up is showing.
 */
public class AodDozeService extends DreamService implements NotifStore.Listener {
    static final String TAG = "TabAOD";
    /** Samsung's doze mode, as used by SystemUI's own doze service. */
    static final int SEM_DOZE_MODE = 0x10002;


    private final Handler handler = new Handler(Looper.getMainLooper());
    private AodStage view;
    private Prefs prefs;
    private boolean popupShowing;
    /** True while the cover is closed over the screen: the AOD stays fully off. */
    private boolean coverClosed;
    /** True while the AOD is on screen (false while blanked by the cover or "When to show"). */
    private boolean shown;
    /** "For new notifications": alerts that arrived since this screen-off. */
    private final java.util.Set<String> newKeys = new java.util.HashSet<>();
    private AutoGate auto;

    private final Runnable suspend = () -> {
        if (!popupShowing) setState(Display.STATE_DOZE_SUSPEND);
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            redraw(() -> view.tick());
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - (now % 60_000) + 50);
        }
    };

    private final Runnable endPopup = () -> {
        popupShowing = false;
        redraw(() -> view.hidePopup());
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        view = new AodStage(this, false);
        setContentView(view);
    }

    /** The AOD that is currently running, so app shortcuts can switch it straight away. */
    private static volatile AodDozeService running;

    /** Called by the "Turn AOD on/off" shortcuts: applies the change to a running AOD. */
    static void applyEnabled(boolean on) {
        AodDozeService s = running;
        if (s == null) return; // not dozing: the setting applies at the next screen-off
        s.handler.post(() -> {
            if (s.view == null) return;
            s.prefs = Prefs.load(s);
            if (on) s.startAod();
            else s.screenOff();
            Log.i(TAG, "AOD switched " + (on ? "on" : "off") + " by shortcut");
        });
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        running = this;
        prefs = Prefs.load(this);
        NotifStore.addListener(this);
        if (!prefs.enabled) {
            // AOD switched off: behave like a normal screen-off.
            view.setVisibility(android.view.View.INVISIBLE);
            setState(Display.STATE_OFF);
            call("startDozing", new Class<?>[0]);
            Log.i(TAG, "AOD disabled, screen off");
            return;
        }
        newKeys.clear();
        if (Fingerprint.natural(this) == null) {
            new Thread(() -> { Fingerprint.ensure(this); handler.post(() -> { if (view != null && shown) redraw(() -> view.aod.reload()); }); }).start();
        }
        if (auto == null) auto = new AutoGate(this, handler);
        auto.reset();
        startAod();
        if (LockWidgets.isOn(this)) syncLockWidgets();
        Log.i(TAG, "AOD started, style=" + prefs.style);
    }

    /** Shows the AOD (or keeps the screen off if the cover is closed) and starts its timers. */
    private void startAod() {
        view.setVisibility(android.view.View.VISIBLE);
        view.reload();
        setState(Display.STATE_DOZE);
        float b = Math.max(1, Math.min(60, prefs.brightnessPct)) / 100f;
        Object r = call("semSetDozeScreenBrightness", new Class<?>[]{int.class, float.class}, SEM_DOZE_MODE, b);
        if (String.valueOf(r).startsWith("ERR")) {
            call("setDozeScreenBrightness", new Class<?>[]{float.class}, b);
        }
        call("startDozing", new Class<?>[0]);
        coverClosed = prefs.coverOff && Cover.isClosed(this);
        handler.removeCallbacks(tick);
        if (coverClosed || !modeAllows()) {
            screenOff();
            Log.i(TAG, coverClosed ? "cover closed: AOD off" : "AOD hidden: " + Prefs.MODE_NAMES[prefs.showMode]);
        } else {
            show();
        }
        handler.removeCallbacks(check);
        if (needsChecks()) handler.postDelayed(check, checkInterval());
    }

    /** Puts the AOD on screen and starts the clock. */
    private void show() {
        shown = true;
        view.resetShift();
        view.setVisibility(android.view.View.VISIBLE);
        view.reload();
        showInstantly();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1100);
    }

    /** "When to show": whether the AOD may show right now (the cover is handled separately). */
    private boolean modeAllows() {
        switch (prefs.showMode) {
            case Prefs.MODE_SCHEDULE: {
                java.util.Calendar c = java.util.Calendar.getInstance();
                return prefs.inSchedule(c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE));
            }
            case Prefs.MODE_NEW_NOTIFS:
                for (String k : newKeys) if (NotifStore.contains(k)) return true;
                return false;
            case Prefs.MODE_AUTO:
                return auto == null || !auto.shouldHide();
            default:
                return true;
        }
    }

    private boolean needsChecks() {
        return prefs.coverOff || prefs.showMode == Prefs.MODE_SCHEDULE || prefs.showMode == Prefs.MODE_AUTO;
    }

    /** Auto checks more often, so the AOD comes back soon after the tablet is picked up. */
    private long checkInterval() {
        return prefs.showMode == Prefs.MODE_AUTO ? 20_000 : 60_000;
    }

    /** Shows or blanks the AOD to match the cover and "When to show". */
    private void evaluate() {
        if (view == null || prefs == null || !prefs.enabled) return;
        boolean want = !coverClosed && modeAllows();
        if (want == shown) return;
        Log.i(TAG, want ? "AOD on" : "AOD off (" + (coverClosed ? "cover" : Prefs.MODE_NAMES[prefs.showMode]) + ")");
        if (want) show();
        else screenOff();
    }

    /** Periodic check: the cover (apps can't listen to it), the schedule and the Auto sensors. */
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (prefs == null || !needsChecks()) return;
            boolean coverOff = prefs.coverOff;
            Runnable afterSensors = () -> {
                if (!coverOff) { evaluate(); return; }
                new Thread(() -> {
                    boolean closed = Cover.isClosed(AodDozeService.this);
                    handler.post(() -> { coverClosed = closed; evaluate(); });
                }).start();
            };
            if (prefs.showMode == Prefs.MODE_AUTO && auto != null) auto.sample(afterSensors);
            else afterSensors.run();
            handler.postDelayed(this, checkInterval());
        }
    };

    private void screenOff() {
        shown = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(suspend);
        handler.removeCallbacks(endPopup);
        popupShowing = false;
        view.hidePopup();
        view.setVisibility(android.view.View.INVISIBLE);
        setState(Display.STATE_OFF);
    }

    /** If the lock screen's widgets changed since the last copy, copy them again. */
    private void syncLockWidgets() {
        new Thread(() -> {
            java.util.List<LockWidgets.Spec> specs = LockWidgets.read(this);
            if (specs.isEmpty() || !LockWidgets.changed(this, specs)) return;
            handler.post(() -> {
                if (view == null) return;
                int n = LockWidgets.apply(this, specs);
                Log.i(TAG, "lock screen widgets copied: " + n);
                redraw(() -> view.reloadWidgets());
            });
        }).start();
    }

    /** Lock screen -> AOD: no animation, the AOD just appears. */
    private void showInstantly() {
        view.setWake(0f);
        view.setAlpha(1f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setTranslationY(0f);
        handler.removeCallbacks(suspend);
        setState(Display.STATE_DOZE);
        handler.postDelayed(suspend, 1000);
    }

    /** AOD -> lock screen: no animation, hand over straight away. */
    @Override public void onWakeUp() {
        handler.removeCallbacksAndMessages(null);
        popupShowing = false;
        // Put everything back in its home position before handing over to the lock screen.
        if (view != null) view.resetShift();
        super.onWakeUp();
    }

    @Override public void onDreamingStopped() {
        handler.removeCallbacks(check);
        shown = false;
        newKeys.clear();
        coverClosed = false;
        if (running == this) running = null;
        NotifStore.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        popupShowing = false;
        call("stopDozing", new Class<?>[0]);
        super.onDreamingStopped();
    }

    @Override public void onNotificationsChanged(NotifStore.Item alert) {
        handler.post(() -> {
            if (view == null || prefs == null || !prefs.enabled) return;
            if (alert != null) newKeys.add(alert.key);
            if (prefs.showMode == Prefs.MODE_NEW_NOTIFS) evaluate();
            if (coverClosed || !shown) return;
            if (alert != null && prefs.popups) {
                popupShowing = true;
                handler.removeCallbacks(endPopup);
                redraw(() -> view.showPopup(alert));
                handler.postDelayed(endPopup, Math.max(3, prefs.popupSeconds) * 1000L);
            } else {
                redraw(() -> view.aod.invalidate());
            }
        });
    }

    /** Switch to DOZE so the change reaches the screen, apply it, then settle again. */
    private void redraw(Runnable change) {
        handler.removeCallbacks(suspend);
        setState(Display.STATE_DOZE);
        change.run();
        handler.postDelayed(suspend, 1000);
    }

    private void setState(int state) {
        call("setDozeScreenState", new Class<?>[]{int.class}, state);
    }

    /** The doze methods are hidden from normal apps, so call them by reflection. */
    private Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method m = DreamService.class.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(this, args);
        } catch (Throwable t) {
            Log.e(TAG, name + " failed: " + t);
            return "ERR:" + t.getClass().getSimpleName();
        }
    }
}
