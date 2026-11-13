package dev.deang.tabaod;

import android.appwidget.AppWidgetHost;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The widgets placed on the AOD, and the app's single widget host. */
final class WidgetStore {
    static final int HOST_ID = 0x7AB0D;
    private static final String KEY = "widgets";
    private static AppWidgetHost host;
    private static int listeners;

    /** One placed widget: centre (x, y) as fractions of the screen, size (w, h) likewise. */
    static final class Entry {
        int id;
        float x, y, w, h;

        Entry(int id, float x, float y, float w, float h) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    static synchronized AppWidgetHost host(Context c) {
        if (host == null) host = new AppWidgetHost(c.getApplicationContext(), HOST_ID);
        return host;
    }

    /** Widgets only receive updates while the host listens; shared by the AOD and the editor. */
    static synchronized void listen(Context c, boolean on) {
        if (on && listeners++ == 0) host(c).startListening();
        if (!on && listeners > 0 && --listeners == 0) host(c).stopListening();
    }

    static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        String s = Prefs.raw(c).getString(KEY, "");
        for (String part : s.split("\\|")) {
            String[] f = part.split(":");
            if (f.length != 5) continue;
            try {
                out.add(new Entry(Integer.parseInt(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]),
                        Float.parseFloat(f[3]), Float.parseFloat(f[4])));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static void save(Context c, List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) {
            if (sb.length() > 0) sb.append('|');
            sb.append(String.format(Locale.ROOT, "%d:%.4f:%.4f:%.4f:%.4f", e.id, e.x, e.y, e.w, e.h));
        }
        Prefs.raw(c).edit().putString(KEY, sb.toString()).commit();
    }

    /** Frees the widget id so the provider stops updating it. */
    static void delete(Context c, int id) {
        try {
            host(c).deleteAppWidgetId(id);
        } catch (Exception ignored) { }
    }
}
