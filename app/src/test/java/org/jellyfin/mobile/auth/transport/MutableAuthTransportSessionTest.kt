package org.jellyfin.mobile.auth.transport

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MutableAuthTransportSessionTest {
    @Test
    fun `logout clear cancels every registered exact-epoch call once`() {
        val session = MutableAuthTransportSession()
        session.activate(1L, SERVER_URL, TOKEN, AUTHORIZATION)
        val snapshot = requireNotNull(session.currentSnapshot())
        var firstCancelled = 0
        var secondCancelled = 0

        session.registerAuthenticatedCall(snapshot, "first") { firstCancelled++ }.shouldBeTrue()
        session.registerAuthenticatedCall(snapshot, "second") { secondCancelled++ }.shouldBeTrue()

        session.clear()
        session.clear()

        firstCancelled shouldBe 1
        secondCancelled shouldBe 1
        session.currentSnapshot() shouldBe null
    }

    @Test
    fun `logout clears the mutable route before retiring transport ownership`() {
        val session = MutableAuthTransportSession()
        session.activate(1L, SERVER_URL, TOKEN, AUTHORIZATION)
        val snapshot = requireNotNull(session.currentSnapshot())
        var routeClearedWhileSnapshotWasOwned = false

        session.clear {
            routeClearedWhileSnapshotWasOwned = session.currentSnapshot() === snapshot
        }

        routeClearedWhileSnapshotWasOwned.shouldBeTrue()
        session.currentSnapshot() shouldBe null
    }

    @Test
    fun `new epoch cancels old calls and refuses late old registration`() {
        val session = MutableAuthTransportSession()
        session.activate(1L, SERVER_URL, TOKEN, AUTHORIZATION)
        val oldSnapshot = requireNotNull(session.currentSnapshot())
        var cancelled = false
        session.registerAuthenticatedCall(oldSnapshot, Any()) { cancelled = true }.shouldBeTrue()

        session.activate(2L, SERVER_URL, TOKEN, AUTHORIZATION)

        cancelled.shouldBeTrue()
        session.registerAuthenticatedCall(oldSnapshot, Any()) {}.shouldBeFalse()
        session.currentSnapshot()?.epoch shouldBe 2L
    }

    @Test
    fun `accepted old rejection cannot clear a concurrently activated same-token epoch`() {
        val session = MutableAuthTransportSession()
        session.activate(1L, SERVER_URL, TOKEN, AUTHORIZATION)
        val oldSnapshot = requireNotNull(session.currentSnapshot())
        var oldRouteClearCalled = false
        session.setAcceptedRejectionHandler { oldRouteClearCalled = true }
        session.setRejectionHandler {
            session.activate(2L, SERVER_URL, TOKEN, AUTHORIZATION)
            true
        }

        session.publishAuthenticationRejected(oldSnapshot).shouldBeTrue()

        oldRouteClearCalled.shouldBeFalse()
        session.currentSnapshot()?.epoch shouldBe 2L
    }

    private companion object {
        const val SERVER_URL = "https://media.example.test/jellyfin"
        const val TOKEN = "test-token"
        const val AUTHORIZATION = "MediaBrowser Token=\"test-token\""
    }
}
