package org.jellyfin.mobile.feature.music.auto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.auth.MobileAuthState
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.feature.music.MusicPlaybackController
import org.jellyfin.mobile.feature.music.MusicPlaybackState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.jellyfin.mobile.feature.music.MusicRepository
import org.jellyfin.mobile.feature.music.MusicSongActionHandler
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.createMediaNotificationChannel
import org.koin.android.ext.android.inject
import timber.log.Timber

@UnstableApi
@Suppress("LargeClass") // Media3 requires lifecycle, session, and notification ownership in this service.
class PtvMusicService : MediaLibraryService() {
    private val apiClientController: ApiClientController by inject()
    private val musicRepository: MusicRepository by inject()
    private val playbackController: MusicPlaybackController by inject()
    private val songActionHandler: MusicSongActionHandler by inject()
    private val resumeStore: PtvMusicAutoResumeStore by inject()
    private val resumeCoordinator: PtvMusicAutoResumeCoordinator by inject()
    private val serverDao: ServerDao by inject()
    private val userDao: UserDao by inject()
    private val imageLoader: ImageLoader by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager: NotificationManager by lazy { getSystemService()!! }

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var libraryCallback: PtvMusicLibrarySessionCallback? = null
    private var sessionPlayer: PtvMusicSessionPlayer? = null
    private var foregroundStartRequested = false
    private var media3ForegroundRequired = false
    private var foregroundPromotionFailed = false
    private var mediaSessionPlaybackActive = false
    private var lastNotificationRefreshSnapshot: PtvMusicNotificationRefreshSnapshot? = null
    private var notificationArtworkTrackId: String? = null
    private var notificationArtwork: Bitmap? = null
    private var notificationArtworkJob: Job? = null

    @Volatile
    private var queueAuthenticationProof: PtvMusicQueueAuthenticationProof? = null
    private var pendingQueueAuthentication: PtvMusicQueueAuthenticationProof? = null
    private var queueAuthenticationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        setForegroundServiceTimeoutMs(PTV_FOREGROUND_SERVICE_TIMEOUT_MS)
        isRunning = true
        Timber.i(
            "PTV Music MediaLibraryService created serviceId=${runtimeInstanceId(this)} " +
                "controllerId=${playbackController.instanceId} repositoryId=${musicRepository.instanceId} " +
                "playerId=${playbackController.playerInstanceId}",
        )
        resumeStore.markServiceLifecycle("created serviceId=${runtimeInstanceId(this)}")
        Timber.i(
            "PTV Music notification permission status onCreate " +
                "granted=${notificationPermissionGranted()} sdk=${Build.VERSION.SDK_INT}",
        )
        configureNotificationProvider()

        val sessionReady = serviceScope.async(Dispatchers.IO) {
            Timber.i("PTV Music Auto restoring saved Jellyfin session")
            runCatching {
                apiClientController.loadSavedServerUser()
            }.onSuccess { userId ->
                if (userId == null) {
                    Timber.w("PTV Music Auto saved Jellyfin session requires phone sign-in or reconnection")
                } else {
                    Timber.i("PTV Music Auto saved Jellyfin session restore finished authenticated=true")
                }
            }.onFailure { error ->
                Timber.w(error, "PTV Music Auto could not restore the saved Jellyfin session")
            }.getOrNull()
        }

        resumeStore.markAutoConnection()
        sessionPlayer = PtvMusicSessionPlayer(
            playbackController,
            ::isCurrentSessionQueueAuthenticated,
            pendingQueueAuthenticationProof = {
                // Media3 may send Play before the state collector starts account proof for a
                // restored queue. Start that proof here without exposing the queue prematurely.
                val state = playbackController.state.value
                refreshQueueAuthenticationProof(state)
                pendingQueueAuthentication?.takeIf { proof ->
                    proof.authentication == apiClientController.authState.value && state.queueOwner == proof.owner
                }
            },
        )
        val initialPlaybackState = playbackController.state.value
        val initialButtons = PtvMusicAutoCommand.buttonsFor(visibleSessionPlaybackState(initialPlaybackState))
        val libraryCallback = PtvMusicLibrarySessionCallback(
            context = this,
            repository = musicRepository,
            playbackController = playbackController,
            songActionHandler = songActionHandler,
            resumeStore = resumeStore,
            resumeCoordinator = resumeCoordinator,
            savedSessionReady = sessionReady,
            authState = apiClientController.authState,
            serverUrlForLocalId = { id -> serverDao.getServer(id)?.hostname?.trimEnd('/') },
        )
        this.libraryCallback = libraryCallback
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            requireNotNull(sessionPlayer),
            libraryCallback,
        )
            .setId(SESSION_ID)
            .setSessionActivity(buildSessionActivityPendingIntent())
            .setCustomLayout(initialButtons)
            .setMediaButtonPreferences(initialButtons)
            .build()
        publishSessionPlaybackState(initialPlaybackState, reason = "created", force = true)
        publishRuntimeStatus()

        serviceScope.launch {
            apiClientController.authState.collect { authState ->
                playbackController.discardQueueIfOwnershipChanged()
                publishSessionPlaybackState(
                    playbackController.state.value,
                    reason = "authenticationChanged",
                    force = true,
                )
                libraryCallback.onAuthenticationChanged()
                mediaLibrarySession?.notifyChildrenChanged(
                    PtvMusicAutoIds.ROOT,
                    ptvMusicAutoRootItemsFor(authState).size,
                    null,
                )
            }
        }

        serviceScope.launch {
            val restoreObserver = PtvMusicAuthenticatedRestoreObserver(
                currentAuthentication = { apiClientController.authState.value },
                currentLocalOwner = resumeStore::currentLocalQueueOwner,
                loadSavedState = resumeStore::loadPlaybackState,
                resolveExternalUserId = { owner ->
                    withContext(Dispatchers.IO) {
                        runCatching { userDao.getServerUser(owner.serverId, owner.userId)?.user?.userId?.toString() }
                            .getOrNull()
                    }
                },
                restoreQueue = resumeCoordinator::restoreQueueIfNeeded,
            )
            observePtvMusicAuthenticatedQueueRestoration(
                savedSessionReady = sessionReady,
                authenticationStates = apiClientController.authState,
                observer = restoreObserver,
            ) { result ->
                Timber.i("PTV Music Auto authenticated queue restore: ${result.message}")
                publishSessionPlaybackState(
                    playbackController.state.value,
                    reason = "authenticatedRestore",
                    force = true,
                )
                mediaLibrarySession?.notifyChildrenChanged(
                    PtvMusicAutoIds.ROOT,
                    ptvMusicAutoRootItemsFor(apiClientController.authState.value).size,
                    null,
                )
            }
        }
        serviceScope.launch {
            playbackController.state
                .map(MusicPlaybackState::toAutoRuntimeSnapshot)
                .distinctUntilChanged()
                .collect { snapshot ->
                    publishRuntimeStatus()
                    val playbackState = playbackController.state.value
                    publishSessionPlaybackState(playbackState, reason = "controllerState")
                    Timber.d(
                        "PTV Music Auto shared runtime state changed " +
                            "track=${snapshot.currentTrackId ?: "<none>"} queue=${snapshot.queueSize} " +
                            "index=${snapshot.currentIndex} playing=${snapshot.isPlaying} " +
                            "repeat=${snapshot.repeatMode} shuffle=${snapshot.shuffleEnabled} " +
                            "favorite=${snapshot.currentTrackFavorite}",
                    )
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val superResult = super.onStartCommand(intent, flags, startId)
        val playbackState = playbackController.state.value
        val startSource = intent?.getStringExtra(EXTRA_START_SOURCE) ?: "<none>"
        Timber.i(
            "PTV Music MediaLibraryService startCommand action=${intent?.action ?: "<none>"} " +
                "source=$startSource startId=$startId flags=$flags " +
                "superResult=$superResult notificationPermissionGranted=${notificationPermissionGranted()} " +
                "currentTrack=${playbackState.currentItem?.id ?: "<none>"} queue=${playbackState.queue.size} " +
                "playing=${playbackState.isPlaying} buffering=${playbackState.isBuffering}",
        )
        resumeStore.markServiceLifecycle(
            "startCommand action=${intent?.action ?: "<none>"} source=$startSource",
        )
        if (handleNotificationAction(intent?.action)) {
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_KEEP_ALIVE && !playbackState.hasCurrent && playbackState.queue.isEmpty()) {
            Timber.w("PTV Music ignored empty foreground-service start source=$startSource")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        publishSessionPlaybackState(
            playbackController.state.value,
            reason = "startCommand:$startSource",
            force = true,
        )
        return superResult
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Timber.i(
            "PTV Music MediaLibraryService session requested by ${controllerInfo.packageName} " +
                "serviceId=${runtimeInstanceId(this)} sessionId=${mediaLibrarySession?.let(::runtimeInstanceId)} " +
                "controllerId=${playbackController.instanceId} repositoryId=${musicRepository.instanceId} " +
                "playerId=${playbackController.playerInstanceId}",
        )
        resumeStore.markAutoConnection()
        resumeStore.markSessionCommand("session requested by ${controllerInfo.packageName}")
        publishRuntimeStatus()
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val playbackState = visibleSessionPlaybackState(playbackController.state.value)
        if (
            shouldKeepPtvMusicServiceOnTaskRemoved(
                foregroundStartRequested,
                playbackState,
                playbackController.mediaSessionPlayer.playWhenReady,
                sessionPlayer?.hasPendingPlayFor(pendingQueueAuthentication) == true,
            )
        ) {
            Timber.i(
                "PTV Music task removed during active or foreground playback; keeping service alive " +
                    "playing=${playbackState.isPlaying} buffering=${playbackState.isBuffering} " +
                    "queue=${playbackState.queue.size} currentTrack=${playbackState.currentItem?.id}",
            )
            return
        }

        Timber.i("PTV Music task removed without foreground playback; allowing Media3 cleanup")
        super.onTaskRemoved(rootIntent)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        media3ForegroundRequired = startInForegroundRequired
        val playbackState = visibleSessionPlaybackState(playbackController.state.value)
        when {
            startInForegroundRequired && !foregroundStartRequested -> {
                Timber.i(
                    "PTV Music foreground start requested by Media3 " +
                        "currentTrack=${playbackState.currentItem?.id ?: "<none>"} " +
                        "notificationPermissionGranted=${notificationPermissionGranted()}",
                )
            }

            !startInForegroundRequired && foregroundStartRequested -> {
                Timber.i(
                    "PTV Music foreground stop requested by Media3 " +
                        "currentTrack=${playbackState.currentItem?.id ?: "<none>"} " +
                        "playing=${playbackState.isPlaying} buffering=${playbackState.isBuffering}",
                )
            }
        }
        Timber.i(
            "PTV Music Media3 notification update requested; " +
                "foregroundRequired=$startInForegroundRequired playbackOngoing=${isPlaybackOngoing()} " +
                "notificationPermissionGranted=${notificationPermissionGranted()} " +
                "currentTrack=${playbackState.currentItem?.id ?: "<none>"} queue=${playbackState.queue.size} " +
                "playing=${playbackState.isPlaying} buffering=${playbackState.isBuffering}",
        )
        postMediaNotification(
            playbackState = playbackState,
            reason = "media3Update",
            force = true,
            foregroundRequired = requiresPtvMusicForeground(
                startInForegroundRequired,
                playbackState,
                playbackController.mediaSessionPlayer.playWhenReady,
            ),
        )
    }

    override fun onDestroy() {
        Timber.i(
            "PTV Music MediaLibraryService destroyed serviceId=${runtimeInstanceId(this)} " +
                "controllerId=${playbackController.instanceId} playerId=${playbackController.playerInstanceId} " +
                "currentTrack=${playbackController.state.value.currentItem?.id ?: "<none>"} " +
                "queue=${playbackController.state.value.queue.size} playing=${playbackController.state.value.isPlaying}",
        )
        resumeStore.markServiceLifecycle("destroyed serviceId=${runtimeInstanceId(this)}")
        stopMediaNotification(reason = "destroy", remove = true)
        isRunning = false
        libraryCallback?.onServiceDestroyed()
        libraryCallback = null
        serviceScope.cancel()
        if (mediaSessionPlaybackActive) {
            Timber.i("PTV Music media session inactive reason=destroy")
        }
        lastNotificationRefreshSnapshot = null
        mediaLibrarySession?.release()
        mediaLibrarySession = null
        sessionPlayer = null
        super.onDestroy()
    }

    private fun configureNotificationProvider() {
        setMediaNotificationProvider(PtvMusicNotificationProvider(this))
        Timber.i(
            "PTV Music Media3 notification provider configured " +
                "notificationId=${Constants.MEDIA_PLAYER_NOTIFICATION_ID} " +
                "channelId=${Constants.MEDIA_NOTIFICATION_CHANNEL_ID}",
        )
    }

    private fun buildSessionActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            SESSION_ACTIVITY_REQUEST_CODE,
            intent,
            Constants.PENDING_INTENT_FLAGS,
        )
    }

    private fun isCurrentSessionQueueAuthenticated(): Boolean {
        val playbackState = playbackController.state.value
        return playbackController.isCurrentQueueOwnedByAuthenticatedUser() &&
            isPtvMusicQueueAuthenticationCurrent(
                playbackState,
                apiClientController.authState.value,
                queueAuthenticationProof,
            )
    }

    private fun visibleSessionPlaybackState(playbackState: MusicPlaybackState): MusicPlaybackState =
        if (playbackController.state.value === playbackState && isCurrentSessionQueueAuthenticated()) {
            playbackState
        } else {
            MusicPlaybackState()
        }

    private fun refreshQueueAuthenticationProof(playbackState: MusicPlaybackState) {
        val owner = playbackState.queueOwner
        val authentication = apiClientController.authState.value as? MobileAuthState.Authenticated
        if (!playbackState.hasCurrent || owner == null || authentication == null) {
            queueAuthenticationProof = null
            pendingQueueAuthentication = null
            queueAuthenticationJob?.cancel()
            sessionPlayer?.cancelPendingPlay()
            return
        }
        if (isPtvMusicQueueAuthenticationCurrent(playbackState, authentication, queueAuthenticationProof)) return

        val requestedProof = PtvMusicQueueAuthenticationProof(owner, authentication)
        if (pendingQueueAuthentication == requestedProof) return
        queueAuthenticationProof = null
        pendingQueueAuthentication = requestedProof
        queueAuthenticationJob?.cancel()
        queueAuthenticationJob = serviceScope.launch {
            val resolvedUserId = withContext(Dispatchers.IO) {
                runCatching { userDao.getServerUser(owner.serverId, owner.userId)?.user?.userId?.toString() }
                    .getOrNull()
            }
            if (pendingQueueAuthentication != requestedProof) return@launch
            pendingQueueAuthentication = null
            val currentState = playbackController.state.value
            if (currentState.queueOwner != owner || apiClientController.authState.value != authentication) return@launch

            val verifiedProof = verifiedPtvMusicQueueAuthenticationProof(
                currentState,
                authentication,
                resolvedUserId,
            )
            if (verifiedProof != null && playbackController.isCurrentQueueOwnedByAuthenticatedUser()) {
                queueAuthenticationProof = verifiedProof
                sessionPlayer?.onQueueAuthenticationVerified(requestedProof, verifiedProof)
                publishSessionPlaybackState(currentState, reason = "authenticationVerified", force = true)
            } else {
                sessionPlayer?.cancelPendingPlay()
                playbackController.stop(source = "authenticationMismatch")
                publishSessionPlaybackState(
                    playbackController.state.value,
                    reason = "authenticationMismatch",
                    force = true,
                )
            }
        }
    }

    private fun publishSessionPlaybackState(
        playbackState: MusicPlaybackState,
        reason: String,
        force: Boolean = false,
    ) {
        refreshQueueAuthenticationProof(playbackState)
        val visibleState = visibleSessionPlaybackState(playbackState)
        val shouldBeActive = visibleState.hasCurrent || visibleState.queue.isNotEmpty()
        if (force || shouldBeActive != mediaSessionPlaybackActive) {
            Timber.i(
                "PTV Music media session ${if (shouldBeActive) "active" else "inactive"} " +
                    "reason=$reason currentTrack=${visibleState.currentItem?.id ?: "<none>"} " +
                    "queue=${visibleState.queue.size} playing=${visibleState.isPlaying} " +
                    "buffering=${visibleState.isBuffering}",
            )
        }
        mediaSessionPlaybackActive = shouldBeActive

        sessionPlayer?.notifyControllerStateChanged(visibleState, force = force)
        val buttons = PtvMusicAutoCommand.buttonsFor(visibleState)
        mediaLibrarySession?.setCustomLayout(buttons)
        mediaLibrarySession?.setMediaButtonPreferences(buttons)
        mediaLibrarySession?.notifyChildrenChanged(
            PtvMusicAutoCategory.RECOMMENDATIONS.mediaId,
            visibleState.queue.size,
            null,
        )
        refreshMediaNotification(visibleState, reason = reason, force = force)
    }

    private fun refreshMediaNotification(playbackState: MusicPlaybackState, reason: String, force: Boolean) {
        if (mediaLibrarySession == null) {
            if (force) {
                Timber.w("PTV Music Media3 notification refresh skipped; media session is not ready reason=$reason")
            }
            return
        }
        val shouldShowNotification = playbackState.hasCurrent || playbackState.queue.isNotEmpty()
        if (!shouldShowNotification) {
            stopMediaNotification(reason = reason, remove = true)
            return
        }

        val foregroundRequired = requiresPtvMusicForeground(
            media3Required = false,
            playbackState = playbackState,
            playbackRequested = playbackController.mediaSessionPlayer.playWhenReady,
        )
        val snapshot = PtvMusicNotificationRefreshSnapshot(
            currentTrackId = playbackState.currentItem?.id?.toString(),
            queueSize = playbackState.queue.size,
            isPlaying = playbackState.isPlaying,
            isBuffering = playbackState.isBuffering,
            foregroundRequired = foregroundRequired,
            notificationPermissionGranted = notificationPermissionGranted(),
        )
        if (!force && snapshot == lastNotificationRefreshSnapshot) return
        lastNotificationRefreshSnapshot = snapshot

        Timber.i(
            "PTV Music Media3 notification refresh requested reason=$reason " +
                "foregroundRequired=$foregroundRequired notificationPermissionGranted=${snapshot.notificationPermissionGranted} " +
                "currentTrack=${snapshot.currentTrackId ?: "<none>"} queue=${snapshot.queueSize} " +
                "playing=${snapshot.isPlaying} buffering=${snapshot.isBuffering}",
        )
        postMediaNotification(
            playbackState = playbackState,
            reason = reason,
            force = force,
            foregroundRequired = requiresPtvMusicForeground(
                media3ForegroundRequired,
                playbackState,
                playbackController.mediaSessionPlayer.playWhenReady,
            ),
        )
    }

    private fun handleNotificationAction(action: String?): Boolean {
        when (action) {
            ACTION_NOTIFICATION_PLAY, ACTION_NOTIFICATION_PREVIOUS, ACTION_NOTIFICATION_NEXT -> {
                Timber.i("PTV Music notification transport action=$action")
                dispatchNotificationTransportAction(action, sessionPlayer)
            }

            ACTION_NOTIFICATION_PAUSE -> {
                Timber.i("PTV Music notification action pause")
                sessionPlayer?.cancelPendingPlay()
                playbackController.pause()
            }

            ACTION_NOTIFICATION_STOP -> {
                Timber.i("PTV Music notification action stop")
                sessionPlayer?.cancelPendingPlay()
                playbackController.stop(source = "notification")
                stopMediaNotification(reason = "notificationStop", remove = true)
                stopSelf()
                return true
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun postMediaNotification(
        playbackState: MusicPlaybackState,
        reason: String,
        force: Boolean,
        foregroundRequired: Boolean,
    ) {
        if (foregroundPromotionFailed) return
        if (!isCurrentSessionQueueAuthenticated()) return stopMediaNotification(reason = reason, remove = true)
        val session = mediaLibrarySession ?: run {
            Timber.w("PTV Music foreground notification skipped; media session is not ready reason=$reason")
            return
        }
        if (!playbackState.hasCurrent && playbackState.queue.isEmpty()) {
            stopMediaNotification(reason = reason, remove = true)
            return
        }

        createMediaNotificationChannel(notificationManager)
        val notification = buildMediaNotification(session, playbackState, foregroundRequired)
        val wasForeground = foregroundStartRequested
        runCatching {
            if (foregroundRequired) {
                if (AndroidVersion.isAtLeastQ) {
                    startForeground(
                        Constants.MEDIA_PLAYER_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startForeground(Constants.MEDIA_PLAYER_NOTIFICATION_ID, notification)
                }
            } else {
                notificationManager.notify(Constants.MEDIA_PLAYER_NOTIFICATION_ID, notification)
                if (wasForeground) {
                    stopForegroundCompat(remove = false)
                    // Android adds foreground-only flags to the first post. Refresh after detaching
                    // so a paused notification is genuinely dismissible.
                    notification.flags = clearPtvMusicForegroundNotificationFlags(notification.flags)
                    notificationManager.notify(Constants.MEDIA_PLAYER_NOTIFICATION_ID, notification)
                }
            }
        }.onSuccess {
            foregroundStartRequested = foregroundRequired
            Timber.i(
                "PTV Music notification posted/updated reason=$reason force=$force " +
                    "foreground=$foregroundRequired " +
                    "currentTrack=${playbackState.currentItem?.id ?: "<none>"} queue=${playbackState.queue.size} " +
                    "playing=${playbackState.isPlaying} buffering=${playbackState.isBuffering} " +
                    "notificationPermissionGranted=${notificationPermissionGranted()}",
            )
            refreshNotificationArtwork(playbackState, reason = reason)
        }.onFailure { error ->
            Timber.e(
                error,
                "PTV Music foreground notification failed reason=$reason " +
                    "currentTrack=${playbackState.currentItem?.id ?: "<none>"} queue=${playbackState.queue.size} " +
                    "playing=${playbackState.isPlaying} buffering=${playbackState.isBuffering} " +
                    "notificationPermissionGranted=${notificationPermissionGranted()}",
            )
            if (foregroundRequired) {
                foregroundPromotionFailed = true
                playbackController.suspendForPlaybackServiceFailure()
                stopMediaNotification(reason = "foregroundPromotionFailed", remove = true)
                stopSelf()
            }
        }
    }

    private fun buildMediaNotification(
        session: MediaLibrarySession,
        playbackState: MusicPlaybackState,
        foregroundRequired: Boolean,
    ): Notification {
        val item = playbackState.currentItem
        val trackId = item?.id?.toString()
        val artwork = notificationArtwork.takeIf { trackId != null && notificationArtworkTrackId == trackId }
        val playbackAction = when {
            playbackState.isPlaying || playbackState.isBuffering -> notificationAction(
                icon = R.drawable.ic_pause_black_42dp,
                title = R.string.notification_action_pause,
                action = ACTION_NOTIFICATION_PAUSE,
                requestCode = NOTIFICATION_ACTION_PAUSE_REQUEST_CODE,
            )

            else -> notificationAction(
                icon = R.drawable.ic_play_black_42dp,
                title = R.string.notification_action_play,
                action = ACTION_NOTIFICATION_PLAY,
                requestCode = NOTIFICATION_ACTION_PLAY_REQUEST_CODE,
            )
        }

        return Notification.Builder(this).apply {
            if (AndroidVersion.isAtLeastO) {
                setChannelId(Constants.MEDIA_NOTIFICATION_CHANNEL_ID)
                setColorized(artwork != null)
            } else {
                @Suppress("DEPRECATION")
                setPriority(Notification.PRIORITY_LOW)
            }
            setCategory(Notification.CATEGORY_TRANSPORT)
            if (foregroundRequired) {
                setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(session.platformToken)
                        .setShowActionsInCompactView(0, 1, 2),
                )
            } else {
                // Android 15+ forces valid MediaStyle notifications to be non-clearable. A paused
                // transport notification keeps the resume actions without trapping the card.
                setCategory(Notification.CATEGORY_TRANSPORT)
            }
            setSmallIcon(R.drawable.ic_notification)
            setContentTitle(item?.title ?: getString(R.string.music_notification_channel))
            setContentText(item?.artist ?: item?.subtitle ?: item?.album)
            setSubText(item?.album)
            setVisibility(Notification.VISIBILITY_PUBLIC)
            setOnlyAlertOnce(true)
            setShowWhen(false)
            setOngoing(foregroundRequired)
            setContentIntent(buildSessionActivityPendingIntent())
            setDeleteIntent(
                servicePendingIntent(
                    action = ACTION_NOTIFICATION_STOP,
                    requestCode = NOTIFICATION_ACTION_STOP_REQUEST_CODE,
                ),
            )
            artwork?.let(::setLargeIcon)
            addAction(
                notificationAction(
                    icon = R.drawable.ic_skip_previous_black_32dp,
                    title = R.string.notification_action_previous,
                    action = ACTION_NOTIFICATION_PREVIOUS,
                    requestCode = NOTIFICATION_ACTION_PREVIOUS_REQUEST_CODE,
                ),
            )
            addAction(playbackAction)
            addAction(
                notificationAction(
                    icon = R.drawable.ic_skip_next_black_32dp,
                    title = R.string.notification_action_next,
                    action = ACTION_NOTIFICATION_NEXT,
                    requestCode = NOTIFICATION_ACTION_NEXT_REQUEST_CODE,
                ),
            )
        }.build()
    }

    private fun notificationAction(icon: Int, title: Int, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            icon,
            getString(title),
            servicePendingIntent(action = action, requestCode = requestCode),
        ).build()

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action)
            .setClass(this, PtvMusicService::class.java)
            .putExtra(EXTRA_START_SOURCE, "notification")
        return PendingIntent.getService(this, requestCode, intent, Constants.PENDING_INTENT_FLAGS)
    }

    private fun refreshNotificationArtwork(playbackState: MusicPlaybackState, reason: String) {
        if (!isCurrentSessionQueueAuthenticated()) return
        val item = playbackState.currentItem ?: return
        val trackId = item.id.toString()
        val posterUrl = item.posterUrl
        when {
            posterUrl.isNullOrBlank() -> {
                notificationArtworkJob?.cancel()
                notificationArtworkTrackId = trackId
                notificationArtwork = null
                Timber.i("PTV Music notification artwork unavailable reason=$reason track=$trackId")
            }

            notificationArtworkTrackId == trackId && notificationArtwork != null -> Unit

            else -> {
                notificationArtworkJob?.cancel()
                notificationArtworkTrackId = trackId
                notificationArtwork = null
                notificationArtworkJob = serviceScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        runCatching {
                            imageLoader.execute(
                                ImageRequest.Builder(this@PtvMusicService)
                                    .data(posterUrl)
                                    .build(),
                            ).image?.toBitmap()
                        }.onFailure { error ->
                            Timber.w(error, "PTV Music notification artwork load failed track=$trackId")
                        }.getOrNull()
                    }
                    val stillCurrent = playbackController.state.value.currentItem?.id?.toString() == trackId &&
                        isCurrentSessionQueueAuthenticated()
                    if (bitmap != null && stillCurrent) {
                        notificationArtwork = bitmap
                        Timber.i("PTV Music notification artwork loaded track=$trackId")
                        postMediaNotification(
                            playbackController.state.value,
                            reason = "artworkLoaded:$reason",
                            force = true,
                            foregroundRequired = requiresPtvMusicForeground(
                                media3ForegroundRequired,
                                playbackController.state.value,
                                playbackController.mediaSessionPlayer.playWhenReady,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun stopMediaNotification(reason: String, remove: Boolean) {
        notificationArtworkJob?.cancel()
        notificationArtworkJob = null
        notificationArtwork = null
        notificationArtworkTrackId = null
        if (foregroundStartRequested) {
            stopForegroundCompat(remove)
        }
        if (remove) {
            notificationManager.cancel(Constants.MEDIA_PLAYER_NOTIFICATION_ID)
        }
        foregroundStartRequested = false
        lastNotificationRefreshSnapshot = null
        Timber.i("PTV Music foreground notification stopped reason=$reason remove=$remove")
    }

    private fun stopForegroundCompat(remove: Boolean) {
        if (AndroidVersion.isAtLeastN) {
            stopForeground(
                if (remove) {
                    Service.STOP_FOREGROUND_REMOVE
                } else {
                    Service.STOP_FOREGROUND_DETACH
                },
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(remove)
        }
    }

    private fun notificationPermissionGranted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        @Volatile
        internal var isRunning = false
            private set

        const val SESSION_ID = "ptv_music"
        const val ACTION_KEEP_ALIVE = "org.piggietv.music.action.KEEP_ALIVE"
        const val EXTRA_START_SOURCE = "org.piggietv.music.extra.START_SOURCE"
        private const val ACTION_NOTIFICATION_PLAY = "org.piggietv.music.notification.PLAY"
        private const val ACTION_NOTIFICATION_PAUSE = "org.piggietv.music.notification.PAUSE"
        private const val ACTION_NOTIFICATION_PREVIOUS = "org.piggietv.music.notification.PREVIOUS"
        private const val ACTION_NOTIFICATION_NEXT = "org.piggietv.music.notification.NEXT"
        private const val ACTION_NOTIFICATION_STOP = "org.piggietv.music.notification.STOP"

        /** Notification transport commands use the same proof-gated player as Media3 commands. */
        internal fun dispatchNotificationTransportAction(
            action: String?,
            sessionPlayer: PtvMusicSessionPlayer?,
        ): Boolean = when (action) {
            ACTION_NOTIFICATION_PLAY -> {
                sessionPlayer?.play()
                true
            }

            ACTION_NOTIFICATION_PREVIOUS -> {
                sessionPlayer?.seekToPreviousMediaItem()
                true
            }

            ACTION_NOTIFICATION_NEXT -> {
                sessionPlayer?.seekToNextMediaItem()
                true
            }

            else -> false
        }

        private const val SESSION_ACTIVITY_REQUEST_CODE = 4102
        private const val NOTIFICATION_ACTION_PLAY_REQUEST_CODE = 4201
        private const val NOTIFICATION_ACTION_PAUSE_REQUEST_CODE = 4202
        private const val NOTIFICATION_ACTION_PREVIOUS_REQUEST_CODE = 4203
        private const val NOTIFICATION_ACTION_NEXT_REQUEST_CODE = 4204
        private const val NOTIFICATION_ACTION_STOP_REQUEST_CODE = 4205
        private const val PTV_FOREGROUND_SERVICE_TIMEOUT_MS = 0L
    }

    private fun publishRuntimeStatus() {
        val playbackState = visibleSessionPlaybackState(playbackController.state.value)
        resumeStore.markRuntimeStatus(
            controllerInstanceId = playbackController.instanceId,
            repositoryInstanceId = musicRepository.instanceId,
            playerInstanceId = playbackController.playerInstanceId,
            mediaSessionInstanceId = mediaLibrarySession?.let(::runtimeInstanceId),
            activeQueueCount = playbackState.queue.size,
            currentTrackId = playbackState.currentItem?.id?.toString(),
            currentTrackTitle = playbackState.currentItem?.title,
        )
    }
}

@UnstableApi
private class PtvMusicNotificationProvider(private val appContext: Context) :
    DefaultMediaNotificationProvider(
        appContext,
        DefaultMediaNotificationProvider.NotificationIdProvider { Constants.MEDIA_PLAYER_NOTIFICATION_ID },
        Constants.MEDIA_NOTIFICATION_CHANNEL_ID,
        R.string.music_notification_channel,
    ) {
    init {
        setSmallIcon(R.drawable.ic_notification)
    }

    override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence =
        metadata.title?.takeIf { title -> title.toString().isNotBlank() }
            ?: appContext.getString(R.string.music_notification_channel)

    override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
        val artist = metadata.artist?.toString()?.takeIf(String::isNotBlank)
        val album = metadata.albumTitle?.toString()?.takeIf(String::isNotBlank)
        return listOfNotNull(artist, album)
            .joinToString(" - ")
            .takeIf(String::isNotBlank)
            ?: super.getNotificationContentText(metadata)
    }
}

internal data class PtvMusicAutoRuntimeSnapshot(
    val currentTrackId: String?,
    val currentTrack: PtvMusicAutoMediaItemSnapshot?,
    val currentIndex: Int,
    val queue: List<PtvMusicAutoMediaItemSnapshot>,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val repeatMode: MusicRepeatMode,
    val shuffleEnabled: Boolean,
    val currentTrackFavorite: Boolean,
) {
    val queueSize: Int get() = queue.size
}

/** Restores only a saved queue whose local and Jellyfin identities match the active session. */
internal class PtvMusicAuthenticatedRestoreObserver(
    private val currentAuthentication: () -> MobileAuthState,
    private val currentLocalOwner: () -> PtvMusicAutoQueueOwner?,
    private val loadSavedState: () -> PtvMusicAutoResumeState?,
    private val resolveExternalUserId: suspend (PtvMusicAutoQueueOwner) -> String?,
    private val restoreQueue: suspend () -> PtvMusicAutoRestoreResult,
) {
    private var lastAttemptedProof: PtvMusicQueueAuthenticationProof? = null

    suspend fun onAuthenticationChanged(authenticationState: MobileAuthState): PtvMusicAutoRestoreResult? {
        val proof = eligibleSavedQueueProof(authenticationState) ?: return null
        val authentication = proof.authentication
        val owner = proof.owner

        val resolvedUserId = resolveExternalUserId(owner)
        if (resolvedUserId != authentication.userId || !isSavedQueueIdentityCurrent(proof)) return null

        lastAttemptedProof = proof
        val result = restoreQueue()
        if (result == PtvMusicAutoRestoreResult.Failed) lastAttemptedProof = null
        return result.takeIf {
            currentAuthentication() == authentication && currentLocalOwner() == owner
        }
    }

    private fun eligibleSavedQueueProof(authenticationState: MobileAuthState): PtvMusicQueueAuthenticationProof? {
        val authentication = authenticationState as? MobileAuthState.Authenticated
        val owner = if (authentication != null) currentLocalOwner() else null
        if (authentication == null || owner == null) {
            lastAttemptedProof = null
            return null
        }
        val proof = PtvMusicQueueAuthenticationProof(owner, authentication)
        if (lastAttemptedProof != null && lastAttemptedProof != proof) lastAttemptedProof = null
        return proof.takeIf {
            authentication.serverId == owner.serverId.toString() &&
                loadSavedState()?.queueOwnerOrNull() == owner &&
                lastAttemptedProof != proof
        }
    }

    private fun isSavedQueueIdentityCurrent(proof: PtvMusicQueueAuthenticationProof): Boolean =
        currentAuthentication() == proof.authentication &&
            currentLocalOwner() == proof.owner &&
            loadSavedState()?.queueOwnerOrNull() == proof.owner
}

/** A failed initial saved-session lookup must not prevent a later authenticated restore. */
internal suspend fun observePtvMusicAuthenticatedQueueRestoration(
    savedSessionReady: Deferred<*>,
    authenticationStates: StateFlow<MobileAuthState>,
    observer: PtvMusicAuthenticatedRestoreObserver,
    onRestored: (PtvMusicAutoRestoreResult.Restored) -> Unit,
) {
    savedSessionReady.await()
    authenticationStates.collect { authentication ->
        val result = observer.onAuthenticationChanged(authentication)
        if (result is PtvMusicAutoRestoreResult.Restored) onRestored(result)
    }
}

internal data class PtvMusicQueueAuthenticationProof(
    val owner: PtvMusicAutoQueueOwner,
    val authentication: MobileAuthState.Authenticated,
) {
    override fun toString(): String = "PtvMusicQueueAuthenticationProof([REDACTED])"
}

internal fun verifiedPtvMusicQueueAuthenticationProof(
    playbackState: MusicPlaybackState,
    authenticationState: MobileAuthState,
    resolvedQueueUserId: String?,
): PtvMusicQueueAuthenticationProof? {
    val authentication = authenticationState as? MobileAuthState.Authenticated ?: return null
    val owner = playbackState.queueOwner ?: return null
    if (!playbackState.hasCurrent || playbackState.queue.isEmpty()) return null
    if (authentication.serverId != owner.serverId.toString() || authentication.userId != resolvedQueueUserId) {
        return null
    }
    return PtvMusicQueueAuthenticationProof(owner, authentication)
}

internal fun isPtvMusicQueueAuthenticationCurrent(
    playbackState: MusicPlaybackState,
    authenticationState: MobileAuthState,
    proof: PtvMusicQueueAuthenticationProof?,
): Boolean {
    val authentication = authenticationState as? MobileAuthState.Authenticated ?: return false
    return proof != null && playbackState.hasCurrent && playbackState.queue.isNotEmpty() &&
        playbackState.queueOwner == proof.owner &&
        authentication.serverId == proof.authentication.serverId &&
        authentication.userId == proof.authentication.userId
}

private data class PtvMusicNotificationRefreshSnapshot(
    val currentTrackId: String?,
    val queueSize: Int,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val foregroundRequired: Boolean,
    val notificationPermissionGranted: Boolean,
)

internal fun MusicPlaybackState.toAutoRuntimeSnapshot(): PtvMusicAutoRuntimeSnapshot = PtvMusicAutoRuntimeSnapshot(
    currentTrackId = currentItem?.id?.toString(),
    currentTrack = currentItem?.toAutoMediaItemSnapshot(),
    currentIndex = currentIndex,
    queue = queue.map { it.toAutoMediaItemSnapshot() },
    isPlaying = isPlaying,
    isBuffering = isBuffering,
    repeatMode = repeatMode,
    shuffleEnabled = shuffleEnabled,
    currentTrackFavorite = currentItem?.isFavorite == true,
)

private fun runtimeInstanceId(value: Any): String = System.identityHashCode(value).toString(16)

internal fun requiresPtvMusicForeground(
    media3Required: Boolean,
    playbackState: MusicPlaybackState,
    playbackRequested: Boolean = false,
): Boolean = media3Required || playbackState.isPlaying || playbackState.isBuffering ||
    (playbackRequested && playbackState.hasCurrent)

internal fun shouldKeepPtvMusicServiceOnTaskRemoved(
    foregroundStartRequested: Boolean,
    playbackState: MusicPlaybackState,
    playbackRequested: Boolean = false,
    pendingPlayAwaitingProof: Boolean = false,
): Boolean = pendingPlayAwaitingProof ||
    requiresPtvMusicForeground(foregroundStartRequested, playbackState, playbackRequested)

internal fun clearPtvMusicForegroundNotificationFlags(flags: Int): Int = flags and (
    Notification.FLAG_ONGOING_EVENT or
        Notification.FLAG_NO_CLEAR or
        Notification.FLAG_FOREGROUND_SERVICE
    ).inv()
