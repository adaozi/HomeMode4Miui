package com.galaxyrio.gracelauncher.platform

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** Read the system's current choice; shortcut access and saved preferences are not HOME ownership. */
internal object DefaultHome {
    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                context.getSystemService(RoleManager::class.java)
                    ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }
                    ?.isRoleHeld(RoleManager.ROLE_HOME)
            }.getOrNull()?.let { return it }
        }
        // Android 9 (and devices without the HOME role): resolve the unrestricted
        // HOME intent. A resolver/no selection must not count as our launcher.
        return runCatching {
            @Suppress("DEPRECATION")
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            home?.activityInfo?.packageName == context.packageName
        }.getOrDefault(false)
    }

    fun requestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                context.getSystemService(RoleManager::class.java)
                    ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) && !it.isRoleHeld(RoleManager.ROLE_HOME) }
                    ?.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }.getOrNull()?.let { return it }
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }
}
