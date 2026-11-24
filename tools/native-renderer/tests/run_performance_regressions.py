#!/usr/bin/env python3
"""Additive 2026-11-19 regression suite; run alongside run_all.sh, leaving its existing tests intact."""
from pathlib import Path
import argparse
import os
import subprocess

p = argparse.ArgumentParser()
p.add_argument("--out", type=Path, required=True)
a = p.parse_args()
a.out = a.out.resolve()
a.out.mkdir(parents=True, exist_ok=False)
tests = Path(__file__).resolve().parent
root = tests.parents[2]
env = dict(os.environ, ASAN_OPTIONS="detect_leaks=0")
for label, flags in (("asan", ["-no-pie", "-fsanitize=address,undefined"]), ("tsan", ["-fsanitize=thread"])):
    exe = a.out / ("waiter-" + label)
    subprocess.run(["g++", "-std=c++17", "-O1", "-g", "-pthread", *flags,
                    "-I" + str(root / "app/src/main/cpp/winlator"),
                    str(tests / "ScanoutWaiterProgressTest.cpp"), "-o", str(exe)], check=True)
    for case in range(2):
        command = [str(exe), str(case)]
        if label == "tsan":
            command = ["setarch", "x86_64", "-R", *command]
        subprocess.run(command, check=True, env=env, timeout=30)
    print("PASS waiter " + label, flush=True)
subprocess.run(["python3", "-B", str(tests / "run_scanout_cursor_work_regressions.py"),
                "--out", str(a.out / "cursor")], check=True, env=env)
subprocess.run(["python3", "-B", str(tests / "run_cpu_copy_work_regressions.py"),
                "--out", str(a.out / "copy")], check=True, env=env)
print("ALL performance regressions PASS", flush=True)
