#!/usr/bin/env python3
"""Test the production cursor allocation/upload/barrier bodies with delayed GPU mocks."""
from pathlib import Path
import argparse,json,os,subprocess,tempfile
parser=argparse.ArgumentParser();parser.add_argument('--source-root',type=Path);parser.add_argument('--report',type=Path);args=parser.parse_args()
root=Path(__file__).resolve().parents[3];source=args.source_root or root;tests=Path(__file__).resolve().parent
s=(source/'app/src/main/cpp/winlator/VulkanRendererContext.cpp').read_text();h=(source/'app/src/main/cpp/winlator/VulkanRendererContext.h').read_text();legacy='struct CursorStaging' not in h
def block(a,b):return s[s.index(a):s.index(b,s.index(a))]
production=block('void VulkanRendererContext::ensureCursorTex(' if legacy else 'bool VulkanRendererContext::ensureCursorTex(', '\nvoid VulkanRendererContext::recordCmdBuf(')
if legacy:
 production=production.replace('void VulkanRendererContext::ensureCursorTex(', 'bool VulkanRendererContext::ensureCursorTex(').replace('cursorTexH==h) return;', 'cursorTexH==h) return true;').replace('cursorTexW=w; cursorTexH=h;', 'cursorTexW=w; cursorTexH=h; return true;')
 production+='\nvoid VulkanRendererContext::commitCursorUpload(){isCursorImageDirty=false;cursorNeedsTransition=false;}\n'
 fields='VkBuffer cursorStg=0;VkDeviceMemory cursorStgM=0;void*cursorStgP=nullptr;VkDeviceSize cursorStgC=0,cursorUploadSize=0;bool cursorNeedsTransition=false;'
 buffer='return cursorStg;';mapped='return cursorStgP;'
else:
 fields=h[h.index('    struct CursorStaging {'):h.index('\n    VkInstance',h.index('    struct CursorStaging {'))]
 buffer='return cursorStaging[i].buffer;';mapped='return cursorStaging[i].mapped;'
a=s.index('    if (hasCursorCopy) {');b=s.index('\n    if (!postUpload.empty())',a);barrier=s[a:b].replace('    if (hasCursorCopy) {','    {',1)
a=s.index('        if (isCursorImageDirty.load()',s.index('void VulkanRendererContext::renderFrame()'));b=s.index('\n    }',a);upload=s[a:b]
# Keep the production pending-upload logic; provide only its surrounding snapshot locals.
upload='bool hasCurUpload=false,curVis=true;VkBuffer curUpload{};short curUpW=0,curUpH=0;cursorImg=12;cursorTexW=2;cursorTexH=2;cursorW=2;cursorH=2;std::vector<uint32_t>cursorPixels(4,7);'+upload
fixture=(tests/'RenderCursorGpuTest.cpp.in').read_text().replace('/* STAGING_FIELDS */',fields).replace('/* SLOT_BUFFER */',buffer).replace('/* SLOT_MAPPED */',mapped).replace('/* PRODUCTION */',production).replace('/* CURSOR_BARRIER */',barrier).replace('/* CURSOR_UPLOAD */',upload)
fixture=fixture.replace('struct VulkanRendererContext {','static constexpr unsigned MAX_FRAMES_IN_FLIGHT=2;\nstruct VulkanRendererContext {')
ndk=Path(os.environ.get('ANDROID_NDK_HOME', str(Path(os.environ.get('ANDROID_HOME','/home/fsos-x/.local/share/wow-build-tools/sdk'))/'ndk/27.3.13750724')))
include=ndk/'toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
with tempfile.TemporaryDirectory(prefix='cursor-gpu-regressions-') as folder:
 p=Path(folder)/'cursor.cpp';p.write_text(fixture);exe=p.with_suffix('')
 subprocess.run(['g++','-std=c++17','-O1','-g','-fsanitize=address,undefined','-idirafter',str(include),str(p),'-o',str(exe)],check=True)
 results=[]
 for n in range(14):
  run=subprocess.run([str(exe),str(n)],capture_output=True,text=True,timeout=10)
  results.append({'case':n,'passed':run.returncode==0,'output':(run.stdout+run.stderr).strip()});print(results[-1]['output'])
 report={'baseline':legacy,'passed':sum(r['passed'] for r in results),'total':len(results),'results':results}
 if args.report:args.report.write_text(json.dumps(report,indent=2)+'\n')
 if not legacy and report['passed']!=report['total']:raise SystemExit(1)
