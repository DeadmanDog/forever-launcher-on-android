#!/usr/bin/env python3
"""Present hand-back timing of the compositor path (presentidle ON), production code compiled on the host.

Extracted verbatim from the given VulkanRendererContext.cpp: freeRetiredTextures, commitTextureRetirements, retireIdleTokens,
drainFinishedOtherSlots, hasIdleTokensInFlight, pollIdleTokens, deliverReleasedIdle, the renderFrame prologue (fence check ..
deliverReleasedIdle before acquire) and the renderFrame snapshot of the retirement lists. IdlePollPacer.h is included as is.
The renderLoop idle wait is transcribed (begin/woken/nextWaitNs/polled, hasIdleTokensInFlight, pollIdleTokens, deliver).
Game: 60 FPS limit on a 144 Hz panel (2,3,2,2,3 refresh releases), presents 8 ms after release; each Present replaces the
window's buffer (updateWindowContentAHBLocked: retireIdleTokens(old tokens), push the new one). GPU composite takes C ms.
Measured per game frame: time from the buffer being replaced to its hold reaching the Java sink, and renderLoop idle wake-ups.
FAIL when the mean hand-back delay exceeds 1 ms or the loop wakes for the poll more than 0.05 times per game frame."""
import subprocess, sys, tempfile
from pathlib import Path
here = Path(__file__).resolve().parent
src_file = (Path(sys.argv[1]) if len(sys.argv) > 1 else here.parents[2] / 'app/src/main/cpp/winlator/VulkanRendererContext.cpp').resolve(); s = src_file.read_text()
def method(marker):
    start = s.index(marker); i = s.index('{', start) + 1; depth = 1
    while depth: depth += (s[i] == '{') - (s[i] == '}'); i += 1
    return s[start:i]
def between(a, b):
    i = s.index(a); return s[i:s.index(b, i)]
methods = '\n'.join(method('%s VulkanRendererContext::%s(' % m) for m in [
    ('void', 'freeRetiredTextures'), ('void', 'commitTextureRetirements'), ('void', 'retireIdleTokens'),
    ('void', 'drainFinishedOtherSlots'), ('bool', 'hasIdleTokensInFlight'), ('void', 'pollIdleTokens'), ('bool', 'deliverReleasedIdle')])
prologue = between('    bool currentFenceWaited = false;', '    VkSemaphore acquiredSems[1]{};')
snapshot = between('        if (pendingRetirements.empty()) {', '        ox=sceneOffsetX')
cpp = r'''
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <map>
#include <mutex>
#include <vector>
#include "IdlePollPacer.h"
using VkResult=int; using VkFence=int; using VkDevice=int; using VkBuffer=int; using VkDeviceMemory=int; using VkDescriptorSet=int;
using VkImageView=int; using VkImage=int; using VkDescriptorPool=int; using VkDeviceSize=uint64_t;
constexpr int VK_SUCCESS=0, VK_NOT_READY=1, VK_NULL_HANDLE=0; constexpr unsigned MAX_FRAMES_IN_FLIGHT=2;
static int64_t g_now = 0; static int64_t fenceDone[3] = {0, 0, 0};
static VkResult getFenceStatus(VkDevice, VkFence f) { return g_now >= fenceDone[f] ? VK_SUCCESS : VK_NOT_READY; }
static void noop3(VkDevice, int, const void*) {}
static void freeDs(VkDevice, VkDescriptorPool, int, const VkDescriptorSet*) {}
struct VulkanRendererContext {
  struct CpuUpload { VkBuffer buffer = 0; VkDeviceMemory memory = 0; };
  struct WinTex { VkDescriptorSet ds = 0; VkImageView view = 0; VkImage img = 0; VkDeviceMemory mem = 0; VkBuffer stg = 0;
                  VkDeviceMemory stgMem = 0; CpuUpload uploads[MAX_FRAMES_IN_FLIGHT]{}; bool isAHB = false; int64_t idleToken = 0; };
  struct { VkResult (*GetFenceStatus)(VkDevice, VkFence) = getFenceStatus; void (*DestroyBuffer)(VkDevice, int, const void*) = noop3;
           void (*FreeMemory)(VkDevice, int, const void*) = noop3; void (*FreeDescriptorSets)(VkDevice, VkDescriptorPool, int, const VkDescriptorSet*) = freeDs;
           void (*DestroyImageView)(VkDevice, int, const void*) = noop3; void (*DestroyImage)(VkDevice, int, const void*) = noop3; } vk_;
  VkDevice device = 1; VkDescriptorPool winTexPool = 1; std::mutex renderMutex, idleSinkMutex;
  VkFence inFlightFences[MAX_FRAMES_IN_FLIGHT]{1, 2}; uint32_t currentFrame = 0;
  std::vector<WinTex> deleteQueue, pendingRetirements, submittedRetirements[MAX_FRAMES_IN_FLIGHT];
  std::vector<int64_t> idleReleased; std::function<void(const std::vector<int64_t>&)> idleSink;
  bool isRunning = true;
  bool waitForFrameFence(VkFence f) { g_now = std::max(g_now, fenceDone[f]); return true; }
  void freeRetiredTextures(std::vector<WinTex>&); void commitTextureRetirements(); void retireIdleTokens(std::vector<int64_t>&);
  void drainFinishedOtherSlots(); bool hasIdleTokensInFlight(); void pollIdleTokens(); bool deliverReleasedIdle();
  void prologue() {
/* PROLOGUE */
  }
  void snapshot() { std::lock_guard<std::mutex> lk(renderMutex);
/* SNAPSHOT */
  }
};
/* METHODS */
int main(int argc, char** argv) {
  const double compositeMs = atof(argv[1]);
  const int64_t R = 1000000000LL / 144; const double per = 60.0 / 144.0;
  VulkanRendererContext c; std::map<int64_t, int64_t> retiredAt, readUntil; int unsafe = 0; std::vector<double> delays; int crossed = 0;
  c.idleSink = [&](const std::vector<int64_t>& tokens) { for (int64_t t : tokens) { auto it = retiredAt.find(t);
      if (it == retiredAt.end()) continue; if (g_now < readUntil[t]) ++unsafe; const int64_t d = g_now - it->second; delays.push_back(d / 1e6);
      if (g_now / R != it->second / R) ++crossed; retiredAt.erase(it); } };
  std::vector<int64_t> arrivals; double budget = 1.0;
  for (int v = 1; v < 144 * 30; ++v) { budget = std::min(1.0 + per, budget + per); if (budget >= 1.0 - 1e-6) { budget -= 1.0; arrivals.push_back(v * R + 8000000); } }
  std::vector<int64_t> windowTokens; int64_t nextToken = 1; IdlePollPacer pacer; bool idleStretch = false; long idleWakeups = 0;
  for (size_t i = 0; i < arrivals.size(); ++i) {
    g_now = std::max(g_now, arrivals[i]);
    { std::lock_guard<std::mutex> lk(c.renderMutex);                    // updateWindowContentAHBLocked: a new buffer replaces the shown one
      for (int64_t t : windowTokens) { retiredAt[t] = g_now; readUntil[t] = std::max(fenceDone[1], fenceDone[2]); }   // frames that may read it
      c.retireIdleTokens(windowTokens); windowTokens.push_back(nextToken++); }
    idleStretch = false; pacer.woken();                                   // the real wake-up of the update
    c.prologue(); c.snapshot();                                           // renderFrame: before acquire, then the draw snapshot
    fenceDone[c.inFlightFences[c.currentFrame]] = g_now + (int64_t)(compositeMs * 1e6);   // QueueSubmit
    c.commitTextureRetirements(); c.currentFrame = (c.currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
    const int64_t next = i + 1 < arrivals.size() ? arrivals[i + 1] : g_now + 100000000;
    while (!pacer.givenUp() && c.hasIdleTokensInFlight()) {               // renderLoop idle wait (hand-back on)
      if (!idleStretch) { pacer.begin(); idleStretch = true; }
      if (g_now + pacer.nextWaitNs() >= next) break;                       // the next game frame wakes it first
      g_now += pacer.nextWaitNs(); ++idleWakeups;
      c.pollIdleTokens(); pacer.polled(c.deliverReleasedIdle());
    }
  }
  double sum = 0, mx = 0; for (double d : delays) { sum += d; mx = std::max(mx, d); }
  const double mean = sum / std::max<size_t>(1, delays.size()), wpf = (double)idleWakeups / arrivals.size();
  printf("composite %.1f ms: %zu game frames, hand-back after the replacing Present: mean %.2f ms max %.2f ms, "
         "in a later 144 Hz refresh %.0f%%, idle poll wake-ups %.2f per game frame\n",
         compositeMs, arrivals.size(), mean, mx, 100.0 * crossed / std::max<size_t>(1, delays.size()), wpf);
  printf("  safety: %d buffers handed back while a frame that may read them was still on the GPU\n", unsafe);
  if (unsafe) return 2;
  if (compositeMs > 13.0) return 0;                                       // overload: only safety is checked
  return mean <= 1.0 && wpf <= 0.05 ? 0 : 1;
}
'''
cpp = cpp.replace('/* PROLOGUE */', prologue).replace('/* SNAPSHOT */', snapshot).replace('/* METHODS */', methods)
ok = True
with tempfile.TemporaryDirectory(dir=here) as tmp:
    f = Path(tmp) / 'handback.cpp'; f.write_text(cpp); exe = Path(tmp) / 'handback'
    subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-fsanitize=address,undefined', '-I', str(src_file.parent), '-I', str(here.parents[2] / 'app/src/main/cpp/winlator'), str(f), '-o', str(exe)], check=True)
    for c in ('2', '5', '8', '20'):
        r = subprocess.run([str(exe), c], capture_output=True, text=True); print(r.stdout.strip(), r.stderr[-800:]); ok &= r.returncode == 0
print('PASS handback' if ok else 'FAIL handback'); sys.exit(0 if ok else 1)
