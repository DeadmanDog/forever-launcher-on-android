# Modified version: notice

This directory is the **modified** `ntsync-android` that Forever Launcher ships as `lib/libntsync_android.so`
inside its Proton 11 ARM64EC package (`proton-11.0-90639.31-arm64ec-r31.wcp`).

- **Original work:** [joshuatam/ntsync-android](https://github.com/joshuatam/ntsync-android),
  Copyright (C) 2026 Joshua Tam, licensed under the GNU Lesser General Public License v3.0 only (see `LICENSE`).
  Our copy started from the upstream state of 2026-08-31 (around commit `eac24f0`, "Add adaptive spin-before-block").
- **Modified by:** the Forever Launcher project (DeadmanDog), October 2026, over several Proton runtime revisions up to r30.
  The modified version is distributed under the same license, LGPL-3.0-only.
- **Main changes** (the complete change is the difference between this directory and upstream; mainly `src/core.rs`, `src/ffi.rs`, `src/lib.rs`, `include/ntsync_user.h`; `src/region_mutex.rs` is new):
  - a wait keeps the objects it waits on alive through a reference count in the slot's closing word
    (bit 31 = closing, bits 0-30 = references), so closing an object no longer breaks a running wait (except alerts);
  - pulse events wake a sorted union of the waiters; dead processes' objects are swept in slices;
  - absolute `FUTEX_WAIT_BITSET` sleeps for timed waits (no drift on repeated wake-ups);
  - a larger object table, a wait-invalid counter and a usage line at wineserver exit;
  - the region lock moved to `src/region_mutex.rs`; tests run one at a time (`.cargo/config.toml`).
  The exact source of each shipped build is this directory; the upstream history shows the original.
- **Not included here:** `vendor/` (the `libc` crate from crates.io, MIT/Apache-2.0, version pinned in `Cargo.lock`) and build
  output. Recreate `vendor/` with `cargo vendor` before building offline; `build-scripts/build-android.sh` builds the
  Android library with the Android NDK.

The rest of the Proton package (Wine, LGPL-2.1-or-later) and the other runtime sources are available on request, see
`THIRD_PARTY_NOTICES` in the repository root.
