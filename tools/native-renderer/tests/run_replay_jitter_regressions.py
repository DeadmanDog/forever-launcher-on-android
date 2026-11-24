#!/usr/bin/env python3
"""1.2.26: drain skip replays with a wobbling game frame time (FifoPresentGate::drainHoldNs uses the limiter's longest gap)."""
import subprocess, sys, tempfile
from pathlib import Path
here = Path(__file__).resolve().parent; inc = (Path(sys.argv[1]) if len(sys.argv) > 1 else here.parents[2] / "app/src/main/cpp/winlator").resolve(); ok = True
with tempfile.TemporaryDirectory(dir=here) as tmp:
    exe = Path(tmp) / "r"
    subprocess.run(["g++", "-std=c++17", "-O1", "-fsanitize=address,undefined", "-I", str(inc), str(here / "ReplayJitterSim.cpp"), "-o", str(exe)], check=True)
    for hz, limit in ((144, 60), (120, 60), (60, 60)):
        for j in (1.0, 2.0, 3.0):
            f, n, h = subprocess.check_output([str(exe), str(hz), str(limit), str(j)], text=True).split(); f, n = int(f), int(n)
            print(f"{hz} Hz / {limit} FPS render 8+-{j} ms: hold {h} ms, skips that would replay {n}/{f} ({100*n/f:.1f}%)")
            ok &= n <= f // 100
print("PASS" if ok else "FAIL"); sys.exit(0 if ok else 1)
