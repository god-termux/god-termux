# god-termux: заметки для ИИ

Неофициальный форк [Termux](https://github.com/termux/termux-app) (GPLv3) с системой модов, glibc/Debian и встроенным ADB. Это приложение на Android, собирается **прямо на телефоне** (aarch64 Termux, без NDK). Пользователю отвечать по-русски.

Люди читают `god/README.md`; этот файл для ИИ. Про написание модов см. `~/mods-src/CLAUDE.md`.

## Где что лежит
- `app/src/main/java/com/termux/mod/` — загрузчик модов: `ModLoader` (загрузка, защита от падений), `Builtin` (список встроенных), `Mod`, `ModScreen`, `ModActivity` (окно мода), `WlActivity` (окно Wayland), `GodRuntime` (разворачивает `god/` из assets в `$PREFIX`), `macros/` (макросы и радиальное меню).
- `app/src/main/java/{modsmgr,adbfind,glibcrt,gldemo,wlcomp,godui}` — встроенные моды и UI-кит `godui.Ui`.
- `app/src/main/cpp/wlcomp` — нативная часть Wayland-композитора.
- `god/fhs` — glibc/FHS-шим: `fhs.c` (LD_PRELOAD для динамических программ), `fhs-run.c` (seccomp-супервизор для статических), `fhsmap.h`.
- `god/runtime` — скрипты, ставятся в `$PREFIX`: `dapt` (изолированный Debian arm64 в `$PREFIX/debian`), `gsh`, `wlrun`, `glibc-fhs.sh`, `xfce-wl`, `nolink.c`.
- `god/tools/god-ui-mcp` — MCP-сервер для управления интерфейсом телефона через ADB (Python 3, stdlib).
- `god/tests/wlcomp` — тесты Wayland-композитора.
- `god/build-local.sh` — сборка на устройстве.
- Остальное (`terminal-emulator`, `terminal-view`, `termux-shared`, `docs`, `site`) — апстрим Termux, лишний раз не трогать.

## Встроенные моды
Определены в `Builtin.java` (`Builtin.ALL`):
`modsmgr` (Моды), `adbfind` (Привилегированный шелл, команда `gsh`), `glibcrt` (glibc и Debian, `dapt`), `gldemo` (GL demo), `wlcomp` (Wayland-композитор, `wlrun`).
Добавить встроенный мод: класс в `app/src/main/java/<имя>/`, запись в `Builtin.ALL` (поля: name, icon, title, desc, screen, fullscreen, toggleable, класс `Mod` или `null`, note).
Состояние включения хранится в `~/.termux/builtin-mods.disabled`. Журнал загрузки: `$PREFIX/tmp/god-mods.log`.

## Сборка
```
bash god/build-local.sh      # APK: ~/god-build/app/build/outputs/apk/debug/*.apk
```
Нужно: `pkg install openjdk-17 clang git curl unzip aapt2`. Скрипт сам ставит Android SDK (platforms;android-36, build-tools;35.0.0), собирает копию дерева в `~/god-build` (исходники не трогает), оставляет только `arm64-v8a`, собирает нативные библиотеки clang'ом Termux.
Отладочная сборка подписана публичным тестовым ключом Termux (`app/testkey_untrusted.jks`): **не раздавать** такие сборки другим.
Установка APK: на этом телефоне (Redmi, HyperOS 3) системе нужен разблокированный экран, и может потребоваться «Установка через USB». Программно: `gsh pm install -r файл.apk` (может вернуть `INSTALL_FAILED_USER_RESTRICTED`, если запрещено в настройках разработчика). Первый запуск собранного APK не проверялся.

## Быстрые правки без пересборки APK
Если мод можно сделать внешним, используйте `modbuild` (см. `~/mods-src/CLAUDE.md`), это быстрее. Встроенные моды меняются только пересборкой APK. Копии в `~/mods-src/{adbfind,modsmgr}` отличаются от `app/src/main/java/...`: сначала `diff -r`, потом правка.

## Привилегированный доступ
- `dsu` (`god/runtime/dsu`, этапы 1-2 готовы): команды от shell (uid 2000). Порядок каналов: Shizuku (`rish`, проба 3 с, кэш 30 с) -> ADB (endpoint: `--ep`/`DSU_EP` -> единственное устройство из `adb devices` -> `$PREFIX/tmp/adb-connect-port`). `dsu -c 'строка'`, `dsu КОМАНДА арг…` (аргументы через `printf %q`), `dsu status`, `dsu pair КОД`, `dsu --install-wrappers` (обёртки `gpm gam gset gcmd gdumpsys gwm gappops`, маркер `# dsu-wrapper`). Коды: 125 нет канала, 126 программа Termux недоступна shell. Эксперимент `tx` (этап 4б): `dsu --tx` (или `DSU_TX=1` в `~/.termux/dsu.conf`) запускает дочерний сервер `god/runtime/dsu-tx-server.py` (127.0.0.1, случайный порт, токен на сессию, лог `$PREFIX/tmp/dsu-tx.log`), в сессии shell появляется `tx [-n] КОМАНДА…`, которая выполняет программу в Termux (stdout+stderr вместе, без PTY; `~` и переменные раскрывает удалённая оболочка). Сервер умирает вместе с `dsu`. `dsu --tab [опции]` открывает интерактивный `dsu` в новой вкладке Termux через `RunCommandService` (терминальная сессия стартует процесс только когда её показали на экране; действие сессии 0). **Автовключение отладки** (этап 4а/5): внешний мод `dsuset` (`god/mods/dsuset` в репозитории, `~/mods-src/dsuset` — симлинк на него; ставится `modbuild ~/mods-src/dsuset`, нужен перезапуск Termux; фильтр отправителя `android.permission.WRITE_SECURE_SETTINGS`) принимает `am broadcast -a dsu.ADB_WIFI --ei value 0|1` / `--es op get` и меняет `Settings.Global.adb_wifi_enabled`, ответ в `$PREFIX/tmp/dsu-adbwifi.result`. `dsu` при отсутствии канала сам включает выключенную отладку и подключается (до 20 с; отключается `--no-autoadb` или `DSU_AUTOADB=0`); `dsu --restart-adb` перезапускает отладку; `dsu status` отладку не трогает. Интерактив: `dsu` без аргументов (нужен терминал; по умолчанию канал adb; rc `/data/local/tmp/dsu/rc.sh` заливается по sha256; опции `-s -p -a -E`), `dsu push ФАЙЛ…`. План и отчёты: `dsu-plan.md`, `dsu-reports/`.
- `gsh` (`god/runtime/gsh`): теперь тонкая обёртка над `dsu` (совместимость). ВНИМАНИЕ: `$PREFIX/bin/gsh` ставит `GodRuntime` из assets APK и перезаписывает ручную копию при старте Termux, пока APK не пересобран.
- `adbfind` через NSD ищет порты Беспроводной отладки и пишет `$PREFIX/tmp/adb-connect-port` и `adb-pair-port` (второй только пока открыт диалог спаривания).
- Shizuku: `rish` в `~/.local/bin`, `RISH_APPLICATION_ID=com.termux`. Сервис Shizuku Android периодически убивает: тогда `rish` отвечает `Request timeout`.
- Это uid shell, не root: нет доступа к `/data/data/<чужое>`, нет записи в `/system`.

## Рабочий стол и Chrome (на этом устройстве)
- `desk` — XFCE в Termux:X11 с virgl (GPU Mali-G57), `desk stop` останавливает.
- `chromen` — Chrome (glibc, без Debian) с рендером через virgl; сам поднимает `socat` и GL-сервер.
- `go` — всё сразу: `desk` + Chrome; `go stop` — остановить.
- Логи: `~/.desk.log`, `~/.chromen.log`.
- Помощники (`socat`, `virgl_test_server`, Venus) умирают сами, когда Android убивает фоновые процессы.

## Соглашения и осторожность
- Мод живёт в процессе `com.termux`: ошибка в `onLoad` роняет Termux. Защита: `.loading` -> `.crashed`, файл `DISABLED`.
- Не менять `app/testkey_untrusted.jks`, не коммитить `local.properties`.
- Код встроенных модов пишется под Java 8 (`-source 8`) и `minSdk 24` для внешних модов.
- Перед правкой проверяйте, что файл существует, и смотрите на актуальную копию (см. выше).
- MCP `god-ui` (если подключён): скриншоты и тапы по телефону, нужен ADB.

## Не проверено
Пересборка APK на этой машине, запуск встроенных модов после установки APK, работа `adbfind` без Shizuku на текущей сборке. Не утверждайте, что это работает, пока не проверили.

## dsu: уроки и не проверено (итог этапа 5, 2026-11-11)
- `adb disconnect` старых `offline`-записей сразу после нового `adb connect` заставляет новое соединение мигнуть `offline` (ошибка `adb: device offline` на первой команде). Чистить мёртвые записи нужно ДО нового подключения (так сделано в `try_autoadb`).
- Подключение, оставшееся в `adb devices` как `device` после выключения отладки, мёртвое: `find_ep` проверяет живость реальным обращением (`ep_alive`), а не `get-state`.
- Телефон держит точку доступа (`ap0`, 10.14.238.211) и Wi‑Fi (`wlan0`); при смене сети Android сам выключает Беспроводную отладку, порт после включения новый. В файле порта `adbfind` может быть адрес другой сети.
- `tx`: `~`, `$VAR` и `*` раскрывает удалённая оболочка; stdin пересылается, только если он не терминал и нет `-n`.
- Не проверено: `rish` (Shizuku не запущен: проверки 2, 8, 12, 22), `dsu pair` с реальным кодом, экран вкладки `dsu --tab`, диалог HyperOS при программном включении отладки, работа без сети вообще, `tx` через `rish`.
- `$PREFIX/bin/gsh` и новые `dsu`/`dsu-tx-server.py` приедут через `GodRuntime` только после пересборки APK (строка в `god/build-local.sh` уже есть); до этого ручная копия `gsh` откатывается при старте Termux. Мод `dsuset` внешний (`god/mods/dsuset`), грузится при старте Termux.
- Телефон, раздающий точку доступа и подключённый к Wi‑Fi, показывает одно устройство двумя адресами (`ap0` и `wlan0`, один порт); новый `adbfind` подключается к обоим. `dsu` при нескольких `device` берёт адрес из `adb-connect-port` (`find_ep`).
- `$PREFIX/bin/dsu`, `dsu-tx-server.py`, `gsh` теперь поставляет `GodRuntime` из APK: ручная правка `$PREFIX/bin/*` откатывается при следующем старте Termux, пока APK не пересобран (правьте `god/runtime/*`, затем `bash god/build-local.sh`, ~2,5 мин).
- Новый `adbfind` (APK от 2026-11-11 03:29) проверен на устройстве: `adb-state` пишется при старте, при пропаже службы (`disconnected: …`) и при новом порте (`connected …`), автоподключение срабатывает за ~0,2 с на оба адреса. Не проверено: кнопка «Открыть привилегированный терминал» (экран не наблюдался), повторы NSD при кодах ошибок (в логах не было).

