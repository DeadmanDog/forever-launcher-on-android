#!/usr/bin/env python3
"""Host tests for the exec shim (app/src/main/cpp/wowexec/wow_exec.c): the shim is built for the host and a stand-in plays the
system linker. Each case runs with the shim and without it; without it the denied exec must fail (mutation check).

    python3 -B tools/wowexec-tests/run.py
"""
import os, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
SHIM = os.path.join(HERE, "../../app/src/main/cpp/wowexec/wow_exec.c")

def build(work):
    cc = shutil.which("cc") or shutil.which("gcc")
    run = lambda *a: subprocess.run(a, check=True)
    run(cc, "-shared", "-fPIC", "-O1", "-Wall", "-Werror", "-o", f"{work}/libwowexec.so", SHIM, "-ldl")
    run(cc, "-O1", "-o", f"{work}/fakelinker", f"{HERE}/fakelinker.c")
    run(cc, "-O1", "-o", f"{work}/caller", f"{HERE}/caller.c")
    os.makedirs(f"{work}/data")
    run(cc, "-O1", "-o", f"{work}/data/prog", f"{HERE}/prog.c")
    static_ok = subprocess.run([cc, "-O1", "-static", "-o", f"{work}/data/static", f"{HERE}/prog.c"]).returncode == 0
    with open(f"{work}/data/script.sh", "w") as f: f.write("#!/bin/sh\necho script_ran=$1\n")
    for name in ("prog", "static", "script.sh"):
        p = f"{work}/data/{name}"
        if os.path.exists(p): os.chmod(p, 0o644)   # not executable: execve() is denied, like the app's data directory on Android
    return static_ok

def case(work, mode, target, shim=True, extra=None):
    out = f"{work}/out.txt"
    if os.path.exists(out): os.remove(out)
    env = dict(os.environ, WOW_EXEC_LINKER=f"{work}/fakelinker", WOW_TEST_OUT=out)
    env.pop("WOW_EXEC_SELF", None)
    if shim: env["LD_PRELOAD"] = f"{work}/libwowexec.so"
    if extra: env.update(extra)
    r = subprocess.run([f"{work}/caller", mode, target], env=env, capture_output=True, text=True)
    record = dict(l.split("=", 1) for l in open(out).read().splitlines()) if os.path.exists(out) else {}
    return r.stdout + r.stderr, record

def main():
    work = tempfile.mkdtemp(prefix="wowexec-")
    static_ok = build(work)
    prog, failures = f"{work}/data/prog", []
    def check(name, cond, detail):
        print(("PASS " if cond else "FAIL ") + name + ("" if cond else f": {detail}"))
        if not cond: failures.append(name)

    out, rec = case(work, "execve", prog)
    check("denied execve runs through the linker", rec.get("arg0") == f"{work}/fakelinker" and rec.get("arg1") == prog
          and rec.get("arg2") == "first" and rec.get("arg3") == "second", (out, rec))
    check("the child gets its program in WOW_EXEC_SELF", rec.get("self_env") == prog, rec)
    check("/proc/self/exe answers the program, not the linker", rec.get("proc_self_exe") == prog, rec)
    out, rec = case(work, "execve", prog, shim=False)
    check("mutation: without the shim the exec stays denied", "execve_errno=13" in out and not rec, (out, rec))

    out, rec = case(work, "spawn", prog)
    check("posix_spawn of a data-directory program goes through the linker", rec.get("arg1") == prog and "spawn_exit=0" in out, (out, rec))

    if static_ok:
        out, rec = case(work, "execve", f"{work}/data/static")
        check("a static program keeps the denial (it cannot run under the linker)", "execve_errno=13" in out and not rec, (out, rec))
    out, _ = case(work, "execve", f"{work}/data/script.sh")
    check("a script runs through its interpreter", "script_ran=first" in out, out)

    # The first Wine process is started by the app as "linker64 <wine> ...": no WOW_EXEC_SELF, the program comes from /proc/self/cmdline.
    out_file = f"{work}/out.txt"
    if os.path.exists(out_file): os.remove(out_file)
    env = dict(os.environ, WOW_EXEC_LINKER=f"{work}/fakelinker", WOW_TEST_OUT=out_file, LD_PRELOAD=f"{work}/libwowexec.so")
    env.pop("WOW_EXEC_SELF", None)
    subprocess.run([f"{work}/fakelinker", prog, "x"], env=env, check=True)
    rec = dict(l.split("=", 1) for l in open(out_file).read().splitlines())
    check("without WOW_EXEC_SELF /proc/self/exe comes from the command line", rec.get("proc_self_exe") == prog, rec)

    shutil.rmtree(work)
    print(f"{'ALL PASSED' if not failures else str(len(failures)) + ' FAILED'}")
    sys.exit(1 if failures else 0)

main()
