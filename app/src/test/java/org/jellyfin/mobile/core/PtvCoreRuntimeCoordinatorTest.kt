package org.jellyfin.mobile.core

import com.piggietv.core.PtvCoreError
import com.piggietv.core.PtvCoreErrorKind
import com.piggietv.core.PtvCoreInitializationStatus
import com.piggietv.core.PtvCoreInitializationStep
import com.piggietv.core.PtvCoreResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PtvCoreRuntimeCoordinatorTest {
    @Test
    fun `successful startup uses the ordered lifecycle`() {
        val calls = mutableListOf<PtvCoreInitializationStep>()
        val coordinator = coordinator { step ->
            calls += step
            success()
        }
        val result = coordinator.initialize()

        assertTrue(result.ready)
        assertFalse(result.degraded)
        assertEquals(PtvCoreInitializationStep.entries.dropLast(1), calls)
        assertEquals(PtvCoreInitializationStep.entries, result.steps.map { it.step })
    }

    @Test
    fun `cache and diagnostics failures degrade without blocking ready`() {
        listOf(PtvCoreInitializationStep.CACHE, PtvCoreInitializationStep.DIAGNOSTICS).forEach { failedStep ->
            val result = coordinator { step ->
                if (step == failedStep) failure(PtvCoreErrorKind.CACHE, "NONCRITICAL_FAILURE") else success()
            }.initialize()

            assertTrue(result.ready)
            assertTrue(result.degraded)
            assertEquals(
                PtvCoreInitializationStatus.DEGRADED,
                result.steps.first { it.step == failedStep }.status,
            )
        }
    }

    @Test
    fun `authentication failure retains existing login routing signal`() {
        val result = coordinator { step ->
            if (step == PtvCoreInitializationStep.SESSION) {
                failure(PtvCoreErrorKind.AUTHENTICATION, "SESSION_EXPIRED")
            } else {
                success()
            }
        }.initialize()

        assertFalse(result.ready)
        assertTrue(result.routeToLogin)
        assertEquals(PtvCoreInitializationStatus.SKIPPED, result.steps.first {
            it.step == PtvCoreInitializationStep.SETTINGS
        }.status)
    }

    @Test
    fun `critical api failure stops startup`() {
        val result = coordinator { step ->
            if (step == PtvCoreInitializationStep.API) {
                failure(PtvCoreErrorKind.NETWORK, "API_INITIALIZATION_FAILED")
            } else {
                success()
            }
        }.initialize()

        assertFalse(result.ready)
        assertFalse(result.routeToLogin)
        assertEquals(PtvCoreInitializationStatus.FAILED, result.steps.first {
            it.step == PtvCoreInitializationStep.API
        }.status)
    }

    @Test
    fun `repeated and concurrent callers share initialization`() {
        val apiCalls = AtomicInteger()
        val apiStarted = CountDownLatch(1)
        val releaseApi = CountDownLatch(1)
        val coordinator = coordinator { step ->
            if (step == PtvCoreInitializationStep.API) {
                apiCalls.incrementAndGet()
                apiStarted.countDown()
                releaseApi.await(5, TimeUnit.SECONDS)
            }
            success()
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit(Callable { coordinator.initialize() })
            assertTrue(apiStarted.await(5, TimeUnit.SECONDS))
            val second = executor.submit(Callable { coordinator.initialize() })
            releaseApi.countDown()
            val firstResult = first.get(5, TimeUnit.SECONDS)
            val secondResult = second.get(5, TimeUnit.SECONDS)

            assertSame(firstResult, secondResult)
            assertSame(firstResult, coordinator.initialize())
            assertEquals(1, apiCalls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `listeners deduplicate and disposal resets initialization`() {
        val installs = AtomicInteger()
        val disposals = AtomicInteger()
        val starts = AtomicInteger()
        val coordinator = coordinator {
            starts.incrementAndGet()
            success()
        }

        assertTrue(coordinator.registerListener("settings") {
            installs.incrementAndGet()
            val disposer: () -> Unit = { disposals.incrementAndGet() }
            disposer
        })
        assertFalse(coordinator.registerListener("settings") {
            installs.incrementAndGet()
            val disposer: () -> Unit = { disposals.incrementAndGet() }
            disposer
        })
        coordinator.initialize()
        coordinator.initialize()

        assertEquals(1, installs.get())
        assertEquals(1, coordinator.listenerCount())
        coordinator.disposeAndReset()
        assertEquals(1, disposals.get())
        assertEquals(0, coordinator.listenerCount())
        coordinator.initialize()
        assertEquals(18, starts.get())
    }

    private fun coordinator(
        handler: (PtvCoreInitializationStep) -> PtvCoreResult<Unit>,
    ): PtvCoreRuntimeCoordinator = PtvCoreRuntimeCoordinator(
        PtvCoreInitializationStep.entries
            .filter { it != PtvCoreInitializationStep.READY }
            .associateWith { step -> { handler(step) } },
    )

    private fun success(): PtvCoreResult<Unit> = PtvCoreResult.Success(Unit)

    private fun failure(kind: PtvCoreErrorKind, code: String): PtvCoreResult<Unit> =
        PtvCoreResult.Failure(
            PtvCoreError(
                kind = kind,
                code = code,
                message = code,
                retryable = false,
                timestamp = "2026-07-22T00:00:00.000Z",
            ),
        )
}
