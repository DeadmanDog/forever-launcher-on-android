#!/usr/bin/env python3
"""Builds drawable.c for the host with ASan/UBSan and runs the bounds regression (needs JAVA_HOME for jni.h)."""
from pathlib import Path
import os, subprocess, sys, tempfile
root = Path(__file__).resolve().parents[2]; here = Path(__file__).resolve().parent
java = Path(os.environ['JAVA_HOME'])
with tempfile.TemporaryDirectory(prefix='wow-drawable-test-') as folder:
    f = Path(folder); (f / 'include/android').mkdir(parents=True)
    (f / 'include/android/bitmap.h').write_text('#pragma once\n#include <jni.h>\n#include <stdint.h>\ntypedef struct { uint32_t width, height, stride; int32_t format; uint32_t flags; } AndroidBitmapInfo;\n'
        'static inline int AndroidBitmap_getInfo(JNIEnv*e, jobject b, AndroidBitmapInfo*i){(void)e;(void)b;(void)i;return -1;}\n'
        'static inline int AndroidBitmap_lockPixels(JNIEnv*e, jobject b, void**p){(void)e;(void)b;(void)p;return -1;}\n'
        'static inline int AndroidBitmap_unlockPixels(JNIEnv*e, jobject b){(void)e;(void)b;return -1;}\n'
        '#define ANDROID_BITMAP_RESULT_SUCCESS 0\n')
    exe = f / 'drawable_test'
    subprocess.run(['gcc', '-std=gnu11', '-O1', '-g', '-fsanitize=address,undefined', '-Wall',
                    '-I' + str(java / 'include'), '-I' + str(java / 'include/linux'), '-I' + str(f / 'include'),
                    str(here / 'drawable_host_test.c'), str(root / 'app/src/main/cpp/winlator/drawable.c'), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True, timeout=30)
