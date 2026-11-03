#!/usr/bin/env python3
from pathlib import Path
import argparse,hashlib,json,subprocess,os
p=argparse.ArgumentParser();p.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[3]);p.add_argument('--out',type=Path,required=True);p.add_argument('--baseline',action='store_true');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
src=a.source_root/'app/src/main/cpp/winlator/VulkanRendererContext.cpp';s=src.read_text()
def method(marker):
 start=s.index(marker);pos=s.index('{',start);d=1;i=pos+1
 while d:d+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[start:i]
markers=['uint32_t VulkanRendererContext::findMemType(', 'void VulkanRendererContext::createBuffer(', 'bool VulkanRendererContext::createWinTexResources(', 'void VulkanRendererContext::destroyWinTex(', 'void VulkanRendererContext::freeRetiredTextures(', 'void VulkanRendererContext::updateWindowContent(', 'void VulkanRendererContext::setRenderList(', 'void VulkanRendererContext::setTransform(']
fixed='void VulkanRendererContext::appendWindowDraw(' in s
if fixed:
 markers+=['void VulkanRendererContext::appendWindowDraw(', 'void VulkanRendererContext::commitCpuUploads(', 'VkBuffer VulkanRendererContext::prepareCpuUpload(']
 capture='frameDraws.clear();for(auto& re:renderList)appendWindowDraw(re);';commit='commitCpuUploads();'
else:
 start=s.index('        frameDraws.clear();',s.index('void VulkanRendererContext::renderFrame()'));end=s.index('        if (isCursorImageDirty.load()',start);capture=s[start:end];commit=''
start=s.index('    for (auto& d : draws)',s.index('void VulkanRendererContext::recordCmdBuf('));end=s.index('    for (auto& d : draws)',start+10);barriers=s[start:end]
t=Path(__file__).with_name('RenderUploadSceneRegression.cpp.in');cpp=a.out/'upload.cpp';cpp.write_text(t.read_text().replace('/* PRODUCTION */','\n'.join(method(m) for m in markers)).replace('/* CAPTURE */',capture).replace('/* COMMIT */',commit).replace('/* BARRIERS */',barriers));exe=a.out/'upload'
inc=Path(os.environ.get('ANDROID_HOME','/home/fsos-x/.local/share/wow-build-tools/sdk'))/'ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include';own=Path(__file__).resolve().parents[3]/'app/src/main/cpp/winlator'
subprocess.run(['g++','-std=c++17','-O1','-g','-pthread','-fsanitize=address,undefined','-idirafter',str(inc),'-I'+str(own),str(cpp),'-o',str(exe)],check=True)
results=[]
for n in range(21):
 r=subprocess.run([str(exe),str(n)],capture_output=True,text=True,timeout=10);results.append(dict(case=n,passed=r.returncode==0,output=r.stdout+r.stderr))
report=dict(sourceSha256=hashlib.sha256(src.read_bytes()).hexdigest(),passed=sum(r['passed'] for r in results),total=len(results),results=results);(a.out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if not a.baseline and report['passed']!=report['total']:raise SystemExit(1)
