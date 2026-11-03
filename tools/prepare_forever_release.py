#!/usr/bin/env python3
"""Prepare a WoW Forever RELEASE source tree from the mainline.

Since 2026-11-01 the mainline itself targets World of Warcraft: Forever (product wowf, Wow-ARM64.exe, _forever_); the Classic beta
is gone and only its install is upgraded in place. The development build stays forever.launcher.beta. The release is built with
-PwowRelease=true (forever.launcher, "Forever Launcher"; app.wowforever until 1.4.4) and has the best measured pacing as its default (phase-aligned vsync pacing +
the one-step FIFO latency drain). The archives are not copied.

    python3 -B tools/prepare_forever_release.py <target dir>

Then in <target dir>: build with `./gradlew --offline --no-build-cache -PwowRelease=true ...` as usual.
"""
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, pairs):
    text = path.read_text()
    for old, new in pairs:
        if text.count(old) != 1:
            raise SystemExit(f"{path}: expected one '{old[:70]}', found {text.count(old)}")
        text = text.replace(old, new)
    path.write_text(text)


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    target = Path(sys.argv[1]).expanduser().resolve()
    if target.exists():
        raise SystemExit(f"{target} exists; remove it first")
    subprocess.run(["rsync", "-a", "--exclude", "docs/apk-archive", "--exclude", "docs/runtime-archive", "--exclude", "docs/parked",
                    "--exclude", "/build", "--exclude", "app/build", "--exclude", ".gradle", "--exclude", "local.properties",
                    f"{ROOT}/", f"{target}/"], check=True)

    wow = target / "app/src/main/java/app/gamenative/ui/screen/wow"
    dialogs = target / "app/src/main/java/app/gamenative/ui/component/dialog"
    replace_once(wow / "WowRuntimeTuning.kt", [('        else if (key == "present" && presentIsDefault(context)) "fifo"\n',
        '        else if (key == "present" && presentIsDefault(context)) "fifo"\n'
        '        // WoW Forever release: the best measured setup is the default until the player picks another (phase-aligned vsync\n'
        '        // pacing + the one-step FIFO latency drain, about 43 ms and smooth on the RedMagic 11 at 60 Hz).\n'
        '        else if ((key == "pacing" || key == "fifolatency") && !context.getSharedPreferences(WowLaunchPolicy.PREFS, 0).contains(key))\n'
        '            if (key == "pacing") "phase" else "low"\n')])
    replace_once(dialogs / "WowLaunchExperimentsSection.kt", [
        ('listOf("" to tx("App, vsync-locked (recommended)", "App, vsync-hez kötött (ajánlott)"),',
         'listOf("" to tx("App, vsync-locked", "App, vsync-hez kötött"),'),
        ('"phase" to tx("App, vsync + phase (experimental)", "App, vsync + időzítés-igazítás (kísérleti)"),',
         '"phase" to tx("App, vsync + phase (default)", "App, vsync + időzítés-igazítás (alapértelmezett)"),'),
        ('listOf("" to tx("Smooth (default)", "Sima (alapértelmezett)"), "low" to tx("Lower (experimental)", "Alacsonyabb (kísérleti)"),',
         'listOf("" to tx("Smooth", "Sima"), "low" to tx("Lower (default)", "Alacsonyabb (alapértelmezett)"),'),
        ("App, vsync-locked (recommended): the app releases", "App, vsync-locked: the app releases"),
        ("App, vsync + phase (experimental): like vsync-locked", "App, vsync + phase (default): like vsync-locked"),
        ("App, vsync-hez kötött (ajánlott): az app", "App, vsync-hez kötött: az app"),
        ("App, vsync + időzítés-igazítás (kísérleti): mint a vsync-hez kötött", "App, vsync + időzítés-igazítás (alapértelmezett): mint a vsync-hez kötött"),
        ("Only with FIFO. Smooth (default): frames", "Only with FIFO. Smooth: frames"),
        ("Lower (experimental): after a hitch", "Lower (default): after a hitch"),
        ("Csak FIFO-val. Sima (alapértelmezett): a kockák", "Csak FIFO-val. Sima: a kockák"),
        ("Alacsonyabb (kísérleti): akadás után", "Alacsonyabb (alapértelmezett): akadás után")])
    test = target / "app/src/test/java/app/gamenative/ui/screen/wow/WowRuntimeTuningTest.kt"
    replace_once(test, [("        assertFalse(WowRuntimeTuning.fifoLowLatency(context)) // the smooth FIFO queue unless the player lowers it\n"
                         "        assertEquals(0, WowRuntimeTuning.fifoDrainMode(context))\n",
                         "        assertEquals(1, WowRuntimeTuning.fifoDrainMode(context)) // release: the one-step drain by default\n"
                         "        assertTrue(WowRuntimeTuning.phaseAlign(context))           // release: phase-aligned pacing by default\n")])
    print(f"release tree ready: {target}")


if __name__ == "__main__":
    main()
