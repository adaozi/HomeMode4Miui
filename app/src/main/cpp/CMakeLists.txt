# Route B PoC（phase 1）：观察型 native 入口。
# 仅编译观察探针；业务 hook（madvise guard / 指纹 resolver / trampoline）在后续批次接入。
cmake_minimum_required(VERSION 3.22.1)
project(homemode4miui_native CXX)

add_library(homemode4miui_native SHARED
        homemode4miui_native.cpp
        hook_guard.cpp)

target_compile_options(homemode4miui_native PRIVATE
        -O2
        -fvisibility=hidden)
# HyperOS 4 设备为 16KB page：ELF LOAD 段必须 16KB 对齐（对齐 WOMMO/SwipeGate CI 检查项）
target_link_options(homemode4miui_native PRIVATE
        "-Wl,-z,max-page-size=16384")
target_link_libraries(homemode4miui_native PRIVATE log)
