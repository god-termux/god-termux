package com.termux.mod.macros;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import com.termux.app.TermuxActivity;

/** Detects "tap, tap, tap-and-hold" at one spot of the terminal and then drives the radial macro menu. */
public final class RadialGesture {
    private static final long TAP_MAX_MS = 250, TAP_GAP_MS = 300, HOLD_MS = 300;
    private final TermuxActivity act; private final Handler handler = new Handler(Looper.getMainLooper());
    private final float slop; private RadialMenu menu;
    private int taps; private long downTime, lastUp; private float lx, ly; private boolean down;

    public RadialGesture(TermuxActivity a) { act = a; slop = 36 * a.getResources().getDisplayMetrics().density; }

    private final Runnable hold = new Runnable() { @Override public void run() {
        if (!down || taps != 3) return;
        MotionEvent c = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, lx, ly, 0);
        act.godSuperDispatch(c); c.recycle();                      // the terminal must not start a text selection
        menu = new RadialMenu(act, new MacroStore(act));
        if (!menu.show(lx, ly)) { menu = null; reset(); }
    } };

    private void reset() { taps = 0; down = false; handler.removeCallbacks(hold); }

    private boolean inTerminal(MotionEvent e) {
        View t = act.getTerminalView(); if (t == null || act.getDrawer().isDrawerOpen(android.view.Gravity.LEFT)) return false;
        int[] loc = new int[2]; t.getLocationInWindow(loc);
        return e.getX() >= loc[0] && e.getX() <= loc[0] + t.getWidth() && e.getY() >= loc[1] && e.getY() <= loc[1] + t.getHeight();
    }

    /** @return true when the event was consumed (radial menu active). */
    public boolean onTouch(MotionEvent e) {
        if (menu != null) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_MOVE) menu.move(e.getX(), e.getY());
            else if (a == MotionEvent.ACTION_UP) { menu.release(e.getX(), e.getY()); menu = null; reset(); }
            else if (a == MotionEvent.ACTION_CANCEL) { menu.dismiss(); menu = null; reset(); }
            return true;
        }
        if (e.getPointerCount() > 1) { reset(); return false; }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inTerminal(e)) { reset(); return false; }
                long now = e.getEventTime();
                boolean near = Math.hypot(e.getX() - lx, e.getY() - ly) <= slop;
                taps = (taps > 0 && now - lastUp <= TAP_GAP_MS && near) ? taps + 1 : 1;
                lx = e.getX(); ly = e.getY(); downTime = now; down = true;
                if (taps == 3) handler.postDelayed(hold, HOLD_MS);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (down && Math.hypot(e.getX() - lx, e.getY() - ly) > slop) reset();
                return false;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(hold); down = false;
                if (e.getEventTime() - downTime > TAP_MAX_MS) taps = 0; else lastUp = e.getEventTime();
                return false;
            default: return false;
        }
    }
}
