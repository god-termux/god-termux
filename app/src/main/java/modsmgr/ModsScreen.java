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
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public class ModsScreen implements ModScreen {
    Activity act; Ui ui; LinearLayout col; int listStart; File root; final Set<String> changed = new HashSet<>();
    static final String CATALOG_URL = "https://god-termux.github.io/mods/index.json";
    static final class Cat { String name, title, desc, icon, url, sha; int version; long size; }
    List<Cat> catalog; String catStatus = "Каталог не загружен"; boolean catBusy; String catBase;

    public void onCreate(Activity a, Bundle b) {
        act = a; ui = new Ui(a); root = ModLoader.modsDir(a.getApplication());
        col = ui.page(a, "Моды", "Встроенные модули и ваши плагины",
            ui.button("📁 Папка", false, new Runnable() { public void run() { openFolder(); } }),
            ui.button("⎘ Путь", false, new Runnable() { public void run() { copyPath(); } }),
            ui.button("↻ Рестарт", false, new Runnable() { public void run() { restart(); } }));
        listStart = col.getChildCount(); refresh(); loadCatalog();
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
        catalogSection();
    }

    // ---- catalog (github.com/god-termux/mods) ----------------------------------------------------------------------
    void catalogSection() {
        ui.section(col, "Каталог модов");
        LinearLayout c = ui.card(col);
        c.addView(ui.label(catStatus, 13, ui.sub, false));
        c.addView(ui.button(catBusy ? "Подождите…" : "↻ Обновить каталог", false, new Runnable() { public void run() { loadCatalog(); } }), ui.gap(8));
        if (catalog == null) return;
        for (final Cat m : catalog) {
            int inst = installedVersion(m.name); boolean builtin = isBuiltin(m.name);
            LinearLayout k = ui.card(col); LinearLayout top = new LinearLayout(act); top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(ui.badge(m.icon));
            LinearLayout mid = new LinearLayout(act); mid.setOrientation(LinearLayout.VERTICAL); mid.setPadding(ui.dp(12), 0, ui.dp(8), 0);
            mid.addView(ui.label(m.title, 16, ui.text, true)); if (!m.desc.isEmpty()) mid.addView(ui.label(m.desc, 13, ui.sub, false));
            String cs; int cc;
            if (builtin) { cs = "Уже встроен в приложение"; cc = ui.sub; }
            else if (inst < 0) { cs = "Не установлен · v" + m.version + " · " + (m.size / 1024 + 1) + " КБ"; cc = ui.sub; }
            else if (inst < m.version) { cs = "Доступно обновление: v" + inst + " → v" + m.version; cc = ui.warn; }
            else { cs = "Установлен · v" + inst; cc = ui.ok; }
            if (changed.contains(m.name)) { cs = "Установлен, вступит в силу после перезапуска"; cc = ui.warn; }
            TextView chip = ui.chip(cs, cc); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = ui.dp(6); mid.addView(chip, lp);
            top.addView(mid, new LinearLayout.LayoutParams(0, -2, 1)); k.addView(top);
            if (!builtin && (inst < 0 || inst < m.version) && !catBusy)
                k.addView(ui.button(inst < 0 ? "Установить" : "Обновить", true, new Runnable() { public void run() { confirmInstall(m); } }), ui.gap(12));
        }
    }

    boolean isBuiltin(String n) { for (Item i : builtins()) if (i.name.equals(n)) return true; return false; }

    int installedVersion(String name) {
        try { return new JSONObject(readAll(new File(new File(root, name), "mod.json"))).optInt("version", 0); } catch (Throwable t) { return -1; }
    }

    String catalogUrl() {
        try { File f = new File(act.getFilesDir(), "home/.termux/mods-catalog-url"); if (f.exists()) { String u = readAll(f).trim(); if (u.startsWith("https://")) return u; } } catch (Throwable ignored) {}
        return CATALOG_URL;
    }

    static byte[] fetch(String url, long max) throws Exception {
        if (!url.startsWith("https://")) throw new IOException("только https: " + url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "god-termux-mods");
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() > max) throw new IOException("слишком большой файл"); } return o.toByteArray(); }
    }

    void loadCatalog() {
        if (catBusy) return; catBusy = true; catStatus = "Загрузка каталога…"; refresh();
        new Thread(new Runnable() { public void run() {
            String st; List<Cat> list = null;
            try {
                String url = catalogUrl(); JSONObject j = new JSONObject(new String(fetch(url, 1 << 20), "UTF-8"));
                if (j.optInt("format") != 1) throw new IOException("неизвестный формат каталога");
                list = new ArrayList<>(); JSONArray a = j.getJSONArray("mods");
                for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); Cat m = new Cat();
                    m.name = o.getString("name"); if (!m.name.matches("[a-z0-9][a-z0-9_-]{0,40}")) continue;
                    m.title = o.optString("title", m.name); m.desc = o.optString("description", ""); m.icon = o.optString("icon", "🧩");
                    m.url = o.getString("url"); m.sha = o.getString("sha256").toLowerCase(); m.version = o.getInt("version"); m.size = o.optLong("size", 0); list.add(m); }
                st = "Каталог: " + list.size() + " " + (list.size() == 1 ? "мод" : "мода/модов") + " · " + url;
            } catch (Throwable t) { st = "Каталог недоступен: " + t.getMessage(); list = null; }
            final String fs = st; final List<Cat> fl = list;
            act.runOnUiThread(new Runnable() { public void run() { catBusy = false; catStatus = fs; if (fl != null) catalog = fl; refresh(); } });
        } }).start();
    }

    void confirmInstall(final Cat m) {
        new android.app.AlertDialog.Builder(act).setTitle("Установить «" + m.title + "»?")
            .setMessage("Мод — это код, который выполняется внутри Termux с его правами и доступом к его данным. Ставьте только то, чему доверяете.\n\nИсточник: " + m.url)
            .setPositiveButton("Установить", new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int w) { install(m); } })
            .setNegativeButton("Отмена", null).show();
    }

    void install(final Cat m) {
        catBusy = true; catStatus = "Скачивание " + m.name + "…"; refresh();
        new Thread(new Runnable() { public void run() {
            String st;
            try { installZip(m); changed.add(m.name); st = "«" + m.title + "» установлен (v" + m.version + "). Перезапустите Termux"; }
            catch (Throwable t) { st = "Не удалось установить " + m.name + ": " + t.getMessage(); }
            final String fs = st;
            act.runOnUiThread(new Runnable() { public void run() { catBusy = false; catStatus = fs; refresh(); } });
        } }).start();
    }

    void installZip(Cat m) throws Exception {
        byte[] z = fetch(m.url, 64L << 20);
        if (m.size > 0 && z.length != m.size) throw new IOException("размер не совпал с каталогом");
        byte[] h = MessageDigest.getInstance("SHA-256").digest(z); StringBuilder hx = new StringBuilder(); for (byte b : h) hx.append(String.format("%02x", b));
        if (!hx.toString().equals(m.sha)) throw new IOException("контрольная сумма не совпала");
        File tmp = new File(root, "." + m.name + ".new"); deleteTree(tmp); if (!tmp.mkdirs()) throw new IOException("нет доступа к " + root);
        try {
            String tmpPath = tmp.getCanonicalPath() + File.separator; boolean hasJson = false; long total = 0;
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(z))) {
                ZipEntry e; while ((e = in.getNextEntry()) != null) {
                    String n = e.getName(); if (e.isDirectory()) continue;
                    if (!(n.equals("mod.json") || n.equals("classes.dex") || n.startsWith("lib/") || n.startsWith("assets/"))) throw new IOException("лишний файл в архиве: " + n);
                    File out = new File(tmp, n); if (!out.getCanonicalPath().startsWith(tmpPath)) throw new IOException("недопустимый путь: " + n);
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) { byte[] b = new byte[8192]; int r; while ((r = in.read(b)) > 0) { total += r; if (total > (128L << 20)) throw new IOException("архив слишком велик"); os.write(b, 0, r); } }
                    if (n.equals("mod.json")) hasJson = true;
                }
            }
            if (!hasJson) throw new IOException("в архиве нет mod.json");
            JSONObject j = new JSONObject(readAll(new File(tmp, "mod.json")));
            if (!m.name.equals(j.optString("name")) || j.optInt("version") != m.version) throw new IOException("mod.json не совпадает с каталогом");
            File dst = new File(root, m.name); File keep = new File(dst, "disabled"); boolean wasOff = keep.exists();
            deleteTree(dst); if (!tmp.renameTo(dst)) throw new IOException("не удалось переименовать каталог мода");
            if (wasOff) new File(dst, "disabled").createNewFile();
        } finally { deleteTree(tmp); }
    }

    static void deleteTree(File f) { File[] k = f.listFiles(); if (k != null) for (File x : k) deleteTree(x); f.delete(); }

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
