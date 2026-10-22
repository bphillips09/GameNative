#!/usr/bin/env bash
# Builds the Xodus Nexus backend for GameNative's Xbox (Play Anywhere / PC Game Pass) support:
#   - app/src/main/jniLibs/arm64-v8a/libxodus_cli.so      xodus-cli, aarch64-linux-android executable
#   - app/src/main/jniLibs/arm64-v8a/libxodus_service.so  xodus-service, aarch64-linux-android executable
# Both run via ProcessBuilder from nativeLibraryDir (same packaging trick as libcomet.so).
#
# Source recipe (tools/xodus-patches/source.lock.json):
#   1. upstream xodus-gaming/xodus at the commit Xodus Nexus pins;
#   2. the Xodus Nexus backend patches, in nexus-backend-source.lock.json order,
#      checksum-verified, taken from the pinned xodus-nexus commit;
#   3. GameNative's Android patches (tools/xodus-patches/*.patch):
#      file credential store, synthetic SMBIOS identity, explicit TLS roots,
#      vendored OpenSSL, and the `prepare-launch` handoff command.
#
# Prerequisites:
#   - rustup with the pinned toolchain and the Android target:
#       rustup toolchain install 1.99.0 && rustup target add aarch64-linux-android --toolchain 1.99.0
#   - Android NDK r27c (ANDROID_NDK_HOME, else the default SDK locations for ndk/27.3.13750724)
#   - protoc, cmake, perl, make, git, jq, sha256sum
#
# Overrides: XODUS_BUILD_DIR, NEXUS_DIR (an existing xodus-nexus checkout at the pinned commit).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PATCH_DIR="$REPO_ROOT/tools/xodus-patches"
LOCK="$PATCH_DIR/source.lock.json"
BUILD_DIR="${XODUS_BUILD_DIR:-$REPO_ROOT/build/xodus}"
SRC_DIR="$BUILD_DIR/src"
JNI_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"

export PATH="$HOME/.cargo/bin:$PATH"
need() { command -v "$1" >/dev/null || { echo "missing prerequisite: $1" >&2; exit 1; }; }
for tool in git jq cargo rustup protoc cmake perl make sha256sum; do need "$tool"; done

XODUS_URL=$(jq -r .xodus.url "$LOCK")
XODUS_COMMIT=$(jq -r .xodus.commit "$LOCK")
RUST_VERSION=$(jq -r .xodus.rust "$LOCK")
NEXUS_URL=$(jq -r .nexus.url "$LOCK")
NEXUS_COMMIT=$(jq -r .nexus.commit "$LOCK")

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    for c in "$HOME/Library/Android/sdk/ndk/27.3.13750724" "$HOME/Android/Sdk/ndk/27.3.13750724" \
             "${ANDROID_HOME:-/nonexistent}/ndk/27.3.13750724" "${ANDROID_SDK_ROOT:-/nonexistent}/ndk/27.3.13750724"; do
        [[ -d "$c" ]] && ANDROID_NDK_HOME="$c" && break
    done
fi
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an NDK r27c install}"
export ANDROID_NDK_HOME="$NDK" ANDROID_NDK_ROOT="$NDK"
case "$(uname -s)" in
    Darwin) HOST_TAG="darwin-x86_64" ;;
    Linux) HOST_TAG="linux-x86_64" ;;
    *) echo "unsupported host $(uname -s)" >&2; exit 1 ;;
esac
NDK_BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[[ -x "$NDK_BIN/aarch64-linux-android26-clang" ]] || { echo "NDK clang not found in $NDK_BIN" >&2; exit 1; }

echo "==> Xodus Nexus patch set at $NEXUS_COMMIT"
mkdir -p "$BUILD_DIR"
NEXUS_DIR="${NEXUS_DIR:-$BUILD_DIR/nexus}"
if [[ ! -d "$NEXUS_DIR/.git" ]]; then
    git init --quiet "$NEXUS_DIR"
    git -C "$NEXUS_DIR" fetch --quiet --depth 1 "$NEXUS_URL" "$NEXUS_COMMIT"
    git -C "$NEXUS_DIR" checkout --quiet --detach FETCH_HEAD
fi
[[ "$(git -C "$NEXUS_DIR" rev-parse HEAD)" == "$NEXUS_COMMIT" ]] || { echo "xodus-nexus is not at $NEXUS_COMMIT" >&2; exit 1; }
# The Nexus lock we vendor must be the one at the pinned commit.
cmp -s "$NEXUS_DIR/assets/backend-source.lock.json" "$PATCH_DIR/nexus-backend-source.lock.json" \
    || { echo "tools/xodus-patches/nexus-backend-source.lock.json differs from xodus-nexus@$NEXUS_COMMIT" >&2; exit 1; }
[[ "$(jq -r .commit "$PATCH_DIR/nexus-backend-source.lock.json")" == "$XODUS_COMMIT" ]] \
    || { echo "source.lock.json and the Nexus lock pin different Xodus commits" >&2; exit 1; }

echo "==> xodus $XODUS_COMMIT into $SRC_DIR"
if [[ ! -d "$SRC_DIR/.git" ]]; then
    git init --quiet "$SRC_DIR"
fi
git -C "$SRC_DIR" fetch --quiet "$XODUS_URL" "$XODUS_COMMIT"
git -C "$SRC_DIR" checkout --quiet --force --detach "$XODUS_COMMIT"
git -C "$SRC_DIR" clean -fdxq -e target
[[ "$(sha256sum "$SRC_DIR/Cargo.lock" | cut -d' ' -f1)" == "$(jq -r .cargoLockSha256 "$PATCH_DIR/nexus-backend-source.lock.json")" ]] \
    || { echo "upstream Cargo.lock does not match the Nexus recipe" >&2; exit 1; }

echo "==> Nexus backend patches ($(jq -r .patchId "$PATCH_DIR/nexus-backend-source.lock.json"))"
while IFS=$'\t' read -r file sha; do
    patch="$NEXUS_DIR/assets/$file"
    [[ "$(sha256sum "$patch" | cut -d' ' -f1)" == "$sha" ]] || { echo "checksum mismatch: $file" >&2; exit 1; }
    git -C "$SRC_DIR" apply --check "$patch"
    git -C "$SRC_DIR" apply "$patch"
done < <(jq -r '.patches[] | [.file, .sha256] | @tsv' "$PATCH_DIR/nexus-backend-source.lock.json")

echo "==> GameNative Android patches"
for name in $(jq -r '.androidPatches[]' "$LOCK"); do
    echo "    applying $name"
    git -C "$SRC_DIR" apply --check "$PATCH_DIR/$name"
    git -C "$SRC_DIR" apply "$PATCH_DIR/$name"
done

echo "==> cargo build --release --target aarch64-linux-android (Rust $RUST_VERSION)"
rustup target add aarch64-linux-android --toolchain "$RUST_VERSION" >/dev/null
export CC_aarch64_linux_android="$NDK_BIN/aarch64-linux-android26-clang"
export CXX_aarch64_linux_android="$NDK_BIN/aarch64-linux-android26-clang++"
export AR_aarch64_linux_android="$NDK_BIN/llvm-ar"
export RANLIB_aarch64_linux_android="$NDK_BIN/llvm-ranlib"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android26-clang"
# xodus_android selects the Android code paths; 16 KiB pages for Android 15+ devices.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="--cfg xodus_android -C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_PROFILE_RELEASE_STRIP=true
(cd "$SRC_DIR" && cargo "+$RUST_VERSION" build --release --locked --no-default-features \
    --target aarch64-linux-android -p xodus-cli -p xodus-service)

mkdir -p "$JNI_DIR"
OUT="$SRC_DIR/target/aarch64-linux-android/release"
"$NDK_BIN/llvm-strip" -o "$JNI_DIR/libxodus_cli.so" "$OUT/xodus-cli"
"$NDK_BIN/llvm-strip" -o "$JNI_DIR/libxodus_service.so" "$OUT/xodus-service"

echo "==> results"
ls -la "$JNI_DIR/libxodus_cli.so" "$JNI_DIR/libxodus_service.so"
file "$JNI_DIR/libxodus_cli.so" "$JNI_DIR/libxodus_service.so" || true
for bin in "$JNI_DIR/libxodus_cli.so" "$JNI_DIR/libxodus_service.so"; do
    # Only system libraries may be needed: everything else is linked statically.
    "$NDK_BIN/llvm-readelf" -d "$bin" | awk '/NEEDED/ {print "    needs", $NF}'
    if "$NDK_BIN/llvm-readelf" -d "$bin" | grep NEEDED | grep -vqE '\[(libc|libm|libdl|liblog)\.so\]'; then
        echo "unexpected shared library dependency in $bin" >&2; exit 1
    fi
done
