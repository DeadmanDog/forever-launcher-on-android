// FIFO present gate (FifoPresentGate.h as included via -I) on a simulated display, the run_fifo_gate_regressions.py model
// (same loop, same judder definition) with one change: the game's render time varies frame to frame by +-J ms (uniform),
// instead of +-0.3 ms. The app limiter releases on vsync (VsyncPacingCore budget pattern), the game presents [render+-J] later.
#include "FifoPresentGate.h"
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <vector>
#include <random>
int main(int argc, char** argv) {
  const double hz = atof(argv[1]); const int limit = atoi(argv[2]); const double renderMs = atof(argv[3]);
  const int mouse = atoi(argv[4]); const double jitMs = atof(argv[5]);
  const int64_t R = (int64_t)(1e9 / hz), G = limit > 0 ? (int64_t)(1e9 / limit) : 0;
  std::mt19937 rng(7); std::uniform_int_distribution<int> jit(-(int)(jitMs * 1e6), (int)(jitMs * 1e6));
  std::vector<int64_t> arrive; double budget = 1.0, per = limit > 0 ? limit / hz : 1.0;
  for (int v = 1; v < (int)(hz * 60); v++) { budget = std::min(per >= 1 ? per : 1.0 + per, budget + per);
    if (budget >= 1.0 - 1e-6) { budget -= 1.0; arrive.push_back(v * R + (int64_t)(renderMs * 1e6) + jit(rng)); } }
  for (size_t i = 1; i < arrive.size(); i++) if (arrive[i] <= arrive[i-1]) arrive[i] = arrive[i-1] + 100000;   // presents stay in order
  int64_t t = 0, lastPresent = -1000000000, lastGamePresent = 0, lastDisplay = 0; size_t presentedGame = 0, next = 0;
  int late = 0, counted = 0; const int64_t end = (int64_t)(hz * 60 - 2) * R; int64_t prevShown = 0, prevNatural = 0;
  int64_t nextCursor = 0;
  while (t < end) {
    int64_t wake = next < arrive.size() ? arrive[next] : end;
    if (mouse) { while (nextCursor <= t) nextCursor += 2000000; wake = std::min(wake, nextCursor); }
    if (wake > t) t = wake;
    size_t avail = next; while (avail < arrive.size() && arrive[avail] <= t) avail++;
    bool newGame = avail > presentedGame;
    if (lastGamePresent && !newGame) {
      int64_t until = FifoPresentGate::holdUntilNs(lastGamePresent, R, G);
      if (t < until) { int64_t g = avail < arrive.size() ? arrive[avail] : end; t = std::min(until, g);
        while (avail < arrive.size() && arrive[avail] <= t) avail++; newGame = avail > presentedGame; }
    }
    int64_t spacing = FifoPresentGate::minSpacingNs(R);
    if (t - lastPresent < spacing) { t = lastPresent + spacing; while (avail < arrive.size() && arrive[avail] <= t) avail++; newGame = avail > presentedGame; }
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
