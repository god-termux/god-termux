package com.termux.mod.macros;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.termux.app.TermuxActivity;
import godui.Ui;
import java.util.ArrayList;
import java.util.List;

/** Right-hand drawer with user buttons (macros) and the dialogs to create/edit them. */
public final class MacroPanel {
    private final TermuxActivity act; private final FrameLayout host; private final Ui ui; private final MacroStore store;
    private LinearLayout listBox;

    private interface Cb<T> { void ok(T v); }

    public MacroPanel(TermuxActivity a, FrameLayout h) { act = a; host = h; ui = new Ui(a); store = new MacroStore(a); }

    public void build() {
        host.removeAllViews();
        ScrollView sv = new ScrollView(act);
        LinearLayout col = new LinearLayout(act); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(ui.dp(14), ui.dp(24), ui.dp(14), ui.dp(18));
        LinearLayout head = new LinearLayout(act); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.label("Кнопки", 22, ui.text, true); head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = ui.button("＋", true, new Runnable() { @Override public void run() { edit(null); } });
        add.setTextSize(20); head.addView(add, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(44)));
        col.addView(head);
        TextView hint = ui.label("Нажать — выполнить, долго держать — изменить.", 12, ui.sub, false); hint.setPadding(0, ui.dp(4), 0, ui.dp(8)); col.addView(hint);
        listBox = new LinearLayout(act); listBox.setOrientation(LinearLayout.VERTICAL); col.addView(listBox);
        sv.addView(col); host.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        refresh();
    }

    private void refresh() {
        listBox.removeAllViews();
        if (store.macros.isEmpty()) listBox.addView(ui.label("Пока нет кнопок. Нажмите «＋».", 14, ui.sub, false));
        LinearLayout row = null;
        for (int i = 0; i < store.macros.size(); i++) {
            final Macro m = store.macros.get(i);
            if (i % 2 == 0) { row = new LinearLayout(act); listBox.addView(row, ui.gap(8)); }
            TextView b = ui.button(m.label, false, new Runnable() { @Override public void run() { MacroRunner.run(act, m, store); } });
            b.setMaxLines(2); b.setMinHeight(ui.dp(52));
            b.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { options(m); return true; } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(i % 2 == 0 ? 0 : ui.dp(4), 0, i % 2 == 0 ? ui.dp(4) : 0, 0);
            row.addView(b, lp);
        }
    }

    private void options(final Macro m) {
        new AlertDialog.Builder(act).setTitle(m.label).setItems(new String[]{"Изменить", "Дублировать", "Выше", "Ниже", "Удалить"}, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                int i = store.macros.indexOf(m);
                if (w == 0) edit(m);
                else if (w == 1) { Macro c = m.copy(); c.label = m.label + " (копия)"; store.macros.add(i + 1, c); }
                else if (w == 2 && i > 0) { store.macros.remove(i); store.macros.add(i - 1, m); }
                else if (w == 3 && i < store.macros.size() - 1) { store.macros.remove(i); store.macros.add(i + 1, m); }
                else if (w == 4) store.macros.remove(m);
                store.save(); refresh();
            } }).show();
    }

    // ---- editor ------------------------------------------------------------------------------------------------------
    private EditText field(String hint, String value, int type) {
        EditText e = new EditText(act); e.setHint(hint); e.setText(value); e.setInputType(type); e.setSingleLine(type != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE)); return e;
    }

    private void edit(final Macro existing) {
        final boolean isNew = existing == null;
        final Macro m = isNew ? Macro.of("Новая кнопка") : existing.copy();
        if (!isNew) m.id = existing.id;
        LinearLayout v = new LinearLayout(act); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4));
        final EditText name = field("Название кнопки", m.label, InputType.TYPE_CLASS_TEXT);
        final EditText rep = field("Повторить раз", String.valueOf(m.repeat), InputType.TYPE_CLASS_NUMBER);
        final LinearLayout stepsBox = new LinearLayout(act); stepsBox.setOrientation(LinearLayout.VERTICAL);
        v.addView(name); v.addView(rep);
        TextView sh = ui.label("ШАГИ", 12, ui.sub, true); sh.setPadding(0, ui.dp(14), 0, ui.dp(4)); v.addView(sh);
        v.addView(stepsBox);
        renderSteps(m, stepsBox);
        v.addView(ui.button("＋ Добавить шаг", false, new Runnable() { @Override public void run() { addStep(m, stepsBox); } }), ui.gap(10));
        ScrollView sv = new ScrollView(act); sv.addView(v);
        final Runnable sync = new Runnable() { @Override public void run() {
            String n = name.getText().toString().trim(); m.label = n.isEmpty() ? "Кнопка" : n;
            try { m.repeat = Math.max(1, Math.min(99, Integer.parseInt(rep.getText().toString().trim()))); } catch (Exception e) { m.repeat = 1; }
        } };
        final AlertDialog d = new AlertDialog.Builder(act).setTitle(isNew ? "Новая кнопка" : "Изменить кнопку").setView(sv)
            .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface di, int w) {
                sync.run();
                if (isNew) store.macros.add(m); else { int i = store.macros.indexOf(existing); if (i >= 0) store.macros.set(i, m); else store.macros.add(m); }
                store.save(); refresh(); } })
            .setNegativeButton("Отмена", null).setNeutralButton("Проверить", null).create();
        d.show();
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { sync.run(); MacroRunner.run(act, m, store); } });
    }

    private void renderSteps(final Macro m, final LinearLayout box) {
        box.removeAllViews();
        if (m.steps.isEmpty()) box.addView(ui.label("Шагов нет. Добавьте первый.", 13, ui.sub, false));
        for (int i = 0; i < m.steps.size(); i++) {
            final int idx = i; final Macro.Step s = m.steps.get(i);
            LinearLayout r = new LinearLayout(act); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, ui.dp(5), 0, ui.dp(5));
            TextView t = ui.label((i + 1) + ". " + s.describe(), 14, ui.text, false); r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(mini("↑", new Runnable() { @Override public void run() { if (idx > 0) { m.steps.remove(idx); m.steps.add(idx - 1, s); renderSteps(m, box); } } }));
            r.addView(mini("↓", new Runnable() { @Override public void run() { if (idx < m.steps.size() - 1) { m.steps.remove(idx); m.steps.add(idx + 1, s); renderSteps(m, box); } } }));
            r.addView(mini("✕", new Runnable() { @Override public void run() { m.steps.remove(idx); renderSteps(m, box); } }));
            box.addView(r);
        }
    }

    private TextView mini(String s, Runnable r) {
        TextView b = ui.button(s, false, r); b.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(38)); lp.leftMargin = ui.dp(4); b.setLayoutParams(lp); return b;
    }

    // ---- step dialogs ---------------------------------------------------------------------------------------------------
    private void addStep(final Macro m, final LinearLayout box) {
        final Cb<Macro.Step> done = new Cb<Macro.Step>() { @Override public void ok(Macro.Step s) { m.steps.add(s); renderSteps(m, box); } };
        final String[] items = {"Клавиша (нажать / зажать / отпустить)", "Сочетание (Ctrl, Alt, Shift + клавиша)", "Зажать клавишу на N мс", "Пауза, мс",
            "Текст", "Команда (текст + Enter)", "Готовый текст из списка", "Вставить из буфера обмена", "Запустить другой макрос (цепочка)"};
        new AlertDialog.Builder(act).setTitle("Добавить шаг").setItems(items, new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
            switch (w) {
                case 0: pickKey(new Cb<String>() { @Override public void ok(final String k) { pickAction(k, new Cb<String>() { @Override public void ok(String a) { done.ok(Macro.Step.key(k, a)); } }); } }); break;
                case 1: pickCombo(done); break;
                case 2: pickKey(new Cb<String>() { @Override public void ok(final String k) { askNumber("Держать, мс", "1000", new Cb<Integer>() { @Override public void ok(Integer ms) { done.ok(Macro.Step.hold(k, ms)); } }); } }); break;
                case 3: askNumber("Пауза, мс", "500", new Cb<Integer>() { @Override public void ok(Integer ms) { done.ok(Macro.Step.delay(ms)); } }); break;
                case 4: askText("Текст", false, new Cb<String>() { @Override public void ok(String s) { done.ok(Macro.Step.text(s)); } }); break;
                case 5: askText("Команда (Enter добавится)", true, new Cb<String>() { @Override public void ok(String s) { done.ok(Macro.Step.text(s + "\r")); } }); break;
                case 6: pickSnippet(done); break;
                case 7: done.ok(Macro.Step.clip()); break;
                default: pickMacro(m, done);
            }
        } }).show();
    }

    private void pickKey(final Cb<String> cb) {
        final String[] names = MacroRunner.KEYS.keySet().toArray(new String[0]);
        new AlertDialog.Builder(act).setTitle("Клавиша").setItems(names, new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { cb.ok(names[w]); } }).show();
    }

    private void pickAction(String key, final Cb<String> cb) {
        final String[] items = {"Нажать и отпустить", "Зажать (держать)", "Отпустить"};
        final String[] vals = {Macro.Step.TAP, Macro.Step.DOWN, Macro.Step.UP};
        new AlertDialog.Builder(act).setTitle(key).setItems(items, new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { cb.ok(vals[w]); } }).show();
    }

    private void pickCombo(final Cb<Macro.Step> done) {
        LinearLayout v = new LinearLayout(act); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
        final CheckBox c1 = new CheckBox(act), c2 = new CheckBox(act), c3 = new CheckBox(act);
        c1.setText("Ctrl"); c2.setText("Alt"); c3.setText("Shift"); c1.setChecked(true); v.addView(c1); v.addView(c2); v.addView(c3);
        new AlertDialog.Builder(act).setTitle("Модификаторы").setView(v).setNegativeButton("Отмена", null)
            .setPositiveButton("Дальше: клавиша", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                final StringBuilder mods = new StringBuilder();
                if (c1.isChecked()) mods.append("CTRL+"); if (c2.isChecked()) mods.append("ALT+"); if (c3.isChecked()) mods.append("SHIFT+");
                if (mods.length() > 0) mods.setLength(mods.length() - 1);
                pickKey(new Cb<String>() { @Override public void ok(String k) { done.ok(Macro.Step.combo(mods.toString(), k)); } });
            } }).show();
    }

    private void pickSnippet(final Cb<Macro.Step> done) {
        final List<String> labels = new ArrayList<>(); labels.add("＋ Новый готовый текст…");
        for (MacroStore.Snippet s : store.snippets) labels.add(s.name + "  —  " + s.text);
        final AlertDialog d = new AlertDialog.Builder(act).setTitle("Готовый текст (долго держать — удалить)")
            .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface di, int w) {
                if (w == 0) newSnippet(done); else { MacroStore.Snippet s = store.snippets.get(w - 1); done.ok(Macro.Step.text(s.enter ? s.text + "\r" : s.text)); }
            } }).create();
        d.show();
        d.getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() { @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
            if (pos > 0) { store.snippets.remove(pos - 1); store.save(); d.dismiss(); pickSnippet(done); } return true; } });
    }

    private void newSnippet(final Cb<Macro.Step> done) {
        LinearLayout v = new LinearLayout(act); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        final EditText n = field("Название", "", InputType.TYPE_CLASS_TEXT);
        final EditText t = field("Текст или команда", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final CheckBox e = new CheckBox(act); e.setText("Нажать Enter в конце"); e.setChecked(true);
        v.addView(n); v.addView(t); v.addView(e);
        new AlertDialog.Builder(act).setTitle("Новый готовый текст").setView(v).setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить и добавить", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                String text = t.getText().toString(); if (text.isEmpty()) return;
                String name = n.getText().toString().trim(); if (name.isEmpty()) name = text.length() > 24 ? text.substring(0, 24) + "…" : text;
                store.snippets.add(new MacroStore.Snippet(name, text, e.isChecked())); store.save();
                done.ok(Macro.Step.text(e.isChecked() ? text + "\r" : text));
            } }).show();
    }

    private void pickMacro(Macro self, final Cb<Macro.Step> done) {
        final List<Macro> others = new ArrayList<>(); List<String> labels = new ArrayList<>();
        for (Macro o : store.macros) if (!o.id.equals(self.id)) { others.add(o); labels.add(o.label); }
        if (others.isEmpty()) { Toast.makeText(act, "Нет других кнопок для цепочки", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(act).setTitle("Какую кнопку запустить").setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
            Macro o = others.get(w); done.ok(Macro.Step.macro(o.id, o.label)); } }).show();
    }

    private void askNumber(String title, String initial, final Cb<Integer> cb) {
        final EditText e = field(title, initial, InputType.TYPE_CLASS_NUMBER);
        FrameLayout f = new FrameLayout(act); f.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0); f.addView(e);
        new AlertDialog.Builder(act).setTitle(title).setView(f).setNegativeButton("Отмена", null).setPositiveButton("OK", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
            try { cb.ok(Math.max(0, Math.min(60000, Integer.parseInt(e.getText().toString().trim())))); } catch (Exception ex) { Toast.makeText(act, "Нужно число", Toast.LENGTH_SHORT).show(); } } }).show();
    }

    private void askText(String title, boolean command, final Cb<String> cb) {
        final EditText e = field(command ? "Например: ls -la" : "Текст, который нужно напечатать", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        FrameLayout f = new FrameLayout(act); f.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0); f.addView(e);
        new AlertDialog.Builder(act).setTitle(title).setView(f).setNegativeButton("Отмена", null).setPositiveButton("OK", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
            String s = e.getText().toString(); if (!s.isEmpty()) cb.ok(s); } }).show();
    }
}
