package com.termux.mod;
import android.content.Intent;
import android.os.Bundle;

/** Single-instance host for the Wayland compositor screen: re-launching just brings the existing window to the front. */
public class WlActivity extends ModActivity {
    @Override protected void onCreate(Bundle s) { getIntent().putExtra("class", "wlcomp.WlScreen").putExtra("fullscreen", true); super.onCreate(s); }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); notifyNewIntent(i); }
}
