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
