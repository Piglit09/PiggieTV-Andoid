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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
        assertEquals(
            PtvCoreInitializationStatus.SKIPPED,
            result.steps.first {
            it.step == PtvCoreInitializationStep.SETTINGS
        }.status
        )
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
        assertEquals(
            PtvCoreInitializationStatus.FAILED,
            result.steps.first {
            it.step == PtvCoreInitializationStep.API
        }.status
        )
    }

    @Test
    fun `unexpected noncritical exception degrades without blocking ready`() {
        val result = coordinator { step ->
            if (step == PtvCoreInitializationStep.CACHE) {
                throw IllegalStateException("cache implementation unavailable")
            }
            success()
        }.initialize()

        val cacheStep = result.steps.first { it.step == PtvCoreInitializationStep.CACHE }
        assertTrue(result.ready)
        assertTrue(result.degraded)
        assertEquals(PtvCoreInitializationStatus.DEGRADED, cacheStep.status)
        assertEquals(PtvCoreErrorKind.UNKNOWN, cacheStep.error?.kind)
        assertEquals("CORE_INITIALIZATION_STEP_FAILED", cacheStep.error?.code)
        assertEquals("core-initialization:cache", cacheStep.error?.operation)
        assertEquals("IllegalStateException", cacheStep.error?.cause)
        assertTrue(cacheStep.error?.timestamp?.endsWith("Z") == true)
    }

    @Test
    fun `unexpected critical api exception fails without escaping`() {
        val result = coordinator { step ->
            if (step == PtvCoreInitializationStep.API) {
                throw IllegalArgumentException("api implementation unavailable")
            }
            success()
        }.initialize()

        val apiStep = result.steps.first { it.step == PtvCoreInitializationStep.API }
        assertFalse(result.ready)
        assertFalse(result.degraded)
        assertEquals(PtvCoreInitializationStatus.FAILED, apiStep.status)
        assertEquals(PtvCoreErrorKind.UNKNOWN, apiStep.error?.kind)
        assertEquals("CORE_INITIALIZATION_STEP_FAILED", apiStep.error?.code)
        assertEquals("core-initialization:api", apiStep.error?.operation)
        assertEquals("IllegalArgumentException", apiStep.error?.cause)
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

        assertTrue(
            coordinator.registerListener("settings") {
            installs.incrementAndGet()
            val disposer: () -> Unit = { disposals.incrementAndGet() }
            disposer
        }
        )
        assertFalse(
            coordinator.registerListener("settings") {
            installs.incrementAndGet()
            val disposer: () -> Unit = { disposals.incrementAndGet() }
            disposer
        }
        )
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

    @Test
    fun `throwing disposer does not prevent remaining cleanup or reset`() {
        val successfulDisposals = AtomicInteger()
        val coordinator = coordinator { success() }
        coordinator.registerListener("successful") { { successfulDisposals.incrementAndGet() } }
        coordinator.registerListener("throwing") { { throw IllegalStateException("dispose failed") } }

        coordinator.initialize()
        coordinator.disposeAndReset()

        assertEquals(1, successfulDisposals.get())
        assertEquals(0, coordinator.listenerCount())
        assertTrue(coordinator.initialize().ready)
    }

    @Test
    fun `concurrent disposal waits for initialization and removes installed listeners`() {
        val initializationPaused = CountDownLatch(1)
        val releaseInitialization = CountDownLatch(1)
        val disposalStarted = CountDownLatch(1)
        val disposals = AtomicInteger()
        lateinit var coordinator: PtvCoreRuntimeCoordinator
        coordinator = coordinator { step ->
            if (step == PtvCoreInitializationStep.SETTINGS) {
                initializationPaused.countDown()
                assertTrue(releaseInitialization.await(5, TimeUnit.SECONDS))
                coordinator.registerListener("settings") {
                    val disposer: () -> Unit = { disposals.incrementAndGet() }
                    disposer
                }
            }
            success()
        }

        val disposalFailure = AtomicReference<Throwable?>()
        val disposalThread = Thread({
            disposalStarted.countDown()
            try {
                coordinator.disposeAndReset()
            } catch (throwable: Throwable) {
                disposalFailure.set(throwable)
            }
        }, "ptv-core-disposal-test")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val initialization = executor.submit(Callable { coordinator.initialize() })
            assertTrue(initializationPaused.await(5, TimeUnit.SECONDS))
            disposalThread.start()
            assertTrue(disposalStarted.await(5, TimeUnit.SECONDS))
            awaitBlocked(disposalThread)
            releaseInitialization.countDown()

            assertTrue(initialization.get(5, TimeUnit.SECONDS).ready)
            disposalThread.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(disposalThread.isAlive)
            assertEquals(null, disposalFailure.get())
            assertEquals(1, disposals.get())
            assertEquals(0, coordinator.listenerCount())
        } finally {
            releaseInitialization.countDown()
            disposalThread.join(TimeUnit.SECONDS.toMillis(5))
            executor.shutdownNow()
        }
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    private fun coordinator(handler: (PtvCoreInitializationStep) -> PtvCoreResult<Unit>,): PtvCoreRuntimeCoordinator =
        PtvCoreRuntimeCoordinator(
            PtvCoreInitializationStep.entries
                .filter { it != PtvCoreInitializationStep.READY }
                .associateWith { step -> { handler(step) } },
        )

    private fun success(): PtvCoreResult<Unit> = PtvCoreResult.Success(Unit)

    private fun failure(kind: PtvCoreErrorKind, code: String): PtvCoreResult<Unit> = PtvCoreResult.Failure(
        PtvCoreError(
            kind = kind,
            code = code,
            message = code,
            retryable = false,
            timestamp = "2026-07-22T00:00:00.000Z",
        ),
    )
}
