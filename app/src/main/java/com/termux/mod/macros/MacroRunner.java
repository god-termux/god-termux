package com.termux.mod.macros;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import java.util.*;

/** Plays a macro into the current terminal: key down/up with modifier state, combos, hold, pauses in ms, text, clipboard, chained macros. */
public final class MacroRunner {
    private MacroRunner() {}

    public static final LinkedHashMap<String, Integer> KEYS = new LinkedHashMap<>();
    public static final List<String> MODIFIERS = Arrays.asList("CTRL", "ALT", "SHIFT");
    static {
        KEYS.put("CTRL", KeyEvent.KEYCODE_CTRL_LEFT); KEYS.put("ALT", KeyEvent.KEYCODE_ALT_LEFT); KEYS.put("SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT);
        KEYS.put("ESC", KeyEvent.KEYCODE_ESCAPE); KEYS.put("TAB", KeyEvent.KEYCODE_TAB); KEYS.put("ENTER", KeyEvent.KEYCODE_ENTER);
        KEYS.put("BACKSPACE", KeyEvent.KEYCODE_DEL); KEYS.put("DELETE", KeyEvent.KEYCODE_FORWARD_DEL); KEYS.put("SPACE", KeyEvent.KEYCODE_SPACE);
        KEYS.put("INSERT", KeyEvent.KEYCODE_INSERT);
        KEYS.put("UP", KeyEvent.KEYCODE_DPAD_UP); KEYS.put("DOWN", KeyEvent.KEYCODE_DPAD_DOWN); KEYS.put("LEFT", KeyEvent.KEYCODE_DPAD_LEFT); KEYS.put("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT);
        KEYS.put("HOME", KeyEvent.KEYCODE_MOVE_HOME); KEYS.put("END", KeyEvent.KEYCODE_MOVE_END); KEYS.put("PGUP", KeyEvent.KEYCODE_PAGE_UP); KEYS.put("PGDN", KeyEvent.KEYCODE_PAGE_DOWN);
        for (int i = 1; i <= 12; i++) KEYS.put("F" + i, KeyEvent.KEYCODE_F1 + i - 1);
        for (char c = 'A'; c <= 'Z'; c++) KEYS.put(String.valueOf(c), KeyEvent.KEYCODE_A + (c - 'A'));
        for (char c = '0'; c <= '9'; c++) KEYS.put(String.valueOf(c), KeyEvent.KEYCODE_0 + (c - '0'));
    }

    private static int flag(String k) {
        if ("CTRL".equals(k)) return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        if ("ALT".equals(k)) return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        if ("SHIFT".equals(k)) return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        return 0;
    }

    private static int modsFlags(String mods) {
        int f = 0; if (mods != null) for (String p : mods.split("\\+")) f |= flag(p.trim()); return f;
    }

    public static void run(final TermuxActivity act, final Macro m, final MacroStore store) {
        new Thread(new Runnable() { @Override public void run() { try { play(act, m, store, 0); } catch (InterruptedException ignored) {} } }, "god-macro").start();
    }

    private static void key(final TermuxActivity act, final int code, final boolean down, final int meta) {
        act.runOnUiThread(new Runnable() { @Override public void run() {
            TerminalView v = act.getTerminalView(); if (v == null) return;
            long t = SystemClock.uptimeMillis();
            KeyEvent e = new KeyEvent(t, t, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, 0, meta);
            if (down) v.onKeyDown(code, e); else v.onKeyUp(code, e);
        } });
    }

    private static void write(final TermuxActivity act, final String txt) {
        act.runOnUiThread(new Runnable() { @Override public void run() { TerminalSession s = act.getCurrentSession(); if (s != null && txt != null && !txt.isEmpty()) s.write(txt); } });
    }

    private static void play(TermuxActivity act, Macro m, MacroStore store, int depth) throws InterruptedException {
        if (depth > 8) return;                                        // chains: protect against macros that call each other forever
        int meta = 0; final Set<Integer> held = new HashSet<>();
        try {
            for (int r = 0; r < Math.max(1, m.repeat); r++) {
                for (Macro.Step s : m.steps) {
                    if (Macro.Step.DELAY.equals(s.t)) { Thread.sleep(Math.max(0, Math.min(s.ms, 60000))); continue; }
                    if (Macro.Step.TEXT.equals(s.t)) { write(act, s.s); continue; }
                    if (Macro.Step.CLIP.equals(s.t)) {
                        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData cd = cm == null ? null : cm.getPrimaryClip();
                        if (cd != null && cd.getItemCount() > 0) write(act, String.valueOf(cd.getItemAt(0).coerceToText(act)));
                        continue;
                    }
                    if (Macro.Step.MACRO.equals(s.t)) { Macro other = store == null ? null : store.find(s.s); if (other != null) play(act, other, store, depth + 1); continue; }
                    Integer code = KEYS.get(s.k); if (code == null) continue;
                    if (Macro.Step.COMBO.equals(s.t)) { int mm = meta | modsFlags(s.mods); key(act, code, true, mm); key(act, code, false, mm); continue; }
                    if (Macro.Step.HOLD.equals(s.t)) { key(act, code, true, meta); Thread.sleep(Math.max(0, Math.min(s.ms, 60000))); key(act, code, false, meta); continue; }
                    int f = flag(s.k);
                    if (f != 0) {                                     // modifier key step: only tracked state, no real key event
                        if (Macro.Step.DOWN.equals(s.a)) meta |= f; else if (Macro.Step.UP.equals(s.a)) meta &= ~f;
                        continue;
                    }
                    if (!Macro.Step.UP.equals(s.a)) { key(act, code, true, meta); held.add(code); }
                    if (!Macro.Step.DOWN.equals(s.a)) { key(act, code, false, meta); held.remove(code); }
                }
            }
        } finally {
            for (Integer c : held) key(act, c, false, 0);            // never leave a key stuck
        }
    }
}
