# Шим glibc/FHS: Todo и отчёт (2026-11-13)

Код: `fhs.c` (LD_PRELOAD), `fhs-run.c` (seccomp-супервизор), `fhsmap.h`, установка и проверка: `app/src/main/java/com/termux/mod/GodRuntime.java`, `termux-shared/.../GodEnvironment.java`, профиль `god/runtime/glibc-fhs.sh`.
Пометки: **проверено** (воспроизведено на телефоне), **гипотеза** (не подтверждено), **не сделано**.

## 1. Unix-сокеты в /tmp не переназначаются (мешает Claude in Chrome)  — не сделано

**Проверено.** Хост расширения Claude (`claude --chrome-native-host`, Bun-бинарник 2.1.322) создаёт сокет по жёсткому пути `/tmp/claude-mcp-browser-bridge-unknown/<pid>.sock`. На Android `/tmp` системный (владелец `shell`, `drwxrwx--x`), приложению писать нельзя. Шим переназначает `/tmp` в `$PREFIX/tmp` только частично:
- `mkdir` переназначается, каталог создаётся в `$PREFIX/tmp`;
- `bind()` (адрес в `sockaddr_un.sun_path`) и проверка существования идут по настоящему `/tmp`.
Следствие: если каталога нет, хост пишет `Socket server error` (bind не находит каталог); если каталог уже есть, `EEXIST: file already exists, mkdir '/tmp/claude-mcp-browser-bridge-unknown'` (mkdir видит каталог, проверка не видит). Переменные `TMPDIR` и `CLAUDE_CODE_TMPDIR` путь не меняют (проверено). Режим `FHS_DEBIAN=1` ничего не меняет.

**Воспроизведение:**
```
echo -n | timeout 4 ~/.claude/chrome/chrome-native-host 2>&1 | tail -c 450
rm -rf $PREFIX/tmp/claude-mcp-browser-bridge-unknown   # прибрать за собой
```

**Что сделать:**
- обернуть `bind()`, `connect()` (и `getsockname`/`accept` при необходимости) в `fhs.c`: для `AF_UNIX` с `sun_path`, начинающимся с `/tmp/`, подставлять `$PREFIX/tmp/` (учесть лимит 108 байт в `sun_path`: `$PREFIX/tmp` это 35 символов, весь путь сокета Claude 80 байт, запас есть, но для длинных имён проверять длину);
- проверить, делает ли Bun эти вызовы через libc или прямыми системными вызовами (`strace` в Termux недоступен; проверить `LD_DEBUG=bindings` или `fhs-run` в режиме трассировки). Если прямыми, то нужна обработка `bind`/`connect` в seccomp-супервизоре `fhs-run` (чтение `sockaddr` из памяти процесса и подмена пути);
- после правки: хост и сессия Claude Code должны видеть один и тот же каталог; тест: `FHS_DEBIAN=1 claude --chrome` плюс расширение в `~/.config/chrome-native`.

## 2. Надёжность установки шима (из отчёта по `exec(login): Permission denied`)

Отчёт: `/sdcard/Download/termux-login-eacces-report.md`. Причина `EACCES` **не подтверждена**.

**Проверено:**
- В `$PREFIX/glibc/god-lib/` остался `libwayland-client.so.0.tmp` (134760 байт, 11-11 17:20): `GodRuntime.copyTree` был прерван, либо `renameTo` не удался (результат повторной попытки не проверяется).
- `.ok` (11-09) не обновляется: `createNewFile()` ничего не делает, если файл есть; при исключении в `init` он остаётся от прошлой версии. `GodEnvironment` подгружает шим, если `.ok` просто существует.
- В failsafe-сессии `glibc-fhs.sh` показал «shim checksum mismatch»: рассинхронизация `SHA256SUMS` и файлов шима была на диске после старта приложения (не из-за `LD_PRELOAD`). Следующий старт приложения всё починил (файлы шима переписаны 11-13 07:48).
- `targetSdkVersion=28`, пакет `com.termux`, домен `untrusted_app_27`: версия «сменился targetSdk» исключена.

**Не сделано (предложения):**
- `GodRuntime`: удалять `.ok` до копирования, создавать только после успешной `verifyShim`; писать в `.ok` хэш `SHA256SUMS`; проверять результат `delete`/`renameTo` и писать ошибку в `$PREFIX/tmp/god-mods.log` (`Log.e` виден только в logcat); чистить остатки `*.tmp` при старте;
- `GodEnvironment`: перед `LD_PRELOAD` проверять, что шим читается и сумма совпадает с записанной в `.ok`; иначе запускать сессию без шима;
- `glibc-fhs.sh`: убрать подсказку `~/mods-src/fhs/install.sh` (скрипта и каталога нет), написать «перезапустите Termux (GodRuntime восстановит шим) или `touch ~/.termux/fhs.off`»;
- запасной шелл в `TermuxSession`, если `login` или его интерпретатор не исполняются;
- при повторе сбоя снять: `adb logcat -d | grep -iE "avc|termux|exec" | tail -60`, `ls -lZ` для `login`, `sh`, `dash`, `glibc/shim` (метки SELinux важнее прав), `dumpsys package com.termux | grep -E "targetSdk|lastUpdateTime"`, `getenforce`.

## 3. god-lib и ручная установка

**Проверено:** при ручной установке `cp -a` оставил `libwayland-client.so.0` симлинком на `libwayland-client.so.0.26.0`, а в APK это обычные файлы. `GodRuntime` сравнивает хэш по ссылке, `.tmp` пишется отдельно: поведения расходятся.
**Не сделано:** копировать `god-lib` без `-a` (разворачивать ссылки), как в APK (`god/build-local.sh` кладёт `~/god-libs/lib*` через `cp -a` в assets: проверить, что в assets попадают файлы, а не ссылки).

## 4. Процесс установки форка

Ставить через приложение-посредник **Termux Rescue** (`~/glbuild/rescue`), а не голым `adb install -r` (см. память проекта и `CLAUDE.md`). Rescue ставит APK тихо через adb без Shizuku и выдаёт права.

## 5. Мелочи

- `fhs-run.c:103`: предупреждение компилятора «incompatible pointer types» (`const uint64_t*` из `__u64[6]`): не влияет на работу, но стоит привести тип.
- `fhs.c` содержит чужие правки, попавшие в коммит `7bcb1f42` (не внедрять шим в бинарники `/system`, `/apex`, `/vendor`; защита от пустого `shim`): добавить тест на них в `god/tests/fhs`.
