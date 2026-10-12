package org.jellyfin.mobile.app

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.auth.AuthFailureCategory
import org.jellyfin.mobile.auth.AuthMethod
import org.jellyfin.mobile.auth.AuthSessionCoordinator
import org.jellyfin.mobile.auth.AuthenticatedSessionMarker
import org.jellyfin.mobile.auth.MobileAuthState
import org.jellyfin.mobile.auth.MobileAuthenticationResult
import org.jellyfin.mobile.auth.ServerRevocationResult
import org.jellyfin.mobile.auth.storage.SecureSessionCommitResult
import org.jellyfin.mobile.auth.storage.SecureSessionCredential
import org.jellyfin.mobile.auth.storage.SecureSessionLogoutPreparationResult
import org.jellyfin.mobile.auth.storage.SecureSessionReadResult
import org.jellyfin.mobile.auth.storage.SecureSessionTokenStore
import org.jellyfin.mobile.auth.transport.JellyfinSessionRevoker
import org.jellyfin.mobile.auth.transport.MutableAuthTransportSession
import org.jellyfin.mobile.data.LegacyTokenPresence
import org.jellyfin.mobile.data.LegacyTokenResidueRemediation
import org.jellyfin.mobile.data.dao.ServerDao
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.ServerUser
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.mobile.feature.music.auto.PtvMusicSessionInvalidator
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.reflect.KClass

@Suppress("LargeClass")
class ApiClientControllerTest {
    @Test
    fun `secure restore is authoritative and repairs stale local pointers`() = runBlocking<Unit> {
        val fixture = fixture(currentServerId = STALE_SERVER_ID, currentUserId = STALE_LOCAL_USER_ID)
        every { fixture.secureStore.readResult() } returnsMany listOf(
            SecureSessionReadResult.Available(CREDENTIAL),
            SecureSessionReadResult.Available(CREDENTIAL),
        )
        every { fixture.serverDao.getServer(SERVER_ID) } returns SERVER
        every { fixture.userDao.upsert(SERVER_ID, USER_ID) } returns CURRENT_LOCAL_USER_ID
        every { fixture.userDao.clearAllLegacyAccessTokensVerified() } returns true

        val result = fixture.controller.restoreSession()

        result.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().apply {
            user.id shouldBe USER_ID
            method shouldBe AuthMethod.RESTORE
        }
        fixture.currentServerId shouldBe SERVER_ID
        fixture.currentUserId shouldBe CURRENT_LOCAL_USER_ID
        fixture.apiClient.baseUrl shouldBe SERVER_URL
        fixture.apiClient.accessToken shouldBe ACCESS_TOKEN
        fixture.controller.authenticatedUserId shouldBe USER_ID
        fixture.controller.authState.value.shouldBeInstanceOf<MobileAuthState.Authenticated>().apply {
            serverId shouldBe SERVER_ID.toString()
            userId shouldBe USER_ID.toString()
            credentialInstanceId shouldBe CREDENTIAL_INSTANCE_ID
            method shouldBe AuthMethod.RESTORE
        }
        verify(exactly = 1) { fixture.serverDao.getServer(SERVER_ID) }
        verify(exactly = 0) { fixture.serverDao.getServer(STALE_SERVER_ID) }
        verify(exactly = 1) { fixture.userDao.upsert(SERVER_ID, USER_ID) }
        verify(exactly = 0) { fixture.secureStore.clear(any()) }
    }

    @Test
    fun `user-specific 401 403 and 404 clear the rejected secure credential`() = runBlocking<Unit> {
        listOf(401, 403, 404).forEach { status ->
            val fixture = preparedRestoreFixture()
            fixture.apiClient.respondWith { _, path ->
                check(path == CURRENT_USER_PATH)
                throw InvalidStatusException(status, null)
            }

            val result = fixture.controller.restoreSession()

            result shouldBe MobileAuthenticationResult.LoginRequired
            fixture.controller.authState.value shouldBe MobileAuthState.Unauthenticated
            fixture.controller.authenticatedUserId shouldBe null
            fixture.currentUserId shouldBe null
            fixture.apiClient.baseUrl shouldBe SERVER_URL
            fixture.apiClient.accessToken shouldBe null
            verify(exactly = 1) { fixture.secureStore.clear(any()) }
        }
    }

    @Test
    fun `restore IOException and 500 preserve the secure credential for retry`() = runBlocking<Unit> {
        val cases = listOf(
            IOException("offline") to AuthFailureCategory.NETWORK_UNAVAILABLE,
            InvalidStatusException(500, null) to AuthFailureCategory.SERVER_UNAVAILABLE,
        )

        cases.forEach { (failure, expectedCategory) ->
            val fixture = preparedRestoreFixture()
            fixture.apiClient.respondWith { _, path ->
                check(path == CURRENT_USER_PATH)
                throw failure
            }

            val result = fixture.controller.restoreSession()

            result shouldBe MobileAuthenticationResult.RecoverableFailure(expectedCategory)
            fixture.controller.authState.value shouldBe MobileAuthState.ConnectivityError(expectedCategory)
            fixture.currentServerId shouldBe SERVER_ID
            fixture.currentUserId shouldBe CURRENT_LOCAL_USER_ID
            fixture.apiClient.baseUrl shouldBe SERVER_URL
            fixture.apiClient.accessToken shouldBe null
            verify(exactly = 0) { fixture.secureStore.clear(any()) }
            verify(exactly = 2) { fixture.secureStore.readResult() }
        }
    }

    @Test
    fun `dao fault after secure read preserves credential and converges to storage error`() = runBlocking<Unit> {
        val fixture = fixture()
        every { fixture.secureStore.readResult() } returns SecureSessionReadResult.Available(CREDENTIAL)
        every { fixture.serverDao.getServer(SERVER_ID) } returns SERVER
        every { fixture.userDao.upsert(SERVER_ID, USER_ID) } throws IllegalStateException("database unavailable")

        val result = fixture.controller.restoreSession()

        result shouldBe MobileAuthenticationResult.SecureStorageFailure
        fixture.controller.authState.value shouldBe MobileAuthState.StorageError
        fixture.apiClient.baseUrl shouldBe SERVER_URL
        fixture.apiClient.accessToken shouldBe null
        verify(exactly = 2) { fixture.secureStore.readResult() }
        verify(exactly = 0) { fixture.secureStore.clear(any()) }
        verify(exactly = 0) { fixture.userDao.clearAllLegacyAccessTokensVerified() }
    }

    @Test
    fun `armed clear pending is consumed before server or user lookup`() = runBlocking<Unit> {
        val events = mutableListOf<String>()
        val fixture = fixture(currentServerId = STALE_SERVER_ID, currentUserId = STALE_LOCAL_USER_ID)
        every { fixture.secureStore.readResult() } answers {
            events += "secure-read"
            SecureSessionReadResult.ClearPending
        }
        every { fixture.secureStore.clear(any()) } answers {
            events += "secure-clear"
            firstArg<() -> Boolean>().invoke()
        }
        every { fixture.userDao.clearAllLegacyAccessTokensVerified() } answers {
            events += "legacy-clear"
            true
        }
        fixture.onUserPointerCommit = { events += "user-pointer-clear" }

        val result = fixture.controller.restoreSession()

        result shouldBe MobileAuthenticationResult.LoginRequired
        fixture.controller.authState.value shouldBe MobileAuthState.Unauthenticated
        fixture.currentUserId shouldBe null
        events.shouldContainExactly("secure-read", "secure-clear", "legacy-clear", "user-pointer-clear")
        verify(exactly = 0) { fixture.serverDao.getServer(any()) }
        verify(exactly = 0) { fixture.userDao.upsert(any(), any()) }
        verify(exactly = 0) { fixture.userDao.getServerUser(any<Long>(), any<Long>()) }
    }

    @Test
    fun `legacy migration verifies secure activation before logical and physical cleanup`() = runBlocking<Unit> {
        val events = mutableListOf<String>()
        val fixture = fixture(currentServerId = SERVER_ID, currentUserId = CURRENT_LOCAL_USER_ID)
        fixture.configureInstallTimes(firstInstallTime = 1_000L, lastUpdateTime = 2_000L)
        every { fixture.legacyTokenResidueRemediation.isComplete() } returns false
        every { fixture.legacyTokenResidueRemediation.legacyTokenPresence() } returns LegacyTokenPresence.PRESENT
        every { fixture.userDao.getServerUser(SERVER_ID, CURRENT_LOCAL_USER_ID) } returns LEGACY_SERVER_USER
        every { fixture.userDao.clearAllLegacyAccessTokensVerified() } returns true
        lateinit var activatedCredential: SecureSessionCredential
        var secureActive = false
        var verifiedReadbackRecorded = false
        every { fixture.secureStore.readResult() } answers {
            if (secureActive) {
                if (!verifiedReadbackRecorded) {
                    events += "secure-readback"
                    verifiedReadbackRecorded = true
                }
                SecureSessionReadResult.Available(activatedCredential)
            } else {
                events += "legacy-read"
                SecureSessionReadResult.Missing
            }
        }
        every { fixture.secureStore.activate(any(), any()) } answers {
            events += "secure-activate"
            activatedCredential = firstArg()
            secureActive = true
            SecureSessionCommitResult.ACTIVATED
        }
        every {
            fixture.legacyTokenResidueRemediation.clearLogicalTokensAfterSecureVerification()
        } answers {
            events += "logical-clear"
            true
        }
        every { fixture.legacyTokenResidueRemediation.sanitizeHistoricalResidue(ACCESS_TOKEN) } answers {
            events += "physical-sanitize"
            true
        }
        fixture.apiClient.respondWith { _, path ->
            check(path == CURRENT_USER_PATH)
            events += "server-validate"
            currentUserResponse()
        }

        val result = fixture.controller.restoreSession()

        result.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().method shouldBe AuthMethod.RESTORE
        events.shouldContainExactly(
            "legacy-read",
            "secure-activate",
            "secure-readback",
            "logical-clear",
            "physical-sanitize",
            "server-validate",
        )
        activatedCredential.accessToken shouldBe ACCESS_TOKEN
        verify(exactly = 1) { fixture.secureStore.activate(any(), any()) }
    }

    @Test
    fun `secure activation failure preserves legacy state and never starts sanitation`() = runBlocking<Unit> {
        val fixture = fixture(currentServerId = SERVER_ID, currentUserId = CURRENT_LOCAL_USER_ID)
        fixture.configureInstallTimes(firstInstallTime = 1_000L, lastUpdateTime = 2_000L)
        every { fixture.legacyTokenResidueRemediation.isComplete() } returns false
        every { fixture.legacyTokenResidueRemediation.legacyTokenPresence() } returns LegacyTokenPresence.PRESENT
        every { fixture.secureStore.readResult() } returns SecureSessionReadResult.Missing
        every { fixture.userDao.getServerUser(SERVER_ID, CURRENT_LOCAL_USER_ID) } returns LEGACY_SERVER_USER
        every { fixture.secureStore.activate(any(), any()) } returns SecureSessionCommitResult.STORAGE_FAILURE

        val result = fixture.controller.restoreSession()

        result shouldBe MobileAuthenticationResult.SecureStorageFailure
        fixture.currentUserId shouldBe CURRENT_LOCAL_USER_ID
        LEGACY_SERVER_USER.user.legacyAccessToken shouldBe ACCESS_TOKEN
        fixture.apiClient.requests shouldBe emptyList()
        verify(exactly = 0) {
            fixture.legacyTokenResidueRemediation.clearLogicalTokensAfterSecureVerification()
        }
        verify(exactly = 0) { fixture.legacyTokenResidueRemediation.sanitizeHistoricalResidue(any()) }
    }

    @Suppress("LongMethod")
    @Test
    fun `logical clear failure after secure activation preserves both credentials and retry succeeds`() =
        runBlocking<Unit> {
            val fixture = fixture(currentServerId = SERVER_ID, currentUserId = CURRENT_LOCAL_USER_ID)
            fixture.configureInstallTimes(firstInstallTime = 1_000L, lastUpdateTime = 2_000L)
            var logicalLegacyToken: String? = ACCESS_TOKEN
            var remediationComplete = false
            lateinit var activatedCredential: SecureSessionCredential
            var secureActive = false

            every { fixture.legacyTokenResidueRemediation.isComplete() } answers { remediationComplete }
            every { fixture.legacyTokenResidueRemediation.legacyTokenPresence() } answers {
                if (logicalLegacyToken == null) LegacyTokenPresence.ABSENT else LegacyTokenPresence.PRESENT
            }
            every { fixture.userDao.getServerUser(SERVER_ID, CURRENT_LOCAL_USER_ID) } answers {
                LEGACY_SERVER_USER.copy(
                    user = LEGACY_SERVER_USER.user.copy(legacyAccessToken = logicalLegacyToken),
                )
            }
            every { fixture.userDao.clearAllLegacyAccessTokensVerified() } returns true
            every { fixture.serverDao.getServer(SERVER_ID) } returns SERVER
            every { fixture.userDao.upsert(SERVER_ID, USER_ID) } returns CURRENT_LOCAL_USER_ID
            every { fixture.secureStore.readResult() } answers {
                if (secureActive) {
                    SecureSessionReadResult.Available(activatedCredential)
                } else {
                    SecureSessionReadResult.Missing
                }
            }
            every { fixture.secureStore.activate(any(), any()) } answers {
                activatedCredential = firstArg()
                secureActive = true
                SecureSessionCommitResult.ACTIVATED
            }
            var logicalClearAttempts = 0
            every {
                fixture.legacyTokenResidueRemediation.clearLogicalTokensAfterSecureVerification()
            } answers {
                logicalClearAttempts += 1
                if (logicalClearAttempts == 1) {
                    false
                } else {
                    logicalLegacyToken = null
                    true
                }
            }
            every {
                fixture.legacyTokenResidueRemediation.sanitizeHistoricalResidue(ACCESS_TOKEN)
            } answers {
                remediationComplete = true
                true
            }

            val failedRestore = fixture.controller.restoreSession()

            failedRestore shouldBe MobileAuthenticationResult.SecureStorageFailure
            secureActive shouldBe true
            (fixture.secureStore.readResult() as SecureSessionReadResult.Available)
                .credential.accessToken shouldBe ACCESS_TOKEN
            logicalLegacyToken shouldBe ACCESS_TOKEN
            remediationComplete shouldBe false
            verify(exactly = 0) {
                fixture.legacyTokenResidueRemediation.sanitizeHistoricalResidue(any())
            }

            val retriedRestore = fixture.controller.restoreSession()

            retriedRestore.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().method shouldBe
                AuthMethod.RESTORE
            logicalLegacyToken shouldBe null
            remediationComplete shouldBe true
            fixture.apiClient.accessToken shouldBe ACCESS_TOKEN
            verify(exactly = 1) { fixture.secureStore.activate(any(), any()) }
            verify(exactly = 2) {
                fixture.legacyTokenResidueRemediation.clearLogicalTokensAfterSecureVerification()
            }
            verify(exactly = 1) {
                fixture.legacyTokenResidueRemediation.sanitizeHistoricalResidue(ACCESS_TOKEN)
            }
        }

    @Test
    fun `sanitation failure keeps secure credential and restart retries without remigration`() = runBlocking<Unit> {
        val fixture = fixture(currentServerId = SERVER_ID, currentUserId = CURRENT_LOCAL_USER_ID)
        fixture.configureInstallTimes(firstInstallTime = 1_000L, lastUpdateTime = 2_000L)
        every { fixture.legacyTokenResidueRemediation.isComplete() } returns false
        every { fixture.legacyTokenResidueRemediation.legacyTokenPresence() } returns LegacyTokenPresence.PRESENT
        every { fixture.userDao.getServerUser(SERVER_ID, CURRENT_LOCAL_USER_ID) } returns LEGACY_SERVER_USER
        every { fixture.userDao.clearAllLegacyAccessTokensVerified() } returns true
        every { fixture.serverDao.getServer(SERVER_ID) } returns SERVER
        every { fixture.userDao.upsert(SERVER_ID, USER_ID) } returns CURRENT_LOCAL_USER_ID
        lateinit var activatedCredential: SecureSessionCredential
        var secureActive = false
        every { fixture.secureStore.readResult() } answers {
            if (secureActive) {
                SecureSessionReadResult.Available(activatedCredential)
            } else {
                SecureSessionReadResult.Missing
            }
        }
        every { fixture.secureStore.activate(any(), any()) } answers {
            activatedCredential = firstArg()
            secureActive = true
            SecureSessionCommitResult.ACTIVATED
        }
        every {
            fixture.legacyTokenResidueRemediation.clearLogicalTokensAfterSecureVerification()
        } returns true
        every {
            fixture.legacyTokenResidueRemediation.sanitizeHistoricalResidue(ACCESS_TOKEN)
        } returnsMany listOf(false, true)

        val firstRestore = fixture.controller.restoreSession()
        val retriedRestore = fixture.controller.restoreSession()

        firstRestore shouldBe MobileAuthenticationResult.SecureStorageFailure
        retriedRestore.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().method shouldBe AuthMethod.RESTORE
        secureActive shouldBe true
        activatedCredential.accessToken shouldBe ACCESS_TOKEN
        fixture.apiClient.accessToken shouldBe ACCESS_TOKEN
        verify(exactly = 1) { fixture.secureStore.activate(any(), any()) }
        verify(exactly = 2) {
            fixture.legacyTokenResidueRemediation.sanitizeHistoricalResidue(ACCESS_TOKEN)
        }
    }

    @Test
    fun `already migrated secure session sanitizes before validation and restores normally`() = runBlocking<Unit> {
        val events = mutableListOf<String>()
        val fixture = preparedRestoreFixture()
        every {
            fixture.legacyTokenResidueRemediation.clearLogicalTokensAfterSecureVerification()
        } answers {
            events += "logical-clear"
            true
        }
        every { fixture.legacyTokenResidueRemediation.sanitizeHistoricalResidue(ACCESS_TOKEN) } answers {
            events += "physical-sanitize"
            true
        }
        fixture.apiClient.respondWith { _, path ->
            check(path == CURRENT_USER_PATH)
            events += "server-validate"
            currentUserResponse()
        }

        val result = fixture.controller.restoreSession()

        result.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().method shouldBe AuthMethod.RESTORE
        events.shouldContainExactly("logical-clear", "physical-sanitize", "server-validate")
        fixture.apiClient.accessToken shouldBe ACCESS_TOKEN
    }

    @Test
    fun `fresh install records pristine state without historical sanitation`() = runBlocking<Unit> {
        val fixture = fixture()
        fixture.configureInstallTimes(firstInstallTime = 2_000L, lastUpdateTime = 2_000L)
        every { fixture.secureStore.readResult() } returns SecureSessionReadResult.Missing
        every { fixture.legacyTokenResidueRemediation.isComplete() } returns false
        every { fixture.legacyTokenResidueRemediation.legacyTokenPresence() } returns LegacyTokenPresence.ABSENT
        every { fixture.legacyTokenResidueRemediation.markFreshInstallPristine() } returns true
        every { fixture.userDao.clearAllLegacyAccessTokensVerified() } returns true
        every { fixture.secureStore.clear(any()) } answers { firstArg<() -> Boolean>().invoke() }

        val result = fixture.controller.restoreSession()

        result shouldBe MobileAuthenticationResult.LoginRequired
        verify(exactly = 1) { fixture.legacyTokenResidueRemediation.markFreshInstallPristine() }
        verify(exactly = 0) { fixture.legacyTokenResidueRemediation.sanitizeHistoricalResidue(any()) }
        verify(exactly = 0) { fixture.secureStore.activate(any(), any()) }
    }

    @Test
    fun `password login commits the verified credential and repeated login replaces it`() = runBlocking<Unit> {
        val fixture = preparedCredentialCommitFixture()
        val tokens = ArrayDeque(listOf(FIRST_LOGIN_TOKEN, REPLACEMENT_LOGIN_TOKEN))
        fixture.apiClient.respondWith { _, path ->
            check(path == PASSWORD_AUTH_PATH)
            authenticationResponse(tokens.removeFirst())
        }

        val first = fixture.controller.authenticatePassword(SERVER, "Piggie", "first-password")
        val replacement = fixture.controller.authenticatePassword(SERVER, "Piggie", "replacement-password")

        first.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().method shouldBe AuthMethod.PASSWORD
        replacement.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().method shouldBe AuthMethod.PASSWORD
        fixture.activatedCredentials.map(SecureSessionCredential::accessToken).shouldContainExactly(
            FIRST_LOGIN_TOKEN,
            REPLACEMENT_LOGIN_TOKEN,
        )
        fixture.activatedCredentials.map(SecureSessionCredential::credentialInstanceId).distinct().shouldHaveSize(2)
        fixture.apiClient.accessToken shouldBe REPLACEMENT_LOGIN_TOKEN
        fixture.authTransportSession.currentSnapshot()
            ?.matchesAccessToken(REPLACEMENT_LOGIN_TOKEN) shouldBe true
        fixture.controller.authState.value.shouldBeInstanceOf<MobileAuthState.Authenticated>().apply {
            method shouldBe AuthMethod.PASSWORD
            credentialInstanceId shouldBe fixture.activatedCredentials.last().credentialInstanceId
        }
        verify(exactly = 2) { fixture.secureStore.activateAfterPreparedClear(any(), any()) }
        verify(exactly = 2) { fixture.userDao.clearAllLegacyAccessTokensVerified() }
    }

    @Test
    fun `concurrent password login supersedes the older request before credential commit`() = runBlocking<Unit> {
        val fixture = preparedCredentialCommitFixture()
        val firstRequestStarted = CompletableDeferred<Unit>()
        val holdFirstRequest = CompletableDeferred<Unit>()
        var passwordRequests = 0
        fixture.apiClient.respondWith { _, path ->
            check(path == PASSWORD_AUTH_PATH)
            passwordRequests += 1
            if (passwordRequests == 1) {
                firstRequestStarted.complete(Unit)
                holdFirstRequest.await()
                authenticationResponse(FIRST_LOGIN_TOKEN)
            } else {
                authenticationResponse(REPLACEMENT_LOGIN_TOKEN)
            }
        }
        val firstLogin = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.controller.authenticatePassword(SERVER, "Piggie", "losing-password")
        }
        firstRequestStarted.await()

        val winner = fixture.controller.authenticatePassword(SERVER, "Piggie", "winning-password")
        holdFirstRequest.complete(Unit)
        firstLogin.join()

        winner.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().method shouldBe AuthMethod.PASSWORD
        firstLogin.isCancelled shouldBe true
        fixture.activatedCredentials.map(SecureSessionCredential::accessToken)
            .shouldContainExactly(REPLACEMENT_LOGIN_TOKEN)
        fixture.apiClient.accessToken shouldBe REPLACEMENT_LOGIN_TOKEN
        fixture.controller.authState.value.shouldBeInstanceOf<MobileAuthState.Authenticated>().apply {
            method shouldBe AuthMethod.PASSWORD
        }
        verify(exactly = 1) { fixture.secureStore.activateAfterPreparedClear(any(), any()) }
    }

    @Test
    fun `queued restore cannot supersede the password login that replaced its predecessor`() = runBlocking<Unit> {
        val fixture = preparedRestoreFixture()
        val restoreValidationStarted = CompletableDeferred<Unit>()
        val holdRestoreValidation = CompletableDeferred<Unit>()
        val passwordAuthenticationStarted = CompletableDeferred<Unit>()
        val releasePasswordAuthentication = CompletableDeferred<Unit>()
        fixture.apiClient.respondWith { _, path ->
            when (path) {
                CURRENT_USER_PATH -> {
                    restoreValidationStarted.complete(Unit)
                    holdRestoreValidation.await()
                    currentUserResponse()
                }

                PASSWORD_AUTH_PATH -> {
                    passwordAuthenticationStarted.complete(Unit)
                    releasePasswordAuthentication.await()
                    authenticationResponse(REPLACEMENT_LOGIN_TOKEN)
                }

                else -> error("Unexpected request path $path")
            }
        }
        every { fixture.secureStore.activateAfterPreparedClear(any(), any()) } answers {
            fixture.activatedCredentials += firstArg<SecureSessionCredential>()
            if (secondArg<() -> Boolean>().invoke()) {
                SecureSessionCommitResult.ACTIVATED
            } else {
                SecureSessionCommitResult.POST_ACTIVATION_CALLBACK_FAILED_ROLLED_BACK
            }
        }
        val firstRestore = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.controller.restoreSession()
        }
        restoreValidationStarted.await()
        val queuedRestore = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.controller.restoreSession()
        }

        val passwordLogin = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.controller.authenticatePassword(SERVER, "Piggie", "winning-password")
        }
        passwordAuthenticationStarted.await()
        val queuedRestoreResult = queuedRestore.await()

        queuedRestoreResult shouldBe MobileAuthenticationResult.Superseded
        fixture.controller.authState.value shouldBe MobileAuthState.Authenticating(AuthMethod.PASSWORD)
        releasePasswordAuthentication.complete(Unit)
        val passwordResult = passwordLogin.await()
        holdRestoreValidation.complete(Unit)
        firstRestore.join()

        firstRestore.isCancelled shouldBe true
        passwordResult.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().method shouldBe
            AuthMethod.PASSWORD
        fixture.activatedCredentials.map(SecureSessionCredential::accessToken)
            .shouldContainExactly(REPLACEMENT_LOGIN_TOKEN)
        fixture.apiClient.accessToken shouldBe REPLACEMENT_LOGIN_TOKEN
        verify(exactly = 2) { fixture.secureStore.readResult() }
        verify(exactly = 1) { fixture.secureStore.activateAfterPreparedClear(any(), any()) }
    }

    @Test
    fun `Quick Connect initiation poll and final authentication securely persist one credential`() = runBlocking<Unit> {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1L
            val fixture = preparedCredentialCommitFixture()
            fixture.apiClient.respondWith { _, path ->
                when (path) {
                    QUICK_CONNECT_ENABLED_PATH -> rawResponse("true")
                    QUICK_CONNECT_INITIATE_PATH -> quickConnectResponse(authenticated = false)
                    QUICK_CONNECT_POLL_PATH -> quickConnectResponse(authenticated = true)
                    QUICK_CONNECT_AUTH_PATH -> authenticationResponse(QUICK_CONNECT_TOKEN)
                    else -> error("Unexpected request path $path")
                }
            }

            val result = fixture.controller.authenticateWithQuickConnect(SERVER)

            result.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().method shouldBe
                AuthMethod.QUICK_CONNECT
            fixture.apiClient.requests.shouldContainExactly(
                "GET $QUICK_CONNECT_ENABLED_PATH",
                "POST $QUICK_CONNECT_INITIATE_PATH",
                "GET $QUICK_CONNECT_POLL_PATH",
                "POST $QUICK_CONNECT_AUTH_PATH",
            )
            fixture.activatedCredentials.single().accessToken shouldBe QUICK_CONNECT_TOKEN
            fixture.apiClient.accessToken shouldBe QUICK_CONNECT_TOKEN
            fixture.controller.authState.value.shouldBeInstanceOf<MobileAuthState.Authenticated>().apply {
                method shouldBe AuthMethod.QUICK_CONNECT
                credentialInstanceId shouldBe fixture.activatedCredentials.single().credentialInstanceId
            }
            verify(exactly = 1) { fixture.secureStore.activateAfterPreparedClear(any(), any()) }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test
    fun `Quick Connect duplicate is rejected and cancellation restores the prior route`() = runBlocking<Unit> {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1L
            val fixture = fixture(
                currentServerId = SERVER_ID,
                initialBaseUrl = SERVER_URL,
            )
            val requestStarted = CompletableDeferred<Unit>()
            val holdRequest = CompletableDeferred<Unit>()
            fixture.apiClient.respondWith { _, path ->
                check(path == QUICK_CONNECT_ENABLED_PATH)
                requestStarted.complete(Unit)
                holdRequest.await()
                rawResponse("true")
            }
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.controller.authenticateWithQuickConnect(SERVER)
            }
            requestStarted.await()

            fixture.controller.authenticateWithQuickConnect(SERVER) shouldBe
                MobileAuthenticationResult.QuickConnectAlreadyRunning
            fixture.controller.cancelQuickConnect() shouldBe true
            holdRequest.complete(Unit)
            active.join()

            active.isCancelled shouldBe true
            fixture.controller.cancelQuickConnect() shouldBe false
            fixture.apiClient.baseUrl shouldBe SERVER_URL
            fixture.apiClient.accessToken shouldBe null
            fixture.controller.authState.value shouldBe MobileAuthState.Unauthenticated
            verify(exactly = 0) { fixture.secureStore.activateAfterPreparedClear(any(), any()) }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test
    fun `Quick Connect cancellation wins against a returning final authentication response`() = runBlocking<Unit> {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1L
            val fixture = preparedCredentialCommitFixture()
            val finalAuthenticationStarted = CompletableDeferred<Unit>()
            val releaseFinalAuthentication = CompletableDeferred<Unit>()
            fixture.apiClient.respondWith { _, path ->
                when (path) {
                    QUICK_CONNECT_ENABLED_PATH -> rawResponse("true")

                    QUICK_CONNECT_INITIATE_PATH -> quickConnectResponse(authenticated = false)

                    QUICK_CONNECT_POLL_PATH -> quickConnectResponse(authenticated = true)

                    QUICK_CONNECT_AUTH_PATH -> {
                        finalAuthenticationStarted.complete(Unit)
                        releaseFinalAuthentication.await()
                        authenticationResponse(QUICK_CONNECT_TOKEN)
                    }

                    else -> error("Unexpected request path $path")
                }
            }
            val outcome = startWithoutJob {
                fixture.controller.authenticateWithQuickConnect(SERVER)
            }
            finalAuthenticationStarted.await()

            fixture.controller.cancelQuickConnect() shouldBe true
            releaseFinalAuthentication.complete(Unit)
            val result = outcome.await().getOrThrow()

            result shouldBe MobileAuthenticationResult.Superseded
            fixture.activatedCredentials shouldBe emptyList()
            fixture.apiClient.baseUrl shouldBe SERVER_URL
            fixture.apiClient.accessToken shouldBe null
            fixture.authTransportSession.currentSnapshot() shouldBe null
            fixture.controller.authState.value shouldBe MobileAuthState.Unauthenticated
            fixture.controller.cancelQuickConnect() shouldBe false
            verify(exactly = 0) { fixture.secureStore.activateAfterPreparedClear(any(), any()) }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test
    fun `password login supersedes an in-flight Quick Connect attempt`() = runBlocking<Unit> {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 1L
            val fixture = preparedCredentialCommitFixture()
            val quickConnectStarted = CompletableDeferred<Unit>()
            val holdQuickConnect = CompletableDeferred<Unit>()
            fixture.apiClient.respondWith { _, path ->
                when (path) {
                    QUICK_CONNECT_ENABLED_PATH -> {
                        quickConnectStarted.complete(Unit)
                        holdQuickConnect.await()
                        rawResponse("true")
                    }

                    PASSWORD_AUTH_PATH -> authenticationResponse(REPLACEMENT_LOGIN_TOKEN)

                    else -> error("Unexpected request path $path")
                }
            }
            val quickConnect = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.controller.authenticateWithQuickConnect(SERVER)
            }
            quickConnectStarted.await()

            val password = fixture.controller.authenticatePassword(
                SERVER,
                "Piggie",
                "winning-password",
            )
            holdQuickConnect.complete(Unit)
            quickConnect.join()

            password.shouldBeInstanceOf<MobileAuthenticationResult.Authenticated>().method shouldBe
                AuthMethod.PASSWORD
            quickConnect.isCancelled shouldBe true
            fixture.activatedCredentials.map(SecureSessionCredential::accessToken)
                .shouldContainExactly(REPLACEMENT_LOGIN_TOKEN)
            fixture.apiClient.accessToken shouldBe REPLACEMENT_LOGIN_TOKEN
            fixture.controller.cancelQuickConnect() shouldBe false
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test
    fun `logout arms fail closed storage then revokes and always clears locally on success`() = runBlocking<Unit> {
        val fixture = logoutFixture()

        val result = fixture.controller.logoutCurrentUser()

        result.localCredentialsCleared shouldBe true
        result.serverRevocation shouldBe ServerRevocationResult.SUCCEEDED
        fixture.controller.authState.value shouldBe MobileAuthState.Unauthenticated
        fixture.currentUserId shouldBe null
        fixture.apiClient.accessToken shouldBe null
        fixture.apiClient.requests shouldBe emptyList()
        fixture.revocationObservedSharedRouteTokens shouldBe listOf(null)
        val authorization = fixture.revocationAuthorization.single()
        authorization shouldContain ACCESS_TOKEN
        authorization shouldNotContain SHARED_ROUTE_TOKEN
        verifyOrder {
            fixture.secureStore.prepareFailClosedLogout()
            fixture.serverDao.getServer(SERVER_ID)
            fixture.revoker.revoke(SERVER_URL, any())
            fixture.secureStore.clear(any())
        }
        verify(exactly = 0) { fixture.secureStore.readResult() }
        verify(exactly = 0) { fixture.legacyTokenResidueRemediation.sanitizeHistoricalResidue(any()) }
    }

    @Test
    fun `throwing logout revoker cannot prevent guarded local clear`() = runBlocking<Unit> {
        val fixture = logoutFixture()
        every { fixture.revoker.revoke(SERVER_URL, any()) } throws IllegalStateException("network down")

        val result = fixture.controller.logoutCurrentUser()

        result.localCredentialsCleared shouldBe true
        result.serverRevocation shouldBe ServerRevocationResult.FAILED
        fixture.controller.authState.value shouldBe MobileAuthState.Unauthenticated
        fixture.currentUserId shouldBe null
        fixture.apiClient.accessToken shouldBe null
        fixture.apiClient.requests shouldBe emptyList()
        verifyOrder {
            fixture.secureStore.prepareFailClosedLogout()
            fixture.revoker.revoke(SERVER_URL, any())
            fixture.secureStore.clear(any())
        }
        verify(exactly = 1) { fixture.userDao.clearAllLegacyAccessTokensVerified() }
        verify(exactly = 1) { fixture.appPreferences.commitCurrentUserId(null) }
    }

    @Test
    fun `accepted runtime rejection clears immediately and coalesces async credential cleanup`() {
        val queuedDispatcher = QueuedDispatcher()
        val fixture = fixture(
            initialBaseUrl = SERVER_URL,
            initialAccessToken = ACCESS_TOKEN,
            ioDispatcher = queuedDispatcher,
        )
        every { fixture.serverDao.getServer(SERVER_ID) } returns SERVER
        every { fixture.userDao.clearAllLegacyAccessTokensVerified() } returns true
        every { fixture.secureStore.clear(any()) } answers { firstArg<() -> Boolean>().invoke() }
        val marker = AuthenticatedSessionMarker.fromCredential(
            serverId = SERVER_ID.toString(),
            userId = USER_ID.toString(),
            credentialInstanceId = CREDENTIAL_INSTANCE_ID,
            credential = ACCESS_TOKEN,
        )
        val epoch = fixture.authCoordinator.configureActiveMarker(marker, AuthMethod.PASSWORD)
        fixture.authTransportSession.activate(epoch, SERVER_URL, ACCESS_TOKEN, "MediaBrowser Token=\"$ACCESS_TOKEN\"")
        val snapshot = requireNotNull(fixture.authTransportSession.currentSnapshot())

        fixture.authTransportSession.publishAuthenticationRejected(snapshot) shouldBe true
        fixture.authTransportSession.publishAuthenticationRejected(snapshot) shouldBe false

        fixture.apiClient.accessToken shouldBe null
        fixture.authTransportSession.currentSnapshot() shouldBe null
        queuedDispatcher.pendingCount shouldBe 1
        verify(exactly = 1) { fixture.secureStore.prepareFailClosedClear() }
        verify(exactly = 0) { fixture.secureStore.clear(any()) }
        queuedDispatcher.runAll()
        fixture.controller.authState.value shouldBe MobileAuthState.Unauthenticated
        fixture.currentUserId shouldBe null
        verify(exactly = 1) { fixture.secureStore.clear(any()) }
        verify(exactly = 1) {
            fixture.musicSessionInvalidator.invalidate("authenticationRejected")
        }
    }

    @Test
    fun `restore cannot resurrect a rejected credential while runtime cleanup is queued`() = runBlocking<Unit> {
        val queuedDispatcher = QueuedDispatcher()
        val fixture = fixture(
            initialBaseUrl = SERVER_URL,
            initialAccessToken = ACCESS_TOKEN,
            ioDispatcher = queuedDispatcher,
        )
        every { fixture.serverDao.getServer(SERVER_ID) } returns SERVER
        every { fixture.userDao.clearAllLegacyAccessTokensVerified() } returns true
        every { fixture.secureStore.clear(any()) } answers { firstArg<() -> Boolean>().invoke() }
        val marker = AuthenticatedSessionMarker.fromCredential(
            serverId = SERVER_ID.toString(),
            userId = USER_ID.toString(),
            credentialInstanceId = CREDENTIAL_INSTANCE_ID,
            credential = ACCESS_TOKEN,
        )
        val epoch = fixture.authCoordinator.configureActiveMarker(marker, AuthMethod.PASSWORD)
        fixture.authTransportSession.activate(epoch, SERVER_URL, ACCESS_TOKEN, "MediaBrowser Token=\"$ACCESS_TOKEN\"")
        val snapshot = requireNotNull(fixture.authTransportSession.currentSnapshot())

        fixture.authTransportSession.publishAuthenticationRejected(snapshot) shouldBe true
        verify(exactly = 1) { fixture.secureStore.prepareFailClosedClear() }
        verify(exactly = 0) { fixture.secureStore.clear(any()) }
        val restore = startWithoutJob { fixture.controller.restoreSession() }
        queuedDispatcher.pendingCount shouldBe 2
        queuedDispatcher.runLast()

        restore.await().getOrThrow() shouldBe MobileAuthenticationResult.Superseded
        fixture.apiClient.requests shouldBe emptyList()
        fixture.controller.authState.value.shouldBeInstanceOf<MobileAuthState.AuthRejected>()
        verify(exactly = 0) { fixture.secureStore.readResult() }
        verify(exactly = 0) { fixture.secureStore.activateAfterPreparedClear(any(), any()) }

        queuedDispatcher.runAll()
        fixture.controller.authState.value shouldBe MobileAuthState.Unauthenticated
        verifyOrder {
            fixture.secureStore.prepareFailClosedClear()
            fixture.secureStore.clear(any())
        }
    }

    @Test
    fun `new controller after logout cannot restore the retired credential`() = runBlocking<Unit> {
        val loggedOut = logoutFixture()

        val logout = loggedOut.controller.logoutCurrentUser()

        logout.localCredentialsCleared shouldBe true
        loggedOut.currentUserId shouldBe null
        val restarted = fixture(
            currentServerId = loggedOut.currentServerId,
            currentUserId = loggedOut.currentUserId,
            initialBaseUrl = null,
            initialAccessToken = null,
        )
        every { restarted.secureStore.readResult() } returns SecureSessionReadResult.Missing
        every { restarted.serverDao.getServer(SERVER_ID) } returns SERVER
        every { restarted.userDao.clearAllLegacyAccessTokensVerified() } returns true
        every { restarted.secureStore.clear(any()) } answers { firstArg<() -> Boolean>().invoke() }

        val restore = restarted.controller.restoreSession()

        restore shouldBe MobileAuthenticationResult.LoginRequired
        restarted.controller.authState.value shouldBe MobileAuthState.Unauthenticated
        restarted.controller.authenticatedUserId shouldBe null
        restarted.currentUserId shouldBe null
        restarted.apiClient.baseUrl shouldBe SERVER_URL
        restarted.apiClient.accessToken shouldBe null
        restarted.apiClient.requests shouldBe emptyList()
        verify(exactly = 1) { restarted.secureStore.readResult() }
        verify(exactly = 0) { restarted.secureStore.activateAfterPreparedClear(any(), any()) }
    }

    private fun logoutFixture(): Fixture = fixture(
        currentServerId = SERVER_ID,
        currentUserId = CURRENT_LOCAL_USER_ID,
        initialBaseUrl = SHARED_ROUTE_URL,
        initialAccessToken = SHARED_ROUTE_TOKEN,
    ).also { fixture ->
        every { fixture.secureStore.prepareFailClosedLogout() } returns
            SecureSessionLogoutPreparationResult.Prepared(CREDENTIAL)
        every { fixture.serverDao.getServer(SERVER_ID) } returns SERVER
        every { fixture.userDao.clearAllLegacyAccessTokensVerified() } returns true
        every { fixture.secureStore.clear(any()) } answers { firstArg<() -> Boolean>().invoke() }
        every { fixture.revoker.revoke(any(), any()) } answers {
            fixture.revocationObservedSharedRouteTokens += fixture.apiClient.accessToken
            fixture.revocationAuthorization += secondArg<String>()
            true
        }
    }

    private fun preparedRestoreFixture(): Fixture = fixture(
        currentServerId = SERVER_ID,
        currentUserId = CURRENT_LOCAL_USER_ID,
    ).also { fixture ->
        every { fixture.secureStore.readResult() } returns SecureSessionReadResult.Available(CREDENTIAL)
        every { fixture.serverDao.getServer(SERVER_ID) } returns SERVER
        every { fixture.userDao.upsert(SERVER_ID, USER_ID) } returns CURRENT_LOCAL_USER_ID
        every { fixture.userDao.clearAllLegacyAccessTokensVerified() } returns true
        every { fixture.secureStore.clear(any()) } answers { firstArg<() -> Boolean>().invoke() }
    }

    private fun preparedCredentialCommitFixture(): Fixture = fixture(
        currentServerId = SERVER_ID,
        initialBaseUrl = SERVER_URL,
    ).also { fixture ->
        every { fixture.userDao.upsert(SERVER_ID, USER_ID) } returns CURRENT_LOCAL_USER_ID
        every { fixture.userDao.clearAllLegacyAccessTokensVerified() } returns true
        every { fixture.secureStore.activateAfterPreparedClear(any(), any()) } answers {
            fixture.activatedCredentials += firstArg<SecureSessionCredential>()
            if (secondArg<() -> Boolean>().invoke()) {
                SecureSessionCommitResult.ACTIVATED
            } else {
                SecureSessionCommitResult.POST_ACTIVATION_CALLBACK_FAILED_ROLLED_BACK
            }
        }
    }

    private fun <T> startWithoutJob(block: suspend () -> T): CompletableDeferred<Result<T>> {
        val outcome = CompletableDeferred<Result<T>>()
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome.complete(result)
                }
            },
        )
        return outcome
    }

    private fun fixture(
        currentServerId: Long? = null,
        currentUserId: Long? = null,
        initialBaseUrl: String? = null,
        initialAccessToken: String? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): Fixture = Fixture(currentServerId, currentUserId, initialBaseUrl, initialAccessToken, ioDispatcher)

    private class Fixture(
        currentServerId: Long?,
        currentUserId: Long?,
        initialBaseUrl: String?,
        initialAccessToken: String?,
        ioDispatcher: CoroutineDispatcher,
    ) {
        val secureStore = mockk<SecureSessionTokenStore>()
        val serverDao = mockk<ServerDao>()
        val userDao = mockk<UserDao>()
        val revoker = mockk<JellyfinSessionRevoker>()
        val legacyTokenResidueRemediation = mockk<LegacyTokenResidueRemediation>()
        val appPreferences = mockk<AppPreferences>()
        val apiClient = FakeControllerApiClient(initialBaseUrl, initialAccessToken)
        val activatedCredentials = mutableListOf<SecureSessionCredential>()
        val revocationAuthorization = mutableListOf<String>()
        val revocationObservedSharedRouteTokens = mutableListOf<String?>()
        var currentServerId = currentServerId
        var currentUserId = currentUserId
        var onUserPointerCommit: () -> Unit = {}

        val authCoordinator = AuthSessionCoordinator()
        val authTransportSession = MutableAuthTransportSession()
        private val jellyfin = mockk<Jellyfin>(relaxed = true)
        private val application = mockk<Application>(relaxed = true)
        val musicSessionInvalidator = mockk<PtvMusicSessionInvalidator>(relaxed = true)

        init {
            every { appPreferences.currentServerId } answers { this@Fixture.currentServerId }
            every { appPreferences.currentUserId } answers { this@Fixture.currentUserId }
            every { appPreferences.commitCurrentServerId(any()) } answers {
                this@Fixture.currentServerId = firstArg()
                true
            }
            every { appPreferences.commitCurrentUserId(any()) } answers {
                this@Fixture.currentUserId = firstArg()
                onUserPointerCommit()
                true
            }
            every { secureStore.prepareFailClosedClear() } returns true
            every { legacyTokenResidueRemediation.isComplete() } returns true
            every { legacyTokenResidueRemediation.legacyTokenPresence() } returns LegacyTokenPresence.ABSENT
            every { legacyTokenResidueRemediation.clearLogicalTokensAfterSecureVerification() } returns true
            every { legacyTokenResidueRemediation.sanitizeHistoricalResidue(any()) } returns true
            every { legacyTokenResidueRemediation.markFreshInstallPristine() } returns true
            every { jellyfin.options.deviceInfo } returns DEVICE_INFO
        }

        fun configureInstallTimes(firstInstallTime: Long, lastUpdateTime: Long) {
            val packageManager = mockk<PackageManager>()
            val packageInfo = PackageInfo().apply {
                this.firstInstallTime = firstInstallTime
                this.lastUpdateTime = lastUpdateTime
            }
            every { application.packageName } returns TEST_PACKAGE_NAME
            every { application.packageManager } returns packageManager
            every { packageManager.getPackageInfo(TEST_PACKAGE_NAME, 0) } returns packageInfo
        }

        val controller = ApiClientController(
            application = application,
            appPreferences = appPreferences,
            jellyfin = jellyfin,
            apiClient = apiClient,
            serverDao = serverDao,
            userDao = userDao,
            musicSessionInvalidator = musicSessionInvalidator,
            secureSessionTokenStore = secureStore,
            legacyTokenResidueRemediation = legacyTokenResidueRemediation,
            authCoordinator = authCoordinator,
            authTransportSession = authTransportSession,
            sessionRevoker = revoker,
            ioDispatcher = ioDispatcher,
        )
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = mutableListOf<Runnable>()
        val pendingCount: Int get() = pending.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending += block
        }

        fun runAll() {
            while (pending.isNotEmpty()) pending.removeAt(0).run()
        }

        fun runLast() {
            pending.removeAt(pending.lastIndex).run()
        }
    }

    private class FakeControllerApiClient(initialBaseUrl: String?, initialAccessToken: String?) : ApiClient() {
        private var routeBaseUrl = initialBaseUrl
        private var routeAccessToken = initialAccessToken
        private var routeDeviceInfo = DEVICE_INFO
        private var responder: suspend (HttpMethod, String) -> RawResponse = { method, path ->
            check(method == HttpMethod.GET && path == CURRENT_USER_PATH) {
                "Unexpected request ${method.name} $path"
            }
            currentUserResponse()
        }

        val requests = mutableListOf<String>()
        override val baseUrl: String? get() = routeBaseUrl
        override val accessToken: String? get() = routeAccessToken
        override val clientInfo: ClientInfo = CLIENT_INFO
        override val deviceInfo: DeviceInfo get() = routeDeviceInfo
        override val httpClientOptions: HttpClientOptions = HttpClientOptions()
        override val webSocket: SocketApi = EmptySocketApi

        override fun update(baseUrl: String?, accessToken: String?, clientInfo: ClientInfo, deviceInfo: DeviceInfo) {
            routeBaseUrl = baseUrl
            routeAccessToken = accessToken
            routeDeviceInfo = deviceInfo
        }

        fun respondWith(nextResponder: suspend (HttpMethod, String) -> RawResponse) {
            responder = nextResponder
        }

        override suspend fun request(
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            requests += "${method.name} $pathTemplate"
            return responder(method, pathTemplate)
        }
    }

    private object EmptySocketApi : SocketApi {
        override val state = MutableStateFlow<SocketApiState>(SocketApiState.Disconnected())

        override fun subscribeAll(): Flow<OutboundWebSocketMessage> = emptyFlow()

        override fun <T : OutboundWebSocketMessage> subscribe(messageType: KClass<T>): Flow<T> = emptyFlow()
    }

    private companion object {
        const val SERVER_ID = 41L
        const val STALE_SERVER_ID = 9L
        const val CURRENT_LOCAL_USER_ID = 81L
        const val STALE_LOCAL_USER_ID = 7L
        const val SERVER_URL = "https://media.example.test/jellyfin"
        const val SHARED_ROUTE_URL = "https://stale-route.example.test"
        const val ACCESS_TOKEN = "retiring-secure-token"
        const val SHARED_ROUTE_TOKEN = "shared-api-route-token"
        const val FIRST_LOGIN_TOKEN = "first-password-token"
        const val REPLACEMENT_LOGIN_TOKEN = "replacement-password-token"
        const val QUICK_CONNECT_TOKEN = "quick-connect-token"
        const val CURRENT_USER_PATH = "/Users/Me"
        const val PASSWORD_AUTH_PATH = "/Users/AuthenticateByName"
        const val QUICK_CONNECT_ENABLED_PATH = "/QuickConnect/Enabled"
        const val QUICK_CONNECT_INITIATE_PATH = "/QuickConnect/Initiate"
        const val QUICK_CONNECT_POLL_PATH = "/QuickConnect/Connect"
        const val QUICK_CONNECT_AUTH_PATH = "/Users/AuthenticateWithQuickConnect"
        const val TEST_PACKAGE_NAME = "com.piggietv.android.debug"
        val USER_ID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        val CREDENTIAL_INSTANCE_ID: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val CREDENTIAL = SecureSessionCredential(
            serverId = SERVER_ID,
            userId = USER_ID,
            credentialInstanceId = CREDENTIAL_INSTANCE_ID,
            accessToken = ACCESS_TOKEN,
        )
        val SERVER = ServerEntity(SERVER_ID, SERVER_URL, 1L)
        val LEGACY_SERVER_USER = ServerUser(
            user = UserEntity(
                id = CURRENT_LOCAL_USER_ID,
                serverId = SERVER_ID,
                userId = USER_ID,
                legacyAccessToken = ACCESS_TOKEN,
                lastLoginTimestamp = 1L,
            ),
            server = SERVER,
        )
        val CLIENT_INFO = ClientInfo(name = "test", version = "1")
        val DEVICE_INFO = DeviceInfo(id = "device", name = "Test Device")

        fun currentUserResponse(): RawResponse = rawResponse(userJson())

        fun authenticationResponse(token: String): RawResponse = rawResponse(
            """
                {
                  "User": ${userJson()},
                  "SessionInfo": null,
                  "AccessToken": "$token",
                  "ServerId": "test-server"
                }
            """.trimIndent(),
        )

        fun quickConnectResponse(authenticated: Boolean): RawResponse = rawResponse(
            """
                {
                  "Authenticated": $authenticated,
                  "Secret": "quick-connect-secret",
                  "Code": "ABCD12",
                  "DeviceId": "device",
                  "DeviceName": "Test Device",
                  "AppName": "test",
                  "AppVersion": "1",
                  "DateAdded": "2026-08-26T12:00:00"
                }
            """.trimIndent(),
        )

        fun rawResponse(body: String): RawResponse = RawResponse(
            body = body.encodeToByteArray(),
            status = 200,
            headers = emptyMap(),
        )

        fun userJson(): String = """
            {
              "Id": "$USER_ID",
              "Name": "Piggie",
              "HasPassword": false,
              "HasConfiguredPassword": false,
              "HasConfiguredEasyPassword": false
            }
        """.trimIndent()
    }
}
