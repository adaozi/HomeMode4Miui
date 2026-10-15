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

### recents-strings（v2.9.46-recents-strings / versionCode 79，2026-10-14）—— 模块页退出多任务 + 硬编码文本资源化

- [x] **多任务排除**：Manifest 给 MainActivity / SettingsActivity 加 `android:excludeFromRecents="true"` —— 模块门禁页与 Grace 设置页不再出现在多任务界面（task 根为 MainActivity，LAUNCHER 入口排除即整任务不进 recents；SettingsActivity 同声明兜底独立 task 场景）。
- [x] **Manifest label 资源化**：application label → @string/app_name；xposeddescription → @string/xposed_description；MediaNotificationListener label → @string/media_service_name（复用既有 key）；GraceAccessibilityService label → @string/accessibility_service_name。
- [x] **MainActivity 硬编码文本全量迁移（~50 处）**：首页说明 / gate 前置条件区（标题、按钮、提示）/ 开关与拒绝回落 Toast / gate 缺项清单 / ①②③④ 状态行 / hook 三级状态 / root 探测结果 / 媒体授权回显 / 桥自诊断 / 日志导出对话框与 Toast —— 全部改走 getString(R.string.*)。
- [x] **LogExporter 回调重构**：Callback 签名 `onDone(String)` → `onDone(boolean success, String message, String path)` —— 替代旧版"已导出"前缀字符串匹配（文案资源化后前缀不再稳定）；busy/failed/success 三类消息资源化；pathLineOf（前缀提取残留）删除；导出文件头/来源描述属文件内容非 UI，保持中文原样（反馈文件不随设备语言变化）。
- [x] **PlaceholderHomeView**（宿主进程）：提示文本经 createPackageContext 解析模块 placeholder_tip 资源（随设备语言），模块资源不可达回落中文原样 —— 沿用同文件 resolveSemanticColor 的包上下文模式。
- [x] **双语落地**：values/strings.xml（EN 默认翻译）+ values-zh-rCN/strings.xml（中文原文）各追加 60+ key，与既有 C 组字符串同文件维护。
- [x] 构建：assembleRelease 通过，versionCode 79。

### l10n（v2.9.47-l10n / versionCode 80，2026-10-14）—— B6.x 桌面文案中文翻译补齐

- [x] **装机实锤**：桌面日程/天气/媒体行大面积英文（Your agenda / today / New event / No upcoming events / now / updated…）—— B6.x 批次搬运时标注"沿用英文，本地化随 C 组"，但 zh-rCN 一直没补，中文设备回落 values 默认（英文）。
- [x] **差集清零**：脚本比对 values/strings.xml vs values-zh-rCN/strings.xml，补齐 74 个缺失 string key（媒体控制 11 / 通知预览 6 / 日程 13 / 天气 22 / 小组件弹层 15 / 私密空间 3 / 通用 4）+ widget_count plurals；仅剩 8 个 translatable=false 字体名（无需翻译）。反向差集为 0。
- [x] 构建：assembleRelease 通过，versionCode 80；产物已导出 .build/out/。

### l10n2（v2.9.48-l10n2 / versionCode 81，2026-10-14）—— 设置页残留英文收尾

- [x] **装机实锤**：设置页仍有英文（非字体名）—— 扫描 zh-rCN 中不含中文的 string 值，锁定 4 个漏网：icon_designer_title（Icon designer → 图标设计器，设置入口标题）、icon_designer_shape_gem（Gem → 宝石形）、icon_designer_shape_cookie（n-side cookie → 甜饼形（N 边））、accessibility_service_name（→ HomeMode4Miui 系统动作）。
- [x] **终验**：zh-rCN 剩余无中文值全部为合理专名（app_name / 字体名×2 / GNU GPLv3 / 搜索引擎品牌×4 / URL 示例），无 UI 文案残留。
- [x] 构建：assembleRelease 通过，versionCode 81；产物已导出 .build/out/。

### settings-home（v2.9.49-settings-home / versionCode 82，2026-10-14）—— 设置页退出直达桌面

- [x] **需求**：设置页返回不回模块主界面，直接回桌面。
- [x] **场景根因**：模块任务栈可能残留旧的 MainActivity（gate 页）—— 用户先手动开过模块主界面，之后从桌面进设置页时栈为 [MainActivity(旧), SettingsActivity]，设置页普通 finish 落回旧主界面。
- [x] **修复**：SettingsActivity 新增 exitToHome()（CATEGORY_HOME 拉起被接管的 com.miui.home + finishAffinity 清整个模块任务栈），三处退出路径统一接入：① Compose 根页返回箭头 onBack；② 系统返回手势（onBackPressedDispatcher callback，仅根页生效——NavHost 子页返回自持弹栈，handleRootBack=false 保证 PredictiveBackHandler 不拦截）；③ closeRequests（跨进程 moveWidget 关页回桌面看编辑态）。
- [x] **防回归**：finishAffinity 同时清掉残留 gate 页，任务栈归零，后续入口不再堆叠。
- [x] 构建：assembleRelease 通过，versionCode 82；产物已导出 .build/out/。

### folder-icon（v2.9.50-folder-icon / versionCode 83，2026-10-14）—— 文件夹自定义图标主页生效

- [x] **装机实锤**：设置文件夹图标（FolderEditor → 图标设计器）预览正常，主页/抽屉仍是默认圆标。
- [x] **根因**：宿主 GraceHomeViewModel.folderItem 直接 folder.asApp()（icon=null）—— 上游 LauncherViewModel.kt:445-447 会先把 item_icons 里 key "folder:<id>" 的自定义选择经 ItemIconStore.apply 渲染成位图存进 LauncherUiState.folderApps，folderItem 优先取装饰项；本仓 folders 阶段搬运时裁掉了 folderApps 链路，设置页设计器预览直连 ItemIconStore 所以能显示。
- [x] **修复（对齐上游）**：① GraceHomeUiState 增 folderApps: Map<folderId, LauncherApp>；② folderItem 优先 folderApps[id] 回落 asApp()；③ 新增 decorateFolders（无选择的文件夹零渲染），接入 refreshApps（冷启动）+ iconsJob collect（设计器保存后立即生效）双链；④ AppDrawerScreen 补 folderApps 参数（上游签名对齐），抽屉 FolderRow app = folderApps[id] ?: asApp()（onSwipeLeft 同步）；⑤ Route 层透传 state.folderApps。
- [x] 覆盖面：主页收藏区文件夹行 / 抽屉文件夹行 / 弹层锚点动画（行图标即锚）/ 左滑第一项 open 语义；文件夹弹层成员图标不做装饰为既有降级登记，不在本批范围。
- [x] 构建：assembleRelease 通过，versionCode 83；产物已导出 .build/out/。

### icon-snapshot（v2.9.51-icon-snapshot / versionCode 84，2026-10-14）—— 单个图标设计保存不生效

- [x] **装机实锤**：图标设计器改单个应用图标，保存提示成功，主页不变。
- [x] **根因**：iconsJob collect 里算出的 icons 只是局部变量，只喂了 itemIcons UI state 与 Grace 按钮；`iconsSnapshot` 字段声明后**从未赋值**——decorateApps / decorateFolders 的冷启动路径（refreshApps）与即时重装饰路径全部从该字段读 per-item 选择，永远拿到空 map。上游 LauncherViewModel.kt:427 的 itemsSnapshot 在 collect 内落快照字段，本仓搬运时漏了这一行。连带影响：v2.9.50 的文件夹图标热保存链（传局部 icons）生效、但冷启动链读空会丢。
- [x] **修复**：iconsJob collect 头部 `iconsSnapshot = icons`（先落快照再渲染），一行修复覆盖单个应用图标 + 文件夹图标两条消费链（冷启动 / 即时）。
- [x] 构建：assembleRelease 通过，versionCode 84；产物已导出 .build/out/。

### refresh-keep（v2.9.52-refresh-keep / versionCode 85，2026-10-14）—— 安装/卸载刷新期列表不再整列消失

- [x] **装机实锤**：每次安装/卸载应用，主页收藏区 + 抽屉整个列表消失几秒才显示。
- [x] **定性**：移植引入的偏差，非上游设计。上游 favoriteApps/appListApps 只挡 settings 加载/失败（isLoadingSettings || settingsLoadFailed）；本仓 P1 搬运时在 favorites/favoriteItems/appListApps 三个 getter 加了 `isLoadingApps ||` 空化 —— 包变化事件触发 refreshApps（重查 LauncherApps + 200+ 次图标装饰，耗时数秒），刷新期 getters 返回空列表 → 整列消失。上游语义是后台刷新、旧列表保持显示、新列表就绪后原位换入。
- [x] **修复**：三个 getter 去掉 isLoadingApps（保留 appLoadFailed 失败语义）；refreshApps 本就不清 apps（成功才整体换入），去掉空化后即天然对齐上游。Route 不消费 loading 态，无副作用；isLoadingApps 字段保留（收藏编辑 shim 的 loading 指示仍消费）。
- [x] 构建：assembleRelease 通过，versionCode 85；产物已导出 .build/out/。

### icon-perf（v2.9.53-icon-perf / versionCode 86，2026-10-14）—— 重启桌面后字母索引滑动卡顿

- [x] **装机实锤**：每次重启桌面后，抽屉字母索引条滑动卡顿。
- [x] **定性**：v2.9.51（iconsSnapshot 修复）激活的冷启动装饰渲染风暴。v2.9.51 前 iconsSnapshot 恒空 → decorateApps 全局短路 → 冷启动零图标渲染；之后只要 item_icons 非空，**每个应用**（含无自定义选择的）都走 applyDesign「默认设计全量重渲染」：systemDrawable binder 往返 + 144px 多层位图渲染 + monochrome 逐像素扫描，冷启动 200+ 次、~20MB 位图分配。上游独立进程可承受；宿主 com.miui.home 进程 IO 线程与 MIUI 自身 UI/Render 线程抢 CPU + GC 压力 → 重启后首次滑动字母条窗口期卡顿。
- [x] **修复①（主）decorateApps 逐应用快速路径**：无 per-item 选择 + 无全局面设计 + 未开主题化 + loadApps 图标存在 → 跳过重渲染，保留原图标、仅按设计分支语义清 monochromeIcon（桌面 themed 圆显隐与重渲染版逐字节一致，iconPackPackage 同为 theme kind 原样保留语义）；monochrome 本就为空时零 copy（保持实例同一性，行重组最少化）。渲染量从 200+ 收敛到「有自定义选择 + 有全局面 + themed 开」的子集。loadApps 图标缺失的应用仍走全量渲染（systemDrawable 兜底补图不受影响）。
- [x] **修复②重装饰收敛为单一入口 applyItemIconSnapshot**（Mutex 串行「读列表→装饰→原子换入」）：iconsJob 与 refreshApps 装载尾部补偿共用；itemsDecoratedWith 快照标记去重（Room 流重发不重复全量渲染）；isLoadingApps 在途让路（在途 refreshApps 会读到刚落的最新快照）——**顺带修复 v2.9.51 起的空列表覆盖竞态**（iconsJob 在 refreshApps 换入间隙用空/旧 apps update 覆盖装载结果）；装载期间快照前进由 refreshApps 尾部以最新快照补一轮。
- [x] 构建：assembleRelease 通过，versionCode 86；产物已导出 .build/out/。

### favorites-first（v2.9.54-favorites-first / versionCode 87，2026-10-14）—— 重启桌面后应用区延迟 1-2 秒

- [x] **装机实锤**：重启桌面后时钟/文件夹立即显示，应用（收藏区）要等 1-2 秒。
- [x] **根因**：收藏行渲染依赖全量 apps 列表，而装载是 200+ 应用**串行** getBadgedIcon 图标解码（单 IO 线程 1-2s），收藏区被迫等全量就绪。时钟（widget）与收藏文件夹（Room folders 流）不依赖 apps 所以先出。
- [x] **修复①（主）两段式装载**：AppRepository 新增 enumerateApps（枚举全量骨架：label/key/包图标就绪，系统图标延后为按 key 的惰性解码句柄）+ AppInventory.withIcons(keys)（limitedParallelism(4) 并行解码子集回填）。VM refreshApps：phase 1 = withIcons(已存收藏序子集) → decorateApps → 先换入（收藏区随时钟立即绘制，favoriteOrder 用 currentOrder() 只读值）；phase 2 = 全量解码 → favoritesFor/workProfiles → 整体换入。首装无已存收藏序（currentOrder()=null）自动跳过 phase 1（默认收藏要由 favoritesFor 基于全量列表生成）。loadApps 保留为 enumerate+withIcons(全量) 便捷封装（SettingsViewModel 应用选择列表等调用方零变化）。
- [x] **修复②decorateApps 骨架跳过**：icon==null 的骨架应用（phase 1 未解码）在无 per-item 选择 + 无全局面设计时跳过装饰 —— 否则 systemDrawable 兜底会对 200+ 骨架触发全量重渲染（v2.9.53 修复的风暴复现）；phase 2 解码全量后统一装饰兜底。装饰分支重写为 when 三路（骨架跳过 / 快速路径 / 全量渲染）。
- [x] **已知权衡**：phase 1→2 间隙（约 0.5-1s）若立即打开抽屉，未解码完成的应用行短暂显示字母占位圆标，全量换入后恢复 —— 上游为同款瞬时行为（其独立进程装载更快），实测窗口很短。
- [x] 构建：assembleRelease 通过，versionCode 87；产物已导出 .build/out/。

### sync-reveal（v2.9.55-sync-reveal / versionCode 88，2026-10-14）—— 首页内容同步揭示

- [x] **需求（装机反馈）**：等收藏区应用解码完成后再显示时钟/widget/文件夹等一切首页内容，整体一起出现（消除"时钟先出、应用后跳"的分层加载观感）。
- [x] **实现**：GraceHomeUiState 新增 homeReady 门控位——三条路径置 true：两段式 phase 1 收藏子集换入（v2.9.54）/ 无已存收藏序的全量换入 / 装载失败（空态兜底，不让桌面永久空白）；后续包变化刷新不重置（桌面已显示，防闪断）。Route 侧 HomeScreen 与 GraceButton 按 homeReady 条件组合（条件组合而非 alpha=0，避免不可见但可点击的幽灵热区）；AlphabetRail 无需门控（apps 未就绪时 letters 为空，rail 自行不渲染）；壁纸背景层不受影响。
- [x] 构建：assembleRelease 通过，versionCode 88；产物已导出 .build/out/。

### gadget 归档说明（2026-10-14）—— MIUI 内置部件尝试已回退，挂起待后续

- v2.9.56/57（vc89/90，commit 96b15da/d052b80，tag v2.9.56-gadget / v2.9.57-gadget-cl）尝试接入
  MIUI 内置 gadget 部件（时钟 4 规格/一键清理/全局搜索，反射门面 MiuiGadgets + GadgetHostView
  + "gadget:<id>" 落库约定）：装机反馈选择器分区已出现，但**部件添加后不可使用**（渲染/交互不工作）。
- 用户决定回退到适配前（v2.9.55-sync-reveal / vc88）：revert 提交 591af59/（前一 revert），
  代码树与 v2.9.55 逐字节一致。两枚 tag 保留在历史中可随时翻阅。
- 逆向资产保留（.workbuddy/memory/2026-10-14.md）：GadgetFactory.getNoMtzInfo/createGadget、
  id 映射 4/6/7/8=ClockGadgetDelegate、12=NormalClearButton、21=CommonGlobalSearch、
  生命周期 onAdded→onCreate→onResume/onPause/onDestroy、跨 classloader 反射铁律。
- **下次重启该方向的排查线索**：addGadget 返回的 Gadget 直接放进 Compose AndroidView 不可用，
  疑似依赖宿主 Launcher 环境（mWorkspace.addInScreen / LauncherFrameLayout 的属性动画体系 /
  TrackView 统计），需对照 CellLayout.createNewGadget 完整宿主上下文，而非裸 View 挂载。

### OS4 段回退 + Route B PoC（2026-10-14）—— v2.9.66-os4b-poc / vc97

- **OS4 Java 层适配段整体回退**：revert commit 4206741（revert 8b6d869..HEAD，即
  v2.9.58~v2.9.65 / vc89~96 全部 8 个 commit），工作树与 v2.9.55-sync-reveal（vc88）
  逐字节一致；OS4 优雅退场（os4Skip/FAMILY_OS4）与诊断探针全部移除。回退版产物
  .build/out/HomeMode4Miui-v2.9.55-sync-reveal-88.apk。
- **Route B（native hook）可行性评估**：docs/RE_HyperOS4_NATIVE_HOOK.md。基于
  WOMMO（GPL-3.0）+ HyperOS4SwipeGate（MIT）代码级精读（源码在
  .workbuddy/re/os4-ref/），结论：OS4 桌面（Flutter UI + Rust 核心，APK 零 Java
  字节码）native hook 工程可行，需 LSPosed Modern API 102（≥7846 fork）+
  Zygisk Next 1.5.0+。
- **符号情报突破**：libapp.so / libapp_launcher.so 均带 .gnu_debugdata（XZ 压缩
  符号表），解出 Dart 符号 78,505 个、Rust 符号 23,071 个（CSV 在
  .workbuddy/re/os4/）。此前"魔数扫描 0 命中"结论作废。SwipeGate 全部目标函数
  在 7748 版按符号名精确命中：on_swipe_process @0x702690、convert_offset
  @0x6b3cc4、handle_back_gesture @0x6fe948、is_merge_back_break_open_anim_support
  @0x6cf9a0（RVA 与参考版本全部不同 → 实锤按符号定位是唯一可持续方案）。
- **PoC phase 1（本批）**：native_init 观察入口，零业务 hook。新增
  app/src/main/cpp/{native_api.h, homemode4miui_native.cpp, CMakeLists.txt} +
  META-INF/xposed/native_init.list（libhomemode4miui_native.so）；legacy 入口
  assets/xposed_init 不动，两链路独立加载。实现：hyos_spawner/cmdline 双身份
  判定、观察库 libapp.so/libapp_launcher.so/libhyper_os_flutter.so/
  librust_maml_sdk.so 的 load 回调日志 + backfill 探针 + Dart snapshot 符号
  dlsym 验证，全 WARN 级日志（tag H4MNative）。NDK 29.0.14206865 + cmake 3.22.1，
  16KB page 对齐（max-page-size=16384，PT_LOAD align 0x4000 实测通过）。
- 构建：assembleRelease 通过，vc97；产物 .build/out/HomeMode4Miui-v2.9.66-os4b-poc-97.apk。
- **装机验证点（下一步）**：① 混装 APK 在 LSPosed 中 legacy 入口是否照常加载
  （OS2/OS3 回归）；② OS4 宿主内 logcat 过滤 H4MNative 看 spawner/launcher
  身份判定与库加载回调是否触发；③ 若 native_init 不触发，检查 LSPosed fork
  是否要求 META-INF/xposed/module.prop 与 java_init.list 同批声明。
- 里程碑批次规划：phase 2 = madvise guard；phase 3 = 只读 trampoline 验证；
  phase 4 = 选点真 hook（建议 Rust 层起步）。

### 导出日志附 H4MNative 探针段（2026-10-14）—— v2.9.67-os4b-diag / vc98

- LogExporter 新增 buildH4mSection（追加在主体之后，独立 try/catch 永不拖垮导出）：
  ① su `logcat -d -v time -s H4MNative:V` 采集 native_init PoC 日志（logd 全局
  缓冲，含宿主/launcher 进程），无 su 降级本进程（注明 UID 隔离局限）；
  ② 附宿主上下文：`pm path com.miui.home` + dumpsys version（判 OS 家族）；
  ③ 时间范围内无输出时给出占位说明（非 OS4 / 未加载探针 / native 入口未启用）。
- 复用 runCommandCapture（行级时间过滤 + 10MB 限幅 + stderr 排空），零新依赖。
- 用途：OS4 实机上点导出按钮即可拿到 spawner/launcher 身份判定与库加载回调
  日志，直接发给 AI 定位 PoC phase 1 是否触发。
- 构建通过；产物 .build/out/HomeMode4Miui-v2.9.67-os4b-diag-98.apk。

### META-INF 元数据补齐：module.prop + scope.list（2026-10-14）—— v2.9.68-os4b-diag2 / vc99

- 双机日志定位：OS2 机 legacy 无回归（hook installed 12 条 0 FAILED）；OS4 机
  （LSPosed 2.2.0 / 7854 + NeoZygisk 2.4）重启桌面后 native_init 仍未触发，且
  OS4 launcher 进程零模块日志。
- 根因候选实锤：对照 WOMMO/SwipeGate，两者 META-INF/xposed/module.prop 均为
  必填（minApiVersion=102 / targetApiVersion=102），本模块缺该文件 → fork 不把
  模块注册为 modern/native 入口。补 module.prop（staticScope=false /
  autoHotReload=false）+ META-INF/xposed/scope.list（com.miui.home）。
- OS2/OS3 兼容性：旧容器不解析 META-INF/xposed/*（modern 格式是 fork 引入的），
  legacy 链路不受影响；OS2 机导日志复核一次即可闭环。
- 若 vc99 后 native_init 仍不触发：下一嫌疑是 NeoZygisk（ptrace 注入）不向
  hyos_spawner 注入 native 模块（SwipeGate 要求 Zygisk Next 1.5.0+ 的 HyOS
  Runtime），届时查 LSPosed 管理器日志页的 native 模块加载行。
- 构建通过；产物 .build/out/HomeMode4Miui-v2.9.68-os4b-diag2-99.apk。

### 导出日志附容器诊断段（2026-10-14）—— v2.9.69-os4b-diag3 / vc100

- LogExporter 新增 buildContainerSection：① /data/adb/modules/* 的 name/version
  清单（识别 LSPosed fork 与注入器）；② lspd/lsposed 日志目录 listing；
  ③ 容器日志按关键词（homemode4miui/com.miui.home/hyos/spawner/native_init/
  NativeModule）过滤，续行跟随，限 400 行保最新。
- 目的：导出按钮一站式拿齐"管理器日志页"才有的容器侧信息（native 模块注册、
  hyos_spawner 注入记录），不用再让用户手动截图。
- 构建通过；产物 .build/out/HomeMode4Miui-v2.9.69-os4b-diag3-100.apk。

### 容器日志采集修复（2026-10-14）—— v2.9.70-os4b-diag4 / vc101

- 双发现：① fork 日志文件名带时间戳（modules_2026-10-14T*.log），写死路径永远
  落空；② 容器段的时间窗过滤把启动期的模块加载记录全滤掉。
- 修复：tryLspLog 改目录 glob（modules*.log）；容器诊断段 cat 不过滤时间
  （400 行保最新已限幅）；删除死常量 LSP_LOG_PATHS。
- 构建通过；产物 .build/out/HomeMode4Miui-v2.9.70-os4b-diag4-101.apk。

### Route B phase 2：madvise guard（2026-10-14）—— v2.9.71-os4b-p2 / vc102

- phase 1 实机通过（18:51）：Zygisk Next 1.5.2 换装后 H4MNative 全链路打通
  （native_init accepted / load 回调全命中 / Dart 符号可达）；关键观察 = libapp.so
  load 回调重复 4 次（同 handle），installer 幂等实锤。
- 本批新增 hook_guard.{h,cpp}（机制改自 WOMMO lsposed_hook_backend.cpp，GPL-3.0
  同源）：
  ① EnsureMadviseGuard：LSPosed hookFunc inline hook libc madvise，一次性状态机
  （幂等，防重复回调重复安装）；
  ② GuardedMadvise：MADV_DONTNEED 区间按登记页分段剔除，其余透传；
  ③ ProtectHookRange：补丁前登记（时序硬约束），32 页上限；
  ④ RunGuardSelfTest：trampoline 读回（/proc/self/mem，bti c prologue 消失判定）
  + 双页自测试（登记页 DONTNEED 后 0xA5 存活、对照页被内核清零）。
- 触发时机：native_init backfill（libapp 已映射）或首个 libapp.so load 回调，
  原子一次性；phase 1 的 dart image dlsym 检查由 self test 取代。
- 构建通过；产物 .build/out/HomeMode4Miui-v2.9.71-os4b-p2-102.apk。
- 实机判读：H4MNative 期待 `guard: madvise guard installed` +
  `guard self-test: ... => PASSED`；FAILED 时先看 head hex 与 trampoline=0。

### native 日志双写文件镜像（2026-10-14）—— v2.9.72-os4b-p2b / vc103

- 排障发现：launcher 冷启动 40s 的日志量把 logd main 缓冲打穿，19:01:38 启动的
  native_init/backfill/libapp/phase2 十几行全部被轮转掉（仅剩最后 2 行），phase 2
  在 vc102 上到底跑没跑无从判断。
- 修复（照搬 SwipeGate fileLog 模式）：新增 h4m_log.h —— logcat（WARN/ERROR）
  + 文件镜像双写 `/data/user_de/0/com.miui.home/cache/h4m_native.log`（行前缀
  HH:MM:SS.mmm）；LogExporter H4MNative 段追加读该文件尾部 64KB（不过滤时间）。
- 构建通过；产物 .build/out/HomeMode4Miui-v2.9.72-os4b-p2b-103.apk。
- 实机判读（重启桌面后导出）：文件镜像段必有完整序列 native_init → guard
  installed → self-test PASSED/FAILED；logd 只当旁证。

### phase 2 自测试修复（2026-10-14）—— v2.9.73-os4b-p2c / vc104

- vc103 实机：guard 安装成功（hookFunc 打通 libc madvise，幂等守卫生效——4 次
  libapp.so 回调仅装 1 次），但 self-test 卡在 /proc/self/mem read failed
  （SELinux 拒绝 proc_mem 读），双页测试未执行；文件镜像也未出现（写 cache 疑似
  同类拒绝，errno 待确诊）。
- 修复：① 双页 splitting 测试提前为判定项（未 hook 时登记页会被真 madvise 清零
  → 测试本身即 hook 存活证明，不依赖 mem 读）；② mem 读取降级为诊断项，open/
  lseek/read 全记 errno；③ 文件镜像三路径（user_de/user/data）+ 失败 errno 诊断
  （50 次封顶）；④ 导出器同步支持三路径。
- 构建通过；产物 .build/out/HomeMode4Miui-v2.9.73-os4b-p2c-104.apk。

### Route B phase 3：Rust 层只读观测 hook（2026-10-14）—— v2.9.74-os4b-p3 / vc105

- phase 2 实机 PASSED（19:19）：guard 安装 + 双页自测试全绿，且安装后 1 分钟内
  拦下一次 HyperOS 真实 MADV_DONTNEED（0x73f9f1b000+0x2000）——守卫实战验证。
  /proc/self/mem 被拒 errno=13（SELinux）→ trampoline 检查永久降级为诊断，
  后续以补丁读回验证为主。
- 本批 obs_hook.{h,cpp}：在 libapp_launcher.so 的 load 回调里，dlsym(handle)
  解析 FRB 导出符号（ARC increment Uri 主选 / frb_free_wire_sync_rust2dart_dco
  备选，均为 extern C 单指针 ABI），hook_func 只读观测 hook：计数 + 首次/每 256
  次落日志，原样转发。幂等（回调重复 2 次）。目标选择 fail-closed（全失败不装）。
- 已知小风险：hook_func 返回与 backup 存储之间的窗口期丢一次调用（ARC 泄 1 引用，
  观测期可接受，有日志）。
- 文件镜像仍缺席但 native 无 open 失败日志 → 加一次性 "file mirror active path="
  诊断（疑似 launcher mnt namespace 隔离，su 全局命名空间读不到其写入）。
- 构建通过（APK mtime 19:43 校验）；产物 .build/out/HomeMode4Miui-v2.9.74-os4b-p3-105.apk。
- 实机判读：`phase3: observation hook installed on ...` + 桌面操作后
  `observation hook call #1/#256/...` + launcher 不崩 = phase 3 过，进 phase 4
  （on_swipe_process 真门控，按符号定位）。

### logd 轮转治本 + namespace 穿透读镜像（2026-10-14）—— v2.9.75-os4b-p3b / vc106

- vc105 实机：容器 verbose 证实两次 launcher 重启（20:04:23 / 20:08:33）native
  模块均加载成功（zn-daemon64 "loading module into ... hyos_spawner" 注入链确认），
  但 logcat 5 分钟窗口内 H4MNative 全被轮转——58s 冷启动日志量打穿 main 缓冲。
- 治本：① 导出时 `su logcat -G 4M` 扩 logd 缓冲（幂等保底）；② 镜像读取加
  /proc/<pidof com.miui.home>/root/ 前缀（穿透可能的 mnt namespace 隔离），
  直连路径兜底。
- phase 3 观测 hook 无需改动（vc105 已装载，只是看不见日志）。
- 构建通过；产物 .build/out/HomeMode4Miui-v2.9.75-os4b-p3b-106.apk。

### Route B phase 4a：on_swipe_process 只读观测（2026-10-14）—— v2.9.76-os4b-p4a / vc107

- phase 3 实机 PASSED：观测 hook 三次启动均装载 + call #1 转发成功 + launcher 稳定；
  文件镜像经 /proc/<pid>/root 全量可读（namespace 穿透成功），跨启动历史齐备。
- 指纹交叉验证：按符号定位的 on_swipe_process（7748 vaddr 0x702690）入口 24 字节
  与 SwipeGate gesture-frame-v2 逐字节一致——符号定位与社区实测指纹互证。
- 本批 gesture_hook.{h,cpp,S}：dl_iterate_phdr 定位 base → base+0x702690 处 24
  字节指纹精确比对（fail closed）→ hook_func + ABI 透明 AArch64 trampoline
  （改自 SwipeGate swipe_hook_entry.S，MIT 署名）：保存 x0..x8/q0..q7/x30，
  只读记录 w1(ready)/w2(side)/s0(dist)，Ready 边沿单独落日志，原样转发。
- 构建修复两笔：CMake 开 ASM 语言；gesture_obs_enter 移出 namespace 保证 unmangled。
- 构建通过；产物 .build/out/HomeMode4Miui-v2.9.76-os4b-p4a-107.apk。
- 实机判读：`phase4a: gesture observation hook installed` + 侧滑返回手势时
  `call #1` / `Ready edge #n`（dist 递增）+ 桌面手势功能正常 = 观测闭环，
  phase 4b（阈值门控改写 s0）具备全部前置。

### OS4 运行时结构探针（2026-10-14）—— v2.9.77-os4b-probe / vc108

- 方向定案（User）：phase 4b 起，目标是**让 HomeMode4Miui 完整功能跑在 OS4 桌面**，
  非独立小功能。phase 3 实锤 legacy 注入可用 → 架构 = Java 侧（框架类 hook，不依赖
  宿主 dex）挂载 Compose 桌面到窗口层 + native 侧（已验证）状态读取/门控。
- 关键前置问题：OS4 桌面的 View 层级结构（FlutterView/SurfaceView/DecorView 形态）。
  uiautomator 对 Flutter null root，但模块 Java 代码在进程内 —— 直接探。
- 本批：Os4Probe.java（hook 框架类 Instrumentation.callActivityOnCreate，after 后
  2s 遍历 DecorView 树 14 层深度：类名/id/尺寸/visibility/alpha）+ classloader
  类探测（com.miui.home.Launcher / io.flutter.embedding.* 五连）+ 每活动类只 dump
  一次。HostCompat 补 FAMILY_OS4（vc ≥ 800M，先于 OS3 阈值判定）+ family()/
  hostVersionCode() getter + label()。
- 既有 OS2/OS3 hook 在 OS4 上会自然 FAILED（宿主类不存在）——日志即证据，无行为风险。
- 构建通过；产物 .build/out/HomeMode4Miui-v2.9.77-os4b-probe-108.apk。
- 判读：导出日志中 Os4Probe: 段 —— activity created 类名 + view tree + classloader
  HAS/MISSING 清单 → 直接决定挂载落点（往哪挂、怎么压住 Flutter 面）。

### Modern API 入口（OS4 Java 触达路径）（2026-10-14）—— v2.9.78-os4b-modern / vc109

- v2.9.77 探针轮判读：native 全绿，但 Java 侧在 OS4 launcher 进程零日志 + 容器
  verbose 只有 native 加载记录（legacy 派发从不进 com.miui.home 主进程）→
  装机实锤 7854 fork 对 OS4 主进程只派发 Modern API 模块（Eta 同款路径）。
- 本批补 Modern 轨：ModernEntry.java（extends libxposed XposedModule，无参构造，
  onPackageLoaded 过滤 com.miui.home → hook 框架 Instrumentation.callActivityOnCreate
  → 回调 Os4Probe.probeActivity）+ META-INF/xposed/java_init.list + proguard keep。
- Os4Probe 重构：日志直写 logcat（modern 模块禁访问 legacy API，ModLog 的
  XposedBridge 引用不可靠）；probeActivity 内做 vc≥800M 门禁（PackageInfo.
  getLongVersionCode，规避 api-82 jar 的 ApplicationInfo 桩缺 longVersionCode）；
  MainHook 0c 探针任务移除（legacy 路径在 OS4 永不派发）。
- libxposed api 102.0.0 主构件是 AAR（Maven Central .jar 404），已提取 classes.jar
  为 app/libs/libxposed-api-102.jar。
- 双轨并存：legacy（xposed_init，OS2/OS3）+ modern（java_init.list，OS4），门禁
  互不越界。构建通过；产物 .build/out/HomeMode4Miui-v2.9.78-os4b-modern-109.apk。
- 判读：OS4 导出应首次出现 Java 侧日志 —— "ModernEntry: Instrumentation hook
  installed" + "Os4Probe: activity created: <壳类名>" + view tree + classloader
  HAS/MISSING。OS2/OS3 机回归确认 legacy 不受影响（modern 路径 vc<800M 零行为）。

### Route B phase 5：native → Java 自举（2026-10-14）—— v2.9.79-os4b-jb / vc110

- v2.9.78 判读：modern Java 派发同样不进 OS4 launcher 主进程（ModernEntry 零日志，
  verbose 仅 native 加载记录）；结合时间线（13:46 Eta 成功 = NeoZygisk 时代，
  18:49 换 Zygisk Next 后 Java 派发整体消失）实锤 **Zygisk Next HyOS 模式下
  OS4 主桌面进程 native-only**。
- 架构定案：Java 触达 = **native 自举**——native 模块已在进程内，JNI
  AttachCurrentThread → ActivityThread.currentApplication()（重试兜底）→
  PathClassLoader 加载模块 APK 自身 dex → Os4Bootstrap.init(Context, apkPath)。
  零容器 Java 派发依赖。
- 本批 java_bridge.{h,cpp} + os4/Os4Bootstrap.java（R8 keep）：
  ① 生命周期回调捕获桌面壳 Activity → Os4Probe 结构探针（View 树终于可见）；
  ② 首里程碑标记：content 层顶部 24dp 非触摸标记（不拦截手势/焦点），
  证明「模块 UI 可画在 OS4 桌面上」。
- Os4Probe 保持模块 APK 内共享（modern 路径与 bootstrap 路径同代码）。
- 构建通过（APK mtime 21:20）；产物 .build/out/HomeMode4Miui-v2.9.79-os4b-jb-110.apk。
- 实机判读：`java bridge: bootstrap invoked — Java side is live` +
  `Os4Bootstrap: alive` + `marker mounted into <壳类>` + **桌面上可见黑色半透明
  标记条** + 桌面功能不受影响 = OS4 Java 触达闭环，UI 挂载架构正式可行。
- 修复批（vc110 重导）：JNI_GetCreatedJavaVMs 在 Android 17 linker namespace 下
  dlsym(RTLD_DEFAULT) 不可见（实测 errno 无符号）→ 三级降级解析：①RTLD_DEFAULT
  ②dlopen("libart.so") ③/proc/self/maps 找 libart 基址 + 手解文件 ELF dynsym
  （symtab 紧邻 strtab 启发式计数，文件读取不受 proc_mem SELinux 限制）。
- 产物 .build/out/HomeMode4Miui-v2.9.79-os4b-jb2-110.apk（版本号未变，同 vc110）。

### JavaVM 解析修复批（2026-10-15）—— v2.9.80-os4b-jb3 / vc111

- vc110 实机：`vm resolver exhausted` —— 三级全败。根因 = maps 解析权限位判断
  笔误：`r-xp` 的 'x' 在第 3 位，判成了第 1 位 → 所有可执行行被跳过，
  FindLibartPath 永远为空，tier 3 短路。
- 修复：perms 判定改为 [1]=='r' && [3]=='x'；exhausted 日志附 libart 路径诊断。
- 构建通过（APK mtime 18:54 校验）；产物 .build/out/HomeMode4Miui-v2.9.80-os4b-jb3-111.apk。

### tier4：主可执行文件内存 dynsym（2026-10-15）—— v2.9.81-os4b-jb4 / vc112

- vc111 实机：`vm resolver exhausted (libart=)` —— perms 修好后 maps 里依然
  没有任何 libart.so → **静态 ART 假设**：ART 可能静态链接进 hyos_spawner
  单体 exe（launcher 子进程 exe 就是它，与"无 libart 映射"吻合）。
- 新增 tier 4：getauxval(AT_PHDR/AT_PHNUM) 定位主可执行 program headers →
  ELF magic 向下扫描定 base → 内存内直读 PT_DYNAMIC → dynsym/strtab →
  按符号名直查 JNI_GetCreatedJavaVMs。无文件读取、无 namespace/SELinux 限制。
- tier3 open 失败补 errno 日志（区分"maps 没路径"和"SELinux 拒打开"）。
- 构建通过（APK mtime 19:19 校验）；产物 .build/out/HomeMode4Miui-v2.9.81-os4b-jb4-112.apk。

### 崩溃循环修复 + 断路器（2026-10-15）—— v2.9.82-os4b-jb5 / vc113

- vc112 实机：SIGSEGV 崩溃循环（fault addr 0x390，SEGV_MAPERR）→ 系统兜底桌面。
  符号化（未 strip 中间产物）：#00 = JavaBridgeThread+0xb5c、#01 = thread proxy
  —— tier4 的**内存直读**（AT_PHDR magic 扫描 + dynsym 内存遍历）在 base 误判时
  踩野指针。铁律事件。
- 架构修正：tier4 重写为**纯文件解析** —— readlink /proc/self/exe（= spawner
  本体，静态 ART 假设目标）→ pread 全量（Ehdr 强校验 magic/machine/base 推导
  AT_PHDR - e_phoff → PT_DYNAMIC → DT_HASH nchain 精确符号数）→ 符号命中返回
  base + st_value。零内存直读，任何失败只返回 null。
- 断路器：/data/user_de/0/com.miui.home/cache/h4m_bridge_state 记录
  "running:<apkPath>" / "ok:<apkPath>"；同 APK 路径上次未到 ok = 上次崩溃 →
  本次跳过 bootstrap。任何缺陷最多崩一次，绝不进循环；APK 更新路径变化自动重试。
- 宿主注意：设备已更新为 /product/priv-app 7719 版（vc 801027719 < 7748）——
  手势指纹按设计 fail-closed（不装），7748 符号表仍作参考资产。
- 构建通过（APK mtime 19:39 校验）；产物 .build/out/HomeMode4Miui-v2.9.82-os4b-jb5-113.apk。
- 用户恢复步骤：LSPosed 停用模块 → 重启（桌面恢复）→ 装 vc113 → 重新启用 → 重启桌面。

### 状态语义修正 + 全量诊断（2026-10-15）—— v2.9.83-os4b-jb6 / vc114

- vc113 实机：resolver 优雅失败后状态留 "running" → 断路器误伤后续启动。
  语义修正：优雅失败写 "failed:<apk>"（可重试），仅真崩溃（未写终态）留在
  "running" 触发跳过。
- tier4 静默退出点全补日志（readlink/header/base/PT_DYNAMIC/符号未命中）；
  resolver 新增 **maps 全量 so 路径枚举**（/apex + /system + /product 去重，
  64 条封顶）—— 一次导出终结"ART 到底在哪"的猜测。
- 构建通过（APK mtime 20:27 校验）；产物 .build/out/HomeMode4Miui-v2.9.83-os4b-jb6-114.apk。

### 断路器时间窗语义（2026-10-15）—— v2.9.84-os4b-jb7 / vc115

- vc114 实机：断路器再次误伤 —— 同签名覆盖安装不换 /data/app 路径，vc113 的
  "running:<path>" 拦住 vc114。"APK 更新路径变化自动重试"假设不成立。
- 修正：running 状态改为 "running:<unix秒>"，**90 秒时间窗**——窗内视为崩溃
  （快速循环立即切断），超窗或 failed/ok 一律放行重试。用户重启/等待即可恢复。
- 构建通过（APK mtime 20:39 校验）；产物 .build/out/HomeMode4Miui-v2.9.84-os4b-jb7-115.apk。
