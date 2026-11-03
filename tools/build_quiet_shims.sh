#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your Linux Android NDK}"
: "${SDL2_INCLUDE_DIR:?Set SDL2_INCLUDE_DIR to the directory containing SDL2/SDL.h}"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
lib_dir="$repo_dir/app/src/main/jniLibs/arm64-v8a"
"$compiler" -shared -fPIC -O3 -fvisibility=hidden -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -s \
    -I"$SDL2_INCLUDE_DIR" "$repo_dir/app/src/main/cpp/evshim/evshim.c" -ldl -llog -o "$lib_dir/libevshim.so"
"$compiler" -shared -fPIC -O2 -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -s \
    "$repo_dir/app/src/main/cpp/gamenative_dns_v4mapped.c" -ldl -o "$lib_dir/libgamenative_dns_v4mapped.so"
bash "$repo_dir/app/src/main/cpp/gnoverlay/build.sh"
bash "$repo_dir/tools/build_logging_control.sh"
