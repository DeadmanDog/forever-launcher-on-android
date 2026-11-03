#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your Linux Android NDK}"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang" \
    -shared -fPIC -O2 -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -s \
    "$repo_dir/app/src/main/cpp/logging_control.c" -ldl \
    -o "$repo_dir/app/src/main/jniLibs/arm64-v8a/libwow_logging_control.so"
