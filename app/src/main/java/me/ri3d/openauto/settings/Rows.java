package me.ri3d.openauto.settings;

import android.content.Context;
import android.content.res.Resources;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import me.ri3d.openauto.R;
import me.ri3d.openauto.ui.BarlowText;
import me.ri3d.openauto.ui.Fonts;
import me.ri3d.openauto.ui.IconView;

/** Settings row widgets in the reference style: title + description left, control right. */
public final class Rows {
    public interface OnChoice { void onChoice(int index); }
    public interface OnStep { void onStep(int value); }

    private Rows() {}

    private static int px(Context c, int dimen) {
        return c.getResources().getDimensionPixelSize(dimen);
    }

    private static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    /** A row shell. Returns the row; the right-hand slot is child index 1. */
    public static LinearLayout row(Context c, CharSequence title, CharSequence desc) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(c, R.dimen.row_h));
        int pv = px(c, R.dimen.gap_h);
        row.setPadding(0, pv, 0, pv);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.weight = 1;
        tlp.rightMargin = px(c, R.dimen.gap_v);
        row.addView(text, tlp);

        BarlowText t = text(c, title, Fonts.SEMIBOLD, R.dimen.row_title, R.color.fg);
        text.addView(t);
        if (desc != null) {
            BarlowText d = text(c, desc, Fonts.REGULAR, R.dimen.row_desc, R.color.muted);
            LinearLayout.LayoutParams dlp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = px(c, R.dimen.group_pad);
            text.addView(d, dlp);
        }
        return row;
    }

    /** Adds a muted note line under the description (e.g. "applies at next connection"). */
    public static BarlowText note(LinearLayout row, CharSequence note, int color) {
        Context c = row.getContext();
        LinearLayout text = (LinearLayout) row.getChildAt(0);
        BarlowText n = text(c, note, Fonts.REGULAR, R.dimen.row_desc, color);
        LinearLayout.LayoutParams lp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(c, R.dimen.group_pad);
        text.addView(n, lp);
        return n;
    }

    public static BarlowText text(Context c, CharSequence s, int font, int sizeDimen, int colorRes) {
        BarlowText t = new BarlowText(c);
        t.setFont(font);
        t.setText(s);
        t.setIncludeFontPadding(false);
        Resources r = c.getResources();
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, r.getDimensionPixelSize(sizeDimen));
        t.setTextColor(r.getColor(colorRes));
        return t;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(c.getResources().getColor(R.color.tile_border));
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(c, R.dimen.dot_ring) / 2)));
        return v;
    }

    private static LinearLayout group(Context c) {
        LinearLayout g = new LinearLayout(c);
        g.setOrientation(LinearLayout.HORIZONTAL);
        g.setGravity(Gravity.CENTER_VERTICAL);
        g.setBackgroundResource(R.drawable.bg_segment_group);
        int p = px(c, R.dimen.group_pad);
        g.setPadding(p, p, p, p);
        return g;
    }

    /**
     * Segmented choice. {@code disabledReason[i]} non-null disables option i and shows the reason
     * as a note when it is tapped.
     */
    public static LinearLayout choice(Context c, CharSequence title, CharSequence desc, CharSequence[] labels,
                                      int selected, final String[] disabledReason, final OnChoice cb) {
        final LinearLayout row = row(c, title, desc);
        final LinearLayout g = group(c);
        final BarlowText[] reason = new BarlowText[1];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            BarlowText b = text(c, labels[i], Fonts.SEMIBOLD, R.dimen.seg_text, R.color.segment_text);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(px(c, R.dimen.seg_h));
            b.setMinWidth(px(c, R.dimen.seg_minw));
            int ph = px(c, R.dimen.seg_pad_h);
            b.setPadding(ph, 0, ph, 0);
            b.setSingleLine(true);
            boolean disabled = disabledReason != null && disabledReason[i] != null;
            if (disabled) b.setAlpha(0.35f);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (disabledReason != null && disabledReason[idx] != null) {
                        if (reason[0] == null) reason[0] = note(row, "", R.color.accent);
                        reason[0].setText(disabledReason[idx]);
                        return;
                    }
                    select(g, idx);
                    cb.onChoice(idx);
                }
            });
            LinearLayout.LayoutParams blp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) blp.leftMargin = px(c, R.dimen.group_pad);
            g.addView(b, blp);
        }
        select(g, selected);
        row.addView(g);
        return row;
    }

    public static void select(LinearLayout g, int idx) {
        Resources r = g.getResources();
        for (int i = 0; i < g.getChildCount(); i++) {
            BarlowText b = (BarlowText) g.getChildAt(i);
            boolean on = i == idx;
            b.setBackgroundResource(on ? R.drawable.bg_segment_active : R.drawable.bg_segment);
            b.setTextColor(r.getColor(on ? R.color.on_accent : R.color.segment_text));
        }
    }

    public static LinearLayout toggle(Context c, CharSequence title, CharSequence desc, boolean on,
                                      String disabledReason, final OnChoice cb) {
        String[] dis = disabledReason == null ? null : new String[]{disabledReason, disabledReason};
        return choice(c, title, desc, new CharSequence[]{c.getString(R.string.off), c.getString(R.string.on)},
                on ? 1 : 0, dis, cb);
    }

    public static LinearLayout stepper(Context c, CharSequence title, CharSequence desc, final int min, final int max,
                                       final int step, int value, final OnStep cb) {
        LinearLayout row = row(c, title, desc);
        LinearLayout g = group(c);
        final BarlowText val = text(c, String.valueOf(value), Fonts.SEMIBOLD, R.dimen.stepper_value_text, R.color.fg);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(px(c, R.dimen.stepper_value_w));
        final int[] cur = {value};
        View.OnClickListener l = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int n = cur[0] + ((Integer) v.getTag()) * step;
                n = Math.max(min, Math.min(max, n));
                if (n == cur[0]) return;
                cur[0] = n;
                val.setText(String.valueOf(n));
                cb.onStep(n);
            }
        };
        g.addView(stepButton(c, IconView.MINUS, -1, l));
        g.addView(val);
        g.addView(stepButton(c, IconView.PLUS, 1, l));
        row.addView(g);
        return row;
    }

    private static View stepButton(Context c, int icon, int dir, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundResource(R.drawable.bg_stepper);
        f.setLayoutParams(lp(px(c, R.dimen.stepper_w), px(c, R.dimen.seg_h)));
        IconView iv = new IconView(c, null);
        iv.setIcon(icon);
        iv.setColor(c.getResources().getColor(R.color.fg));
        int s = px(c, R.dimen.nav_icon);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(s, s, Gravity.CENTER);
        f.addView(iv, ilp);
        f.setTag(dir);
        f.setOnClickListener(l);
        f.setClickable(true);
        return f;
    }

    /** Whole-row action with a trailing arrow. */
    public static LinearLayout action(Context c, CharSequence title, CharSequence desc, View.OnClickListener l) {
        LinearLayout row = row(c, title, desc);
        row.setBackgroundResource(R.drawable.bg_row_action);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(l);
        IconView iv = new IconView(c, null);
        iv.setIcon(IconView.ARROW_RIGHT);
        iv.setColor(c.getResources().getColor(R.color.muted));
        int s = px(c, R.dimen.nav_icon);
        row.addView(iv, lp(s, s));
        return row;
    }

    /** Read-only label/value row used by Diagnostics. */
    public static LinearLayout info(Context c, CharSequence label, CharSequence value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pv = px(c, R.dimen.gap_h);
        row.setPadding(0, pv, 0, pv);
        BarlowText l = text(c, label, Fonts.SEMIBOLD, R.dimen.diag_text, R.color.fg);
        LinearLayout.LayoutParams llp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.weight = 0.42f;
        row.addView(l, llp);
        BarlowText v = text(c, value, Fonts.REGULAR, R.dimen.diag_text, R.color.muted);
        v.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams vlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.weight = 0.58f;
        row.addView(v, vlp);
        return row;
    }
}
