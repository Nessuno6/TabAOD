package dev.deang.tabaod;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings in one place. */
final class Prefs {
    static final String[] ACCENT_NAMES = {"White", "Blue", "Teal", "Amber", "Pink", "Purple"};
    static final int[] ACCENTS = {0xFFE8E8E8, 0xFF5B9DFF, 0xFF4DD0C4, 0xFFFFC14D, 0xFFFF7AA8, 0xFFB18CFF};

    // "When to show", in the order the settings screen lists them.
    static final int MODE_AUTO = 1, MODE_ALWAYS = 0, MODE_SCHEDULE = 2, MODE_NEW_NOTIFS = 3;
    static final int[] MODE_ORDER = {MODE_AUTO, MODE_ALWAYS, MODE_SCHEDULE, MODE_NEW_NOTIFS};
    static final String[] MODE_NAMES = {"Always", "Auto", "As scheduled", "For new notifications"};
    static final String[] MODE_INFO = {
        null,
        "Show all the time unless the tablet is face down, or in a dark place and hasn't moved for at least 5 minutes.",
        null,
        "Show only while there are notifications that arrived since the screen turned off."};

    final int style, accent, brightnessPct, wallpaperPct, popupSeconds, widgetPct;
    /** When to show (MODE_*), and the schedule as minutes after midnight. */
    final int showMode, schedStart, schedEnd;
    final boolean nowBar, fpIcon;
    final boolean enabled, showDate, showBattery, notifIcons, popups, wallpaper, showWidgets, widgetGrey, widgetInvert, widgetBlur, coverOff;

    /**
     * Custom layout from the editor: element centres as fractions of the screen, plus the
     * clock's size. When {@code customLayout} is false the automatic layout is used.
     */
    final boolean customLayout;
    final float clockX, clockY, clockScale, infoX, infoY, iconsX, iconsY;

    private Prefs(SharedPreferences p) {
        // Older versions had different style numbers; anything out of range falls back to AOSP.
        int st = p.getInt("style", ClockStyles.AOSP);
        style = st >= 0 && st < ClockStyles.NAMES.length ? st : ClockStyles.AOSP;
        accent = ACCENTS[Math.min(p.getInt("accent", 1), ACCENTS.length - 1)];
        brightnessPct = p.getInt("brightness", 10);
        wallpaperPct = p.getInt("wallpaper_level", 15);
        popupSeconds = p.getInt("popup_seconds", 8);
        enabled = p.getBoolean(KEY_ENABLED, true);
        showDate = p.getBoolean("show_date", true);
        showBattery = p.getBoolean("show_battery", true);
        notifIcons = p.getBoolean("notif_icons", true);
        popups = p.getBoolean("popups", true);
        wallpaper = p.getBoolean("wallpaper", false);
        showWidgets = p.getBoolean("widgets_on", true);
        widgetPct = p.getInt("widget_level", 80);
        widgetGrey = p.getBoolean("widget_grey", false);
        widgetInvert = p.getBoolean("widget_invert", true);
        widgetBlur = p.getBoolean("widget_blur", true);
        coverOff = p.getBoolean("cover_off", true);
        nowBar = p.getBoolean("now_bar", true);
        fpIcon = p.getBoolean("fp_icon", true);
        int m = p.getInt("show_mode", MODE_ALWAYS);
        showMode = m >= 0 && m < MODE_NAMES.length ? m : MODE_ALWAYS;
        schedStart = p.getInt("sched_start", 7 * 60);
        schedEnd = p.getInt("sched_end", 23 * 60);
        customLayout = p.getBoolean("lay_custom", false);
        clockX = p.getFloat("lay_clock_x", 0.5f);
        clockY = p.getFloat("lay_clock_y", 0.34f);
        clockScale = p.getFloat("lay_clock_scale", 1f);
        infoX = p.getFloat("lay_info_x", 0.5f);
        infoY = p.getFloat("lay_info_y", 0.55f);
        iconsX = p.getFloat("lay_icons_x", 0.5f);
        iconsY = p.getFloat("lay_icons_y", 0.62f);
    }

    /** True when minute-of-day {@code now} is inside the schedule (which may cross midnight). */
    boolean inSchedule(int now) {
        if (schedStart == schedEnd) return true;
        return schedStart < schedEnd ? now >= schedStart && now < schedEnd
                                     : now >= schedStart || now < schedEnd;
    }

    static String time(int minutes) {
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Saves a layout from the editor (all positions as fractions of the screen). */
    static void saveLayout(Context c, float clockX, float clockY, float clockScale,
                           float infoX, float infoY, float iconsX, float iconsY) {
        raw(c).edit()
                .putBoolean("lay_custom", true)
                .putFloat("lay_clock_x", clockX).putFloat("lay_clock_y", clockY)
                .putFloat("lay_clock_scale", clockScale)
                .putFloat("lay_info_x", infoX).putFloat("lay_info_y", infoY)
                .putFloat("lay_icons_x", iconsX).putFloat("lay_icons_y", iconsY)
                .commit();
    }

    /** Back to the automatic layout. */
    static void resetLayout(Context c) {
        raw(c).edit().putBoolean("lay_custom", false).commit();
    }

    static final String KEY_ENABLED = "aod_enabled";

    static boolean isEnabled(Context c) {
        return raw(c).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        // commit(), not apply(): the tile's process can be frozen right after a tap.
        raw(c).edit().putBoolean(KEY_ENABLED, on).commit();
    }

    static SharedPreferences raw(Context c) {
        return c.getSharedPreferences("aod", Context.MODE_PRIVATE);
    }

    static Prefs load(Context c) {
        return new Prefs(raw(c));
    }
}
