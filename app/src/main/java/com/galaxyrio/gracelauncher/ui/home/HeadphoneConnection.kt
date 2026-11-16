package com.galaxyrio.gracelauncher.ui.home

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun rememberHeadphonesConnected(): State<Boolean> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    fun connected() = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { device ->
        device.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)
    } == true
    val state = remember(audio) { mutableStateOf(connected()) }
    DisposableEffect(audio, lifecycle) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { state.value = connected() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { state.value = connected() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = connected()
        }
        audio?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        lifecycle.addObserver(observer)
        state.value = connected()
        onDispose {
            audio?.unregisterAudioDeviceCallback(callback)
            lifecycle.removeObserver(observer)
        }
    }
    return state
}
