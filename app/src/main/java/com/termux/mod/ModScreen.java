package com.termux.mod;
import android.app.Activity;
import android.os.Bundle;
/** Mods implement this to draw into a window hosted by ModActivity. */
public interface ModScreen {
    void onCreate(Activity host, Bundle state);
    default void onStart() {}
    default void onResume() {}
    default void onPause() {}
    default void onStop() {}
    default void onDestroy() {}
    default void onFocus(boolean has) {}
}
