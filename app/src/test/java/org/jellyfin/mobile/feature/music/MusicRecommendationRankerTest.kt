package org.jellyfin.mobile.feature.music

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test
import java.util.UUID

class MusicRecommendationRankerTest {
    @Test
    fun `surface policies expose the shared familiar discovery contract`() {
        MusicRecommendationSurface.HOME.policy.familiarTarget(20) shouldBe 14
        MusicRecommendationSurface.MADE_FOR_YOU.policy.familiarTarget(20) shouldBe 14
        MusicRecommendationSurface.RADIO.policy.familiarTarget(20) shouldBe 5
        MusicRecommendationSurface.RELATED.policy.familiarTarget(20) shouldBe 12
        MusicRecommendationSurface.DISCOVERY.policy.familiarTarget(20) shouldBe 4
        MusicRecommendationSurface.values().forEach { surface ->
            surface.policy.maxConsecutiveArtist shouldBe 2
            surface.policy.maxConsecutiveAlbum shouldBe 2
        }
    }

    @Test
    fun `ranking is deterministic and keeps a playable seed first`() {
        val seed = track("seed", artistId = artistId("a"), albumId = albumId("one"), genres = listOf("Rock"))
        val sameAlbum = track(
            "same-album",
            artistId = artistId("a"),
            albumId = albumId("one"),
            genres = listOf("Rock"),
        )
        val sameArtist = track(
            "same-artist",
            artistId = artistId("a"),
            albumId = albumId("two"),
            genres = listOf("Rock"),
        )
        val genreDiscovery = track(
            "genre-discovery",
            artistId = artistId("b"),
            albumId = albumId("three"),
            genres = listOf("Rock"),
        )
        val catalogDiscovery = track(
            "catalog-discovery",
            artistId = artistId("c"),
            albumId = albumId("four"),
            genres = listOf("Jazz"),
        )
        val groups = listOf(
            MusicRecommendationGroup(MusicRecommendationSource.ARTIST_AFFINITY, listOf(sameArtist)),
            MusicRecommendationGroup(MusicRecommendationSource.ALBUM_AFFINITY, listOf(sameAlbum)),
            MusicRecommendationGroup(MusicRecommendationSource.GENRE_AFFINITY, listOf(genreDiscovery)),
            MusicRecommendationGroup(MusicRecommendationSource.DISCOVERY, listOf(catalogDiscovery)),
        )

        val first = MusicRecommendationRanker.rank(
            seed = seed,
            groups = groups,
            maxItems = 5,
        )
        val second = MusicRecommendationRanker.rank(
            seed = seed,
            groups = groups.reversed().map { group -> group.copy(items = group.items.reversed()) },
            maxItems = 5,
        )

        first.map(MusicItem::id) shouldBe second.map(MusicItem::id)
        first.first() shouldBe seed
        first.take(4).map(MusicItem::id) shouldContain genreDiscovery.id
    }

    @Test
    fun `artist album genre listening and favorite signals beat unrelated popularity`() {
        val affinity = track(
            "affinity",
            artistId = artistId("favorite"),
            albumId = albumId("favorite"),
            genres = listOf("Neo Soul"),
            playCount = 12,
            isFavorite = true,
        )
        val strongMatch = track(
            "strong-match",
            artistId = artistId("favorite"),
            albumId = albumId("favorite"),
            genres = listOf("Neo Soul"),
        )
        val genreOnly = track(
            "genre-only",
            artistId = artistId("genre"),
            albumId = albumId("genre"),
            genres = listOf("Neo Soul"),
        )
        val popularButUnrelated = track(
            "popular-unrelated",
            artistId = artistId("popular"),
            albumId = albumId("popular"),
            genres = listOf("Metal"),
            playCount = 25,
        )

        val ranked = MusicRecommendationRanker.rank(
            groups = listOf(
                MusicRecommendationGroup(
                    MusicRecommendationSource.RECOMMENDATION,
                    listOf(popularButUnrelated, genreOnly, strongMatch),
                ),
            ),
            affinityItems = listOf(affinity),
            maxItems = 3,
        )

        ranked.first() shouldBe strongMatch
        ranked.map(MusicItem::id).indexOf(genreOnly.id) shouldBe 1
    }

    @Test
    fun `familiar and discovery sources are interleaved before either can monopolize`() {
        val affinity = track(
            "affinity",
            artistId = artistId("a"),
            albumId = albumId("a"),
            genres = listOf("Rock"),
            isFavorite = true,
        )
        val dominantArtist = (1..12).map { index ->
            track(
                "dominant-$index",
                artistId = artistId("a"),
                albumId = albumId("dominant-$index"),
                genres = listOf("Rock"),
            )
        }
        val otherFamiliar = (1..6).map { index ->
            track(
                "favorite-$index",
                artistId = artistId("f-$index"),
                albumId = albumId("f-$index"),
                genres = listOf("Rock"),
                isFavorite = true,
            )
        }
        val discoveries = (1..6).map { index ->
            track(
                "discovery-$index",
                artistId = artistId("d-$index"),
                albumId = albumId("d-$index"),
                genres = listOf("Rock"),
            )
        }

        val ranked = MusicRecommendationRanker.rank(
            groups = listOf(
                MusicRecommendationGroup(MusicRecommendationSource.ARTIST_AFFINITY, dominantArtist),
                MusicRecommendationGroup(MusicRecommendationSource.FAVORITE, otherFamiliar),
                MusicRecommendationGroup(MusicRecommendationSource.GENRE_AFFINITY, discoveries),
            ),
            affinityItems = listOf(affinity),
            maxItems = 12,
        )
        val dominantIds = dominantArtist.mapTo(mutableSetOf(), MusicItem::id)
        val discoveryIds = discoveries.mapTo(mutableSetOf(), MusicItem::id)

        ranked.take(6).count { item -> item.id in discoveryIds } shouldBe 1
        ranked.count { item -> item.id in dominantIds } shouldBe 4
    }

    @Test
    fun `one candidate source cannot fill every familiar slot when alternatives exist`() {
        val affinity = track(
            "affinity",
            artistId = artistId("affinity"),
            albumId = albumId("affinity"),
            genres = listOf("Rock"),
        )
        val dominantSource = (1..12).map { index ->
            track(
                "artist-source-$index",
                artistId = artistId("artist-source-$index"),
                albumId = albumId("artist-source-$index"),
                genres = listOf("Rock"),
            )
        }
        val alternateFamiliar = (1..8).map { index ->
            track(
                "listening-source-$index",
                artistId = artistId("listening-source-$index"),
                albumId = albumId("listening-source-$index"),
                genres = listOf("Rock"),
            )
        }
        val discoveries = (1..8).map { index ->
            track(
                "source-discovery-$index",
                artistId = artistId("source-discovery-$index"),
                albumId = albumId("source-discovery-$index"),
                genres = listOf("Pop"),
            )
        }

        val ranked = MusicRecommendationRanker.rank(
            groups = listOf(
                MusicRecommendationGroup(MusicRecommendationSource.ARTIST_AFFINITY, dominantSource),
                MusicRecommendationGroup(MusicRecommendationSource.LISTENING_HISTORY, alternateFamiliar),
                MusicRecommendationGroup(MusicRecommendationSource.DISCOVERY, discoveries),
            ),
            affinityItems = listOf(affinity),
            maxItems = 15,
        )
        val dominantIds = dominantSource.mapTo(mutableSetOf(), MusicItem::id)

        ranked.count { item -> item.id in dominantIds } shouldBe 5
    }

    @Test
    fun `three consecutive songs from one artist or album are avoided while alternatives remain`() {
        val seed = track(
            "seed",
            artistId = artistId("a"),
            albumId = albumId("a"),
            genres = listOf("Rock"),
        )
        val artistA = (1..6).map { index ->
            track(
                "artist-a-$index",
                artistId = artistId("a"),
                albumId = albumId("a"),
                genres = listOf("Rock"),
            )
        }
        val alternatives = (1..6).map { index ->
            track(
                "alternative-$index",
                artistId = artistId("alternative-$index"),
                albumId = albumId("alternative-$index"),
                genres = listOf("Rock"),
            )
        }

        val ranked = MusicRecommendationRanker.rank(
            seed = seed,
            groups = listOf(
                MusicRecommendationGroup(MusicRecommendationSource.ARTIST_AFFINITY, artistA),
                MusicRecommendationGroup(MusicRecommendationSource.GENRE_AFFINITY, alternatives),
            ),
            maxItems = 10,
        )

        ranked.windowed(3).forEach { window ->
            (window.map { item -> item.artistIds.firstOrNull() }.distinct().size == 1) shouldBe false
            (window.map(MusicItem::albumId).distinct().size == 1) shouldBe false
        }
    }

    @Test
    fun `auto picks uses its deterministic familiar discovery target when both buckets are available`() {
        val favorite = (1..8).map { index -> track("favorite-policy-$index", isFavorite = true) }
        val history = (1..8).map { index -> track("history-policy-$index", playCount = 3) }
        val discovery = (1..20).map { index -> track("discovery-policy-$index") }
        val familiarIds = (favorite + history).mapTo(mutableSetOf(), MusicItem::id)

        val ranked = MusicRecommendationRanker.rank(
            groups = listOf(
                MusicRecommendationGroup(MusicRecommendationSource.FAVORITE, favorite),
                MusicRecommendationGroup(MusicRecommendationSource.LISTENING_HISTORY, history),
                MusicRecommendationGroup(MusicRecommendationSource.DISCOVERY, discovery),
            ),
            maxItems = 20,
            surface = MusicRecommendationSurface.MADE_FOR_YOU,
        )

        ranked.size shouldBe 20
        ranked.count { item -> item.id in familiarIds } shouldBe 14
    }

    @Test
    fun `explicit seed affinity wins inside the same source without violating run caps`() {
        val seed = track(
            "seed-affinity",
            artistId = artistId("seed-affinity"),
            albumId = albumId("seed"),
            genres = listOf("Neo Soul"),
        )
        val seedGenre = track(
            "same-seed-genre",
            artistId = artistId("new-artist"),
            albumId = albumId("other"),
            genres = listOf("Neo Soul"),
        )
        val popularUnrelated = track("popular-unrelated-policy", genres = listOf("Metal"), playCount = 25)

        val ranked = MusicRecommendationRanker.rank(
            seed = seed,
            groups = listOf(
                MusicRecommendationGroup(
                    MusicRecommendationSource.RECOMMENDATION,
                    listOf(popularUnrelated, seedGenre),
                ),
            ),
            maxItems = 3,
            surface = MusicRecommendationSurface.RADIO,
        )

        ranked.map(MusicItem::id) shouldBe listOf(seed.id, seedGenre.id, popularUnrelated.id)
    }

    @Test
    fun `not interested songs are filtered while an explicitly selected seed is retained`() {
        val seed = track("seed")
        val blocked = track("blocked")
        val allowed = track("allowed")

        val ranked = MusicRecommendationRanker.rank(
            seed = seed,
            groups = listOf(
                MusicRecommendationGroup(
                    MusicRecommendationSource.RECOMMENDATION,
                    listOf(blocked, seed.copy(title = "Duplicate seed"), allowed),
                ),
            ),
            notInterestedIds = setOf(seed.id, blocked.id),
            maxItems = 5,
        )

        ranked.map(MusicItem::id) shouldContain seed.id
        ranked.map(MusicItem::id) shouldContain allowed.id
        ranked.map(MusicItem::id) shouldNotContain blocked.id
        ranked.count { item -> item.id == seed.id } shouldBe 1
    }

    @Test
    fun `metal session modes keep verified seed matches ahead of unrelated favorites`() {
        val slipknot = track("slipknot-seed", artistId = artistId("slipknot"), genres = listOf("Metal"))
        val metalDiscovery = (1..8).map { index ->
            track("metal-discovery-$index", genres = listOf("Metal"))
        }
        val unrelatedRock = track("favorite-rock", genres = listOf("Rock"), isFavorite = true)
        val unrelatedPop = track("history-pop", genres = listOf("Pop"), playCount = 10)
        val groups = listOf(
            MusicRecommendationGroup(MusicRecommendationSource.FAVORITE, listOf(unrelatedRock)),
            MusicRecommendationGroup(MusicRecommendationSource.LISTENING_HISTORY, listOf(unrelatedPop)),
            MusicRecommendationGroup(MusicRecommendationSource.RECOMMENDATION, metalDiscovery),
        )

        listOf(MusicListeningMode.STAY_IN_THE_VIBE, MusicListeningMode.BALANCED_DISCOVERY).forEach { mode ->
            val ranked = MusicRecommendationRanker.rank(
                seed = slipknot,
                groups = groups,
                maxItems = 7,
                surface = MusicRecommendationSurface.RADIO,
                listeningMode = mode,
            )

            ranked.size shouldBe 7
            ranked.first() shouldBe slipknot
            ranked.drop(1).all { item -> "Metal" in item.genres } shouldBe true
        }

        val anythingGoes = MusicRecommendationRanker.rank(
            seed = slipknot,
            groups = groups,
            maxItems = 7,
            surface = MusicRecommendationSurface.RADIO,
            listeningMode = MusicListeningMode.ANYTHING_GOES,
        )

        anythingGoes.first() shouldBe slipknot
        anythingGoes.take(4).map(MusicItem::id) shouldContain unrelatedRock.id
    }

    @Test
    fun `explicit modes use distinct familiar discovery mixes among matching metal songs`() {
        val slipknot = track("mode-mix-seed", genres = listOf("Metal"))
        val favorites = (1..8).map { index ->
            track("mode-favorite-$index", genres = listOf("Metal"), isFavorite = true)
        }
        val history = (1..8).map { index ->
            track("mode-history-$index", genres = listOf("Metal"), playCount = 4)
        }
        val discoveries = (1..20).map { index ->
            track("mode-discovery-$index", genres = listOf("Metal"))
        }
        val familiarIds = (favorites + history + slipknot).mapTo(mutableSetOf(), MusicItem::id)
        val groups = listOf(
            MusicRecommendationGroup(MusicRecommendationSource.FAVORITE, favorites),
            MusicRecommendationGroup(MusicRecommendationSource.LISTENING_HISTORY, history),
            MusicRecommendationGroup(MusicRecommendationSource.DISCOVERY, discoveries),
        )
        val expectedFamiliar = mapOf(
            MusicListeningMode.STAY_IN_THE_VIBE to 14,
            MusicListeningMode.BALANCED_DISCOVERY to 8,
            MusicListeningMode.ANYTHING_GOES to 5,
        )

        expectedFamiliar.forEach { (mode, expectedCount) ->
            val ranked = MusicRecommendationRanker.rank(
                seed = slipknot,
                groups = groups,
                maxItems = 20,
                surface = MusicRecommendationSurface.RADIO,
                listeningMode = mode,
            )

            ranked.size shouldBe 20
            ranked.count { item -> item.id in familiarIds } shouldBe expectedCount
            ranked.all { item -> "Metal" in item.genres } shouldBe true
        }
    }

    @Test
    fun `coherent modes fill sparse catalog after seed matches run out`() {
        val slipknot = track("sparse-seed", genres = listOf("Metal"))
        val otherMetal = track("sparse-metal", genres = listOf("Metal"))
        val rock = track("sparse-rock", genres = listOf("Rock"), isFavorite = true)
        val pop = track("sparse-pop", genres = listOf("Pop"), playCount = 6)
        val unknownGenre = track("sparse-unknown", genres = emptyList())
        val groups = listOf(
            MusicRecommendationGroup(MusicRecommendationSource.FAVORITE, listOf(rock)),
            MusicRecommendationGroup(MusicRecommendationSource.LISTENING_HISTORY, listOf(pop)),
            MusicRecommendationGroup(MusicRecommendationSource.RECOMMENDATION, listOf(unknownGenre, otherMetal)),
        )

        listOf(MusicListeningMode.STAY_IN_THE_VIBE, MusicListeningMode.BALANCED_DISCOVERY).forEach { mode ->
            val ranked = MusicRecommendationRanker.rank(
                seed = slipknot,
                groups = groups,
                maxItems = 8,
                surface = MusicRecommendationSurface.RADIO,
                listeningMode = mode,
            )

            ranked.take(2).map(MusicItem::id) shouldBe listOf(slipknot.id, otherMetal.id)
            ranked.map(MusicItem::id).toSet() shouldBe setOf(
                slipknot.id,
                otherMetal.id,
                rock.id,
                pop.id,
                unknownGenre.id,
            )
        }
    }

    @Test
    fun `metal session remains seed aligned through one hundred selections`() {
        val slipknot = track("long-session-seed", genres = listOf("Metal"))
        val metal = (1..110).map { index ->
            track("long-session-metal-$index", genres = listOf("Metal"))
        }
        val unrelated = (1..30).map { index ->
            track("long-session-pop-$index", genres = listOf("Pop"), isFavorite = true)
        }
        val groups = listOf(
            MusicRecommendationGroup(MusicRecommendationSource.FAVORITE, unrelated),
            MusicRecommendationGroup(MusicRecommendationSource.DISCOVERY, metal),
        )

        listOf(MusicListeningMode.STAY_IN_THE_VIBE, MusicListeningMode.BALANCED_DISCOVERY).forEach { mode ->
            val ranked = MusicRecommendationRanker.rank(
                seed = slipknot,
                groups = groups,
                maxItems = 100,
                surface = MusicRecommendationSurface.RADIO,
                listeningMode = mode,
            )

            ranked.size shouldBe 100
            ranked.map(MusicItem::id).distinct().size shouldBe 100
            ranked.all { item -> "Metal" in item.genres } shouldBe true
        }
    }

    private fun track(
        key: String,
        artistId: UUID = artistId(key),
        albumId: UUID = albumId(key),
        genres: List<String> = listOf("Genre"),
        playCount: Int = 0,
        isFavorite: Boolean = false,
    ) = MusicItem(
        id = stableId("track-$key"),
        title = key,
        subtitle = "Artist $artistId",
        album = "Album $albumId",
        albumId = albumId,
        artist = "Artist $artistId",
        artistIds = listOf(artistId),
        genres = genres,
        type = BaseItemKind.AUDIO,
        collectionType = null,
        posterUrl = null,
        backdropUrl = null,
        container = "mp3",
        codec = "mp3",
        playCount = playCount,
        progress = if (playCount > 0) 100f else null,
        isFavorite = isFavorite,
        isFolder = false,
        isPlayable = true,
    )

    private fun artistId(key: String): UUID = stableId("artist-$key")

    private fun albumId(key: String): UUID = stableId("album-$key")

    private fun stableId(key: String): UUID = UUID.nameUUIDFromBytes(key.toByteArray())
}
