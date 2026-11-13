package dev.deang.tabaod;

import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Settings: a live, scaled preview of the AOD on one side and grouped settings on the
 * other (stacked on top of each other in portrait). Every change shows in the preview
 * straight away.
 */
public class MainActivity extends Activity {
    static final String PKG = "dev.deang.tabaod";
    static final String LISTENER = PKG + "/" + PKG + ".NotifService";

    /** Copies the lock screen wallpaper (or the home one) into the app's private files. */
    static final String COPY_WALLPAPER =
        "F=/data/system/users/0/wallpaper_lock_images/wallpaper_lock\n"
        + "[ -f $F ] || F=/data/system/users/0/wallpaper\n"
        + "D=/data/data/" + PKG + "/files; mkdir -p $D\n"
        + "cp $F $D/wallpaper.img && U=$(stat -c %u /data/data/" + PKG + ")\n"
        + "chown $U:$U $D $D/wallpaper.img; chmod 600 $D/wallpaper.img; restorecon -R $D\n"
        + "echo wallpaper copied from $F\n";

    static final String ENABLE =
        "settings put global hidden_api_blacklist_exemptions 'Landroid/service/dreams/DreamService;'\n"
        + "cmd notification allow_listener " + LISTENER + "\n"
        + "pm grant " + PKG + " android.permission.DUMP\n"
        + "pm grant " + PKG + " android.permission.POST_NOTIFICATIONS\n"
        + COPY_WALLPAPER
        + "echo enabled";

    static final String DISABLE =
        "settings delete global hidden_api_blacklist_exemptions\n"
        + "cmd notification disallow_listener " + LISTENER + "\n"
        + "echo disabled";

    static final String STATUS =
        "echo doze service: $(dumpsys dreams | grep -o 'getDozeComponent()=.*')\n"
        + "echo exemptions: $(settings get global hidden_api_blacklist_exemptions)\n"
        + "echo notification access: $(settings get secure enabled_notification_listeners | tr ':' '\\n' | grep -c " + PKG + ")";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private Ui ui;

    // preview
    private PreviewFrame frame;
    private AodStage stage;
    private boolean fullReload;

    // header and setup
    private TextView statusLine, console;
    private Boolean setupOk; // null until checked
    private TextView[] setupPills;
    private Ui.Row heroRow;

    /** Rows whose look depends on other settings; refreshed by {@link #updateDependents}. */
    private Ui.Row popupRow, scheduleRow, modeRow;
    private final List<Ui.Row> widgetRows = new ArrayList<>(), wallpaperRows = new ArrayList<>();
    private LinearLayout modeList;
    private final List<RadioButton> modeRadios = new ArrayList<>();
    /** Switches bound to a pref key, re-synced in onResume (the tile can change "enabled"). */
    private final List<Object[]> boundSwitches = new ArrayList<>();
    private Ui.Row mirrorRow;

    private final Runnable refreshPreview = () -> {
        if (stage == null) return;
        if (fullReload) stage.reload();
        else stage.aod.reload();
        fullReload = false;
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (p, key) -> {
        if (key == null) return;
        if (key.equals("accent")) ui.setAccent(accentColour());
        if (key.startsWith("widget") || key.startsWith("wallpaper") || key.startsWith("mirror")
                || key.startsWith("lay_") || key.equals("widgets")) fullReload = true;
        updateDependents();
        handler.removeCallbacks(refreshPreview);
        handler.postDelayed(refreshPreview, 120);
    };

    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            if (stage != null) stage.aod.invalidate();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - (now % 60_000) + 50);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Prefs.raw(this);
        ui = new Ui(this, accentColour());
        getWindow().getDecorView().setBackgroundColor(Ui.BG);

        Rect screen = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        boolean landscape = screen.width() >= screen.height();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });

        View left = previewPane(landscape);
        View right = settingsPane(landscape);
        if (landscape) {
            root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));
            root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        } else {
            root.addView(left, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f));
            root.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.58f));
        }
        setContentView(root);
        updateDependents();
        run(STATUS, this::showStatus);
        Updater.schedule(this);
        new Thread(() -> {
            Updater.checkIfDue(this);
            runOnUiThread(this::refreshUpdate);
        }).start();
        new Thread(() -> {
            boolean had = Fingerprint.natural(this) != null;
            Fingerprint.ensure(this);
            if (!had && Fingerprint.natural(this) != null) runOnUiThread(() -> { if (stage != null) stage.aod.reload(); });
        }).start();
    }

    // The preview exists only while the screen is visible: widgets update in one view at a
    // time, so it must not hold on to them while the editor or the AOD shows.
    @Override protected void onStart() {
        super.onStart();
        stage = new AodStage(this, false);
        frame.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sp.registerOnSharedPreferenceChangeListener(prefsListener);
        handler.post(minuteTick);
    }

    @Override protected void onResume() {
        super.onResume();
        for (Object[] s : boundSwitches) {
            ((Switch) s[0]).setChecked(sp.getBoolean((String) s[1], (Boolean) s[2]));
        }
        updateDependents();
    }

    @Override protected void onStop() {
        sp.unregisterOnSharedPreferenceChangeListener(prefsListener);
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(refreshPreview);
        frame.removeAllViews();
        stage = null;
        super.onStop();
    }

    // --- left: title, status and the live preview ---

    private View previewPane(boolean landscape) {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        int side = ui.dp(landscape ? 40 : 24);
        pane.setPadding(side, ui.dp(28), landscape ? ui.dp(20) : side, ui.dp(24));

        TextView title = ui.title("Always On Display", landscape ? 30 : 26);
        pane.addView(title);
        statusLine = ui.text("Checking setup…", 14, Ui.SUB);
        statusLine.setPadding(0, ui.dp(6), 0, ui.dp(20));
        pane.addView(statusLine);

        FrameLayout holder = new FrameLayout(this);
        frame = new PreviewFrame(this, ui);
        frame.setOnClickListener(v -> startActivity(new Intent(this, PreviewActivity.class)));
        frame.setContentDescription("AOD preview. Tap for full screen.");
        holder.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        pane.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = ui.text("Tap the preview to see it full screen", 13, Ui.FAINT);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, ui.dp(12), 0, ui.dp(16));
        pane.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(ui.button("Edit layout", true, v -> startActivity(new Intent(this, EditorActivity.class))));
        addSpaced(buttons, ui.button("Add widget", false, v -> startActivity(
                new Intent(this, EditorActivity.class).putExtra(EditorActivity.EXTRA_ADD, true))));
        addSpaced(buttons, ui.button("Full screen", false, v -> startActivity(new Intent(this, PreviewActivity.class))));
        pane.addView(buttons);
        return pane;
    }

    private void addSpaced(LinearLayout parent, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ui.dp(10);
        parent.addView(v, lp);
    }

    // --- right: the settings cards ---

    private View settingsPane(boolean landscape) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = ui.dp(landscape ? 20 : 24);
        col.setPadding(side, ui.dp(28), ui.dp(landscape ? 40 : 24), ui.dp(40));

        addCard(col, updateCard());
        addCard(col, heroCard());
        addCard(col, whenCard());
        addCard(col, clockCard());
        addCard(col, notificationsCard());
        addCard(col, widgetsCard());
        addCard(col, wallpaperCard());
        addCard(col, setupCard());
        addCard(col, aboutCard());

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.addView(col);
        return sv;
    }

    private void addCard(LinearLayout col, View card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(16);
        col.addView(card, lp);
    }

    private View heroCard() {
        LinearLayout card = ui.card(null);
        Switch main = boundSwitch(Prefs.KEY_ENABLED, true);
        heroRow = ui.row("Always On Display", null, main);
        heroRow.title.setTextSize(20);
        heroRow.title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clickToggles(heroRow, main);
        card.addView(heroRow.view);
        card.addView(ui.divider());
        card.addView(switchRow("Turn off when the cover is closed", "Keeps the screen dark under the Book Cover",
                "cover_off", true).view);
        TextView hint = ui.text("Also in Quick Settings as the AOD tile.", 13, Ui.FAINT);
        hint.setPadding(0, ui.dp(2), 0, ui.dp(12));
        card.addView(hint);
        return card;
    }

    private View whenCard() {
        LinearLayout card = ui.card(null);
        modeRow = ui.navRow("When to show", null, v ->
                modeList.setVisibility(modeList.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        ui.onAccent(() -> modeRow.sub.setTextColor(ui.accent));
        card.addView(modeRow.view);

        modeList = new LinearLayout(this);
        modeList.setOrientation(LinearLayout.VERTICAL);
        modeList.setVisibility(View.GONE);
        for (int mode : Prefs.MODE_ORDER) {
            modeList.addView(ui.divider());
            RadioButton rb = new RadioButton(this);
            rb.setClickable(false);
            rb.setFocusable(false);
            ui.onAccent(() -> rb.setButtonTintList(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ui.accent, Ui.FAINT})));
            rb.setTag(mode);
            modeRadios.add(rb);
            Ui.Row r = ui.row(Prefs.MODE_NAMES[mode], Prefs.MODE_INFO[mode], null);
            r.view.addView(rb, 0);
            r.view.setBackground(ui.ripple(null, 14));
            r.view.setOnClickListener(v -> sp.edit().putInt("show_mode", mode).apply());
            modeList.addView(r.view);
        }
        card.addView(modeList);

        card.addView(ui.divider());
        scheduleRow = ui.navRow("Schedule", null, v -> pickSchedule());
        card.addView(scheduleRow.view);
        return card;
    }

    /** Start time, then end time. */
    private void pickSchedule() {
        Prefs p = Prefs.load(this);
        new TimePickerDialog(this, (tp, h, m) -> {
            sp.edit().putInt("sched_start", h * 60 + m).apply();
            new TimePickerDialog(this, (tp2, h2, m2) -> sp.edit().putInt("sched_end", h2 * 60 + m2).apply(),
                    p.schedEnd / 60, p.schedEnd % 60, android.text.format.DateFormat.is24HourFormat(this)) {
                { setTitle("Hide from"); }
            }.show();
        }, p.schedStart / 60, p.schedStart % 60, android.text.format.DateFormat.is24HourFormat(this)) {
            { setTitle("Show from"); }
        }.show();
    }

    private View clockCard() {
        LinearLayout card = ui.card("Clock");

        card.addView(label("Style"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        List<TextView> chipViews = new ArrayList<>();
        for (int i = 0; i < ClockStyles.NAMES.length; i++) {
            final int idx = i;
            TextView chip = ui.chip(ClockStyles.NAMES[i]);
            chip.setOnClickListener(v -> sp.edit().putInt("style", idx).apply());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            chips.addView(chip, lp);
            chipViews.add(chip);
        }
        Runnable paintChips = () -> {
            int sel = Prefs.load(this).style;
            for (int i = 0; i < chipViews.size(); i++) ui.setChip(chipViews.get(i), i == sel);
        };
        ui.onAccent(paintChips);
        dependents.add(paintChips);
        hs.addView(chips);
        hs.setPadding(0, 0, 0, ui.dp(12));
        card.addView(hs);

        card.addView(label("Accent colour"));
        LinearLayout swatches = new LinearLayout(this);
        swatches.setPadding(0, 0, 0, ui.dp(14));
        List<View> swatchViews = new ArrayList<>();
        for (int i = 0; i < Prefs.ACCENTS.length; i++) {
            final int idx = i;
            View sw = new View(this);
            sw.setContentDescription(Prefs.ACCENT_NAMES[i]);
            sw.setOnClickListener(v -> sp.edit().putInt("accent", idx).apply());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
            lp.rightMargin = ui.dp(12);
            swatches.addView(sw, lp);
            swatchViews.add(sw);
        }
        Runnable paintSwatches = () -> {
            int sel = Math.min(sp.getInt("accent", 1), Prefs.ACCENTS.length - 1);
            for (int i = 0; i < swatchViews.size(); i++) {
                android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
                d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                d.setColor(Prefs.ACCENTS[i]);
                if (i == sel) d.setStroke(ui.dp(3), Ui.TEXT);
                swatchViews.get(i).setBackground(d);
            }
        };
        ui.onAccent(paintSwatches);
        card.addView(swatches);

        card.addView(ui.divider());
        card.addView(sliderRow("Brightness", "Screen brightness while the AOD shows",
                "brightness", 2, 40, 10, "%").view);
        card.addView(ui.divider());
        card.addView(switchRow("Fingerprint icon", "Shows where the fingerprint sensor is", "fp_icon", true).view);
        card.addView(ui.divider());
        card.addView(switchRow("Date", null, "show_date", true).view);
        card.addView(ui.divider());
        card.addView(switchRow("Battery", null, "show_battery", true).view);
        return card;
    }

    private View notificationsCard() {
        LinearLayout card = ui.card("Notifications");
        card.addView(switchRow("Show Now bar", "Music, timers, calls and navigation at the bottom",
                "now_bar", true).view);
        card.addView(ui.divider());
        card.addView(switchRow("Notification icons", "App icons for notifications you haven't seen",
                "notif_icons", true).view);
        card.addView(ui.divider());
        card.addView(switchRow("Pop up new notifications", "Shows a card for each new one",
                "popups", true).view);
        card.addView(ui.divider());
        popupRow = sliderRow("Pop-up duration", null, "popup_seconds", 3, 20, 8, " s");
        card.addView(popupRow.view);
        return card;
    }

    private View widgetsCard() {
        LinearLayout card = ui.card("Widgets");
        card.addView(switchRow("Show widgets", null, "widgets_on", true).view);

        Switch mirror = ui.toggle(LockWidgets.isOn(this));
        mirrorRow = ui.row("Use my lock screen widgets", "Copies them and keeps them in sync (root)", mirror);
        clickToggles(mirrorRow, mirror);
        mirror.setOnCheckedChangeListener((v, on) -> {
            sp.edit().putBoolean(LockWidgets.KEY_ON, on).commit();
            mirrorRow.setSub(on ? "Copying lock screen widgets…" : "");
            new Thread(() -> {
                String msg;
                if (on) {
                    List<LockWidgets.Spec> specs = LockWidgets.read(this);
                    msg = specs.isEmpty() ? "No lock screen widgets found (needs root)"
                            : "Copied " + LockWidgets.apply(this, specs) + " of " + specs.size()
                              + ", in the lock screen's layout";
                    if (!specs.isEmpty()) LockWidgets.applyLockLayout(this);
                } else {
                    LockWidgets.clear(this);
                    msg = "Copies removed";
                }
                runOnUiThread(() -> mirrorRow.setSub(msg));
            }).start();
        });
        Ui.Row match = ui.navRow("Match the lock screen layout", "Moves the clock, date and widgets to where the lock screen has them", v -> {
            LockWidgets.applyLockLayout(this);
            toast("Clock and date moved to the lock screen's positions");
        });
        Ui.Row level = sliderRow("Widget light level", null, "widget_level", 10, 100, 80, "%");
        Ui.Row invert = switchRow("Readable on black", "Inverts dark text and white cards", "widget_invert", true);
        Ui.Row grey = switchRow("Greyscale", null, "widget_grey", false);
        Ui.Row blur = switchRow("Blur behind widgets", "Frosted glass, like the lock screen", "widget_blur", true);
        for (Ui.Row r : new Ui.Row[]{mirrorRow, match, level, invert, grey, blur}) {
            card.addView(ui.divider());
            card.addView(r.view);
            widgetRows.add(r);
        }
        TextView hint = ui.text("Arrange and resize widgets in Edit layout. Long-press a widget there to remove it.", 13, Ui.FAINT);
        hint.setPadding(0, ui.dp(2), 0, ui.dp(12));
        card.addView(hint);
        return card;
    }

    private View wallpaperCard() {
        LinearLayout card = ui.card("Wallpaper");
        card.addView(switchRow("Show lock screen wallpaper", "Dimmed behind the clock", "wallpaper", false).view);
        Ui.Row level = sliderRow("Wallpaper light level", null, "wallpaper_level", 3, 40, 15, "%");
        Ui.Row refresh = ui.navRow("Refresh wallpaper copy", "Copies the current lock screen wallpaper (root)", v -> {
            toast("Copying wallpaper…");
            run(COPY_WALLPAPER, out -> { fullReload = true; refreshPreview.run(); toast(out.contains("copied") ? "Wallpaper updated" : "Couldn't copy the wallpaper"); });
        });
        for (Ui.Row r : new Ui.Row[]{level, refresh}) {
            card.addView(ui.divider());
            card.addView(r.view);
            wallpaperRows.add(r);
        }
        return card;
    }

    private View setupCard() {
        LinearLayout card = ui.card("Setup");
        String[] names = {"Doze service", "Doze access", "Notification access"};
        String[] subs = {"Tab AOD is the screen-off display", "Hidden API exemption for the doze service", "Reads notifications for icons and pop-ups"};
        setupPills = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            TextView pill = ui.text("…", 13, Ui.SUB);
            pill.setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5));
            pill.setBackground(ui.round(Ui.RAISED, 999));
            setupPills[i] = pill;
            if (i > 0) card.addView(ui.divider());
            card.addView(ui.row(names[i], subs[i], pill).view);
        }
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, ui.dp(8), 0, ui.dp(10));
        buttons.addView(ui.button("Enable", true, v -> run(ENABLE, out -> { showConsole(out); run(STATUS, this::showStatus); })));
        addSpaced(buttons, ui.button("Disable", false, v -> run(DISABLE, out -> { showConsole(out); run(STATUS, this::showStatus); })));
        addSpaced(buttons, ui.button("Refresh", false, v -> run(STATUS, this::showStatus)));
        card.addView(buttons);

        console = ui.text("", 12, Ui.SUB);
        console.setTypeface(Typeface.MONOSPACE);
        console.setBackground(ui.round(Ui.BG, 14));
        console.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        console.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(14);
        card.addView(console, lp);
        TextView hint = ui.text("Enable and Disable need root. Enable also copies the lock screen wallpaper.", 13, Ui.FAINT);
        hint.setPadding(0, 0, 0, ui.dp(12));
        card.addView(hint);
        return card;
    }

    private View aboutCard() {
        LinearLayout card = ui.card(null);
        TextView credits = ui.text("The \"AOSP\" clock style is adapted from the Android Open Source "
                + "Project's AnimatableClockView, Copyright (C) 2021 The Android Open Source Project, "
                + "licensed under the Apache License, Version 2.0 "
                + "(http://www.apache.org/licenses/LICENSE-2.0). It uses the system font "
                + "/system/fonts/AndroidClock.ttf.\n\n"
                + "Bundled fonts, licensed under the SIL Open Font License 1.1: "
                + "Fredoka (Copyright 2016 The Fredoka Project Authors), "
                + "Big Shoulders Display (The Big Shoulders Project Authors), "
                + "Bodoni Moda (The Bodoni Moda Project Authors). "
                + "License texts are included in the app under assets/fonts/licenses.", 13, Ui.SUB);
        credits.setPadding(0, 0, 0, ui.dp(14));
        credits.setVisibility(View.GONE);
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        updateRow = ui.navRow("Check for updates", null, v -> {
            updateRow.setSub("Checking…");
            new Thread(() -> {
                Updater.check(this);
                runOnUiThread(this::refreshUpdate);
            }).start();
        });
        card.addView(updateRow.view);
        card.addView(ui.divider());
        card.addView(switchRow("Install updates automatically", "Checks GitHub twice a day and installs new versions with root",
                Updater.KEY_AUTO, true).view);
        card.addView(ui.divider());
        Ui.Row about = ui.navRow("About Tab AOD", "Version " + version + " · licences and credits",
                v -> credits.setVisibility(credits.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        card.addView(about.view);
        card.addView(credits);
        return card;
    }

    // --- updates ---

    private View updateCardView;
    private Ui.Row updateRow, updateInfo;
    private TextView updateButton;

    private View updateCard() {
        LinearLayout card = ui.card("Update");
        updateInfo = ui.row("", null, null);
        updateInfo.title.setTextSize(19);
        card.addView(updateInfo.view);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, ui.dp(4), 0, ui.dp(14));
        updateButton = ui.button("Install", true, v -> {
            updateButton.setText("Installing…");
            updateButton.setEnabled(false);
            new Thread(() -> {
                String r = Updater.install(this); // the app restarts if this succeeds
                runOnUiThread(() -> {
                    toast(r);
                    updateButton.setText("Install");
                    updateButton.setEnabled(true);
                    refreshUpdate();
                });
            }).start();
        });
        buttons.addView(updateButton);
        addSpaced(buttons, ui.button("What's new", false, v -> startActivity(new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse(Updater.RELEASES + "/latest")))));
        card.addView(buttons);
        card.setVisibility(View.GONE);
        updateCardView = card;
        return card;
    }

    private void refreshUpdate() {
        if (updateRow == null) return;
        String installed = "";
        try { installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        boolean available = Updater.available(this);
        updateCardView.setVisibility(available ? View.VISIBLE : View.GONE);
        updateInfo.title.setText("Version " + Updater.latestVersion(this).replaceFirst("^v", "") + " is available");
        updateInfo.setSub("You have " + installed + ". It installs with root and keeps your settings.");
        long last = sp.getLong(Updater.KEY_LAST, 0);
        String when = last == 0 ? "never" : android.text.format.DateUtils.getRelativeTimeSpanString(last).toString();
        String err = sp.getString(Updater.KEY_ERROR, null);
        updateRow.setSub(err != null ? "Couldn't check (" + err + ") · " + when
                : available ? "Update available · checked " + when
                : last == 0 ? "Not checked yet" : "Up to date · checked " + when);
    }

    // --- rows bound to settings ---

    private TextView label(String s) {
        TextView t = ui.text(s, 15, Ui.SUB);
        t.setPadding(0, ui.dp(10), 0, ui.dp(10));
        return t;
    }

    private Switch boundSwitch(String key, boolean def) {
        Switch sw = ui.toggle(sp.getBoolean(key, def));
        sw.setOnCheckedChangeListener((v, on) -> sp.edit().putBoolean(key, on).apply());
        boundSwitches.add(new Object[]{sw, key, def});
        return sw;
    }

    private Ui.Row switchRow(String title, String sub, String key, boolean def) {
        Switch sw = boundSwitch(key, def);
        Ui.Row r = ui.row(title, sub, sw);
        clickToggles(r, sw);
        return r;
    }

    /** Tapping anywhere on the row flips its switch, like Settings. */
    private void clickToggles(Ui.Row r, Switch sw) {
        r.view.setBackground(ui.ripple(null, 14));
        r.view.setOnClickListener(v -> sw.toggle());
        sw.setClickable(true);
    }

    private Ui.Row sliderRow(String title, String sub, String key, int min, int max, int def, String unit) {
        TextView value = ui.text(sp.getInt(key, def) + unit, 15, Ui.TEXT);
        ui.onAccent(() -> value.setTextColor(ui.accent));
        Ui.Row r = ui.row(title, sub, value);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, ui.dp(4), 0, ui.dp(10));
        r.view.setPadding(0, ui.dp(12), 0, 0);
        r.view.setMinimumHeight(0);
        SeekBar bar = ui.slider();
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(sp.getInt(key, def));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                value.setText(v + unit);
                if (user) sp.edit().putInt(key, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        // The row view holds the title line; wrap it with the slider so both enable together.
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(r.view);
        box.addView(bar);
        outer.addView(box);
        return new Ui.Row(outer, r.title, r.sub);
    }

    // --- state that depends on several settings ---

    private final List<Runnable> dependents = new ArrayList<>();

    private void updateDependents() {
        if (sp == null || heroRow == null) return;
        Prefs p = Prefs.load(this);
        heroRow.setSub(p.enabled ? "On · shows when the screen turns off" : "Off · the screen just turns off");
        popupRow.setEnabled(p.popups);
        for (Ui.Row r : widgetRows) r.setEnabled(p.showWidgets);
        for (Ui.Row r : wallpaperRows) r.setEnabled(p.wallpaper);
        modeRow.setSub(Prefs.MODE_NAMES[p.showMode]
                + (p.showMode == Prefs.MODE_SCHEDULE ? " · " + Prefs.time(p.schedStart) + "–" + Prefs.time(p.schedEnd) : ""));
        for (RadioButton rb : modeRadios) rb.setChecked((Integer) rb.getTag() == p.showMode);
        scheduleRow.view.setVisibility(p.showMode == Prefs.MODE_SCHEDULE ? View.VISIBLE : View.GONE);
        scheduleRow.setSub("Shows from " + Prefs.time(p.schedStart) + " to " + Prefs.time(p.schedEnd));
        for (Runnable r : dependents) r.run();
        updateStatusLine(p);
    }

    private void updateStatusLine(Prefs p) {
        if (statusLine == null) return;
        if (setupOk == null) statusLine.setText(Ui.dot("Checking setup…", Ui.FAINT));
        else if (!setupOk) statusLine.setText(Ui.dot("Setup needed · see Setup below", Ui.WARN));
        else if (!p.enabled) statusLine.setText(Ui.dot("Off", Ui.FAINT));
        else statusLine.setText(Ui.dot("On · " + Prefs.MODE_NAMES[p.showMode], Ui.GOOD));
    }

    private void showStatus(String out) {
        boolean[] ok = {
            out.contains("getDozeComponent()=") && out.contains(PKG),
            out.contains("DreamService"),
            out.matches("(?s).*notification access: [1-9].*")};
        boolean root = !out.startsWith("error") && !out.contains("exit 1") && out.contains("doze service:");
        boolean all = true;
        for (int i = 0; i < ok.length; i++) {
            boolean good = root && ok[i];
            all &= good;
            setupPills[i].setText(!root ? "Needs root" : good ? "Ready" : "Missing");
            setupPills[i].setTextColor(good ? Ui.GOOD : Ui.WARN);
        }
        setupOk = all;
        updateStatusLine(Prefs.load(this));
    }

    private void showConsole(String out) {
        console.setText(out.trim());
        console.setVisibility(View.VISIBLE);
    }

    private int accentColour() {
        return Prefs.ACCENTS[Math.min(sp.getInt("accent", 1), Prefs.ACCENTS.length - 1)];
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Runs a root script off the main thread; {@code done} gets its output on the main thread. */
    private void run(String script, Consumer<String> done) {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                Process p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    for (String l; (l = r.readLine()) != null; ) sb.append(l).append('\n');
                }
                sb.append("exit ").append(p.waitFor());
            } catch (Exception e) {
                sb.append("error: ").append(e);
            }
            runOnUiThread(() -> done.accept(sb.toString()));
        }).start();
    }
}
