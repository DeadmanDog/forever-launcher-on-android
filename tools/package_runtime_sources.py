#!/usr/bin/env python3
"""Pack the corresponding source of the runtime components bundled in the APK, for a release.

    python3 -B tools/package_runtime_sources.py <new output dir>

Each bundled component that we build or modify gets one .tar.xz with its source tree as it was built, our build scripts and
patches, and a SOURCE-README.md that names the upstream base and what we changed. Build outputs, logs and caches are left out.
The work trees live outside this repository (see ~/README-PROJEKTEK.txt); the per-build archives under docs/runtime-archive hold
the packaging scripts and patches. The output goes into the runtime-<version> release, apart from the APK release.
"""
from pathlib import Path
import hashlib
import shutil
import subprocess
import sys

HOME = Path.home()
REPO = Path(__file__).resolve().parents[1]
ARCHIVE = REPO / "docs/runtime-archive"
SKIP = {".git", "autom4te.cache", "target", "__pycache__", "build-turnrip", "build-r10", "release", "releases"}
SKIP_SUFFIX = {".o", ".obj", ".log", ".pyc"}


def copy_tree(src: Path, dst: Path, skip=()):
    def ignore(directory, names):
        rel = Path(directory).relative_to(src)
        return [n for n in names if n in SKIP or Path(n).suffix in SKIP_SUFFIX or (rel / n).as_posix() in skip]
    shutil.copytree(src, dst, ignore=ignore, symlinks=True)


def copy_files(paths, dst: Path):
    dst.mkdir(parents=True, exist_ok=True)
    for p in paths:
        if p.is_dir():
            copy_tree(p, dst / p.name)
        else:
            shutil.copy2(p, dst / p.name)


def proton(stage: Path):
    work = HOME / "proton-r24-work/work"
    copy_tree(work / "wine", stage / "wine", skip={"android/wayland-deps"})
    # The shipped r30/r31 ntdll.so is the r24 tree with the WINE_REQ_STATS files of r30 on top.
    for f in (ARCHIVE / "20261024-proton-r30-reqstats-spin/ntdll-src-r30").iterdir():
        shutil.copy2(f, stage / "wine/dlls/ntdll/unix" / f.name)
    copy_tree(HOME / "proton-r30-work/ntsync", stage / "ntsync-android")
    copy_files([work / n for n in ("rebuild_runtime.py", "android-aarch64.ini", "native.ini", "aarch64-windows.txt",
                                   "backend_selection.c", "prefix_test_bridge.c", "test_backend_selection.py",
                                   "test_runtime_layout.py", "test_sync.py", "test_teb_dispatcher.py")]
               + [HOME / "proton-r24-work/BUILD_RUNTIME.md"], stage / "build")
    r26, r30, r31 = (ARCHIVE / d for d in ("20261022-proton-r26-wait-ref-clean-trim", "20261024-proton-r30-reqstats-spin",
                                            "20261025-proton-r31-dx-trim"))
    copy_files([r26 / "wine-mf-no-evr.patch", r26 / "reg_trim.py", r26 / "registry-trim-r26.json", r26 / "dropped-r26.txt",
                r26 / "package_r26.py", r30 / "ntdll-reqstats-r30.patch", r30 / "ntsync-core-r30-vs-r29.patch",
                r30 / "package_r30.py", r31 / "package_r31.py", r31 / "dropped-r31.txt"], stage / "wow-forever-changes")
    return """# Proton 11 ARM64EC r31 (WoW Forever) — corresponding source

Bundled as `proton-11.0-90639.31-arm64ec-r31.wcp`. License: LGPL-2.1-or-later (Wine), LGPL-3.0 (`ntsync-android`).

## Base
The community Proton 11 ARM64EC runtime of the WoW Forever RP6 bundle, built from
[The412Banner/proton-wine](https://github.com/The412Banner/proton-wine/tree/e5fa703ed7f7329e20d7ede481ab185cf8b1a8b2)
with an ARM64 copied-syscall fix and an NLS fallback allocation patch, for Android (bionic).

## What we build and change
- `wine/`: the Wine tree our Unix side (`ntdll.so`, `wineserver`) is built from, with all our changes applied: CPU identity
  (`WINE_CPU_NAME`, `WINE_CPU_VENDOR`, `WINE_CPU_EFFICIENCY`, `WINE_CPU_PEAK_MHZ`), the ntsync fast path backed by
  `ntsync-android`, and the `WINE_REQ_STATS` wineserver request statistics.
- `ntsync-android/`: userspace replacement of the Linux `ntsync` driver for Android (Rust; `vendor/` holds its crates).
- `wow-forever-changes/`:
  - `wine-mf-no-evr.patch`: `mf.dll` rebuilt without the `evr` delay import;
  - `dropped-r26.txt`, `dropped-r31.txt`: Windows modules we removed because WoW never loads them;
  - `reg_trim.py`, `registry-trim-r26.json`: the registry entries of those modules, removed;
  - `ntdll-reqstats-r30.patch`, `ntsync-core-r30-vs-r29.patch`: the last ntdll and ntsync changes as diffs;
  - `package_r*.py`: how the WCP is assembled from the previous one.
- `build/`: the Android cross build (`rebuild_runtime.py --source <dir> --ndk <Android NDK> --output <new dir>`), the Meson and
  cross files, and the tests. `BUILD_RUNTIME.md` lists the toolchain versions.

All other Windows modules of the WCP are the unmodified binaries of the base runtime.
"""


def vkd3d(stage: Path):
    w = HOME / "vkd3d-r10-work"
    copy_tree(w / "src/docs/dx12/vkd3d-proton", stage / "vkd3d-proton")
    copy_files([w / "cross.ini", w / "package_r10.py", w / "tests"], stage / "build")
    return """# VKD3D-Proton r10 (WoW Forever build) — corresponding source

Bundled in the APK as the VKD3D-Proton r10 package (Windows ARM64 `d3d12.dll`, `d3d12core.dll`). License: LGPL-2.1-or-later.

## Base
[HansKristian-Work/vkd3d-proton](https://github.com/HansKristian-Work/vkd3d-proton) 3.0.1 for Windows ARM64.

## What we change
`vkd3d-proton/` is the tree as built, with every change in place. In order: one fallback pipeline compile per PSO (r3),
fence/query/out-of-memory fixes (r4), swapchain cleanup (r5), fallback variants compiled in parallel (r6), disk-cache merge
recovery (r7), no busy retry when the worker queue cannot grow (r8), the upstream WoW MSAA workaround also for the ARM64 client
names, no CPU fence waits on a shared Turnip queue and fence signal order (r9), depth/stencil barrier fusion, disjoint copy
rectangles and the sparse-fallback leak (r10, upstream 461678be53, 382fc3b64f, 730e228796, 582a1175d3).

`build/`: `cross.ini` (LLVM-MinGW, Windows ARM64), `package_r10.py` (WCP assembly) and the regression tests.
"""


def turnrip(stage: Path):
    w = HOME / "turnrip-1117-work"
    copy_tree(w / "work/mesa", stage / "mesa")
    copy_files([w / "work/android-aarch64.ini", w / "work/native.ini", w / "tests"], stage / "build")
    return """# TurnRIP 1.1.20 (Mesa Turnip for Adreno 8xx, WoW Forever) — source

Bundled as `TurnRIP-A840-Performance-v1.1.20.zip`, the driver of the APK. License: MIT (Mesa).

## Base
Mesa Turnip for Adreno 8xx (`turnip/gen8`) as built by [arusiasotto](https://github.com/arusiasotto/wow-forever-a840) from
[whitebelyash/mesa-unified](https://github.com/whitebelyash/mesa-unified).

## What we change
`mesa/` is the tree as built: KGSL GPU power control (`TU_KGSL_PWR_MAX`, `TU_KGSL_GPU_FLOOR`), adaptive wait boost, an ADPF
session for frame timing, frame tagging and statistics (`TU_STATS_FILE`), the A8xx community fixes (LRZ reset, cube coordinates,
bindless invalidation, KGSL IB alias, memory budget) and the fixes of the later rounds.

`build/`: the Android cross file (NDK r29), `native.ini` and the tests; `tests/ninja_turnrip.py` builds it.
"""


def pack(out: Path, name: str, fill):
    stage = out / "stage" / name
    stage.mkdir(parents=True)
    readme = fill(stage)
    (stage / "SOURCE-README.md").write_text(readme)
    target = out / f"{name}.tar.xz"
    subprocess.run(["tar", "--sort=name", "--owner=0", "--group=0", "--numeric-owner", "-I", "xz -6 -T4",
                    "-cf", target, "-C", stage.parent, name], check=True)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print(f"{target.name} {target.stat().st_size} {digest}", flush=True)
    shutil.rmtree(stage)
    return f"{digest}  {target.name}\n"


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    out = Path(sys.argv[1]).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=False)
    # DXVK Vegas (zlib) is left out on purpose: its base is a private community fork, and zlib does not ask for the source.
    sums = [
        pack(out, "proton-11.0-arm64ec-r31-source", proton),
        pack(out, "vkd3d-proton-arm64-r10-source", vkd3d),
        pack(out, "turnrip-a840-1.1.20-source", turnrip),
    ]
    (out / "stage").rmdir()
    (out / "SHA256SUMS-sources.txt").write_text("".join(sums))


if __name__ == "__main__":
    main()
