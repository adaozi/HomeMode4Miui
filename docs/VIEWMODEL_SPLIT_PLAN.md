# GraceHomeViewModel 拆分方案（只读分析，待确认后实施）

> 基线：`main @ 2a931ef`（v2.9.162 / vc178）
> 目标文件：`app/src/main/java/com/homemode4miui/grace/GraceHomeViewModel.kt`（1775 行）
> 本文档只描述边界与顺序，不含代码改动。

## 1. 现状度量

| 项 | 值 |
|---|---|
| 总行数 | 1775 |
| `GraceHomeUiState` 定义 + 8 个派生属性 | 94–262（169 行） |
| 成员字段 | 47 个（含 6 个 `Job`、4 个 `SharedFlow`、4 个 `StateFlow`、6 个 `BroadcastReceiver`/`Callback`/`Listener`） |
| 方法 | 48 个 |
| 依赖的仓库/控制器 | `FavoritesStore`、`AppRepository`、`GraceSettingsRepository`、`ShortcutRepository`、`CalendarRepository`、`LauncherDatabase`、`GraceSettingsHostEngine`、`GraceWeatherHostEngine`、`WidgetController`、`ItemIconStore` |
| 承载的功能面 | 应用装载/图标装饰/快捷方式/设置/收藏/文件夹/弹层/部件/媒体/通知/天气/日程/启动/生命周期 |

一句话：**一个类同时是 6 个功能的「状态持有者 + 副作用执行者 + 跨进程入口」**。

## 2. 路线选择：薄拆分（不改 UiState 结构）

两条路：

| 路线 | 做法 | 代价 |
|---|---|---|
| A. 薄拆分（**推荐**） | 保留单一 `MutableStateFlow<GraceHomeUiState>`，把**逻辑与私有字段**按域切到 delegate 类，delegate 通过共享的 state flow 读写自己那一份 | Route/UI **零改动**；uiState 仍是共享可变状态 |
| B. 厚拆分 | 每域独立 `StateFlow`，VM 用 `combine` 合成 UiState | 真解耦，但任何域变化都会让整棵 Composable 重组 —— 与本批刚做完的重组优化直接冲突 |

选 A。理由：项目铁律是零崩溃，B 的收益（更彻底解耦）抵不上它带来的重组风暴风险。A 能把 1775 行降到「主壳 ~450 + 7 个小文件」，改一个功能只需读 200 行，这已经解决 90% 的问题。

**A 的硬约束**：delegate 只允许写自己域的字段。这条靠约定 + code review，不加运行时检查（运行时检查会变成新的崩溃面）。

## 3. 域边界

| Delegate | state 字段（搬走的） | 私有字段（搬走的） | 方法 | 依赖的其他域 |
|---|---|---|---|---|
| **AppsLoader** | `apps` `isLoadingApps` `appLoadFailed` `homeReady` `folderApps` `itemIcons` `graceButtonApp` | `appRepository` `iconStore` `iconsSnapshot` `itemsDecoratedWith` `decorationThemeStamp` `decorateMutex` `appLoadGeneration` `appLoadJob` `appLoadRetryCount` `iconsJob` `wallpaperRevision` `wallpaperColorsListener` `wallpaperReceiver` `packageReceiver` | `refreshApps` `decorateApps` `decorateFolders` `applyItemIconSnapshot` `currentIconSettings(OrNull)` `decorationThemeStampOf` | 读 settings（图标包顺序）；调 ShortcutSync 重建快捷方式列表（单向） |
| **ShortcutSync** | `savedShortcutApps` `shownShortcutKeys` `shortcutIcons` `hasShortcutAccess` | `shortcutRepository` `launcherAppsRef` `launcherAppsCallback` `savedShortcutsJob` `lastShortcutSyncAt` `lastPinnedSyncAt` | `syncPinnedShortcuts` `buildShortcutApps` `shortcutItemKey` `removeShortcut` `loadShortcuts` `cachedShortcuts` `prepareShortcuts` `launchShortcut` `refreshShortcutAccess` `onBridgeShortcutQuery` `rebuildFromRows`（供 AppsLoader 调） | 读 apps（key→app 映射）；**调装饰 lambda**（见 §4-8） |
| **SettingsSync** | `settings` `settingsRevision` `hiddenAppKeys` `folders` `folderWidgets` `popups` | `settingsRepository` `favoritesStore` `settingsHostEngine` `settingsJob` `hiddenAppsJob` `foldersJob` `lastIconPacks` | `updateSettings` `onSettingsCommand` `onSettingsMutated` `updateHomeOrder` `updatePopupWidget` `toggleFavorite` `reorderFavorites` `setFolderFavorites` `recordSearchApp` `setIconPack` `loadIconPacks` | 通知 apps（图标包变化 → 重刷） |
| **BridgeInbox** | `homeMedia` `notifications` `hasMediaAccess` | `bridgeReceiver` `hostForeground` `pendingNotifications` `pendingMedia` `snapshotFlushScheduled` `graceUntilUptime` `graceFlushJob` `lastSnapshotPullUptime` | `onBridgeMedia` `onBridgeNotifications` `applyMedia` `applyPendingSnapshots` `scheduleSnapshotFlush` `sameNotifications` `inResumeGrace` `onHostPaused` `onHostResumed` `controlMedia` `dismissMedia` `openNotification` `dismissNotification` `sendBridgeCommand` | 无（只写显示态） |
| **WidgetSlot** | `widgetProviders` `widgetPickerBusy` | `widgetController` `_widgetSetupResults` `_moveWidgetRequests` `_addWidgetRequests` `_addPopupWidgetRequests` | `loadWidgetProviders` `addHomeWidget` `removeHomeWidget` `addPopupWidgetTo` `configureHomeWidget` `updateHomeLayoutTopOffset` `updateHomeLayoutWidgetHeight` | 写 settings（homeLayout） |
| **AgendaFeed** | `events` `scheduleStatus` | `calendarRepository` `calendarObserver` `agendaRefreshJob` | `refreshAgenda` `ensureCalendarObserver` `scheduleAgendaRefresh` `openCalendarApp` | 无 |
| **WeatherFeed** | `weather` | `weatherHostEngine` | `onWeatherState` `refreshWeather` | 通知 settings（回推快照） |
| **Shell（留在 VM）** | — | `_uiState` 及 6 个对外 Flow | `uiState` `folderItem` 等派生属性、`launch` `openAppDetails` `openClock` `requestReturnHome` `onHomeIntent` `shutdown` | 全部（编排 + 生命周期） |

拆分后规模估算：VM ~450 行（壳 + 派生属性 + 生命周期）、`GraceHomeUiState.kt` ~180、AppsLoader ~350、ShortcutSync ~300、BridgeInbox ~300、WidgetSlot ~200、SettingsSync ~200、AgendaFeed ~120、WeatherFeed ~60。

## 4. 跨域耦合点（拆分的真正难点）

| # | 耦合 | 证据 | 处理方式 |
|---|---|---|---|
| 1 | `refreshApps` 同时写 `apps` 和 `savedShortcutApps`/`shownShortcutKeys`/`shortcutIcons` | `refreshApps` 内 `buildShortcutApps` 分支 | 拆成两段：AppsLoader 只写 apps/folderApps；pinned 部分交给 ShortcutSync 自己的方法写。**这是全仓最容易改出 bug 的一处**，必须先做 ShortcutSync 再做 AppsLoader |
| 2 | `updateSettings` 是公共写通道 | WidgetSlot、搜索记录、图标包都调它 | SettingsSync 暴露 `update` 方法，其他 delegate 通过注入的 lambda 调用（不持有 SettingsSync 实例，避免环） |
| 3 | 图标装饰依赖 settings（图标包顺序、图标设计） | `currentIconSettings()` 读 `settingsRepository` | AppsLoader 自己持有 `settingsRepository` 只读引用，不经过 SettingsSync |
| 4 | `onBridgeShortcutQuery` 读 `apps` | `_uiState.value.apps.associateBy` | ShortcutSync 读 state（只读），不调 AppsLoader 方法 |
| 5 | `shutdown()` 要取消全部域的 6 个 Job | 1755–1775 | 每个 delegate 提供 `dispose()`，VM 的 `shutdown` 顺序调用 |
| 6 | 装饰互斥与代际（`decorateMutex` + `appLoadGeneration`） | 401–421 的注释记录了 2026-10-28 的实机事故 | 两个字段必须留在**同一个** delegate（AppsLoader）内，不可跨文件拆分 |
| 7 | `buildShortcutApps` 被 **3 处**调用，其中一处在装饰路径 | 708（init 的 savedShortcutsJob）、832（`syncPinnedShortcuts`）、**1097（`applyItemIconSnapshot` 装饰后重建快捷方式列表）** | 声明为 ShortcutSync 的公开方法 `rebuildFromRows(rows)`；AppsLoader 装饰完成后调它 |
| 8 | **第 7 条判断有误：这是双向依赖，不是单向** | `buildShortcutApps`（658）内部调 `decorateApps`；而 `applyItemIconSnapshot`（1097）又调 `buildShortcutApps` | 直接拆会让两个域互相持有。解法：**装饰能力以 lambda 注入 ShortcutSync**（`decorate: suspend (List<LauncherApp>) -> List<LauncherApp>`），由 VM 提供实现；第 8 步搬 AppsLoader 时把 lambda 换成 `appsLoader::decorate`，ShortcutSync 一行不用改。这样依赖被压成「谁调用谁传参」，没有环 |

## 5. 实施顺序（每步一个 commit，可单独 revert）

| 步 | 内容 | 验证 |
|---|---|---|
| 1 | 抽出 `GraceHomeUiState` 到独立文件（纯移动，零逻辑变更） | 编译通过即可 |
| 2 | **AgendaFeed**（最小、依赖最少） | 编译 → 装机 → 日程行正常 |
| 3 | **WeatherFeed** | 编译 → 装机 → 天气行正常 |
| 4 | **BridgeInbox** | 编译 → 装机 → 通知行/媒体卡/回桌面不"回放" |
| 5 | **WidgetSlot** | 编译 → 装机 → 添加/移除部件、编辑把手持久化 |
| 6 | **ShortcutSync** | 编译 → 装机 → pin 后抽屉出现、删除快捷方式 |
| 7 | **SettingsSync** | 编译 → 装机 → 改设置即刻生效、重启不回弹 |
| 8 | **AppsLoader**（最复杂，放最后） | 编译 → 装机 → 图标 142/142、抽屉/搜索列表完整、**装饰完成后快捷方式列表不丢**（走 `rebuildFromRows`，见 §4-7） |

每一步都跑 `./gradlew :app:testDebugUnitTest`（编解码护栏）+ `assembleRelease` + 装机冒烟。第 7、8 步额外跑一次「设置改了关桌面再开」验证不回弹。

## 6. 明确不做

- **不改 UiState 结构**（不拆成多个 flow）——理由见 §2
- **不改 Route/UI 的任何调用点** —— 拆分后 VM 的公开方法签名保持不变
- **不顺手改业务逻辑** —— 拆分批次只做搬移，行为差异单独提交（否则出问题无法二分）
- **不引入 DI 框架**（Hilt/Koin）—— 7 个 delegate 手工构造足够，引框架等于新增强制依赖

## 7. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 搬移时漏搬字段 → 该功能静默失效 | 每步编译 + 装机冒烟；字段清单按 §3 逐项勾 |
| 跨域回调注入错 → NPE / 死循环 | 注入一律用 lambda，不互相持有实例 |
| 生命周期清理漏（receiver/job 未注销） | `shutdown()` 最后一步统一补 `dispose()`，并对照 §3 的私有字段清单核 |
| 拆分后发现性能回退 | 每步装机后看 `refreshApps` / `snapshot pushed` 日志与首帧耗时 |

回滚：单步 `git revert <commit>` 即可，不涉及数据迁移与协议变更。

## 8. 实施结果（已完成，v2.9.163 / vc179）

| 文件 | 行数 | 说明 |
|---|---|---|
| `GraceHomeViewModel.kt` | **472**（原 1775，−73%） | 只剩编排：派生属性转发、launch、生命周期、命令入口 |
| `GraceHomeUiState.kt` | 195 | 状态定义 + 8 个派生属性 |
| `AppsLoader.kt` | 449 | 枚举 / 全量解码 / 图标装饰 / 互斥代际 |
| `ShortcutSync.kt` | 439 | pinned 对账 / 图标解码 / 查询启动 / 系统回调 |
| `BridgeInbox.kt` | 266 | 通知媒体快照 / 回放抑制三时间窗 |
| `SettingsSync.kt` | 224 | 三条 Room 观察流 / 收藏 / 图标包 |
| `WidgetSlot.kt` | 160 | picker / 增删配置 / homeLayout |
| `AgendaFeed.kt` | 138 | 日历失效链 |
| `WeatherFeed.kt` | 56 | 天气直读 |

提交序列（每步独立可 revert）：`04523dc`(1-2) → `c73620f`(3-4) → `310ecfe`(5) →
`596d74c`(6) → `c83b5b8`(7) → `63cc9fd`(8)。

### 实施中修正的两处原方案判断

1. **§4-7 的「单向依赖」是错的**（见 §4-8）：`buildShortcutApps` 调 `decorateApps`，
   而装饰路径又调 `buildShortcutApps` —— 双向。用 lambda 注入装饰能力解决。
2. **`buildShortcutApps` 必须拆两个出口**：`buildForRows`（纯计算，供装饰路径与
   `apps`/`folderApps` 原子换入）与 `rebuildFromRows`（计算+写 state，供流跟随）。
   只留写 state 的版本时，装饰路径会多一次 `update`，中间态可能被并发装载覆盖。

### 三条必须守住的约束（已写进 AppsLoader 类头）

- `decorateMutex` + `appLoadGeneration` **不可拆散**：读/校验代际/写全在锁内，装载换入
  也在锁内。这是 2026-10-28「收藏有图标、抽屉全占位」事故的修复本体。
- `favoriteOrder` 是 AppsLoader 唯一允许跨域写的字段（必须与 `apps` 同一次 update 换入）。
- AppsLoader 对 ShortcutSync 零直接依赖（全部 lambda 注入）。

### 一个编译期坑

两个 delegate 互相引用（`appsLoader` ← `shortcuts` ← `appsLoader.decorate`）时，
Kotlin 类型推断会报 `TYPECHECKER_HAS_RUN_INTO_RECURSIVE_PROBLEM` ——
**两个 val 都要显式标注类型**（`private val appsLoader: AppsLoader = AppsLoader(...)`）。

### 验证口径

装机日志三个指标必须同时为真：
```
refreshApps: enumerated=142
refreshApps: icons done missing=0/142
refreshApps: decorated iconMissing=0/142      ← "抽屉字母占位"的反向指标
```
外加抽屉目视确认（不能有任何一行是字母占位）。

