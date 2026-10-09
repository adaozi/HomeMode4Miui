# 架构说明 —— 三类代码的完整边界

本文是 [`README.md`](../README.md) 的展开：把模块全部源码按 **① Hook 原版桌面 / ② 实现新桌面 / ③ 获取数据** 三类，
逐文件、逐调用链讲清楚。目标读者是要读代码、改代码或移植代码的人。

> 本仓库已清除桌面 UI 实现（Niagara 移植版，≤ v2.22.0，git 历史可考）。
> ②③ 中与 UI 相关的数据获取实现一并移除，其**逆向知识**以"知识存档"形式保留在 §3。

---

## 0. 进程模型（先理解这个，后面全部成立）

```
┌──────────────────────────────────────────────────────────────┐
│ 桌面进程 com.miui.home（LSPosed 注入）                       │
│                                                              │
│   MainHook ──hook──> 宿主类（LauncherModeController /        │
│                              Launcher / SuperDraglayer …）    │
│                                                              │
│        │ 反射/直接持有                                        │
│        ▼                                                     │
│   HomeController ──挂载──> HomeView（home/ 接缝）             │
│        │                      ↑                              │
│        │                      └── HomeViewFactory.create()   │
│        │                          = 你的桌面 UI（待移植）     │
│        ▼                                                     │
│   ModePrefs 读写宿主 SP（launcher_sharedpreference）          │
└──────────────────────────────────────────────────────────────┘
        ▲ 广播 + 宿主 SharedPreferences（跨进程通信通道）
        │
┌───────┴──────────────────────────────────────────────────────┐
│ 模块进程 com.homemode4miui（MainActivity，普通 Android App）   │
└──────────────────────────────────────────────────────────────┘
```

**要点**：底座代码**全部住在桌面进程里**（`MainActivity` 除外）。区别不在"跑在哪"，而在
**"它碰不碰宿主、碰的是行为还是界面"**：

- ① 碰宿主**行为** → 必须 hook，是脆弱的、需要随版本维护的；
- ② 碰**自己的界面** → 纯自绘，是最稳定、最可移植的部分（现在只剩接口，实现归你）；
- ③ 碰**数据源** → 读框架 API 与 SP，边界清晰，风险集中在权限与私有 Provider 契约。

---

## ① Hook 原版桌面

### 1.1 `MainHook.java` —— 总入口

实现 `IXposedHookLoadPackage`，在 `handleLoadPackage` 里只对 `com.miui.home` 生效。

**设计要点：单 hook 隔离降级。**

```java
tryHook("1.getAllModes",            () -> hookGetAllModes());
tryHook("2.modeAdjudication",       () -> hookModeAdjudication(cl));
tryHook("3.Launcher.setupViews",    () -> hookLauncher(cl, "setupViews", mLauncherUiSync));
tryHook("4.Launcher.onLauncherModeChanged", …);
tryHook("5.Launcher.onResume",      …);
tryHook("9.SuperDraglayer.findActiveController (手势隔离)", () -> GestureIsolator.inject(cl));
tryHook("10.Launcher.onMinusOneScreenViewScroll (负一屏关闭同步)", …);
tryHook("11.Launcher.onNewIntent (Home 键回首页态)", …);
tryHook("6-8.DesktopModeInjector",  () -> DesktopModeInjector.inject(cl)); // 实验性
```

每个 hook 独立 `try/catch`：**一处失败只降级该功能并打日志，绝不放弃其余 hook**。日志逐条打印
`hook installed: X` / `hook FAILED (degraded): X -> …`，这是移植时最重要的诊断信息。

**模式裁决的三级自适应锚点**（`hookModeAdjudication`）——这是应对版本漂移的核心机制：

| 级别 | 扫描条件                                               | 处理方式                         |
| -- | -------------------------------------------------- | ---------------------------- |
| a  | 静态 + 返回 `LauncherMode` + 名字含 `loadcurrentlauncher` | hook 返回值，追加自定义模式分支       |
| b  | 静态 + 名字含 `resetcache`                              | hook 后改写静态字段 `*CurrentMode*` |
| c  | 静态 + 无参 + 返回 `LauncherMode` + 名字含 `getcurrentmode` | hook 访问器返回值                  |

三级全落空 → `dumpMembers()` 把宿主控制器所有方法/字段打进日志，供下一轮**精准补锚点**。

**模式状态查询**：`isCustomModeActive()` 优先读宿主 `getCurrentMode()`（极简优先级语义保持原生），
锚点缺失时回退直读模块写入宿主 SP 的标志位。

### 1.2 `hook/HomeController.java` —— 视图挂载与原生表面隐藏

**挂载** `attach(launcher)`：

- 容器解析**降级链**：`getDragLayer()` → `mDragLayer` 字段 → `DecorView` 兜底（逐级留痕）；
- 经 `HomeViewFactory.create(launcher)` 取根视图，以 MATCH_PARENT 加入容器；
- **隐藏原生表面**（关键设计，见下）；
- `view.onHomeAttached(launcher)` 首次生命周期回调 + 注册应用增删广播；
- **600ms 延迟补扫**：捕获原生在 `setupViews` 之后异步重建/补加的视图。

**隐藏原生表面的版本无关实现**（`hideLauncherSurfaces`）：

```java
// 遍历容器，把除挂载视图之外的所有子 View 记账后 GONE
for (int i = container.getChildCount() - 1; i >= 0; i--) {
    View child = container.getChildAt(i);
    if (child == sView || child == null) continue;
    if (child.getVisibility() == View.GONE) continue;
    setGoneRecorded(child);   // 记账原可见性 → 退出时逐一还原
    hidden++;
}
```

> **为什么不用 `getWorkspace()` / `getHotSeats()`？** 真机联调发现这些方法名在新版宿主上**全部变了**。
> 改为"容器子 View 记账"后彻底版本无关——只要挂载视图是容器的直接子级，其余一律隐藏。
> 代价：需要记账表 `sOriginalVisibility` 保证退出模式时精确还原。

**负一屏**：`openMinusOneScreen()` 复刻宿主"页面指示条点击"的完整序列
（`isUseMiuiMinusScreen` → `getMinusOneScreenView` → `addMinusOneView` → 显示祖先链 → `openMinusOne()`），
供你的自定义 UI 主动调用。关闭同步通过 hook `Launcher.onMinusOneScreenViewScroll`，
带 800ms 动画宽限期防误判。

### 1.3 `hook/GestureIsolator.java` —— 手势隔离闸门

**逆向依据**：宿主 `SuperDraglayer.findActiveController(MotionEvent)` 是**唯一手势分配点**。
它按序评估一批 `TouchController`（`AllAppsSwipeController` / `GlobalSearchSwipeController` /
`StatusBarSwipeController` / `Overlay*Controller` …），命中者认领**整条手势流**（子 View 收 `ACTION_CANCEL`）。

**致命点**：这些控制器的认领逻辑**不看** `LauncherMode.supportSearch/supportFeed/getPullDownGesture`——
即使我们把动作 override 成 `no_action`，手势流仍会被中途抢走 → 自定义视图滚动被打断 = "手势互相捣乱"。

**修复**：

```java
if (!MainHook.isCustomModeActive()) return;         // 模式关闭：完全透明
if (hostFloatingViewOpen(param.thisObject)) return; // 宿主浮层在场：放行原生
param.setResult(false);                             // 闸门落下：无任何宿主控制器认领
```

**防御边界**：回调异常一律降级为"不认领"（触摸链路绝不卡死）。

### 1.4 `hook/DesktopModeInjector.java` —— 【实验性】设置页第四选项

hook `DesktopModePreference` 的 3 个方法：`onBindViewHolder` after（追加 `VisualCheckBox`），
`onCheckedChanged` before（拦截/索引重映射）、`calModeVisualWidth` after（四等分宽度）。
miuix 内部 API 未做静态逆向，**全程反射 + try/catch**，失败只影响设置页入口。

### 1.5 `app/src/host/java/**` —— 宿主类编译期桩

**这是 ① 中最容易被误解的部分：它不是 hook，它是"编译期契约"。**

| 桩文件                                     | 内容                                                    |
| --------------------------------------- | ----------------------------------------------------- |
| `allapps/LauncherMode.java`             | 4 个抽象方法 + 12 个被覆写方法 + 若干被引用方法签名，方法体 `throw stub()`    |
| `allapps/LauncherModeController.java`   | `public static` 成员（如 `ELDERLYMAN`、`getCurrentMode()`） |
| `allapps/LauncherModeDatabasePath.java` | 字段 `modeDatabasePath`                                 |
| `ShortcutInfo.java` / `DragObject.java` | 仅作为方法参数类型存在                                           |

**运行期安全性**：LSPosed 以宿主类加载器为父加载器、父优先委派，桩在宿主进程内被真实类**遮蔽**，
永不执行；`LauncherModeController.ELDERLYMAN` 实际读宿主字段，`getCurrentMode()` 实际执行宿主方法。

**两条刻意留白**（README 与代码注释均标注）：

- `loadCurrentLauncherMode` / `onLauncherModeChanged` 是宿主 `private static`，桩中**故意不声明**，
  避免跨类加载器直接 invoke 触发 `IllegalAccessError`（只能经 Xposed 反射访问）；
- 方法体 `throw RuntimeException("HOST STUB")`，误执行会**显式失败**而非静默错行为。

> **移植时这是唯一需要手改的编译期文件。** 详见 [`PORTING.md`](PORTING.md)。

---

## ② 实现新桌面（接口已备，UI 待移植）

### 2.1 `mode/CustomLauncherMode.java` —— 唯一"进入宿主"的类

```java
public final class CustomLauncherMode extends LauncherMode {
    public static final String MODE_NAME = "custom";
    @Override public String getModeName() { return MODE_NAME; }
    @Override public boolean isScreenCellsLocked() { return true; }
    @Override public boolean supportSearch() { return false; }
    @Override public String getPullDownGesture(Context c) { return "no_action"; }
    // …共 16 个 override
}
```

**惰性单例**（`Holder` 静态内部类）：首次调用才触发宿主 `LauncherMode` 的 `<clinit>`，确保宿主已完全初始化。

**关键收益 —— 布局数据库隔离是"免费"的**：继承的 `getLauncherDatabaseDir()` 按 `getModeName()` 拼路径，
自动得到 `databases/custom/launcher.db`，与原生三模式完全同机制。

**override 语义模板**：这 16 个 override 是"自定义 UI 全面接管桌面"的实证语义
（锁定网格、关搜索条、关看点链路、关拖拽、手势让位），源自 Niagara 移植期的真机验证。
移植新桌面时可按需调整，但改动前建议先在真机上验证宿主行为。

### 2.2 `home/` —— UI 与底座的唯一接缝

| 文件                          | 职责                                                                       |
| --------------------------- | ------------------------------------------------------------------------ |
| `HomeView.java`             | 抽象根视图基类（`extends FrameLayout`），持有 `mLauncher`（宿主 Activity）                |
| `HomeViewFactory.java`      | 工厂：`create()` 返回根视图。**移植时唯一必须修改的文件**                                      |
| `PlaceholderHomeView.java`  | 占位实现：中央一行提示文字，验证底座链路；移植时删除                                                |

**生命周期**（与原生模式视图同机制，由 `HomeController` 驱动）：

```
构造（每次 attach 新建，无单例）
 → onHomeAttached(launcher)        ← 挂载完成：搭 UI / 装载数据（含复用路径也会再调一次）
 → [onPackagesChanged()]*          ← 应用增删/变更广播聚合
 → [onHomeIntent()]*               ← Home 键回桌面（Launcher.onNewIntent 主意图）
 → onHomeDetached()                ← 卸载：切回原生模式 / Launcher 重建
```

**底座已经提供、UI 不用再写的**：

- 原生表面隐藏（记账式 GONE）与退出还原；
- DragLayer 挂载/卸载（容器三级降级解析）；
- 手势隔离（宿主控制器永不认领手势流）；
- 负一屏打开/关闭同步（`HomeController.openMinusOneScreen()` 供 UI 主动调用）；
- 模式注册/裁决/切换链、独立布局数据库、模块 App 开关广播。

### 2.3 其余基础设施

| 文件                  | 设计要点                                                                       |
| ------------------- | -------------------------------------------------------------------------- |
| `MainActivity.java` | 模块 App 设置界面。**运行在模块进程**，经广播 + 宿主 SP 与桌面通信（只含开关 + 手势选项）                      |
| `util/ModLog.java`  | 统一日志，**打全堆栈**（任何 UI/hook 链路必须同等兜底）                                          |
| `util/UiText.java`  | 去宿主文字阴影（MIUI 主题给 TextView 默认挂阴影 → 宿主 Context 创建的 UI 一律需要它）                  |

---

## ③ 获取数据

### 3.1 宿主 SP 桥 `ModePrefs.java`

| 项        | 值                                                                        |
| -------- | ------------------------------------------------------------------------ |
| 宿主 SP 文件 | `launcher_sharedpreference`（与原生 `is_all_apps_drawer_mode_enable` 同文件同机制） |
| 模式开关 key | `is_custom_mode_enable`                                                  |
| 手势选项 key | `custom_feed_gesture_intercept`                                          |
| 模块 SP 文件 | `base_prefs`（仅存模块 App 开关回显）                                              |

**为什么写宿主 SP**：模块运行在宿主进程内，读写无跨进程/权限问题；`switch` 后重启桌面状态天然保持。

**跨进程通信通道**（模块 App → 桌面）：

```
MainActivity 改设置
   → sendBroadcast(ACTION_TOGGLE_MODE / ACTION_SET_FEED_INTERCEPT)
   → MainHook.registerToggleReceiver 收到
   → ModePrefs.setModeEnable / setFeedGestureIntercept 写宿主 SP
   → HomeController.sync(launcher, on)  兜底直刷
```

副作用是**兜底直刷**：不依赖原生 `onLauncherModeChanged` 事件链，宿主重构时仍可切换。

### 3.2 知识存档：已随 UI 清除的数据获取实现（移植时从 git 历史找回或重写）

以下实现曾在 Niagara 版（≤ v2.22.0）验证可用，代码已删，**逆向结论保留**：

| 能力     | 获取方式（实证可用）                                                                                                                          |
| ------ | ----------------------------------------------------------------------------------------------------------------------------------- |
| 应用列表   | `LauncherApps.getProfiles()` 遍历主用户 + 工作资料；工作资料图标加蓝点角标                                                                                |
| 隐藏应用   | MIUI `HideAppProvider`：`content://com.miui.home.app.hide`（列 `package_name`/`is_hide_app`/`user_hide_app`；schema 不识别返回 null 防误杀、>400 条保险丝）+ 进程内反射 `HideAppList.Companion.getHideAppList()` 兜底 |
| 使用记录   | `UsageStatsManager.queryUsageStats(INTERVAL_BEST, now-28d, now)`；`AppOpsManager.checkOpNoThrow(OPSTR_GET_USAGE_STATS,…)` 先探测权限，未授权不请求 |
| 收藏持久化  | 模块 SP + JSON 数组保序（`StringSet` 无序不可用于重排）；不进宿主数据库                                                                                      |
| 小部件    | **独立 hostId 自建 `AppWidgetHost`（0x4E49）**，不借宿主 host：免确认框绑定、互不干扰、零额外 hook 点                                                            |
| 拼音分组   | `android.icu.text.Transliterator("Han-Latin; Latin-ASCII")`（minSdk 33 可用），首字母 → 分区键（A–Z / `#` / `。`），失败永久降级                          |

---

## 附录：完整调用链

### 冷启动 → 进入自定义模式

```
LSPosed 加载模块 into com.miui.home
 └─ MainHook.handleLoadPackage
     ├─ hookGetAllModes()                     → 注册第四模式
     ├─ hookModeAdjudication()                → 三级锚点（记 sAdjudicationInstalled）
     ├─ hook Launcher.setupViews()            → after: mLauncherUiSync
     ├─ hook Launcher.onLauncherModeChanged() → after: mLauncherUiSync
     ├─ hook Launcher.onResume()              → after: sync + notifyPackagesChanged
     ├─ GestureIsolator.inject()              → 手势闸门
     ├─ installMinusCloseSync() / installHomeIntentSync()
     └─ DesktopModeInjector.inject()          → 设置页第四选项
```

### 用户从模块 App 开启自定义模式

```
MainActivity switch ON
 → broadcast ACTION_TOGGLE_MODE(extra ON=true)
 → MainHook 接收 → ModePrefs.setModeEnable(ctx, true)
     ├─ 写宿主 SP: is_custom_mode_enable = true
     └─ callNativeOnLauncherModeChanged(ctx)   [反射 private static]
         → 宿主 enableAndResetCache()
             → 宿主 loadCurrentLauncherMode()  ← 命中我们的裁决 hook，返回 CustomLauncherMode
         → 宿主 Launcher.onLauncherModeChanged()  ← 命中我们的 hook
             → mLauncherUiSync
                 ├─ registerToggleReceiver(launcher)
                 └─ HomeController.sync(launcher, on=true)
                     ├─ resolveContainer()           [getDragLayer → mDragLayer → DecorView]
                     ├─ HomeViewFactory.create(launcher) → addView(container)
                     ├─ hideLauncherSurfaces(container)   [记账式 GONE]
                     ├─ view.onHomeAttached(launcher)     [你的 UI 初始化点]
                     ├─ registerPkgReceiver()             [应用增删 → onPackagesChanged]
                     └─ postDelayed(600ms) 延迟补扫
```

### 触摸事件（手势隔离生效时）

```
用户手指下落
 → SuperDraglayer.onInterceptTouchEvent
     → findActiveController(ev)          ← GestureIsolator before-hook
         ├─ isCustomModeActive() ? false : return;     [模式关 → 透明]
         ├─ hostFloatingViewOpen() ? return;           [宿主浮层 → 放行]
         └─ setResult(false)                            [闸门落下]
     → 无控制器认领 → 事件继续下传到子 View → 你的 HomeView 自行处理全部手势
```
