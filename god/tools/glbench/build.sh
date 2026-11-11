#!/data/data/com.termux/files/usr/bin/bash
# build glbench with Termux clang and install it to $PREFIX/bin
set -e
cd "$(dirname "$0")"
clang -O2 -Wall -o glbench glbench.c -ldl -lm
install -m 755 glbench "$PREFIX/bin/glbench"
echo "installed: $PREFIX/bin/glbench"
