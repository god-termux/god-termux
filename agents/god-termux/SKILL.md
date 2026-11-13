---
name: god-termux
description: "Управление окружением god-termux (форк Termux с модами, glibc-шимом, Debian-деревом dapt, Wayland-композитором wlcomp, привилегированным каналом dsu/adb). Используй, когда нужно: поставить или запустить Debian/glibc-программу (apt-пакет, Electron, Chromium, GTK), показать окно через wlrun/wlchromium/xfce-wl, выполнить команду от uid 2000 через dsu/gsh, чинить dapt/dpkg, разбираться с шимом fhs/fhs-run, собирать моды или APK, смотреть экран через god-ui. Не для обычного Termux без этих компонентов."
---

# god-termux: как этим управлять

Окружение: телефон (aarch64, Android), форк Termux `~/god-termux/termux-app`. Отвечай пользователю по-русски.

## Главное правило: спрашивай перед запуском

Всё, что открывает окно, запускает GUI, включает отладку, трогает экран или ставит пакеты, делай **только после согласия пользователя**. Чтение файлов и анализ — без вопросов.

Требует согласия: `wlrun`, `wlchromium`, `xfce-wl`, `wlbench`, `desk`, `termux-x11`, `am start`, любой запуск GUI-приложения, `dapt install/remove/fix`, `dsu --restart-adb`, `modbuild`, сборка APK, `god-ui` (tap/type/launch). Один отказ действует на всю серию попыток: не пробуй обходные пути.

Не запускай и не оставляй в фоне то, что не просили. Не убивай процессы по шаблону (`pkill -f имя` убьёт и твою собственную оболочку, если имя есть в командной строке). Не бери `X11`: здесь Wayland (`wlcomp`), пользователь прямо отказался от X11.

## Карта компонентов

| Что | Где | Зачем |
|---|---|---|
| Шим glibc/FHS | `$PREFIX/glibc/shim/` (`libfhs-bionic.so`, `libfhs-glibc.so`, `fhs-run`, `libdapt-nolink.so`), исходники `god/fhs/` | запуск glibc-бинарей и подмена путей `/usr /lib /etc /tmp /var` |
| `dapt` | `god/runtime/dapt`, дерево `$PREFIX/debian` | свой apt/dpkg для Debian arm64, не трогает apt Termux |
| `dsu`, `gsh`, `g*` | `god/runtime/dsu` | команды от shell uid 2000 через Shizuku (rish) или adb |
| `wlcomp` | мод в APK, `wlrun` | Wayland-композитор на `wl_shm`, сокет `$PREFIX/tmp/wayland-0` |
| `wlchromium`, `xfce-wl` | `god/runtime/` | Chromium и XFCE как клиенты композитора |
| Моды | `app/src/main/java/{modsmgr,adbfind,glibcrt,gldemo,wlcomp}`, внешний `dsuset` | встроенные в APK; внешние в `~/.termux/mods/` |
| god-ui | MCP `mcp__god-ui__*` | скриншоты и действия на экране через adb |

Актуальные исходники встроенных модов лежат в `termux-app/app/src/main/java`. Копия `~/mods-src.of` устарела, её не правь.

## Запуск Debian/glibc-программ

1. Из обычной оболочки Termux шим уже в `LD_PRELOAD`, поэтому `$PREFIX/debian/usr/bin/ПРОГРАММА` запускается напрямую. Шим сам выберет загрузчик и выставит `FHS_EXE` (путь настоящего бинаря для `/proc/self/exe`). **Не выставляй `FHS_EXE` вручную.**
2. `fhs-run` делает голый `execve` первого аргумента. Glibc-бинарь ему передавать нельзя (ядру нужен `/lib/ld-linux-aarch64.so.1`). Всегда оборачивай: `fhs-run $PREFIX/bin/sh -c 'exec ... ПРОГРАММА'`, как в `wlchromium`.
3. Для GUI нужны переменные: `XDG_RUNTIME_DIR=$PREFIX/tmp`, `WAYLAND_DISPLAY=wayland-0`, `FHS_DEBIAN=1`, `XDG_DATA_DIRS=/usr/share`, `XDG_CONFIG_DIRS=/etc/xdg`, `NO_AT_BRIDGE=1`, отдельные `XDG_CONFIG_HOME`/`XDG_CACHE_HOME`.
4. Chromium и Electron: `--ozone-platform=wayland --no-sandbox --disable-dev-shm-usage --disable-gpu --password-store=basic`. Запускай внутри `dbus-run-session`. Для Chromium бери готовый `wlchromium`.
5. Композитор поддерживает только программный вывод (`wl_shm`): без dmabuf, субповерхности не отрисовываются, клиентам нужен `--disable-gpu`. Окно композитора открывает `wlrun --open` (после согласия пользователя).
6. Долгие GUI-процессы запускай через `run_in_background`, пиши лог в файл, а ожидание делай циклом `until`, не `sleep`.

## Запуск среды (рабочего стола)

Все варианты открывают окно на телефоне: сначала спроси пользователя, какой нужен.

**Wayland, основной путь (`xfce-wl`).** XFCE из Debian-дерева во вложенном `labwc` внутри окна `wlcomp`.
1. Один раз поставь пакеты (проверка: есть ли `$PREFIX/debian/usr/bin/labwc`):
   `dapt update && dapt install --no-install-recommends labwc xfce4-panel xfdesktop4 xfce4-terminal thunar xfce4-whiskermenu-plugin xfce4-settings adwaita-icon-theme fonts-dejavu-core dbus dbus-daemon xfce4-session`
2. `xfce-wl` запускает сессию. Скрипт сам вызывает `wlrun --open` (открывает `com.termux/com.termux.mod.WlActivity`), ждёт сокет `wayland-0`, поднимает `labwc` через `fhs-run ... dbus-run-session -- labwc` и ждёт сокет `wayland-1` (до 15 с). В автозапуске `labwc` лежат `xfsettingsd`, `xfdesktop`, `xfce4-panel` (файл `~/.config/god-xfce/labwc/autostart`).
3. Остановить: `xfce-wl stop`. Лог: `$PREFIX/tmp/xfce-wl.log`. Если пишет «labwc did not start», читай этот лог.
4. Сеансу нужен включённый мод `wlcomp` (Настройки Termux → Моды); без него `wlrun` пишет «compositor did not start».
5. Приложения внутри сессии: `WAYLAND_DISPLAY=wayland-1`, остальное окружение как в разделе выше. Для Chromium: `WLCHROMIUM_DISPLAY=wayland-1 wlchromium` (два слоя копирования кадра, медленнее, чем напрямую).

**Wayland без рабочего стола (`wlrun`, `wlchromium`).** Одно приложение прямо на `wayland-0`, без панели и управления окнами: `wlrun xfce4-terminal`, `wlrun gtk3-widget-factory`, `wlchromium URL`. Это быстрее, чем `xfce-wl`. `wlrun --open` только открывает окно композитора. Окно закрывается кнопкой «назад».

**Termux:X11 (устаревший путь, не по умолчанию).** `desk` (XFCE с virgl, `desk stop`), `chromen` (Chrome с virgl), `go` (оба сразу, `go stop`). Логи `~/.desk.log`, `~/.chromen.log`. Пользователь отказался от X11: предлагай этот путь только по прямой просьбе.

**После запуска проверь результат:** сокет живой (`python3 -c` с `socket.connect` по `$PREFIX/tmp/wayland-1`, как в функции `alive` у `xfce-wl`), в логе нет ошибок, на скриншоте (`mcp__god-ui__screenshot`) окно композитора, а не терминал. Не сообщай «запущено» без этого. Не проверено на этом телефоне: полный запуск `xfce-wl` и работа `claude-desktop` внутри него.

## dapt: пакеты

- `dapt update`, `dapt install ПАКЕТ`, `dapt install ПАКЕТ --fix`, `dapt dpkg ...`, `dapt dpkg -l`, `dapt list --installed`, `dapt fix`.
- Обёртка dpkg работает с `--force-depends` и `--force-script-chrootless`. Пакет может стоять в статусе `iU` (распакован, не настроен). Дожимай `dapt dpkg --configure ПАКЕТ`, потом проверяй `dapt dpkg -l ПАКЕТ`: нужно `ii`.
- Известные грабли:
  - `dpkg-divert` не обёрнут, поэтому пакеты с diversion в preinst (например `fuse3`) падают с `Permission denied` на `diversions-old`. Это гипотеза, не подтверждена.
  - После такого сбоя может остаться мусорный `$PREFIX/debian/var/lib/dpkg/diversions-new`. Удаляй его только с согласия пользователя.
  - Из-за `fuse3` ломается `xdg-desktop-portal`, и `dapt install` жалуется на зависимости, хотя нужный пакет уже стоит.
  - Репозиторий со сторонним ключом (например `downloads.claude.ai`) даёт `NO_PUBKEY`, пока ключа нет в `etc/apt/trusted.gpg.d`.
- Не ставь `.deb`, скачанные с GitHub и сторонних сайтов, если пользователь просил пакет из `dapt`. Спрашивай, откуда ставить.

## dsu / adb

- `dsu status` показывает канал и uid. `dsu -c 'строка'` выполняет строку как есть, `dsu КОМАНДА арг…` экранирует аргументы. Это не root, программ Termux в сессии нет.
- Обёртки: `gpm gam gset gcmd gdumpsys gwm gappops` (`dsu --install-wrappers`).
- Если нет канала, мод `dsuset` включает Беспроводную отладку сам. Сопряжение: `dsu pair КОД`. После смены сети порт меняется.
- Не вызывай `dsu --restart-adb` и не переключай отладку без согласия.

## Моды и сборка

- Внешний мод: `modbuild ~/mods-src/NAME`, затем полный перезапуск Termux. Встроенный мод требует пересборки APK (`god/build-local.sh`). Подпись только тестовым ключом Termux, такие APK нельзя раздавать.
- Журнал загрузки: `$PREFIX/tmp/god-mods.log`. Защита от цикла падений: `~/.termux/mods/.loading`, `DISABLED`.
- Мод живёт в процессе Termux: его краш убивает Termux. Оборачивай `onLoad` в `try/catch`.
- Установка форка голым `adb install -r` ломает вход (см. память «Установка APK через посредника»): ставь через Termux Rescue.

## Диагностика

- Шим: `FHS_TRACE=1` (показывает подмены путей), `FHS_DEBUG=1`, `FHS_OFF=1` (выключить), `FHS_FAST_OFF=1`, `FHS_SUP_STATS=ФАЙЛ`.
- Проверка шима при старте: `cd $PREFIX/glibc/shim && sha256sum -c SHA256SUMS`.
- Код выхода 159 (SIGSYS) у Electron/Chromium: Android seccomp убивает процесс за запрещённый системный вызов. Номер вызова лежит в дампе Crashpad (`<XDG_CONFIG_HOME>/Claude/Crashpad/completed/*.dmp`, поток исключения, код 31, первый параметр = номер syscall; на arm64 437 = `openat2`). Шим (ветка `claude/sigsys-enosys`, `fhs.c`, glibc-вариант) превращает SIGSYS в `ENOSYS` и подавляет чужие `sigaction(SIGSYS)`; `FHS_SIGSYS_LOG=1` печатает номера, `FHS_SIGSYS=0` выключает. Тест: `god/tests/fhs/sigsys.c`.
- Запуск Claude Desktop: `wlclaude` (`god/runtime/wlclaude`, ярлык `~/Desktop/claude-desktop.desktop`). Бинарь только по реальному пути `$PREFIX/debian/usr/lib/claude-desktop/claude-desktop`: через симлинк `/usr/bin/claude-desktop` Chromium не находит `icudtl.dat` («Invalid file descriptor to ICU data»). Лог: `$PREFIX/tmp/claude-desktop.log`, логи приложения: `~/.config/god-claude/Claude/logs/main.log`.
- Тесты, которым нужен процесс без шима: запускай через `/system/bin/linker64 $PREFIX/bin/env ...`. Обычный `env` внутри сессии несёт шим и при `execve` подменяет твой `LD_PRELOAD` на установленный.
- Экран: `mcp__god-ui__screenshot`, `ui_tree`, `logcat` (нужен adb). Скриншот может показать твой же терминал вместо окна приложения: проверь, что окно композитора действительно открыто.
- Тесты шима: `python3 god/tests/fhs/run.py`, `dapt.py`, `paths.py`, `fast.py`, `refresh.py` (временные каталоги, реальный apt не запускают).

## Чего не делать

- Не запускай GUI и фоновые серверы «на пробу» без разрешения.
- Не правь `$PREFIX/debian`, `$PREFIX/glibc`, чужие правки в рабочем дереве и `~/.termux/mods.bak` без явной просьбы.
- Не коммить `.cache/` и чужие незакоммиченные файлы: добавляй в коммит только свои пути.
- Не сообщай «работает», пока не увидел результат (лог, скриншот, `ii` в `dapt dpkg -l`). Если запуск упал, так и скажи.
