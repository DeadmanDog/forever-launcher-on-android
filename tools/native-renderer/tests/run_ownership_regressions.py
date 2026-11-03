#!/usr/bin/env python3
from pathlib import Path
import subprocess,tempfile,sys
project=Path(__file__).resolve().parents[3];t=Path(__file__).resolve().parent
s=Path(sys.argv[1]).read_text() if len(sys.argv)>1 else (project/'app/src/main/cpp/winlator/VulkanRendererContext.cpp').read_text()
def body(a,b):return s[s.index(a):s.index(b,s.index(a))]
def method(marker):
 start=s.index(marker);i=s.index('{',start)+1;depth=1
 while depth:depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[start:i]
a=s.index('        frameDraws.clear();',s.index('void VulkanRendererContext::renderFrame()'));b=s.index('        if (isCursorImageDirty.load()',a);snapshot=s[a:b]
a=s.index('    for (auto& d : draws)',s.index('void VulkanRendererContext::recordCmdBuf('));b=s.index('    if (!ahbTransitions.empty())',a);barriers=s[a:b]
commit=body('void VulkanRendererContext::commitAhbTransitions()', '\nbool VulkanRendererContext::waitForFrameFence(') if 'void VulkanRendererContext::commitAhbTransitions()' in s else 'void VulkanRendererContext::commitAhbTransitions(){}'
parts={'ahb':s[s.index('void VulkanRendererContext::destroyWinTex('):s.index('\n}\n',s.index('void VulkanRendererContext::destroyWinTex('))+3]+commit+body('void VulkanRendererContext::updateWindowContentAHB(','\nvoid VulkanRendererContext::setIdleSink(')+method('void VulkanRendererContext::retireIdleTokens(')+method('void VulkanRendererContext::releaseAhbOwnership(')+method('void VulkanRendererContext::removeWindow('),'cursor':method('void VulkanRendererContext::updateCursorImage(')+method('void VulkanRendererContext::commitCursorUpload(')}
if 'void VulkanRendererContext::appendWindowDraw(' in s:parts['ahb']+=method('void VulkanRendererContext::appendWindowDraw(')
with tempfile.TemporaryDirectory(prefix='wow-ownership-tests-') as folder:
 for name,template,count in [('ahb','RenderAhbOwnershipTest.cpp.in',22),('cursor','RenderCursorLifetimeTest.cpp.in',5)]:
  p=Path(folder)/(name+'.cpp');p.write_text((t/template).read_text().replace('/* PRODUCTION */',parts[name]).replace('/* SNAPSHOT */',snapshot).replace('/* BARRIERS */',barriers));exe=p.with_suffix('')
  subprocess.run(['g++','-std=c++17','-O1','-g','-pthread','-fsanitize=address,undefined','-I'+str(project/'app/src/main/cpp/winlator'),str(p),'-o',str(exe)],check=True)
  for i in range(count):subprocess.run([str(exe),str(i)],check=True,timeout=10)
