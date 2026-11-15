package com.galaxyrio.gracelauncher.data

import android.content.Context
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

enum class LauncherProfileType { Personal, Work, Private, Clone, Other, Unavailable }

data class LauncherProfile(
    val user: UserHandle,
    val serial: Long,
    val type: LauncherProfileType,
    val quietMode: Boolean? = null,
    val unlocked: Boolean? = null,
) {
    val isPublic: Boolean get() = type != LauncherProfileType.Private && type != LauncherProfileType.Unavailable
}

/** Partial discovery can be used immediately; the repository retries only the missing sections. */
data class LauncherProfileSnapshot(
    val profiles: List<LauncherProfile>,
    val complete: Boolean,
    /** Optional cheap inventory for detecting OEM package changes without callbacks. */
    val launcherComponents: Map<UserHandle, Set<ComponentName>> = emptyMap(),
    /** Missing users are authoritative only when both enumeration APIs succeeded. */
    val discoveryComplete: Boolean = true,
)

/**
 * Discover users before loading their activities, as Launcher3/Lawnchair does. A profile's
 * presentation is independent of its identity: an OEM clone is still a separate installation
 * even when the platform cannot identify its exact type.
 */
class LauncherProfiles(context: Context) {
    private val appContext = context.applicationContext
    private val users = appContext.getSystemService(UserManager::class.java)
    private val launcher = appContext.getSystemService(LauncherApps::class.java)
    private val packageManager = appContext.packageManager
    private val knownTypes by lazy {
        synchronized(typeStores) {
            val file = File(appContext.noBackupFilesDir, "launcher-profile-types.json")
            typeStores.getOrPut(file.absolutePath) { ProfileTypeStore(file) }
        }
    }

    /** Call on IO: profile discovery uses system services and may remember a newly observed type. */
    fun snapshot(): LauncherProfileSnapshot {
        val current = Process.myUserHandle()
        val handles = linkedSetOf(current)
        var complete = true
        var identitiesComplete = true

        // UserManager is important for OEM clone profiles. LauncherApps additionally exposes
        // hidden profiles while this application holds the HOME role and required permission.
        val userProfiles = runCatching { users?.userProfiles }.getOrNull()
        if (userProfiles == null) complete = false else handles.addAll(userProfiles)
        val launcherProfiles = runCatching { launcher?.profiles }.getOrNull()
        if (launcherProfiles == null) complete = false else handles.addAll(launcherProfiles)

        val profiles = handles.mapNotNull { user ->
            val managerSerial = runCatching { users?.getSerialNumberForUser(user) }
                .getOrNull()?.takeIf { it >= 0 }
            val metadata = if (Build.VERSION.SDK_INT >= 35 && (user != current || managerSerial == null)) {
                runCatching { launcher?.getLauncherUserInfo(user) }.getOrNull()
            } else null
            val userType = if (Build.VERSION.SDK_INT >= 35) {
                runCatching { metadata?.userType?.takeIf(String::isNotBlank) }.getOrNull()
            } else null
            if (Build.VERSION.SDK_INT >= 35 && user != current && userType == null) complete = false

            val serial = managerSerial ?: if (Build.VERSION.SDK_INT >= 35) {
                runCatching { metadata?.userSerialNumber?.toLong() }
                    .getOrNull()?.takeIf { it >= 0 }
            } else null
            if (serial == null) {
                complete = false
                identitiesComplete = false
                // A fabricated serial could merge two installations or restore a removed user's
                // items into a new user. The current user keeps its existing unprefixed app keys.
                if (user != current) return@mapNotNull null
            }

            val exactType = if (Build.VERSION.SDK_INT >= 35 && userType != null) {
                when (userType) {
                    UserManager.USER_TYPE_PROFILE_MANAGED -> LauncherProfileType.Work
                    UserManager.USER_TYPE_PROFILE_PRIVATE -> LauncherProfileType.Private
                    UserManager.USER_TYPE_PROFILE_CLONE -> LauncherProfileType.Clone
                    else -> LauncherProfileType.Other
                }
            } else null
            val type = if (user == current) LauncherProfileType.Personal else {
                // PrivateSpaceController can discover this profile before the first catalog load.
                // Its in-memory observation must also protect that first load if metadata fails.
                val knownPrivate = serial?.takeIf { synchronized(privateSerials) { it in privateSerials } }
                    ?.let { LauncherProfileType.Private }
                val recognizedType = exactType ?: knownPrivate
                // Failure to persist a type must not make a successfully read catalog incomplete.
                // The in-memory observation is still valid and the store retries its dirty write.
                if (recognizedType != null && serial != null) knownTypes.remember(serial, recognizedType)
                recognizedType ?: serial?.let(knownTypes::get) ?: if (Build.VERSION.SDK_INT >= 35) {
                    // Missing metadata means inaccessible on Android. On first discovery it
                    // may also be Private Space: retry instead of guessing that it is public.
                    // A successfully returned OEM-specific type is Other and remains visible.
                    LauncherProfileType.Unavailable
                } else run {
                    // Lawnchair's legacy badge heuristic. Non-current alone does not mean work.
                    val probe = ProfileBadgeProbe()
                    val badged = runCatching { packageManager.getUserBadgedIcon(probe, user) }
                    if (badged.isFailure) complete = false
                    if (badged.getOrNull()?.let { it !== probe } == true) LauncherProfileType.Work
                    else LauncherProfileType.Other
                }
            }
            val quietMode = runCatching { users?.isQuietModeEnabled(user) }.getOrNull()
            val unlocked = runCatching { users?.isUserUnlocked(user) }.getOrNull()
            if (quietMode == null || unlocked == null) complete = false
            LauncherProfile(user, serial ?: -1L, type, quietMode, unlocked)
        }
        return LauncherProfileSnapshot(profiles.sortedWith(
            compareBy<LauncherProfile> { it.user != current }.thenBy { it.serial }), complete,
            discoveryComplete = userProfiles != null && launcherProfiles != null && identitiesComplete)
    }

    /** Nonzero dimensions also allow older platform versions to create a badge bitmap. */
    private class ProfileBadgeProbe : ColorDrawable() {
        override fun getIntrinsicWidth(): Int = 1
        override fun getIntrinsicHeight(): Int = 1
    }

    /**
     * Keep recognized types across permission/metadata failures and process restarts, especially
     * Private: a temporary failure must not expose a known private installation in the normal list.
     * Serial numbers are device-local, so this file must never be restored onto another device.
     */
    private class ProfileTypeStore(file: File) {
        private val storage = AtomicFile(file)
        private val types = runCatching {
            val json = JSONObject(storage.readFully().toString(Charsets.UTF_8))
            json.keys().asSequence().mapNotNull { key ->
                val serial = key.toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
                val type = LauncherProfileType.entries.firstOrNull { it.name == json.optString(key) }
                    ?: return@mapNotNull null
                serial to type
            }.toMap().toMutableMap()
        }.getOrElse { mutableMapOf() }
        private var dirty = false

        @Synchronized
        fun get(serial: Long): LauncherProfileType? = types[serial]

        @Synchronized
        fun remember(serial: Long, type: LauncherProfileType): Boolean {
            if (types[serial] != type) {
                types[serial] = type
                dirty = true
            }
            if (!dirty) return true
            val saved = runCatching {
                val json = JSONObject()
                types.forEach { (key, value) -> json.put(key.toString(), value.name) }
                val output = storage.startWrite()
                try {
                    output.write(json.toString().toByteArray(Charsets.UTF_8))
                    storage.finishWrite(output)
                } catch (error: Exception) {
                    storage.failWrite(output)
                    throw error
                }
            }.isSuccess
            if (saved) dirty = false
            return saved
        }
    }

    companion object {
        private val typeStores = mutableMapOf<String, ProfileTypeStore>()
        private val privateSerials = mutableSetOf<Long>()

        /** Use the same identity fallback for widget binding and external shortcut requests. */
        fun serialNumber(context: Context, user: UserHandle): Long? =
            runCatching { context.getSystemService(UserManager::class.java).getSerialNumberForUser(user) }
                .getOrNull()?.takeIf { it >= 0 }
                ?: if (Build.VERSION.SDK_INT >= 35) {
                    runCatching { context.getSystemService(LauncherApps::class.java)
                        .getLauncherUserInfo(user)?.userSerialNumber?.toLong() }
                        .getOrNull()?.takeIf { it >= 0 }
                } else null

        /** No disk IO: the next IO snapshot persists this observation for all consumers. */
        fun rememberPrivateProfile(serial: Long) {
            if (serial >= 0) synchronized(privateSerials) { privateSerials.add(serial) }
        }
    }
}
