#!/bin/bash
# compile_jni_libs_cpp.sh
# Compiles the C++ JNI library and copies it + ONNX Runtime to the Android project.
# Replaces the Rust-based compile_jni_libs.sh.
# Supported ABIs: arm64-v8a, x86_64

set -euo pipefail

ABIS="${ABIS:-${1:-arm64-v8a,x86_64}}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ONNX_ANDROID_ROOT="${ONNX_ANDROID_ROOT:-$HOME/onnxruntime-android}"
ONNX_JNI_ROOT="${ONNX_JNI_ROOT:-$ONNX_ANDROID_ROOT/jni}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
MIN_SDK="${MIN_SDK:-24}"
MIN_ELF_ALIGNMENT=16384

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
CPP_DIR="$REPO_ROOT/cpp/android"

echo "=== Supertonic C++ JNI Build Script ==="
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

NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
TOOLCHAIN_ROOT="$NDK_ROOT/toolchains/llvm/prebuilt"
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

NDK_CMAKE_TOOLCHAIN="$NDK_ROOT/build/cmake/android.toolchain.cmake"
if [ ! -f "$NDK_CMAKE_TOOLCHAIN" ]; then
    echo "Error: Android NDK CMake toolchain not found at $NDK_CMAKE_TOOLCHAIN"
    exit 1
fi

# Locate cmake
CMAKE_BIN="$(command -v cmake || true)"
if [ -z "$CMAKE_BIN" ]; then
    # Try Android SDK cmake
    SDK_CMAKE_DIR="$ANDROID_SDK_ROOT/cmake"
    if [ -d "$SDK_CMAKE_DIR" ]; then
        latest_cmake="$(find "$SDK_CMAKE_DIR" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
        if [ -n "$latest_cmake" ] && [ -x "$latest_cmake/bin/cmake" ]; then
            CMAKE_BIN="$latest_cmake/bin/cmake"
        fi
    fi
    if [ -z "$CMAKE_BIN" ]; then
        echo "Error: cmake not found. Install CMake or the Android SDK CMake component."
        exit 1
    fi
fi

# Locate ninja (prefer SDK, fallback to system)
NINJA_BIN="$(command -v ninja || true)"
if [ -z "$NINJA_BIN" ]; then
    SDK_CMAKE_DIR="$ANDROID_SDK_ROOT/cmake"
    if [ -d "$SDK_CMAKE_DIR" ]; then
        latest_cmake="$(find "$SDK_CMAKE_DIR" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
        if [ -n "$latest_cmake" ] && [ -x "$latest_cmake/bin/ninja" ]; then
            NINJA_BIN="$latest_cmake/bin/ninja"
        fi
    fi
fi

CMAKE_GENERATOR="Unix Makefiles"
if [ -n "$NINJA_BIN" ]; then
    CMAKE_GENERATOR="Ninja"
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
    local onnx_abi_dir="$ONNX_JNI_ROOT/$abi"
    local onnx_lib="$onnx_abi_dir/libonnxruntime.so"
    local jni_libs_dir="$REPO_ROOT/android/app/src/main/jniLibs/$abi"
    local build_dir="$CPP_DIR/build-$abi"

    if [ ! -f "$onnx_lib" ]; then
        echo "Error: libonnxruntime.so not found for ABI '$abi' at $onnx_lib"
        exit 1
    fi

    check_alignment "$onnx_lib"

    echo "[build] ABI=$abi"
    echo "        Using ONNX Runtime from: $onnx_abi_dir"

    mkdir -p "$build_dir"

    # Determine STL target dir for libc++_shared.so
    local stl_target=""
    case "$abi" in
        arm64-v8a) stl_target="aarch64-linux-android" ;;
        x86_64)    stl_target="x86_64-linux-android"  ;;
        *)
            echo "Error: Unsupported ABI '$abi'. Supported values: arm64-v8a, x86_64"
            exit 1
            ;;
    esac

    # CMake configure
    "$CMAKE_BIN" \
        -S "$CPP_DIR" \
        -B "$build_dir" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_CMAKE_TOOLCHAIN" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$MIN_SDK" \
        -DANDROID_STL="c++_shared" \
        -DCMAKE_BUILD_TYPE=Release \
        -DONNX_JNI_ROOT="$ONNX_JNI_ROOT" \
        -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
        -G "$CMAKE_GENERATOR"

    # CMake build
    "$CMAKE_BIN" --build "$build_dir" --config Release --target supertonic_tts -j

    local built_lib="$build_dir/libsupertonic_tts.so"
    if [ ! -f "$built_lib" ]; then
        echo "Error: Built library not found at $built_lib"
        exit 1
    fi

    check_alignment "$built_lib"

    mkdir -p "$jni_libs_dir"
    cp "$built_lib" "$jni_libs_dir/"
    cp "$onnx_lib" "$jni_libs_dir/"

    # Copy libc++_shared.so from NDK sysroot
    local stl_so="$TOOLCHAIN_HOST_DIR/sysroot/usr/lib/$stl_target/libc++_shared.so"
    if [ -f "$stl_so" ]; then
        cp "$stl_so" "$jni_libs_dir/"
        echo "        Copied libc++_shared.so"
    fi

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
