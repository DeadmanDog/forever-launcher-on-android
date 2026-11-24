#!/usr/bin/env python3
"""Hidden pending cursor must not prevent unchanged-frame suppression or FIFO drain."""
from pathlib import Path
import argparse,subprocess,json,os
p=argparse.ArgumentParser();p.add_argument('--source',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args();r=Path(__file__).resolve().parents[3]
s=(a.source or r/'app/src/main/cpp/winlator/VulkanRendererContext.cpp').read_text()
def method(marker):
 start=s.index(marker);i=s.index('{',start)+1;depth=1
 while depth:depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[start:i]
production='\n'.join(method(m) for m in ['bool VulkanRendererContext::frameUnchanged(', 'bool VulkanRendererContext::drainQueuedFrame(', 'bool VulkanRendererContext::holdDrainedContent('])
stub=(Path(__file__).with_name('RenderPresentRegression.cpp.in')).read_text().split('int main(')[0]
main=r"""
int main(int,char**argv){int n=atoi(argv[1]);VulkanRendererContext c;c.presentedGen=c.sceneGen.load();
 // Valid event order: new visible image staged; mouse-look hides cursor before the frame snapshot.
 c.cursorVisible=true;c.isCursorImageDirty=true;c.cursorVisible=false;
 if(n==0){int requests=0;for(int i=0;i<1000;i++)requests+=!c.frameUnchanged();
 printf("1000 unchanged wakeups with pending hidden image: additional frame requests=%d\n",requests);
 check(requests==0,"hidden pending image forces frames which cannot upload or draw that image");
 check(c.isCursorImageDirty,"skip lost deferred image");}
 if(n==1){c.drainOne=true;c.contentGen=1;c.presentedContentGen=0;
 check(c.drainQueuedFrame(),"hidden dirty cursor prevents valid FIFO backlog drain");
 check(c.statDrains==1&&!c.drainOne,"requested drain happens once");
 check(c.isCursorImageDirty,"FIFO drain must retain hidden pending image");
 check(c.holdDrainedContent(),"skipped game picture remains held until next game frame/deadline");
 c.contentGen=2;check(!c.holdDrainedContent(),"new game picture must not be frozen by hold");}
 if(n==2){c.cursorVisible=true;check(!c.frameUnchanged(),"reveal must upload/draw pending image");
 c.drainOne=true;c.contentGen=1;check(!c.drainQueuedFrame(),"visible dirty cursor must not be skipped");
 c.isCursorImageDirty=false;c.pointerX=23;check(!c.frameUnchanged(),"real visible pointer movement must redraw");}
 if(n==3){c.markSceneDirty();check(!c.frameUnchanged(),"new game/scene content must present even with hidden cursor");
 c.presentedGen=c.sceneGen.load();c.deleteQueue.push_back(WinTex{});check(!c.frameUnchanged(),"retired resources still require processing");}
 printf("PASS hidden cursor pacing case%d\n",n);}
"""
a.out.mkdir(parents=True,exist_ok=False);f=a.out/'hidden-pacing.cpp';f.write_text(stub.replace('/* PRODUCTION */',production)+main);exe=a.out/'hidden-pacing'
inc=Path.home()/'.local/share/wow-build-tools/sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
subprocess.run(['g++','-std=c++17','-O1','-g','-no-pie','-fsanitize=address,undefined','-I',str(r/'app/src/main/cpp/winlator'),'-idirafter',str(inc),str(f),'-o',str(exe)],check=True)
results=[]
for n in range(4):
 z=subprocess.run([str(exe),str(n)],capture_output=True,text=True,timeout=10);results.append({'case':n,'exit':z.returncode,'output':z.stdout+z.stderr})
(a.out/'result.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results,indent=2));raise SystemExit(any(z['exit'] for z in results))
