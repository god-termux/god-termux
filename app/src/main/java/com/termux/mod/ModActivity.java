package com.termux.mod;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

/** am start -n com.termux/com.termux.mod.ModActivity --es mod NAME --es class pkg.Cls [--ez fullscreen true] */
public class ModActivity extends Activity {
    private ModScreen screen;
    protected void notifyNewIntent(Intent i) { if (screen != null) screen.onNewIntent(i); }
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        String mod = getIntent().getStringExtra("mod");
        String cls = getIntent().getStringExtra("class");
        try {
            ClassLoader cl = mod == null ? getClassLoader() : ModLoader.loader(mod);
            if (cl == null) cl = getClassLoader();   // built-in screens live in the app class loader
            if (getIntent().getBooleanExtra("fullscreen", true))
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            screen = (ModScreen) cl.loadClass(cls).getDeclaredConstructor().newInstance();
            screen.onCreate(this, s);
        } catch (Throwable t) { Log.e("TermuxMod", "ModActivity", t); finish(); }
    }
    @Override public void onBackPressed() { if (screen != null && screen.onBack()) return; super.onBackPressed(); }
    @Override protected void onStart() { super.onStart(); if (screen != null) screen.onStart(); }
    @Override protected void onResume() { super.onResume(); if (screen != null) screen.onResume(); }
    @Override protected void onPause() { if (screen != null) screen.onPause(); super.onPause(); }
    @Override protected void onStop() { if (screen != null) screen.onStop(); super.onStop(); }
    @Override protected void onDestroy() { if (screen != null) screen.onDestroy(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (screen != null) screen.onFocus(f); }
}
