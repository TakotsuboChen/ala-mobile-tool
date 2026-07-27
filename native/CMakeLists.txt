cmake_minimum_required(VERSION 3.22.1)
project("ala-core")

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Only build for the architectures the game ships
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Only arm64-v8a is supported")
endif()

include_directories(src)

# ShadowHook is provided via prefab (com.bytedance.android:shadowhook).
find_package(shadowhook REQUIRED CONFIG)

add_library(
    ala-core
    SHARED
    src/ala_core.c
    src/il2cpp_hooks.c
    src/pedal_hook.c
    src/drs_hook.c
)

find_library(log-lib log)

target_link_libraries(ala-core ${log-lib} shadowhook::shadowhook)
