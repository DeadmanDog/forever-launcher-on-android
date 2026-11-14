#!/usr/bin/env python3
"""Run the production scanout code against the checking SurfaceControl stub of RenderScanoutLifetimeTest.cpp.in with the optional
entry points on (transaction completion, release fences, reparent): replaced buffers are handed back only after SurfaceFlinger let go
of them, teardown takes the layers out of the tree, cursor pixels/visibility/geometry are right. ASan+UBSan; cases 0 and 6 also TSan."""
from pathlib import Path
import subprocess, sys, tempfile
project = Path(__file__).resolve().parents[3]
tests = Path(__file__).resolve().parent
source = Path(sys.argv[1]) if len(sys.argv) > 1 else project / 'app/src/main/cpp/winlator/VulkanRendererScanout.cpp'
s = source.read_text()
production = s[s.index('#define SC_CREATE'):]
stub = (tests / 'RenderScanoutLifetimeTest.cpp.in').read_text()
stub = stub[:stub.index('/* PRODUCTION */')]
prelude = '''#include <set>
struct FakeStats { std::vector<void*> scs; int fenceFd; };
typedef void (*OnCompleteFn)(void*, void*);
static std::mutex cbMutex;
static std::map<void*, std::vector<std::pair<void*, OnCompleteFn>>> txCallbacks;
static std::vector<std::pair<void*, OnCompleteFn>> appliedCallbacks;
static std::set<void*> reparentedNull;
static std::map<void*, int> lastVisibility;
static int visibility(void* sc) { std::lock_guard<std::mutex> l(cbMutex); auto it = lastVisibility.find(sc); return it == lastVisibility.end() ? -1 : it->second; }
static void moveCallbacks(void* t) {
    std::lock_guard<std::mutex> l(cbMutex);
    auto it = txCallbacks.find(t);
    if (it == txCallbacks.end()) return;
    for (auto& c : it->second) appliedCallbacks.push_back(c);
    txCallbacks.erase(it);
}
'''
def swap(text, a, b):
    assert text.count(a) == 1, a
    return text.replace(a, b)
stub = swap(stub, '// ---- minimal Android surface', prelude + '// ---- minimal Android surface')
stub = swap(stub, 'useHandle(t, "apply"); ++applyCalls;', 'useHandle(t, "apply"); ++applyCalls; moveCallbacks(t);')
stub = swap(stub, 'static void api_st_setvis(void* t, void* sc, int8_t) { useHandle(t, "setVisibility transaction"); useHandle(sc, "setVisibility surface"); }',
    'static void api_st_setvis(void* t, void* sc, int8_t v) { useHandle(t, "setVisibility transaction"); useHandle(sc, "setVisibility surface"); std::lock_guard<std::mutex> l(cbMutex); lastVisibility[sc] = v; }')
stub = swap(stub, '    std::lock_guard<std::mutex> l(geoMutex); geoSeen.push_back(*d); (void)s;',
    '    if (s->left > s->right || s->top > s->bottom || d->left > d->right || d->top > d->bottom) { fprintf(stderr, "FAIL inverted rectangle (the platform aborts)\\n"); misuse++; }\n'
    '    std::lock_guard<std::mutex> l(geoMutex); geoSeen.push_back(*d);')
extra = '''
static void api_st_setoncomplete(void* t, void* ctx, OnCompleteFn fn) { useHandle(t, "setOnComplete"); std::lock_guard<std::mutex> l(cbMutex); txCallbacks[t].push_back({ctx, fn}); }
static void api_st_reparent(void* t, void* sc, void* parent) { useHandle(t, "reparent transaction"); useHandle(sc, "reparent surface"); if (!parent) { std::lock_guard<std::mutex> l(cbMutex); reparentedNull.insert(sc); } }
static void api_stats_controls(void* stats, void*** out, size_t* n) { auto* s = (FakeStats*)stats; auto** a = new void*[s->scs.size() + 1]; for (size_t i = 0; i < s->scs.size(); i++) a[i] = s->scs[i]; *out = a; *n = s->scs.size(); }
static void api_stats_release(void** a) { delete[] a; }
static int api_stats_prevfence(void* stats, void* sc) {
    auto* s = (FakeStats*)stats; bool listed = false; for (void* x : s->scs) if (x == sc) listed = true;
    if (!listed) { fprintf(stderr, "FAIL fence asked for a layer the stats do not list (the platform aborts)\\n"); exit(2); }
    return s->fenceFd >= 0 ? dup(s->fenceFd) : -1;
}
'''
stub = swap(stub, 'struct VulkanRendererContext {', extra + 'struct VulkanRendererContext {')
stub = swap(stub, '        fnSTSetZOrder = (void*)api_st_setzorder; fnSTSetVisibility = (void*)api_st_setvis; fnSTSetGeometry = (void*)api_st_setgeo;',
    '        fnSTSetZOrder = (void*)api_st_setzorder; fnSTSetVisibility = (void*)api_st_setvis; fnSTSetGeometry = (void*)api_st_setgeo;\n'
    '        fnSTSetOnComplete = (void*)api_st_setoncomplete; fnSTReparent = (void*)api_st_reparent; fnStatsGetControls = (void*)api_stats_controls;\n'
    '        fnStatsReleaseControls = (void*)api_stats_release; fnStatsGetPrevFence = (void*)api_stats_prevfence;')
inc = project / 'app/src/main/cpp/winlator'
with tempfile.TemporaryDirectory(prefix='wow-scanout-release-') as folder:
    cpp = Path(folder) / 'release.cpp'
    cpp.write_text(stub + production + '\n' + (tests / 'RenderScanoutReleaseTest.cpp.in').read_text())
    for name, flags, cases in [('asan', ['-fsanitize=address,undefined'], range(8)), ('tsan', ['-fsanitize=thread'], [0, 6])]:
        exe = Path(folder) / name
        subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-pthread', *flags, '-I' + str(inc), str(cpp), '-o', str(exe)], check=True)
        for i in cases:
            command = ['setarch', 'x86_64', '-R', str(exe), str(i)] if name == 'tsan' else [str(exe), str(i)]
            env = {'ASAN_OPTIONS': 'detect_leaks=0'} if i == 6 else None   # case 6 drops its completion contexts on purpose
            import os
            subprocess.run(command, check=True, timeout=120, env=dict(os.environ, **(env or {})))
        print('PASS scanout release under', name)
