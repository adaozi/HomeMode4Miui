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

### OS4 适配终止归档（2026-10-15）—— revert 9f52967，恢复 v2.9.55-sync-reveal / vc88

- **决策（User）**：放弃 OS4 适配。revert 4206741..ffde171 全段（v2.9.58~v2.9.86，
  vc89~117），代码与版本号恢复到 v2.9.55-sync-reveal / vc88。
- **技术定案**（完整证据链见 docs/RE_HyperOS4_NATIVE_HOOK.md 归档）：
  OS4 桌面主进程 = 纯原生进程（Flutter AOT + Rust + binder-ndk 直连
  system_server），**无 ART 虚拟机**（JNI_GetCreatedJavaVMs 返回 vm=0x0 count=0，
  libnativehelper 转发符号经 tier5 命中实测）——legacy/modern Java 派发与
  native→Java 自举均架构性不可行。
- 保留资产：
  - docs/RE_HyperOS4_NATIVE_HOOK.md（Route B 全评估 + 进程模型 + 符号体系）
  - .workbuddy/re/os4/（libapp.so 78,505 符号 / libapp_launcher.so 23,071 符号 CSV、
    7719/7748 宿主分析、WOMMO/SwipeGate 参考源码 os4-ref/）
  - .workbuddy/memory/2026-10-14~15.md（逐批排障记录：五级符号解析、断路器、
    文件镜像、崩溃循环复盘）
- 若未来 OS4 桌面架构变化（如恢复 dex/ART）或出现 Java 派发能力，本归档可直接续命。
- 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.55-sync-reveal-88.apk。

### wipe-v3（v2.9.61-wipe-v3 / versionCode 94，2026-10-16）—— 清除第四桌面数据根因修复

- [x] **需求（装机反馈）**：模块主页"清除第四桌面数据"按钮 wipe-v2（vc93）实测无效——Toast 走完、桌面重启后设置/收藏/图标数据仍回归。
- [x] **根因**：v2 时序模型假设 force-stop 后桌面进程保持死亡，实际桌面是 HOME 应用，force-stop 后系统几百 ms 内自动拉起新进程：① 新进程在 v2 首删（+0.5s）前打开旧 DB/加载旧 SP（旧数据进内存缓存）；② rm 后 `[ -e ]` 回读全不存在 → 误报 wiped；③ 进程 SharedPreferencesImpl 内存缓存随后续任意 pref 写入把旧内容整份写回磁盘，文件"复活"；④ grace_launcher_settings.xml 复活后 `GraceSettingsRepository.loadInitial()` 发现 Room 无行 → 从复活 SP seed 回 Room → 数据全回归。
- [x] **修复（wipe-v3，HostDataWipe.kt）**：时序改为【杀→删 ×3 轮 + 末次强杀收尾】——每轮 force-stop 先杀自动重启进程（丢弃 SP 缓存）再 rm，轮间兜住复活文件，末次 force-stop 保证下次冷启动读干净磁盘；逐文件回读验证不变。
- [x] **目标清单补缺**：+grace_launcher.db-journal（非 WAL 日志）、+files/item_icons（ItemIconStore 自定义图标位图目录，rm -rf）；files/clock_fonts 按协议保留。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.61-wipe-v3-94.apk。
- 备注：工作树中 v2.9.56~v2.9.60 批次（gadget/字体跨进程/wipe v1~v2）尚未入库，本提交仅含 HostDataWipe.kt + 版本号 + 本记录。

### wipe-v4（v2.9.62-wipe-v4 / versionCode 95，2026-10-16）—— 清除改走宿主数据层自清（User 思路定案）

- [x] **装机取证（wipe-v3 无效后）**：设备 root 为 KernelSU（me.weishu.kernelsu，非 Magisk）；adb shell 无 su；logcat 无 HostDataWipe 打点 —— su 链路本身即最脆弱环节，root 删文件路线放弃。
- [x] **方案（User 提出"写入方式清除"定案）**：主路改走设置桥 —— 宿主进程内自清数据层，同进程操作不存在 v3 的"force-stop 竞态 + SP 内存缓存复活"问题，且零 root 依赖：
  - 协议：GraceSettingsBridge 增 Cmd.WIPE_DATA + ACTION_WIPE_DONE/EXTRA_OK 回执；
  - 宿主：GraceSettingsHostEngine.wipeHostData() —— Room clearAllTables（活连接，IO 协程）+ 三 SP（grace_launcher_settings/grace_launcher_preferences/launcher_appearance）edit().clear().commit（内存缓存+磁盘同进程一致清）+ files/item_icons deleteRecursively；回执后 800ms Process.killProcess 自杀重启（HOME 自动拉起，ModePrefs/clock_fonts 保留）；
  - 模块：按钮发桥命令，3s 无回执（第四桌面未挂载/桥断）回落 root 通道（HostDataWipe 保留为兜底）。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.62-wipe-v4-95.apk，已 adb install 到 d6516f3a 待实机验证。

### settings-wallpaper（v2.9.63-settings-wallpaper / versionCode 96，2026-10-16）—— 设置页壁纸窗口补齐

- [x] **需求（装机反馈 + 截图）**：模块设置页（收藏与应用列表等）背景是纯深灰 surface 底，"背景应该是壁纸"。
- [x] **根因**：上游 Theme.GraceLauncher.Settings 本就是壁纸窗口（windowShowWallpaper=true + 透明 windowBackground），Compose 层 LauncherSettingsScreen 已配套移植（NavHost 对 ListAppearance/Alphabet/ClockStyle/IconDesigner 画 Transparent，普通设置页画 surfaceContainer 不透明）；模块侧从未配窗口主题，默认不透明窗口把壁纸挡住。
- [x] **修复**：新建 app/src/main/res/values/themes.xml —— Theme.HomeMode4Miui.Settings 逐项对齐上游（壁纸窗口 + 透明背景 + 透明系统栏 + NoActionBar）；AndroidManifest SettingsActivity 挂主题。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.63-settings-wallpaper-96.apk，已 adb install 到 d6516f3a 待实机验证。

### appfont-static（v2.9.64-appfont-static / versionCode 97，2026-10-16）—— 桌面字体解析双根因修复

- [x] **需求（装机反馈）**：设置字体后时钟生效，但字母条/应用名等桌面文本不生效。
- [x] **排障（解析打点 instrumented build + adb 复现）**：新增 SelectedTypefaceApi29 逐 id/来源/失败原因去重打点，装机实锤两个根因：
  ① **resolver context 错位**：AndroidFont 加载 context 来自 FontFamilyResolver（宿主
  Composition 根构造，assets 无模块字体）→ preset 字体 fonts/sacramento.ttf
  FileNotFoundException。此前 hotfix 给 rememberAppFontFamily 传的 fontContext 只当了
  remember key，从未进加载链。修复：SelectedFont/LauncherFont 携带创建点捕获的
  context（桌面 = moduleContext），loader 优先用之；Josefin 内置链同步改自带 context。
  ② **静态字体 variation 崩点**：PlatformFont.Builder.setFontVariationSettings 对静态
  （非可变）字体抛 IllegalArgumentException 被 runCatching 吞掉 → 全源失败静默回落；
  时钟路径走 framework Typeface.Builder（静态字体忽略 variation）所以时钟生效、
  应用名/字母条不生效——同一文件两条链行为分裂。修复：variation 被拒时降级重建
  （保留 setWeight，framework 就近匹配/合成粗细）。
- [x] **验证（adb 自主复现）**：修复前 `id=sacramento resolve failed / ALL SOURCES FAILED`；
  修复后强停桌面重启 `SelectedTypeface resolved ids=[sacramento, system] families=1 w=500`。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.64-appfont-static-97.apk，已 adb install 到 d6516f3a。

### appfont-provider（v2.9.67-appfont-provider / versionCode 100，2026-10-16）—— 导入字体零 root 按需拉取（User 定案）

- [x] **需求（装机反馈）**：preset 字体修复后，导入的自定义字体桌面仍不生效。
- [x] **排障（打点 + adb 实证）**：root 镜像通道在 KernelSU 装机上"mirror install ok（chown/chmod/chcon 全过）但宿主 list()=null / isFile=false"——DAC/SELinux MCS/命名空间三重怪癖，root 路线不可依赖；分片广播方案实测被 MIUI 吞掉（76 发 4 收）。
- [x] **方案（User 提出弃用 root，定案）**：模块暴露只读 FontBridgeProvider（exported，openFile 强制 mode=r + id 严格校验防穿越），宿主解析缺文件时 FontBridgePull 按需拉取 —— 普通 binder 不受广播抑制，宿主用自己的 UID/SELinux 标签落自己的 filesDir。关键点：
  - 落盘基准目录必须用 createPackageContext 反取【宿主】filesDir（context=模块 Context 时其 filesDir 宿主不可写，mkdirs failed 实锤）；
  - 主目录 filesDir/clock_fonts 被 root 时代污染（root:root 700）→ 探测失败自动换 clock_fonts_h，ClockFontStore 双目录（模块侧 + 宿主侧）读取兜底；
  - openInputStream 会把 SecurityException/FNFE 吞成 null —— 改 acquireContentProviderClient 显式取异常；
  - FontCrossProcess root 镜像代码整体移除，FONT_DELETE 单广播保留（删除同步）。
- [x] **验证（adb 自主复现）**：FontBridgePull pulled zpixx.ttf (4681088B) → SelectedTypeface resolved ids=[...zpixx.ttf] families=1；新导入字体同样即时解析。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.67-appfont-provider-100.apk，已 adb install 到 d6516f3a。

### clock-font-override（v2.9.68-clock-font-override / versionCode 101，2026-10-16）—— preset 布局放开自定义字体

- [x] **需求（装机反馈）**："我们的时钟不能自定义字体" —— 时钟样式面板选 Sacramento/Bokor 等 preset 布局时，字体选择入口被隐藏（allowsCustomFont=presetFont==null），且 normalized() 直接剥离 fontId、渲染端 presetFont 恒优先 —— 自定义字体对 preset 布局完全不可用。
- [x] **修复（模块定制，偏离上游 v1.1.4 固定字体设计）**：allowsCustomFont 恒 true（所有布局可选字体）；ClockFace 渲染序改为 customFamily ?: presetFont ?: LauncherFontFamily —— fontId 为空时 preset 布局回落各自 preset 字体（上游默认观感不变），选中导入/preset 外字体即在任意布局生效。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.68-clock-font-override-101.apk，已 adb install 到 d6516f3a。

### clock-font-upstream（v2.9.69-clock-font-upstream / versionCode 102，2026-10-17）—— 时钟字体还原上游语义（User 定案）

- [x] **决策（User）**：v2.9.68 的"preset 布局放开自定义字体"偏离上游设计，还原为与上游 GraceLauncher 一致 —— `allowsCustomFont = presetFont == null`，preset 布局（Sacramento/Bokor/Plaster/Monoton/Lucky）锁死签名字体，自定义字体仅在 SingleLine/TwoLines/Week 三种素坯布局可用；ClockFace 渲染序还原 presetFont 恒优先。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.69-clock-font-upstream-102.apk，已 adb install 到 d6516f3a。
- 备注：字体三分职责不变（时钟字体 clockStyle.fontId / 全局字体库首位 / 列表字体）；导入字体跨进程链（FontBridgePull）不受本次还原影响。

### v1.1.9 同步战役（v2.9.88~v2.9.92 / versionCode 104~108，2026-10-17）—— 上游 GraceLauncher v1.1.9 五批次移植

- [x] **S1（v2.9.88 / vc104）**：vendor 源码 bump v1.1.8→v1.1.9。6 新文件（GraceButtonLayout/IconDisplay/IconDominantColor/ColorAdjustment/IconAspectRatio/ThemePalette + GraceButtonPositionSettings）；DB v21→v22（launcher_settings.paletteStyle + folders.autoHideApps，Migration21To22 + schema 22.json）；LauncherApp 四新字段（iconDisplay/originalIconColor/isSystemApp/isAdaptiveIcon）；AppRepository 模块两段式装载适配——主色采样挂解码阶段（包图标命中路径不额外解码，null 走主题回退）；40 条上游 strings + ic_grace_solid；ShortcutRepository/FontSettings delta 挂不上（模块形态差异）零改动登记。三方合并教训：CRLF 噪音曾导致 43/48 误判深度改造，strip-trailing-cr 归一化后真实本地差异极小。
- [x] **S2（v2.9.89 / vc105）**：GraceButton 可移动 + 分区可见性——GraceLauncherRoute 渲染点 layout.bounds 绝对定位（absoluteOffset）替代 BottomEnd 固定对齐；isVisible(drawerOpen) 分区可见性（Favorites/AppList/Both）。
- [x] **S3（v2.9.90 / vc106）**：PaletteStyle 调色板风格——GraceHomeRoot/SettingsActivity 主题透传；GraceHomeSettings 字段 + decode 工厂；GraceSettingsRepository 双向映射；GraceSettingsBridge encodeRow/decode/delta 三处加列（typed 列编解码防丢字段）。
- [x] **S4（v2.9.91 / vc107）**：图标圆点/隐藏 + 主色重着色——GraceHomeRoot appearance 链传 iconDisplay；GraceHomeSettings.iconDisplay 派生属性；抽屉/收藏行走 vendored LauncherAppRow/FolderRow effectiveDisplay 自动生效；ItemIconStore fromApp 主色 + HSB 调整随 S1 落地。
- [x] **S5（v2.9.92 / vc108）**：文件夹 autoHideApps + 单应用隐藏——GraceHomeViewModel effectiveHiddenAppKeys 并集过滤；Room 行映射补字段；SettingsViewModel hideApp/setFolderAutoHide（SAVE_FOLDER 通道）+ encode/decodeFolder 补序列化。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.92-v119-s5-108.apk。上游应用图标换新（ic_launcher 系）不跟进（模块独立图标）。

### v119-s6（v2.9.93 / versionCode 109，2026-10-17）—— v1.1.9 收尾对齐（搜索/工作资料隐藏过滤 + 收藏直达）

- [x] **搜索过滤**（上游 LauncherScreen 语义对齐，顺带修审计 #6）：搜索默认排除 effective 隐藏集合（手动 ∪ autoHideApps 文件夹派生），SearchSettings.hiddenApps「搜索包含隐藏应用」开关自此生效。
- [x] **工作资料行过滤**：抽屉工作资料 Apps 同样排除 effective 隐藏（AppListModel remember 键补 hiddenAppKeys）。
- [x] **收藏直达**：LauncherOverlay.Favorites 改 data class（initialAppKey）；弹层新增「编辑收藏」附加行（带应用打开，FavoritesScreen 自动选入收藏）；widget 面板编辑收藏行随 data class 构造。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.93-v119-s6-109.apk。

### v119-s6b / v119-zh / autohide-fix（v2.9.94~v2.9.96 / versionCode 110~112，2026-10-17）—— 收尾三连

- [x] **s6b（v2.9.94 / vc110，ec44ed5）**：AppList 手势在抽屉已开时 no-op（上游 LauncherScreen.kt:877）；核实 HomeWidgetSheet 1.1.8→1.1.9 实为零改动（此前 40 行系 CRLF 噪音），无欠账。
- [x] **v119-zh（v2.9.95 / vc111，1466a92）**：values-zh-rCN 补齐 S1 追加的 40 条上游字符串官方中文（取自上游 zh-CN，非自译，零 key 冲突）。
- [x] **autohide-fix（v2.9.96 / vc112，594ebdb）**：装机回归首 bug —— 文件夹「自动隐藏内部的应用」开关点不动。根因：GraceSettingsBridge 快照 fols 线格式 encode/decode 双漏 autoHideApps，设置页 folders 该字段恒 false 且回推快照无字段，开关视觉弹回。修复：bridge encode/decode 成对补字段，SAVE_FOLDER → pushSnapshot 闭环即时刷新。
- [x] 惯例沉淀：新增设置字段必须全通道成对 —— DB 列 / LauncherSettingsRepository、GraceSettingsBridge（encodeRow/decode/delta + **快照 wire 格式 fols**）、GraceHomeSettings/快照模型；folder 类字段另查 fols 线格式。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.96-autohide-fix-112.apk。

### wallpaper-follow（v2.9.97 / versionCode 113，2026-10-21）—— 壁纸跟随三连修（主题色/模糊层/Grace 按钮+themed 图标）

- [x] **问题（装机反馈）**：换壁纸后主题色不跟随、开启模糊时模糊壁纸不刷新、Grace 按钮颜色不跟随主题色。同源根因：桌面各处只在进程启动时读一次壁纸，无任何"壁纸变化失效源"。
- [x] **根因拆解**：① Theme.kt dynamicColor 分支组合期读一次静态 `system_accent1_500`（MIUI/HyperOS 无完整 Material You 取色，该资源恒定）且无重组触发；② rememberWallpaperBlurBitmap 的 LaunchedEffect 只 key hostContext，壁纸快照永不过期；③ Grace 按钮/themed 图标是 VM 层光栅化位图（ItemIconStore），配色种子走 launcherThemeSeed 且 icons Room 流只在 item_icons 行变化时重渲染 —— 壁纸/主题变化后位图颜色冻结。
- [x] **修复**：ThemePalette.kt 落共享底座 —— `wallpaperPrimaryArgb`（反射取 WallpaperColors 主色）+ `launcherWallpaperSeed(context,...)`（主色优先，accent 兜底，与上游 launcherThemeSeed 同降级链）；LauncherAppearance.kt 新增 `rememberWallpaperVersion`（OnColorsChangedListener + ACTION_WALLPAPER_CHANGED 广播双通道，广播补 AOSP WallpaperColors 同色去重盲区）与 `rememberWallpaperSeedColor`（组合层 reactive 种子）；Theme.kt dynamicColor 分支接 reactive 种子；WallpaperEffects.kt 模糊位图 key 壁纸版本重载（重载期保留旧图不闪窗）；ItemIconStore themeColors/dynamicColors 切 launcherWallpaperSeed；GraceHomeViewModel 新增 wallpaperRevision 失效源（init 注册/shutdown 解绑）+ icons 流 combine(icons, 设置流, 壁纸revision) + 主题戳（壁纸 rev+主题设置+夜间模式）强制重装饰。
- [x] **关键坑（编译期实锤）**：compileSdk 37 下 `WallpaperColors.getPrimaryColor()` 返回类型从 int 变为 `android.graphics.Color`（同名不同方法描述符）——按新签名直编，装机在旧 framework 上 NoSuchMethodError；必须 Java 反射按名调用 + toArgb 兼容。
- [x] 构建：assembleRelease 通过；产物 .build/out/HomeMode4Miui-v2.9.97-wallpaper-follow-113.apk。行为说明：默认 G glyph 按钮走 colorScheme.primaryContainer 本就跟随；自定义设计按钮/themed 图标随主题戳重渲染即时跟随（IO 异步，允许极短旧色→新色过渡）。

### search-shortcut-fix（v2.9.122 / versionCode 138，2026-10-21）—— 装机四连 bug（搜索背景/隐藏应用页缺项/pin 快捷方式/编辑收藏）

- [x] **① 抽屉启动搜索背景错乱**：搜索浮层是所有表面的上层，`AppDrawerScreen` 只挡了 `drawerOpen`，搜索打开时整张应用列表（含字母条）在搜索页底下透出 —— 与首页启动搜索（`HomeScreen` 带 `!searchOpen`）观感完全不同。修复：抽屉 `retainedPage(visible = drawerOpen && !searchOpen && !pickerOpen)`，与首页页可见性条件同构（上游 LauncherScreen.kt:998 容器级 `!fullScreen` 语义）。
- [x] **② 隐藏应用页找不到应用（R-安装组件）**：设置页在模块 App 进程自行枚举应用，MIUI 对第三方调用方的 LauncherApps/PM 可见性有裁剪，个别应用抽屉里有、设置页没有。修复：新增宿主应用清单跨桥（`GraceSettingsBridge.Snapshot.appCatalog` = AppWire[component,label,serial]），宿主引擎 `appCatalog()` 取 `_uiState.apps`，设置页 `refreshApps` 按 key 补差集（图标留空走字母占位）。
- [x] **③ 应用列表不显示桌面上添加的快捷方式**：`GracePinItemHook` 落库时写了 `showInAppList = true`（注释即"抽屉主列表显示"），但 VM 从不读该列、`appListApps` 也不并入 shortcutApps（上游 LauncherViewModel 是把 shown 快捷方式 merge 进 `apps` 的）。修复：`GraceHomeUiState.shownShortcutKeys`（savedShortcutsJob 按行填充）+ `shownShortcutApps`/`appsWithShortcuts`（用所属应用图标顶替、按 LauncherAppOrder 归位），抽屉与搜索统一走 `appsWithShortcuts`；`launch()` 补 shortcut 分支（走 `LauncherApps.startShortcut`，不能按主 Activity 起）。
- [x] **④ 右滑面板「编辑收藏」点了没反应**：`ShortcutPopup` 的附加行统一 `entry.onClick(); onDismiss()` —— onClick 刚设 `overlay = Favorites`，紧接着的 onDismiss（路由侧 `overlay = null`）把它清掉。修复：`PopupEntry.dismissFirst`，编辑收藏行先关弹层再切 overlay。
- [x] 构建：assembleRelease 通过；产物 `.build/out/HomeMode4Miui-v2.9.122-search-shortcut-fix-138.apk`。
- [x] **惯例沉淀**：① 全屏浮层（搜索/picker/overlay）打开时，抽屉与首页的可见性条件必须同构，新增表面改动两处一起改；② 任何"动作会打开另一个 overlay"的弹层行，必须 dismissFirst，不能沿用"先动作后 dismiss"；③ 设置页应用清单与抽屉同源的权威来源是【宿主】，模块进程枚举只能作为图标/装饰的补充。

### blur-restart（v2.9.123 / versionCode 139，2026-10-21）—— pin 快捷方式真修 + 切模式状态栏露壁纸（blur-gap）

- [x] **② pin 快捷方式（v2.9.122 修法不彻底的真因，adb 实锤）**：`saved_shortcuts` 表恒 0 行 —— MIUI 的 AddItemActivity accept 链路不走 `PinItemRequestCompat`（旁听 hook 空转）；且框架层 `LauncherApps$PinItemRequest` 的 `REQUEST_TYPE_SHORTCUT=1`（compat 用 0），v2.9.122 的框架兜底 hook 用 0 比对 → 永久 skip。三重修复：① 框架 accept hook 改 `FW_TYPE_SHORTCUT=1`；② 新增 `GraceHomeViewModel.syncPinnedShortcuts()`（LauncherApps `FLAG_MATCH_PINNED` 公开查询面，attach/refreshApps/回桌面触发，upsert 幂等自愈，与 MIUI 内部实现无关）；③ 落库行 `activity` 常为空 → 不再整行丢弃（componentName 用 packageName 占位），launch 走 `startShortcut(pkg,id)` 不依赖 activity。真机：pin 后 rows=1 built=1 shown=1。
- [x] **① blur-gap（切模式后抽屉模糊壁纸不盖状态栏条带）**：adb 复现实锤 —— 切换后同会话内 Compose 根只被量到 2296（窗口/decor/DragLayer 全 2400），整块内容下移 104 = 状态栏高；`decorFitsSystemWindows(false)+requestLayout`、View 级 padding 硬闸门（GraceHomeView/ComposeView setPadding 全拦截）均纠正不了 → MIUI 内部某层 insets/translation 残留，无法从外部安全纠正。按用户实测结论落地：**切【进】第四模式（状态沿 false→true）延迟 600ms 重启宿主桌面**（`HomeController.scheduleRestartOnEntering`，3s 冷却；进程冷启动首帧 null→true 不触发，无循环；killProcess 后系统自动拉起 HOME）。真机验证：切关→切开 = "ON edge → restart now" → 新进程 blurLayer rect 0..2400，状态栏条带被模糊+遮罩覆盖（像素级确认）。附带加固保留：ensureFullBleedWindow（decorFits 强制 false + 还原）、GraceHomeView/ComposeView padding 硬闸门、clearSystemBarPadding。
- [x] 诊断日志收敛：`blurLayer ANOMALY`（仅 rect 顶边>0 或尺寸≠窗口时打印）+ `geometry:`（attach 后 600ms 一次），保留作长期看门狗。
- [x] 构建：assembleRelease 通过；产物 `.build/out/HomeMode4Miui-v2.9.123-blur-restart-139.apk`（已装机冒烟：桌面冷启正常、零 FATAL、快捷方式 rows=1）。

### minusone-keepalive（v2.9.144 / versionCode 160，2026-11-12）—— 负一屏 PA 持久绑定保活（RE 落地）

- [x] **方案来源**：`docs/RE_MINUS_ONE_KEEPALIVE.md`（OS3 RELEASE-6.01.03 反编译定案）。原生桌面 PA 常驻的机制是"桌面进程存活期间绑定永不解绑"，第四桌面模式此前"负一屏不存活"的真因不是缺保活，而是 `MinusOneGate` 把 connect 拦掉导致绑定从未建立。
- [x] **保活主改动（新增 `hook/OverlayKeepAlive.java`）**：拦 `PersistentLauncherOverlayConnection.setStopped(Z)` 与 `LauncherClientService.setStopped(Z)`，包名 `com.miui.personalassistant` 时吞掉 `setStopped(true)` → `mStopped` 恒 false → `cleanUp()` 的 `mStopped && overlay==null` 永不成立 → 不再 unbind。PA 被 LMK 杀掉后系统按存活 binding 重启 → `onServiceConnected` → `setOverlay` → 内容链与 `AppWidgetHost` 自动重挂。`LauncherClient.onStop` 的 `mActivityState` 通知照常下发，PA 后台不空转渲染。
- [x] **与文档 §6.1 的偏差（必读）**：§6.1 的 `onStop` 桩读 `strategy.mPackageName`，但反编译确认 `ServiceConnectionStrategy` **零字段**（mPackageName 在 `LauncherOverlayConnection` / `LauncherClientService` 上）→ 原样落地必然 field miss、永远放行 = 空补丁。改拦 `setStopped`：两条落点（flags=33 persistent / flags=65 clientService）的唯一公共环节，且粒度只封"降级为 stopped"。
- [x] **文档 §4 缺口的补丁**：Waive 策略下 `onBindingDied()` 不复位 `mConnected` → 绑定已死但 `connect()` 被 "already connected" 短路。新增 after 钩子复位 `mConnected` 后走 2s/6s/15s 有界补绑，overlay 回来即停。`onServiceDisconnected`（binding 仍活）只观测不重复 bind（避免引用计数叠加）。
- [x] **`MinusOneGate` v6→v7**：放行上限 60s→8s、轮询 1s→500ms、放行后 `sOpen` 永久为 true（四条 connect 通道含 reconnect/事件通道全部交还原生）、toggle 改为"仅当 misettings/notes provider 不可见时才做"。`MinusOneEntry.open()` 在 gate 预热期改 500ms×16 有界重试，不再直接丢弃用户手势。
- [x] 旧补丁按文档 §6.3 全部保留为兜底（`scheduleOpenRetry` / `startClientAwaitHeal` / `sReattachDone`），不删。
- [x] 构建：assembleRelease 通过（dex 内已确认 OverlayKeepAlive 与两个 hook 目标类名齐全）；产物 `.build/out/HomeMode4Miui-v2.9.144-minusone-keepalive-160.apk`。
- [ ] **待装机验证**：`am kill com.miui.personalassistant` 后应出现 `PA binder lost(...)->binding kept` → `PA binder restored(overlay=true)`，再滑负一屏应即时显示且微件正常；同时观察 `dumpsys meminfo com.miui.personalassistant` 常驻内存与耗电排行（PA 常驻是原生桌面本就在付的成本）。

### keepalive-noscript（v2.9.145 / versionCode 161，2026-11-12）—— service.d 开机脚本彻底废弃（整机重启事故收尾）

- [x] **事故（用户设备实锤，2026-11-12 22:00 起）**：装机 v2.9.144 后整机进入反复重启循环（约 90s 一轮，无 tombstone / 无 dropbox —— 死在日志落地之前）。真凶不是负一屏 hook，而是 `RootKeepAlive` 早期版本安装的 **`/data/adb/service.d/hm4m_keepalive.sh`**：`while true` 轮询 `pidof` 在 KSU/Magisk service 阶段被串行等待，永久阻塞启动流程。
- [x] **为什么卸载 APK 也不恢复**：该脚本位于 `/data/adb`，不属于模块刷入包，`pm uninstall` 不会带走它 —— 这正是"卸载了模块还在重启"的原因，也是排查被误导的核心（禁用 LSPosed 模块、删 APK 都无效，直到脚本被摘掉）。
- [x] **已实施修复（efdaa9d / 4e5d6e9）**：保活改为纯用户态三条线 —— ① 主链路 `pokeFromHost`（宿主前台 startService，零 root 依赖）；② Doze 白名单 + PowerKeeper 策略 + 自启动 provider（只降低被杀概率，不负责拉起）；③ `START_STICKY` 白赚一层。开机脚本方案整体删除。
- [x] **本次收紧（v2.9.145）**：① `removeLegacyBootScript()` 提到 su 探测之后**第一步**执行（原来排最后，前面任一 su 失败就跳过清理）并覆盖 `post-fs-data.d`，命中残留时打印 `legacy boot script REMOVED -> <path>` 作为排障锚点；② `GraceKeepAliveService` 头注释删掉"Magisk service.d 开机守护"，改为显式**禁止区**警告，防止后来者照旧注释重装回去。
- [x] **纪律（写入 MEMORY.md）**：模块**永不**写 `/data/adb/service.d`、`post-fs-data.d`、`init.d`、`/system/etc/init.d` 等任何开机自启脚本。若某功能非开机常驻不可，只能在应用层用前台服务/JobScheduler 实现；`/data/adb` 只允许读（日志导出）与删（清理历史残留）。
- [x] 构建：assembleRelease 通过；产物 `.build/out/HomeMode4Miui-v2.9.145-keepalive-noscript-161.apk`。
- [ ] **装机前**：确认 `/data/adb/service.d/hm4m_keepalive.sh` 已不存在（TWRP 终端：`ls -l /data/adb/service.d/`）；若仍在，先 `rm -f` 再装。

### widget-zombie + scope-pa（v2.9.146 → v2.9.149 / vc162~165，2026-11-13）—— 负一屏"微件无法添加"三段根因

- [x] **症状与证据**：负一屏微件显示"无法添加微件"。`dumpsys appwidget` 里 PA 的 widget 记录**只有 host 行、没有 `provider=` 行**（id 568~571 / 573~574 / 576~579 均如此），provider 注册表本身完好（notes 6 个 + misettings 2 个都在）。判定：id 存在但 provider 绑定为空 = 渲染不出任何内容的僵尸 id。
- [x] **根因①（v2.9.146）**：`PaWidgetGuard` 无条件拦截 `deleteAppWidgetId`。PA 早于 provider 注册恢复 widget → bind 失败 → provider 为空 → PA 想删掉重来 → 被拦下 → 僵尸 id 永久占位。改为**条件拦截**：`getAppWidgetInfo(id) == null`（provider 已空）放行清理，provider 仍在才拦截保值（保住 notes 按 appWidgetId 键控的内容映射）。
- [x] **根因②（v2.9.148，结论已修正）**：`assets/scope.list` 只有 `com.miui.home`。它不是"UI 只读的静态作用域"（LSPosed 作用域界面仍可勾选，实测可证），而是**模块安装/覆盖更新时 LSPosed 读取的作用域来源** —— 每次 `adb install -r` 都按它重置一遍 scope，把用户手动勾选的 `com.miui.personalassistant` 冲掉 → **PA 进程零注入**（19:08 实测：桌面有注入、PA 无），`PaWidgetGuard` 全程未运行。修复：`assets/scope.list` 写全 `com.miui.home` + `com.miui.personalassistant`，装后 PA 注入恢复（11 条注入日志）。
  附：同时加的 `META-INF/xposed/scope.list` 在该版 LSPosed 上**未生效**（若生效 scope 勾选框会变只读，截图实测仍可勾），属无效文件，已删除；保留单一来源 `assets/scope.list` 避免双份声明漂移。
- [x] **根因③（v2.9.147 → v2.9.149）**：`PaWidgetGuard` 的"就绪自杀重载"看门狗阈值写的是 `providers >= 50`，本机注册表满值只有 **25**（原样永不触发）；改成定向就绪后又暴露更严重的问题 —— PA 进程刚起时 `getInstalledProvidersForProfile` **读数恒为 0**（进程 init 中），1s 后才 24，于是每次启动都判"缩水→就绪→自杀"，实测连杀 4 轮，把 launcher 侧绑定拖成 `CR IMP DEAD`，PA 不再被拉起、负一屏整页空掉。**v5 彻底移除自杀重载**，只记录启动快照。
- [x] **同时修**：`MinusOneGate` 放行上限 8s → **25s**（实测 unlock 后注册表 25s 时只有 2 个 provider，8s 上限必然提前放行）；`MinusOneEntry` gate 预热期重试 8s → 10s。
- [x] **验证（真机，2026-11-13 19:23 重启后）**：
  - `PaWidgetGuard: killing PA for clean reload` 计数 **0**（不再自杀循环），PA 进程存活；
  - `dumpsys appwidget` → `id=581 provider=com.miui.notes/NoteWidgetProvider_4x2 ... views=RemoteViews`、`id=582 provider=xiaomi.misettings/SimpleUsageStatsWidget ... views=RemoteViews` —— **provider 与 RemoteViews 同时在场**，僵尸 id 全部消失；
  - 产物 `.build/out/HomeMode4Miui-v2.9.149-guard-v5-165.apk`。
- [x] **教训**：① 任何"拦截系统清理"的补丁都必须检查拦截对象是否还有效，否则会把可自愈状态变成永久坏死；② 静态作用域会覆盖 LSPosed 数据库里的手动勾选，且每次覆盖安装都会重放，PA 侧 hook 必须写进 `scope.list`；③ 在进程 init 早期读 `AppWidgetManager` 不可靠（恒 0），不能拿它做"自杀重载"的判据。

### listening-gate + gate-diag（v2.9.150 / v2.9.151 / vc166~167，2026-11-14）—— 开机即开负一屏的时序闸 + 已知残留

- [x] **v2.9.150：PA 侧 `startListening` 时序闸**。用户实测"开机立刻滑负一屏必坏，等一会儿再开则正常"——微件恢复发生在 `AppWidgetHost.startListening()` 那一刻（按 PA 库里的 appWidgetId 逐个 bind）。现在在 PA 进程拦 `startListening`：misettings/notes 两个 provider 未可见就挂起（500ms 重试，上限 20s），就绪或超时立刻放行，之后闩死不再扫描。实测日志：`startListening deferred #1..#8` → `providers ready -> startListening allowed`。
- [x] **v2.9.151：gate 上限 45s + 采样诊断**。诊断结论：**launcher 侧读不到真实注册表**（`15.8s 时 providers=2`，系统真实 25；`targeted=false`），所以 `MinusOneGate` 的"等就绪"信号在本机等价于**固定延时**。真正有效的时序控制只有 PA 侧的 `startListening` 闸（它读到 0→24 的准确曲线）。用户决策：保留 45s 上限（微件最稳），接受"开机后 45s 内负一屏要等"。
- [x] **验证（真机，15:02 / 15:06 两次重启）**：`dumpsys appwidget` 中 PA 的两个 widget 均带 `provider=` 与 `views=RemoteViews`；`AppWidgetServiceImpl: bindAppWidgetId() callingPackage: com.miui.personalassistant appWidgetId: 602 → NoteWidgetProvider_4x2 / 603 → SimpleUsageStatsWidget`，绑定链路正常；`mResetHost:false`、`zombie=false`、host 2048 `widgets.size=2`。
- [ ] **已知残留（用户已接受，优先不改）**：PA 每次开机都会重新分配 appWidgetId（599 → 602 → 603 …），而笔记部件的内容绑定是 notes 侧按 appWidgetId 键控的 → **每次重启需重新绑定一次笔记内容**，其他部件显示正常。根治方向（未实施，风险中）：在 PA 进程 hook `AppWidgetHost.allocateAppWidgetId/deleteAppWidgetId` 保住原 id，或维护 id 映射表；需 2~3 轮真机验证。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.151-gate-diag-167.apk`（当前装机版本）。

### icons-singlepass（v2.9.152 / versionCode 168，2026-11-14）—— 重启后抽屉图标整批丢失：装载回归单段

- [x] **症状（User 实锤）**：重启手机后收藏区图标全部正常，抽屉里所有应用都是字母占位；等多久都不恢复（除非再触发一次包变化/重启桌面）。
- [x] **根因（两处叠加，都在"两段式装载"）**：
  1. **解码超时粒度错**（`AppRepository.withIcons`）：原实现用**整批共用一个 2500ms 窗口**的非结构化等待。冷启动 MIUI `getBadgedIcon` 走 IconCustomizer 主题图标，首解 80–150ms/个，200+ 应用即使 4 并行也要 3–8s → 必然超时 → 超时分支只收"已完成"的、"未完成"的直接放弃 → 抽屉整批退回骨架（字母）。
  2. **两段各自抢同一把主题资源锁**：phase1（收藏子集 ≈ 20 个）先跑完拿到锁、正常出图并换入首屏；phase2（全量 200+）紧接着撞上仍被占用的 IconCustomizer 锁 + 自己的 2.5s 窗口 → 几乎全灭。这正是"收藏正常 / 抽屉全占位"的形态，与用户直觉（先收藏后抽屉）一致。
  3. 之后的"缺失自愈"也没救回来：`missing ≥ 5%` 的重试走 `parallelism = 1`（200 个串行、同样吃 2.5s 窗口）几乎不产出；退避重装只有 2 轮，且每轮重新进入同一竞态。
- [x] **修复 ①（回归单段装载，`GraceHomeViewModel.refreshApps`）**：删除 phase1 收藏预换入，改为 `enumerateApps → withIcons(全量) → decorateApps → 一次换入`。首帧晚 1–2 秒（时钟/文件夹/收藏一起出现，`homeReady` 单点置位），用户已确认接受这个代价换抽屉图标可靠。
- [x] **修复 ②（解码超时改按块，`AppRepository.withIcons`）**：`ICON_CHUNK_SIZE = 24`，每块独立 `CoroutineScope + limitedParallelism`、独立超时 `max(1500ms, 块大小 × 150ms / 并行度)`。单块卡死只丢该块最多 24 个图标，其余块照常出图；日志锚点 `icon decode abandoned=N/总数 (stuck decoders on MIUI IconCustomizer)`。
- [x] **修复 ③（重试路径）**：缺失重试从 `parallelism = 1` 改 `2`（第二轮已暖 IconCustomizer 缓存，配合按块超时能真正补回）；退避重装两轮（6s / 15s）保留为最终兜底。
- [x] **保留项**：`appLoadGeneration` + `decorateMutex` 不动 —— 虽然 phase1 覆盖窗口随两段式一起消失，iconsJob 重装饰与包变化刷新并发时仍是同一类竞态。
- [x] 构建：assembleRelease 通过；产物 `.build/out/HomeMode4Miui-v2.9.152-icons-singlepass-168.apk`。
- [ ] **待装机验证**：冷启动（重启手机）后 `logcat -s HomeMode4Miui` 应看到 `refreshApps: enumerated=N` → `icons done ... missing=0/N`；抽屉/搜索图标全部为真实图标；观察 `/data/adb` 无关项不要误动（保活纪律见 v2.9.145 小节）。

### shortcut-sync（v2.9.153 / versionCode 169，2026-11-14）—— 快捷方式三连修：重复行 / 图标错 / 删不掉 + 实时对账

- [x] **症状（User 实锤，pin 快捷方式 = 第三方应用创建的指向文件/联系人/功能的桌面图标）**：
  ① 抽屉里同一条快捷方式出现**两个**（原生桌面只有一个）；
  ② 图标是"创建快捷方式的应用"的图标，和原生桌面上的不一样；
  ③ 长按无法删除（只能打开应用信息页）。
- [x] **根因（logcat 实锤）**：`syncPinnedShortcuts: pinned=1` 而 `savedShortcuts: rows=2`
  —— `saved_shortcuts` 只做 upsert、**从不按系统 pinned 集合对账**，历史 pin 过的旧
  shortcutId 行永久残留（shortcutmaker 每次生成新 uuid → 行数只增不减）。
  图标侧 `shownShortcutApps` 在行内 icon 为 null 时回落所属应用图标；
  删除侧没有入口，且即便删行也会被下一轮 `syncPinnedShortcuts` 自愈写回。
- [x] **修复 ①（对账，DB v24）**：`saved_shortcuts` 加 `dismissed` 列（Migration23To24）。
  `syncPinnedShortcuts` 改为权威对账：
  - 系统 pinned 集合外的行 → 删除；但若该 key 仍被 `app_popups`（文件夹/弹层成员）引用，
    只 `showInAppList=0` 保值（`demoted`），不破坏成员解析；
  - 只对账当前用户行（`profile:` 前缀跳过，work profile 的 key 不混进来）；
  - `getShortcuts` 返回 null（无权限/服务未就绪）**等于查询失败，绝不当"没有 pin"去清库**。
  日志锚点：`syncPinnedShortcuts: pinned=N icons=M purged=X demoted=Y`。
- [x] **修复 ②（图标）**：`LauncherApps.getShortcutBadgedIconDrawable(info, density)` 解码真实
  快捷方式图标（含角标）→ `GraceHomeUiState.shortcutIcons`（内存，冷启动/resume 重解），
  `shownShortcutApps` 优先用它，取不到才回落应用图标。与原生桌面显示一致。
- [x] **修复 ③（删除 + 原生同步）**：抽屉长按**快捷方式项**（`app.shortcut != null`）= 移除
  （普通应用仍是应用信息）→ 确认框 → `dao.dismissShortcut`（UI 立即消失）+ 
  `LauncherApps.pinShortcuts(pkg, 该应用剩余 pinned id, user)` 取消系统 pin
  （公开 API 没有 unpin，pinShortcuts 传全量剩余集合即等价；查询失败不下发，避免误清）。
  真机验证：`removeShortcut: ... unpinOk=true remaining=0` → 原生桌面图标同步消失。
  dismissed 行在系统 unpin 后由对账彻底删行；重新 pin（hook 写 dismissed=false）可正常恢复。
- [x] **修复 ④（实时刷新，User 追加要求）**：`LauncherApps.registerCallback` 的
  `onShortcutsChanged`（600ms 合并）→ 立刻对账，覆盖"别处删了快捷方式、模块桌面还留着"；
  `resume`（已有）与**抽屉每次打开**补对账为兜底。
- [x] **顺手挖出的坑（资源 id 撞号）**：确认框"取消"用 `R.string.cancel` 显示成 **"400"** ——
  模块 `0x7f0b002b` 与宿主 `integer/config_itemShakeTime(=400)` 同 id，经宿主 Resources
  解析取到宿主值。改用本批新增字符串 `shortcut_remove_cancel`（新 id 不与宿主冲突）即正常。
  **新增 UI 文案一律新建独立 string，不要复用上游旧名**（旧 id 有撞宿主的历史概率）。
- [x] **验证（真机 OS2 RELEASE-5.39.40 / d6516f3a）**：`rows=1 dismissed=0 built=1 shown=1`（重复行清除）；
  `icons=1`；长按 → 确认框"移除快捷方式？/取消/移除"；确认后 `unpinOk=true` 且行立即消失、
  系统 pinned 归零。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.153-shortcut-sync-169.apk`。

### shortcut-icon-design（v2.9.154 / versionCode 170，2026-11-14）—— 快捷方式图标对齐桌面 + 接入图标设计器

- [x] **症状（User 实锤，接着 v2.9.153）**：图标虽然换成了快捷方式自己的，但仍与原生桌面不一致 ——
  用户原话"这个区别相当于是应用的图标和在桌面的图标的区别"，且要求"快捷方式的图标也要接入图标设计器"。
- [x] **根因**：快捷方式图标来自 `ShortcutInfo.icon`（应用自带位图），不经系统 `getBadgedIcon` 的
  MIUI 主题化，也**没走模块的图标装饰链**（`decorateApps` 只处理 `apps`，`savedShortcutApps` 被跳过）。
- [x] **修复 ①（MIUI 图标形状）**：解码后套 `miui.content.res.IconCustomizer.generateIconStyleDrawable(Drawable)`
  —— 原生桌面裁图标形状/内容占比的同一入口（RE 定案：类在宿主 dex，静态方法返回 `BitmapDrawable`）。
  真机日志锚点：`IconCustomizer: Content Ratio = 0.725` / `drawableToBitmap: sourceWidth=198`。
  反射失败（非 MIUI/签名漂移）原样返回，不影响可用性。
- [x] **修复 ②（接入模块装饰链）**：新增 `buildShortcutApps(rows)` = 行 → LauncherApp（带 shortcutIcons）
  → `decorateApps`（主题化图标 / 图标包 / 图标设计器产出），Room 流、sync 完成、`applyItemIconSnapshot`
  三处统一走它。`shownShortcutKeys` 由未装饰的原始项算，不受装饰影响。
- [x] **修复 ③（图标设计器可选中快捷方式）**：设置页模块进程读不到宿主 `saved_shortcuts`，
  故 `GraceSettingsBridge.Snapshot` 新增 `savedShortcuts: List<ShortcutWire>`（encode 键 `shc`），
  宿主 `pushSnapshot` 从 DAO 回带（过滤 dismissed + `profile:` 前缀）；`SettingsViewModel.applySnapshot`
  并入 `shortcutApps`（key 与宿主 `LauncherApp.key` 逐字一致）；`AppSelectionSettings` 加
  `includeShortcuts`，图标设计器选择页传 `allApps + shortcutApps` 并 distinctBy key。
  保存走既有 `SET_ITEM_ICON` → 宿主 `item_icons`，`decorateApps` 按 `shortcut:<pkg>/<id>` 查表即生效
  （`applyItemIconSnapshot` 已同步重装饰快捷方式，改完即时生效）。
- [x] **已知降级**：设计器列表里快捷方式行的缩略图为空（模块进程无快捷方式图标权限），靠名字识别。
- [x] **回归（User 真机验收四项全通过）**：① 图标与原生桌面一致；② 图标设计器可选中快捷方式且设计即时生效；
  ③ 长按移除 + 原生桌面同步消失；④ 别处删除 → 模块实时消失。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.154-shortcut-icon-design-170.apk`。

### shortcut-thumb + icon-cleanup（v2.9.155 / v2.9.156 / vc171~172，2026-11-14）—— 设计器缩略图回带 + 删除时清理设计数据

- [x] **v2.9.155（User 实锤：设计器列表里快捷方式那行缩略图是空的）**：
  模块进程**没有快捷方式图标权限**（`LauncherApps` 只认默认桌面/HOME 角色），拿不到图。
  做法：宿主解码 → 随快照回带。
  - 新增 `grace/ShortcutIconCache`：`decodePinned(launcher, density)` 统一解码 + `miuiStyle()`
    套 `IconCustomizer.generateIconStyleDrawable`，**桌面抽屉与设计器列表共用同一份位图**
    （避免两张不一致的图）；`thumbnailPng(bitmap, 160)` → Base64 PNG。
  - `GraceSettingsBridge.ShortcutWire` 加 `iconPng`；Snapshot 的 `shc` 数组编解码带 `ip` 字段。
  - 宿主 `pushSnapshot`：优先复用缓存（桌面 VM 已填），miss 时现场解码；**最多回带 8 个缩略图**
    （160px PNG ≈ 20~40KB/个，广播上限 1MB），超出留 null。
  - 模块 `SettingsViewModel.decodeShortcutThumbnail()` 解 Base64 → `ImageBitmap` 填
    `LauncherApp.icon`，设计器列表行与预览底图都有图。真机验收通过。
- [x] **v2.9.156（User 要求：删除快捷方式时图标设计器里的数据也要删）**：
  - `removeShortcut`：`dismissShortcut` 后追加 `resetIcon(app.key)` —— 清 `item_icons`
    里 `shortcut:<pkg>/<id>` 的设计行。
  - `syncPinnedShortcuts` 对账 `purged` 分支同样 `resetIcon`（覆盖"在原生桌面删掉"的路径）。
  - 为什么必须删：孤儿设计行会让**同一 shortcutId 重新 pin 时自动套用旧设计**（PWA 类快捷
    方式 id 固定），用户会以为"删了没删干净"；设置页也会残留不可达条目。
  - 真机验收：删除后设计器列表该条消失，重新创建为默认图标。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.155-shortcut-thumb-171.apk`、
  `.build/out/HomeMode4Miui-v2.9.156-shortcut-icon-cleanup-172.apk`。

### shortcut-favorites + icon-cache（v2.9.157 / v2.9.158 / vc173~174，2026-11-15）—— 快捷方式可收藏 + 收藏区图标错位根因

- [x] **v2.9.157（User：快捷方式不能加到收藏）**：三处独立拦截，缺一处都进不去。
  1. `GraceHomeUiState.favorites` 解析池只有 `apps` → 快捷方式 key 永远解析不到（即使写进
     `favoriteOrder` 也不显示）。改为 `apps + savedShortcutApps`（用全量 saved，弹层里的
     快捷方式也能收藏）。
  2. `GraceFavoritesShim.uiState` 没投影 `shortcutApps` → 编辑收藏页 `favoriteItems` 无此项。
     补 `shortcutApps = state.savedShortcutApps`。
  3. `ItemSelectionScreen` 内部硬过滤 `apps.filter { it.shortcut == null }` → 可选列表选不到。
     加 `includeShortcuts` 参数，仅收藏页传 true（弹层编辑器保持原行为，避免与其自身
     "添加快捷方式"入口语义冲突）；收藏页可选池 `allApps + shortcutApps` 并 `distinctBy(key)`
     （防 LazyColumn key 重复崩溃）。
  - 附带：`removeShortcut` 时把 key 从 `favoriteOrder` 摘掉（不留脏成员槽位）；
    `toggleFavorite` 加日志锚点（key / shortcut / size）。
- [x] **v2.9.158（User：收藏页图标与抽屉不一致）**：根因是**图标渲染缓存签名不含输入图标**。
  快捷方式数据先到、图标后到：① 行到达即装饰，此时 `icon == null` → 空图标渲染结果被
  `IconSnapshotCache` 缓存；② `LauncherApps` 图标到达后重建，装饰命中 ① 的缓存 → 永久返回
  空图标副本。抽屉走另一条数据路径，故只有收藏区异常。
  日志实锤：`toggleFavorite: ... shortcut=true` 紧跟 `AppIcon fallback: key=shortcut:... mono=false themed=true`，
  而同时 `syncPinnedShortcuts: icons=1`。
  - 修复①：`ItemIconStore.apply` 的 renderKey **只对 shortcut** 追加 `identityHashCode(icon/monochrome)`。
    普通应用保持原签名 —— 它们每次 refreshApps 都是新图标实例，加指纹会让全量装饰永久 miss。
  - 修复②：`decorateApps` 增加 `app.shortcut != null && app.icon == null → 原样返回`（图标未就绪
    不装饰，既不白渲染也不污染缓存）。
  - 真机验收：两处图标一致，无 AppIcon fallback 日志。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.157-shortcut-favorites-173.apk`、
  `.build/out/HomeMode4Miui-v2.9.158-shortcut-icon-cache-174.apk`。

### alphabet-rail-position（v2.9.159 / versionCode 175，2026-11-15）—— 字母表上下限设置不生效

- [x] **症状（User 实锤）**：设置 → 外观 → 字母表里调「顶部 / 底部」位置，设置页预览会动，桌面字母条**纹丝不动**。
- [x] **根因**：桌面渲染端根本没消费 `AlphabetAppearance.topPercent / bottomPercent`。
  `GraceLauncherRoute` 里 rail 几何是硬编码常量：
  `railHeight = ((letters.size + 1) * 18).dp.coerceAtMost(safeHeight * 0.65f)`、
  `railTop = statusBarHeight + safeHeight * 0.39f`；`layout(viewportHeightDp, entryCount)`
  只在设置页预览里被调用。
- [x] **修复（对齐上游 `LauncherScreen.kt:1012-1013` 逐字）**：
  ```kotlin
  val alphabetAppearance = state.settings.listAppearance.alphabet.normalized()
  val railLayout = alphabetAppearance.layout(maxHeight.value, model.letters.size + 1)
  val railHeight = railLayout.heightDp.dp
  val railTop = railLayout.topDp.dp
  ```
  `alphabet` 参数同步传 normalized 版本；`touchHeight` 仍为整屏（scrub 手势面不受位置设置影响）。
  `normalized()` 保证 `bottomPercent > topPercent`（上下限填反自动修正）。
- [x] **验证**：装机后字母条按设置值定位，scrub 跳转正常，用户确认"已经可以了"。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.159-alphabet-rail-position-175.apk`。

### close-anim-uniform（v2.9.160 / versionCode 176，2026-11-15）—— 返回桌面动画一半飞图标、一半缩小消失

- [x] **症状（User 实锤）**：从应用返回桌面，部分应用窗口缩小后**飞进桌面图标位置**，另一部分
  则是窗口缩到屏幕中部消失；上游（GraceLauncher 作为第三方桌面）在 MIUI 上恒为后者。
- [x] **根因（OS2/OS3 dex 逆向，两版本同构）**：MIUI 关闭动画的飞行目标由
  `com.miui.home.recents.CloseShortcutIconUtils.getCloseShortcutIcon(ComponentName, userId, Launcher|BaseLauncher)`
  解析 —— 内部 `launcher.getShowingItem(flatten, userId, 0)` + `launcher.getClosingShortcutIcon()`，
  即"在**宿主自己的** Workspace 里找当前正在显示的图标 View"。本模块桌面是 Compose 自绘盖在宿主
  Workspace 之上，宿主图标与模块图标位置不重合且多被遮住：
  - 宿主 `getShowingItem` 恰好命中 → 返回图标 View → 窗口飞向**宿主的**图标位置；
  - 命中不到 → null → 原生回落 `DeviceConfig.getClosingAppDefaultDisappearRect()`
    （缩到屏幕中部消失，即上游观感）。
  ⇒ "宿主是否能命中图标"成了随机分界线 → 两种返回动画混现。
- [x] **修复**：新增 `hook/AppCloseAnim.java`，`MainHook` gate 12 安装；`XposedBridge.hookAllMethods`
  钩 `CloseShortcutIconUtils#getCloseShortcutIcon`（两版本参数表不同，按名字匹配），自定义桌面模式下
  `setResult(null)`。两个调用方都原生兜底 null，无 NPE 风险：
  - `NavStubView.findClosingShortcutIcon` → `mAppToHomeTargetRect.set(getClosingAppDefaultDisappearRect(mTouchRange))`；
  - `WindowAnimParamsProvider.getClosingShortcutIcon` → `findClosingAnimTarget` 返回 null →
    `getIconRect` 取 `getClosingAppDefaultDisappearRect(...)`。
  原生 MIUI 桌面模式下完全放行（不动 MIUI 自己的动画）；方法缺失由 tryHook 记录失败并放行原逻辑。
- [x] **验证**：构建通过；待装机确认所有应用返回动画统一为"缩小消失"（真机日志锚点
  `AppCloseAnim: icon target suppressed -> default disappear rect`）。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.160-close-anim-uniform-176.apk`。

### static-scope-pa（v2.9.161 / versionCode 177，2026-11-15）—— 作用域改为静态双包声明

- [x] **诉求**：模块作用域改为静态声明，固定为系统桌面 `com.miui.home` + 智能助理
  `com.miui.personalassistant`；并显式承认**部分机型未预装智能助理**，缺该包不得影响桌面侧。
- [x] **问题（漂移）**：`assets/scope.list` 自 v2.9.148 起已写全两包（安装/覆盖更新时 LSPosed
  重放的作用域来源），但 `res/values/arrays.xml` 的 `xposed_scope` 仍只有 `com.miui.home`
  —— 两份声明不同源：管理器 UI 推荐作用域少一项，新装用户拿不到 PA 的默认勾选提示，
  两处迟早再次分叉（v2.9.148 的漂移即由此类不一致积累）。
- [x] **修复**：`xposed_scope` 补齐 `com.miui.personalassistant`，与 `assets/scope.list` 逐字一致；
  arrays.xml 内加注释固化"两份声明必须同源"的约束与各自职责（scope.list = 安装时重放、
  会覆盖手动勾选；xposedscope = UI 推荐作用域）。
- [x] **缺包容错（无需代码改动，已核实三层）**：
  ① LSPosed 读取 scope.list/推荐作用域时对未安装包**自动跳过**，不在管理器报错、不阻断加载；
  ② `MainHook.handleLoadPackage` 的 PA 分支只在 `packageName` 命中时装载，未安装 = 零注入，
     `PaWidgetGuard` 不运行，桌面侧 hook 全部照常；
  ③ 宿主侧 `MinusOneEntry` 本就有"overlay client 未就绪"降级链（client 重试 → 状态机兜底 →
  等 PA 上线自愈轮询），`MinusOneGate` 无 PA 时无 connect 可拦、25s 上限兜底放行，不阻塞桌面。
- [x] **文档**：README 安装步骤改为"静态作用域，无需手动勾选"，并注明未预装智能助理机型的表现；
  `PaWidgetGuard` 类注释同步作用域出处（删除已废弃的 `META-INF/xposed/scope.list` 引用）。
- [x] **验证**：`assembleRelease` 通过；产物反查确认
  `assets/scope.list` = `["com.miui.home","com.miui.personalassistant"]`、
  `array/xposed_scope size=2 = ["com.miui.home","com.miui.personalassistant"]`。
- [ ] **待装机**：覆盖安装后 LSPosed 作用域页应自动勾选两项（无 PA 机型只有桌面一项）；
  有 PA 机型确认 `loaded into com.miui.personalassistant (PaWidgetGuard)` 日志出现。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.161-static-scope-pa-177.apk`。

---

## 上游同步战役：GraceLauncher v1.2.0 → v1.2.4（2026-11-15 起，批次 P0~P9）

### P0 地基（v2.9.164-p0-upstream124 / versionCode 180，2026-11-15）

**delta 基线**（`git diff` 语义统计，LF 归一化后）：

| 项 | 值 |
| --- | --- |
| 上游版本 | v1.2.0（vc12，2026-10-18）→ **v1.2.4（vc16，2026-11-13）**，中间含 1.2.1/1.2.2/1.2.3 |
| 文件增量 | 新增 168（86 个 .kt + 82 个 res），删除 0，修改 113（其中 .kt 100 个，8697 行） |
| 模块映射 | 83 个改动文件模块已移植（36 个三方合并零冲突 / 47 个有冲突）；17 个改动文件模块未移植；86 个新 .kt 模块全部没有 |
| DB | 模块 v24 → 上游 v34（10 个迁移，均为 `launcher_settings` 加列） |
| 文案 | 新增 539 条英文 + 538 条中文（与宿主 string 同名仅 2 条，已按铁律改 `grace_` 前缀） |

**三方合并预演方法（后续批次复用）**：逐文件
`sed 's/\r$//'` 归一化后 `git merge-file -p --diff3 <模块> <1.2.0> <1.2.4>`，
冲突块用「ours 段 + theirs 段」语义合并；判据 = 模块相对 1.2.0 是否存在**独有声明**
（`val/fun/class` 名集合差集）：无独有声明 → 直接用上游新版；有 → 手工叠加上游改动。

**本批交付**：

- [x] 源码副本 bump：`GraceLauncher-src/` → v1.2.4（旧版备份 `.workbuddy/tmp/GraceLauncher-src-v1.2.0/`，三方合并 base 来源）。
- [x] DB v24→v34：补 `Migration24To25`..`Migration33To34`（含 26→27 三列 + `dismissResistance=75`
  初始化），`version = 34`，迁移链注册齐全。`widgetScreenJson` 上游在 v23 添加、本仓当时跳过，
  本批并入 24→25，使**模块 v25 起列集合与上游完全一致**，后续迁移可逐字沿用。
- [x] schema 自检：导出 `app/schemas/.../34.json` 与上游 34.json 逐表逐列比对 —— 表集合一致、
  列/类型/默认值全一致，唯一差异是本仓独有 `saved_shortcuts.dismissed`（v2.9.141 快捷方式三连修，保留）。
- [x] `LauncherSettings` 补齐 13 字段（allowScreenRotation / mediaKeepWhilePlaying / dismissResistance /
  dismissThresholdDp / mediaStyle / weatherConnection / agendaAppearance / fontColors / digitalWellbeing /
  widgetScreen / appCategories / preferNonAccessibility / calendar），`LauncherSettingsRepository`
  双向编解码同步（`toSettings()` + `updateSettings()`）；`DEFAULT_THEME_COLOR` 单一事实源保留。
- [x] 新增数据类 19 个（零 UI 依赖）：AgendaAppearance/AgendaEntry/AppCategory/CalendarPreferences/
  ClockContent/ClockRegions/ClockTasks/DigitalWellbeing/FontColors/LauncherCatalogMemory/LauncherProfiles/
  MaterialColorRole/MediaPlayerStyle/RenamedApps/UsageRepository/AppOpenHistory/WidgetScreen/
  weather:WeatherConnectionPreferences/tasks:{TaskItem,TasksPreferences,TasksRepository}。
- [x] `PopupItem.grid` 恢复（上游 1.2.0 就有，本仓当年裁剪）：`encode/decode` 双向 + `WidgetGridPlacement`。
- [x] `LauncherApp.isCloneProfile`（1.2.2 分身应用）；`LauncherShortcut` 同步 `iconCacheKey` /
  clone 透传，并从 `ShortcutRepository` 移除重复声明（本仓独立文件为准）。
- [x] 资源：139 个 `ms_*` 矢量图标（75 个新增）+ `xml/locales_config.xml` + 539/538 条文案。
  文案铁律：`edit_folder`/`uninstall` 与宿主 `com.miui.home` 资源同名 → 改名 `grace_edit_folder`/`grace_uninstall`。
- [x] `normalizeHomeOrder()` 以本仓语义補齐（GraceWidget 仍分离 Clock/Calendar，只做去重）——
  P3 合并 clock+calendar 后再换成上游的 key 映射。
- [x] 构建：`assembleRelease` 通过；产物 `.build/out/HomeMode4Miui-v2.9.164-p0-upstream124-180.apk`。

**本批刻意缓后（各归其批次，避免一次改动面过大）**：`HomeContent`（GraceWidget 合并）、`HomeLayout`
（clockCalendarEnabled/editingLocked）、`ListAppearance`（AppListLayout/Neither）、`AppRepository`、
`CalendarRepository`、`MediaPlayback`/`MediaSessionRepository`（media3 依赖未引入）、`AppNotificationStore`、
`ItemIconStore`/`IconPackRepository`（IconSnapshotCache 与 materialRole 渲染）、`ShortcutRepository` 本体、
`weather/*`、`AppListLayout`、`LauncherAppCache`/`PrivateAppCache`。

**待装机**：覆盖安装后确认 ① 桌面正常进第四模式零崩溃；② 设置页/抽屉/收藏全部照常（本批不改行为，
只加列与模型）；③ `logcat` 无 Room 迁移异常（`Migration didn't properly handle`）。

### P1 字体颜色集中管理（v2.9.165-p1-fontcolors / versionCode 181，2026-11-15）

**范围界定**：上游 1.2.2「重新整理设置页面 + 统一字体颜色管理」拆两半 —— 本批只做**字体颜色链路**
（可验证闭环）；设置页的**分组重排**（Homepage/AppList/FeedScreen/StatusScreen/DigitalWellbeing 等新页）
依赖小组件与应用类别功能，随 P3/P7 一起落，避免建空壳页。

- [x] **桥扩展到 4 条线齐全**（此前 P0 只做 ①②）：
  ① `LauncherSettings.fontColors`（P0）→ ② Room 列 `fontColorsJson` + 迁移 v29（P0）→
  ③ `GraceSettingsBridge.encodeRow/decodeRow/applyDelta` 补齐 P0 的 13 个字段 →
  ④ `GraceHomeSettings`（桌面侧 SP 模型 + KEY_FONT_COLORS）+ `GraceSettingsRepository` 三处映射
  （toEntity / seed / toGraceSettings）+ `GraceHomeRoot`/`SettingsActivity` 传 `fontColors` 给主题。
  **本批把 P0 遗留的「字段只进 DB、不过桥」全部补完**，后续每一批都直接复用这条链。
- [x] 新增 Compose 侧文件（R import 换 `com.homemode4miui.R`）：
  `ui/theme/FontColors.kt`（`LocalFontColors` + `resolvedFontColor` 显示期解析）、
  `ui/theme/WallpaperFontColor.kt`（`LocalWallpaperFontColor` + 壁纸明暗探测，仅用户显式授权才调 WallpaperManager）、
  `ui/settings/FontColorSettings.kt`（色板列表 + 新建 + 取色）、
  `ui/settings/WallpaperFontColorSettings.kt`、`ui/settings/MaterialColorLabels.kt`（37 个材质色角色中文标签）。
- [x] `Theme.kt`：新增 `fontColors` 参数；seed 计算提到分支外，新增 `rememberWallpaperIsDark` + `wallpaperScheme`
  仅供 `LocalWallpaperFontColor`（壁纸文字色随明暗自适应）；`CompositionLocalProvider` 补两条 provide。
  **保留模块定制**：`rememberWallpaperSeedColor`（壁纸主色驱动，替代上游静态 system_accent1_500）与 `fontContext`。
- [x] `IconDesignerColorPicker` 换上游 1.2.4 版：材质色角色 / 共享字体颜色两套预设 + `fromAppLabel` 上下文标签。
- [x] `SettingsSelectionDialog` 换上游版（`selectedItem: T?` + `footer` + `itemLeading` + `leadingButton`）。
- [x] `SettingsComponents` 补 `LocalOpenFontColors`；`LauncherSettingsScreen` 在导航目标内 provide
  （受 `isCurrent()` 保护的 `navigate(FontColors)`），路由加 `SettingsPage.FontColors`；
  `FontSettings`（FontLibrarySettings.kt）加「字体颜色」入口项（计数 0/1 → 0/2）。
- [x] **装机验证（fuxi / OS2 5.39.40.10843）**：零 FATAL；`GraceSettings diffRow keys=[fontColorsJson]` ×3
  （改色写回链路实锤）；`snapshot pushed ok=true len 10651 → 28377`（新字段进桥并回推）；
  用户手动确认「入口在 / 色板列表正常 / 改动返回后保留 / 重启桌面后保留」四项全绿。
- [ ] **已知边界（非缺陷）**：字体颜色当前只作用于设置页内部（色板、取色器、预览）。桌面实际文字
  （时钟 / 日期 / 应用名 / 字母条）的 `resolvedFontColor` 接线随 P2（时钟）/ P3（首页）/ P4（列表）落。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.165-p1-fontcolors-181.apk`。

### P2 时钟区域化体系（v2.9.166-p2-clock-regions / versionCode 182，2026-11-16）

上游 1.2.3「时钟组件更多自定义 + 时钟颜色」+ 1.2.4「时钟边距修复」的完整落地。

- [x] **渲染层（12 个文件）**：`ClockRegionHeader`（新 `HomeClockHeader`：按 `ClockStyle.regions` 多区域渲染，
  逐区域可见性/顺序/对齐 + `ClockColorPart` 逐元素配色 + 秒/前导零/等宽数字/居中/拉丁数字 + 旋转布局）、
  `ClockAgendaContent` / `ClockBatteryContent` / `ClockBatteryIcons` / `ClockCalendarDate` / `ClockTaskContent` /
  `FixedClockWidth` / `HomeTime` 新增；`ClockFace` / `SeparatedClockGlyphs` / `ClockSpacing` 换上游 1.2.4 版。
- [x] **`region == null` 降级链**：上游用 `LegacyHomeClockHeader`（旧一体化渲染）兜底，本批把模块原
  `HomeClockHeader` 改名 `LegacyHomeClockHeader` 并用**上游实现替换**（签名 17 参数与旧版同序，调用点零改动），
  保证未编辑过时钟的老用户视觉不变。
- [x] **外观层**：`LauncherAppearance` 增 `textColor: Color?`（null=沿用 darkText 固定双色）、
  `textShadow` 改按 `text.luminance()` 判定；`wallpaperTint` 增 `colorScheme` 参数（材质角色色才能解析）；
  新增 `LauncherBatteryState` + `rememberBatteryPercent` / `rememberBatteryState`（电量与充电态同时读，
  插拔电源图标与文字同步）。
- [x] **设置基础设施（6 个新文件）**：`SettingsPreviewLayout`（`SettingsPreviewScaffold` / `SettingsPreviewSurface` /
  `settingsPreviewScrim`）、`SettingsComponents` 扩 `SettingsScaffold` 三参数（wallpaperBackground /
  wallpaperNavigationBar / controlsAbovePreview）+ `SettingsApplyAction` + `LocalSettingsOpenFontColors` 旁路、
  `SettingsModeToolbar`、`ClockFieldOrder`、`ClockRegionControls`（730 行）、`ClockRegionOrder`、`ClockPresetPreview`。
  `SettingsSystemBarBackground` 四档 + `LocalSettingsSystemBarBackground` 落在 LauncherSettingsScreen，
  `SettingsScaffold` 用 `SideEffect` 上报 → 窗口统一应用系统栏。
- [x] **设置页**：`ClockStyleSettings` 换上游 657 行版（样式 / 内容 / 颜色三页签 + 预设预览 + 区域编辑器），
  入口加 `onManageFonts`（跳 Fonts 页）；路由 `SettingsPage.ClockStyle` 已适配。
- [x] **数据层同步**：`CalendarRepository`（多日历源 `calendars()` + `refresh(CalendarQuery)` 取代无参
  `upcomingEvents()`）、`ScheduleEvent`（displayColor/calendarName）、`WeatherModels`（WeatherMeasure /
  WeatherWind / WeatherHour / WeatherDay）、`ListAppearance`（AppListLayout / recentlyInstalledName /
  AppNameVisibility.Neither）、`AppListLayout`、`ShortcutsFoldersSettings`（previewNotifications）、
  `AppNotificationStore`（categoryEntries + `NotificationAppId`）。
- [x] **模块定制保留**：① `AppNotificationStore.disconnected(keepDisplay)` —— MIUI 省电解绑时保留快照不发布
  （notif-churn hotfix，上游无此语义）；② `AppNameVisibility.Neither` 档位文案新建 `grace_list_names_neither`
  （上游无该档文案，遵守"新增 UI 文案必须独立 string 名"铁律）。
- [x] **`LocalFontAssetContext`（模块架构补丁）**：桌面在宿主进程，`LocalContext` 的 assets 不含模块字体资产
  → 上游 `ClockFace` 的 `presetFont.family()` 必然回落。改为 Theme 层用 `fontContext` provide
  `LocalFontAssetContext`，`ClockFace` 按它解析 preset 字体（导入字体同理）。
- [x] **桌面接线**：`HomeScreen` 的 `GraceWidget.Clock` 区块切到新 `HomeClockHeader`（regions 存在时区域化渲染，
  否则内部回落 Legacy）。
- [x] **兼容层**：`WeatherComponents` 合并上游 1.2.4（`WeatherSourceNote` 新签名）+ 从 1.2.0 备份补回
  `DailyWeather` / `HourlyWeather` / `HourlyWeatherCell`（1.2.4 已删，模块 AgendaSheet 仍消费，P5 切
  `AgendaPanel` 后移除）。
- [x] **装机验证（fuxi / OS2）**：零 FATAL；桌面时钟/日期/电量/天气/收藏全部正常；用户确认四项全绿 ——
  ① 时钟样式页变三页签编辑器；② 改时钟颜色桌面立即生效；③ 秒/前导零开关生效；④ 设置页背景透出壁纸。
- [ ] **已知边界**：日程仍由独立 `LegacyHomeClockHeader` 区块渲染（上游把 clock+calendar 合成一个区域
  widget，需改 `HomeContent` 枚举 → P3）；Agenda 面板天气仍是兼容层（P5）。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.166-p2-clock-regions-182.apk`。

### P3 首页区块合并 + 天气点击 + 日期文字色（v2.9.168-p3-weather-click / versionCode 184，2026-11-16）

上游 1.2.2「clock 与 calendar 合并为单一区域 widget」的模型对齐，附两处用户实测问题的修复。

- [x] **GraceWidget 合并**：`{Clock, Calendar, Media, Baseline}` → 上游 `{ClockCalendar, Media, Baseline}`。
  日历 / 日期 / 天气 / 电量 / 任务都由 ClockCalendar 的内部区域承担，开关收敛为
  `homeLayout.clockCalendarEnabled`（`enabled()` 里再与本区块各部件开关做或运算）。
- [x] **老数据兼容**：`normalizeHomeOrder` 用上游 `normalizedGraceWidgetKey` —— 存量 homeOrder 里的
  `grace:clock` / `grace:calendar` 自动映射到 `grace:clock-calendar` 并 `distinct()`，用户无需清排序。
- [x] `HomeLayout` 补 `clockCalendarEnabled` + `editingLocked`（含 encode/decode，DB 无新列）。
- [x] **HomeScreen**：原先 `GraceWidget.Clock` / `GraceWidget.Calendar` 两个分支合并为一个
  `ClockCalendar` 分支 → 一个 `HomeClockHeader`（`showClock=clockEnabled`、`showCalendar=calendarAgenda`、
  `showBattery=showBatteryPercentage`、`weather=weather`），区块顺序里只保留一个 key，杜绝重复渲染。
- [x] **设置页**：主页排序区块列表 4 项 → 3 项（时钟与日程 / 小组件 / 媒体）；文案新建
  `grace_home_order_clock_calendar`（上游无对应 key，遵守独立 string 名铁律）。
- [x] **修复：天气图标点击无反应（移植缺口，非上游行为）** —— 上游链路是
  `LauncherScreen:1208 onWeatherClick = actions.openWeatherApp` → `HomeScreen` → `HomeClockHeader` →
  `HomeWeather.onClick`；模块 `HomeScreen` 主签名从来没有这个参数（只有内部 `LegacyHomeClockHeader` 有），
  链路自旧版起就是断的。本批补 `HomeScreen.onWeatherClick` → `ClockCalendar` 分支透传 →
  `GraceLauncherRoute` 接 `AgendaIntents.openWeatherApp(hostContext)`（与 Agenda 面板同一条 Intent 链）。
- [x] **修复：日期行「月」「日」与数字不同色（上游同样存在，已上报 issue #263）** ——
  `clockCalendarDate()` 按 `DateFormat.Field` 逐段上色，中文 pattern `M月d日` 的「月」「日」是 literal
  文本、不属于任何 Field → 拿不到颜色 → 落回基础文字色。修复：literal 段继承**前一个已上色字段**的颜色
  （首个上色字段之前的 literal 仍保持不上色）。英文等 literal 为标点的语言肉眼无变化。
  **这是本仓对上游的唯一主动偏离**（上游 issue：
  https://github.com/Galaxy-rio/GraceLauncher/issues/263 ，建议上游同样处理）。
- [x] **验证**：`assembleRelease` 通过；装机零 FATAL；区块合并不重复渲染；天气图标点击可打开天气应用。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.168-p3-weather-click-184.apk`。

### P4a 应用装载增量刷新（v2.9.169-p4a-incremental-apps / versionCode 185，2026-11-16）

上游 1.2.2「修复返回桌面时重新加载应用导致的卡顿」的模块实现（P4 三小步的第 1 步：只增不删）。

**问题**：模块 `refreshApps` 每次都是「全量枚举 + 全量 getBadgedIcon」。实测冷启动 142 个应用
解码耗时约 2.1s（`refreshApps: enumerated` → `icons done`），且包安装/更新/回桌面都会重走一遍。
上游 1.2.4 已重写成 `profileSnapshot → refreshProfile(fingerprint 增量) → buildApps`，
模块仍是整表重载 —— 两者结构差异过大无法三方合并，故本批**只在模块结构上嫁接增量能力**。

- [x] 新增 `ActivityFingerprint(component, sourceDir, splitSourceDirs, flags, lastUpdateTime)`：
  判定单个启动项是否变化。`lastUpdateTime` 由 `pm.getPackageInfo(pkg, 0)` 按包去重查询
  （同一包多个 launcher activity 只查一次），覆盖"sourceDir 不变但被覆盖安装"的场景。
- [x] `AppInventory` 带出 `fingerprints`（枚举时顺带采集，零额外枚举）；新增 `decoderKeys()`。
- [x] `AppRepository.loadApps` 拆为 `loadAppsIncremental()` + 薄包装，三条路径：
  ① **快路径**：图标包集合与全部指纹都没变 → 直接返回上次列表（含已解码图标），不再解码；
  ② **增量**：只对新增/指纹变化的 key 解码，其余复用上次对象（含 originalIconColor / isAdaptiveIcon 采样）；
  ③ **全量**：首轮或图标包变更 → 全部"需要系统解码"的项重解（包图标项由 `makeApp` 直接给新图标）。
  返回 `LoadOutcome(apps, inventory, reused, decoded)`，`inventory` 供调用方原地重试缺失图标。
- [x] `invalidateApps(packages)`：只让受影响包失效（`key.contains("<pkg>/")`，覆盖
  `profile:<serial>:<pkg>/<cls>` 形态）；`invalidateAll()` 整表失效。
- [x] `AppsLoader.refreshApps` 改走 `loadAppsIncremental`，保留原有"缺失率 >5% 重试一次"与
  "6s/15s 两轮自动重装"自愈链（重试复用同一 `inventory`，不重复枚举）。
- [x] `packageReceiver` 在 `refreshApps()` 前调 `invalidateApps(listOf(pkg))`。
  `SettingsViewModel` 走 `loadApps` 自动享用同一缓存。

**装机实测（fuxi / OS2，142 个应用）**：

| 场景 | 日志 | 说明 |
| --- | --- | --- |
| 冷启动首轮 | `enumerated=142 reused=0 decoded=142` | 全量，符合预期 |
| 覆盖安装 1 个应用后 | `enumerated=142 **reused=141 decoded=1**`，`icons done` 与 `enumerated` 相差 **1ms** | 只重解变化的那 1 个；改前是全量重解（约 2.1s） |
| 装饰结果 | `decorated iconMissing=0/142` | 无图标缺失，抽屉不会出现字母占位 |

零 FATAL。**本批不动** `enumerateApps` / `withIcons` / `workProfiles` / `makeApp`（P4b 才动）。

- [x] 产物：`.build/out/HomeMode4Miui-v2.9.169-p4a-incremental-apps-185.apk`。
- [ ] **P4b 待办**：枚举路径合并（`enumerateApps` / `workProfiles` / `withIcons` 嫁接进上游
  `profileSnapshot` / `refreshProfile` / `buildApps` 结构）。

### P4b 剖面可读性语义对齐（v2.9.170-p4b-profile-readable / versionCode 186，2026-11-16）

**范围决策（重要）**：P4b 原计划把模块枚举链路整体嫁接进上游
`profileSnapshot → refreshProfile → buildApps` 结构。评估后**降级为只对齐可读性语义**，理由：

| 项 | 结论 |
| --- | --- |
| 上游 `profileSnapshot`（94 行） | 含 profile 生命周期管理（锁定/静默/移除/克隆重建/重试），是 P4a 之外的另一套增量模型 |
| 与模块关系 | 模块 `enumerateApps` 的 dualapp / 工作资料语义是本仓反复修过的（MIUI XSpace），整体替换风险 > 收益 |
| P4a 已覆盖 | "回桌面不重载 / 只解变化图标"这两个核心收益已在 P4a 拿到 |
| 保留的差异 | 上游 `IconSnapshotCache<AppSourceSignature>` 的渲染签名缓存，模块用 P4a 的指纹 + 既有 `IconSnapshotCache` 等价覆盖 |

故本批只搬上游 1.2.2 的 **`readable(profile)`** 语义：静默 / 未解锁的受管 profile 不暴露其应用
（此前模块靠 `runCatching` 吞异常被动兜底，静默 profile 若仍返回数据会显示不该显示的应用）。

- [x] `enumerateApps` 在枚举前过滤：`userManager.isQuietModeEnabled(profile) != true &&
  userManager.isUserUnlocked(profile) != false`；查询失败（null）时**保留**该 profile（安全方向）。
- [x] 只用公开 `UserManager` API，**不改 profile 发现机制** —— `launcher.profiles` 与
  `getSerialNumberForUser` 原样保留，MIUI 双开（clone）语义完全不动。
- [x] 装机验证：应用数仍 **142**（dualapp 应用未丢）、`iconMissing=0/142`、零 FATAL。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.170-p4b-profile-readable-186.apk`。

### P4c-1 字母条组件升级（v2.9.171-p4c-alphabet-rail / versionCode 187，2026-11-16）

**先记一条计划偏差（重要）**：P4 的 UI 部分（抽屉 / 搜索 / 字母条 / 列表外观）**依赖 P9（应用类别）
与 P10（快捷方式设计）**，原批次顺序不成立。实测链条：

```
ListAppearanceSettings ──→ ListAppearancePreviewPopup ──→ ShortcutPopup 新版（P10，390 行重写）
AlphabetRail ──────────→ AppCategory + CategoryIndicator（P9）
ItemSelectionScreen ───→ AppCategoriesSettings / FavoriteFoldersScreen / AppGroupPickerScreen（P9+P12）
AppCategorySettings ───→ CategoryItemsEditor（在 ItemSelectionScreen 内，P13）
```

即 P4c / P9 / P10 / P13 互相咬合。**后续按「最小可编译闭包」推进**：每次选一个功能，
把它的全部依赖一起拷（允许跨批次），保证编译通过且不留 UI 死链，而不是严格按批次号走。

本批是该策略的第一个闭包（字母条显示层），不依赖 P9 的完整设置页：

- [x] `ui/components/AlphabetRail.kt` 换上游 1.2.3 版：**RTL 自动镜像**（`LayoutDirection` 判定 +
  `inwardDirection` 物理像素内移）、**可调触控区宽度**（`rightTouchWidth` 28..56dp）、
  **类别分段入口**（`categorySections` 参数默认 `emptyMap()`，P9 接入后才有内容）、
  起止命名由 left/right 改为 start/end（语义与上游一致）。
- [x] `data/AlphabetAppearance` 补上游 1.2.3 三字段：`showOnHome: Boolean?`（null = 沿用旧
  「隐藏字母条」开关）、`showInAppList: Boolean`、`rightTouchWidth: Int`，含 encode/decode。
- [x] `ui/home/HeadphoneConnection.kt`（新）：耳机连接状态（类别条件与 Agenda 用）。
- [x] `ui/components/CategoryIcon.kt`（新，含 `CategoryIndicator`）+ `ui/settings/CategoryLabels.kt`
  （从上游 `AppCategorySettings` 抽出的 `categoryLabel`，避免为一行文案整页搬 P9）。
- [x] 验证：`assembleRelease` 通过；装机零 FATAL；抽屉正常打开（新 AlphabetRail 用默认参数，
  行为与旧版一致，触控区调宽需 P4c-2 的设置页接线）。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.171-p4c-alphabet-rail-187.apk`。

**P4c 剩余（待 P9/P10 解封）**：`AppListModel`（122）、`AppDrawerScreen`（222）、
`AppSearchScreen`（269）、`SearchIndex`（61）、`LauncherListGestures`（61）、`AppStack`（39）、
`ListAppearanceSettings` + 两个 Preview、`AlphabetSettings`（154）、`ShortcutsFoldersSettings`、
`AppStackSettings`。

### P11a 首页契约补齐（v2.9.172-p11a-uistate-contract / versionCode 188，2026-11-16）

P11（首页切上游 `uiState` 契约）的第一步：**只补契约、不切调用方**，零行为变化。

- [x] `LauncherUiState` 补齐上游 1.2.4 的 12 项：
  - 普通字段 6 个：`calendarSources` / `calendarSourcesStatus` / `isDefaultHome`(null=未查询) /
    `categoryNotifications` / `usage`(UsageSnapshot) / `workFolderApp`
  - 派生 getter 6 个：`canFavoritePrivateSpace` / `canFavoriteWorkProfile` / `favoriteFolders` /
    `appCategories`（`settings.appCategories ?: migrateHomeCategories(...)`）/ `homeItems`
    （`orderedHomeItems(migrated, 类别 + 收藏文件夹, popups[HomeContentKey])`）/ `needsCategoryOpenCounts`
  - 依赖类型经检查全部已在仓内：`CalendarSource`（CalendarRepository）、`NotificationAppId` +
    `CategoryNotificationLevel`（AppCategory）、`UsageSnapshot`（DigitalWellbeing）、`AppCategory`
  - 注意：模块**已有** `workFolder: LauncherFolder?`（可空版，1.2.0 形态），本次复用之，
    `favoriteFolders` 里用 `workFolder?.let { add(it) }`（上游是非空版，语义等价）
- [x] 新增适配层 `homemode4miui/grace/GraceLauncherUiStateAdapter.kt`：
  - `GraceHomeSettings.toLauncherSettings()`：模块桌面侧设置模型 → 上游完整设置模型
    （模块没有的字段取上游默认值：mediaKeepWhilePlaying / dismissResistance / mediaStyle /
    weatherConnection / agendaAppearance / digitalWellbeing / widgetScreen / appCategories 等）
  - `GraceScheduleStatus.toScheduleStatus()`：同名枚举桥接（模块侧独立定义，避免与上游耦合）
  - `GraceHomeUiState.toLauncherUiState()`：26+ 字段投影到 67 字段契约；`favoriteKeys` 由
    `favoriteOrder` 派生、`folderApps` 取 Map.values、`media` 由 `hasMediaAccess + homeMedia` 组装
  - **本批不被任何调用方引用**（P11b 才接线），故零行为变化
- [x] 装机验证：零 FATAL；`GraceHomeView attached` 正常；`refreshApps` 仍 142/142 图标零缺失
  （与 P4a 一致），桌面表现与上一版无差异。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.172-p11a-uistate-contract-188.apk`。

**P11b 待办（高风险，单独一批）**：
① `HomeScreen` / `ClockRegionHeader` / `AlphabetRail` 等换上游实现并接适配层；
② **排序单一来源统一**（现状双源：`FavoritesStore`(SP) 存收藏顺序 + `homeLayout.homeOrder` 存区块顺序；
上游只用 `homeOrder`）—— 需一次性迁移 + 双读兜底；
③ 完整回归：收藏增删排序 / 文件夹 / 应用堆 / 拖动排序 / 手势 / 时钟 / 日程 / 天气 / widget。

### P11b 第一段：排序单一来源（v2.9.174-p11b-single-order-source / versionCode 190，2026-11-16）

P11b 拆两段，本段只解决"收藏顺序双源"，不动 `HomeScreen`（降低单次风险面）。

**问题**：模块此前双源 —— `FavoritesStore`(SP) 存收藏顺序，`homeLayout.homeOrder`(Room) 只存区块顺序；
上游 1.2.2+ 首页顺序是**单一来源** `homeOrder`（区块 / 类别 / 收藏都是其中条目）。

- [x] **一次性迁移**（vc189 已单独装机验证）：`GraceHomeViewModel.migrateFavoritesToHomeOrder()`
  —— `homeReady` 后触发，`homeOrder` 非空即跳过（幂等），把 `区块 key + favoriteOrder` 写入 homeOrder。
  Route 里 `LaunchedEffect(state.homeReady)` 调用。实测日志：
  `favorites→homeOrder migrate: blocks=2 favorites=10 total=12`（该机无小组件，故 blocks=2）。
- [x] **单源读取**（vc190）：`AppsLoader` 的收藏顺序改为
  `homeOrder（去掉 grace:/widget:） + Store 差集` —— 既以 homeOrder 为准，又保留
  "新增收藏只写了 Store"的补全路径，迁移失败也不会丢收藏。
- [x] **双写**：`SettingsSync.reorderFavorites` 同时写 homeOrder（区块相对位置不动，收藏段换新顺序）
  与 `FavoritesStore`（镜像存根）。读取只认 homeOrder → 语义与上游一致，回退仍有 Store 可用。
- [x] 验证：零 FATAL、`refreshApps` 142/142 图标零缺失、迁移日志只在首轮出现（第二轮幂等跳过）。
- [x] 产物：`.build/out/HomeMode4Miui-v2.9.174-p11b-single-order-source-190.apk`。

**P11b 第二段（待做）**：`HomeScreen` / `ClockRegionHeader` 换上游实现 + Route 接适配层。
策略：模块版改名 `LegacyHomeScreen` 保留，上游版上线后出问题可一行切回。

### P9a 应用类别数据层（v2.9.175-p9a-category-actions / versionCode 191，2026-11-16）

上游 1.2.2「App Categories」的**数据与写操作链路**（UI 页面留下一段，见文末）。

- [x] `LauncherActions` 补 4 个类别动作（与上游签名一致）：
  `saveAppCategory: (AppCategory) -> Unit`、`updateAppCategory: (String, (AppCategory) -> AppCategory) -> Unit`、
  `deleteAppCategory: (String) -> Unit`、`toggleCategoryItem: (String, LauncherApp) -> Unit`。
- [x] `SettingsViewModel` 接线：4 个动作全部走既有 `applySettingsSuspend` ——
  本地乐观更新 + `diffRow` 产出 `appCategoriesJson` → 桥命令 → 宿主 `applyDelta`。
  **无需新增桥命令**（P1 的 13 字段里已含 `appCategoriesJson`，4 条线早已铺好）。
  写后统一过 `synchronizeCategoryPlacement(current, next)`（类别放置与首页顺序同步）；
  `deleteAppCategory` 对内建类别（`BuiltInCategoryIds`）直接忽略（上游同语义）。
- [x] `LauncherUiState` 已具备类别派生（上一提交）：`appCategories` / `homeItems` /
  `categoryItems`（手动 / 通知 / 最近安装 / 常用应用四种规则）/ `visibleHomeContent` /
  `needsCategoryOpenCounts`。
- [x] 验证：`assembleRelease` 通过；装机零 FATAL。

**P9b 待办（UI 段落）**：
1. `ItemSelectionScreen` 补上游 5 个参数并实现语义：`onAddStack` / `showApps` /
   `readOnlySelected` / `emptyMessage` / `selectedNotice`（默认值保持现有行为）；
2. 从上游 `ItemSelectionScreen` 抽出 `CategoryItemsEditor`（58 行）到独立文件；
3. 拷 `ui/settings/AppCategorySettings.kt`（268 行，含 `AppCategoriesSettings` 列表页 +
   `AppCategoryEditorSettings` 编辑器），删除与 `CategoryLabels.kt` 重复的 `categoryLabel`；
4. 路由：`SettingsPage.AppCategories` + `CategoryEditor/{id}`；入口：`ProductivitySettings` 加「应用类别」项；
5. 模块 `HomeScreen` 内容源换 `visibleHomeContent(...)`（**不换骨架**，只换数据来源）。

### P9b 应用类别 UI（v2.9.176-p9b-category-ui / versionCode 192，2026-11-16）

上游 1.2.2 App Categories 的界面层落地（数据/写操作已在 P9a 完成）。

- [x] `ItemSelectionScreen` 补上游 5 个参数并实现语义（默认值保持既有行为）：
  `onAddStack`（应用堆入口）/ `showApps`（是否显示全部应用池）/ `readOnlySelected`（动态类别成员只读）/
  `emptyMessage`（自定义空态）/ `selectedNotice`（已选区上方提示条）。
  同时把 `ItemSelectionScreen` 与 `SelectionEntry` 从 `private` 放宽为 `internal`（供类别编辑器跨文件复用）。
- [x] 新增 `ui/overlays/CategoryItemsEditor.kt`（从上游 ItemSelectionScreen 抽出，50 行）。
  **模块差异**：暂无 `AppGroupPickerScreen`（文件夹/应用堆选择页，属 P13），
  类别成员的文件夹/堆入口先置 null；手动类别仍可选应用与快捷方式，动态类别为只读结果。
- [x] 新增 `ui/settings/AppCategorySettings.kt`（261 行）：
  `AppCategoriesSettings`（列表 + 新建对话框 + 选择模式）与 `AppCategoryEditorSettings`
  （名称 / 包含方式 / 动态规则 / 通知级别 / 数量上限 / 图标 / 放置 / 生效条件 / 起止时间 / 删除）。
  重复的 `categoryLabel` 已剔除（统一用 `CategoryLabels.kt`）。
- [x] `LauncherActions` 补 `requestUsageAccess`（常用应用类别的前置权限）；
  `LauncherSettingsScreen` 补 `LocalOpenDigitalWellbeing`（类别编辑器的「去开启数字健康」跳转，默认 null 即不可跳）。
- [x] 路由：`SettingsPage.AppCategories` + `SettingsPage.CategoryEditor`（`CategoryEditor/{categoryId}`，
  categoryId 空 = 新建）；入口加在生产力设置第 9 项（原 8 项的 count 同步改为 9）。
- [x] 文案：补 `plurals/app_category_item_count`（en: 1 item / N items；zh: N 个项目）；
  应用堆入口复用已有 `app_stacks_title`。
- [x] 验证：`assembleRelease` 通过；装机零 FATAL。

**已知边界（P9c 待做）**：桌面**尚未渲染类别行** —— 模块 HomeScreen 的 `favorites` 参数是
`List<LauncherApp>`，而 `visibleHomeContent(...)` 产出 `List<PopupItem>`（类别/文件夹/收藏混合）。
需要给 HomeScreen 增加"类别行"渲染能力（含 `CategoryIndicator` + 成员预览），
与上游 HomeScreen 的 `entries` 渲染对齐后再接，避免只接数据源造成"类别看不见"。

### 修复：应用类别里「收藏」显示 0 个项目（v2.9.179-favorites-category-fix / versionCode 195，2026-11-16）

用户实测：设置 → 生产力 → 应用类别 → 内置「收藏」类别显示 0 个项目。

**根因（两层）**：
1. 上游内置「收藏」类别（`FavoritesCategoryId`）的成员存在 `AppCategory.itemKeys` 里，
   由 `toggleFavorite` 内 `toggleCategoryItem(FavoritesCategoryId, app)` **双写**保持同步
   （`LauncherViewModel.kt:1714`）。模块只搬了类别 UI 与 `toggleCategoryItem`，
   收藏增删仍走自己的 Store 路径 → `itemKeys` 永远为空 → 计数 0。
2. `CategoryItemsEditor` 里非动态类别取 `category.itemKeys`（而非 `uiState.categoryItems()`），
   把这个空值直接显示出来。

**修复**：
- [x] `LauncherUiState.categoryItems()` 对 `FavoritesCategoryId` **读取时派生**
  （`favoriteOrder + favoriteKeys 差集`，按可用性过滤并去重）——
  不依赖写侧双写同步，收藏变多/变少立即正确。
- [x] `CategoryItemsEditor` 的 `selected` 统一取 `uiState.categoryItems(category)`（列表计数与编辑器同源）。
- [x] 收藏类别在编辑器里设为**只读** + 指引文案 `grace_favorites_edit_hint`
  （「收藏成员请在桌面长按 → 编辑收藏中修改」）——
  因为模块暂无"收藏增删"桥命令（`GraceSettingsBridge.Cmd` 里没有 TOGGLE_FAVORITE），
  直接放开会让用户改了没反应。
- [x] 同时补诊断日志（`favorites order: store=/fromHome=/final=`、`host snapshot favoriteOrder=`、
  `settings snapshot: favKeys=/favOrder=/apps=`），本次定位靠它们排除"桌面侧数据为空"的假设。
- [x] 验证：装机零 FATAL。

**后续可做**：补桥命令 `TOGGLE_FAVORITE`（宿主侧 `favoritesStore.toggle` + 重发快照），
即可放开收藏类别的编辑能力，与上游行为完全一致。

### P9c 桌面类别行 + 收藏增删桥命令（v2.9.180-p9c-category-row / versionCode 196，2026-11-16）

两件并行完成（一套代码、一次验证）：桌面渲染应用类别行；设置页可直接增删收藏。

#### 1. 桌面类别行（模块自研渲染，不改 HomeScreen 骨架）

- [x] 新增 `ui/home/CategoryRowBlock.kt`（约 170 行）：类别标题（`CategoryIndicator` + 名称 + 成员数）
  + 成员图标网格（每行 4 个，最多展示 8 个，超出显示 `+N`）；图标缺失时以首字母占位；
  空类别直接不渲染。纯展示组件，不持有状态、不订阅数据。
- [x] `HomeScreen` 加 `categoryRows: List<Pair<AppCategory, List<LauncherApp>>>` 参数：
  类别以 `"category:<id>"` 形式进入 `blockOrder`（与 clock / widget / media 同序，可参与拖动排序），
  `when(blockKey)` 新增前缀分支渲染 `CategoryRowBlock`。
- [x] `GraceLauncherRoute` 计算 `categoryRows`：走适配层 `toLauncherUiState().appCategories` +
  `categoryItems()`（手动/通知/最近安装/常用应用四种规则），按 `showOnHome` 与
  `isVisible(headphones, minuteOfDay)` 过滤，成员为空的不传。
  **排除内置「收藏」类别** —— 收藏区本身已是一行，否则桌面出现两个收藏。
  `headphones` 用 `rememberHeadphonesConnected().value`（返回 `State<Boolean>`）。
- [x] 文案：`grace_category_member_count`（成员数）、`grace_category_more`（+N）。

#### 2. 收藏增删桥命令（并发任务）

- [x] `GraceSettingsBridge.Cmd.TOGGLE_FAVORITE = "settings_toggle_favorite"`（EXTRA_ARG1 = app key）。
- [x] `GraceSettingsHostEngine` 新增分支：`favoritesStore.toggle(key, currentOrder().orEmpty())`，
  `runCatching` + `ModLog.e` 兜底；返回 true 后由 `handle()` 尾部统一 `pushSnapshot()` + `onMutated()`，
  而 `onMutated()` → `SettingsSync.onMutated()` 重读 Store 刷新桌面 `favoriteOrder` —— 闭环成立。
- [x] `SettingsViewModel.toggleCategoryItem`：`FavoritesCategoryId` 走发命令（模块进程无 SP 写权），
  其余类别仍走 `applySettingsSuspend`（appCategories 增量落库）。
- [x] `CategoryItemsEditor` 放开收藏类别编辑：`readOnlySelected = dynamic`（只读仅留给动态类别）；
  收藏类别的「移除」= 再 toggle 一次（成员身份在宿主收藏序里）；删除只读提示分支。
- [x] 验证：装机零 FATAL；`favorites order: store=10 fromHome=11 final=11` 正常。

#### 3. 已知限制

- 收藏类别在编辑器里**拖动重排仍会回弹**（`onReorder` 走 `updateAppCategory{itemKeys}`，
  对收藏不生效）。需给 `LauncherActions` 补 `reorderFavorites` 并让设置页接上，随 P12 处理。
- 类别的「常用应用」动态规则需要数字健康开启（`GraceHomeSettings` 暂无 `digitalWellbeing` 字段，
  适配层取上游默认值 = 关闭）→ 该规则目前恒空，随 P9 后续（数字健康页）补齐。

#### 4. 工程教训

并发跑两个 `gradle`（主代理 + 子代理）会损坏增量编译缓存：
`Incremental compilation failed: Storage ... already registered` + 一串假的 `Unresolved reference`。
解法：`rm -rf app/build/kotlin` 后重编即可，**不要按那些假错误去改代码**。
