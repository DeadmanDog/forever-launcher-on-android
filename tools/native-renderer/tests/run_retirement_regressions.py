#!/usr/bin/env python3
"""Exercise extracted production retirement paths, including the prior blocking path."""
from pathlib import Path
import subprocess, tempfile, sys
root = Path(__file__).resolve().parents[3]
tests = Path(__file__).resolve().parent
source = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'app/src/main/cpp/winlator/VulkanRendererContext.cpp'
s = source.read_text()
start = s.index('        // Capture only resources') if '        // Capture only resources' in s else s.index('        if (!deleteQueue.empty()) {', s.index('void VulkanRendererContext::renderFrame()'))
end = s.index('\n        ox=sceneOffsetX;', start)
capture = s[start:end]
if 'void VulkanRendererContext::freeRetiredTextures(' in s:
 start = s.index('void VulkanRendererContext::freeRetiredTextures(')
 end = s.index('\nbool VulkanRendererContext::ensureCursorTex(', start)
 production = s[start:end]
 start = s.index('    // This slot\'s last successful submission')
 end = s.index('\n    VkSemaphore acquiredSems[1]{};', start)
 completed = s[start:end]
 # The drain is downstream of the current slot's fence check, and batch commit
 # follows the successful QueueSubmit (the frame-generation path no longer exists).
 frame = s[s.index('void VulkanRendererContext::renderFrame()'):s.index('void VulkanRendererContext::onSurfaceResized(')]
 assert frame.index('freeRetiredTextures(submittedRetirements[currentFrame])') > frame.index('if (fenceStatus != VK_SUCCESS)')
 assert frame.count('commitTextureRetirements();') == frame.count('commitAhbTransitions();') == 1
 assert 'for (auto fence : inFlightFences)' not in capture
else:
 production = 'void VulkanRendererContext::freeRetiredTextures(std::vector<WinTex>&){}\nvoid VulkanRendererContext::commitTextureRetirements(){}'
 completed = ''
with tempfile.TemporaryDirectory(prefix='wow-retirement-') as temp:
 cpp = Path(temp)/'test.cpp'
 cpp.write_text((tests/'RenderRetirementTest.cpp.in').read_text().replace('/* CAPTURE */', capture).replace('/* COMPLETED */', completed).replace('/* PRODUCTION */', production))
 exe = cpp.with_suffix('')
 subprocess.run(['g++','-std=c++17','-O1','-g','-pthread','-fsanitize=address,undefined',str(cpp),'-o',str(exe)], check=True)
 subprocess.run([str(exe)],check=True,timeout=20)
