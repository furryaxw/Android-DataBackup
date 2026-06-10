#!/bin/bash
# Build rustic CLI binary for Android
# Usage: bash build_rustic.sh [arch|all]
# Prerequisites:
#   - Rust toolchain with Android targets
#   - Android NDK with cross-compilers

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUSTIC_DIR="$SCRIPT_DIR/../source/native/src/main/jni/rustic"
BUILD_OUT="$SCRIPT_DIR/../source/native/build_bin/built_in"

NDK="${NDK:-}"
if [ -z "$NDK" ]; then
    # Try common locations
    for candidate in "$ANDROID_HOME/ndk/25.2.9519653" "$ANDROID_SDK_ROOT/ndk/25.2.9519653" "$HOME/Android/Sdk/ndk/25.2.9519653"; do
        if [ -d "$candidate" ]; then
            NDK="$candidate"
            break
        fi
    done
fi

if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "Error: NDK not found. Set NDK environment variable."
    exit 1
fi

LLVM="$NDK/toolchains/llvm/prebuilt"
HOST_TAG=""
for tag in "linux-x86_64" "darwin-x86_64" "windows-x86_64"; do
    if [ -d "$LLVM/$tag" ]; then
        HOST_TAG="$tag"
        break
    fi
done

if [ -z "$HOST_TAG" ]; then
    echo "Error: Cannot determine NDK host tag."
    exit 1
fi

TOOLCHAIN="$LLVM/$HOST_TAG/bin"
STRIP="$TOOLCHAIN/llvm-strip"
API=28

echo "NDK: $NDK"
echo "Toolchain: $TOOLCHAIN"

declare -A ARCH_MAP
ARCH_MAP["aarch64-linux-android"]="arm64-v8a"
ARCH_MAP["armv7-linux-androideabi"]="armeabi-v7a"
ARCH_MAP["x86_64-linux-android"]="x86_64"
ARCH_MAP["i686-linux-android"]="x86"

ARCH="${1:-all}"

build_target() {
    local target="$1"
    local abi="${ARCH_MAP[$target]}"
    local clang="$TOOLCHAIN/${target}${API}-clang"

    if [ "${HOST_TAG#windows}" != "$HOST_TAG" ]; then
        clang="${clang}.cmd"
    fi

    if [ ! -f "$clang" ]; then
        echo "  Skipping $target: clang not found at $clang"
        return
    fi

    echo ""
    echo "=== Building for $target ($abi) ==="

    export "CC_${target//[-.]/_}=$clang"
    export "AR_${target//[-.]/_}=$TOOLCHAIN/llvm-ar"
    export "CARGO_TARGET_$(echo "${target}" | tr '[:lower:]' '[:upper:]' | tr '-' '_')_LINKER=$clang"

    cargo build --release --target "$target" --manifest-path "$RUSTIC_DIR/Cargo.toml"

    local src="$RUSTIC_DIR/target/$target/release/rustic"
    if [ -f "$src" ]; then
        "$STRIP" "$src" 2>/dev/null || true
        local out="$BUILD_OUT/$abi"
        mkdir -p "$out"
        cp "$src" "$out/rustic"
        echo "  $target -> $abi ($(stat -c%s "$out/rustic" 2>/dev/null || stat -f%z "$out/rustic" 2>/dev/null) bytes)"
    fi
}

for target in "${!ARCH_MAP[@]}"; do
    if [ "$ARCH" = "all" ] || [ "$ARCH" = "$target" ]; then
        build_target "$target"
    fi
done

echo ""
echo "=== Build complete ==="
echo "Binaries: $BUILD_OUT"
