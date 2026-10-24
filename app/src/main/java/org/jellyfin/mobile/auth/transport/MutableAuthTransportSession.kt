package org.jellyfin.mobile.auth.transport

/**
 * Passive transport view of the coordinator's exact authenticated credential epoch.
 *
 * The controller replaces the whole snapshot on every successful authentication. Interceptors
 * therefore cannot mistake an older same-token response for the newer credential epoch.
 */
internal class MutableAuthTransportSession : AuthTransportSession {
    private val lock = Any()

    @Volatile
    private var snapshot: AuthTransportSnapshot? = null
    private var rejectionHandler: ((AuthTransportSnapshot) -> Boolean)? = null
    private var acceptedRejectionHandler: ((AuthTransportSnapshot) -> Unit)? = null
    private val authenticatedCalls = mutableMapOf<AuthTransportSnapshot, MutableMap<Any, () -> Unit>>()

    override fun currentSnapshot(): AuthTransportSnapshot? = snapshot

    override fun <T> withCurrentSnapshot(block: (AuthTransportSnapshot?) -> T): T = synchronized(lock) {
        block(snapshot)
    }

    override fun mutateRoute(mutation: () -> Unit) {
        synchronized(lock, mutation)
    }

    override fun publishAuthenticationRejected(snapshot: AuthTransportSnapshot): Boolean {
        val handler = synchronized(lock) {
            if (this.snapshot !== snapshot) return false
            rejectionHandler
        }
        val accepted = handler?.invoke(snapshot) == true
        if (!accepted) return false

        val cancellations = synchronized(lock) {
            if (this.snapshot !== snapshot) return@synchronized emptyList()
            acceptedRejectionHandler?.invoke(snapshot)
            this.snapshot = null
            authenticatedCalls.remove(snapshot)?.values?.toList().orEmpty()
        }
        cancellations.forEach(::cancelSafely)
        return true
    }

    override fun registerAuthenticatedCall(snapshot: AuthTransportSnapshot, owner: Any, cancel: () -> Unit): Boolean =
        synchronized(lock) {
            if (this.snapshot !== snapshot) return@synchronized false
            authenticatedCalls.getOrPut(snapshot, ::mutableMapOf)[owner] = cancel
            true
        }

    override fun unregisterAuthenticatedCall(snapshot: AuthTransportSnapshot, owner: Any) {
        synchronized(lock) {
            authenticatedCalls[snapshot]?.let { calls ->
                calls.remove(owner)
                if (calls.isEmpty()) authenticatedCalls.remove(snapshot)
            }
        }
    }

    fun activate(
        epoch: Long,
        serverUrl: String,
        accessToken: String,
        authorizationHeader: String,
        beforeActivate: () -> Unit = {},
    ) {
        require(epoch > 0L) { "An authenticated transport epoch must be positive." }
        val cancellations = synchronized(lock) {
            val previousCalls = authenticatedCalls.values.flatMap { calls -> calls.values }
            beforeActivate()
            authenticatedCalls.clear()
            snapshot = AuthTransportSnapshot(epoch, serverUrl, accessToken, authorizationHeader)
            previousCalls
        }
        cancellations.forEach(::cancelSafely)
    }

    fun clear(beforeClear: () -> Unit = {}) {
        val cancellations = synchronized(lock) {
            beforeClear()
            snapshot = null
            authenticatedCalls.values.flatMap { calls -> calls.values }.also { authenticatedCalls.clear() }
        }
        cancellations.forEach(::cancelSafely)
    }

    fun setRejectionHandler(handler: ((AuthTransportSnapshot) -> Boolean)?) {
        synchronized(lock) { rejectionHandler = handler }
    }

    fun setAcceptedRejectionHandler(handler: ((AuthTransportSnapshot) -> Unit)?) {
        synchronized(lock) { acceptedRejectionHandler = handler }
    }

    private fun cancelSafely(cancel: () -> Unit) {
        runCatching(cancel)
    }
}
