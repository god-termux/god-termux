# Отчёт 01: этап 1 (обёртки g* и dsu -c) — Sonnet → архитектору

Дата: 2026-11-10. Канал при проверках: `adb` (127.0.0.1:40927, uid 2000). Shizuku остаётся недоступным (`rish`: Request timeout).

## 1. Что сделано
- **Новый** `/data/data/com.termux/files/home/god-termux/termux-app/god/runtime/dsu` (bash, версия `1.0.0-stage1`). Установлен: `cp god/runtime/dsu $PREFIX/bin/dsu` (идентичен репозиторию, `cmp` ok).
  - Каналы: `rish` (проба `timeout 3 rish -c true`, пустой вывод = успех; кэш `$PREFIX/tmp/dsu-channel`, 30 с, в том числе отрицательный) → `adb`.
  - Endpoint ADB: `--ep` / `DSU_EP` → единственное устройство в `adb devices` (запасной путь из-за дефекта `adbfind`) → `$PREFIX/tmp/adb-connect-port` (с `adb connect`). Несколько устройств → ошибка 125.
  - Режимы: `dsu -c 'строка'` (как есть), `dsu КОМАНДА арг…` (`printf '%q '`; перед командой `mkdir -p` рабочих папок и `HOME`, `TMPDIR`, `TERM`, `LANG`; `PATH` не трогается), `-t`, `--dbg`, `--ep`, `--rish`, `--adb`, `-h`, `--version`, `--`.
  - Подкоманды: `status`, `pair КОД [--pair-ep]`, `--settings`, `--install-wrappers [ИМЯ|ИМЯ:ОБЁРТКА…]`, `--remove-wrappers [ИМЯ…]`.
  - Обёртки по умолчанию: `gpm gam gset gcmd gdumpsys gwm gappops` (маркер `# dsu-wrapper`; чужие файлы не затираются).
  - Конфиг `~/.termux/dsu.conf` (читается, если файл принадлежит пользователю; в этой сессии файла нет, не тестировался).
  - Коды: 125 нет канала, 126 программа Termux, остальные — удалённого процесса.
- **Изменён** `god/runtime/gsh`: тонкая обёртка над `dsu` (`status`, `pair`, `-c`, `gsh cmd…` → `dsu -c "$*"`). Установлена во **все три** копии: `god/runtime/gsh`, `$PREFIX/bin/gsh`, `~/.local/bin/gsh` (идентичны).
- **Изменён** `god/build-local.sh` (строка 67): добавлен `god/runtime/dsu` в копирование в assets.
- Документация: `god/README.md` (пункт про `dsu`), `CLAUDE.md` (описание `dsu`, предупреждение про `gsh`). 
- Не сделано в репозитории: ручных правок вне `god/runtime/` нет (git status: изменены `god/README.md`, `god/build-local.sh`, `god/runtime/gsh`, `god/fhs/fhs.c` — последний не мой, был изменён до работы; новые: `god/runtime/dsu`, `CLAUDE.md`, `context.md`, `dsu-plan.md`, `dsu-reports/`, `fix-adbfind-endpoint.md`).

## 2. Что проверено на устройстве
| № | Проверка | Команда | Вывод (сокращённо) | Результат |
|---|---|---|---|---|
| 1 | Окружение через adb | `dsu env \| grep …` | `HOME=/data/local/tmp/dsu/home`, `TMPDIR=…/tmp`, `TERM=xterm-256color`; нет `LD_*`, `PREFIX`, `TERMUX_*` | ок |
| 3 | Программы Termux + подсказка | `dsu python`; `dsu …/usr/bin/bash -c true`; `dsu ls /data/data/com.termux/files` | `inaccessible or not found` + подсказка, rc 126; `Permission denied` rc 1 | ок |
| 7 | Кавычки | `dsu printf '[%s]\n' 'a b' "c'd" 'e"f' '$HOME' $'x\ny' 'юни код' '' '*' ';id'` | `cmp` с ожидаемым: SAME | ок |
| 8 | Коды и stdin | `dsu false`; `dsu sh -c 'exit 7'`; `echo hi \| dsu cat` | 1; 7; `hi` | ок (adb) |
| 10 | Без Shizuku | `dsu status` | канал `adb (127.0.0.1:40927)`, uid 2000 | ок |
| 12 | Shizuku недоступен → fallback | `rm dsu-channel; time dsu id -u` | 3,4 с (проба 3 с), далее с кэшем 0,34 с | ок для «Shizuku мёртв»; сценарий «убит в процессе работы» не воспроизведён (см. п. 3) |
| 16 | gsh | `gsh status`; `gsh -c id`; `gsh pm list packages` | работают, 506 пакетов | ок |
| 17 | Обёртки | `dsu --install-wrappers`; `gpm list packages`, `gset get global adb_wifi_enabled` (→1), `gdumpsys battery`, `gwm size` (1080x2392), `gam get-current-user` (0), `gcmd package list packages`; `--remove-wrappers`; чужой файл `gtestmine` | все работают; чужой файл не затёрт; удаляются только с маркером | ок |
| 18 | Недоступный канал | отдельный пустой adb-сервер (`ADB_SERVER_SOCKET=tcp:localhost:5099`, реальное устройство не тронуто): `gpm list packages` | одно сообщение, rc 125; 3,4 с (первый вызов), 0,32 с (с кэшем) | ок (≤10 с) |
| 19 | Запасной поиск endpoint | `dsu status` при отсутствии `adb-connect-port` | «endpoint из adb devices (adbfind не опубликовал adb-connect-port)» | ок |
| — | `-t` в настоящем pty | python-pty: `dsu -t -c 'tty; echo T=$TERM'` | `/dev/pts/32`, `T=xterm-256color`, rc 0 | ок |
| — | `--dbg`, `pair` без кода (rc 2), `--settings` | | показывает канал/строку; usage; открыто «Для разработчиков» | ок |

## 3. Что не проверено и почему
- Проверки 2 и 8 для `rish`, 12 в варианте «Shizuku убит во время работы»: Shizuku недоступен, запуск требует действия пользователя на экране (отложено по плану).
- `dsu pair КОД` с реальным кодом (нужно открытое окно сопряжения) и `adb pair` вывод — не запускался; проверена только валидация.
- `~/.termux/dsu.conf` (чтение/приоритет флагов) не тестировался.
- `gsh pair` через обёртку не запускался.
- Сборка APK не выполнялась (`build-local.sh` только отредактирован).

## 4. Отклонения от плана (с причиной)
1. **Локальная подсказка про программы Termux срабатывает после удалённого кода 127, а не до обращения к каналу.** Причина: в `$PREFIX/bin` есть собственные `am`, `pm`, `settings`, `cmd`, `logcat` (обёртки Termux), и проверка «до» давала бы ложный код 126 для `gam` и т. п. Теперь подсказка (и 126) только если удалённая сторона не нашла команду, а локально она лежит в `$PREFIX`.
2. **Имя обёртки для `settings` — `gset`** (по плану), а не `gsettings` (общее правило `g<имя>` дало бы конфликт с glib `gsettings`). Реализовано как исключение + синтаксис `КОМАНДА:ОБЁРТКА`.
3. В режиме команды выставляются только `HOME`, `TMPDIR`, `TERM`, `LANG` (без `env -i`), чтобы не трогать `PATH` — по замечанию архитектора.
4. Кэш канала хранит только состояние `rish` (up/down) на 30 с; endpoint ADB находится заново каждый раз (дёшево: `adb devices`).
5. Не реализовано в этапе 1 по плану: интерактивный режим `dsu` без аргументов (печатает сообщение, rc 2), `-s -p -a -E`, `push`.

## 5. Риски и находки вне плана
- **`$PREFIX/bin/gsh` управляет `GodRuntime`** (ставится из assets установленного APK; `mtime` совпадает с запуском приложения). При следующем старте Termux ручная копия обновлённого `gsh` будет **перезаписана старой** версией (хеш отличается), пока APK не пересобран. Старый `gsh` продолжит работать как раньше (`rish` → adb через файл порта), но без запасного поиска через `adb devices`. Для `dsu` такой проблемы нет (в assets установленного APK нет `bin/dsu`). После пересборки APK новая версия `gsh` и `dsu` приедут сами.
- В `$PREFIX/bin` у Termux есть свои `am`, `pm`, `settings`, `cmd`, `logcat`; префикс `g` их не затеняет. `gsettings` — программа glib, поэтому `gset`.
- Первый вызов после истечения кэша (30 с) при мёртвом Shizuku стоит 3 с проба; серия вызовов — по 0,3 с.
- Время `dsu status` ~4 с (проба rish 3 с + запросы).
- Дефект `adbfind` (нет `adb-connect-port`) остаётся, в этап не входит; закрыт запасным поиском через `adb devices`. Подробности: `fix-adbfind-endpoint.md`.
- Безопасность не оценивалась (решение пользователя).

## 6. Предложение следующего шага
Этап 2: интерактивный `dsu` (`env -i … ENV=rc sh -i` явно, rc по хэшу, баннер «программ Termux нет», `PS1`, `-s/-p/-a/-E`, `dsu push`, обрыв соединения), проверки 2, 4–6, 9, 11, 14, 15. Для пунктов с `rish` нужен запуск Shizuku (действие пользователя). Жду «принято / доработать».
