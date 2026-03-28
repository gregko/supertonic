cmake_minimum_required(VERSION 3.18)
project(supertonic_tts C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Parent directory containing the actual C++ source files
set(CPP_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/..")

# Aggressive optimisation for release builds
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -DNDEBUG -ffast-math")
set(CMAKE_C_FLAGS_RELEASE   "${CMAKE_C_FLAGS_RELEASE}   -O3 -DNDEBUG -ffast-math")

# ---------------------------------------------------------------------------
# ONNX Runtime  (pre-built shared library supplied via ONNX_JNI_ROOT)
# ---------------------------------------------------------------------------
# ONNX_JNI_ROOT layout (from extracted onnxruntime-android AAR):
#   <onnx_root>/jni/<abi>/libonnxruntime.so
#   <onnx_root>/headers/onnxruntime_cxx_api.h

if(NOT DEFINED ONNX_JNI_ROOT)
    message(FATAL_ERROR "ONNX_JNI_ROOT must be set to the ONNX Runtime Android JNI root directory")
endif()

set(ORT_INCLUDE_DIR "${ONNX_JNI_ROOT}/../headers")
if(NOT EXISTS "${ORT_INCLUDE_DIR}/onnxruntime_cxx_api.h")
    message(FATAL_ERROR
        "Cannot find onnxruntime_cxx_api.h. "
        "Expected at ${ORT_INCLUDE_DIR}/onnxruntime_cxx_api.h")
endif()

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${ONNX_JNI_ROOT}/${ANDROID_ABI}/libonnxruntime.so"
    INTERFACE_INCLUDE_DIRECTORIES "${ORT_INCLUDE_DIR}"
)

# ---------------------------------------------------------------------------
# nlohmann/json  (header-only, bundled as a single header)
# ---------------------------------------------------------------------------
set(JSON_HEADER "${CPP_SRC_DIR}/nlohmann/json.hpp")
if(NOT EXISTS "${JSON_HEADER}")
    file(DOWNLOAD
        "https://github.com/nlohmann/json/releases/download/v3.11.3/json.hpp"
        "${JSON_HEADER}"
        STATUS download_status
        TLS_VERIFY ON)
    list(GET download_status 0 status_code)
    if(NOT status_code EQUAL 0)
        message(FATAL_ERROR
            "Failed to download nlohmann/json.hpp. "
            "Please place it manually at ${JSON_HEADER}")
    endif()
endif()

add_library(nlohmann_json INTERFACE)
target_include_directories(nlohmann_json INTERFACE "${CPP_SRC_DIR}")

# ---------------------------------------------------------------------------
# TTS helper library  (existing C++ engine code from parent dir)
# ---------------------------------------------------------------------------
add_library(tts_helper STATIC
    "${CPP_SRC_DIR}/helper.cpp"
    "${CPP_SRC_DIR}/helper.h"
)
target_link_libraries(tts_helper PUBLIC onnxruntime nlohmann_json)
target_include_directories(tts_helper PUBLIC "${CPP_SRC_DIR}")

# ---------------------------------------------------------------------------
# JNI shared library  (libsupertonic_tts.so - replaces the Rust version)
# ---------------------------------------------------------------------------
add_library(supertonic_tts SHARED
    "${CPP_SRC_DIR}/jni_bridge.cpp"
)
target_link_libraries(supertonic_tts
    PRIVATE
        tts_helper
        onnxruntime
        nlohmann_json
        android
        log
)

# Ensure 16 KB page-size alignment (required by Android 15+/ARM64 security policy)
target_link_options(supertonic_tts PRIVATE "-Wl,-z,max-page-size=16384")
