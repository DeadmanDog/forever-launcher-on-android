#!/usr/bin/env python3
"""1.2.22: compositor windows without blending, neutral colour adjustments skipped. Production shaders on lavapipe
(RenderNeutralGpuTest.cpp): the old shader is the production one with the neutral guard removed."""
from pathlib import Path
import os, subprocess, sys, tempfile
r = Path(__file__).resolve().parents[3]; t = Path(__file__).resolve().parent; cpp = r / 'app/src/main/cpp/winlator'
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / '.local/share/wow-build-tools/sdk')))
include = sdk / 'ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
GUARD = 'if (pc.brightness != 0.0 || pc.contrast != 0.0 || pc.gamma != 1.0) rgb = applyColorAdjustments(rgb);'
frag = (cpp / 'window.frag').read_text()
assert frag.count(GUARD) == 1, 'production shader no longer has the neutral guard'
with tempfile.TemporaryDirectory(prefix='wow-neutral-gpu-') as temp:
    out = Path(temp)
    (out / 'old.frag').write_text(frag.replace(GUARD, 'rgb = applyColorAdjustments(rgb);'))
    for name, path in [('window_vert', cpp / 'window.vert'), ('window_frag', cpp / 'window.frag'), ('window_frag_old', out / 'old.frag')]:
        subprocess.run(['glslangValidator', '-V', '--vn', name + '_code', '-x', '-o', str(out / (name + '.h')), str(path)], check=True, capture_output=True)
    exe = out / 'neutral'
    subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-fsanitize=address,undefined', '-idirafter', str(include), '-I' + str(out), '-I' + str(cpp),
                    str(t / 'RenderNeutralGpuTest.cpp'), '-o', str(exe), '/lib/x86_64-linux-gnu/libvulkan.so.1'], check=True)
    env = dict(os.environ, VK_ICD_FILENAMES='/usr/share/vulkan/icd.d/lvp_icd.json', ASAN_OPTIONS='detect_leaks=0')
    q = subprocess.run([str(exe)], timeout=120, env=env, capture_output=True, text=True)
    print(q.stdout, q.stderr[-2000:] if q.returncode else '')
    sys.exit(q.returncode)
