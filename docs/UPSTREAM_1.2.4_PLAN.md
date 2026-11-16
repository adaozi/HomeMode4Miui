# 上游对齐计划：GraceLauncher v1.2.0 → v1.2.4（P0~P13）

> 制定于 2026-11-16，基准上游 **v1.2.4（versionCode 16，2026-11-13）**。
> 上游仓库：<https://github.com/Galaxy-rio/GraceLauncher>（GPL-3.0）
> 本仓：`main` @ `v2.9.168-p3-weather-click`

---

## 1. 目标与原则

| 项 | 约定 |
| --- | --- |
| 范围 | 全量对齐 v1.2.4（User 2026-11-15 拍板），不挑功能 |
| 节奏 | **每批构建 + 装机验收**，验收通过才 commit + tag + push |
| 优先级 | **零崩溃 > 行为一致 > 新特性** |
| 偏离 | 只允许两类：① 宿主进程适配（Context/资源/权限）；② 上游明确缺陷（须在 `docs/PORTING.md` 登记并尽量上报上游 issue）。其余一律跟上游 |
| 铁律 | 新增设置字段必查 4 条线；新增 UI 文案必须独立 string 名；语义化颜色不硬编码 |

### 4 条线（新增任何设置项都要过一遍）

1. 模型 data class（`LauncherSettings` + 桌面侧 `GraceHomeSettings`）
2. Room entity + 迁移（`LauncherSettingsEntity` + `MigrationNXToNY`）
3. 模块 → 宿主 encode（`GraceSettingsBridge.encodeRow` / `diffRow` / `applyDelta`）
4. 宿主 → 模块快照 decode + VM 从 Room 行构建（`GraceSettingsRepository` 三处映射 + `decodeRow`）

---

## 2. 当前进度

| 批次 | 版本 | 内容 | 状态 |
| --- | --- | --- | --- |
| P0 | 2.9.164 / vc180 | 源码 bump v1.2.4、DB v24→v34、19 个新数据类、139 个图标、539/538 条文案 | ✅ 已上线 |
| P1 | 2.9.165 / vc181 | 字体颜色集中管理（桥补齐 13 字段 + 色板/取色/壁纸文字色） | ✅ 已上线 |
| P2 | 2.9.166 / vc182 | 时钟区域化（ClockRegions 渲染 + 三页签编辑器 + 设置预览脚手架） | ✅ 已上线 |
| P3 | 2.9.168 / vc184 | 首页区块合并（Clock+Calendar）、天气点击、日期文字色 | ✅ 已上线 |

**剩余缺口**（截至 P3 完成，对比上游 245 个 `.kt`）：

| 指标 | 数量 |
| --- | --- |
| 已完全一致 | 50 |
| 有内容差异 | 119（合计约 9700 行） |
| 模块未移植 | 76 |

---

## 3. 批次总览

| 批次 | 主题 | 上游归属 | 文件 | 改动行 | 新增文件 | 风险 |
| --- | --- | --- | --- | --- | --- | --- |
| P4 | 应用列表 + 抽屉 + 搜索 | 1.2.3 | 26 | 2182 | 10 | **高** |
| P5 | Agenda 面板 + Tasks.org | 1.2.4 | 14 | 498 | 11 | 中高 |
| P6 | 天气（Gadgetbridge + 逐小时图表） | 1.2.3 / 1.2.4 | 14 | 271 | 11 | 中 |
| P7 | 小组件（Widget Screen + 尺寸/配置） | 1.2.0 / 1.2.1 | 17 | 347 | 11 | 中高 |
| P8 | 音乐（media3 + 多风格） | 1.2.3 | 12 | 488 | 7 | 中 |
| P9 | 应用类别 + 数字健康 | 1.2.2 | 4 + 契约改造 | — | 4 | 中 |
| P10 | 图标设计器 + 快捷方式设计 | 1.2.1 | 22 | 1155 | 1 | **高** |
| P11 | 首页契约切换（HomeScreen 归位） | 架构 | 1 + 适配层 | 639 | 0 | **高** |
| P12 | 设置页重组 | 1.2.2 | 8 | 1395 | 0 | 中 |
| P13 | 弹层打磨 + 长尾与平台 | 1.2.1~1.2.4 | 20+ | ~1400 | 8 | 中 |

---

## 4. 每批详细

### P4 应用列表 + 抽屉 + 搜索（1.2.3「应用列表更多自定义」）

**为什么先做**：`AppRepository` 是应用装载中枢，P9（应用类别）、P10（图标）都依赖它的新能力（clone profile、类别过滤、usage）。放最后会返工。

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `data/AppRepository.kt` | 864 行 | **最深分叉**。模块定制：两段式装载（先图标后装饰）、`IconSnapshotCache`、缓存命中策略。上游新增 clone profile / 英文标签 / 类别过滤 |
| `ui/drawer/AppDrawerScreen.kt` | 222 | 抽屉主体（模块有 `retainedPage` 可见性同构定制） |
| `ui/drawer/AppListModel.kt` | 122 | 列表模型 + 分组 |
| `ui/search/AppSearchScreen.kt` | 269 | 搜索页（模块有 `appsWithShortcuts` 定制） |
| `data/SearchIndex.kt` | 61 | 搜索索引 |
| `ui/components/AlphabetRail.kt` | 78 | A-Z 条：RTL、触控区域、类别分段 |
| `ui/components/LauncherListGestures.kt` | 61 | 列表手势（双击动作） |
| `ui/components/AppStack.kt` + `data/AppStack.kt` | 58 | 应用堆 |
| `ui/settings/ListAppearanceSettings.kt` + `ListAppearancePreview.kt` + `ListAppearancePreviewPopup.kt` | 242 + 新 | 列表外观（模块缺 showRecentlyInstalled/Neither/AppListLayout 的渲染） |
| `ui/settings/AlphabetSettings.kt` | 154 | A-Z 设置（触控宽度、双击动作） |
| `ui/settings/ShortcutsFoldersSettings.kt` | 新 | 弹层/文件夹策略页（上游从 ProductivitySettings 拆出） |
| `ui/settings/AppStackSettings.kt` | 新 | 应用堆独立页 |
| `data/LauncherAppCache.kt` / `CatalogRefreshState.kt` / `EnglishLabelReader.kt` / `RussianRomanization.kt` / `icons/IconSnapshotCache.kt` | 新/小 | 上游缓存与标签辅助 |
| `ui/components/AppListSectionHeader.kt` / `AppSelectionList.kt` / `LauncherSearchBar.kt` / `AppSelectionScrollbar.kt` | 新/小 | 列表组件 |

**验收**：抽屉打开/滚动/字母条拖拽/双击锁屏、搜索过滤隐藏应用、快捷方式进列表、应用堆渲染、列表外观五项设置即时生效。

---

### P5 Agenda 面板 + Tasks.org（1.2.4）

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `ui/overlays/AgendaSheet.kt` | 487 | 面板整体重写：日分组、逐小时天气、任务区、新建按钮 |
| `ui/overlays/AgendaPanel.kt` / `AgendaDayHeader.kt` / `AgendaCreateButton.kt` | 新 | 拆出的子组件 |
| `ui/overlays/LauncherSheets.kt` / `LauncherSheetWindow.kt` | 新 | **弹层窗口框架**（影响所有 sheet 的呈现/拖拽手感） |
| `ui/settings/AgendaAppearanceSettings.kt` / `AgendaDailyContentSettings.kt` / `AgendaDailyPreview.kt` / `AgendaHourlyWeatherSettings.kt` / `AgendaHourlyWeatherPreview.kt` | 新 | Agenda 外观与内容配置页 |
| `ui/settings/TasksSettings.kt` | 新 | Tasks.org 集成配置 |
| `ui/weather/DailyWeather.kt` / `HourlyWeather.kt` | 新 | 取代 P2 的兼容层（**本批要删掉 WeatherComponents 里的 DailyWeather/HourlyWeather/HourlyWeatherCell**） |
| `ui/home/ClockAgendaContent.kt` / `ui/weather/WeatherAgenda.kt` | 小 | 小改 |

**前置**：`data/tasks/*`、`CalendarPreferences`、`AgendaAppearance`、`AgendaEntry`、`ClockTasks` 已在 P0 落库。
**风险**：`LauncherSheetWindow` 是公共弹层框架，改坏会影响 ShortcutPopup / 搜索 / 文件夹面板。
**验收**：打开 Agenda 面板、日分组滚动、逐小时天气展开、任务打勾（需装 Tasks.org）、新建日程、面板拖拽关闭手感。

---

### P6 天气（Gadgetbridge + 逐小时图表）

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `data/weather/BreezyWeatherParser.kt` / `BreezyWeatherRepository.kt` | 新 | 上游从 `WeatherModels` 里拆出解析层 |
| `data/weather/GadgetbridgeWeatherParser.kt` / `GadgetbridgeWeatherRepository.kt` | 新 | Gadgetbridge 协议（Weather Master 等） |
| `platform/GadgetbridgeWeatherReceiver.kt` / `BreezyWeatherUpdates.kt` | 新 | 广播接收 + 更新检查 |
| `ui/weather/WeatherComponents.kt` | 119 | 新签名 `WeatherSourceNote`（P2 已合一半） |
| `ui/weather/HourlyWeatherChart.kt` / `HourlyWeatherFields.kt` | 新 | 1.2.4 的逐小时图表（柱状/折线可切换） |
| `ui/settings/WeatherSettings.kt` | 150 | 数据源选择 + 连接配置 |
| `ui/settings/WeatherAppSettings.kt` | 新 | 天气应用选择页 |

**风险**：新增外部数据源，必须保证 provider 不可用时**静默降级**（宿主进程崩不起）。
**验收**：小米天气源正常、切到 Gadgetbridge 源能读、逐小时图表切换、断源时桌面不崩不空白。

---

### P7 小组件（Widget Screen + 尺寸/配置兼容）

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `ui/home/WidgetScreen.kt` | 453 | 主页右侧的网格 Widget 页（1.2.0 引入，本仓当年放弃） |
| `ui/home/WidgetGridState.kt` | 新 | 网格状态 |
| `platform/WidgetSizing.kt` / `WidgetCompatibility.kt` / `WidgetConfigurationRouting.kt` / `WidgetLongPressTimeout.kt` | 新 | 1.2.1「小组件添加/修改兼容性」核心 |
| `ui/widgets/HomeWidget.kt` | 133 | 尺寸手柄 + resize |
| `ui/widgets/WidgetPicker.kt` | 109 | 选择器 |
| `ui/widgets/WidgetConfigurationButton.kt` / `WidgetLongPressConfiguration.kt` | 新 | 配置入口 |
| `ui/overlays/HomeWidgetSheet.kt` / `PopulationWidgetEditor.kt` | 变 | 弹层内 widget 编辑 |
| `WidgetSetupActivity.kt` | 新 | 配置 Activity（跨进程） |
| `ui/settings/WidgetScreenSettings.kt` | 新 | Widget Screen 设置页 |

**风险**：新页面 + 新 Activity + AppWidgetHost 交互，宿主进程内 `bindAppWidgetId` 失败要能回退。
**验收**：添加小组件、拖动/缩放、更改配置、Widget Screen 进出、删除清理 appWidgetId 不泄漏。

---

### P8 音乐（media3 + 多风格）

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `build.gradle` | 新依赖 | **引入 androidx.media3**（`MediaShuffleController` 需要） |
| `ui/home/HomeMediaPlayer.kt` | 302 | 多风格（1.2.3）+ 封面配色 |
| `ui/home/MediaPlayerControls.kt` / `MediaPlayerMotion.kt` / `MediaAlbumColors.kt` / `HeadphoneConnection.kt` | 新 | 拆出的控制条/动画/取色/耳机检测 |
| `data/media/MediaSessionRepository.kt` / `MediaPlayback.kt` / `MediaNotificationListener.kt` | 88 / 27 / 63 | 播放控制、随机播放 |
| `data/media/MediaShuffleController.kt` | 新 | media3 MediaController 封装 |
| `ui/settings/MediaPlayerAppearanceSettings.kt` / `MediaPlayerStyleControls.kt` | 新 | 样式配置页 |

**风险**：新增依赖 + 通知监听链路；`MediaNotificationListener` 有模块定制（解绑保留快照）要保住。
**验收**：播放/暂停/上下曲/随机、封面取色、样式切换、无会话时常驻占位、耳机插拔。

---

### P9 应用类别 + 数字健康（1.2.2）

| 内容 | 说明 |
| --- | --- |
| `ui/settings/AppCategorySettings.kt`（新） | 类别列表 + 编辑器（条件、放置、图标、上限） |
| `ui/components/CategoryIcon.kt`（新） | 类别图标 |
| `ui/settings/DigitalWellbeingSettings.kt` / `ui/components/UsageLabel.kt`（新） | 应用使用时长 |
| `LauncherUiState` 补 getter | `appCategories` / `homeItems` / `favoriteFolders` / `usage` / `categoryNotifications` |
| `HomeScreen` 类别行渲染 | 类别作为首页一行（条件显示：耳机/时间段/通知/固定） |
| 搜索与抽屉过滤 | 按类别 `showOnHome` / `showInAppList` 决定可见性 |

**依赖**：P4（AppRepository 的类别过滤能力）。
**风险**：类别是"派生内容"，渲染失败会整行消失 —— 必须保证空类别不渲染。
**验收**：建类别 → 选条件 → 桌面出现类别行 → 抽屉按设置显示/隐藏。

---

### P10 图标设计器 + 快捷方式设计（1.2.1）

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `data/icons/ItemIconStore.kt` | 191 | 上游加了 `IconSnapshotCache<RenderSignature>` + materialRole 渲染。**本仓已有自己的 `IconSnapshotCache` 与 renderKey 修复（v2.9.158），需合并而非覆盖** |
| `data/icons/IconPackRepository.kt` | 204 | 英文标签、增量刷新、complete 标记 |
| `ui/settings/IconDesignerEditor.kt` | 105 | 全局编辑继承（未单独指定的选项跟随全局） |
| `data/IconDisplay.kt` | 40 | 显示模式（点/隐藏/隐藏名） |
| `ui/overlays/ShortcutPopup.kt` | 390 | **深分叉**：模块有 dismissFirst / 编辑收藏 / 命令分发定制 |
| `data/ShortcutRepository.kt` / `ui/components/ShortcutPicker.kt` / `ui/overlays/IconPackGrid.kt` | 小 | 快捷方式自动套设计 |

**风险**：**高**。图标链路是桌面第一优先级，`ItemIconStore` 是本仓改过 bug 的文件，必须逐块比对而不是 take theirs。
**验收**：图标包切换、图标设计器全局继承、快捷方式自动套设计、收藏与抽屉图标一致、冷启动不卡。

---

### P11 首页契约切换（架构批）

**为什么单列**：模块 `HomeScreen` 是"展开 20+ 参数"形态，上游是 `uiState: LauncherUiState` 契约。目前 639 行差异全部来自这个形态差异，每版都要手搬。

**做法**：

1. `ui/LauncherUiState.kt` 补到上游 72 字段（当前 55），补齐 `appCategories` / `homeItems` / `favoriteFolders` / `usage` / `categoryNotifications` / `canFavorite*` 等 getter
2. 新增 `GraceHomeUiState.toLauncherUiState(): LauncherUiState` 适配层（模块 VM 状态 → 上游契约）
3. `GraceLauncherRoute` 改用适配层，`HomeScreen` **整文件 take theirs**
4. 手工保留模块定制：`editingLayout` 区块拖动、`onReorderHome`、`stackApps` 解析、`onWeatherClick` 接线

**收益**：之后每版 `HomeScreen` / `ClockRegionHeader` / `AlphabetRail` 等 UI 组件都能直接 take theirs，长期维护成本断崖下降。
**风险**：**高**（首页是核心）。做完必须完整回归：收藏/文件夹/应用堆/拖动排序/手势/媒体/时钟/日程/天气/widget。
**建议时点**：P9 完成后（P9 本来就要补 getter，可复用）。

---

### P12 设置页重组（1.2.2）

| 文件 | 改动 |
| --- | --- |
| `ui/settings/LauncherSettingsScreen.kt` | 244（分组重排 + 新页路由 + 系统栏背景） |
| `ui/settings/ProductivitySettings.kt` | 231（拆出 shortcuts/folders/app-stacks 子页） |
| `ui/settings/FontSettings.kt` | 291（字体管理器重写） |
| `ui/settings/ThemeSettings.kt` | 143 |
| `ui/settings/SettingsComponents.kt` | 111 |
| `ui/settings/LabSettings.kt` | 120（弹层灵敏度等实验项） |
| `ui/settings/SearchSettingsScreen.kt` | 159 |
| `ui/settings/CalendarSettings.kt` | 97 |

**说明**：本批以"设置页结构与上游一致"为目标，新增页（Homepage / AppList / FeedScreen / StatusScreen / TabletsFoldables / Wallpaper / Language）中依赖未完成功能的，先挂 `AppearancePlaceholder`，不留死链。
**验收**：每个分组都能进、能返回、开关能保存、无空白页。

---

### P13 弹层打磨 + 长尾与平台

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `ui/overlays/ItemSelectionScreen.kt` | 475 | 选择器统一（收藏编辑/文件夹/快捷方式） |
| `ui/components/SwipeDismissContainer.kt` | 177 | 下滑关闭手感（接 `dismissResistance` / `dismissThresholdDp`） |
| `ui/components/LauncherComponents.kt` | 170 | 通用行/图标组件 |
| `ui/theme/LauncherFonts.kt` | 248 | 字体链（模块有 fontContext 定制） |
| `ui/theme/LauncherAppearance.kt` | 164 | 外观（P2 已合一半，剩 1.2.2 的 fontColors 收口） |
| `platform/LauncherAppTransitions.kt` | 236 | 启动/返回动画 |
| `ui/LauncherScreen.kt`（新）/ `LauncherAppTheme.kt`（新） | — | 上游根路由（P11 后才有意义） |
| `platform/StatusBarActions.kt` / `SystemActionRouting.kt` / `CalendarApps.kt` / `ClockLauncher.kt` / `SearchLauncher.kt` / `PinItemViewModel.kt` | 新 | 系统动作路由（锁屏/通知栏/快捷设置/闹钟/搜索） |
| `data/licenses/LicensesRepository.kt` / `data/ClockFontStore.kt` / `data/ThemePalette.kt` | 95 / 80 / 35 | 杂项 |

**风险**：`LauncherAppTransitions` 与模块已有 `hook/AppCloseAnim`（关闭动画统一）有交集，必须一起验。
**验收**：所有弹层下滑关闭手感、启动/返回动画、锁屏/通知栏/快捷设置/闹钟/搜索五个系统动作、字体切换。

---

## 5. 关键决策点（需要 User 拍板时才问）

| # | 决策 | 建议 | 触发时点 |
| --- | --- | --- | --- |
| 1 | 首页是否切上游 `uiState` 契约 | **切**（P11 一次性投入，之后零成本） | P9 完成后 |
| 2 | Widget Screen 是否恢复（1.2.0 当年放弃，用原生负一屏替代） | **恢复**（全量对齐，且能与负一屏并存） | P7 开工前 |
| 3 | 是否引入 media3 依赖 | **引入**（随机播放/多会话必需，只影响音乐） | P8 开工前 |
| 4 | 设置页新分组（Homepage/AppList/FeedScreen/StatusScreen）是否都建 | 依赖未完成的先占位，不留死链 | P12 |

---

## 6. 每批交付纪律

```
1. 侦察：git ls-files 确认文件是否已存在（踩过：覆盖了已有的 LauncherUiState）
2. 三方合并：sed 's/\r$//' 归一化 → git merge-file -p --diff3 <模块> <1.2.0备份> <1.2.4>
   判据：模块相对 1.2.0 有无「独有声明」→ 无则直接 take theirs，有才手工叠加
3. 构建：./gradlew assembleRelease（必须无 e:）
4. 装机：adb install -r → force-stop com.miui.home → 抓 logcat 确认零 FATAL
5. 文档：docs/PORTING.md 记根因/修法/验证证据；.workbuddy/memory 记教训
6. 提交：commit → tag v<ver>-<batch> → push main + tag（先探测代理端口）
```

## 7. 风险登记

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| `AppRepository` 864 行深分叉 | 应用装载全链路 | 逐块比对，保住两段式装载 + 缓存策略；分小步构建 |
| `ItemIconStore` 缓存已被本仓修过 bug | 图标错乱/卡顿 | 不 take theirs，逐块合并；保留 renderKey 指纹修复 |
| `ShortcutPopup` 390 行深分叉 | 长按弹层交互 | 同上；保住 dismissFirst 与命令分发 |
| `LauncherSheetWindow` 换新 | 所有弹层 | P5 单独验证每个 sheet |
| media3 新依赖 | 体积 + 崩溃面 | 依赖隔离在音乐模块，异常全 catch |
| `HomeScreen` 契约切换 | 桌面核心 | P11 单批做，完整回归 |
| 上游继续发版 | 计划漂移 | 每完成 3 批 check 一次上游 release |

---

## 8. 排期建议与理由

```
P4 应用列表 ─┬─→ P9 应用类别（依赖 AppRepository）
             └─→ P10 图标（依赖列表渲染）
P5 Agenda ──→ P6 天气（Agenda 显示逐小时天气）
P7 小组件（独立）
P8 音乐（独立，含新依赖）
P9 完成 ──→ P11 首页契约切换 ──→ P12 设置页重组 ──→ P13 长尾
```

先做 P4 的理由：它同时是 P9/P10 的前置，且是当前剩余缺口里改动量最大、最深分叉的一块 —— 早做早暴露问题。
P5/P6 相邻（Agenda 消费天气），P7/P8 相对独立可穿插。
P11/P12/P13 放在最后，因为它们都是"结构对齐"而非"功能补齐"，前面的批次会让这些文件自然收敛一部分。
