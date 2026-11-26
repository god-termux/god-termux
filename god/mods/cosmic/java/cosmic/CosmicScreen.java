package cosmic;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.termux.mod.ModLoader;
import com.termux.mod.ModScreen;
import java.io.File;

/** Полноэкранное окно, в которое cosmic-comp (libcosmic_comp.so) рисует через EGL. Ввод пробрасывается в композитор. */
public class CosmicScreen implements ModScreen {
    static { System.loadLibrary("cosmic"); }
    static native int nativeStart(Surface s, String runtimeDir, String home, String libDir, int refreshMhz, float scale);
    static native String nativeError();
    static native boolean nativeRunning();
    static native void nativeSetWindow(Surface s);
    static native void nativeWindowLost();
    static native void nativeStop();
    static native void nativeMotion(float x, float y);
    static native void nativeButton(int button, int pressed);
    static native void nativeScroll(float dy);
    static native void nativeTouch(int phase, int id, float x, float y);
    static native void nativeKey(int evdev, int pressed);

    private Activity act;
    private TextView status;

    @Override public void onCreate(Activity host, Bundle state) {
        act = host;
        FrameLayout root = new FrameLayout(host);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView sv = new SurfaceView(host);
        sv.setFocusable(true);
        sv.setFocusableInTouchMode(true);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(host);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(48, 48, 48, 48);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        host.setContentView(root);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {}
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
                CosmicLog.w("surfaceChanged " + w + "x" + hh + " format=" + format);
                try { onSurface(h.getSurface()); } catch (Throwable t) { fail("onSurface", t); }
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                CosmicLog.w("surfaceDestroyed");
                try { nativeWindowLost(); } catch (Throwable t) { fail("nativeWindowLost", t); }
            }
        });
        sv.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return touch(e); }
        });
        sv.setOnGenericMotionListener(new View.OnGenericMotionListener() {
            @Override public boolean onGenericMotion(View v, MotionEvent e) { return generic(e); }
        });
        sv.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) { return key(e); }
        });
        sv.requestFocus();
    }

    private boolean started;

    /** Исключение в обратном вызове: в журнал и на экран, приложение не роняем. */
    private void fail(String where, Throwable t) {
        CosmicLog.e(where, t);
        act.runOnUiThread(new Runnable() {
            @Override public void run() { status.setVisibility(View.VISIBLE); status.setText("COSMIC: ошибка\n" + CosmicLog.trace(t)); }
        });
    }

    private void onSurface(Surface s) {
        if (started && nativeRunning()) { nativeSetWindow(s); return; }
        CosmicLog.w("onSurface: started=" + started + " running=" + nativeRunning());
        if (started) return;   // композитор завершился: перезапуск возможен только после перезапуска приложения
        started = true;
        File dir = ModLoader.dir("cosmic");
        File files = act.getFilesDir();
        int mhz = Math.round(act.getWindowManager().getDefaultDisplay().getRefreshRate() * 1000f);
        float scale = act.getResources().getDisplayMetrics().density;
        try {   // ручная настройка: число в ~/.termux/cosmic-scale (например 2 или 2.5)
            File sf = new File(files, "home/.termux/cosmic-scale");
            if (sf.exists()) scale = Float.parseFloat(new String(java.nio.file.Files.readAllBytes(sf.toPath())).trim());
        } catch (Throwable ignored) {}
        int rc = nativeStart(s, new File(files, "usr/tmp").getAbsolutePath(), new File(files, "home").getAbsolutePath(),
                new File(dir, "lib").getAbsolutePath(), mhz, scale);
        CosmicLog.w("nativeStart -> " + rc + " " + nativeError() + " (scale " + scale + ", " + mhz + " мГц)");
        if (rc != 0) {
            status.setText("COSMIC не запустился: " + nativeError());
        } else {
            status.setVisibility(View.GONE);
        }
    }

    // ---- касания (мультитач) ----
    private boolean touch(MotionEvent e) {
        if (!nativeRunning()) return true;
        if ((e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE && e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return generic(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            nativeTouch(0, e.getPointerId(i), e.getX(i), e.getY(i));
        } else if (a == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) nativeTouch(1, e.getPointerId(i), e.getX(i), e.getY(i));
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) {
            int i = e.getActionIndex();
            nativeTouch(2, e.getPointerId(i), e.getX(i), e.getY(i));
        } else if (a == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < e.getPointerCount(); i++) nativeTouch(3, e.getPointerId(i), e.getX(i), e.getY(i));
        }
        return true;
    }

    // ---- мышь и колесо ----
    private int lastButtons;

    private boolean generic(MotionEvent e) {
        if (!nativeRunning()) return false;
        if ((e.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0) return false;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_SCROLL) {
            nativeMotion(e.getX(), e.getY());
            nativeScroll(-e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 15f);
            return true;
        }
        nativeMotion(e.getX(), e.getY());
        int b = e.getButtonState();
        int changed = b ^ lastButtons;
        if (changed != 0) {
            mouseBtn(changed, b, MotionEvent.BUTTON_PRIMARY, 0);
            mouseBtn(changed, b, MotionEvent.BUTTON_SECONDARY, 1);
            mouseBtn(changed, b, MotionEvent.BUTTON_TERTIARY, 2);
            lastButtons = b;
        }
        return true;
    }

    private void mouseBtn(int changed, int now, int mask, int idx) {
        if ((changed & mask) != 0) nativeButton(idx, (now & mask) != 0 ? 1 : 0);
    }

    // ---- клавиатура ----
    private boolean key(KeyEvent e) {
        if (!nativeRunning()) return false;
        int code = evdev(e.getKeyCode());
        if (code == 0) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) nativeKey(code, 1);
        else if (e.getAction() == KeyEvent.ACTION_UP) nativeKey(code, 0);
        return true;
    }

    private static final int[] LETTERS = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};

    /** Android keycode -> Linux evdev (KEY_*) для раскладки US */
    static int evdev(int k) {
        if (k >= KeyEvent.KEYCODE_A && k <= KeyEvent.KEYCODE_Z) return LETTERS[k - KeyEvent.KEYCODE_A];
        if (k >= KeyEvent.KEYCODE_1 && k <= KeyEvent.KEYCODE_9) return 2 + (k - KeyEvent.KEYCODE_1);
        if (k == KeyEvent.KEYCODE_0) return 11;
        switch (k) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return 28;
            case KeyEvent.KEYCODE_DEL: return 14;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 111;
            case KeyEvent.KEYCODE_TAB: return 15;
            case KeyEvent.KEYCODE_ESCAPE: return 1;
            case KeyEvent.KEYCODE_SPACE: return 57;
            case KeyEvent.KEYCODE_MINUS: return 12;
            case KeyEvent.KEYCODE_EQUALS: return 13;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 26;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 27;
            case KeyEvent.KEYCODE_BACKSLASH: return 43;
            case KeyEvent.KEYCODE_SEMICOLON: return 39;
            case KeyEvent.KEYCODE_APOSTROPHE: return 40;
            case KeyEvent.KEYCODE_GRAVE: return 41;
            case KeyEvent.KEYCODE_COMMA: return 51;
            case KeyEvent.KEYCODE_PERIOD: return 52;
            case KeyEvent.KEYCODE_SLASH: return 53;
            case KeyEvent.KEYCODE_DPAD_UP: return 103;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 108;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 105;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 106;
            case KeyEvent.KEYCODE_MOVE_HOME: return 102;
            case KeyEvent.KEYCODE_MOVE_END: return 107;
            case KeyEvent.KEYCODE_PAGE_UP: return 104;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 109;
            case KeyEvent.KEYCODE_INSERT: return 110;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 29;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 97;
            case KeyEvent.KEYCODE_ALT_LEFT: return 56;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 100;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 42;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 54;
            case KeyEvent.KEYCODE_META_LEFT: return 125;
            case KeyEvent.KEYCODE_META_RIGHT: return 126;
            default: return 0;
        }
    }

    @Override public void onDestroy() { /* композитор остаётся жить в процессе; окно будет передано при следующем открытии экрана */ }
}
