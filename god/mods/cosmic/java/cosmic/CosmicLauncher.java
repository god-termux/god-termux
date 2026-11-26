package cosmic;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;
import com.termux.mod.ModScreen;

/**
 * Точка входа экрана (ModActivity --es class cosmic.CosmicLauncher). ModActivity при любой ошибке молча закрывает экран,
 * поэтому реальный экран CosmicScreen создаётся здесь: ошибка попадает в usr/tmp/cosmic-mod.log и показывается на экране.
 */
public class CosmicLauncher implements ModScreen {
    private ModScreen real;

    @Override public void onCreate(Activity host, Bundle state) {
        CosmicLog.init(host.getFilesDir());
        CosmicLog.w("экран: запуск, loader=" + getClass().getClassLoader());
        try {
            real = (ModScreen) Class.forName("cosmic.CosmicScreen", true, getClass().getClassLoader()).getDeclaredConstructor().newInstance();
            real.onCreate(host, state);
            CosmicLog.w("экран: создан");
        } catch (Throwable t) {
            real = null;
            CosmicLog.e("экран: ошибка создания", t);
            showError(host, "COSMIC: ошибка запуска экрана\n\n" + CosmicLog.trace(t));
        }
    }

    private static void showError(Activity host, String text) {
        TextView tv = new TextView(host);
        tv.setText(text + "\n\n(журнал: usr/tmp/cosmic-mod.log)");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11);
        tv.setGravity(Gravity.START);
        tv.setPadding(32, 32, 32, 32);
        ScrollView sv = new ScrollView(host);
        sv.setBackgroundColor(Color.BLACK);
        sv.addView(tv);
        host.setContentView(sv);
    }

    @Override public void onStart() { if (real != null) real.onStart(); }
    @Override public void onResume() { if (real != null) real.onResume(); }
    @Override public void onPause() { if (real != null) real.onPause(); }
    @Override public void onStop() { if (real != null) real.onStop(); }
    @Override public void onDestroy() { if (real != null) real.onDestroy(); }
    @Override public void onFocus(boolean has) { if (real != null) real.onFocus(has); }
}
