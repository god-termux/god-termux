#!/data/data/com.termux/files/usr/bin/bash
# Build god-termux on an aarch64 Termux device (no NDK, no x86 tools).
#   pkg install openjdk-17 clang git curl unzip aapt2 ; then:  bash god/build-local.sh
# Output: ~/god-build/app/build/outputs/apk/debug/*.apk
set -e
SRC=$(cd "$(dirname "$0")/.." && pwd); B=${GOD_BUILD:-$HOME/god-build}; SDK=${ANDROID_HOME:-$HOME/android-sdk}
export JAVA_HOME=$PREFIX/lib/jvm/java-17-openjdk ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK
export GRADLE_OPTS="-Xmx1800m -Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false"

# 1. Android SDK via the official (Java) sdkmanager
if [ ! -d "$SDK/platforms/android-36" ]; then
  mkdir -p "$SDK"; cd "$SDK"
  F=$(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml | grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' | sort -u | tail -1)
  curl -fsSL -o cmd.zip "https://dl.google.com/android/repository/$F"; mkdir -p cmdline-tools; unzip -q -o cmd.zip -d cmdline-tools; mv cmdline-tools/cmdline-tools cmdline-tools/latest
  yes | cmdline-tools/latest/bin/sdkmanager --sdk_root="$SDK" --licenses >/dev/null
  cmdline-tools/latest/bin/sdkmanager --sdk_root="$SDK" --install "build-tools;35.0.0" "platforms;android-36"
fi
echo "sdk.dir=$SDK" > "$SRC/local.properties"

# 2. build copy (the source tree stays untouched)
rm -rf "$B"; mkdir -p "$B"; (cd "$SRC" && tar --exclude=.git -cf - .) | (cd "$B" && tar xf -); cd "$B"
python3 - <<'PY'
import re
def strip_block(s, head):
    out=[]; i=0
    while True:
        m=re.search(head, s[i:])
        if not m: out.append(s[i:]); break
        a=i+m.start(); out.append(s[i:a]); j=s.index('{', a); d=0; k=j
        while True:
            if s[k]=='{': d+=1
            elif s[k]=='}':
                d-=1
                if d==0: break
            k+=1
        i=k+1
    return ''.join(out)
for f in ('app/build.gradle','terminal-emulator/build.gradle','termux-shared/build.gradle'):
    s=open(f).read(); s=strip_block(s, r'externalNativeBuild\s*\{'); s=re.sub(r'\n\s*ndkVersion\s*=?\s*[^\n]*','',s)
    s=s.replace("abiFilters 'x86', 'x86_64', 'armeabi-v7a', 'arm64-v8a'","abiFilters 'arm64-v8a'")
    if f=='app/build.gradle': s=strip_block(s, r'splits\s*\{'); s=re.sub(r'\n\s*downloadBootstrap\("(arm|i686|x86_64)"[^\n]*','',s)
    open(f,'w').write(s)
PY

# 3. bootstrap (verified by Gradle's checksum) + native libs with Termux clang
V="2026.02.12-r1%2Bapt.android-7"
curl -fsSL -o app/src/main/cpp/bootstrap-aarch64.zip "https://github.com/termux/termux-packages/releases/download/bootstrap-$V/bootstrap-aarch64.zip"
J=app/src/main/jniLibs/arm64-v8a; mkdir -p $J terminal-emulator/src/main/jniLibs/arm64-v8a termux-shared/src/main/jniLibs/arm64-v8a
clang -shared -fPIC -O2 -o terminal-emulator/src/main/jniLibs/arm64-v8a/libtermux.so terminal-emulator/src/main/jni/termux.c
clang++ -shared -fPIC -O2 -o termux-shared/src/main/jniLibs/arm64-v8a/liblocal-socket.so termux-shared/src/main/cpp/local-socket.cpp -llog
clang -shared -fPIC -O2 -Iapp/src/main/cpp -o $J/libtermux-bootstrap.so app/src/main/cpp/termux-bootstrap.c app/src/main/cpp/termux-bootstrap-zip.S
clang -shared -fPIC -O2 -std=c++17 -nostdlib++ -o $J/libgldemo.so app/src/main/cpp/gldemo/gl.cpp /system/lib64/libEGL.so /system/lib64/libGLESv2.so /system/lib64/libandroid.so /system/lib64/liblog.so
cp "$PREFIX/lib/libc++_shared.so" $J/
# Wayland compositor (needs: pkg install libwayland). libwayland-server & friends are bundled; their sonames are already lib*.so
W=app/src/main/cpp/wlcomp
clang -x c -O2 -fPIC -c -I$W -I$PREFIX/include $W/xdg-shell-protocol.c -o $TMPDIR/xdg-shell.o
clang++ -std=c++17 -O2 -fPIC -shared -I$W -I$PREFIX/include $W/wl_android.cpp $W/wlc.cpp $TMPDIR/xdg-shell.o -o $J/libwlcomp.so -L$PREFIX/lib -lwayland-server /system/lib64/libEGL.so /system/lib64/libGLESv2.so /system/lib64/libandroid.so /system/lib64/liblog.so
cp "$PREFIX/lib/libwayland-server.so" "$PREFIX/lib/libffi.so" "$PREFIX/lib/libandroid-support.so" $J/

# 4. runtime assets: shim (bionic + glibc variants), supervisor, scripts, checksums
A=app/src/main/assets/god; mkdir -p $A/glibc/shim $A/bin $A/etc/profile.d; F=god/fhs
clang -shared -fPIC -O2 -o $A/glibc/shim/libfhs-bionic.so $F/fhs.c -ldl
PATH=$PREFIX/glibc/bin:$PATH aarch64-linux-gnu-gcc -shared -fPIC -O2 -DGLIBC_BUILD -fuse-ld=bfd -o $A/glibc/shim/libfhs-glibc.so $F/fhs.c -ldl   # needs: pkg install glibc-repo glibc
clang -O2 -o $A/glibc/shim/fhs-run $F/fhs-run.c
(cd $A/glibc/shim && sha256sum libfhs-bionic.so libfhs-glibc.so fhs-run > SHA256SUMS)
cp god/runtime/dapt god/runtime/gsh god/runtime/wlrun $A/bin/; cp god/runtime/glibc-fhs.sh $A/etc/profile.d/

# 5. Gradle (aapt2 from Termux; the SDK's x86 aapt2 cannot run here)
chmod +x gradlew
./gradlew --no-daemon --console=plain assembleDebug -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2 -Dorg.gradle.jvmargs=-Xmx1800m
ls -l app/build/outputs/apk/debug/*.apk
