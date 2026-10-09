/*
 * keep1：进程保活入口服务 —— 新建文件（非搬运）。运行在【模块 App 进程】。
 *
 * 背景（装机实锤）：第四桌面可启动，但模块进程死在后台后无人拉起 —— 通知/
 * 媒体/天气快照全链依赖本进程（listener + 引擎），进程死 = 桌面侧全部降级。
 * notif7 自愈链只能在"进程还活着"的前提下自救，本文件解决"进程本身被杀"：
 *
 *   1. 宿主拉起（主链路，root-free）：com.miui.home 几乎常驻前台（launcher），
 *      前台应用 startService 不受后台限制 —— 桌面挂载（HomeController.attach）
 *      与首页交互（toggle receiver）时显式拉起本服务，进程随之创建。
 *   2. Magisk service.d 开机守护（root 增强，RootKeepAlive 安装）：开机后
 *      每 30s 检查 pidof，进程不在则 root 身份 am startservice 拉起。
 *
 * 引擎 GraceBridgeEngine.start 幂等（started 标志），onCreate 与 NLS
 * onListenerConnected 重复触发安全 —— 谁先到谁初始化，后者 no-op。
 */
package com.homemode4miui.bridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.homemode4miui.util.ModLog

class GraceKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        ModLog.i("GraceKeepAliveService: process woken, starting engine")
        startEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startEngine(this)
        // STICKY：系统回收后若内存允许会尝试重建（MIUI 上不指望，仅白赚一层）
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val PKG = "com.homemode4miui"

        /** 宿主/脚本跨包拉起入口（引擎幂等，重复调用安全）。 */
        @JvmStatic
        fun startEngine(context: Context) {
            runCatching { GraceBridgeEngine.start(context.applicationContext) }
                .onFailure { ModLog.e("GraceKeepAliveService: engine start failed", it) }
        }

        /**
         * 宿主侧拉起（MainHook / HomeController 调用，宿主进程运行）。
         * 60s 防抖：launcher 事件频繁，service 已活时 onStartCommand 纯开销。
         * 调用方是前台桌面，普通 startService 合法；失败静默（自愈链兜底）。
         */
        @JvmStatic
        fun pokeFromHost(hostContext: Context) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPokeAt < 60_000L) return
            lastPokeAt = now
            runCatching {
                hostContext.startService(Intent().setClassName(PKG, "$PKG.bridge.GraceKeepAliveService"))
                com.homemode4miui.util.ModLog.i("HostWake: keepalive service start requested")
            }.onFailure {
                com.homemode4miui.util.ModLog.e("HostWake: startService failed", it)
            }
        }

        @Volatile private var lastPokeAt = 0L
    }
}
