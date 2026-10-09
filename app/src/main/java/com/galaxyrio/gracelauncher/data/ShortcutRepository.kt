/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 逐字拷贝（P4），原路径 data/ShortcutRepository.kt；LauncherApps 快捷方式查询/缓存/启动。LauncherShortcut 模型 P1 已先行搬入。
 */

package com.galaxyrio.gracelauncher.data

import android.content.Context
import android.content.ComponentName
import android.net.Uri
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// LauncherShortcut 数据类 P1 已搬（data/LauncherShortcut.kt）——此处不再重复声明。

enum class ShortcutStatus { Loading, Ready, DefaultLauncherRequired, Error }

data class ShortcutResult(
    val status: ShortcutStatus,
    val shortcuts: List<LauncherShortcut> = emptyList(),
)

class ShortcutRepository(context: Context) : AutoCloseable {
    private data class QueryKey(val packageName: String, val activity: String, val user: UserHandle,
        val userSerial: Long?, val isPrivateSpace: Boolean)

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val density = context.resources.displayMetrics.densityDpi
    private val user = Process.myUserHandle()
    private val cache = AsyncQueryCache<QueryKey, ShortcutResult>(
        ttlMillis = 30_000,
        shouldCache = { it.status == ShortcutStatus.Ready },
        load = ::queryShortcuts,
    )
    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = invalidate(packageName)
        override fun onPackagesAvailable(packages: Array<out String>, user: UserHandle, replacing: Boolean) {
            packages.forEach(::invalidate)
        }
        override fun onPackagesUnavailable(packages: Array<out String>, user: UserHandle, replacing: Boolean) {
            packages.forEach(::invalidate)
        }
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
            invalidate(packageName)
        }
    }
    private val callbackRegistered = runCatching {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
    }.isSuccess

    fun hasAccess(): Boolean = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    /** No I/O query or placeholder frame when a previously prefetched result is available. */
    fun peek(app: LauncherApp): ShortcutResult? {
        if (!hasAccess()) {
            cache.invalidate()
            return ShortcutResult(ShortcutStatus.DefaultLauncherRequired)
        }
        return cache.peek(app.queryKey())
    }

    suspend fun shortcutsFor(app: LauncherApp): ShortcutResult {
        peek(app)?.let { return it }
        val result = cache.get(app.queryKey())
        // The HOME role can be revoked while a Binder query is running.
        if (!hasAccess()) {
            cache.invalidate()
            return ShortcutResult(ShortcutStatus.DefaultLauncherRequired)
        }
        return result
    }

    /** Bounded by the shared query semaphore; simultaneous touch prefetches are coalesced. */
    suspend fun prefetch(apps: List<LauncherApp>) = coroutineScope {
        if (!hasAccess()) {
            cache.invalidate()
            return@coroutineScope
        }
        apps.distinctBy { it.key }.forEach { app -> launch { shortcutsFor(app) } }
    }

    private fun LauncherApp.queryKey() = QueryKey(packageName, componentName.flattenToString(),
        user ?: this@ShortcutRepository.user, userSerial, isPrivateSpace)

    private fun invalidate(packageName: String) {
        cache.invalidate { it.packageName == packageName }
    }

    private suspend fun queryShortcuts(key: QueryKey): ShortcutResult {
        if (!hasAccess()) return ShortcutResult(ShortcutStatus.DefaultLauncherRequired)
        return try {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(key.packageName)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
                )
            val shortcuts = launcherApps.getShortcuts(query, key.user).orEmpty()
                .filter { it.isEnabled && (it.activity == null || it.activity?.flattenToString() == key.activity) }
                .distinctBy { it.id }
                .sortedWith(compareBy({ !it.isDeclaredInManifest }, { it.rank }))
                .map { info ->
                    LauncherShortcut(
                        id = info.id,
                        packageName = info.`package`,
                        label = info.shortLabel?.toString().orEmpty(),
                        icon = runCatching {
                            launcherApps.getShortcutIconDrawable(info, density)
                                ?.toBitmap(width = 120, height = 120)?.asImageBitmap()
                        }.getOrNull(),
                        activity = info.activity,
                        user = key.user.takeIf { key.userSerial != null }, userSerial = key.userSerial, isPrivateSpace = key.isPrivateSpace,
                    )
                }
            ShortcutResult(ShortcutStatus.Ready, shortcuts)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            ShortcutResult(if (hasAccess()) ShortcutStatus.Error else ShortcutStatus.DefaultLauncherRequired)
        } catch (_: Exception) {
            ShortcutResult(ShortcutStatus.Error)
        }
    }

    fun launch(shortcut: LauncherShortcut, sourceBounds: Rect? = null, options: Bundle? = null): Boolean = runCatching {
        launcherApps.startShortcut(shortcut.packageName, shortcut.id, sourceBounds, options, shortcut.user ?: user)
    }.isSuccess

    /** Preserve dynamic shortcuts once the user adds them to a persistent surface. */
    suspend fun pin(shortcuts: List<LauncherShortcut>) = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext
        shortcuts.groupBy { it.packageName to (it.user ?: user) }.forEach { (target, entries) ->
            val (pkg, targetUser) = target
            runCatching {
                val existing = launcherApps.getShortcuts(LauncherApps.ShortcutQuery().setPackage(pkg)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), targetUser).orEmpty().map { it.id }
                val next = (existing + entries.map { it.id }).distinct()
                if (next.toSet() != existing.toSet()) {
                    launcherApps.pinShortcuts(pkg, next, targetUser)
                    invalidate(pkg)
                }
            }
        }
    }

    override fun close() {
        if (callbackRegistered) launcherApps.unregisterCallback(callback)
        cache.close()
    }
}
