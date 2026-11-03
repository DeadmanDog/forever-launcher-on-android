#!/usr/bin/env python3
"""Run the production scanout code (VulkanRendererScanout.cpp, from the SC_/ST_ macros on) against a checking SurfaceControl stub.

The stub fails on any use of a deleted handle. Cases 0-4 run under ASan+UBSan; the threaded cases 0-2 also run under ThreadSanitizer.
"""
from pathlib import Path
import subprocess, sys, tempfile
project = Path(__file__).resolve().parents[3]
tests = Path(__file__).resolve().parent
source = Path(sys.argv[1]) if len(sys.argv) > 1 else project / 'app/src/main/cpp/winlator/VulkanRendererScanout.cpp'
s = source.read_text()
production = s[s.index('#define SC_CREATE'):]
inc = project / 'app/src/main/cpp/winlator'
with tempfile.TemporaryDirectory(prefix='wow-scanout-tests-') as folder:
    cpp = Path(folder) / 'scanout.cpp'
    cpp.write_text((tests / 'RenderScanoutLifetimeTest.cpp.in').read_text().replace('/* PRODUCTION */', production))
    for name, flags, cases in [('asan', ['-fsanitize=address,undefined'], range(5)), ('tsan', ['-fsanitize=thread'], range(3))]:
        exe = Path(folder) / name
        subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-pthread', *flags, '-I' + str(inc), str(cpp), '-o', str(exe)], check=True)
        for i in cases:
            # ThreadSanitizer cannot map its shadow memory with the kernel's default address randomisation (WSL2 kernels): run it without.
            command = ['setarch', 'x86_64', '-R', str(exe), str(i)] if name == 'tsan' else [str(exe), str(i)]
            subprocess.run(command, check=True, timeout=120)
        print('PASS scanout lifetime under', name)
