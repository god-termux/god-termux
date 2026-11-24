package wlcomp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.PointerIcon;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
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
import java.util.Map;

/** Window of the god-termux Wayland compositor: GTK/Qt programs (or a whole desktop, see xfce-wl) are shown here, composed on the GPU through EGL. */
public class WlScreen implements ModScreen, WlSettingsUi.Host {
    static { System.loadLibrary("wlcomp"); }
    static native void nativeSurface(Surface s, String runtimeDir, int w, int h, int scale);
    static native void nativeResize(int w, int h);
    static native void nativeRefresh(int millihertz);
    static native void nativeScale(int scale);
    static native void nativeStop();
    static native boolean nativeRunning();
    static native int nativeWindows();
    static native void nativeMotion(float x, float y);
    static native void nativeButton(int button, int pressed);
    static native void nativeAxis(float dy);
    static native void nativeKey(int evdev, int pressed);
    static native void nativeChar(int c);
    static native void nativeCycle();

    private Activity act; private Ui ui; private WlPrefs p; private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root, stage, box; private WlView view; private CursorView cursor; private TextView hint, menuBtn; private LinearLayout keybar, padButtons, menuCard;
    private View scrim, settingsView;
    private boolean quit, ctrl, alt, warnedNonAscii; private TextView ctrlBtn, altBtn, modeItem, cosmicItem; private volatile Process cosmicProc;
    private int surfW, surfH, curScale = 2, lastSentScale = -1; private int lastBoxW = -1, lastBoxH = -1, lastSurfW = -1, lastSurfH = -1;

    // ------------------------------------------------------------------------------------------------ setup
    @Override public void onCreate(Activity a, Bundle b) {
        act = a; ui = new Ui(a); p = new WlPrefs(a);
        root = new FrameLayout(a); root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(a); root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        box = new FrameLayout(a); stage.addView(box, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        view = new WlView(a); box.addView(view, new FrameLayout.LayoutParams(-1, -1));
        cursor = new CursorView(a); cursor.setVisibility(View.GONE); box.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
        stage.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int or, int ob) {
            if (r - l != or - ol || bt - t != ob - ot) layoutStage(); } });

        hint = ui.label("Wayland-композитор готов\n\nЗапустите программу в Termux:\n  wlrun xfce4-terminal\nили весь рабочий стол:\n  xfce-wl", 15, 0xFFB0B6C0, false);
        hint.setGravity(Gravity.CENTER); hint.setPadding(ui.dp(24), 0, ui.dp(24), 0); hint.setClickable(false);
        root.addView(hint, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        padButtons = new LinearLayout(a); padButtons.setOrientation(LinearLayout.HORIZONTAL); padButtons.setVisibility(View.GONE);
        padButtons.addView(holdBtn("ЛКМ", 0)); padButtons.addView(holdBtn("ПКМ", 1));
        FrameLayout.LayoutParams pb = new FrameLayout.LayoutParams(-1, ui.dp(56), Gravity.BOTTOM); pb.bottomMargin = ui.dp(12); pb.leftMargin = ui.dp(12); pb.rightMargin = ui.dp(12);
        root.addView(padButtons, pb);

        keybar = new LinearLayout(a); keybar.setOrientation(LinearLayout.HORIZONTAL); keybar.setBackgroundColor(0xE61A1C21); keybar.setVisibility(View.GONE);
        keybar.addView(key("Esc", 1)); keybar.addView(key("Tab", 15)); ctrlBtn = stickyKey("Ctrl", true); altBtn = stickyKey("Alt", false);
        keybar.addView(ctrlBtn); keybar.addView(altBtn); keybar.addView(key("←", 105)); keybar.addView(key("↑", 103)); keybar.addView(key("↓", 108)); keybar.addView(key("→", 106));
        root.addView(keybar, new FrameLayout.LayoutParams(-1, ui.dp(44), Gravity.BOTTOM));

        buildMenu();
        settingsView = new WlSettingsUi(a, ui, p, this).build(); settingsView.setVisibility(View.GONE);
        root.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));
        a.setContentView(root);
        applyDisplay(); applyPointer(); applyChrome();
        handler.post(poll);
        if (a.getIntent().getBooleanExtra("settings", false)) root.post(new Runnable() { public void run() { openSettings(); } });
        if (a.getIntent().getBooleanExtra("cosmic", false)) root.post(new Runnable() { public void run() { startCosmic(); } });
    }

    private final Runnable poll = new Runnable() { @Override public void run() {
        hint.setVisibility(nativeRunning() && nativeWindows() > 0 ? View.GONE : View.VISIBLE); handler.postDelayed(this, 500); } };

    // ------------------------------------------------------------------------------------------------ display
    /** Orientation, system bars, keep-screen-on, and the picture size (native / scaled / exact resolution). */
    @Override public void applyDisplay() {
        act.setRequestedOrientation(p.orient == 1 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : p.orient == 2 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        WindowManager.LayoutParams lp = act.getWindow().getAttributes();
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = p.cutout ? (Build.VERSION.SDK_INT >= 30 ? 3 /* ALWAYS: also the long edges in landscape */ : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        lp.preferredDisplayModeId = 0; lp.preferredRefreshRate = 0;
        if (p.fps > 0) try {
            android.view.Display d = act.getWindowManager().getDefaultDisplay(); android.view.Display.Mode cur = d.getMode();
            for (android.view.Display.Mode m : d.getSupportedModes())
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight() && Math.round(m.getRefreshRate()) == p.fps) { lp.preferredDisplayModeId = m.getModeId(); break; }
            lp.preferredRefreshRate = p.fps;
        } catch (Throwable ignored) {}
        act.getWindow().setAttributes(lp);
        try { nativeRefresh(p.fps > 0 ? p.fps * 1000 : Math.round(act.getWindowManager().getDefaultDisplay().getRefreshRate() * 1000f)); } catch (Throwable ignored) {}
        if (p.keepOn) act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applySystemBars();
        if (p.kbMode == 2 && kbShown) toggleKeyboard();   // hardware-only: hide the on-screen keyboard
        lastBoxW = lastBoxH = lastSurfW = lastSurfH = -1; layoutStage();
    }

    private void applySystemBars() {
        View d = act.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            act.getWindow().setDecorFitsSystemWindows(!p.fullscreen);
            android.view.WindowInsetsController c = act.getWindow().getInsetsController();
            if (c != null) { if (p.fullscreen) { c.hide(android.view.WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); } else c.show(android.view.WindowInsets.Type.systemBars()); }
        }
        if (p.fullscreen) d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        else d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void layoutStage() {
        int aw = stage.getWidth(), ah = stage.getHeight(); if (aw <= 0 || ah <= 0) return;
        int sw, sh, bw = aw, bh = ah;
        if (p.resMode == 1) { sw = Math.max(64, (aw * p.resPct / 100) & ~1); sh = Math.max(64, (ah * p.resPct / 100) & ~1); }
        else if (p.resMode == 2) {
            sw = Math.max(64, p.resW & ~1); sh = Math.max(64, p.resH & ~1);
            if (p.fit == 0) { float k = Math.min(aw / (float) sw, ah / (float) sh); bw = Math.round(sw * k); bh = Math.round(sh * k); }
        } else { sw = aw; sh = ah; }
        surfW = sw; surfH = sh;
        if (bw != lastBoxW || bh != lastBoxH) { lastBoxW = bw; lastBoxH = bh; FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams(); lp.width = bw; lp.height = bh; lp.gravity = Gravity.CENTER; box.setLayoutParams(lp); }
        if (sw != lastSurfW || sh != lastSurfH) {
            lastSurfW = sw; lastSurfH = sh;
            if (p.resMode == 0) view.getHolder().setSizeFromLayout(); else view.getHolder().setFixedSize(sw, sh);
        }
        // interface scale: fixed, or from the effective density of the picture (a smaller picture stretched over the screen is bigger per pixel)
        int sc = p.uiScale > 0 ? p.uiScale : Math.max(1, Math.min(3, Math.round(act.getResources().getDisplayMetrics().densityDpi * (sw / (float) Math.max(1, bw)) / 240f)));
        curScale = sc;
        if (sc != lastSentScale) { lastSentScale = sc; if (nativeRunning()) nativeScale(sc); }
    }

    /** physical pixels of the picture for a point in the view's coordinates */
    private float kx() { return view.getWidth() > 0 ? surfW / (float) view.getWidth() : 1f; }
    private float ky() { return view.getHeight() > 0 ? surfH / (float) view.getHeight() : 1f; }
    private void motion(float vx, float vy) { nativeMotion(vx * kx(), vy * ky()); }

    @Override public void applyPointer() {
        boolean pad = p.touchMode == 1;
        cursor.setVisibility(pad ? View.VISIBLE : View.GONE); cursor.invalidate();
        padButtons.setVisibility(pad && p.padButtons ? View.VISIBLE : View.GONE);
        if (pad && cursor.x == 0 && cursor.y == 0) { cursor.x = Math.max(1, view.getWidth()) / 2f; cursor.y = Math.max(1, view.getHeight()) / 2f; motion(cursor.x, cursor.y); }
        if (modeItem != null) modeItem.setText(modeLabel());
    }

    @Override public void applyChrome() {
        menuBtn.setVisibility(p.menuBtn && settingsView.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE); placeMenuBtn();
        boolean kb = keyboardShown(); keybar.setVisibility(kb && p.keybar ? View.VISIBLE : View.GONE);
    }

    @Override public void close() { settingsView.setVisibility(View.GONE); applyChrome(); view.requestFocus(); }
    private void openSettings() { closeMenu(); settingsView.setVisibility(View.VISIBLE); menuBtn.setVisibility(View.GONE); }

    @Override public boolean onBack() { if (settingsView.getVisibility() == View.VISIBLE) { close(); return true; } if (menuCard.getVisibility() == View.VISIBLE) { closeMenu(); return true; } return false; }
    @Override public void onFocus(boolean has) { if (has) applySystemBars(); }
    @Override public void onNewIntent(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra("settings", false)) openSettings();
        if (i.getBooleanExtra("cosmic", false)) startCosmic();
    }

    // ------------------------------------------------------------------------------------------------ COSMIC (пакет god-cosmic из репозитория god-termux/packages)
    private boolean cosmicUp() { Process c = cosmicProc; if (c == null) return false; try { c.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; } }
    private String cosmicLabel() { return cosmicUp() ? "🌌  Остановить COSMIC" : "🌌  Запустить COSMIC"; }
    private void toggleCosmic() { if (cosmicUp()) stopCosmic(); else startCosmic(); }

    private ProcessBuilder cosmicCmd(String verb) {
        File files = act.getFilesDir(), prefix = new File(files, "usr"), home = new File(files, "home");
        ProcessBuilder pb = new ProcessBuilder(new File(prefix, "bin/god-cosmic").getAbsolutePath(), verb).redirectErrorStream(true);
        Map<String, String> e = pb.environment(); e.put("HOME", home.getAbsolutePath()); e.put("PREFIX", prefix.getAbsolutePath());
        e.put("TMPDIR", new File(prefix, "tmp").getAbsolutePath()); e.put("PATH", prefix + "/bin:/system/bin"); e.put("LANG", "C.UTF-8");
        File bs = new File(prefix, "glibc/shim/libfhs-bionic.so"); if (bs.exists() && !new File(home, ".termux/fhs.off").exists()) e.put("LD_PRELOAD", bs.getAbsolutePath());
        return pb;
    }

    private void startCosmic() {
        if (cosmicUp()) return;
        if (!new File(act.getFilesDir(), "usr/bin/god-cosmic").exists()) {
            Toast.makeText(act, "COSMIC не установлен. В Termux: dapt install god-cosmic (см. github.com/god-termux/packages)", Toast.LENGTH_LONG).show(); return; }
        try {
            final Process proc = cosmicCmd("start").start(); cosmicProc = proc; refreshCosmicItem();
            new Thread(new Runnable() { public void run() {
                try (java.io.BufferedReader rd = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                    String l; while ((l = rd.readLine()) != null) android.util.Log.i("god-cosmic", l); } catch (Throwable ignored) {}
                try { proc.waitFor(); } catch (InterruptedException ignored) {}
                handler.post(new Runnable() { public void run() { refreshCosmicItem(); } });
            } }).start();
        } catch (Throwable t) { Toast.makeText(act, "COSMIC не запустился: " + t, Toast.LENGTH_LONG).show(); }
    }

    private void stopCosmic() {
        final Process c = cosmicProc;
        new Thread(new Runnable() { public void run() {
            try { cosmicCmd("stop").start().waitFor(); } catch (Throwable ignored) {}
            if (c != null) c.destroy();
            handler.post(new Runnable() { public void run() { refreshCosmicItem(); } });
        } }).start();
    }

    private void refreshCosmicItem() { if (cosmicItem != null) cosmicItem.setText(cosmicLabel()); }

    // ------------------------------------------------------------------------------------------------ floating menu
    private String modeLabel() { return "🖱  Режим: " + (p.touchMode == 1 ? "тачпад" : "прямое касание"); }

    private void buildMenu() {
        menuBtn = ui.label("☰", 22, 0xFFFFFFFF, true); menuBtn.setGravity(Gravity.CENTER); menuBtn.setBackground(ui.rect(0x99101318, 24, 0x55FFFFFF)); menuBtn.setAlpha(0.7f);
        root.addView(menuBtn, new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        final float[] d = new float[4]; final boolean[] drag = new boolean[1]; final int slop = ViewConfiguration.get(act).getScaledTouchSlop();
        menuBtn.setOnTouchListener(new View.OnTouchListener() { public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: d[0] = e.getRawX(); d[1] = e.getRawY(); d[2] = v.getX(); d[3] = v.getY(); drag[0] = false; return true;
                case MotionEvent.ACTION_MOVE: { float dx = e.getRawX() - d[0], dy = e.getRawY() - d[1];
                    if (!drag[0] && Math.hypot(dx, dy) > slop) drag[0] = true;
                    if (drag[0]) { v.setX(Math.max(0, Math.min(root.getWidth() - v.getWidth(), d[2] + dx))); v.setY(Math.max(0, Math.min(root.getHeight() - v.getHeight(), d[3] + dy))); } return true; }
                case MotionEvent.ACTION_UP:
                    if (drag[0]) { p.menuX = v.getX() / Math.max(1, root.getWidth()); p.menuY = v.getY() / Math.max(1, root.getHeight()); p.save(); } else toggleMenu(); return true;
                default: return true; } } });

        scrim = new View(act); scrim.setVisibility(View.GONE); scrim.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeMenu(); } });
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        menuCard = new LinearLayout(act); menuCard.setOrientation(LinearLayout.VERTICAL); menuCard.setBackground(ui.rect(0xF2171A20, 16, 0x44FFFFFF)); menuCard.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); menuCard.setVisibility(View.GONE);
        menuCard.addView(menuItem("⚙  Настройки", new Runnable() { public void run() { openSettings(); } }));
        menuCard.addView(menuItem("⌨  Клавиатура", new Runnable() { public void run() { closeMenu(); toggleKeyboard(); } }));
        modeItem = menuItem(modeLabel(), new Runnable() { public void run() { p.touchMode = 1 - p.touchMode; p.save(); applyPointer(); closeMenu(); } });
        menuCard.addView(modeItem);
        cosmicItem = menuItem(cosmicLabel(), new Runnable() { public void run() { closeMenu(); toggleCosmic(); } });
        menuCard.addView(cosmicItem);
        menuCard.addView(menuItem("▤  Следующее окно", new Runnable() { public void run() { closeMenu(); nativeCycle(); } }));
        menuCard.addView(menuItem("✕  Закрыть Wayland", new Runnable() { public void run() { quit = true; act.finish(); } }));
        root.addView(menuCard, new FrameLayout.LayoutParams(ui.dp(250), -2, Gravity.CENTER));
    }

    private TextView menuItem(String s, final Runnable r) {
        TextView t = ui.label(s, 16, 0xFFE9ECF1, false); t.setPadding(ui.dp(16), ui.dp(13), ui.dp(16), ui.dp(13));
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { r.run(); } }); return t;
    }

    private void placeMenuBtn() {
        if (root.getWidth() == 0) { root.post(new Runnable() { public void run() { placeMenuBtn(); } }); return; }
        float x = p.menuX >= 0 ? p.menuX * root.getWidth() : root.getWidth() - ui.dp(48) - ui.dp(6), y = p.menuY >= 0 ? p.menuY * root.getHeight() : root.getHeight() * 0.35f;
        menuBtn.setX(Math.max(0, Math.min(root.getWidth() - ui.dp(48), x))); menuBtn.setY(Math.max(0, Math.min(root.getHeight() - ui.dp(48), y)));
    }
    private void toggleMenu() { if (menuCard.getVisibility() == View.VISIBLE) closeMenu(); else { modeItem.setText(modeLabel()); scrim.setVisibility(View.VISIBLE); menuCard.setVisibility(View.VISIBLE); } }
    private void closeMenu() { scrim.setVisibility(View.GONE); menuCard.setVisibility(View.GONE); }

    // ------------------------------------------------------------------------------------------------ keyboard / key bar
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

    private TextView holdBtn(String s, final int btnIdx) {
        TextView t = ui.button(s, false, new Runnable() { public void run() {} }); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.setMargins(ui.dp(4), 0, ui.dp(4), 0); t.setLayoutParams(lp);
        t.setBackground(ui.rect(0x99000000, 14, 0x55FFFFFF));
        t.setOnTouchListener(new View.OnTouchListener() { @Override public boolean onTouch(View v, MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { motion(cursor.x, cursor.y); nativeButton(btnIdx, 1); v.setPressed(true); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { nativeButton(btnIdx, 0); v.setPressed(false); }
            return true; } });
        return t;
    }

    private void releaseSticky() { ctrl = alt = false; if (ctrlBtn != null) ctrlBtn.setBackground(ui.rect(0, 12, ui.accent)); if (altBtn != null) altBtn.setBackground(ui.rect(0, 12, ui.accent)); }
    private void tap(int evdev) { mods(true); nativeKey(evdev, 1); nativeKey(evdev, 0); mods(false); releaseSticky(); }
    private void mods(boolean down) { if (ctrl) nativeKey(29, down ? 1 : 0); if (alt) nativeKey(56, down ? 1 : 0); }

    private boolean kbShown;
    private boolean keyboardShown() { return kbShown; }
    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (p.kbMode == 2 && !kbShown) { Toast.makeText(act, "Режим ввода: только физическая клавиатура (Настройки → Клавиатура)", Toast.LENGTH_SHORT).show(); return; }
        kbShown = !kbShown; view.requestFocus(); applyChrome();
        if (kbShown) imm.showSoftInput(view, InputMethodManager.SHOW_FORCED); else imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    // A hardware key can reach us twice: as a key event and as text committed by the IME. The same character from the other path within 80 ms is dropped.
    private int lastC = -1; private long lastT; private boolean lastPhys;
    private long lastEvT = -1; private int lastEvK = -1, lastEvA = -1;
    private void typeChar(int c, boolean phys) {
        long now = android.os.SystemClock.uptimeMillis();
        if (c == lastC && phys != lastPhys && now - lastT < 80) { lastC = -1; return; }
        lastC = c; lastT = now; lastPhys = phys;
        typeChar(c);
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

    /** Input mode (settings): 0 both, 1 on-screen keyboard only, 2 hardware keyboard only. The on-screen keyboard reports the virtual device id (-1). */
    private boolean keyAllowed(KeyEvent e) {
        boolean phys = e.getDeviceId() != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD;
        return p.kbMode == 0 || (p.kbMode == 2 ? phys : !phys);
    }

    private boolean handleKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN; if (e.getAction() == KeyEvent.ACTION_MULTIPLE) return false;
        if (e.getEventTime() == lastEvT && e.getKeyCode() == lastEvK && e.getAction() == lastEvA) return true;   // the very same event delivered twice (view + IME)
        lastEvT = e.getEventTime(); lastEvK = e.getKeyCode(); lastEvA = e.getAction();
        int code = evdev(e.getKeyCode());
        if ((code != 0 || e.getUnicodeChar(e.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK)) > 0) && !keyAllowed(e)) return true;   // input mode: hardware only / on-screen only
        if (code != 0) { if (down || e.getAction() == KeyEvent.ACTION_UP) nativeKey(code, down ? 1 : 0); return true; }
        int u = e.getUnicodeChar(e.getMetaState() & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK));
        if (u > 0 && down) {
            if (e.isCtrlPressed()) { nativeKey(29, 1); nativeChar(Character.toLowerCase(u)); nativeKey(29, 0); } else typeChar(u, e.getDeviceId() != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD);
            return true;
        }
        return u > 0;
    }

    // ------------------------------------------------------------------------------------------------ pointer cursor (touchpad mode)
    private final class CursorView extends View {
        float x, y; private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path arrow = new Path();
        CursorView(Context c) { super(c); fill.setColor(Color.WHITE); line.setColor(Color.BLACK); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(3f); setClickable(false); }
        @Override protected void onDraw(Canvas c) {
            float u = ui.dp(1) * p.curSize; arrow.reset(); arrow.moveTo(x, y); arrow.lineTo(x, y + 20 * u); arrow.lineTo(x + 5 * u, y + 15.5f * u); arrow.lineTo(x + 9 * u, y + 24 * u);
            arrow.lineTo(x + 12 * u, y + 22.5f * u); arrow.lineTo(x + 8 * u, y + 14.5f * u); arrow.lineTo(x + 14 * u, y + 14.5f * u); arrow.close();
            c.drawPath(arrow, fill); c.drawPath(arrow, line);
        }
    }

    // ------------------------------------------------------------------------------------------------ the picture
    private final class WlView extends SurfaceView implements SurfaceHolder.Callback {
        private boolean leftDown, twoFinger, moved; private float downX, downY, lastY, accY; private final int slop;
        private final Runnable longPress = new Runnable() { @Override public void run() {
            if (!moved && leftDown) { nativeButton(0, 0); nativeButton(1, 1); nativeButton(1, 0); leftDown = false; } } };

        WlView(Context c) { super(c); getHolder().addCallback(this); setFocusable(true); setFocusableInTouchMode(true); slop = ViewConfiguration.get(c).getScaledTouchSlop();
            if (Build.VERSION.SDK_INT >= 24) setPointerIcon(PointerIcon.getSystemIcon(c, PointerIcon.TYPE_NULL)); }   // hide Android's own mouse cursor over the picture: the compositor draws its own

        @Override public void surfaceCreated(SurfaceHolder h) {}
        @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
            nativeSurface(h.getSurface(), new File(act.getFilesDir(), "usr/tmp").getAbsolutePath(), w, hh, curScale); lastSentScale = curScale;
            try { nativeRefresh(p.fps > 0 ? p.fps * 1000 : Math.round(act.getWindowManager().getDefaultDisplay().getRefreshRate() * 1000f)); } catch (Throwable ignored) {}
        }
        @Override public void surfaceDestroyed(SurfaceHolder h) { nativeSurface(null, "", 0, 0, 0); }

        // ---- touchpad mode
        private long padT, lastTapT; private float padLX, padLY, padDX, padDY, padScroll; private int padMax; private boolean padMoved, padDrag, padIgnore, padTwoMoved, padLong;
        private final Runnable padLongRun = new Runnable() { @Override public void run() {
            if (padMax != 1 || padMoved || padDrag || p.longMode == 2) return;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); padLong = true; motion(cursor.x, cursor.y);
            if (p.longMode == 0) { padDrag = true; nativeButton(0, 1); } else { nativeButton(1, 1); nativeButton(1, 0); } } };

        private boolean padTouch(MotionEvent e) {
            float x = e.getX(), y = e.getY(); long now = e.getEventTime(); float w = getWidth(), h = getHeight();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    padT = now; padLX = padDX = x; padLY = padDY = y; padMax = 1; padMoved = padTwoMoved = padIgnore = padLong = false; padDrag = false; handler.removeCallbacks(padLongRun); handler.postDelayed(padLongRun, 450);
                    if (p.tapDrag && now - lastTapT < 280) { padDrag = true; motion(cursor.x, cursor.y); nativeButton(0, 1); }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    handler.removeCallbacks(padLongRun); if (padDrag) { nativeButton(0, 0); padDrag = false; }
                    padMax = 2; padLY = (e.getY(0) + e.getY(1)) / 2; padScroll = 0; padTwoMoved = false; return true;
                case MotionEvent.ACTION_POINTER_UP: padIgnore = true; return true;
                case MotionEvent.ACTION_MOVE:
                    if (padMax >= 2) {
                        if (e.getPointerCount() >= 2) { float my = (e.getY(0) + e.getY(1)) / 2; padScroll += padLY - my; if (Math.abs(padLY - my) > 1) padTwoMoved = true; padLY = my;
                            if (Math.abs(padScroll) >= 6) { nativeAxis(padScroll / 5f * p.scrollSpeed * (p.invScroll ? -1 : 1)); padScroll = 0; } }
                    } else if (!padIgnore) {
                        float dx = x - padLX, dy = y - padLY; padLX = x; padLY = y;
                        if (!padMoved && Math.hypot(x - padDX, y - padDY) > slop) { padMoved = true; handler.removeCallbacks(padLongRun); }
                        float sp = (float) Math.hypot(dx, dy); float gain = p.sens * (p.accel ? (sp > 25 ? 2.4f : sp > 8 ? 1.8f : 1.2f) : 1.5f);
                        cursor.x = Math.max(0, Math.min(w - 1, cursor.x + dx * gain)); cursor.y = Math.max(0, Math.min(h - 1, cursor.y + dy * gain));
                        motion(cursor.x, cursor.y); cursor.invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(padLongRun);
                    if (padDrag) { nativeButton(0, 0); padDrag = false; lastTapT = 0; }
                    else if (padLong) { }
                    else if (p.tapClick && padMax == 1 && !padMoved && now - padT < 250) { motion(cursor.x, cursor.y); nativeButton(0, 1); nativeButton(0, 0); lastTapT = now; }
                    else if (p.tapClick && padMax == 2 && !padTwoMoved && now - padT < 350) { motion(cursor.x, cursor.y); nativeButton(1, 1); nativeButton(1, 0); }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(padLongRun); if (padDrag) { nativeButton(0, 0); padDrag = false; } return true;
                default: return true;
            }
        }

        // ---- physical mouse (tool type MOUSE): absolute position, buttons, wheel; drawn with the compositor's own cursor
        private int mouseBtns;
        private boolean isMouse(MotionEvent e) { return e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE || (e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE; }
        private void mouseCursor(float x, float y) { cursor.x = x; cursor.y = y; if (cursor.getVisibility() != View.VISIBLE) cursor.setVisibility(View.VISIBLE); cursor.invalidate(); }
        private boolean mouseTouch(MotionEvent e) {
            float x = e.getX(), y = e.getY(); mouseCursor(x, y); motion(x, y);
            int a = e.getActionMasked(), bs = (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) ? 0 : e.getButtonState(), ch = bs ^ mouseBtns;
            if ((ch & MotionEvent.BUTTON_PRIMARY) != 0) nativeButton(0, (bs & MotionEvent.BUTTON_PRIMARY) != 0 ? 1 : 0);
            if ((ch & MotionEvent.BUTTON_SECONDARY) != 0) nativeButton(1, (bs & MotionEvent.BUTTON_SECONDARY) != 0 ? 1 : 0);
            if ((ch & MotionEvent.BUTTON_TERTIARY) != 0) nativeButton(2, (bs & MotionEvent.BUTTON_TERTIARY) != 0 ? 1 : 0);
            mouseBtns = bs; return true;
        }
        @Override public boolean onGenericMotionEvent(MotionEvent e) {
            if (isMouse(e)) {
                if (p.mouseMode == 1) return true;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_HOVER_MOVE: case MotionEvent.ACTION_HOVER_ENTER: mouseCursor(e.getX(), e.getY()); motion(e.getX(), e.getY()); return true;
                    case MotionEvent.ACTION_SCROLL: mouseCursor(e.getX(), e.getY()); motion(e.getX(), e.getY());
                        nativeAxis(-e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 15f * p.scrollSpeed * (p.invScroll ? -1 : 1)); return true;
                    default: return true;
                }
            }
            return super.onGenericMotionEvent(e);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (isMouse(e)) { if (p.mouseMode == 1) return true; return mouseTouch(e); }
            if (p.mouseMode == 2) return true;   // mouse-only mode: touches on the picture are ignored
            if (p.touchMode == 0 && cursor.getVisibility() == View.VISIBLE) cursor.setVisibility(View.GONE);
            if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() >= 3) {   // three fingers: menu
                handler.removeCallbacks(longPress); if (leftDown) { nativeButton(0, 0); leftDown = false; } toggleMenu(); return true; }
            if (p.touchMode == 1) return padTouch(e);
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    moved = false; twoFinger = false; downX = x; downY = y; motion(x, y); nativeButton(0, 1); leftDown = true;
                    handler.postDelayed(longPress, 550); return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    handler.removeCallbacks(longPress); if (leftDown) { nativeButton(0, 0); leftDown = false; }
                    twoFinger = true; lastY = (e.getY(0) + e.getY(1)) / 2; accY = 0; return true;
                case MotionEvent.ACTION_MOVE:
                    if (twoFinger && e.getPointerCount() >= 2) {
                        float my = (e.getY(0) + e.getY(1)) / 2; accY += (lastY - my); lastY = my;
                        if (Math.abs(accY) >= 6) { nativeAxis(accY / 5f * p.scrollSpeed * (p.invScroll ? -1 : 1)); accY = 0; }
                    } else if (!twoFinger) {
                        if (!moved && Math.hypot(x - downX, y - downY) > slop) { moved = true; handler.removeCallbacks(longPress); }
                        motion(x, y);
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
                @Override public boolean commitText(CharSequence t, int pos) { if (WlScreen.this.p.kbMode == 2) return true; for (int i = 0; i < t.length(); i++) typeChar(t.charAt(i), false); return true; }
                @Override public boolean setComposingText(CharSequence t, int p) { return true; }
                @Override public boolean finishComposingText() { return true; }
                @Override public boolean deleteSurroundingText(int before, int after) { if (WlScreen.this.p.kbMode == 2) return true; for (int i = 0; i < before; i++) { nativeKey(14, 1); nativeKey(14, 0); } return true; }
                @Override public boolean sendKeyEvent(KeyEvent e) { return handleKey(e); } };
        }
        @Override public boolean onKeyDown(int k, KeyEvent e) { return handleKey(e) || super.onKeyDown(k, e); }
        @Override public boolean onKeyUp(int k, KeyEvent e) { return handleKey(e) || super.onKeyUp(k, e); }
    }

    // the compositor (and the programs connected to it) outlive the window; only the close button stops it
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); if (quit) nativeStop(); }
}
