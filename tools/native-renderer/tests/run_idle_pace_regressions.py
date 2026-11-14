#!/usr/bin/env python3
"""Host test of IdlePollPacer (when the render thread looks at the frame fences for hand-back tokens)."""
from pathlib import Path
import subprocess, tempfile
project = Path(__file__).resolve().parents[3]
tests = Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix='wow-idle-pace-') as folder:
    exe = Path(folder) / 'test'
    subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-fsanitize=address,undefined', '-I', str(project / 'app/src/main/cpp/winlator'),
                    str(tests / 'RenderIdlePaceTest.cpp'), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True, timeout=30)
