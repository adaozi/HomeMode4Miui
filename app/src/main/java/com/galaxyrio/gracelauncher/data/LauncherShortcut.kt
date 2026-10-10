/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/data/ShortcutRepository.kt
 *   内的 LauncherShortcut 数据类。P1 仅搬数据模型（shortcut 恒 null 但保持
 *   LauncherApp 模型完整）；repository 本体（LauncherApps 查询/缓存）P4 随
 *   长按弹层一并搬入。
 */
package com.galaxyrio.gracelauncher.data

import android.content.ComponentName
import android.net.Uri
import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap

data class LauncherShortcut(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val activity: ComponentName? = null,
    val user: UserHandle? = null,
    val userSerial: Long? = null,
    val isPrivateSpace: Boolean = false,
) {
    val key: String get() = (userSerial?.let { "profile:$it:" } ?: "") + "shortcut:$packageName/${Uri.encode(id)}"
    fun asApp(owner: LauncherApp): LauncherApp {
        val identity = copy(user = user ?: owner.user, userSerial = userSerial ?: owner.userSerial,
            isPrivateSpace = isPrivateSpace || owner.isPrivateSpace)
        return LauncherApp(componentName = activity ?: owner.componentName, label = label, icon = icon, shortcut = identity,
            user = identity.user, userSerial = identity.userSerial, isPrivateSpace = identity.isPrivateSpace,
            showPrivateIndicator = owner.showPrivateIndicator,
            isWorkProfile = owner.isWorkProfile, showWorkIndicator = owner.showWorkIndicator)
    }
}
