/*
 * keep1：进程保活入口服务 —— 新建文件（非搬运）。运行在【模块 App 进程】。
 *
 * 背景（装机实锤）：第四桌面可启动，但模块进程死在后台后无人拉起 —— 通知/
 * 媒体/天气快照全链依赖本进程（listener + 引擎），进程死 = 桌面侧全部降级。
 * notif7 自愈链只能在"进程还活着"的前提下自救，本文件解决"进程本身被杀"：
 *
 *   1. 宿主拉起（**唯一**主链路，root-free）：com.miui.home 几乎常驻前台
 *      （launcher），前台应用 startService 不受后台限制 —— 桌面挂载
 *      （HomeController.attach）与首页交互（toggle receiver）时显式拉起本服务，
 *      进程随之创建。
 *   2. RootKeepAlive 辅助手段（Doze 白名单 / PowerKeeper 策略 / 自启动 provider）
 *      —— 降低被 MIUI 杀掉的概率，不负责拉起。
 *
 * ⚠️ 禁止区（2026-11-12 用户设备实锤）：**绝不**再用 Magisk/KSU service.d、
 *    post-fs-data.d、init.d 写开机脚本拉起本服务。旧实现（while true 轮询 pidof）
 *    在 KSU service 阶段被串行等待，直接卡死启动流程 → 整机反复重启，且脚本位于
 *    /data/adb 不受模块卸载管理，卸载后仍持续破坏用户环境。详见
 *    RootKeepAlive.removeLegacyBootScript() 注释。
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
