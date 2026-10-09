package com.galaxyrio.gracelauncher.platform

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.PrivateSpaceSettings
import com.galaxyrio.gracelauncher.data.PrivateSpaceDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PrivateSpaceState(
    val supported: Boolean = false,
    val user: UserHandle? = null,
    val serial: Long = -1,
    val locked: Boolean = true,
    /** A system-unlocked profile is still hidden until explicitly opened in Grace. */
    val accessible: Boolean = false,
    val authenticating: Boolean = false,
    val revision: Int = 0,
    val lockVersion: Int = 0,
)

/** One ephemeral session shared by HOME and the standalone settings Activity. */
class PrivateSpaceController private constructor(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PrivateSpaceState(supported = Build.VERSION.SDK_INT >= 35))
    val state = _state.asStateFlow()
    private var pending: Request? = null
    private var generation = 0
    private var lockOnReturn = false
    private var preparedAppKey: String? = null
    private var settings = PrivateSpaceSettings()
    private var configured = false

    fun configure(value: PrivateSpaceSettings) {
        val previous = settings
        settings = value
        configured = true
        if (!value.locksOnExit) lockOnReturn = false
        if (!value.enabled || (!previous.protectsApps && value.protectsApps)) lock()
        else refresh()
    }

    private class Request(val activity: Activity, val user: UserHandle, val authenticate: Boolean, val ready: () -> Unit) {
        var systemChallenge = false
        var waitingForProfile = false
        var paused = false
        var signal: CancellationSignal? = null
    }

    init {
        if (Build.VERSION.SDK_INT >= 35) {
            ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        if (settings.enabled && settings.display != PrivateSpaceDisplay.NormalApp) lock() else clearSession()
                    } else refresh()
                }
            }, IntentFilter().apply {
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
                addAction(Intent.ACTION_USER_UNLOCKED)
                addAction(Intent.ACTION_SCREEN_OFF)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            launcherApps.registerCallback(object : LauncherApps.Callback() {
                private fun changed(user: UserHandle) {
                    if (user == _state.value.user) {
                        refresh()
                        _state.update { it.copy(revision = it.revision + 1) }
                    }
                }
                override fun onPackageAdded(packageName: String, user: UserHandle) = changed(user)
                override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(user)
                override fun onPackageChanged(packageName: String, user: UserHandle) = changed(user)
                override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
                override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
            }, handler)
        }
        refresh()
    }

    fun refresh() {
        if (Build.VERSION.SDK_INT < 35) return
        val user = if (DefaultHome.isDefault(context)) runCatching {
            launcherApps.profiles.firstOrNull {
                launcherApps.getLauncherUserInfo(it)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
            }
        }.getOrNull() else null
        val locked = user == null || runCatching { users.isQuietModeEnabled(user) || !users.isUserUnlocked(user) }.getOrDefault(true)
        val previous = _state.value
        _state.value = previous.copy(user = user, serial = user?.let(users::getSerialNumberForUser) ?: -1,
            locked = locked, accessible = !locked && (settings.exposesApps || (previous.accessible && previous.user == user)),
            lockVersion = previous.lockVersion + if ((locked && !previous.locked) || previous.user != user) 1 else 0)
        val request = pending
        if (request != null) {
            if (request.user != user) cancelAuthentication(request.activity)
            else if (!locked && request.waitingForProfile) {
                request.waitingForProfile = false
                // A false return from requestQuietModeEnabled opened Android's own
                // profile credential screen. Do not ask for the device credential twice.
                if (request.systemChallenge || !request.authenticate) complete(request) else authenticate(request)
            }
        }
    }

    fun unlock(activity: Activity, authenticate: Boolean, forceAuthentication: Boolean = false, ready: () -> Unit) {
        if (pending != null) return
        refresh()
        val user = _state.value.user
        if (user == null) {
            Toast.makeText(context, if (DefaultHome.isDefault(context)) R.string.private_space_setup else R.string.shortcut_permission, Toast.LENGTH_LONG).show()
            return
        }
        if (_state.value.accessible && !_state.value.locked && !forceAuthentication) { ready(); return }
        val request = Request(activity, user, authenticate, ready)
        pending = request
        generation++
        _state.update { it.copy(authenticating = true) }
        if (_state.value.locked) {
            request.waitingForProfile = true
            runCatching { users.requestQuietModeEnabled(false, user) }.onSuccess { immediate ->
                request.systemChallenge = !immediate
                if (immediate) awaitProfile(request, generation, 0)
            }.onFailure { fail(request, R.string.private_space_unavailable) }
        } else if (request.authenticate) authenticate(request) else complete(request)
    }

    private fun awaitProfile(request: Request, token: Int, attempt: Int) {
        if (pending !== request || generation != token) return
        refresh()
        if (pending !== request || !request.waitingForProfile) return
        if (attempt >= 50) { fail(request, R.string.private_space_unavailable); return }
        handler.postDelayed({ awaitProfile(request, token, attempt + 1) }, 200)
    }

    private fun authenticate(request: Request) {
        if (Build.VERSION.SDK_INT < 35) {
            fail(request, R.string.private_space_unsupported)
            return
        }
        if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            fail(request, R.string.private_space_security_required)
            return
        }
        val signal = CancellationSignal()
        request.signal = signal
        val prompt = BiometricPrompt.Builder(request.activity)
            .setTitle(context.getString(R.string.private_space_verify_title))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        runCatching {
            prompt.authenticate(signal, request.activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (pending === request) { request.signal = null; complete(request) }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (pending === request) {
                        request.signal = null
                        cancelAuthentication(request.activity)
                    }
                }
            })
        }.onFailure { fail(request, R.string.private_space_unavailable) }
    }

    private fun complete(request: Request) {
        if (pending !== request) return
        val available = request.user == _state.value.user && DefaultHome.isDefault(context) && runCatching {
            !users.isQuietModeEnabled(request.user) && users.isUserUnlocked(request.user)
        }.getOrDefault(false)
        if (!available) { fail(request, R.string.private_space_unavailable); return }
        pending = null
        lockOnReturn = false
        _state.update { it.copy(accessible = true, locked = false, authenticating = false) }
        request.ready()
    }

    private fun fail(request: Request, message: Int) {
        cancelAuthentication(request.activity)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun cancelAuthentication(activity: Activity) {
        val request = pending ?: return
        if (request.activity !== activity) return
        pending = null
        generation++
        request.signal?.cancel()
        _state.update { it.copy(authenticating = false) }
        if (settings.protectsApps) lock() else clearSession()
    }

    private fun clearSession() {
        preparedAppKey = null
        lockOnReturn = false
        val request = pending
        pending = null
        generation++
        request?.signal?.cancel()
        _state.update { it.copy(accessible = false, authenticating = false, lockVersion = it.lockVersion + 1) }
    }

    fun lock() {
        clearSession()
        val user = _state.value.user ?: return
        lockOnReturn = runCatching {
            if (!users.isQuietModeEnabled(user)) users.requestQuietModeEnabled(true, user) else true
        }.getOrDefault(false).not()
        refresh()
    }

    /** Locking a profile stops its apps. Defer that operation until HOME resumes. */
    fun prepareAppLaunch(user: UserHandle?, key: String): Boolean {
        if (preparedAppKey == key && user == _state.value.user && !_state.value.locked) return true
        if (!settings.enabled || _state.value.locked || user != _state.value.user ||
            (settings.protectsApps && !_state.value.accessible)) return false
        preparedAppKey = key
        lockOnReturn = settings.locksOnExit
        if (lockOnReturn) _state.update { it.copy(accessible = false) }
        return true
    }

    fun finishAppLaunch(launched: Boolean = true) {
        preparedAppKey = null
        if (!launched) {
            lockOnReturn = false
            if (settings.locksOnExit) lock()
        }
    }

    fun close() {
        if (configured && settings.locksOnExit && _state.value.accessible && !_state.value.authenticating) lock()
    }

    fun onPause() {
        val request = pending
        if (request != null) request.paused = true
        else if (!lockOnReturn) close()
    }

    fun onResume() {
        refresh()
        // A process restart must not inherit an unlocked session from the system.
        if (configured && settings.locksOnExit && pending == null &&
            (lockOnReturn || (settings.protectsApps && !_state.value.accessible && !_state.value.locked))) lock()
        val request = pending ?: return
        if (request.paused && request.systemChallenge && request.waitingForProfile) {
            val token = generation
            // Android sends availability after starting the profile, potentially
            // just after the credential Activity returns to HOME.
            handler.postDelayed({
                if (pending === request && generation == token) {
                    refresh()
                    if (pending === request && request.waitingForProfile) {
                        if (users.isQuietModeEnabled(request.user)) cancelAuthentication(request.activity)
                        else awaitProfile(request, token, 0)
                    }
                }
            }, 500)
        }
    }

    fun openSettings(activity: Activity) {
        close()
        if (Build.VERSION.SDK_INT >= 36) {
            val sender = runCatching { launcherApps.privateSpaceSettingsIntent }.getOrNull()
            if (sender != null) {
                // System-created IntentSenders need the visible launcher's explicit
                // opt-in; a blocked start otherwise returns without throwing.
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
                    .toBundle()
                if (runCatching { activity.startIntentSender(sender, null, 0, 0, 0, options) }.isSuccess) return
            }
        }
        // Without the public IntentSender, the private-space Activity isn't
        // exported by AOSP Settings. Use its public security page as a fallback.
        runCatching { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            .onFailure { Toast.makeText(context, R.string.action_unavailable, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        @Volatile private var instance: PrivateSpaceController? = null
        fun get(context: Context): PrivateSpaceController = instance ?: synchronized(this) {
            instance ?: PrivateSpaceController(context.applicationContext).also { instance = it }
        }
    }
}
