#!/usr/bin/env bash
# CI (Ubuntu runner): the native pieces that god/build-local.sh builds on the phone and that Gradle/ndk-build cannot:
#   * libwlcomp.so (Wayland compositor) for arm64-v8a, linked against Termux's libwayland-server (bionic) taken from the Termux apt repo,
#     plus the bionic libraries it needs at runtime (libwayland-server, libffi, libandroid-support) -> app/src/main/jniLibs/arm64-v8a
#   * assets/god/glibc/god-lib (real libSPIRV-Tools.so, libwayland-client >= 1.24) via god/tools/gpu/build-glibc-libs.sh (optional: failure is a warning)
# The FHS shim, fhs-run, libdapt-nolink and the runtime scripts are produced by the Gradle task generateGodAssets.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
W=${RUNNER_TEMP:-${TMPDIR:-/tmp}}/god-native; rm -rf "$W"; mkdir -p "$W"; cd "$W"

# ---- NDK (the version the project pins; Gradle would fetch it later, we need it now)
NDKV=$(sed -n 's/^ndkVersion=//p' "$ROOT/gradle.properties" | tr -d '\r')
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
NDK="$SDK/ndk/$NDKV"
if [ ! -d "$NDK" ]; then
  SM=$(ls "$SDK"/cmdline-tools/*/bin/sdkmanager | head -1)
  yes | "$SM" --sdk_root="$SDK" --licenses >/dev/null || true
  "$SM" --sdk_root="$SDK" --install "ndk;$NDKV" >/dev/null
fi
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
echo "NDK: $NDK"

# ---- Termux packages (aarch64, bionic): headers and libraries
REPO=https://packages.termux.dev/apt/termux-main
curl -fsSL "$REPO/dists/stable/main/binary-aarch64/Packages" -o Packages
pkgfile() { awk -v p="$1" 'BEGIN{RS="";FS="\n"} { ok=0; f=""; for(i=1;i<=NF;i++){ if($i=="Package: " p) ok=1; if($i ~ /^Filename: /) f=substr($i,11) } if(ok) print f }' Packages | head -1; }
mkdir -p sysroot
for p in libwayland libffi libandroid-support; do
  f=$(pkgfile "$p"); [ -n "$f" ] || { echo "Termux package not found: $p" >&2; exit 1; }
  echo "termux: $p -> $f"
  curl -fsSL "$REPO/$f" -o "$p.deb"; dpkg-deb -x "$p.deb" sysroot
done
SR="$W/sysroot/data/data/com.termux/files/usr"
[ -f "$SR/include/wayland-server.h" ] && [ -f "$SR/lib/libwayland-server.so" ] || { echo "libwayland files missing in $SR" >&2; exit 1; }

# ---- libwlcomp.so (same command lines as build-local.sh, NDK clang instead of Termux clang)
CC="$BIN/aarch64-linux-android24-clang"; CXX="$BIN/aarch64-linux-android24-clang++"
WL="$ROOT/app/src/main/cpp/wlcomp"
J="$ROOT/app/src/main/jniLibs/arm64-v8a"; mkdir -p "$J"
"$CC" -x c -O2 -fPIC -c -I"$WL" -I"$SR/include" "$WL/xdg-shell-protocol.c" -o xdg-shell.o
"$CXX" -std=c++17 -O2 -fPIC -shared -I"$WL" -I"$SR/include" "$WL/wl_android.cpp" "$WL/wlc.cpp" xdg-shell.o -o "$J/libwlcomp.so" \
  -L"$SR/lib" -lwayland-server -lEGL -lGLESv2 -landroid -llog
# runtime dependencies the compositor needs inside the app lib dir (build-local.sh copies the same three)
cp -L "$SR/lib/libwayland-server.so" "$SR/lib/libffi.so" "$SR/lib/libandroid-support.so" "$J/"
# NDK clang++ links libc++ shared (libwlcomp.so has NEEDED libc++_shared.so); build-local.sh copies it from $PREFIX/lib too
cp -L "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$J/"
# every NEEDED entry of libwlcomp.so must be a system library or shipped next to it
for n in $(readelf -d "$J/libwlcomp.so" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'); do
  case "$n" in libEGL.so|libGLESv2.so|libandroid.so|liblog.so|libm.so|libdl.so|libc.so) ;; *) [ -f "$J/$n" ] || { echo "libwlcomp.so needs $n but it is not shipped" >&2; exit 1; } ;; esac
done
ls -l "$J"

# ---- glibc god-lib (optional)
GL="$ROOT/app/src/main/assets/god/glibc/god-lib"
if [ "${GOD_SKIP_GLIBC_LIBS:-0}" != 1 ]; then
  mkdir -p "$GL"
  if GOD_LIBS="$GL" bash "$ROOT/god/tools/gpu/build-glibc-libs.sh"; then :; else
    echo "::warning::god-lib (libSPIRV-Tools / libwayland-client) was not built; the APK ships without glibc/god-lib"
    rm -rf "$GL"
  fi
fi
