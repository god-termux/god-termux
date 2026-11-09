package glibcrt;

import android.app.Application;
import com.termux.mod.Mod;
import java.io.File;

/** Marker/entry for the glibc + Debian runtime (shim files and scripts live in $PREFIX; see GlibcScreen). */
public class GlibcRt implements Mod {
    public void onLoad(Application app, File modDir, ClassLoader loader) {}
}
