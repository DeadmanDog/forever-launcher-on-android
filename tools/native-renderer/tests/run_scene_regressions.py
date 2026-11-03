#!/usr/bin/env python3
from pathlib import Path
import argparse,subprocess,hashlib,json,os
p=argparse.ArgumentParser();p.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[3]);p.add_argument('--out',type=Path,required=True);p.add_argument('--baseline',action='store_true');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
src=a.source_root/'app/src/main/java/com/winlator/renderer/VulkanRenderer.java';s=src.read_text()
def method(marker):
 start=s.index(marker);pos=s.index('{',start);depth=1;i=pos+1
 while depth:depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[start:i]
markers=['private long did(', 'public void updateScene(']
if 'private void collectStaleDrawableIds(' in s:markers.append('private void collectStaleDrawableIds(')
start=s.index('    private final java.util.',s.index('AtomicLong ID_GEN'));end=s.index(';',start)+1;field=s[start:end]
java=a.out/'VulkanSceneRegression.java';java.write_text(Path(__file__).with_name('VulkanSceneRegression.java.in').read_text().replace('/* MAP */',field).replace('/* PRODUCTION */','\n'.join(method(m) for m in markers)))
jdk=Path(os.environ['JAVA_HOME'])/'bin';subprocess.run([str(jdk/'javac'),str(java)],check=True)
results=[]
for n in range(4):
 r=subprocess.run([str(jdk/'java'),'-cp',str(a.out),'VulkanSceneRegression',str(n)],capture_output=True,text=True,timeout=10);results.append(dict(case=n,passed=r.returncode==0,output=r.stdout+r.stderr))
report=dict(sourceSha256=hashlib.sha256(src.read_bytes()).hexdigest(),passed=sum(r['passed'] for r in results),total=len(results),results=results);(a.out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if not a.baseline and report['passed']!=report['total']:raise SystemExit(1)
