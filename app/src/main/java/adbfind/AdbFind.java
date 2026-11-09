package adbfind;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.termux.mod.Mod;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Finds Android's wireless-debugging endpoints via mDNS and publishes them for the `gsh` command:
 *  $PREFIX/tmp/adb-connect-port (host:port) and $PREFIX/tmp/adb-pair-port (host:port, only while the pairing dialog is open).
 *  While the pairing dialog is open it shows a notification with a text field: enter the 6-digit code there. */
public class AdbFind implements Mod {
    static final String TAG = "AdbFind", CHANNEL = "adbfind", ACTION = "adbfind.PAIR";
    static final int N_PAIR = 7311, N_RESULT = 7312, FLAG_MUTABLE = 1 << 25;
    public Application app; NsdManager nsd; NotificationManager nm; public File tmp, prefix, home;
    final Handler h = new Handler(Looper.getMainLooper());
    final ConcurrentLinkedQueue<Object[]> queue = new ConcurrentLinkedQueue<>(); boolean busy;
    public volatile String pairEp;
    public static volatile AdbFind INSTANCE;

    public void onLoad(Application a, File modDir, ClassLoader loader) {
        INSTANCE = this; app = a; prefix = new File(a.getFilesDir(), "usr"); home = new File(a.getFilesDir(), "home");
        tmp = new File(prefix, "tmp"); tmp.mkdirs();
        nsd = (NsdManager) a.getSystemService(Context.NSD_SERVICE);
        nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && android.os.Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Wireless debugging pairing", NotificationManager.IMPORTANCE_HIGH));
        a.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                android.os.Bundle r = RemoteInput.getResultsFromIntent(i);
                final CharSequence code = r == null ? null : r.getCharSequence("code");
                if (code != null) new Thread(new Runnable() { public void run() { pair(code.toString().trim()); } }).start();
            }
        }, new IntentFilter(ACTION));
        publishShortcut();
        hookSettings();
        if (nsd == null) return;
        start("_adb-tls-connect._tcp", "adb-connect-port");
        start("_adb-tls-pairing._tcp", "adb-pair-port");
    }

    void start(final String type, final String file) {
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, new NsdManager.DiscoveryListener() {
                public void onServiceFound(NsdServiceInfo s) { queue.add(new Object[]{s, file}); pump(); }
                public void onServiceLost(NsdServiceInfo s) {
                    new File(tmp, file).delete();
                    if (file.equals("adb-pair-port")) { pairEp = null; if (nm != null) nm.cancel(N_PAIR); }
                }
                public void onDiscoveryStarted(String t) { Log.i(TAG, "discovering " + t); }
                public void onDiscoveryStopped(String t) {}
                public void onStartDiscoveryFailed(String t, int c) { Log.e(TAG, "start failed " + t + " " + c); }
                public void onStopDiscoveryFailed(String t, int c) {}
            });
        } catch (Throwable t) { Log.e(TAG, "discover " + type, t); }
    }

    void pump() {
        h.post(new Runnable() { public void run() {
            if (busy) return; final Object[] it = queue.poll(); if (it == null) return; busy = true;
            nsd.resolveService((NsdServiceInfo) it[0], new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int c) { busy = false; if (c == 3) queue.add(it); h.postDelayed(new Runnable(){ public void run(){ pump(); }}, 500); }
                public void onServiceResolved(NsdServiceInfo s) {
                    busy = false; String host = s.getHost() != null ? s.getHost().getHostAddress() : "127.0.0.1";
                    String ep = host + ":" + s.getPort();
                    try (FileWriter w = new FileWriter(new File(tmp, (String) it[1]))) { w.write(ep + "\n"); } catch (Throwable ignored) {}
                    Log.i(TAG, it[1] + " = " + ep);
                    if (it[1].equals("adb-pair-port")) { pairEp = ep; notifyPair(); }
                    pump();
                }
            });
        }});
    }


    // ---- adds "Privileged shell" to Termux's own Settings screen (androidx.preference, via reflection) ----
    void hookSettings() {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(final android.app.Activity a) {
                if (!a.getClass().getName().equals("com.termux.app.activities.SettingsActivity")) return;
                h.postDelayed(new Runnable() { public void run() { addSetting(a); } }, 250);
            }
            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}
            public void onActivityStarted(android.app.Activity a) {}
            public void onActivityPaused(android.app.Activity a) {}
            public void onActivityStopped(android.app.Activity a) {}
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            public void onActivityDestroyed(android.app.Activity a) {}
        });
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
                if (screen.getClass().getMethod("findPreference", CharSequence.class).invoke(screen, "god_privileged") != null) return;
                Object pref = pc.getConstructor(Context.class).newInstance(a);
                pc.getMethod("setKey", String.class).invoke(pref, "god_privileged");
                pc.getMethod("setTitle", CharSequence.class).invoke(pref, "Привилегированный шелл");
                pc.getMethod("setSummary", CharSequence.class).invoke(pref, "Встроенный ADB вместо Shizuku: сопряжение, статус, проверка (команда gsh)");
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

    Intent uiIntent() {
        return new Intent(Intent.ACTION_VIEW).setClassName(app.getPackageName(), "com.termux.mod.ModActivity")
                .putExtra("class", "adbfind.AdbScreen").putExtra("fullscreen", false)
                .addFlags(0x18080000);
    }

    android.content.pm.ShortcutInfo shortcutInfo() {
        return new android.content.pm.ShortcutInfo.Builder(app, "adbfind")
                .setShortLabel("Wireless debugging").setLongLabel("Privileged shell (wireless debugging)")
                .setIcon(Icon.createWithResource(app, app.getApplicationInfo().icon)).setIntent(uiIntent()).build();
    }

    void publishShortcut() {
        String res;
        try {
            android.content.pm.ShortcutManager sm = app.getSystemService(android.content.pm.ShortcutManager.class);
            boolean ok = sm.setDynamicShortcuts(java.util.Collections.singletonList(shortcutInfo()));
            res = "setDynamicShortcuts=" + ok + " max=" + sm.getMaxShortcutCountPerActivity() + " rateLimited=" + sm.isRateLimitingActive()
                    + " dyn=" + sm.getDynamicShortcuts().size() + " manifest=" + sm.getManifestShortcuts().size();
        } catch (Throwable t) { res = "EXCEPTION " + t; }
        Log.i(TAG, "shortcut: " + res);
        try (FileWriter w = new FileWriter(new File(tmp, "adbfind-shortcut.log"))) { w.write(res + "\n"); } catch (Throwable ignored) {}
    }

    /** Asks the launcher to place an icon on the home screen (user confirms in a system dialog). */
    public String pinShortcut() {
        try {
            android.content.pm.ShortcutManager sm = app.getSystemService(android.content.pm.ShortcutManager.class);
            if (!sm.isRequestPinShortcutSupported()) return "Лаунчер не поддерживает закрепление ярлыков";
            return sm.requestPinShortcut(shortcutInfo(), null) ? "Подтвердите добавление ярлыка в системном окне" : "Лаунчер отклонил запрос";
        } catch (Throwable t) { return "Ошибка: " + t; }
    }

    Notification.Builder builder() {
        return android.os.Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
    }

    void notifyPair() {
        if (nm == null) return;
        RemoteInput ri = new RemoteInput.Builder("code").setLabel("6-digit pairing code").build();
        PendingIntent pi = PendingIntent.getBroadcast(app, 0, new Intent(ACTION).setPackage(app.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | FLAG_MUTABLE);
        Icon icon = Icon.createWithResource(app, app.getApplicationInfo().icon);
        Notification.Action act = new Notification.Action.Builder(icon, "Enter pairing code", pi).addRemoteInput(ri).build();
        nm.notify(N_PAIR, builder().setSmallIcon(app.getApplicationInfo().icon)
                .setContentTitle("Wireless debugging: pairing").setContentText("Tap \"Enter pairing code\" and type the 6-digit code shown in Settings")
                .setOngoing(true).setOnlyAlertOnce(true).addAction(act)
                .setContentIntent(PendingIntent.getActivity(app, 1, uiIntent(), PendingIntent.FLAG_UPDATE_CURRENT)).build());
    }

    void result(String text) {
        if (nm == null) return;
        nm.cancel(N_PAIR);
        nm.notify(N_RESULT, builder().setSmallIcon(app.getApplicationInfo().icon).setContentTitle("Wireless debugging")
                .setContentText(text).setAutoCancel(true).build());
    }

    public String run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("HOME", home.getAbsolutePath()); pb.environment().put("PREFIX", prefix.getAbsolutePath());
        pb.environment().put("TMPDIR", tmp.getAbsolutePath()); pb.environment().put("PATH", prefix + "/bin");
        Process p = pb.start(); StringBuilder sb = new StringBuilder(); String l;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) { while ((l = r.readLine()) != null) sb.append(l).append('\n'); }
        p.waitFor(); return sb.toString().trim();
    }

    void pair(String code) { result(doPair(code)); }

    public String connectEp() { try { File f = new File(tmp, "adb-connect-port"); return f.exists() ? new BufferedReader(new java.io.FileReader(f)).readLine() : null; } catch (Throwable t) { return null; } }

    public String doPair(String code) {
        String ep = pairEp; String adb = prefix + "/bin/adb";
        if (ep == null) return "Окно сопряжения закрыто: откройте «Подключить устройство с помощью кода» и повторите";
        if (!new File(adb).exists()) return "adb не установлен: pkg install android-tools";
        try {
            String out = run(adb, "pair", ep, code); Log.i(TAG, "pair: " + out);
            if (!out.toLowerCase().contains("success")) return "Сопряжение не удалось: " + out;
            Thread.sleep(1500);
            return "Сопряжено. " + doConnect();
        } catch (Throwable t) { Log.e(TAG, "pair", t); return "Ошибка: " + t; }
    }

    public String doConnect() {
        try { String c = connectEp(); if (c == null) return "Нет точки подключения (включите «Беспроводная отладка»)";
            String out = run(prefix + "/bin/adb", "connect", c); Log.i(TAG, "connect: " + out); return out; }
        catch (Throwable t) { return "Ошибка: " + t; }
    }

    public String doTest() {
        try { String c = connectEp(); if (c == null) return "нет точки подключения";
            return run(prefix + "/bin/adb", "-s", c, "shell", "id"); }
        catch (Throwable t) { return "Ошибка: " + t; }
    }

    public String doStatus() {
        String c = connectEp(); StringBuilder sb = new StringBuilder();
        sb.append("Точка подключения: ").append(c == null ? "не найдена (включите «Беспроводная отладка»)" : c).append('\n');
        sb.append("Окно сопряжения: ").append(pairEp == null ? "закрыто" : "открыто (" + pairEp + ")").append('\n');
        if (c != null) { try { run(prefix + "/bin/adb", "connect", c);
            String st = run(prefix + "/bin/adb", "-s", c, "get-state");
            sb.append("Состояние ADB: ").append(st.equals("device") ? "подключено (shell)" : "не подключено - нужно сопряжение").append('\n'); } catch (Throwable t) { sb.append("Состояние ADB: ошибка ").append(t).append('\n'); } }
        return sb.toString();
    }
}
