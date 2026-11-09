package modsmgr;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.termux.mod.ModLoader;
import com.termux.mod.ModScreen;
import godui.Ui;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import org.json.JSONObject;

public class ModsScreen implements ModScreen {
    Activity act; Ui ui; LinearLayout col; int listStart; File root; final Set<String> changed = new HashSet<>();

    public void onCreate(Activity a, Bundle b) {
        act = a; ui = new Ui(a); root = ModLoader.modsDir(a.getApplication());
        col = ui.page(a, "Моды", "Встроенные модули и ваши плагины",
            ui.button("📁 Папка", false, new Runnable() { public void run() { openFolder(); } }),
            ui.button("⎘ Путь", false, new Runnable() { public void run() { copyPath(); } }),
            ui.button("↻ Рестарт", false, new Runnable() { public void run() { restart(); } }));
        listStart = col.getChildCount(); refresh();
    }

    // ---- data -------------------------------------------------------------------------------------------------------
    static final class Item { String name, title, desc, icon = "🧩", screen, note; boolean builtin, fullscreen = true, toggleable = true, enabled, loaded; }

    List<Item> builtins() {
        List<Item> r = new ArrayList<>();
        try {
            Class<?> bc = Class.forName("com.termux.mod.Builtin", true, act.getClassLoader());
            Object[] all = (Object[]) bc.getField("ALL").get(null);
            Set<String> off = ModLoader.class.getMethod("disabledBuiltins", android.app.Application.class).invoke(null, act.getApplication()) instanceof Set
                ? (Set<String>) ModLoader.class.getMethod("disabledBuiltins", android.app.Application.class).invoke(null, act.getApplication()) : new HashSet<String>();
            for (Object e : all) { Item i = new Item(); i.builtin = true;
                i.name = (String) f(e, "name"); i.title = (String) f(e, "title"); i.desc = (String) f(e, "desc"); i.screen = (String) f(e, "screen");
                i.fullscreen = (Boolean) f(e, "fullscreen"); i.toggleable = (Boolean) f(e, "toggleable");
                try { i.icon = (String) f(e, "icon"); i.note = (String) f(e, "note"); } catch (Throwable ignored) {}
                i.enabled = !off.contains(i.name);
                i.loaded = (Boolean) ModLoader.class.getMethod("builtinLoaded", String.class).invoke(null, i.name);
                r.add(i); }
        } catch (Throwable t) { /* running as a plain external mod: no built-in registry */ }
        return r;
    }

    static Object f(Object o, String n) throws Exception { Field x = o.getClass().getField(n); return x.get(o); }

    List<Item> externals() {
        List<Item> r = new ArrayList<>(); File[] dirs = root.listFiles(); if (dirs == null) return r; Arrays.sort(dirs);
        for (File d : dirs) {
            if (!d.isDirectory() || d.getName().startsWith(".")) continue; Item i = new Item(); i.name = d.getName(); i.title = i.name; i.desc = "";
            try { JSONObject j = new JSONObject(readAll(new File(d, "mod.json"))); i.title = j.optString("title", i.name); i.desc = j.optString("description", "");
                i.screen = j.optString("screen", null); i.fullscreen = j.optBoolean("fullscreen", true); i.icon = j.optString("icon", "🧩"); } catch (Throwable ignored) {}
            i.enabled = !new File(d, "disabled").exists(); i.loaded = ModLoader.loader(i.name) != null; r.add(i);
        }
        return r;
    }

    // ---- ui ---------------------------------------------------------------------------------------------------------
    void refresh() {
        while (col.getChildCount() > listStart) col.removeViewAt(listStart);
        if (!changed.isEmpty()) {
            LinearLayout b = ui.card(col); b.setBackground(ui.rect((ui.warn & 0x00FFFFFF) | 0x22000000, 16, (ui.warn & 0x00FFFFFF) | 0x66000000));
            b.addView(ui.label("Есть изменения", 15, ui.warn, true));
            b.addView(ui.label("Они вступят в силу после перезапуска Termux.", 13, ui.text, false));
            b.addView(ui.button("Перезапустить сейчас", true, new Runnable() { public void run() { restart(); } }), ui.gap(10));
        }
        ui.section(col, "Встроенные");
        List<Item> bs = builtins(); if (bs.isEmpty()) col.addView(ui.label("Реестр встроенных модулей недоступен в этом режиме", 13, ui.sub, false));
        for (Item i : bs) card(i);
        ui.section(col, "Свои моды");
        List<Item> es = externals();
        for (Item i : es) card(i);
        if (es.isEmpty()) {
            LinearLayout e = ui.card(col); e.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView ic = ui.label("🗂", 30, ui.text, false); ic.setGravity(Gravity.CENTER); e.addView(ic);
            TextView t = ui.label("Своих модов пока нет", 15, ui.text, true); t.setGravity(Gravity.CENTER); e.addView(t);
            TextView s = ui.label("Положите папку с модом (mod.json, classes.dex, lib/) в папку модов и перезапустите Termux.", 13, ui.sub, false); s.setGravity(Gravity.CENTER); s.setPadding(0, ui.dp(4), 0, ui.dp(10)); e.addView(s);
            e.addView(ui.button("Открыть папку", false, new Runnable() { public void run() { openFolder(); } }));
        }
    }

    View wrap(LinearLayout l) { return l; }

    LinearLayout card(final Item it) {
        LinearLayout c = ui.card(col);
        LinearLayout top = new LinearLayout(act); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = ui.badge(it.icon == null ? "🧩" : it.icon); top.addView(badge);
        LinearLayout mid = new LinearLayout(act); mid.setOrientation(LinearLayout.VERTICAL); mid.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        mid.addView(ui.label(it.title, 16, ui.text, true));
        if (it.desc != null && !it.desc.isEmpty()) mid.addView(ui.label(it.desc, 13, ui.sub, false));
        String key = (it.builtin ? "builtin:" : "") + it.name; String cs; int cc;
        if (it.note != null && !it.note.isEmpty()) { cs = it.note; cc = ui.accent; }
        else if (!it.enabled) { cs = changed.contains(key) ? "Выключится после перезапуска" : "Выключен"; cc = changed.contains(key) ? ui.warn : ui.sub; }
        else if (it.loaded) { cs = "Работает"; cc = ui.ok; }
        else { cs = changed.contains(key) ? "Включится после перезапуска" : "Ждёт перезапуска"; cc = ui.warn; }
        TextView chip = ui.chip(cs, cc); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = ui.dp(6); mid.addView(chip, lp);
        top.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        if (it.toggleable && (it.note == null || it.note.isEmpty())) {
            Switch sw = new Switch(act); sw.setChecked(it.enabled);
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton v, boolean on) { toggle(it, on); } });
            top.addView(sw);
        }
        c.addView(top);
        boolean canOpen = it.screen != null && !it.screen.isEmpty() && it.enabled && it.loaded;
        if (canOpen) c.addView(ui.button("Открыть", true, new Runnable() { public void run() { open(it); } }), ui.gap(12));
        return c;
    }

    void toggle(Item it, boolean on) {
        try {
            if (it.builtin) ModLoader.class.getMethod("setBuiltinEnabled", android.app.Application.class, String.class, boolean.class).invoke(null, act.getApplication(), it.name, on);
            else { File f = new File(new File(root, it.name), "disabled"); if (on) f.delete(); else f.createNewFile(); }
            changed.add((it.builtin ? "builtin:" : "") + it.name);
        } catch (Throwable e) { Toast.makeText(act, "Не удалось: " + e, Toast.LENGTH_LONG).show(); }
        refresh();
    }

    void open(Item it) {
        Intent i = new Intent(Intent.ACTION_VIEW).setClassName(act.getPackageName(), "com.termux.mod.ModActivity")
            .putExtra("class", it.screen).putExtra("fullscreen", it.fullscreen).addFlags(0x18080000);
        if (!it.builtin) i.putExtra("mod", it.name);
        act.startActivity(i);
    }

    void openFolder() {
        try {
            Uri u = DocumentsContract.buildDocumentUri("com.termux.documents", root.getAbsolutePath());
            act.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) { Toast.makeText(act, "Файловый менеджер не открыл папку. Путь: " + root, Toast.LENGTH_LONG).show(); }
    }

    void copyPath() {
        ((ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("mods", root.getAbsolutePath()));
        Toast.makeText(act, "Путь скопирован: " + root, Toast.LENGTH_SHORT).show();
    }

    void restart() {
        try {
            Intent li = act.getPackageManager().getLaunchIntentForPackage(act.getPackageName());
            PendingIntent pi = PendingIntent.getActivity(act, 0, li, PendingIntent.FLAG_CANCEL_CURRENT | (1 << 26));
            ((AlarmManager) act.getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi);
            Toast.makeText(act, "Перезапуск… если Termux не открылся, откройте его вручную", Toast.LENGTH_LONG).show();
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable e) { Toast.makeText(act, "Не удалось: " + e, Toast.LENGTH_LONG).show(); }
    }

    static String readAll(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toString("UTF-8"); }
    }

    public void onResume() { if (col != null) refresh(); }
}
