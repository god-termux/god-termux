package com.termux.mod;
import android.app.Application;
import android.util.Log;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

public final class ModLoader {
    static final String TAG = "TermuxMod";
    static final Map<String, ClassLoader> LOADERS = new HashMap<>();
    static final Map<String, File> DIRS = new HashMap<>();

    public static File modsDir(Application app) {
        return new File(app.getFilesDir(), "home/.termux/mods");
    }

    public static void loadAll(Application app) {
        File root = modsDir(app);
        root.mkdirs();
        if (new File(root, "DISABLED").exists()) { Log.w(TAG, "mods disabled (DISABLED file)"); return; }
        new File(app.getFilesDir(), "usr/tmp/god-mods.log").delete();
        File marker = new File(root, ".loading");
        if (marker.exists()) {   // previous start died while loading mods: skip once
            new File(root, ".crashed").delete();
            marker.renameTo(new File(root, ".crashed"));
            Log.e(TAG, "previous mod load crashed; skipping mods this start");
            return;
        }
        File[] dirs = root.listFiles();
        if (dirs == null) dirs = new File[0];
        Arrays.sort(dirs);
        try { marker.createNewFile(); } catch (IOException ignored) {}
        loadBuiltins(app);
        for (File d : dirs) {
            if (!d.isDirectory() || d.getName().startsWith(".") || new File(d, "disabled").exists()) continue;
            if (isBuiltinName(d.getName())) { status(app, "skip external " + d.getName() + " (built-in)"); continue; }
            try { load(app, d); status(app, "external " + d.getName() + " ok"); }
            catch (Throwable t) { Log.e(TAG, "mod " + d.getName() + " failed", t); status(app, "external " + d.getName() + " FAILED: " + t); }
        }
        marker.delete();
    }

    // ---- built-in mods (compiled into the APK) ----
    static final Set<String> BUILTIN_LOADED = new HashSet<>();

    public static File builtinDisabledFile(Application app) { return new File(app.getFilesDir(), "home/.termux/builtin-mods.disabled"); }

    public static Set<String> disabledBuiltins(Application app) {
        Set<String> r = new HashSet<>();
        try { for (String l : readAll(builtinDisabledFile(app)).split("\\n")) if (!l.trim().isEmpty()) r.add(l.trim()); } catch (Throwable ignored) {}
        return r;
    }

    public static void setBuiltinEnabled(Application app, String name, boolean on) throws IOException {
        Set<String> r = disabledBuiltins(app); if (on) r.remove(name); else r.add(name);
        File f = builtinDisabledFile(app); f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f)) { for (String n : r) w.write(n + "\n"); }
    }

    static boolean isBuiltinName(String n) { for (Builtin.Entry e : Builtin.ALL) if (e.name.equals(n)) return true; return false; }

    /** Appends a line to $PREFIX/tmp/god-mods.log (cleared at each start) so load problems are visible without logcat. */
    static void status(Application app, String line) {
        try (FileWriter w = new FileWriter(new File(app.getFilesDir(), "usr/tmp/god-mods.log"), true)) { w.write(line + "\n"); } catch (Throwable ignored) {}
    }

    public static boolean builtinLoaded(String name) { return BUILTIN_LOADED.contains(name); }

    static void loadBuiltins(Application app) {
        Set<String> off = disabledBuiltins(app);
        for (Builtin.Entry e : Builtin.ALL) {
            if (off.contains(e.name)) continue;
            try { if (e.mod != null) e.mod.getDeclaredConstructor().newInstance().onLoad(app, null, app.getClassLoader()); BUILTIN_LOADED.add(e.name); status(app, "builtin " + e.name + " ok"); }
            catch (Throwable t) { Log.e(TAG, "builtin " + e.name + " failed", t); status(app, "builtin " + e.name + " FAILED: " + t); }
        }
    }

    static void load(Application app, File dir) throws Exception {
        JSONObject j = new JSONObject(readAll(new File(dir, "mod.json")));
        String name = j.optString("name", dir.getName());
        File libDir = new File(dir, "lib");
        File dex = new File(dir, j.optString("dex", "classes.dex"));
        ClassLoader cl = app.getClassLoader();
        if (dex.exists()) {
            dex.setReadOnly();
            File opt = new File(app.getCodeCacheDir(), "mod-" + name);
            opt.mkdirs();
            cl = new DexClassLoader(dex.getAbsolutePath(), opt.getAbsolutePath(),
                    libDir.getAbsolutePath(), app.getClassLoader());
        }
        LOADERS.put(name, cl); DIRS.put(name, dir);
        for (String l : strs(j.optJSONArray("preload")))
            System.load(new File(libDir, l).getAbsolutePath());
        String main = j.optString("main", "");
        if (!main.isEmpty()) {
            Object o = cl.loadClass(main).getDeclaredConstructor().newInstance();
            ((Mod) o).onLoad(app, dir, cl);
        }
        Log.i(TAG, "loaded mod " + name);
    }

    public static ClassLoader loader(String mod) { return LOADERS.get(mod); }
    public static File dir(String mod) { return DIRS.get(mod); }

    static List<String> strs(org.json.JSONArray a) throws Exception {
        List<String> r = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) r.add(a.getString(i));
        return r;
    }
    static String readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }
}
