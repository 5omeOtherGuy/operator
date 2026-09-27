# ===========================================================================
# operator copy of the upstream CMakeLists (FOUNDATION §4.2 C2, OQ-21 default (a) "upstream lib,
# extended").
#
# upstream path: third_party/llama.cpp/examples/llama.android/lib/src/main/cpp/CMakeLists.txt
# upstream commit: 95887577ab5fead779581a7030a83c7752ff3234 (b11205, 2026-09-26)
#
# Deltas against that file, and nothing else:
#   P1   GGML_OPENMP is forced OFF (upstream sets it ON for arm64, which a Gradle -D cannot
#        override; §4.2, ADR-0005) so native workers can be pinned to cores (§4.7).
#   §4.2 the build flags are fixed in this file: BUILD_SHARED_LIBS=ON, GGML_BACKEND_DL=ON,
#        GGML_CPU_ALL_VARIANTS=ON, GGML_NATIVE=OFF, GGML_OPENMP=OFF, GGML_LLAMAFILE=OFF,
#        LLAMA_BUILD_COMMON=ON, LLAMA_OPENSSL=OFF, no curl. GGML_CPU_KLEIDIAI keeps upstream's
#        per-ABI choice (ON for arm64, OFF for x86_64).
#   path LLAMA_SRC comes from the Gradle argument -DOPERATOR_LLAMA_SRC (the pinned submodule) with a
#        relative fallback; ai_chat.cpp and logging.h stay in place in the submodule, because they are
#        unchanged and only changed upstream files are copied into llm/ (C2).
#   P2   operator_jni.cpp is added to the target (S1 fills it with the handle-based entry points).
#
# CI diffs this file against the submodule on every submodule bump (§12 `size` job, later slice).
# ===========================================================================
cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# operator build flags (FOUNDATION §4.2). Set before add_subdirectory so ggml and llama.cpp see them.
# --------------------------------------------------------------------------
set(BUILD_SHARED_LIBS       ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL         ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS   ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE             OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP             OFF CACHE BOOL "" FORCE) # P1
set(GGML_LLAMAFILE          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON      ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL           OFF CACHE BOOL "" FORCE)
# no curl: the option is gone upstream at this commit, so nothing is set for it.

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

if(DEFINED OPERATOR_LLAMA_SRC)
    set(LLAMA_SRC "${OPERATOR_LLAMA_SRC}")
else()
    set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
endif()

# The upstream JNI source stays in the submodule (unchanged); only this file is a copy (C2).
set(LLAMA_ANDROID_LIB_SRC "${LLAMA_SRC}/examples/llama.android/lib/src/main/cpp")

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ${LLAMA_ANDROID_LIB_SRC}/ai_chat.cpp
        operator_jni.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${LLAMA_ANDROID_LIB_SRC})

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
