package org.jellyfin.mobile.core

import com.piggietv.core.PtvClientCapability
import com.piggietv.core.PtvClientCapabilityReport

object PtvCorePlatformAdapter {
    fun capabilityReport(): PtvClientCapabilityReport = PtvClientCapabilityReport(
        platform = "android",
        deviceClass = "mobile",
        capabilities = mapOf(
            PtvClientCapability.VIDEO_PLAYBACK.wireName to true,
            PtvClientCapability.AUDIO_PLAYBACK.wireName to true,
            PtvClientCapability.BACKGROUND_AUDIO.wireName to true,
            PtvClientCapability.DOWNLOADS.wireName to true,
            PtvClientCapability.ANDROID_AUTO.wireName to true,
            PtvClientCapability.TV_FOCUS_NAVIGATION.wireName to false,
            PtvClientCapability.POINTER_INPUT.wireName to false,
            PtvClientCapability.TOUCH_INPUT.wireName to true,
            PtvClientCapability.KEYBOARD_INPUT.wireName to true,
            PtvClientCapability.REMOTE_INPUT.wireName to false,
            PtvClientCapability.READER.wireName to true,
            PtvClientCapability.COMIC_PAGING.wireName to true,
            PtvClientCapability.NATIVE_NOTIFICATIONS.wireName to true,
            PtvClientCapability.LOCK_SCREEN_CONTROLS.wireName to true,
            PtvClientCapability.HARDWARE_DECODING.wireName to true,
            PtvClientCapability.TELEMETRY.wireName to false,
            PtvClientCapability.OFFLINE_CACHE.wireName to true,
            PtvClientCapability.QUICK_CONNECT.wireName to false,
            PtvClientCapability.DEEP_LINKS.wireName to false,
        ),
    )
}
