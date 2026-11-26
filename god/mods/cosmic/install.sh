#!/data/data/com.termux/files/usr/bin/bash
# Установка мода cosmic на телефоне (без пересборки APK): мод (dex + JNI) через modbuild и библиотеки композитора.
#   install.sh [путь/libcosmic_comp.so]   — по умолчанию ~/cc-android-target/release/libcosmic_comp.so
# Зависимости (libinput, libudev, libdisplay-info) собирает god-termux/android-native/scripts/build-libs.sh.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SO=${1:-$HOME/cc-android-target/release/libcosmic_comp.so}
LIBS=${COSMIC_LIBS:-$HOME/android-libs/prefix/lib}
[ -f "$SO" ] || { echo "нет $SO (соберите cosmic-comp: ветка android-native форка god-termux/cosmic-comp)"; exit 1; }
modbuild "$HERE"
OUT=$HOME/.termux/mods/cosmic/lib
mkdir -p "$OUT"
cp "$SO" "$OUT/libcosmic_comp.so"
for l in libinput.so libudev.so.1 libdisplay-info.so; do cp -L "$LIBS/$l" "$OUT/$l"; done
# зависимости ищем рядом, затем в Termux (libgbm, libseat, libpixman, libxkbcommon, libevdev, libmtdev …)
patchelf --set-rpath '$ORIGIN:/data/data/com.termux/files/usr/lib' "$OUT/libcosmic_comp.so" "$OUT/libinput.so"
cat > "$PREFIX/bin/cosmic-native" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# Открыть экран нативного COSMIC (мод cosmic). Журнал мода: $PREFIX/tmp/cosmic-mod.log
exec am start -n com.termux/com.termux.mod.ModActivity --es mod cosmic --es class cosmic.CosmicLauncher --ez fullscreen true "$@"
EOS
chmod +x "$PREFIX/bin/cosmic-native"
echo "готово: перезапустите Termux, затем: cosmic-native"
