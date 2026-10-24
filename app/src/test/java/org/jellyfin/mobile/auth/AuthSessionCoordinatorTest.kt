package org.jellyfin.mobile.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AuthSessionCoordinatorTest {
    @Test
    fun `new operation cancels and rejects an older authentication result`() {
        val coordinator = AuthSessionCoordinator()
        val firstCancelled = AtomicBoolean(false)
        val first = coordinator.beginOperation(AuthOperationKind.RESTORE) { firstCancelled.set(true) }
        val second = coordinator.beginOperation(AuthOperationKind.PASSWORD_LOGIN)
        var staleCommitCalled = false

        assertTrue(firstCancelled.get())
        assertFalse(
            coordinator.commitAuthenticated(first, marker(), AuthMethod.RESTORE) {
                staleCommitCalled = true
                true
            },
        )
        assertFalse(staleCommitCalled)
        assertTrue(coordinator.commitAuthenticated(second, marker(), AuthMethod.PASSWORD) { true })
        assertFalse(coordinator.isCurrent(first))
        assertFalse(coordinator.isCurrent(second))
        assertTrue(coordinator.state.value is MobileAuthState.Authenticated)
    }

    @Test
    fun `logout during validation remains logged out and rejects late commit`() {
        val coordinator = AuthSessionCoordinator()
        val cancelled = AtomicBoolean(false)
        val operation = coordinator.beginOperation(AuthOperationKind.RESTORE) { cancelled.set(true) }

        assertTrue(coordinator.transitionIfCurrent(operation, MobileAuthState.Validating))
        assertTrue(coordinator.invalidateAndClearForLogout { true })
        assertTrue(cancelled.get())
        assertEquals(MobileAuthState.Unauthenticated, coordinator.state.value)
        assertFalse(coordinator.commitAuthenticated(operation, marker(), AuthMethod.RESTORE) { true })
        assertNull(coordinator.currentAuthenticatedMarker())
    }

    @Test
    fun `logout clear is atomic against a newer login operation`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val clearStarted = CountDownLatch(1)
        val releaseClear = CountDownLatch(1)
        val logoutFinished = CountDownLatch(1)
        val nextOperation = AtomicReference<AuthOperation?>()
        val nextOperationStarted = CountDownLatch(1)

        Thread {
            coordinator.invalidateAndClearForLogout {
                clearStarted.countDown()
                releaseClear.await(1, TimeUnit.SECONDS)
            }
            logoutFinished.countDown()
        }.start()
        assertTrue(clearStarted.await(1, TimeUnit.SECONDS))

        Thread {
            nextOperation.set(coordinator.beginOperation(AuthOperationKind.PASSWORD_LOGIN))
            nextOperationStarted.countDown()
        }.start()
        assertFalse(nextOperationStarted.await(100, TimeUnit.MILLISECONDS))

        releaseClear.countDown()
        assertTrue(logoutFinished.await(1, TimeUnit.SECONDS))
        assertTrue(nextOperationStarted.await(1, TimeUnit.SECONDS))
        assertTrue(coordinator.isCurrent(requireNotNull(nextOperation.get())))
        assertNull(coordinator.currentAuthenticatedMarker())
    }

    @Test
    fun `simultaneous current 401 responses publish one rejection`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val ticket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))
        val listenerCalls = AtomicInteger()
        coordinator.addAuthenticationRejectionListener { listenerCalls.incrementAndGet() }
        val workerCount = 12
        val ready = CountDownLatch(workerCount)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val results = List(workerCount) {
                executor.submit<Boolean> {
                    ready.countDown()
                    release.await(1, TimeUnit.SECONDS)
                    coordinator.notifyAuthenticationRejected(ticket)
                }
            }
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            release.countDown()

            assertEquals(1, results.count { result -> result.get(1, TimeUnit.SECONDS) })
            assertEquals(1, listenerCalls.get())
            assertTrue(coordinator.state.value is MobileAuthState.AuthRejected)
            assertNull(coordinator.authenticatedRequestTicket(TOKEN))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `claimed runtime rejection makes a captured route credential unrestorable`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val ticket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))

        assertTrue(coordinator.isCurrentCredentialUsable(TOKEN))
        assertTrue(coordinator.notifyAuthenticationRejected(ticket))
        assertFalse(coordinator.isCurrentCredentialUsable(TOKEN))
        assertFalse(coordinator.isCurrentCredentialUsable("different"))
    }

    @Test
    fun `old 401 cannot reject a newer epoch with the same token`() {
        val coordinator = AuthSessionCoordinator()
        val firstEpoch = coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val oldTicket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))
        val secondEpoch = coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val newTicket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))

        assertTrue(secondEpoch > firstEpoch)
        assertFalse(coordinator.notifyAuthenticationRejected(oldTicket))
        assertTrue(coordinator.state.value is MobileAuthState.Authenticated)
        assertTrue(coordinator.notifyAuthenticationRejected(newTicket))
        assertTrue(coordinator.state.value is MobileAuthState.AuthRejected)
    }

    @Test
    fun `credential instance is carried into state and distinguishes same-user relogin`() {
        val coordinator = AuthSessionCoordinator()
        val firstMarker = marker(credentialInstanceId = FIRST_CREDENTIAL_INSTANCE_ID)
        val secondMarker = marker(credentialInstanceId = SECOND_CREDENTIAL_INSTANCE_ID)

        coordinator.configureActiveMarker(firstMarker, AuthMethod.PASSWORD)
        val firstState = coordinator.state.value as MobileAuthState.Authenticated
        assertEquals(FIRST_CREDENTIAL_INSTANCE_ID, firstState.credentialInstanceId)
        assertFalse(firstMarker == secondMarker)

        coordinator.configureActiveMarker(secondMarker, AuthMethod.PASSWORD)
        val secondState = coordinator.state.value as MobileAuthState.Authenticated
        assertEquals(SECOND_CREDENTIAL_INSTANCE_ID, secondState.credentialInstanceId)
    }

    @Test
    fun `runtime 401 cannot supersede a newer replacement login operation`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val oldTicket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))
        val rejectionNotifications = AtomicInteger()
        coordinator.addAuthenticationRejectionListener { rejectionNotifications.incrementAndGet() }
        val replacement = coordinator.beginOperation(AuthOperationKind.PASSWORD_LOGIN)

        assertTrue(coordinator.notifyAuthenticationRejected(oldTicket))
        assertEquals(0, rejectionNotifications.get())
        assertTrue(coordinator.isCurrent(replacement))
        assertTrue(coordinator.state.value is MobileAuthState.Authenticating)
        assertTrue(coordinator.commitAuthenticated(replacement, marker("replacement"), AuthMethod.PASSWORD) { true })
        assertTrue(coordinator.state.value is MobileAuthState.Authenticated)
        assertEquals(0, rejectionNotifications.get())
    }

    @Test
    fun `runtime 401 prepares durable clear and same rejected token cannot recommit`() {
        val coordinator = AuthSessionCoordinator()
        val epoch = coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val replacement = coordinator.beginOperation(AuthOperationKind.PASSWORD_LOGIN)
        var failClosedPrepared = false
        var candidateCommitCalled = false

        assertTrue(
            coordinator.notifyAuthenticationRejected(epoch) {
                failClosedPrepared = true
                true
            },
        )
        assertTrue(failClosedPrepared)
        assertFalse(
            coordinator.commitAuthenticated(replacement, marker(), AuthMethod.PASSWORD) {
                candidateCommitCalled = true
                true
            },
        )

        assertFalse(candidateCommitCalled)
        assertTrue(coordinator.state.value is MobileAuthState.AuthRejected)
    }

    @Test
    fun `failed replacement promotes the deferred old-session rejection exactly once`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val oldTicket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))
        val rejectionNotifications = AtomicInteger()
        coordinator.addAuthenticationRejectionListener { rejectionNotifications.incrementAndGet() }
        val replacement = coordinator.beginOperation(AuthOperationKind.PASSWORD_LOGIN)

        assertTrue(coordinator.notifyAuthenticationRejected(oldTicket))
        assertEquals(0, rejectionNotifications.get())
        assertFalse(coordinator.commitAuthenticated(replacement, marker("replacement"), AuthMethod.PASSWORD) { false })

        assertTrue(coordinator.state.value is MobileAuthState.AuthRejected)
        assertEquals(1, rejectionNotifications.get())
        assertNull(coordinator.authenticatedRequestTicket(TOKEN))
    }

    @Test
    fun `cancelled replacement promotes the deferred old-session rejection exactly once`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val oldTicket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))
        val rejectionNotifications = AtomicInteger()
        coordinator.addAuthenticationRejectionListener { rejectionNotifications.incrementAndGet() }
        val replacement = coordinator.beginOperation(AuthOperationKind.QUICK_CONNECT)

        assertTrue(coordinator.notifyAuthenticationRejected(oldTicket))
        assertTrue(coordinator.cancelIfCurrent(replacement))

        assertTrue(coordinator.state.value is MobileAuthState.AuthRejected)
        assertEquals(1, rejectionNotifications.get())
    }

    @Test
    fun `only runtime 401 can claim the active epoch`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.RESTORE)
        val ticket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))

        listOf(400, 403, 404, 429, 500).forEach { status ->
            assertFalse(coordinator.notifyAuthenticationRejected(ticket, status), "status=$status")
        }
        assertTrue(coordinator.state.value is MobileAuthState.Authenticated)
        assertTrue(coordinator.notifyAuthenticationRejected(ticket, 401))
    }

    @Test
    fun `stale rejection cannot clear a newer same-token session`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val oldTicket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))
        var oldRejection: AuthenticationRejection? = null
        coordinator.addAuthenticationRejectionListener { rejection -> oldRejection = rejection }
        assertTrue(coordinator.notifyAuthenticationRejected(oldTicket))

        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        var staleClearCalled = false
        assertFalse(
            coordinator.compareAndClearAuthenticationRejection(requireNotNull(oldRejection)) {
                staleClearCalled = true
                true
            },
        )
        assertFalse(staleClearCalled)
        assertTrue(coordinator.state.value is MobileAuthState.Authenticated)
    }

    @Test
    fun `failed rejected-session clear stays fail closed and can retry`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val ticket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))
        var rejection: AuthenticationRejection? = null
        coordinator.addAuthenticationRejectionListener { value -> rejection = value }
        assertTrue(coordinator.notifyAuthenticationRejected(ticket))

        assertFalse(coordinator.compareAndClearAuthenticationRejection(requireNotNull(rejection)) { false })
        assertEquals(MobileAuthState.StorageError, coordinator.state.value)
        assertNull(coordinator.authenticatedRequestTicket(TOKEN))
        assertTrue(coordinator.compareAndClearAuthenticationRejection(requireNotNull(rejection)) { true })
        assertEquals(MobileAuthState.Unauthenticated, coordinator.state.value)
        assertNull(coordinator.currentAuthenticatedMarker())
    }

    @Test
    fun `late rejection listener receives the pending rejection`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val ticket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))
        assertTrue(coordinator.notifyAuthenticationRejected(ticket))
        val listenerCalls = AtomicInteger()

        coordinator.addAuthenticationRejectionListener { listenerCalls.incrementAndGet() }

        assertEquals(1, listenerCalls.get())
    }

    @Test
    fun `server switch supersedes operation and invalidates active request tickets`() {
        val coordinator = AuthSessionCoordinator()
        coordinator.configureActiveMarker(marker(), AuthMethod.PASSWORD)
        val oldTicket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))

        val switch = coordinator.beginOperation(AuthOperationKind.SERVER_SWITCH)

        assertTrue(coordinator.isCurrent(switch))
        assertNull(coordinator.currentAuthenticatedMarker())
        assertFalse(coordinator.notifyAuthenticationRejected(oldTicket))
    }

    @Test
    fun `diagnostic strings redact credential and Quick Connect code`() {
        val coordinator = AuthSessionCoordinator()
        val marker = marker()
        coordinator.configureActiveMarker(marker, AuthMethod.PASSWORD)
        val ticket = requireNotNull(coordinator.authenticatedRequestTicket(TOKEN))
        var rejection: AuthenticationRejection? = null
        coordinator.addAuthenticationRejectionListener { value -> rejection = value }
        coordinator.notifyAuthenticationRejected(ticket)
        val quickState = MobileAuthState.QuickConnectWaiting("ABCD12", 123_456L)

        listOf(marker, ticket, requireNotNull(rejection), quickState).forEach { value ->
            assertFalse(value.toString().contains(TOKEN))
            assertFalse(value.toString().contains("ABCD12"))
            assertTrue(value.toString().contains("REDACTED"))
        }
    }

    private fun marker(token: String = TOKEN, credentialInstanceId: UUID = FIRST_CREDENTIAL_INSTANCE_ID) =
        AuthenticatedSessionMarker.fromCredential(
            serverId = "server-a",
            userId = "user-a",
            credentialInstanceId = credentialInstanceId,
            credential = token,
        )

    private companion object {
        const val TOKEN = "PTV_TEST_TOKEN_NEVER_LOG_19473"
        val FIRST_CREDENTIAL_INSTANCE_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val SECOND_CREDENTIAL_INSTANCE_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
    }
}
