/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/data/AppRepository.kt
 * P3 裁剪版搬运 —— B3.1 应用仓库分层（P1 逻辑自 GraceHomeViewModel 移入）：
 *   - 保留：loadApps（LauncherApps.getActivityList 主路径 + PM queryIntentActivities
 *     兜底 + LauncherAppOrder 排序 + 协程取消检查 ensureActive 对齐原版）、launch
 *     （LauncherApps.startMainActivity，宿主即默认桌面）。
 *   - 裁剪：Private Space 查询（folders 阶段）、isLauncherSettings 排除分支
 *     （本模块 HOME activity 是宿主的，不进列表）。
 * P6（B6.4）：恢复图标包装饰面 —— normalizeIconPackOrder + IconPackRepository.load +
 *   firstMatchingPackIcon(iconFor) 的 makeApp 流程对齐原版；包加载失败自动回退
 *   系统图标（原版 safely/firstMatchingPackIcon 语义），装饰面零阻塞主链路。
 * 上游同步 v1.1.9（S1）：LauncherApp 增 originalIconColor/isSystemApp/isAdaptiveIcon。
 *   模块适配：主色采样挂在【解码阶段】顺带完成 —— 包图标命中的应用不额外解码
 *   系统图标（两段式装载的性能适配优先），originalIconColor 记 null 走主题回退
 *   （与上游 "null = neutral/unavailable" 语义一致）；isAdaptiveIcon 同理解码时回填。
 */
package com.galaxyrio.gracelauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.galaxyrio.gracelauncher.data.icons.IconDominantColor
import com.galaxyrio.gracelauncher.data.icons.LoadedIconPack
import com.galaxyrio.gracelauncher.data.icons.IconPackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 上游同步 v1.1.8 S8a：工作资料 profile 描述（仅 managed type，MIUI 双开/clone 永不进入）。 */
data class WorkProfile(val user: android.os.UserHandle, val serial: Long)

/**
 * 图标解码边长（px）。
 *
 * 模块侧根因修复（2026-10-28 装机实锤）：`Drawable.toBitmap()` 不传尺寸时按 intrinsic
 * 出图 —— `getBadgedIcon(0)`（density=0）返回的 MIUI 主题/自适应图标 intrinsic 可达
 * 300px+（108dp × 440dpi），200+ 应用全量并发解码时峰值位图 ≈ 70MB+（drawable 内部
 * 位图与目标位图并存时翻倍）。重启手机后系统内存紧张 → 整批解码抛 OutOfMemoryError，
 * 被解码器里的 runCatching 吞成 null → 桌面退化为字母占位（收藏区正常、抽屉全占位：
 * phase1 只解收藏子集未触发上限）。
 *
 * 固定 144px：覆盖 48dp@3x 的显示尺寸，内存降到约 1/4，同时绕开 intrinsic 为 -1 时
 * `toBitmap()` 抛 IllegalArgumentException 的坑。
 */
private const val ICON_DECODE_PX = 144

/**
 * 单块解码的应用数：块内共享一个超时窗口（见 AppInventory.withIcons）。
 *
 * 历史：整批共用一个 2500ms 硬超时。冷启动首解（MIUI IconCustomizer 主题图标，
 * 80-150ms/个）200+ 应用必然超时 → 整批图标被判"卡死"丢弃 → 抽屉整屏字母占位。
 * 分块后单块超时只丢该块的图标，其余块照常出图，最坏情况也只是少几个图标。
 */
private const val ICON_CHUNK_SIZE = 24

/** 单块解码超时下限（ms）。 */
private const val ICON_CHUNK_MIN_TIMEOUT_MS = 1_500L

/** 单图标时间预算（ms，按并行度折算出块超时）。冷启动实测 80-150ms/个。 */
private const val ICON_PER_ICON_BUDGET_MS = 150L

/** 固定尺寸解码（见 ICON_DECODE_PX）；任何失败返回 null，由消费方按"图标缺失"处理。 */
private fun android.graphics.drawable.Drawable.toIconBitmap(): android.graphics.Bitmap? = try {
    toBitmap(ICON_DECODE_PX, ICON_DECODE_PX)
} catch (t: Throwable) {
    // 诊断留痕（前 3 次）：图标缺失此前被 runCatching 完全静默 —— OOM / intrinsic 非法
    // 这类"整批失败"在现场看不出原因。tag 与宿主 ModLog 一致，logcat 可直接过滤。
    if (iconDecodeFailureLogBudget.getAndDecrement() > 0) {
        android.util.Log.w("HomeMode4Miui", "icon decode failed: "
            + t.javaClass.simpleName + ": " + t.message)
    }
    null
}

/** 解码失败日志预算（避免 200+ 条失败刷屏，前 3 条足够定位异常类型）。 */
private val iconDecodeFailureLogBudget = java.util.concurrent.atomic.AtomicInteger(3)

/**
 * 上游 1.2.2「返回桌面重新加载应用导致卡顿」的修复基础：单个启动项的变更指纹。
 *
 * 只要指纹不变，就认为该应用的名称/图标来源没有变化 → 可直接复用上一次装载结果（含已解码图标），
 * 无需再次 enumerate + getBadgedIcon。覆盖场景：应用更新（sourceDir/mtime 变化）、
 * 拆分包变化、flags 变化（系统应用/停用）、卸载重装。
 */
internal data class ActivityFingerprint(
    val component: ComponentName,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val flags: Int,
    /** 包更新时间戳（ms）：sourceDir 不变但内容被覆盖安装时兜底。 */
    val lastUpdateTime: Long,
)

class AppRepository(
    private val context: Context,
    /** B6.4：图标包仓储（installedPacks 面板查询 + loadApps 装饰共用一个缓存）。 */
    val iconPacks: IconPackRepository = IconPackRepository(context),
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    /**
     * 装载骨架：枚举返回应用骨架（label/key/包图标就绪，系统图标延后解码）＋按 key 的
     * 惰性解码句柄，消费方用 `withIcons(keys)` 决定解哪些。
     *
     * 2026-11-14 回归单段装载：曾用"先解收藏子集出首屏、再解全量"的两段式换首帧，
     * 但两段各起一批 getBadgedIcon，冷启动时在 IconCustomizer 主题资源锁上互相踩踏，
     * phase2 整批超时被放弃 → 重启后收藏有图标、抽屉全字母占位。现由消费方一次性
     * `withIcons(全量)`：首帧晚 1-2 秒，但抽屉图标不再整批丢失。
     */
    /** 解码产物：系统图标 + 上游 v1.1.9 的 originalIconColor/isAdaptiveIcon 顺带采样。 */
    internal class IconDecode(
        val bitmap: androidx.compose.ui.graphics.ImageBitmap?,
        val originalIconColor: Int?,
        val isAdaptiveIcon: Boolean,
    )

    class AppInventory internal constructor(
        val apps: List<LauncherApp>,
        private val decoders: Map<String, suspend () -> IconDecode?>,
        /** 上游 1.2.2：app.key → 变更指纹（增量装载判据）。 */
        internal val fingerprints: Map<String, ActivityFingerprint> = emptyMap(),
    ) {
        /**
         * 并行解码指定 key 的系统图标并回填（icon 仍为空的项；有包图标的保持包图标）。
         *
         * 超时兜底（2026-10-31 装机实锤：首页收藏图标正常、抽屉整屏字母占位）：
         * MIUI 的 `getBadgedIcon` 走 IconCustomizer（主题图标），在主题资源锁/主线程
         * 繁忙时会**阻塞在 binder 上不可中断**。结构化等待（awaitAll）会因单个卡死
         * 拖死整批，所以改成"非结构化 + 超时"。
         *
         * 2026-11-14 修正超时粒度：原实现是**整批一个 2500ms 窗口**，冷启动 200+ 应用
         * 首解时长本就超过它 → 超时后整批被判卡死，抽屉全字母占位（而收藏子集在另一段
         * 装载里单独拿到新窗口，所以正常）。现改为**按块**解码、每块独立超时：
         * 单块最多丢 ICON_CHUNK_SIZE 个图标，其余块不受牵连。
         */
        /** 需要系统图标解码的 key 集合（包图标命中的项不在其中）。 */
        internal fun decoderKeys(): Set<String> = decoders.keys

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        suspend fun withIcons(keys: Set<String>, parallelism: Int = 4): List<LauncherApp> {
            if (keys.isEmpty() || decoders.isEmpty()) return apps
            val targets = decoders.filterKeys { it in keys }.toList()
            if (targets.isEmpty()) return apps
            val decoded = HashMap<String, IconDecode>(targets.size)
            var abandoned = 0
            targets.chunked(ICON_CHUNK_SIZE).forEach { chunk ->
                // 每块独立 scope + 独立限流：本块的卡死协程只吃满本块的并行额度
                val scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + Dispatchers.IO.limitedParallelism(parallelism))
                val jobs = chunk.map { (key, decode) ->
                    key to scope.async { runCatching { decode() }.getOrNull() }
                }
                val budget = maxOf(ICON_CHUNK_MIN_TIMEOUT_MS,
                    chunk.size.toLong() * ICON_PER_ICON_BUDGET_MS / parallelism.coerceAtLeast(1))
                val done: List<Pair<String, IconDecode?>> =
                    kotlinx.coroutines.withTimeoutOrNull(budget) {
                        jobs.map { (key, deferred) -> key to deferred.await() }
                    } ?: jobs.mapNotNull { (key, deferred) ->
                        // 超时：只收本块已完成的结果，卡住的协程随 scope.cancel 放弃
                        if (!deferred.isCompleted) null
                        else runCatching { key to deferred.getCompleted() }.getOrNull()
                    }
                done.forEach { (key, icon) -> if (icon != null) decoded[key] = icon }
                abandoned += chunk.size - done.size
                scope.cancel()
            }
            if (abandoned > 0) {
                android.util.Log.w("HomeMode4Miui", "icon decode abandoned=" + abandoned + "/"
                    + targets.size + " (stuck decoders on MIUI IconCustomizer)")
            }
            if (decoded.isEmpty()) return apps
            return apps.map { app ->
                val icon = decoded[app.key] ?: return@map app
                if (app.icon != null || icon == null) app
                else app.copy(icon = icon.bitmap, originalIconColor = icon.originalIconColor,
                    isAdaptiveIcon = icon.isAdaptiveIcon)
            }
        }
    }

    suspend fun enumerateApps(iconPackPackages: List<String> = emptyList()): AppInventory = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = checkNotNull(launcherApps)
        val myUser = Process.myUserHandle()
        // dualapp：枚举 launcher 可见全部 profile 再逐 profile 拉应用列表。MIUI 双开
        // （XSpace user）经 LauncherApps.getProfiles() 下发给默认桌面，主列表只查
        // myUserHandle 时双开应用整体缺席（装机实锤）。返回集合含 managed/clone
        // profile，通用；受限环境抛 SecurityException 时降级为仅主 user。
        val userManager = context.getSystemService(UserManager::class.java)
        val profiles = runCatching { launcher.profiles }.getOrNull() ?: listOf(myUser)
        // 上游 1.2.4 readable 语义（1.2.2 工作资料修复）：静默 / 未解锁的受管 profile 不暴露应用。
        // 只读公开 UserManager 状态，不改 profile 发现机制 —— MIUI 双开（clone）语义完全不动。
        val visibleProfiles = profiles.filter { profile ->
            if (profile == myUser) return@filter true
            val quiet = runCatching { userManager?.isQuietModeEnabled(profile) }.getOrNull()
            val unlocked = runCatching { userManager?.isUserUnlocked(profile) }.getOrNull()
            quiet != true && unlocked != false
        }
        // B6.4：图标包预载（解析失败 = 排除出 packs，应用回退系统图标）
        val packs = normalizeIconPackOrder(iconPackPackages).mapNotNull { iconPacks.load(it) }
        class Entry(val app: LauncherApp, val fp: ActivityFingerprint, val decode: (suspend () -> IconDecode?)?)
        // 包更新时间戳按包去重查询（同一包多个 launcher activity 只查一次）
        val updateTimes = HashMap<String, Long>()
        fun lastUpdateOf(pkg: String): Long = updateTimes.getOrPut(pkg) {
            runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        }
        fun fingerprintOf(component: ComponentName, info: ApplicationInfo): ActivityFingerprint =
            ActivityFingerprint(component, info.sourceDir, info.splitSourceDirs.orEmpty().toList(),
                info.flags, lastUpdateOf(component.packageName))
        val entries = visibleProfiles.flatMap { profile ->
            // 单 profile 失败不拖垮整体（XSpace 受限/未就绪时 getActivityList 抛
            // SecurityException，降级为该 profile 无应用）
            val items = runCatching { launcher.getActivityList(null, profile) }.getOrDefault(emptyList())
            // 主 user 不带 serial → key 维持无前缀（收藏/隐藏等既有数据全兼容）；
            // 其他 profile 带上 serial → key = profile:<serial>:<cmp>，与主应用天然区分
            //（同组件双开若不带 serial，key 相撞会互相吞掉）。
            val serial = if (profile == myUser) null else userManager?.let {
                runCatching { it.getSerialNumberForUser(profile) }.getOrNull()
            }
            items.mapNotNull { info ->
                currentCoroutineContext().ensureActive()
                val component = info.componentName ?: return@mapNotNull null
                val label = info.label.toString().trim().ifBlank { return@mapNotNull null }
                // getBadgedIcon 对非主 profile 自动叠加系统角标（双开/工作资料标识）——
                // 两段式装载下延后到 withIcons 解码（角标语义不变，解码时同样走 getBadgedIcon）。
                val base = makeApp(component, label, null, packs, info.applicationInfo.flags)
                val app = if (serial == null) base else base.copy(user = profile, userSerial = serial)
                // 包图标命中的应用不需要系统图标解码（原版语义包图标直接顶替系统图标）；
                // originalIconColor/isAdaptiveIcon 在解码时顺带采样（v1.1.9，见类头注）。
                Entry(app, fingerprintOf(component, info.applicationInfo), if (app.icon == null) {
                    {
                        val drawable = runCatching { info.getBadgedIcon(0) }.getOrNull()
                        val bitmap = drawable?.toIconBitmap()?.asImageBitmap()
                        IconDecode(bitmap,
                            bitmap?.let { runCatching { IconDominantColor.of(it.asAndroidBitmap()) }.getOrNull() },
                            drawable is AdaptiveIconDrawable)
                    }
                } else null)
            }
        }
        val result = if (entries.isNotEmpty()) entries else {
            // PM 兜底路径：部分 ROM 对宿主 profile 限制 LauncherApps 时使用。
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0).mapNotNull { resolve ->
                currentCoroutineContext().ensureActive()
                val activity = resolve.activityInfo ?: return@mapNotNull null
                val label = resolve.loadLabel(pm).toString().trim()
                if (label.isBlank()) return@mapNotNull null
                val component = ComponentName(activity.packageName, activity.name)
                val app = makeApp(component, label, null, packs, activity.applicationInfo.flags)
                Entry(app, fingerprintOf(component, activity.applicationInfo)) {
                    val drawable = runCatching { resolve.loadIcon(pm) }.getOrNull()
                    val bitmap = drawable?.toIconBitmap()?.asImageBitmap()
                    IconDecode(bitmap,
                        bitmap?.let { runCatching { IconDominantColor.of(it.asAndroidBitmap()) }.getOrNull() },
                        drawable is AdaptiveIconDrawable)
                }
            }
        }
        AppInventory(
            apps = result.map(Entry::app).sortedWith(LauncherAppOrder),
            decoders = result.mapNotNull { e -> e.decode?.let { e.app.key to it } }.toMap(),
            fingerprints = result.associate { it.app.key to it.fp },
        )
    }

    /**
     * 增量装载（上游 1.2.2「返回桌面重新加载应用导致的卡顿」修复的模块实现）。
     *
     * 三种路径：
     *  ① 快路径：图标包集合与全部应用指纹都没变 → 直接返回上次结果（已含解码图标），
     *     零枚举成本以外的开销，桌面/抽屉切回时首帧即出图；
     *  ② 增量：只对新增/变化的 key 走 getBadgedIcon 解码，其余复用旧对象（含采样结果）；
     *  ③ 全量：首轮或图标包变更 → 全部需要系统解码的项重解（包图标项由 makeApp 直接给新图标）。
     */
    suspend fun loadApps(iconPackPackages: List<String> = emptyList()): List<LauncherApp> =
        loadAppsIncremental(iconPackPackages).apps

    /**
     * 增量装载的结果：除最终列表外还带回本次的 [inventory]（同一批枚举产物），
     * 供调用方对"图标缺失"做原地重试 —— 不必再次枚举（枚举是 200+ 次 binder 调用）。
     */
    class LoadOutcome(
        val apps: List<LauncherApp>,
        internal val inventory: AppInventory,
        /** 本次复用的上次装载项数（诊断用）。 */
        val reused: Int,
        /** 本次实际走系统图标解码的项数（诊断用）。 */
        val decoded: Int,
    )

    /**
     * 增量装载（上游 1.2.2「返回桌面重新加载应用导致的卡顿」修复的模块实现）。
     *
     * 三种路径：
     *  ① 快路径：图标包集合与全部应用指纹都没变 → 直接返回上次结果（已含解码图标），
     *     桌面/抽屉切回时首帧即出图；
     *  ② 增量：只对新增/变化的 key 走 getBadgedIcon 解码，其余复用旧对象（含采样结果）；
     *  ③ 全量：首轮或图标包变更 → 全部需要系统解码的项重解（包图标项由 makeApp 直接给新图标）。
     */
    suspend fun loadAppsIncremental(iconPackPackages: List<String> = emptyList()): LoadOutcome {
        val packs = normalizeIconPackOrder(iconPackPackages)
        val inventory = enumerateApps(iconPackPackages)
        val previous = cachedInventory
        if (previous != null && previous.packs == packs && previous.apps.isNotEmpty() &&
            previous.fingerprints == inventory.fingerprints) {
            return LoadOutcome(previous.apps, inventory, reused = previous.apps.size, decoded = 0)
        }
        val fullReload = previous == null || previous.packs != packs
        val changed: Set<String> = if (fullReload) {
            inventory.decoderKeys()
        } else {
            val old = previous.fingerprints
            inventory.fingerprints.keys.filterTo(HashSet()) { old[it] != inventory.fingerprints[it] }
        }
        val decoded = inventory.withIcons(if (fullReload) inventory.decoderKeys() else changed)
        val reusable = if (fullReload) emptyMap() else previous.apps.associateBy { it.key }
        val merged = if (fullReload) decoded else decoded.map { app ->
            if (app.key in changed) app else reusable[app.key] ?: app
        }
        cachedInventory = CachedInventory(packs, inventory.fingerprints, merged)
        return LoadOutcome(merged, inventory,
            reused = if (fullReload) 0 else merged.size - changed.size.coerceAtMost(merged.size),
            decoded = if (fullReload) inventory.decoderKeys().size else changed.size)
    }

    /** 单个已装载结果的缓存（指纹 + 已解码列表 + 图标包签名）。 */
    private data class CachedInventory(
        val packs: List<String>,
        val fingerprints: Map<String, ActivityFingerprint>,
        val apps: List<LauncherApp>,
    )

    @Volatile
    private var cachedInventory: CachedInventory? = null

    /**
     * 包变化（安装/更新/卸载/启用停用）：只让受影响的应用失效，其余继续复用。
     * key 形态覆盖：`<pkg>/<cls>`、`profile:<serial>:<pkg>/<cls>`。
     */
    fun invalidateApps(packages: Collection<String>) {
        val previous = cachedInventory ?: return
        if (packages.isEmpty()) return
        fun affected(key: String) = packages.any { key.contains("$it/") }
        cachedInventory = previous.copy(
            fingerprints = previous.fingerprints.filterKeys { !affected(it) },
            apps = previous.apps.filterNot { it.packageName in packages },
        )
    }

    /** 显式恢复用：整表失效（换 profile / 用户手动刷新）。 */
    fun invalidateAll() = run { cachedInventory = null }

    /** 原版 makeApp 对齐：包内组件匹配图标 → 包通用 fallback → 系统图标。 */
    private fun makeApp(
        component: ComponentName,
        label: String,
        icon: androidx.compose.ui.graphics.ImageBitmap?,
        packs: List<LoadedIconPack>,
        flags: Int = 0,
    ): LauncherApp {
        // v1.1.9：isSystemApp 从 ApplicationInfo.flags 直取；originalIconColor/isAdaptiveIcon
        // 由解码阶段回填（包图标命中路径保持 null/false，不额外解码）。
        val match = firstMatchingPackIcon(packs) { it.iconFor(component, null) }
        if (match == null) return LauncherApp(componentName = component, label = label, icon = icon,
            isSystemApp = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        return LauncherApp(
            componentName = component, label = label, icon = match.second.bitmap,
            monochromeIcon = match.second.monochrome, monochromeScale = match.second.monochromeScale,
            iconPackPackage = match.first.packageName,
            isSystemApp = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
        )
    }

    /** 原版 AppRepository.launch 对齐：Launcher API 保留目标 task 语义，Android 出启动动画。 */
    /**
     * 上游同步 v1.1.8 S8a：工作资料 profile 枚举。
     * 与上游差异：严格只认 USER_TYPE_PROFILE_MANAGED（去掉上游 API<35 的放行分支）——
     * MIUI 双开（clone/XSpace）必须留在主列表（dualapp 装机实锤语义），永不进工作文件夹。
     */
    fun workProfiles(): List<WorkProfile> {
        val launcher = launcherApps ?: return emptyList()
        val users = context.getSystemService(android.os.UserManager::class.java) ?: return emptyList()
        return runCatching {
            launcher.profiles.filter { user ->
                user != android.os.Process.myUserHandle() &&
                    launcher.getLauncherUserInfo(user)?.userType == android.os.UserManager.USER_TYPE_PROFILE_MANAGED
            }.mapNotNull { user ->
                users.getSerialNumberForUser(user).takeIf { it >= 0 }?.let { WorkProfile(user, it) }
            }
        }.getOrDefault(emptyList())
    }

    /** 上游同步 v1.1.8 S8a：逐工作资料拉应用（isWorkProfile 标记 + serial key 前缀，与主列表天然区分）。 */
    suspend fun loadWorkApps(profiles: List<WorkProfile>, iconPackPackages: List<String> = emptyList()): List<LauncherApp> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val packs = normalizeIconPackOrder(iconPackPackages).mapNotNull { iconPacks.load(it) }
            val launcher = checkNotNull(launcherApps)
            profiles.flatMap { profile ->
                // Quiet/removed profiles must not prevent the personal app inventory from loading.
                try {
                    launcher.getActivityList(null, profile.user).mapNotNull { info ->
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val component = info.componentName ?: return@mapNotNull null
                        val label = info.label.toString().trim().ifBlank { return@mapNotNull null }
                        val drawable = runCatching { info.getBadgedIcon(0) }.getOrNull()
                        val icon = drawable?.toIconBitmap()?.asImageBitmap()
                        makeApp(component, label, icon, packs, info.applicationInfo.flags)
                            .copy(user = profile.user, userSerial = profile.serial, isWorkProfile = true,
                                originalIconColor = icon?.let {
                                    runCatching { IconDominantColor.of(it.asAndroidBitmap()) }.getOrNull()
                                },
                                isAdaptiveIcon = drawable is AdaptiveIconDrawable)
                    }
                } catch (error: SecurityException) {
                    emptyList()
                } catch (error: IllegalStateException) {
                    emptyList()
                }
            }.distinctBy(LauncherApp::key).sortedWith(LauncherAppOrder)
        }

    fun launch(
        app: LauncherApp,
        sourceBounds: Rect? = null,
        options: Bundle? = null,
    ): Boolean = runCatching {
        checkNotNull(launcherApps).startMainActivity(
            app.componentName,
            app.user ?: Process.myUserHandle(),
            sourceBounds,
            options,
        )
    }.isSuccess
}
