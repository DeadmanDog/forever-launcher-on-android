#!/usr/bin/env python3
"""Host test of the Wine DNS shim (app/src/main/cpp/gamenative_dns_v4mapped.c): builds it for this machine, preloads it into
probe.c and checks gethostname/gethostbyname/gethostbyname_r/getaddrinfo with and without GAMENATIVE_HOSTNAME.
Optional argument: another shim source (e.g. the previous one) to show what it fails."""
from pathlib import Path
import os, subprocess, sys, tempfile
root = Path(__file__).resolve().parents[2]
here = Path(__file__).resolve().parent
source = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app/src/main/cpp/gamenative_dns_v4mapped.c"
with tempfile.TemporaryDirectory(prefix="dns-shim-") as tmp:
    t = Path(tmp)
    subprocess.run(["gcc", "-shared", "-fPIC", "-O2", "-o", str(t / "shim.so"), str(source), "-ldl", "-lpthread"], check=True)
    subprocess.run(["gcc", "-O1", "-o", str(t / "probe"), str(here / "probe.c")], check=True)
    results = []
    for env in ({"GAMENATIVE_HOSTNAME": "DESKTOP-TEST123"}, {}):
        e = dict(os.environ, LD_PRELOAD=str(t / "shim.so")); e.pop("GAMENATIVE_HOSTNAME", None); e.update(env)
        r = subprocess.run([str(t / "probe")], env=e, capture_output=True, text=True, timeout=30)
        print(("with" if env else "without"), "GAMENATIVE_HOSTNAME:", r.stdout.strip(), r.stderr.strip())
        results.append(r.returncode)
    sys.exit(0 if results == [0, 0] else 1)
