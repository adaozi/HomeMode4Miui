# HyperOS 4 桌面 native hook 可行性评估（路线 B 前置调研）

> 调研日期：2026-10-14。基于两个活跃开源项目的代码级精读，源码已克隆至
> `.workbuddy/re/os4-ref/`（SwipeGate + WOMMO，浅克隆）。
> 结论：**路线 B（OS4 桌面 native hook）工程可行，有两条已验证的参考实现**，
> 但需要把模块基础设施从 api-82 传统 Java hook 升级到 LSPosed Modern API 102
> + native_init。

## 1. 参考项目

| 项目 | 许可 | 目标库 | 克隆位置 |
|---|---|---|---|
| [HyperOS4SwipeGate](https://github.com/PzHown/HyperOS4SwipeGate) (PzHown) | MIT | `libapp_launcher.so`（Rust 核心） | `.workbuddy/re/os4-ref/HyperOS4SwipeGate` |
| [WOMMO](https://github.com/YifePlayte/WOMMO) (YifePlayte) | GPL-3.0 | `libapp.so`（Dart AOT UI） | `.workbuddy/re/os4-ref/WOMMO` |

两家已测试版本：Launcher 8.01.02.5459/5465/6174/6179（RELEASE-8.01.02.*），
Android 17 / API 37，arm64-v8a，LSPosed 2.2.0（Modern API 102），
Zygisk Next 1.5.0+（HyOS Runtime）必需。

## 2. 进程模型（两家的共识，逆向实锤）

```
/system_ext/bin/hyos_spawner（root spawner，cmdline 为 usap64）
        ↓ fork
com.miui.home（launcher 子进程）
        ├─ libapp.so           Dart AOT UI 层（无 dex，Java hook 不可达）
        └─ libapp_launcher.so  Rust 核心层
```

- native_init 在 **spawner 进程**里先执行（此时还没有 libapp.so）。
- 身份判定双通道：`/proc/self/exe == /system_ext/bin/hyos_spawner`（spawner 家族）
  + `/proc/self/cmdline == com.miui.home`（launcher 本体）。两个都要接受
  native_init，但**业务 hook 只在 launcher 进程装**。
- WOMMO 走 LSPosed 库加载回调：`native_init` 返回 `OnLibraryLoaded` 函数指针，
  每次 dlopen 回调里匹配 `libapp.so` 尾缀再装 hook；另做一次 dlopen(RTLD_NOLOAD)
  backfill 兜底热重载场景。
- SwipeGate 在 native_init 里直接 `dl_iterate_phdr` 扫已加载库 backfill，同样返回
  `onLibraryLoaded` 回调等 libapp_launcher.so 加载。
- **模块更新生效方式**：kill spawner（`readlink /proc/<pid>/exe` 找到
  hyos_spawner 的 pid，`kill -TERM`），launcher 自动重新 fork；如果日志缺失，
  `kill $(pidof com.miui.home)` 让 launcher 从已加载模块的 spawner 重新 fork。

## 3. WOMMO 的 Dart 层方案（打 libapp.so）

### 3.1 镜像校验（fail-closed 四连）

`wommo_native.cpp: AcquireLauncherImage`：
1. `dlsym(handle, "_kDartIsolateSnapshotInstructions")` 必须命中；
2. `dladdr` 回报的映射路径必须是 `/data/app/.../base.apk!/lib/arm64-v8a/libapp.so`
   或 `/product/priv-app/MiuiHome/.../MiuiHome.apk!/lib/arm64-v8a/libapp.so`；
3. `dart::AcquireImageAtBase` 用 load bias 通过 `dl_iterate_phdr` 再找一次同一
   ELF，把 PT_LOAD 段表钉死到已验证 handle 上；
4. 任何一环失败 → 整体放弃，保持原厂行为。

### 3.2 定位与补丁

- 定位：**指令指纹**（`PatternWord {value, mask}` 掩码扫描，R-X 段逐 32bit word
  匹配），池页操作数（`add x17, x27, #page` / `ldr [x17, #imm]`）用 wildcard
  mask，其余 exact。命中数 ≠ 1 → fail closed。
- 补丁：`dart::PatchCode`（dart_image.cpp:186）= **先登记保护区（见 3.3）→
  mprotect RWX → memcpy → `__builtin___clear_cache` → mprotect 恢复 RX →
  逐 word 读回验证**。
- 最小补丁原则：只 nop/ret 掉验证过的单个失败分支，不重写共享行为，不强改
  其他代码路径依赖的数据字段（例：不全局置 `isMIUIWidget` 来骗过拖拽门控）。

### 3.3 madvise(MADV_DONTNEED) 页面保护（必做，否则补丁静默回滚）

`lsposed_hook_backend.cpp`：
- HyperOS 内存清理会对桌面镜像发 `madvise(MADV_DONTNEED)`，APK-backed mapping
  的 CoW 代码页被丢弃后内核重读原始字节 → **所有补丁被无声还原**。
- 解法：用 LSPosed `hookFunc` inline hook libc `madvise` → `GuardedMadvise` 把
  已登记的 hook 页从 MADV_DONTNEED 区间里切出来（分段跳过，其余照常透传）。
- **时序硬约束**：`ProtectHookRange` 必须在写补丁**之前**调用（堵并发清理竞态），
  保护失败 → 拒绝打补丁。
- 上限 32 页（SwipeGate 用 128 页 + span 32 bytes 双页登记），spinlock + 原子
  状态机管理。

### 3.4 Dart AOT ABI 陷阱（最容易死人的地方）

- **x15 是 Dart frame/stack 指针**——绝不能用普通 C 函数直接替换 Dart 函数
  （C 编译器会 clobber x15，调用方栈帧直接损坏 → SIGSEGV/SIGBUS）。
- 正确姿势：`__attribute__((naked))` 裸汇编 trampoline，在真实 sp 上保存
  x0..x18、x30、d0..d7（`back_home_ratio_hook.cpp:156` 有参考实现），调 C
  recorder 后全量恢复，再 `br` 到 LSPosed `original`。
- 压缩指针：`low32 | (x28 << 32)` 重建；对象字段读偏移 `offset + 7`（tagged
  pointer）；bool 单例在 x22+0x20（true）/ x22+0x30（false），条件判断常见
  `tbnz/tbz wN, #4`。
- **x27 = 对象池指针，x22 = null 对象，x28 高 32 位**——这些是 Dart AOT 固定
  寄存器约定，wrapper 里谁都不能碰。

### 3.5 RE 金矿

- `libapp.so` 的 **`.gnu_debugdata` 节 = XZ 压缩 ELF，内含完整 Dart 符号表**。
  解压后 `readelf -sW` 直接拿函数名+offset。这解决我们此前"936 条目魔数扫描
  0 命中"的符号缺失问题——**Dart 符号不是不存在，是藏在 debugdata 里**。
- maml 主题部件走 `librust_maml_sdk.so` 导出符号：命令 `send_command(id, ...)`、
  变量 `put_variable_number(id, ...)`。该库懒加载，不能在 libapp.so 回调里
  dlopen(RTLD_NOLOAD)（会失败），要挂独立的 per-library installer。
- jadx 对 Flutter 桌面无用（无 dex），但对旧桌面 APK 和模块自身 Java 侧仍是对的
  工具。Flutter 桌面无 uiautomator 层级（dump 返回 null root），实机定位用
  `input swipe` + 读 `mCurrentFocus`。

## 4. SwipeGate 的 Rust 层方案（打 libapp_launcher.so）

### 4.1 双重定位：exact 指纹 + 语义 resolver 交叉验证

- exact 指纹：`gesture-frame-v1/v2`（16~24 字节 AArch64 序列 + 全 FF mask），
  是"已验证实现的代码特征"，不是版本白名单。
- 语义 resolver：解析 mapped ELF 的 PT_DYNAMIC → DT_JMPREL/DT_PLTREL 拿 PLT
  imports → 定位 MotionEvent 导入 API（getActionMasked/getActionIndex/getRawX/
  getRawY）→ 在 R-X 段扫描引用这些 import 的调用图 → 验证 AArch64 frame family
  （寄存器保存族）→ 唯一语义候选。
- 决策表：exact 唯一 → exact-authoritative；exact 多候选 → 用
  `BackGestureUtils::convert_offset` 行为链逐个消歧，仅一个通过才收；
  semantic/exact 地址冲突或 0 候选 → **fail closed**。
- 二级目标从一级目标派生：从已确认的 `on_swipe_process` caller 区（0x900 字节）
  扫直接 `BL` + `fmov s1, #20.0`（0x1e269001）+ 函数体结构（s0→s8 保存、负值
  guard、`mov w8, #110` = 0x52a85b88）反推 `convert_offset`，≥3 个 caller 佐证。

### 4.2 受保护 inline hook（先守卫后 hook）

`hook_page_guard.cpp: swipegate_install_protected_inline_hook`：
- **hook 前置条件是 page guard 就绪**，guard 没装上 → `-EAGAIN` 拒绝 hook。
- guard 实现：直接改写 `libhyper_os_flutter.so`（HyperRT runtime）live GOT 上的
  `madvise` 槽位（PT_DYNAMIC 定位 + 槽位 patch，免额外 dlopen），窄策略只拦
  HyperRT 的 madvise，只豁免含自己 inline hook 的页（目标地址 + span 32B 双页
  登记）。
- 主 hook 安装后立刻读回 16 字节 probe head 验证：**hookFunc 返回成功但入口没
  变 = 判定失败**（防 hook 函数假成功）。

### 4.3 watchdog 自修复

- 500ms 周期线程：读回 hook 入口 probe head → 与 `gInstalledPatchHead`/
  `gExpectedOriginalHead` 比对：
  - 仍是补丁字节 → HEALTHY；
  - 恢复成原始字节（被 madvise/其他机制还原）→ 等活跃调用退出（120ms 上限）→
    `unhook_func` + 重新 installFreshHook（1.5s 冷却）；
  - 既非原始也非自家补丁 → **foreign patch，拒绝覆盖**；
  - 库 mapping 变了（桌面更新）→ 清状态重新扫描。
- 附加 resolver（haptic callsite、convert_offset、break-open）未就绪时每 5s 重试。

### 4.4 AArch64 ABI-transparent wrapper

`swipe_hook_entry.S`：手工汇编，保存 x0..x8、q0..q7、x30（0x110 字节栈帧），
只读取 w1(readyFinish)/w2(side)/s0(距离)，调 gate helper 后全量恢复再
`adrp x17` 跳 LSPosed original。好处：不依赖 Point 参数是指针还是拆分浮点的
C++ prototype 差异，ABI 兼容性靠寄存器约定而非函数签名。

### 4.5 控制链路（免 root）

- App → XposedService RemotePreferences → **SystemUI 里的桥模块** →
  小米现成 `com.android.systemui.fsgesture` 通道 → Launcher native。
- 所以 SwipeGate 的 scope 必须同时勾 com.miui.home + com.android.systemui
  （SystemUI 不是可选作用域）。
- 日志兜底：logd 会过滤 launcher 进程的 INFO/DEBUG，native 端同时写文件
  `/data/user_de/0/com.miui.home/cache/*.log`（WOMMO 只用 WARN/ERROR + logcat）。

## 5. 对 HomeMode4Miui 路线 B 的落地评估

### 5.1 需要的基础设施升级

| 项 | 现状 | 路线 B 要求 |
|---|---|---|
| LSPosed API | api-82（传统 XposedBridge） | **Modern API 102**（native_init + XposedService） |
| 容器 | 普通 LSPosed | LSPosed 2.2.0+ / Zygisk Next 1.5.0+（HyOS Runtime） |
| 入口 | Java MainHook（dex） | 新增 `META-INF/xposed/native_init.list` + native .so 入口 |
| 双家族共存 | — | Java hook 家族（OS2/OS3）与 native 家族（OS4）互相独立，MainHook 里 os4Skip() 逻辑保留为 gate1 |

两家的 xposed 元数据可直接参考：WOMMO `native_init.list` 填
`libwommo_native.so`；SwipeGate 填 `libhyperos4swipegate.so`，java_init.list
同时声明 Java 入口（ModuleMain / NativeErrorReporter / SystemUiBridgeModule）。

### 5.2 推荐技术栈（融合两家长处）

1. **入口层**照抄 WOMMO：native_init 双身份判定 + OnLibraryLoaded 回调 +
   RTLD_NOLOAD backfill，代码最干净（235 行全读完了，结构值得直接借鉴，
   注意 WOMMO 是 GPL-3.0——借鉴思想可以，抄代码需要我们的模块同样 GPL-3.0
   开源兼容，本项目已是 GPL-3.0，无冲突）。
2. **定位层**用 SwipeGate 的 exact 指纹 + 行为链消歧 + fail closed；Rust 层
   （libapp_launcher.so）比 Dart 层更适合起步——行为逻辑（手势/状态/门控）
   在 Rust 核心里，且有 `.gnu_debugdata` 之外的正常符号表（crate__api__* 导出
   我们已确认过）。
3. **补丁层**：WOMMO 的 PatchCode（mprotect + icache + 读回验证）适合打
   libapp.so 的指令补丁；libapp_launcher.so 走 LSPosed hookFunc inline hook
   + SwipeGate 式 probe 读回。
4. **madvise guard** 是硬前提，WOMMO 全局拦 libc madvise（实现简单）vs
   SwipeGate live-GOT 窄拦截（更精准、不碰 libc）——起步建议 WOMMO 方案，
   32 页表够用。
5. **Dart 层如果要动**：x15/x27/x28 ABI 约束 + naked asm trampoline 是
   不可绕过的门槛，WOMMO 的 back_home_ratio_hook.cpp 是唯一可参考的完整实现。

### 5.3 已知风险

- **崩溃爆炸半径大**：native 补丁错误直接 SIGSEGV/SIGBUS → launcher 崩溃循环 →
  safe mode（`com.miui.home:safe_mode`，libapp.so 不加载）。恢复 = `adb install -r`
  重装 MiuiHome.apk，**不能清数据**。每次 native 测试前必须留存已知良好的
  MiuiHome APK。
- fail-closed 纪律必须贯彻：0 候选/多候选/冲突 → 保持原厂行为，这是两家共同
  的生存底线（SwipeGate 曾有 backing-flag 实验导致 SIGBUS BUS_ADRALN，已废弃
  该路线）。
- Launcher 独立更新（8.0+ 常态化日常推送）会让 RVA 频繁漂移——指纹/语义定位
  是唯一可持续方案，任何硬编码 offset 都只能作为逆向笔记。
- 16KB page 对齐：CI 需检查 native 库 ELF LOAD alignment + APK zip alignment
  （SwipeGate 的 GitHub Actions 清单可参考）。

### 5.4 建议下一步（若启动路线 B）

1. 从设备提取的 OS4 桌面 APK 复核 `libapp_launcher.so`：确认
   `.gnu_debugdata` 是否同样存在（Rust 层符号我们已确认存在，但 debugdata
   能给 Dart 层开图）。
2. 最小 PoC：native_init 空入口（双身份判定 + 日志），实机验证 spawner →
   launcher fork 链上我们的 .so 能被加载，再谈 hook。
3. PoC 二：madvise guard 单独验证（hook libc madvise + 人为 MADV_DONTNEED
   自测页），确认补丁存活。
4. 之后再选第一个真实 hook 点（建议 Rust 层某个可观测函数，只读不改，验证
   trampoline 与寄存器保存正确性）。

## 6. 关键源码索引

```
os4-ref/WOMMO/app/src/main/cpp/
  wommo_native.cpp          native_init 入口 + 镜像校验（235 行，全读）
  lsposed_hook_backend.cpp  hookFunc 封装 + madvise guard（247 行，全读）
  dart_image.cpp            段表采集/指纹扫描/PatchCode（226 行，全读）
  hooks/back_home_ratio_hook.cpp   Dart naked asm trampoline 参考（712 行，抽查）
os4-ref/HyperOS4SwipeGate/
  native/src/main.cpp       native_init + 指纹 resolver + watchdog（1793 行，关键段已读）
  native/src/hook_page_guard.cpp   live-GOT madvise guard（422 行，关键段已读）
  native/src/swipe_hook_entry.S    AArch64 ABI-transparent wrapper（抽查）
  docs/TECHNICAL.md         全文精读（逆向目标/Hook 策略/安全约束）
  docs/SEMANTIC_RESOLVER.md 全文精读（语义解析决策表）
```
