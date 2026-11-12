#!/usr/bin/env python3
from pathlib import Path
import argparse,hashlib,json,os,subprocess
p=argparse.ArgumentParser();p.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[3]);p.add_argument('--out',type=Path,required=True);p.add_argument('--baseline',action='store_true');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
src=a.source_root/'app/src/main/java/com/winlator/renderer/VulkanRenderer.java';s=src.read_text()
def method(m):
 start=s.index(m);i=s.index('{',start)+1;d=1
 while d:d+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[start:i]
methods=['private long did(', 'private void collectWindows(', 'public void onUnmapWindow(', 'public void onDestroyWindow(', 'public void queueSceneUpdate(', 'public boolean onUpdateWindowContentDirect(']
j=a.out/'VulkanWindowRegression.java';j.write_text(Path(__file__).with_name('VulkanWindowRegression.java.in').read_text().replace('/* PRODUCTION */','\n'.join(method(m) for m in methods)))
jdk=Path(os.environ['JAVA_HOME'])/'bin';subprocess.run([str(jdk/'javac'),str(j)],check=True);results=[]
for n in range(8):
 r=subprocess.run([str(jdk/'java'),'-cp',str(a.out),'VulkanWindowRegression',str(n)],capture_output=True,text=True,timeout=10);results.append(dict(case=n,passed=r.returncode==0,output=r.stdout+r.stderr))
report=dict(sourceSha256=hashlib.sha256(src.read_bytes()).hexdigest(),passed=sum(r['passed'] for r in results),total=len(results),results=results);(a.out/'result.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
if not a.baseline and report['passed']!=report['total']:raise SystemExit(1)
