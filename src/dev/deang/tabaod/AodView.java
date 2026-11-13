package dev.deang.tabaod;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Draws the whole AOD: dim wallpaper, clock, date/battery, notification icons and pop-up. */
public class AodView extends View {
    private Prefs prefs;
    private Bitmap wallpaper;
    private NotifStore.Item popup;
    private final Random rnd = new Random();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private int dx, dy;
    /** 0 = normal AOD, 1 = fully woken (wallpaper at full light). Animated on wake-up. */
    private float wake;

    // Layout: element positions in pixels (clock centre, info baseline, icon row centre).
    static final int EL_CLOCK = 0, EL_INFO = 1, EL_ICONS = 2;
    private boolean posReady, editing;
    private int selected = -1;
    private float clockX, clockY, clockScale = 1f, infoX, infoY, iconsX, iconsY;
    private final RectF clockRect = new RectF(), infoRect = new RectF(), iconsRect = new RectF();

    public AodView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        ClockStyles.init(c);
        reload();
    }

    /** Re-read settings and the wallpaper copy. */
    public void reload() {
        prefs = Prefs.load(getContext());
        fpSensor = Fingerprint.natural(getContext());
        wallpaper = prefs.wallpaper ? loadWallpaper() : null;
        posReady = false;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        posReady = false;
    }

    /** Shift everything a little (burn-in protection) and redraw. */
    /**
     * Burn-in protection: each minute everything drifts a few pixels, staying within 0.5% of
     * the short side of its home position (about 9 px on this tablet). Small steps instead of
     * random jumps, so the move is invisible and waking up to the lock screen barely shifts.
     */
    public void tick() {
        int range = Math.max(4, Math.round(Math.min(getWidth(), getHeight()) * 0.005f));
        int step = Math.max(1, range / 3);
        dx = Math.max(-range, Math.min(range, dx + rnd.nextInt(2 * step + 1) - step));
        dy = Math.max(-range, Math.min(range, dy + rnd.nextInt(2 * step + 1) - step));
        invalidate();
    }

    /** Back to the home position (used when the AOD starts and on wake-up). */
    public void resetShift() {
        dx = 0;
        dy = 0;
        invalidate();
    }

    Bitmap wallpaperBitmap() { return wallpaper; }

    int wallpaperPct() { return prefs.wallpaperPct; }

    int shiftX() { return dx; }

    int shiftY() { return dy; }

    /** Wake-up progress, used to brighten the wallpaper towards the lock screen. */
    public void setWake(float progress) {
        wake = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    public void showPopup(NotifStore.Item item) {
        popup = item;
        invalidate();
    }

    public void hidePopup() {
        popup = null;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        if (w <= 0 || h <= 0) return;
        if (wallpaper != null) drawWallpaper(c, w, h);
        ensurePositions(w, h, s);

        // Burn-in shift applies on top of the layout, except while editing.
        float ox = editing ? 0 : dx, oy = editing ? 0 : dy;

        float cx = clockX + ox, cy = clockY + oy;
        float bottom = drawClock(c, cx, cy, s);
        float half = s * 0.32f * clockScale;
        clockRect.set(cx - half, cy - (bottom - cy), cx + half, bottom);

        Paint info = infoPaint(s);
        String line = infoLine();
        if (!line.isEmpty()) {
            float ix = infoX + ox, iy = infoY + oy, tw = info.measureText(line);
            c.drawText(line, ix, iy, info);
            infoRect.set(ix - tw / 2f, iy - info.getTextSize(), ix + tw / 2f, iy + info.getTextSize() * 0.35f);
        } else {
            infoRect.setEmpty();
        }

        // The pop-up card already names the app, so hide the icon row while it shows.
        if (prefs.notifIcons && popup == null) drawIcons(c, iconsX + ox, iconsY + oy, s);
        else iconsRect.setEmpty();

        // The Now bar: one live activity, as a pill near the bottom (hidden behind a pop-up).
        if (prefs.nowBar && popup == null && !editing) drawNowBar(c, w, h, s, ox, oy);

        if (popup != null) {
            // Keep the card away from the clock: below it if the clock is high, at the top otherwise.
            float lowest = Math.max(clockRect.bottom, infoRect.isEmpty() ? 0 : infoRect.bottom);
            float top = clockY > h * 0.5f ? h * 0.06f : Math.max(lowest + s * 0.06f, h * 0.62f);
            drawPopup(c, w, top, s);
        }
        // Fingerprint icon over the under-display sensor. Never shifted: it must stay on the sensor.
        if (prefs.fpIcon && fpSensor != null) {
            android.view.Display d = getDisplay();
            android.graphics.PointF at = Fingerprint.onScreen(fpSensor, (int) w, (int) h,
                    d == null ? android.view.Surface.ROTATION_0 : d.getRotation());
            Fingerprint.draw(c, at.x, at.y, fpSensor[2] * 0.8f, Color.rgb(130, 130, 135));
        }
        if (editing) drawEditFrames(c, s);
    }

    /** The fingerprint sensor (natural orientation x, y, radius), or null if unknown. */
    private float[] fpSensor;

    private float drawClock(Canvas c, float x, float y, float s) {
        boolean bold = getResources().getConfiguration().fontWeightAdjustment > 100;
        return ClockStyles.draw(prefs.style, c, x, y, s * clockScale, Calendar.getInstance(),
                DateFormat.is24HourFormat(getContext()), prefs.accent, wake, bold);
    }

    private static Paint infoPaint(float s) {
        Paint info = new Paint(Paint.ANTI_ALIAS_FLAG);
        info.setTextAlign(Paint.Align.CENTER);
        info.setColor(Color.rgb(160, 160, 160));
        info.setTextSize(s * 0.032f);
        return info;
    }

    // --- layout ---

    private void ensurePositions(float w, float h, float s) {
        if (posReady) return;
        if (prefs.customLayout) {
            clockScale = Math.max(0.5f, Math.min(1.8f, prefs.clockScale));
            clockX = prefs.clockX * w;
            clockY = prefs.clockY * h;
            infoX = prefs.infoX * w;
            infoY = prefs.infoY * h;
            iconsX = prefs.iconsX * w;
            iconsY = prefs.iconsY * h;
        } else {
            applyDefaults(w, h, s);
        }
        posReady = true;
    }

    /** The automatic layout: clock at the upper third, date/battery and icons stacked under it. */
    private void applyDefaults(float w, float h, float s) {
        clockScale = 1f;
        clockX = w / 2f;
        clockY = h * 0.34f;
        android.graphics.Picture pic = new android.graphics.Picture();
        float bottom = drawClock(pic.beginRecording((int) w, (int) h), clockX, clockY, s);
        pic.endRecording();
        infoX = w / 2f;
        infoY = bottom + infoPaint(s).getTextSize() * 1.6f;
        iconsX = w / 2f;
        iconsY = infoY + s * 0.07f;
    }

    // --- editing (used by EditorActivity) ---

    public void setEditing(boolean on) {
        editing = on;
        invalidate();
    }

    /** Which element is under (x, y): icons and info first since they are smaller. -1 if none. */
    public int hitTest(float x, float y) {
        float pad = Math.min(getWidth(), getHeight()) * 0.025f;
        if (inflated(iconsRect, pad).contains(x, y)) return EL_ICONS;
        if (inflated(infoRect, pad).contains(x, y)) return EL_INFO;
        if (inflated(clockRect, pad).contains(x, y)) return EL_CLOCK;
        return -1;
    }

    /** Whether (x, y) is on the given element. */
    public boolean contains(int element, float x, float y) {
        RectF r = element == EL_CLOCK ? clockRect : element == EL_INFO ? infoRect : element == EL_ICONS ? iconsRect : null;
        return r != null && inflated(r, Math.min(getWidth(), getHeight()) * 0.025f).contains(x, y);
    }

    /** The lowest point of the clock, info line and icons. */
    public float contentBottom() {
        return Math.max(clockRect.bottom, Math.max(infoRect.bottom, iconsRect.bottom));
    }

    public void select(int element) {
        selected = element;
        invalidate();
    }

    public void moveBy(int element, float ddx, float ddy) {
        float w = getWidth(), h = getHeight();
        switch (element) {
            case EL_CLOCK: clockX = clamp(clockX + ddx, 0, w); clockY = clamp(clockY + ddy, 0, h); break;
            case EL_INFO: infoX = clamp(infoX + ddx, 0, w); infoY = clamp(infoY + ddy, 0, h); break;
            case EL_ICONS: iconsX = clamp(iconsX + ddx, 0, w); iconsY = clamp(iconsY + ddy, 0, h); break;
            default: return;
        }
        invalidate();
    }

    public void scaleClock(float factor) {
        clockScale = clamp(clockScale * factor, 0.5f, 1.8f);
        invalidate();
    }

    /** Puts one element back where the automatic layout would place it. */
    public void resetElement(int element) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        float[] keep = {clockX, clockY, clockScale, infoX, infoY, iconsX, iconsY};
        applyDefaults(w, h, s);
        if (element != EL_CLOCK) { clockX = keep[0]; clockY = keep[1]; clockScale = keep[2]; }
        if (element != EL_INFO) { infoX = keep[3]; infoY = keep[4]; }
        if (element != EL_ICONS) { iconsX = keep[5]; iconsY = keep[6]; }
        invalidate();
    }

    public void saveLayout() {
        float w = getWidth(), h = getHeight();
        Prefs.saveLayout(getContext(), clockX / w, clockY / h, clockScale,
                infoX / w, infoY / h, iconsX / w, iconsY / h);
    }

    private void drawEditFrames(Canvas c, float s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        float r = s * 0.02f;
        RectF[] rects = {clockRect, infoRect, iconsRect};
        for (int i = 0; i < rects.length; i++) {
            if (rects[i].isEmpty()) continue;
            boolean sel = i == selected;
            p.setColor(sel ? prefs.accent : Color.argb(150, 255, 255, 255));
            p.setStrokeWidth(sel ? s * 0.004f : s * 0.0025f);
            p.setPathEffect(sel ? null : new android.graphics.DashPathEffect(new float[]{s * 0.012f, s * 0.008f}, 0));
            c.drawRoundRect(inflated(rects[i], s * 0.015f), r, r, p);
        }
    }

    private static RectF inflated(RectF r, float by) {
        RectF o = new RectF(r);
        if (!o.isEmpty()) o.inset(-by, -by);
        return o;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private String infoLine() {
        StringBuilder sb = new StringBuilder();
        if (prefs.showDate && !ClockStyles.showsDate(prefs.style)) {
            // The lock screen style writes the month short, like One UI's lock screen.
            String fmt = prefs.style == ClockStyles.LOCK ? "EEE d MMM" : "EEE d MMMM";
            sb.append(new SimpleDateFormat(fmt, Locale.getDefault()).format(Calendar.getInstance().getTime()));
        }
        if (prefs.showBattery) {
            Intent b = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100
                        / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
                if (sb.length() > 0) sb.append("   ·   ");
                sb.append(charging ? "Charging " : "").append(pct).append('%');
            }
        }
        return sb.toString();
    }

    /** Notification icon row centred on (cx, cy). In the editor, shows placeholders if empty. */
    private void drawIcons(Canvas c, float cx, float cy, float s) {
        List<NotifStore.Item> items = NotifStore.perApp();
        float size = s * 0.04f, gap = s * 0.022f, top = cy - size / 2f;
        if (items.isEmpty()) {
            if (!editing) { iconsRect.setEmpty(); return; }
            Paint ph = new Paint(Paint.ANTI_ALIAS_FLAG);
            ph.setColor(Color.argb(120, 200, 200, 200));
            float total = 3 * size + 2 * gap, x = cx - total / 2f;
            for (int i = 0; i < 3; i++, x += size + gap) c.drawCircle(x + size / 2f, cy, size * 0.4f, ph);
            iconsRect.set(cx - total / 2f, top, cx + total / 2f, top + size);
            return;
        }
        int max = Math.min(items.size(), 8);
        float total = max * size + (max - 1) * gap + (items.size() > max ? size : 0);
        float x = cx - total / 2f;
        iconsRect.set(x, top, x + total, top + size);
        for (int i = 0; i < max; i++) {
            Drawable d = icon(items.get(i).pkg + "/small", items.get(i).smallIcon, items.get(i).pkg, true);
            if (d != null) {
                d.setBounds((int) x, (int) top, (int) (x + size), (int) (top + size));
                d.draw(c);
            }
            x += size + gap;
        }
        if (items.size() > max) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.rgb(160, 160, 160));
            p.setTextSize(size * 0.7f);
            c.drawText("+" + (items.size() - max), x, top + size * 0.75f, p);
        }
    }

    private void drawPopup(Canvas c, float w, float top, float s) {
        float cardW = Math.min(w * 0.6f, s * 0.95f), pad = s * 0.025f, iconSize = s * 0.07f;
        float left = (w - cardW) / 2f;

        TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.rgb(235, 235, 235));
        title.setTextSize(s * 0.034f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(Color.rgb(175, 175, 175));
        body.setTextSize(s * 0.03f);
        TextPaint app = new TextPaint(body);
        app.setTextSize(s * 0.024f);
        app.setColor(prefs.accent);

        int textW = (int) (cardW - pad * 3 - iconSize);
        String t = popup.title.isEmpty() ? appLabel(popup.pkg) : popup.title;
        StaticLayout tl = layout(t, title, textW, 1);
        StaticLayout bl = layout(popup.text, body, textW, 3);
        float textH = app.getTextSize() * 1.4f + tl.getHeight() + (popup.text.isEmpty() ? 0 : bl.getHeight() + pad * 0.3f);
        float cardH = Math.max(iconSize, textH) + pad * 2;
        // Keep the whole card on screen, even under tall clock styles.
        top = Math.min(top, getHeight() - cardH - s * 0.05f);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(235, 28, 28, 30));
        c.drawRoundRect(new RectF(left, top, left + cardW, top + cardH), pad * 1.4f, pad * 1.4f, bg);

        Drawable icon = popup.largeIcon != null
                ? icon(popup.key + "/large", popup.largeIcon, popup.pkg, false)
                : appIcon(popup.pkg);
        if (icon != null) {
            icon.setBounds((int) (left + pad), (int) (top + pad), (int) (left + pad + iconSize), (int) (top + pad + iconSize));
            icon.draw(c);
        }

        float tx = left + pad * 2 + iconSize, ty = top + pad;
        c.drawText(appLabel(popup.pkg), tx, ty + app.getTextSize(), app);
        ty += app.getTextSize() * 1.4f;
        c.save();
        c.translate(tx, ty);
        tl.draw(c);
        c.translate(0, tl.getHeight() + pad * 0.3f);
        if (!popup.text.isEmpty()) bl.draw(c);
        c.restore();
    }

    /** The last Now bar icon and its drawable: album art changes per track, so cache only one. */
    private Icon nowIcon;
    private Drawable nowDrawable;

    private void drawNowBar(Canvas c, float w, float h, float s, float ox, float oy) {
        NotifStore.Now now = NotifStore.now();
        if (now == null) return;
        float barH = s * 0.085f, pad = s * 0.018f, iconSize = barH - pad * 2;
        float barW = Math.min(w * 0.42f, s * 0.8f);
        float left = (w - barW) / 2f + ox, top = h - barH - s * 0.06f + oy;
        RectF bar = new RectF(left, top, left + barW, top + barH);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(230, 30, 30, 34));
        c.drawRoundRect(bar, barH / 2f, barH / 2f, bg);

        // Icon: album art (rounded) for music, otherwise the app's small icon in white.
        if (now.icon != nowIcon) {
            nowIcon = now.icon;
            nowDrawable = null;
            try { if (now.icon != null) nowDrawable = now.icon.loadDrawable(getContext()); } catch (Exception ignored) { }
            if (nowDrawable == null) nowDrawable = appIcon(now.pkg);
            if (nowDrawable != null && now.kind != NotifStore.Now.MEDIA) {
                nowDrawable = nowDrawable.mutate();
                nowDrawable.setColorFilter(new PorterDuffColorFilter(Color.rgb(225, 225, 225), PorterDuff.Mode.SRC_IN));
            }
        }
        float ix = left + pad * 1.4f, iy = top + pad;
        if (nowDrawable != null) {
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(new RectF(ix, iy, ix + iconSize, iy + iconSize), iconSize / 2f, iconSize / 2f, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            float inset = now.kind == NotifStore.Now.MEDIA ? 0 : iconSize * 0.22f;
            nowDrawable.setBounds((int) (ix + inset), (int) (iy + inset), (int) (ix + iconSize - inset), (int) (iy + iconSize - inset));
            if (now.kind != NotifStore.Now.MEDIA) {
                Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
                disc.setColor(Color.argb(255, 52, 52, 58));
                c.drawCircle(ix + iconSize / 2f, iy + iconSize / 2f, iconSize / 2f, disc);
            }
            nowDrawable.draw(c);
            c.restore();
        }

        // Right side: time left / elapsed, or a percentage, in the accent colour.
        String status = nowStatus(now);
        TextPaint st = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        st.setColor(prefs.accent);
        st.setTextSize(s * 0.028f);
        st.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        st.setTextAlign(Paint.Align.RIGHT);
        float right = left + barW - pad * 2f;
        float statusW = status.isEmpty() ? 0 : st.measureText(status) + pad;
        if (!status.isEmpty()) c.drawText(status, right, top + barH / 2f + st.getTextSize() * 0.35f, st);

        // Middle: title over subtitle (or the app's name).
        TextPaint t1 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        t1.setColor(Color.rgb(235, 235, 235));
        t1.setTextSize(s * 0.027f);
        t1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextPaint t2 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        t2.setColor(Color.rgb(160, 160, 165));
        t2.setTextSize(s * 0.023f);
        float tx = ix + iconSize + pad * 1.2f, textW = right - statusW - tx;
        String title = now.title.isEmpty() ? appLabel(now.pkg) : now.title;
        String sub = now.text.isEmpty() ? (now.title.isEmpty() ? "" : appLabel(now.pkg)) : now.text;
        CharSequence a = TextUtils.ellipsize(title, t1, Math.max(1, textW), TextUtils.TruncateAt.END);
        CharSequence b = TextUtils.ellipsize(sub, t2, Math.max(1, textW), TextUtils.TruncateAt.END);
        if (b.length() == 0) {
            c.drawText(a, 0, a.length(), tx, top + barH / 2f + t1.getTextSize() * 0.35f, t1);
        } else {
            float mid = top + barH / 2f;
            c.drawText(a, 0, a.length(), tx, mid - t1.getTextSize() * 0.12f, t1);
            c.drawText(b, 0, b.length(), tx, mid + t2.getTextSize() * 1.05f, t2);
        }

        // Progress along the bottom edge.
        if (now.max > 0 && now.kind == NotifStore.Now.PROGRESS) {
            float frac = Math.max(0f, Math.min(1f, now.progress / (float) now.max));
            Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
            track.setStrokeWidth(s * 0.004f);
            track.setStrokeCap(Paint.Cap.ROUND);
            float y = top + barH - pad * 0.6f, x0 = tx, x1 = right;
            track.setColor(Color.argb(70, 255, 255, 255));
            c.drawLine(x0, y, x1, y, track);
            track.setColor(prefs.accent);
            c.drawLine(x0, y, x0 + (x1 - x0) * frac, y, track);
        }
    }

    /**
     * The Now bar's status text. The AOD only redraws once a minute, so times are shown in
     * minutes, never seconds.
     */
    private static String nowStatus(NotifStore.Now n) {
        long now = System.currentTimeMillis();
        if (n.kind == NotifStore.Now.MEDIA) return "\u266B";
        if (n.chrono && n.when > 0) {
            long ms = n.countDown ? n.when - now : now - n.when;
            if (ms < 0) ms = 0;
            long min = n.countDown ? (ms + 59_999) / 60_000 : ms / 60_000;
            String t = min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
            return n.countDown ? t + " left" : t;
        }
        if (n.max > 0) return Math.round(100f * n.progress / n.max) + "%";
        return "";
    }

    private void drawWallpaper(Canvas c, float w, float h) {
        float scale = Math.max(w / wallpaper.getWidth(), h / wallpaper.getHeight());
        float bw = wallpaper.getWidth() * scale, bh = wallpaper.getHeight() * scale;
        RectF dst = new RectF((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(wallpaper, null, dst, p);
        // Darken: keep only wallpaperPct of the original light; lift the shade while waking up.
        int alpha = 255 - Math.round(255 * Math.max(1, Math.min(60, prefs.wallpaperPct)) / 100f);
        c.drawColor(Color.argb(Math.round(alpha * (1f - wake)), 0, 0, 0));
    }

    private Bitmap loadWallpaper() {
        File f = new File(getContext().getFilesDir(), "wallpaper.img");
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int target = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private Drawable icon(String cacheKey, Icon ic, String pkg, boolean tintWhite) {
        if (iconCache.containsKey(cacheKey)) return iconCache.get(cacheKey);
        Drawable d = null;
        try {
            if (ic != null) d = ic.loadDrawable(getContext());
        } catch (Exception ignored) { }
        if (d == null) d = appIcon(pkg);
        if (d != null && tintWhite) {
            d = d.mutate();
            d.setColorFilter(new PorterDuffColorFilter(Color.rgb(200, 200, 200), PorterDuff.Mode.SRC_IN));
        }
        iconCache.put(cacheKey, d);
        return d;
    }

    private Drawable appIcon(String pkg) {
        try {
            return getContext().getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static StaticLayout layout(String text, TextPaint p, int width, int maxLines) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(1, width))
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build();
    }
}
