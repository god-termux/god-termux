package com.termux.mod.macros;

import android.content.Context;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persists macros and ready-made texts ("snippets") in ~/.termux/god-macros.json (human-editable). Seeds presets on first run. */
public final class MacroStore {
    public static final class Snippet {
        public String name, text; public boolean enter;
        public Snippet(String n, String t, boolean e) { name = n; text = t; enter = e; }
    }

    private final File file;
    public final List<Macro> macros = new ArrayList<>();
    public final List<Snippet> snippets = new ArrayList<>();

    public MacroStore(Context c) {
        file = new File(c.getFilesDir(), "home/.termux/god-macros.json");
        load();
    }

    public Macro find(String id) { for (Macro m : macros) if (m.id.equals(id)) return m; return null; }

    private void load() {
        macros.clear(); snippets.clear();
        if (!file.exists()) { seed(); save(); return; }
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            JSONObject root = new JSONObject(o.toString("UTF-8"));
            JSONArray a = root.optJSONArray("macros");
            if (a != null) for (int i = 0; i < a.length(); i++) macros.add(Macro.fromJson(a.getJSONObject(i)));
            JSONArray sn = root.optJSONArray("snippets");
            if (sn != null) for (int i = 0; i < sn.length(); i++) { JSONObject s = sn.getJSONObject(i); snippets.add(new Snippet(s.optString("name"), s.optString("text"), s.optBoolean("enter", true))); }
            else seedSnippets();
        } catch (Throwable t) { macros.clear(); snippets.clear(); seed(); }
    }

    public void save() {
        try {
            file.getParentFile().mkdirs();
            JSONArray a = new JSONArray(); for (Macro m : macros) a.put(m.toJson());
            JSONArray sn = new JSONArray(); for (Snippet s : snippets) sn.put(new JSONObject().put("name", s.name).put("text", s.text).put("enter", s.enter));
            try (FileWriter w = new FileWriter(file)) { w.write(new JSONObject().put("macros", a).put("snippets", sn).toString(2)); }
        } catch (Throwable ignored) {}
    }

    private static Macro combo(String label, String mod, String key) {
        return Macro.of(label, Macro.Step.combo(mod, key));
    }

    private void seed() {
        macros.add(combo("Ctrl+C", "CTRL", "C")); macros.add(combo("Ctrl+D", "CTRL", "D"));
        macros.add(combo("Ctrl+Z", "CTRL", "Z")); macros.add(combo("Ctrl+L", "CTRL", "L"));
        macros.add(Macro.of("Esc", Macro.Step.key("ESC", Macro.Step.TAP))); macros.add(Macro.of("Tab", Macro.Step.key("TAB", Macro.Step.TAP)));
        seedSnippets();
    }

    private void seedSnippets() {
        snippets.add(new Snippet("Список файлов", "ls -la", true)); snippets.add(new Snippet("В домашнюю папку", "cd ~", true));
        snippets.add(new Snippet("Очистить экран", "clear", true)); snippets.add(new Snippet("Обновить пакеты", "pkg update && pkg upgrade -y", true));
        snippets.add(new Snippet("git status", "git status", true)); snippets.add(new Snippet("git pull", "git pull", true));
        snippets.add(new Snippet("Место на диске", "df -h", true)); snippets.add(new Snippet("Память", "free -h", true));
        snippets.add(new Snippet("Мои IP-адреса", "ip -brief addr", true)); snippets.add(new Snippet("Проверка сети", "ping -c 3 1.1.1.1", true));
    }
}
