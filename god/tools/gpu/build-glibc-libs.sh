#!/data/data/com.termux/files/usr/bin/bash
# build-glibc-libs.sh - the two glibc libraries the PanVK glibc driver (zenithblue-oss/panvk-kbase-android) needs and Debian 13 lacks:
#   * libSPIRV-Tools.so (shared; Debian ships only static libraries), built from KhronosGroup/SPIRV-Tools with the glibc cross compiler
#   * libwayland-client.so.0 >= 1.24 (wl_fixes_interface), taken from Debian unstable (MIT license), unpacked, not installed
# Output: ~/god-libs/ (build-local.sh ships it as assets/god/glibc/god-lib, GodRuntime puts it into $PREFIX/glibc/god-lib).
set -e
OUT=${GOD_LIBS:-$HOME/god-libs}; W=${TMPDIR:-/tmp}/god-libs-build; mkdir -p "$OUT" "$W"; cd "$W"
export PATH="$PREFIX/glibc/bin:$PATH"
# --- libwayland-client from Debian unstable
if ! ls "$OUT"/libwayland-client.so.0.* >/dev/null 2>&1; then
  idx=$(curl -fsSL http://deb.debian.org/debian/pool/main/w/wayland/); f=$(echo "$idx" | grep -o 'libwayland-client0_[0-9][^"]*arm64.deb' | sort -V | uniq | tail -1)
  curl -fsSL -o w.deb "http://deb.debian.org/debian/pool/main/w/wayland/$f"; rm -rf wx; mkdir wx; dpkg-deb -x w.deb wx
  cp -a wx/usr/lib/aarch64-linux-gnu/libwayland-client.so.0* "$OUT/"; echo "libwayland-client: $f"
fi
# --- libSPIRV-Tools.so
if [ ! -f "$OUT/libSPIRV-Tools.so" ]; then
  [ -d SPIRV-Tools ] || git clone --depth 1 https://github.com/KhronosGroup/SPIRV-Tools.git
  HREV=$(grep -o "spirv_headers_revision': '[0-9a-f]*" SPIRV-Tools/DEPS | grep -o '[0-9a-f]\{40\}')
  if [ ! -d SPIRV-Headers ]; then git clone https://github.com/KhronosGroup/SPIRV-Headers.git; fi
  git -C SPIRV-Headers checkout -q "$HREV"
  [ -f b/build.ninja ] || cmake -S SPIRV-Tools -B b -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DSPIRV_SKIP_TESTS=ON -DSPIRV_SKIP_EXECUTABLES=ON \
    -DSPIRV-Headers_SOURCE_DIR="$W/SPIRV-Headers" -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
    -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++ -DCMAKE_INSTALL_RPATH= -DCMAKE_SKIP_RPATH=ON
  cmake --build b -j"$(nproc)"
  # the shared target is libSPIRV-Tools-shared.so; the driver asks for the name libSPIRV-Tools.so (C API only: spvContextCreate, spvBinaryToText, ...)
  cp -L b/source/libSPIRV-Tools-shared.so "$OUT/libSPIRV-Tools.so"; echo "libSPIRV-Tools.so: $(readelf -d "$OUT/libSPIRV-Tools.so" | grep SONAME)"
fi
ls -l "$OUT"
