// 2026-11-12 review: the hand-back poll aims at the measured frame finish instead of waking at 3, 6, 12 ... ms.
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "IdlePollPacer.h"
static void check(bool b, const char* m) { if (!b) { fprintf(stderr, "FAIL %s\n", m); exit(2); } }
constexpr int64_t ms = IdlePollPacer::kMs;

struct Result { double polls, releaseMs; };
// One frame: the fences finish [finish] ns after the loop starts waiting; count the looks and when the buffer goes back.
static Result frameNew(IdlePollPacer& p, int64_t finish) {
    p.woken();
    int64_t t = 0; int polls = 0;
    while (true) { t += p.nextWaitNs(); ++polls; bool done = t >= finish; p.polled(done); if (done) break; }
    return {double(polls), t / double(ms)};
}
static Result frameOld(int64_t finish) {
    int64_t t = 0, step = 3 * ms; int polls = 0;
    while (true) { t += step; ++polls; if (t >= finish) break; step = std::min(step * 2, 50 * ms); }
    return {double(polls), t / double(ms)};
}
static void compare(const std::vector<int64_t>& finishes, const char* name) {
    IdlePollPacer p; double np = 0, nr = 0, op = 0, orr = 0;
    for (int64_t f : finishes) { auto n = frameNew(p, f); auto o = frameOld(f); np += n.polls; nr += n.releaseMs; op += o.polls; orr += o.releaseMs; }
    np /= finishes.size(); nr /= finishes.size(); op /= finishes.size(); orr /= finishes.size();
    printf("%-22s new %.2f looks/frame, back after %.2f ms | old %.2f looks, %.2f ms\n", name, np, nr, op, orr);
    // At most a tenth of a look more per frame (only for very fast frames, where the buffer then goes back over 1 ms sooner).
    check(np <= op + 0.1, name); check(nr <= orr + 0.01, name);
}
int main() {
    std::vector<int64_t> steady(600, 5 * ms), fast(600, ms + ms / 2), slow(600, 14 * ms), mixed;
    for (int i = 0; i < 600; ++i) mixed.push_back((4 + (i * 7919) % 9) * ms);   // 4..12 ms
    compare(steady, "finish 5 ms");
    compare(fast, "finish 1.5 ms");
    compare(slow, "finish 14 ms");
    compare(mixed, "finish 4..12 ms");
    // Steady 5 ms: the aim settles near the finish and the buffer goes back within ~1.5 ms of it, not at 9 ms.
    { IdlePollPacer p; Result r{}; for (int i = 0; i < 200; ++i) r = frameNew(p, 5 * ms);
      check(p.aimNs() >= 4 * ms && p.aimNs() <= 6 * ms, "aim settles near the finish");
      check(r.releaseMs <= 6.5, "buffer goes back soon after the finish"); }
    // Bounds and the give-up clock.
    { IdlePollPacer p; p.begin(); for (int i = 0; i < 1000; ++i) p.polled(true);
      check(p.aimNs() >= IdlePollPacer::kMinAim, "aim never below 1 ms (no busy loop)"); }
    { IdlePollPacer p; p.begin(); int n = 0; while (!p.givenUp() && n < 10000) { p.polled(false); ++n; }
      check(p.givenUp() && n < 200, "a frame that never finishes stops the looking after ~3.5 s");
      check(p.nextWaitNs() <= IdlePollPacer::kMaxStep, "steps stay at most 50 ms");
      p.woken(); check(!p.givenUp(), "a real wake-up starts the clock again"); }
    { IdlePollPacer p; for (int i = 0; i < 50; ++i) frameNew(p, 60 * ms);
      check(p.aimNs() <= IdlePollPacer::kMaxAim, "aim at most 20 ms"); }
    puts("PASS idle pace");
}
