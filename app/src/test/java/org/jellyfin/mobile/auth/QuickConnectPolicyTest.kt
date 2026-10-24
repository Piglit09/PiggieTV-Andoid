package org.jellyfin.mobile.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickConnectPolicyTest {
    @Test
    fun `default policy has finite time poll and transient-failure bounds`() {
        val policy = QuickConnectPolicy()

        assertEquals(2_000L, policy.pollIntervalMillis)
        assertEquals(600_000L, policy.totalTimeoutMillis)
        assertEquals(300, policy.maxPollAttempts)
        assertEquals(3, policy.maxConsecutiveTransientFailures)
    }

    @Test
    fun `polling obeys interval and hard attempt count`() {
        val controller = QuickConnectAttemptController(
            policy = QuickConnectPolicy(
                pollIntervalMillis = 100L,
                totalTimeoutMillis = 10_000L,
                maxPollAttempts = 2,
                maxConsecutiveTransientFailures = 3,
            ),
            startedAtMillis = 0L,
        )
        assertTrue(controller.markWaitingForApproval())

        assertEquals(QuickConnectPollAction.Poll, controller.nextPoll(0L))
        assertTrue(controller.recordPollResult(approved = false, nowMillis = 0L))
        val wait = assertInstanceOf(QuickConnectPollAction.Wait::class.java, controller.nextPoll(50L))
        assertEquals(50L, wait.delayMillis)
        assertEquals(QuickConnectPollAction.Poll, controller.nextPoll(100L))
        assertTrue(controller.recordPollResult(approved = false, nowMillis = 100L))
        assertEquals(
            QuickConnectPollAction.Stop(QuickConnectPhase.EXPIRED),
            controller.nextPoll(200L),
        )
        assertEquals(2, controller.snapshot().pollAttempts)
    }

    @Test
    fun `deadline expires even when poll budget remains`() {
        val controller = QuickConnectAttemptController(
            policy = QuickConnectPolicy(
                pollIntervalMillis = 100L,
                totalTimeoutMillis = 1_000L,
                maxPollAttempts = 100,
                maxConsecutiveTransientFailures = 3,
            ),
            startedAtMillis = 10L,
        )
        controller.markWaitingForApproval()

        assertEquals(
            QuickConnectPollAction.Stop(QuickConnectPhase.EXPIRED),
            controller.nextPoll(1_010L),
        )
    }

    @Test
    fun `transient polling failures are bounded`() {
        val controller = QuickConnectAttemptController(
            policy = QuickConnectPolicy(
                pollIntervalMillis = 1L,
                totalTimeoutMillis = 1_000L,
                maxPollAttempts = 100,
                maxConsecutiveTransientFailures = 3,
            ),
            startedAtMillis = 0L,
        )
        controller.markWaitingForApproval()

        assertTrue(controller.recordTransientPollFailure(1L))
        assertTrue(controller.recordTransientPollFailure(2L))
        assertFalse(controller.recordTransientPollFailure(3L))
        assertEquals(QuickConnectPhase.FAILED, controller.snapshot().phase)
        assertEquals(3, controller.snapshot().consecutiveTransientFailures)
    }

    @Test
    fun `approval permits exactly one final authentication`() {
        val controller = QuickConnectAttemptController(startedAtMillis = 0L)
        assertTrue(controller.markWaitingForApproval())
        assertTrue(controller.recordPollResult(approved = true, nowMillis = 1L))

        assertTrue(controller.claimFinalAuthentication())
        assertFalse(controller.claimFinalAuthentication())
        assertTrue(controller.markSuccess())
        assertFalse(controller.cancel())
        assertEquals(QuickConnectPhase.SUCCESS, controller.snapshot().phase)
    }

    @Test
    fun `cancellation is terminal and stops polling`() {
        val controller = QuickConnectAttemptController(startedAtMillis = 0L)
        controller.markWaitingForApproval()

        assertTrue(controller.cancel())
        assertFalse(controller.cancel())
        assertEquals(
            QuickConnectPollAction.Stop(QuickConnectPhase.CANCELLED),
            controller.nextPoll(1L),
        )
    }

    @Test
    fun `duplicate waiting transition is rejected`() {
        val controller = QuickConnectAttemptController(startedAtMillis = 0L)

        assertTrue(controller.markWaitingForApproval())
        assertFalse(controller.markWaitingForApproval())
    }
}
