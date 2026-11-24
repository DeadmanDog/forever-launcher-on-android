#!/usr/bin/env python3
"""1.2.23: how long a FIFO drain skip waits for the next game frame (FifoPresentGate::drainHoldNs), compiled from the header.

A skip holds the game's newest content back and waits for the next game frame; if none comes in time the skipped content goes
out after all (a replay: nothing was taken out of the queue, and the frame came late). The app limiter releases the game's frames
on vsync: 60 FPS on 144 Hz is 2,3,2,2,3 refreshes (13.9 / 20.8 ms), on 120 Hz every 2nd refresh (16.7 ms). Game frames reach the
renderer at those releases plus a constant render time with +-1 ms of jitter. Old = two refreshes (1.2.22), new = drainHoldNs.
Every game frame is tried as a skip; a replay is a next frame later than the hold.
"""
import subprocess, sys, tempfile
from pathlib import Path
here = Path(__file__).resolve()
inc = here.parents[3] / "app/src/main/cpp/winlator"
assert "FifoPresentGate::drainHoldNs(" in (inc / "VulkanRendererContext.cpp").read_text(), "production skip no longer uses drainHoldNs"
code = r'''
#include "FifoPresentGate.h"
#include <cstdio>
#include <cstdlib>
#include <random>
int main(int argc, char** argv) {
  const int hz = atoi(argv[1]), limit = atoi(argv[2]);
  const int64_t R = 1000000000LL / hz, G = limit > 0 ? 1000000000LL / limit : 0;
  const int64_t oldHold = 2 * R, newHold = FifoPresentGate::drainHoldNs(R, G);
  std::mt19937 rng(3); std::uniform_int_distribution<int64_t> jit(-1000000, 1000000);
  int64_t prev = -1; double budget = 0; int frames = 0, oldReplays = 0, newReplays = 0;
  const double per = limit > 0 ? (double)limit / hz : 1.0;
  for (int64_t v = 0; v < (int64_t)hz * 600; ++v) {          // ten minutes of vsyncs
    budget += per; if (budget < 1.0 - 1e-9) continue; budget -= 1.0;
    const int64_t arrive = v * R + 4000000 + jit(rng);
    if (prev >= 0) { ++frames; const int64_t gap = arrive - prev; if (gap > oldHold) ++oldReplays; if (gap > newHold) ++newReplays; }
    prev = arrive;
  }
  std::printf("%d %d %d %.1f %.1f\n", frames, oldReplays, newReplays, oldHold / 1e6, newHold / 1e6);
  return 0;
}
'''
with tempfile.TemporaryDirectory() as tmp:
    src = Path(tmp) / "replay.cpp"; exe = Path(tmp) / "replay"
    src.write_text(code)
    subprocess.run(["g++", "-std=c++17", "-O1", "-fsanitize=address,undefined", "-I", str(inc), str(src), "-o", str(exe)], check=True)
    ok = True
    for hz, limit in ((144, 60), (120, 60), (60, 60), (60, 30), (144, 72), (120, 120)):
        f, o, n, oh, nh = subprocess.check_output([str(exe), str(hz), str(limit)], text=True).split()
        f, o, n = int(f), int(o), int(n)
        print(f"{hz} Hz / {limit} FPS: hold old {oh} ms new {nh} ms | skips that would replay: old {o}/{f} ({100*o/f:.0f}%) new {n}/{f}")
        ok &= n == 0
        if (hz, limit) in ((144, 60), (120, 60), (60, 30)): ok &= o > f // 4
        if hz == limit: ok &= float(oh) == float(nh)
print("PASS drain_replay" if ok else "FAIL drain_replay")
sys.exit(0 if ok else 1)
