# 代码审计报告 — 写了但没实现 / 无效功能（2026-10-16）

审计口径：全 app/src（181 个源文件）静态交叉引用，验证每条链路「声明 → 触发 → 执行 → 消费」的完整性。四个子系统并行审计 + 关键结论人工抽查实锤。置信度标注：★★★ 确定无效 / ★★ 高疑 / ★ 有注释自认或版本限定。

---

## 一、用户可感知的「开关无效」（★★★，优先修）

| # | 功能 | 断链位置 | 问题 |
|---|------|---------|------|
| 1 | 时钟点击打开自定义应用（clockAppKey） | GraceHomeViewModel.kt:820-824 | 设置页可选时钟应用并落 Room，但 `openClock()` 无条件发 `SHOW_ALARMS`，无 clockAppKey 分支；且 clockAppKey 不在 GraceHomeSettings 模型中，桌面读不到。GraceSettingsHostEngine.kt:20 头注自己登记了「待接」 |
| 2 | 媒体兜底应用（mediaAppKey） | 全仓无消费方 | MediaPlayerSettings 写入后零读取；闲置播放器点击走 `resumeLastPlayer()`，永不读该 key |
| 3 | 搜索总开关（SearchSettings.enabled） | 无读取方 | 搜索入口无条件可开，开关纯存储 |
| 4 | 联系人搜索（SearchSettings.contacts） | AppSearchScreen.kt 头注自认「P6 评估恢复」 | 联系人索引已删，但设置页保留完整开关 + READ_CONTACTS 权限申请 UI，全部白写 |
| 5 | 网络搜索 + 搜索引擎选择（internet/engine/custom） | SearchLauncher.internet()/internetUri() 零调用 | 引擎选择/自定义引擎只影响一个永不执行的函数；P2 裁剪删了 Internet 结果，设置页没同步裁 |
| 6 | 「搜索包含隐藏应用」（hiddenApps） | GraceLauncherRoute.kt:822 | 搜索页拿到的是未过滤全量列表，开关无论开/关隐藏应用照样出现 |
| 7 | 壁纸文字颜色 Light/Dark（WallpaperTextMode） | GraceHomeRoot.kt:106-107 | 桌面主题根硬编码 `Auto`，`LauncherPreferences.textMode` 在宿主桌面零读取；强制 Light/Dark 只在设置页预览生效 |
| 8 | 桌面触感开关（allowHapticFeedback） | GraceLauncherRoute.kt:690 | 硬编码 `= true`，设置项语义与实际行为不符 |
| 9 | 抽屉行内通知预览策略 | GraceLauncherRoute.kt:740-770 | `AppDrawerScreen(popupSettings=...)` 路由层漏传，恒用默认值 → 首页传了（Route:678），唯独抽屉没传，静默/展开策略无视用户配置 |
| 10 | 「设为默认启动器」按钮 | SettingsViewModel.kt:204 `requestDefaultHome = {}` | 3 处 UI 触发 + shim 面 1 处，点了无反应；桌面端明明有 `DefaultHome.requestIntent()` 现成执行端 |
| 11 | 私密空间「系统设置」入口 | SettingsViewModel.kt:207 `openPrivateSpaceSettings = {}` | 点击无反应；`PrivateSpaceProbe.openSettings()` 就是现成执行端，没接 |
| 12 | 「连接日历」按钮 | SettingsViewModel.kt:357 `requestCalendarAccess = {}` + scheduleStatus 永不更新 | 按钮无反应，且权限提示行因 scheduleStatus 恒为 PermissionRequired 无条件常驻 |
| 13 | 媒体/通知控制四个动作（设置页） | SettingsViewModel.kt:352-355 | `controlMedia/dismissMedia/dismissNotification/openNotification` 全为空 lambda（注释自认 P6），且这些字段在 LauncherActions 上本就零消费，双保险死 |

## 二、Private Space 私密空间 — 展示/编辑侧整体死亡（★★★）

「锁」的一侧活着（enabled→lock、passwordProtected→lock、PS_CLOSE、重命名）；「展示/编辑」侧整体断链：

- **编辑按钮永久禁用**：PrivateSpaceSettings.kt:77 要求 `user != null`，而快照重建时 user 恒为 null（PrivateSpaceWire 无 user 字段）→ `appOrder` 无任何可达写入路径
- **解锁/建档伪成功**：`requestPrivateSpace = { _, onResult -> onResult() }`（SettingsViewModel.kt:205）+ 宿主 `PS_REQUEST -> { false }`（GraceSettingsHostEngine.kt:179）
- **排序不落库**：`reorderPrivateApps = {}`（:208），拖完退出即丢
- **桌面无载体**：GraceLauncherRoute.kt:238 `privateSpaceState = remember { probe(...) }` 赋值后全文件零引用（死变量）；GraceHomeUiState 无 privateApps 字段，桌面不渲染、不加指示标、不做启动鉴权
- **display=List 档位无实现**：三档设置实际只有两档行为，List 与 Folder 完全同分支

## 三、桥层死代码（★★★）

| 项 | 位置 | 说明 |
|---|------|------|
| `lastKnownSettings` | GraceSettingsSnapshotStore.kt:28/49/73 | 只写不读；宿主引擎实际直读 Room |
| `iconPacksDecorating` | GraceHomeViewModel.kt:326-327 | 双重死：无生产者（永不置 true）+ 无消费者 |
| `Cmd.PS_REQUEST/PS_SETTINGS/PS_REORDER/PS_RESET_APPEARANCE` | GraceSettingsBridge.kt:69-73 | 接收分支写好了（两分支还自认 no-op），发送端从未实现 |
| `PrivateSpaceProbe.probe` 结果 + `openSettings()` | PrivateSpaceProbe.kt:25/41 | probe 结果断在赋值行；openSettings 零调用 |
| `PrivateSpaceController.unlock/prepareAppLaunch/finishAppLaunch/onPause/onResume/openSettings` | PrivateSpaceController.kt | 宿主引擎只接 4/10 个方法；连带 BiometricPrompt 全套成为不可达代码 |

## 四、Hook 层无效 / 隐患

| # | 项 | 位置 | 说明 | 置信度 |
|---|---|------|------|--------|
| 1 | handleLoadPackage 末尾 live 广播 | MainHook.java:207-217 | 该阶段 `currentApplication()` 恒 null，永不发送；真正生效的是 :141 Application.onCreate 锚点。自家 CODE_AUDIT_2026-10-13.md:131 已判死刑，至今未删 | ★★★ |
| 2 | GestureIsolator 负缓存 NPE | GestureIsolator.java:109 | `ConcurrentHashMap.put(name, null)` 必抛 NPE → 负缓存永远写不进去 → :103「负缓存命中」分支是死代码，注释宣称的 perf 收益为零（功能结果不受影响，hostFloatingViewOpen 兜底 false） | ★★★ |
| 3 | 陈旧注释 isSupportShowHomeFeed / DesktopModeInjector | MainHook.java:48 | 注释声称的逻辑全仓不存在 | ★★★ |
| 4 | 声明类 walk 无宿主边界 | HostCompat.java:195-207 | 父类链可走到 `android.app.Activity`；一旦宿主版本不再 override 生命周期方法，hook 会静默装到框架 Activity 上变全局 hook | ★★（结构性隐患） |
| 5 | OS4 skip 闸门已删、hook 逻辑全保留 | MainHook/HostCompat 全局 | os4Skip() 已随 revert 9f52967 删除，OS4 上各 hook 走 ClassNotFoundError → sFailedHooks（可见非静默，criticalHookFailed 拦截有效）；仅 HostCompat.java:46 OS3 判据无上界，OS4 会被误标 "OS3(6.x)"（label 错误） | ★（已知归档） |
| 6 | GracePinItemHook OS2 未验证 | GracePinItemHook.kt:37-39 | 仅 OS3 逆向实锤；OS2 缺类时记 sFailedHooks（可见） | ★ |
| 7 | 媒体状态死 UI 环 | MainActivity.java:63/386/654-663 | mMediaStatusRef 从未在 onCreate 赋值 → refreshMediaStatus 不可达（约 25 行） | ★★★ |
| 8 | HomeController.log() 缺 logcat 侧 | HomeController.java:321-323 | 只走 XposedBridge.log；LogExporter 的 logcat 兜底来源看不到宿主 sync/attached 日志 | ★★★ |
| 9 | CustomLauncherMode OS2 死覆写 | CustomLauncherMode.java:79/128/142 | OS2 上三个方法注定不被宿主调用——**有意版本路由，非遗漏** | ★（登记备查） |

## 五、零调用死代码（★★★，可直接删）

- `Modifier.graceReveal()`（GraceButtonTransition.kt:134）— 连定义文件内部都不用
- `rememberLauncherHaptics()`（LauncherHaptics.kt）— **整文件即此一个符号**，头注「P0 暂未消费，随 theme 全量引入」——theme 已落地仍未接线
- `WeatherAttribution()` + `weatherCredits`（WeatherComponents.kt:225/237）— 双死
- `MediaAccess.settingsIntent()`（MediaAccess.kt:17）— 授权引导走的是 SettingsViewModel 自建路径
- `LauncherUiState.recentlyInstalledFolder()`（LauncherUiState.kt:133）
- `GraceRootActions.lastRootResult`（GraceRootActions.kt:28）— 只写不读的诊断残留
- `SearchLauncher.contact()/internetUri()/internet()`（SearchLauncher.kt:12/22/29）
- `GracePopupActions.launchAppAt`（恒默认 null，零消费）；`cycleClockStyle`（**死接线**：Route:556 接了 viewModel::cycleClockLayout，但消费侧 HomeSheets 零引用）
- `HomeView.onHomeIntent()` 无参重载（HomeView.java:54-55）— 为第三方移植 UI 预留的无人走路径
- **LauncherActions 21 个零消费字段**（screenTime/importItemIcon/showShortcutInAppList/categorize/copyPackageName/storePage/newEvent/openEvent/refreshAgenda/themedIcons/reorderFavorites/launchAppAt/launchSearchApp/launchShortcutAt/launchShortcut/prepareShortcuts/controlMedia/dismissMedia/dismissNotification/configureWidget/removeWidget）— 上游 70+ 字段聚合接口裁剪不彻底
- **SettingsViewModel 有实现但无人调**：`reorderFavorites = { keys -> … }`（:343，完整桥命令实现白写）；`GraceFavoritesShim.kt:38-40` 给 shim 面赋的真实现也走不到（FavoritesScreen 用独立 onReorder 参数）
- **天气数据面**：`WeatherLocation.isCurrentPosition`、`WeatherRow.humidity/wind/forecastType`（列白投影）、`WeatherSnapshot.sourceLinks`（恒空 map，唯一消费者又是死 Composable）、`WeatherHour.description` 恒 null、`WeatherTemperature.format` 的 f/k 分支不可达、`WeatherStatus.UnsupportedVersion` UI 死分支

## 六、注释自认 no-op（有意降级，建议文档登记而非删）

- SettingsViewModel 8 处空 lambda（requestCalendarAccess/newEvent/openEvent/refreshAgenda/cachedShortcuts/prepareShortcuts/launchShortcut/configureWidget/removeWidget 等）
- `HostBackPressedDispatcherOwner`（HostBackHandlerOwner.kt:21-32）— 防组合期崩溃垫片，回调源刻意为空，**不建议删**
- `WindowWallpaperBlurEffect`（WallpaperEffects.kt:49-65）— MIUI home 窗口静默 no-op，其他 ROM 生效的跨 ROM 兼容路径
- 过期注释两处：SettingsViewModel.kt:17 头注（addPopupWidget/addWidget 实际已接线）、AppDrawerScreen.kt:8 / HomeScreen.kt:156（reveal 拖拽 morph 实际已接线）

## 七、附带发现（非死代码，但值得关注）

1. **安全校验失效**：`GraceCommandReceiver.fromHost()`（GraceCommandReceiver.kt:88-95）uid 校验未命中时恒放行（注释自认 insecure）
2. **SP 降级丢字段**：GraceHomeSettingsStore.load/save 不含 appFontId/iconDesign/useDynamicColors/themeColor/darkMode/amoledMode，DB 初始化失败时 6 项外观设置静默回默认（仅极端路径）
3. `themedIcons` 桌面外观层硬编码 true（GraceHomeRoot.kt:107）——装饰链另有读取，视觉可能被掩盖

## 八、验证过完整链路（避免误报）

ModePrefs 全部 key 读写闭环；广播收发全闭环（toggle/ping/live/rejected/bridge/settings 四套）；GraceKeepAliveService/RootKeepAlive/AutoStartProbe/HostDataWipe/FontCrossProcess 均有消费方；媒体控制全链路接通（UI→桥→MediaSessionRepository→transportControls）；天气主干链路完整（断点仅字段级）；图标包/图标设计器/字体库链路完整；开源许可页为真实非空数据；root 动作三层兜底（MIUI 原生→su→无障碍）无断点；Manifest 无死注册。

---

## 修复优先级建议

1. **小改动高收益**（用户可直接感知）：#一-1 openClock 加 clockAppKey 分支、#一-7 GraceHomeRoot 读 LauncherPreferences.textMode、#一-8 删硬编码 allowHapticFeedback、#一-9 Route 补传 popupSettings、#一-10/11 接上 DefaultHome.requestIntent / PrivateSpaceProbe.openSettings 现成执行端
2. **设置页同步裁剪**（P2/P6 裁掉的功能）：隐藏 #一-3/4/5/6 四个搜索设置行（或补实现）
3. **死代码清理**：三 + 五全部（可一次批 commit）
4. **hook 层修缮**：GestureIsolator 负缓存改哨兵值（3 行）、HostCompat 声明类 walk 加 com.miui.home 包名边界、删 MainHook.java:207-217、删 MainActivity 死 UI 环、HomeController.log() 改走 ModLog
5. **私密空间**：明确标注降级形态，或按 P6 路线补桌面载体
