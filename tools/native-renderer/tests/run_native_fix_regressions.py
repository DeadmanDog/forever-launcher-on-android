#!/usr/bin/env python3
"""Regressions for the 2026-11-14 native review fixes, on the host, against the production source.

  1. idle queue  - Present holds retired after the last snapshot are seen and freed once every frame is done (no 2 s stall
                   under scanout).
  2. AHB hold    - an AHB update that cannot be used (failed import, out of memory anywhere) hands its hold back exactly once.
  3. CPU switch  - a window going from Present buffers to CPU pixels gives its imported buffers up; a failed CPU texture still
                   wakes the loop for the tokens it retired.
  4. structure   - render-loop guards that need a device to run: scanout+resize never spins, swapchain failures back off,
                   bounded acquire, sampler swap order, cubic extension name, dead renderer refuses a reattach.

    python3 -B run_native_fix_regressions.py [source root]   (a different root, e.g. the base copy, is a mutation check)
"""
from pathlib import Path
import os, subprocess, sys, tempfile
tests = Path(__file__).resolve().parent
project = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else tests.parents[2]
own = tests.parents[2] / 'app/src/main/cpp/winlator'
s = (project / 'app/src/main/cpp/winlator/VulkanRendererContext.cpp').read_text()
def method(marker):
    start = s.index(marker); i = s.index('{', start) + 1; depth = 1
    while depth: depth += (s[i] == '{') - (s[i] == '}'); i += 1
    return s[start:i]
def body(a, b): return s[s.index(a):s.index(b, s.index(a))]
failures = []
def run(name, cpp_text, args_list, extra_flags=()):
    with tempfile.TemporaryDirectory(prefix='wow-native-fix-') as folder:
        cpp = Path(folder) / (name + '.cpp'); cpp.write_text(cpp_text); exe = Path(folder) / name
        r = subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-pthread', '-no-pie', '-fsanitize=address,undefined', *extra_flags,
                            '-I' + str(own), str(cpp), '-o', str(exe)], capture_output=True, text=True)
        if r.returncode: print(r.stderr[-3000:]); failures.append(name + ' (build)'); return
        for args in args_list:
            r = subprocess.run([str(exe), *args], capture_output=True, text=True, timeout=30)
            print((r.stdout + r.stderr).strip())
            if r.returncode: failures.append(f'{name} {" ".join(args)}')

# 1. idle queue
stub = (tests / 'RenderIdlePollTest.cpp.in').read_text()
stub = stub[:stub.index('/* PRODUCTION */')]
if 'deleteQueue' not in stub: stub = stub.replace(' std::vector<int64_t> idleReleased;', ' std::vector<WinTex> deleteQueue,pendingRetirements;\n std::vector<int64_t> idleReleased;')
run('idle_queue', stub + method('bool VulkanRendererContext::hasIdleTokensInFlight(') + '\n' + method('void VulkanRendererContext::pollIdleTokens(')
    + '\n' + (tests / 'RenderIdleQueueTest.cpp.in').read_text(), [[]])

# 2. AHB hold
t = (tests / 'RenderAhbOwnershipTest.cpp.in').read_text()
t = t[:t.index('\n', t.index('int VulkanRendererContext::barrierCount()')) + 1]
a = s.index('        frameDraws.clear();', s.index('void VulkanRendererContext::renderFrame()')); snapshot = s[a:s.index('        if (isCursorImageDirty.load()', a)]
a = s.index('    for (auto& d : draws)', s.index('void VulkanRendererContext::recordCmdBuf(')); barriers = s[a:s.index('    if (!ahbTransitions.empty())', a)]
commit = body('void VulkanRendererContext::commitAhbTransitions()', '\nbool VulkanRendererContext::waitForFrameFence(')
prod = (s[s.index('void VulkanRendererContext::destroyWinTex('):s.index('\n}\n', s.index('void VulkanRendererContext::destroyWinTex(')) + 3] + commit
        + body('void VulkanRendererContext::updateWindowContentAHB(', '\nvoid VulkanRendererContext::setIdleSink(')
        + method('void VulkanRendererContext::retireIdleTokens(') + method('void VulkanRendererContext::releaseAhbOwnership(')
        + method('void VulkanRendererContext::removeWindow(') + method('void VulkanRendererContext::setRenderList(')
        + method('void VulkanRendererContext::appendWindowDraw('))
main = r'''
static void check(bool b,const char*m){if(!b){fprintf(stderr,"FAIL %s\n",m);exit(2);}}
static int occurrences(VulkanRendererContext&c,int64_t t){
 int n=0;for(auto v:c.idleReleased)n+=v==t;for(auto&kv:c.windowIdleTokens)for(auto v:kv.second)n+=v==t;for(auto&w:c.deleteQueue)n+=w.idleToken==t;return n;}
int main(int,char**argv){int which=atoi(argv[1]);
 if(which==0){VulkanRendererContext c;AHardwareBuffer a{1};c.failImport=true;
  c.updateWindowContentAHB(1,&a,32,32,0,0,0,0,77);
  check(c.idleReleased==std::vector<int64_t>({77}),"a failed import hands its hold back (it used to wait out the 2 s limit)");
  check(c.needsRender.load(),"and wakes the render thread to deliver it");}
 if(which==1){
  // Out of memory at every allocation point: the hold ends up in exactly one place, never lost, never twice.
  for(int n=1;n<=24;n++){VulkanRendererContext c;AHardwareBuffer a{50+n};bool threw=false;g_failCountdown=n;
   try{c.updateWindowContentAHB(1,&a,32,32,0,0,0,0,900+n);}catch(...){threw=true;}
   g_failCountdown=0;(void)threw;
   check(occurrences(c,900+n)==1,"every hold is kept or handed back exactly once");}}
 puts("PASS ahb hold");}
'''
run('ahb_hold', t.replace('/* SNAPSHOT */', snapshot).replace('/* BARRIERS */', barriers).replace('/* PRODUCTION */', prod) + main, [['0'], ['1']])

# 3. CPU switch
t = (tests / 'RenderUploadSceneRegression.cpp.in').read_text()
t = t[:t.index('/* PRODUCTION */')].replace('/* CAPTURE */', '').replace('/* COMMIT */', '').replace('/* BARRIERS */', '')
if 'windowAhbs' not in t:
    t = t.replace(' std::vector<int64_t> idleReleased;', ' std::unordered_map<int64_t,std::vector<AHardwareBuffer*>> windowAhbs;void releaseAhbOwnership(AHardwareBuffer*){}\n std::vector<int64_t> idleReleased;')
t = t.replace('void releaseAhbOwnership(AHardwareBuffer*){}', 'std::vector<AHardwareBuffer*> releasedAhbs;void releaseAhbOwnership(AHardwareBuffer*b){releasedAhbs.push_back(b);}')
prod = '\n'.join(method(m) for m in ['uint32_t VulkanRendererContext::findMemType(', 'void VulkanRendererContext::createBuffer(',
    'bool VulkanRendererContext::createWinTexResources(', 'void VulkanRendererContext::destroyWinTex(',
    'void VulkanRendererContext::freeRetiredTextures(', 'void VulkanRendererContext::updateWindowContent('])
main = r'''
static void check(bool b,const char*m){if(!b){fprintf(stderr,"FAIL %s\n",m);exit(2);}}
int main(int,char**argv){int which=atoi(argv[1]);unsigned char p[16];memset(p,7,sizeof p);
 if(which==0){VulkanRendererContext c;AHardwareBuffer a,b;
  VulkanRendererContext::WinTex shown;shown.isAHB=true;shown.ahb=&a;shown.img=5;shown.w=shown.h=2;c.texMap[1]=shown;c.windowAhbs[1]={&a,&b};
  c.updateWindowContent(1,p,2,2,2,0,0);
  check(c.releasedAhbs.size()==2&&c.windowAhbs.count(1)==0,"the window's Present imports are given up when it shows CPU pixels");
  check(!c.texMap[1].isAHB&&c.texMap[1].img!=0,"and it shows its CPU texture");
  c.cleanup();}
 if(which==1){VulkanRendererContext c;c.windowIdleTokens[1]={5};c.vk_.fail=5;
  c.updateWindowContent(1,p,2,2,2,0,0);
  check(c.needsRender.load(),"a failed CPU texture still wakes the loop for the holds it retired");
  check(c.vk_.empty(),"nothing leaks");}
 puts("PASS cpu switch");}
'''
inc = Path(os.environ.get('ANDROID_HOME', str(Path.home() / '.local/share/wow-build-tools/sdk'))) / 'ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
run('cpu_switch', t + prod + main, [['0'], ['1']], ['-idirafter', str(inc)])

# 4. structure
def need(name, cond):
    print(('PASS ' if cond else 'FAIL ') + name)
    if not cond: failures.append(name)
frame = body('void VulkanRendererContext::renderFrame()', 'bool VulkanRendererContext::frameUnchanged(')
loop = body('void VulkanRendererContext::renderLoop()', 'void VulkanRendererContext::commitAhbTransitions()')
scan = frame[frame.index('if (scanoutActive.load()) {'):frame.index('if (surfaceWidth==0||surfaceHeight==0)')]
need('scanout early return only without a pending resize (no 100% CPU spin)', '} else if (!fbResized.load()) {' in scan and 'return;' in scan)
need('scanout early return hands queued holds back', 'pollIdleTokens();' in scan and 'deliverReleasedIdle();' in scan)
rec = frame[frame.index('if (fbResized.load()) {'):frame.index('if (currentFrame >= cmdBufs.size()')]
need('failed swapchain rebuild keeps fbResized and backs off', 'recreateBackoff = true;' in rec and 'SwapchainZeroExtent' in rec)
need('a new swapchain under scanout gets its black frame', 'scanoutBlackFrameDone.store(false)' in rec)
need('render loop waits out the back-off', 'if (recreateBackoff) {' in loop and 'waitFor(std::chrono::milliseconds(100)' in loop)
need('acquire is bounded and a timeout retries', 'AcquireNextImageKHR(device,swapchain,UINT64_MAX' not in frame and 'res==VK_TIMEOUT' in frame)
sf = method('void VulkanRendererContext::setFilterMode(')
need('new sampler made before the old one is destroyed', sf.index('makeSampler(mode)') < sf.index('DestroySampler'))
dev = method('void VulkanRendererContext::createLogicalDevice(')
need('cubic extension enabled under the name the driver offers', 'extList.push_back(cubicExtName)' in dev and 'extList.push_back("VK_EXT_filter_cubic")' not in dev)
ra = method('bool VulkanRendererContext::reattachSurface(')
need('a dead renderer refuses a reattach', ra.index('if (!isRunning.load())') < ra.index('CreateAndroidSurfaceKHR'))
print('ALL PASSED' if not failures else 'FAILED: ' + ', '.join(failures))
sys.exit(1 if failures else 0)
