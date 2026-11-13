package dev.deang.tabaod;

import android.graphics.drawable.Icon;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Current notifications, shared between the listener service and the AOD screen. */
final class NotifStore {
    static final class Item {
        final String key, pkg, title, text;
        final Icon smallIcon, largeIcon;

        Item(String key, String pkg, String title, String text, Icon smallIcon, Icon largeIcon) {
            this.key = key;
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.smallIcon = smallIcon;
            this.largeIcon = largeIcon;
        }
    }

    /**
     * A live activity for the Now bar: music playing, a timer or stopwatch, a call, navigation
     * or a task with progress. {@code rank} decides which one shows when there are several.
     */
    static final class Now {
        static final int MEDIA = 0, PROGRESS = 1, NAVIGATION = 2, TIMER = 3, CALL = 4;
        final String key, pkg, title, text;
        final Icon icon;
        final int kind, progress, max;
        /** For timers, stopwatches and calls: the chronometer's base time (ms since epoch). */
        final long when;
        final boolean chrono, countDown;
        final long posted;

        Now(String key, String pkg, String title, String text, Icon icon, int kind,
            int progress, int max, long when, boolean chrono, boolean countDown, long posted) {
            this.key = key;
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.icon = icon;
            this.kind = kind;
            this.progress = progress;
            this.max = max;
            this.when = when;
            this.chrono = chrono;
            this.countDown = countDown;
            this.posted = posted;
        }
    }

    private static final LinkedHashMap<String, Now> nows = new LinkedHashMap<>();

    static synchronized void putNow(Now n) {
        nows.put(n.key, n);
        notifyListeners(null);
    }

    static synchronized void removeNow(String key) {
        if (nows.remove(key) != null) notifyListeners(null);
    }

    /** The Now bar's item: the highest-ranked live activity, newest first on a tie. */
    static synchronized Now now() {
        Now best = null;
        for (Now n : nows.values()) {
            if (best == null || n.kind > best.kind || (n.kind == best.kind && n.posted > best.posted)) best = n;
        }
        return best;
    }

    interface Listener {
        /** Called on the main thread. {@code alert} is set when a new notification should pop up. */
        void onNotificationsChanged(Item alert);
    }

    private static final LinkedHashMap<String, Item> items = new LinkedHashMap<>();
    private static final List<Listener> listeners = new ArrayList<>();

    static synchronized void put(Item item, boolean alert) {
        items.remove(item.key);
        items.put(item.key, item);
        notifyListeners(alert ? item : null);
    }

    static synchronized void remove(String key) {
        if (items.remove(key) != null) notifyListeners(null);
    }

    static synchronized void clear() {
        items.clear();
        nows.clear();
        notifyListeners(null);
    }

    /** Newest first, one entry per app. */
    static synchronized List<Item> perApp() {
        List<Item> out = new ArrayList<>();
        List<Item> all = new ArrayList<>(items.values());
        for (int i = all.size() - 1; i >= 0; i--) {
            Item it = all.get(i);
            boolean seen = false;
            for (Item o : out) if (o.pkg.equals(it.pkg)) { seen = true; break; }
            if (!seen) out.add(it);
        }
        return out;
    }

    static synchronized boolean contains(String key) {
        return items.containsKey(key);
    }

    static synchronized void addListener(Listener l) {
        listeners.add(l);
    }

    static synchronized void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners(Item alert) {
        for (Listener l : new ArrayList<>(listeners)) l.onNotificationsChanged(alert);
    }
}
