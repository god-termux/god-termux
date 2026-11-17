# ПК тест-версия: настоящий APK в Android-эмуляторе на диске E:

Цель: проверять сплит-скрин ([split_screen_plan.md](split_screen_plan.md)) на ПК без телефона. Собираем настоящий debug APK, ставим в эмулятор и гоняем сценарии скриптами. Всё, что весит много, лежит на `E:` (на `C:` свободно всего ~10 ГБ).

## Что уже есть / чего не хватает (проверено)
| Пункт | Состояние |
|---|---|
| `E:\android-sdk` (platform-tools, cmdline-tools, build-tools 35/36, platforms android-36, ndk) | есть, `local.properties` уже указывает на него |
| JDK | OpenJDK 21 |
| `emulator\emulator.exe` | **нет**, нужно поставить |
| `system-images` | **нет**, нужно поставить |
| AVD | нет |
| APK x86 / x86_64 | **уже собраны**: `app/build/outputs/apk/debug/termux-app_apt-android-7-debug_x86_64.apk` и `..._x86.apk` (плюс universal, arm) |
| Bootstrap x86_64 / i686 | вшит в приложение (`app/src/main/cpp/bootstrap-x86_64.zip`, `bootstrap-i686.zip`) |
| Железо | Ryzen 7 5700X, 32 ГБ ОЗУ; Hyper-V службы запущены, поэтому ускорение через WHPX. Включена ли функция Windows Hypervisor Platform, не проверено (нужны права администратора) |
| Место на `E:` | 44 ГБ, хватит (эмулятор ~1 ГБ, образ ~1.5 ГБ, AVD 4-6 ГБ) |

## Решения
- **Образ:** `system-images;android-30;default;x86_64` (AOSP без Google-сервисов, лёгкий). У приложения `targetSdk=28`, так что запуск бинарников из данных приложения работает.
- **APK:** ставим готовый `..._x86_64.apk`. Если пересборка не нужна, `install.ps1` просто делает `adb install -r`; при изменении кода пересобирает `:app:assembleDebug` (сплит по ABI уже включён). Опционально второй AVD `x86` (32-бит, `android-30;default;x86`) для проверки `..._x86.apk`.
- **Расположение:** `ANDROID_HOME=E:\android-sdk`, `ANDROID_AVD_HOME=E:\android-avd`, `ANDROID_USER_HOME=E:\android-avd\.android`. Тогда AVD и кэши не попадают на `C:`.
- **Экран AVD** (`hw.lcd.width/height/density`): телефон 1080×2400, 420 dpi (портрет) плюс профиль планшета 1600×2560 для проверки раскладок. RAM 3 ГБ, 4 ядра, `-gpu host`.
- **Управление:** только `adb` (`input tap/swipe/text/keyevent`, `exec-out screencap -p`, `uiautomator dump`, `am start`). Ничего, кроме platform-tools, не нужно.
- **Запуск без окна** (`-no-window`) для автотестов, с окном для ручной проверки.

## Структура файлов (создаются при реализации)
```
god/emu/
  env.ps1              # ANDROID_HOME/AVD_HOME, функции adb/emulator
  setup.ps1            # sdkmanager: emulator + образ; avdmanager create (phone, tablet)
  start.ps1 [-Avd phone|tablet] [-Headless]   # запуск, ожидание sys.boot_completed
  install.ps1          # gradlew :app:assembleDebug -> adb install -r (x86_64 APK)
  bootstrap.ps1        # первый запуск: ждёт, пока Termux скачает bootstrap и покажет prompt
  scenarios/           # сценарии сплита (см. ниже)
  run-all.ps1          # прогон всех сценариев, отчёт и скриншоты в god/emu/out/
```

## Сценарии (по плану сплита)
Каждый сценарий: действия через `adb input`, затем проверка (a) скриншотом, (b) `stty size` / вывода в нужной панели, (c) `uiautomator dump` или лог-маркеры.
1. **Регрессия 1 панели:** `echo hi`, extra keys, drawer, новая сессия.
2. **Сплит до 4:** меню панели → разделить ↔ / ↕; проверка, что панелей не больше 4; в каждой сессии запустить `yes | head`/`date`, убедиться, что вывод идёт параллельно.
3. **Фокус:** тап по каждой панели → в ней появляется введённый текст, остальные не меняются; клавиатура остаётся открытой (по `dumpsys input_method` / `mInputShown`).
4. **Разделители:** `input swipe` по разделителю → проверить `stty size` до и после (PTY ресайзится).
5. **Drawer:** выбор занятой сессии → swap панелей; `exit` в панели → замена сессии или закрытие панели.
6. **Поворот:** `settings put system user_rotation 1` → все панели пересчитали размер, текст не потерян.
7. **Сохранение:** `am force-stop` + запуск → раскладка и фокус восстановились.
8. **Multi-window Android:** `adb shell cmd activity` (resize/split) → панели живы.

## Диагностика и CI-режим
- Лог-маркеры в коде сплита (`Logger` с тегом `GodSplit`: фокус, ratio, assign) читаются через `adb logcat -s GodSplit` и используются в проверках вместо хрупких пиксельных сравнений.
- `run-all.ps1` возвращает ненулевой код при падении сценария и складывает `logcat`, скриншоты и дамп UI в `god/emu/out/<дата>/`.

## Порядок работ
1. `setup.ps1` и `start.ps1`: поставить эмулятор и образ, поднять AVD, проверить WHPX (при необходимости включить функцию Windows Hypervisor Platform, нужен администратор и перезагрузка).
2. `install.ps1`, `bootstrap.ps1`: поставить готовый x86_64 APK (пересобрать при необходимости), довести до рабочей оболочки. Это базовая точка до начала сплита.
3. Сценарий 1 (регрессия) и общие хелперы (`tap-pane`, `shot`, `shell-in-pane`).
4. Остальные сценарии по мере готовности сплита.

## Риски
- Без включённого WHPX/Hyper-V эмулятор будет в 10-20 раз медленнее. Проверяется первым шагом.
- Bootstrap вшит в APK, сеть для первого запуска не нужна (пакеты через `pkg` всё же качаются из сети).
- Тапы по координатам привязаны к размеру экрана; координаты считаем от `wm size`, а не зашиваем.
