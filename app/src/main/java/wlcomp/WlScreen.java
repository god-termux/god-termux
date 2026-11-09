package wlcomp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.ScrollView;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
    // touchpad settings (persisted)
    private SharedPreferences prefs; private float sens = 1f, scrollSpeed = 1f, curSize = 1f; private boolean accel = true, invScroll, tapClick = true, tapDrag = true, showBtns = true; private int longMode = 0;
    private LinearLayout settingsPanel;
    private boolean padMode; private CursorView cursor; private TextView padBtn; private LinearLayout padButtons;
    private boolean ctrl, alt; private TextView ctrlBtn, altBtn; private boolean warnedNonAscii;

    @Override public void onCreate(Activity a, Bundle b) {
        act = a; ui = new Ui(a);
        FrameLayout root = new FrameLayout(a); root.setBackgroundColor(Color.BLACK);
        view = new WlView(a); root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        hint = ui.label("Wayland-композитор готов\n\nЗапустите программу в Termux:\n  wlrun xfce4-terminal\n  wlrun gtk3-widget-factory", 15, 0xFFB0B6C0, false);
        hint.setGravity(Gravity.CENTER); hint.setPadding(ui.dp(24), 0, ui.dp(24), 0);
        root.addView(hint, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL);
        prefs = a.getSharedPreferences("wlcomp", Context.MODE_PRIVATE);
        sens = prefs.getFloat("sens", 1f); scrollSpeed = prefs.getFloat("scroll", 1f); curSize = prefs.getFloat("cur", 1f); accel = prefs.getBoolean("accel", true);
        invScroll = prefs.getBoolean("inv", false); tapClick = prefs.getBoolean("tap", true); tapDrag = prefs.getBoolean("tapdrag", true); showBtns = prefs.getBoolean("btns", true); longMode = prefs.getInt("long", 0);
        padBtn = btn("🖱", new Runnable() { @Override public void run() { setPad(!padMode); } });
        top.addView(padBtn);
        top.addView(btn("⚙", new Runnable() { @Override public void run() { settingsPanel.setVisibility(settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); } }));
        top.addView(btn("▤", new Runnable() { @Override public void run() { nativeCycle(); } }));
        top.addView(btn("⌨", new Runnable() { @Override public void run() { toggleKeyboard(); } }));
        top.addView(btn("✕", new Runnable() { @Override public void run() { act.finish(); } }));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); tp.setMargins(0, ui.dp(8), ui.dp(8), 0);
        root.addView(top, tp);

        cursor = new CursorView(a); cursor.setVisibility(View.GONE); root.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
        padButtons = new LinearLayout(a); padButtons.setOrientation(LinearLayout.HORIZONTAL); padButtons.setVisibility(View.GONE);
        padButtons.addView(holdBtn("ЛКМ", 0)); padButtons.addView(holdBtn("ПКМ", 1));
        FrameLayout.LayoutParams pb = new FrameLayout.LayoutParams(-1, ui.dp(56), Gravity.BOTTOM); pb.bottomMargin = ui.dp(60); pb.leftMargin = ui.dp(12); pb.rightMargin = ui.dp(12);
        root.addView(padButtons, pb);

        settingsPanel = buildSettings(a);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); sp.topMargin = ui.dp(60); sp.leftMargin = ui.dp(12); sp.rightMargin = ui.dp(12);
        root.addView(settingsPanel, sp);

        keybar = new LinearLayout(a); keybar.setOrientation(LinearLayout.HORIZONTAL); keybar.setBackgroundColor(0xE61A1C21); keybar.setVisibility(View.GONE);
        keybar.addView(key("Esc", 1)); keybar.addView(key("Tab", 15)); ctrlBtn = stickyKey("Ctrl", true); altBtn = stickyKey("Alt", false);
        keybar.addView(ctrlBtn); keybar.addView(altBtn); keybar.addView(key("←", 105)); keybar.addView(key("↑", 103)); keybar.addView(key("↓", 108)); keybar.addView(key("→", 106));
        root.addView(keybar, new FrameLayout.LayoutParams(-1, ui.dp(44), Gravity.BOTTOM));
        a.setContentView(root);
        if (a.getIntent().getBooleanExtra("settings", false)) root.post(new Runnable() { public void run() { setPad(true); settingsPanel.setVisibility(View.VISIBLE); } });
        else if (prefs.getBoolean("padmode", false)) root.post(new Runnable() { public void run() { setPad(true); } });
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

    private void save() { prefs.edit().putFloat("sens", sens).putFloat("scroll", scrollSpeed).putFloat("cur", curSize).putBoolean("accel", accel).putBoolean("inv", invScroll)
        .putBoolean("tap", tapClick).putBoolean("tapdrag", tapDrag).putBoolean("btns", showBtns).putInt("long", longMode).apply(); }

    private LinearLayout buildSettings(Context a) {
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        col.setBackground(ui.rect(0xF0181A1F, 18, 0x44FFFFFF)); col.setVisibility(View.GONE);
        col.setClickable(true);
        col.addView(ui.label("Тачпад", 17, 0xFFFFFFFF, true));
        col.addView(seek("Скорость курсора", 0.3f, 4f, sens, new FloatSet() { public void set(float v) { sens = v; } }));
        col.addView(toggle("Ускорение при быстром движении", accel, new BoolSet() { public void set(boolean v) { accel = v; } }));
        col.addView(seek("Скорость прокрутки", 0.3f, 4f, scrollSpeed, new FloatSet() { public void set(float v) { scrollSpeed = v; } }));
        col.addView(toggle("Обратная прокрутка", invScroll, new BoolSet() { public void set(boolean v) { invScroll = v; } }));
        col.addView(toggle("Тап = клик", tapClick, new BoolSet() { public void set(boolean v) { tapClick = v; } }));
        col.addView(toggle("Тап и тяни = перетаскивание", tapDrag, new BoolSet() { public void set(boolean v) { tapDrag = v; } }));
        col.addView(toggle("Кнопки ЛКМ/ПКМ внизу", showBtns, new BoolSet() { public void set(boolean v) { showBtns = v; applyPadUi(); } }));
        col.addView(seek("Размер курсора", 0.6f, 2.5f, curSize, new FloatSet() { public void set(float v) { curSize = v; cursor.invalidate(); } }));
        final TextView lm = ui.button(longLabel(), false, null);
        lm.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { longMode = (longMode + 1) % 3; ((TextView) v).setText(longLabel()); save(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = ui.dp(8); col.addView(lm, lp);
        return col;
    }
    private String longLabel() { return "Долгое нажатие: " + (longMode == 0 ? "удерживать ЛКМ (перетаскивание)" : longMode == 1 ? "правый клик" : "ничего"); }

    interface FloatSet { void set(float v); }
    interface BoolSet { void set(boolean v); }

    private View seek(String title, final float min, final float max, float cur, final FloatSet cb) {
        LinearLayout l = new LinearLayout(act); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(0, ui.dp(6), 0, 0);
        final TextView t = ui.label(title + ": " + String.format("%.1f", cur), 13, 0xFFC8CCD4, false); l.addView(t);
        SeekBar sb = new SeekBar(act); sb.setMax(100); sb.setProgress(Math.round((cur - min) / (max - min) * 100));
        final String ttl = title;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean u) { float v = min + (max - min) * p / 100f; cb.set(v); t.setText(ttl + ": " + String.format("%.1f", v)); }
            public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) { save(); } });
        l.addView(sb); return l;
    }

    private View toggle(String title, boolean cur, final BoolSet cb) {
        LinearLayout l = new LinearLayout(act); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); l.setPadding(0, ui.dp(6), 0, 0);
        TextView t = ui.label(title, 14, 0xFFE6E8EC, false); l.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(act); sw.setChecked(cur);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { cb.set(c); save(); } });
        l.addView(sw); return l;
    }

    private void applyPadUi() { padButtons.setVisibility(padMode && showBtns ? View.VISIBLE : View.GONE); }

    private void setPad(boolean on) {
        padMode = on; cursor.setVisibility(on ? View.VISIBLE : View.GONE); applyPadUi(); prefs.edit().putBoolean("padmode", on).apply();
        padBtn.setBackground(on ? ui.rect(ui.accent, 12, 0x33FFFFFF) : ui.rect(0x99000000, 12, 0x33FFFFFF));
        if (on) { int w = view.getWidth(), h = view.getHeight(); if (cursor.x == 0 && cursor.y == 0) { cursor.x = w / 2f; cursor.y = h / 2f; } cursor.invalidate(); nativeMotion(cursor.x, cursor.y); }
    }

    private TextView holdBtn(String s, final int btnIdx) {
        TextView t = ui.button(s, false, null); t.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1)); ((LinearLayout.LayoutParams) t.getLayoutParams()).setMargins(ui.dp(4), 0, ui.dp(4), 0);
        t.setBackground(ui.rect(0x99000000, 14, 0x55FFFFFF));
        t.setOnTouchListener(new View.OnTouchListener() { @Override public boolean onTouch(View v, MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { nativeMotion(cursor.x, cursor.y); nativeButton(btnIdx, 1); v.setPressed(true); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { nativeButton(btnIdx, 0); v.setPressed(false); }
            return true; } });
        return t;
    }

    private final class CursorView extends View {
        float x, y; private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path arrow = new Path();
        CursorView(Context c) { super(c); fill.setColor(Color.WHITE); line.setColor(Color.BLACK); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(3f); setClickable(false); }
        @Override protected void onDraw(Canvas c) {
            float u = ui.dp(1) * curSize; arrow.reset(); arrow.moveTo(x, y); arrow.lineTo(x, y + 20 * u); arrow.lineTo(x + 5 * u, y + 15.5f * u); arrow.lineTo(x + 9 * u, y + 24 * u);
            arrow.lineTo(x + 12 * u, y + 22.5f * u); arrow.lineTo(x + 8 * u, y + 14.5f * u); arrow.lineTo(x + 14 * u, y + 14.5f * u); arrow.close();
            c.drawPath(arrow, fill); c.drawPath(arrow, line);
        }
    }

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

        private long padT, lastTapT; private float padLX, padLY, padDX, padDY, padScroll; private int padMax; private boolean padMoved, padDrag, padIgnore, padTwoMoved, padLong;
        private final Runnable padLongRun = new Runnable() { @Override public void run() {
            if (padMax != 1 || padMoved || padDrag || longMode == 2) return;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); padLong = true; nativeMotion(cursor.x, cursor.y);
            if (longMode == 0) { padDrag = true; nativeButton(0, 1); } else { nativeButton(1, 1); nativeButton(1, 0); } } };

        private boolean padTouch(MotionEvent e) {
            float x = e.getX(), y = e.getY(); long now = e.getEventTime(); float w = getWidth(), h = getHeight();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    padT = now; padLX = padDX = x; padLY = padDY = y; padMax = 1; padMoved = padTwoMoved = padIgnore = padLong = false; padDrag = false; handler.removeCallbacks(padLongRun); handler.postDelayed(padLongRun, 450);
                    if (tapDrag && now - lastTapT < 280) { padDrag = true; nativeMotion(cursor.x, cursor.y); nativeButton(0, 1); }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    handler.removeCallbacks(padLongRun); if (padDrag) { nativeButton(0, 0); padDrag = false; }
                    padMax = 2; padLY = (e.getY(0) + e.getY(1)) / 2; padScroll = 0; padTwoMoved = false; return true;
                case MotionEvent.ACTION_POINTER_UP:
                    padIgnore = true; return true;
                case MotionEvent.ACTION_MOVE:
                    if (padMax >= 2) {
                        if (e.getPointerCount() >= 2) { float my = (e.getY(0) + e.getY(1)) / 2; padScroll += padLY - my; if (Math.abs(padLY - my) > 1) padTwoMoved = true; padLY = my;
                            if (Math.abs(padScroll) >= 6) { nativeAxis(padScroll / 5f * scrollSpeed * (invScroll ? -1 : 1)); padScroll = 0; } }
                    } else if (!padIgnore) {
                        float dx = x - padLX, dy = y - padLY; padLX = x; padLY = y;
                        if (!padMoved && Math.hypot(x - padDX, y - padDY) > slop) { padMoved = true; handler.removeCallbacks(padLongRun); }
                        float sp = (float) Math.hypot(dx, dy); float gain = sens * (accel ? (sp > 25 ? 2.4f : sp > 8 ? 1.8f : 1.2f) : 1.5f);
                        cursor.x = Math.max(0, Math.min(w - 1, cursor.x + dx * gain)); cursor.y = Math.max(0, Math.min(h - 1, cursor.y + dy * gain));
                        nativeMotion(cursor.x, cursor.y); cursor.invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(padLongRun);
                    if (padDrag) { nativeButton(0, 0); padDrag = false; lastTapT = 0; }
                    else if (padLong) { }
                    else if (tapClick && padMax == 1 && !padMoved && now - padT < 250) { nativeMotion(cursor.x, cursor.y); nativeButton(0, 1); nativeButton(0, 0); lastTapT = now; }
                    else if (tapClick && padMax == 2 && !padTwoMoved && now - padT < 350) { nativeMotion(cursor.x, cursor.y); nativeButton(1, 1); nativeButton(1, 0); }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(padLongRun); if (padDrag) { nativeButton(0, 0); padDrag = false; } return true;
                default: return true;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (padMode) return padTouch(e);
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
