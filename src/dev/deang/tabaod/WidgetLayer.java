package dev.deang.tabaod;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Home-screen widgets on the AOD, each drawn through a colour filter that dims it
 * (and optionally turns it grey). Positions come from {@link WidgetStore}.
 */
public class WidgetLayer extends FrameLayout {
    private final List<WidgetStore.Entry> entries = new ArrayList<>();
    private final List<AppWidgetHostView> views = new ArrayList<>();
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private java.util.Set<Integer> mirrored = new java.util.HashSet<>();
    private final java.util.Map<View, Boolean> inverted = new java.util.WeakHashMap<>();
    private Prefs prefs;
    private boolean editing;
    private int selected = -1;

    /** Tell each widget its size once things settle (not on every frame of a pinch). */
    private final Runnable reportSizes = this::reportSizes;

    public WidgetLayer(Context c) {
        super(c);
        setWillNotDraw(false);
        frame.setStyle(Paint.Style.STROKE);
    }

    public void setEditing(boolean on) {
        editing = on;
        if (on) { setTranslationX(0); setTranslationY(0); }
        invalidate();
    }

    /** Rebuild the widget views from the saved list. */
    public void reload() {
        removeAllViews();
        views.clear();
        entries.clear();
        prefs = Prefs.load(getContext());
        mirrored = LockWidgets.mirroredIds(getContext());
        if (!prefs.showWidgets) return;
        AppWidgetManager awm = AppWidgetManager.getInstance(getContext());
        for (WidgetStore.Entry e : WidgetStore.load(getContext())) {
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(e.id);
            if (info == null) continue; // app removed or id no longer bound
            AppWidgetHostView v = WidgetStore.host(getContext()).createView(getContext(), e.id, info);
            v.setPadding(0, 0, 0, 0);
            applyDim(v);
            addView(v);
            entries.add(e);
            views.add(v);
        }
        requestLayout();
    }

    /** Burn-in shift, matching the clock's. */
    public void setShift(int dx, int dy) {
        if (editing) return;
        setTranslationX(dx);
        setTranslationY(dy);
    }

    // --- blur behind widget backgrounds ---

    private Bitmap blurred;
    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix blurMatrix = new Matrix();
    /** A faint white wash on the blur, so tiles read as frosted glass. */
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    { sheen.setColor(Color.argb(12, 255, 255, 255)); }

    /**
     * Gives the layer the AOD wallpaper. Like Samsung's own widget hosts, a widget's
     * {@code @android:id/background} view then gets a blurred copy of the wallpaper under it,
     * tinted by the widget's own (semi-transparent) background colour.
     */
    public void setBackdrop(Bitmap wallpaper, int wallpaperPct) {
        blurred = null;
        if (wallpaper == null || !prefs.widgetBlur) { invalidate(); return; }
        int w = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        if (h > w) { int t = w; w = h; h = t; }
        // Work at 1/10 size: cheap, and the upscale adds to the softness.
        int bw = Math.max(8, w / 10), bh = Math.max(8, h / 10);
        Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        float sc = Math.max((float) bw / wallpaper.getWidth(), (float) bh / wallpaper.getHeight());
        float dw = wallpaper.getWidth() * sc, dh = wallpaper.getHeight() * sc;
        c.drawBitmap(wallpaper, null, new RectF((bw - dw) / 2f, (bh - dh) / 2f, (bw + dw) / 2f, (bh + dh) / 2f),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        boxBlur(small, 5, 3);
        // A little lighter than the AOD wallpaper itself, so the tiles read as glass.
        float light = Math.min(100, Math.max(1, wallpaperPct) * 1.15f) / 100f;
        c.drawColor(Color.argb(Math.round(255 * (1f - light)), 0, 0, 0));
        blurred = small;
        blurPaint.setShader(new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        invalidate();
    }

    /** In-place box blur, run a few times (close to a Gaussian). */
    private static void boxBlur(Bitmap b, int radius, int passes) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            blur1d(px, tmp, w, h, radius, true);
            blur1d(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blur1d(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int sr = 0, sg = 0, sb = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int c = in[horizontal ? line * w + k : k * w + line];
                    sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; n++;
                }
                out[horizontal ? line * w + i : i * w + line] = 0xFF000000 | (sr / n << 16) | (sg / n << 8) | (sb / n);
            }
        }
    }

    /** Draws the blur under every widget that marks its background the standard way. */
    private void drawBlurs(Canvas c) {
        if (blurred == null) return;
        float W = getWidth(), H = getHeight();
        // The wallpaper doesn't move with the burn-in shift, so undo it for the blur too.
        blurMatrix.setScale(W / blurred.getWidth(), H / blurred.getHeight());
        blurMatrix.postTranslate(-getTranslationX(), -getTranslationY());
        blurPaint.getShader().setLocalMatrix(blurMatrix);
        float maxRadius = widgetRadius();
        android.graphics.Rect r = new android.graphics.Rect();
        for (int i = 0; i < views.size(); i++) {
            AppWidgetHostView v = views.get(i);
            View bg = v.findViewById(android.R.id.background);
            if (mirrored.contains(entries.get(i).id)) {
                // Like the lock screen's own host: a glass tile behind the whole cell.
                r.set(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
            } else if (bg != null && bg.getWidth() > 0 && bg.getVisibility() == VISIBLE) {
                bg.getDrawingRect(r);
                offsetDescendantRectToMyCoords(bg, r);
            } else {
                continue;
            }
            // Rounded squares like the lock screen's tiles, never circles on small ones.
            float radius = Math.min(maxRadius, Math.min(r.width(), r.height()) * 0.27f);
            c.drawRoundRect(new RectF(r), radius, radius, blurPaint);
            c.drawRoundRect(new RectF(r), radius, radius, sheen);
        }
    }

    private float widgetRadius() {
        return 20 * getResources().getDisplayMetrics().density;
    }

    // --- colour: dim, grey, and invert where the content is dark ---

    /**
     * Whether a widget's content is dark (so it should be inverted to read on the AOD).
     * Its own background view is left out; only text and icons count, unless the widget
     * is an opaque card, in which case a light card counts as needing inversion.
     */
    private static boolean needsInvert(AppWidgetHostView v) {
        View content = v.getChildCount() > 0 ? v.getChildAt(0) : null;
        if (content == null || content.getWidth() < 4 || content.getHeight() < 4) return false;
        // Record into a Picture and render it with the GPU: widgets often use hardware
        // bitmaps, which a software canvas can't draw.
        android.graphics.Picture pic = new android.graphics.Picture();
        Canvas c = pic.beginRecording(content.getWidth(), content.getHeight());
        View bg = v.findViewById(android.R.id.background);
        // Leave out the background fill only: a background view often also holds the content.
        android.graphics.drawable.Drawable bgFill = bg != null ? bg.getBackground() : null;
        boolean leaf = bg != null && !(bg instanceof android.view.ViewGroup);
        float bgAlpha = bg != null ? bg.getAlpha() : 1f;
        try {
            if (bg != null) {
                if (leaf) bg.setAlpha(0f);
                else bg.setBackground(null);
            }
            content.draw(c);
        } catch (Exception e) {
            return false;
        } finally {
            pic.endRecording();
            if (bg != null) {
                if (leaf) bg.setAlpha(bgAlpha);
                else bg.setBackground(bgFill);
            }
        }
        Bitmap b;
        try {
            Bitmap hw = Bitmap.createBitmap(pic); // rendered by the GPU (HARDWARE config)
            Bitmap full = hw.copy(Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            b = Bitmap.createScaledBitmap(full, Math.max(1, full.getWidth() / 4), Math.max(1, full.getHeight() / 4), true);
            if (b != full) full.recycle();
        } catch (Exception e) {
            return false;
        }
        if (b == null) return false;
        int bw = b.getWidth(), bh = b.getHeight();
        int[] px = new int[bw * bh];
        b.getPixels(px, 0, bw, 0, 0, bw, bh);
        double sumA = 0, sumL = 0;
        for (int p : px) {
            double a = (p >>> 24) / 255.0;
            if (a < 0.05) continue;
            double l = (0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255)) / 255.0;
            sumA += a;
            sumL += a * l;
        }
        if (sumA <= 0) return false;
        double lum = sumL / sumA, coverage = sumA / px.length;
        return coverage > 0.6 ? lum > 0.55 : lum < 0.45;
    }

    /** Re-checks each widget's content (it may have updated) and refreshes its filter. */
    public void refreshColours() {
        for (int i = 0; i < views.size(); i++) {
            AppWidgetHostView v = views.get(i);
            if (mirrored.contains(entries.get(i).id)) glassStyle(v);
            applyDim(v);
        }
    }

    /**
     * Lock screen look for copied lock screen widgets: their own background fill goes (the
     * glass tile replaces it) and dark text and icons turn white, so they read on the AOD.
     * Runs again after updates, since an update re-applies the widget's own colours.
     */
    private void glassStyle(AppWidgetHostView v) {
        View bg = v.findViewById(android.R.id.background);
        if (bg != null) {
            if (bg instanceof android.view.ViewGroup) bg.setBackground(null);
            else bg.setAlpha(0f);
        }
        if (v.getChildCount() > 0) lighten(v.getChildAt(0));
    }

    private boolean isMirrored(AppWidgetHostView v) {
        return mirrored.contains(v.getAppWidgetId());
    }

    private static final int LIGHT = Color.rgb(240, 240, 240);

    static void lighten(View view) {
        if (view instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) view;
            int col = t.getCurrentTextColor();
            if (luminance(col) < 0.7) t.setTextColor(Color.argb(Math.max(Color.alpha(col), 200), 240, 240, 240));
        } else if (view instanceof android.widget.ImageView) {
            android.widget.ImageView iv = (android.widget.ImageView) view;
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d != null && isDark(d)) iv.setColorFilter(LIGHT, android.graphics.PorterDuff.Mode.SRC_IN);
        } else if (view instanceof android.widget.ProgressBar) {
            android.widget.ProgressBar pb = (android.widget.ProgressBar) view;
            if (pb.getProgressTintList() != null
                    && luminance(pb.getProgressTintList().getDefaultColor()) < 0.5) {
                pb.setProgressTintList(android.content.res.ColorStateList.valueOf(LIGHT));
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) lighten(g.getChildAt(i));
        }
    }

    private static double luminance(int c) {
        return (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
    }

    /** Whether a drawable is mostly dark and not colourful (a black glyph, not a photo). */
    private static boolean isDark(android.graphics.drawable.Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            android.graphics.Rect old = new android.graphics.Rect(d.getBounds());
            d.setBounds(0, 0, 16, 16);
            d.draw(c);
            d.setBounds(old);
            double sa = 0, sl = 0, sat = 0;
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int p = b.getPixel(x, y);
                double a = Color.alpha(p) / 255.0;
                if (a < 0.1) continue;
                float[] hsv = new float[3];
                Color.colorToHSV(p, hsv);
                sa += a; sl += a * luminance(p); sat += a * hsv[1];
            }
            return sa > 0 && sl / sa < 0.4 && sat / sa < 0.3;
        } catch (Exception e) {
            return false; // hardware bitmaps etc.: leave as is
        }
    }

    private void applyDim(View v) {
        boolean invert = prefs.widgetInvert && v instanceof AppWidgetHostView
                && !isMirrored((AppWidgetHostView) v) && needsInvert((AppWidgetHostView) v);
        Boolean was = inverted.get(v);
        if (was != null && was == invert) return;
        inverted.put(v, invert);
        float level = Math.max(5, Math.min(100, prefs.widgetPct)) / 100f;
        if (level >= 1f && !prefs.widgetGrey && !invert) {
            v.setLayerType(LAYER_TYPE_NONE, null);
            return;
        }
        ColorMatrix m = new ColorMatrix();
        if (invert) {
            // Invert lightness but keep hues: invert, then turn the hue wheel half way back.
            m.set(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0});
            m.postConcat(hueRotate180());
        }
        if (prefs.widgetGrey) {
            ColorMatrix g = new ColorMatrix();
            g.setSaturation(0f);
            m.postConcat(g);
        }
        m.postConcat(new ColorMatrix(new float[]{
                level, 0, 0, 0, 0,
                0, level, 0, 0, 0,
                0, 0, level, 0, 0,
                0, 0, 0, 1, 0}));
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(m));
        v.setLayerType(LAYER_TYPE_HARDWARE, p);
    }

    /** Standard hue rotation by 180 degrees (cos = -1, sin = 0) around the luminance axis. */
    private static ColorMatrix hueRotate180() {
        float lr = 0.213f, lg = 0.715f, lb = 0.072f;
        return new ColorMatrix(new float[]{
                2 * lr - 1, 2 * lg, 2 * lb, 0, 0,
                2 * lr, 2 * lg - 1, 2 * lb, 0, 0,
                2 * lr, 2 * lg, 2 * lb - 1, 0, 0,
                0, 0, 0, 1, 0});
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        WidgetStore.listen(getContext(), true);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(reportSizes);
        WidgetStore.listen(getContext(), false);
        super.onDetachedFromWindow();
    }

    // --- layout: absolute positions from the entries ---

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        setMeasuredDimension(w, h);
        for (int i = 0; i < views.size(); i++) {
            RectF r = rect(i, w, h);
            views.get(i).measure(MeasureSpec.makeMeasureSpec(Math.round(r.width()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(r.height()), MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (AppWidgetHostView v : views) {
            if (v.getPaddingLeft() != 0 || v.getPaddingTop() != 0) v.setPadding(0, 0, 0, 0);
        }
        for (int i = 0; i < views.size(); i++) {
            RectF rc = rect(i, getWidth(), getHeight());
            views.get(i).layout(Math.round(rc.left), Math.round(rc.top), Math.round(rc.right), Math.round(rc.bottom));
        }
        removeCallbacks(reportSizes);
        postDelayed(reportSizes, 500);
    }

    private RectF rect(int i, float w, float h) {
        WidgetStore.Entry e = entries.get(i);
        float ww = e.w * w, hh = e.h * h, cx = e.x * w, cy = e.y * h;
        return new RectF(cx - ww / 2f, cy - hh / 2f, cx + ww / 2f, cy + hh / 2f);
    }

    private void reportSizes() {
        refreshColours();
        float density = getResources().getDisplayMetrics().density;
        AppWidgetManager awm = AppWidgetManager.getInstance(getContext());
        for (int i = 0; i < views.size(); i++) {
            AppWidgetHostView v = views.get(i);
            // Copied lock screen widgets keep the exact size and options they were bound with;
            // reporting a new set would turn them back into their home screen style.
            if (mirrored.contains(entries.get(i).id)) {
                LockWidgets.restoreOptions(getContext(), entries.get(i).id);
                continue;
            }
            if (v.getWidth() == 0) continue;
            SizeF dp = new SizeF(v.getWidth() / density, v.getHeight() / density);
            try {
                // Start from the current options so nothing else the widget was given is lost.
                Bundle opts = awm.getAppWidgetOptions(entries.get(i).id);
                v.updateAppWidgetSize(opts != null ? opts : new Bundle(), Collections.singletonList(dp));
            } catch (Exception ignored) { }
        }
    }

    // --- editing (used by EditorActivity) ---

    public int count() {
        return views.size();
    }

    /** Index of the top-most widget under (x, y), or -1. */
    public int hitTest(float x, float y) {
        for (int i = views.size() - 1; i >= 0; i--) {
            if (rect(i, getWidth(), getHeight()).contains(x, y)) return i;
        }
        return -1;
    }

    public void select(int i) {
        selected = i;
        invalidate();
    }

    public void moveBy(int i, float dx, float dy) {
        WidgetStore.Entry e = entries.get(i);
        e.x = clamp(e.x + dx / getWidth(), 0f, 1f);
        e.y = clamp(e.y + dy / getHeight(), 0f, 1f);
        requestLayout();
        invalidate();
    }

    public void scaleBy(int i, float f) {
        WidgetStore.Entry e = entries.get(i);
        float nw = clamp(e.w * f, 0.08f, 0.95f), nh = clamp(e.h * f, 0.05f, 0.9f);
        // Keep the aspect ratio when one side hits its limit.
        float k = Math.min(nw / e.w, nh / e.h);
        e.w *= k;
        e.h *= k;
        requestLayout();
        invalidate();
    }

    public boolean contains(int i, float x, float y) {
        return i >= 0 && i < views.size() && rect(i, getWidth(), getHeight()).contains(x, y);
    }

    /** Adds a newly bound widget under the other items (y = their lowest point), at roughly its natural size. */
    public void add(int id, AppWidgetProviderInfo info, float below) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        float ww = clamp(Math.max(info.minWidth, s * 0.42f), s * 0.15f, w * 0.9f);
        float hh = clamp(Math.max(info.minHeight, s * 0.18f), s * 0.08f, h * 0.6f);
        float cy = Math.min(below + s * 0.04f + hh / 2f, h - hh / 2f - s * 0.02f);
        List<WidgetStore.Entry> list = currentList();
        list.add(new WidgetStore.Entry(id, 0.5f, cy / h, ww / w, hh / h));
        WidgetStore.save(getContext(), list);
        Prefs.raw(getContext()).edit().putBoolean("widgets_on", true).commit();
        reload();
        select(views.size() - 1);
    }

    public void remove(int i) {
        int id = entries.get(i).id;
        List<WidgetStore.Entry> list = currentList();
        list.remove(i);
        WidgetStore.save(getContext(), list);
        WidgetStore.delete(getContext(), id);
        selected = -1;
        reload();
    }

    public String label(int i) {
        AppWidgetProviderInfo info = views.get(i).getAppWidgetInfo();
        return info == null ? "widget" : info.loadLabel(getContext().getPackageManager());
    }

    /** Saves positions and sizes. Widgets hidden from this layer (unbound ids) are kept as they were. */
    public void save() {
        WidgetStore.save(getContext(), currentList());
    }

    /** The saved list with this layer's edits applied. */
    private List<WidgetStore.Entry> currentList() {
        List<WidgetStore.Entry> saved = WidgetStore.load(getContext());
        for (int i = 0; i < saved.size(); i++) {
            for (WidgetStore.Entry e : entries) if (e.id == saved.get(i).id) saved.set(i, e);
        }
        return saved;
    }

    @Override protected void dispatchDraw(Canvas c) {
        drawBlurs(c);
        super.dispatchDraw(c);
        if (!editing) return;
        float s = Math.min(getWidth(), getHeight()), r = s * 0.02f;
        for (int i = 0; i < views.size(); i++) {
            boolean sel = i == selected;
            frame.setColor(sel ? prefs.accent : Color.argb(150, 255, 255, 255));
            frame.setStrokeWidth(sel ? s * 0.004f : s * 0.0025f);
            frame.setPathEffect(sel ? null : new DashPathEffect(new float[]{s * 0.012f, s * 0.008f}, 0));
            RectF rc = rect(i, getWidth(), getHeight());
            rc.inset(-s * 0.01f, -s * 0.01f);
            c.drawRoundRect(rc, r, r, frame);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
