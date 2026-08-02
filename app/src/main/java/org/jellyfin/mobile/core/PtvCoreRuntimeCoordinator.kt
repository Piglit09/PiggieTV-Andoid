package org.jellyfin.mobile.core

import com.piggietv.core.PtvCoreInitializationResult
import com.piggietv.core.PtvCoreInitializationStep
import com.piggietv.core.PtvCoreInitializer
import com.piggietv.core.PtvCoreResult

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
        disposers.values.toList().asReversed().forEach { it() }
        disposers.clear()
    }

    @Synchronized
    fun size(): Int = disposers.size
}

class PtvCoreRuntimeCoordinator(
    handlers: Map<PtvCoreInitializationStep, () -> PtvCoreResult<Unit>>,
    private val listenerRegistry: PtvCoreListenerRegistry = PtvCoreListenerRegistry(),
) {
    private val initializer = PtvCoreInitializer(handlers)

    fun initialize(): PtvCoreInitializationResult = initializer.initialize()

    fun registerListener(key: String, install: () -> (() -> Unit)): Boolean =
        listenerRegistry.register(key, install)

    @Synchronized
    fun disposeAndReset() {
        listenerRegistry.dispose()
        initializer.reset()
    }

    fun listenerCount(): Int = listenerRegistry.size()
}
