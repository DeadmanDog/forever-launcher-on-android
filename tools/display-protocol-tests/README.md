# Display/protocol regression checks

Run with the persistent JDK environment:

```sh
source /home/fsos-x/.local/share/wow-build-tools/env.sh
python3 -B tools/display-protocol-tests/run.py . /tmp/new-empty-display-test-directory
```

The runner compiles complete production Java classes for Texture, VertexAttribute,
ShaderMaterial, RenderTarget, EffectComposer, effects, ASurfaceRenderer, XClient,
MITSHMExtension and their pure dependencies. Only Android, JNI, GL and adjacent
manager/socket/data boundaries are replaced with deterministic test doubles.
Window lifecycle checks compile the complete production destroyWindow,
removeAllSubwindowsAndWindow, unmapWindow and notification methods with model
state. Hashes of all production inputs are recorded. No production method is
replaced with a hand-written implementation of its behavior.

There are 41 assertion cases and one 1000-frame counted GL work experiment.
Multiple cases cover variants of one defect; the issue count is 17, not 42.
The saved original source fails 29 checks covering those 17 defects, while 13
checks/controls succeed. The fixed source passes all 42. This tests protocol bytes,
API state, cleanup/notification order and counted GL calls, not GPU timing/FPS.
Native rendering, actual SurfaceControl retirement, driver fences and game-side
usage still require device validation. GL/SHM findings are conditional paths;
they are not presented as continuous overhead on the DXVK/AHB main path.

`prepare_candidate.py` was used with the unfixed 0.1.31 baseline and creates a new scratch candidate and refuses to overwrite
one. `integrate_candidate.py` applies only explicitly named, validated clusters;
it checks source/candidate hashes and passing results before each write. These
are audit support scripts, not app/runtime components or automatic launch steps.
