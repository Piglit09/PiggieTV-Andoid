package org.jellyfin.mobile.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID

/** Identifies one operation that is allowed to mutate authentication state. */
class AuthOperation internal constructor(internal val generation: Long, val kind: AuthOperationKind) {
    override fun toString(): String = "AuthOperation(generation=$generation, kind=$kind)"
}

/**
 * Secret-free identity for an authenticated session. Only a one-way credential fingerprint is
 * retained, and it is always redacted from diagnostic output.
 */
class AuthenticatedSessionMarker private constructor(
    val serverId: String,
    val userId: String,
    val credentialInstanceId: UUID,
    internal val credentialFingerprint: String,
) {
    fun matchesCredential(credential: String?): Boolean =
        credential?.takeIf(String::isNotBlank)?.fingerprint() == credentialFingerprint

    override fun equals(other: Any?): Boolean = other is AuthenticatedSessionMarker &&
        serverId == other.serverId &&
        userId == other.userId &&
        credentialInstanceId == other.credentialInstanceId &&
        credentialFingerprint == other.credentialFingerprint

    override fun hashCode(): Int {
        var result = serverId.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + credentialInstanceId.hashCode()
        result = 31 * result + credentialFingerprint.hashCode()
        return result
    }

    override fun toString(): String = "AuthenticatedSessionMarker(serverId=[REDACTED], userId=[REDACTED], " +
        "credentialInstanceId=[REDACTED], credential=[REDACTED])"

    companion object {
        fun fromCredential(
            serverId: String,
            userId: String,
            credentialInstanceId: UUID,
            credential: String,
        ): AuthenticatedSessionMarker {
            require(serverId.isNotBlank()) { "An authenticated marker requires a server id." }
            require(userId.isNotBlank()) { "An authenticated marker requires a user id." }
            require(credentialInstanceId != EMPTY_UUID) { "An authenticated marker requires a credential instance id." }
            require(credential.isNotBlank()) { "An authenticated marker requires a credential." }
            return AuthenticatedSessionMarker(serverId, userId, credentialInstanceId, credential.fingerprint())
        }

        private val EMPTY_UUID: UUID = UUID(0L, 0L)
    }
}

/** Opaque ownership captured before an authenticated request enters transport. */
class AuthenticatedRequestTicket internal constructor(
    internal val credentialEpoch: Long,
    internal val serverId: String,
    internal val userId: String,
    internal val credentialFingerprint: String,
) {
    override fun toString(): String = "AuthenticatedRequestTicket(epoch=$credentialEpoch, credential=[REDACTED])"
}

/** One deduplicated rejection of an exact authenticated credential epoch. */
class AuthenticationRejection internal constructor(
    internal val credentialEpoch: Long,
    internal val serverId: String,
    internal val userId: String,
    internal val credentialFingerprint: String,
) {
    override fun toString(): String = "AuthenticationRejection(epoch=$credentialEpoch, credential=[REDACTED])"
}

/**
 * Process-wide authentication ownership gate.
 *
 * Operation generation protects restore, login, Quick Connect, logout, and server-switch results.
 * Credential epoch separately binds runtime responses to the exact committed session. Both are
 * owned by this single coordinator and all mutations are serialized by [lock].
 */
@Suppress("TooManyFunctions")
class AuthSessionCoordinator {
    private data class ActiveAuthentication(
        val marker: AuthenticatedSessionMarker,
        val method: AuthMethod,
        val epoch: Long,
    )

    private data class OperationSlot(
        val operation: AuthOperation,
        val cancel: () -> Unit,
        val fallbackState: MobileAuthState,
    )

    private data class RejectionDispatch(
        val cancellation: (() -> Unit)?,
        val rejection: AuthenticationRejection,
        val listeners: List<(AuthenticationRejection) -> Unit>,
    )

    private val lock = Any()
    private val _state = MutableStateFlow<MobileAuthState>(MobileAuthState.Unauthenticated)
    val state: StateFlow<MobileAuthState> = _state.asStateFlow()

    private var operationGeneration = 0L
    private var credentialEpoch = 0L
    private var currentOperation: OperationSlot? = null
    private var activeAuthentication: ActiveAuthentication? = null
    private var rejectionClaimedEpoch: Long? = null
    private var pendingRejection: AuthenticationRejection? = null
    private var rejectionDispatchDeferred = false
    private val authenticationRejectionListeners = mutableSetOf<(AuthenticationRejection) -> Unit>()

    fun beginOperation(kind: AuthOperationKind, cancel: () -> Unit = {}): AuthOperation {
        val (previousCancellation, operation) = synchronized(lock) {
            val previous = currentOperation
            val fallbackState = when (kind) {
                AuthOperationKind.SERVER_SWITCH -> MobileAuthState.Unauthenticated
                else -> previous?.fallbackState ?: stableFallbackState()
            }
            operationGeneration += 1L
            val nextOperation = AuthOperation(operationGeneration, kind)
            currentOperation = OperationSlot(nextOperation, cancel, fallbackState)

            if (kind == AuthOperationKind.SERVER_SWITCH) {
                activeAuthentication = null
                rejectionClaimedEpoch = null
                pendingRejection = null
                rejectionDispatchDeferred = false
            }
            _state.value = kind.initialState()
            previous?.cancel to nextOperation
        }
        previousCancellation?.invokeSafely()
        return operation
    }

    /** Starts background restoration only when it cannot supersede an interactive auth operation. */
    fun beginRestoreOperationIfIdle(cancel: () -> Unit = {}): AuthOperation? = synchronized(lock) {
        if (currentOperation != null || (activeAuthentication != null && !activeAuthentication.isUsable())) {
            return@synchronized null
        }
        operationGeneration += 1L
        val operation = AuthOperation(operationGeneration, AuthOperationKind.RESTORE)
        currentOperation = OperationSlot(operation, cancel, stableFallbackState())
        _state.value = MobileAuthState.Restoring
        operation
    }

    fun isCurrent(operation: AuthOperation): Boolean = synchronized(lock) {
        currentOperation?.operation === operation
    }

    /** Runs a short local mutation only while [operation] still owns authentication. */
    fun mutateIfCurrent(operation: AuthOperation, mutation: () -> Boolean): Boolean = synchronized(lock) {
        if (currentOperation?.operation !== operation) return@synchronized false
        runCatching(mutation).getOrDefault(false)
    }

    /** Moves a current operation through a non-authenticated intermediate state. */
    fun transitionIfCurrent(operation: AuthOperation, nextState: MobileAuthState): Boolean = synchronized(lock) {
        require(nextState !is MobileAuthState.Authenticated) {
            "Authenticated state must be published through commitAuthenticated."
        }
        if (currentOperation?.operation !== operation) return@synchronized false
        _state.value = nextState
        true
    }

    /** Completes a current operation without establishing an authenticated session. */
    fun completeOperation(operation: AuthOperation, finalState: MobileAuthState): Boolean = synchronized(lock) {
        require(finalState !is MobileAuthState.Authenticated) {
            "Authenticated state must be published through commitAuthenticated."
        }
        if (currentOperation?.operation !== operation) return@synchronized false
        currentOperation = null
        operationGeneration += 1L
        _state.value = finalState
        true
    }

    /**
     * Completes a current operation and performs its short local cleanup under the same ownership
     * gate. A newer login/logout therefore cannot race between an ownership check and credential
     * removal after rejected or malformed restoration.
     */
    fun completeOperationWithCleanup(
        operation: AuthOperation,
        successState: MobileAuthState,
        cleanup: () -> Boolean,
    ): Boolean {
        var rejectionDispatch: RejectionDispatch? = null
        val cleaned = synchronized(lock) {
            require(successState !is MobileAuthState.Authenticated) {
                "Authenticated state must be published through commitAuthenticated."
            }
            if (currentOperation?.operation !== operation) return@synchronized false
            val didClean = runCatching(cleanup).getOrDefault(false)
            currentOperation = null
            operationGeneration += 1L
            rejectionDispatch = promoteDeferredRejectionLocked()
            if (rejectionDispatch == null) {
                _state.value = if (didClean) successState else MobileAuthState.StorageError
            }
            didClean
        }
        rejectionDispatch.dispatch()
        return cleaned
    }

    /** Cancels only the exact current operation and restores its previous stable state by default. */
    fun cancelIfCurrent(
        operation: AuthOperation,
        nextState: MobileAuthState? = null,
        beforeCancel: (fallbackWasRejected: Boolean) -> Boolean = { true },
    ): Boolean {
        var rejectionDispatch: RejectionDispatch? = null
        val cancellation = synchronized(lock) {
            val slot = currentOperation?.takeIf { it.operation === operation } ?: return false
            val fallbackWasRejected = hasDeferredRejectionLocked()
            if (!runCatching { beforeCancel(fallbackWasRejected) }.getOrDefault(false)) return false
            currentOperation = null
            operationGeneration += 1L
            rejectionDispatch = promoteDeferredRejectionLocked()
            if (rejectionDispatch == null) _state.value = nextState ?: slot.fallbackState
            slot.cancel
        }
        cancellation.invokeSafely()
        rejectionDispatch.dispatch()
        return true
    }

    /**
     * Finishes work owned by [operation] without invoking its supersession callback.
     *
     * The callback belongs to a different owner that replaces this operation. Calling it from the
     * operation's own ordinary failure path would cancel the coroutine before it can return its
     * semantic result to the UI.
     */
    fun finishIfCurrent(
        operation: AuthOperation,
        nextState: MobileAuthState? = null,
        beforeFinish: (fallbackWasRejected: Boolean) -> Boolean = { true },
    ): Boolean {
        var rejectionDispatch: RejectionDispatch? = null
        val finished = synchronized(lock) {
            val slot = currentOperation?.takeIf { it.operation === operation } ?: return@synchronized false
            val fallbackWasRejected = hasDeferredRejectionLocked()
            if (!runCatching { beforeFinish(fallbackWasRejected) }.getOrDefault(false)) {
                return@synchronized false
            }
            currentOperation = null
            operationGeneration += 1L
            rejectionDispatch = promoteDeferredRejectionLocked()
            if (rejectionDispatch == null) _state.value = nextState ?: slot.fallbackState
            true
        }
        rejectionDispatch.dispatch()
        return finished
    }

    /**
     * Commits a newly accepted credential only if [operation] still owns authentication.
     *
     * [commit] is called under the ownership lock with the proposed credential epoch. It must be a
     * short local transaction that securely persists/verifies the session and configures the exact
     * epoch route. Returning false or throwing never publishes the candidate.
     */
    fun commitAuthenticated(
        operation: AuthOperation,
        marker: AuthenticatedSessionMarker,
        method: AuthMethod,
        commit: (credentialEpoch: Long) -> Boolean,
    ): Boolean {
        var rejectionDispatch: RejectionDispatch? = null
        val committed = synchronized(lock) {
            val slot = currentOperation?.takeIf { it.operation === operation } ?: return@synchronized false
            if (
                hasDeferredRejectionLocked() &&
                pendingRejection?.credentialFingerprint == marker.credentialFingerprint
            ) {
                currentOperation = null
                operationGeneration += 1L
                rejectionDispatch = promoteDeferredRejectionLocked()
                return@synchronized false
            }
            val proposedEpoch = credentialEpoch + 1L
            val didCommit = runCatching { commit(proposedEpoch) }.getOrDefault(false)
            currentOperation = null
            operationGeneration += 1L

            if (!didCommit) {
                rejectionDispatch = promoteDeferredRejectionLocked()
                if (rejectionDispatch == null) {
                    _state.value = when {
                        activeAuthentication.isUsable() -> slot.fallbackState
                        else -> MobileAuthState.StorageError
                    }
                }
                return@synchronized false
            }

            credentialEpoch = proposedEpoch
            activeAuthentication = ActiveAuthentication(marker, method, proposedEpoch)
            rejectionClaimedEpoch = null
            pendingRejection = null
            rejectionDispatchDeferred = false
            _state.value = marker.authenticatedState(method, proposedEpoch)
            true
        }
        rejectionDispatch.dispatch()
        return committed
    }

    /** Configures an already validated marker and allocates a new, unique credential epoch. */
    fun configureActiveMarker(marker: AuthenticatedSessionMarker, method: AuthMethod): Long {
        val (cancellation, epoch) = synchronized(lock) {
            operationGeneration += 1L
            val pendingCancellation = currentOperation?.cancel
            currentOperation = null
            credentialEpoch += 1L
            activeAuthentication = ActiveAuthentication(marker, method, credentialEpoch)
            rejectionClaimedEpoch = null
            pendingRejection = null
            rejectionDispatchDeferred = false
            _state.value = marker.authenticatedState(method, credentialEpoch)
            pendingCancellation to credentialEpoch
        }
        cancellation?.invokeSafely()
        return epoch
    }

    fun currentAuthenticatedMarker(): AuthenticatedSessionMarker? = synchronized(lock) {
        activeAuthentication?.marker
    }

    fun currentCredentialEpoch(): Long? = synchronized(lock) {
        activeAuthentication?.epoch
    }

    /** True only while [credential] still belongs to a usable, unrejected active session. */
    fun isCurrentCredentialUsable(credential: String?): Boolean = synchronized(lock) {
        val active = activeAuthentication
        active.isUsable() && requireNotNull(active).marker.matchesCredential(credential)
    }

    /** Captures a request ticket only for the exact active credential. */
    fun authenticatedRequestTicket(credential: String?): AuthenticatedRequestTicket? {
        val fingerprint = credential?.takeIf(String::isNotBlank)?.fingerprint() ?: return null
        return synchronized(lock) {
            val active = activeAuthentication ?: return@synchronized null
            if (
                active.marker.credentialFingerprint != fingerprint ||
                rejectionClaimedEpoch == active.epoch
            ) {
                return@synchronized null
            }
            active.requestTicket()
        }
    }

    /** Captures a request ticket only when the full marker is still active. */
    fun authenticatedRequestTicket(marker: AuthenticatedSessionMarker): AuthenticatedRequestTicket? =
        synchronized(lock) {
            val active = activeAuthentication ?: return@synchronized null
            if (active.marker != marker || rejectionClaimedEpoch == active.epoch) return@synchronized null
            active.requestTicket()
        }

    fun notifyAuthenticationRejected(ticket: AuthenticatedRequestTicket?): Boolean =
        notifyAuthenticationRejected(ticket, HTTP_UNAUTHORIZED)

    /** Publishes and deduplicates only a genuine 401 for the exact active credential epoch. */
    fun notifyAuthenticationRejected(ticket: AuthenticatedRequestTicket?, statusCode: Int): Boolean {
        if (ticket == null) return false
        return claimAuthenticationRejection(
            statusCode = statusCode,
            ownsActiveAuthentication = { active -> active.matches(ticket) },
        )
    }

    /**
     * Atomically claims a transport rejection for [exactCredentialEpoch].
     *
     * Transport has already proven that its immutable snapshot is still current. Comparing the
     * epoch inside this coordinator lock prevents a relogin between separate epoch/marker reads
     * from turning an old response into a rejection ticket for the new session.
     */
    fun notifyAuthenticationRejected(
        exactCredentialEpoch: Long,
        statusCode: Int = HTTP_UNAUTHORIZED,
        prepareFailClosedClear: () -> Boolean = { true },
    ): Boolean = claimAuthenticationRejection(
        statusCode = statusCode,
        ownsActiveAuthentication = { active -> active.epoch == exactCredentialEpoch },
        prepareFailClosedClear = prepareFailClosedClear,
    )

    private fun claimAuthenticationRejection(
        statusCode: Int,
        ownsActiveAuthentication: (ActiveAuthentication) -> Boolean,
        prepareFailClosedClear: () -> Boolean = { true },
    ): Boolean {
        if (statusCode != HTTP_UNAUTHORIZED) return false
        var accepted = false
        val dispatch = synchronized(lock) {
            val active = activeAuthentication ?: return false
            if (
                !ownsActiveAuthentication(active) ||
                rejectionClaimedEpoch == active.epoch
            ) {
                return false
            }

            val operationInProgress = currentOperation != null
            val failClosedPrepared = runCatching(prepareFailClosedClear).getOrDefault(false)
            rejectionClaimedEpoch = active.epoch
            val rejection = active.rejection()
            pendingRejection = rejection
            accepted = true
            if (!failClosedPrepared) {
                val cancellation = currentOperation?.cancel
                currentOperation = null
                rejectionDispatchDeferred = false
                operationGeneration += 1L
                _state.value = MobileAuthState.StorageError
                RejectionDispatch(cancellation, rejection, authenticationRejectionListeners.toList())
            } else if (operationInProgress) {
                rejectionDispatchDeferred = true
                null
            } else {
                rejectionDispatchDeferred = false
                operationGeneration += 1L
                _state.value = if (failClosedPrepared) {
                    MobileAuthState.AuthRejected(active.epoch)
                } else {
                    MobileAuthState.StorageError
                }
                RejectionDispatch(null, rejection, authenticationRejectionListeners.toList())
            }
        }

        dispatch.dispatch()
        return accepted
    }

    /** Late listeners immediately receive an uncleared pending rejection. */
    fun addAuthenticationRejectionListener(listener: (AuthenticationRejection) -> Unit) {
        val rejection = synchronized(lock) {
            authenticationRejectionListeners += listener
            pendingRejection.takeUnless { rejectionDispatchDeferred }
        }
        rejection?.let { listener.invokeSafely(it) }
    }

    fun removeAuthenticationRejectionListener(listener: (AuthenticationRejection) -> Unit) {
        synchronized(lock) { authenticationRejectionListeners -= listener }
    }

    fun rejectionMatches(rejection: AuthenticationRejection, marker: AuthenticatedSessionMarker): Boolean =
        synchronized(lock) {
            val active = activeAuthentication
            active != null && active.matches(rejection) && active.marker == marker
        }

    /**
     * Clears a rejected credential only while [rejection] still identifies the active epoch.
     * [clear] runs under the ownership lock and must be a short, fail-closed local transaction.
     */
    fun compareAndClearAuthenticationRejection(rejection: AuthenticationRejection, clear: () -> Boolean): Boolean =
        synchronized(lock) {
            val active = activeAuthentication ?: return@synchronized false
            val rejectionIsOwned = active.matches(rejection) && pendingRejection === rejection
            val rejectionCanBeClaimed = !rejectionDispatchDeferred && rejectionClaimedEpoch == active.epoch
            if (!rejectionIsOwned || !rejectionCanBeClaimed) {
                return@synchronized false
            }

            val cleared = runCatching(clear).getOrDefault(false)
            if (cleared) {
                activeAuthentication = null
                rejectionClaimedEpoch = null
                pendingRejection = null
                rejectionDispatchDeferred = false
                _state.value = MobileAuthState.Unauthenticated
            } else {
                _state.value = MobileAuthState.StorageError
            }
            cleared
        }

    /**
     * Invalidates all auth operations before performing a short local logout transaction.
     * Failed clearing remains fail-closed in [MobileAuthState.StorageError].
     */
    fun invalidateAndClearForLogout(clear: () -> Boolean): Boolean {
        val (cancellation, cleared) = synchronized(lock) {
            operationGeneration += 1L
            val pendingCancellation = currentOperation?.cancel
            currentOperation = null
            _state.value = MobileAuthState.LoggingOut
            rejectionClaimedEpoch = activeAuthentication?.epoch

            val didClear = runCatching(clear).getOrDefault(false)
            pendingRejection = null
            rejectionDispatchDeferred = false
            if (didClear) {
                activeAuthentication = null
                rejectionClaimedEpoch = null
                _state.value = MobileAuthState.Unauthenticated
            } else {
                _state.value = MobileAuthState.StorageError
            }
            pendingCancellation to didClear
        }
        cancellation?.invokeSafely()
        return cleared
    }

    /** Invalidates current operations and in-memory authentication without invoking storage. */
    fun invalidateAll(nextState: MobileAuthState = MobileAuthState.Unauthenticated) {
        require(nextState !is MobileAuthState.Authenticated) {
            "Authenticated state must be published through commitAuthenticated."
        }
        val cancellation = synchronized(lock) {
            operationGeneration += 1L
            val pendingCancellation = currentOperation?.cancel
            currentOperation = null
            activeAuthentication = null
            rejectionClaimedEpoch = null
            pendingRejection = null
            rejectionDispatchDeferred = false
            _state.value = nextState
            pendingCancellation
        }
        cancellation?.invokeSafely()
    }

    internal fun resetForTests() {
        val cancellation = synchronized(lock) {
            val pendingCancellation = currentOperation?.cancel
            operationGeneration = 0L
            credentialEpoch = 0L
            currentOperation = null
            activeAuthentication = null
            rejectionClaimedEpoch = null
            pendingRejection = null
            rejectionDispatchDeferred = false
            authenticationRejectionListeners.clear()
            _state.value = MobileAuthState.Unauthenticated
            pendingCancellation
        }
        cancellation?.invokeSafely()
    }

    private fun stableFallbackState(): MobileAuthState {
        val active = activeAuthentication
        return when {
            active.isUsable() -> requireNotNull(active).authenticatedState()
            _state.value is MobileAuthState.ConnectivityError -> _state.value
            _state.value is MobileAuthState.StorageError -> _state.value
            else -> MobileAuthState.Unauthenticated
        }
    }

    private fun hasDeferredRejectionLocked(): Boolean {
        val active = activeAuthentication ?: return false
        val rejection = pendingRejection ?: return false
        return rejectionDispatchDeferred &&
            rejectionClaimedEpoch == active.epoch &&
            active.matches(rejection)
    }

    private fun promoteDeferredRejectionLocked(): RejectionDispatch? {
        if (!hasDeferredRejectionLocked()) return null
        val active = requireNotNull(activeAuthentication)
        val rejection = requireNotNull(pendingRejection)
        rejectionDispatchDeferred = false
        _state.value = MobileAuthState.AuthRejected(active.epoch)
        return RejectionDispatch(null, rejection, authenticationRejectionListeners.toList())
    }

    private fun RejectionDispatch?.dispatch() {
        this ?: return
        cancellation?.invokeSafely()
        listeners.forEach { listener -> listener.invokeSafely(rejection) }
    }

    private fun ActiveAuthentication?.isUsable(): Boolean = this != null && rejectionClaimedEpoch != epoch

    private fun ActiveAuthentication.authenticatedState(): MobileAuthState.Authenticated =
        marker.authenticatedState(method, epoch)

    private fun AuthenticatedSessionMarker.authenticatedState(method: AuthMethod, epoch: Long) =
        MobileAuthState.Authenticated(serverId, userId, credentialInstanceId, method, epoch)

    private fun ActiveAuthentication.requestTicket() = AuthenticatedRequestTicket(
        credentialEpoch = epoch,
        serverId = marker.serverId,
        userId = marker.userId,
        credentialFingerprint = marker.credentialFingerprint,
    )

    private fun ActiveAuthentication.rejection() = AuthenticationRejection(
        credentialEpoch = epoch,
        serverId = marker.serverId,
        userId = marker.userId,
        credentialFingerprint = marker.credentialFingerprint,
    )

    private fun ActiveAuthentication.matches(ticket: AuthenticatedRequestTicket): Boolean =
        epoch == ticket.credentialEpoch &&
            marker.serverId == ticket.serverId &&
            marker.userId == ticket.userId &&
            marker.credentialFingerprint == ticket.credentialFingerprint

    private fun ActiveAuthentication.matches(rejection: AuthenticationRejection): Boolean =
        epoch == rejection.credentialEpoch &&
            marker.serverId == rejection.serverId &&
            marker.userId == rejection.userId &&
            marker.credentialFingerprint == rejection.credentialFingerprint

    private fun AuthOperationKind.initialState(): MobileAuthState = when (this) {
        AuthOperationKind.RESTORE -> MobileAuthState.Restoring
        AuthOperationKind.PASSWORD_LOGIN -> MobileAuthState.Authenticating(AuthMethod.PASSWORD)
        AuthOperationKind.QUICK_CONNECT -> MobileAuthState.QuickConnectRequesting
        AuthOperationKind.SERVER_SWITCH -> MobileAuthState.Unauthenticated
    }

    private fun (() -> Unit).invokeSafely() {
        runCatching { invoke() }
    }

    private fun ((AuthenticationRejection) -> Unit).invokeSafely(rejection: AuthenticationRejection) {
        runCatching { invoke(rejection) }
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}

private fun String.fingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
