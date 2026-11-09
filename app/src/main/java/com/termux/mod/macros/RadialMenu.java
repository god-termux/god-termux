package com.termux.mod.macros;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.termux.app.TermuxActivity;
import godui.Ui;
import java.util.ArrayList;
import java.util.List;

/** Radial menu around the finger: drag towards a macro and release to run it; release in the middle to cancel. Name of the choice is shown in a tooltip. */
public final class RadialMenu {
    private final TermuxActivity act; private final MacroStore store; private final Ui ui;
    private final List<Macro> items = new ArrayList<>();
    private View view; private float cx, cy, fx, fy, startX, startY; private int sel = -1; private boolean moved;
    private final float dp;

    public RadialMenu(TermuxActivity a, MacroStore s) {
        act = a; store = s; ui = new Ui(a); dp = a.getResources().getDisplayMetrics().density;
        for (int i = 0; i < Math.min(8, s.macros.size()); i++) items.add(s.macros.get(i));
    }

    /** @return false when there is nothing to show */
    public boolean show(float x, float y) {
        if (items.isEmpty()) { android.widget.Toast.makeText(act, "Нет кнопок-макросов", android.widget.Toast.LENGTH_SHORT).show(); return false; }
        ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
        float reach = (96 + 34 + 10) * dp;                                   // ring radius + bubble + margin
        cx = Math.max(reach, Math.min(decor.getWidth() - reach, x)); cy = Math.max(reach + 40 * dp, Math.min(decor.getHeight() - reach, y));
        fx = startX = x; fy = startY = y;
        view = new RadialView(act);
        decor.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        return true;
    }

    public void move(float x, float y) {
        fx = x; fy = y;
        if (!moved && Math.hypot(x - startX, y - startY) > 14 * dp) moved = true;
        int n = pick(x, y);
        if (n != sel) { sel = n; if (view != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
        if (view != null) view.invalidate();
    }

    public void release(float x, float y) {
        move(x, y); Macro m = sel >= 0 ? items.get(sel) : null; dismiss();
        if (m != null) MacroRunner.run(act, m, store);
    }

    public void dismiss() { if (view != null && view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view); view = null; }

    private int pick(float x, float y) {
        if (!moved) return -1;
        double dx = x - cx, dy = y - cy;
        if (Math.hypot(dx, dy) < 36 * dp) return -1;                          // middle = cancel
        double ang = Math.toDegrees(Math.atan2(dy, dx)) + 90;                 // 0 = up, clockwise
        double step = 360.0 / items.size();
        return (int) Math.round(((ang % 360 + 360) % 360) / step) % items.size();
    }

    private final class RadialView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadialView(Context c) { super(c); setClickable(false); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(Typeface.DEFAULT_BOLD); }

        @Override protected void onDraw(Canvas c) {
            float R = 96 * dp, br = 34 * dp;
            p.setStyle(Paint.Style.FILL); p.setColor(0x33000000); c.drawRect(0, 0, getWidth(), getHeight(), p);
            p.setColor(ui.dark ? 0xB31A1C21 : 0xB3FFFFFF); c.drawCircle(cx, cy, R + br + 6 * dp, p);              // glass disc
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * dp); p.setColor(ui.accent & 0x00FFFFFF | 0x66000000); c.drawCircle(cx, cy, 36 * dp, p);
            p.setStyle(Paint.Style.FILL);
            int n = items.size();
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(-90 + i * 360.0 / n); float bx = (float) (cx + R * Math.cos(a)), by = (float) (cy + R * Math.sin(a));
                boolean on = i == sel;
                p.setColor(on ? ui.accent : (ui.dark ? 0xFF23262D : 0xFFECEEF3)); c.drawCircle(bx, by, on ? br * 1.12f : br, p);
                t.setColor(on ? (ui.dark ? 0xFF0B1220 : 0xFFFFFFFF) : ui.text); t.setTextSize(11 * dp);
                String s = items.get(i).label; if (s.length() > 9) s = s.substring(0, 8) + "…";
                c.drawText(s, bx, by + 4 * dp, t);
            }
            // finger marker
            p.setColor(ui.accent & 0x00FFFFFF | 0x99000000); c.drawCircle(Math.max(0, Math.min(getWidth(), fx)), Math.max(0, Math.min(getHeight(), fy)), 9 * dp, p);
            // tooltip with the name of the current choice
            String tip = sel >= 0 ? items.get(sel).label + "  —  отпустите" : "Ведите к кнопке  ·  в центре отмена";
            t.setTextSize(14 * dp); float w = t.measureText(tip) + 28 * dp, h = 34 * dp, ty = cy - R - br - 30 * dp;
            RectF r = new RectF(cx - w / 2, ty - h, cx + w / 2, ty);
            p.setColor(sel >= 0 ? ui.accent : (ui.dark ? 0xFF23262D : 0xFFECEEF3)); c.drawRoundRect(r, h / 2, h / 2, p);
            t.setColor(sel >= 0 ? (ui.dark ? 0xFF0B1220 : 0xFFFFFFFF) : ui.text); c.drawText(tip, cx, ty - h / 2 + 5 * dp, t);
        }
    }
}
