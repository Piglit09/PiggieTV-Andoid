@file:Suppress(
    "BlankLineBetweenWhenConditions",
    "ClassSignature",
    "FunctionSignature",
)

package org.jellyfin.mobile.app

import android.app.Application
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jellyfin.mobile.auth.AuthFailureCategory
import org.jellyfin.mobile.auth.AuthFailureClassifier
import org.jellyfin.mobile.auth.AuthFailureContext
import org.jellyfin.mobile.auth.AuthMethod
import org.jellyfin.mobile.auth.AuthOperation
import org.jellyfin.mobile.auth.AuthOperationKind
import org.jellyfin.mobile.auth.AuthSessionCoordinator
import org.jellyfin.mobile.auth.AuthenticatedSessionMarker
import org.jellyfin.mobile.auth.AuthenticationRejection
import org.jellyfin.mobile.auth.LegacyTokenMigrationEligibility
import org.jellyfin.mobile.auth.LegacyTokenMigrationPolicy
import org.jellyfin.mobile.auth.MobileAuthState
import org.jellyfin.mobile.auth.MobileAuthenticationResult
import org.jellyfin.mobile.auth.MobileLogoutResult
import org.jellyfin.mobile.auth.QuickConnectAttemptController
import org.jellyfin.mobile.auth.QuickConnectPhase
import org.jellyfin.mobile.auth.QuickConnectPolicy
import org.jellyfin.mobile.auth.QuickConnectPollAction
import org.jellyfin.mobile.auth.ServerRevocationResult
import org.jellyfin.mobile.auth.storage.SecureSessionCommitResult
import org.jellyfin.mobile.auth.storage.SecureSessionCredential
import org.jellyfin.mobile.auth.storage.SecureSessionLogoutPreparationResult
import org.jellyfin.mobile.auth.storage.SecureSessionReadResult
import org.jellyfin.mobile.auth.storage.SecureSessionTokenStore
import org.jellyfin.mobile.auth.transport.AuthTransportSnapshot
import org.jellyfin.mobile.auth.transport.JellyfinSessionRevoker
import org.jellyfin.mobile.auth.transport.MutableAuthTransportSession
import org.jellyfin.mobile.auth.transport.hasSameOrigin
import org.jellyfin.mobile.data.LegacyTokenPresence
import org.jellyfin.mobile.data.LegacyTokenResidueRemediation
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.feature.music.auto.PtvMusicSessionInvalidator
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sole application owner for Jellyfin authentication and session lifecycle.
 *
 * Candidate credentials are configured only on epoch-excluded SDK routes. A credential becomes
 * visible to shared transports only inside [AuthSessionCoordinator.commitAuthenticated], after
 * secure persistence and legacy Room cleanup have been verified.
 */
@Suppress(
    "LargeClass",
    "LongMethod",
    "LongParameterList",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)
class ApiClientController internal constructor(
    private val application: Application,
    private val appPreferences: AppPreferences,
    private val jellyfin: Jellyfin,
    private val apiClient: ApiClient,
    private val serverDao: ServerDao,
    private val userDao: UserDao,
    private val musicSessionInvalidator: PtvMusicSessionInvalidator,
    private val secureSessionTokenStore: SecureSessionTokenStore,
    private val legacyTokenResidueRemediation: LegacyTokenResidueRemediation,
    private val authCoordinator: AuthSessionCoordinator,
    private val authTransportSession: MutableAuthTransportSession,
    private val sessionRevoker: JellyfinSessionRevoker,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    var authenticatedUserId: UUID? = null
        private set

    val authState: StateFlow<MobileAuthState> = authCoordinator.state

    private val baseDeviceInfo: DeviceInfo
        get() = requireNotNull(jellyfin.options.deviceInfo)

    private val restoreMutex = Mutex()
    private val quickConnectActive = AtomicBoolean(false)
    private val quickConnectLock = Any()
    private val applicationAuthScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var validatedUser: UserDto? = null
    private var activeQuickConnect: ActiveQuickConnect? = null

    private val runtimeRejectionListener: (AuthenticationRejection) -> Unit = { rejection ->
        applicationAuthScope.launch {
            runCatching { handleRuntimeAuthenticationRejection(rejection) }
        }
    }

    init {
        authTransportSession.setRejectionHandler(::publishTransportRejection)
        authTransportSession.setAcceptedRejectionHandler(::clearAcceptedTransportRejection)
        authCoordinator.addAuthenticationRejectionListener(runtimeRejectionListener)
    }

    /** Stores a validated server URL and supersedes any session owned by another server. */
    suspend fun setupServer(hostname: String) = withContext(ioDispatcher) {
        val normalizedHostname = normalizeServerUrl(hostname)
        val previousServerId = appPreferences.currentServerId
        val serverId = serverDao.getServerByHostname(normalizedHostname)?.id
            ?: serverDao.insert(normalizedHostname)

        if (serverId == previousServerId) {
            check(appPreferences.commitCurrentServerId(serverId)) { "Unable to persist the selected server." }
            if (authCoordinator.currentAuthenticatedMarker() == null) {
                configureServerOnly(normalizedHostname)
            }
            return@withContext
        }

        val operation = authCoordinator.beginOperation(AuthOperationKind.SERVER_SWITCH)
        val completed = authCoordinator.completeOperationWithCleanup(
            operation = operation,
            successState = MobileAuthState.Unauthenticated,
        ) {
            clearInMemoryAuthentication(normalizedHostname)
            val credentialsCleared = secureSessionTokenStore.clear(::clearExternalAuthenticationState)
            val serverCommitted = appPreferences.commitCurrentServerId(serverId)
            credentialsCleared && serverCommitted
        }
        musicSessionInvalidator.invalidate("serverChanged")
        if (!completed) configureServerOnly(normalizedHostname)
    }

    suspend fun loadSavedServer(): ServerEntity? = withContext(ioDispatcher) {
        val server = appPreferences.currentServerId?.let(serverDao::getServer)
        if (authCoordinator.currentAuthenticatedMarker() == null) {
            val serverUrl = server?.hostname?.let { value ->
                runCatching { normalizeServerUrl(value) }.getOrNull()
            }
            configureServerOnly(serverUrl)
        }
        server
    }

    /** Restores and semantically validates the one selected Jellyfin session. */
    suspend fun restoreSession(): MobileAuthenticationResult = withContext(ioDispatcher) {
        currentValidatedResult()?.let { return@withContext it }
        restoreMutex.withLock {
            currentValidatedResult()?.let { return@withLock it }
            val ownerJob = currentCoroutineContext()[Job]
            val operation = authCoordinator.beginRestoreOperationIfIdle {
                ownerJob?.cancel(AuthOperationSupersededException())
            } ?: return@withLock MobileAuthenticationResult.Superseded
            try {
                restoreOwnedSession(operation)
            } catch (_: AuthOperationSupersededException) {
                MobileAuthenticationResult.Superseded
            } catch (error: CancellationException) {
                if (authCoordinator.isCurrent(operation)) {
                    authCoordinator.finishIfCurrent(operation) {
                        clearInMemoryAuthentication(null)
                        true
                    }
                    throw error
                }
                MobileAuthenticationResult.Superseded
            } catch (_: Exception) {
                preserveStorageFailure(operation, null)
            }
        }
    }

    /** Compatibility entrypoint for background consumers that only need the validated user id. */
    suspend fun loadSavedServerUser(): UUID? = when (val result = restoreSession()) {
        is MobileAuthenticationResult.Authenticated -> result.user.id
        else -> null
    }

    /** Password exists only as this transient call argument and is never placed in state. */
    suspend fun authenticatePassword(
        server: ServerEntity,
        username: String,
        password: String,
    ): MobileAuthenticationResult = withContext(ioDispatcher) {
        val ownerJob = currentCoroutineContext()[Job]
        val operation = authCoordinator.beginOperation(AuthOperationKind.PASSWORD_LOGIN) {
            ownerJob?.cancel(AuthOperationSupersededException())
        }
        val routeBackup = captureApiRoute()
        val serverUrl = runCatching { normalizeServerUrl(server.hostname) }.getOrElse {
            return@withContext finishCandidateFailure(
                operation,
                routeBackup,
                AuthFailureCategory.INVALID_LOCAL_SESSION,
            )
        }

        if (server.id != appPreferences.currentServerId) {
            return@withContext finishCandidateFailure(
                operation,
                routeBackup,
                AuthFailureCategory.INVALID_LOCAL_SESSION,
            )
        }

        if (!authCoordinator.mutateIfCurrent(operation) {
                configureCandidateRoute(serverUrl, null)
                true
            }
        ) {
            return@withContext MobileAuthenticationResult.Superseded
        }

        try {
            val authentication = apiClient.userApi
                .authenticateUserByName(username.trim(), password)
                .content
            val user = authentication.user
                ?: return@withContext finishCandidateFailure(
                    operation,
                    routeBackup,
                    AuthFailureCategory.REQUEST_FAILED,
                )
            val token = authentication.accessToken?.takeIf(String::isNotBlank)
                ?: return@withContext finishCandidateFailure(
                    operation,
                    routeBackup,
                    AuthFailureCategory.REQUEST_FAILED,
                )

            if (commitNewCredential(operation, routeBackup, server, user, token, AuthMethod.PASSWORD)) {
                MobileAuthenticationResult.Authenticated(user, AuthMethod.PASSWORD)
            } else if (authCoordinator.isCurrent(operation)) {
                MobileAuthenticationResult.SecureStorageFailure
            } else {
                when (authCoordinator.state.value) {
                    MobileAuthState.StorageError -> MobileAuthenticationResult.SecureStorageFailure
                    else -> MobileAuthenticationResult.Superseded
                }
            }
        } catch (_: AuthOperationSupersededException) {
            MobileAuthenticationResult.Superseded
        } catch (error: CancellationException) {
            if (authCoordinator.isCurrent(operation)) {
                authCoordinator.finishIfCurrent(operation) {
                    restoreApiRoute(routeBackup)
                    true
                }
                throw error
            }
            MobileAuthenticationResult.Superseded
        } catch (error: Exception) {
            finishCandidateFailure(
                operation,
                routeBackup,
                AuthFailureClassifier.classify(error, AuthFailureContext.PASSWORD_AUTHENTICATION),
            )
        }
    }

    /** Runs one bounded native Jellyfin Quick Connect attempt. */
    suspend fun authenticateWithQuickConnect(server: ServerEntity): MobileAuthenticationResult {
        if (!quickConnectActive.compareAndSet(false, true)) {
            return MobileAuthenticationResult.QuickConnectAlreadyRunning
        }

        return try {
            val ownerJob = currentCoroutineContext()[Job]
            val operation = authCoordinator.beginOperation(AuthOperationKind.QUICK_CONNECT) {
                ownerJob?.cancel(AuthOperationSupersededException())
            }
            val routeBackup = captureApiRoute()
            val attempt = QuickConnectAttemptController(startedAtMillis = SystemClock.elapsedRealtime())
            synchronized(quickConnectLock) {
                activeQuickConnect = ActiveQuickConnect(operation, attempt, routeBackup)
            }
            withContext(ioDispatcher) {
                withTimeout(QuickConnectPolicy.DEFAULT_TOTAL_TIMEOUT_MILLIS) {
                    runQuickConnect(server, operation, routeBackup, attempt)
                }
            }
        } finally {
            synchronized(quickConnectLock) { activeQuickConnect = null }
            quickConnectActive.set(false)
        }
    }

    fun cancelQuickConnect(): Boolean {
        val active = synchronized(quickConnectLock) { activeQuickConnect } ?: return false
        return authCoordinator.cancelIfCurrent(active.operation) { fallbackWasRejected ->
            active.attempt.cancel()
            if (fallbackWasRejected) {
                clearInMemoryAuthentication(active.routeBackup.baseUrl)
            } else {
                restoreApiRoute(active.routeBackup)
            }
            true
        }
    }

    /**
     * Invalidates ownership first, attempts Jellyfin token revocation, then always performs a
     * guarded local clear. Cancellation or network failure cannot skip the local phase.
     */
    suspend fun logoutCurrentUser(): MobileLogoutResult = withContext(ioDispatcher + NonCancellable) {
        val configuredServerUrl = apiClient.baseUrl
        authCoordinator.invalidateAll(MobileAuthState.LoggingOut)
        clearInMemoryAuthentication(configuredServerUrl)

        val logoutPreparation = secureSessionTokenStore.prepareFailClosedLogout()
        val retiringCredential = (logoutPreparation as? SecureSessionLogoutPreparationResult.Prepared)
            ?.credential
        val retiringServerUrl = retiringCredential
            ?.serverId
            ?.let { serverId -> runCatching { serverDao.getServer(serverId) }.getOrNull() }
            ?.hostname
            ?.let { value -> runCatching { normalizeServerUrl(value) }.getOrNull() }
        val revocationResult = when {
            logoutPreparation is SecureSessionLogoutPreparationResult.StorageFailure ->
                ServerRevocationResult.NOT_ATTEMPTED

            retiringCredential == null || retiringServerUrl == null ->
                ServerRevocationResult.NOT_ATTEMPTED

            runCatching {
                sessionRevoker.revoke(
                    serverUrl = retiringServerUrl,
                    authorizationHeader = authorizationHeader(retiringCredential.accessToken),
                )
            }.getOrDefault(false) -> ServerRevocationResult.SUCCEEDED

            else -> ServerRevocationResult.FAILED
        }

        val serverUrl = retiringServerUrl ?: configuredServerUrl ?: appPreferences.currentServerId
            ?.let { serverId -> runCatching { serverDao.getServer(serverId) }.getOrNull() }
            ?.hostname
            ?.let { value -> runCatching { normalizeServerUrl(value) }.getOrNull() }
        val cleared = authCoordinator.invalidateAndClearForLogout {
            clearInMemoryAuthentication(serverUrl)
            secureSessionTokenStore.clear(::clearExternalAuthenticationState)
        }
        musicSessionInvalidator.invalidate("logout")
        MobileLogoutResult(cleared, revocationResult)
    }

    suspend fun loadPreviouslyUsedServers(): List<ServerEntity> = withContext(ioDispatcher) {
        serverDao.getAllServers().filterNot { server ->
            server.id == appPreferences.currentServerId
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun restoreOwnedSession(operation: AuthOperation): MobileAuthenticationResult {
        var serverId = 0L
        var localUserId = 0L
        lateinit var serverUrl: String

        val migrationEligibility = legacyMigrationEligibility()
        val credential = when (val stored = secureSessionTokenStore.readResult()) {
            is SecureSessionReadResult.Available -> {
                val secureCredential = stored.credential
                val logicalCredentialCleared = authCoordinator.mutateIfCurrent(operation) {
                    val secureReadback = secureSessionTokenStore.readResult()
                    val verifiedCredential = (secureReadback as? SecureSessionReadResult.Available)?.credential
                    verifiedCredential.exactlyMatches(secureCredential) &&
                        legacyTokenResidueRemediation.clearLogicalTokensAfterSecureVerification()
                }
                if (!logicalCredentialCleared) return preserveStorageFailure(operation, null)
                if (!legacyTokenResidueRemediation.sanitizeHistoricalResidue(secureCredential.accessToken)) {
                    return preserveStorageFailure(operation, null)
                }
                if (!authCoordinator.isCurrent(operation)) return MobileAuthenticationResult.Superseded

                serverId = secureCredential.serverId
                val server = runCatching { serverDao.getServer(serverId) }.getOrElse {
                    return preserveStorageFailure(operation, null)
                } ?: return clearInvalidRestoredSession(operation, null)
                serverUrl = runCatching { normalizeServerUrl(server.hostname) }.getOrElse {
                    return clearInvalidRestoredSession(operation, null)
                }
                localUserId = runCatching {
                    userDao.upsert(serverId, secureCredential.userId)
                }.getOrElse {
                    return preserveStorageFailure(operation, serverUrl)
                }
                val metadataReconciled = authCoordinator.mutateIfCurrent(operation) {
                    appPreferences.commitCurrentServerId(serverId) &&
                        appPreferences.commitCurrentUserId(localUserId)
                }
                if (!metadataReconciled) return preserveStorageFailure(operation, serverUrl)
                secureCredential
            }

            SecureSessionReadResult.Missing -> {
                val remediationWasComplete = legacyTokenResidueRemediation.isComplete()
                val legacyPresence = if (remediationWasComplete) {
                    LegacyTokenPresence.ABSENT
                } else {
                    legacyTokenResidueRemediation.legacyTokenPresence()
                }
                if (!remediationWasComplete) {
                    val prepared = when (legacyPresence) {
                        LegacyTokenPresence.PRESENT -> true
                        LegacyTokenPresence.ABSENT -> when (migrationEligibility) {
                            LegacyTokenMigrationEligibility.DENIED_FRESH_INSTALL ->
                                legacyTokenResidueRemediation.markFreshInstallPristine()

                            LegacyTokenMigrationEligibility.ALLOWED_IN_PLACE_UPDATE,
                            LegacyTokenMigrationEligibility.UNKNOWN,
                            -> legacyTokenResidueRemediation.sanitizeHistoricalResidue(null)
                        }

                        LegacyTokenPresence.UNKNOWN -> false
                    }
                    if (!prepared) return preserveStorageFailure(operation, null)
                    if (!authCoordinator.isCurrent(operation)) return MobileAuthenticationResult.Superseded
                }

                serverId = appPreferences.currentServerId
                    ?: return clearMissingLegacySession(
                        operation = operation,
                        serverUrl = null,
                        requiresSanitation = !remediationWasComplete && legacyPresence == LegacyTokenPresence.PRESENT,
                    )
                localUserId = appPreferences.currentUserId
                    ?: return clearMissingLegacySession(
                        operation = operation,
                        serverUrl = safeServerUrl(serverId),
                        requiresSanitation = !remediationWasComplete && legacyPresence == LegacyTokenPresence.PRESENT,
                    )
                val serverUser = runCatching {
                    userDao.getServerUser(serverId, localUserId)
                }.getOrElse {
                    return preserveStorageFailure(operation, safeServerUrl(serverId))
                } ?: return clearMissingLegacySession(
                    operation = operation,
                    serverUrl = safeServerUrl(serverId),
                    requiresSanitation = !remediationWasComplete && legacyPresence == LegacyTokenPresence.PRESENT,
                )
                serverUrl = runCatching { normalizeServerUrl(serverUser.server.hostname) }.getOrElse {
                    return clearMissingLegacySession(
                        operation = operation,
                        serverUrl = null,
                        requiresSanitation = !remediationWasComplete && legacyPresence == LegacyTokenPresence.PRESENT,
                    )
                }
                val legacyToken = serverUser.user.legacyAccessToken?.takeIf(String::isNotBlank)
                    ?: return clearMissingLegacySession(
                        operation = operation,
                        serverUrl = serverUrl,
                        requiresSanitation = !remediationWasComplete && legacyPresence == LegacyTokenPresence.PRESENT,
                    )
                when (migrationEligibility) {
                    LegacyTokenMigrationEligibility.ALLOWED_IN_PLACE_UPDATE -> Unit
                    LegacyTokenMigrationEligibility.DENIED_FRESH_INSTALL ->
                        return rejectLegacySessionAndSanitize(operation, serverUrl, legacyToken)
                    LegacyTokenMigrationEligibility.UNKNOWN ->
                        return preserveStorageFailure(operation, serverUrl)
                }
                val candidate = SecureSessionCredential(
                    serverId = serverId,
                    userId = serverUser.user.userId,
                    credentialInstanceId = UUID.randomUUID(),
                    accessToken = legacyToken,
                )
                val secureCredentialActivated = authCoordinator.mutateIfCurrent(operation) {
                    secureSessionTokenStore.activate(candidate) == SecureSessionCommitResult.ACTIVATED &&
                        (secureSessionTokenStore.readResult() as? SecureSessionReadResult.Available)
                            ?.credential
                            .exactlyMatches(candidate)
                }
                if (!secureCredentialActivated) return preserveStorageFailure(operation, serverUrl)

                val logicalCredentialCleared = authCoordinator.mutateIfCurrent(operation) {
                    val secureReadback = secureSessionTokenStore.readResult()
                    val verifiedCredential = (secureReadback as? SecureSessionReadResult.Available)?.credential
                    verifiedCredential.exactlyMatches(candidate) &&
                        legacyTokenResidueRemediation.clearLogicalTokensAfterSecureVerification()
                }
                if (!logicalCredentialCleared) return preserveStorageFailure(operation, serverUrl)
                if (!legacyTokenResidueRemediation.sanitizeHistoricalResidue(candidate.accessToken)) {
                    return preserveStorageFailure(operation, serverUrl)
                }
                if (!authCoordinator.isCurrent(operation)) return MobileAuthenticationResult.Superseded
                candidate
            }

            SecureSessionReadResult.ClearPending -> {
                val cleared = authCoordinator.mutateIfCurrent(operation) {
                    secureSessionTokenStore.clear(::clearExternalAuthenticationState)
                }
                if (!cleared) return preserveStorageFailure(operation, null)
                if (!legacyTokenResidueRemediation.sanitizeHistoricalResidue(null)) {
                    return preserveStorageFailure(operation, null)
                }
                return completeLoginRequired(operation, null)
            }

            SecureSessionReadResult.StorageFailure -> return preserveStorageFailure(operation, null)
        }

        if (!authCoordinator.mutateIfCurrent(operation) {
                configureCandidateRoute(serverUrl, null)
                true
            }
        ) {
            return MobileAuthenticationResult.Superseded
        }

        if (!authCoordinator.transitionIfCurrent(operation, MobileAuthState.Validating)) {
            return MobileAuthenticationResult.Superseded
        }
        if (!authCoordinator.mutateIfCurrent(operation) {
                configureCandidateRoute(serverUrl, credential.accessToken)
                true
            }
        ) {
            return MobileAuthenticationResult.Superseded
        }

        val user = try {
            apiClient.userApi.getCurrentUser().content
        } catch (_: AuthOperationSupersededException) {
            return MobileAuthenticationResult.Superseded
        } catch (error: CancellationException) {
            if (authCoordinator.isCurrent(operation)) throw error
            return MobileAuthenticationResult.Superseded
        } catch (error: Exception) {
            val category = AuthFailureClassifier.classify(
                error,
                AuthFailureContext.USER_SPECIFIC_SESSION_VALIDATION,
            )
            return when (category) {
                AuthFailureCategory.AUTH_REJECTED -> clearRejectedRestoredSession(operation, serverUrl)
                else -> preserveConnectivityFailure(operation, serverUrl, category)
            }
        }

        if (user.id != credential.userId) {
            return clearRejectedRestoredSession(operation, serverUrl)
        }

        val marker = AuthenticatedSessionMarker.fromCredential(
            serverId = serverId.toString(),
            userId = user.id.toString(),
            credentialInstanceId = credential.credentialInstanceId,
            credential = credential.accessToken,
        )
        val authorizationHeader = authorizationHeader(credential.accessToken)
        val committed = authCoordinator.commitAuthenticated(operation, marker, AuthMethod.RESTORE) { epoch ->
            val currentCredential = (secureSessionTokenStore.readResult() as? SecureSessionReadResult.Available)
                ?.credential
            if (!currentCredential.exactlyMatches(credential)) return@commitAuthenticated false
            if (!userDao.clearAllLegacyAccessTokensVerified()) return@commitAuthenticated false
            if (!appPreferences.commitCurrentServerId(serverId)) return@commitAuthenticated false
            if (!appPreferences.commitCurrentUserId(localUserId)) return@commitAuthenticated false
            authTransportSession.activate(epoch, serverUrl, credential.accessToken, authorizationHeader) {
                configureCandidateRoute(serverUrl, credential.accessToken)
            }
            authenticatedUserId = user.id
            validatedUser = user
            true
        }

        return when {
            committed -> MobileAuthenticationResult.Authenticated(user, AuthMethod.RESTORE)
            authCoordinator.state.value == MobileAuthState.StorageError ->
                MobileAuthenticationResult.SecureStorageFailure
            else -> MobileAuthenticationResult.Superseded
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun runQuickConnect(
        server: ServerEntity,
        operation: AuthOperation,
        routeBackup: ApiRouteBackup,
        attempt: QuickConnectAttemptController,
    ): MobileAuthenticationResult {
        val serverUrl = runCatching { normalizeServerUrl(server.hostname) }.getOrElse {
            return finishCandidateFailure(operation, routeBackup, AuthFailureCategory.INVALID_LOCAL_SESSION)
        }
        if (server.id != appPreferences.currentServerId) {
            return finishCandidateFailure(operation, routeBackup, AuthFailureCategory.INVALID_LOCAL_SESSION)
        }
        if (!authCoordinator.mutateIfCurrent(operation) {
                configureCandidateRoute(serverUrl, null)
                true
            }
        ) {
            return MobileAuthenticationResult.Superseded
        }

        try {
            if (!apiClient.quickConnectApi.getQuickConnectEnabled().content) {
                attempt.fail()
                return finishCandidateFailure(
                    operation,
                    routeBackup,
                    AuthFailureCategory.QUICK_CONNECT_FAILED,
                )
            }

            val initiated = apiClient.quickConnectApi.initiateQuickConnect().content
            val secret = initiated.secret.takeIf(String::isNotBlank)
                ?: return finishCandidateFailure(
                    operation,
                    routeBackup,
                    AuthFailureCategory.QUICK_CONNECT_FAILED,
                )
            val displayCode = initiated.code.takeIf(String::isNotBlank)
                ?: return finishCandidateFailure(
                    operation,
                    routeBackup,
                    AuthFailureCategory.QUICK_CONNECT_FAILED,
                )
            if (!attempt.markWaitingForApproval()) {
                return MobileAuthenticationResult.Superseded
            }
            if (!authCoordinator.transitionIfCurrent(
                    operation,
                    MobileAuthState.QuickConnectWaiting(
                        displayCode = displayCode,
                        deadlineElapsedRealtimeMillis = attempt.snapshot().deadlineMillis,
                    ),
                )
            ) {
                return MobileAuthenticationResult.Superseded
            }

            while (true) {
                when (val action = attempt.nextPoll(SystemClock.elapsedRealtime())) {
                    QuickConnectPollAction.Poll -> {
                        val poll = try {
                            apiClient.quickConnectApi.getQuickConnectState(secret).content
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            val category = AuthFailureClassifier.classify(
                                error,
                                AuthFailureContext.QUICK_CONNECT_POLLING,
                            )
                            if (
                                category == AuthFailureCategory.NETWORK_UNAVAILABLE ||
                                category == AuthFailureCategory.SERVER_UNAVAILABLE
                            ) {
                                if (!attempt.recordTransientPollFailure(SystemClock.elapsedRealtime())) {
                                    val terminalCategory = if (attempt.snapshot().phase == QuickConnectPhase.EXPIRED) {
                                        AuthFailureCategory.QUICK_CONNECT_EXPIRED
                                    } else {
                                        AuthFailureCategory.QUICK_CONNECT_FAILED
                                    }
                                    return finishCandidateFailure(operation, routeBackup, terminalCategory)
                                }
                                continue
                            }
                            attempt.fail()
                            return finishCandidateFailure(operation, routeBackup, category)
                        }
                        if (!attempt.recordPollResult(poll.authenticated, SystemClock.elapsedRealtime())) {
                            return finishCandidateFailure(
                                operation,
                                routeBackup,
                                AuthFailureCategory.QUICK_CONNECT_EXPIRED,
                            )
                        }
                        if (poll.authenticated) break
                    }

                    is QuickConnectPollAction.Wait -> delay(action.delayMillis)
                    is QuickConnectPollAction.Stop -> {
                        val category = if (action.phase == QuickConnectPhase.CANCELLED) {
                            AuthFailureCategory.QUICK_CONNECT_CANCELLED
                        } else {
                            AuthFailureCategory.QUICK_CONNECT_EXPIRED
                        }
                        return finishCandidateFailure(operation, routeBackup, category)
                    }
                }
            }

            if (!attempt.claimFinalAuthentication()) return MobileAuthenticationResult.Superseded
            if (!authCoordinator.transitionIfCurrent(
                    operation,
                    MobileAuthState.Authenticating(AuthMethod.QUICK_CONNECT),
                )
            ) {
                return MobileAuthenticationResult.Superseded
            }
            val authentication = apiClient.userApi.authenticateWithQuickConnect(secret).content
            val user = authentication.user
                ?: return finishCandidateFailure(
                    operation,
                    routeBackup,
                    AuthFailureCategory.QUICK_CONNECT_FAILED,
                )
            val token = authentication.accessToken?.takeIf(String::isNotBlank)
                ?: return finishCandidateFailure(
                    operation,
                    routeBackup,
                    AuthFailureCategory.QUICK_CONNECT_FAILED,
                )

            return if (
                commitNewCredential(operation, routeBackup, server, user, token, AuthMethod.QUICK_CONNECT)
            ) {
                attempt.markSuccess()
                MobileAuthenticationResult.Authenticated(user, AuthMethod.QUICK_CONNECT)
            } else if (authCoordinator.state.value == MobileAuthState.StorageError) {
                MobileAuthenticationResult.SecureStorageFailure
            } else {
                MobileAuthenticationResult.Superseded
            }
        } catch (_: AuthOperationSupersededException) {
            return MobileAuthenticationResult.Superseded
        } catch (error: CancellationException) {
            if (authCoordinator.isCurrent(operation)) {
                attempt.cancel()
                return finishCandidateFailure(
                    operation,
                    routeBackup,
                    if (error is TimeoutCancellationException) {
                        AuthFailureCategory.QUICK_CONNECT_EXPIRED
                    } else {
                        AuthFailureCategory.QUICK_CONNECT_CANCELLED
                    },
                )
            }
            return MobileAuthenticationResult.Superseded
        } catch (error: Exception) {
            attempt.fail()
            return finishCandidateFailure(
                operation,
                routeBackup,
                AuthFailureClassifier.classify(error, AuthFailureContext.QUICK_CONNECT_AUTHENTICATION),
            )
        }
    }

    private fun commitNewCredential(
        operation: AuthOperation,
        routeBackup: ApiRouteBackup,
        server: ServerEntity,
        user: UserDto,
        accessToken: String,
        method: AuthMethod,
    ): Boolean {
        val credential = SecureSessionCredential(
            serverId = server.id,
            userId = user.id,
            credentialInstanceId = UUID.randomUUID(),
            accessToken = accessToken,
        )
        val marker = AuthenticatedSessionMarker.fromCredential(
            serverId = server.id.toString(),
            userId = user.id.toString(),
            credentialInstanceId = credential.credentialInstanceId,
            credential = accessToken,
        )
        val authorizationHeader = authorizationHeader(accessToken)
        return authCoordinator.commitAuthenticated(operation, marker, method) { epoch ->
            val activated = secureSessionTokenStore.activateAfterPreparedClear(credential) {
                if (!persistSessionMetadata(server.id, user.id)) return@activateAfterPreparedClear false
                authTransportSession.activate(epoch, server.hostname, accessToken, authorizationHeader) {
                    configureCandidateRoute(server.hostname, accessToken)
                }
                authenticatedUserId = user.id
                validatedUser = user
                true
            } == SecureSessionCommitResult.ACTIVATED
            if (!activated) restoreApiRoute(routeBackup)
            activated
        }
    }

    private fun persistSessionMetadata(serverId: Long, userId: UUID): Boolean {
        val previousUserId = appPreferences.currentUserId
        val localUserId = runCatching { userDao.upsert(serverId, userId) }.getOrNull() ?: return false
        if (!appPreferences.commitCurrentUserId(localUserId)) return false
        val legacyCleared = runCatching(userDao::clearAllLegacyAccessTokensVerified).getOrDefault(false)
        if (!legacyCleared) {
            appPreferences.commitCurrentUserId(previousUserId)
            return false
        }
        return true
    }

    private fun finishCandidateFailure(
        operation: AuthOperation,
        routeBackup: ApiRouteBackup,
        category: AuthFailureCategory,
    ): MobileAuthenticationResult {
        val finished = authCoordinator.finishIfCurrent(operation) { fallbackWasRejected ->
            if (fallbackWasRejected) {
                clearInMemoryAuthentication(routeBackup.baseUrl)
            } else {
                restoreApiRoute(routeBackup)
            }
            true
        }
        return if (finished) {
            MobileAuthenticationResult.RecoverableFailure(category)
        } else {
            MobileAuthenticationResult.Superseded
        }
    }

    private fun clearMissingLegacySession(
        operation: AuthOperation,
        serverUrl: String?,
        requiresSanitation: Boolean,
    ): MobileAuthenticationResult = if (requiresSanitation) {
        rejectLegacySessionAndSanitize(operation, serverUrl, null)
    } else {
        clearInvalidRestoredSession(operation, serverUrl)
    }

    /** Rejects an ineligible legacy credential, then physically removes its database residue. */
    private fun rejectLegacySessionAndSanitize(
        operation: AuthOperation,
        serverUrl: String?,
        knownLegacyToken: String?,
    ): MobileAuthenticationResult {
        val logicallyCleared = authCoordinator.mutateIfCurrent(operation) {
            clearInMemoryAuthentication(serverUrl)
            secureSessionTokenStore.clear(::clearExternalAuthenticationState)
        }
        if (!logicallyCleared) return preserveStorageFailure(operation, serverUrl)
        if (!legacyTokenResidueRemediation.sanitizeHistoricalResidue(knownLegacyToken)) {
            return preserveStorageFailure(operation, serverUrl)
        }
        return completeLoginRequired(operation, serverUrl)
    }

    private fun clearInvalidRestoredSession(
        operation: AuthOperation,
        serverUrl: String?,
    ): MobileAuthenticationResult {
        val cleared = authCoordinator.completeOperationWithCleanup(
            operation,
            MobileAuthState.Unauthenticated,
        ) {
            clearInMemoryAuthentication(serverUrl)
            secureSessionTokenStore.clear(::clearExternalAuthenticationState)
        }
        return if (cleared) {
            MobileAuthenticationResult.LoginRequired
        } else if (authCoordinator.state.value == MobileAuthState.StorageError) {
            MobileAuthenticationResult.SecureStorageFailure
        } else {
            MobileAuthenticationResult.Superseded
        }
    }

    private fun clearRejectedRestoredSession(
        operation: AuthOperation,
        serverUrl: String,
    ): MobileAuthenticationResult = clearInvalidRestoredSession(operation, serverUrl)

    private fun completeLoginRequired(
        operation: AuthOperation,
        serverUrl: String?,
    ): MobileAuthenticationResult {
        val completed = authCoordinator.completeOperationWithCleanup(
            operation,
            MobileAuthState.Unauthenticated,
        ) {
            clearInMemoryAuthentication(serverUrl)
            true
        }
        return if (completed) MobileAuthenticationResult.LoginRequired else MobileAuthenticationResult.Superseded
    }

    private fun preserveStorageFailure(
        operation: AuthOperation,
        serverUrl: String?,
    ): MobileAuthenticationResult {
        val completed = authCoordinator.completeOperationWithCleanup(
            operation,
            MobileAuthState.StorageError,
        ) {
            clearInMemoryAuthentication(serverUrl)
            true
        }
        return if (completed) MobileAuthenticationResult.SecureStorageFailure else MobileAuthenticationResult.Superseded
    }

    private fun preserveConnectivityFailure(
        operation: AuthOperation,
        serverUrl: String,
        category: AuthFailureCategory,
    ): MobileAuthenticationResult {
        val connectivityCategory = when (category) {
            AuthFailureCategory.NETWORK_UNAVAILABLE -> AuthFailureCategory.NETWORK_UNAVAILABLE
            else -> AuthFailureCategory.SERVER_UNAVAILABLE
        }
        val completed = authCoordinator.completeOperationWithCleanup(
            operation,
            MobileAuthState.ConnectivityError(connectivityCategory),
        ) {
            clearInMemoryAuthentication(serverUrl)
            true
        }
        return if (completed) {
            MobileAuthenticationResult.RecoverableFailure(connectivityCategory)
        } else {
            MobileAuthenticationResult.Superseded
        }
    }

    private fun publishTransportRejection(snapshot: AuthTransportSnapshot): Boolean =
        authCoordinator.notifyAuthenticationRejected(
            exactCredentialEpoch = snapshot.epoch,
            prepareFailClosedClear = secureSessionTokenStore::prepareFailClosedClear,
        )

    /** Runs under MutableAuthTransportSession's exact-snapshot lock before that snapshot is removed. */
    private fun clearAcceptedTransportRejection(snapshot: AuthTransportSnapshot) {
        authenticatedUserId = null
        validatedUser = null
        val configuredUrl = apiClient.baseUrl
        val configuredOrigin = configuredUrl?.toHttpUrlOrNull()
        if (
            configuredOrigin != null &&
            snapshot.serverOrigin?.hasSameOrigin(configuredOrigin) == true &&
            snapshot.matchesAccessToken(apiClient.accessToken)
        ) {
            // Clear the mutable SDK route before MutableAuthTransportSession drops its snapshot.
            // This prevents an orphaned token from being captured as an epoch-excluded route.
            configureServerOnly(configuredUrl)
        }
    }

    private suspend fun handleRuntimeAuthenticationRejection(rejection: AuthenticationRejection) {
        withContext(ioDispatcher) {
            val serverUrl = runCatching {
                appPreferences.currentServerId
                    ?.let(serverDao::getServer)
                    ?.hostname
                    ?.let(::normalizeServerUrl)
            }.getOrNull()
            val cleared = authCoordinator.compareAndClearAuthenticationRejection(rejection) {
                clearInMemoryAuthentication(serverUrl)
                secureSessionTokenStore.clear(::clearExternalAuthenticationState)
            }
            if (cleared) musicSessionInvalidator.invalidate("authenticationRejected")
        }
    }

    private fun clearExternalAuthenticationState(): Boolean {
        val legacyCleared = runCatching(userDao::clearAllLegacyAccessTokensVerified).getOrDefault(false)
        return legacyCleared && appPreferences.commitCurrentUserId(null)
    }

    private fun clearInMemoryAuthentication(serverUrl: String?) {
        authTransportSession.clear {
            apiClient.update(
                baseUrl = serverUrl,
                accessToken = null,
                deviceInfo = baseDeviceInfo,
            )
        }
        authenticatedUserId = null
        validatedUser = null
    }

    private fun configureServerOnly(serverUrl: String?) {
        authTransportSession.mutateRoute {
            apiClient.update(
                baseUrl = serverUrl,
                accessToken = null,
                deviceInfo = baseDeviceInfo,
            )
        }
    }

    private fun configureCandidateRoute(serverUrl: String, accessToken: String?) {
        authTransportSession.mutateRoute {
            apiClient.update(
                baseUrl = serverUrl,
                accessToken = accessToken,
                deviceInfo = baseDeviceInfo,
            )
        }
    }

    private fun captureApiRoute() = ApiRouteBackup(
        baseUrl = apiClient.baseUrl,
        accessToken = apiClient.accessToken,
        deviceInfo = apiClient.deviceInfo,
    )

    private fun restoreApiRoute(backup: ApiRouteBackup) {
        val restorableAccessToken = backup.accessToken
            ?.takeIf(authCoordinator::isCurrentCredentialUsable)
        authTransportSession.mutateRoute {
            apiClient.update(
                baseUrl = backup.baseUrl,
                accessToken = restorableAccessToken,
                deviceInfo = backup.deviceInfo,
            )
        }
    }

    private fun authorizationHeader(accessToken: String): String = AuthorizationHeaderBuilder.buildHeader(
        clientName = apiClient.clientInfo.name,
        clientVersion = apiClient.clientInfo.version,
        deviceId = baseDeviceInfo.id,
        deviceName = baseDeviceInfo.name,
        accessToken = accessToken,
    )

    private fun currentValidatedResult(): MobileAuthenticationResult.Authenticated? {
        val state = authCoordinator.state.value as? MobileAuthState.Authenticated ?: return null
        val user = validatedUser?.takeIf { it.id.toString() == state.userId } ?: return null
        return MobileAuthenticationResult.Authenticated(user, state.method)
    }

    private fun legacyMigrationEligibility(): LegacyTokenMigrationEligibility = runCatching {
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        LegacyTokenMigrationPolicy.evaluate(
            firstInstallTimeMillis = packageInfo.firstInstallTime,
            lastUpdateTimeMillis = packageInfo.lastUpdateTime,
        )
    }.getOrDefault(LegacyTokenMigrationEligibility.UNKNOWN)

    private fun safeServerUrl(serverId: Long): String? = runCatching {
        serverDao.getServer(serverId)
            ?.hostname
            ?.let(::normalizeServerUrl)
    }.getOrNull()

    private fun normalizeServerUrl(value: String): String {
        val parsed = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid server address.")
        require(parsed.scheme == "http" || parsed.scheme == "https") { "Unsupported server scheme." }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Server credentials are not allowed." }
        require(parsed.query == null && parsed.fragment == null) { "Server query and fragment are not allowed." }
        return parsed.toString().trimEnd('/')
    }

    private fun SecureSessionCredential.matches(serverId: Long, userId: UUID): Boolean =
        this.serverId == serverId && this.userId == userId && accessToken.isNotBlank()

    private fun SecureSessionCredential?.exactlyMatches(other: SecureSessionCredential): Boolean =
        this != null &&
            serverId == other.serverId &&
            userId == other.userId &&
            credentialInstanceId == other.credentialInstanceId &&
            accessToken == other.accessToken

    private data class ApiRouteBackup(
        val baseUrl: String?,
        val accessToken: String?,
        val deviceInfo: DeviceInfo,
    ) {
        override fun toString(): String =
            "ApiRouteBackup(baseUrl=[REDACTED], accessToken=[REDACTED], deviceInfo=$deviceInfo)"
    }

    private data class ActiveQuickConnect(
        val operation: AuthOperation,
        val attempt: QuickConnectAttemptController,
        val routeBackup: ApiRouteBackup,
    )

    private class AuthOperationSupersededException : CancellationException("AUTH_OPERATION_SUPERSEDED")
}
