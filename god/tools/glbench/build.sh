#!/data/data/com.termux/files/usr/bin/bash
# build glbench (OpenGL ES) and vkbench (Vulkan) with Termux clang and install them to $PREFIX/bin
# shaders.h (SPIR-V of vk*.vert/frag) is committed; regenerate with: ./build.sh shaders  (needs glslang and xxd)
set -e
cd "$(dirname "$0")"
if [ "$1" = shaders ]; then
  : > shaders.h
  for s in vk.vert vk_heavy.frag vk_flat.frag; do glslangValidator -V "$s" -o "${s//./_}.spv" >/dev/null; xxd -i "${s//./_}.spv" >> shaders.h; rm -f "${s//./_}.spv"; done
fi
clang -O2 -Wall -o glbench glbench.c -ldl -lm
clang -O2 -Wall -Wno-missing-field-initializers -o vkbench vkbench.c -ldl
install -m 755 glbench "$PREFIX/bin/glbench"; install -m 755 vkbench "$PREFIX/bin/vkbench"
echo "installed: $PREFIX/bin/glbench $PREFIX/bin/vkbench"
