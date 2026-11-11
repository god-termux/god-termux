package wlcomp;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

/** Persisted settings of the Wayland window (modelled on Termux:X11's preference groups: display / pointer / keyboard). */
final class WlPrefs {
    // display
    int resMode;        // 0 native, 1 scaled (% of the screen), 2 exact (W x H)
    int resPct = 100; int resW = 1280, resH = 720;
    int fit;            // 0 keep aspect ratio (black bars), 1 stretch to the whole screen
    int uiScale;        // 0 auto, 1..3 fixed wl_output scale
    int orient;         // 0 auto, 1 landscape, 2 portrait
    boolean fullscreen = true, keepOn = true, cutout = true;
    // pointer
    int touchMode;      // 0 direct touch, 1 touchpad
    float sens = 1f, scrollSpeed = 1f, curSize = 1f; boolean accel = true, invScroll, tapClick = true, tapDrag = true, padButtons = true; int longMode;   // 0 hold LMB, 1 right click, 2 nothing
    // keyboard / chrome
    boolean keybar = true, menuBtn = true; float menuX = -1, menuY = -1;
    // render: 0 platform GLES driver, 1 ANGLE (read by the native render loop from flag files in ~/.termux when the compositor starts)
    int render; boolean bench;
    int fps;           // requested display refresh rate in Hz, 0 = system default
    boolean pipeline = true;   // send frame callbacks right after a commit is copied (client draws N+1 while N is presented); flag ~/.termux/wl-pipeline
    int mouseMode;      // pointer source: 0 touch and physical mouse (auto), 1 touch only, 2 physical mouse only
    int kbMode;         // keyboard input: 0 both (auto, duplicates dropped), 1 on-screen only, 2 hardware only
    private final Context ctx;

    private final SharedPreferences sp;
    WlPrefs(Context c) {
        ctx = c; sp = c.getSharedPreferences("wlcomp", Context.MODE_PRIVATE);
        render = sp.getInt("render", new File(c.getFilesDir(), "home/.termux/wl-angle").exists() ? 1 : 0); kbMode = sp.getInt("kbMode", 0); mouseMode = sp.getInt("mouseMode", 0); bench = sp.getBoolean("bench", new File(c.getFilesDir(), "home/.termux/wl-bench").exists());
        resMode = sp.getInt("resMode", 0); resPct = sp.getInt("resPct", 100); resW = sp.getInt("resW", 1280); resH = sp.getInt("resH", 720); fit = sp.getInt("fit", 0);
        uiScale = sp.getInt("uiScale", 0); orient = sp.getInt("orient", 0); fullscreen = sp.getBoolean("full", true); keepOn = sp.getBoolean("keepOn", true); cutout = sp.getBoolean("cutout", true);
        touchMode = sp.getInt("touchMode", sp.getBoolean("padmode", false) ? 1 : 0);
        sens = sp.getFloat("sens", 1f); scrollSpeed = sp.getFloat("scroll", 1f); curSize = sp.getFloat("cur", 1f); accel = sp.getBoolean("accel", true); invScroll = sp.getBoolean("inv", false);
        tapClick = sp.getBoolean("tap", true); tapDrag = sp.getBoolean("tapdrag", true); padButtons = sp.getBoolean("btns", true); longMode = sp.getInt("long", 0);
        fps = sp.getInt("fps", 0); pipeline = sp.getBoolean("pipeline", true); flag("wl-pipeline", pipeline);
        keybar = sp.getBoolean("keybar", true); menuBtn = sp.getBoolean("menuBtn", true); menuX = sp.getFloat("menuX", -1); menuY = sp.getFloat("menuY", -1);
    }
    void save() {
        sp.edit().putInt("resMode", resMode).putInt("resPct", resPct).putInt("resW", resW).putInt("resH", resH).putInt("fit", fit).putInt("uiScale", uiScale).putInt("orient", orient)
            .putBoolean("full", fullscreen).putBoolean("keepOn", keepOn).putBoolean("cutout", cutout).putInt("touchMode", touchMode)
            .putFloat("sens", sens).putFloat("scroll", scrollSpeed).putFloat("cur", curSize).putBoolean("accel", accel).putBoolean("inv", invScroll).putBoolean("tap", tapClick)
            .putBoolean("tapdrag", tapDrag).putBoolean("btns", padButtons).putInt("long", longMode).putBoolean("keybar", keybar).putBoolean("menuBtn", menuBtn)
            .putFloat("menuX", menuX).putFloat("menuY", menuY).putInt("render", render).putBoolean("bench", bench).putBoolean("pipeline", pipeline).putInt("fps", fps).putInt("kbMode", kbMode).putInt("mouseMode", mouseMode).apply();
        flag("wl-angle", render == 1); flag("wl-bench", bench); flag("wl-pipeline", pipeline);
    }
    /** The native loop looks for these files when it starts (home/.termux inside the app's files dir = Termux $HOME/.termux). */
    private void flag(String name, boolean on) {
        try { File f = new File(ctx.getFilesDir(), "home/.termux/" + name); if (on) { f.getParentFile().mkdirs(); f.createNewFile(); } else f.delete(); } catch (Throwable ignored) {}
    }
    /** Distinct refresh rates (Hz, rounded) the panel supports at its current resolution, ascending. */
    static int[] rates(android.app.Activity a) {
        java.util.TreeSet<Integer> t = new java.util.TreeSet<>();
        try {
            android.view.Display d = a.getWindowManager().getDefaultDisplay(); android.view.Display.Mode cur = d.getMode();
            for (android.view.Display.Mode m : d.getSupportedModes()) if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()) t.add(Math.round(m.getRefreshRate()));
        } catch (Throwable ignored) {}
        int[] r = new int[t.size()]; int k = 0; for (int v : t) r[k++] = v; return r;
    }
}
