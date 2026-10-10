# GraceLauncher 移植清单

源码已下载到工作区 `GraceLauncher-src/`（经镜像站 gh-proxy.com 拉取 master 分支，zip 5.4 MB / 455 个文件）。  
本文回答一个具体问题：**把 GraceLauncher 的 UI 装进 HomeMode4Miui 第四模式底座，要改哪些文件、按什么顺序、有什么坑。**

---

## 1. 源码概况（**v1.1.8，tag @ 2026-10-10，2026-10-11 已同步替换**；原 v1.1.4 分析见文末 §9）

| 项   | 值                                                                           | 对移植的影响                                                                  |
| --- | --------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| 技术栈 | Kotlin 2.4.20 + Jetpack Compose (BOM 2026.09.00) + Material 3               | ⚠️ **最大风险点**：底座模块目前是纯 Java + 传统 View，宿主 com.miui.home 进程内没有 Compose 运行时 |
| 规模  | 176 个 .kt，0 个 .java；分层 data / platform / ui                                 | 需要决定"整包搬"还是"分阶段搬"                                                       |
| SDK | minSdk 28 / compileSdk 37 / AGP 9.3.3 / **OpenJDK 21** / build-tools 36.1.0 | ⚠️ 当前模块 compileSdk 34 + 用户环境 JDK17 + build-tools 34，**构建链必须整体升级**       |
| 数据  | Room 2.8.4（LauncherDatabase）+ SharedPreferences（"launcher_appearance"）      | 库落在宿主进程数据目录，需与模式 DB 隔离策略对齐                                              |
| 权限  | 无 INTERNET；通知监听/日历可选；天气依赖外部 Breezy Weather                                  | 宿主进程内权限模型不同，附属特性需要降级设计                                                  |
| 小部件 | 自建 AppWidgetHost，HOST_ID = `0x47524143`("GRAC")                             | 需核对与宿主自身 host id 不冲突（前作 Niagara 用 `0x4E49`）                             |
| 许可证 | **GPL-3.0**                                                                 | 引用其代码后，本模块分发需遵循 GPL-3.0（当前模块 LICENSE 需同步调整）                             |

---

## 2. 架构对位：GraceLauncher ↔ 底座接缝

GraceLauncher 是独立 APK：`MainActivity.setContent { LauncherAppTheme { LauncherRoute } }`。  
本底座是宿主进程内挂载：`HomeView(抽象) → HomeViewFactory.create() → 挂到 DragLayer`。

```
GraceLauncher 独立APK                      HomeMode4Miui 底座
─────────────────────                     ─────────────────────
MainActivity (ComponentActivity)    →     GraceHomeView extends HomeView
  setContent { ... }                →     内部 ComposeView + 手动 LifecycleOwner/ViewModelStoreOwner
LauncherRoute (Compose 根路由)       →     同一个 Composable 原样复用
LauncherViewModel (viewModels())    →     手动构造（绕过 viewModels() 委托，宿主 Activity 不是 ComponentActivity）
LauncherAppTransitions (合约动画)    →     适配层：改走宿主 Launcher.startActivity 路径或降级系统动画
SettingsActivity / IconDesigner      →     ✅ 不动：运行在模块 App 自己进程，Compose Activity 原样保留
```

**两个红利**：

1. 设置全家桶（`ui/settings/*` 约 20 个文件）跑在模块 App 自己的进程 → 原样可用，只需把读写接到 `ModePrefs` 的 SP 桥上（模块 App 与 com.miui.home 的数据目录不互通）。
2. 手势隔离（GestureIsolator）隔离的是 View 层触摸分发，ComposeView 是普通 View → **天然兼容，零改动**。

---

## 3. 移植清单（按三角色归类）

### A. hook 原版桌面（底座侧，改动极小）

| #  | 文件                                      | 改动      | 说明                                                         |
| -- | --------------------------------------- | ------- | ---------------------------------------------------------- |
| A1 | `app/build.gradle`                      | **重写**  | 加 Kotlin 插件 + Compose 依赖组；compileSdk 34→37；JDK 17→21       |
| A2 | `gradle/wrapper` + 根 `build.gradle`     | 升级      | AGP 9.3.3 要求；建议同时配 maven 镜像（腾讯云/阿里）                        |
| A3 | `home/HomeViewFactory.java`             | 1 行     | `PlaceholderHomeView` → `GraceHomeView`（唯一必改点，照 README 约定） |
| A4 | `LICENSE`                               | 同步      | 含 GPL-3.0 衍生代码后改用 GPL-3.0                                  |
| A5 | 底座其余全部（`hook/**`、`mode/**`、`ModePrefs`） | **零改动** | 已由 HOME 键/广播/手势隔离/负一屏链路验证                                  |


### B. 实现新桌面（移植主体，按阶段推进）

**P0 — Compose 宿主接桩（风险最高，先证明可行）**

| #    | 内容                               | 源文件                                                                                | 要点                                                                                                                              |
| ---- | -------------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| B0.1 | 新建 `home/GraceHomeView.java/.kt` | （新增）                                                                               | FrameLayout 内嵌 ComposeView；自实现 `LifecycleOwner` + `ViewModelStoreOwner`（SavedStateRegistry 可选）；`onHomeDetached` 里销毁 composition |
| B0.2 | 引入 `ui/theme/` 全部                | Theme.kt / LauncherAppearance.kt / LauncherFonts.kt / Type.kt / LauncherHaptics.kt | Material You 动态色（material-kolor）在 HyperOS 上可用，保留                                                                                |
| B0.3 | 最小路由                             | LauncherScreen.kt 裁剪版                                                              | 只渲染 HomeScreen 静态骨架，验证宿主进程内 Compose 可跑                                                                                          |
| B0.4 | 依赖裁剪                             | libs.versions.toml                                                                 | 先不带 aboutlibraries / navigation-compose（P0 不需要）                                                                                 |

**P1 — 首页（favorites + 时钟）**

| #    | 内容            | 源文件                                                                                                       | 要点                                                              |
| ---- | ------------- | --------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| B1.1 | HomeScreen 主体 | ui/home/HomeScreen.kt (435 行)                                                                             | favorites 列表 + 嵌套滚动（NestedScrollConnection 逻辑在内）                |
| B1.2 | 自绘时钟          | ui/home/ClockFace.kt / ClockSpacing.kt / SeparatedClockGlyphs.kt + data/ClockStyle.kt / ClockFontStore.kt | 字体走 `res/raw ttf`（noCompress 已配置），随资源打包                         |
| B1.3 | ViewModel 中枢  | ui/LauncherViewModel.kt (851 行)                                                                           | 绕过 `viewModels()`，在 GraceHomeView 构造时手动创建并传入 Composable         |
| B1.4 | 偏好落地          | data/LauncherPreferences.kt / LauncherSettings.kt                                                         | SP 文件名建议加前缀（如 `grace_launcher_appearance`）避免与宿主 shared_prefs 混淆 |

**P2 — 应用列表（抽屉 + 字母索引）**

| #    | 内容   | 源文件                                                                    | 要点                                                                      |
| ---- | ---- | ---------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| B2.1 | 抽屉   | ui/drawer/AppDrawerScreen.kt (193 行) / AppListModel.kt                 | —                                                                       |
| B2.2 | 字母索引 | ui/components/AlphabetRail.kt                                          | 拼音分组：GraceLauncher 无拼音实现，中文首字母需按前作拼音分组知识补（docs/ARCHITECTURE.md §3.2 存档） |
| B2.3 | 搜索   | ui/search/AppSearchScreen.kt + data/SearchIndex.kt / SearchSettings.kt | SearchIndex 含拼音/联系人索引，评估后可裁                                             |

**P3 — 数据层（Room + 应用仓库）**

| #    | 内容     | 源文件                                                                                           | 要点                                                                                               |
| ---- | ------ | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| B3.1 | 应用仓库   | data/AppRepository.kt                                                                         | `PackageManager.queryIntentActivities` + `LauncherApps.getActivityList` = AOSP 稳定 API，宿主进程直接可用 ✅ |
| B3.2 | Room 库 | data/LauncherDatabase.kt + FavoritesStore / HomeLayout / LauncherItems / ShortcutRepository 等 | 建库位置 = com.miui.home 进程数据目录；对照底座"独立 DB 目录"惯例（custom/）确认不与宿主库撞名；KSP 编译器接入构建脚本                     |
| B3.3 | 应用增删同步 | HomeView.onPackagesChanged() → ViewModel.refreshApps()                                        | 底座已聚合广播，接一行                                                                                      |

**P4 — 小部件 + 弹层**

| #    | 内容        | 源文件                                                                                                            | 要点                                                                                                                                                                |
| ---- | --------- | -------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| B4.1 | Widget 宿主 | platform/HomeWidgetHost.kt + ui/widgets/HomeWidget.kt / WidgetPicker.kt / WidgetPreview.kt / HomeEditHandle.kt | ① HOST_ID `0x47524143` 建议改（避开与宿主冲突，前作惯例 `0x4E49` 系）；② 宿主 com.miui.home 有 BIND_APPWIDGET，bind 链路可用；③ startListening/stopListening 挂到 onHomeAttached/onHomeDetached |
| B4.2 | 长按弹层      | ui/overlays/ShortcutPopup.kt / LauncherSheets.kt / PopupMorphGeometry.kt + ui/components/ShortcutPicker.kt     | Compose 弹层画在 HomeView 内部，不走宿主 AbstractFloatingView（手势隔离已偏向隔离生效）                                                                                                   |
| B4.3 | 收藏重排      | ui/overlays/FavoritesReorderState.kt / ItemSelectionScreen.kt                                                  | —                                                                                                                                                                 |

**P5 — 启动动画适配**

| #    | 内容       | 源文件                                                                                 | 要点                                                                                              |
| ---- | -------- | ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| B5.1 | 启动转场     | platform/LauncherAppTransitions.kt / AppLaunchTransition.kt / GestureNavContract.kt | 依赖 ComponentActivity 合约 → 二选一：改走宿主 Launcher 的 startActivitySafely 路径；或 P5 先降级为系统默认动画（标注砍功能不砍体验） |
| B5.2 | 启动图标动画目标 | ui/components/LauncherAnimationTarget.kt / LauncherIcon.kt                          | 跟随 B5.1 决策                                                                                      |

**P6 — 附属特性（逐项独立开关，坏了可降）**

| #    | 内容                | 源文件                                                                                     | 要点                                                                                       |
| ---- | ----------------- | --------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| B6.1 | 媒体控制              | data/media/* + ui/home/HomeMediaPlayer.kt                                               | NotificationListenerService 跑在**模块 App 进程**，落盘 JSON → 宿主 UI 读文件（前作 SP 桥同思路）；无通知监听权限则隐藏组件 |
| B6.2 | 日程/天气             | data/CalendarRepository.kt / data/weather/* + ui/weather/* / ui/overlays/AgendaSheet.kt | 日历权限在宿主进程内申请（宿主也有日历权限可借）；Breezy Weather Provider 跨进程读需实测，读不到就降级隐藏                        |
| B6.3 | 通知小红点             | data/notifications/AppNotificationStore.kt                                              | 同 B6.1 通道                                                                                |
| B6.4 | 图标包/Icon Designer | data/icons/* + ui/overlays/IconPackGrid.kt                                              | 渲染纯本地，可用；设置页在模块 App 进程（见 C 组）                                                            |
| B6.5 | Private Space     | platform/PrivateSpaceController.kt                                                      | API 35 特性，宿主进程内可探测，做独立开关                                                                 |

### C. 获取数据（模块 App 进程，原样保留）

| #  | 文件                                                                                                                                                                                                                                              | 处置                                                                          |
| -- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| C1 | SettingsActivity.kt + ui/settings/* 全家（LauncherSettingsScreen / ThemeSettings / ClockStyleSettings / IconDesigner* / FontSettings / GraceButtonSettings / WeatherSettings / Media / Search / Calendar / PrivateSpace / License / About ≈ 20 文件） | **原样搬入模块 App**：正常 Compose Activity，不碰宿主                                     |
| C2 | WidgetSetupActivity.kt                                                                                                                                                                                                                          | 同上                                                                          |
| C3 | 跨进程读写                                                                                                                                                                                                                                           | 设置项落 `ModePrefs` SP 桥（已有机制）；大块数据（图标设计、字体选择）走模块目录文件 + 宿主进程按路径读               |
| C4 | MainActivity.kt                                                                                                                                                                                                                                 | ❌ **不移植**（宿主进程没有自己的 HOME Activity；其 onNewIntent 逻辑已由底座 `onHomeIntent()` 覆盖） |

---

## 4. 构建环境前置项（✅ 2026-10-08 已全部就绪，详见 tools/ENV.md）

1. ✅ **JDK 21** — Temurin 21.0.12.1 → `C:\Users\Administrator\dev-env\jdk-21`（清华镜像）
2. ✅ **SDK Platform 37 + Build-Tools 36.1.0** — `dev-env/android-sdk/`（另备 platform-34 供现模块构建）
3. ✅ **Gradle 9.5.0 + wrapper** — 项目根 `gradlew` 指腾讯镜像（sha256 官方锚定 + 缓存预热）；AGP/Kotlin 插件随 P0 构建脚本升级引入
4. ✅ **maven 镜像** — `~/.gradle/init.gradle`：阿里云 google/public/gradle-plugin 优先 + 腾讯聚合兜底
5. ✅ **Xposed API jar** — `app/libs/XposedBridgeApi-82.jar` 已补齐（阿里云 maven）
6. ✅ **全链路验证** — `build_apk.sh` 已用新环境出包成功；脚本已完成 Git Bash/Windows 路径兼容修复

---

## 5. 风险登记

| 风险                                          | 等级                      | 缓解                                                                                                                                                                                        |
| ------------------------------------------- | ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **宿主进程内模块资源不可达（R.string/R.font）**           | 🔴 高（P1 已实锤，hotfix1 修复） | Compose LocalContext 被覆盖为模块包 Context（createPackageContext）；宿主侧行为走 LocalHostContext；后续阶段搬运的任何 `stringResource`/`Font(R.font.x)` 自动生效，但新增宿主 Context 依赖（如 filesDir 写入）必须显式用 LocalHostContext |
| Compose 运行时在 com.miui.home 进程初始化失败（反射/资源冲突） | 🔴 高                    | P0 第一天就验证；失败兜底方案 = 只搬 Compose 渲染产物思路改自绘 View（成本翻倍，提前知道）                                                                                                                                   |
| APK 体积（Compose 依赖组），R8 未开                   | 🟡 中（已缓解，2026-10-09）    | 已开 `minifyEnabled + shrinkResources` + `app/proguard-rules.pro` 放行规则；31MB → 7.3MB（详见 §④）                                                                                                  |
| 两大铁律（零崩溃 / 语义化颜色）：Compose 异常不会自动走 ModLog    | 🔴 高                    | GraceHomeView 全链路 try-catch + `CompositionError` 回调打 ModLog；Theme 层把 M3 colorScheme 映射到壁纸取色，禁硬编码色值进 UI                                                                                    |
| Room 库与宿主数据库目录混淆                            | 🟡 中                    | 库名加 grace_ 前缀；卸载模式时保留（用户数据）                                                                                                                                                               |
| HOST_ID 冲突导致 widget 列表异常                    | 🟡 中                    | 换 id + 实测 widget 绑定/解绑/重启桌面三场景                                                                                                                                                            |
| GPL-3.0 传染                                  | 🟡 中                    | 发布前确认 LICENSE 与 README 声明                                                                                                                                                                 |

---


## 6. 里程碑验收（对齐 PORTING.md 第 5.4 节风格）

- [ ] P0：切入第四模式 → 宿主进程内 Compose 渲染出静态时钟 + 空列表，LSPosed 日志 9 条 hook 全 installed，零崩溃
  - ✅ 开发侧完成（2026-10-08）：构建链升级（AGP 9.3.3 / Gradle 9.5.0 / compileSdk 37 / JDK21）+ GraceHomeView 接桩 + theme 全量引入 + 最小路由；`./gradlew :app:assembleRelease` 出包成功（v2.1.0-grace-p0, versionCode 3, 25MB 未开 R8），dex 内已验证 Compose 运行时 + materialkolor + 主题包齐全，签名沿用 homemode4miui keystore。待装机验收（切第四模式看静态时钟 + LSPosed 日志）
- [x] P1：favorites 增删即时刷新；时钟字体/样式切换生效；滚动流畅不被宿主抢断（手势隔离）——✅ **装机验收通过（2026-10-08 13:24 日志）**：11 hook installed / 挂载+心跳正常 / 五次复用路径幂等 / 零崩溃零异常；自绘时钟+日期+favorites 渲染正确，长按菜单（hotfix2 后）与启动应用使用正常
  - ✅ 开发侧完成（2026-10-08，v2.2.0-grace-p1 / versionCode 5 / 27.8MB）：  
    B1.1 裁剪版 HomeScreen（保留 BoxWithConstraints 顶距约束 + HomeOverscrollConnection 嵌套滚动 + favorites LazyColumn；删 widget/media/weather/calendar/folder/editingLayout 分支）；  
    B1.2 时钟全家（ClockFace/ClockSpacing/SeparatedClockGlyphs/ClockStyle/ClockFontStore 逐字搬运）+ LauncherFonts 完整版（Josefin Sans + noto_sans fallback；阿拉伯/希伯来/天城文/泰文 4 个 fallback 资产裁剪省 1.7MB；5 个 preset 字体入 res/font）；  
    B1.3 新建 GraceHomeViewModel（LauncherApps.getActivityList 查询 + PM 兜底 + FavoritesStore 收藏/排序/迁移 + SP settings + 包变化广播自刷新；shutdown() 由 GraceHomeView.onHomeDetached 显式调用，规避手动构造 ViewModel 不走 onCleared 的泄漏）；  
    B1.4 SP 偏好落地（LauncherPreferences 文件名改 grace_launcher_appearance；新建 GraceHomeSettings/GraceHomeSettingsStore，clockStyle 用原版 encode/decode 格式落 grace_launcher_settings，P3 切 Room 字节级兼容）。  
    演示入口：长按时钟循环 ClockLayout（P3 接设置全家桶后替换）、长按收藏行菜单（移除收藏/应用信息；P4 换 ShortcutPopup）、favorites 空态点按恢复默认收藏（P2 抽屉落地后移除）。  
    通用注意：移植文件的 R import 必须指向 com.homemode4miui.R（模块 namespace），非原版包名。
  - ⚠️ hotfix1（2026-10-08，v2.2.1-grace-p1-hotfix1 / versionCode 6，commit a1b8c46）：装机长按收藏行桌面崩溃  
    （Resources$NotFoundException: 0x7f0b0065，Popup 组合期，熔断覆盖不到）。根因：宿主进程内 Compose  
    LocalContext 是宿主 Context，resources 解析不了模块 APK 资源 ID。修复：GraceHomeRoot 覆盖

```
LocalContext 为模块包 Context（createPackageContext，不带 INCLUDE_CODE）；新增 LocalHostContext
供宿主侧行为（filesDir/Activity window）；preset 字体迁移 res/font → assets/fonts/ 经
Typeface.createFromAsset 加载（Font(R.font.x) 同根因，预防性修复）。待复测长按菜单 + 时钟长按切样式。
```

- [ ] ⚠️ hotfix2（2026-10-08，v2.2.2-grace-p1-hotfix2 / versionCode 7，commit 70a61d7）：装机截图确认 hotfix1  
  主链路恢复（时钟/日期/favorites 正常），但长按菜单两个菜单项文字显示 "false"。根因：Compose 1.12 的  
  PopupLayout 是独立 ComposeView 树根，经 ComposeViewContext.ProvideCompositionLocals 把 LocalContext  
  重设为宿主——模块 Context 覆盖不穿透 Popup，菜单内 stringResource 在宿主表错位解析（bool→"false"）。  
  修复：菜单文案主树预解析成 String 传入，Popup 子树零资源调用。规则：Popup/Dialog 子树禁止  
  stringResource；P4 弹层全家沿用此规则或做 GracePopup 统一重覆盖。
- [ ] P2：中文应用按拼音分组 + 字母索引跳转正确
  - ✅ 开发侧完成（2026-10-08，v2.3.0-grace-p2 / versionCode 8 / 27.9MB）：  
    B2.1 抽屉（AppListModel 裁剪版：删 folders/private/recentlyInstalled，保留 LauncherAppOrder 排序 +  
    section 分组 + 字母表顺序；AppDrawerScreen 裁剪版：保留 retainItemSpace 字母 scrub 几何保持 +  
    userScrollEnabled 语义，删 FolderRow/PrivateApp/notifications；P2 新增行长按菜单 =  
    加入/移出收藏 + 应用信息，文案主树预解析遵守 hotfix2 Popup 规则）；  
    B2.2 字母索引（AlphabetRail 逐字搬运 + LauncherIcon Star 裁剪版 + ms_star drawable；  
    拼音分组无需补——v1.1.4 LauncherApp.appSortKey 已内置 ICU Transliterator  
    "Any-Latin; Latin-ASCII"，中文应用自动归入 A–Z，P1 已随 LauncherApp.kt 搬入）；  
    B2.3 搜索（AppSearchScreen 裁剪版：删 SearchContacts/Internet/SearchLauncher 与设置按钮，  
    保留 SearchQuery/SearchName 打分（拼音 initials/子序列/模糊）+ 最近应用建议；  
    LauncherSearchBar 逐字搬运；SearchSettings 逐字搬运并挂入 GraceHomeSettings.searchSettings  
    SP 落盘 grace_launcher_settings/search_settings key；recordSearchApp 接 recordApp）。  
    路由重构：新建 GraceLauncherRoute（两态 UI：首页/抽屉/搜索 + rail + FAB 搜索入口），  
    GraceHomeRoot 改为直连路由；删 P1 favorites 空态点按引导（收藏管理走抽屉长按菜单）；  
    Home 键回桌面链路：底座 onHomeIntent → GraceHomeView override → ViewModel.returnHomeRequests  
    (SharedFlow) → 路由复位两态（宿主非 ComponentActivity，BackHandler 不可用，rail 星星/HOME 关抽屉）。  
    版本注意：本 compose foundation 版本 TextFieldState 只有 clearText() 扩展（无 edit）。  
    待装机验收（rail scrub 跳转/中文分组/搜索匹配/收藏增删即时刷新）。
- [ ] P3：重启桌面后 favorites/布局从 Room 恢复；装/卸应用列表自动刷新
  - ✅ 开发侧完成（2026-10-08，v2.4.0-grace-p3 / versionCode 9）：  
    B3.2 Room 库（LauncherDatabase 逐字搬运：6 实体 + 全部 17 个 migration，schema version 18  
    一次到位，P4 弹层/widget、P6 private space 直接复用无需补 migration；唯一适配点 =  
    建库路径 databases/custom/grace_launcher.db，对齐底座独立 DB 目录惯例，Room name 传  
    绝对路径 + 父目录 mkdirs；LauncherItems 裁剪版 = entities + DAO + PopupItem，裁  
    ItemIcon/LauncherItemsRepository 到 P4/P6；HomeLayout 逐字）+ 构建链 KSP 2.3.10 +  
    room-runtime/room-ktx 2.8.4（schemaLocation → app/schemas 入 git）；  
    B3.1 AppRepository 裁剪版（loadApps = LauncherApps 主路径 + PM 兜底 + ensureActive  
    对齐原版；launch = startMainActivity；裁 icon packs/P6）自 ViewModel 抽出；  
    B3.3 装卸同步 P1 已实现（packageReceiver → refreshApps → favoritesFor 补 key），本阶段零改动；  
    新建 GraceSettingsRepository（Room launcher_settings 承载 GraceHomeSettings 4 字段：  
    clockEnabled/clockStyleJson/hideFavoriteNames/searchJson；SP→Room 一次性 seed 迁移  
    ——clockStyle 原版 JSON 字节级兼容；withTransaction read-modify-write，copy 只覆写  
    4 列、未知列原样带回；DB 初始化/读/写失败全链路降级回 SP，零崩溃铁律）。  
    ViewModel 切换：settings 初值/更新走 Room（observeSettings Flow 常驻 + mutateSettings  
    launch 内消化，对外签名不变 → 路由层零改动）；favorites 保留 SP（原版 v1.1.4 语义）；  
    shutdown() 增加 settingsJob 取消（手动构造 ViewModel 不走 onCleared，防 collect 泄漏）。  
    待装机验收（重启后时钟样式/布局/搜索设置从 Room 恢复 + 首次启动 SP 迁移 + 装卸刷新）。
- [x] P4：桌面 widget 添加/缩放/移除 + 重启保持；长按弹层 morph 动画正常
  - ✅ **装机验收通过（2026-10-08 16:2x，用户确认 + 日志复核）**：

    ① 时钟长按 → HomeWidgetMenu → 添加 widget → WidgetPicker 选型 → 桌面渲染 ✅

    （**bind 免确认链路同项实锤**：widget 上桌 = bindAppWidgetIdIfAllowed 返回

    true——本实现 bind 失败不会弹系统确认对话框而是直接失败 Toast，无对话框

    即宿主 BIND_APPWIDGET 免确认路径生效）；② widget 长按 → 移动/调整（编辑

    把手）→ 重启桌面布局保持（Room homeLayoutJson 持久化）✅；③ 行右滑 →

    ShortcutPopup reveal morph 弹层 + 快捷方式 + 尾部收藏行 ✅。

    待观察项（非阻塞）：带配置页 widget 的 configure 跳转（NEW_TASK）、

    CustomWidgetMenu"Change widget settings"入口、provider 推送刷新

    （startListening 生命周期）。
  - ✅ 开发侧完成（2026-10-08，v2.5.0-grace-p4 / versionCode 11 / 28.9MB）：  
    B4.1 Widget 宿主（HomeWidgetHost/HomeWidgetHostView 逐字，HOST_ID 0x47524143；  
    HomeWidget.kt 逐字——rememberWidgetHost 的 lifecycle 观察随自托管 LifecycleOwner 原语义  
    成立；build.gradle 补 lifecycle-runtime-compose 2.9.4；startListening/stopListening 由  
    GraceLauncherRoute 顶层 rememberWidgetHost 挂 ON_START/ON_STOP）；  
    **WidgetSetupActivity 不搬**（原版跑模块 App 进程，其 Room 写入与宿主进程  
    grace_launcher.db 不互通）→ bind 链路宿主进程内完成：allocateAppWidgetId →  
    bindAppWidgetIdIfAllowed（宿主持 BIND_APPWIDGET 免确认）→ 写 Room homeLayout →  
    有配置页 NEW_TASK 启动 configure（fire-and-forget）；WidgetPicker 裁剪版改为宿主  
    Compose 全屏浮层（删 SettingsScaffold/IconPackRepository/多 profile 过滤）；  
    HomeLayout 持久化：GraceHomeSettings 加 homeLayout 字段（第 5 列），Room  
    launcher_settings.homeLayoutJson 原版 schema 已有零 migration；HomeEditHandle×2  
    编辑把手（位置/高度），FAB 编辑态变完成键；  
    B4.2 弹层（PopupMorphGeometry/AsyncQueryCache/ShortcutRepository 逐字 +  
    LauncherShortcut 去重沿用 P1 模型；ShortcutPopup 裁剪版：删 FolderPopup/notifications/  
    Private/弹层 widget 分支，LauncherActions → GracePopupActions 子集 + menuEntries  
    附加行承载收藏增删；SwipeRevealPanel 同窗口 morph 动画原样；HomeSheets 新建 =  
    LauncherOverlay 裁剪 sealed（Shortcuts/HomeWidgetMenu/CustomWidgetMenu）+  
    PanelAction + LauncherOverlays 分发 + HomeWidgetPanel；**原版 ModalBottomSheet  
    替换为同窗口底部面板**——ModalBottomSheetPopup 是独立窗口，踩 hotfix2 的  
    LocalContext 重置坑）；手势接线：favorites/抽屉行右滑 = ShortcutPopup reveal  
    morph（原版 soul 交互），长按保持应用信息语义，AppRowGestures onPrepare 预取/  
    onDrag 拖拽 reveal/onDragEnd 提交；时钟长按 = HomeWidgetMenu，widget 长按 =  
    CustomWidgetMenu，P1 演示入口 cycleClockLayout 迁入菜单；删 P1/P2 临时 DropdownMenu；  
    B4.3 收藏重排（ItemSelectionScreen/FavoritesScreen 依赖 C 组设置体系  
    （SettingsScaffold/FavoriteFoldersScreen/ShortcutPickerScreen）**缓至 C 组**；  
    FavoritesReorderState 同缓；收藏增删经弹层 menuEntries 承载零回退）。  
    LauncherIcon 补 10 符号（Outbound/Info/Plus/Delete/Edit/Apps/Check/Settings/Move/  
    Resize/Clock）+ drawable 12 个（ms_schedule 等）；strings 补 P4 面（widget\_*/  
    shortcut\_*/plurals widget_count 等）。待装机验收（widget 添加/缩放/移除/重启保持 +  
    右滑弹层 morph + 时钟/widget 长按菜单）。
- [ ] P5：点应用有启动动画（或明确降级声明）；Home 键两态复位
  - ✅ 开发侧完成（2026-10-08，v2.6.0-grace-p5 / versionCode 13）：  
    B5.1 启动转场走「系统默认动画」路径（ActivityOptions.makeThumbnailScaleUpAnimation，  
    不依赖 ComponentActivity，不改底座）：AppLaunchTransition.kt 逐字搬运  
    （Compose boundsInWindow → 屏幕坐标换算 + thumbnail/scale-up options + API 33  
    splash style）；LauncherAppTransitions.kt **裁剪版**（LauncherIconTarget +  
    register/unregister/launch 防抖 + 80ms capture 超时 + close，逐字；  
    **裁掉 HomeReturnAnimation + GestureNavContract + selectHomeTarget** ——  
    降级声明：① 底座 HomeView.onHomeIntent() 不带 Intent 参数，接 Quickstep 合约  
    需扩展底座 hook（违反 A 组零改动边界）；② MIUI 快速手势是否注入 AOSP  
    gesture_nav_contract_v1 未实测；③ HomeReturnAnimation 往宿主 Activity content  
    注入 SurfaceView 在 MIUI DragLayer 窗口结构内未验证。恢复节点 = 实测 contract  
    后按原文件头部 diff 逐字补回，构造签名已保留 activity 参数零变更）；  
    B5.2 LauncherAnimationTarget.kt 逐字（LocalAppTransitions/LocalHomeAnimationTarget
    - Modifier.launcherAnimationTarget：GraphicsLayer 录制真实渲染 artwork 含  
      themed/单色图标 → capture 做 thumbnail）；AppIcon 恢复动画目标挂点  
      （app.folderId == null 时包装）。  
      接线（GraceLauncherRoute）：顶层 CompositionLocalProvider(LocalAppTransitions)  
      （对齐原版 MainActivity.setContent）+ HomeScreen 包 LocalHomeAnimationTarget  
      provides true（仅首页图标 home=true，原版语义）；启动链路 launchApp(app, bounds) /  
      launchShortcut(shortcut, bounds) —— favorites/抽屉行点击（rowGestures.onLaunchAt  
      P4 丢弃的 iconBounds 接回）、右滑 reveal 提交、弹层快捷方式（GracePopupActions  
      launchShortcutAt P4 预留字段接上）、搜索结果行（AppSearchScreen onLaunch 签名扩  
      (LauncherApp, Rect?)，open(app, iconBounds) 原本就有）全覆盖；  
      GraceHomeViewModel.launch/launchShortcut 加 sourceBounds/options 透传  
      （AppRepository/ShortcutRepository P3/P4 已带参数零改动）；失败 Toast 对齐原版  
      （app_unavailable/shortcut_error，P1 裁剪版 strings 已有）。  
      宿主 Activity 获取：GraceHomeView.mLauncher → GraceHomeRoot(hostActivity) →  
      GraceLauncherRoute（ComposeView 的 LocalContext 是 ModuleResourceBridge  
      ContextWrapper 拿不到 Activity，必须显式透传）；LauncherAppTransitions 的 scope  
      用 rememberCoroutineScope（组合级，onHomeDetached dispose 时随组合取消，替代原版  
      Activity lifecycleScope，与自托管生命周期对齐）。  
      Home 键复位扩展：returnHomeRequests.collect 里加 appTransitions?.close()（对齐  
      原版 onHomeIntent 取消进行中 capture）。  
      降级链（零崩溃铁律）：hostActivity == null → appTransitions = null →  
      LocalAppTransitions null → 图标不注册目标 → bounds 启动直接 fromIcon（无 thumbnail  
      的 makeScaleUpAnimation）；bounds == null → 纯 startMainActivity（P4 及以前行为）。  
      待装机验收（点应用 thumbnail scale-up 动画 + Home 键复位 + 右滑/弹层/搜索路径动画）。
- [ ] P6：每项独立开关，任一失效不阻塞主链路
  - ⚠️ **装机验收部分通过（2026-10-08 晚，用户确认）**：通知预览/日程/天气/图标包/

    Private Space/开关面均可使用；媒体行显示正常（曲名/歌手/按钮）✓。

    残留 2 点挂起：① 媒体控制命令（hotfix8 已修 sessionId 漂移根因，待复测）；

    ② 封面显示（原版 loadArtwork 拒绝网络封面 = 原版语义，小米音乐封面为网络

    URI 不显示，维持原版行为）。

    hotfix 链（v2.7.1~v2.7.8，5 轮根因修复 + 1 次 NPE 手误）：NOT_EXPORTED 跨包

    广播不可达 → NEW_TASK 设置页秒退 → BAL 静默拦截 → 引擎启动时序 → bridgeDiag

    NPE → 布局截断 → MIUI 媒体通知缺 EXTRA_MEDIA_SESSION token → 降级模式

    sessionId 漂移。通用教训入 memory：跨包广播动态 receiver 必须 EXPORTED；

    HyperOS 宿主直启他应用深层设置页 = 无异常静默拦截（引导一律走主入口

    Activity 二级跳转）；LSPosed modules 日志只收 hook 进程输出。
  - ✅ 开发侧完成（2026-10-08，v2.7.0-grace-p6 / versionCode 14 / 28.1MB）：  
    **B6.1 媒体控制 + B6.3 通知小红点（跨进程广播桥）**：  
    NotificationListenerService 落模块 App 进程（系统按 manifest 绑定，token/

    controller 不可离进程；含 binder 的 Parcel 不能 marshall 落盘 → 文件桥不可行，

    PORTING 原设想修正为广播桥）；data/media/*（MediaAccess/MediaPlayback/

    MediaNotificationListener/MediaSessionRepository）+ AppNotificationStore 逐字

    搬运（listener 仅 onListenerConnected/onListenerDisconnected 两处挂桥，数据面

    单例 appNotifications/mediaNotifications 与原版一致）；新建 bridge/

    BridgeContract（ACTION_GRACE_MEDIA/NOTIFICATIONS/WEATHER 快照广播 +

    ACTION_GRACE_COMMAND 命令广播 + JSON 编解码 + 封面 JPEG bytes extra ≤100KB，

    bandle 1MB 缓冲安全区）+ GraceBridgeEngine（模块进程单例：state collect →

    指纹去重 → 显式 setPackage("com.miui.home") 下发；命令执行面）+

    GraceCommandReceiver（manifest 静态注册，exported=true + onReceive 内

    Binder.getCallingUid 校验必须解析为 com.miui.home——宿主无法声明自定义

    uses-permission，权限保护不可用，uid 校验兜底）+ 宿主侧 GraceBridgeReceiver

    （动态注册 RECEIVER_NOT_EXPORTED）。媒体行（HomeMediaPlayer 逐字 +

    IdleMediaSessionId 常驻占位 mediaAlwaysVisible）、favorites 行通知预览

    （LauncherRow notification 分支恢复：label 时间后缀 + 正文两行 + Chevron 箭头，

    notificationAge 逐字）+ 弹层附加行通知打开/清除；无通知监听权限（宿主进程

    isNotificationListenerAccessGranted 探测）→ 组件整体隐藏；mediaPlayer 开关开启

    且未授权时引导 MediaAccess.settingsIntent。  
    **B6.2 日程/天气**：CalendarRepository + ScheduleEvent（agendaDays/

    nextVisibleEvent/eventCountdown）宿主进程直读；READ_CALENDAR 能力探测（宿主

    manifest 不可改、无法在宿主进程弹授权 → PermissionRequired 降级，Connect

    calendar 按钮打开系统日历 app，宿主 manifest 已声明该权限时授权后自动生效）；

    weather/*（WeatherModels/BreezyWeatherParser/BreezyWeatherRepository/

    BreezyWeatherUpdates）在模块 App 进程读 Breezy provider（manifest 声明

    org.breezyweather.READ_PROVIDER——custom permission 宿主拿不到，读侧必须

    在模块进程）经桥下发；HomeClockHeader 恢复日程行（ScheduleLine）/

    calendarAboveClock/电量（rememberBatteryPercent）/天气温度行（HomeWeather/

    WeatherIcon/WeatherComponents 搬运 + 12 个 ms_weather drawable）；日期点击 →

    AgendaSheet 裁剪版（同窗口面板，ModalBottomSheet → scrim+Surface 对齐 hotfix2

    规则；AgendaEvent/NewAgendaEvent/WeatherConnectionNotice/weatherAgendaDays；

    newEvent/openEvent 降级系统日历 Intent，openBreezy/installBreezy 降级

    launch intent/市场 intent）。  
    **B6.4 图标包**：IconPackDefinition/IconPackRepository/IconPackOrder 搬运

    （裁 selectedIcon/matchingIconName/designLayers* —— ItemIcon/IconDesignRenderer

    per-app 选择面随 C 组）；AppRepository 恢复 makeApp 装饰链（normalizeIconPackOrder
    - iconFor + firstMatchingPackIcon，包加载失败回退系统图标）；IconPackPanel

      宿主内单选面板（原版多选 fallback 顺序随 C 组设置页恢复），时钟长按面板加

      "Icon pack"入口；iconPackPackages 落 Room iconPackPackagesJson 列。  
      **B6.5 Private Space（诚实降级）**：PrivateSpaceSettings 数据面搬运

      （Room privateSpaceJson 列就位）+ PrivateSpaceProbe 轻量探测（API 35 +

      launcherApps.profiles USER_TYPE_PROFILE_PRIVATE + QuietMode 状态）+ 面板状态行

      （跳系统安全设置）；完整 PrivateSpaceController（310 行解锁/锁定/凭据会话）

      依赖抽屉 PrivateApp/folders UI 载体，随 folders 阶段恢复。  
      **开关面**：GraceHomeSettings 扩 9 字段（calendarAgenda/calendarAboveClock/

      showBatteryPercentage/mediaPlayer/mediaAlwaysVisible/weatherEnabled/

      weatherForecastDays/iconPackPackages/privateSpace）——全部命中 schema v18

      launcher_settings 已有列，零 migration（P3 一次到位策略兑现）；SP 桥同步扩 key；

      时钟长按面板注入开关行组（WidgetToggle 原版逐字：日程/天气/媒体控制+授权引导/

      电量）。drawable 补 19 个（ms_sunny 全套/ms_music_note/ms_pause/ms_play_arrow/

      ms_skip\_*/ms_calendar_month/ms_lock），strings 补 ~60 key；LauncherSymbol 补

      Chevron/Calendar/Weather/Lock/Music。  
      降级链（任一失效不阻塞主链路）：桥断/广播被拦 → 快照过期 → 组件空缺；无通知

      监听权限 → 媒体/通知隐藏；宿主无 READ_CALENDAR → 日程 PermissionRequired →

      首页行自然无事件隐藏；Breezy 未装/未授权/读不到 → 天气 Disabled/NotInstalled

      不下发 → 隐藏；图标包加载失败 → safely 回退系统图标；private space API < 35 →

      unsupported 状态行。  
      待装机验收（通知监听授权后媒体行/通知预览/控制命令闭环 + 日程行/Agenda 面板 +

      Breezy 装机后天气 + 图标包选择上图标 + 开关落 Room 重启保持）。
- [ ] 全程：切回原生模式完整还原；GPU 过度渲染无静止重绘

---

## 7. 一句话总结

> **P0 用一周证明"Compose 能活在 com.miui.home 里"，证明成功后 P1–P4 基本是搬运 + 接线（底座侧只改 `HomeViewFactory` 一行）；  
> 真正的工程量在 P5 动画适配和 P6 跨进程数据通道，先把它们标为独立可降级项。**

---

## 8. 后续路线图（2026-10-08 定，P6 装机验收后）

当前位置（2026-10-09）：P0–P6 + C 组设置全家桶 + folders/notif/gate/keep 全链

- dualapp 双开支持均已完成并入库；装机验收 P1/P2/P3/P4 ✓、P6 主体 ✓、C 组

  外观面 ✓（媒体控制命令等 hotfix8 复测；封面为原版语义——拒绝网络封面，维持）。

  \*\*项目已发布 GitHub：<https://github.com/adaozi/HomeMode4Miui>，当前版本

  v2.9.20-grace-dualapp（versionCode 53）。\** 剩余为收尾验证与增量增强。

### ① C 组：设置全家桶（唯一剩的大块，约 20 个文件）—— v2.8.0-grace-c1（2026-10-08，开发侧完成，待装机验收）

- [x] C1 `SettingsActivity` + `ui/settings/*` 原样搬入模块 App 进程（23 文件全量：

  LauncherSettingsScreen / ThemeSettings / ClockStyleSettings / IconDesigner* /

  FontSettings / GraceButtonSettings / WeatherSettings / Media / Search /

  Calendar / PrivateSpace / License / About）＋ 依赖补齐：ShortcutPicker /

  AppSelectionList / AppSelectionScrollbar / IconPackGrid / SettingsDialogs

  （自原版 LauncherSheets 尾部拆出）/ LauncherUiState / LauncherActions /

  SearchLauncher / SearchContacts / IconPackRepository 恢复原版全量。

  入口 = 模块 MainActivity（route extra）→ SettingsActivity（BAL 可行路径，P6 hotfix3 实锤）

  —— 正常 Compose Activity，不碰宿主；新增依赖 androidx.activity-compose 1.13.0 /

  navigation-compose 2.9.8
- [x] C3 跨进程设置同步：模块设置页写 → `ACTION_GRACE_SETTINGS_COMMAND`（列级增量

  JSON）→ 宿主 GraceSettingsHostEngine（新建）→ LauncherSettingsRepository 大模型

  read-modify-write 全量落 Room（launcher_settings 表 schema v18 列全覆盖，零 migration）

  → `ACTION_GRACE_SETTINGS_SNAPSHOT` 快照回推闭环；宿主 receiver = GraceBridgeReceiver

  扩展（挂载期动态注册）。快照超时 ≈4s → settingsLoadFailed → 设置页只读

  （SettingsStorageState.canEdit 语义，与原版一致）
- [x] 解锁挂起项：**收藏重排组件**（FavoritesReorderState / ItemSelectionScreen /

  FavoritesScreen / FavoriteFoldersScreen 落仓，收藏序命令 reorder_favorites →

  FavoritesStore，宿主桌面弹层接线待装机复测后启用）、multi icon pack fallback 顺序

  （iconPackPackagesJson 全量往返 + IconPackRepository 原版恢复）、weather location

  （weatherLocationId 设置→宿主 Room→快照回带→模块桥引擎 load(preferredLocationId)

  闭环）、**图标设计器**（ItemIcon 补回 LauncherItems + IconDesign + IconDesignRenderer
  - ItemIconStore + per-app item_icons 表经桥读写）、mediaAppKey（设置行持久化）、

    时钟样式选择器（桌面长按面板"时钟样式"行改为直达模块设置页 ClockStyle 面板，

    替掉 cycleClockLayout 演示入口）、时钟长按面板 "Grace settings" 行接通

    （→ 模块 MainActivity route 转发）
- [x] 宿主侧字段对齐：宿主仓库升级为 LauncherSettings 大模型（34 列 ↔ 原版模型逐项

  映射，GraceHomeSettings 变为宿主 UI 视图，主链路零破坏）；设置页逐面板接线经

  updateSettings 统一走增量桥。**仅持久化、宿主渲染待接**（登记）：hideStatusBar ✅

  （hotfix3 接渲染 + hotfix6 升级 autoHide 语义：LauncherSystemBars 移植，滚顶下拉/边缘滑

  唤出 3s 回收，2026-10-09 装机验收通过）/ dimWallpaper ✅（hotfix3 已接：黑 scrim 随抽屉

  可见度；hotfix6 纠正为原版 wallpaperTint 主题色调遮罩，2026-10-09 验收通过）/

  blurWallpaper ✅（hotfix3 已接：window.setBackgroundBlurRadius 随抽屉可见度，宿主原窗口；

  hotfix5 修 dp→px 量纲；hotfix6 加 WallpaperManager 快照 + Modifier.blur Compose 兜底层

  ——MIUI home 窗口对 setBackgroundBlurRadius 静默 no-op 定案，2026-10-09 验收通过）/

  hideAlphabet ✅（hotfix3 接渲染 + hotfix4 纠正为原版 autoHide 语义：触摸即现松手淡出，

  2026-10-09 验收通过）/

  themeColor / darkMode / amoledMode（外观三件套）✅ / appFontId（全局字体）✅ /

  allowHapticFeedback（触感反馈）✅ / clockAppKey / mediaAppKey（跳转目标）✅

  —— 以上 7 项宿主消费链路本已存在（LauncherAppearance / Typography / 时钟媒体点击），

  **2026-10-09 装机验收全部通过，"仅持久化待接"清单清零**
- [ ] **C 组降级登记**（桌面零崩溃铁律，任一失效不阻塞主链路）：① 图片导入图标

  （importItemIcon）：图片必须落宿主 filesDir 才能被宿主渲染，模块进程导入无意义 →

  返回 false（UI 走 icon_edit_failed 提示）；② 私人空间建档/解锁（requestPrivateSpace）

  需宿主 Activity 鉴权 → 乐观回调，编辑器 app 列表空（privateApps 不跨桥，ps 状态

  行来自快照）；③ 弹层 widget 项（addPopupWidget）：宿主 widget host 特有 → no-op；

  ④ 快捷方式查询在模块进程无 shortcut host 权限 → DefaultLauncherRequired（原版

  同款提示）；⑤ 许可列表（LicensesRepository）：aboutlibraries 插件不引入 → 静态

  依赖清单（手工维护，GPL-3.0 自身条目在列）；⑦ 设置页时钟预览不含宿主导入的

  自定义时钟字体（ClockFontStore filesDir 跨进程不可达，typeface() file.isFile

  守卫 → 回落 preset/默认字体；导入入口仍在设置页可用，文件落模块目录仅设置页

  预览可见——hotfix1 修复 LocalHostContext 组合期 error 崩溃时一并登记）；

  ⑥ 安全区：宿主 receiver 导出

  （动态注册无法 uid 校验），伪造广播面 = 等同用户操作设置数据，AIDL 收紧同 P6 登记项

### folders 阶段（v2.9.0-grace-folders1 / versionCode 33，commit 665da1a，2026-10-09）

- [x] 原版语义核对（逐字）：数据 = Room folders 表 + 成员存 popup items JSON

  （owner `folder:<id>`，LauncherSettingsRepository.snapshots :18-33）；placement 双表面

  （Favorites/AppList/Both/None）；抽屉两种位置（按名内联 / appListAtBottom 底部 ◇ 区）；

  收藏区 favoriteItems = favoriteApps + inFavorites 文件夹项（LauncherViewModel.kt:183-195

  "Folder placement owns membership; shared order determines position"，新文件夹排末尾）；

  FolderPopup = ShortcutPopup 文件夹分支（header 点击→编辑、空态提示+编辑钮、

  已删文件夹自动关弹层 LauncherSheets.kt:172-174）
- [x] 恢复渲染：AppListModel 文件夹分支 / AppDrawerScreen FolderRow /

  HomeScreen 收藏区 folder 分支 / LauncherComponents FolderIcon+FolderRow+AppIcon

  folderId 兜底+LauncherRow trailing 参数
- [x] 宿主数据面：GraceHomeUiState.folders（combine(observeFolders, popups) 直读，

  原版映射逐字）+ folderItem + favoriteItems 派生；文件夹增删改实时跟随
- [x] 文件夹弹层：LauncherOverlay.Folder + FolderPopup.kt（同窗口 SwipeRevealPanel

  morph；成员路由层解析；点成员 launchApp 带 bounds 走 P5 动画）
- [x] 文件夹设置（重命名/placement/删除）→ 模块设置页 Folders 面板

  （openGraceSettingsInModule(context, "folders")，对齐 hotfix8 模式）
- [x] 编辑收藏页：文件夹入口恢复（FavoriteFoldersScreen → setFolderFavorites 直连宿主

  Room，同进程免桥）；快捷方式添加接通宿主 ShortcutRepository

  （DefaultLauncherRequired 降级解除——宿主持 HOME 角色，shortcut host 权限成立）
- [x] 补 4 字符串（open_folder / folder_actions / folder_contents_empty / recently_installed）
- [ ] **folders 阶段降级登记**：① 文件夹成员图标不做 ItemIcon 装饰（原版经

  uiState.folderItem 走 itemIcons，模块弹层暂用原始应用图标）；② 文件夹 reveal 拖拽

  morph 参数默认 no-op（点按即开弹层；行内拖拽展开随后续 soul 动画阶段）；

  ③ recentlyInstalledFolder / Private Space 文件夹载体仍随对应阶段

### folders hotfix2（v2.9.1-grace-folders2 / versionCode 34，2026-10-09）

装机反馈三连修复（文件夹已显示但设置不生效 / 弹层两个"+"不可用）：

- [x] **文件夹重命名不保存**（根因）：模块设置页 rename 对 folderId != null 的

  owner 走 RENAME（SP `label:`）—— 文件夹名在 folders 表，永不被消费。按原版

  renameApp 分支（LauncherViewModel.kt:589-596）：普通文件夹 → UPDATE_FOLDER{name}；

  PrivateSpaceFolderId → MUTATE_SETTINGS privateSpace.name；app/shortcut 保持 SP。
- [x] **弹层成员新增静默丢失**（根因）：宿主引擎 SAVE_POPUP 以 `defaults=emptyList`

  调 updatePopup，transform 内 keys 仅在 previous popup 行内匹配 → 新勾选成员

  （行不存在/不在 previous）全被丢弃。改为无命中时构造 `PopupItem(key)`（widget 项

  仍保序追加尾部，模块 keys 已滤 widget）。
- [x] **快捷方式"+"（模块设置页）**：模块进程无 shortcut host 权限 → 新增桥中继：

  `Cmd.SHORTCUTS_QUERY`（批量 appKey）→ 宿主 ShortcutRepository 代查 →

  `ACTION_SHORTCUTS_RESULT` 回包 → 模块 GraceShortcutsRelay（快照同生命周期动态

  receiver）+ SettingsViewModel.shortcuts 接通（单飞行批 + 会话内缓存；

  hasShortcutAccess 随快照 ok=true 置真）。快捷方式成员落库路径不变

  （REMEMBER_SHORTCUT + SAVE_POPUP）。
- [x] **弹层小组件"+"**：addPopupWidget 走 moveWidget 同款模式 —— 发

  `Cmd.ADD_POPUP_WIDGET(ownerKey)` + 关设置页；宿主 Route 收

  addPopupWidgetRequests 开 WidgetPicker 弹层模式；VM.addPopupWidgetTo：

  allocate → bindAppWidgetIdIfAllowed → `PopupItem("widget:<id>", HomeLayout)`

  落 app_popups（原版 WidgetSetupActivity.finishAdding popupOwner 分支同构）→

  配置页 fire-and-forget。
- [x] **桌面弹层 widget 渲染恢复**：FolderPopup 补原版 ShortcutPopup.kt:172-182

  widget 分支（rememberHostedWidget + HomeWidget，editing=false，长按进编辑）；

  宿主 UiState 透出 folderWidgets（combine popups widget 项）+ savedShortcutApps

  （saved_shortcuts 表 → LauncherApp，成员 shortcut key 解析）。
- [ ] **hotfix2 降级登记**：① 快捷方式跨桥无图标（ImageBitmap 不可 JSON 序列化）→

  选择列表/文件夹成员行用默认图标；② 文件夹弹层 widget 行排成员尾部（原版按

  popup JSON 顺序与 app 成员交错）；③ showInAppList 快捷方式的抽屉内嵌显示

  随 app-list 阶段；④ 抽屉 FolderRow 成员 shortcut key 解析未接（仅收藏区弹层）。

### folders hotfix3（v2.9.2-grace-folders3 / versionCode 35，2026-10-09）

装机反馈：弹层小组件选择完成后直接停在桌面，看不到添加结果。

- [x] **添加成功后回弹文件夹弹层**（根因）：设置页 onEdit 已把 `overlay=null`，

  WidgetPicker 选中 → addPopupWidgetTo 落库成功后无恢复动作 → 用户停在裸桌面。

  原版同进程语义 = WidgetSetupActivity finish 后 launcher 恢复、弹层仍在最上层、

  新 widget 即时可见。修复（GraceLauncherRoute）：① `lastFolderAnchor` 记录最近一次

  onOpenFolder 锚点（收藏区 + 抽屉两处）；② onSelect 弹层分支登记

  `popupWidgetReturn = ownerKey`；③ widgetSetupResults 成功且 returnOwner 非 null →

  重开 `LauncherOverlay.Folder(folder, lastFolderAnchor)`，widget 经 folderWidgets

  流即时透出（Room 流毫秒级，视觉上弹层打开即含新 widget）。
- [ ] **hotfix3 降级登记**：① 锚点恢复依赖同次宿主进程会话（进程被杀后锚点为

  Rect.Zero，reveal morph 定位退化但弹层功能不受影响）；② 带 configure 的 widget

  配置页仍 fire-and-forget 覆盖在弹层之上（原版同序）。

### notif1（v2.9.3-grace-notif1 / versionCode 36，2026-10-09）

装机反馈：桌面图标不显示通知。

- [x] **抽屉行内通知预览恢复**（根因）：P2 移植 AppDrawerScreen 时删掉

  `notifications` 参数（头注登记"P6 恢复"），P6 只恢复了 LauncherRow 组件分支与

  收藏区（HomeScreen.kt:261），抽屉这层漏接 —— 应用列表行永不出现通知预览。

  按原版 AppDrawerScreen.kt:66/152 逐字恢复：`notifications` 参数 +

  `LauncherAppRow(notification = notifications[item.app.packageName]?.firstOrNull())`
  - Route 传入 `state.notifications`。
- [ ] **设计语义澄清（对齐 Niagara/GraceLauncher）**：本桌面没有 MIUI 式角标圆点，

  通知以**行内预览**呈现（label · 时间 + 正文摘要 + 箭头），"打开/清除"走行右滑

  弹层附加行（menuEntriesProvider）。数据链：模块进程 NotificationListener →

  appNotifications 快照 → GraceBridgeEngine 指纹去重下发 → 宿主

  GraceBridgeReceiver → UiState.notifications（收藏区在 P6 已接通）。
- [ ] **前置条件**：模块 App 需要"通知使用权"（NotificationListenerService 授权），

  未授权 = 快照为空 = 全部行无预览（降级语义，不报错）。

### notif2（v2.9.4-grace-notif2 / versionCode 37，2026-10-09）

装机反馈：仍然没有通知显示，媒体仍不能控制，封面要过挺久才显示。

- [x] **宿主挂载快照拉取（SNAPSHOT_REFRESH）**（通知不显示 + 封面慢的共同根因）：

  GraceBridgeEngine 指纹去重是模块进程的持久单例状态，快照只在底层状态变化时重发

  —— 宿主桌面每次重启（装新 APK / 桌面进程被杀）都会错过重启前的一次性推送，

  要等到下一次新通知/切歌才有数据。修复：宿主 GraceHomeViewModel 挂载（receiver

  注册后）发 `Cmd.SNAPSHOT_REFRESH` → 引擎清全部指纹 → 重发当前通知快照（空也发，

  宿主据此清行）+ 当前媒体快照（含封面，publishMediaCurrent 与 observe 同一套

  指纹记账）+ refreshWeather()。
- [x] **命令回程 uid 校验加固**（媒体控制失效候选根因）：manifest 静态 receiver

  的派发路径上 `Binder.getCallingUid()` 不可靠（可能返回 system uid 而非发送方，

  宿主命令被误判 "dropped broadcast from untrusted uid" 丢弃）。API 34+ 改用

  `BroadcastReceiver.getSentFromUid()`（权威，防伪造），叠加原 getCallingUid

  双路径判定；uid 必须解析为 com.miui.home 不变。
- [ ] **装机复测锚点**：装好后若媒体控制仍失败，logcat 抓 `HomeMode4Miui` tag ——

  若出现 "dropped broadcast from untrusted uid" 说明 uid 校验仍未命中宿主

  （需升级 AIDL 绑定方案，见 GraceBridgeReceiver 头注收紧路径）。

### notif3（v2.9.5-grace-notif3 / versionCode 38，2026-10-09）

用户 logcat（logcat_media_control.txt）实锤三根因：

- [ ] **日志关键证据**：① 14:05:46 QQ 通知真实到达系统（第三方监听 app 都收到），

  引擎零动作、零 `ACTION_GRACE_NOTIFICATIONS` 下发；② 14:06:55 切歌引擎立即发

  媒体快照但 `art=0`（切歌却无封面）；③ "attach: reused existing view" ——

  宿主进程装新 APK 后**没有重启**，宿主侧注入代码仍是旧版。
- [x] **封面异步加载被指纹吞掉**（引擎实锤 bug）：封面由 repository artJob 在

  publish 之后异步加载，加载完成时 sessionId:revision 未变 → 引擎 artKey 相同 →

  `art=null` 且 json 相同 → 去重吞掉。封面只能等下一次播放状态变化"搭车"发出

  （"过挺久才显示"的根因）。修复：artKey 加入封面存在性

  `"$sessionId:$revision:${artwork != null}"`（observe 与 publishMediaCurrent 两处）。
- [x] **listener 服务未绑定自愈**：媒体链只需授权角色（MediaSessionManager

  getActiveSessions），通知链必须服务绑定（onNotificationPosted）——授权在、

  媒体工作、通知零反应即"服务未绑"状态（更新 APK/懒绑定/MIUI 省电解绑后可卡住）。

  引擎 ensureListenerBound()：`MediaAccess.isGranted && !mediaNotifications.connected`

  → `NotificationListenerService.requestRebind(component)`（60s 防抖），

  在 start() 与 refreshSnapshots() 触发。
- [ ] **⚠️ 装机前置（交付疏漏，本轮补登记）**：LSPosed 注入宿主进程的代码在宿主

  进程启动时加载 —— \*\*模块更新 APK 后必须强停 com.miui.home（或重启手机）再进

  桌面\*\*，否则 notif1 抽屉通知行 / notif2 挂载拉取等宿主侧修复全部不生效。

  日志中"reused existing view"即旧宿主进程未重启的直接证据。
- [ ] **封面残余降级**：loadArtwork 只接受 metadata bitmap / content|android.resource

  URI —— 播放器只给 http 或本地文件路径封面时无图（原版同语义，网络抓取禁止）。

### notif4（v2.9.6-grace-notif4 / versionCode 39，2026-10-09）

装机复测：**通知已通**（notif2/3 修复确认生效）。剩余两症结 + 用户日志实锤：

- [ ] **日志关键证据**：① 通知快照链路全程可见（引擎 send ↔ 宿主 bridge snapshot，

  双端打点齐全）；② 切歌快照 `art=5421` 已带封面字节且宿主已接收，但 UI 仍显示

  上一首封面（暂停后才换）；③ 整段日志**零** `GraceCommandReceiver` 打点 ——

  该 receiver 无论命令到达（hotfix8 诊断打点）还是被 uid 拒（dropped 日志）

  都必留痕，零输出 = 广播根本没派发到 receiver。
- [x] **媒体控制失效 = manifest 静态 receiver 被 MIUI/HyperOS 省电策略拦截**

  （根因定案）：后台模块进程收不到任何命令广播。反方向（模块→宿主快照）一直

  正常，正因为宿主 `GraceBridgeReceiver` 是动态注册。修复：从 AndroidManifest

  移除 `GraceCommandReceiver` 声明，改为 `GraceBridgeEngine.start()` 内动态注册

  （`ContextCompat.registerReceiver` + `RECEIVER_EXPORTED`，幂等；模块进程由

  listener service 常驻，start 时机覆盖全部命令窗口）。uid 校验语义不变。
- [x] **封面滞后 = 引擎指纹协议与 repository 异步封面加载语义错位**：切歌时

  repository 先发布 `artwork=null` 再由 artJob 异步加载（装机时序：art=0 快照 →

  补发带封面），而旧 `artKey(session:rev:hasArt)` 去重 + 宿主"沿用旧封面"合并

  让封面字节只能在部分状态跳变时搭车发出。修复（引擎 observe + publishMediaCurrent

  统一）：语义指纹 = `session|rev|title|artist|playing|can*|封面对象 identityHashCode`，

  **指纹变化即发送且总是携带当前封面字节**（JPEG 512px 编码 ≈几 ms，频率 ≤ 播放

  状态变化率）。`lastMediaArtKey` 字段删除；宿主合并逻辑保留作兜底。
- [ ] **降级登记**：引擎未 start（listener 未授权/模块进程死）时命令无人接收 ——

  动态注册前提是进程常驻；原 manifest 注册在此场景同样被 MIUI 拦截，无功能回退。

### notif5（v2.9.7-grace-notif5 / versionCode 40，2026-10-09）

装机复测：动态注册生效、命令**到达** receiver（"registered (dynamic)" + 逐次

"dropped"），快照/封面补发协议全程正常。卡点收敛为 uid 校验误杀：

- [ ] **根因**：AMS 派发广播时 `onReceive` 内 `Binder.getCallingUid()` \*\*恒为

  system_server（1000）\** 而非发送方；`getSentFromUid()`（API 34+）为权威，

  但设备实际 API / MIUI 转发都可能让双源落空 → 全拒。
- [x] 修复：`fromHost` 重写 —— 命中（任一候选 uid 解析出 com.miui.home）即放行；

  未命中时**打印完整诊断**（SDK_INT + 各候选 uid 的包解析 + myUid）后放行

  （insecure 标记）。下轮日志直接看到真实 sender uid，为收紧定案。
- [ ] **安全降级声明**：命令面低敏 —— 媒体控制需随机 UUID sessionId、通知 key

  不可枚举、天气/快照刷新无害，第三方注入面极小。**后续收紧路径 = AIDL 绑定**

  （onBind 的 `Binder.getCallingUid()` 对 binder 调用可靠，不受广播派发路径

  影响），随工程化阶段实施。

### notif6（v2.9.8-grace-notif6 / versionCode 41，2026-10-09）

装机复测：**媒体控制全通**（`mediaCommand -> true`），uid 诊断行定案：

`sdk=35`，`getSentFromUid() = -1`（**MIUI 广播派发不填充 sentFromUid**），

`Binder.getCallingUid() = 模块自身 uid` —— 两个来源都拿不到宿主 uid，

notifi5 放行策略是当前唯一可行路径（AIDL 收紧维持登记）。

本轮新问题：**通知行消失**。日志窗口内引擎零通知快照、无 requestRebind、

无 refreshSnapshots 打点，且尾部 `attach: reused existing view` 证明宿主

进程未随模块 APK 重装而重启 —— 时序推断：模块进程重启后 listener 重连

晚于宿主的最后一次快照拉取，宿主停在空快照上。

- [x] **宿主每次挂载都拉取快照**：`HomeController.attach()`（首挂 + 复用两条

  路径）发送 `SNAPSHOT_REFRESH`（`GraceBridge.snapshotRefreshIntent()` Java

  互操作）。此前只在宿主 VM init 发一次，宿主存活期内模块 listener 重连 /

  模块重装后的状态变化无法同步。
- [x] **通知链全打点**：listener seed 数量（`seeded=N active notifications`）、

  引擎每次通知快照下发（apps/entries/len）、ensureListenerBound 各分支

  （not granted / already connected / rebind）—— 下轮日志可直接区分

  "listener 未连"、"连了但 seed 空"、"seed 有但未下发"三类断点。

### notif7（v2.9.9-grace-notif7 / versionCode 42，2026-10-09）

notif6 复测 + 用户操作定性，根因**彻底定案**：

- [ ] **媒体≠通知的连接语义**：媒体链 `MediaSessionManager.getActiveSessions(component)`

  只要求授权 component 处于**启用**状态（服务可以没绑）；通知链必须

  NotificationListenerService **实际绑定**（onNotificationPosted）。所以出现

  "媒体一直正常、通知全死"的组合态。
- [ ] **日志实锤**：`send ACTION_GRACE_NOTIFICATIONS len=2`（len=2 = 空 JSON `{}`，

  `appNotifications.state` 恒空 = stores disconnected 后的清空值）；窗口内

  `refreshSnapshots` 调用 ensureListenerBound 但 "already connected /

  not granted / requestRebind" 三条日志全无 → 唯一可能 = \*\*静默的 60s 防抖

  分支\*\*（rebind 刚发过但 listener 至今没重连）。
- [ ] **用户操作定性**：重新授权权限 → 立即恢复；关掉模块页面再回来 → 又死。

  = MIUI 省电策略在模块退后台后**解绑**后台模块进程里的 listener，

  系统不自动重绑；重授权动作触发系统重绑所以恢复。原有代码 rebind 只在

  宿主挂载时被动触发一次、防抖分支零日志、断连不主动重绑 —— 后台一解绑

  就永远没人救。
- [x] **断连即重绑**：`onListenerDisconnected()` 调 `ensureListenerBound()`

  （原只清指纹）。
- [x] **30s 看门狗**：引擎 `startWatchdog()` 每 30s 检查

  `mediaNotifications.state.connected`，未连接即自愈（防抖挡重频）。
- [x] **防抖分支补日志**：`rebind debounced (Xs ago, attempts=N)`。
- [x] **component toggle 升级兜底**：连续 3 次 rebind 仍连不上 →

  `setComponentEnabledSetting(DISABLED→ENABLED, DONT_KILL_APP)` 强制

  NotificationManagerService 拆绑重绑（HyperOS 上 requestRebind 偶发不派发

  的实锤手段；瞬间闪断后系统自动重绑走 onListenerConnected）。
- [x] **重连登记**：listener `onListenerConnected` → 引擎

  `noteListenerConnected()`（清升级计数 + 打点）。
- [ ] **装机侧配套（用户操作项）**：模块应用给 MIUI「自启动」+ 省电策略

  「无限制」—— 自愈链是兜底，白名单是第一道防线。

### notif8（v2.9.10-grace-notif8 / versionCode 43，2026-10-09）

notif7 基础上追加 **root 保活**（用户确认：LSPosed 设备必然已 root，su 通道

现成）。防线前移 —— notif7 自愈链是"解绑后 1~2 分钟自愈"，root 保活目标是

"让 MIUI 根本不解绑"。

- [ ] **开关设计**：不做自定义设置页开关（设置页是整屏搬运的

  LauncherSettingsScreen，注入自定义行造成移植漂移）—— \*\*Magisk su 授权框

  即开关\*\*：授予 = 保活生效；永久拒绝 = su 失败静默停用，进程内不再重试。
- [ ] **触发**：引擎 `start()` 时 best-effort 执行一次（进程内幂等），全程后台

  线程 + 10s 超时强杀（Magisk 授权框挂起时 waitFor 不能阻塞 —— 读流另起

  线程，否则 readText() 卡在挂起进程上超时永不生效）。
- [ ] **手段清单**（`RootKeepAlive.kt` 新建，逐项隔离失败、逐项打点）：
  1. Doze 白名单 `dumpsys deviceidle whitelist +pkg`（标准 Android，可靠）；
  2. MIUI PowerKeeper `powerkeeper.db` per-app 省电策略（解绑 listener 的

     元凶；表结构随 HyperOS 版本漂移，多 SQL 变体尝试，sqlite3 缺失/表结构

     不符静默跳过）；
  3. MIUI 自启动 SecurityCenter provider（老版接口，纯尝试性）。
- [x] **降级链**：任一失败不影响其余；全失败 = 无 root，notif7 自愈链照常兜底

  —— 保活是增强不是依赖。
- [x] 装机验证：首次触发 su 授权框（授予）→ logcat 见

  `RootKeepAlive: su granted` + 三项结果打点。

### gate1（v2.9.11-grace-gate1 / versionCode 44，2026-10-09）

**使用前置条件门槛**（用户定案）：root / 通知权限等必须在【模块首页】确认

齐备后才允许使用第四桌面；权限丢失桌面自动回落原生。桌面（宿主）不做权限

引导 —— 引导与开关都在模块 App 首页（MainActivity）。

- **判定统一收敛**（`PermissionGate.java` 新建，宿主/模块两侧共用）：

  `customModeAllowed = 通知监听授权 && root 保活就绪`。SP 开关记用户意愿，

  allowed 决定实际生效 —— 权限补齐后自动恢复，无需重新开关。

  通知授权 = NotificationManager 公开查询（任意进程可查，宿主侧 5s TTL

  缓存防裁决高频穿透 binder）；root 就绪 = su 探测只能模块进程发起

  （Magisk 授权框 + 后台线程），结果经 `ACTION_SET_ROOT_READY` 广播落宿主

  SP（`custom_root_keepalive_ready`），宿主裁决链零开销读。
- **首页 UI**（MainActivity 重排）：前置条件区块置于模式开关之前

  （① LSPosed hook 生效 / ② 通知使用权 / ③ root 保活，绿红状态 +

  引导按钮），开关仅在三项齐备时可开；不齐时点击弹回 + Toast 指明缺项。
- **hook 存活探测**：首页 `ACTION_HOOK_PING` → 宿主 MainHook 回

  `ACTION_HOOK_LIVE`（ping/pong，800ms 超时按未生效回显）；宿主 hook

  安装完成时也主动广播一次（模块进程活着即收到）。
- **三层 gate 落点**：
  1. 首页开关（UI 拦截 + Toast）；
  2. 宿主 toggle 广播 receiver（开启前校验，防伪造/竞态直开）；
  3. **裁决 hook**（`mAdjudicationResultHook` + `isCustomModeActive` 回退

     分支）—— 权限被撤销（如系统设置里关通知授权）后，桌面无需重启自动

     回落原生；补齐后自动恢复。
- **onResume 自动回落**：首页发现"已开启但权限丢失"→ 自动发关闭广播 +

  Toast 说明缺项。
- **降级声明**：root 在 Magisk 侧被撤销时宿主 SP 标志不会自动翻 false

  （su 探测只在首页发生）—— 首页重新打开时探测刷新；裁决与通知 gate 不受

  影响（通知授权是实时查询）。
- RootKeepAlive 新增 `ensure(onResult)` 带回调探测入口（幂等 applied 标志，

  首页按钮用；引擎自动触发路径 `apply()` 保留）。

#### keep1（v2.9.16 / vc49）：模块进程后台保活（宿主拉起 + Magisk 守护）

- **装机实锤**：第四桌面可启动，但模块进程死在后台后无人拉起 —— 通知/媒体/

  天气快照全链依赖模块进程（listener + 引擎），进程死 = 桌面侧全部降级。

  notif7 自愈链只能在"进程还活着"前提下自救；gate5 的 root 三连里自启动

  provider（可靠性最低的一项）没兜住。
- **宿主拉起（主链路，root-free）**：`GraceKeepAliveService`（模块进程普通

  Service，onCreate/onStartCommand → `GraceBridgeEngine.start`，引擎 started

  标志幂等）。宿主 com.miui.home 几乎常驻前台，前台应用 startService 不受

  后台限制 —— 两个拉起点：
  1. `HomeController.attach()` 每次桌面挂载（用户回桌面即复活模块进程）；
  2. MainHook toggle receiver onReceive 开头（首页任何交互顺带确认）。

     `pokeFromHost` 60s 防抖；失败静默（自愈链兜底）。广播到不了死进程，

     所以拉起必须由宿主主动 startService（与 notif6 的 snapshot refresh

     广播是互补关系：广播管快照、startService 管进程）。
- **Magisk service.d 守护脚本（root 增强）**：RootKeepAlive 新增第 4 项

  `installBootScript()` —— 写 `/data/adb/service.d/hm4m_keepalive.sh`

  （内容 base64 传输规避 su -c 引号嵌套），开机后常驻循环每 30s

  `pidof` 检查，进程不在则 root 身份 `am startservice` 拉起（root 调用方

  不受后台 startService 限制）。重复安装覆盖写幂等；pkill 旧守护 +

  nohup 立即启动，不要求重启即生效。
- **服务声明**：manifest `exported=true` —— 仅暴露"幂等启动引擎"一个无害

  动作；跨包 startService（显式 setClassName）要求目标 exported。
- **降级链不变**：任一保活手段失效 → notif7 自愈链（看门狗/rebind/toggle）

  照常兜底；保活是前移防线不是依赖。

#### gate6（v2.9.17 / vc50）：MIUI 自启动纳入前置条件（第④项）

- **用户定案**：自启动是模块后台保活的前提（进程被杀后系统能否拉起），

  与通知授权/root 并列 —— 前置条件区块扩为四项。
- **探测通道**（`AutoStartProbe.java` 新建，模块进程查自己包）：MIUI/HyperOS

  私有 AppOps op 10008（AUTO_START，SecurityCenter 自启动开关的底层落点），

  反射 `checkOpNoThrow`（普通公开方法，无 gate5 实锤的跨包守卫问题）。
- **三态语义（防 gate3 式误锁）**：GRANTED → 绿；DENIED → 红，阻断 gate +

  引导跳 `com.miui.securitycenter/.permcenter.autostart.AutoStartManagementActivity`

  （路径漂移降级到应用详情页）；UNKNOWN（op 不存在/反射失败/机型差异）→

  黄色提示但【不阻断】—— HyperOS 版本漂移时宁可放行不锁死用户。
- **宿主侧判定**：与 root 标志同模式 —— 模块探测 GRANTED/UNKNOWN 后经

  `ACTION_SET_AUTOSTART_READY` 广播落宿主 SP（`custom_autostart_ready`），

  `PermissionGate.customModeAllowed` 扩为 通知授权 && root && 自启动；

  onResume 探测结果变化即广播（复用 gate4 补发语义）。宿主拒绝开启的

  reason 扩 `autostart_off` 分支，首页 Toast 对应提示。

### dualapp（v2.9.18 → v2.9.20-grace-dualapp / versionCode 51–53，2026-10-09）

**装机实锤**：MIUI 双开（XSpace user）的应用整体缺席应用列表与抽屉 —— 移植版

loadApps 主路径只查 `myUserHandle()`，而 MIUI 把双开 profile 经

`LauncherApps.getProfiles()` 下发给默认桌面，需逐 profile 枚举。

- [x] **主列表逐 profile 枚举**（`AppRepository.loadApps` 重写数据源）：原版

  GraceLauncher 用 `PackageManager.queryIntentActivities`（天然仅主 user）；

  移植版 P3 起已切 `LauncherApps.getActivityList`，本阶段扩为

  `launcher.profiles` 全量枚举 + 逐 profile 拉列表。健壮性三件套：

  ① `runCatching { launcher.profiles }` 失败降级仅主 user；

  ② **单 profile 失败不拖垮整体**（XSpace 受限/未就绪时 getActivityList 抛

  SecurityException，该 profile 记空集）；

  ③ LauncherApps 整体受限时回落 PM 兜底路径。
- [x] **key 隔离复用原版 profile 协议**：`LauncherApp.user/userSerial` 与

  `profile:<serial>:<cmp>` key 前缀是原版为 Private Space/managed profile

  设计的现成机制，本阶段直接复用 —— 主 user 不带 serial（key 维持无前缀，

  收藏/隐藏等既有数据全兼容），其他 profile 带 serial（同组件双开不撞 key

  互相吞）。`LauncherUiState`/`IconDesignerData` 原有的 `profile:` 过滤语义

  随模型自动生效。
- [x] **图标与启动链路**：`getBadgedIcon(0)` 对非主 profile 自动叠加系统角标

  （双开/工作资料标识）；`launch` 走 `startMainActivity(component, app.user,
  bounds, options)` —— 原版签名本就带 user，双开应用带 user 启动直达目标

  profile，P5 缩略图动画链路不受影响。
- [ ] **降级登记**：PM 兜底路径只能枚举主 user（PM 无跨 profile 查询能力）；

  双开应用参与排序/分组/搜索与主应用一致（`LauncherAppOrder`/拼音 sortKey

  不区分 profile）。

### ② GitHub 发布准备 —— ✅ 已完成（2026-10-09）

- [x] 仓库：\*\*<https://github.com/adaozi/HomeMode4Miui\\\\\\\\\\\\\\\*\\\\\\\\\\\\\\\*（Public，GPL-3.0），>

  首次入库 commit `b5fb742`（255 文件 / 31536 行），分支 main，tag

  `v2.9.20-grace-dualapp` 已推送。远程推送凭据走 Windows 凭据管理器

  （git credential fill），无需 gh CLI。
- [x] LICENSE 同步 **GPL-3.0**（含 GraceLauncher 衍生代码必须遵循 GPL-3.0

  分发，风险登记合规项闭环）
- [x] README 重写为"第四桌面模式 + GraceLauncher 全量移植"现状

  （进程模型 → 三类代码+桥 → Compose 宿主接桩四难题 → 跨进程桥+保活三闸

  → 权限前置 gate → 构建 → 已知限制），versionCode 53
- [x] 入库卫生：`.gitignore` 排除 `*.keystore` / `.build/` /

  `GraceLauncher-src/`（GPL 参考源，重下见本文 §1）/ `.workbuddy/`；

  `gradle-wrapper.jar` 刻意入库（`!` 覆盖排除）；`.gitattributes` 补齐

### ③ 收尾验证（顺手穿插）

- [x] P5 启动动画装机确认（thumbnail scale-up + Home 键复位，无异常即勾选）
- [x] 全程项装机验收：切回原生模式完整还原；GPU 过度渲染无静止重绘
- [x] 媒体控制命令闭环 ✅（notifi4~6 链路定案：manifest 静态 receiver 被 MIUI

  省电拦截 → 动态注册 + uid 放行策略，notif6 复测 `mediaCommand -> true`）

### ④ R8/minify（发布前体积优化）—— ✅ 已完成（2026-10-09）

- [x] 开 `minifyEnabled + shrinkResources`，规则见 `app/proguard-rules.pro`：

  Xposed 入口（`MainHook` + `IXposedHook*` 实现者）、`com.miui.home.**` 宿主同名桩、

  清单组件、Room 生成物一律保名；`com.homemode4miui.**` / `com.galaxyrio.gracelauncher.**`

  用 `-keepnames` 保可读类名 + `SourceFile/LineNumberTable` 保行号（异常栈可定位）。
- [x] **实测：31MB → 7.3MB（-76%）**；dex 27.38MB → 4.37MB（-84%）；

  单 dex 文件（R8 已合并）。对照：纯激进混淆（不保可读名）为 6.9MB，多出 0.4MB

  换取设备日志可读，属刻意取舍。
- [x] APK 签名校验通过（Signer #1：CN=HomeMode4Miui Debug）；关键类存活验证通过

  （MainHook / ModePrefs / HomeViewFactory / HomeController / LauncherModeController 均在）。
- [ ] **待装机回归**：模式切换 / 挂载熔断 / 桥广播 / Room 迁移四链路实机验证

### 建议顺序

> ②（发布合规）✅ 已完成（2026-10-09 已上 GitHub）→ 剩余：③ 收尾验证穿插
>
> → ① C 组遗留降级项 → ④ 装机回归四链路（模式切换/挂载熔断/桥广播/Room 迁移）。

### perf1（v2.9.22-grace-perf1 / versionCode 55，2026-10-10）

**功耗 + 冷启动专项**（用户反馈：功耗偏高、桌面重启后加载慢）。全量代码审计  
（挂载链 / 保活体系 / 常驻循环 / 监听 / SP / 每帧分配七类嫌疑逐项排查）后按  
优先级实施五项根因修复，全部带 perf1~perf5 标注：

- [x] **perf1 壁纸位图异步化（冷启动单点最重）**：`GraceLauncherRoute` 的  
  壁纸模糊层原在首组合 remember{} 主线程同步取整屏壁纸 drawable + 全分辨率  
  解码（4K 屏数十 MB）。改 LaunchedEffect + Dispatchers.IO 异步加载，并按  
  屏幕分辨率重绘（toBitmap(w,h)；模糊层细节本被 blur 抹平）。视觉零回退：  
  模糊层初始 alpha=0（抽屉打开才渐入），加载完成前本不可见。
- [x] **perf2 挂载主线程串行链消减**：① `markGraceComposePending` commit→apply  
  （崩溃安全：进程死亡在首帧 traversal，apply 异步落盘早已完成，熔断语义不变）；  
  ② 宿主 versionCode 进程级缓存（getPackageInfo 是同步 binder，复用路径零开销）；  
  ③ `GraceHomeViewModel` 构造中的 refreshShortcutAccess/refreshMediaAccess 两个  
  同步 binder 往返移入协程延后一拍（状态位到达前按"无权限"默认渲染，无视觉回退）。
- [x] **perf3 Launcher 生命周期同步（后台零周期唤醒）**：新增 hook 5b  
  `Launcher.onPause` → `HomeController.notifyHomePaused` → `GraceHomeView`  
  生命周期推 STOP；回前台由 onResume → onHomeAttached 复用分支自动补  
  ON_START/ON_RESUME。`HomeScreen` 时钟循环与 `TimeText` 通知时长循环（每条  
  带通知的收藏行各一个 60s 循环）包 `repeatOnLifecycle(STARTED)` —— 退后台  
  整体挂起。hook 未命中的宿主版本 lifecycle 恒为 RESUMED，行为同旧版（安全降级）。  
  widget 宿主 stopListening 随 ON_STOP 生效（对齐原生桌面行为，附带走电项）。
- [x] **perf4 root 守护脚本降频**：`hm4m_keepalive.sh` 轮询 30s→120s。  
  主保活链是 attach 时 pokeFromHost（回桌面即拉起，零延迟），root 脚本仅兜底；  
  待机期周期 fork（pidof）降 75%，兜底拉活最坏延迟 30s→120s 无实际体验差异。
- [x] **perf5 日期格式化器缓存**：`HomeClockHeader` 的 SimpleDateFormat ×2 +  
  getBestDateTimePattern（ICU 查询）remember(locale/week) 缓存，消除时钟每分钟  
  tick 与通知/媒体重组时的重复构造。
- [x] 审计确认无需改动：MediaSessionRepository/通知监听（纯事件驱动）、日历  
  （一次性查询）、天气（广播失效）、Room 初始化（协程内）、跨进程命令链  
  （异步广播）；`GraceBridgeEngine` 30s 看门狗仅 connected=false 时动作且进程  
  被冻结时 delay 不醒，保留原状（notif7 自愈链是解绑刚需，不弱化）。
- [x] 构建验证：assembleRelease 通过（首 builds 复盘一处 COMPOSABLE_INVOCATION  
  —— LocalLifecycleOwner.current 不能进 produceState producer，已移至组合层）。
- [ ] **待装机回归**：① 冷启动首帧体感（壁纸模糊层随抽屉渐入是否如常）；  
  ② 退后台后电池统计（bathtotal 曲线对比 v2.9.21）；③ 退后台 → 回桌面  
  时钟/通知时长即时恢复刷新；④ keepalive.sh 新周期生效（ps | grep hm4m）。

### scrubfix（v2.9.23-grace-scrubfix / versionCode 56，2026-10-10）

**快速滑字母条时其他字母应用行残留**（用户装机反馈）。根因定位到自托管  
Recomposer 的帧时序错误，两层修复（第一层根因，第二层布局兜底）：

- [x] **根因：自托管 Recomposer 用 `Dispatchers.Main` 导致重组滞后一帧**。  
  `DefaultChoreographerFrameClock.withFrameNanos` 的帧回调经  
  `CancellableContinuation.resume` 被 `Dispatchers.Main` 排成 Handler 任务，  
  在整帧 doFrame（含 measure/layout/draw）**之后**才执行；而 LazyList 的  
  measure（`requestScrollToItem` 消费跳转 + 新窗口行组合）跑在当帧 traversal，  
  用的是**上一帧**的"区段隐藏"组合结果（`retainItemSpace` 的 visible 是组合期  
  闭包）→ 快速 scrub 时每帧都是"跳到新区段 + 按旧字母隐藏"→ 旧字母行残留 /  
  新行缺失。上游独立 Activity 走 `windowRecomposer`（`AndroidUiDispatcher`  
  的 dispatchCallback 是 Choreographer.FrameCallback，帧回调内 trampoline  
  同步排空队列，重组先于 traversal 完成）故无此问题。修复：  
  `GraceHomeView.createRecomposer` 换官方同款 `AndroidUiDispatcher.CurrentThread`  
  （自托管场景的官方推荐路径，字节码级核对过 1.12.1 实现）。
- [x] **布局层兜底：`AppDrawerScreen.retainItemSpace` 拆双参**。新增  
  `selectedLetterState: State<String?>` 参数（Route 侧保留原始 MutableState +  
  `var selectedLetter by selectedLetterState` 委托，不能用 rememberUpdatedState  
  —— 那仍是组合期写入）；`liveVisible: () -> Boolean` 在 placement 块内实时读  
  快照（snapshot-observed 重放置，`Modifier.offset{}` 同款语义）：状态变化只  
  触发重放置、不依赖重组时序，scrub 跳转与区段隐藏严格同帧。  
  `composedVisible` 仅驱动 `clearAndSetSemantics`（一帧滞后仅影响 TalkBack）。
- [ ] **待装机回归**：快速滑字母条（上下两方向 + 抽屉隐藏态触摸唤起），  
  确认无旧字母行残留/闪烁；顺带观察动画跟手性（重组不再滞后一帧，  
  drawerAlpha 淡入应更贴合）。

---

## 9. 上游同步 v1.1.4 → v1.1.8（2026-10-11，参考源已替换，代码待移植）

### 9.1 上游版本动态（2026-10-08 ~ 10-10 连发 4 版）

| 版本     | 日期    | 核心内容                                                                                                                                               |
| ------ | ----- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| v1.1.5 | 10-08 | Grace Button 重做（手势/动作/动画）；**桌面手势**：上滑/下滑/双击；Icon Designer 旋转图标；**Work Profile 支持**                                                                 |
| v1.1.6 | 10-09 | 双击与快捷方式展开冲突修复；动画时长+手势手感调整（实验开关 Settings > Advanced > **Lab**）；**PWA 与应用生成 widget**；**字体管理器**（可删导入字体）；**Favorites & App List 编辑器**（左侧 A-Z 滑动控制应用列表） |
| v1.1.7 | 10-10 | 搜索框下拉关闭；双击与 Pop-ups 冲突再修；**导航栏沉浸**；Icon Designer 适配非标准图标；**文件夹左滑打开内部第一项**；Grace Button 可打开文件夹；**Dim Wallpaper 自定义遮罩颜色**；A-Z 列表更多自定义                |
| v1.1.8 | 10-10 | 紧急修复：西语等语言 Hide Apps 崩溃；A-Z 列表默认设置显示错误；Grace Button 部分设备颜色/阴影问题                                                                                    |

参考源已通过 gh-proxy 拉取 v1.1.8 tag 并整目录替换 `GraceLauncher-src/`（506 文件，5.5MB zip）。

### 9.2 影响面量化（上游 diff × 模块 118 个移植副本）

- 上游 main .kt：**67 变更 + 16 新增 + 2 删除**；
- 与模块副本交集：**55 个文件需同步**（上游累计 **+1794 / −778 行**）；
- 16 个全新文件（约 1952 行）：PinItemActivity / PinItemViewModel / GraceSystemActions / GraceButton / GraceButtonTransition / GraceButtonIcon / LauncherListGestures / data: AlphabetAppearance / ListAppearance / ProfileSettings / ShortcutsFoldersSettings / GraceButtonIcon；settings: AlphabetSettings / ListAppearanceSettings / LabSettings / ProfileSettings / ShortcutsFoldersSettings；
- **上游删除 PrivateSpaceSettings×2**（模块仍有副本）——合并进新 `ProfileSettings`（Private Space + Work Profile 统一 profile 模型，常量 PrivateSpaceFolderId 等迁移到 ProfileSettings.kt）；
- 6 个上游变更文件模块无对应副本（SettingsActivity / WidgetSetupActivity / LauncherAppTheme / LauncherScreen / LauncherViewModel / LauncherSheets）——模块用自建适配层（GraceHomeViewModel / GraceLauncherRoute / GraceHomeRoot 等），其**行为变化需人工核对**（重点：LauncherViewModel 手势分发、LauncherSheets 双击冲突修复、LauncherScreen Lab 参数透传）。

### 9.3 Room schema：v18 → v21（模块库零破坏，照搬三条加列 migration）

| migration | 列                                      |
| --------- | -------------------------------------- |
| 18→19     | `workProfileJson`、`homeGesturesJson`   |
| 19→20     | `fontLibraryJson`、`listAppearanceJson` |
| 20→21     | `shortcutsFoldersJson`                 |

全部是 launcher_settings 加列（可空/带默认值），模块侧 `LauncherSettingsRepository` 大模型 read-modify-write 增量桥兼容；`LauncherSettings` 模型新增 5 个 embedded 字段：`fontLibrary` / `homeGestures` / `listAppearance` / `shortcutsFolders` / `workProfile`。

### 9.4 移植批次（建议顺序，全部遵循三角色与逐文件三方合并）

| 批次                                   | 内容                                                     | 关键文件（上游）                                                                                                                                                                                                    | 量 | 风险点                                                                               |
| ------------------------------------ | ------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | - | --------------------------------------------------------------------------------- |
| **S1 数据层先行**                         | schema v21 + settings 5 新字段 + ProfileSettings 统一模型     | LauncherDatabase(+27) / LauncherSettings / LauncherSettingsRepository / LauncherPreferences / 新增 ProfileSettings / AlphabetAppearance / ListAppearance / ShortcutsFoldersSettings；PrivateSpaceSettings 删除迁移 | 小 | 模块 PrivateSpaceSettings 副本需同步删除/改引用                                               |
| **S2 A-Z 列表增强 + 列表外观**               | AlphabetRail 大改（+176/−130）、listAppearance 行内边距可调、编辑器增强 | AlphabetRail / AppSelectionScrollbar / AppDrawerScreen(+34) / HomeScreen contentPadding / AlphabetSettings / ListAppearanceSettings / AppListModel(+14)                                                     | 中 | scrubfix 自托管 Recomposer 修复保留，勿被上游覆盖                                               |
| **S3 桌面手势 + Lab**                    | 上滑/下滑/双击手势（GraceButtonGesture 目标动作）、搜索框下拉关、动画手感实验开关    | GraceSystemActions(80 行) / LauncherListGestures(185 行) / HomeScreen 接线 onHomeGesture+observeBlankDoubleTaps / LabSettings / AppSearchScreen(+38)                                                            | 中 | 与 MIUI 手势导航冲突面；手势隔离铁律（gesture belongs only to its own View）；宿主动作（回桌面/通知栏）需宿主侧执行入口 |
| **S4 Grace Button 重做**               | 新按钮组件+过渡动画+可开文件夹+颜色阴影修复                                | GraceButton / GraceButtonTransition / GraceButtonIcon / GraceButtonSettings(+93) / GraceButtonSettings.kt(settings +148)                                                                                    | 中 | 纯渲染+少量宿主动作                                                                        |
| **S5 字体管理器**                         | 导入字体可管理/删除                                             | FontSettings(+204) / LauncherFonts(+94) / ClockFontStore(+8) / fontLibrary 字段                                                                                                                               | 中 | 时钟字体跨进程 filesDir 限制（C 组降级登记项）依旧适用                                                 |
| **S6 Icon Designer 增强**              | 图标旋转 + 非标准图标适配                                         | IconDesigner* 5 文件 / IconDesign / IconDesignRenderer(+51) / ItemIconStore                                                                                                                                   | 中 | 纯渲染面                                                                              |
| **S7 弹层/文件夹/外观修复**                   | 双击×Pop-ups 冲突、文件夹左滑开首项、dimWallpaper 自定义遮罩色、导航栏沉浸       | PopupSwipeIntent / ShortcutPopup(+45) / LauncherComponents(+117) / LauncherAppearance(+14) / Theme(+4) / LauncherLayout(+26)                                                                                | 小 | Popup 子树禁 stringResource 规则沿用                                                     |
| **S8 PWA / pin item / Work Profile** | PWA 与应用生成 widget、pin item 支持                           | PinItemActivity / PinItemViewModel / AppRepository(+26) / HomeWidgetHost(+9) / WidgetSetupActivity 行为核对                                                                                                     | 大 | 宿主进程适配风险最高：pin shortcut 请求 hook 面、PWA 会话查询；**缓后，独立降级开关**                          |

顺带白拿的修复（随所属批次）：v1.1.8 A-Z 默认设置显示错误、Grace Button 颜色/阴影、多语言 Hide Apps 崩溃（strings fallback 语义）。

### 9.5 移植纪律（本轮新增强化）

1. **禁止整文件覆盖**：模块 55 个副本全部是裁剪+适配版（R import → com.homemode4miui、hotfix2 Popup 规则、LocalHostContext/LocalModuleContext、scrubfix 双参 retainItemSpace、裁 widget/media 分支等）。每文件走 **v1.1.4 原版 → 模块副本 → v1.1.8 原版** 三方合并，逐 hunk 决策；
2. 上游新增文件若含宿主侧行为（filesDir/Activity/系统动作），引用点必须走 LocalHostContext（§5 风险登记第一条）；
3. 新增设置项落 Room 走 §9.3 三条 migration，模块进程设置页经增量桥自动生效（C3 链路零改动）；
4. 每批次独立可降级，坏了不阻塞主链路；批次完成后 commit + tag + build-fix 闭环。

### gestures1（v2.9.25-grace-gestures1 / versionCode 58，2026-10-11）—— §9 落地：S3 桌面手势 + Lab

- [x] **手势检测**：LauncherListGestures.kt v1.1.8 整文件（LauncherListGestureConnection 取代  
  HomeOverscrollConnection，原生 stretch 效果保留；零消费观察，行内拖拽/widget/滚动不被打断）；  
  HomeScreen 接线 onHomeGesture + observeBlankDoubleTaps（双击；弹层禁用时保持节点挂载防连坐取消）；  
  AppSearchScreen 顶部下拉关闭（v1.1.7 特性；swipeSensitivity 显式参数由 Route 透传）。
- [x] **动作分发**：Route performGestureAction（上游 performGestureAction 语义裁剪版）——  
  Search/AppList/App/Shortcut/Folder/Agenda/Settings 宿主进程内直达；  
  GraceHomeUiState apps + savedShortcutApps 双池按键解析。
- [x] **系统动作跨进程**：GraceSystemActions.kt（Website/Assistant 宿主进程直接 startActivity）；  
  LockScreen/Notifications/QuickSettings 走 **GRACE 桥 Cmd.SYSTEM_ACTION**（arg1=action.name）→  
  模块 App 进程 GraceAccessibilityService.perform（manifest 注册服务 + xml 配置，无事件订阅/无窗口  
  内容读取/无手势注入，仅 performGlobalAction 三动作）。
- [x] **设置面**：GraceButtonSettings 页 v1.1.8（home 双面：按钮/手势；GraceButtonActionSettings  
  目标编辑：App/Shortcut/Folder/Website/系统动作 + GraceSystemAccessDialog 引导）；  
  参数化路由 GraceAction/{gesture}?home={home}（旧 GraceTap\*/GraceLong* 四页移除）；  
  LabSettings（Advanced 页 = Lab 入口：动画时长/滑动灵敏度/双击间隔，带重置）；  
  ProductivitySettings Gestures 行恢复（S2 降级登记 ① 消除）。
- [x] 构建：assembleRelease 通过，7.92MB（R8 后），versionCode 58。
- [ ] **装机回归**：① 桌面上滑/下滑/双击触发（homeDefaults：上滑=搜索、下滑=通知栏、双击默认关）；  
  ② 无障碍服务授权后锁屏/通知栏/快捷设置三动作跨进程生效（设置 → 更多设置 → 已下载的无障碍服务）；③  
  搜索页顶部下拉关闭；④ Lab 灵敏度/双击间隔调节实时生效；⑤ 行内拖拽弹层/桌面滚动与手势零冲突。
- [ ] **降级登记**：① 无障碍服务未授权时三系统动作静默失效（bridge 侧安全丢弃，UI 引导随  
  GraceButton 页 GraceSystemAccessDialog，需用户手动到系统设置开启）；② GraceButtonTransition  
  英雄动画（搜索/抽屉/锁屏过渡）随 S4 接线，当前手势动作瞬时直达；③ GraceButton 按钮本体渲染  
  尚未挂上桌面（组件与动画已就位，随 S4）。

### hostact（v2.9.29-grace-hostact / versionCode 62，2026-10-11）—— 无障碍依赖降级为第三层兜底（逆向实锤）

- [x] **逆向确认**（MiuiHomeOS3.apk + MiuiHomeOS2.apk，androguard 双版本一致）：
  - 锁屏 = MIUI 桌面双击锁屏原生广播（SuperDraglayer$DoubleTapListener.onDoubleTapEvent / 旧版
    Workspace.onDoubleTap 逐字复刻）：`sendBroadcast(Intent("com.miui.app.ExtraStatusBarManager.action_TRIGGER_TOGGLE")
    .addFlags(FLAG_ACTIVITY_NEW_TASK).putExtra("...extra_TOGGLE_ID", 10))`，TOGGLE_ID=10 双版本一致；
  - 通知栏 = `LauncherUtils.expandStatusBar(Context)` 原生复刻：getSystemService("statusbar") + 反射
    expandNotificationsPanel；快捷设置 = 同 manager 反射 expandSettingsPanel。
- [x] **三层兜底链**（GraceSystemActions.perform）：MIUI 原生（HostSystemActions，宿主/模块进程均可用）
  → root（GraceRootActions：su input keyevent 26 / cmd statusbar expand-*，fire-and-forget worker）
  → 无障碍服务（GraceAccessibilityService，保留为最后兜底）。无障碍授权从"必需"降级为"可选兜底"。
- [x] 接线更新：Route 通知栏/快捷设置/锁屏提交改走 GraceSystemActions（原生直调，不再跨进程桥委派）；
  桥 Cmd.SYSTEM_ACTION 保留（receiver 侧同三层链），作为外部触发备用通道。
- [x] 构建：assembleRelease 通过，8.02MB，versionCode 62。
- [ ] **装机回归**：① 手势/按钮触发锁屏（黑场过渡 → MIUI 原生锁屏广播 → 灭屏；无残留遮罩）；
  ② 下滑手势/按钮拉通知栏、快捷设置（无 su 授权弹窗 = 原生层已生效）；③ 无障碍服务【不授权】时三动作
  依然可用（原生层独立工作）；④ su 拒绝授权场景回落无障碍不崩。
- [ ] **降级登记更新**：① 无障碍服务授权降为可选（S3 登记① 失效）；② 非 MIUI ROM 上原生广播层失效
  （ExtraStatusBarManager 为 MIUI 私有），自动落 root/a11y 层——本模块定位 MIUI 专属，无实际影响面；
  ③ goToSleep/DevicePolicyManager 层已在逆向确认后移除（原生 toggle 广播即原版唯一实现，无需备胎）。

### 装机回归总清单（v2.9.29 / vc62，2026-10-11 合并版——按测试流排序）

**准备（必做）**：安装 vc62 → **强停 com.miui.home 或重启桌面**（首次启动触发 Room v18→v21 迁移）。
反馈载体：`adb logcat -s HomeMode4Miui`（ModLog 全打点）+ 截图；任何崩溃直接贴堆栈。

**T0 冒烟（任一失败即阻断后续）**
- [ ] 桌面正常渲染：收藏区/时钟/天气/widget 区齐全，无崩溃无白屏
- [ ] 抽屉/搜索/设置入口可用；重启桌面第二次 → 全部设置项保留（时钟/收藏/图标包/主题 = v21 迁移无损）

**T1 新功能主线（本批新增）**
- [ ] A-Z 列表页（设置）：字形/间距/自由移动调节 → 预览与桌面 rail 实时生效
- [ ] 列表外观页：行距/边距/字号/名字可见性 → 桌面收藏+抽屉行即时刷新（默认值 = 旧视觉，零回退）
- [ ] 桌面手势：上滑=搜索、下滑=通知栏（默认配置）；双击默认关 → Lab/手势页开启后生效
- [ ] Grace Button：右下渲染；点按/长按动作；动画开 = hero 展开、关 = 瞬时直达；编辑态拖拽+完成键
- [ ] **系统动作三件套（重点，无障碍不授权状态下测）**：锁屏（黑场过渡→灭屏→亮屏无残留遮罩）、
      下滑/按钮拉通知栏、拉快捷设置 —— 无 su 弹窗 = MIUI 原生层生效
- [ ] 字体管理器：Themes → 字体 → 导入/排序/删除；字体库 selected 应用到桌面文本（回退链逐级生效）
- [ ] 遮罩色：列表外观页自定义遮罩色 → 抽屉/搜索壁纸遮罩即时变色
- [ ] 文件夹左滑开首项：ShortcutsFolders 页开启后，收藏/抽屉文件夹行左滑直启第一个应用

**T2 回归（老功能不破坏）**
- [ ] A-Z scrub 跳转（含隐藏态触摸即现）；行内拖拽弹层 reveal/expand 不受手势连接器影响
- [ ] 抽屉行通知预览按 ShortcutsFolders 显示策略过滤（silent/expanded/hidden）
- [ ] 图标设计器：旋转/非标准图标/GraceButton 图标设计面
- [ ] 双击锁屏冲突面：无障碍三动作在 su 拒绝授权时回落不崩；多语言（非中英）设置页无崩溃
- [ ] 锁屏后亮屏：黑场遮罩无残留（OEM 兜底启发式）

**触发开关备忘**：无障碍服务在本版为**可选兜底**（原生层独立工作）；su 授权弹窗出现 = 原生层未生效，直接报。
