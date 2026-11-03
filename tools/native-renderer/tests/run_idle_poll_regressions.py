#!/usr/bin/env python3
"""Exercise the production idle-token poll (hand-back progress without a render request) on the host."""
from pathlib import Path
import subprocess, tempfile, sys
project = Path(__file__).resolve().parents[3]
tests = Path(__file__).resolve().parent
source = Path(sys.argv[1]) if len(sys.argv) > 1 else project / 'app/src/main/cpp/winlator/VulkanRendererContext.cpp'
s = source.read_text()
def method(marker):
    start = s.index(marker); i = s.index('{', start) + 1; depth = 1
    while depth:
        depth += (s[i] == '{') - (s[i] == '}'); i += 1
    return s[start:i]
production = method('bool VulkanRendererContext::hasIdleTokensInFlight(') + '\n' + method('void VulkanRendererContext::pollIdleTokens(')
with tempfile.TemporaryDirectory(prefix='wow-idle-poll-') as folder:
    cpp = Path(folder) / 'test.cpp'
    cpp.write_text((tests / 'RenderIdlePollTest.cpp.in').read_text().replace('/* PRODUCTION */', production))
    exe = Path(folder) / 'test'
    subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-pthread', '-fsanitize=address,undefined', str(cpp), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True, timeout=10)
