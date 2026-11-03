#!/usr/bin/env python3
"""Production present-on-change and display-timing accounting on the host."""
from pathlib import Path
import subprocess, sys, tempfile
project = Path(__file__).resolve().parents[3]
include = Path.home() / '.local/share/wow-build-tools/sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
source = Path(sys.argv[1]) if len(sys.argv) > 1 else project / 'app/src/main/cpp/winlator/VulkanRendererContext.cpp'
s = source.read_text()
def method(marker):
    start = s.index(marker); pos = s.index('{', start); depth = 1; i = pos + 1
    while depth: depth += (s[i] == '{') - (s[i] == '}'); i += 1
    return s[start:i]
production = '\n'.join(method(m) for m in ['bool VulkanRendererContext::frameUnchanged(',
    'void VulkanRendererContext::collectPresentTimings(', 'void VulkanRendererContext::takeDisplayStats(', 'void VulkanRendererContext::phaseStats(', 'void VulkanRendererContext::logFrame(', 'int VulkanRendererContext::takeFrameLog(', 'void VulkanRendererContext::latencyWindowDone(', 'bool VulkanRendererContext::drainQueuedFrame(', 'bool VulkanRendererContext::holdDrainedContent('])
with tempfile.TemporaryDirectory(prefix='wow-present-tests-') as temp:
    cpp = Path(temp) / 'present.cpp'; exe = Path(temp) / 'present'
    cpp.write_text((Path(__file__).with_name('RenderPresentRegression.cpp.in')).read_text().replace('/* PRODUCTION */', production))
    subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-fsanitize=address,undefined', '-idirafter', str(include), str(cpp), '-o', str(exe)], check=True)
    failed = 0
    for case in (0, 2, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22):
        r = subprocess.run([str(exe), str(case)], capture_output=True, text=True, timeout=30)
        print(r.stdout + r.stderr, end='')
        failed += r.returncode != 0
    print(f'present regressions: {20 - failed}/20')
    raise SystemExit(1 if failed else 0)
