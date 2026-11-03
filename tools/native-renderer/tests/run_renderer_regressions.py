#!/usr/bin/env python3
"""Build host regressions against the production wake helper and detach body."""
from pathlib import Path
import subprocess,tempfile
r=Path(__file__).resolve().parents[3];t=Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix='wow-renderer-tests-') as temp:
 out=Path(temp);s=(r/'app/src/main/cpp/winlator/VulkanRendererContext.cpp').read_text();a=s.index('void VulkanRendererContext::detachSurface()');b=s.index('\nbool VulkanRendererContext::reattachSurface',a)
 p=out/'detach.cpp';p.write_text((t/'RenderDetachTest.cpp.in').read_text().replace('/* DETACH_BODY */',s[a:b]))
 for source,name in [(t/'RenderWakeSignalTest.cpp','wake'),(p,'detach')]:
  binary=out/name
  subprocess.run(['g++','-std=c++17','-O1','-g','-pthread','-fsanitize=address,undefined','-I'+str(r/'app/src/main/cpp/winlator'),str(source),'-o',str(binary)],check=True)
  subprocess.run([str(binary)],check=True,timeout=30)

subprocess.run(["python3", "-B", str(t/"run_ownership_regressions.py")], check=True)

subprocess.run(["python3", "-B", str(t/"run_cursor_gpu_regressions.py")], check=True)

subprocess.run(["python3", "-B", str(t/"run_retirement_regressions.py")], check=True)

subprocess.run(["python3", "-B", str(t/"run_scanout_regressions.py")], check=True)
