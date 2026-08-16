package org.jellyfin.mobile.ui.screens.home

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class SeriesPlaybackQueuePolicyTest {
    @Test
    fun `play all preserves server episode order and removes duplicate ids`() {
        val first = id("first")
        val second = id("second")

        SeriesPlaybackQueuePolicy.ordered(listOf(first, second, first))
            .shouldContainExactly(first, second)
    }

    @Test
    fun `shuffle all retains exactly the same unique episodes`() {
        val episodes = listOf(id("one"), id("two"), id("three"), id("four"))
        val shuffled = SeriesPlaybackQueuePolicy.shuffled(episodes, Random(42))

        shuffled.size shouldBe episodes.size
        shuffled.toSet() shouldBe episodes.toSet()
        shuffled shouldBe SeriesPlaybackQueuePolicy.shuffled(episodes, Random(42))
    }

    @Test
    fun `empty episode data creates no playback queue`() {
        SeriesPlaybackQueuePolicy.ordered(emptyList()) shouldBe emptyList()
        SeriesPlaybackQueuePolicy.shuffled(emptyList(), Random(1)) shouldBe emptyList()
    }

    private fun id(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray())
}
