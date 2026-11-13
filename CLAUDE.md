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
**Установка APK форка: через приложение-посредник Termux Rescue (`com.termux.rescue`, исходники `~/glbuild/rescue/`; `am start -a com.termux.rescue.INSTALL --es path /sdcard/Download/x.apk`), не голым `adb install -r`/`pm install -r`** (после `adb install -r` обычная сессия не стартовала: `exec(login): Permission denied`, причина не подтверждена; разбор в `/sdcard/Download/termux-login-eacces-report.md`, см. также память проекта). Установка перезапускает Termux. Установка APK: на этом телефоне (Redmi, HyperOS 3) системе нужен разблокированный экран, и может потребоваться «Установка через USB». Программно: `gsh pm install -r файл.apk` (может вернуть `INSTALL_FAILED_USER_RESTRICTED`, если запрещено в настройках разработчика). Первый запуск собранного APK не проверялся.

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
- Беспроводная отладка: программное включение (`Settings.Global adb_wifi_enabled`, мод `dsuset`) система откатывает, когда у телефона нет Wi‑Fi клиента (только режим модема `ap0` и мобильные данные), а включение вручную в «Для разработчиков → Беспроводная отладка» в этом же состоянии работает и держится (проверено 2026-11-11: `dsu` сразу подключился, порт `10.14.238.211:…`). Видимо, у системного пункта есть шаг, которого нет у записи настройки; какой именно, не выяснено (системный лог при уровне журнала Error пуст).
- `god/tools/glbench` (`./build.sh`, ставит `glbench` и `vkbench` в `$PREFIX/bin`): замер GPU. `glbench default` (платформенный GLES через libEGL.so), `glbench angle vulkan` (ANGLE из `/system/lib64/libEGL_angle.so`, работает без настроек системы, хотя переключатель ANGLE в «Для разработчиков» серый: `ro.gfx.angle.supported` пусто), `vkbench` (нативный Vulkan, `/system/lib64/libvulkan.so`). Результаты на Mali-G57 MC2 (3 круга, 2026-11-11): тяжёлый шейдер 1280x720 — GLES 221-231, Vulkan 224-226, ANGLE 197-207 кадров/с; вызовы отрисовки — GLES 181-228k, Vulkan 212-215k, ANGLE 142-157k вызовов/с.

## GPU: сравнение путей рендера (Mali-G57 MC2, 2026-11-11, `god/tools/glbench/gpubench`)
Тот же офскрин-тест 1280x720 (тяжёлый шейдер, кадров/с; вызовы отрисовки, тыс./с): Vulkan — родной драйвер Mali 226 / 223; PanVK zenithblue (bionic) 186 / 239; PanVK zenithblue (glibc) 189 / 148; PanVK apexspan (bionic) 186 / 151. OpenGL ES — родной 221-247 / 181-228; ANGLE→Vulkan 197-207 / 142-157; Zink→PanVK zenithblue 182 / 136; Zink→PanVK apexspan 163 / 63.
- Открытый Vulkan-драйвер PanVK поверх `kbase` (`/dev/mali0`) работает на этом телефоне: [zenithblue-oss/panvk-kbase-android](https://github.com/zenithblue-oss/panvk-kbase-android) (релизы bionic И glibc, glibc-сборка слинкована с `libwayland-client` и XCB, т. е. умеет вывод в Wayland) и [apexspan-svg/mesa-panvk-mali-g57](https://github.com/apexspan-svg/mesa-panvk-mali-g57) (только X11). Сторонние бинарники, не проверены; ставятся только в `~/gpu-drivers` через `VK_ICD_FILENAMES`.
- glibc-сборка zenithblue в Debian 13 требует: `libwayland-client` ≥ 1.24 (взят 1.26 из Debian unstable, распакован в `~/gpu-drivers/lib`; в Debian 13 версия 1.23 без `wl_fixes_interface`) и `libSPIRV-Tools.so` (в Debian только статические; для замера использована заглушка из 5 функций отладочного дизассемблера, `~/gpu-drivers/lib/spvstub.c`). Это временные обходные пути, а не чистое решение.
- Запуск glibc-программ с нестандартным загрузчиком: шим удаляет `LD_LIBRARY_PATH` (отключается `FHS_KEEP_LDLP=1`), `timeout` нужно ставить перед `env`, а не после (иначе glibc-каталог попадёт в bionic-процесс).
- Не проверено: вывод окна драйвером PanVK в наш композитор (Wayland WSI), корректность картинки (в `vkbench` нет проверки пикселей), стабильность и нагрев, Chromium через ANGLE→Vulkan→PanVK.


## Chromium на нашем Wayland и 60 кадров (2026-11-11)
- Debian Chromium идёт прямо на `wayland-0` (`wlchromium`). `WLCHROMIUM_GPU=panvk wlchromium URL` — рендер через ANGLE → Vulkan → PanVK (glibc-сборка zenithblue из `~/gpu-drivers`, нужны настоящие `libSPIRV-Tools.so` и `libwayland-client` 1.26 из `$PREFIX/glibc/god-lib`, их собирает `god/tools/gpu/build-glibc-libs.sh`, шим ставит этот каталог первым в `--library-path`).
- Собственный Vulkan-вывод Chromium на ozone-wayland не работает («not compatible with Vulkan», «Failed to create vulkan surface»): PanVK используется только слоем ANGLE (GL поверх Vulkan).
- **Потеря кадров была не в копии, а во времени отправки `frame callback`:** композитор слал его после блокирующего `eglSwapBuffers` (vsync), и клиент с композитором работали по очереди. Флаг `~/.termux/wl-pipeline` (настройка «Конвейер кадров», по умолчанию вкл.) шлёт callback сразу после приёма кадра. Лёгкая страница (`bench.html?light=1`, окно 2392x903): PanVK 45,7 → 59,5 кадров/с (медиана 16,6 мс), программный 47 → 52,8. Передача буфера без копии (`dmabuf` → `AHardwareBuffer`) для этого не понадобилась.
- Тяжёлая страница (градиент на всё окно + 300 полупрозрачных прямоугольников): PanVK 26 кадров/с, с `--enable-gpu-rasterization --enable-accelerated-2d-canvas --canvas-oop-rasterization` 30,6 (в `wlchromium` включены). Это уже предел заполнения/растеризации, а не передачи кадра.
- Замеры только при открытом экране Wayland: в фоне страница не отчитывается. Режим «Замер» композитора должен быть выключен.
- Не проверено: программный режим на тяжёлой странице с `wl-pipeline` (страница не отчиталась), реальные сайты и видео, влияние на батарею.
- **120 Гц (2026-11-11, позже):** панель поддерживает 30/60/90/120 Гц, HyperOS переключается настройками `miui_refresh_rate`/`user_refresh_rate` (system и secure; `peak_refresh_rate`/`min_refresh_rate` его не переключают). Композитор раньше зашивал `wl_output.mode` как 60000 мГц; теперь Java передаёт `Display.getRefreshRate()` (`nativeRefresh`), в настройках Wayland есть «Частота кадров экрана» (Авто/30/60/90/120). Но Chromium сам ограничивает себя ~60 кадрами: с `--disable-frame-rate-limit --disable-gpu-vsync` (теперь стандартно в `wlchromium`) лёгкая страница даёт 113 кадров/с (медиана 7,6 мс) вместо 64. Тяжёлая страница остаётся ~28–31 (упирается в нагрузку, не в лимит). Закрепление Chromium на быстрых ядрах 6–7 ухудшает (20 кадров): у него много потоков.

## Публикация сборок (2026-11-13)
- Готовые сборки лежат в приватном репозитории `CrafterMinecrafter/god-termux-builds` (только релизы, исходников нет). Публикует `bash god/tools/release.sh [--beta] [--no-build] [--apk F] [--notes ТЕКСТ] [--keep N]`: берёт APK из `~/god-build`, читает `versionCode`/`versionName` через `aapt2`, считает SHA-256, создаёт релиз `v<версия>-<дата>` (стабильный) или `beta-<дата>` (prerelease) с APK и `latest.json`, оставляет последние N релизов канала (по умолчанию 10).
- `GET /releases/latest` НЕ возвращает prerelease (404): читать список `/releases` и выбирать по префиксу тега (`v` или `beta-`). Скачивание актива из приватного репозитория: `Accept: application/octet-stream` на `/releases/assets/{id}` с токеном.
- Токен для Rescue: fine-grained, только репозиторий `god-termux-builds`, Contents: read-only, срок 90 дней; создаётся на сайте GitHub (через API/`gh` создать нельзя). Через `gh` нельзя положить файлы в `.github/workflows` (нет scope `workflow`), поэтому очистка старых релизов встроена в `release.sh`.
- Не проверено: чтение релиза самим Rescue (кнопки «Проверить обновления» и «Установить последнюю сборку» ещё не сделаны).
