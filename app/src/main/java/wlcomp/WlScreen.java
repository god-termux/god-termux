package wlcomp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.termux.mod.ModScreen;
import godui.Ui;
import java.io.File;

/** Window of the god-termux Wayland compositor: GTK/Qt programs started with `wlrun` are shown here (GPU composition through EGL). */
public class WlScreen implements ModScreen {
    static { System.loadLibrary("wlcomp"); }
    static native void nativeSurface(Surface s, String runtimeDir, int w, int h, int scale);
    static native void nativeResize(int w, int h);
    static native void nativeStop();
    static native boolean nativeRunning();
    static native int nativeWindows();
    static native void nativeMotion(float x, float y);
    static native void nativeButton(int button, int pressed);
    static native void nativeAxis(float dy);
    static native void nativeKey(int evdev, int pressed);
    static native void nativeChar(int c);
    static native void nativeCycle();

    private Activity act; private Ui ui; private WlView view; private TextView hint; private LinearLayout keybar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ctrl, alt; private TextView ctrlBtn, altBtn; private boolean warnedNonAscii;

    @Override public void onCreate(Activity a, Bundle b) {
        act = a; ui = new Ui(a);
        FrameLayout root = new FrameLayout(a); root.setBackgroundColor(Color.BLACK);
        view = new WlView(a); root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        hint = ui.label("Wayland-композитор готов\n\nЗапустите программу в Termux:\n  wlrun xfce4-terminal\n  wlrun gtk3-widget-factory", 15, 0xFFB0B6C0, false);
        hint.setGravity(Gravity.CENTER); hint.setPadding(ui.dp(24), 0, ui.dp(24), 0);
        root.addView(hint, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(btn("▤", new Runnable() { @Override public void run() { nativeCycle(); } }));
        top.addView(btn("⌨", new Runnable() { @Override public void run() { toggleKeyboard(); } }));
        top.addView(btn("✕", new Runnable() { @Override public void run() { act.finish(); } }));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); tp.setMargins(0, ui.dp(8), ui.dp(8), 0);
        root.addView(top, tp);

        keybar = new LinearLayout(a); keybar.setOrientation(LinearLayout.HORIZONTAL); keybar.setBackgroundColor(0xE61A1C21); keybar.setVisibility(View.GONE);
        keybar.addView(key("Esc", 1)); keybar.addView(key("Tab", 15)); ctrlBtn = stickyKey("Ctrl", true); altBtn = stickyKey("Alt", false);
        keybar.addView(ctrlBtn); keybar.addView(altBtn); keybar.addView(key("←", 105)); keybar.addView(key("↑", 103)); keybar.addView(key("↓", 108)); keybar.addView(key("→", 106));
        root.addView(keybar, new FrameLayout.LayoutParams(-1, ui.dp(44), Gravity.BOTTOM));
        a.setContentView(root);
        handler.post(poll);
    }

    private final Runnable poll = new Runnable() { @Override public void run() {
        hint.setVisibility(nativeRunning() && nativeWindows() > 0 ? View.GONE : View.VISIBLE); handler.postDelayed(this, 500); } };

    private TextView btn(String s, final Runnable r) {
        TextView t = ui.button(s, false, r); t.setTextSize(18); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(46), ui.dp(40)); lp.leftMargin = ui.dp(6);
        t.setLayoutParams(lp); t.setBackground(ui.rect(0x99000000, 12, 0x33FFFFFF)); return t;
    }

    private TextView key(String s, final int evdev) {
        TextView t = ui.button(s, false, new Runnable() { @Override public void run() { tap(evdev); } });
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1)); return t;
    }

    private TextView stickyKey(String s, final boolean isCtrl) {
        final TextView[] self = new TextView[1];
        self[0] = ui.button(s, false, new Runnable() { @Override public void run() {
            if (isCtrl) ctrl = !ctrl; else alt = !alt; self[0].setBackground(ui.rect((isCtrl ? ctrl : alt) ? ui.accent : 0, 12, ui.accent)); } });
        self[0].setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1)); return self[0];
    }

    private void releaseSticky() { ctrl = alt = false; if (ctrlBtn != null) ctrlBtn.setBackground(ui.rect(0, 12, ui.accent)); if (altBtn != null) altBtn.setBackground(ui.rect(0, 12, ui.accent)); }

    private void tap(int evdev) { mods(true); nativeKey(evdev, 1); nativeKey(evdev, 0); mods(false); releaseSticky(); }
    private void mods(boolean down) { if (ctrl) nativeKey(29, down ? 1 : 0); if (alt) nativeKey(56, down ? 1 : 0); }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean show = keybar.getVisibility() != View.VISIBLE;
        keybar.setVisibility(show ? View.VISIBLE : View.GONE);
        view.requestFocus();
        if (show) imm.showSoftInput(view, InputMethodManager.SHOW_FORCED); else imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void typeChar(int c) {
        if (c > 127) { if (!warnedNonAscii) { warnedNonAscii = true; Toast.makeText(act, "Пока поддерживается ввод только латиницей и цифрами", Toast.LENGTH_LONG).show(); } return; }
        mods(true); nativeChar(c); mods(false); releaseSticky();
    }

    // Android keycode -> Linux evdev code (hardware keyboards and special keys)
    private static int evdev(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return 28; case KeyEvent.KEYCODE_DEL: return 14; case KeyEvent.KEYCODE_FORWARD_DEL: return 111;
            case KeyEvent.KEYCODE_TAB: return 15; case KeyEvent.KEYCODE_ESCAPE: return 1; case KeyEvent.KEYCODE_SPACE: return 57;
            case KeyEvent.KEYCODE_DPAD_UP: return 103; case KeyEvent.KEYCODE_DPAD_DOWN: return 108; case KeyEvent.KEYCODE_DPAD_LEFT: return 105; case KeyEvent.KEYCODE_DPAD_RIGHT: return 106;
            case KeyEvent.KEYCODE_MOVE_HOME: return 102; case KeyEvent.KEYCODE_MOVE_END: return 107; case KeyEvent.KEYCODE_PAGE_UP: return 104; case KeyEvent.KEYCODE_PAGE_DOWN: return 109;
            case KeyEvent.KEYCODE_INSERT: return 110; case KeyEvent.KEYCODE_CTRL_LEFT: case KeyEvent.KEYCODE_CTRL_RIGHT: return 29; case KeyEvent.KEYCODE_ALT_LEFT: case KeyEvent.KEYCODE_ALT_RIGHT: return 56;
            case KeyEvent.KEYCODE_SHIFT_LEFT: case KeyEvent.KEYCODE_SHIFT_RIGHT: return 42; default: return 0;
        }
    }

    private boolean handleKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN; if (e.getAction() == KeyEvent.ACTION_MULTIPLE) return false;
        int code = evdev(e.getKeyCode());
        if (code != 0) { if (down || e.getAction() == KeyEvent.ACTION_UP) nativeKey(code, down ? 1 : 0); return true; }
        int u = e.getUnicodeChar(e.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK));
        if (u > 0 && down) {
            if (e.isCtrlPressed()) { nativeKey(29, 1); nativeChar(Character.toLowerCase(u)); nativeKey(29, 0); } else typeChar(u);
            return true;
        }
        return u > 0;
    }

    private final class WlView extends SurfaceView implements SurfaceHolder.Callback {
        private boolean leftDown, twoFinger, moved, started; private float downX, downY, lastY, accY; private final int slop;
        private final Runnable longPress = new Runnable() { @Override public void run() {
            if (!moved && leftDown) { nativeButton(0, 0); nativeButton(1, 1); nativeButton(1, 0); leftDown = false; } } };

        WlView(Context c) { super(c); getHolder().addCallback(this); setFocusable(true); setFocusableInTouchMode(true); slop = ViewConfiguration.get(c).getScaledTouchSlop(); }

        @Override public void surfaceCreated(SurfaceHolder h) {}
        @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
            int scale = Math.max(1, Math.min(3, Math.round(getResources().getDisplayMetrics().densityDpi / 240f)));
            nativeSurface(h.getSurface(), new File(act.getFilesDir(), "usr/tmp").getAbsolutePath(), w, hh, scale); started = true;
        }
        @Override public void surfaceDestroyed(SurfaceHolder h) { nativeSurface(null, "", 0, 0, 0); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    moved = false; twoFinger = false; downX = x; downY = y; nativeMotion(x, y); nativeButton(0, 1); leftDown = true;
                    handler.postDelayed(longPress, 550); return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    handler.removeCallbacks(longPress); if (leftDown) { nativeButton(0, 0); leftDown = false; }
                    twoFinger = true; lastY = (e.getY(0) + e.getY(1)) / 2; accY = 0; return true;
                case MotionEvent.ACTION_MOVE:
                    if (twoFinger && e.getPointerCount() >= 2) {
                        float my = (e.getY(0) + e.getY(1)) / 2; accY += (lastY - my); lastY = my;
                        if (Math.abs(accY) >= 6) { nativeAxis(accY / 5f); accY = 0; }
                    } else if (!twoFinger) {
                        if (!moved && Math.hypot(x - downX, y - downY) > slop) { moved = true; handler.removeCallbacks(longPress); }
                        nativeMotion(x, y);
                    }
                    return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress); if (leftDown) { nativeButton(0, 0); leftDown = false; } twoFinger = false; return true;
                default: return true;
            }
        }

        @Override public boolean onCheckIsTextEditor() { return true; }
        @Override public InputConnection onCreateInputConnection(EditorInfo o) {
            o.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            o.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
            return new BaseInputConnection(this, false) {
                @Override public boolean commitText(CharSequence t, int p) { for (int i = 0; i < t.length(); i++) typeChar(t.charAt(i)); return true; }
                @Override public boolean setComposingText(CharSequence t, int p) { return true; }
                @Override public boolean finishComposingText() { return true; }
                @Override public boolean deleteSurroundingText(int before, int after) { for (int i = 0; i < before; i++) { nativeKey(14, 1); nativeKey(14, 0); } return true; }
                @Override public boolean sendKeyEvent(KeyEvent e) { return handleKey(e); } };
        }
        @Override public boolean onKeyDown(int k, KeyEvent e) { return handleKey(e) || super.onKeyDown(k, e); }
        @Override public boolean onKeyUp(int k, KeyEvent e) { return handleKey(e) || super.onKeyUp(k, e); }
    }

    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); nativeStop(); }
}
