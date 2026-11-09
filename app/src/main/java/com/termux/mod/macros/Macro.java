package com.termux.mod.macros;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** A macro: a list of steps repeated N times. Steps: key (tap/down/up), combo, hold, pause, text, command, clipboard, other macro (chains). */
public final class Macro {
    public String id, label; public int repeat = 1;
    public final List<Step> steps = new ArrayList<>();

    public static final class Step {
        public static final String KEY = "key", COMBO = "combo", HOLD = "hold", DELAY = "delay", TEXT = "text", CLIP = "clip", MACRO = "macro";
        public static final String TAP = "tap", DOWN = "down", UP = "up";
        /** t: type; k: key (or cached label for MACRO); a: tap/down/up; s: text (or macro id); ms: pause/hold time; mods: "CTRL+ALT" for COMBO. */
        public String t = KEY, k = "", a = TAP, s = "", mods = ""; public int ms = 100;

        public static Step key(String k, String a) { Step x = new Step(); x.t = KEY; x.k = k; x.a = a; return x; }
        public static Step delay(int ms) { Step x = new Step(); x.t = DELAY; x.ms = ms; return x; }
        public static Step text(String txt) { Step x = new Step(); x.t = TEXT; x.s = txt; return x; }
        public static Step combo(String mods, String k) { Step x = new Step(); x.t = COMBO; x.mods = mods; x.k = k; return x; }
        public static Step hold(String k, int ms) { Step x = new Step(); x.t = HOLD; x.k = k; x.ms = ms; return x; }
        public static Step clip() { Step x = new Step(); x.t = CLIP; return x; }
        public static Step macro(String id, String label) { Step x = new Step(); x.t = MACRO; x.s = id; x.k = label; return x; }

        public String describe() {
            if (DELAY.equals(t)) return "Пауза " + ms + " мс";
            if (TEXT.equals(t)) {
                boolean enter = s.endsWith("\r");
                String shown = (enter ? s.substring(0, s.length() - 1) : s).replace("\r", "⏎").replace("\n", "⏎");
                return (enter ? "Команда: " : "Текст: ") + shown;
            }
            if (COMBO.equals(t)) return "Сочетание " + (mods.isEmpty() ? "" : mods + "+") + k;
            if (HOLD.equals(t)) return k + "  ⏱ держать " + ms + " мс";
            if (CLIP.equals(t)) return "Вставить из буфера обмена";
            if (MACRO.equals(t)) return "Запустить: " + k;
            String act = DOWN.equals(a) ? "↓ зажать" : UP.equals(a) ? "↑ отпустить" : "⇅ нажать";
            return k + "  " + act;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject(); o.put("t", t);
            if (DELAY.equals(t)) o.put("ms", ms);
            else if (TEXT.equals(t)) o.put("s", s);
            else if (COMBO.equals(t)) { o.put("mods", mods); o.put("k", k); }
            else if (HOLD.equals(t)) { o.put("k", k); o.put("ms", ms); }
            else if (MACRO.equals(t)) { o.put("s", s); o.put("k", k); }
            else if (!CLIP.equals(t)) { o.put("k", k); o.put("a", a); }
            return o;
        }

        static Step fromJson(JSONObject o) {
            Step x = new Step(); x.t = o.optString("t", KEY); x.k = o.optString("k", ""); x.a = o.optString("a", TAP);
            x.s = o.optString("s", ""); x.ms = o.optInt("ms", 100); x.mods = o.optString("mods", ""); return x;
        }

        Step copy() { Step n = new Step(); n.t = t; n.k = k; n.a = a; n.s = s; n.ms = ms; n.mods = mods; return n; }
    }

    public static Macro of(String label, Step... st) {
        Macro m = new Macro(); m.id = String.valueOf(System.nanoTime()); m.label = label;
        for (Step s : st) m.steps.add(s); return m;
    }

    public Macro copy() {
        Macro c = new Macro(); c.id = String.valueOf(System.nanoTime()); c.label = label; c.repeat = repeat;
        for (Step s : steps) c.steps.add(s.copy()); return c;
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject(); o.put("id", id); o.put("label", label); o.put("repeat", repeat);
        JSONArray a = new JSONArray(); for (Step s : steps) a.put(s.toJson()); o.put("steps", a); return o;
    }

    static Macro fromJson(JSONObject o) {
        Macro m = new Macro(); m.id = o.optString("id", String.valueOf(System.nanoTime())); m.label = o.optString("label", "?"); m.repeat = Math.max(1, o.optInt("repeat", 1));
        JSONArray a = o.optJSONArray("steps"); if (a != null) for (int i = 0; i < a.length(); i++) m.steps.add(Step.fromJson(a.optJSONObject(i)));
        return m;
    }
}
