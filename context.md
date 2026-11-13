# Контекст для архитектора: команда `dsu` (аналог `tsu` без root) в god-termux

Документ для модели-архитектора. Задача в конце (раздел 12). Всё, что отмечено «проверено», я видел на устройстве в этой сессии. Всё, что отмечено «не проверено», нужно перепроверить. Пользователь пишет по-русски, отвечать и писать план по-русски.

---

## 1. Цель одной строкой

Сделать команду `dsu`, которая работает так же удобно, как `tsu` (Termux su-обёртка), но вместо root-`su` даёт **сессию с правами Android shell (uid 2000) через ADB** (Беспроводная отладка) и окружением, максимально похожим на Termux. Использовать часть исходников нашего мода `adbfind` (поиск порта, сопряжение, статус).

Пользователь сформулировал идею так: «сессия от имени adb только со всеми путями Termux» и «а если в ядро Termux вшить команду». Под «ядром» он имеет в виду само приложение god-termux (форк Termux).

## 2. Устройство и среда (проверено)

- Телефон: Redmi, HyperOS 3 (`ro.build.version.incremental = OS3.0.302.0.WPGEUXM`), Android 16, ядро `6.12.69-android16`, aarch64.
- Termux (форк god-termux): пакет `com.termux`, uid `u0_a318`.
- Мой процесс в Termux: SELinux-контекст `u:r:untrusted_app_27:s0:c62,c257,c512,c768`.
- Права на файлы Termux (`ls -ldZ`):
  - `/data/data/com.termux` — `drwx------`, `u0_a318`, `app_data_file:s0:c62,c257,c512,c768`.
  - `/data/data/com.termux/files` — `drwxrwx--x`.
  - `/data/data/com.termux/files/usr` — `drwx------`.
  - `/data/data/com.termux/files/usr/bin/bash` — `-rwx------`, 880416 байт.
- `/data/local/tmp` — `drwxrwx--x shell shell`, метка `shell_data_file:s0`. Shell там может писать и исполнять.
- `$PREFIX` занимает 11 ГБ.
- Стабильного root нет. Загрузчик/Magisk не обсуждались.

### Привилегированный доступ на этом устройстве
- **Shizuku** (`moe.shizuku.privileged.api`) установлен. Мост `rish` скопирован в `~/.local/bin/rish` + `rish_shizuku.dex` (из папки `Download/SHIZUKU`, экспортированной из приложения Shizuku). Нужна переменная `RISH_APPLICATION_ID=com.termux`.
- Проверено: `rish -c id` вернул `uid=2000(shell) gid=2000(shell) groups=...,1004(input),1007(log),1011(adb),1015(sdcard_rw),1028(sdcard_r),3003(inet),3009(readproc)... context=u:r:shell:s0`.
- Через `rish` удалось установить APK командой `pm install -r /data/local/tmp/x.apk` (сначала была ошибка `INSTALL_FAILED_USER_RESTRICTED`, потому что пользователь случайно отказал в запросе и выбрал «запомнить»; потом включили «Установка через USB» заново, после чего `Success`).
- Shizuku **нестабилен**: сервис периодически убивает система, тогда `rish` отвечает `Request timeout. The connection between the current app (com.termux) and Shizuku app may be blocked...`. Так было на момент последней проверки.
- На момент написания Shizuku не запущен, и ADB не спарен: `adb devices` пуст. Поэтому поведение сессии от shell я **не проверял на практике**.
- Из Termux обычный `settings get global ...` падает с `SecurityException ... INTERACT_ACROSS_USERS` (нужны права). `adb mdns` в нашей сборке adb не поддерживается (`adb: mdns is not supported by this version of adb`), поэтому порты ищет Java-код через NSD.

## 3. Главное ограничение (проверено по правам, не проверено в живой сессии)

Процесс от shell **не может** открыть файлы Termux: каталоги `0700` у владельца-приложения плюс SELinux (`app_data_file` с категориями приложения; домен `shell` их не читает). `chmod` и `PATH` этого не обходят, в отличие от root.
Следствия:
- В сессии shell можно запускать только системные программы (`/system/bin`, `/system/xbin`: `toybox`, `pm`, `am`, `cmd`, `settings`, `dumpsys`, `wm`, `appops`, `input`, `screencap`, ...) и всё, что лежит в `/data/local/tmp` или других доступных shell путях.
- `bash`, `git`, `python`, `vim` из Termux в такой сессии получат `Permission denied`.
- Скрипты с shebang `#!/data/data/com.termux/files/usr/bin/...` тоже не запустятся.
- Копирование нужных бинарников в `/data/local/tmp`: теоретически возможно, но у Termux-бинарников и скриптов жёстко прописаны пути `/data/data/com.termux/files/usr`, плюс размер. Скорее всего хрупко. Архитектор должен оценить и либо отвергнуть, либо описать минимальный набор.
- Привилегированные команды можно вызывать **из обычного Termux** через обёртки (`gsh`), без входа в отдельный shell.

## 4. Как работает `tsu` (проверено, пакет `tsu` 8.6.0 из репозитория Termux)

Файл: `/data/data/com.termux/files/usr/bin/tsu`, bash-скрипт на 343 строки. Upstream: https://github.com/cswl/tsu. Лицензия в `$PREFIX/share/doc/tsu/LICENSE` (тип лицензии я не проверял, проверить перед копированием кода).

Устройство:
- Режимы: `tsu [-s SHELL] [-p|-a] [USER]` — shell; `tsu --dbg ...` — отладка; `-E` — сохранить окружение; режим `sudo`-обёртки (`_TSU_AS_SUDO`) — выполнить команду, а не shell (аргументы экранируются через `printf '%q '`).
- Константы: `TERMUX_PATH="$TERMUX_PREFIX/bin:$TERMUX_PREFIX/bin/applets"`, `ROOT_HOME="$TERMUX_FS/home/.suroot"`, `ANDROID_SYSPATHS="/system/bin:/system/xbin"`, `EXTRA_SYSPATHS="/sbin:/sbin/bin"`.
- Окружение собирается массивом `EXP_ENV[...]`: `PATH`, `HOME` (по умолчанию `~/.suroot`; `TMPDIR=$ROOT_HOME/.tmp`), `TERM=xterm-256color`, `ANDROID_ROOT`, `ANDROID_DATA`, `LD_LIBRARY_PATH` (с добавлением системных библиотек, если был задан; на Android < 7 он ломает всё).
- Флаги `-p`/`-a`: системные пути в начало/конец `PATH`.
- Выбор shell (функция `root_shell_helper`): `-s` (с раскрытием `//usr/`), `system` → `/system/bin/sh`, `~/.termux/shell`, `$PREFIX/bin/bash`, `$PREFIX/bin/sh`. При переключении пользователя — `/system/bin/sh`.
- Перед запуском: `unset LD_LIBRARY_PATH LD_PRELOAD` (чтобы не подтягивались библиотеки Termux в системных программах), если не задан `-E`.
- Поиск `su`: `/system/xbin/su`, `/system/bin/su`, `/sbin/su`, `/sbin/bin/su` (если `/sbin` доступен).
- Запуск: `exec su [USER] [--preserve-environment] -c "PATH=$ANDROID_SYSPATHS env -i $ENV_BUILT $STARTUP_SCRIPT"` (или с `--preserve-environment`: `PATH=...:$PATH $ENV_BUILT $STARTUP_SCRIPT`).
- Журнал отладки: `_TSU_DEBUG`, функция `log_DEBUG`.

## 5. Что уже есть в god-termux

Подробно про проект см. `CLAUDE.md` рядом и `~/mods-src/CLAUDE.md`. Кратко:
- Система модов: встроенные (в APK, список `Builtin.ALL`) и внешние (`~/.termux/mods/NAME/{mod.json,classes.dex,lib/}`). Сборка внешних: `modbuild`. После установки нужен перезапуск Termux.
- Встроенные моды: `modsmgr`, `adbfind`, `glibcrt`, `gldemo`, `wlcomp`.
- Команда `gsh` (`~/.local/bin/gsh`, в APK копия в `god/runtime/gsh`) — выполнить команду от shell.
- Скрипты `desk`, `chromen`, `go` для рабочего стола и Chrome (не относятся к `dsu`).
- Установка рантайма: `GodRuntime.init` копирует содержимое assets `god/` в `$PREFIX` при старте (идемпотентно, сравнение sha256). Туда же могут попасть новые команды (`dsu`).

### 5.1. `gsh` (проверено, весь файл прочитан)
Порядок выбора канала: (1) запущенный Shizuku через `rish -c`, (2) встроенный ADB: `adb connect` к адресу из `$PREFIX/tmp/adb-connect-port`, затем `adb -s EP shell "<команда>"`.
Подкоманды:
- `gsh status` — показывает endpoint подключения/сопряжения, состояние ADB (с uid) и работоспособность `rish`.
- `gsh pair КОД` — `adb pair $(cat $T/adb-pair-port) КОД` (нужно открытое окно «Подключить устройство с помощью кода»).
- `gsh -c 'cmd'` или `gsh cmd args`.
Нюансы: нет интерактивного режима (`adb shell` без команды не вызывается); все аргументы склеиваются в одну строку `"$*"` (риск проблем с кавычками); `rish -c "$*"` тоже строка; при отсутствии канала печатает «gsh: no privileged shell. Enable Wireless debugging, then: gsh pair CODE». Реализация `-c` написана через `set -- "$1" ;&` (fallthrough case-ветки).

### 5.2. Мод `adbfind` — что в коде (проверено, прочитан `AdbFind.java` 240 строк и `AdbScreen.java` 68 строк)

Файлы: `app/src/main/java/adbfind/AdbFind.java`, `AdbScreen.java`. Копия в `~/mods-src/adbfind/java/adbfind/` отличается одной строкой (в `uiIntent()` там есть `.putExtra("mod","adbfind")`). Важно: **ADB-протокол мод сам не реализует**, он вызывает внешний бинарник `$PREFIX/bin/adb` (пакет `android-tools` 37.0.0p1-3, установлен).

`AdbFind implements com.termux.mod.Mod`, поле `public static volatile AdbFind INSTANCE`.

Что делает:
1. `onLoad(app, modDir, loader)`: заполняет `app`, `prefix=files/usr`, `home=files/home`, `tmp=prefix/tmp`; получает `NsdManager`, `NotificationManager`; создаёт канал уведомлений `adbfind` (важность HIGH); регистрирует `BroadcastReceiver` на действие `adbfind.PAIR`, который достаёт код из `RemoteInput` (ключ `"code"`) и в потоке вызывает `pair(code)`; вызывает `publishShortcut()`, `hookSettings()`; запускает два NSD-обнаружения.
2. `start(type, file)`: `nsd.discoverServices` для `_adb-tls-connect._tcp` (файл `adb-connect-port`) и `_adb-tls-pairing._tcp` (файл `adb-pair-port`). Найденные сервисы кладутся в очередь `queue`. При потере сервиса файл удаляется; для пары ещё `pairEp = null` и снимается уведомление `N_PAIR`.
3. `pump()`: последовательно (флаг `busy`) резолвит сервисы через `nsd.resolveService`. При успехе пишет `host:port` в `$PREFIX/tmp/<file>`; для сопряжения ставит `pairEp` и вызывает `notifyPair()`. При ошибке `c==3` (FAILURE_ALREADY_ACTIVE) повторяет через 500 мс.
4. `notifyPair()`: постоянное (ongoing) уведомление с полем ввода `RemoteInput` («6-digit pairing code»); `PendingIntent` на `adbfind.PAIR`, флаги `FLAG_UPDATE_CURRENT | FLAG_MUTABLE (1<<25)`. Нажатие на уведомление открывает `AdbScreen`.
5. `result(text)`: убирает уведомление сопряжения, показывает итоговое.
6. `run(String... cmd)`: запускает процесс (`ProcessBuilder`, объединяет stderr, окружение `HOME`, `PREFIX`, `TMPDIR`, `PATH=$PREFIX/bin`), возвращает вывод.
7. `doPair(code)`: если `pairEp==null` — сообщение «Окно сопряжения закрыто...»; если нет `adb` — «adb не установлен: pkg install android-tools»; `adb pair <pairEp> <code>`; успех определяется по подстроке `success`; затем пауза 1.5 с и `doConnect()`.
8. `doConnect()`: `adb connect <connectEp>`; `connectEp()` читает первую строку `adb-connect-port`.
9. `doTest()`: `adb -s <ep> shell id`. `doStatus()`: endpoint подключения, состояние окна сопряжения, `adb connect` + `adb -s ep get-state` (ожидается `device`).
10. UI-интеграция: `hookSettings()` через `ActivityLifecycleCallbacks` и рефлексию добавляет пункт «Привилегированный шелл» (ключ `god_privileged`) в `SettingsActivity` Termux (`RootPreferencesFragment`, `androidx.preference` через `Proxy`/рефлексию); `shortcutInfo()`, `publishShortcut()` (динамический ярлык, результат пишется в `$PREFIX/tmp/adbfind-shortcut.log`), `pinShortcut()` (запрос закрепления ярлыка на экране).
11. `uiIntent()`: `ModActivity` с `class=adbfind.AdbScreen`, `fullscreen=false`, флаги `0x18080000` (new task / multiple task).

`AdbScreen implements ModScreen`: страница со статусом (три строки: точка подключения, окно сопряжения, ADB `get-state` → «Подключено (shell, uid 2000)» / «Не подключено — нужно сопряжение»), карточка сопряжения (поле на 6 цифр), кнопки «Подключить», «Проверить (shell id)», «Открыть «Для разработчиков»», «Добавить ярлык». Все сетевые действия в фоновых потоках, обновление статуса в `onResume`.

Публикуемые файлы-контракты для скриптов (читают `gsh`):
- `$PREFIX/tmp/adb-connect-port` — `host:port` точки подключения,
- `$PREFIX/tmp/adb-pair-port` — `host:port` окна сопряжения (только пока оно открыто).

Что **нет** в `adbfind` (важно для плана): интерактивного терминала/PTY, собственной реализации протокола ADB/TLS-сопряжения, автоматического включения «Беспроводной отладки», автопереподключения при смене порта (порт меняется при каждом включении отладки; перечитывается из файла при каждом вызове `gsh`), работы без бинарника `adb`.

## 6. Что значит «адб-сессия» технически (не проверено в живой сессии)

- Интерактивный shell: `adb -s EP shell` (с tty) даёт PTY на стороне adbd, `TERM` берётся из окружения клиента. Размер окна передаётся протоколом shell v2. Проверить, что `SIGWINCH` и размер доходят.
- Команда без tty: `adb -s EP shell "..."`; для принудительного PTY — `-t`/`-tt`.
- Окружение на стороне shell создаётся заново adbd'ом. Переменные Termux туда **не передаются сами**: их нужно подставлять в командной строке (`env PATH=... HOME=... TERM=... /system/bin/sh -l`) или через стартовый скрипт во временном каталоге, доступном shell (`/data/local/tmp`). Полезно для `PATH`, `TERM`, `TMPDIR`, `LANG`; `HOME` для shell не может указывать в `/data/data/com.termux/...` (нет доступа), нужен `/data/local/tmp/dsu-home` (проверить права).
- `adb` server (порт 5037) запускается из Termux (`adb devices` стартует демон) и работает с uid приложения.

## 7. Варианты реализации (для оценки архитектором)

A. **Только скрипт** (`dsu`, bash, по образцу `tsu`) поверх `gsh`/`adb`. Плюсы: быстро, без пересборки APK, устанавливается через `GodRuntime` или руками. Минусы: зависит от внешнего `adb`, нет автоматического сопряжения вне `adbfind`.
B. **Скрипт + доработка `adbfind`** (встроенный мод): обмен состоянием через файлы в `$PREFIX/tmp`, команды для ручного сопряжения, автоматическое подключение, лучшая диагностика, UI-кнопка «Открыть привилегированный терминал» через `RUN_COMMAND`/терминальную сессию Termux.
C. **Реализация ADB-клиента в Java** (протокол ADB, TLS, SPAKE2 для сопряжения; готовые библиотеки существуют, но зависимости на устройстве без NDK нужно оценить) и PTY прямо в приложении. Максимально самостоятельно, но очень дорого и рискованно; тут нужна оценка.
D. **Копия набора Termux-утилит в `/data/local/tmp`** (чтобы shell мог запускать `bash` и т.п.). Предварительно: вероятно не стоит, но нужно явно оценить и записать причину.
E. **Обратный режим (рекомендация пользователю в диалоге)**: основной терминал остаётся Termux-овским, а системные привилегированные команды (`pm`, `am`, `settings`, `cmd`, `dumpsys`, `wm`, `appops`) вызываются обёртками через `gsh`. Можно комбинировать с A/B (режим `dsu` = shell от adb + эти обёртки).

## 8. Желаемое поведение `dsu` (предложение, подлежит уточнению)

- `dsu` — интерактивная сессия от shell (системный `/system/bin/sh`), `PATH` с системными путями (аналог `tsu -p`), `TERM`, `LANG`, рабочая папка по возможности, подсказка при `Permission denied` для программ Termux.
- `dsu команда…` — выполнить одну команду (аналог `sudo`/`gsh -c`), с корректной передачей кавычек и кода возврата.
- `dsu -s SHELL`, `-p/-a` (порядок путей), `-E` (передать часть окружения), `--dbg`, `-h`, `--version` — по образцу `tsu`.
- Диагностика: `dsu status` (как `gsh status`), понятные сообщения: «нет Беспроводной отладки», «нет сопряжения: `dsu pair КОД`», «Shizuku остановлен».
- Порядок каналов как у `gsh`: Shizuku (`rish`) → встроенный ADB. Архитектору решить, не слить ли `dsu` и `gsh`.

## 9. Ограничения и риски

- Нет root: `dsu` не равен `su`. Нельзя читать данные чужих приложений, писать в `/system`, менять SELinux.
- Порт подключения Беспроводной отладки меняется при каждом включении; отладка выключается при смене сети и перезагрузке. Нужно автопереподключение и ясные ошибки.
- Android убивает фоновые процессы (Shizuku, помощники). Это уже происходило в сессии.
- HyperOS: «Установка через USB» и «запомнить решение» влияют на `pm install` от shell.
- Приложение Termux debug-подписано публичным ключом (`testkey_untrusted.jks`). Не раздавать сборки.
- Лицензии: `tsu` (проверить `LICENSE`), Termux — GPLv3 (форк тоже).
- Сборка APK на устройстве длинная, первый запуск собранного god-termux не проверялся (не проверено). Поэтому предпочтителен путь без пересборки (варианты A/B через внешний мод и скрипты).

## 10. Что не проверено (нужно проверить на этапе реализации)

1. Реальное поведение `adb shell` с окружением Termux в `PATH` (какие именно программы Termux не открываются, есть ли исключения).
2. Передача размера окна и `TERM` через `adb shell` из терминала Termux; поведение Ctrl+C, цвета, `less`, `vim` из системы.
3. Работа `adbfind` и `gsh` в текущей сборке без Shizuku (ADB сейчас не спарен).
4. Полная совместимость кавычек в `gsh` (склейка `"$*"`).
5. Автопереподключение при смене порта.
6. Можно ли запускать `dsu` из внешнего мода (`~/.termux/mods`) вместо встроенного, и как положить скрипт в `$PREFIX/bin` без пересборки APK (вручную, `modbuild` или `GodRuntime` + assets).

## 11. Полезные пути

- Проект: `~/god-termux/termux-app` (`CLAUDE.md`, `god/README.md`, `god/runtime/gsh`).
- Мод `adbfind`: `app/src/main/java/adbfind/{AdbFind,AdbScreen}.java`; копия `~/mods-src/adbfind`.
- Загрузчик модов: `app/src/main/java/com/termux/mod/{Mod,ModScreen,ModLoader,Builtin,ModActivity,GodRuntime}.java`.
- Документация по модам: `~/mods-src/CLAUDE.md`.
- `tsu`: `$PREFIX/bin/tsu`, документация `$PREFIX/share/doc/tsu`.
- Команды: `gsh`, `rish` (в `~/.local/bin`), `modbuild`, `modwin`, `adb` (android-tools).
- Журналы: `$PREFIX/tmp/god-mods.log`, `$PREFIX/tmp/adb-*-port`, `$PREFIX/tmp/adbfind-shortcut.log`.

## 11а. Как устроен сам форк god-termux (данные из исходников, проверено чтением кода)

### Общее
- Репозиторий: `~/god-termux/termux-app`, git, remote `origin = git@github.com:CrafterMinecrafter/god-termux.git`. Последние коммиты: `535e73b0 wlcomp: edge-to-edge fullscreen...`, `d3802e96 Wayland: nested labwc + real XFCE...`, `a027943a dapt: systemd stubs...`, `3ac21c25 wlcomp: touchpad mode...`. Рабочее дерево: изменён `god/fhs/fhs.c`, не отслеживаются `CLAUDE.md` и `context.md`.
- База: Termux `0.118.0` (versionCode 118), `applicationId/namespace com.termux`, имя в интерфейсе `god-termux`, `compileSdk 36`, **`targetSdk 28`** (поэтому разрешён запуск исполняемых файлов из домашнего каталога приложения; это же причина, по которой Termux-бинарники вообще работают), `minSdk 21`, `ndkVersion 29.0.14206865` (в локальной сборке NDK не используется), bootstrap `apt-android-7`.
- Манифест: `android:sharedUserId="com.termux"` (плагины Termux:X11, Termux:API и т.п. делят uid, если подписаны тем же ключом; на практике Termux:X11 и Termux:API на устройстве стоят отдельными пакетами).
- Подпись сборок: `signingConfig signingConfigs.debug`, ключ `app/testkey_untrusted.jks` (публичный тестовый ключ Termux).
- Установка приложения: `com.termux`, пакеты рядом: `com.termux.x11`, `com.termux.api` (0.53.0 debug, поставлен через `rish` + `pm install`).

### Разрешения в манифесте (важно для `dsu`)
Помимо обычных (интернет, хранилище, сеть, звук, камера и т.д.), в манифесте заявлены «выдаваемые через shell» разрешения: `WRITE_SECURE_SETTINGS`, `READ_LOGS`, `DUMP`, `PACKAGE_USAGE_STATS`, `REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `NEARBY_WIFI_DEVICES`, `MANAGE_EXTERNAL_STORAGE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `FOREGROUND_SERVICE`, `WAKE_LOCK` и др. Собственное разрешение приложения: `com.termux.permission.RUN_COMMAND`.
Следствие, которое стоит учесть в плане: **эти разрешения можно выдать приложению один раз через shell** (`pm grant com.termux android.permission.WRITE_SECURE_SETTINGS` и т.п.). Какие из них выданы сейчас, я **не проверял** (из Termux `dumpsys`/`settings get` недоступны: `SecurityException INTERACT_ACROSS_USERS`). Если `WRITE_SECURE_SETTINGS` выдан, приложение само сможет менять `Settings.Global` (в частности включать «Беспроводную отладку», параметры `adb_wifi_enabled`, `adb_enabled`) без участия пользователя — это можно использовать для автоматического включения отладки и автопереподключения. `READ_LOGS` даёт чтение системного `logcat`, `DUMP` — часть `dumpsys`. Это гипотеза для проверки, а не проверенный факт.

### Старт приложения
`TermuxApplication.onCreate` (`app/src/main/java/com/termux/app/TermuxApplication.java`):
1. crash-обработчик и конфигурация логов,
2. **`com.termux.mod.GodRuntime.init(this)`** — копирует `assets/god/*` в `$PREFIX` (идемпотентно, сравнение sha256; ставит `bin/dapt`, `bin/gsh`, `bin/wlrun`, `bin/xfce-wl`, `etc/profile.d/glibc-fhs.sh`, `glibc/shim/*`; делает симлинки `dapt-get`, `dapt-cache` → `dapt`; проверяет SHA256SUMS шима и создаёт `.ok`). Работает только когда bootstrap уже распакован (`$PREFIX/bin` существует), иначе при следующем старте.
3. **`com.termux.mod.ModLoader.loadAll(this)`** — загрузка встроенных и внешних модов (раздел про моды в `~/mods-src/CLAUDE.md`).
4. Только после этого: `TermuxBootstrap`, `TermuxAppSharedProperties` (`termux.properties`), `TermuxShellManager`, тема, проверка каталога файлов.
Обе god-строки обёрнуты в `try/catch Throwable`, так что падение мода не должно ронять приложение (но `.loading`-защита есть на случай смерти процесса).

### Терминальные сессии и запуск команд
- `TermuxService` (`app/.../TermuxService.java`, foreground-сервис, внутренний) создаёт сессии: `createTermuxSession(String executablePath, String[] arguments, String stdin, String workingDirectory)` и `createTermuxSession(ExecutionCommand)`; фоновые задачи без tty: `createTermuxTask(...)` (`AppShell`). Сессии под капотом создаёт `TermuxSession` (`termux-shared/.../command/runner/terminal/TermuxSession.java`) поверх `terminal-emulator` (JNI `createSubprocess`, свой PTY).
- Внешний вход: `RunCommandService` (`exported=true`, под разрешением `com.termux.permission.RUN_COMMAND`), действие `com.termux.RUN_COMMAND`. Extras: `RUN_COMMAND_PATH` (обязателен), `ARGUMENTS`, `WORKDIR`, `BACKGROUND`/`RUNNER` (`terminal-session` или `app-shell`), `SESSION_ACTION`, `SHELL_NAME`, `STDIN`, `PENDING_INTENT`, каталог/файлы результата и т.д. Требует `allow-external-apps=true` в `~/.termux/termux.properties` — на устройстве **включено** (проверено).
  Вывод для `dsu`: интерактивную привилегированную сессию можно открыть как **обычную терминальную сессию Termux с исполняемым файлом `dsu`** (то есть скрипт или `adb`), не изобретая свой терминал: `createTermuxSession(executablePath="$PREFIX/bin/dsu", args, ...)` или `am startservice`/`RUN_COMMAND`.
- Окружение любого процесса приложения собирает `TermuxShellEnvironment.getEnvironment` (`termux-shared/.../command/environment/TermuxShellEnvironment.java`): строится на среде Android, затем добавляются переменные `TERMUX_*` (`TermuxAppShellEnvironment`, `TermuxAPIShellEnvironment`), `HOME=/data/data/com.termux/files/home`, `PREFIX=/data/data/com.termux/files/usr`, `TMPDIR=$PREFIX/tmp`, `PATH=$PREFIX/bin`, `LD_LIBRARY_PATH` убирается (бинарники Termux используют `DT_RUNPATH`). Для fail-safe сессии остаются дефолтные `PATH` и `TMPDIR`.
- **god-termux добавляет `GodEnvironment.apply(environment, isFailSafe)`** (`termux-shared/.../environment/GodEnvironment.java`, вызывается из `TermuxShellEnvironment`): в **каждый** процесс, который запускает приложение, добавляется `LD_PRELOAD=$PREFIX/glibc/shim/libfhs-bionic.so`, если шим существует, есть файл `.ok` (прошла проверка sha256) и нет флага `~/.termux/fhs.off`. На устройстве это включено: в моём окружении `LD_PRELOAD=/data/data/com.termux/files/usr/glibc/shim/libfhs-bionic.so`, `LD_LIBRARY_PATH` пуст. Профиль `$PREFIX/etc/profile.d/glibc-fhs.sh` делает то же для shell, если `FHS_OFF` не задан.
  **Для `dsu` это критично:** `tsu` делает `unset LD_LIBRARY_PATH; unset LD_PRELOAD`, потому что Termux-библиотеки ломают системные программы. Здесь `LD_PRELOAD` указывает в каталог, **закрытый для uid shell**, поэтому при передаче окружения в `adb shell` его нужно **явно не передавать** (иначе системные программы могут падать на загрузке). Надо проверить, пробрасывает ли сам `adb`/`rish` окружение клиента (по умолчанию `adb shell` не пробрасывает произвольные переменные, кроме `TERM`; `rish` — не проверено).

### glibc/FHS-шим (`god/fhs`)
- `fhs.c` + `fhsmap.h`: библиотека, собирается дважды (`-DGLIBC_BUILD` для glibc-мира, обычная для bionic): `libfhs-bionic.so` и `libfhs-glibc.so`. Подменяет вызовы libc (`open`, `stat`, `exec*`, `spawn`, `opendir` и др.), переписывая FHS-пути `/lib /usr /bin /etc /tmp /var` внутрь `$PREFIX`, и «прозрачно» запускает glibc-программы через `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` (`LDSO`). Шим-библиотеки лежат в `$PREFIX/glibc/shim/`.
- `fhs-run.c`: супервизор на `seccomp` user-notification для **статических** программ, где LD_PRELOAD не работает: переписывает пути на уровне системных вызовов; режим `FHS_DEBIAN=1` («Debian mode») для скриптов `dpkg`.
- `fhs/build/`, `fhs/install.sh` — сборка. Файл `~/.termux/fhs-static.env` (1 строка) — настройки статического режима (содержимое не читал).
- Вывод для `dsu`: шим работает только внутри процессов Termux; **в процессе от shell он не действует** (и недоступен). Seccomp-супервизор технически мог бы подменять пути для процесса, но только процесса того же uid и домена; обойти запрет SELinux на чтение чужих файлов он **не может**.

### `dapt` (`god/runtime/dapt`, 146 строк) и `wlrun`/`xfce-wl`
- `dapt` — apt для изолированного Debian arm64 в `$PREFIX/debian`: `dapt init|update|install|remove|search|show|list --installed|dpkg|dpkg-query|fixlinks`. Вспомогательные скрипты (`.ddpkg-bin/dpkg` и т.д.) перегенерируются при каждом запуске, чтобы обновления форка доходили до уже установленных систем. Использует `fhs-run` в Debian-режиме.
- `wlrun CMD` — запускает Wayland-программу на встроенном композиторе (`WlActivity`, один экземпляр окна, сокет `$PREFIX/tmp/wayland-0`), при необходимости сам открывает окно командой `am start -n com.termux/com.termux.mod.WlActivity`. `xfce-wl` — запуск XFCE внутри этого композитора (вложенный `labwc`).
- Эти части с `dsu` напрямую не связаны, но показывают принятые соглашения: скрипты в `god/runtime/`, установка через `GodRuntime`, команды без пересборки Termux-пакетов.

### Сборка и поставка
- `god/build-local.sh` (на устройстве): ставит Android SDK (`platforms;android-36`, `build-tools;35.0.0`), копирует дерево в `~/god-build` (исходники не меняет), убирает `externalNativeBuild`/`ndkVersion` из gradle-файлов, оставляет `arm64-v8a`, собирает нативные библиотеки (`libfhs-*.so`, `fhs-run`, `libwlcomp.so`, gldemo) clang'ом Termux, кладёт файлы в `app/src/main/assets/god/` (строка 61: `A=app/src/main/assets/god`; `bin/` ← `dapt gsh wlrun xfce-wl`, `etc/profile.d/` ← `glibc-fhs.sh`, `glibc/shim/`), вызывает gradle (`assembleDebug`). Выход: `~/god-build/app/build/outputs/apk/debug/*.apk`. **Чтобы новая команда `dsu` попала в APK, её надо добавить в список копирования (строка 67) и в assets.**
- Вне APK: `~/glbuild` — инструменты для внешних модов и «загрузчика»: `android.jar`, `r8.jar`, `loader/` (+`loader.apk`), `loader-api-cls`, `patch_loader.py` (правит `AndroidManifest.xml` готового APK, добавляя запись и новый `classesN.dex`, для установки загрузчика модов в уже собранное приложение без полной сборки), `rescue/` (`TermuxRescue.apk` — спасательное приложение). Это даёт путь **внедрения изменений в Termux без полной пересборки**, но я его детали не изучал.
- Скрипты `modbuild` и `modwin` в `~/.local/bin` (см. `~/mods-src/CLAUDE.md`).

### Что из этого определяет решения по `dsu`
1. `dsu` как **скрипт** в `god/runtime/` + строка в `build-local.sh` + копия в `$PREFIX/bin` вручную (для немедленного использования). Новая версия доедет до уже установленных систем через `GodRuntime.init` при следующем запуске нового APK.
2. Интерактивная сессия: либо запуск `dsu` в обычной терминальной сессии (самый простой и надёжный путь), либо отдельная сессия через `TermuxService.createTermuxSession(...)`/`RUN_COMMAND` (если нужна кнопка в интерфейсе или шорткат).
3. Окружение для shell нужно собирать **явно** и без `LD_PRELOAD`/`LD_LIBRARY_PATH` Termux; `HOME`/`TMPDIR` с путями Termux для shell недоступны.
4. Возможность автоматизировать включение «Беспроводной отладки» через `WRITE_SECURE_SETTINGS` (если выдан) — гипотеза, проверять.
5. Любая работа в `AdbFind.java` (встроенный мод) требует пересборки APK или переноса кода во внешний мод (`modbuild`); внешний мод с тем же именем встроенному **игнорируется** (`isBuiltinName`), поэтому для экспериментов нужно другое имя мода.

## 11б. Что вообще позволяет запускать программы в Termux (механика исполнения)

Проверено на устройстве: `readelf`, `ls -l`, `/proc/self/status`, `/proc/self/attr/current`, версии пакетов. Общие знания об Android помечены как таковые.

1. **Приложение — обычный Android-процесс.** Все процессы Termux (shell, `adb`, `python`...) — потомки процесса приложения `com.termux` (uid `10318` = `u0_a318`), создаются через `fork`+`exec` в нативном `terminal-emulator/src/main/jni/termux.c` (`JNI.createSubprocess`: открывает `/dev/ptmx`, задаёт размер окна, `exec`). Привилегий больше, чем у приложения, у них нет.
2. **Главное условие: `targetSdk 28`.** SELinux-домен процесса: `u:r:untrusted_app_27:s0:c62,c257,c512,c768` (проверено). Приложения с `targetSdk` ≥ 29 (домен `untrusted_app`) не могут исполнять файлы из своего каталога данных (W^X). Для `targetSdk` ≤ 28 домен `untrusted_app_27` это разрешает (общее знание Android, подтверждено тем, что бинарники в `$PREFIX/bin` запускаются). Поэтому нельзя повышать `targetSdk` в `gradle.properties` без потери запуска Termux-программ. Поэтому же форк нельзя выложить в Google Play.
3. **Динамический загрузчик.** Бинарники Termux — обычные ELF с интерпретатором `/system/bin/linker64` (проверено на `bash`). Библиотеки ищутся по **`DT_RUNPATH=/data/data/com.termux/files/usr/lib`** (проверено), поэтому `LD_LIBRARY_PATH` не нужен и намеренно удаляется в `TermuxShellEnvironment`. Путь `/data/data/com.termux/files/usr` вшит в бинарники при сборке пакетов; поэтому каталог нельзя переместить, и поэтому shell не может использовать их «через PATH».
4. **Права и размещение.** Файлы в `$PREFIX` принадлежат uid приложения, каталоги `0700` (`usr`) и `0771` (`files`), исполняемые файлы `0700`. Всё имеет метку `app_data_file` с категориями приложения: другие uid и домены (в том числе `shell`) не имеют доступа.
5. **Bootstrap.** Архив с минимальной системой (вариант `apt-android-7`) вшит в нативную библиотеку приложения (`app/src/main/cpp/termux-bootstrap.c`, `termux-bootstrap-zip.S`). `TermuxInstaller` при первом запуске: чистит `staging`, распаковывает zip в `$STAGING_PREFIX`, ставит права исполнения, читает `SYMLINKS.txt` и создаёт симлинки (`Os.symlink`), затем переименовывает staging в `$PREFIX`. Условия: основной пользователь устройства (на вторичном пользователе или SD-установке выдаёт ошибку), доступный каталог файлов. Дальше пакеты ставит `pkg`/`apt`/`dpkg` из репозиториев Termux (всё собрано под префикс `/data/data/com.termux/files/usr`).
6. **Оболочка и утилиты.** Версии на устройстве: `bash 5.3.20`, `dash 0.5.13.5` (`$PREFIX/bin/sh -> dash`), `coreutils 9.12` (`$PREFIX/bin/env -> coreutils` — единый мультибинарник), `termux-tools 1.46`, `termux-core 0.4.0`, `libc++ 30`, `libandroid-support 29`. Скрипты (`pkg`, `desk`...) начинаются с абсолютного shebang `#!/data/data/com.termux/files/usr/bin/bash`: пути переписаны на этапе сборки пакетов. Системные `/bin`, `/usr/bin`, `/tmp`, `/etc` в Android отсутствуют/недоступны.
7. **Пакет `termux-exec` 2.5.0.** Библиотеки лежат в `$PREFIX/lib`: `libtermux-exec.so`, `libtermux-exec-ld-preload.so`, `-direct-ld-preload.so`, `-linker-ld-preload.so` (+ `libtermux-core_nos_*`, `libtermux-platform-ns.so`, `libtermux-api.so`, `libtermux-auth.so`). Это механизм `LD_PRELOAD`, помогающий запускать скрипты с shebang вне префикса, исполнять файлы через `linker` и т.п. В каталоге `~/god-termux/termux-exec-package` лежит его исходный код. **На этом устройстве в `LD_PRELOAD` стоит только `libfhs-bionic.so` форка** (проверено значением переменной); подгружается ли `termux-exec` другим путём, я не проверял.
8. **Расширение форка: glibc/FHS.** Для запуска «обычных» glibc-программ `GodEnvironment` вставляет `libfhs-bionic.so` в `LD_PRELOAD` каждого процесса; шим перехватывает `exec*`/`open`/`stat`... и запускает glibc-ELF через `$PREFIX/glibc/lib/ld-linux-aarch64.so.1`, переписывая пути FHS внутрь префикса (`god/fhs/fhs.c`, `fhsmap.h`). Для статических бинарников — `fhs-run` на `seccomp` user-notification. В процессе приложения активен seccomp-фильтр Android (`Seccomp: 2`, `Seccomp_filters: 1`, `NoNewPrivs: 0` — проверено), вложенные фильтры допускаются (иначе `fhs-run` не работал бы; на устройстве его работа в этой сессии не проверялась).
9. **Жизнь процессов.** `TermuxService` — foreground-сервис с уведомлением и wake-lock. Android (в версиях 12+ есть «убийца фантомных процессов» — общее знание) убивает дочерние фоновые процессы; на этом устройстве в сессии были убиты `socat`, `virgl_test_server` и Shizuku. Для демонов надёжного решения нет; `chromen` поэтому сам поднимает помощников.
10. **Взаимодействие с Android из Termux.**
    - Внутри приложения работает сервер `TermuxAmSocketServer` (`termux-shared/.../shell/am/`) для быстрых вызовов `am`; детали я не читал. Все такие вызовы идут с правами приложения.
    - `termux-api`: пакет с командами `termux-*` + отдельное приложение `com.termux.api` (на устройстве 0.53.0, команды из пакета 0.60.0).
    - `RUN_COMMAND` (разрешение `com.termux.permission.RUN_COMMAND`, `allow-external-apps=true` включено) позволяет другим приложениям запускать команды в Termux.
    - Приложение имеет `INTERNET`, значит локальные сокеты и TCP на `127.0.0.1` работают (так работают `socat` → Termux:X11 и `virgl`).
11. **Привилегированная сторона (вне ядра).** Процесс с `uid=2000(shell)`, контекст `u:r:shell:s0`, получают извне: `rish` (Shizuku) или `adb -s ЭНДПОИНТ shell` (adbd через Беспроводную отладку; сервер adb — процесс в Termux, ADB-клиент на устройстве `android-tools 37.0.0p1-3`). Эти процессы запускаются **не из ядра Termux**, а системой по запросу.

**Следствия для `dsu`:** (а) то, что в Termux «запускается всё», держится на трёх вещах — `targetSdk 28`, `DT_RUNPATH` с абсолютным префиксом и `0700`-файлах приложения; (б) любой процесс не от uid приложения (в том числе shell) этим набором не пользуется; (в) переменные ядра Termux (`LD_PRELOAD`, `PREFIX`, `TMPDIR`, `HOME`) в сессию shell переносить нельзя; (г) PTY для интерактивной сессии даёт `createSubprocess` на стороне Termux (запуск `adb shell`) либо pty от adbd.

## 12. Задача архитектору

Напиши **план** (не код) реализации `dsu` как альтернативы `tsu`, использующей части исходников мода `adbfind`. Результат сохрани в `~/god-termux/termux-app/dsu-plan.md` по-русски.

План должен содержать:
1. **Выбор архитектуры.** Сравни варианты A–E из раздела 7, назови выбранный (допускается комбинация) и объясни почему, с учётом ограничения из раздела 3. Если считаешь, что цель «shell от adb со средой Termux» достижима лишь частично, скажи об этом прямо и опиши, что именно достижимо.
2. **Что переиспользовать из `adbfind`** (по методам из 5.2): какие части нужны как есть (`start`/`pump` NSD-обнаружение, файлы `adb-connect-port`/`adb-pair-port`, `notifyPair` + `RemoteInput`, `doPair`/`doConnect`/`doStatus`), что менять, что добавить. Укажи, нужна ли отдельная копия кода в новом моде или правка `adbfind`.
3. **Спецификация команды `dsu`**: синтаксис, флаги (по образцу `tsu`), режимы, формирование окружения (`PATH`, `HOME`, `TERM`, `TMPDIR`, `LANG`), выбор shell, обработка кавычек и кодов возврата, подсказки по ошибкам, порядок каналов (`rish` → встроенный ADB), слияние с `gsh` или нет.
4. **Устройство терминальной сессии**: PTY, размер окна, сигналы, конфликт `LD_LIBRARY_PATH`/`LD_PRELOAD` (по образцу `tsu`), рабочая папка и `HOME` для shell.
5. **Поставка и установка**: где лежит скрипт и как попадает в `$PREFIX/bin` (assets + `GodRuntime`, `modbuild`, вручную), нужна ли пересборка APK, как обновлять.
6. **Состояния и ошибки**: нет отладки, нет сопряжения, смена порта, Shizuku остановлен, запрет установки/`USER_RESTRICTED`, нет `adb`.
7. **Безопасность**: что именно получает пользователь (uid 2000, не root), риски передачи окружения, права на `/data/local/tmp`, где хранить временные файлы, лицензия `tsu`.
8. **План проверки**: что и как проверить на устройстве (пункты из раздела 10), критерии «готово».
9. **Этапы и порядок работ** с оценкой сложности, что можно сделать сразу без пересборки APK (минимальный рабочий `dsu`), а что отложить.
10. **Открытые вопросы к пользователю**, если без них нельзя выбрать вариант.

Ограничения: работа идёт на самом телефоне (aarch64 Termux, Java 17, clang, без NDK, без root); не предлагай решений, требующих root; не раздавай debug-подписанные сборки; не тащи крупные зависимости без оценки; если нужна проверка на устройстве, сформулируй её как шаг плана. Не придумывай факты о системе: опирайся на разделы 2–6 и помечай допущения.
