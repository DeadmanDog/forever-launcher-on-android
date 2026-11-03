#!/usr/bin/env bash
# Rebuilds the prebuilt native libraries of the modern flavor from app/src/main/cpp and copies them to app/src/modern/jniLibs.
# Gradle does NOT compile native code: every C/C++ change is only shipped after this script ran.
#   libvulkan_renderer.so  (tools/native-renderer CMake, as documented since v0.1.113)
#   libwinlator.so         (drawable.c, sysvshared_memory.c, vulkan_version.c)
#   libxconnectorpatch.so  (xconnectorpatch.c)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${WOW_SDK:-$HOME/.local/share/wow-build-tools/sdk}"
NDK="$SDK/ndk/27.3.13750724"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$BIN/aarch64-linux-android28-clang"
OUT="${1:-$(mktemp -d)}"
DEST="$ROOT/app/src/modern/jniLibs/arm64-v8a"
CPP="$ROOT/app/src/main/cpp"
mkdir -p "$OUT"

export PATH="$SDK/cmake/3.22.1/bin:$PATH"
rm -rf "$OUT/vr-build"
cmake -S "$ROOT/tools/native-renderer" -B "$OUT/vr-build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release >"$OUT/vr-cfg.log"
ninja -C "$OUT/vr-build" vulkan_renderer >"$OUT/vr-build.log"
cp "$OUT/vr-build/libvulkan_renderer.so" "$OUT/libvulkan_renderer.so"
"$BIN/llvm-strip" --strip-unneeded "$OUT/libvulkan_renderer.so"

"$CC" -shared -fPIC -O2 -Wall -Wno-unused-function -Wimplicit-function-declaration \
  -Wl,-z,max-page-size=16384 -Wl,-soname,libwinlator.so -s \
  "$CPP/winlator/drawable.c" "$CPP/winlator/sysvshared_memory.c" "$CPP/winlator/vulkan_version.c" \
  -llog -landroid -ljnigraphics -ldl -o "$OUT/libwinlator.so"

"$CC" -shared -fPIC -O2 -Wall -Wno-unused-function -Wl,-z,max-page-size=16384 -s \
  "$CPP/xconnectorpatch/xconnectorpatch.c" -llog -ldl -o "$OUT/libxconnectorpatch.so"

# The exec shim Wine preloads (replaces GameNative's closed libredirect-bionic-wx-minimal.so); host tests: tools/wowexec-tests/run.py
"$CC" -shared -fPIC -O2 -Wall -Werror -Wl,-z,max-page-size=16384 -s \
  "$CPP/wowexec/wow_exec.c" -llog -ldl -o "$OUT/libwowexec.so"

for lib in libvulkan_renderer libwinlator libxconnectorpatch libwowexec; do
  install -m 0755 "$OUT/$lib.so" "$DEST/$lib.so"
  echo "installed $DEST/$lib.so ($(stat -c %s "$DEST/$lib.so") bytes)"
done
