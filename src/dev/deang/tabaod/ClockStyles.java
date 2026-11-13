package dev.deang.tabaod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.util.Calendar;

/**
 * TabAOD clock faces. Each draws centred on (cx, cy) inside a box of size s and returns the
 * lowest y it used. {@code lock} goes from 0 (AOD) to 1 (lock screen) during transitions, so
 * every face can animate between a lighter AOD look and a fuller lock screen look.
 *
 * Fonts (bundled in assets/fonts, SIL Open Font License 1.1, see assets/fonts/licenses):
 * Fredoka, Big Shoulders Display, Bodoni Moda. The AOSP face uses the system's AndroidClock.ttf.
 */
final class ClockStyles {
    static final String[] NAMES = {"AOSP", "Bubble", "Tall", "Numbers", "Serif", "Compact", "Rounded", "Lock screen"};
    static final int AOSP = 0, BUBBLE = 1, TALL = 2, NUMBERS = 3, SERIF = 4, COMPACT = 5, ROUNDED = 6, LOCK = 7;

    private static Typeface androidClock, fredoka, bigShoulders, bodoni, lockFont;

    /** Loads the fonts once. Safe to call repeatedly. */
    static synchronized void init(Context c) {
        if (fredoka != null) return;
        androidClock = loadSystemClockFont();
        fredoka = asset(c, "fonts/Fredoka.ttf");
        bigShoulders = asset(c, "fonts/BigShouldersDisplay.ttf");
        bodoni = asset(c, "fonts/BodoniModa.ttf");
        lockFont = clockPackFont(c, LOCK_FONT);
    }

    /** One UI's lock screen clock font (font type 901, "Stretch"), in Samsung's clock pack. */
    static final String CLOCK_PACK = "com.samsung.android.app.clockpack", LOCK_FONT = "fonts/9_One_Stretch_Clock.ttf";

    /**
     * Loads a font from the clock pack installed on the device (it isn't bundled with this
     * app). Null if the clock pack or the font isn't there.
     */
    private static Typeface clockPackFont(Context c, String path) {
        try {
            Context pack = c.createPackageContext(CLOCK_PACK, 0);
            return new Typeface.Builder(pack.getAssets(), path).build();
        } catch (Exception e) {
            return null;
        }
    }

    static float draw(int style, Canvas c, float cx, float cy, float s, Calendar t, boolean is24,
                      int accent, float lock, boolean bold) {
        String hh = is24 ? two(t.get(Calendar.HOUR_OF_DAY)) : two(hour12(t));
        String mm = two(t.get(Calendar.MINUTE));
        lock = Math.max(0f, Math.min(1f, lock));
        int color = blend(Color.WHITE, accent, lock);
        switch (style) {
            case BUBBLE: return bubble(c, cx, cy, s, hh, mm, color, lock);
            case TALL: return tall(c, cx, cy, s, hh, mm, color, lock, bold);
            case NUMBERS: return numbers(c, cx, cy, s, t, accent, lock);
            case SERIF: return serif(c, cx, cy, s, hh, mm, color, lock, bold);
            case COMPACT: return compact(c, cx, cy, s, hh + ":" + mm, color, lock, bold);
            case ROUNDED: return rounded(c, cx, cy, s, hh, mm, color, lock, bold);
            case LOCK: return lockScreen(c, cx, cy, s, hh + ":" + mm, color, lock, bold);
            default: return aosp(c, cx, cy, s, hh, mm, color, lock, bold);
        }
    }

    /** Whether the style already shows the date itself. */
    static boolean showsDate(int style) {
        return false;
    }

    /*
     * "AOSP" face, adapted from the Android Open Source Project's AnimatableClockView
     * (packages/SystemUI/customization/.../shared/clocks/AnimatableClockView.kt).
     * Copyright (C) 2021 The Android Open Source Project.
     * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
     * Changes: rewritten in Java for TabAOD; drawn on a Canvas instead of a TextView with
     * TextAnimator; the weight is set through font variation settings each frame.
     * Design kept from AOSP: hours above minutes, Android's clock font, weight 100 when
     * dozing and 300 on the lock screen (+100 with bold text), white when dozing.
     */
    static final int AOSP_DOZE_WEIGHT = 100, AOSP_LOCK_WEIGHT = 300;

    private static float aosp(Canvas c, float cx, float cy, float s, String hh, String mm,
                              int color, float lock, boolean bold) {
        float weight = AOSP_DOZE_WEIGHT + (bold ? 100 : 0) + (AOSP_LOCK_WEIGHT - AOSP_DOZE_WEIGHT) * lock;
        Paint p = text(androidClock, s * 0.30f, color);
        p.setFontVariationSettings("'wght' " + Math.round(weight));
        return stacked(c, p, cx, cy, hh, mm, 0.22f, s);
    }

    /**
     * Compact: one line, Android's clock font, for layouts with widgets under the clock.
     * Lighter on the AOD, heavier on the lock screen side of the transition.
     */
    private static float compact(Canvas c, float cx, float cy, float s, String time,
                                 int color, float lock, boolean bold) {
        float weight = 300 + (bold ? 100 : 0) + 200 * lock;
        Paint p = text(androidClock, s * 0.09f, color);
        p.setFontVariationSettings("'wght' " + Math.round(weight));
        android.graphics.Rect r = new android.graphics.Rect();
        p.getTextBounds("0123456789", 0, 10, r);
        float baseline = cy + r.height() / 2f;
        c.drawText(time, cx, baseline, p);
        return baseline + s * 0.01f;
    }

    /**
     * Lock screen: the same face as One UI's lock screen clock (its condensed, round-ended
     * "Stretch" font, heavy, one line), at the lock screen's size: digits 6.2% of the short
     * side tall. Falls back to Big Shoulders when the clock pack isn't installed.
     */
    private static float lockScreen(Canvas c, float cx, float cy, float s, String time,
                                    int color, float lock, boolean bold) {
        Typeface tf = lockFont != null ? lockFont : bigShoulders;
        Paint p = text(tf, 100f, color);
        int weight = Math.min(1000, 880 + (bold ? 60 : 0) + Math.round(60 * lock));
        p.setFontVariationSettings("'wght' " + weight);
        p.setLetterSpacing(0.07f); // One UI spaces this face out a little
        android.graphics.Rect r = new android.graphics.Rect();
        p.getTextBounds("0123456789", 0, 10, r);
        // Scale so the digits are as tall as on the lock screen.
        p.setTextSize(100f * (s * 0.062f) / Math.max(1, r.height()));
        p.getTextBounds("0123456789", 0, 10, r);
        float baseline = cy + r.height() / 2f;
        c.drawText(time, cx, baseline, p);
        return baseline + s * 0.01f;
    }

    /**
     * Rounded: one line of heavy, soft-cornered digits (Fredoka) with a small accent-free
     * colon dot pair, for layouts with widgets under the clock. Weight 560 on the AOD,
     * 650 on the lock screen side of the transition.
     */
    private static float rounded(Canvas c, float cx, float cy, float s, String hh, String mm,
                                 int color, float lock, boolean bold) {
        float weight = 560 + (bold ? 60 : 0) + 90 * lock;
        Paint p = text(fredoka, s * 0.092f, color);
        p.setFontVariationSettings("'wght' " + Math.round(weight) + ", 'wdth' 100");
        android.graphics.Rect r = new android.graphics.Rect();
        p.getTextBounds("0123456789", 0, 10, r);
        float baseline = cy + r.height() / 2f;
        // Hours and minutes either side of a colon made of two round dots.
        float gap = s * 0.022f, dot = s * 0.0068f;
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(hh, cx - gap, baseline, p);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(mm, cx + gap, baseline, p);
        Paint d = new Paint(Paint.ANTI_ALIAS_FLAG);
        d.setColor(color);
        c.drawCircle(cx, cy - r.height() * 0.18f, dot, d);
        c.drawCircle(cx, cy + r.height() * 0.18f, dot, d);
        return baseline + s * 0.01f;
    }

    /** Bubble: wide, heavy rounded digits that overlap. Outlined on the AOD, filled on the lock screen. */
    private static float bubble(Canvas c, float cx, float cy, float s, String hh, String mm,
                                int color, float lock) {
        Paint fill = text(fredoka, s * 0.36f, color);
        fill.setFontVariationSettings("'wght' " + Math.round(560 + 140 * lock) + ", 'wdth' 125");
        float[] rows = rowBaselines(fill, cy, -0.04f, s);
        float base1 = rows[0], base2 = rows[1];

        Paint outline = new Paint(fill);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(s * 0.006f);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setAlpha(Math.round(255 * (1f - lock)));
        fill.setAlpha(Math.round(255 * lock));

        float nudge = s * 0.02f;
        drawTilted(c, hh, cx - nudge, base1, -4f, fill, outline);
        drawTilted(c, mm, cx + nudge, base2, 3f, fill, outline);
        return base2 + s * 0.04f;
    }

    /** Tall: big condensed digits, thin on the AOD and bold on the lock screen. */
    private static float tall(Canvas c, float cx, float cy, float s, String hh, String mm,
                              int color, float lock, boolean bold) {
        Paint p = text(bigShoulders, s * 0.34f, color);
        p.setFontVariationSettings("'wght' " + Math.round(200 + (bold ? 100 : 0) + 500 * lock));
        return stacked(c, p, cx, cy, hh, mm, 0.10f, s);
    }

    /** Serif: narrow, high-contrast display serif. */
    private static float serif(Canvas c, float cx, float cy, float s, String hh, String mm,
                               int color, float lock, boolean bold) {
        Paint p = text(bodoni, s * 0.30f, color);
        p.setFontVariationSettings("'wght' " + Math.round(400 + (bold ? 100 : 0) + 400 * lock) + ", 'opsz' 96");
        p.setTextScaleX(0.74f);
        return stacked(c, p, cx, cy, hh, mm, 0.12f, s);
    }

    /** Numbers: an analog face where 12, 3, 6 and 9 are big numerals at mixed sizes and angles. */
    private static float numbers(Canvas c, float cx, float cy, float s, Calendar t, int accent, float lock) {
        float r = s * 0.23f;
        String[] labels = {"12", "3", "6", "9"};
        float[] sizes = {0.20f, 0.15f, 0.17f, 0.15f};
        float[] tilt = {-6f, 5f, -3f, 6f};
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(i * 90);
            float x = cx + (float) Math.sin(a) * r;
            float y = cy - (float) Math.cos(a) * r;
            Paint fill = text(fredoka, s * sizes[i], i == 0 ? blend(Color.WHITE, accent, lock) : Color.rgb(225, 225, 225));
            fill.setFontVariationSettings("'wght' " + Math.round(560 + 140 * lock) + ", 'wdth' 110");
            Paint.FontMetrics fm = fill.getFontMetrics();
            float base = y - (fm.ascent + fm.descent) / 2f;
            Paint outline = new Paint(fill);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(s * 0.004f);
            outline.setAlpha(Math.round(255 * (1f - lock)));
            fill.setAlpha(Math.round(255 * lock));
            drawTilted(c, labels[i], x, base, tilt[i], fill, outline);
        }
        float m = t.get(Calendar.MINUTE);
        float h = t.get(Calendar.HOUR) + m / 60f;
        hand(c, cx, cy, h * 30, r * 0.62f, stroke(s * 0.016f, Color.rgb(235, 235, 235)));
        hand(c, cx, cy, m * 6, r * 0.82f, stroke(s * 0.008f, accent));
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(accent);
        c.drawCircle(cx, cy, s * 0.014f, dot);
        return cy + r + s * 0.10f;
    }

    // --- helpers ---

    /**
     * Hours above minutes, spaced by the real digit height (not the font's line height, which
     * varies a lot between fonts). {@code gap} is the space between the rows as a fraction of
     * the digit height; negative values overlap. The block is centred on cy but kept on screen.
     */
    private static float stacked(Canvas c, Paint p, float cx, float cy, String hh, String mm, float gap, float s) {
        float[] rows = rowBaselines(p, cy, gap, s);
        c.drawText(hh, cx, rows[0], p);
        c.drawText(mm, cx, rows[1], p);
        return rows[1] + s * 0.04f;
    }

    /** Baselines for two stacked rows of digits, centred on cy and never above the top margin. */
    private static float[] rowBaselines(Paint p, float cy, float gap, float s) {
        android.graphics.Rect b = new android.graphics.Rect();
        p.getTextBounds("0123456789", 0, 10, b);
        float h = b.height();
        float total = h * 2 + h * gap;
        float top = Math.max(s * 0.05f, cy - total / 2f);
        float base1 = top + h;
        return new float[]{base1, base1 + h + h * gap};
    }

    private static void drawTilted(Canvas c, String text, float x, float baseline, float deg, Paint fill, Paint outline) {
        c.save();
        c.rotate(deg, x, baseline - fill.getTextSize() * 0.35f);
        if (fill.getAlpha() > 0) c.drawText(text, x, baseline, fill);
        if (outline.getAlpha() > 0) c.drawText(text, x, baseline, outline);
        c.restore();
    }

    private static void hand(Canvas c, float cx, float cy, float deg, float len, Paint p) {
        double a = Math.toRadians(deg);
        c.drawLine(cx, cy, cx + (float) Math.sin(a) * len, cy - (float) Math.cos(a) * len, p);
    }

    private static Paint text(Typeface tf, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        return p;
    }

    private static Paint stroke(float w, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        return p;
    }

    static int blend(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    private static Typeface asset(Context c, String path) {
        try {
            return new Typeface.Builder(c.getAssets(), path).build();
        } catch (Exception e) {
            return Typeface.DEFAULT_BOLD;
        }
    }

    private static Typeface loadSystemClockFont() {
        java.io.File f = new java.io.File("/system/fonts/AndroidClock.ttf");
        try {
            if (f.exists()) return new Typeface.Builder(f).build();
        } catch (Exception ignored) { }
        return Typeface.create("sans-serif-thin", Typeface.NORMAL);
    }

    private static int hour12(Calendar t) {
        int h = t.get(Calendar.HOUR);
        return h == 0 ? 12 : h;
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
