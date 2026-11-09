package gldemo;
import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import com.termux.mod.ModScreen;

public class GlScreen implements ModScreen {
    static { System.loadLibrary("gldemo"); }
    static native void nStart(Surface s);
    static native void nStop();
    public void onCreate(Activity host, Bundle st) {
        SurfaceView sv = new SurfaceView(host);
        host.setContentView(sv);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { nStart(h.getSurface()); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
            public void surfaceDestroyed(SurfaceHolder h) { nStop(); }
        });
    }
}
