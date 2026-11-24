#!/usr/bin/env python3
"""1.2.23: the FIFO drain unit (FifoPresentGate::drainStepNs) with the production latencyWindowDone, compiled on the host.

One more queued frame adds one GAME frame interval to the time to the screen. At 60 FPS on the player's 144 Hz panel (frames shown
for 2,3,2,2,3 refreshes) the window median also wobbles by about half a refresh; with the refresh as the unit the drain skipped
frames that were not queued (a stutter each). Old = the refresh as the unit, new = drainStepNs. An hour of 1 s windows, a hitch
(one more queued frame, at most two) in 5% of them, the median wobbling by +-half a refresh when the limit does not divide the rate.
"""
import subprocess, sys, tempfile
from pathlib import Path
src_path = Path(__file__).resolve().parents[3] / "app/src/main/cpp/winlator/VulkanRendererContext.cpp"
pre = r'''
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <algorithm>
#include "FifoPresentGate.h"
enum { VK_PRESENT_MODE_FIFO_KHR = 2 };
struct Ctx {
  enum WindowState { kWindowNone = 0, kWindowClean, kWindowDirty, kWindowSlow, kWindowHold, kWindowSettle, kWindowRefilled };
  static constexpr int kDrainOff = 0, kDrainOneStep = 1, kDrainProbe = 2;
  static constexpr int kSettleWindows = 2, kRefillWatchWindows = 20;
  std::atomic<int> fifoDrainMode{kDrainProbe}; std::atomic<int> activePresentMode{VK_PRESENT_MODE_FIFO_KHR};
  std::atomic<int64_t> exportWindowNs{0}, exportFloorNs{0}; std::atomic<int> exportWindowState{0};
  int64_t latencyFloorNs = 0; int highWindows = 0; int drainHoldWindows = 0; int64_t latencyBeforeDrainNs = 0; bool drainOne = false;
  bool latencyWindowAppLate = false; int64_t drainReachedNs = 0; int cleanWindows = kSettleWindows; int refillWatchWindows = 0;
  bool refilled = false; bool drainHoldDirty = false;
  void latencyWindowDone(int64_t medianNs, int64_t refreshNs, bool dirty, bool slow);
};
'''
post = r'''
int main(int argc, char** argv) {
  const double hz = atof(argv[1]); const int limit = atoi(argv[2]); const int useNew = atoi(argv[3]);
  const int64_t R = (int64_t)(1e9 / hz), G = (int64_t)(1e9 / limit), base = 2 * G + R;
  const int64_t unit = useNew ? FifoPresentGate::drainStepNs(R, G) : R;
  const bool divides = ((int)hz % limit) == 0;
  Ctx c; c.fifoDrainMode = 2;
  std::mt19937 rng(5); std::uniform_real_distribution<double> p(0, 1), u(-0.5, 0.5); std::normal_distribution<double> nd(0, 3e5);
  int q = 0, wasted = 0, useful = 0; long qsum = 0; const int windows = 3600;
  for (int w = 0; w < windows; w++) {
    bool dirty = false; if (p(rng) < 0.05) { q = std::min(q + 1, 2); dirty = true; }
    const int64_t wobble = divides ? 0 : (int64_t)(u(rng) * R);
    c.latencyWindowDone(base + q * G + wobble + (int64_t)nd(rng), unit, dirty, false); qsum += q;
    if (c.drainOne) { c.drainOne = false; if (q > 0) { q--; useful++; } else wasted++; }
  }
  std::printf("%d %d %.2f\n", wasted, useful, qsum / (double)windows);
  return 0;
}
'''
text = src_path.read_text()
a = text.index("void VulkanRendererContext::latencyWindowDone(")
b = text.index("\nbool VulkanRendererContext::drainQueuedFrame()", a)
assert "FifoPresentGate::drainStepNs(refresh, interval)" in text, "the production call no longer passes drainStepNs"
with tempfile.TemporaryDirectory() as tmp:
    cpp = Path(tmp) / "step.cpp"; exe = Path(tmp) / "step"
    cpp.write_text(pre + text[a:b].replace("VulkanRendererContext::", "Ctx::") + post)
    subprocess.run(["g++", "-std=c++17", "-O1", "-fsanitize=address,undefined", "-I", str(src_path.parent), str(cpp), "-o", str(exe)], check=True)
    ok = True
    for hz, limit in ((144, 60), (120, 60), (60, 60), (144, 72)):
        o = subprocess.check_output([str(exe), str(hz), str(limit), "0"], text=True).split()
        n = subprocess.check_output([str(exe), str(hz), str(limit), "1"], text=True).split()
        print(f"{hz} Hz / {limit} FPS: wasted skips old {o[0]} new {n[0]} | useful old {o[1]} new {n[1]} | mean extra frames old {o[2]} new {n[2]}")
        ok &= int(n[0]) == 0 and float(n[2]) <= 0.5
        if (hz, limit) == (144, 60): ok &= int(o[0]) > 0
        if hz == limit: ok &= o == n
print("PASS drain_step" if ok else "FAIL drain_step")
sys.exit(0 if ok else 1)
