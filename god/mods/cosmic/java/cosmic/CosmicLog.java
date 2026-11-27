package cosmic;

import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Журнал мода: usr/tmp/cosmic-mod.log (читается без logcat) + logcat, тег cosmic-mod. */
final class CosmicLog {
    private static File file;

    static void init(File filesDir) {
        file = new File(filesDir, "usr/tmp/cosmic-mod.log");
    }

    static synchronized void w(String msg) {
        Log.i("cosmic-mod", msg);
        if (file == null) return;
        try (FileWriter f = new FileWriter(file, true)) {
            f.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()) + " " + msg + "\n");
        } catch (Throwable ignored) {}
    }

    static String trace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    static void e(String msg, Throwable t) {
        w(msg + ": " + trace(t));
    }
}
