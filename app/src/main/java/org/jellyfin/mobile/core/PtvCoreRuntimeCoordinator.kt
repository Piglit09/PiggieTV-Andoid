package org.jellyfin.mobile.core

import com.piggietv.core.PtvCoreError
import com.piggietv.core.PtvCoreErrorKind
import com.piggietv.core.PtvCoreInitializationResult
import com.piggietv.core.PtvCoreInitializationStep
import com.piggietv.core.PtvCoreInitializer
import com.piggietv.core.PtvCoreResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PtvCoreListenerRegistry {
    private val disposers = linkedMapOf<String, () -> Unit>()

    @Synchronized
    fun register(key: String, install: () -> (() -> Unit)): Boolean {
        if (disposers.containsKey(key)) return false
        disposers[key] = install()
        return true
    }

    @Synchronized
    fun dispose() {
        val pendingDisposers = disposers.values.toList().asReversed()
        disposers.clear()
        pendingDisposers.forEach { disposer ->
            runCatching { disposer() }
        }
    }

    @Synchronized
    fun size(): Int = disposers.size
}

class PtvCoreRuntimeCoordinator(
    handlers: Map<PtvCoreInitializationStep, () -> PtvCoreResult<Unit>>,
    private val listenerRegistry: PtvCoreListenerRegistry = PtvCoreListenerRegistry(),
) {
    private val initializer = PtvCoreInitializer(
        handlers.mapValues { (step, handler) ->
            { executeHandler(step, handler) }
        },
    )

    @Synchronized
    fun initialize(): PtvCoreInitializationResult = initializer.initialize()

    @Synchronized
    fun registerListener(key: String, install: () -> (() -> Unit)): Boolean = listenerRegistry.register(key, install)

    @Synchronized
    fun disposeAndReset() {
        listenerRegistry.dispose()
        initializer.reset()
    }

    fun listenerCount(): Int = listenerRegistry.size()

    private fun executeHandler(
        step: PtvCoreInitializationStep,
        handler: () -> PtvCoreResult<Unit>,
    ): PtvCoreResult<Unit> = try {
        handler()
    } catch (exception: Exception) {
        PtvCoreResult.Failure(
            PtvCoreError(
                kind = PtvCoreErrorKind.UNKNOWN,
                code = "CORE_INITIALIZATION_STEP_FAILED",
                message = "PiggieTV could not finish starting.",
                retryable = false,
                timestamp = timestamp(),
                cause = exception.javaClass.simpleName,
                operation = "core-initialization:${step.wireName}",
            ),
        )
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}
