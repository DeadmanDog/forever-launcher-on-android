#!/bin/bash
# Runs every native renderer regression suite with its own arguments. Usage: run_all.sh <empty output dir>
# Prints one "<suite> rc=<code>" line per suite; the logs are in the output dir.
source ~/.local/share/wow-build-tools/env.sh >/dev/null 2>&1
T="$(cd "$(dirname "$0")" && pwd)"
O=$1; [ -n "$O" ] || { echo "usage: $0 <output dir>"; exit 2; }; rm -rf "$O"; mkdir -p "$O"
cd "$T"
run() { n=$1; shift; timeout 900 python3 -B "$@" > "$O/$n.log" 2>&1; echo "$n rc=$?"; }
run cursor_gpu run_cursor_gpu_regressions.py --report "$O/cursor_gpu.json"
run idle_poll run_idle_poll_regressions.py
run idle_pace run_idle_pace_regressions.py
run ownership run_ownership_regressions.py
run retirement run_retirement_regressions.py
run scanout run_scanout_regressions.py
run import run_import_regressions.py --out "$O/import"
run renderer run_renderer_regressions.py
run rotation_gpu run_rotation_gpu_regressions.py
run scene run_scene_regressions.py --out "$O/scene"
run settings run_settings_regressions.py --out "$O/settings"
run state run_state_regressions.py --out "$O/state"
run upload_scene run_upload_scene_regressions.py --out "$O/upload_scene"
run window run_window_regressions.py --out "$O/window"
run present run_present_regressions.py
run occluded run_occluded_regressions.py "$O/occluded"
run fsr_gpu run_fsr_gpu_regressions.py
run scanout_waiter run_scanout_waiter_regressions.py
run scanout_release run_scanout_release_regressions.py
run native_fix run_native_fix_regressions.py

run hidden_cursor_work run_hidden_cursor_work_regressions.py --out "$O/hidden_cursor_work"

run teardown_oom run_teardown_oom_regressions.py --out "$O/teardown_oom"

run hidden_cursor_pacing run_hidden_cursor_pacing_regressions.py --out "$O/hidden_cursor_pacing"
run fifo_gate run_fifo_gate_regressions.py
run neutral_gpu run_neutral_gpu_regressions.py
run drain_hold run_drain_hold_regressions.py
run drain_step run_drain_step_regressions.py
run drain_replay run_drain_replay_regressions.py
run handback run_handback_regressions.py
run gate_jitter run_gate_jitter_regressions.py
run replay_jitter run_replay_jitter_regressions.py
