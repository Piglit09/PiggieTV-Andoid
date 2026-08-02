package com.piggietv.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PtvCoreContractsTest {
    @Test
    fun `normalizes media and API compatibility policy`() {
        assertEquals(PtvCoreMediaType.SHOW, PtvCoreMediaType.fromWire("Series"))
        assertEquals(PtvCoreMediaType.ALBUM, PtvCoreMediaType.fromWire("AudioAlbum"))
        assertEquals(7, PtvCoreApiPolicy.field(mapOf("CacheVersion" to 7), "cacheVersion", "CacheVersion"))
        assertTrue(PtvCoreApiPolicy.shouldRetry(PtvCoreHttpMethod.GET, attempt = 1, status = 503))
        assertFalse(PtvCoreApiPolicy.shouldRetry(PtvCoreHttpMethod.POST, attempt = 1, status = 503))
    }

    @Test
    fun `validates logical navigation intents`() {
        val valid = PtvNavigationIntent(
            destination = PtvNavigationDestination.DETAILS,
            mediaId = "item-1",
        ).validate(TIMESTAMP)
        val invalid = PtvNavigationIntent(
            destination = PtvNavigationDestination.PLAYER,
        ).validate(TIMESTAMP)

        assertTrue(valid is PtvCoreResult.Success)
        assertTrue(invalid is PtvCoreResult.Failure)
        assertEquals(
            "NAVIGATION_MEDIA_ID_REQUIRED",
            (invalid as PtvCoreResult.Failure).error.code,
        )
    }

    @Test
    fun `reduces queue repeat shuffle and completion state`() {
        var state = PtvPlaybackState(status = PtvPlaybackStatus.PLAYING)
        state = PtvPlaybackReducer.reduce(
            state,
            PtvPlaybackCommand.ReplaceQueue(listOf("one", "two")),
        )
        state = PtvPlaybackReducer.reduce(state, PtvPlaybackCommand.PlayNext(listOf("next")))
        assertEquals(listOf("one", "next", "two"), state.queue)

        state = PtvPlaybackReducer.reduce(state, PtvPlaybackCommand.Next)
        assertEquals("next", state.mediaId)
        state = PtvPlaybackReducer.reduce(state, PtvPlaybackCommand.SetRepeat(PtvRepeatMode.ALL))
        state = PtvPlaybackReducer.reduce(state, PtvPlaybackCommand.SetShuffle(true))
        assertEquals(PtvRepeatMode.ALL, state.repeatMode)
        assertTrue(state.shuffled)
    }

    @Test
    fun `isolates cache scope and declares capabilities`() {
        val scope = PtvCoreScope(
            kind = PtvCoreScopeKind.USER,
            serverId = "server-1",
            userId = "user-1",
        )
        val envelope = PtvCacheEnvelope(
            schemaVersion = 1,
            key = "home",
            scope = scope,
            createdAt = TIMESTAMP,
            expiresAt = TIMESTAMP,
            payload = emptyList<String>(),
        )

        assertEquals(
            PtvCacheOutcome.HIT,
            PtvCachePolicy.inspect(envelope, "home", scope, 1, 10, 20),
        )
        assertEquals(
            PtvCacheOutcome.MISS,
            PtvCachePolicy.inspect(
                envelope,
                "home",
                scope.copy(userId = "other"),
                1,
                10,
                20,
            ),
        )

        val report = PtvClientCapabilityReport(
            platform = "android",
            deviceClass = "mobile",
            capabilities = mapOf(
                PtvClientCapability.VIDEO_PLAYBACK.wireName to true,
                PtvClientCapability.TOUCH_INPUT.wireName to true,
            ),
        )
        assertTrue(report.supports(PtvClientCapability.VIDEO_PLAYBACK))
        assertTrue(report.supports(PtvClientCapability.TOUCH_INPUT))
    }

    @Test
    fun `redacts diagnostic metadata and preserves unknown feature flags`() {
        val sanitized = PtvCoreRedactor.metadata(
            mapOf(
                "authorization" to "Bearer private-token",
                "note" to "password=secret person@example.test",
            ),
        )
        assertEquals("[redacted]", sanitized["authorization"])
        assertFalse(sanitized.toString().contains("private-token"))
        assertFalse(sanitized.toString().contains("person@example.test"))

        val flags = PtvFeatureFlagDocument(
            schemaVersion = 1,
            scope = PtvCoreScope(PtvCoreScopeKind.GLOBAL),
            values = mapOf("futureFlag" to true),
        )
        assertTrue(flags.enabled("futureFlag"))
        assertTrue(flags.enabled("defaulted", mapOf("defaulted" to true)))
    }

    @Test
    fun `initialization is ordered observable and idempotent`() {
        val calls = mutableListOf<PtvCoreInitializationStep>()
        val initializer = PtvCoreInitializer(
            handlers = listOf(
                PtvCoreInitializationStep.ENVIRONMENT,
                PtvCoreInitializationStep.DEVICE_IDENTITY,
                PtvCoreInitializationStep.SESSION,
                PtvCoreInitializationStep.API,
            ).associateWith { step ->
                {
                    calls += step
                    PtvCoreResult.Success(Unit)
                }
            },
        )

        val first = initializer.initialize()
        val second = initializer.initialize()
        assertTrue(first.ready)
        assertSame(first, second)
        assertEquals(
            listOf(
                PtvCoreInitializationStep.ENVIRONMENT,
                PtvCoreInitializationStep.DEVICE_IDENTITY,
                PtvCoreInitializationStep.SESSION,
                PtvCoreInitializationStep.API,
            ),
            calls,
        )
        assertEquals(PtvCoreInitializationStep.entries.size, first.steps.size)
    }

    private companion object {
        const val TIMESTAMP = "2026-07-22T12:00:00.000Z"
    }
}
