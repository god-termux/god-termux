package wlcomp;

import android.content.Context;
import android.content.SharedPreferences;

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

    private final SharedPreferences sp;
    WlPrefs(Context c) {
        sp = c.getSharedPreferences("wlcomp", Context.MODE_PRIVATE);
        resMode = sp.getInt("resMode", 0); resPct = sp.getInt("resPct", 100); resW = sp.getInt("resW", 1280); resH = sp.getInt("resH", 720); fit = sp.getInt("fit", 0);
        uiScale = sp.getInt("uiScale", 0); orient = sp.getInt("orient", 0); fullscreen = sp.getBoolean("full", true); keepOn = sp.getBoolean("keepOn", true); cutout = sp.getBoolean("cutout", true);
        touchMode = sp.getInt("touchMode", sp.getBoolean("padmode", false) ? 1 : 0);
        sens = sp.getFloat("sens", 1f); scrollSpeed = sp.getFloat("scroll", 1f); curSize = sp.getFloat("cur", 1f); accel = sp.getBoolean("accel", true); invScroll = sp.getBoolean("inv", false);
        tapClick = sp.getBoolean("tap", true); tapDrag = sp.getBoolean("tapdrag", true); padButtons = sp.getBoolean("btns", true); longMode = sp.getInt("long", 0);
        keybar = sp.getBoolean("keybar", true); menuBtn = sp.getBoolean("menuBtn", true); menuX = sp.getFloat("menuX", -1); menuY = sp.getFloat("menuY", -1);
    }
    void save() {
        sp.edit().putInt("resMode", resMode).putInt("resPct", resPct).putInt("resW", resW).putInt("resH", resH).putInt("fit", fit).putInt("uiScale", uiScale).putInt("orient", orient)
            .putBoolean("full", fullscreen).putBoolean("keepOn", keepOn).putBoolean("cutout", cutout).putInt("touchMode", touchMode)
            .putFloat("sens", sens).putFloat("scroll", scrollSpeed).putFloat("cur", curSize).putBoolean("accel", accel).putBoolean("inv", invScroll).putBoolean("tap", tapClick)
            .putBoolean("tapdrag", tapDrag).putBoolean("btns", padButtons).putInt("long", longMode).putBoolean("keybar", keybar).putBoolean("menuBtn", menuBtn)
            .putFloat("menuX", menuX).putFloat("menuY", menuY).apply();
    }
}
