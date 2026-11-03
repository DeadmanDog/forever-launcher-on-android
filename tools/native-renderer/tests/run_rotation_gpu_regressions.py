#!/usr/bin/env python3
"""Run the production window shaders on lavapipe and check the swapchain pre-rotation."""
from pathlib import Path
import os,subprocess,tempfile
r=Path(__file__).resolve().parents[3];t=Path(__file__).resolve().parent;cpp=r/'app/src/main/cpp/winlator'
sdk=Path(os.environ.get('ANDROID_HOME','/home/fsos-x/.local/share/wow-build-tools/sdk'))
include=sdk/'ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
with tempfile.TemporaryDirectory(prefix='wow-rotation-gpu-') as temp:
 out=Path(temp)
 for name,suffix in [('window_vert','vert'),('window_frag','frag')]:
  subprocess.run(['glslangValidator','-V','--vn',name+'_code','-x','-o',str(out/(name+'.h')),str(cpp/('window.'+suffix))],check=True,capture_output=True)
 exe=out/'rotation'
 subprocess.run(['g++','-std=c++17','-O1','-g','-fsanitize=address,undefined','-idirafter',str(include),'-I'+str(out),str(t/'RenderRotationGpuTest.cpp'),'-o',str(exe),'/lib/x86_64-linux-gnu/libvulkan.so.1'],check=True)
 env=dict(os.environ,VK_ICD_FILENAMES='/usr/share/vulkan/icd.d/lvp_icd.json',ASAN_OPTIONS='detect_leaks=0')
 subprocess.run([str(exe)],check=True,timeout=120,env=env)
