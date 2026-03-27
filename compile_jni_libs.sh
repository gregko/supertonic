#!/bin/bash
# compile_jni_libs.sh
# Compiles the Rust JNI library and copies it + ONNX Runtime to the Android project.
# Supported ABIs: arm64-v8a, x86_64

set -euo pipefail

ABIS="${ABIS:-${1:-arm64-v8a,x86_64}}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ONNX_ANDROID_ROOT="${ONNX_ANDROID_ROOT:-$HOME/onnxruntime-android}"
ONNX_JNI_ROOT="${ONNX_JNI_ROOT:-$ONNX_ANDROID_ROOT/jni}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
MIN_SDK="${MIN_SDK:-24}"
MIN_ELF_ALIGNMENT=16384

echo "=== Supertonic JNI Build Script ==="
echo "Requested ABIs: $ABIS"

if [ ! -d "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION" ]; then
    latest_ndk="$(find "$ANDROID_SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort -V | tail -n 1)"
    if [ -z "$latest_ndk" ]; then
        echo "Error: Android NDK not found under $ANDROID_SDK_ROOT/ndk"
        exit 1
    fi
    echo "Warning: NDK $NDK_VERSION not found. Using $latest_ndk instead."
    NDK_VERSION="$latest_ndk"
fi

TOOLCHAIN_ROOT="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt"
TOOLCHAIN_HOST_DIR="$(find "$TOOLCHAIN_ROOT" -mindepth 1 -maxdepth 1 -type d | head -n 1)"

if [ -z "$TOOLCHAIN_HOST_DIR" ]; then
    echo "Error: Could not locate the NDK LLVM toolchain under $TOOLCHAIN_ROOT"
    exit 1
fi

TOOLCHAIN_BIN="$TOOLCHAIN_HOST_DIR/bin"
READELF_BIN="$TOOLCHAIN_BIN/llvm-readelf"

if [ ! -x "$READELF_BIN" ]; then
    READELF_BIN="$(command -v llvm-readelf || command -v readelf || true)"
fi

if [ -z "$READELF_BIN" ]; then
    echo "Error: Could not locate llvm-readelf or readelf to validate ELF alignment"
    exit 1
fi

trim() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
}

check_alignment() {
    local library_path="$1"
    local bad_alignment=""

    while read -r alignment; do
        [ -z "$alignment" ] && continue
        local alignment_hex="${alignment#0x}"
        local alignment_dec=$((16#$alignment_hex))
        if [ "$alignment_dec" -lt "$MIN_ELF_ALIGNMENT" ]; then
            bad_alignment="$alignment"
            break
        fi
    done < <("$READELF_BIN" -l "$library_path" | awk '$1 == "LOAD" { print $NF }')

    if [ -n "$bad_alignment" ]; then
        echo "Error: $library_path has ELF LOAD alignment $bad_alignment; 0x4000 or higher is required."
        exit 1
    fi
}

build_abi() {
    local abi="$1"
    local target=""
    local linker_base=""

    case "$abi" in
        arm64-v8a)
            target="aarch64-linux-android"
            linker_base="aarch64-linux-android${MIN_SDK}-clang"
            ;;
        x86_64)
            target="x86_64-linux-android"
            linker_base="x86_64-linux-android${MIN_SDK}-clang"
            ;;
        *)
            echo "Error: Unsupported ABI '$abi'. Supported values: arm64-v8a, x86_64"
            exit 1
            ;;
    esac

    local onnx_abi_dir="$ONNX_JNI_ROOT/$abi"
    local onnx_lib="$onnx_abi_dir/libonnxruntime.so"
    local jni_libs_dir="android/app/src/main/jniLibs/$abi"
    local output_lib="rust/target/$target/release/libsupertonic_tts.so"
    local linker_path="$TOOLCHAIN_BIN/$linker_base"

    if [ -f "${linker_path}.cmd" ]; then
        linker_path="${linker_path}.cmd"
    fi

    if [ ! -f "$linker_path" ]; then
        echo "Error: Linker not found at $linker_path"
        exit 1
    fi

    if [ ! -f "$onnx_lib" ]; then
        echo "Error: libonnxruntime.so not found for ABI '$abi' at $onnx_lib"
        echo "Extract the ONNX Runtime Android package so it contains $ONNX_JNI_ROOT/<abi>/libonnxruntime.so"
        exit 1
    fi

    check_alignment "$onnx_lib"

    echo "[build] ABI=$abi target=$target"
    echo "        Using ONNX Runtime from: $onnx_abi_dir"

    export ORT_STRATEGY=system
    export ORT_LIB_LOCATION="$onnx_abi_dir"

    local cargo_env_name="CARGO_TARGET_$(printf '%s' "$target" | tr '[:lower:]-' '[:upper:]_')_LINKER"
    export "$cargo_env_name=$linker_path"

    if command -v rustup >/dev/null 2>&1; then
        rustup target add "$target"
    fi

    (
        cd rust
        cargo build --release --target "$target"
    )
    cargo_status=$?
    if [ $cargo_status -ne 0 ]; then
        echo "Error: cargo build failed for target $target"
        exit $cargo_status
    fi

    check_alignment "$output_lib"

    mkdir -p "$jni_libs_dir"
    cp "$output_lib" "$jni_libs_dir/"
    cp "$onnx_lib" "$jni_libs_dir/"

    echo "        Copied:"
    ls -lh "$jni_libs_dir"
}

IFS=',' read -r -a abi_list <<< "$ABIS"
for raw_abi in "${abi_list[@]}"; do
    abi="$(trim "$raw_abi")"
    if [ -n "$abi" ]; then
        build_abi "$abi"
    fi
done

echo "JNI libraries are ready under android/app/src/main/jniLibs/"
