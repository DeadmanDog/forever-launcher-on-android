#!/usr/bin/env python3
"""1.2.23: the FIFO drain check after a skip (VulkanRendererContext::latencyWindowDone, production source compiled on the host).

A hitch in the first window after a working skip refills the queue the skip had emptied. The check then sees no drop, calls the skip
useless and raises the floor by a whole refresh, so that queued frame is never taken out again (+1 refresh of latency for good, and
the next such case +2). Old = the production function with the drainHoldDirty guard removed; it must fail, the production one pass.
Scenario A (deterministic): floor learned, hitch (+1 frame), skip, a hitch in the first check window, then clean windows.
Scenario B ("lowest", the player's mode): an hour of 1 s windows with a hitch in 5% of them (each leaves one more queued frame,
at most two); "low" stops by design at the first level a skip reached.
"""
import subprocess, sys, tempfile
from pathlib import Path
src_path = Path(__file__).resolve().parents[3] / "app/src/main/cpp/winlator/VulkanRendererContext.cpp"
GUARD = "{ if (!drainHoldDirty) latencyFloorNs = medianNs; }"
pre = r'''
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <algorithm>
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
  const int mode = atoi(argv[1]); const int64_t R = 16666667, base = (int64_t)(2.2 * R);
  int fails = 0;
  {   // A
    Ctx c; c.fifoDrainMode = mode; int q = 0;
    auto win = [&](bool hitch) { if (hitch) q = std::min(q + 1, 2); c.latencyWindowDone(base + q * R, R, hitch, false);
                                 if (c.drainOne) { c.drainOne = false; if (q > 0) q--; } };
    for (int i = 0; i < 5; i++) win(false);
    const int64_t floor0 = c.latencyFloorNs;
    win(true); win(false);                  // one more queued frame, then the skip (q back to 0)
    win(true);                              // a hitch in the first check window refills it
    for (int i = 0; i < 12; i++) win(false);
    std::printf("A: floor %.1f -> %.1f ms, queued frames at the end %d\n", floor0 / 1e6, c.latencyFloorNs / 1e6, q);
    if (c.latencyFloorNs > floor0 + R / 2 || q != 0) fails++;
  }
  {   // B
    Ctx c; c.fifoDrainMode = mode; std::mt19937 rng(11); std::uniform_real_distribution<double> p(0, 1); std::normal_distribution<double> nd(0, 3e5);
    int q = 0; long qsum = 0; const int windows = 3600;
    for (int w = 0; w < windows; w++) {
      bool dirty = false; if (p(rng) < 0.05) { q = std::min(q + 1, 2); dirty = true; }
      c.latencyWindowDone(base + q * R + (int64_t)nd(rng), R, dirty, false); qsum += q;
      if (c.drainOne) { c.drainOne = false; if (q > 0) q--; }
    }
    const double mean = qsum / (double)windows;
    std::printf("B: mean extra queued frames over an hour %.2f\n", mean);
    if (mode == 2 && mean > 0.5) fails++;   // "low" stops by design at the first level a skip reached
  }
  return fails ? 1 : 0;
}
'''
def build(text, tmp, name):
    a = text.index("void VulkanRendererContext::latencyWindowDone(")
    b = text.index("\nbool VulkanRendererContext::drainQueuedFrame()", a)
    cpp = Path(tmp) / (name + ".cpp"); exe = Path(tmp) / name
    cpp.write_text(pre + text[a:b].replace("VulkanRendererContext::", "Ctx::") + post)
    subprocess.run(["g++", "-std=c++17", "-O1", "-fsanitize=address,undefined", str(cpp), "-o", str(exe)], check=True)
    return exe
src = src_path.read_text()
assert src.count(GUARD) == 1, "production drain check no longer has the drainHoldDirty guard"
with tempfile.TemporaryDirectory() as tmp:
    new = build(src, tmp, "new"); old = build(src.replace(GUARD, "latencyFloorNs = medianNs;"), tmp, "old")
    ok = True
    for mode, label in ((2, "lowest"), (1, "low")):
        o = subprocess.run([str(old), str(mode)], capture_output=True, text=True); n = subprocess.run([str(new), str(mode)], capture_output=True, text=True)
        print(f"[{label}] old:\n{o.stdout}[{label}] new:\n{n.stdout}")
        ok &= o.returncode != 0 and n.returncode == 0
print("PASS drain_hold" if ok else "FAIL drain_hold"); sys.exit(0 if ok else 1)
