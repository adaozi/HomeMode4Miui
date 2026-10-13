# 移植指南 —— 把本模块搬到其它小米桌面版本

本文回答一个具体问题：**宿主 `com.miui.home` 换版本后，这个模块要怎么改才能跑起来？**

开发基准是 **RELEASE-5.39.40.10843（HyperOS）**。真机联调时已经踩过一轮"设备版本 ≠ 分析版本"的坑，  
所以模块内部已经内建了三层漂移防护。但防护不是万能的，仍然有必须手工适配的部分。  
本文按「先分类 → 再逐项核对 → 然后排错」组织。

---

## 1. 先搞清楚：什么会漂移，什么不会

| 类别                                                                 | 是否随版本漂移       | 模块如何应对                    |
| ------------------------------------------------------------------ | ------------- | ------------------------- |
| 宿主**方法签名**（裁决入口、当前模式字段）                                            | ✅ 会           | 按**名字特征扫描**，而非硬编码签名（三级锚点） |
| 宿主**类名**（`SuperDraglayer` / `Launcher` / `LauncherModeController`） | ⚠️ 基本稳定，但可能挪包 | hook 点集中在 4 个类；失败即降级      |
| 宿主**视图结构**（Workspace/HotSeats 类名）                                  | ✅ 会（真机已验证全变）  | **不按类名**隐藏，改为容器子 View 记账  |
| 宿主**资源 ID**（图标尺寸、颜色）                                               | ✅ 会           | 零宿主资源依赖，UI 全自绘            |
| `LauncherMode` **抽象方法/覆写方法签名**                                     | ⚠️ 可能增减       | **必须手工同步宿主桩**（唯一编译期契约）    |
| 系统框架 API（`LauncherApps` / `UsageStatsManager` / `AppWidgetHost`）   | ❌ 不会（AOSP 稳定） | 直接用公开 API（应用/使用记录/小部件获取已随 UI 清除，重写时照用，见 ARCHITECTURE §3.2） |
| MIUI 私有 Provider（隐藏应用）                                             | ⚠️ 可能改 schema | 探测 + 反射兜底 + 降级不过滤（实现已随数据层清除，实证结论存档） |
| 模块自有 SP / 数据格式                                                     | ❌ 不会          | —                         |

**结论**：真正需要人工介入的只有三处 —— ① 宿主桩签名、② hook 锚点、③ 私有 Provider schema。  
其余部分（约 80% 代码，即全部 UI 与数据层）是版本无关的。

---

## 2. 移植三步法

### 第 1 步：反编译目标宿主，只核对一件事 —— `LauncherMode`

本仓库自带方法表导出工具（已实测可用）：

```bash
pip install androguard

# 导出目标宿主 LauncherMode 的完整方法/字段表，并与桩文件对比
python tools/dump_class_methods.py <目标版本APK> \
    com.miui.home.launcher.allapps.LauncherMode \
    --diff app/src/host/java/com/miui/home/launcher/allapps/LauncherMode.java

# 一次性导出移植关心的全部类
python tools/dump_class_methods.py <目标版本APK> --preset --out docs/host-signatures-<版本>.txt
```

输出示例（对基准版实测）：

```
--- METHODS (47) ---
  getModeName                  (<none>) -> java.lang.String  [ABSTRACT]
  supportAssistant             (<none>) -> boolean  [ABSTRACT]
  supportFeed                  (<none>) -> boolean  [ABSTRACT]
  supportSearch                (<none>) -> boolean  [ABSTRACT]
  canAddToWorkspace            (java.util.Collection, com.miui.home.launcher.ShortcutInfo) -> boolean
  …

DIFF vs 桩文件: app/src/host/java/…/LauncherMode.java
  [OK] 宿主全部抽象方法都已在桩中声明。
  [i]  宿主有、桩未声明的普通方法（安全——运行期自然继承宿主实现）：
       - backupDatabaseFile
       - getDBCellCountPreKey
       …
```

**工具会重点标出 `[ABSTRACT]`**：宿主新增的抽象方法如果桩里没有、`CustomLauncherMode` 也没实现，
运行期直接 `AbstractMethodError` 崩桌面 —— 这是移植时**唯一必须立刻处理**的漂移类型。

**要核对的清单**（逐条比对 `app/src/host/java/com/miui/home/launcher/allapps/LauncherMode.java`）：

1. **抽象方法**有没有增减？
   > 基准版是 4 个：`getModeName()` / `supportAssistant()` / `supportFeed()` / `supportSearch()`。  
   > 新增抽象方法 → `CustomLauncherMode` 必须实现，否则运行期 `AbstractMethodError`。
2. **我们覆写的 12 个方法**签名有没有变？
   | 方法                              | 基准签名                          |
   | ------------------------------- | ----------------------------- |
   | `isScreenCellsLocked`           | `()Z`                         |
   | `isAutoFillEmptyEnable`         | `()Z`                         |
   | `isHomeSupportSearchBar`        | `(Context)Z`                  |
   | `isHomeSupportIconSearchBar`    | `(Context)Z`                  |
   | `isPersonalAssistantOn`         | `(Context)Z`                  |
   | `isIconPositionFixed`           | `(View)Z`                     |
   | `canAddToWorkspace`             | `(Collection, ShortcutInfo)Z` |
   | `canAddToFolder`                | `(Collection, ShortcutInfo)Z` |
   | `canShowShortcutMenu`           | `(DragObject)Z`               |
   | `defaultPullDownIsNotification` | `()Z`                         |
   | `getPullDownGesture`            | `(Context)String`             |
   | `getSlideUpGesture`             | `(Context)String`             |
   > **参数类型变了是硬错误**：Java 里那是**重载**不是覆写，  
   > 结果就是我们 override 的方法永不生效，宿主走默认实现。而且**不会报错**，只会行为诡异。
3. `ShortcutInfo` / `DragObject` 作为参数类型是否还在原包？（挪包 → 改桩的包名）

### 第 2 步：同步宿主桩

改 `app/src/host/java/**`。这是**唯一的编译期契约**，改完必须能编译通过。

```bash
# 快速自检：只编译，不打包
JAVA_HOME=<jdk17> ANDROID_HOME=<sdk> bash tools/build_apk.sh 2>&1 | head -40
# javac rc=0 且 classes > 0 即为通过
```

注意两条**不要动**的留白：

- `LauncherModeController.loadCurrentLauncherMode` / `onLauncherModeChanged` 是宿主 `private static`，  
  桩里**故意不声明** —— 声明了会在跨类加载器 invoke 时 `IllegalAccessError`；
- 桩方法体保持 `throw RuntimeException("HOST STUB")` —— 这是"误执行即显式失败"的保险丝，别改成 return 默认值。

### 第 3 步：装机跑，读日志，补锚点

装好后打开 LSPosed 日志，过滤 tag **`HomeMode4Miui`**：

```bash
adb logcat -s LSPosed-Bridge | grep HomeMode4Miui
# 或直接看 LSPosed 管理器 → 日志
```

**逐条判读**：

```
[HomeMode4Miui] loaded into com.miui.home / … | host 5.39.40.10843 (…)   ← 确认版本对上了
[HomeMode4Miui] hook installed: 1.getAllModes                            ← ✓
[HomeMode4Miui] hook installed: 2.modeAdjudication                       ← ✓
[HomeMode4Miui] adjudication via loadCurrentLauncherMode([...])          ← 命中哪一级锚点
[HomeMode4Miui] hook FAILED (degraded): 3.Launcher.setupViews -> …       ← ✗ 需处理
```

**如果出现 `hook FAILED (degraded): 2.modeAdjudication`**，日志里会紧接着有 `dumpMembers` 打出的  
宿主 `LauncherModeController` 全部成员：

```
[HomeMode4Miui] host LauncherModeController members:
  M loadCurrentLauncherMode([Context]) -> LauncherMode
  M someRenamedResetXxx() -> void
  F sCurrentMode : LauncherMode
```

照着 dump 结果，去 `MainHook.hookModeAdjudication()` 里补一级锚点即可（或调整 `contains(...)` 的关键词）。

---

## 3. 漂移防护机制详解（决定你要改多少）

### 3.1 模式裁决 —— 三级自适应

`MainHook.hookModeAdjudication()` **不依赖固定方法名**，按特征扫描：

| 级别 | 扫描条件                                               | 命中后做法                                      |
| -- | -------------------------------------------------- | ------------------------------------------ |
| a  | 静态 + 返回 `LauncherMode` + 名字含 `loadcurrentlauncher` | hook 返回值                                   |
| b  | 静态 + 名字含 `resetcache`                              | hook 后改写静态字段 `*CurrentMode*`（字段也是按类型+名字扫描） |
| c  | 静态 + 无参 + 返回 `LauncherMode` + 名字含 `getcurrentmode` | hook 访问器                                   |

**如果宿主把这三个命名习惯全改了**（新写一个 `resolveMode()`），三级都会落空 → 走 `dumpMembers` 降级。  
此时需要加第 d 级锚点，或放宽匹配条件。

### 3.2 当前模式字段 —— 按类型+名字扫描

```java
// MainHook.sCurrentModeField()
// 扫描"静态 + LauncherMode 类型 + 名称含 CurrentMode"的字段
```

字段被重命名（如 `mCurrentMode` → `sActiveMode`）会失配 → 改关键词。

### 3.3 原生表面隐藏 —— 容器子 View 记账

```java
// HomeController.hideLauncherSurfaces()
// 遍历容器所有子 View，除挂载视图（HomeView）外全部记账 GONE
```

**这个设计对版本完全免疫**，因为它不认任何宿主类名。  
唯一前提：挂载视图得挂在正确的容器下 → 靠 `resolveContainer()` 三级降级：

```
getDragLayer()  →  mDragLayer 字段  →  DecorView（最后兜底，但原生表面可能藏不掉）
```

若日志出现 `falling back to DecorView container (native surfaces may stay visible)`，  
说明宿主把 DragLayer 访问方式也改了 → 按 dump 补第三种取法。

### 3.4 隐藏应用 —— Provider + 反射双路（知识存档，实现已随数据层清除）

```
content://com.miui.home.app.hide
   ├─ 打不开 / schema 不识别 → 返回 null（区别于"空列表"）
   └─ 结果 > 400 条 → 保险丝，视为误读 → 降级
        ↓ 兜底
反射 com.miui.home.launcher.HideAppList.Companion.getHideAppList()
        ↓ 仍失败
不过滤（列表会显示被隐藏的应用，但不崩）
```

**代价**：Provider schema 变了 → 隐藏应用失效（用户可见异常），**但绝不崩溃**。  
你的 UI 若要支持隐藏应用过滤，重写时按此降级链设计（旧实现见 git 历史 v2.22.0）。

---

## 4. 移植到**旧版**小米桌面

旧版（如 Android 12/13 时代的小米桌面）通常**没有 `LauncherModeController` 这套三模式抽象**  
（经典 / 抽屉 / 极简 是本模块开发基准期才成熟的架构）。这意味着：

**如果旧版没有 `LauncherMode` / `LauncherModeController`**：

| 方案                      | 做法                                                                                  | 代价                                      |
| ----------------------- | ----------------------------------------------------------------------------------- | --------------------------------------- |
| **A. 放弃"原生第四模式"，改借壳**   | 复用宿主已有的 DRAWER 模式，hook 其行为方法                                                        | 与"零借壳"设计原则冲突；但旧版无原生注册点，这是唯一路径           |
| **B. 不注册模式，只做 overlay** | 不碰模式系统，仅 hook `Launcher.setupViews` 直接在 DragLayer 上挂载 HomeView，并把隐藏/显示交给模块自己的开关 | 最简单、最稳；但失去「独立数据库目录」「设置页选项」「原生菜单集成」等模式红利 |

**方案 B 的改法**（推荐作为旧版最小可用路径）：

```java
// 删掉：hookGetAllModes / hookModeAdjudication（旧版无对应类，会整段降级）
// 保留：其余全部底座代码（它们是版本无关的）
// 修改：isCustomModeActive() 直接读 SP → 完全绕开宿主模式系统
public static boolean isCustomModeActive() {
    Activity launcher = sLauncher;
    return launcher != null && ModePrefs.isModeEnabled(launcher);
}
```

`CustomLauncherMode` 与 `mode/` 整个包可以不要（旧版无 `LauncherMode` 可继承）。

**其他旧版注意点**：

- **`minSdk`**：旧版宿主可能 minSdk 29~31 → 把 `app/build.gradle` 的 `minSdk 33` 下调；  
  同时检查是否用到高版本 API（`Transliterator` 需要 API 24，没问题；`AppWidgetHost` 一直有）；
- **`RECEIVER_EXPORTED`**：这是 `targetSdk 34` 的要求。若旧版工程 targetSdk < 33，  
  广播注册去掉该 flag 亦可（但建议保留 targetSdk 34 + flag 的组合）；
- **深色/浅色壁纸取色**：`WallpaperColors.HINT_SUPPORTS_DARK_TEXT` 需要 API 27+，旧版一般满足。

---

## 5. 移植到**新版**小米桌面

新版本（小米桌面大版本更新 / HyperOS 迭代）常见变化：

### 5.1 `LauncherMode` 增删方法

**最可能的破坏点。** 宿主新增一个抽象方法 → 模块编译期报错（好事，能立刻发现）；  
宿主改某个 `isXxx()` 的参数类型 → **编译通过但 override 失效**（坏事，静默出错）。

**自检手段**：反编译目标版本，把 `LauncherMode` 的方法表和桩文件做 diff。

```bash
# 生成方法表
python tools/dump_class_methods.py <目标APK> com.miui.home.launcher.allapps.LauncherMode
# 与 docs/ 里的基准表对比
```

### 5.2 手势控制器新增/改名

`GestureIsolator` hook 的是 `SuperDraglayer.findActiveController`，**这个点本身很稳定**（是唯一分配点）。  
但如果新版**不再用 `findActiveController`**，或改了参数：  
→ 手势闸门失效 → 表现为"列表滚到一半被打断""下拉弹出原生搜索"。  
**排查**：反编译 `SuperDraglayer`，看 `onInterceptTouchEvent` / `onTouchEvent` 里调用了什么分配方法。

### 5.3 浮层检测 API 失效

```java
// GestureIsolator.hostFloatingViewOpen()
// 依赖 AbstractFloatingView.getTopOpenView(activity)（public static）
```

变了的话，闸门会在"宿主浮层在场"时误判为无浮层 → 可能拦住宿主自己的弹层手势。  
**当前降级偏向**：探测失败按"无浮层"处理（即偏向隔离生效）。这个取舍是刻意的。

### 5.4 新版本验收清单

移植到新版后，逐项勾选（这是从真机联调里沉淀出来的，只列**底座相关**项；
UI 相关验收项由你的桌面实现自行定义）：

- [ ] 日志 tag `HomeMode4Miui` 里 **9 条 hook 全部 `hook installed`**（无 `FAILED (degraded)`）
- [ ] 模块 App 开关能切进 / 切出自定义模式
- [ ] 进入模式后显示 `PlaceholderHomeView` 占位提示（挂载链路通）
- [ ] 原生桌面表面**完全隐藏**（日志无 `falling back to DecorView` / `warn: no siblings hidden`）
- [ ] 自定义视图滚动流畅，**滚动中途不被抢断**（手势隔离生效）
- [ ] Home 键触发 `HomeView.onHomeIntent()`（加日志验证）
- [ ] 关闭模式后原生桌面**完整恢复**（可见性记账还原正确）
- [ ] 静止时零无效重绘（开发者选项"显示 GPU 过度渲染"辅助观察）

---

## 6. 排错手册


| 症状                                           | 最可能原因                   | 处理                                                                  |
| -------------------------------------------- | ----------------------- | ------------------------------------------------------------------- |
| 装完模块，桌面**毫无变化**                              | 作用域没勾 / 没重启桌面           | LSPosed 勾 `com.miui.home` → 重启桌面；看日志有没有 `loaded into com.miui.home` |
| 日志有 `loaded into` 但无 `hook installed`        | 宿主类加载失败                 | 看具体哪条 FAILED；多半是 `Launcher` 挪包了                                     |
| `hook FAILED (degraded): 2.modeAdjudication` | 裁决入口改名                  | 读紧随其后的 `dumpMembers`，补第 d 级锚点                                       |
| 能进模式但**原生桌面还露着**                             | 容器解析走到了 DecorView 兜底    | 看 `attach: container=…` 日志；补 `resolveContainer` 的取法                 |
| 自定义视图滚动被打断 / 下拉弹原生搜索                        | 手势闸门失效                  | 反编译 `SuperDraglayer`，核对 `findActiveController`                      |
| 文字有难看的阴影                                     | 宿主主题默认样式                | 确认该 TextView 走了 `UiText.noShadow`；容器用 `noShadowTree`                |
| 你的 UI 一开桌面崩                                  | UI 链路没兜底                | UI/弹层必须与 hook 链路同等兜底，走 `util/ModLog` 打全堆栈                           |
| 退出模式后原生桌面**少了一块**                            | 可见性记账还原失败               | 检查 `sOriginalVisibility` 是否被中途清空 / 容器被重建                            |
| `AbstractMethodError` / `NoSuchMethodError`  | 宿主桩签名与真机不符              | 回到第 2 步，同步桩                                                         |

---

## 7. 一句话总结

> **改版本 = 改 2 个文件 + 补 1 个锚点。**
>
> - `app/src/host/java/**` —— 编译期契约（必改）
> - `mode/CustomLauncherMode.java` —— override 列表（跟随契约改）
> - `MainHook.java` —— 锚点匹配关键词（按日志 dump 补）
>
> 其余底座代码（`hook/**`、`home/**`、`ModePrefs`）**版本无关，不用动**；
> 桌面 UI 由你实现（`home/HomeView` 接缝），同样是版本无关的自绘代码。

---

## 8. 附：本次移植自检记录（基准版实测）

对开发基准版（RELEASE-5.39.40.10843）跑一遍工具，确认：

```
$ python tools/dump_class_methods.py \
    xiaomizhuomian_RELEASE-5.39.40.10843-03212137.apk \
    com.miui.home.launcher.allapps.LauncherMode \
    --diff app/src/host/java/com/miui/home/launcher/allapps/LauncherMode.java

CLASS  com.miui.home.launcher.allapps.LauncherMode
SUPER  java.lang.Object
--- METHODS (47) ---
  getModeName / supportAssistant / supportFeed / supportSearch   [ABSTRACT]
  ... 43 个具体方法 ...
--- FIELDS (1) ---
  modeDatabasePath  com.miui.home.launcher.allapps.LauncherModeDatabasePath

DIFF vs 桩文件:
  [OK] 宿主全部抽象方法都已在桩中声明。
```

```

**结论**：桩文件与宿主完全对齐，4 个抽象方法齐备 → 这条链路是健康的。
把这段输出留档，将来换版本时直接 diff 两版输出即可看出差异。
```

### miuiweather（v2.9.36-miuiweather / versionCode 69，2026-10-14）—— 天气数据源切换：Breezy Weather → 小米天气（进程内直读）

- [x] **根因（装机实锤）**：小米天气 WeatherProvider（authorities `weather;weatherinfo`，MIUI 主题小部件同一取数入口）有调用方白名单（Wth2:ToolUtils hasAccessProvider 日志）：com.miui.home=true / com.miui.weather2=true / **com.homemode4miui=false** —— 模块 App 进程读 provider 拿 null，桌面显示"无法读取数据"。shell 同样被拒（content query 不可作调试探针）。
- [x] **架构上移（零新增 hook 目标）**：天气读取整体搬到宿主进程 com.miui.home（白名单放行，hook 代码本就运行其中）。新建 GraceWeatherHostEngine（MiuiWeatherRepository + ContentObserver(content://weather) + encodeWeatherWire 指纹去重），进程内直推 GraceHomeViewModel.onWeatherState；ACTION_WEATHER 广播 / GraceBridge.Cmd.WEATHER_REFRESH / GraceBridgeEngine 天气链废止；设置页天气经 GraceSettingsBridge.Snapshot.weatherJson 回带，刷新走 SETTINGS_COMMAND "settings_weather_refresh"。两引擎互引用 by lazy 断环。
- [x] **Provider 逆向（天气.apk 16.0.7.17）**：selected_city 表（posID/flag/position/name）；weather 表主键 (city_id, day)，实机实锤 **day=1 = 实况（forecast_type=0）、day≥2 = 预报（ft=1）**；hourlyData/<cityId> 25 行 = 未来 25 整点，**date_time/sunrise/sunset 均为发布日零点起的毫秒偏移**（sunrise mod 24h = 实际日出）；temperature_range 实测 "17℃~28℃" 格式；publish_time = epoch ms；weather_type 为 V7 编码，V7V6_MAP 自 WeatherType clinit 逐条还原（V7 11 = 晴夜，特判 Clear）。
- [x] **清理**：BreezyWeather{Repository,Parser,Updates} 删除；manifest 去 org.breezyweather.READ_PROVIDER + BreezyWeatherUpdateReceiver，queries 补 `<provider android:authorities="weather"/>`（Android 11+ 包可见性）；UI 动作收敛 openWeatherApp（install 入口删除）；双语字符串换"小米天气"。
- [x] 构建：assembleRelease 通过，versionCode 69；实机验证 status=Ready locs=2 cur=true、hourly parsed=25（now + 24 小时全渲染）、设置页快照回带带天气，User 目检确认 OK。
- [ ] **遗留**：① provider 无更新推送 → 依赖 ContentObserver + 挂载首载，若小米天气长期后台不刷新需评估主动唤醒；② weatherappconfig.unit（℃/℉）未消费（恒摄氏）；③ 诊断日志（MiuiWeather rows/hourly）保留至下批清理。

### auditfix（v2.9.37-auditfix / versionCode 70，2026-10-14）—— 全仓代码审计 P0 修复批

- 背景：docs/CODE_AUDIT_2026-10-13.md 全仓审计（177 文件三路扫读 + 引用验证），本批修复 P0 五项 + 新增 P1 天气引擎收编。铁律整改（吞异常/硬编码色值）与 P2 死代码清理留后续批次。
- [x] **shutdown 收编（P0-1 + P1）**：GraceHomeViewModel.shutdown 补 `iconsJob?.cancel()`（detach 后 Room 观察链 + 200+ 应用装饰管线白跑）；GraceWeatherHostEngine 加 `dispose()`（反注册 ContentObserver + disposed 守卫，MiuiWeatherRepository.observeChanges 改返回 observer），shutdown 调用 —— detach 后小米天气写库不再触发全量 provider 读 + 快照广播。
- [x] **HomeController 跨 Context 反注册（P0-2）**：registerPkgReceiver/unregisterPkgReceiver 改走 ApplicationContext —— Launcher 重建时旧 ContextImpl 注册的接收器原先反注册必抛 IllegalArgumentException 且被吞（旧接收器持旧 Activity 泄漏 + 广播双份分发）；反注册失败记 ModLog。
- [x] **SnapshotStore 残留快照（P0-3）**：unregister 清 `_snapshot` + `lastKnownSettings` —— 二次进设置页时 4s 超时判断（value==null）恒 false，桌面未挂载场景陈旧数据可编辑且写命令进黑洞。对齐 GraceShortcutsRelay 语义。
- [x] **SYSTEM_ACTION 死路径删除（P0-4）**：Cmd.SYSTEM_ACTION + GraceHomeViewModel.sendSystemAction + GraceCommandReceiver 分支全链删除（全仓零发送方，Route 进程内直调 GraceSystemActions）—— 导出 receiver 内无 runCatching 裸奔分支的崩溃面随之消除。
- [x] **GraceRootActions 子进程流（P0-5a）**：读流独立线程排水 + waitFor(15s) 超时强杀 + finally destroy —— 旧实现 waitFor 在关流之前（输出超 64KB 管道即永不返回）且 catch 无 destroy。
- [x] **RootKeepAlive（P0-5b）**：ensure/apply 双入口收敛到 `tryRun()` 单飞（同一把 gate 锁 + 同一 attempted/applied 协议，消除并发重复 root 写）；su() 输出改 AtomicReference 跨线程传递（局部捕获变量可见性无保证）+ join 5s；PowerKeeper 校验收紧 —— `UPDATE; SELECT changes()` 同连接校验实改行数（退出码 0 ≠ 生效）。
- [x] 构建：assembleRelease 通过，versionCode 70。
- [ ] **遗留**：P1 铁律批（ModePrefs/PermissionGate/HostCompat 等吞异常、MainActivity 16 处 + PlaceholderHomeView 硬编码色值、GracePinItemHook 丢栈）与 P2 批（死代码 ~500 行、MiuiWeatherRepository 诊断 Log.d 摘除、引擎主线程 JSON 编码、组合期写状态、反射缓存）待后续批次。

### colorlaw（v2.9.38-colorlaw / versionCode 71，2026-10-14）—— P1 铁律整改批

- [x] **吞异常清零**：ModePrefs 7 处（熔断/root/autostart 标志读写全链记 ModLog.e）、MainHook isCustomModeActive 裁决读取（裁决链路异常最需留痕）、HostCompat 两级版本探测失败（UNKNOWN 落定留因）、PermissionGate Settings.Secure 读失败、MainActivity 2 处（自启动降级/onStop 反注册）、GracePinItemHook 3 处丢栈（t.message → ModLog.e 全栈）。catch (Throwable ignored) 全仓归零。
- [x] **日志出口统一**：MainHook/HomeController/HostCompat 三处私有 log() 委托 ModLog.i（补齐 logcat 双写腿，LogExporter tag 过滤从此覆盖宿主 hook 日志）；AutoStartProbe 直写 logcat 改 ModLog.i。
- [x] **硬编码色值清零（自研侧）**：colors.xml 新增 6 个语义 token（status_ok/error/warn、text_hint/secondary、fallback_text）；MainActivity 16 处 → R.color 引用（新增 color() helper）；PlaceholderHomeView（宿主进程熔断常驻降级 UI）经包上下文解析模块 fallback_text，不可达时回落系统 secondary_text_light；0xFF6750A4 三处（GraceSettingsRepository seed / GraceSettingsBridge 解码默认 / GraceHomeViewModel 回退）收敛到 LauncherSettings.DEFAULT_THEME_COLOR 单一事实源（companion 常量，上游默认值唯一保留点）。上游原文色值（Theme.kt 调色板/ThemeSettings 种子清单等）按判定保留。
- [x] **顺手加固**：MainHook 子进程守卫（processName ≠ 主进程只打日志不装 hook，防多份 pong 与静态状态分裂）；MainHook hostVersion/hostVersionSafe 合并为单实现（withCode 参数）；failedHooksSnapshot 死方法删除；HostCompat.family() 死方法删除；ModePrefs graceSp 与 sp() 重复实现去重；PermissionGate 通知授权匹配改正则精确段匹配（子串撞包防护）；AutoStartProbe checkOpNoThrow 反射缓存；GracePinItemHook launcherApps 死方法删除。
- [x] 构建：assembleRelease 通过，versionCode 71。
- [ ] **遗留**：P2 批（死代码 ~450 行、MiuiWeatherRepository 诊断 Log.d 摘除、GraceBridgeEngine 通知快照主线程 JSON 编码、GraceLauncherRoute 组合期写状态、热路径反射缓存、60s 时钟循环抽公共组件）待后续批次。

### cleanup（v2.9.39-cleanup / versionCode 72，2026-10-14）—— P2 死代码清理 + 主线程编码治理批

- [x] **死代码删除（~370 行）**：util/UiText.java 整类（View 体系遗留，Compose 化后零消费）、data/PrivateAppCache.kt 整文件（上游消费端未移植）、GraceHomeViewModel 3 死方法（resetFavorites/mediaAccessSettingsIntent/hasCalendarPermissionEntry）、SettingsViewModel.orderedBy、GraceLauncherRoute systemActionAccess 死状态机 + 17 个零引用 import（getValue/setValue 为 by 委托必需，保留）、GraceHomeRoot 多余 uiState 订阅、GraceSystemActions 不可达 a11y 短路项（GraceRootActions 恒返回 true）。
- [x] **host stub 冗余收敛**：LauncherMode 21 个零引用"编译期完整性"方法 + modeDatabasePath 字段删除，LauncherModeDatabasePath.java 整文件删除（stub 头注自述规则恢复为真：只声明被模块继承/覆写/引用的成员）；LauncherModeController DESKTOP/DRAWER + 11 个零引用方法删除；DragObject 零引用自方法删除。宿主签名漂移时的不一致维护面收窄。
- [x] **MiuiWeatherRepository 诊断 Log.d 摘除**（5 处，v2.9.36 登记遗留）—— 装机诊断已完成使命（结论已固化进逆向注释），logcat/LogExporter 不再被全量行 dump 刷屏。
- [x] **主线程编码治理**：GraceBridgeEngine 通知快照 collect 与 refreshSnapshots 的 192KB 级 JSON 编码移 Dispatchers.Default（sendBroadcast 线程安全，无需回主线程）；encodeNotifications 超限后 break@outer（旧实现 return@forEach 后续条目仍白做构造/序列化）。
- [x] **组合期写状态修复**：GraceLauncherRoute 壁纸模糊 else 分支包 SideEffect（Compose 明令禁止 composition 期间写 MutableState）。
- [x] **LogExporter**：导出头 root 行缺 \n 格式 bug；runCommandCapture finally destroy 兜底（读流异常不再泄漏子进程）；export 单飞闸（连点防重名文件竞态）。
- [x] **GraceSettingsHostEngine**：快照构建内 currentOrder() 连读两次 SP → 单读复用；SET_HIDDEN_APPS 逐字 JSONArray 双解析改用既有 decodeKeys()。
- [x] **GraceBridgeEngine mediaCommand**：OpenPlayer 冗余 when（两分支同调用）收敛为直调。
- [x] 构建：assembleRelease 通过，versionCode 72（中途三次编译错误即时修正：when 替换括号残留、孤儿 KDoc、内部类引用非 final 局部变量 → final holder）。
- [ ] **遗留（v2.9.40-perf 候选）**：热路径反射缓存（GestureIsolator/HomeController/MainHook）、rowGestures remember 化（Compose 稳定性）、60s 时钟循环抽 rememberMinuteTick 公共组件、GraceHomeUiState 拆分与超长文件职责拆分（结构性，随 P3+ 推进）。

### setbk（v2.9.40-setbk / versionCode 73，2026-10-14）—— 设置页返回堆叠修复

- [x] **根因（装机实锤）**：桌面入口链 openGraceSettingsInModule → MainActivity（route 转发后 finish）→ SettingsActivity —— 中转 MainActivity 自杀后任务栈里只剩设置页，每次进入压一个新实例，N 次进入按 N 次返回。
- [x] **修复**：MainActivity 转发 Intent 加 FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP（复用已有实例并清中间层）；SettingsActivity 补 onNewIntent（setIntent + page 变化时 recreate 重定向 NavHost startDestination —— initialPage 只在 onCreate 读，普通字段变更不触发重组导航）；Manifest 补 launchMode="singleTop" 双保险（未来新入口漏 flags 时连续启动仍复用栈顶）。
- [x] 行为验证（推演）：桌面 N 次进入 → 栈内恒 1 个设置页，返回一次回桌面；模块首页进入 → 返回回首页（CLEAR_TOP 只清设置页之上的层，不影响 MainActivity）。
- [x] 构建：assembleRelease 通过，versionCode 73。

### perf（v2.9.41-perf / versionCode 74，2026-10-14）—— 热路径缓存 + 时钟节拍统一批

- [x] **热路径反射缓存**：GestureIsolator findActiveController 每触摸事件级的 findClass/字段解析改静态缓存（含缺失负缓存 —— OS3 mActivity 上移 BaseDragLayer 时不再每事件重抛全父类链扫描）；HomeController findMethodDeep 结果按 (类#方法) 缓存（右滑负一屏单次 4 个 callMethodSafe；含负缓存，宿主类层级进程内恒定；惰性 getDeclaredMethod 语义不变）；MainHook resetcache 路径 sCurrentModeField 扫描结果缓存。
- [x] **60s 时钟节拍统一**：新增 ui/components/MinuteTick.kt `rememberMinuteTick()`（repeatOnLifecycle(STARTED) + 整分对齐），HomeScreen / ClockStyleSettings / AgendaSheet 三处手写循环收敛。TimeText.kt 的 postedAt 键控"通知年龄"节拍器语义不同（新通知需立即归零），不改 —— 已有 perf3 改造。
- [x] **Compose 重组治理**：GraceLauncherRoute rowGestures（非 @Stable 类实例）按 shortcutsFolders 键 remember —— 旧实现每次 uiState 变化（含通知快照高频桥推送）都新建实例传给 HomeScreen/AppDrawerScreen，大子树跳过不了重组；捕获集（viewModel/launchApp/overlay delegate）均为稳定引用。
- [x] 构建：assembleRelease 通过，versionCode 74。
- [ ] **遗留（随 P3+ 推进的结构性项）**：GraceHomeUiState 30+ 字段单一 data class 按消费面拆分；GraceLauncherRoute（~1090 行）/ GraceHomeViewModel（~1030 行）职责拆分（Route: 壁纸效果层/GraceButton 过渡；VM: WidgetController/BridgeCommander/AgendaController）。

### minusoff（v2.9.42-minusoff / versionCode 75，2026-10-14）—— 负一屏预留线整体清除

- [x] **决策**：User 定案 —— 负一屏预留入口（openMinusOneScreen）删除（"负一屏改由自定义 UI 主动打开"设计的最后环节不再推进；右滑手势本就由 GestureIsolator 恒定隔离）。
- [x] **清除范围（HomeController 551 → 321 行）**：openMinusOneScreen / syncMinusClosed / restoreFromMinusScreen / showMinusChain / hideNativesForMinus / ancestorUnderContainer / hideNativeBranch / isNativeSurfaceName / installMinusCloseSync / callMethodSafe / findMethodDeep（含 v2.9.41 缓存）/ currentLauncher / logOnceNoUse + sMinus* 四字段 —— 全链仅被负一屏路径消费，随入口死亡。MainHook #10 onMinusOneScreenViewScroll 钩子与 hook 清单同步移除（现役 hook 10 号起为 onNewIntent）。
- [x] **边界确认**：页面指示条点击进负一屏的路径在自定义模式下不存在 —— 指示条（pageindicators）属隐藏的原生表面；手势隔离管点击，但入口视图本身不可见，故关闭同步链删除无行为影响。
- [x] 过时注释清理：ModePrefs/GestureIsolator/CustomLauncherMode/HomeView/PlaceholderHomeView/MainActivity 共 6 处 openMinusOneScreen 引用。
- [x] 构建：assembleRelease 通过，versionCode 75。

### refactor1（v2.9.43-refactor1 / versionCode 76，2026-10-14）—— 结构性拆分第一批

- [x] **Route 壁纸效果层抽离**：新建 grace/WallpaperEffects.kt —— WindowWallpaperBlurEffect（S+ 跨窗口模糊半径）/ rememberWallpaperBlurBitmap（perf1 异步 fallback 位图）/ rememberResolvedWallpaperTint（hotfix6 遮罩色 + S7 自定义色）/ WallpaperEffectLayers（渲染层）。dimfix/hotfix6/perf1 五轮装机调试的注释与 z 序语义（效果层必须在 BoxWithConstraints 之外的兄弟层位置）原样随迁；GraceLauncherRoute 减少 ~60 行，调用点按 dimfix 定案的层级原位替换。
- [x] **VM widget bind 去重**：addHomeWidget/addPopupWidgetTo 逐字重复的 allocate→bind→回收→label→configure 段收敛为 bindWidgetOrCleanup + launchConfigureIfAny 两个私有 helper，两方法各自只保留差异段（写库目标）。
- [x] 构建：assembleRelease 通过，versionCode 76。
- [ ] **遗留（refactor2+ 候选）**：GraceButton 过渡状态机抽离（Route，与 overlay 状态耦合需设计）；widget 管理抽 WidgetController（VM）；GraceHomeUiState 30+ 字段按消费面拆分（动全部消费面，最后做）。

### refactor2（v2.9.44-refactor2 / versionCode 77，2026-10-14）—— WidgetController 抽离

- [x] **VM widget 管理抽离**：新建 grace/WidgetController.kt —— AppWidgetHost 生命周期 + addHome/addPopupTo/removeHome 全链路（bind 公共段 + 配置页 fire-and-forget 随迁），写库结果经 onHomeLayoutSaved 回调（GraceHomeSettings），setup 结果经 onSetupResult 回调转发 widgetSetupResults SharedFlow。VM 保留三行委托门面（Route/settingsHostEngine 调用面零变化），GraceHomeViewModel 再减 ~120 行。
- [x] 类型教训：GraceSettingsRepository 操作 GraceHomeSettings（非 LauncherSettings）—— 控制器回调签名按实际类型落。
- [x] 构建：assembleRelease 通过，versionCode 77。
- [ ] **遗留（refactor3 候选）**：GraceButton 过渡状态机抽离（Route，~50 行，需 holder 设计）；GraceHomeUiState 30+ 字段按消费面拆分（动全部消费面，最后做）。

### refactor3（v2.9.45-refactor3 / versionCode 78，2026-10-14）—— GraceButton 过渡状态机抽离

- [x] **Route 状态机收编 holder**：新建 grace/GraceButtonTransitionState.kt（沿 FavoritesReorderState 状态 holder 模式）—— transition/instantEntrance 状态 + searchTransition/drawerTransition 派生 + begin/clear/markInstantEntrance/clearInstantEntrance/onLifecycleEvent（前台清账：ON_PAUSE 记 leftForeground、未提交锁屏即清、回前台清残留）。锁屏末帧提交协程（withFrameNanos + GraceSystemActions + 1.5s 验证）依赖 context/lifecycle 留 Route LaunchedEffect，键改读 buttonState.transition。
- [x] Route 消费点全部切换（performGestureAction 守卫/entrance/begin×3、widgetInputEnabled、GraceButton enabled/hidden/heldOrigin、TransitionOverlay、rememberUpdatedState 删除），代码标识符零残留。
- [x] 构建：assembleRelease 通过，versionCode 78。
- [ ] **遗留（refactor4）**：GraceHomeUiState 拆分 —— **设计定稿挂起**（docs/UISTATE_SPLIT_DESIGN.md：字段→消费面矩阵 + 三阶段方案）。结论：热路径已被 perf 批治理、settings 低频占 76% 读取量、Phase 2 侵入面与收益不成比；触发条件 = profiler 实测热点 / 新增高频状态面 / 大版本顺路重构。
