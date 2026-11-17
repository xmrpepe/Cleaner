#!/usr/bin/env bash
# Regenerates the embedded SPIR-V headers. Requires glslangValidator (glslang-tools).
set -euo pipefail
cd "$(dirname "$0")/../app/src/main/cpp/shaders"
glslangValidator -V --target-env vulkan1.1 --vn kFmaComp -o ../spirv_fma_comp.h fma.comp
glslangValidator -V --target-env vulkan1.1 --vn kFullscreenVert -o ../spirv_fullscreen_vert.h fullscreen.vert
glslangValidator -V --target-env vulkan1.1 --vn kFillFrag -o ../spirv_fill_frag.h fill.frag
