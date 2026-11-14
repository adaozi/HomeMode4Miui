# 负一屏保活逆向分析（RE_MINUS_ONE_KEEPALIVE）

- 日期：2026-11-12
- 目标：`com.miui.home`（OS3 `RELEASE-6.01.03` 为主，OS2 交叉验证）
- 方法：Androguard 静态反编译（`.workbuddy/re/api.py` / `dumpcls.py`），逐方法证据链
- 结论分级：**已证实**（有反编译证据）/ **推断**（静态推理，未运行时验证）/ **未知**（缺 PA 侧 APK）
- 授权与用途：自有设备预装应用，互操作性开发与功能研究，结论仅用于本项目

---

## 0. 一句话结论

> MIUI 桌面**从设计上就让 personalassistant 常驻**：桌面进程存活期间绑定关系永不解绑，
> 退后台只把 `mStopped` 置 true；真正的 unbind 只在「桌面在后台 **且** PA 的 binder 已断」这一交集发生。
> 第四桌面模式之所以"负一屏不存活"，是因为 `MinusOneGate` 把 `connect()` 拦掉了 ——
> PA 从来没有建立过绑定，谈不上常驻。**方案不是增加保活，而是恢复原生的绑定语义。**

---

## 1. 分析对象与环境

| 项 | 值 |
|---|---|
| 宿主 APK（OS3） | `Downloads/MiuiHomeOS3.apk`（24.9 MB） |
| 宿主 APK（OS2） | `Downloads/MiuiHomeOS2.apk`（21.3 MB） |
| PA 包名 | `com.miui.personalassistant` |
| 反编译产物 | `.workbuddy/re/persistent.txt`、`ccompat.txt`、`loc.txt`、`alc.txt`、`olc.txt`、`lc_core.txt`、`cb.txt`、`os2_check.txt` |
| OS2/OS3 一致性 | `overlay/client` 包 16 个类两版完全同名；`cleanUp()` / `onStop()` 语义逐字节一致 |

**未取得**：`com.miui.personalassistant` 的 APK。因此本报告只覆盖**桌面侧**行为，
PA 进程内部的常驻/退出策略（第 8 节未知项）不在证据范围内。

---

## 2. 连接层类关系（已证实）

```
LauncherClient                      ← 生命周期入口（onStart/onStop/onResume/onPause/onDestroy）
  └─ OverlayLauncherClient          ← 增加 launcher 弱引用 + 断线重连门禁
       └─ AssistantLauncherClient   ← 负一屏专用（延迟连接 / EventBus 重连通道）
            ↑ 由 LauncherAssistantCompatMIUI.createLauncherClient() 实例化

LauncherClient.mLauncherOverlayConnection : LauncherOverlayConnectionCompat
  └─ ServiceConnectionStrategy（按 ClientOptions.priorityStrategy 选择）
       ├─ WaivePriorityServiceConnectionStrategy   (strategy = 1)
       ├─ MiddlePriorityServiceConnectionStrategy  (strategy = 2)
       └─ HighPriorityServiceConnectionStrategy    (其他)

PersistentLauncherOverlayConnection  ← 进程级单例（sInstanceMap，按包名），flags = 33
LauncherClientService                ← HighPriority 路径的等价物，flags = 65
```

`ClientOptions` 由 `LauncherAssistantCompatMIUI.createClientOptions()` 构造：

```java
int strategy = MIUIWidgetCompat.getOverlayPriorityStrategy(mLauncher);  // 1 / 2 / 其他
return new LauncherClient.ClientOptions(mPackageName, enabled, strategy);
```

三条策略的 `onStop` 最终都落到同一个判定上：

| 策略 | onStop 实现 | 落点 |
|---|---|---|
| Waive (1) | `mPersistentConnection.setStopped(true)` + `mPriorityConnection.disconnect()` | `PersistentLauncherOverlayConnection.cleanUp()` |
| Middle (2) | 继承 Waive（仅额外 `setStrategy(this)`） | 同上 |
| High (其他) | `mClientService.setStopped(true)` | `LauncherClientService.cleanUp()` |

两条 `cleanUp()` 的实现**逐行同构**（OS2/OS3 亦同构）：

```java
private void cleanUp() {
    if (mStopped && mLauncherOverlay == null) {
        disconnect();          // 全项目唯一的 unbindService 入口
    }
}
```

---

## 3. 生命周期调用链（已证实）

### 3.1 桌面退后台

```
BaseLauncher.onStopAfterCheckCreatedFlag()
  → mLauncherCallbacks (LauncherCallbacksChinese).onStop()
    → mAssistantCallbacks (AssistantLauncherCallbacks).onStop()
      → mLauncherAssistant (LauncherAssistantCompatMIUI).onStop()
        → mClient (AssistantLauncherClient).onStop()
          → LauncherClient.onStop()
              mLauncherOverlayConnection.onStop();       // → setStopped(true) → cleanUp()
              mActivityState &= -2;                      // 清 start 位
              onActivityStateChange();                   // → PA.setActivityState(...)
```

每一层都有反编译证据（`cb.txt`、`lacm.txt`、`lc_lifecycle.txt`）。
注意最后两行：**"桌面不可见"是通过 `setActivityState` 通知 PA 的，与解不解绑无关**。
这一点决定了后续方案可以在"保持绑定"的同时不影响 PA 的前后台感知。

### 3.2 桌面回前台

```
BaseLauncher.onStartAfterCheckCreatedFlag()
  → ... → LauncherClient.onStart()
        mLauncherOverlayConnection.onStart();          // → setStopped(false)
        mActivityState |= 1; onActivityStateChange();
```

`AssistantLauncherClient.onStart()` 额外做一件事：`Looper.myQueue().addIdleHandler(this)`，
`queueIdle()` → `connect()`。即**连接被推迟到消息队列空闲**，不阻塞首帧 —— 这是原生的启动性能优化，
不是"延迟加载 PA"的意思。

### 3.3 桌面销毁

```
LauncherAssistantCompatMIUI.destroyLauncherClient()
  → mClient.onDetachedFromWindow(); mClient.onDestroy(); mClient.disconnect();
LauncherClient.onDestroy()
  → mLauncherOverlayConnection.onDestroy(this, !isChangingConfigurations())
    → strategy.onDestroy() → PersistentLauncherOverlayConnection.unbindClient(client, flag)
      → 仅当 client 与单例记录的 client 相同且 flag=true 时 disconnect() + 从 sInstanceMap 移除
```

**结论：常驻粒度 = 桌面进程生命周期。** 配置变更（横竖屏/深色模式）不断开；
桌面进程真正销毁才解绑。

---

## 4. 断链缺口（问题的真正来源，已证实）

正常路径没有洞。洞在这条交叉路径：

```
[桌面已退后台: mStopped = true]
        ↓  PA 进程被 LMK / MIUI 清理杀掉
PersistentLauncherOverlayConnection.onServiceDisconnected()
  → setOverlay(null)            // mLauncherOverlay = null
  → cleanUp()                   // mStopped=true 且 overlay=null → 命中
    → disconnect()              // unbindService，绑定关系消失
```

绑定一消失，就不再有任何机制把 PA 拉回来：下次滑出只能走 `connect()` 重新 `bindService`，
等价于 PA 冷启动（装机观测 6s+），期间 `AppWidgetHost` 未 `startListening`，
已保存的微件拿到空 RemoteViews → 渲染成"无法添加微件"。

第二处缺口是重连门禁（`olc.txt`）：

```java
// OverlayLauncherClient.onServiceDisconnected()
if (!mHasTryReconnect && isEnable() && launcher.hasBeenResumed()) {
    mHasTryReconnect = true;
    BackgroundThread.post(reconnectRunnable);
}
```

`hasBeenResumed()` 意味着**桌面在后台时 PA 崩了不会重连**。
即：桌面在前台断线能自愈，在后台断线只能等下次滑出。

原生另有三条被动重连通道（`alc.txt`），只在收到事件时触发：

| 事件 | 触发条件 |
|---|---|
| `AssistantConnectMessage` | 外部请求连接 |
| `OverlayReconnectMessage` | `getDirection() == 0` |
| `PackageDataClearMessage` | `getPackageName() == "com.miui.personalassistant"`（清数据） |

三者都调用 `reconnect()`：

```java
if (mLauncherOverlay != null) return;   // 已连则忽略
mLauncherOverlayConnection.disconnect();
connect();
```

而 `LauncherOverlayConnection.onServiceDisconnected()` 已把 `mConnected = 0`，
所以 `connect()` 不会被"already connected"短路 —— **绑定关系保留时，重新拉起 PA 是可行的**。

---

## 5. 第四桌面模式 vs 原生：差异定位（已证实 + 推断）

| 维度 | 原生桌面 | 第四桌面模式（v7 + 保活落地后） |
|---|---|---|
| 首次 connect 时机 | `waitForUnlockAndRunOnUiThread` 或 `queueIdle`，解锁后即连 | `MinusOneGate` v7 一次性门：启动窗口内 defer，**最多 8s** 放行 |
| 绑定状态 | 贯穿桌面进程生命周期 | 放行后由 `OverlayKeepAlive` 维持，等同原生 |
| 退后台 | `setStopped(true)`，不解绑 | `setStopped(true)` 被吞 → 不解绑（§6.1） |
| PA 被杀后 | 绑定在 → 系统重启 → `onServiceConnected` 自愈 | 同左；`onBindingDied` 另有 2s/6s/15s 补绑兜底（§6.3） |
| 唤出入口 | 原生滑页 | `MinusOneEntry` overlay 协议（重试链退为兜底） |

**根因判定**：`MinusOneGate` 的 `connect` 拦截与"保活"目标直接冲突。
v6 的 gate 在 `sReady` 置位前会吞掉**全部四条 connect 通道**：

1. `AssistantLauncherClient.queueIdle()` → `connect()`
2. `LauncherClient.setClientOptions()` → `reconnect()`（模式切换）
3. `AssistantConnectMessage` / `OverlayReconnectMessage` → `reconnect()`
4. `PackageDataClearMessage` → `reconnect()`

gate 未 ready 期间这四条全部被吞，PA 无法起身；gate ready 后重放，
但重放只覆盖 `sPending` 里挂起的那几个 client 实例，**事件通道触发的重连不会补发**。

> 推断（未运行时验证）：这解释了"解锁后第一次滑负一屏必定重载、且微件易空白"的时序症状。

**已落地（2026-11-12，MinusOneGate v7）**：gate 已降级为**一次性启动门** ——
只在"未解锁 / 注册未就绪"的启动窗口内 defer，`READY_TIMEOUT_MS` 由 60s 收到 **8s**，
放行后 `sOpen` 永久为 true，不再拦截任何 connect（含 reconnect 与事件通道）。
即 §5 的冲突已消除，剩余问题只剩"绑定层保活"本身。

---

## 6. 方案与实现（v2，2026-11-13 修订）

> **修订 2026-11-13**：初版 §6.1 的拦截点（hook `LauncherOverlayConnectionCompat.onStop`，
> 读 `strategy.mPackageName`）**不可用**，已废弃。复核反编译确认：
> `ServiceConnectionStrategy` 是**零字段**类（仅 1 个构造器 + 8 个抽象方法），
> `mPackageName` 实际声明在 `LauncherOverlayConnection`（`private final`，
> `PersistentLauncherOverlayConnection` 的父类）与 `LauncherClientService`（`private`）上。
> 原样实现必然 field miss → 走放行分支 → 空补丁。
> 同时初版漏了缺口二（`onBindingDied` 后 `mConnected` 不复位），本版一并补上。

### 6.1 拦截点：`setStopped` 双落点

两条策略链的降级动作最终都收敛到 `setStopped(boolean)`，这是唯一的公共环节：

| 类 | flags | 覆盖策略 | 包名字段 | 读取方式 |
|---|---|---|---|---|
| `PersistentLauncherOverlayConnection` | 33（`AUTO_CREATE \| WAIVE_PRIORITY`） | Waive(1) / Middle(2) | 继承自 `LauncherOverlayConnection`，`private final` | `getObjectField`（Xposed 向上查找父类）或 `getPackageName()` |
| `LauncherClientService` | 65（`AUTO_CREATE \| IMPORTANT`） | High（其它） | 自有 `private final`，无 getter | `getObjectField` |

```java
// hook/OverlayKeepAlive.java（已落地）
public static void install(ClassLoader cl) {
    hookSetStopped(cl, CLS_PERSISTENT);    // flags=33：Waive / Middle 落点
    hookSetStopped(cl, CLS_SERVICE);       // flags=65：High 落点
    hookBindingLoss(cl, CLS_PERSISTENT);   // 缺口二：onBindingDied / 观测
}

private static void hookSetStopped(ClassLoader cl, String cls) {
    XposedHelpers.findAndHookMethod(cls, cl, "setStopped", boolean.class, new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            if (!Boolean.TRUE.equals(param.args[0])) return;   // setStopped(false) 必须放行
            if (!isPaTarget(param.thisObject)) return;         // 非 PA 走原生
            param.setResult(null);                             // 吞掉 true
        }
    });
}
```

`isPaTarget()` 统一读 `mPackageName`：取不到即返回 false（原生行为），不做激进降级。

### 6.2 生效原理

1. `mStopped` 恒为 false → `cleanUp()` 的 `if (mStopped && ...)` 永不成立 → **永不 unbind**；
2. 绑定关系保留 → PA 被杀后系统按 binding 重启服务 → `onServiceConnected` → `setOverlay(binder)` →
   `updateOverlay()` → PA 内容链与 `AppWidgetHost` 重新挂载；
3. `LauncherClient.onStop()` 后续的 `mActivityState &= -2` 与 `onActivityStateChange()` **照常执行**，
   PA 仍收到"桌面不可见"通知，不会在后台持续渲染 —— 耗电影响与原生一致；
4. **`mPriorityConnection`（flags=65）仍在 `Waive.onStop()` 里被 `disconnect()`** —— 见 §6.4 权衡。

### 6.3 缺口二：`onBindingDied` 后的 `mConnected` 残留

反编译证据（`persistent.txt` / `loc.txt`）：

```java
// LauncherOverlayConnection.onBindingDied()      → 只打日志，不复位 mConnected
// LauncherClientService.onBindingDied()          → 只通知 client，不复位
// PersistentLauncherOverlayConnection.onBindingDied()
super.onBindingDied(p1);
if (mStrategy instanceof MiddlePriorityServiceConnectionStrategy) mConnected = 0;   // 仅 Middle！
```

而 `WaivePriorityServiceConnectionStrategy` 的构造器**不调** `setStrategy()`（只有 Middle 调），
所以 Waive 路径下 `mStrategy == null` → 不复位；High 路径走 `LauncherClientService` → 也不复位。

后果：`onBindingDied`（force-stop / 重装 / 系统回收 binding）后 `mConnected` 仍为 true，
之后任何 `connect()` 都会命中 `"overlay service already connected"` 直接 return → **PA 永不回来**。

修法（三点缺一不可）：

| 动作 | 原因 |
|---|---|
| after hook **立即** `setBooleanField(mConnected, false)` | 不能等补绑链。复位晚 2s 的话，这期间宿主自己的 `connect()`（onStart / setClientOptions / 事件通道）仍被短路 |
| 有界补绑 `2s / 6s / 15s`，每轮先 `overlay != null` → 停手 | 覆盖 PA 冷启动 + provider 注册窗口，不做无限重试 |
| 每轮若 `mConnected == true` 则跳过本轮（只等回调） | 说明 `bindService` 已发起，再 bind 会叠加引用计数 → `addService` 永不匹配 `unbind` 的泄漏 |
| `onServiceDisconnected` **不**补绑（仅观测） | 进程被杀时 binding 仍活，系统会自己重启服务；主动 bind 反而重复计数 |

### 6.4 权衡与可选增强

- 本方案保证的是"**绑定在 + 被杀后能重建**"，不是"不被杀"：
  flags=33 含 `BIND_WAIVE_PRIORITY`，绑定本身不给 PA 提升 oom 档位；
- 若要更硬的保活，需额外拦住 `WaivePriorityServiceConnectionStrategy.onStop()` 里的
  `mPriorityConnection.disconnect()`（flags=65 `BIND_IMPORTANT`）。代价是 PA 全程以 IMPORTANT
  优先级常驻，内存与耗电上升，**默认不启用**，按需开关；
- 桌面进程销毁（`destroyLauncherClient → unbindClient → disconnect`）仍会正常解绑 ——
  常驻粒度保持"桌面进程生命周期"，不跨重启。

### 6.5 配套：`MinusOneGate` v7（已落地 2026-11-12）

- 一次性启动门：只在未解锁 / 注册未就绪的窗口内 defer；
- `READY_TIMEOUT_MS` 60s → **8s**（`READY_POLL_INTERVAL_MS` 1000 → 500ms）；
- 放行后 `sOpen` 永久 true，gate 退出，PA 生命周期交还原生（含 reconnect 与事件通道）；
- `toggleKnownProviders()` 改为"仅已知 provider 不可见时才做"，已就绪的普通重启不再白等。

### 6.6 收益：可降级的旧补丁

落地并装机验证后，以下代码可退为纯兜底（不必删除）：

| 现有补丁 | 现状 | 落地后 |
|---|---|---|
| `MinusOneEntry.scheduleOpenRetry` 重试链（120/250/500/1000/2000ms） | 覆盖 client 未就绪 | 大概率一次命中，链表空转 |
| `startClientAwaitHeal`（20 × 400ms 轮询） | 等 PA 上线 | 不再触发 |
| `sReattachDone` 进程内首次强制 reattach | 修补"attach 标记为真但 widget 未绑定" | 语义变为冗余保险，可保留 |

---

## 7. 验证方法（装机）

### 7.1 日志采证

```bash
adb logcat -s LauncherClient.Service LauncherClient.PersistentConnection \
              LauncherClient.Connection LauncherClient OverlayKeepAlive
```

| 观测点 | 期望（修复后） | 现状（修复前） |
|---|---|---|
| 桌面退后台再回前台若干次 | 不出现 `unbind overlay service of com.miui.personalassistant` | 可能不出现（binder 尚在） |
| `am kill com.miui.personalassistant` 后 | `onServiceDisconnected` → 随后 `onServiceConnected` 自动回来 | 继续 `unbind ...` 且不再回来 |
| 再次滑出负一屏 | 立即显示、微件正常渲染 | 白屏 6s+ / "无法添加微件" |

### 7.2 行为验证

1. 冷启动 → 滑出负一屏 → 记录首帧时间与微件渲染结果；
2. 回桌面 → 在最近任务中划掉 personalassistant → 再滑出负一屏；
3. 重复 10 次，观察是否出现内容重载 / 微件空白；
4. 观察 `dumpsys meminfo com.miui.personalassistant` 的常驻内存与耗电排行变化。

---

## 8. 局限与未知

| 项 | 状态 | 说明 |
|---|---|---|
| 全部结论 | 静态 | 反编译推断，未经运行时验证；类名/方法名在其它 MIUI Home 版本可能漂移 |
| PA 侧行为 | **未知** | 无 `com.miui.personalassistant` APK，无法确认它收到 `setActivityState(非 started)` 后是否自降优先级/延迟自杀 |
| force-stop 场景 | 覆盖不到 | 若 MIUI 省电策略对 PA 执行 force-stop（package stopped），绑定不会拉起服务，需另设唤醒兜底 |
| 内存代价 | 已知 | PA 常驻（百 MB 量级）。**原生桌面本就在付这份成本**，本方案只是把第四模式对齐原生 |
| 其它 overlay | 已隔离 | Google overlay（gsa）走不同包名与不同 client，hook 内按包名判定，不误伤 |
| 字段访问 | 已修正 | `mPackageName` **不在** `ServiceConnectionStrategy`（零字段类），而在 `LauncherOverlayConnection`（`private final`）与 `LauncherClientService`（`private final`）；`XposedHelpers.getObjectField` 会向上查找父类，两处共用同一读取逻辑；取不到返回 false → 放行，不做激进降级 |

---

## 9. 证据清单

| 文件 | 内容 |
|---|---|
| `.workbuddy/re/persistent.txt` | `PersistentLauncherClient` / `PersistentLauncherOverlayConnection` / `HighPriorityServiceConnectionStrategy` / `LauncherClientService` 全类 |
| `.workbuddy/re/ccompat.txt` | `LauncherOverlayConnectionCompat` 策略选择 + Waive/Middle 策略实现 |
| `.workbuddy/re/loc.txt` | `LauncherOverlayConnection.connect/disconnect/onServiceConnected/onServiceDisconnected`（`mConnected` 状态机） |
| `.workbuddy/re/alc.txt` | `AssistantLauncherClient` 全类（延迟连接 / EventBus 重连 / `onServiceDisconnected`） |
| `.workbuddy/re/olc.txt` | `OverlayLauncherClient`（`hasBeenResumed` 重连门禁） |
| `.workbuddy/re/lc_core.txt` | `LauncherClient` 构造 / `connect` / `setOverlay` / `setServiceState` |
| `.workbuddy/re/lc_lifecycle.txt` | `LauncherClient.onStart/onStop/onResume/onPause/onDestroy/disconnect/reconnect` |
| `.workbuddy/re/lacm.txt` | `LauncherAssistantCompatMIUI`（`createClientOptions` / `createLauncherClient` / `destroyLauncherClient`） |
| `.workbuddy/re/cb.txt` | `LauncherCallbacksChinese` / `AssistantLauncherCallbacks` 转发链 |
| `.workbuddy/re/os2_check.txt` | OS2 同名类的 `onStop` 与 `cleanUp`（与 OS3 同构） |
| `.workbuddy/re/bl_life.txt` | `BaseLauncher` 的 `onStart/onStop/onResume/onDestroy` after-check 实现 |

---

## 10. 关键代码摘录（原文反编译，未改写）

```java
// PersistentLauncherOverlayConnection.cleanUp()  —— 唯一 unbind 判定（OS2/OS3 同构）
if ((this.mStopped) && (this.mLauncherOverlay == null)) {
    this.disconnect();
}

// WaivePriorityServiceConnectionStrategy.onStop()
void onStop() {
    this.mPersistentConnection.setStopped(1);
    this.mPriorityConnection.disconnect();
}

// OverlayLauncherClient.onServiceDisconnected()  —— 重连门禁
if ((!this.mHasTryReconnect) && (this.isEnable())) {
    if ((launcher != null) && launcher.get().hasBeenResumed()) {
        this.mHasTryReconnect = 1;
        BackgroundThread.post(...);
    }
}

// LauncherOverlayConnection.onServiceDisconnected()  —— 允许再次 bind
this.mConnected = 0;
```

---

## 11. 落地记录（v2.9.144-minusone-keepalive，2026-11-12）

实现与 §6 的方案有两点实质偏差，均为反编译复核后修正（**§6.1 的代码原样落地等于空补丁**）：

### 11.1 拦截点选型（补充理由）

初版 §6.1 的 `onStop` 桩读 `...mLauncherOverlayConnectionCompat.mPackageName`，但
`ServiceConnectionStrategy` **没有任何字段**（只有 8 个抽象方法 + 一个构造器），
即按原样写必然 field miss → 走放行分支 → 保活从未生效。选 `setStopped(boolean)`
的实现见 §6.1，理由：

- `onStop` 在三个策略类各有一份（Waive/Middle 落 persistent、High 落 clientService），
  拦截点分散且**策略类拿不到包名**；`setStopped` 是两条落点的唯一公共环节。
- `cleanUp()` 是 private 且各类各一份，拦它会把 `onServiceDisconnected` 的正常清理也
  一并封死；拦 `setStopped(true)` 只封"降级为 stopped"这一个语义。
- `LauncherClient.onStop()` 中 `mActivityState &= -2` 与 `onActivityStateChange()` 照常
  执行 —— §3.1 强调的"PA 仍收到桌面不可见通知"得以保留。

### 11.2 实现补强：复位时机（2026-11-13）

§6.3 的补绑链最初把 `mConnected` 复位放在首轮 2s 延迟里执行，留了一个窗口：
宿主自己的 `connect()`（onStart / setClientOptions / 事件通道）在这 2s 内仍会被
"already connected" 短路，等于没修。现改为 **after hook 内立即复位**，
补绑链只负责"未连上就 bind"：

```
onBindingDied after → setBooleanField(mConnected, false)        // 立即，修复窗口
                    → 2s/6s/15s：overlay != null 停手；
                                  mConnected == true 跳过本轮（bind 已发起，避免重复计数）
onServiceDisconnected → 只观测，不补绑（进程被杀时 binding 仍活，系统按绑定自行重启）
```

### 11.3 配套：`MinusOneGate` v7（§6.5）

- 放行上限 60s → **8s**，轮询 1s → 500ms；
- 放行后 `sOpen` **永久为 true**，四条 connect 通道（queueIdle / setClientOptions /
  `AssistantConnectMessage` / `PackageDataClearMessage`）全部交还原生；
- toggle 改为"仅当 `misettings`/`notes` provider 不可见时才做"（普通重启不再白等
  PACKAGE_CHANGED 重注册）；
- `MinusOneEntry.open()` 在 gate 预热期不再丢手势，改为 500ms × 16 有界重试。

### 11.4 涉及文件

| 文件 | 改动 |
|---|---|
| `hook/OverlayKeepAlive.java` | 新增：setStopped 拦截 + onBindingDied 补绑 + 连接恢复观测 |
| `hook/MinusOneGate.java` | v7：一次性启动门，8s 上限，放行后永久退出 |
| `hook/MinusOneEntry.java` | gate 预热期 open 有界重试（不再直接丢弃） |
| `MainHook.java` | 新增 `7.OverlayKeepAlive` 挂载项（独立降级） |

### 11.5 待验证（装机）

§7 的验证项全部仍待执行（本改动为静态实现 + 构建通过，未实机采证）。新增观测点：

```
adb logcat -s HomeMode4Miui | grep OverlayKeepAlive
```

| 期望日志 | 含义 |
|---|---|
| `setStopped(true) suppressed on PersistentLauncherOverlayConnection` | 保活生效（每次桌面退后台最多一次） |
| `PA binder lost (stopped=false, connected=false) -> binding kept` | 退后台后被杀且未解绑 |
| `PA binder restored (overlay=true)` | 系统按绑定自愈成功 |
| `binding died on com.miui.personalassistant -> rebind chain armed` | 走到补绑兜底路径 |

