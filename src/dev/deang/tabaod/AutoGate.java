package dev.deang.tabaod;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;

/**
 * "Auto" when-to-show: hide the AOD while the tablet is face down, or in the dark and
 * still for 5 minutes. Sensors are only sampled briefly (about half a second) on each
 * check, not left running, to keep the AOD's battery use low.
 */
final class AutoGate implements SensorEventListener {
    private static final long STILL_MS = 5 * 60_000L;
    private static final float DARK_LUX = 5f;
    /** Change in the gravity vector (m/s^2) between samples that counts as moved. */
    private static final float MOVED = 1.2f;

    private final SensorManager sm;
    private final Sensor light, accel;
    private final Handler handler;

    private float lux = -1;
    private float[] g;
    private float[] lastG;
    private long stillSince = SystemClock.elapsedRealtime();
    private boolean sampling;

    AutoGate(Context c, Handler handler) {
        this.handler = handler;
        sm = c.getSystemService(SensorManager.class);
        light = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        accel = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    /** Starts counting stillness from now (call when the AOD starts). */
    void reset() {
        lux = -1;
        g = lastG = null;
        stillSince = SystemClock.elapsedRealtime();
    }

    /** Reads the sensors for a moment, then runs {@code done} on the handler. */
    void sample(Runnable done) {
        if (sm == null || sampling) { handler.post(done); return; }
        sampling = true;
        if (light != null) sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL, handler);
        if (accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL, handler);
        handler.postDelayed(() -> {
            sm.unregisterListener(this);
            sampling = false;
            if (g != null) {
                if (lastG != null && dist(g, lastG) > MOVED) stillSince = SystemClock.elapsedRealtime();
                lastG = g.clone();
            }
            done.run();
        }, 600);
    }

    /** True when the AOD should be hidden. Unknown sensor values never hide it. */
    boolean shouldHide() {
        boolean faceDown = g != null && g[2] < -7f && Math.abs(g[0]) < 3f && Math.abs(g[1]) < 3f;
        boolean darkAndStill = lux >= 0 && lux < DARK_LUX
                && SystemClock.elapsedRealtime() - stillSince >= STILL_MS;
        return faceDown || darkAndStill;
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_LIGHT) lux = e.values[0];
        else if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) g = e.values.clone();
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) { }

    private static float dist(float[] a, float[] b) {
        float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
}
