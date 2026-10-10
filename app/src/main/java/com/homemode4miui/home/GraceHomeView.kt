/*
 * GraceLauncher（Jetpack Compose）宿主接桩 —— P0（PORTING_GraceLauncher.md B0.1）
 *
 * 背景：宿主 com.miui.home 进程没有 Compose 运行时，也没有 ComponentActivity。
 * ComposeView 正常工作需要三个 ViewTree Owner，全部由本类自实现并挂到视图树：
 *   - LifecycleOwner          → androidx.lifecycle.ViewTreeLifecycleOwner
 *   - ViewModelStoreOwner     → androidx.lifecycle.ViewTreeViewModelStoreOwner
 *   - SavedStateRegistryOwner → androidx.savedstate.ViewTreeSavedStateRegistryOwner
 *
 * 【崩溃循环根因（真机 5.39.40 已复现 18 次，见 LSPosed 日志 2026-10-08）】
 *   ComposeView 默认经 windowRecomposer() 创建 Recomposer，其 LifecycleAware 工厂
 *   从宿主【窗口根视图】(StrictFrameLayout) 向上找 ViewTreeLifecycleOwner —— 宿主
 *   没有，直接抛 IllegalStateException。而组合创建发生在 Launcher 首帧 traversal 的
 *   Choreographer 回调里（MIUI 延迟到 performTraversals 才 attach 整棵树），该异常在
 *   所有 hook 之外 → 进程死亡 → 模式仍开 → 重启再挂载 → 死循环。
 *   修复：本类自建 Recomposer 经 setParentCompositionContext 注入，
 *   resolveParentCompositionContext 命中后根本不会走 windowRecomposer()。
 *
 * 崩溃循环防护（两道铁律：桌面零崩溃）：
 *   1. 幂等：onHomeAttached 会被复用路径重复调用（HomeController.attach 的 existing
 *      分支，每次 Launcher.onResume 都进）→ 只装一份内容，不重复挂 ComposeView。
 *   2. 半死视图摘除：addView 内组合一旦失败，立即把 ComposeView 摘出视图树，
 *      残缺组合绝不进入宿主帧管线。
 *   3. 持久化熔断：挂载前在宿主 SP 落 pending，首帧【绘制完成后】心跳清除（GraceHomeRoot
 *      的 withFrameNanos 之后回调）。若进程在心跳前死亡（帧阶段异常无法捕获），
 *      pending 跨重启存活 → 失败计数，连续超过阈值永久降级为占位视图，桌面保可用。
 *      新版本 APK 安装后熔断自动清零重新放开。
 */
package com.homemode4miui.home

import android.app.Activity
import android.widget.FrameLayout
import androidx.compose.runtime.DefaultMonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.homemode4miui.ModePrefs
import com.homemode4miui.grace.GraceHomeRoot
import com.homemode4miui.grace.GraceHomeViewModel
import com.homemode4miui.util.ModLog
// ModuleResourceBridge 同包（com.homemode4miui.home），无需 import
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GraceHomeView(launcher: Activity) :
    HomeView(launcher), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val hostViewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = hostViewModelStore

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var composeView: ComposeView? = null
    private var fallbackView: PlaceholderHomeView? = null
    private var recomposerScope: CoroutineScope? = null
    /** P1：手动构造的 ViewModel（B1.3，绕过 viewModels() 委托）；生命周期随本视图 */
    private var homeViewModel: GraceHomeViewModel? = null
    /** onHomeAttached 幂等闸：复用路径（onResume）会重复进入，只装一份内容 */
    private var contentInstalled = false
    private var heartbeatReceived = false

    companion object {
        /**
         * perf2（冷启动专项）：宿主 versionCode 进程级缓存。getPackageInfo 是
         * 一次同步 binder 往返，挂在 Launcher.onCreate 主线程；versionCode 在
         * 进程生命周期内不变（APK 更新必换进程），缓存后复用路径（每次
         * Launcher.onResume 的 attach）零 binder 开销。
         */
        @Volatile private var sHostVersionCode: Int = -1
    }

    init {
        try {
            // 组合期按视图树向上解析三 Owner（CompositionLocals 从 ComposeView 向上查，命中本类）
            setViewTreeLifecycleOwner(this)
            setViewTreeViewModelStoreOwner(this)
            setViewTreeSavedStateRegistryOwner(this)
            savedStateController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        } catch (t: Throwable) {
            ModLog.e("GraceHomeView init failed", t)
        }
    }

    override fun onHomeAttached(launcher: Activity) {
        // ---- 复用路径幂等：只把生命周期推回 RESUMED，绝不重复挂内容 ----
        // perf3：复用分支同时承担"退后台恢复"—— Launcher.onResume 必经此处。
        // 此前只处理 STARTED→RESUMED；新增 CREATED（onHomePaused 推到 STOP 后）
        // 的 ON_START 补链。状态已 RESUMED 时两次 handleLifecycleEvent 均为 no-op。
        if (contentInstalled) {
            try {
                if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                }
                if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                }
            } catch (t: Throwable) {
                ModLog.e("GraceHomeView re-attach sync failed", t)
            }
            return
        }
        contentInstalled = true
        heartbeatReceived = false

        // ---- 持久化熔断检查（版本变更自动清零；连崩超阈值永久降级）----
        // perf2：versionCode 走进程级缓存（同进程恒定），复用路径零 binder 开销
        val versionCode = runCatching {
            sHostVersionCode.takeIf { it > 0 } ?: run {
                val info = launcher.packageManager.getPackageInfo(launcher.packageName, 0)
                @Suppress("DEPRECATION")
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
                sHostVersionCode = code
                code
            }
        }.getOrDefault(0)
        val failures = ModePrefs.onGraceComposeMountAttempt(launcher, versionCode)
        if (ModePrefs.isGraceComposeDead(launcher) || failures > ModePrefs.GRACE_MAX_FAILURES) {
            ModLog.e("GraceHomeView degrade: compose mount failures=$failures"
                    + " -> permanent placeholder fallback (desktop safety first)", null)
            ModePrefs.markGraceComposeDead(launcher)
            installFallback(launcher)
            return
        }

        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            // 挂载前落 pending：进程若在心跳前死亡，此标记跨重启存活 → 熔断计数
            ModePrefs.markGraceComposePending(launcher)

            // P1（B1.3）：宿主 Activity 不是 ComponentActivity，viewModels() 委托不可用，
            // 手动构造并以参数传入 Composable；shutdown 在 onHomeDetached 显式调用。
            val viewModel = GraceHomeViewModel(launcher.applicationContext as android.app.Application)
            homeViewModel = viewModel

            val view = ComposeView(ModuleResourceBridge.wrap(launcher)).apply {
                // 根因修复：自托管 Recomposer。绝不触发 windowRecomposer()
                // （其工厂从宿主窗口根视图找 LifecycleOwner，必抛）
                setParentCompositionContext(createRecomposer())
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { GraceHomeRoot(viewModel, onComposeAlive = { onHeartbeat(launcher) }, hostActivity = mLauncher) }
            }
            composeView = view
            try {
                addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            } catch (t: Throwable) {
                // 半死视图立即摘除：残缺组合进入宿主帧管线必杀进程
                try {
                    removeView(view)
                } catch (ignored: Throwable) {
                }
                composeView = null
                throw t
            }

            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            ModLog.i("GraceHomeView attached: Compose P0 skeleton live (self-hosted recomposer)")
        } catch (t: Throwable) {
            ModLog.e("GraceHomeView onHomeAttached failed", t)
            onMountFailed(launcher)
        }
    }

    /**
     * perf3（功耗专项）：Launcher.onPause 同步 —— 自定义桌面退后台。
     * 生命周期推到 STOPPED：HomeScreen / TimeText 的时钟循环（repeatOnLifecycle
     * STARTED 包裹）整体挂起、widget 宿主 stopListening（rememberWidgetHost 观察
     * ON_START/ON_STOP）—— 桌面在后台期间零周期唤醒。回桌面由 onResume 路径的
     * onHomeAttached 复用分支推回 RESUMED（CREATED 分支补 ON_START）。
     * hook 5b 未命中的宿主版本上本方法不会被调用，行为等同旧版（安全降级）。
     */
    override fun onHomePaused() {
        try {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        } catch (t: Throwable) {
            ModLog.e("GraceHomeView onHomePaused failed", t)
        }
    }

    /**
     * 自托管 Recomposer：主线程调度器 + Choreographer 驱动的默认帧时钟，
     * launch runRecomposeAndApplyChanges 驱动重组；卸载时 cancel 作用域即取消 Recomposer。
     */
    private fun createRecomposer(): Recomposer {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + DefaultMonotonicFrameClock)
        val rec = Recomposer(scope.coroutineContext)
        scope.launch { rec.runRecomposeAndApplyChanges() }
        recomposerScope = scope
        return rec
    }

    /** 首帧绘制完成后的心跳（GraceHomeRoot 在 withFrameNanos 之后回调） */
    private fun onHeartbeat(launcher: Activity) {
        if (heartbeatReceived) return
        heartbeatReceived = true
        ModePrefs.onGraceComposeHeartbeat(launcher)
        ModLog.i("GraceHomeView heartbeat: first frame drawn OK, compose alive")
    }

    /** 挂载链路内任何失败：摘除 Compose 视图 + 计入熔断 + 降级占位视图 */
    private fun onMountFailed(launcher: Activity) {
        try {
            composeView?.let { v ->
                try {
                    removeView(v)
                } catch (ignored: Throwable) {
                }
            }
            composeView = null
            releaseRecomposer()
            homeViewModel?.shutdown()
            homeViewModel = null
        } catch (t: Throwable) {
            ModLog.e("GraceHomeView cleanup after failure failed", t)
        }
        ModePrefs.onGraceComposeMountFailure(launcher)
        installFallback(launcher)
    }

    /** 降级兜底：原生占位视图（纯 View，零 Compose 依赖），保证桌面可用 */
    private fun installFallback(launcher: Activity) {
        if (fallbackView != null) return
        try {
            val fb = PlaceholderHomeView(launcher)
            fallbackView = fb
            addView(
                fb,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            fb.onHomeAttached(launcher)
            ModLog.i("GraceHomeView fallback placeholder installed")
        } catch (t: Throwable) {
            ModLog.e("GraceHomeView installFallback failed", t)
        }
    }

    private fun releaseRecomposer() {
        recomposerScope?.cancel()
        recomposerScope = null
    }

    override fun onHomeDetached() {
        try {
            // 正常卸载（模式切换/桌面重建）清 pending，防止熔断误计
            if (!heartbeatReceived) {
                ModePrefs.clearGraceComposePending(mLauncher)
            }
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            composeView?.disposeComposition()
            composeView = null
            releaseRecomposer()
            // P1：手动构造的 ViewModel 不走 onCleared()，receiver/协程在此显式回收
            homeViewModel?.shutdown()
            homeViewModel = null
            hostViewModelStore.clear()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            ModLog.i("GraceHomeView detached: composition disposed, recomposer cancelled, store cleared")
        } catch (t: Throwable) {
            ModLog.e("GraceHomeView onHomeDetached failed", t)
        }
    }

    /**
     * P2：Home 键回桌面（底座在 Launcher.onNewIntent 主意图时回调）——转发给
     * 路由层复位两态 UI（关抽屉/搜索），对齐原版 returnHomeRequests。
     */
    override fun onHomeIntent() {
        try {
            homeViewModel?.requestReturnHome()
        } catch (t: Throwable) {
            ModLog.e("GraceHomeView onHomeIntent failed", t)
        }
    }
}
