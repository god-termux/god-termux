package com.termux.mod;
import android.app.Application;
import java.io.File;
/** Optional entry point of a mod, named by "main" in mod.json. */
public interface Mod { void onLoad(Application app, File modDir, ClassLoader loader); }
