# Frame resource ownership regressions

Run after sourcing the project's persistent build-tool environment:

```sh
python3 -B tools/frame-resource-tests/run.py . /tmp/wow-frame-resource-tests
```

The script copies full production Java classes and substitutes only JNI library
loading/native declarations, Android platform objects and surrounding XServer
boundary objects. The original resource decisions, DRI3 parsers, constructors,
cleanup and dirty-flag methods execute as written. The artifact records source
SHA256 hashes. A failing current case exits nonzero. For an intentionally failing
saved baseline add `--baseline` after the output path.

Cases 0–11 reproduce twelve failure scenarios. Cases 12–18 are positive controls:
ordinary map/FD cleanup, successful CPU/AHB imports, fence replacement, successful
EGL destruction and actual changed-pixel upload. Case 19 races 1000 SurfaceControl
release callbacks against AHB destruction; case 20 verifies the production
XServerComponent stops its dispatcher before detaching mappings. Teardown's
socket join is a mocked boundary; its implementation was inspected separately.

These are Java ownership/control-flow tests with counted JNI/GL boundaries, not
real Android GPU tests. They prove eliminated native calls, balanced references,
protocol failure cleanup and liveness; they cannot establish device FPS, hardware
synchronization correctness or Vulkan conformance. Normal DRI3 Present does not
import a new pixmap every frame. The four avoided AHB allocations are on the
memory-FD import branch; the AHB branch previously attempted a zero-width CPU
allocation. The duplicate upload affects GL fallback immediately after allocating
a texture, rather than all frames on ASurface/Vulkan.

`prepare_candidate.py` is an audit-only scratch generator: it refuses to overwrite
its candidate directory and never writes app/src. Saved evidence and candidate
patches reside under docs/performance-audits/current-frame-resource-audit-path.txt.
Older evidence/candidates remain untouched.
