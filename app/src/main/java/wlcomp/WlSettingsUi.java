package wlcomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import godui.Ui;
import java.util.Locale;

/** Full-screen settings page: Display / Pointer / Keyboard & interface. Changes are applied live. */
final class WlSettingsUi {
    interface Host { void applyDisplay(); void applyPointer(); void applyChrome(); void close(); }
    private interface IntSet { void set(int v); }
    private interface FloatSet { void set(float v); }
    private interface BoolSet { void set(boolean v); }

    private final Activity act; private final Ui ui; private final WlPrefs p; private final Host host;
    private LinearLayout list; private FrameLayout root;

    WlSettingsUi(Activity a, Ui ui, WlPrefs p, Host h) { act = a; this.ui = ui; this.p = p; host = h; }

    View build() {
        root = new FrameLayout(act); root.setBackgroundColor(0xFF0F1115); root.setClickable(true);
        LinearLayout col = new LinearLayout(act); col.setOrientation(LinearLayout.VERTICAL); root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = new LinearLayout(act); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setBackgroundColor(0xFF171A20); bar.setPadding(ui.dp(8), ui.dp(8), ui.dp(16), ui.dp(8));
        TextView back = ui.label("←", 24, ui.accent, true); back.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { host.close(); } });
        bar.addView(back); bar.addView(ui.label("Настройки Wayland", 19, 0xFFFFFFFF, true));
        col.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        ScrollView sv = new ScrollView(act); sv.setFillViewport(false); list = new LinearLayout(act); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0, 0, 0, ui.dp(40));
        sv.addView(list, new FrameLayout.LayoutParams(-1, -2)); col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        fill(); return root;
    }

    private void fill() {
        list.removeAllViews();
        section("Экран");
        choice("Разрешение", new String[] {"Родное (как у экрана)", "Масштаб от экрана", "Точное (ширина × высота)"}, p.resMode, new IntSet() { public void set(int v) { p.resMode = v; changed(true); } },
            "Меньше разрешение — выше скорость и крупнее интерфейс");
        if (p.resMode == 1) slider("Масштаб разрешения", 25, 100, p.resPct, "%", new IntSet() { public void set(int v) { p.resPct = v; changed(false); } });
        if (p.resMode == 2) {
            number("Ширина, px", p.resW, 320, 7680, new IntSet() { public void set(int v) { p.resW = v; changed(true); } });
            number("Высота, px", p.resH, 240, 4320, new IntSet() { public void set(int v) { p.resH = v; changed(true); } });
            choice("Вписывание картинки", new String[] {"Сохранять пропорции (чёрные поля)", "Растянуть на весь экран"}, p.fit, new IntSet() { public void set(int v) { p.fit = v; changed(false); } }, null);
        }
        { final int[] hz = WlPrefs.rates(act); String[] names = new String[hz.length + 1]; names[0] = "Авто (как в системе)"; int sel = 0;
          for (int i = 0; i < hz.length; i++) { names[i + 1] = hz[i] + " Гц"; if (hz[i] == p.fps) sel = i + 1; }
          choice("Частота кадров экрана", names, sel, new IntSet() { public void set(int v) { p.fps = v == 0 ? 0 : hz[v - 1]; changed(false); } },
              "Какую частоту обновления просить у экрана. Если в системных настройках частота зафиксирована, система может её не уступить"); }
        choice("Масштаб интерфейса", new String[] {"Авто", "1×", "2×", "3×"}, p.uiScale, new IntSet() { public void set(int v) { p.uiScale = v; changed(false); } }, "Размер элементов программ (целое число раз)");
        choice("Ориентация", new String[] {"Авто", "Альбомная", "Портретная"}, p.orient, new IntSet() { public void set(int v) { p.orient = v; changed(false); } }, null);
        toggle("Полный экран", "Скрыть строку состояния и кнопки навигации", p.fullscreen, new BoolSet() { public void set(boolean v) { p.fullscreen = v; changed(false); } });
        toggle("Под вырез камеры", "Использовать область вокруг выреза", p.cutout, new BoolSet() { public void set(boolean v) { p.cutout = v; changed(false); } });
        toggle("Не выключать экран", null, p.keepOn, new BoolSet() { public void set(boolean v) { p.keepOn = v; changed(false); } });

        section("Клавиатура");
        choice("Режим ввода", new String[] {"Обе (авто)", "Только экранная", "Только физическая"}, p.kbMode, new IntSet() { public void set(int v) { p.kbMode = v; changed(false); } },
            "Авто: работают обе, повтор одного символа из двух путей отбрасывается. Только экранная: события физической клавиатуры игнорируются. Только физическая: экранная клавиатура не показывается и её ввод игнорируется");

        section("Рендер");
        choice("Графический драйвер", new String[] {"Системный (драйвер Mali)", "ANGLE (OpenGL ES поверх Vulkan)"}, p.render, new IntSet() { public void set(int v) { p.render = v; changed(false); } },
            "Применяется при следующем запуске Wayland: ☰ → ✕ Закрыть Wayland, затем открыть снова. Если ANGLE не загрузится, будет системный");
        toggle("Конвейер кадров", "Отправлять клиенту сигнал следующего кадра сразу после приёма, а не после вывода на экран. Даёт ~60 кадров/с вместо ~45-50. Применяется после «Закрыть Wayland» и повторного запуска", p.pipeline, new BoolSet() { public void set(boolean v) { p.pipeline = v; changed(false); } });
        toggle("Замер производительности", "Непрерывная перерисовка без ограничения кадров; статистика в logcat (тег wlcomp) и tmp/wlcomp-render.log. Нагружает батарею", p.bench, new BoolSet() { public void set(boolean v) { p.bench = v; changed(false); } });

        section("Указатель");
        choice("Режим касаний", new String[] {"Прямое касание (как мышь под пальцем)", "Тачпад (весь экран — тачпад)"}, p.touchMode, new IntSet() { public void set(int v) { p.touchMode = v; ptr(true); } },
            "Прямое: тап — клик, долгое нажатие — правая кнопка, два пальца — прокрутка");
        if (p.touchMode == 1) {
            slider10("Скорость курсора", 0.3f, 4f, p.sens, new FloatSet() { public void set(float v) { p.sens = v; ptr(false); } });
            toggle("Ускорение", "Быстрое движение пальца двигает курсор дальше", p.accel, new BoolSet() { public void set(boolean v) { p.accel = v; ptr(false); } });
            toggle("Тап = клик", "Два пальца — правый клик", p.tapClick, new BoolSet() { public void set(boolean v) { p.tapClick = v; ptr(false); } });
            toggle("Тап и тяни", "Касание и сразу движение = перетаскивание", p.tapDrag, new BoolSet() { public void set(boolean v) { p.tapDrag = v; ptr(false); } });
            choice("Долгое нажатие", new String[] {"Удерживать левую кнопку", "Правый клик", "Ничего"}, p.longMode, new IntSet() { public void set(int v) { p.longMode = v; ptr(false); } }, null);
            toggle("Кнопки ЛКМ / ПКМ", "Две экранные кнопки внизу (можно зажимать)", p.padButtons, new BoolSet() { public void set(boolean v) { p.padButtons = v; ptr(false); } });
            slider10("Размер курсора", 0.6f, 2.5f, p.curSize, new FloatSet() { public void set(float v) { p.curSize = v; ptr(false); } });
        }
        choice("Источник указателя", new String[] {"Касания и мышь (авто)", "Только касания", "Только физическая мышь"}, p.mouseMode, new IntSet() { public void set(int v) { p.mouseMode = v; ptr(false); } },
            "Физическая мышь: движение, кнопки (левая, правая, средняя) и колесо. Родной курсор Android над окном скрыт, курсор рисует композитор. В режиме «только мышь» касания по картинке игнорируются (кнопка меню остаётся)");
        slider10("Скорость прокрутки", 0.3f, 4f, p.scrollSpeed, new FloatSet() { public void set(float v) { p.scrollSpeed = v; ptr(false); } });
        toggle("Обратная прокрутка", null, p.invScroll, new BoolSet() { public void set(boolean v) { p.invScroll = v; ptr(false); } });

        section("Клавиатура и интерфейс");
        toggle("Панель клавиш", "Esc, Tab, Ctrl, Alt и стрелки над клавиатурой", p.keybar, new BoolSet() { public void set(boolean v) { p.keybar = v; host.applyChrome(); p.save(); } });
        toggle("Плавающая кнопка меню", "Если выключить — меню открывается тремя пальцами или из уведомления", p.menuBtn, new BoolSet() { public void set(boolean v) { p.menuBtn = v; host.applyChrome(); p.save(); } });
        TextView reset = ui.button("Сбросить положение кнопки меню", false, new Runnable() { public void run() { p.menuX = p.menuY = -1; host.applyChrome(); p.save(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(ui.dp(16), ui.dp(8), ui.dp(16), 0); list.addView(reset, lp);
    }

    private void changed(boolean rebuild) { p.save(); host.applyDisplay(); if (rebuild) fill(); else fill(); }
    private void ptr(boolean rebuild) { p.save(); host.applyPointer(); if (rebuild) fill(); }

    // ---------------------------------------------------------------- rows
    private void section(String t) { TextView h = ui.label(t.toUpperCase(Locale.ROOT), 12, ui.accent, true); h.setPadding(ui.dp(16), ui.dp(22), ui.dp(16), ui.dp(6)); list.addView(h); }

    private LinearLayout row(String title, String summary) {
        LinearLayout r = new LinearLayout(act); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        r.addView(ui.label(title, 16, 0xFFE9ECF1, false));
        if (summary != null) { TextView s = ui.label(summary, 13, 0xFF8E96A3, false); r.addView(s); }
        list.addView(r, new LinearLayout.LayoutParams(-1, -2)); return r;
    }

    private void toggle(String title, String summary, boolean cur, final BoolSet cb) {
        LinearLayout r = new LinearLayout(act); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        LinearLayout t = new LinearLayout(act); t.setOrientation(LinearLayout.VERTICAL);
        t.addView(ui.label(title, 16, 0xFFE9ECF1, false)); if (summary != null) t.addView(ui.label(summary, 13, 0xFF8E96A3, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        final Switch sw = new Switch(act); sw.setChecked(cur); r.addView(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton b, boolean c) { cb.set(c); } });
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { sw.toggle(); } });
        list.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private void choice(final String title, final String[] opts, final int cur, final IntSet cb, String hint) {
        LinearLayout r = row(title, opts[Math.max(0, Math.min(cur, opts.length - 1))] + (hint != null ? "\n" + hint : ""));
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
                .setSingleChoiceItems(opts, cur, new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int which) { d.dismiss(); cb.set(which); } })
                .setNegativeButton("Отмена", null).show(); } });
    }

    private void number(final String title, final int cur, final int min, final int max, final IntSet cb) {
        LinearLayout r = row(title, String.valueOf(cur));
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            final EditText e = new EditText(act); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setText(String.valueOf(cur)); e.setSelectAllOnFocus(true);
            new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title + " (" + min + "–" + max + ")").setView(e)
                .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int w) {
                    try { int x = Integer.parseInt(e.getText().toString().trim()); cb.set(Math.max(min, Math.min(max, x))); } catch (NumberFormatException ignored) {} } })
                .setNegativeButton("Отмена", null).show(); } });
    }

    private void slider(String title, final int min, final int max, int cur, final String unit, final IntSet cb) {
        LinearLayout r = row(title + ": " + cur + unit, null); final TextView t = (TextView) r.getChildAt(0); final String ttl = title;
        SeekBar sb = new SeekBar(act); sb.setMax(max - min); sb.setProgress(cur - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int pr, boolean u) { t.setText(ttl + ": " + (min + pr) + unit); }
            public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) { cb.set(min + b.getProgress()); } });
        r.addView(sb);
    }

    private void slider10(String title, final float min, final float max, float cur, final FloatSet cb) {
        LinearLayout r = row(title + ": " + String.format(Locale.US, "%.1f", cur), null); final TextView t = (TextView) r.getChildAt(0); final String ttl = title;
        SeekBar sb = new SeekBar(act); sb.setMax(100); sb.setProgress(Math.round((cur - min) / (max - min) * 100));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int pr, boolean u) { float v = min + (max - min) * pr / 100f; t.setText(ttl + ": " + String.format(Locale.US, "%.1f", v)); if (u) cb.set(v); }
            public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) { p.save(); } });
        r.addView(sb);
    }
}
