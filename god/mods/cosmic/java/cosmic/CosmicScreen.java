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
    static native int nativeStart(Surface s, String runtimeDir, String home, String libDir, int refreshMhz, float scale);
    static native String nativeError();
    static native boolean nativeRunning();
    static native void nativeSetWindow(Surface s);
    static native void nativeWindowLost();
    static native void nativeSetScale(float scale);
    static native void nativeStop();
    static native void nativeMotion(float x, float y);
    static native void nativeButton(int button, int pressed);
    static native void nativeScroll(float dy);
    static native void nativeTouch(int phase, int id, float x, float y);
    static native void nativeKey(int evdev, int pressed);

    private Activity act;
    private TextView status;

    private static boolean libLoaded;

    @Override public void onCreate(Activity host, Bundle state) {
        act = host;
        // автоповорот: следовать датчику ориентации (ModActivity сам обрабатывает configChanges, экран не пересоздаётся)
        host.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        // на весь экран: без системной панели навигации и без чёрных полос у выреза камеры
        try {
            android.view.Window win = host.getWindow();
            win.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams lp = win.getAttributes();
                lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                win.setAttributes(lp);
            }
        } catch (Throwable ignored) {}
        CosmicLog.init(host.getFilesDir());
        CosmicLog.w("экран: onCreate, loader=" + getClass().getClassLoader());
        try {
            if (!libLoaded) { System.loadLibrary("cosmic"); libLoaded = true; CosmicLog.w("libcosmic.so загружена"); }
            build(host);
            CosmicLog.w("экран: создан");
        } catch (Throwable t) {
            CosmicLog.e("экран: ошибка создания", t);
            showError(host, "COSMIC: ошибка запуска экрана\n\n" + CosmicLog.trace(t));
        }
    }

    private static void showError(Activity host, String text) {
        android.widget.TextView tv = new android.widget.TextView(host);
        tv.setText(text + "\n\n(журнал: usr/tmp/cosmic-mod.log)");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11);
        tv.setPadding(32, 32, 32, 32);
        android.widget.ScrollView sv = new android.widget.ScrollView(host);
        sv.setBackgroundColor(Color.BLACK);
        sv.addView(tv);
        host.setContentView(sv);
    }

    private void build(Activity host) {
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
        // безопасная зона: отступы по радиусу скругления дисплея и вырезу камеры, иначе кнопки в углах (питание, Workspaces,
        // закрытие окна) попадают под закруглённые края и не нажимаются. Переопределение: ~/.termux/cosmic-margin (пиксели)
        final SurfaceView svf = sv;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                try { applyMargins(svf, in); } catch (Throwable t) { CosmicLog.e("applyMargins", t); }
                return in;
            }
        });
        root.requestApplyInsets();
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {}
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
                CosmicLog.w("surfaceChanged " + w + "x" + hh + " format=" + format);
                try { onSurface(h.getSurface(), w); } catch (Throwable t) { fail("onSurface", t); }
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


    private static float lastScale;

    /**
     * Масштаб интерфейса. Приоритет: файл ~/.termux/cosmic-scale (число). Иначе 60% плотности экрана (Android 2.6 даёт
     * слишком крупный COSMIC: он рассчитан на ~96 dpi, а не на 420), но не больше, чем нужно, чтобы логическая ширина
     * экрана была не меньше ~1300 пикселей: экранной клавиатуре COSMIC нужно ~1200, иначе она обрезается (в портретной
     * ориентации масштаб поэтому ниже, чем в альбомной).
     */
    private float desiredScale(int widthPx) {
        try {
            File sf = new File(act.getFilesDir(), "home/.termux/cosmic-scale");
            if (sf.exists()) {
                float v = Float.parseFloat(new String(java.nio.file.Files.readAllBytes(sf.toPath())).trim());
                if (v >= 0.5f && v <= 4.0f) return v;
            }
        } catch (Throwable ignored) {}
        float d = act.getResources().getDisplayMetrics().density * 0.6f;
        if (widthPx > 0) d = Math.min(d, widthPx / 1300f);
        return Math.max(0.7f, Math.min(2.0f, d));
    }


    private int lastMarginSig;

    /** Радиус скругления дисплея (px) через рефлексию: API 31+, в сборочном android.jar его нет. */
    private static int roundedRadius(android.view.WindowInsets in) {
        int r = 0;
        try {
            java.lang.reflect.Method get = in.getClass().getMethod("getRoundedCorner", int.class);
            for (int pos = 0; pos < 4; pos++) {
                Object rc = get.invoke(in, pos);
                if (rc != null) r = Math.max(r, (Integer) rc.getClass().getMethod("getRadius").invoke(rc));
            }
        } catch (Throwable ignored) {}
        return r;
    }

    private void applyMargins(SurfaceView sv, android.view.WindowInsets in) {
        int m;
        File mf = new File(act.getFilesDir(), "home/.termux/cosmic-margin");
        int fileMargin = -1;
        try { if (mf.exists()) fileMargin = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(mf.toPath())).trim()); } catch (Throwable ignored) {}
        int radius = roundedRadius(in);
        if (fileMargin >= 0) m = fileMargin;
        else if (radius > 0) m = Math.round(radius * 0.55f);   // угол прямоугольника должен лежать внутри дуги скругления + запас под палец
        else m = Math.round(12 * act.getResources().getDisplayMetrics().density);
        int l = m, t = m, r = m, b = m;
        android.view.DisplayCutout dc = null;
        if (android.os.Build.VERSION.SDK_INT >= 28) dc = in.getDisplayCutout();
        if (dc != null) {
            l = Math.max(l, dc.getSafeInsetLeft()); t = Math.max(t, dc.getSafeInsetTop());
            r = Math.max(r, dc.getSafeInsetRight()); b = Math.max(b, dc.getSafeInsetBottom());
        }
        int sig = (l * 31 + t) * 31 + (r * 31 + b);
        if (sig == lastMarginSig) return;
        lastMarginSig = sig;
        CosmicLog.w("отступы l=" + l + " t=" + t + " r=" + r + " b=" + b + " (радиус скругления " + radius + ")");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.setMargins(l, t, r, b);
        sv.setLayoutParams(lp);
    }

    private int lastWidth;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable scalePoll = new Runnable() {
        @Override public void run() {
            try { if (nativeRunning()) applyScale(lastWidth); } catch (Throwable ignored) {}
            handler.postDelayed(this, 2000);   // файл ~/.termux/cosmic-scale можно править без перезапуска
        }
    };

    /** Применить масштаб на лету, если он изменился. */
    private void applyScale(int widthPx) {
        if (widthPx > 0) lastWidth = widthPx;
        float sc = desiredScale(lastWidth);
        if (Math.abs(sc - lastScale) > 0.01f) {
            lastScale = sc;
            CosmicLog.w("масштаб " + sc + " (ширина " + lastWidth + ")");
            nativeSetScale(sc);
        }
    }

    /** Запуск композитора неудачен: повторить можно только после перезапуска приложения (глобальный логгер) */
    private static boolean failed;

    /** Исключение в обратном вызове: в журнал и на экран, приложение не роняем. */
    private void fail(String where, Throwable t) {
        CosmicLog.e(where, t);
        act.runOnUiThread(new Runnable() {
            @Override public void run() { status.setVisibility(View.VISIBLE); status.setText("COSMIC: ошибка\n" + CosmicLog.trace(t)); }
        });
    }

    private void onSurface(Surface s, int width) {
        CosmicLog.w("onSurface: running=" + nativeRunning() + " failed=" + failed);
        if (nativeRunning()) {   // композитор уже работает (экран открыт повторно или повёрнут): отдаём ему окно
            nativeSetWindow(s);
            applyScale(width);
            status.setVisibility(View.GONE);
            return;
        }
        if (failed) return;
        failed = true;   // снимается после успешного старта ниже
        File dir = ModLoader.dir("cosmic");
        File files = act.getFilesDir();
        int mhz = Math.round(act.getWindowManager().getDefaultDisplay().getRefreshRate() * 1000f);
        lastWidth = width;
        float scale = desiredScale(width);
        lastScale = scale;
        int rc = nativeStart(s, new File(files, "usr/tmp").getAbsolutePath(), new File(files, "home").getAbsolutePath(),
                new File(dir, "lib").getAbsolutePath(), mhz, scale);
        CosmicLog.w("nativeStart -> " + rc + " " + nativeError() + " (scale " + scale + ", " + mhz + " мГц)");
        if (rc != 0) {
            status.setText("COSMIC не запустился: " + nativeError());
        } else {
            failed = false;
            status.setVisibility(View.GONE);
            handler.removeCallbacks(scalePoll);
            handler.postDelayed(scalePoll, 2000);
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

    // Все методы ModScreen переопределены явно: вызовы через default-методы интерфейса из загрузчика мода не используем
    @Override public void onStart() {}
    @Override public void onResume() {}
    @Override public void onPause() {}
    @Override public void onStop() {}
    @Override public void onFocus(boolean has) {}
    public void onNewIntent(android.content.Intent i) {}
    public boolean onBack() { return false; }
    @Override public void onDestroy() { /* композитор остаётся жить в процессе; окно будет передано при следующем открытии экрана */ }
}
