#!/usr/bin/env python3
"""FIFO present gate (FifoPresentGate.h, used by VulkanRendererContext::renderLoop) on a simulated display.

The game's buffers are released on the panel's vsync by the app limiter (the VsyncPacingCore budget pattern: 60 FPS on 144 Hz =
2,3,2,2,3 refreshes), the game presents [render] ms later; the pointer moves every 2 ms (or not at all). The render loop applies the
two production gates (hold a cursor-only change for the next game frame; presents >= 3/4 refresh apart), composites in 1.5 ms and
the FIFO shows a present on the first vsync after that, never two on one vsync. A JUDDER is a game frame whose spacing on screen to the previous
one differs from the spacing it would have had if both were presented at once (a repeated or doubled frame), by half a refresh or more. Old = the 1.2.20 hold (2 refreshes, game interval ignored), new = the production header.
"""
import subprocess, sys, tempfile
from pathlib import Path
here = Path(__file__).resolve().parent
header = here.parents[2] / "app/src/main/cpp/winlator/FifoPresentGate.h"
sim = r'''
#include "FifoPresentGate.h"
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <vector>
#include <random>
int main(int argc, char** argv) {
  const double hz = atof(argv[1]); const int limit = atoi(argv[2]); const double renderMs = atof(argv[3]);
  const int mouse = atoi(argv[4]); const int oldGate = atoi(argv[5]);
  const int64_t R = (int64_t)(1e9 / hz), G = limit > 0 ? (int64_t)(1e9 / limit) : 0;
  std::mt19937 rng(7); std::uniform_int_distribution<int> jit(-300000, 300000);
  // game present arrivals: limiter budget on the vsync grid (perVsync = limit / hz), then render time
  std::vector<int64_t> arrive; double budget = 1.0, per = limit > 0 ? limit / hz : 1.0;
  for (int v = 1; v < (int)(hz * 30); v++) { budget = std::min(per >= 1 ? per : 1.0 + per, budget + per);
    if (budget >= 1.0 - 1e-6) { budget -= 1.0; arrive.push_back(v * R + (int64_t)(renderMs * 1e6) + jit(rng)); } }
  int64_t t = 0, lastPresent = -1000000000, lastGamePresent = 0, lastDisplay = 0; size_t presentedGame = 0, next = 0;
  int late = 0, counted = 0; const int64_t end = (int64_t)(hz * 30 - 2) * R; int64_t prevShown = 0, prevNatural = 0;
  auto holdUntil = [&](int64_t lg) { return oldGate ? lg + 2 * FifoPresentGate::refresh(R) : FifoPresentGate::holdUntilNs(lg, R, G); };
  int64_t nextCursor = 0;
  while (t < end) {
    // wake: the next game frame or (mouse) the next pointer move
    int64_t wake = next < arrive.size() ? arrive[next] : end;
    if (mouse) { while (nextCursor <= t) nextCursor += 2000000; wake = std::min(wake, nextCursor); }
    if (wake > t) t = wake;
    size_t avail = next; while (avail < arrive.size() && arrive[avail] <= t) avail++;
    bool newGame = avail > presentedGame;
    if (lastGamePresent && !newGame) {
      int64_t until = holdUntil(lastGamePresent);
      if (t < until) { int64_t g = avail < arrive.size() ? arrive[avail] : end; t = std::min(until, g);
        while (avail < arrive.size() && arrive[avail] <= t) avail++; newGame = avail > presentedGame; }
    }
    int64_t spacing = FifoPresentGate::minSpacingNs(R);
    if (t - lastPresent < spacing) { t = lastPresent + spacing; while (avail < arrive.size() && arrive[avail] <= t) avail++; newGame = avail > presentedGame; }
    // present
    int64_t latch = ((t + 1500000 + R - 1) / R) * R; int64_t shown = std::max(latch, lastDisplay + R); lastDisplay = shown;
    if (newGame) {
      int64_t a = arrive[avail - 1]; int64_t natural = ((a + 1500000 + R - 1) / R) * R;
      if (a > 2e9 && prevShown) { counted++; if (std::llabs((shown - prevShown) - (natural - prevNatural)) >= R / 2) late++; }
      prevShown = shown; prevNatural = natural;
      presentedGame = avail; lastGamePresent = t;
    }
    lastPresent = t; next = avail;
    if (!mouse && next >= arrive.size()) break;
  }
  printf("%d %d\n", late, counted);
}
'''
cases = []
for hz, limit in ((144, 60), (120, 60), (60, 60), (60, 30), (120, 120)):
    for render in (4.0, 8.0, 12.0):
        for mouse in (1, 0):
            cases.append((hz, limit, render, mouse))
with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / "sim.cpp"; exe = Path(tmp) / "sim"
    src.write_text(sim)
    subprocess.run(["g++", "-std=c++17", "-O2", "-fsanitize=address,undefined", "-I", str(header.parent), str(src), "-o", str(exe)], check=True)
    def run(c, old): return tuple(map(int, subprocess.check_output([str(exe), str(c[0]), str(c[1]), str(c[2]), str(c[3]), str(old)], text=True).split()))
    ok = True; old_bad = 0
    for c in cases:
        o = run(c, 1); n = run(c, 0)
        po = 100.0 * o[0] / max(1, o[1]); pn = 100.0 * n[0] / max(1, n[1])
        print(f"hz={c[0]} limit={c[1]} render={c[2]}ms mouse={c[3]}: judder old {po:5.1f}%  new {pn:5.1f}%")
        if po > 1.0: old_bad += 1
        if pn > 1.0 or pn > po: ok = False
    print(f"old gate: {old_bad} cases with >1% late game frames; new gate: {'none' if ok else 'FAIL'}")
    assert old_bad > 0, "the old gate should show the repeated frames"
    assert ok
print("PASS fifo_gate")
