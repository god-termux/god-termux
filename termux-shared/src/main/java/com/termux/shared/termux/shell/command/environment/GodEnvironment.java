package com.termux.shared.termux.shell.command.environment;

import com.termux.shared.termux.TermuxConstants;
import java.io.File;
import java.util.HashMap;

/** god-termux: preload the glibc/FHS shim into every process the app starts (verified at install by GodRuntime). */
public final class GodEnvironment {
    private GodEnvironment() {}

    public static void apply(HashMap<String, String> environment, boolean isFailSafe) {
        if (isFailSafe) return;
        File shim = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/glibc/shim/libfhs-bionic.so");
        File ok = new File(shim.getParentFile(), ".ok");
        File off = new File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/fhs.off");
        if (!shim.exists() || !ok.exists() || off.exists()) return;
        String old = environment.get("LD_PRELOAD");
        environment.put("LD_PRELOAD", shim.getAbsolutePath() + (old != null && !old.isEmpty() ? ":" + old : ""));
    }
}
