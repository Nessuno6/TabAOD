package dev.deang.tabaod;

import android.app.Notification;
import android.app.NotificationManager;
import android.graphics.drawable.Icon;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Feeds notifications into NotifStore. Needs notification access (granted with root by the app). */
public class NotifService extends NotificationListenerService {

    @Override public void onListenerConnected() {
        NotifStore.clear();
        StatusBarNotification[] active = getActiveNotifications();
        if (active == null) return;
        for (StatusBarNotification sbn : active) {
            NotifStore.Item it = toItem(sbn);
            if (it != null) NotifStore.put(it, false);
            updateNow(sbn);
        }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankings) {
        updateNow(sbn);
        NotifStore.Item it = toItem(sbn);
        if (it == null) return;
        Notification n = sbn.getNotification();
        boolean alerting = true;
        Ranking r = new Ranking();
        if (rankings != null && rankings.getRanking(sbn.getKey(), r)) {
            alerting = r.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT && !r.isAmbient();
        }
        boolean groupSummary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        boolean onlyOnce = (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
        NotifStore.put(it, alerting && !groupSummary && !onlyOnce);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        NotifStore.remove(sbn.getKey());
        NotifStore.removeNow(sbn.getKey());
    }

    /** Adds, updates or drops the notification's Now bar entry. */
    private void updateNow(StatusBarNotification sbn) {
        NotifStore.Now n = toNow(sbn);
        if (n != null) NotifStore.putNow(n);
        else NotifStore.removeNow(sbn.getKey());
    }

    /**
     * A Now bar entry for live activities: playing media, timers and stopwatches, calls,
     * navigation, progress, and Android 16's promoted ("live") notifications.
     */
    private NotifStore.Now toNow(StatusBarNotification sbn) {
        if (sbn.getPackageName().equals(getPackageName())) return null;
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        boolean ongoing = sbn.isOngoing();
        boolean chrono = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER);
        boolean countDown = x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN);
        int max = x.getInt(Notification.EXTRA_PROGRESS_MAX), progress = x.getInt(Notification.EXTRA_PROGRESS);
        boolean promoted = (n.flags & Notification.FLAG_PROMOTED_ONGOING) != 0;
        String cat = n.category == null ? "" : n.category;

        int kind;
        if (x.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            if (!isPlaying(x)) return null; // paused music stays off the AOD
            kind = NotifStore.Now.MEDIA;
        } else if (!ongoing && !promoted) {
            return null;
        } else if (Notification.CATEGORY_CALL.equals(cat)) {
            kind = NotifStore.Now.CALL;
        } else if (chrono || Notification.CATEGORY_STOPWATCH.equals(cat)) {
            kind = NotifStore.Now.TIMER;
        } else if (Notification.CATEGORY_NAVIGATION.equals(cat)) {
            kind = NotifStore.Now.NAVIGATION;
        } else if (max > 0 || promoted) {
            kind = NotifStore.Now.PROGRESS;
        } else {
            return null; // plain ongoing notifications (sync, VPN, ...) aren't live activities
        }
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        Icon icon = kind == NotifStore.Now.MEDIA && n.getLargeIcon() != null ? n.getLargeIcon() : n.getSmallIcon();
        return new NotifStore.Now(sbn.getKey(), sbn.getPackageName(), title, text, icon, kind,
                progress, max, n.when, chrono, countDown, sbn.getPostTime());
    }

    private boolean isPlaying(Bundle x) {
        try {
            MediaSession.Token t = x.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
            if (t == null) return false;
            PlaybackState st = new MediaController(this, t).getPlaybackState();
            return st != null && (st.getState() == PlaybackState.STATE_PLAYING
                    || st.getState() == PlaybackState.STATE_BUFFERING);
        } catch (Exception e) {
            return false;
        }
    }

    /** Skips our own, ongoing and service notifications. */
    private NotifStore.Item toItem(StatusBarNotification sbn) {
        if (sbn.getPackageName().equals(getPackageName())) return null;
        Notification n = sbn.getNotification();
        if (sbn.isOngoing() || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return null;
        Bundle x = n.extras;
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        return new NotifStore.Item(sbn.getKey(), sbn.getPackageName(), title, text,
                n.getSmallIcon(), n.getLargeIcon());
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
