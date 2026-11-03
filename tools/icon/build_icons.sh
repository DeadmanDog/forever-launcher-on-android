#!/usr/bin/env bash
# Approved 04-2 artwork packaging. Requires Python with Pillow.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
"${PYTHON:-python3}" "$HERE/package_042.py" "$@"
