@file:Suppress("LongParameterList")

package org.jellyfin.mobile.feature.library

import java.util.UUID

/**
 * Runtime-only handoff for reader URLs. Fragment arguments contain only the opaque request ID so
 * credential-bearing acquisition URLs never enter Android saved state.
 */
class LibraryReaderLaunchRequestRegistry(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val requests = LinkedHashMap<String, LibraryReaderLaunchRequest>()

    init {
        require(capacity > 0) { "Reader launch request capacity must be positive." }
    }

    @Synchronized
    fun register(request: LibraryReaderLaunchRequest): String {
        require(request.readerKey.isSafePersistentReaderKey()) {
            "Reader launch request requires a safe persistent identity."
        }

        val requestId = nextUniqueRequestId()
        requests[requestId] = request
        while (requests.size > capacity) {
            requests.remove(requests.keys.first())
        }
        return requestId
    }

    @Synchronized
    fun resolve(requestId: String): LibraryReaderLaunchRequest? =
        requestId.takeIf(OPAQUE_REQUEST_ID::matches)?.let(requests::get)

    @Synchronized
    internal fun size(): Int = requests.size

    private fun nextUniqueRequestId(): String {
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = requestIdFactory()
            if (OPAQUE_REQUEST_ID.matches(candidate) && candidate !in requests) return candidate
        }
        error("Could not allocate an opaque reader launch request ID.")
    }

    private companion object {
        const val DEFAULT_CAPACITY = 16
        const val MAX_ID_GENERATION_ATTEMPTS = 8
        val OPAQUE_REQUEST_ID = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
                "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
        )
    }
}

data class LibraryReaderLaunchRequest(
    val uri: String,
    val title: String,
    val filename: String,
    val mimeType: String?,
    val readerKey: String,
    val jellyfinItemId: String?,
    val source: LibraryBookSource,
) {
    override fun toString(): String = "LibraryReaderLaunchRequest(content=[REDACTED], source=$source)"
}
