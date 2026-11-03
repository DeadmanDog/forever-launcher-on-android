#!/bin/bash
set -euo pipefail
root_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
llvm_root=${FRONTMAGE_LLVM_MINGW:-/home/fsos-x/WoW-Proton/vegas-pipeline-diagnostic/llvm-mingw-20250709-ucrt-ubuntu-22.04-x86_64}
smoke_output=${1:?Specify a new output .exe path}
[[ ! -e "$smoke_output" ]] || { echo "Refusing to overwrite an existing diagnostic binary" >&2; exit 1; }
"$llvm_root/bin/aarch64-w64-mingw32-clang++" -std=c++17 -O2 -static-libstdc++ -static-libgcc \
    "$root_dir/docs/dx12/smoke/frontmage-dx12-smoke.cpp" -o "$smoke_output" \
    -ld3d12 -ldxgi -ldxguid -luser32
"$llvm_root/bin/llvm-readobj" --file-headers --coff-imports "$smoke_output"
