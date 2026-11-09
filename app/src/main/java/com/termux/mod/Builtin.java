package com.termux.mod;

/** Mods that ship inside the god-termux APK. Third-party mods live in ~/.termux/mods (see ModLoader). */
public final class Builtin {
    public static final class Entry {
        public final String name, icon, title, desc, screen, note; public final boolean fullscreen, toggleable; public final Class<? extends Mod> mod;
        Entry(String name, String icon, String title, String desc, String screen, boolean fullscreen, boolean toggleable, Class<? extends Mod> mod, String note) {
            this.name = name; this.icon = icon; this.title = title; this.desc = desc; this.screen = screen; this.fullscreen = fullscreen;
            this.toggleable = toggleable; this.mod = mod; this.note = note; }
    }
    public static final Entry[] ALL = {
        new Entry("modsmgr", "🧩", "Моды", "Список модов, включение и выключение, папка с модами", "modsmgr.ModsScreen", false, false, modsmgr.ModsMgr.class, null),
        new Entry("adbfind", "🔑", "Привилегированный шелл", "Встроенный ADB вместо Shizuku (команда gsh)", "adbfind.AdbScreen", false, true, adbfind.AdbFind.class, null),
        new Entry("glibcrt", "🐧", "glibc и Debian", "Запуск glibc-программ, FHS-пути, apt для Debian (команда dapt)", "glibcrt.GlibcScreen", false, true, glibcrt.GlibcRt.class, null),
        new Entry("gldemo", "🎮", "GL demo", "Окно с EGL-контекстом на GPU", "gldemo.GlScreen", true, true, null, null),
        new Entry("wlcomp", "🪟", "Wayland-композитор", "Приложения GTK прямо в окне, без X11 и VNC", null, true, false, null, "В разработке"),
    };
    private Builtin() {}
}
