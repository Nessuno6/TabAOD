package dev.deang.tabaod;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.util.SizeF;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors the lock screen's widgets: reads which widgets SystemUI shows on the lock screen
 * (from dumpsys, as root), binds TabAOD's own copy of each, and lays them out in the same
 * two-row grid.
 */
final class LockWidgets {
    static final String KEY_ON = "mirror_lock", KEY_SIG = "mirror_sig", KEY_IDS = "mirror_ids";
    /** Samsung widget style to ask for (light text for the dark AOD); -1 copies the lock screen's. */
    static final String KEY_STYLE = "mirror_style";
    /**
     * Where One UI's lock screen puts things on this tablet (fractions of the landscape height,
     * measured from the lock screen's own layout): the top of the widget grid, the time's
     * centre and the date's baseline.
     */
    static final float LOCK_TOP = 440f / 1848f, LOCK_CLOCK_Y = 355f / 1848f, LOCK_DATE_Y = 262f / 1848f;

    /** Puts the clock and date where the lock screen has them, above the copied widgets. */
    static void applyLockLayout(Context c) {
        Prefs.raw(c).edit().putInt("style", ClockStyles.LOCK).commit();
        Prefs.saveLayout(c, 0.5f, LOCK_CLOCK_Y, 1f, 0.5f, LOCK_DATE_Y, 0.5f, 760f / 1848f);
    }

    /** Columns in One UI's lock screen widget grid on this tablet. */
    static final int GRID_COLS = 10;

    static Set<Integer> mirroredIds(Context c) {
        Set<Integer> out = new HashSet<>();
        for (String s : Prefs.raw(c).getString(KEY_IDS, "").split(",")) {
            try { out.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** Samsung's lock screen widget cell (1x1) and the gap between cells, in dp. */
    private static final float CELL_DP = 70.28571f, GAP_DP = 14.28573f;

    static final class Spec {
        int id, cols, rows;
        /** Cell on the lock screen's grid, or -1 when unknown (then the widgets are packed). */
        int x = -1, y = -1;
        float density;
        ComponentName provider;
        Bundle options = new Bundle();
    }

    /**
     * The widget service's dump. Uses the DUMP permission (granted by the module or the Enable
     * button) and falls back to su.
     */
    static String dump(Context c) {
        java.io.File f = new java.io.File(c.getCacheDir(), "appwidget.txt");
        try {
            android.os.IBinder b = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "appwidget");
            try (android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.open(f,
                    android.os.ParcelFileDescriptor.MODE_READ_WRITE | android.os.ParcelFileDescriptor.MODE_CREATE
                            | android.os.ParcelFileDescriptor.MODE_TRUNCATE)) {
                b.dump(pfd.getFileDescriptor(), new String[0]);
            }
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()));
            if (s.contains("\nWidgets:")) return s;
        } catch (Throwable t) {
            Log.w(AodDozeService.TAG, "appwidget dump failed: " + t);
        } finally {
            f.delete();
        }
        return Root.run("dumpsys appwidget");
    }

    /**
     * SystemUI's own dump. Its AOD data manager lists the widgets the lock screen actually shows,
     * with their grid cells: SystemUI's widget host also keeps ids of widgets that were removed
     * or replaced, so the widget service's list alone isn't enough.
     */
    static String sysuiDump(Context c) {
        String[] args = {"service", "com.android.systemui/.SystemUIService"};
        java.io.File f = new java.io.File(c.getCacheDir(), "sysui.txt");
        try {
            android.os.IBinder b = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "activity");
            try (android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.open(f,
                    android.os.ParcelFileDescriptor.MODE_READ_WRITE | android.os.ParcelFileDescriptor.MODE_CREATE
                            | android.os.ParcelFileDescriptor.MODE_TRUNCATE)) {
                b.dump(pfd.getFileDescriptor(), args);
            }
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()));
            if (s.contains("complicationWidgetsFlow=")) return s;
        } catch (Throwable t) {
            Log.w(AodDozeService.TAG, "systemui dump failed: " + t);
        } finally {
            f.delete();
        }
        return Root.run("dumpsys activity service com.android.systemui/.SystemUIService");
    }

    private static final Pattern WIDGET_IMPL = Pattern.compile(
            "WidgetImpl\\(widgetId=(\\d+), componentName=([^,]+), width=(\\d+), height=(\\d+), x=(-?\\d+), y=(-?\\d+)");

    /** The lock screen's widgets as SystemUI lays them out (landscape), or empty if unknown. */
    static List<Spec> readLayout(String sysui) {
        List<Spec> out = new ArrayList<>();
        String line = null;
        for (String l : sysui.split("\n")) {
            if (!l.contains("complicationWidgetsFlow=")) continue;
            if (line == null || l.contains("Landscape")) line = l;
        }
        if (line == null) return out;
        Matcher m = WIDGET_IMPL.matcher(line);
        while (m.find()) {
            Spec s = new Spec();
            s.id = Integer.parseInt(m.group(1));
            s.provider = ComponentName.unflattenFromString(m.group(2).trim());
            s.cols = Math.max(1, Integer.parseInt(m.group(3)));
            s.rows = Math.max(1, Integer.parseInt(m.group(4)));
            s.x = Integer.parseInt(m.group(5));
            s.y = Integer.parseInt(m.group(6));
            if (s.provider != null) out.add(s);
        }
        return out;
    }

    /** The lock screen's widgets, with their grid cells when known. Call off the main thread. */
    static List<Spec> read(Context ctx) {
        List<Spec> fromHost = readHost(ctx);
        List<Spec> layout = readLayout(sysuiDump(ctx));
        if (layout.isEmpty()) return fromHost; // no layout in SystemUI's dump: pack them ourselves
        // Take each widget's options (style, size class, density) from the widget service.
        java.util.Map<Integer, Spec> byId = new java.util.HashMap<>();
        for (Spec s : fromHost) byId.put(s.id, s);
        for (Spec s : layout) {
            Spec h = byId.get(s.id);
            if (h != null) {
                s.options = h.options;
                s.density = h.density;
            } else {
                s.options.putInt("appWidgetCategory", 8192);
                s.options.putInt("semHostType", 2);
                s.options.putInt("semWidgetStyle", 2);
            }
            s.options.putInt("semAppWidgetColumnSpan", s.cols);
            s.options.putInt("semAppWidgetRowSpan", s.rows);
        }
        layout.sort((a, b) -> a.y != b.y ? Integer.compare(a.y, b.y) : Integer.compare(a.x, b.x));
        return layout;
    }

    /** Every lock screen widget id SystemUI's host has bound (may include stale ones). */
    private static List<Spec> readHost(Context ctx) {
        String dump = dump(ctx);
        int a = dump.indexOf("\nWidgets:"), b = dump.indexOf("\nHosts:");
        if (a < 0 || b < a) return Collections.emptyList();
        List<Spec> out = new ArrayList<>();
        Pattern idP = Pattern.compile("^\\s+\\[\\d+\\] id=(\\d+)");
        Pattern cmpP = Pattern.compile("cmp:ComponentInfo\\{([^}]+)\\}");
        Spec cur = null;
        boolean sysui = false;
        for (String line : dump.substring(a, b).split("\n")) {
            Matcher m = idP.matcher(line);
            if (m.find()) {
                cur = new Spec();
                cur.id = Integer.parseInt(m.group(1));
                sysui = false;
            } else if (cur != null && line.contains("host=HostId")) {
                sysui = line.contains("pkg:com.android.systemui}");
            } else if (cur != null && line.contains("provider=")) {
                Matcher c = cmpP.matcher(line);
                if (c.find()) cur.provider = ComponentName.unflattenFromString(c.group(1));
            } else if (cur != null && line.contains("options=")) {
                if (sysui && cur.provider != null && intOpt(line, "semHostType") == 2) {
                    cur.cols = Math.max(1, intOpt(line, "semAppWidgetColumnSpan"));
                    cur.rows = Math.max(1, intOpt(line, "semAppWidgetRowSpan"));
                    Matcher d = Pattern.compile("semDisplayDensity=([0-9.]+)").matcher(line);
                    cur.density = d.find() ? Float.parseFloat(d.group(1)) : 0f;
                    // Same options as the lock screen, so the provider draws its lock screen style.
                    for (String k : new String[]{"appWidgetCategory", "semHostType", "semWidgetStyle",
                            "semWidgetSize", "semAppWidgetRowSpan", "semAppWidgetColumnSpan"}) {
                        int v = intOpt(line, k);
                        if (v != Integer.MIN_VALUE) cur.options.putInt(k, v);
                    }
                    out.add(cur);
                }
                cur = null;
            }
        }
        out.sort((x, y) -> Integer.compare(x.id, y.id));
        return out;
    }

    private static int intOpt(String line, String key) {
        Matcher m = Pattern.compile("\\b" + key + "=(-?\\d+)").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    static String signature(List<Spec> specs) {
        StringBuilder sb = new StringBuilder();
        for (Spec s : specs) sb.append(s.provider.flattenToShortString()).append('/').append(s.cols).append('x').append(s.rows)
                .append('@').append(s.x).append(',').append(s.y).append(';');
        return sb.toString();
    }

    static boolean isOn(Context c) {
        return Prefs.raw(c).getBoolean(KEY_ON, false);
    }

    /** True when the lock screen's widgets differ from the last copy. */
    static boolean changed(Context c, List<Spec> specs) {
        return !signature(specs).equals(Prefs.raw(c).getString(KEY_SIG, ""));
    }

    /**
     * Replaces the previous copies with fresh ones of {@code specs}, as a grid centred across
     * the screen with its top at {@code topFrac} of the height. Manually added widgets stay.
     * Returns how many were added.
     */
    static int apply(Context c, List<Spec> specs) {
        float topFrac = LOCK_TOP;
        SharedPreferences sp = Prefs.raw(c);
        AppWidgetManager awm = AppWidgetManager.getInstance(c);
        // The lock screen's grid uses its own density (semDisplayDensity), not the display's.
        float density = c.getResources().getDisplayMetrics().density;
        for (Spec s : specs) if (s.density > 0) { density = s.density; break; }
        int W = c.getResources().getDisplayMetrics().widthPixels;
        int H = c.getResources().getDisplayMetrics().heightPixels;
        // The AOD runs in landscape on this tablet; use the long side as the width.
        if (H > W) { int t = W; W = H; H = t; }

        // Drop the old copies.
        Set<Integer> old = new HashSet<>();
        for (String s : sp.getString(KEY_IDS, "").split(",")) {
            try { old.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) { }
        }
        List<WidgetStore.Entry> list = new ArrayList<>();
        SharedPreferences.Editor cleanup = sp.edit();
        for (WidgetStore.Entry e : WidgetStore.load(c)) {
            if (old.contains(e.id)) { WidgetStore.delete(c, e.id); cleanup.remove("mopt_" + e.id); }
            else list.add(e);
        }
        cleanup.apply();

        // Pack like One UI's lock screen: a grid of GRID_COLS x 2 cells, filled row by row,
        // each widget in the first free spot that fits.
        List<int[]> cells = new ArrayList<>(); // {col, row} per spec
        boolean[][] used = new boolean[2][GRID_COLS];
        int maxCol = 0, minCol = GRID_COLS;
        for (Spec s : specs) {
            int rows = Math.min(2, s.rows), cols = Math.min(GRID_COLS, s.cols);
            if (s.x >= 0 && s.y >= 0 && s.x + cols <= GRID_COLS && s.y + rows <= 2) {
                // The lock screen's own cell, as SystemUI reported it.
                cells.add(new int[]{s.x, s.y});
                minCol = Math.min(minCol, s.x);
                maxCol = Math.max(maxCol, s.x + cols);
                continue;
            }
            int[] at = null;
            for (int row = 0; row + rows <= 2 && at == null; row++) {
                for (int col = 0; col + cols <= GRID_COLS && at == null; col++) {
                    boolean free = true;
                    for (int r = row; r < row + rows; r++)
                        for (int k = col; k < col + cols; k++) free &= !used[r][k];
                    if (free) at = new int[]{col, row};
                }
            }
            if (at == null) { cells.add(null); continue; }
            for (int r = at[1]; r < at[1] + rows; r++)
                for (int k = at[0]; k < at[0] + cols; k++) used[r][k] = true;
            minCol = Math.min(minCol, at[0]);
            maxCol = Math.max(maxCol, at[0] + cols);
            cells.add(at);
        }
        if (minCol > maxCol) minCol = 0;
        float cell = CELL_DP * density, gap = GAP_DP * density;
        int usedCols = Math.max(1, maxCol - minCol);
        float gridW = usedCols * cell + (usedCols - 1) * gap;
        // Centre the columns in use; cell positions below count from column 0.
        float left = (W - gridW) / 2f - minCol * (cell + gap), top = topFrac * H;

        StringBuilder ids = new StringBuilder();
        int added = 0;
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            int[] at = cells.get(i);
            if (at == null) continue;
            int rows = Math.min(2, s.rows);
            float w = s.cols * cell + (s.cols - 1) * gap, h = rows * cell + (rows - 1) * gap;
            int id = WidgetStore.host(c).allocateAppWidgetId();
            Bundle opts = new Bundle(s.options);
            int style = sp.getInt(KEY_STYLE, -1);
            if (style >= 0) opts.putInt("semWidgetStyle", style);
            ArrayList<SizeF> sizes = new ArrayList<>();
            sizes.add(new SizeF(w / density, h / density));
            opts.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes);
            boolean ok;
            try {
                ok = awm.bindAppWidgetIdIfAllowed(id, s.provider, opts);
            } catch (Exception e) {
                ok = false;
            }
            if (!ok) {
                Log.w(AodDozeService.TAG, "couldn't bind " + s.provider);
                WidgetStore.delete(c, id);
                continue;
            }
            saveOptions(sp, id, s.options, style, w / density, h / density);
            float x = left + at[0] * (cell + gap) + w / 2f, y = top + at[1] * (cell + gap) + h / 2f;
            list.add(new WidgetStore.Entry(id, x / W, y / H, w / W, h / H));
            if (ids.length() > 0) ids.append(',');
            ids.append(id);
            added++;
        }
        WidgetStore.save(c, list);
        SharedPreferences.Editor ed = sp.edit().putString(KEY_IDS, ids.toString()).putString(KEY_SIG, signature(specs))
                .putBoolean("widgets_on", true);
        // Saved options of copies that no longer exist (earlier versions never removed them).
        Set<String> keep = new HashSet<>();
        for (String id : ids.toString().split(",")) keep.add("mopt_" + id);
        for (String k : sp.getAll().keySet()) if (k.startsWith("mopt_") && !keep.contains(k)) ed.remove(k);
        ed.commit();
        return added;
    }

    // --- keeping the lock screen options ---
    // Android hands a widget fresh default options when a host recreates its view, and the
    // provider then draws its home screen style. The lock screen options are saved at copy
    // time and put back whenever they've been lost.

    private static void saveOptions(SharedPreferences sp, int id, Bundle o, int style, float wDp, float hDp) {
        StringBuilder sb = new StringBuilder();
        for (String k : o.keySet()) {
            int v = "semWidgetStyle".equals(k) && style >= 0 ? style : o.getInt(k);
            sb.append(k).append('=').append(v).append(';');
        }
        sb.append("w=").append(wDp).append(";h=").append(hDp);
        sp.edit().putString("mopt_" + id, sb.toString()).apply();
    }

    /** Puts a copied widget's lock screen options back if they were replaced. */
    static void restoreOptions(Context c, int id) {
        String saved = Prefs.raw(c).getString("mopt_" + id, null);
        if (saved == null) return;
        AppWidgetManager awm = AppWidgetManager.getInstance(c);
        Bundle cur = awm.getAppWidgetOptions(id);
        if (cur != null && cur.getInt("semHostType", -1) == 2) return; // still intact
        Bundle o = new Bundle();
        float w = 0, h = 0;
        for (String part : saved.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            try {
                if (kv[0].equals("w")) w = Float.parseFloat(kv[1]);
                else if (kv[0].equals("h")) h = Float.parseFloat(kv[1]);
                else o.putInt(kv[0], Integer.parseInt(kv[1]));
            } catch (NumberFormatException ignored) { }
        }
        if (w > 0 && h > 0) {
            ArrayList<SizeF> sizes = new ArrayList<>();
            sizes.add(new SizeF(w, h));
            o.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes);
            o.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, Math.round(w));
            o.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, Math.round(w));
            o.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, Math.round(h));
            o.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, Math.round(h));
        }
        try {
            awm.updateAppWidgetOptions(id, o);
            Log.i(AodDozeService.TAG, "restored lock screen options for widget " + id);
        } catch (Exception e) {
            Log.w(AodDozeService.TAG, "couldn't restore options for " + id + ": " + e);
        }
    }

    /** Removes the copies (when the option is switched off). */
    static void clear(Context c) {
        SharedPreferences sp = Prefs.raw(c);
        Set<String> old = new HashSet<>();
        Collections.addAll(old, sp.getString(KEY_IDS, "").split(","));
        List<WidgetStore.Entry> list = new ArrayList<>();
        SharedPreferences.Editor ed = sp.edit();
        for (WidgetStore.Entry e : WidgetStore.load(c)) {
            if (old.contains(String.valueOf(e.id))) { WidgetStore.delete(c, e.id); ed.remove("mopt_" + e.id); }
            else list.add(e);
        }
        WidgetStore.save(c, list);
        ed.remove(KEY_IDS).remove(KEY_SIG).commit();
    }
}
