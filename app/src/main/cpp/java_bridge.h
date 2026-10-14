// Phase 5: native → Java bootstrap.
//
// 装机实锤（v2.9.78 轮）：Zygisk Next HyOS Runtime 下，OS4 launcher 主进程是
// native-only 的 —— legacy 与 modern 的 Java 派发都不进。但我们的 native 模块
// 本身就在进程里（native_init 已验证），而进程的 ART 虚拟机活着（Activity 壳
// 由 rustruntime JAR 提供）。
//
// 因此 Java 触达 = 自举：native 线程 AttachCurrentThread → 反射拿
// ActivityThread.currentApplication()（Context）→ 用 PathClassLoader 加载
// 模块 APK 自己的 dex（Os4Bootstrap）→ Java 侧接管（lifecycle 回调 + UI 挂载）。
// 不依赖任何容器 Java 派发。
#pragma once

#include "native_api.h"

namespace h4m {

// One-shot: spawn a detached thread that bootstraps the module's Java code.
// Retries currentApplication() (may be null while the process is still
// specializing). Safe to call from any load callback.
void StartJavaBridge(const NativeAPIEntries *entries);

}  // namespace h4m
