// FIFO drain skip hold (FifoPresentGate::drainHoldNs via -I), the run_drain_replay_regressions.py model with the game's render time
// varying by +-J ms per frame. A replay = the next game frame came after the hold ended (the skipped content went out after all).
#include "FifoPresentGate.h"
#include <cstdio>
#include <cstdlib>
#include <random>
int main(int argc, char** argv) {
  const int hz = atoi(argv[1]), limit = atoi(argv[2]); const double J = atof(argv[3]);
  const int64_t R = 1000000000LL / hz, G = limit > 0 ? 1000000000LL / limit : 0;
  const int64_t hold = FifoPresentGate::drainHoldNs(R, G);
  std::mt19937 rng(3); std::uniform_int_distribution<int64_t> jit(-(int64_t)(J * 1e6), (int64_t)(J * 1e6));
  int64_t prev = -1; double budget = 0; int frames = 0, replays = 0;
  const double per = limit > 0 ? (double)limit / hz : 1.0;
  for (int64_t v = 0; v < (int64_t)hz * 600; ++v) {
    budget += per; if (budget < 1.0 - 1e-9) continue; budget -= 1.0;
    const int64_t arrive = v * R + 8000000 + jit(rng);
    if (prev >= 0) { ++frames; if (arrive - prev > hold) ++replays; }
    prev = arrive;
  }
  std::printf("%d %d %.1f\n", frames, replays, hold / 1e6);
}
