#!/usr/bin/env python3
"""1.2.26 (FifoPresentGate::longestGameGapNs): run GateJitterSim against a FifoPresentGate.h directory. The gate's own share of repeated game frames is the rate with a moving
pointer minus the rate without one (jitter at the panel rate repeats frames in FIFO by itself, with or without the gate).
Fails when that share exceeds 1% in any case."""
import subprocess, sys, tempfile
from pathlib import Path
here = Path(__file__).resolve().parent
inc = (Path(sys.argv[1]) if len(sys.argv) > 1 else here.parents[2] / "app/src/main/cpp/winlator").resolve()
with tempfile.TemporaryDirectory(dir=here) as tmp:
    exe = Path(tmp) / "sim"
    subprocess.run(["g++", "-std=c++17", "-O2", "-fsanitize=address,undefined", "-I", str(inc), str(here / "GateJitterSim.cpp"), "-o", str(exe)], check=True)
    bad = []
    for hz, limit in ((144, 60), (120, 60), (60, 60), (60, 30), (120, 120)):
        for render in (6.0, 10.0):
            for jit in (0.3, 2.0, 3.0, 4.0):
                r = {}
                for mouse in (1, 0):
                    late, n = map(int, subprocess.check_output([str(exe), str(hz), str(limit), str(render), str(mouse), str(jit)], text=True).split())
                    r[mouse] = 100.0 * late / max(1, n)
                gate = r[1] - r[0]
                print(f"hz={hz:3d} limit={limit:3d} render={render:4.1f}ms jitter=+-{jit}ms: repeated game frames pointer moving {r[1]:5.2f}%  still {r[0]:5.2f}%  -> gate {gate:5.2f}%")
                if gate > 1.0: bad.append((hz, limit, render, jit, round(gate, 2)))
    print("cases over 1%:", bad if bad else "none")
    sys.exit(1 if bad else 0)
