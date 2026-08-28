cmake_minimum_required(VERSION 3.22.1)

project(
        surveycore_native
        LANGUAGES C CXX
)

# Keep the JNI layer on the same C++ standard used by llama.cpp.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Resolve the pinned llama.cpp submodule from the SurveyCore project root.
get_filename_component(
        LLAMA_CPP_DIR
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp"
        ABSOLUTE
)

if (NOT EXISTS "${LLAMA_CPP_DIR}/include/llama.h")
    message(
            FATAL_ERROR
            "llama.cpp was not found at: ${LLAMA_CPP_DIR}"
    )
endif()

message(
        STATUS
        "SurveyCore llama.cpp directory: ${LLAMA_CPP_DIR}"
)

# ------------------------------------------------------------
# llama.cpp configuration
# ------------------------------------------------------------
#
# SurveyCore embeds only the core inference library.
# Tests, command-line tools, examples, server components, and
# application binaries are unnecessary inside the Android APK.
#

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)

set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# Disable host-native CPU detection.
#
# Android binaries are cross-compiled on macOS, so the build must not infer
# CPU capabilities from the development machine.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# ------------------------------------------------------------
# Android ARM64 CPU optimization
# ------------------------------------------------------------
#
# The current performance target is Pixel 9a / Tensor G4.
#
# The device reports support for FP16 and I8MM, and this architecture setting
# matches the optimized ARM64 configuration used by upstream llama.cpp's
# Android build.
#
# IMPORTANT:
# This is currently a device-specific benchmark configuration. A production
# build intended for ARM64 devices that do not support I8MM must use a more
# portable baseline or runtime-selected CPU backend variants.
if (
        ANDROID
        AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a"
)
    set(
            GGML_CPU_ARM_ARCH
            "armv8.5-a+fp16+i8mm"
            CACHE STRING
            "ARM architecture used by the SurveyCore llama.cpp benchmark"
            FORCE
    )

    message(
            STATUS
            "SurveyCore llama.cpp ARM architecture: ${GGML_CPU_ARM_ARCH}"
    )
endif()

# OpenMP is intentionally disabled for the Android integration.
#
# llama.cpp manages its own worker threads, and avoiding OpenMP also keeps the
# native dependency surface smaller.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Disable the optional llamafile implementation.
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# Keep backend loading simple for the current integration.
#
# The CPU backend is linked directly into libsurvey_llama.so instead of
# packaging runtime-loadable backend modules.
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

# ------------------------------------------------------------
# Native Debug optimization
# ------------------------------------------------------------
#
# Android Studio currently runs the smoke-test application as a Debug build.
# Without an explicit optimization level, llama.cpp and GGML are compiled
# effectively at -O0, which makes inference dramatically slower and does not
# represent realistic runtime performance.
#
# Keep the Android/Kotlin application debuggable while compiling the native
# inference code with -O3.
#
# Release builds retain CMake/NDK's normal Release optimization settings.
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    message(
            STATUS
            "SurveyCore native Debug build: enabling -O3 for inference"
    )

    add_compile_options(
            -O3
    )
endif()

# ------------------------------------------------------------
# Build pinned llama.cpp
# ------------------------------------------------------------

add_subdirectory(
        "${LLAMA_CPP_DIR}"
        "${CMAKE_BINARY_DIR}/llama.cpp"
        EXCLUDE_FROM_ALL
)

# ------------------------------------------------------------
# SurveyCore JNI library
# ------------------------------------------------------------

add_library(
        survey_llama
        SHARED
        llama_jni.cpp
)

find_library(
        log_lib
        log
)

if (NOT log_lib)
    message(
            FATAL_ERROR
            "Android log library was not found."
    )
endif()

target_link_libraries(
        survey_llama
        PRIVATE
        llama
        ${log_lib}
)