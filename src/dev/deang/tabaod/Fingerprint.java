package dev.deang.tabaod;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.Surface;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The under-display fingerprint sensor: where it is, and a fingerprint icon to draw over it
 * on the AOD (like One UI's "Show icon when screen is off"). The position comes from
 * SystemUI's biometrics dump, once, and is cached.
 */
final class Fingerprint {
    /** "x,y,r,rotation,w,h": the sensor in screen coordinates at the rotation it was read in. */
    private static final String KEY = "fp_sensor2";
    private static final Pattern BOUNDS = Pattern.compile("udfpsBounds=Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)");

    /** The cached sensor: {x, y, radius, rotation, screen width, screen height}, or null. */
    static float[] natural(Context c) {
        String s = Prefs.raw(c).getString(KEY, null);
        if (s == null) return null;
        String[] f = s.split(",");
        if (f.length != 6) return null;
        float[] out = new float[6];
        try {
            for (int i = 0; i < 6; i++) out[i] = Float.parseFloat(f[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /**
     * Reads the sensor position from SystemUI if it isn't cached yet. SystemUI reports it in
     * the screen's current coordinates (despite the dump's "natural orientation" label), so the
     * rotation and size at that moment are saved with it. Call off the main thread.
     */
    static void ensure(Context c) {
        if (natural(c) != null) return;
        if (!c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return;
        Matcher m = BOUNDS.matcher(LockWidgets.sysuiDump(c));
        if (!m.find()) return;
        float l = Float.parseFloat(m.group(1)), t = Float.parseFloat(m.group(2));
        float r = Float.parseFloat(m.group(3)), b = Float.parseFloat(m.group(4));
        if (r <= l || b <= t) return;
        android.view.Display d = c.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
        android.graphics.Rect size = c.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        Prefs.raw(c).edit().putString(KEY, ((l + r) / 2f) + "," + ((t + b) / 2f) + "," + (Math.max(r - l, b - t) / 2f)
                + "," + d.getRotation() + "," + size.width() + "," + size.height()).apply();
    }

    /** The sensor centre on a {@code w} x {@code h} screen at display {@code rotation}. */
    static PointF onScreen(float[] f, int w, int h, int rotation) {
        int r0 = (int) f[3];
        float w0 = f[4], h0 = f[5];
        // Natural size: the screen as it is at rotations 0 and 180.
        float natW = r0 % 2 == 0 ? w0 : h0, natH = r0 % 2 == 0 ? h0 : w0;
        // Saved point -> natural orientation...
        float x = f[0], y = f[1], nx, ny;
        switch (r0) {
            case Surface.ROTATION_90: nx = natW - y; ny = x; break;
            case Surface.ROTATION_180: nx = natW - x; ny = natH - y; break;
            case Surface.ROTATION_270: nx = y; ny = natH - x; break;
            default: nx = x; ny = y;
        }
        // ... -> the current rotation.
        PointF p;
        switch (rotation) {
            case Surface.ROTATION_90: p = new PointF(ny, natW - nx); break;
            case Surface.ROTATION_180: p = new PointF(natW - nx, natH - ny); break;
            case Surface.ROTATION_270: p = new PointF(natH - ny, nx); break;
            default: p = new PointF(nx, ny);
        }
        // The view may be smaller than the screen (it isn't for the AOD, but be safe).
        float curW = rotation % 2 == 0 ? natW : natH, curH = rotation % 2 == 0 ? natH : natW;
        p.x *= w / curW;
        p.y *= h / curH;
        return p;
    }

    /** A simple fingerprint glyph: open concentric arcs, centred on (cx, cy). */
    static void draw(Canvas c, float cx, float cy, float radius, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(radius * 0.075f);
        p.setColor(color);
        // Ridges from the inside out: (radius fraction, start angle, sweep), a bit taller than wide.
        float[][] ridges = {
            {0.14f, 200, 250}, {0.30f, 180, 290}, {0.46f, 165, 300}, {0.62f, 175, 245}, {0.78f, 195, 150}};
        for (float[] rd : ridges) {
            float rx = radius * rd[0], ry = radius * rd[0] * 1.15f;
            c.drawArc(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), rd[1], rd[2], false, p);
        }
    }
}
