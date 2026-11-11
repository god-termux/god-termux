package adbfind;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import com.termux.mod.ModScreen;
import godui.Ui;
import java.util.concurrent.Callable;

public class AdbScreen implements ModScreen {
    Activity act; Ui ui; LinearLayout col, statusCard; TextView out; EditText code;

    public void onCreate(Activity a, Bundle b) {
        act = a; ui = new Ui(a);
        col = ui.page(a, "Привилегированный шелл", "Встроенный ADB вместо Shizuku · команда gsh");
        statusCard = ui.card(col);

        LinearLayout pair = ui.card(col);
        pair.addView(ui.label("Сопряжение", 16, ui.text, true));
        pair.addView(ui.label("Откройте «Беспроводная отладка → Подключить устройство с помощью кода» и введите код. Проще: введите код в появившемся уведомлении.", 13, ui.sub, false));
        code = new EditText(a); code.setHint("Код из 6 цифр"); code.setInputType(InputType.TYPE_CLASS_NUMBER); code.setTextSize(18);
        code.setTextColor(ui.text); code.setHintTextColor(ui.sub); code.setBackground(ui.rect(ui.dark ? 0xFF14161A : 0xFFF0F2F5, 12, 0)); code.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        pair.addView(code, ui.gap(10));
        pair.addView(ui.button("Сопрячь", true, new Runnable() { public void run() { final String c = code.getText().toString().trim();
            if (c.length() != 6) { show("Введите 6-значный код"); return; }
            bg(new Callable<String>() { public String call() { return AdbFind.INSTANCE.doPair(c); } }); } }), ui.gap(10));

        LinearLayout act2 = ui.card(col);
        act2.addView(ui.label("Действия", 16, ui.text, true));
        act2.addView(ui.button("Подключить", false, new Runnable() { public void run() { bg(new Callable<String>() { public String call() { return AdbFind.INSTANCE.doConnect(); } }); } }), ui.gap(10));
        act2.addView(ui.button("Проверить (shell id)", false, new Runnable() { public void run() { bg(new Callable<String>() { public String call() { return AdbFind.INSTANCE.doTest(); } }); } }), ui.gap(8));
        act2.addView(ui.button("Открыть привилегированный терминал", true, new Runnable() { public void run() { openTerminal(); } }), ui.gap(8));
        act2.addView(ui.button("Открыть «Для разработчиков»", false, new Runnable() { public void run() {
            try { act.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); } catch (Throwable e) { show("Не удалось: " + e); } } }), ui.gap(8));
        act2.addView(ui.button("Добавить ярлык на главный экран", false, new Runnable() { public void run() { show(AdbFind.INSTANCE.pinShortcut()); } }), ui.gap(8));
        out = ui.mono(act2);
        refresh();
    }

    /** New Termux tab running the interactive `dsu` (RunCommandService is a component of this very app, so no extra permission is needed). */
    void openTerminal() {
        try {
            AdbFind f = AdbFind.INSTANCE;
            Intent i = new Intent("com.termux.RUN_COMMAND").setClassName(act.getPackageName(), "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", f.prefix + "/bin/dsu")
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", f.home.getAbsolutePath())
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            if (!new java.io.File(f.prefix + "/bin/dsu").exists()) { show("Команда dsu не найдена в $PREFIX/bin (нужна новая сборка или ручная копия god/runtime/dsu)"); return; }
            act.startService(i);
            show("Открываю вкладку с dsu…");
        } catch (Throwable t) { show("Не удалось открыть терминал: " + t); }
    }

    void show(final String s) { act.runOnUiThread(new Runnable() { public void run() { out.setText(s); out.setVisibility(View.VISIBLE); } }); }

    void bg(final Callable<String> c) {
        show("…");
        new Thread(new Runnable() { public void run() { String r; try { r = c.call(); } catch (Throwable t) { r = "Ошибка: " + t; } show(r); refresh(); } }).start();
    }

    void refresh() {
        new Thread(new Runnable() { public void run() {
            final AdbFind f = AdbFind.INSTANCE; final String ep = f == null ? null : f.connectEp(); final boolean pairing = f != null && f.pairEp != null;
            String st = "—"; int sc = ui.sub;
            if (ep != null) { try { f.run(f.prefix + "/bin/adb", "connect", ep); String s = f.run(f.prefix + "/bin/adb", "-s", ep, "get-state");
                if (s.equals("device")) { st = "Подключено (shell, uid 2000)"; sc = ui.ok; } else { st = "Не подключено — нужно сопряжение"; sc = ui.warn; } } catch (Throwable t) { st = "Ошибка: " + t; sc = ui.err; } }
            final String fst = st; final int fsc = sc;
            act.runOnUiThread(new Runnable() { public void run() {
                statusCard.removeAllViews();
                statusCard.addView(ui.label("Статус", 16, ui.text, true));
                ui.row(statusCard, "Точка подключения", ep == null ? "не найдена — включите «Беспроводная отладка»" : ep, ep == null ? ui.err : ui.ok);
                ui.row(statusCard, "Окно сопряжения", pairing ? "открыто (" + f.pairEp + ")" : "закрыто", pairing ? ui.ok : ui.sub);
                ui.row(statusCard, "ADB", fst, fsc);
                ui.row(statusCard, "Команда в терминале", "dsu  (dsu --tab - в новой вкладке)", ui.sub);
            } });
        } }).start();
    }

    public void onResume() { if (statusCard != null) refresh(); }
}
