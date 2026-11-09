package godui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;

/** Tiny programmatic UI kit: cards, chips, buttons, light/dark. No resources, no AppCompat. */
public final class Ui {
    public final Context c; public final boolean dark;
    public final int bg, card, text, sub, line, accent, ok, warn, err;
    public Ui(Context c) {
        this.c = c; dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF121316 : 0xFFF3F4F7; card = dark ? 0xFF1E2025 : 0xFFFFFFFF; text = dark ? 0xFFEDEEF0 : 0xFF16181D;
        sub = dark ? 0xFF9AA0AA : 0xFF646B76; line = dark ? 0xFF2C2F36 : 0xFFE3E5EA;
        accent = dark ? 0xFF7AA7FF : 0xFF2F6BFF; ok = dark ? 0xFF5BD68B : 0xFF1E9E5A; warn = dark ? 0xFFFFC266 : 0xFFD98200; err = dark ? 0xFFFF7A7A : 0xFFD93636;
    }
    public int dp(float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public GradientDrawable rect(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor); return g;
    }

    /** Full-screen page: header (title, subtitle, optional action buttons) + scrolling column. Returns the column. */
    public LinearLayout page(Activity a, String title, String subtitle, View... actions) {
        Window w = a.getWindow(); w.setStatusBarColor(bg); w.setNavigationBarColor(bg);
        if (!dark) w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView sv = new ScrollView(a); sv.setBackgroundColor(bg); sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(28), dp(16), dp(32)); sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        TextView t = label(title, 26, text, true); col.addView(t);
        if (subtitle != null) { TextView s = label(subtitle, 13, sub, false); s.setPadding(0, dp(2), 0, dp(10)); col.addView(s); }
        if (actions.length > 0) { LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, dp(4), 0, dp(8));
            for (View v : actions) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.rightMargin = dp(8); row.addView(v, lp); } col.addView(row); }
        a.setContentView(sv); return col;
    }

    public TextView label(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }

    public LinearLayout card(ViewGroup parent) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(rect(card, 16, line));
        l.setPadding(dp(16), dp(14), dp(16), dp(14)); l.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10); parent.addView(l, lp); return l;
    }

    public TextView section(ViewGroup parent, String s) {
        TextView t = label(s.toUpperCase(), 12, sub, true); t.setLetterSpacing(0.08f); t.setPadding(dp(4), dp(22), 0, dp(2)); parent.addView(t); return t;
    }

    /** Status chip: color-coded pill. */
    public TextView chip(String s, int color) {
        TextView t = label(s, 12, color, true); t.setPadding(dp(10), dp(3), dp(10), dp(3));
        t.setBackground(rect((color & 0x00FFFFFF) | 0x26000000, 20, 0)); return t;
    }

    /** Round icon badge with an emoji/letter. */
    public TextView badge(String glyph) {
        TextView t = label(glyph, 20, text, false); t.setGravity(Gravity.CENTER); t.setBackground(rect((accent & 0x00FFFFFF) | 0x22000000, 14, 0));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44))); return t;
    }

    public TextView button(String s, boolean primary, final Runnable r) {
        TextView b = label(s, 14, primary ? (dark ? 0xFF0B1220 : 0xFFFFFFFF) : accent, true);
        b.setGravity(Gravity.CENTER); b.setPadding(dp(14), dp(10), dp(14), dp(10)); b.setSingleLine(false);
        GradientDrawable base = rect(primary ? accent : 0, 12, primary ? 0 : (accent & 0x00FFFFFF) | 0x66000000);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080), base, null)); b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { r.run(); } }); return b;
    }

    public LinearLayout.LayoutParams gap(int topDp) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(topDp); return lp; }

    /** key / value row with a status dot. */
    public TextView row(ViewGroup parent, String key, String value, int dot) {
        LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(5), 0, dp(5));
        TextView d = label("●", 12, dot, false); d.setPadding(0, 0, dp(10), 0); r.addView(d);
        LinearLayout tx = new LinearLayout(c); tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(label(key, 12, sub, false)); TextView v = label(value, 14, text, false); v.setTextIsSelectable(true); tx.addView(v);
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1)); parent.addView(r); return v;
    }

    public TextView mono(ViewGroup parent) {
        TextView t = label("", 12, text, false); t.setTypeface(Typeface.MONOSPACE); t.setTextIsSelectable(true);
        t.setBackground(rect(dark ? 0xFF14161A : 0xFFF0F2F5, 10, 0)); t.setPadding(dp(12), dp(10), dp(12), dp(10)); t.setVisibility(View.GONE);
        parent.addView(t, gap(10)); return t;
    }
}
