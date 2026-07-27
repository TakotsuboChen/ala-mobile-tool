cmake_minimum_required(VERSION 3.22.1)
project("ala-core")

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Only build for the architectures the game ships
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Only arm64-v8a is supported")
endif()

include_directories(src)

# Optional: link ShadowHook if available.
# In CI/prebuilt workflows, place libshadowhook.so in native/libs/${ANDROID_ABI}/
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libshadowhook.so")
    add_library(shadowhook SHARED IMPORTED)
    set_target_properties(shadowhook PROPERTIES
        IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libshadowhook.so"
    )
    set(SHADOWHOOK_FOUND ON)
    add_definitions(-DHAS_SHADOWHOOK)
else()
    message(WARNING "ShadowHook not found at native/libs/${ANDROID_ABI}/libshadowhook.so; native hooks will be stubbed")
    set(SHADOWHOOK_FOUND OFF)
endif()

add_library(
    ala-core
    SHARED
    src/ala_core.c
    src/il2cpp_hooks.c
    src/pedal_hook.c
    src/drs_hook.c
)

find_library(log-lib log)

target_link_libraries(ala-core ${log-lib})

if(SHADOWHOOK_FOUND)
    target_link_libraries(ala-core shadowhook)
endif()
