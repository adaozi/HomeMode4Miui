/*
 * P6（B6.1/B6.3/B6.2）桥引擎 —— 新建文件（非搬运）。运行在【模块 App 进程】
 * com.homemode4miui（PORTING_GraceLauncher.md B6.1：NotificationListenerService
 * 由系统按 manifest 绑定，只能落在声明包自己的进程）。
 *
 * 职责：
 *   1. 收集进程内单例 appNotifications（B6.3 通知快照）与 MediaSessionRepository
 *      （B6.1 媒体快照，原样搬运，token/controller 全在本进程内），序列化 JSON 后
 *      显式广播给宿主 com.miui.home；封面走独立 JPEG bytes extra（见 BridgeContract）。
 *   2. Breezy Weather provider 读取（B6.2 天气）：READ_PROVIDER 是 Breezy 声明的
 *      custom permission，宿主 manifest 不可改拿不到 —— 因此天气也在本进程读
 *      （模块 manifest 已声明 uses-permission），快照经同一广播桥下发。
 *   3. 宿主命令执行（GraceCommandReceiver 转发）：媒体控制/通知打开与清除 ——
 *      token 只在本进程可用（Parcel 含 binder 不可落盘），命令必须回到本进程执行。
 *
 * 降级链（零崩溃铁律）：广播发送失败静默（宿主侧快照过期即隐藏）；collect 全链
 * runCatching；引擎永不抛出到调用方。
 */
package com.homemode4miui.bridge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import com.galaxyrio.gracelauncher.data.media.MediaCommand
import com.galaxyrio.gracelauncher.data.media.MediaSessionRepository
import com.galaxyrio.gracelauncher.data.notifications.appNotifications
import com.galaxyrio.gracelauncher.data.weather.BreezyWeatherRepository
import com.galaxyrio.gracelauncher.data.weather.WeatherState
import com.galaxyrio.gracelauncher.data.weather.WeatherStatus
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object GraceBridgeEngine {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var context: Context? = null
    @Volatile private var started = false
    private var scope: CoroutineScope? = null
    private var mediaRepository: MediaSessionRepository? = null
    private var weatherRepository: BreezyWeatherRepository? = null

    // 去重指纹：状态未变化不重发（媒体进度心跳已被原版 callback 过滤，此处兜底）
    private var lastNotificationsFingerprint: String? = null
    private var lastMediaFingerprint: String? = null
    private var lastWeatherFingerprint: String? = null

    /** 由 MediaNotificationListener.onListenerConnected / GraceCommandReceiver 懒触发。 */
    fun start(appContext: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val app = appContext.applicationContext
            context = app
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            // 原版语义：MediaSessionRepository 依赖 mediaNotifications.state（单例），
            // enabled 由 collect 快照时按 hasAccess 驱动。
            mediaRepository = runCatching { MediaSessionRepository(app) }
                .onFailure { ModLog.e("GraceBridgeEngine MediaSessionRepository init failed", it) }
                .getOrNull()
            weatherRepository = runCatching { BreezyWeatherRepository(app) }
                .onFailure { ModLog.e("GraceBridgeEngine weather repo init failed", it) }
                .getOrNull()
            started = true
            runCatching { mediaRepository?.setEnabled(true) }
                .onFailure { ModLog.e("GraceBridgeEngine enable media failed", it) }
            ensureListenerBound()
            registerCommandReceiver(app)
            observe()
            startWatchdog()
            // notif7.5：root 保活（best-effort，后台线程，su 被拒即静默停用 ——
            // 防线前移：尽量让 MIUI 不解绑 listener；notif7 自愈链仍是兜底）
            com.homemode4miui.bridge.RootKeepAlive.apply(app)
            // hotfix4 诊断：本日志只落 logcat（tag HomeMode4Miui）——模块进程内
            // XposedBridge.log 不可用，LSPosed modules 日志看不到本进程输出
            ModLog.i("GraceBridgeEngine started (module process bridge live, media=${mediaRepository != null}, weather=${weatherRepository != null})")
        }
    }

    // ---- notif4：命令接收器动态注册 ----

    private var commandReceiverRegistered = false

    /**
     * notif4：命令接收器改为引擎内动态注册。manifest 静态注册在 MIUI/HyperOS 上
     * 会被省电策略拦截（后台模块进程收不到任何命令广播 —— 装机实锤：receiver
     * 打点零输出，连 dropped 日志都没有），而快照方向（模块→宿主）的宿主
     * receiver 一直是动态注册、工作正常。模块进程由 listener service 常驻，
     * start 时机即进程存活期，动态注册覆盖全部命令窗口。
     */
    private fun registerCommandReceiver(app: Context) {
        if (commandReceiverRegistered) return
        commandReceiverRegistered = true
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                app, GraceCommandReceiver(),
                android.content.IntentFilter(GraceBridge.ACTION_COMMAND),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            ModLog.i("GraceBridgeEngine command receiver registered (dynamic)")
        }.onFailure { ModLog.e("GraceBridgeEngine register command receiver failed", it) }
    }

    private fun observe() {
        val engineScope = scope ?: return
        // B6.3：通知快照下发
        engineScope.launch {
            runCatching {
                appNotifications.state.collect { map ->
                    val fingerprint = GraceBridge.encodeNotifications(map)
                    if (fingerprint == lastNotificationsFingerprint) return@collect
                    lastNotificationsFingerprint = fingerprint
                    // notif6：通知快照下发打点（含应用数/条目数，诊断"通知消失"）
                    ModLog.i("GraceBridgeEngine send notifications: apps=${map.size} " +
                        "entries=${map.values.sumOf(List<*>::size)} len=${fingerprint.length}")
                    send(GraceBridge.ACTION_NOTIFICATIONS, fingerprint, null)
                }
            }.onFailure { ModLog.e("GraceBridgeEngine notifications observe failed", it) }
        }
        // B6.1：媒体快照下发（封面仅在 track 身份变化时携带）
        engineScope.launch {
            runCatching {
                mediaRepository?.state?.collectLatest { snapshot ->
                    val nowPlaying = snapshot.nowPlaying ?: run {
                        if (lastMediaFingerprint != "null") {
                            lastMediaFingerprint = "null"
                            send(GraceBridge.ACTION_MEDIA, "null", null)
                        }
                        return@collectLatest
                    }
                    // notif4：语义指纹 = 会话+修订+曲目文本+播放能力+封面对象身份。
                    // 旧 artKey(session:rev:hasArt) 去重让封面只能"搭车"发出 —— 切歌
                    // 时 publish 先清空 artwork（repository 异步加载语义）→ art=0 快照，
                    // 加载完成才有补发；中间任何指纹错位（同 entry 同 rev 的曲目/封面
                    // 变化）都会让宿主停留在上一首封面（装机实锤：暂停才换封面）。
                    // 改为：指纹变化即发送，且总是携带当前封面字节（JPEG 512px 编码
                    // ≈几 ms，频率 ≤ 播放状态变化率，代价可忽略）。
                    val fingerprint = mediaFingerprint(nowPlaying)
                    if (fingerprint == lastMediaFingerprint) return@collectLatest
                    val art = nowPlaying.artwork?.let {
                        withContext(Dispatchers.Default) {
                            GraceBridge.encodeArtwork(it.asAndroidBitmap())
                        }
                    }
                    lastMediaFingerprint = fingerprint
                    send(GraceBridge.ACTION_MEDIA,
                        GraceBridge.encodeMedia(nowPlaying, art).toString(), art)
                }
            }.onFailure { ModLog.e("GraceBridgeEngine media observe failed", it) }
        }
        // B6.2：天气（装载时一次 + 宿主命令触发刷新）
        refreshWeather()
    }

    /** Breezy provider 读取全程 IO + runCatching；结果经广播下发（含不可用状态）。 */
    fun refreshWeather() {
        val engineScope = scope ?: return
        val repository = weatherRepository ?: return
        engineScope.launch {
            // C 组（C4）：weatherLocationId 宿主消费链 —— 设置页把该列写进宿主 Room 后，
            // 快照回带模块进程，这里按首选 location 读取（null = Breezy 首个位置，原版语义）
            val preferred = GraceSettingsSnapshotStore.lastKnownSettings?.weatherLocationId
            runCatching { repository.load(preferred) }
                .onSuccess { state -> publishWeather(state) }
                .onFailure { ModLog.e("GraceBridgeEngine weather load failed", it) }
        }
    }

    private fun publishWeather(state: WeatherState) {
        // 禁用/未安装状态不下发：宿主侧快照保持过期 = 天气组件自然隐藏（降级语义）
        if (state.status == WeatherStatus.Disabled || state.status == WeatherStatus.NotInstalled) {
            if (lastWeatherFingerprint != null) {
                lastWeatherFingerprint = null
                send(GraceBridge.ACTION_WEATHER, "", null)
            }
            return
        }
        val json = GraceBridge.encodeWeather(state)
        if (json == lastWeatherFingerprint) return
        lastWeatherFingerprint = json
        send(GraceBridge.ACTION_WEATHER, json, null)
    }

    private fun send(action: String, json: String, art: ByteArray?) {
        val app = context ?: return
        runCatching {
            val intent = Intent(action).setPackage(GraceBridge.PACKAGE_HOST)
                .putExtra(GraceBridge.EXTRA_JSON, json)
            if (art != null) intent.putExtra(GraceBridge.EXTRA_ART, art)
            app.sendBroadcast(intent)
            // hotfix4 诊断：每次实际发送打点（指纹去重后频率低），logcat 可见
            ModLog.i("GraceBridgeEngine send $action len=${json.length} art=${art?.size ?: 0}")
        }.onFailure { ModLog.e("GraceBridgeEngine send $action failed", it) }
    }

    // ---- 宿主命令（GraceCommandReceiver 主线程转发）----

    fun mediaCommand(sessionId: String, command: MediaCommand): Boolean {
        start(context ?: return false)
        return runCatching {
            when (command) {
                MediaCommand.OpenPlayer -> mediaRepository?.command(sessionId, MediaCommand.OpenPlayer) ?: false
                else -> mediaRepository?.command(sessionId, command) ?: false
            }.also { result ->
                // hotfix8 诊断：控制命令闭环打点（logcat tag HomeMode4Miui）
                ModLog.i("GraceBridgeEngine mediaCommand($sessionId, $command) -> $result")
            }
        }.getOrDefault(false)
    }

    fun dismissMedia(sessionId: String, revision: Long): Boolean =
        runCatching { mediaRepository?.dismiss(sessionId, revision) ?: false }.getOrDefault(false)

    fun openNotification(context: Context, key: String, revision: Long): Boolean {
        start(context)
        return runCatching { appNotifications.open(context, key, revision) }.getOrDefault(false)
    }

    fun dismissNotification(key: String, revision: Long): Boolean {
        start(context ?: return false)
        return runCatching { appNotifications.dismiss(key, revision) }.getOrDefault(false)
    }

    fun onListenerDisconnected() {
        // 服务被系统解绑：快照失效，清指纹以便重连后重发
        lastNotificationsFingerprint = null
        lastMediaFingerprint = null
        // notif7：断连即刻尝试重绑（原逻辑只等宿主挂载触发 —— MIUI 解绑后桌面
        // 可能长期不重新挂载，通知就一直死着）
        ensureListenerBound()
    }

    // ---- notif1：宿主挂载拉取（SNAPSHOT_REFRESH 命令）----

    private var lastRebindAt = 0L
    // notif7：连续 rebind 无效计数（重连成功即清零）——超阈值升级 component toggle
    private var rebindAttempts = 0

    /** notif7：listener 重连登记（MediaNotificationListener.onListenerConnected 调用）。 */
    fun noteListenerConnected() {
        rebindAttempts = 0
        ModLog.i("GraceBridgeEngine listener connected (rebind chain reset)")
    }

    /**
     * notif3：授权在但 listener 服务未绑定 → 请求系统重绑（装机实锤：媒体回调工作、
     * 通知回调零反应 —— QQ 通知到达系统而引擎零动作。媒体链只需授权角色
     * （getActiveSessions），通知链必须服务绑定（onNotificationPosted））。
     * 更新 APK / 系统懒绑定 / MIUI 省电解绑后都可能卡在这个状态。
     * 60s 防抖：rebind 会短暂断开媒体会话监听。
     *
     * notif7 自愈链（装机实锤：MIUI 省电策略在模块退后台后解绑 listener ——
     * 用户重新授权即恢复 = 系统重绑生效；媒体正常全靠 getActiveSessions
     * 只看授权角色不看服务连接）：
     *   ① 断连即刻重绑（onListenerDisconnected 调本方法）；
     *   ② 引擎 30s 看门狗持续检查（startWatchdog）；
     *   ③ 防抖分支补日志（此前静默，日志无迹可查）；
     *   ④ 连续 3 次 rebind 仍连不上 → component toggle（disable→enable，
     *      DONT_KILL_APP）强制 NotificationManagerService 拆绑重绑 —— HyperOS
     *      上 requestRebind 偶发不派发的兜底实锤手段。
     */
    private fun ensureListenerBound() {
        val app = context ?: return
        if (!com.galaxyrio.gracelauncher.data.media.MediaAccess.isGranted(app)) {
            ModLog.i("GraceBridgeEngine ensureListenerBound: not granted")
            return
        }
        if (com.galaxyrio.gracelauncher.data.media.mediaNotifications.state.value.connected) {
            ModLog.i("GraceBridgeEngine ensureListenerBound: already connected")
            rebindAttempts = 0
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRebindAt < 60_000) {
            ModLog.i("GraceBridgeEngine ensureListenerBound: rebind debounced " +
                "(${(now - lastRebindAt) / 1000}s ago, attempts=$rebindAttempts)")
            return
        }
        lastRebindAt = now
        // notif7：升级路径 —— rebind 连续无效时用 component toggle 强制系统重绑。
        // toggle 瞬间授权通道会闪断（enabled 恢复后系统自动重绑并走 onListenerConnected）
        if (rebindAttempts >= 3) {
            rebindAttempts = 0
            runCatching {
                val pm = app.packageManager
                val comp = com.galaxyrio.gracelauncher.data.media.MediaAccess.component(app)
                pm.setComponentEnabledSetting(comp,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(comp,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP)
                ModLog.i("GraceBridgeEngine listener component toggled (rebind escalation)")
            }.onFailure { ModLog.e("GraceBridgeEngine component toggle failed", it) }
            return
        }
        rebindAttempts++
        runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                com.galaxyrio.gracelauncher.data.media.MediaAccess.component(app))
            ModLog.i("GraceBridgeEngine requestRebind (granted but listener unbound, attempts=$rebindAttempts)")
        }.onFailure { ModLog.e("GraceBridgeEngine requestRebind failed", it) }
    }

    /** notif7：看门狗 —— 每 30s 检查 listener 连接态，未连接即走 ensureListenerBound 自愈。 */
    private fun startWatchdog() {
        val engineScope = scope ?: return
        engineScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                runCatching {
                    if (!com.galaxyrio.gracelauncher.data.media.mediaNotifications.state.value.connected) {
                        ModLog.i("GraceBridgeEngine watchdog: listener unbound, healing")
                        ensureListenerBound()
                    }
                }.onFailure { ModLog.e("GraceBridgeEngine watchdog tick failed", it) }
            }
        }
    }

    /**
     * 清指纹并立即重发当前通知/媒体/天气快照。指纹去重是本单例的持久状态：
     * 宿主桌面重启（装新 APK / 进程被杀）会错过重启前的一次性推送 —— 快照只随
     * 底层状态变化重发的话，桌面要等到下一次新通知/切歌才有数据（装机实锤：
     * 通知行不出现、封面迟迟不显示的共同根因）。宿主每次挂载发本命令拉取。
     */
    fun refreshSnapshots() {
        start(context ?: return)
        ensureListenerBound()
        lastNotificationsFingerprint = null
        lastMediaFingerprint = null
        // 通知快照纯 JSON，主线程直发（StateFlow 当前值，即使空也发 —— 宿主据此清行）
        runCatching {
            send(GraceBridge.ACTION_NOTIFICATIONS,
                GraceBridge.encodeNotifications(appNotifications.state.value), null)
        }.onFailure { ModLog.e("GraceBridgeEngine refreshSnapshots notifications failed", it) }
        // 媒体快照含封面编码（Dispatchers.Default），走引擎协程
        scope?.launch { publishMediaCurrent(); refreshWeather() }
        ModLog.i("GraceBridgeEngine refreshSnapshots (pull on host mount)")
    }

    /** 当前媒体快照重发（指纹已清，与 observe 的 collectLatest 同一套语义指纹）。 */
    private suspend fun publishMediaCurrent() {
        val nowPlaying = mediaRepository?.state?.value?.nowPlaying
        if (nowPlaying == null) {
            if (lastMediaFingerprint != "null") {
                lastMediaFingerprint = "null"
                send(GraceBridge.ACTION_MEDIA, "null", null)
            }
            return
        }
        val art = nowPlaying.artwork?.let {
            withContext(Dispatchers.Default) { GraceBridge.encodeArtwork(it.asAndroidBitmap()) }
        }
        lastMediaFingerprint = mediaFingerprint(nowPlaying)
        send(GraceBridge.ACTION_MEDIA, GraceBridge.encodeMedia(nowPlaying, art).toString(), art)
    }

    /** notif4：媒体语义指纹（含封面对象身份；identityHashCode 区分新旧位图）。 */
    private fun mediaFingerprint(nowPlaying: com.galaxyrio.gracelauncher.data.media.NowPlaying): String =
        listOf(nowPlaying.sessionId, nowPlaying.revision, nowPlaying.title, nowPlaying.artist,
            nowPlaying.playing, nowPlaying.canToggle, nowPlaying.canPrevious, nowPlaying.canNext,
            nowPlaying.artwork?.let(System::identityHashCode) ?: 0).joinToString("|")
}
