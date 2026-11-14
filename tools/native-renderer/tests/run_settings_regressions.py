#!/usr/bin/env python3
from pathlib import Path
import argparse,subprocess,hashlib,json,os
p=argparse.ArgumentParser();p.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[3]);p.add_argument('--out',type=Path,required=True);p.add_argument('--baseline',action='store_true');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
src=a.source_root/'app/src/main/cpp/winlator/VulkanRendererContext.cpp';s=src.read_text()
def method(marker):
 start=s.index(marker);pos=s.index('{',start);depth=1;i=pos+1
 while depth:depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[start:i]
cpp=a.out/'settings.cpp';cpp.write_text(Path(__file__).with_name('RenderSettingsRegression.cpp.in').read_text().replace('/* PRODUCTION */','\n'.join(method(m) for m in ['void VulkanRendererContext::setFilterMode(', 'void VulkanRendererContext::setEffect(', 'void VulkanRendererContext::setSwapRB(', 'void VulkanRendererContext::setPresentMode('])));exe=a.out/'settings'
root=Path(__file__).resolve().parents[3];inc=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'.local/share/wow-build-tools/sdk')))/'ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
subprocess.run(['g++','-std=c++17','-O1','-g','-pthread','-fsanitize=address,undefined','-idirafter',str(inc),'-I'+str(root/'app/src/main/cpp/winlator'),str(cpp),'-o',str(exe)],check=True)
results=[]
for n in range(5):
 r=subprocess.run([str(exe),str(n)],capture_output=True,text=True,timeout=10);results.append(dict(case=n,passed=r.returncode==0,output=r.stdout+r.stderr))
report=dict(sourceSha256=hashlib.sha256(src.read_bytes()).hexdigest(),passed=sum(r['passed'] for r in results),total=len(results),results=results);(a.out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if not a.baseline and report['passed']!=report['total']:raise SystemExit(1)
