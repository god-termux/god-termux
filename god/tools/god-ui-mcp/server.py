#!/data/data/com.termux/files/usr/bin/python3
"""god-ui: MCP server (stdio, no dependencies) that lets Claude look at and drive the phone's UI through ADB.
Uses the Wireless-debugging endpoint published by god-termux ($PREFIX/tmp/adb-connect-port) or any adb device.
Tools: screenshot, ui_tree, tap, tap_text, swipe, key, type_text, open, launch, logcat, screen_info, wait."""
import base64, json, os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

PREFIX = os.environ.get("PREFIX", "/data/data/com.termux/files/usr")
PORT_FILE = os.path.join(PREFIX, "tmp", "adb-connect-port")
PKG = "com.termux"
SCREENS = {  # name -> (component, extras)
    "termux":   (f"{PKG}/.app.TermuxActivity", ""),
    "settings": (f"{PKG}/.app.activities.SettingsActivity", ""),
    "mods":     (f"{PKG}/com.termux.mod.ModActivity", "--es class modsmgr.ModsScreen --ez fullscreen false"),
    "shell":    (f"{PKG}/com.termux.mod.ModActivity", "--es class adbfind.AdbScreen --ez fullscreen false"),
    "glibc":    (f"{PKG}/com.termux.mod.ModActivity", "--es class glibcrt.GlibcScreen --ez fullscreen false"),
    "gldemo":   (f"{PKG}/com.termux.mod.ModActivity", "--es class gldemo.GlScreen --ez fullscreen true"),
}
KEYS = {"back": 4, "home": 3, "enter": 66, "tab": 61, "esc": 111, "up": 19, "down": 20, "left": 21, "right": 22,
        "menu": 82, "recents": 187, "power": 26, "delete": 67, "space": 62, "volume_up": 24, "volume_down": 25}

def endpoint():
    try:
        return open(PORT_FILE).read().strip() or None
    except OSError:
        return None

def adb(*args, binary=False, timeout=40):
    ep = endpoint()
    base = ["adb"] + (["-s", ep] if ep else [])
    r = subprocess.run(base + list(args), capture_output=True, timeout=timeout)
    if r.returncode != 0 and (b"not found" in r.stderr or b"offline" in r.stderr or b"no devices" in r.stderr) and ep:
        subprocess.run(["adb", "connect", ep], capture_output=True, timeout=15)
        r = subprocess.run(base + list(args), capture_output=True, timeout=timeout)
    if binary:
        return r.stdout
    return (r.stdout + r.stderr).decode("utf-8", "replace")

def sh(cmd, **kw):
    return adb("shell", cmd, **kw)

def text(s):
    return [{"type": "text", "text": s}]

def nodes():
    sh("uiautomator dump /sdcard/god-ui.xml >/dev/null 2>&1")
    xml = adb("exec-out", "cat", "/sdcard/god-ui.xml")
    root = ET.fromstring(xml[xml.index("<"):])
    out = []
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "id": n.get("resource-id", "").split("/")[-1],
                    "cls": n.get("class", "").split(".")[-1], "click": n.get("clickable") == "true", "checked": n.get("checked") == "true",
                    "enabled": n.get("enabled") == "true", "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2, "b": f"[{x1},{y1}][{x2},{y2}]"})
    return out

def t_screenshot(a):
    data = adb("exec-out", "screencap", "-p", binary=True)
    if not data.startswith(b"\x89PNG"):
        return text("screenshot failed: " + data[:200].decode("utf-8", "replace")), True
    return [{"type": "image", "data": base64.b64encode(data).decode(), "mimeType": "image/png"}], False

def t_ui_tree(a):
    lines = []
    for n in nodes():
        if not (n["text"] or n["desc"] or n["click"]):
            continue
        lines.append(f'{n["cls"]:<12} text={n["text"]!r} desc={n["desc"]!r} id={n["id"]} tap=({n["cx"]},{n["cy"]}) {"CLICK " if n["click"] else ""}{"CHECKED " if n["checked"] else ""}{n["b"]}')
    return text("\n".join(lines) or "(empty tree: the screen may be a game/surface or secure)"), False

def t_tap(a):
    sh(f'input tap {int(a["x"])} {int(a["y"])}')
    return text(f'tapped ({a["x"]},{a["y"]})'), False

def t_tap_text(a):
    q = a["text"].lower(); best = None
    for n in nodes():
        hay = (n["text"] + " " + n["desc"]).lower()
        if q in hay and n["enabled"]:
            if best is None or (n["click"] and not best["click"]) or (n["click"] == best["click"] and len(hay) < len(best["text"] + best["desc"]) + 1):
                best = n
    if not best:
        return text(f'no element containing {a["text"]!r}'), True
    sh(f'input tap {best["cx"]} {best["cy"]}')
    return text(f'tapped {best["text"] or best["desc"]!r} at ({best["cx"]},{best["cy"]})'), False

def t_swipe(a):
    sh(f'input swipe {int(a["x1"])} {int(a["y1"])} {int(a["x2"])} {int(a["y2"])} {int(a.get("ms", 300))}')
    return text("swiped"), False

def t_key(a):
    k = a["key"]; code = KEYS.get(str(k).lower(), k)
    sh(f"input keyevent {code}")
    return text(f"key {k}"), False

def t_type_text(a):
    s = a["text"]
    if not s.isascii():
        return text("input text supports ASCII only"), True
    sh("input text " + json.dumps(s.replace(" ", "%s")))
    return text("typed"), False

def t_open(a):
    name = a["screen"]
    if name == "drawer":
        sh(f"am start -n {SCREENS['termux'][0]} >/dev/null 2>&1; sleep 1.5; input swipe 44 1200 800 1200 500")
        return text("opened the sessions drawer (swipe from the left edge)"), False
    if name not in SCREENS:
        return text("unknown screen; one of: drawer, " + ", ".join(SCREENS)), True
    comp, extras = SCREENS[name]
    flags = "-f 0x18080000" if "ModActivity" in comp else ""
    out = sh(f"am start -n {comp} {flags} {extras} 2>&1; sleep 2.5")
    return text(f"opened {name}\n{out.strip()}"), False

def t_launch(a):
    return text(sh("am start " + a["args"] + " 2>&1").strip()), False

def t_logcat(a):
    tag = a.get("tag"); n = int(a.get("lines", 40))
    cmd = f"logcat -d -s {tag}" if tag else "logcat -d"
    out = sh(f"{cmd} | tail -n {n}")
    return text(out), False

def t_screen_info(a):
    return text(sh("wm size; wm density; dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2")), False

def t_wait(a):
    time.sleep(min(float(a.get("seconds", 1)), 20)); return text("waited"), False

TOOLS = {
    "screenshot": (t_screenshot, "Take a screenshot of the phone screen (returns the image).", {}),
    "ui_tree": (t_ui_tree, "List on-screen elements with text, description, id, tap coordinates, clickable/checked state (uiautomator).", {}),
    "tap": (t_tap, "Tap at pixel coordinates.", {"x": "integer", "y": "integer"}),
    "tap_text": (t_tap_text, "Tap the first enabled element whose text/content-description contains the given text.", {"text": "string"}),
    "swipe": (t_swipe, "Swipe between two points (optional ms duration).", {"x1": "integer", "y1": "integer", "x2": "integer", "y2": "integer", "ms": "integer?"}),
    "key": (t_key, "Send a key: back, home, enter, tab, esc, up, down, left, right, menu, recents, or an Android keycode number.", {"key": "string"}),
    "type_text": (t_type_text, "Type ASCII text into the focused field.", {"text": "string"}),
    "open": (t_open, "Open a god-termux screen: drawer, termux, settings, mods, shell, glibc, gldemo.", {"screen": "string"}),
    "launch": (t_launch, "Run `am start <args>` for any activity/intent.", {"args": "string"}),
    "logcat": (t_logcat, "Recent logcat lines, optionally filtered by tag.", {"tag": "string?", "lines": "integer?"}),
    "screen_info": (t_screen_info, "Screen size, density and the focused window.", {}),
    "wait": (t_wait, "Sleep for N seconds (max 20).", {"seconds": "number?"}),
}

def schema(props):
    p = {}; req = []
    for k, t in props.items():
        opt = t.endswith("?"); t = t.rstrip("?")
        p[k] = {"type": t}
        if not opt:
            req.append(k)
    return {"type": "object", "properties": p, "required": req}

def handle(m):
    meth, mid, params = m.get("method"), m.get("id"), m.get("params") or {}
    if meth == "initialize":
        return {"protocolVersion": params.get("protocolVersion", "2024-11-05"), "capabilities": {"tools": {}},
                "serverInfo": {"name": "god-ui", "version": "1.0"}}
    if meth == "ping":
        return {}
    if meth == "tools/list":
        return {"tools": [{"name": n, "description": d, "inputSchema": schema(p)} for n, (f, d, p) in TOOLS.items()]}
    if meth == "tools/call":
        name = params.get("name"); args = params.get("arguments") or {}
        if name not in TOOLS:
            return {"content": text(f"unknown tool {name}"), "isError": True}
        try:
            content, err = TOOLS[name][0](args)
        except Exception as e:
            content, err = text(f"{type(e).__name__}: {e}"), True
        return {"content": content, "isError": err}
    return None

def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            m = json.loads(line)
        except ValueError:
            continue
        if "id" not in m:          # notification
            continue
        res = handle(m)
        if res is None:
            out = {"jsonrpc": "2.0", "id": m["id"], "error": {"code": -32601, "message": "method not found"}}
        else:
            out = {"jsonrpc": "2.0", "id": m["id"], "result": res}
        sys.stdout.write(json.dumps(out) + "\n"); sys.stdout.flush()

if __name__ == "__main__":
    main()
