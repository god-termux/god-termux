package cosmic;

import android.app.Application;
import android.util.Log;
import com.termux.mod.Mod;
import java.io.File;

/** Мод «COSMIC (нативный)»: экран открывается командой cosmic-native (ModActivity, класс cosmic.CosmicScreen). */
public class CosmicMod implements Mod {
    @Override public void onLoad(Application app, File modDir, ClassLoader loader) {
        Log.i("cosmic-mod", "загружен, каталог " + modDir);
    }
}
