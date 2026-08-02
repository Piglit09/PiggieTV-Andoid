package org.jellyfin.mobile.core

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.piggietv.core.PtvCacheEnvelope
import com.piggietv.core.PtvClientCapabilityReport
import com.piggietv.core.PtvCoreInitializationResult
import com.piggietv.core.PtvCoreInitializationStep
import com.piggietv.core.PtvCoreResult
import com.piggietv.core.PtvCoreScope
import com.piggietv.core.PtvCoreScopeKind
import com.piggietv.core.PtvDiagnosticEvent
import com.piggietv.core.PtvFeatureFlagDocument
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.app.AppPreferences
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class PtvCoreMobileRuntimeSnapshot(
    val capabilityReport: PtvClientCapabilityReport? = null,
    val cacheEnvelope: PtvCacheEnvelope<Map<String, String>>? = null,
    val diagnosticEvent: PtvDiagnosticEvent? = null,
    val featureFlags: PtvFeatureFlagDocument? = null,
    val settings: Map<String, Any?> = emptyMap(),
)

object PtvCoreRuntime {
    private val lock = Any()

    @Volatile
    private var coordinator: PtvCoreRuntimeCoordinator? = null

    @Volatile
    private var state = PtvCoreMobileRuntimeSnapshot()

    fun initialize(context: Context): PtvCoreInitializationResult =
        getOrCreateCoordinator(context.applicationContext).initialize()

    fun snapshot(): PtvCoreMobileRuntimeSnapshot = state

    private fun getOrCreateCoordinator(context: Context): PtvCoreRuntimeCoordinator {
        coordinator?.let { return it }
        return synchronized(lock) {
            coordinator ?: createCoordinator(context).also { coordinator = it }
        }
    }

    private fun createCoordinator(context: Context): PtvCoreRuntimeCoordinator {
        val listenerRegistry = PtvCoreListenerRegistry()
        val preferences = AppPreferences(context)
        val sharedPreferences = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        val scope = {
            val serverId = preferences.currentServerId?.toString()
            val userId = preferences.currentUserId?.toString()
            when {
                serverId != null && userId != null -> PtvCoreScope(PtvCoreScopeKind.USER, serverId, userId)
                serverId != null -> PtvCoreScope(PtvCoreScopeKind.SERVER, serverId = serverId)
                else -> PtvCoreScope(
                    PtvCoreScopeKind.DEVICE,
                    deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                        ?: context.packageName,
                )
            }
        }
        val refreshSettings = {
            val values = mapOf<String, Any?>(
                "ignoreBatteryOptimizations" to preferences.ignoreBatteryOptimizations,
                "ignoreWebViewChecks" to preferences.ignoreWebViewChecks,
                "backgroundAudio" to preferences.exoPlayerAllowBackgroundAudio,
                "videoPlayerType" to preferences.videoPlayerType,
            )
            state = state.copy(
                featureFlags = PtvFeatureFlagDocument(
                    schemaVersion = 1,
                    scope = scope(),
                    values = values.filterValues { it is Boolean }.mapValues { it.value as Boolean },
                    updatedAt = timestamp(),
                ),
                settings = values,
            )
        }

        return PtvCoreRuntimeCoordinator(
            handlers = mapOf(
                PtvCoreInitializationStep.ENVIRONMENT to {
                    state = state.copy(capabilityReport = PtvCorePlatformAdapter.capabilityReport())
                    success()
                },
                PtvCoreInitializationStep.DEVICE_IDENTITY to {
                    scope()
                    success()
                },
                PtvCoreInitializationStep.SESSION to {
                    // AppPreferences and MainViewModel remain authoritative for session and login state.
                    preferences.currentServerId
                    preferences.currentUserId
                    success()
                },
                PtvCoreInitializationStep.SETTINGS to {
                    refreshSettings()
                    listenerRegistry.register("mobile-settings") {
                        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshSettings() }
                        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
                        val disposer: () -> Unit = {
                            sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
                        }
                        disposer
                    }
                    success()
                },
                PtvCoreInitializationStep.CACHE to {
                    val cacheDirectory = context.cacheDir
                    if (cacheDirectory.exists() || cacheDirectory.mkdirs()) {
                        val now = System.currentTimeMillis()
                        state = state.copy(
                            cacheEnvelope = PtvCacheEnvelope(
                                schemaVersion = 1,
                                key = "core-runtime-capabilities",
                                scope = scope(),
                                createdAt = timestamp(now),
                                expiresAt = timestamp(now + 24 * 60 * 60 * 1000L),
                                payload = mapOf("owner" to "existing-android-cache"),
                            ),
                        )
                        success()
                    } else {
                        failure("CACHE_DIRECTORY_UNAVAILABLE", com.piggietv.core.PtvCoreErrorKind.CACHE)
                    }
                },
                PtvCoreInitializationStep.API to {
                    // Koin and the existing Jellyfin API module are initialized by JellyfinApplication.
                    success()
                },
                PtvCoreInitializationStep.DIAGNOSTICS to {
                    val capabilities = state.capabilityReport ?: PtvCorePlatformAdapter.capabilityReport()
                    val event = PtvDiagnosticEvent(
                        category = "startup",
                        name = "core-runtime-initialized",
                        appVersion = BuildConfig.VERSION_NAME,
                        platform = capabilities.platform,
                        deviceClass = capabilities.deviceClass,
                        sessionId = "existing-mobile-session",
                        timestamp = timestamp(),
                        success = true,
                        metadata = mapOf("adapter" to "android-mobile"),
                    )
                    state = state.copy(diagnosticEvent = event)
                    Timber.i("PTV Core runtime initialized contract=%s", event.contractVersion)
                    success()
                },
                PtvCoreInitializationStep.NAVIGATION to {
                    // MainActivity and its fragment manager continue to own navigation.
                    success()
                },
                PtvCoreInitializationStep.PLAYBACK to {
                    // Web player, Media3, Cast, notifications, and lock-screen controls remain legacy-owned.
                    success()
                },
            ),
            listenerRegistry = listenerRegistry,
        )
    }

    internal fun disposeAndResetForTests() {
        synchronized(lock) {
            coordinator?.disposeAndReset()
            coordinator = null
            state = PtvCoreMobileRuntimeSnapshot()
        }
    }

    private fun success(): PtvCoreResult<Unit> = PtvCoreResult.Success(Unit)

    private fun failure(
        code: String,
        kind: com.piggietv.core.PtvCoreErrorKind,
    ): PtvCoreResult<Unit> = PtvCoreResult.Failure(
        com.piggietv.core.PtvCoreError(
            kind = kind,
            code = code,
            message = "PiggieTV core initialization was degraded.",
            retryable = true,
            timestamp = timestamp(),
            operation = "core-runtime",
        ),
    )

    private fun timestamp(value: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(value))
}
