package com.termux.mod;

import android.app.Application;
import android.system.Os;
import android.util.Log;
import java.io.*;
import java.security.MessageDigest;

/** Installs the god-termux runtime (glibc shim, dapt, gsh, profile.d hook) from APK assets into $PREFIX. Idempotent; runs once bootstrap exists. */
public final class GodRuntime {
    static final String TAG = "GodRuntime", ROOT = "god";
    private GodRuntime() {}

    public static void init(Application app) {
        File prefix = new File(app.getFilesDir(), "usr");
        if (!new File(prefix, "bin").isDirectory()) return;      // bootstrap not extracted yet; called again at next start
        try {
            copyTree(app, ROOT, prefix);
            File shim = new File(prefix, "glibc/shim");
            link(new File(prefix, "bin/dapt-get"), "dapt"); link(new File(prefix, "bin/dapt-cache"), "dapt");
            if (verifyShim(shim)) new File(shim, ".ok").createNewFile(); else new File(shim, ".ok").delete();
        } catch (Throwable t) { Log.e(TAG, "init failed", t); }
    }

    static void copyTree(Application app, String assetDir, File dest) throws IOException {
        String[] names = app.getAssets().list(assetDir); if (names == null) return;
        for (String n : names) {
            String path = assetDir + "/" + n; String[] sub = app.getAssets().list(path);
            File out = new File(dest, n);
            if (sub != null && sub.length > 0) { out.mkdirs(); copyTree(app, path, out); continue; }
            byte[] data = readAll(app.getAssets().open(path));
            if (out.exists() && sha(data).equals(sha(readAll(new FileInputStream(out))))) continue;
            out.getParentFile().mkdirs();
            File tmp = new File(out.getPath() + ".tmp"); try (FileOutputStream f = new FileOutputStream(tmp)) { f.write(data); }
            tmp.setExecutable(true, false); tmp.setReadable(true, false); if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out); }
        }
    }

    static void link(File l, String target) { try { if (!l.exists()) Os.symlink(target, l.getPath()); } catch (Throwable ignored) {} }

    static boolean verifyShim(File dir) {
        File sums = new File(dir, "SHA256SUMS"); if (!sums.exists()) return false;
        try (BufferedReader r = new BufferedReader(new FileReader(sums))) { String l; int n = 0;
            while ((l = r.readLine()) != null) { String[] p = l.trim().split("\\s+"); if (p.length < 2) continue;
                if (!sha(readAll(new FileInputStream(new File(dir, p[1])))).equals(p[0])) return false; n++; }
            return n > 0; } catch (Throwable t) { return false; }
    }

    static byte[] readAll(InputStream in) throws IOException { try (InputStream i = in) { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[1 << 16]; int n; while ((n = i.read(b)) > 0) o.write(b, 0, n); return o.toByteArray(); } }

    static String sha(byte[] d) { try { StringBuilder sb = new StringBuilder(); for (byte x : MessageDigest.getInstance("SHA-256").digest(d)) sb.append(String.format("%02x", x)); return sb.toString(); } catch (Exception e) { return ""; } }
}
