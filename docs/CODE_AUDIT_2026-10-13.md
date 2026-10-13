# HomeMode4Miui 全仓库代码审计报告（只分析，未改动）

> **修订 2026-10-14 00:15**：v2.9.36 最终提交（3ea9f10）与我 23:02 复审的中间版差异巨大 —— 装机实锤小米天气 provider **调用方白名单**（hasAccessProvider：com.miui.home=true / com.homemode4miui=false），"模块进程直读"方案废弃，天气读取**上移宿主进程**（新建 GraceWeatherHostEngine），设置页天气改随 GraceSettingsBridge.Snapshot.weatherJson（wthr 字段）回带。第二节起追加最终版审查结论。

- 日期：2026-10-13
- 范围：app/src 全部 177 个源文件（~18,000 行），host stub 7 个
- 方法：三路全量扫读 + 逐条 Grep 引用验证 + 与上游 GraceLauncher-src 比对判定是否上游原文
- 结论分级：P0（真 Bug/泄漏）> P1（铁律违规/性能）> P2（死代码/清理）

---

## 终版审查（v2.9.36 天气上移宿主架构，2026-10-14）

**架构判定：正确。** 白名单根因（Wth2:ToolUtils hasAccessProvider 装机日志）下宿主进程读取是与系统桌面天气部件同路径的唯一可行方案。双消费通道闭合：桌面进程内直推（onState→uiState），设置页随快照 wthr 回带，两端同源。

**闭环验证通过项：**
- 旧 API 零残留：encodeWeather/decodeWeather/onBridgeWeather/ACTION_WEATHER 广播分支/IntentFilter 注册全部清除，仅存注释性废止说明
- 新契约两端闭环：Cmd.WEATHER_REFRESH（settings 通道）+ Snapshot.weatherJson + currentWeatherLocationId()，收发/编解码配对完整
- 无反馈环：pushSnapshot→设置页 applySnapshot 仅在 weatherLocationId 变化时回发 WEATHER_REFRESH
- 双引擎互引（settingsHostEngine ↔ weatherHostEngine）用 by lazy 断开初始化环，正确
- 逆向校准落地（均带装机日期注释）：live=min day 行（day=1 实况/day≥2 预报）、temperature_range "17℃~28℃" 正则（含负温）、hourly date_time/sunrise/sunset = 发布日零点 ms 偏移 + floorMod(86_400_000) 求时刻、V7 11 晴夜特判
- rows.isEmpty() → NoWeather 显式分支、openWeatherApp 直接 getLaunchIntentForPackage + market 兜底（预装不可达，无害）

**新发现：**
1. 【P1·新增泄漏面】`GraceWeatherHostEngine` 的 ContentObserver 注册在 appContext 永不注销，refresh 用 viewModelScope（手动构造 VM 永不 onCleared）——`GraceHomeViewModel.shutdown()` 未收编天气引擎。detach 后 MIUI 天气每次写库仍触发：全量 provider 读 + uiState 写 + **pushSnapshot 全量设置快照编码 + 跨进程广播**。与 P0-1 iconsJob 同类，且多一路广播开销。建议：引擎加 `dispose()`（unregisterContentObserver），shutdown() 调用并在 refresh 入口加 disposed 守卫。
2. 【P2·诊断代码未摘】`MiuiWeatherRepository.kt:148-151,233-234,258,261-262,264` 五处 `android.util.Log.d("HomeMode4Miui", ...)` 每次 weather/hourly 读取全量 dump 行数据（宿主进程，会被 LogExporter 按 tag 捕获）。诊断期合理，稳定后应 BuildConfig.DEBUG 门控或删除。`GraceWeatherHostEngine.kt:57-58` 的 ModLog.i 装载打点量级小可保留。
3. 【P2·备案延续】onChange 连发无防抖（指纹去重兜底，成本=重复 provider 读），可加 300-500ms debounce。
4. 【P2·备案延续】encodeWeatherWire 在 pushSnapshot 编码路径主线程执行（25 hourly + 5 daily 量级小），与通知快照主线程编码同类，归并 perf 项。
5. 【已修复确认】上轮备案"hourly isDaylight 跨日误判"已被本轮零点偏移 + floorMod 方案修复；"UnsupportedVersion 无生产者"维持防御性保留。

**对原报告的影响**：天气相关条目（BreezyWeatherUpdates 断线、MiuiWeather 系列备案）以本节为准；P0-1～P0-5 其余四项、P1 铁律项、其余 P2 全部维持原状（shutdown 仍漏 iconsJob，见 P0-1）。

---

## 复审（天气数据源切换后的消费端重读）

**本次改动范围**：删 BreezyWeatherParser/Repository/BreezyWeatherUpdates 整条链；新增 MiuiWeatherParser（V7→V6 weather_type 解码，有 apk 16.0.7.17 逆向出处）/ MiuiWeatherRepository（provider 只读客户端）；GraceBridgeEngine 增 ContentObserver 失效链（content://weather，notifyForDescendants）；Manifest 移除 Breezy 权限与 receiver，`<queries>` 增 `<provider android:authorities="weather"/>`；UI 消费端（AgendaSheet/WeatherSettings/WeatherComponents/SettingsViewModel/GraceLauncherRoute）全部对齐 openWeatherApp 单动作。

### 原审计结论修订
| 原条目 | 状态 |
|---|---|
| P2 死代码：`BreezyWeatherUpdates.events` 断线空转（上游消费端未移植） | **已失效** — 整文件随数据源切换删除，链路不复存在 |
| P2：AgendaSheet 60s 裸 `while(true)` 循环未做 repeatOnLifecycle | **仍成立** — 现 AgendaSheet.kt:95-100，HomeScreen 已做 perf3 改造，此处未同步 |

### 新代码审查（MiuiWeather 系列）
质量良好：cursor 全 `.use` + CancellationSignal 随协程取消、CancellationException 正确重抛、IO 全在 Dispatchers.IO、`ensureActive` 防取消竞态、provider 缺失降级语义完整（NotInstalled/NoLocations/NoWeather/Error 分层）、实况三空判脏数据、包可见性链路自洽（`<queries>` provider 授权覆盖 installedPackage/resolveContentProvider/getLaunchIntentForPackage 三处消费）。

备案级观察（均低危，无需立即处理）：
1. 【MiuiWeatherRepository.kt:89-96】ContentObserver 注册后句柄不保留、永不注销 — 单例引擎语义下可接受，但 `weatherObserverRegistered` 置 true 后将来即便加 stop() 也无法反注册。
2. 【GraceBridgeEngine.kt observeWeatherChanges】onChange 连发无防抖 — MIUI 天气一次刷新写多表会触发 2~4 次串行全量 provider 重读，仅靠 publishWeather 指纹去重兜底。可加 300-500ms debounce。
3. 【MiuiWeatherRepository.kt:218-222】hourly 行 `isDaylight` 用"今天"的日出日落判定 — 跨日的小时行（深夜 23 点/凌晨）昼夜可能误判，仅显示影响。
4. 【WeatherModels.kt】`WeatherStatus.UnsupportedVersion` 现无生产者（MiuiWeatherRepository 不产生），UI 分支保留属防御性对齐上游枚举，合理保留；`WeatherSnapshot.sourceLinks` 恒为空 map，weatherCredits 退化为纯文本，无 bug。

Breezy 全仓残留检查：仅 GraceBridgeEngine.kt:13 头注释一处历史说明，无功能残留。

---

## P0 真实缺陷（建议优先修）

### 1. GraceHomeViewModel.shutdown() 漏 cancel iconsJob —— 协程泄漏
- `grace/GraceHomeViewModel.kt:1016-1028`：shutdown 依次 cancel appLoadJob/settingsJob/hiddenAppsJob/foldersJob/savedShortcutsJob，**唯独漏了 iconsJob**（:373 启动，collect Room 永续 Flow）。
- 影响：桌面 detach 后 iconsJob 仍存活，item_icons 表每次变化都会执行 `currentIconSettings()`（Room 读）+ `decorateApps`（200+ 应用位图全量重装饰）并写 `_uiState`。手动构造的 VM 不走 onCleared，只有这条 shutdown 路径。
- 修复：补 `iconsJob?.cancel()`。

### 2. HomeController 包接收器跨 Context 反注册失效 —— 泄漏 + 广播双份
- `hook/HomeController.java:499-526`：`registerPkgReceiver(ctx)` 用**当时挂载的 Launcher** 注册；`unregisterPkgReceiver(ctx)` 用 **detach 时传入的 Launcher** 反注册。Launcher 重建时新 Activity 的 ContextImpl 反注册旧 ContextImpl 的接收器必抛 IllegalArgumentException，被 `catch (Throwable ignored)` 吞掉后 `sPkgReceiver = null`。
- 影响：旧接收器（持旧 Activity 强引用）永久残留；重新 attach 后再注册新接收器 → 应用增删广播双份分发 + Activity 泄漏至进程死亡。
- 修复：注册时改用 `ctx.getApplicationContext()`，反注册失败记 ModLog。

### 3. GraceSettingsSnapshotStore 残留快照绕过只读降级 —— 陈旧数据可编辑且写丢失
- `bridge/GraceSettingsSnapshotStore.kt:63-69`：`unregister()` 只清 receiver/registered，**不清 `_snapshot`**（对照 GraceShortcutsRelay.unregister 有 `_results.value = null`，本类漏了）。
- 影响：同进程第二次打开设置页时 4s 超时判断 `snapshot.value == null` 恒 false → `settingsLoadFailed` 永不置位。若桌面未挂载，设置页显示**陈旧数据且可编辑**，所有写命令广播进黑洞无提示。
- 修复：unregister 中补 `_snapshot.value = null`。

### 4. GraceCommandReceiver 的 SYSTEM_ACTION 分支无 runCatching —— 导出 receiver 崩溃面
- `bridge/GraceCommandReceiver.kt:69-87`：onReceive 内走 `HostSystemActions`/`GraceRootActions`（含反射 + root 通道）这段无任何包裹，动态注册且 `RECEIVER_EXPORTED`，onReceive 抛未捕获异常 = 模块进程崩溃（违反零崩溃铁律）。该分支当前恰是死路径（见 P2-1），但崩溃面与死代码叠加更危险。
- 修复：整段包 runCatching；同时处置 SYSTEM_ACTION 死路径。

### 5. GraceRootActions 子进程流顺序错误 —— 潜在挂死 + 进程泄漏
- `platform/GraceRootActions.kt:39-44`：`exec("su -c cmd")` 后**先 `waitFor()` 再关流**——输出超过管道缓冲区（~64KB）时子进程写阻塞、waitFor 永不返回（daemon 线程泄漏）；catch 分支无 `process.destroy()`（孤儿进程）；每次动作 new 裸 Thread 无并发上限。
- 修复：`ProcessBuilder.redirectErrorStream(true)` → 读完输出再 waitFor → `finally { process.destroy() }`，或协程 + withTimeout。
- 关联 `bridge/RootKeepAlive.kt:165-179` 同款模式（reader.join(2s) 超时即误判失败）；`:112-118` 用 `sqlite3` 退出码判成功属误报（0 行 UPDATE 也返回 0，应查 `SELECT changes();`）；`:43-61 vs 71-92` `ensure()` 与 `apply()` 双入口不共享 attempted/applied，可并发重复执行 root 写操作。

---

## P1 铁律违规

### 硬编码色值（铁律：禁止硬编码，必须语义化）

**自研代码违规（应修）：**
| 位置 | 值 | 说明 |
|---|---|---|
| `MainActivity.java:165,182,252,286,303,512-518,529-566,623` | 0xFF888888 / 0xFF2E7D32 / 0xFFC62828 / 0xFFF57F17 等 16 处 | 状态色全部硬编码，深色主题下灰字对比度失效 |
| `home/PlaceholderHomeView.java:21` | 0xFFCCCCCC | 该类是熔断后的**常驻降级 UI**（非一次性占位），白字浅色壁纸上不可读 |
| `bridge/GraceSettingsBridge.kt:169` | 0xFF6750A4 | 与上游 LauncherSettings 默认值重复，双处漂移风险 |
| `grace/GraceHomeViewModel.kt:497` | 0xFF6750A4 | 同上，三处同一魔数 |
| `data/GraceSettingsRepository.kt:135` | 0xFF6750A4 | seedEntity 写死，同上 |

**上游原文（保留，可提常量标注出处）：** LauncherAppearance.kt:44、Theme.kt 调色板 11 个 token、LauncherComponents.kt:112-122 首字母占位色 5+1 处、IconDesignerPreviewIcon.kt:151-152、ThemeSettings.kt:316-317 种子色清单（色值即内容，合理）。

### catch 吞异常不记日志（铁律：ModLog 记全栈）
- `ModePrefs.java:78,95,152,200,210,230,238` —— 7 处 `catch (Throwable ignored)`，熔断计数/root 标志写 SP 失败全静默。
- `MainHook.java:254-257` —— isCustomModeActive 裁决读取失败静默回退 SP 分支（恰是裁决链路最需留痕处）。
- `HostCompat.java:84,104` —— 版本探测两级失败落 UNKNOWN 无日志。
- `PermissionGate.java:51-53` —— Settings.Secure 读失败静默按未授权。
- `GracePinItemHook.kt:80,106,122` —— 三处只记 `t.message` **丢栈**。
- `HomeController.java:522-524`、`MainActivity.java:213,407`、`AutoStartProbe.java:53`（只走 logcat 不走 ModLog）。
- 全局不一致：MainHook/HomeController/HostCompat 各自私有 `log()` 只写 XposedBridge.log，未委托 ModLog 双写，LogExporter 的 logcat 来源看不到宿主 hook 日志。

---

## P2 死代码清单（Grep 全仓库零引用已验证）

| 项 | 位置 | 规模 |
|---|---|---|
| UiText 整类（View 阴影工具，Compose 化后无消费） | `util/UiText.java` | ~35 行整类 |
| PrivateAppCache 整类（上游消费端未移植） | `data/PrivateAppCache.kt` | 73 行整文件 |
| `failedHooksSnapshot()` | `MainHook.java:96-98` | pong 广播直接读 sFailedHooks |
| `HostCompat.family()` | `HostCompat.java:154-156` | isOS3 仅类内使用 |
| `PermissionGate.autoStartReady()` | `PermissionGate.java:68-70` | customModeAllowed 直接调 ModePrefs |
| MainActivity 媒体状态死 UI 环 | `MainActivity.java:61,357,617-654` | mMediaStatusRef 永不为空 → refreshMediaStatus 不可达，~25 行 |
| `GraceSystemAccessDialog` 死状态机 | `GraceLauncherRoute.kt:167,927-929` | systemActionAccess 从未置 true，分支永不执行 |
| `Cmd.SYSTEM_ACTION` 全链路 | `BridgeContract.kt:85` + `GraceHomeViewModel.kt:955-957` + `GraceCommandReceiver.kt:69` | Route 直接进程内调 GraceSystemActions，三方皆死 |
| 4 个死 PS 命令 | `GraceSettingsBridge.kt:69-73` + `GraceSettingsHostEngine.kt:171-177` | PS_REQUEST/PS_SETTINGS/PS_REORDER/PS_RESET_APPEARANCE 从未被发送 |
| VM 4 个死方法 | `GraceHomeViewModel.kt:882,909-913,827-831` + `SettingsViewModel.kt:522-525` | mediaAccessSettingsIntent/hasCalendarPermissionEntry/resetFavorites/orderedBy |
| `GraceLauncherRoute` 死 import 12 个 | `GraceLauncherRoute.kt:26-127` | combinedClickable/CircleShape/Icon/ripple 等 |
| GracePinItemHook.launcherApps() | `GracePinItemHook.kt:177-180` | @Suppress("unused") 自我声明 |
| host stub 冗余成员 | `LauncherMode.java:81-121`（21 个方法）、`modeDatabasePath` 字段 + `LauncherModeDatabasePath.java` 整类、`LauncherModeController.java:18-44`（DESKTOP/DRAWER + 11 个方法）、`DragObject.java:6` | 零引用，stub 自述规则失真；建议删除或改注释为"完整逆向快照" |
| `BreezyWeatherUpdates.events` 断线 | `platform/BreezyWeatherUpdates.kt:20` | 上游消费端（collectLatest 触发刷新）未移植，广播→tryEmit 进无人 collect 的 SharedFlow，整条刷新链空转 |
| `GraceSystemActions.kt:45` 不可达项 | `GraceRootActions.perform` 对三个 action 恒返回 true，`||` 右侧 a11y 兜底永不可达 | 误导性死代码 |
| MainHook.java:212-220 live 广播 | handleLoadPackage 末尾 currentApplication() 恒 null（该阶段 app 未就绪），几乎永不生效 | 真正生效的是 Application.onCreate 锚点 |

---

## P2 其他问题与优化空间

**功能/正确性（中）：**
1. `MainActivity.java:24 vs ModePrefs.java`：KEY_ROOT_READY 两份 SP（模块 base_prefs / 宿主 launcher_sharedpreference）靠广播单向同步，宿主拒绝/回退时首页开关回显漂移。建议以宿主 SP 为唯一事实源，onResume ping/pong 回显。
2. `MainHook.java:106-110`：handleLoadPackage 未过滤 `lpp.processName`，宿主若有子进程会重复装 hook + 多份 pong 响应。
3. `MainHook.java:436-448 vs 584-595`：hostVersion/hostVersionSafe 同段反射代码写两遍。
4. `ModePrefs.java:46-48 vs 137-139`：`sp()` 与 `graceSp()` 逐字相同；7 处主线程 `commit()`（广播 onReceive 路径），与 perf2 已改 `apply()` 的决策不一致。
5. `LogExporter.java:136`：rootUsed=true 分支缺 `\n`，导出文件头两行并一行（格式 bug）；`:196-257` Process 读流抛异常不 destroy（进程泄漏）；`:70-91` 每次点击 new 裸线程无单飞；`:307-309` SimpleDateFormat 每行 new。
6. `GraceHomeViewModel.kt:607-650 vs 673-720`：addHomeWidget 与 addPopupWidgetTo 约 45 行逐字重复（allocate→bind→回收→configure），可抽 bindAndConfigure。
7. `GraceSettingsHostEngine.kt:212-213`：`currentOrder()` 连读两次 SP；`:102,128` 同一 json 解析 JSONArray 两次，已有 decodeKeys() 未用。
8. `GraceSettingsBridge.kt:121-249`：encodeRow/decodeRow/applyDelta 三份 34 列硬编码，加列要同步改三处。建议列描述符表驱动。
9. `SnapshotStore` 与 `GraceShortcutsRelay` 两类逐行同构（flag+receiver+注册反注册），可抽基类。
10. `RootKeepAlive`/`GraceKeepAliveService` 等 5 处重复定义 `"com.homemode4miui"` 字面量，统一用 GraceBridge.PACKAGE_MODULE。

**性能（中低）：**
1. `GraceBridgeEngine.kt:115-127,341-344`：通知快照全量 JSON 编码 + 逐条字节计数（限幅 192KB）跑在 **Main 线程**（engineScope = Dispatchers.Main.immediate），通知风暴时卡顿。建议 flowOn(Default)。
2. `SettingsViewModel.kt:188-189`：applySnapshot 无条件 `refreshApps()` —— 宿主每条命令落地都回推全量快照 → 设置页每改任意一项都全量重查 PM 应用列表。宿主侧已有 lastIconPacks 指纹方案（GraceHomeViewModel.kt:308），此处漏了。
3. `GraceLauncherRoute.kt:377`：**composition 期间直接写 MutableState**（wallpaperBlurBitmap.value = null），Compose 明令禁止，应包 SideEffect/LaunchedEffect。
4. `GraceLauncherRoute.kt:624-673`：`rowGestures`（非 @Stable 类）等每次重组新建并传给 HomeScreen/AppDrawerScreen → 大子树跳过不了重组。建议 remember。
5. 60 秒时钟循环 4 处（HomeScreen.kt:185 已做 repeatOnLifecycle 改造，ClockStyleSettings.kt:310、AgendaSheet.kt:95、TimeText.kt:43 未做），建议抽 `rememberMinuteTick()` 统一。
6. 触摸/手势热路径重复反射：`GestureIsolator.java:80-95`（每 DOWN/MOVE findClass + findField）、`HomeController.java:466-490`（openMinusOneScreen 单次 4 个 callMethodSafe 各 findMethodDeep 重扫父类链）、`MainHook.java:324`（resetcache 每次全扫 getDeclaredFields）。建议按 (Class,name) 静态缓存。
7. `AppRepository.kt:70`：应用图标全尺寸解码（无 inSampleSize），200+ 应用内存峰值可观；`:45,130` `checkNotNull(launcherApps)` 在 PM 兜底路径之前 —— launcherApps 为 null 时兜底永远到不了，逻辑自相矛盾 [上游同构]。
8. `BridgeContract.kt:139-158`：通知超限后剩余条目仍逐条构造 JSONObject 白做 CPU，应 break 外层循环。
9. `ModuleResourceBridge.kt:100`：每次 attach 都 createPackageContext，模块资源进程内不变可静态缓存。
10. `LauncherComponents.kt:111-114`：无图标占位色列表在重组路径每次分配，提文件级常量。

**风格/可维护性（低）：**
- GraceLauncherRoute.kt（1090 行）/ GraceHomeViewModel.kt（1029 行）职责混杂，建议拆分（Route: 壁纸效果层 / GraceButton 过渡 / widget picker；VM: WidgetController / BridgeCommander / AgendaController）。
- GraceHomeUiState 30+ 字段单一 data class，任一字段变化全量重组，中长期按消费面拆分。
- ui/settings 22 个页面：`SettingsActionItem(i, count)` 手工序号（ProductivitySettings.kt:81-121 连续 0,8→5,8，插删必须全量重排）建议 itemsIndexed 数据驱动；5 个文件逐字重复的字符串路由对话框状态机可抽泛型 ChoiceDialog。
- `HostBackHandlerOwner.kt:21-26`：构造参数遮蔽再手工赋值，改主构造属性；文件名与类名不一致。
- `GraceHomeRoot.kt:81`：`val state by viewModel.uiState.collectAsState()` 声明后零读取，多余订阅。
- `SettingsActivity.kt:98-105`：closeRequestsJobActive 手工布尔冗余（repeatOnLifecycle 自幂等），移 onCreate 一次注册即可。
- `GraceCommandReceiver.kt:20/28`：已 import ModLog 又写全限定名。
- `LauncherDatabase.kt:164`：@Volatile instance 夹在两个 Migration 常量之间，移 companion 顶部。
- `LauncherAppTransitions.kt:80`：每次启动打 Log.d（上游原文），建议 DEBUG 门控。
- `GraceCommandReceiver.kt:104-116`：uid 校验未命中无条件放行 + RECEIVER_EXPORTED（知情降级已登记，建议 PORTING 保持记录）。
- 壁纸模糊位图只在首次组合加载（GraceLauncherRoute.kt:362-375），换壁纸不刷新（无 ACTION_WALLPAPER_CHANGED 监听）——功能缺口，轻。

---

## 排查通过项（无问题，记录备查）

- galaxyrio 层：GlobalScope 0 处、runBlocking 0 处、Thread.sleep 0 处；全部 Cursor/Stream 均 `.use{}`；Compose 内无直接 IO；LauncherDatabase 21 条 Migration 链完整、双检锁正确；AsyncQueryCache 设计精巧（LAZY Deferred + 身份校验 + LRU + Semaphore）；HomeWidgetHost 的 MotionEvent obtain/recycle 配对正确；MediaSessionRepository 无跨线程修改。
- BridgeContract 媒体/通知/天气/命令主链：除 SYSTEM_ACTION 外 7 Cmd 全部两端闭环；GraceSettingsBridge 除 4 个 PS 命令外 20 Cmd 闭环。
- BridgeContract 300 行契约本身无未使用常量（除上述 SYSTEM_ACTION）。

## 建议处理顺序

1. **P0 五项**（iconsJob 泄漏 / 跨 Context 反注册 / 快照残留 / receiver runCatching / su 进程流）—— 都是根因级真缺陷。
2. **P1 吞异常整改 + 自研硬编码色值** —— 铁律一致性。
3. **P2 死代码一次性清理**（预计可减 ~500 行：UiText 35 + PrivateAppCache 73 + SYSTEM_ACTION 链 + 4 PS 命令 + stub 冗余 + 12 死 import + VM 死方法）。
4. **P2 性能项**（引擎主线程编码 / applySnapshot 全量 refreshApps / 组合期写状态 / 反射缓存）。
