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
cp "$SO" "$OUT/.libcosmic_comp.so.new" && mv -f "$OUT/.libcosmic_comp.so.new" "$OUT/libcosmic_comp.so"   # атомарно: работающий композитор держит старый файл
for l in libinput.so libudev.so.1 libdisplay-info.so; do cp -L "$LIBS/$l" "$OUT/.$l.new" && mv -f "$OUT/.$l.new" "$OUT/$l"; done
# зависимости ищем рядом, затем в Termux (libgbm, libseat, libpixman, libxkbcommon, libevdev, libmtdev …)
patchelf --set-rpath '$ORIGIN:/data/data/com.termux/files/usr/lib' "$OUT/libcosmic_comp.so" "$OUT/libinput.so"
# мост Android -> COSMIC (UPower/NetworkManager из данных мода xfcestatus): god-termux/android-native, каталог bridge
BRIDGE=${GOD_BRIDGE:-$HOME/bridge-target/release/god-android-bridge}
if [ ! -x "$BRIDGE" ] && [ -d "$HOME/android-native/bridge" ]; then
  ( cd "$HOME/android-native/bridge" && CARGO_TARGET_DIR="$HOME/bridge-target" cargo build --release ) || true
fi
if [ -x "$BRIDGE" ]; then
  mkdir -p "$HOME/.termux/mods/cosmic/bin"
  cp "$BRIDGE" "$HOME/.termux/mods/cosmic/bin/.god-android-bridge.new" && mv -f "$HOME/.termux/mods/cosmic/bin/.god-android-bridge.new" "$HOME/.termux/mods/cosmic/bin/god-android-bridge"
else
  echo "предупреждение: нет $BRIDGE, апплеты батареи и сети останутся пустыми"
fi
cat > "$PREFIX/bin/cosmic-native" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# Открыть экран нативного COSMIC (мод cosmic). Журнал мода: $PREFIX/tmp/cosmic-mod.log
exec am start -n com.termux/com.termux.mod.ModActivity --es mod cosmic --es class cosmic.CosmicScreen --ez fullscreen true "$@"
EOS
chmod +x "$PREFIX/bin/cosmic-native"
echo "готово: перезапустите Termux, затем: cosmic-native"
