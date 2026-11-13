package dev.deang.tabaod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import org.json.JSONObject;

/**
 * Updates from GitHub releases. {@code update.json} in the repository describes the latest
 * release for both the root module (KernelSU / Magisk read version, versionCode and zipUrl)
 * and this app (apkVersionCode, apkUrl, apkSha256). The APK is checked against its SHA-256
 * before it's installed with root; Android also refuses an APK signed with a different key.
 */
final class Updater {
    static final String REPO = "Nessuno6/TabAOD";
    static final String UPDATE_JSON = "https://raw.githubusercontent.com/" + REPO + "/main/update.json";
    static final String RELEASES = "https://github.com/" + REPO + "/releases";

    static final String KEY_AUTO = "upd_auto", KEY_LAST = "upd_last", KEY_VC = "upd_vc",
            KEY_VERSION = "upd_version", KEY_URL = "upd_url", KEY_SHA = "upd_sha", KEY_ERROR = "upd_error";
    /** Checks from the settings screen happen at most this often (the job runs twice a day). */
    static final long MIN_INTERVAL_MS = 6 * 3600_000L;
    private static final int JOB_ID = 0x7AB0;
    private static final String CHANNEL = "updates";

    /** The installed app's version code. */
    static long installedVc(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /** True when the last check found a newer app version. */
    static boolean available(Context c) {
        return Prefs.raw(c).getLong(KEY_VC, 0) > installedVc(c);
    }

    static String latestVersion(Context c) {
        return Prefs.raw(c).getString(KEY_VERSION, "");
    }

    static boolean autoInstall(Context c) {
        return Prefs.raw(c).getBoolean(KEY_AUTO, true);
    }

    /** Fetches update.json and remembers what it says. Returns true if it worked. Not on the main thread. */
    static boolean check(Context c) {
        SharedPreferences sp = Prefs.raw(c);
        try {
            JSONObject j = new JSONObject(new String(get(UPDATE_JSON, 256 * 1024), "UTF-8"));
            sp.edit()
                    .putLong(KEY_LAST, System.currentTimeMillis())
                    .putLong(KEY_VC, j.getLong("apkVersionCode"))
                    .putString(KEY_VERSION, j.getString("version"))
                    .putString(KEY_URL, j.getString("apkUrl"))
                    .putString(KEY_SHA, j.getString("apkSha256").toLowerCase())
                    .remove(KEY_ERROR)
                    .commit();
            return true;
        } catch (Exception e) {
            Log.w(AodDozeService.TAG, "update check failed: " + e);
            sp.edit().putLong(KEY_LAST, System.currentTimeMillis()).putString(KEY_ERROR, e.getClass().getSimpleName()).commit();
            return false;
        }
    }

    /** Checks unless the last check was recent. Not on the main thread. */
    static void checkIfDue(Context c) {
        long last = Prefs.raw(c).getLong(KEY_LAST, 0);
        if (System.currentTimeMillis() - last >= MIN_INTERVAL_MS) check(c);
    }

    /**
     * Downloads the new APK, verifies its SHA-256 and installs it with root. Returns a message
     * for the user. If the install succeeds Android restarts the app, so this may not return.
     * Not on the main thread.
     */
    static String install(Context c) {
        SharedPreferences sp = Prefs.raw(c);
        String url = sp.getString(KEY_URL, null), sha = sp.getString(KEY_SHA, null);
        if (url == null || sha == null || !url.startsWith("https://github.com/" + REPO + "/releases/download/")) {
            return "No update to install";
        }
        File apk = new File(c.getCacheDir(), "update.apk");
        try {
            byte[] data = get(url, 64 * 1024 * 1024);
            if (!sha.equals(hex(MessageDigest.getInstance("SHA-256").digest(data)))) {
                return "Download didn't match its checksum; not installed";
            }
            try (FileOutputStream o = new FileOutputStream(apk)) {
                o.write(data);
            }
            apk.setReadable(true, false);
            // Streamed through pm, like the root module does: system_server can't read app files.
            String out = Root.run("cat '" + apk.getPath() + "' | pm install -r -S " + data.length);
            return out.contains("Success") ? "Updated to " + latestVersion(c) : "Install failed: " + out.trim();
        } catch (Exception e) {
            return "Update failed: " + e.getClass().getSimpleName();
        } finally {
            apk.delete();
        }
    }

    // --- background checks ---

    /** Schedules the twice-daily check (kept across reboots). */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(12 * 3600_000L)
                .setPersisted(true)
                .build());
    }

    /** The background job's work: check, then install (auto) or tell the user. */
    static void runJob(Context c) {
        if (!check(c) || !available(c)) return;
        if (autoInstall(c)) {
            String r = install(c);
            Log.i(AodDozeService.TAG, "auto update: " + r);
            if (!r.startsWith("Updated")) notifyAvailable(c);
        } else {
            notifyAvailable(c);
        }
    }

    private static void notifyAvailable(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(1, new Notification.Builder(c, CHANNEL)
                // The build doesn't generate an R class, so look the icon up by name.
                .setSmallIcon(c.getResources().getIdentifier("ic_aod", "drawable", c.getPackageName()))
                .setContentTitle("Tab AOD " + latestVersion(c) + " is available")
                .setContentText("Open Tab AOD to install it")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }

    // --- helpers ---

    /** HTTPS GET with redirects (GitHub serves release files from another host), size-limited. */
    private static byte[] get(String url, int maxBytes) throws Exception {
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15_000);
            h.setReadTimeout(30_000);
            h.setInstanceFollowRedirects(false);
            h.setRequestProperty("User-Agent", "TabAOD-updater");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = h.getHeaderField("Location");
                h.disconnect();
                if (next == null || !next.startsWith("https://")) throw new IllegalStateException("bad redirect");
                url = next;
                continue;
            }
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) throw new IllegalStateException("too large");
                }
                return out.toByteArray();
            } finally {
                h.disconnect();
            }
        }
        throw new IllegalStateException("too many redirects");
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
