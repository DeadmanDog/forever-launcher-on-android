# Gamescope Android port additions

The Android base is GameNative/gamescope at `0cb97886e9ab4b0aea8255f9a07ba92fafdaa44b`.
Apply the pinned base's Android patch train, then the patches here in order. Build using
its `android/build-gamescope.sh` and the Android NDK r27d. The app retains the DRM
daemon, Xwayland, ICD and loader dependencies of its paired payload.

Patches 0114 and 0115 come from [DroidDeck aba5503](https://github.com/Droid-Deck/DroidDeck/tree/aba55031c331cd0991c3d36788fb903a3ca30e78/tools/gamescope/patches).
0114 is Matthew Schwartz's [upstream Gamescope fix](https://github.com/ValveSoftware/gamescope/commit/3829340).
0115 uses each window's last latch as the fixed-refresh limiter's timing origin.
0116 fixes ReShade resource creation and cleanup for effects without uniforms or storage images.
0117 scales Android cursor geometry and reuses the completed composite during cursor-only updates with a bundled static picture preset.
0118 routes Quick Menu cursor visibility and SDR effects through the Gamescope compositor. The shader formulas come from the app's XServer window shader.
The Gamescope license is in the payload and the upstream repository.
0119 takes the cursor's 48-pixel readable minimum from GNDRM_CURSOR_MIN_PX (output pixels; the launcher passes 48 x output / panel),
so a game-sized output that the panel enlarges does not make the cursor 1.5x the XServer one. Built 2026-11-07 with NDK r27d against
android/ci/termux-prefix.sh at /tmp/wow-gs-termux (the series + 0114-0118 first reproduced the shipped bin/gamescope 90b46011
byte-identically; host wayland-scanner 1.26.0, wayland-protocols 1.49 with enum headers, stand-in xwayland.pc and hwdata.pc in
docs/runtime-archive/20261107-gamescope-0119-cursor-minimum).
