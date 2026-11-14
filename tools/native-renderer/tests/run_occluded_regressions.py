"""Codex OPT13-07 (adopted from its round-13 probe): a same-size update of a window left out of the render list (covered) must not
request a compositor frame or count as game content; the visible update, the reveal wake-up and the fresh pixels on reveal stay.
Runs the production code and a mutation without the visibility check (which must fail). Usage: run_occluded_regressions.py <out>"""
from pathlib import Path
import subprocess,os,json,hashlib,sys
r=Path(__file__).resolve().parents[3];out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True);s=(r/'app/src/main/cpp/winlator/VulkanRendererContext.cpp').read_text()
def method(m):
 a=s.index(m);i=s.index('{',a)+1;d=1
 while d:d+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[a:i]
markers=['uint32_t VulkanRendererContext::findMemType(', 'void VulkanRendererContext::createBuffer(', 'bool VulkanRendererContext::createWinTexResources(', 'void VulkanRendererContext::destroyWinTex(', 'void VulkanRendererContext::freeRetiredTextures(', 'void VulkanRendererContext::updateWindowContent(', 'void VulkanRendererContext::setRenderList(', 'void VulkanRendererContext::setTransform(', 'void VulkanRendererContext::appendWindowDraw(', 'void VulkanRendererContext::commitCpuUploads(', 'VkBuffer VulkanRendererContext::prepareCpuUpload(']
production='\n'.join(method(m) for m in markers)
template=(r/'tools/native-renderer/tests/RenderUploadSceneRegression.cpp.in').read_text()
a=s.index('    for (auto& d : draws)',s.index('void VulkanRendererContext::recordCmdBuf('));b=s.index('    for (auto& d : draws)',a+10);barriers=s[a:b]
case=r"""
 if(n==21){
  c.updateWindowContent(1,p,2,2,2,0,0);c.updateWindowContent(2,p,2,2,2,0,0);
  int64_t ids[1]={1};int xs[1]={0},ys[1]={0};c.setRenderList(ids,xs,ys,1);
  const auto gen=c.contentGen.load();unsigned requests=0;
  for(int j=0;j<100;j++){c.needsRender=false;memset(p,j,sizeof(p));c.updateWindowContent(2,p,2,2,2,0,0);requests+=c.needsRender.load();}
  const auto spurious=c.contentGen.load()-gen;
  std::printf("occluded_same_size_updates=100 render_requests=%u false_game_updates=%llu\n",requests,(unsigned long long)spurious);
  const bool preserved=(*c.texMap.at(2).pixels)[0]==99;
  c.needsRender=false;memset(p,42,sizeof(p));c.updateWindowContent(1,p,2,2,2,0,0);const bool visibleWorks=c.needsRender.load();
  c.needsRender=false;ids[0]=2;c.setRenderList(ids,xs,ys,1);const bool revealWakes=c.needsRender.load();c.capture();const bool revealFresh=c.frameDraws.size()==1&&c.frameDraws[0].id==2&&c.vk_.first(c.frameDraws[0].upload)==99;
  std::printf("latest_hidden_content=%s visible_update=%s reveal_wakeup=%s reveal_fresh_pixels=%s\n",preserved?"PASS":"FAIL",visibleWorks?"PASS":"FAIL",revealWakes?"PASS":"FAIL",revealFresh?"PASS":"FAIL");
  c.cleanup();check(preserved&&visibleWorks&&revealWakes&&revealFresh,"visibility and latest content controls");check(!requests&&!spurious,"occluded-only updates still request compositor frames and count as game updates");
 }
"""
template=template.replace(' printf("PASS upload/scene',case+'\n printf("PASS upload/scene')
mutated=production.replace('        if (!drawn && !housekeeping && !scanoutActive.load()) return;\n','');assert mutated!=production
inc=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'.local/share/wow-build-tools/sdk')))/'ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include';env=dict(os.environ,ASAN_OPTIONS='detect_leaks=0');results=[]
for label,prod in [('production',production),('mutation-no-visibility-check',mutated)]:
 d=out/label;d.mkdir(exist_ok=True);cpp=d/'test.cpp';exe=d/'test'
 cpp.write_text(template.replace('/* PRODUCTION */',prod).replace('/* CAPTURE */','frameDraws.clear();for(auto& re:renderList)appendWindowDraw(re);').replace('/* COMMIT */','commitCpuUploads();').replace('/* BARRIERS */',barriers))
 subprocess.run(['g++','-std=c++17','-O1','-g','-pthread','-fsanitize=address,undefined','-idirafter',str(inc),'-I'+str(r/'app/src/main/cpp/winlator'),str(cpp),'-o',str(exe)],check=True)
 q=subprocess.run([str(exe),'21'],capture_output=True,text=True,timeout=20,env=env);print(label,q.returncode,q.stdout,q.stderr);results.append(dict(variant=label,exit=q.returncode,stdout=q.stdout,stderr=q.stderr))
exits=[v['exit'] for v in results];print('EXITS',exits);(out/'results.json').write_text(json.dumps(results,indent=2))
assert exits[0]==0 and exits[1]!=0, exits
print('PASS occluded CPU updates publish nothing; the mutation fails')
