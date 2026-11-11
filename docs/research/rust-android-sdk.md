# Rust и Android SDK: исследование (agy, 2026-11-11)

Ответ внешнего исследователя (Antigravity), **непроверенный материал** с моими пометками.

Проверено 2026-11-11 (GitHub API, crates.io): репозитории `termi`, `Rin`, `jni-rs`, `android-activity`, `robusta` существуют и живы;
`jni-bindgen` не обновлялся с апреля 2023 (заброшен); issue `alacritty#7121` называется «Porting to android»;
`alacritty_terminal` 0.26.0 (апрель 2026).
Неточно: `robusta` не «заморожен» (обновлён в сентябре 2026). Не проверено: пакет `rust` в Termux и сборка под Android без NDK.

---

### 1. Крейты и доступ к Java-слою Android SDK

| Инструмент / Крейт | Что покрывает и роль | Состояние и поддержка (2026) |
| :--- | :--- | :--- |
| **`jni`** ([jni-rs/jni-rs](https://github.com/jni-rs/jni-rs)) | Низкоуровневая обёртка над JNI C API (`JNIEnv`, вызовы методов через текстовые сигнатуры типа `"(Ljava/lang/String;)V"`, управление ссылками `GlobalRef`, исключения). Позволяет сделать **всё** (Service, Intent, Notification), но вручную. | **Жив, стандарт де-факто** (v0.21+). Фундамент для любого вызова Android SDK из Rust. |
| **`jni-bindgen` / `jni-android-sys`** ([MaulingMonkey/jni-bindgen](https://github.com/MaulingMonkey/jni-bindgen)) | Генератор Rust-байндингов по `.class`/`.jar` файлам (включая `android.jar`). Идея — генерировать методы для всего Android API. | **Практически заброшен / нежизнеспособен.** Сложные сигнатуры, циклические зависимости Java-классов и чудовищное время компиляции. Полноценного рабочего генератора ко всему `android.jar` на сегодня нет. |
| **`robusta_jni`** ([giovanniberti/robusta](https://github.com/giovanniberti/robusta)) | Процедурные макросы (`#[bridge]`) для автоматической генерации JNI-клея и конвертации типов (Java ↔ Rust). | **Заморожен/нишевый.** Удобен для простых типов, но для сложного Android lifecycle не спасает от бойлерплейта. |
| **`ndk` / `ndk-sys`** ([rust-mobile/ndk](https://github.com/rust-mobile/ndk)) | Безопасные и raw привязки к **Android NDK C API**: `AAssetManager`, `ANativeWindow`, `ALooper`, `AInputQueue`, `AAudio`. | **Активно поддерживается.** Не покрывает чисто Java-вещи (Intent, Service, Notification), только то, что экспортирует NDK. |
| **`ndk-context`** ([rust-mobile/ndk-context](https://github.com/rust-mobile/ndk-context)) | Синглтон для хранения глобальных указателей на `AndroidApp` / `JavaVM` / `android.content.Context`. | **Стабилен.** Нужен, чтобы любая Rust-библиотека могла достать `Context` для JNI-вызовов. |
| **`android-activity`** ([rust-mobile/android-activity](https://github.com/rust-mobile/android-activity)) | Замена устаревшего `ndk-glue`. Предоставляет `android_main` цикл для `NativeActivity` и `GameActivity`. | **Стандарт rust-windowing/winit.** Дает доступ к `NativeWindow`, `SurfaceView`, touch/key событиям, IME (через `GameActivity`). **Не умеет** управлять внешними Service, ContentProvider или BroadcastReceiver. |
| **`winit`** ([rust-windowing/winit](https://github.com/rust-windowing/winit)) | Окно, обработка сенсорного ввода и событий. Под капотом использует `android-activity`. | **Активен.** Работает отлично для отрисовки через wgpu/OpenGL, но для сложного текста/IME на мобильных экранах требует костылей. |
| **UI-фреймворки:** `egui`, `Slint`, `Dioxus`, `makepad` | Отрисовка UI через GPU/Canvas/Webview. | `egui`/`Slint` работают через `winit`/`android-activity`, но не имеют нативных мобильных виджетов Android (шторки, системные меню, нативный text selection). `Dioxus`/`Tauri Mobile` опираются на `android.webkit.WebView`, где под капотом всё равно Java-обвязка. |
| **`rust-android-gradle`** | Плагин для интеграции `cargo` в процесс сборки Gradle. | **В Termux не нужен и не применим**, так как рассчитан на хост-машины (PC) с установленным Android Studio/NDK. |

> **Суть и практический совет:** 
> Нет волшебной библиотеки, которая предоставит идиоматичный `android::content::Intent` или `android::app::Notification` из коробки. Автоматические генераторы ко всему `android.jar` ломаются на реальных проектах. Единственный стабильный путь работы с Android SDK из Rust — вызывать конкретные методы Java через крейт **`jni`** (или писать тонкие фасады на Java/Kotlin и дергать их через native-методы).

---

### 2. Реальные проекты терминалов на Rust под Android

1. **[MannanSaood/termi](https://github.com/MannanSaood/termi)**
   * **Архитектура:** Kotlin + Jetpack Compose для интерфейса, Rust-ядро через JNI для PTY, терминальной логики и VFS (Storage Access Framework).
   * **Решение:** IME, буфер обмена и сервисы полностью отданы на уровень Android SDK (Jetpack Compose / Android Service). Rust отвечает только за эмуляцию VT, парсинг ANSI и управление процессами.
2. **[pavelc4/Rin](https://github.com/pavelc4/Rin)**
   * **Архитектура:** Экспериментальный терминальный эмулятор для Android, совместимый с Termux-пакетами. Построен на Kotlin + Jetpack Compose с подключением Rust-компонентов через JNI.
3. **Порты Alacritty:**
   * Проект [alacritty/alacritty#7121](https://github.com/alacritty/alacritty/issues/7121) годами обсуждает порт на Android. Опытные попытки запустить его через `NativeActivity`/`android-activity` упираются в неполноценный мобильный ввод текста (soft keyboard/IME), отсутствие виртуальных клавиш (Ctrl, Alt, стрелки), выделения текста и шторки уведомлений.
4. **Helix и Zed:**
   * **[helix-editor/helix](https://github.com/helix-editor/helix)**: Работает на Android исключительно как CLI-бинарник **внутри Termux** (рендеринг в PTY Termux, системный IME и ввод обрабатываются Java-слоем Termux).
   * **[zed-industries/zed](https://github.com/zed-industries/zed)**: Нативно на Android не портирован (его движок GPUI не имеет мобильного бэкенда для Android Surface/IME).

> **Суть и практический совет:**
> Ни один успешный терминал под Android не написан на 100% на Rust через `NativeActivity`. Все живые проекты делят зоны ответственности: **UI, IME, Background Service, Notifications — на Java/Kotlin**, а **парсер эмулятора терминала (grid, escape sequences, PTY handling) — на Rust (библиотека `.so`)**.

---

### 3. Ограничения Android: что невозможно без Java/Dex

Android — операционная система на базе JVM/ART. Ядро системы (`system_server`, `ActivityManagerService`) общается с приложениями через межпроцессный IPC (Binder), строго типизированный интерфейсами Android SDK:

* **Что жестко требует Java-класс в `.dex`:**
  1. **`Service` (включая Foreground Service):** Без наследника `android.app.Service`, зарегистрированного в `AndroidManifest.xml`, ОС убьет процесс при сворачивании. `NativeActivity` сервисом быть не может.
  2. **`BroadcastReceiver`:** Для статических ресиверов (например, автозапуск `BOOT_COMPLETED` или прием интентов `RUN_COMMAND`) манифест требует имя класса.
  3. **`ContentProvider`:** Необходим, если вы предоставляете доступ к файлам другим приложениям (shared-файлы, SAF).
  4. **Полноценный IME / `InputConnection`:** `NativeActivity` пересылает только примитивные сканкоды клавиш. Для свайпов, подсказок словаря, китайских/эмодзи иероглифов и выделения текста Android вызывает методы `onCreateInputConnection()`, что реализуется в Java-классе View.

* **Можно ли сгенерировать тонкий Java-слой автоматически?**
  * `GameActivity` поставляется с готовым предкомпилированным AAR, но это решает проблему только для экрана игры/рендеринга (`Activity`).
  * Для `Service` и `BroadcastReceiver` кодогенерация (через DexMaker или предкомпилированные заглушки) **неоправданно усложняет проект**: вы всё равно вынуждены иметь `classes.dex` с классами-прокси, которые лишь пробрасывают вызовы в `native void onStartCommand()`. 
  * Написать вручную 3–4 небольших Java/Kotlin-класса (Service, Receiver, MainBridge) на 100–200 строк на порядки надежнее, чем пытаться компилировать dex на лету.

---

### 4. Сборка Rust-библиотеки и APK прямо на телефоне (внутри Termux)

Сборка прямо в среде Termux aarch64 имеет специфику:

1. **Rust и компилятор:**
   * В Termux ставится нативный пакет: `pkg install rust clang binutils`.
   * Он генерирует код под целевую ОС (Android Bionic).
2. **Нужен ли NDK?**
   * Официальный Google NDK распространяется под x86_64 Linux/macOS/Windows. Официального таргета NDK для запуска на AArch64 нет.
   * **Однако для Termux NDK не нужен:** окружение Termux само по себе работает на базе Bionic libc и `clang`, предоставляя заголовки и библиотеки Android. 
   * Если собирать `cdylib` (`crate-type = ["cdylib"]`), достаточно вызвать:
     ```bash
     cargo build --release
     ```
     На выходе получается `target/release/libtermux_core.so`, слинкованная с системной Bionic libc.
3. **Почему не подходят `cargo-ndk`, `cargo-apk`, `xbuild` на телефоне:**
   * Они рассчитаны на запуск на десктопе, ищут пути к `ANDROID_NDK_HOME` (папки `toolchains/llvm/prebuilt/linux-x86_64`) и вызывают кросс-компиляцию. На телефоне их запуск ломается.
4. **Сборка APK на устройстве:**
   * Полноценная схема без Android Studio внутри Termux работает на связке:
     `cargo` → `lib<name>.so` → `aapt2` (компиляция ресурсов) → `d8` (компиляция Java в `classes.dex`) → сборка zip/apk с помещением библиотеки в `lib/arm64-v8a/lib<name>.so` → `apksigner`.
   * Либо через вызов локального `gradlew` (в Termux доступен OpenJDK: `pkg install openjdk-17`).

---

### 5. Реалистичная оценка: скорость, безопасность и путь миграции

#### Что даст и что НЕ даст переписывание на Rust:
* **Скорость вывода символов и парсинга (ДА):** Обработка кольцевого буфера терминала, парсинг ANSI/VT-последовательностей, reflow текста и декодирование UTF-8 на Rust (например, на базе крейта `alacritty_terminal`) будут работать заметно быстрее и с нулевым давлением на сборщик мусора (GC). В Termux на Java при гигантском потоке логов GC ART вызывает микрофризы.
* **Отрисовка (НЕТ):** Если рендерить через Android `Canvas` (`drawText`), отрисовка упирается в системный Skia/HWUI и шрифт `Typeface`. Если переходить на GPU (OpenGL ES / Vulkan via wgpu), отрисовка ускорится, но сложность поддержки мобильных шрифтов (глифы, HarfBuzz, fallback для emoji) вырастет в разы.
* **fork/exec и ядро (НЕТ):** Вызовы `forkpty()`, `execve()` и работа с PTY — это системные вызовы Linux. Скорость одинакова как из C/Rust, так и через JNI из Java.
* **Безопасность (ЧАСТИЧНО):** Защита от утечек памяти и race conditions в парсере терминала, но на Android права приложения всё равно ограничены песочницей Android SELinux / uid.

#### Рекомендуемый путь миграции:

1. **Не делать полный Rust UI (winit/NativeActivity/GameActivity):**
   Это тупик для терминала: вы потеряете нативную экранную клавиатуру Android, автодополнение, контекстное меню копирования/вставки, шторку уведомлений Termux и поддержку плагинов по Intent/Broadcast.
2. **Идеальный подход — гибридный:**
   * **Шаг 1 (Rust Core):** Вынести тяжелую логику эмулятора терминала (буфер экрана, парсинг VT100/xterm, обработка PTY) в Rust-библиотеку `cdylib` (можно взять готовый проверенный движок [alacritty_terminal](https://crates.io/crates/alacritty_terminal)).
   * **Шаг 2 (JNI-мост):** Связать Rust-ядро с Android через тонкий JNI-слой. Rust отдает плоский массив символов/цветов на экран, Java просто рисует их на `SurfaceView` / `TextureView`.
   * **Шаг 3 (Java/Kotlin для платформы):** Оставить `TermuxService`, `Notification`, `TermuxSession`, обработчики `RUN_COMMAND` интентов на Java или постепенно перевести их на Kotlin.

---

### Где есть неопределенность и источники расходятся

1. **Полноценный запуск Clang NDK внутри Termux для нестандартных крейтов:**
   Если сторонний Rust-крейт внутри своего `build.rs` жестко требует `aarch64-linux-android-clang` из путей официального NDK, компиляция внутри Termux падает без использования сторонних сборок комьюнити NDK (например, репозиториев `lzhiyong/termux-ndk`).
2. **IME в GameActivity:**
   Google активно развивает `GameTextInput` в AGDK, и в крейте `android-activity` есть базовые биндинги. Однако документация и опыт разработчиков показывают, что ввод сложных CJK-иероглифов и управление предиктивным вводом в pure-native приложениях на Rust остаются нестабильными по сравнению со стандартным `InputConnection` в Java.

---

### Итог

* **Что сделать в первую очередь:** Оставить скелет приложения (Activity, Foreground Service, IME, BroadcastReceiver) на Java/Kotlin. Переписать на Rust **только PTY-рантайм и парсер терминального буфера** в виде `.so`-библиотеки через крейт `jni`.
* **Что вряд ли поможет:** Пытаться переписать всё приложение в единый native-бинарник через `NativeActivity` / `winit` или генерировать обёртки ко всему `android.jar`. Это приведет к потере фоновой стабильности Termux, отказу экранной клавиатуры и невозможности обрабатывать системные интенты Android.
