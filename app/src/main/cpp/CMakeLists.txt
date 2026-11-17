cmake_minimum_required(VERSION 3.22.1)
project(pixelforge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(pixelforge SHARED
        jni_bridge.cpp
        bench_cpu.cpp
        bench_memory.cpp
        bench_storage.cpp
        vulkan_lab.cpp)

# Vulkan is loaded dynamically (dlopen) so the library still loads on devices without it.
target_compile_definitions(pixelforge PRIVATE VK_NO_PROTOTYPES=1)
target_compile_options(pixelforge PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -O3 -fvisibility=hidden)
target_link_libraries(pixelforge PRIVATE log dl android)

# 16 KB page-size compatibility (required for Android 15+ devices with 16 KB pages).
target_link_options(pixelforge PRIVATE "-Wl,-z,max-page-size=16384")
