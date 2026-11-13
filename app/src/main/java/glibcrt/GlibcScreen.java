package glibcrt;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import com.termux.mod.ModScreen;
import godui.Ui;
import java.io.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

public class GlibcScreen implements ModScreen {
    Activity act; Ui ui; LinearLayout col, statusCard; TextView log; EditText pkg; File prefix, home, shimDir, debian; volatile boolean running, destroyed;
    final Handler handler = new Handler(Looper.getMainLooper());
    final ArrayDeque<String> output = new ArrayDeque<>();
    int outputChars; boolean outputPosted; String outputTitle;
    final Runnable flushOutput = new Runnable() { public void run() {
        String text;
        synchronized (output) { outputPosted = false; text = "$ " + outputTitle + "\n" + join(output); }
        if (!destroyed) { log.setVisibility(View.VISIBLE); log.setText(text); }
    } };

    void appendOutput(String line) {
        // Bound both queued text and UI work, including commands with very long lines.
        if (line.length() > 16384) line = line.substring(line.length() - 16384);
        synchronized (output) {
            if (destroyed) return;
            output.addLast(line); outputChars += line.length() + 1;
            while (output.size() > 250 || outputChars > 65536) outputChars -= output.removeFirst().length() + 1;
            if (!outputPosted) { outputPosted = true; handler.postDelayed(flushOutput, 100); }
        }
    }

    public void onCreate(Activity a, Bundle b) {
        act = a; ui = new Ui(a); File files = a.getFilesDir(); prefix = new File(files, "usr"); home = new File(files, "home");
        shimDir = new File(prefix, "glibc/shim"); debian = new File(prefix, "debian");
        col = ui.page(a, "glibc и Debian", "Запуск glibc-программ без runner'ов · apt для Debian (arm64)");
        statusCard = ui.card(col);

        LinearLayout sh = ui.card(col);
        sh.addView(ui.label("Совместимость", 16, ui.text, true));
        sh.addView(ui.label("Шим подменяет /lib, /usr, /bin, /etc и запуск glibc-бинарников. Применяется к новым сессиям.", 13, ui.sub, false));
        final TextView tog = ui.button("", false, new Runnable() { public void run() { toggleShim(); } });
        tog.setTag("tog"); sh.addView(tog, ui.gap(10));

        LinearLayout deb = ui.card(col);
        deb.addView(ui.label("Debian (apt)", 16, ui.text, true));
        deb.addView(ui.label("Отдельное окружение $PREFIX/debian, не затрагивает пакеты Termux. Команда: dapt.", 13, ui.sub, false));
        deb.addView(ui.button("Инициализировать", true, new Runnable() { public void run() { exec("init", "dapt init", "dapt", "init"); } }), ui.gap(10));
        deb.addView(ui.button("Обновить индексы", false, new Runnable() { public void run() { exec("update", "dapt update", "dapt", "update"); } }), ui.gap(8));
        deb.addView(ui.button("Установленные пакеты", false, new Runnable() { public void run() { exec("list", "dapt list", "dapt", "list", "--installed"); } }), ui.gap(8));
        pkg = new EditText(a); pkg.setHint("Имя пакета (например jq)"); pkg.setSingleLine(true); pkg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pkg.setTextColor(ui.text); pkg.setHintTextColor(ui.sub); pkg.setBackground(ui.rect(ui.dark ? 0xFF14161A : 0xFFF0F2F5, 12, 0)); pkg.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        deb.addView(pkg, ui.gap(14));
        LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(ui.button("Поиск", false, new Runnable() { public void run() { pk("search"); } }), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.leftMargin = ui.dp(8);
        r.addView(ui.button("Установить", true, new Runnable() { public void run() { pk("install"); } }), lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1); lp2.leftMargin = ui.dp(8);
        r.addView(ui.button("Удалить", false, new Runnable() { public void run() { pk("remove"); } }), lp2);
        deb.addView(r, ui.gap(8));
        log = ui.mono(deb);
        refresh();
    }

    void pk(String verb) {
        String n = pkg.getText().toString().trim();
        if (!n.matches("[a-zA-Z0-9][a-zA-Z0-9.+\\-:]*")) { show("Введите корректное имя пакета"); return; }
        if (verb.equals("search")) exec("search", "dapt search " + n, "dapt", "search", n);
        else exec(verb, "dapt " + verb + " " + n, "dapt", verb, "-y", n);
    }

    void show(final String s) { act.runOnUiThread(new Runnable() { public void run() { if (!destroyed) { log.setVisibility(View.VISIBLE); log.setText(s); } } }); }

    void exec(final String id, final String title, final String... cmd) {
        if (running) { show("Подождите: предыдущая команда ещё выполняется"); return; }
        running = true;
        synchronized (output) { handler.removeCallbacks(flushOutput); outputPosted = false; output.clear(); outputChars = 0; outputTitle = title; }
        show("$ " + title + "\n");
        new Thread(new Runnable() { public void run() {
            try {
                String[] full = cmd.clone(); full[0] = new File(prefix, "bin/" + cmd[0]).getAbsolutePath();
                ProcessBuilder pb = new ProcessBuilder(full).redirectErrorStream(true);
                Map<String, String> e = pb.environment(); e.put("HOME", home.getAbsolutePath()); e.put("PREFIX", prefix.getAbsolutePath());
                e.put("TMPDIR", new File(prefix, "tmp").getAbsolutePath()); e.put("PATH", prefix + "/bin"); e.put("LANG", "C.UTF-8");
                File bs = new File(shimDir, "libfhs-bionic.so"); if (bs.exists() && !new File(home, ".termux/fhs.off").exists()) e.put("LD_PRELOAD", bs.getAbsolutePath());
                Process p = pb.start();
                try (BufferedReader rd = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String l; while ((l = rd.readLine()) != null) appendOutput(l);
                }
                int rc = p.waitFor();
                synchronized (output) {
                    handler.removeCallbacks(flushOutput); outputPosted = false;
                    show("$ " + title + "\n" + join(output) + "\n— завершено, код " + rc);
                }
            } catch (Throwable t) { synchronized (output) { handler.removeCallbacks(flushOutput); outputPosted = false; } show("Ошибка: " + t); }
            running = false; refresh();
        } }).start();
    }

    static String join(Collection<String> l) { StringBuilder sb = new StringBuilder(); for (String s : l) sb.append(s).append('\n'); return sb.toString().trim(); }

    // ---- status ---------------------------------------------------------------------------------------------------
    String shimState() {
        File sums = new File(shimDir, "SHA256SUMS"); if (!sums.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(sums))) { String l; int n = 0;
            while ((l = r.readLine()) != null) { String[] p = l.trim().split("\\s+"); if (p.length < 2) continue; if (!sha(new File(shimDir, p[1])).equals(p[0])) return "bad:" + p[1]; n++; }
            return n > 0 ? "ok:" + n : null; } catch (Throwable t) { return "bad:" + t; }
    }

    static String sha(File f) throws Exception { MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        char[] hex = "0123456789abcdef".toCharArray(); byte[] digest = md.digest(); char[] result = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) { int x = digest[i] & 255; result[i * 2] = hex[x >>> 4]; result[i * 2 + 1] = hex[x & 15]; }
        return new String(result); }

    int countPkgs() { int n = 0; try (BufferedReader r = new BufferedReader(new FileReader(new File(debian, "var/lib/dpkg/status")))) { String l; while ((l = r.readLine()) != null) if (l.startsWith("Package: ")) n++; } catch (Throwable ignored) {} return n; }

    String indexAge() {
        File lists = new File(debian, "var/lib/apt/lists"); long best = 0; File[] fs = lists.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().contains("Packages")) best = Math.max(best, f.lastModified());
        return best == 0 ? null : new SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("ru")).format(new Date(best));
    }

    void refresh() {
        if (destroyed) return;
        new Thread(new Runnable() { public void run() {
            final String sh = shimState(); final boolean off = new File(home, ".termux/fhs.off").exists();
            final boolean profile = new File(prefix, "etc/profile.d/glibc-fhs.sh").exists(); final boolean glibc = new File(prefix, "glibc/lib/libc.so.6").exists();
            final boolean init = new File(debian, "etc/apt/apt.conf").exists(); final int np = init ? countPkgs() : 0; final String age = init ? indexAge() : null;
            act.runOnUiThread(new Runnable() { public void run() {
                if (destroyed) return;
                statusCard.removeAllViews(); statusCard.addView(ui.label("Состояние", 16, ui.text, true));
                ui.row(statusCard, "glibc", glibc ? "найден ($PREFIX/glibc/lib)" : "не найден: pkg install glibc-repo glibc", glibc ? ui.ok : ui.err);
                String s1; int c1; if (sh == null) { s1 = "не установлен"; c1 = ui.err; } else if (sh.startsWith("bad")) { s1 = "хэши не совпали: " + sh.substring(4); c1 = ui.err; } else { s1 = "установлен, хэши совпадают (" + sh.substring(3) + " файла)"; c1 = ui.ok; }
                ui.row(statusCard, "Шим (запуск и пути)", s1, c1);
                ui.row(statusCard, "Автоподключение", !profile ? "нет profile.d-скрипта" : off ? "выключено" : "включено для новых сессий", !profile ? ui.err : off ? ui.warn : ui.ok);
                ui.row(statusCard, "Debian", init ? np + " пакетов (arm64)" : "не инициализирован", init ? ui.ok : ui.warn);
                ui.row(statusCard, "Индексы Debian", age == null ? "нет — нажмите «Обновить индексы»" : "обновлены " + age, age == null ? ui.warn : ui.ok);
                TextView t = (TextView) col.findViewWithTag("tog"); if (t != null) t.setText(off ? "Включить шим для новых сессий" : "Выключить шим для новых сессий");
            } });
        } }).start();
    }

    void toggleShim() {
        File f = new File(home, ".termux/fhs.off");
        try { if (f.exists()) f.delete(); else { f.getParentFile().mkdirs(); f.createNewFile(); } show(f.exists() ? "Шим выключен: действует в новых сессиях" : "Шим включён: действует в новых сессиях"); }
        catch (Throwable t) { show("Ошибка: " + t); }
        refresh();
    }

    public void onDestroy() {
        synchronized (output) { destroyed = true; handler.removeCallbacks(flushOutput); output.clear(); outputChars = 0; }
    }

    public void onResume() { if (statusCard != null) refresh(); }
}
