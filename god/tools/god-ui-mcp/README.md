# god-ui MCP server

Lets Claude Code look at and drive the phone UI through ADB (no dependencies, Python 3 stdlib).
Tools: `screenshot`, `ui_tree`, `tap`, `tap_text`, `swipe`, `key`, `type_text`, `open` (drawer/termux/settings/mods/shell/glibc/gldemo), `launch`, `logcat`, `screen_info`, `wait`.

Needs `adb` connected to the device (god-termux publishes the wireless-debugging endpoint in `$PREFIX/tmp/adb-connect-port`).

    claude mcp add god-ui --scope user -- python3 /path/to/server.py
