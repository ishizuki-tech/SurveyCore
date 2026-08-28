cmake_minimum_required(VERSION 3.22.1)

project(
        surveycore_whisper_native
        LANGUAGES C CXX
)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Keep symbols from whisper.cpp's bundled GGML private to libsurvey_whisper.so.
# This is important because SurveyCore also embeds llama.cpp, which has its own
# independently pinned GGML copy.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

get_filename_component(
        WHISPER_CPP_DIR
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp"
        ABSOLUTE
)

if (NOT EXISTS "${WHISPER_CPP_DIR}/include/whisper.h")
    message(
            FATAL_ERROR
            "whisper.cpp v1.9.3 was not found at: ${WHISPER_CPP_DIR}. "
            "Run scripts/setup_whisper_cpp.sh from the SurveyCore root first."
    )
endif()

message(
        STATUS
        "SurveyCore whisper.cpp directory: ${WHISPER_CPP_DIR}"
)

# ------------------------------------------------------------
# whisper.cpp configuration
# ------------------------------------------------------------

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# This integration is pinned to the official v1.9.3 release tag.
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(WHISPER_COREML OFF CACHE BOOL "" FORCE)
set(WHISPER_OPENVINO OFF CACHE BOOL "" FORCE)

# Android baseline: CPU-only first. Avoid host-native feature detection while
# cross-compiling on macOS.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

# Keep the native Debug smoke test representative of real inference speed.
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(
            -O3
    )
endif()

add_subdirectory(
        "${WHISPER_CPP_DIR}"
        "${CMAKE_BINARY_DIR}/whisper.cpp"
        EXCLUDE_FROM_ALL
)

add_library(
        survey_whisper
        SHARED
        whisper_jni.cpp
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
        survey_whisper
        PRIVATE
        whisper
        ${log_lib}
)

# Do not export symbols contributed by static whisper.cpp/GGML archives.
# JNIEXPORT keeps the JNI entry points visible.
target_link_options(
        survey_whisper
        PRIVATE
        "-Wl,--exclude-libs,ALL"
)
