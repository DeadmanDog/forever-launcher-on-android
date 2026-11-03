#!/usr/bin/env python3
"""Use complete production methods and production cursor-upload snapshot code."""
from pathlib import Path
import argparse,hashlib,json,subprocess
p=argparse.ArgumentParser();p.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[3]);p.add_argument('--out',type=Path,required=True);p.add_argument('--baseline',action='store_true');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
src=a.source_root/'app/src/main/cpp/winlator/VulkanRendererContext.cpp';s=src.read_text()
def method(marker):
 start=s.index(marker);pos=s.index('{',start);depth=1;i=pos+1
 while depth:depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[start:i]
production='\n'.join(method(m) for m in ['void VulkanRendererContext::updateCursorImage(', 'void VulkanRendererContext::setCursorVisible(', 'void VulkanRendererContext::onSurfaceResized('])
start=s.index('        if (isCursorImageDirty.load()',s.index('void VulkanRendererContext::renderFrame()'));pos=s.index('{',start);depth=1;i=pos+1
while depth:depth+=(s[i]=='{')-(s[i]=='}');i+=1
upload=s[start:i]
t=Path(__file__).with_name('RenderStateRegression.cpp.in');cpp=a.out/'state.cpp';cpp.write_text(t.read_text().replace('/* PRODUCTION */',production).replace('/* UPLOAD */',upload));exe=a.out/'state'
inc=Path(__file__).resolve().parents[3]/'app/src/main/cpp/winlator'
subprocess.run(['g++','-std=c++17','-O1','-g','-pthread','-fsanitize=address,undefined','-I'+str(inc),str(cpp),'-o',str(exe)],check=True)
results=[]
for n in range(14):
 r=subprocess.run([str(exe),str(n)],capture_output=True,text=True,timeout=10);results.append(dict(case=n,passed=r.returncode==0,output=r.stdout+r.stderr))
report=dict(sourceSha256=hashlib.sha256(src.read_bytes()).hexdigest(),passed=sum(r['passed'] for r in results),total=len(results),results=results);(a.out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if not a.baseline and report['passed']!=report['total']:raise SystemExit(1)
