#!/usr/bin/env bash
# Puts the runtime packages that are not in git (Proton, VKD3D, DXVK, TurnRIP, the bionic image, the DX12 driver probe) into
# app/src/main/assets and checks them against tools/components.sha256.
#   tools/fetch_components.sh /some/folder  copy them from a folder that holds the files
#   COMPONENTS_TAG=<tag> tools/fetch_components.sh   download them from a release of COMPONENTS_REPO with the gh CLI
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$REPO_DIR/app/src/main/assets"
REPO="${COMPONENTS_REPO:-DeadmanDog/forever-launcher-on-android}"
TAG="${COMPONENTS_TAG:-}"

mapfile -t PATHS < <(awk '{print $2}' "$REPO_DIR/tools/components.sha256")
for p in "${PATHS[@]}"; do
    f="$(basename "$p")"
    mkdir -p "$ASSETS/$(dirname "$p")"
    if [ -s "$ASSETS/$p" ]; then
        echo "have $p"
    elif [ -n "${1:-}" ] && [ -s "$1/$f" ]; then
        cp "$1/$f" "$ASSETS/$p"
        echo "copied $p"
    elif [ -n "$TAG" ]; then
        gh release download "$TAG" --repo "$REPO" --pattern "$f" --dir "$ASSETS/$(dirname "$p")" --clobber
        echo "downloaded $p"
    else
        echo "Missing $p: pass the folder that holds $f, or set COMPONENTS_TAG to a release that carries it." >&2
        exit 1
    fi
done

(cd "$ASSETS" && sha256sum -c "$REPO_DIR/tools/components.sha256")
