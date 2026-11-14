#!/usr/bin/env python3
"""Host test of ScanoutReleaseWaiter.h (scanout Present buffers handed back after SurfaceFlinger's release fence), ASan+UBSan and TSan."""
from pathlib import Path
import subprocess, tempfile
project = Path(__file__).resolve().parents[3]
tests = Path(__file__).resolve().parent
inc = project / 'app/src/main/cpp/winlator'
with tempfile.TemporaryDirectory(prefix='wow-scanout-waiter-') as folder:
    for name, flags in [('asan', ['-fsanitize=address,undefined']), ('tsan', ['-fsanitize=thread'])]:
        exe = Path(folder) / name
        subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-pthread', *flags, '-I' + str(inc), str(tests / 'RenderScanoutWaiterTest.cpp'), '-o', str(exe)], check=True)
        command = ['setarch', 'x86_64', '-R', str(exe)] if name == 'tsan' else [str(exe)]
        subprocess.run(command, check=True, timeout=120)
        print('PASS scanout release waiter under', name)
