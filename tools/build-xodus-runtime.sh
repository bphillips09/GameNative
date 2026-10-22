#!/usr/bin/env bash
# Builds the Windows-side Xbox runtime overlay for GameNative:
#   - app/src/main/assets/xbox/xodus-runtime-overlay.tzst
#
# The overlay holds Xodus's xgameruntime.dll (the GDK runtime games call for
# sign-in, licensing, Store and cloud saves) and the Wine DLLs Xodus patches for
# it (HTTP/2 WinHTTP, Windows.Data.Json, app-state registration), all x86_64 PE.
# GameNative copies them into an Xbox game's container prefix and enables them
# with WINEDLLOVERRIDES at launch, so other containers and the shared Wine
# install are untouched.
#
# The DLLs are produced by Xodus Nexus's own clean-build recipe
# (scripts/build-shared-runtime.mjs at the commit in tools/xodus-patches/source.lock.json):
# pinned xodus-gaming/wine + xodus-gaming/xgameruntime + Nexus runtime patches,
# checksum-verified. tools/xodus-runtime-overlay.py then clears Wine's builtin
# marker and packages them.
#
# GameNative also takes wintypes.dll from that Wine 11 tree. Windows.Data.Json
# needs Windows.Foundation.Collections.PropertySet, which Wine 11's wintypes
# implements but the Wine 9/10 in GameNative's Proton builds does not; Xodus
# Nexus runs on its own Wine 11 and so never ships it. Its checksum is recorded
# in components/gamenative-components.json next to the upstream manifest.
#
# Prerequisites (Ubuntu 24.04, as in Xodus Nexus's runtime CI): node >= 20, git, jq, zstd,
#   build-essential gcc-mingw-w64-x86-64 g++-mingw-w64-x86-64 flex bison autoconf automake
#   libtool libjson-perl wine64-tools cmake pkg-config python3 perl file gettext
#   libfreetype-dev libgnutls28-dev; widl on PATH (export PATH=/usr/lib/wine:$PATH).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$REPO_ROOT/tools/xodus-patches/source.lock.json"
BUILD_DIR="${XODUS_RUNTIME_BUILD_DIR:-$REPO_ROOT/build/xodus-runtime}"
OUTPUT="$REPO_ROOT/app/src/main/assets/xbox/xodus-runtime-overlay.tzst"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"
NEXUS_URL=$(jq -r .nexus.url "$LOCK")
NEXUS_COMMIT=$(jq -r .nexus.commit "$LOCK")

command -v widl >/dev/null || { echo "widl not on PATH (see prerequisites)" >&2; exit 1; }
pkg-config --exists freetype2 || { echo "FreeType development files missing (libfreetype-dev)" >&2; exit 1; }
mkdir -p "$BUILD_DIR"
NEXUS_DIR="${NEXUS_DIR:-$BUILD_DIR/nexus}"
if [[ ! -d "$NEXUS_DIR/.git" ]]; then
    git init --quiet "$NEXUS_DIR"
    git -C "$NEXUS_DIR" fetch --quiet --depth 1 "$NEXUS_URL" "$NEXUS_COMMIT"
    git -C "$NEXUS_DIR" checkout --quiet --detach FETCH_HEAD
fi
[[ "$(git -C "$NEXUS_DIR" rev-parse HEAD)" == "$NEXUS_COMMIT" ]] || { echo "xodus-nexus is not at $NEXUS_COMMIT" >&2; exit 1; }

WORK="$BUILD_DIR/work-$(date +%s)"
echo "==> Nexus runtime component build in $WORK"
(cd "$NEXUS_DIR" && node scripts/build-shared-runtime.mjs --work "$WORK" --jobs "$JOBS")

echo "==> wintypes.dll from the same Wine tree"
WINTYPES="dlls/wintypes/x86_64-windows/wintypes.dll"
make -C "$WORK/wine-build" -j"$JOBS" "$WINTYPES"
file -b "$WORK/wine-build/$WINTYPES" | grep -q 'PE32+ executable.*x86-64' || { echo "wintypes.dll is not a 64-bit Windows DLL" >&2; exit 1; }
cp "$WORK/wine-build/$WINTYPES" "$WORK/components/wintypes.dll"
jq -n --arg sha "$(sha256sum "$WORK/components/wintypes.dll" | cut -d' ' -f1)" \
    '{components: {"wintypes.dll": $sha}, built: "wine-build/'"$WINTYPES"'"}' > "$WORK/components/gamenative-components.json"

echo "==> packaging overlay"
mkdir -p "$(dirname "$OUTPUT")"
python3 "$REPO_ROOT/tools/xodus-runtime-overlay.py" --components "$WORK/components" --output "$OUTPUT"
ls -la "$OUTPUT"
