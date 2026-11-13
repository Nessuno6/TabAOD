package dev.deang.tabaod;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Building blocks for the settings screen: dark cards with grouped rows, switches and
 * sliders tinted with the AOD's accent colour (re-tinted live when the accent changes).
 */
final class Ui {
    static final int BG = 0xFF0A0A0D, CARD = 0xFF16161B, RAISED = 0xFF222229, LINE = 0x1AFFFFFF;
    static final int TEXT = 0xFFF2F2F5, SUB = 0xFF9A9AA5, FAINT = 0xFF5E5E68;
    static final int GOOD = 0xFF5BD68A, WARN = 0xFFFFB547, OFF_TRACK = 0xFF3A3A43, OFF_THUMB = 0xFFC9C9D1;

    final Context c;
    private final float density;
    int accent;
    /** Re-tint actions for everything drawn in the accent colour. */
    private final List<Runnable> accentHooks = new ArrayList<>();

    Ui(Context c, int accent) {
        this.c = c;
        this.accent = accent;
        density = c.getResources().getDisplayMetrics().density;
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    void setAccent(int colour) {
        accent = colour;
        for (Runnable r : accentHooks) r.run();
    }

    /** Runs {@code r} now and again whenever the accent changes. */
    void onAccent(Runnable r) {
        accentHooks.add(r);
        r.run();
    }

    GradientDrawable round(int colour, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(colour);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    Drawable ripple(Drawable content, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, round(Color.WHITE, radiusDp));
    }

    TextView text(String s, float sp, int colour) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        return t;
    }

    TextView title(String s, float sp) {
        TextView t = text(s, sp, TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** A rounded card; {@code heading} (may be null) is a small accent label inside its top. */
    LinearLayout card(String heading) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 26));
        card.setPadding(dp(22), dp(heading == null ? 8 : 18), dp(22), dp(8));
        if (heading != null) {
            TextView h = text(heading.toUpperCase(), 12, accent);
            h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            h.setLetterSpacing(0.1f);
            h.setPadding(0, 0, 0, dp(4));
            onAccent(() -> h.setTextColor(accent));
            card.addView(h);
        }
        return card;
    }

    View divider() {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))));
        return v;
    }

    /** One setting: title and optional subtitle on the left, {@code end} (may be null) on the right. */
    static final class Row {
        final LinearLayout view;
        final TextView title, sub;

        Row(LinearLayout view, TextView title, TextView sub) {
            this.view = view;
            this.title = title;
            this.sub = sub;
        }

        void setSub(CharSequence s) {
            sub.setText(s);
            sub.setVisibility(TextUtils.isEmpty(s) ? View.GONE : View.VISIBLE);
        }

        /** Greys the row out (and disables its control) when a parent setting is off. */
        void setEnabled(boolean on) {
            view.setAlpha(on ? 1f : 0.38f);
            enableAll(view, on);
        }

        private static void enableAll(View v, boolean on) {
            v.setEnabled(on);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) enableAll(g.getChildAt(i), on);
            }
        }
    }

    Row row(String title, String sub, View end) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 17, TEXT);
        TextView s = text("", 14, SUB);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(t);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (end != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(16);
            row.addView(end, lp);
        }
        Row r = new Row(row, t, s);
        r.setSub(sub);
        return r;
    }

    /** A row that opens something: tappable, with a chevron. */
    Row navRow(String title, String sub, View.OnClickListener l) {
        TextView chevron = text("›", 26, FAINT);
        Row r = row(title, sub, chevron);
        r.view.setBackground(ripple(null, 14));
        r.view.setOnClickListener(l);
        return r;
    }

    Switch toggle(boolean checked) {
        Switch sw = new Switch(c);
        sw.setChecked(checked);
        sw.setShowText(false);
        onAccent(() -> {
            int[][] states = {{android.R.attr.state_checked}, {}};
            sw.setThumbTintList(new ColorStateList(states, new int[]{Color.WHITE, OFF_THUMB}));
            sw.setTrackTintList(new ColorStateList(states, new int[]{accent, OFF_TRACK}));
        });
        return sw;
    }

    SeekBar slider() {
        SeekBar bar = new SeekBar(c);
        bar.setSplitTrack(false);
        onAccent(() -> {
            bar.setProgressTintList(ColorStateList.valueOf(accent));
            bar.setThumbTintList(ColorStateList.valueOf(accent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(OFF_TRACK));
        });
        return bar;
    }

    /** A pill-shaped choice; see {@link #setChip}. */
    TextView chip(String label) {
        TextView t = text(label, 15, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(72));
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        return t;
    }

    void setChip(TextView chip, boolean selected) {
        GradientDrawable d = round(selected ? accent : RAISED, 999);
        chip.setBackground(ripple(d, 999));
        chip.setTextColor(selected ? onAccent() : TEXT);
    }

    /** Text colour that reads on the accent (all accents are light, so near-black). */
    int onAccent() {
        return 0xFF101014;
    }

    /** A rounded button; primary ones are filled with the accent. */
    TextView button(String label, boolean primary, View.OnClickListener l) {
        TextView b = text(label, 15, TEXT);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(20), dp(12), dp(20), dp(12));
        b.setOnClickListener(l);
        if (primary) {
            onAccent(() -> {
                b.setBackground(ripple(round(accent, 999), 999));
                b.setTextColor(onAccent());
            });
        } else {
            b.setBackground(ripple(round(RAISED, 999), 999));
        }
        return b;
    }

    /** A small coloured dot followed by text, for status lines. */
    static CharSequence dot(String text, int colour) {
        android.text.SpannableString s = new android.text.SpannableString("●  " + text);
        s.setSpan(new android.text.style.ForegroundColorSpan(colour), 0, 1, 0);
        return s;
    }
}
