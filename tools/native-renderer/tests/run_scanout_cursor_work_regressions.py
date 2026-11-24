#!/usr/bin/env python3
"""Exercise actual scanout source with the existing SurfaceControl checker.
The existing test files remain unchanged. Usage: script --out <new directory>
[--source <VulkanRendererScanout.cpp>] [--baseline].
"""
from pathlib import Path
import argparse
import json
import os
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--out", type=Path, required=True)
parser.add_argument("--source", type=Path)
parser.add_argument("--baseline", action="store_true")
args = parser.parse_args()
root = Path(__file__).resolve().parents[3]
tests = Path(__file__).resolve().parent
source = args.source or root / "app/src/main/cpp/winlator/VulkanRendererScanout.cpp"
production = source.read_text()
production = production[production.index("#define SC_CREATE"):]
stub = (tests / "RenderScanoutLifetimeTest.cpp.in").read_text().split("/* PRODUCTION */")[0]
main = r"""
static void check(bool ok, const char* message) {
    if (!ok) { fprintf(stderr, "FAIL %s\n", message); exit(2); }
}
int main(int argc, char** argv) {
    int which = atoi(argv[1]);
    VulkanRendererContext c;
    ANativeWindow window{};
    c.window = &window;
    c.initScanout();
    uint32_t pixels[16] = {1};
    c.scanoutSetCursorImage(pixels, 4, 4, 4);
    c.scanoutSetCursorPos(100, 100, 1, 1);
    c.applyScanoutBuffer();
    if (which == 0) {
        const int before = applyCalls.load();
        int wakes = 0;
        for (int i = 0; i < 100; ++i) {
            c.cursorMoved = false;
            c.scanoutSetCursorPos(100, 100, 1, 1);
            wakes += c.cursorMoved.load();
            c.applyScanoutBuffer();
        }
        printf("100 identical pointer positions: wakeups=%d transactions=%d\n", wakes, applyCalls.load() - before);
        check(wakes == 0 && applyCalls.load() == before, "identical pointer feedback caused redundant SurfaceControl work");
        c.scanoutSetCursorPos(101, 100, 1, 1);
        c.applyScanoutBuffer();
        check(applyCalls.load() == before + 1, "real pointer movement still applies");
        // A changed cursor image must update geometry even at the same position.
        c.scanoutSetCursorImage(pixels, 2, 2, 2);
        c.scanoutSetCursorPos(101, 100, 1, 1);
        c.applyScanoutBuffer();
        check(geoSeen.back().right - geoSeen.back().left == 2, "same-position image change updates cursor geometry");
    }
    if (which == 1) {
        const int before = applyCalls.load();
        c.scanoutSetDst(0, 0, 2560, 1440);
        c.applyScanoutBuffer();
        const auto rect = geoSeen.back();
        printf("stationary cursor after 2x viewport: x=%d width=%d (expected 198 / 8)\n",
               rect.left, rect.right - rect.left);
        check(rect.left == 198 && rect.right - rect.left == 8, "viewport changed but stationary cursor stayed at old scale");
        check(applyCalls.load() == before + 1, "geometry refresh applies once");
        c.cursorMoved = false;
        c.scanoutSetDst(0, 0, 2560, 1440);
        c.applyScanoutBuffer();
        check(!c.cursorMoved && applyCalls.load() == before + 1, "identical viewport does not redraw");
    }
    c.destroyScanout();
    check(misuse.load() == 0, "surface handles remain valid");
    puts("PASS scanout cursor work");
}
"""
args.out.mkdir(parents=True, exist_ok=False)
cpp = args.out / "scanout.cpp"
cpp.write_text(stub + production + main)
exe = args.out / "scanout"
subprocess.run(["g++", "-std=c++17", "-O1", "-g", "-pthread", "-no-pie",
                "-fsanitize=address,undefined", "-I" + str(root / "app/src/main/cpp/winlator"),
                str(cpp), "-o", str(exe)], check=True)
results = []
for case in range(2):
    result = subprocess.run([str(exe), str(case)], text=True, capture_output=True, timeout=20,
                            env=dict(os.environ, ASAN_OPTIONS="detect_leaks=0"))
    results.append(dict(case=case, exit=result.returncode, output=result.stdout + result.stderr))
report = json.dumps(results, indent=2)
(args.out / "result.json").write_text(report + "\n")
print(report)
if not args.baseline:
    assert all(r["exit"] == 0 for r in results), results
