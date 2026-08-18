package org.jellyfin.mobile.ui.screens.home

import org.jellyfin.sdk.model.UUID
import kotlin.random.Random

internal object SeriesPlaybackQueuePolicy {
    fun ordered(itemIds: List<UUID>): List<UUID> = itemIds.distinct()

    fun shuffled(itemIds: List<UUID>, random: Random = Random.Default): List<UUID> =
        ordered(itemIds).shuffled(random)
}
