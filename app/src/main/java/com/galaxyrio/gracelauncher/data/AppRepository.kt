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
 */
package com.galaxyrio.gracelauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.galaxyrio.gracelauncher.data.icons.LoadedIconPack
import com.galaxyrio.gracelauncher.data.icons.IconPackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 上游同步 v1.1.8 S8a：工作资料 profile 描述（仅 managed type，MIUI 双开/clone 永不进入）。 */
data class WorkProfile(val user: android.os.UserHandle, val serial: Long)

class AppRepository(
    private val context: Context,
    /** B6.4：图标包仓储（installedPacks 面板查询 + loadApps 装饰共用一个缓存）。 */
    val iconPacks: IconPackRepository = IconPackRepository(context),
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    suspend fun loadApps(iconPackPackages: List<String> = emptyList()): List<LauncherApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = checkNotNull(launcherApps)
        val myUser = Process.myUserHandle()
        // dualapp：枚举 launcher 可见全部 profile 再逐 profile 拉应用列表。MIUI 双开
        // （XSpace user）经 LauncherApps.getProfiles() 下发给默认桌面，主列表只查
        // myUserHandle 时双开应用整体缺席（装机实锤）。返回集合含 managed/clone
        // profile，通用；受限环境抛 SecurityException 时降级为仅主 user。
        val userManager = context.getSystemService(UserManager::class.java)
        val profiles = runCatching { launcher.profiles }.getOrNull() ?: listOf(myUser)
        // B6.4：图标包预载（解析失败 = 排除出 packs，应用回退系统图标）
        val packs = normalizeIconPackOrder(iconPackPackages).mapNotNull { iconPacks.load(it) }
        val apps = profiles.flatMap { profile ->
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
                // getBadgedIcon 对非主 profile 自动叠加系统角标（双开/工作资料标识）
                val icon = runCatching { info.getBadgedIcon(0)?.toBitmap()?.asImageBitmap() }.getOrNull()
                val base = makeApp(component, label, icon, packs)
                if (serial == null) base else base.copy(user = profile, userSerial = serial)
            }
        }
        val result = if (apps.isNotEmpty()) apps else {
            // PM 兜底路径：部分 ROM 对宿主 profile 限制 LauncherApps 时使用。
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0).mapNotNull { resolve ->
                currentCoroutineContext().ensureActive()
                val activity = resolve.activityInfo ?: return@mapNotNull null
                val label = resolve.loadLabel(pm).toString().trim()
                if (label.isBlank()) return@mapNotNull null
                val component = ComponentName(activity.packageName, activity.name)
                val icon = runCatching { resolve.loadIcon(pm)?.toBitmap()?.asImageBitmap() }.getOrNull()
                makeApp(component, label, icon, packs)
            }
        }
        result.sortedWith(LauncherAppOrder)
    }

    /** 原版 makeApp 对齐：包内组件匹配图标 → 包通用 fallback → 系统图标。 */
    private fun makeApp(
        component: ComponentName,
        label: String,
        icon: androidx.compose.ui.graphics.ImageBitmap?,
        packs: List<LoadedIconPack>,
    ): LauncherApp {
        val match = firstMatchingPackIcon(packs) { it.iconFor(component, null) }
        if (match == null) return LauncherApp(componentName = component, label = label, icon = icon)
        return LauncherApp(
            componentName = component, label = label, icon = match.second.bitmap,
            monochromeIcon = match.second.monochrome, monochromeScale = match.second.monochromeScale,
            iconPackPackage = match.first.packageName,
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
                        val icon = runCatching { info.getBadgedIcon(0)?.toBitmap()?.asImageBitmap() }.getOrNull()
                        makeApp(component, label, icon, packs)
                            .copy(user = profile.user, userSerial = profile.serial, isWorkProfile = true)
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
