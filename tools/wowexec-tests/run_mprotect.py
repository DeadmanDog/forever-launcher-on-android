#!/usr/bin/env python3
"""Host tests for the exec shim's mprotect fallback (app/src/main/cpp/wowexec/wow_exec.c): a stand-in (fake_mm.c), preloaded after
the shim, denies PROT_EXEC like Android's execmod policy and can make each later step of the fallback fail. Whatever fails, the
pages must keep the protection they had (they used to be left read-only) and their bytes.

    python3 -B tools/wowexec-tests/run_mprotect.py [shim.c]
"""
import os, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
SHIM = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "../../app/src/main/cpp/wowexec/wow_exec.c")

def main():
    work = tempfile.mkdtemp(prefix="wowexec-mprotect-")
    cc = shutil.which("cc") or shutil.which("gcc")
    run = lambda *a: subprocess.run(a, check=True)
    run(cc, "-shared", "-fPIC", "-O1", "-Wall", "-Werror", "-o", f"{work}/libwowexec.so", SHIM, "-ldl")
    run(cc, "-shared", "-fPIC", "-O1", "-Wall", "-o", f"{work}/libfakemm.so", f"{HERE}/fake_mm.c", "-ldl")
    run(cc, "-O1", "-Wall", "-o", f"{work}/case", f"{HERE}/mprotect_case.c")
    failures = []
    def case(**flags):
        env = dict(os.environ, LD_PRELOAD=f"{work}/libwowexec.so {work}/libfakemm.so")
        env.update({k: "1" for k in flags})
        r = subprocess.run([f"{work}/case"], env=env, capture_output=True, text=True)
        rec = dict(part.split("=", 1) for line in r.stdout.splitlines() for part in line.split())
        return r.returncode, rec, r.stdout + r.stderr
    def check(name, cond, detail):
        print(("PASS " if cond else "FAIL ") + name + ("" if cond else f": {detail}"))
        if not cond: failures.append(name)

    rc, rec, out = case()
    check("without a denial mprotect works as usual", rc == 0 and rec.get("result") == "0" and rec.get("prot") == "r-x", out)
    rc, rec, out = case(WOW_TEST_DENY_EXEC=1)
    check("a denied exec request is served from an anonymous copy", rc == 0 and rec.get("result") == "0"
          and rec.get("prot") == "r-x" and rec.get("data") == "ok", out)
    rc, rec, out = case(WOW_TEST_DENY_EXEC=1, WOW_TEST_FAIL_FIXED=1)
    check("a failed remap reports EACCES and leaves the pages read-write with their bytes", rc == 0 and rec.get("result") == "-1"
          and rec.get("errno") == "13" and rec.get("prot") == "rw-" and rec.get("data") == "ok" and rec.get("write") == "ok", out)
    rc, rec, out = case(WOW_TEST_DENY_EXEC=1, WOW_TEST_FAIL_ALL_EXEC=1)
    check("a failed final exec request leaves the copied pages read-write with their bytes", rc == 0 and rec.get("result") == "-1"
          and rec.get("errno") == "13" and rec.get("prot") == "rw-" and rec.get("data") == "ok" and rec.get("write") == "ok", out)
    shutil.rmtree(work)
    print(f"{'ALL PASSED' if not failures else str(len(failures)) + ' FAILED'}")
    sys.exit(1 if failures else 0)

main()
