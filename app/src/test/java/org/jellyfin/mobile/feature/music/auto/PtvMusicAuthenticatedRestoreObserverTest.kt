package org.jellyfin.mobile.feature.music.auto

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.mobile.auth.AuthMethod
import org.jellyfin.mobile.auth.MobileAuthState
import org.jellyfin.mobile.feature.music.MusicRepeatMode
import org.junit.jupiter.api.Test
import java.util.UUID

class PtvMusicAuthenticatedRestoreObserverTest {
    @Test
    fun `failed startup session can restore a saved queue after later authentication`() = runBlocking<Unit> {
        val states = MutableStateFlow<MobileAuthState>(MobileAuthState.Unauthenticated)
        val restored = CompletableDeferred<PtvMusicAutoRestoreResult.Restored>()
        var restoreCalls = 0
        val observer = observer(
            currentAuthentication = { states.value },
            restoreQueue = {
                restoreCalls++
                PtvMusicAutoRestoreResult.Restored(restoredCount = 1, missingCount = 0)
            },
        )
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            observePtvMusicAuthenticatedQueueRestoration(
                savedSessionReady = CompletableDeferred<Any?>().apply { complete(null) },
                authenticationStates = states,
                observer = observer,
                onRestored = { result -> restored.complete(result) },
            )
        }

        restoreCalls shouldBe 0
        states.value = authenticated()
        val result = withTimeoutOrNull(3_000) { restored.await() }
        restoreCalls shouldBe 1
        result.shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>().restoredCount shouldBe 1
        collection.cancelAndJoin()
    }

    @Test
    fun `wrong server and wrong remote user never restore a local queue`() = runBlocking<Unit> {
        var restoreCalls = 0
        val observer = observer(
            currentAuthentication = { MobileAuthState.Unauthenticated },
            restoreQueue = {
                restoreCalls++
                PtvMusicAutoRestoreResult.Restored(1, 0)
            },
        )

        observer.onAuthenticationChanged(authenticated().copy(serverId = "2")) shouldBe null
        observer.onAuthenticationChanged(authenticated().copy(userId = "another-user")) shouldBe null
        restoreCalls shouldBe 0
    }

    @Test
    fun `missing saved queue or local owner never triggers a restore`() = runBlocking<Unit> {
        val auth = authenticated()
        var restoreCalls = 0
        val noQueue = observer(
            currentAuthentication = { auth },
            loadSavedState = { null },
            restoreQueue = {
                restoreCalls++
                PtvMusicAutoRestoreResult.Restored(1, 0)
            },
        )
        val noOwner = observer(
            currentAuthentication = { auth },
            currentLocalOwner = { null },
            restoreQueue = {
                restoreCalls++
                PtvMusicAutoRestoreResult.Restored(1, 0)
            },
        )

        noQueue.onAuthenticationChanged(auth) shouldBe null
        noOwner.onAuthenticationChanged(auth) shouldBe null
        restoreCalls shouldBe 0
    }

    @Test
    fun `authentication changing during user lookup prevents stale restore`() = runBlocking<Unit> {
        val auth = authenticated()
        var activeAuth: MobileAuthState = auth
        var restoreCalls = 0
        val observer = observer(
            currentAuthentication = { activeAuth },
            resolveExternalUserId = {
                activeAuth = MobileAuthState.Unauthenticated
                "remote-user"
            },
            restoreQueue = {
                restoreCalls++
                PtvMusicAutoRestoreResult.Restored(1, 0)
            },
        )

        observer.onAuthenticationChanged(auth) shouldBe null
        restoreCalls shouldBe 0
    }

    @Test
    fun `local account changing during user lookup prevents stale restore`() = runBlocking<Unit> {
        val auth = authenticated()
        var activeOwner = PtvMusicAutoQueueOwner(1, 2)
        var restoreCalls = 0
        val observer = observer(
            currentAuthentication = { auth },
            currentLocalOwner = { activeOwner },
            resolveExternalUserId = {
                activeOwner = PtvMusicAutoQueueOwner(1, 3)
                "remote-user"
            },
            restoreQueue = {
                restoreCalls++
                PtvMusicAutoRestoreResult.Restored(1, 0)
            },
        )

        observer.onAuthenticationChanged(auth) shouldBe null
        restoreCalls shouldBe 0
    }

    @Test
    fun `a continuous authenticated session attempts restore only once`() = runBlocking<Unit> {
        val auth = authenticated()
        var restoreCalls = 0
        val observer = observer(
            currentAuthentication = { auth },
            restoreQueue = {
                restoreCalls++
                PtvMusicAutoRestoreResult.Restored(1, 0)
            },
        )

        observer.onAuthenticationChanged(auth).shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>()
        observer.onAuthenticationChanged(auth) shouldBe null
        restoreCalls shouldBe 1
    }

    @Test
    fun `a later authentication transition may retry a failed restore`() = runBlocking<Unit> {
        val auth = authenticated()
        var activeAuth: MobileAuthState = auth
        var restoreCalls = 0
        val observer = observer(
            currentAuthentication = { activeAuth },
            restoreQueue = {
                restoreCalls++
                if (restoreCalls == 1) {
                    PtvMusicAutoRestoreResult.Failed
                } else {
                    PtvMusicAutoRestoreResult.Restored(1, 0)
                }
            },
        )

        observer.onAuthenticationChanged(auth) shouldBe PtvMusicAutoRestoreResult.Failed
        activeAuth = MobileAuthState.Unauthenticated
        observer.onAuthenticationChanged(activeAuth) shouldBe null
        activeAuth = auth
        observer.onAuthenticationChanged(auth).shouldBeInstanceOf<PtvMusicAutoRestoreResult.Restored>()
        restoreCalls shouldBe 2
    }

    private fun observer(
        currentAuthentication: () -> MobileAuthState,
        currentLocalOwner: () -> PtvMusicAutoQueueOwner? = { PtvMusicAutoQueueOwner(1, 2) },
        loadSavedState: () -> PtvMusicAutoResumeState? = { savedState() },
        resolveExternalUserId: suspend (PtvMusicAutoQueueOwner) -> String? = { "remote-user" },
        restoreQueue: suspend () -> PtvMusicAutoRestoreResult,
    ) = PtvMusicAuthenticatedRestoreObserver(
        currentAuthentication = currentAuthentication,
        currentLocalOwner = currentLocalOwner,
        loadSavedState = loadSavedState,
        resolveExternalUserId = resolveExternalUserId,
        restoreQueue = restoreQueue,
    )

    private fun authenticated() = MobileAuthState.Authenticated(
        serverId = "1",
        userId = "remote-user",
        credentialInstanceId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        method = AuthMethod.RESTORE,
        credentialEpoch = 1,
    )

    private fun savedState() = PtvMusicAutoResumeState(
        queueItemIds = listOf("22222222-2222-2222-2222-222222222222"),
        currentIndex = 0,
        positionMs = 10_000,
        shuffleEnabled = false,
        repeatMode = MusicRepeatMode.NONE.name,
        lastPlayedTimestampMs = 1,
        ownerServerId = 1,
        ownerUserId = 2,
    )
}
