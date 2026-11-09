package modsmgr;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.termux.mod.Mod;
import java.io.File;

/** Adds "Mods" to Termux's own Settings screen (androidx.preference via reflection). */
public class ModsMgr implements Mod {
    static final String TAG = "ModsMgr";
    static Application app; final Handler h = new Handler(Looper.getMainLooper());

    public void onLoad(Application a, File modDir, ClassLoader loader) {
        app = a;
        a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(final android.app.Activity act) {
                if (!act.getClass().getName().equals("com.termux.app.activities.SettingsActivity")) return;
                h.postDelayed(new Runnable() { public void run() { addSetting(act); } }, 300);
            }
            public void onActivityCreated(android.app.Activity x, android.os.Bundle b) {}
            public void onActivityStarted(android.app.Activity x) {}
            public void onActivityPaused(android.app.Activity x) {}
            public void onActivityStopped(android.app.Activity x) {}
            public void onActivitySaveInstanceState(android.app.Activity x, android.os.Bundle b) {}
            public void onActivityDestroyed(android.app.Activity x) {}
        });
    }

    static Intent uiIntent() {
        return new Intent(Intent.ACTION_VIEW).setClassName(app.getPackageName(), "com.termux.mod.ModActivity")
                .putExtra("class", "modsmgr.ModsScreen").putExtra("fullscreen", false).addFlags(0x18080000);
    }

    void addSetting(final android.app.Activity a) {
        try {
            ClassLoader cl = a.getClassLoader();
            Object fm = a.getClass().getMethod("getSupportFragmentManager").invoke(a);
            java.util.List<?> frs = (java.util.List<?>) fm.getClass().getMethod("getFragments").invoke(fm);
            for (Object f : frs) {
                if (!f.getClass().getName().endsWith("RootPreferencesFragment")) continue;
                Object screen = f.getClass().getMethod("getPreferenceScreen").invoke(f);
                if (screen == null) continue;
                Class<?> pc = Class.forName("androidx.preference.Preference", true, cl);
                if (screen.getClass().getMethod("findPreference", CharSequence.class).invoke(screen, "god_mods") != null) return;
                Object pref = pc.getConstructor(Context.class).newInstance(a);
                pc.getMethod("setKey", String.class).invoke(pref, "god_mods");
                pc.getMethod("setTitle", CharSequence.class).invoke(pref, "Моды");
                pc.getMethod("setSummary", CharSequence.class).invoke(pref, "Список модов, включение и выключение, папка с модами");
                Class<?> lc = Class.forName("androidx.preference.Preference$OnPreferenceClickListener", true, cl);
                Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{lc}, new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        if (m.getName().equals("onPreferenceClick")) { a.startActivity(uiIntent()); return Boolean.TRUE; }
                        return null; } });
                pc.getMethod("setOnPreferenceClickListener", lc).invoke(pref, listener);
                screen.getClass().getMethod("addPreference", pc).invoke(screen, pref);
                Log.i(TAG, "settings entry added");
                return;
            }
        } catch (Throwable t) { Log.e(TAG, "addSetting", t); }
    }
}
