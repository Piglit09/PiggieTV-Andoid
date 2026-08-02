package com.piggietv.core

object PtvCoreContract {
    const val VERSION = "1.0"
    const val SCHEMA_VERSION = 1
}

enum class PtvCoreErrorKind(val wireName: String) {
    NETWORK("network"),
    AUTHENTICATION("authentication"),
    AUTHORIZATION("authorization"),
    SERVER("server"),
    VALIDATION("validation"),
    PARSING("parsing"),
    CACHE("cache"),
    PLAYBACK("playback"),
    UNSUPPORTED_PLATFORM("unsupported-platform"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
}

data class PtvCoreError(
    val kind: PtvCoreErrorKind,
    val code: String,
    val message: String,
    val retryable: Boolean,
    val timestamp: String,
    val developerDetail: String? = null,
    val cause: String? = null,
    val httpStatus: Int? = null,
    val operation: String? = null,
    val correlationId: String? = null,
    val contractVersion: String = PtvCoreContract.VERSION,
)

sealed class PtvCoreResult<out T> {
    data class Success<T>(val value: T) : PtvCoreResult<T>()
    data class Failure(val error: PtvCoreError) : PtvCoreResult<Nothing>()
}

enum class PtvCoreScopeKind(val wireName: String) {
    GLOBAL("global"),
    USER("user"),
    SERVER("server"),
    DEVICE("device"),
}

data class PtvCoreScope(
    val kind: PtvCoreScopeKind,
    val serverId: String? = null,
    val userId: String? = null,
    val deviceId: String? = null,
)

enum class PtvCoreMediaType(val wireName: String) {
    MOVIE("movie"),
    SHOW("show"),
    SEASON("season"),
    EPISODE("episode"),
    MUSIC("music"),
    ALBUM("album"),
    ARTIST("artist"),
    PLAYLIST("playlist"),
    BOOK("book"),
    COMIC("comic"),
    MANGA("manga"),
    AUDIOBOOK("audiobook"),
    LIVE_TV("live-tv"),
    GAME("game"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): PtvCoreMediaType {
            val normalized = value.orEmpty().trim().lowercase().replace(Regex("[\\s_-]"), "")
            return when (normalized) {
                "movie" -> MOVIE
                "series", "show" -> SHOW
                "season" -> SEASON
                "episode" -> EPISODE
                "audio", "music", "song" -> MUSIC
                "album", "audioalbum", "musicalbum" -> ALBUM
                "artist", "audioartist", "musicartist" -> ARTIST
                "playlist", "boxset" -> PLAYLIST
                "book" -> BOOK
                "comic" -> COMIC
                "manga" -> MANGA
                "audiobook" -> AUDIOBOOK
                "channel", "livetv" -> LIVE_TV
                "game" -> GAME
                else -> UNKNOWN
            }
        }
    }
}

data class PtvMediaIdentity(
    val id: String,
    val serverId: String,
    val mediaType: PtvCoreMediaType,
    val title: String,
    val userId: String? = null,
    val libraryId: String? = null,
    val sortTitle: String? = null,
    val originalMediaType: String? = null,
)

data class PtvArtworkReference(
    val mediaId: String,
    val imageType: String,
    val tag: String? = null,
    val index: Int? = null,
    val aspectRatio: Double? = null,
)

data class PtvPlaybackProgress(
    val positionMs: Long,
    val played: Boolean,
    val completed: Boolean,
    val durationMs: Long? = null,
    val playedPercentage: Double? = null,
    val playCount: Int? = null,
    val lastPlayedAt: String? = null,
)

enum class PtvCoreHttpMethod {
    GET,
    HEAD,
    OPTIONS,
    POST,
    PUT,
    PATCH,
    DELETE,
}

object PtvCoreApiPolicy {
    private val safeMethods = setOf(PtvCoreHttpMethod.GET, PtvCoreHttpMethod.HEAD, PtvCoreHttpMethod.OPTIONS)
    private val retryableStatuses = setOf(408, 425, 429, 500, 502, 503, 504)

    fun shouldRetry(
        method: PtvCoreHttpMethod,
        attempt: Int,
        status: Int? = null,
        explicitlyIdempotent: Boolean = false,
        maxAttempts: Int = 3,
    ): Boolean {
        if (attempt >= maxAttempts) return false
        if (method !in safeMethods && !explicitlyIdempotent) return false
        return status == null || status in retryableStatuses
    }

    fun field(record: Map<*, *>, camelCase: String, pascalCase: String): Any? = record[camelCase] ?: record[pascalCase]
}

enum class PtvNavigationDestination(val wireName: String) {
    HOME("home"),
    MOVIES("movies"),
    SHOWS("shows"),
    MUSIC("music"),
    READING("reading"),
    LIVE_TV("live-tv"),
    SEARCH("search"),
    REQUESTS("requests"),
    LIBRARY("library"),
    DETAILS("details"),
    PLAYER("player"),
    SETTINGS("settings"),
    PROFILE("profile"),
    DIAGNOSTICS("diagnostics"),
    ;

    companion object {
        fun fromWire(value: String?): PtvNavigationDestination? {
            val normalized = value.orEmpty().trim().lowercase().replace("_", "-")
            return entries.firstOrNull { it.wireName == normalized }
                ?: if (normalized == "livetv") LIVE_TV else null
        }
    }
}

enum class PtvNavigationBackBehavior(val wireName: String) {
    DEFAULT("default"),
    PRESERVE("preserve"),
    REPLACE("replace"),
    MODAL("modal"),
}

data class PtvNavigationIntent(
    val destination: PtvNavigationDestination,
    val mediaId: String? = null,
    val libraryId: String? = null,
    val query: Map<String, String> = emptyMap(),
    val backBehavior: PtvNavigationBackBehavior = PtvNavigationBackBehavior.DEFAULT,
    val deepLink: String? = null,
    val requiredCapabilities: Set<String> = emptySet(),
    val contractVersion: String = PtvCoreContract.VERSION,
) {
    fun validate(timestamp: String): PtvCoreResult<PtvNavigationIntent> =
        if (destination in setOf(PtvNavigationDestination.DETAILS, PtvNavigationDestination.PLAYER) &&
            mediaId.isNullOrBlank()
        ) {
            PtvCoreResult.Failure(
                PtvCoreError(
                    kind = PtvCoreErrorKind.VALIDATION,
                    code = "NAVIGATION_MEDIA_ID_REQUIRED",
                    message = "A media item is required for that destination.",
                    retryable = false,
                    timestamp = timestamp,
                    operation = "navigation-intent",
                ),
            )
        } else {
            PtvCoreResult.Success(this)
        }
}

enum class PtvPlaybackStatus(val wireName: String) {
    IDLE("idle"),
    LOADING("loading"),
    PLAYING("playing"),
    PAUSED("paused"),
    BUFFERING("buffering"),
    ENDED("ended"),
    ERROR("error"),
}

enum class PtvRepeatMode(val wireName: String) {
    OFF("off"),
    ONE("one"),
    ALL("all"),
}

data class PtvPlaybackState(
    val status: PtvPlaybackStatus = PtvPlaybackStatus.IDLE,
    val mediaId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val queue: List<String> = emptyList(),
    val queueIndex: Int = -1,
    val repeatMode: PtvRepeatMode = PtvRepeatMode.OFF,
    val shuffled: Boolean = false,
    val completed: Boolean = false,
    val audioTrackId: String? = null,
    val subtitleTrackId: String? = null,
    val error: PtvCoreError? = null,
    val contractVersion: String = PtvCoreContract.VERSION,
)

sealed class PtvPlaybackCommand {
    data object Play : PtvPlaybackCommand()
    data object Pause : PtvPlaybackCommand()
    data object Next : PtvPlaybackCommand()
    data object Previous : PtvPlaybackCommand()
    data class Seek(val positionMs: Long) : PtvPlaybackCommand()
    data class ReplaceQueue(val mediaIds: List<String>, val startIndex: Int = 0) : PtvPlaybackCommand()
    data class AddToQueue(val mediaIds: List<String>) : PtvPlaybackCommand()
    data class PlayNext(val mediaIds: List<String>) : PtvPlaybackCommand()
    data class SetRepeat(val mode: PtvRepeatMode) : PtvPlaybackCommand()
    data class SetShuffle(val enabled: Boolean) : PtvPlaybackCommand()
}

object PtvPlaybackReducer {
    private const val PREVIOUS_RESTART_THRESHOLD_MS = 5_000L

    fun reduce(state: PtvPlaybackState, command: PtvPlaybackCommand): PtvPlaybackState = when (command) {
        PtvPlaybackCommand.Play -> if (state.mediaId == null) {
            state
        } else {
            state.copy(
                status = PtvPlaybackStatus.PLAYING,
                completed = false,
            )
        }

        PtvPlaybackCommand.Pause -> if (state.mediaId == null) state else state.copy(status = PtvPlaybackStatus.PAUSED)

        PtvPlaybackCommand.Next -> move(state, 1)

        PtvPlaybackCommand.Previous -> if (state.positionMs > PREVIOUS_RESTART_THRESHOLD_MS) {
            state.copy(positionMs = 0)
        } else {
            move(state, -1)
        }

        is PtvPlaybackCommand.Seek -> state.copy(
            positionMs = command.positionMs.coerceAtLeast(0).let { position ->
                state.durationMs?.let(position::coerceAtMost) ?: position
            },
            completed = false,
        )

        is PtvPlaybackCommand.ReplaceQueue -> withQueue(state, command.mediaIds, command.startIndex)

        is PtvPlaybackCommand.AddToQueue -> withQueue(state, state.queue + clean(command.mediaIds), state.queueIndex)

        is PtvPlaybackCommand.PlayNext -> {
            val insertion = (state.queueIndex + 1).coerceAtLeast(0)
            withQueue(
                state,
                state.queue.take(insertion) + clean(command.mediaIds) + state.queue.drop(insertion),
                state.queueIndex,
            )
        }

        is PtvPlaybackCommand.SetRepeat -> state.copy(repeatMode = command.mode)

        is PtvPlaybackCommand.SetShuffle -> state.copy(shuffled = command.enabled)
    }

    private fun clean(ids: List<String>): List<String> = ids.map(String::trim).filter(String::isNotEmpty)

    private fun withQueue(state: PtvPlaybackState, values: List<String>, requestedIndex: Int): PtvPlaybackState {
        val queue = clean(values)
        val index = if (queue.isEmpty()) -1 else requestedIndex.coerceIn(0, queue.lastIndex)
        return state.copy(
            queue = queue,
            queueIndex = index,
            mediaId = queue.getOrNull(index),
            positionMs = if (index == state.queueIndex) state.positionMs else 0,
            status = if (index < 0) PtvPlaybackStatus.IDLE else state.status,
            completed = false,
        )
    }

    private fun move(state: PtvPlaybackState, offset: Int): PtvPlaybackState {
        if (state.queue.isEmpty()) return state
        if (offset > 0 && state.repeatMode == PtvRepeatMode.ONE) return state.copy(positionMs = 0, completed = false)
        val candidate = state.queueIndex + offset
        val index = when {
            candidate in state.queue.indices -> candidate
            state.repeatMode == PtvRepeatMode.ALL && offset > 0 -> 0
            state.repeatMode == PtvRepeatMode.ALL -> state.queue.lastIndex
            offset > 0 -> return state.copy(status = PtvPlaybackStatus.ENDED, completed = true)
            else -> return state.copy(positionMs = 0)
        }
        return withQueue(state, state.queue, index)
    }
}

data class PtvRecommendationReason(val text: String, val code: String? = null, val score: Double? = null)

enum class PtvRecommendationClassification(val wireName: String) {
    AFFINITY("affinity"),
    DISCOVERY("discovery"),
    MIXED("mixed"),
}

data class PtvRecommendationItem(
    val id: String,
    val title: String,
    val mediaType: PtvCoreMediaType,
    val score: Double,
    val source: String,
    val classification: PtvRecommendationClassification,
    val reasons: List<PtvRecommendationReason> = emptyList(),
)

data class PtvRecommendationRow(
    val id: String,
    val title: String,
    val items: List<PtvRecommendationItem>,
    val explanation: String? = null,
    val source: String? = null,
    val mediaType: String? = null,
)

data class PtvRecommendationResponse(
    val cacheVersion: Int,
    val generatedAt: String,
    val serverId: String,
    val userId: String,
    val rows: List<PtvRecommendationRow>,
    val contractVersion: String = PtvCoreContract.VERSION,
)

data class PtvFeatureFlagDocument(
    val schemaVersion: Int,
    val scope: PtvCoreScope,
    val values: Map<String, Boolean>,
    val updatedAt: String? = null,
    val contractVersion: String = PtvCoreContract.VERSION,
) {
    fun enabled(key: String, defaults: Map<String, Boolean> = emptyMap()): Boolean =
        values[key] ?: (defaults[key] == true)
}

data class PtvCacheEnvelope<T>(
    val schemaVersion: Int,
    val key: String,
    val scope: PtvCoreScope,
    val createdAt: String,
    val expiresAt: String,
    val payload: T,
    val staleWhileRevalidateUntil: String? = null,
    val payloadBytes: Long? = null,
    val contractVersion: String = PtvCoreContract.VERSION,
)

enum class PtvCacheOutcome {
    HIT,
    STALE,
    MISS,
    EXPIRED,
    VERSION_MISMATCH,
    CORRUPT,
    ERROR,
}

object PtvCachePolicy {
    fun <T> inspect(
        envelope: PtvCacheEnvelope<T>?,
        expectedKey: String,
        expectedScope: PtvCoreScope,
        expectedSchemaVersion: Int,
        nowEpochMs: Long,
        expiresAtEpochMs: Long,
        staleUntilEpochMs: Long? = null,
        allowStale: Boolean = false,
        maxPayloadBytes: Long? = null,
    ): PtvCacheOutcome {
        if (envelope == null) return PtvCacheOutcome.MISS
        if (envelope.contractVersion != PtvCoreContract.VERSION) return PtvCacheOutcome.CORRUPT
        if (envelope.schemaVersion != expectedSchemaVersion) return PtvCacheOutcome.VERSION_MISMATCH
        if (envelope.key != expectedKey || envelope.scope != expectedScope) return PtvCacheOutcome.MISS
        if (maxPayloadBytes != null && envelope.payloadBytes != null && envelope.payloadBytes > maxPayloadBytes) {
            return PtvCacheOutcome.ERROR
        }
        if (expiresAtEpochMs > nowEpochMs) return PtvCacheOutcome.HIT
        if (allowStale && staleUntilEpochMs != null && staleUntilEpochMs > nowEpochMs) return PtvCacheOutcome.STALE
        return PtvCacheOutcome.EXPIRED
    }
}

enum class PtvClientCapability(val wireName: String) {
    VIDEO_PLAYBACK("videoPlayback"),
    AUDIO_PLAYBACK("audioPlayback"),
    BACKGROUND_AUDIO("backgroundAudio"),
    DOWNLOADS("downloads"),
    ANDROID_AUTO("androidAuto"),
    TV_FOCUS_NAVIGATION("tvFocusNavigation"),
    POINTER_INPUT("pointerInput"),
    TOUCH_INPUT("touchInput"),
    KEYBOARD_INPUT("keyboardInput"),
    REMOTE_INPUT("remoteInput"),
    READER("reader"),
    COMIC_PAGING("comicPaging"),
    NATIVE_NOTIFICATIONS("nativeNotifications"),
    LOCK_SCREEN_CONTROLS("lockScreenControls"),
    HARDWARE_DECODING("hardwareDecoding"),
    TELEMETRY("telemetry"),
    OFFLINE_CACHE("offlineCache"),
    QUICK_CONNECT("quickConnect"),
    DEEP_LINKS("deepLinks"),
}

data class PtvClientCapabilityReport(
    val platform: String,
    val deviceClass: String,
    val capabilities: Map<String, Boolean>,
    val contractVersion: String = PtvCoreContract.VERSION,
) {
    fun supports(capability: PtvClientCapability): Boolean = capabilities[capability.wireName] == true
}

data class PtvDiagnosticEvent(
    val category: String,
    val name: String,
    val appVersion: String,
    val platform: String,
    val deviceClass: String,
    val sessionId: String,
    val timestamp: String,
    val success: Boolean,
    val metadata: Map<String, Any?> = emptyMap(),
    val durationMs: Long? = null,
    val errorCode: String? = null,
    val serverIdHash: String? = null,
    val userIdHash: String? = null,
    val contractVersion: String = PtvCoreContract.VERSION,
)

object PtvCoreRedactor {
    private const val MAX_ARRAY_ITEMS = 32
    private const val MAX_METADATA_DEPTH = 4
    private const val MAX_METADATA_ENTRIES = 64
    private const val MAX_TEXT_LENGTH = 500

    private val sensitiveKey = Regex(
        "authorization|cookie|credential|password|secret|token|api[-_ ]?key|access[-_ ]?key|refresh[-_ ]?key|email|media[-_ ]?path",
        RegexOption.IGNORE_CASE,
    )
    private val credential = Regex(
        "((?:authorization|cookie|credential|password|secret|token|api[-_ ]?key)\\s*[:=]\\s*)(?:bearer\\s+|basic\\s+)?[^\\s\\\"',;]+",
        RegexOption.IGNORE_CASE,
    )
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)

    fun text(value: String, maxLength: Int = MAX_TEXT_LENGTH): String =
        email.replace(credential.replace(value, "$1[redacted]"), "[redacted-email]").take(maxLength.coerceAtLeast(0))

    fun metadata(value: Map<String, Any?>): Map<String, Any?> =
        value.entries.take(MAX_METADATA_ENTRIES).associate { (key, item) ->
            key to if (sensitiveKey.containsMatchIn(key)) "[redacted]" else sanitize(item, 0)
        }

    private fun sanitize(value: Any?, depth: Int): Any? = when {
        value == null || value is Boolean || value is Number -> value

        value is String -> text(value)

        depth >= MAX_METADATA_DEPTH -> "[truncated]"

        value is List<*> -> value.take(MAX_ARRAY_ITEMS).map { sanitize(it, depth + 1) }

        value is Map<*, *> -> value.entries.take(MAX_METADATA_ENTRIES).associate { (key, item) ->
            val safeKey = key.toString()
            safeKey to if (sensitiveKey.containsMatchIn(safeKey)) "[redacted]" else sanitize(item, depth + 1)
        }

        else -> text(value.toString())
    }
}

enum class PtvAuthenticationState {
    SIGNED_OUT,
    RESTORING,
    AUTHENTICATED,
    EXPIRED,
    ERROR,
}

data class PtvSessionSnapshot(
    val state: PtvAuthenticationState,
    val serverId: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val error: PtvCoreError? = null,
)

interface PtvCoreSessionService {
    suspend fun restore(): PtvCoreResult<PtvSessionSnapshot>
    suspend fun tokenForApi(): PtvCoreResult<String?>
    suspend fun logout(): PtvCoreResult<Unit>
    suspend fun invalidateToken(reason: String): PtvCoreResult<Unit>
    suspend fun switchServer(serverId: String): PtvCoreResult<PtvSessionSnapshot>
    suspend fun switchUser(userId: String): PtvCoreResult<PtvSessionSnapshot>
}

interface PtvCoreSettingsStore<T : Map<String, Any?>> {
    suspend fun load(scope: PtvCoreScope): PtvCoreResult<T>
    suspend fun save(scope: PtvCoreScope, schemaVersion: Int, values: T): PtvCoreResult<Unit>
    suspend fun reset(scope: PtvCoreScope): PtvCoreResult<Unit>
}

enum class PtvCoreInitializationStep(val wireName: String) {
    ENVIRONMENT("environment"),
    DEVICE_IDENTITY("device-identity"),
    SESSION("session"),
    SETTINGS("settings"),
    CACHE("cache"),
    API("api"),
    DIAGNOSTICS("diagnostics"),
    NAVIGATION("navigation"),
    PLAYBACK("playback"),
    READY("ready"),
}

enum class PtvCoreInitializationStatus {
    SUCCESS,
    DEGRADED,
    FAILED,
    SKIPPED,
}

data class PtvCoreInitializationStepResult(
    val step: PtvCoreInitializationStep,
    val status: PtvCoreInitializationStatus,
    val critical: Boolean,
    val durationMs: Long,
    val error: PtvCoreError? = null,
)

data class PtvCoreInitializationResult(
    val ready: Boolean,
    val degraded: Boolean,
    val routeToLogin: Boolean,
    val steps: List<PtvCoreInitializationStepResult>,
    val error: PtvCoreError? = null,
)

class PtvCoreInitializer(
    private val handlers: Map<PtvCoreInitializationStep, () -> PtvCoreResult<Unit>>,
    private val criticalSteps: Set<PtvCoreInitializationStep> = setOf(
        PtvCoreInitializationStep.ENVIRONMENT,
        PtvCoreInitializationStep.DEVICE_IDENTITY,
        PtvCoreInitializationStep.SESSION,
        PtvCoreInitializationStep.API,
    ),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var completed: PtvCoreInitializationResult? = null

    @Synchronized
    fun reset() {
        completed = null
    }

    @Synchronized
    fun initialize(): PtvCoreInitializationResult {
        completed?.let { return it }
        val results = mutableListOf<PtvCoreInitializationStepResult>()
        var terminalError: PtvCoreError? = null
        var routeToLogin = false

        PtvCoreInitializationStep.entries.forEach { step ->
            if (terminalError != null) {
                results += PtvCoreInitializationStepResult(
                    step,
                    PtvCoreInitializationStatus.SKIPPED,
                    step in criticalSteps,
                    0,
                )
                return@forEach
            }
            if (step == PtvCoreInitializationStep.READY) {
                results += PtvCoreInitializationStepResult(step, PtvCoreInitializationStatus.SUCCESS, false, 0)
                return@forEach
            }
            val handler = handlers[step]
            if (handler == null) {
                results += PtvCoreInitializationStepResult(
                    step,
                    PtvCoreInitializationStatus.SKIPPED,
                    step in criticalSteps,
                    0,
                )
                return@forEach
            }
            val started = clockMs()
            when (val result = handler()) {
                is PtvCoreResult.Success -> results += PtvCoreInitializationStepResult(
                    step,
                    PtvCoreInitializationStatus.SUCCESS,
                    step in criticalSteps,
                    (clockMs() - started).coerceAtLeast(0),
                )

                is PtvCoreResult.Failure -> {
                    val authenticationFailure = result.error.kind == PtvCoreErrorKind.AUTHENTICATION
                    val critical = step in criticalSteps || authenticationFailure
                    results += PtvCoreInitializationStepResult(
                        step,
                        if (critical) PtvCoreInitializationStatus.FAILED else PtvCoreInitializationStatus.DEGRADED,
                        critical,
                        (clockMs() - started).coerceAtLeast(0),
                        result.error,
                    )
                    if (critical) terminalError = result.error
                    if (authenticationFailure) routeToLogin = true
                }
            }
        }

        return PtvCoreInitializationResult(
            ready = terminalError == null,
            degraded = results.any { it.status == PtvCoreInitializationStatus.DEGRADED },
            routeToLogin = routeToLogin,
            steps = results,
            error = terminalError,
        ).also { completed = it }
    }
}
