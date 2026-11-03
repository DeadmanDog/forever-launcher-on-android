#!/usr/bin/env python3
"""Run the production window shaders on lavapipe with FSR and check that a hard edge stays in place.

The production shader must pass; the same shader with EASU given the pixel centre again (the old input) must fail.
"""
from pathlib import Path
import os,subprocess,sys,tempfile
r=Path(__file__).resolve().parents[3];t=Path(__file__).resolve().parent;cpp=r/'app/src/main/cpp/winlator'
sdk=Path(os.environ.get('ANDROID_HOME','/home/fsos-x/.local/share/wow-build-tools/sdk'))
include=sdk/'ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
FIXED='FsrEasuF(pix, floor(uv * outRes), con0, con1, con2, con3);'
OLD='FsrEasuF(pix, uv * outRes, con0, con1, con2, con3);'
def run(frag_text,out):
 out.mkdir()
 (out/'window.frag').write_text(frag_text)
 for name,path in [('window_vert',cpp/'window.vert'),('window_frag',out/'window.frag')]:
  subprocess.run(['glslangValidator','-V','--vn',name+'_code','-x','-o',str(out/(name+'.h')),str(path)],check=True,capture_output=True)
 exe=out/'fsr'
 subprocess.run(['g++','-std=c++17','-O1','-g','-fsanitize=address,undefined','-idirafter',str(include),'-I'+str(out),str(t/'RenderFsrGpuTest.cpp'),'-o',str(exe),'/lib/x86_64-linux-gnu/libvulkan.so.1'],check=True)
 env=dict(os.environ,VK_ICD_FILENAMES='/usr/share/vulkan/icd.d/lvp_icd.json',ASAN_OPTIONS='detect_leaks=0')
 q=subprocess.run([str(exe)],timeout=120,env=env,capture_output=True,text=True)
 print(q.stdout.strip().splitlines()[-1] if q.stdout.strip() else q.stderr)
 return q.returncode
frag=(cpp/'window.frag').read_text()
assert frag.count(FIXED)==1,'production shader no longer has the integer-pixel EASU call'
with tempfile.TemporaryDirectory(prefix='wow-fsr-gpu-') as temp:
 prod=run(frag,Path(temp)/'production')
 mut=run(frag.replace(FIXED,OLD),Path(temp)/'pixel-centre')
print('production exit',prod,'| pixel-centre mutation exit',mut)
sys.exit(0 if prod==0 and mut!=0 else 1)
