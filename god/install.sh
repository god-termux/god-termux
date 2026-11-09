#!/data/data/com.termux/files/usr/bin/bash
# build + install shims into $PREFIX/glibc/shim and record SHA-256 sums that profile.d verifies at shell start
set -e; cd "$(dirname "$0")"; mkdir -p build; D=$PREFIX/glibc/shim; mkdir -p $D
clang -shared -fPIC -O2 -o build/libfhs-bionic.so fhs.c -ldl
PATH=$PREFIX/glibc/bin:$PATH aarch64-linux-gnu-gcc -shared -fPIC -O2 -DGLIBC_BUILD -fuse-ld=bfd -o build/libfhs-glibc.so fhs.c -ldl
clang -O2 -o build/fhs-run fhs-run.c
cp build/libfhs-bionic.so build/libfhs-glibc.so build/fhs-run $D/
(cd $D && sha256sum libfhs-bionic.so libfhs-glibc.so fhs-run > SHA256SUMS)
echo "installed + hashed:"; cat $D/SHA256SUMS | cut -c1-16,65-
