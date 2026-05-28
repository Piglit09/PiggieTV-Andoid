package org.jellyfin.mobile.ui.screens.home

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

internal const val PTV_ROW_ITEM_LIMIT = 16

internal enum class PtvRowType {
    LIBRARIES,
    CONTINUE,
    LATEST,
    GENRE,
    LIBRARY,
    SEARCH,
    SEERR_REQUESTS,
}

enum class PtvRowPresentation {
    FEATURED,
    STANDARD,
    COMPACT,
    MINI,
    LIBRARY_HUB,
}

enum class PtvRowShape {
    PORTRAIT,
    SQUARE,
    BACKDROP,
}

internal enum class PtvItemMatcher {
    NONE,
    ANIME,
    DISNEY,
    STAR_WARS,
    STAR_TREK,
    FAMILY_ANIMATION,
}

internal data class PtvHomeRowSpec(
    val id: String,
    val title: String,
    val type: PtvRowType,
    val groupKey: String = "",
    val groupKicker: String = "",
    val groupTitle: String = "",
    val rowKicker: String = "",
    val presentation: PtvRowPresentation = PtvRowPresentation.STANDARD,
    val shape: PtvRowShape = PtvRowShape.PORTRAIT,
    val tier: Int = 3,
    val itemLimit: Int = PTV_ROW_ITEM_LIMIT,
    val queryLimit: Int = itemLimit,
    val candidateLimit: Int = queryLimit,
    val itemTypes: List<BaseItemKind> = emptyList(),
    val includeItemTypes: List<BaseItemKind> = movieSeriesTypes,
    val genres: List<String> = emptyList(),
    val libraryNames: List<String> = emptyList(),
    val searchTerms: List<String> = emptyList(),
    val studioKeywords: List<String> = emptyList(),
    val studioNames: List<String> = emptyList(),
    val sortBy: List<ItemSortBy> = listOf(ItemSortBy.RANDOM),
    val sortOrder: List<SortOrder> = emptyList(),
    val matcher: PtvItemMatcher = PtvItemMatcher.NONE,
    val showGroupHeader: Boolean = false,
)

internal object PtvHomeRows {
    private val rows = listOf(
        PtvHomeRowSpec(
            id = "my-media",
            title = "My Media",
            type = PtvRowType.LIBRARIES,
            groupKey = "home-base",
            groupKicker = "Jump back in",
            groupTitle = "Home Base",
            presentation = PtvRowPresentation.LIBRARY_HUB,
            shape = PtvRowShape.BACKDROP,
            tier = 1,
        ),
        PtvHomeRowSpec(
            id = "continue-watching",
            title = "Continue Watching",
            type = PtvRowType.CONTINUE,
            groupKey = "your-favorites",
            groupKicker = "Personal",
            groupTitle = "Your Favorites",
            rowKicker = "Resume without hunting",
            presentation = PtvRowPresentation.FEATURED,
            tier = 1,
            itemLimit = 8,
            queryLimit = 8,
            candidateLimit = 8,
        ),
        PtvHomeRowSpec(
            id = "requested-by-you",
            title = "Requested By You",
            type = PtvRowType.SEERR_REQUESTS,
            groupKey = "your-favorites",
            groupKicker = "Personal",
            groupTitle = "Your Favorites",
            rowKicker = "Requests and watchlist context",
            presentation = PtvRowPresentation.FEATURED,
            tier = 1,
            queryLimit = 24,
            candidateLimit = 24,
        ),
        PtvHomeRowSpec(
            id = "latest-movies",
            title = "Latest Movies",
            type = PtvRowType.LATEST,
            groupKey = "discover",
            groupKicker = "Fresh arrivals",
            groupTitle = "Discover",
            rowKicker = "New films in your libraries",
            presentation = PtvRowPresentation.FEATURED,
            tier = 1,
            itemTypes = listOf(BaseItemKind.MOVIE),
        ),
        PtvHomeRowSpec(
            id = "latest-shows",
            title = "Latest Shows",
            type = PtvRowType.LATEST,
            groupKey = "discover",
            groupKicker = "Fresh arrivals",
            groupTitle = "Discover",
            rowKicker = "Recently added series",
            presentation = PtvRowPresentation.FEATURED,
            tier = 1,
            itemTypes = listOf(BaseItemKind.SERIES),
        ),
        PtvHomeRowSpec(
            id = "latest-music",
            title = "Latest Music",
            type = PtvRowType.LATEST,
            groupKey = "discover",
            groupKicker = "Fresh arrivals",
            groupTitle = "Discover",
            presentation = PtvRowPresentation.STANDARD,
            shape = PtvRowShape.SQUARE,
            tier = 2,
            itemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
        ),
        genre("horror", "Horror", "Horror"),
        genre("comedy", "Comedy", "Comedy"),
        genre(
            id = "family-animation",
            title = "Family Animation",
            genres = listOf("Family", "Animation"),
            matcher = PtvItemMatcher.FAMILY_ANIMATION,
        ),
        genre("drama", "Drama", "Drama"),
        genre("science-fiction", "Science Fiction", "Science Fiction", "Sci-Fi", "Sci Fi"),
        PtvHomeRowSpec(
            id = "anime",
            title = "Anime",
            type = PtvRowType.SEARCH,
            groupKey = "collections-franchises",
            groupKicker = "Curated worlds",
            groupTitle = "Collections & Franchises",
            presentation = PtvRowPresentation.STANDARD,
            tier = 2,
            queryLimit = 14,
            candidateLimit = 220,
            searchTerms = listOf("Anime", "Manga", "Crunchyroll", "Funimation", "Toonami", "Toei Animation", "Studio Pierrot", "MAPPA"),
            studioKeywords = listOf("toei animation", "mappa", "bones", "madhouse", "studio pierrot", "trigger", "a 1 pictures", "kyoto animation", "sunrise", "production i g", "ufotable", "shaft", "wit studio", "cloverworks"),
            studioNames = listOf("Toei Animation", "MAPPA", "Bones", "Madhouse", "Studio Pierrot", "Trigger", "A-1 Pictures", "Kyoto Animation", "Sunrise", "Production I.G", "Ufotable", "Shaft", "Wit Studio", "CloverWorks"),
            matcher = PtvItemMatcher.ANIME,
        ),
        PtvHomeRowSpec(
            id = "music",
            title = "Music",
            type = PtvRowType.LIBRARY,
            groupKey = "library-hubs",
            groupKicker = "Quick access",
            groupTitle = "Library Hubs",
            presentation = PtvRowPresentation.LIBRARY_HUB,
            shape = PtvRowShape.SQUARE,
            tier = 2,
            itemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            libraryNames = listOf("music"),
            sortBy = listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.DESCENDING),
        ),
        genre("family", "Family", "Family"),
        genre("action", "Action", "Action"),
        PtvHomeRowSpec(
            id = "disney",
            title = "Disney Cartoons & Animation",
            type = PtvRowType.SEARCH,
            groupKey = "collections-franchises",
            groupKicker = "Curated worlds",
            groupTitle = "Collections & Franchises",
            presentation = PtvRowPresentation.STANDARD,
            tier = 2,
            queryLimit = 12,
            candidateLimit = 180,
            searchTerms = listOf(
                "Walt Disney Animation",
                "Disney Television Animation",
                "DisneyToon",
                "Pixar",
                "Disney Channel",
                "Disney Junior",
                "Lion King",
                "Frozen",
                "Moana",
                "Aladdin",
                "Little Mermaid",
                "Beauty and the Beast",
                "Mulan",
                "Hercules",
                "Tarzan",
                "Lilo & Stitch",
                "Tangled",
                "Zootopia",
                "Encanto",
                "Wreck-It Ralph",
                "DuckTales",
                "Darkwing Duck",
                "Gravity Falls",
                "Phineas and Ferb",
            ),
            studioKeywords = listOf("walt disney", "disney animation", "disney feature animation", "disney television animation", "disney channel", "disney junior", "disneytoon", "pixar", "blue sky"),
            studioNames = listOf("Walt Disney Animation Studios", "Walt Disney Feature Animation", "Walt Disney Television Animation", "Disney Television Animation", "DisneyToon Studios", "Walt Disney Pictures", "Pixar", "Pixar Animation Studios", "Disney Channel", "Disney Junior", "Blue Sky Studios"),
            matcher = PtvItemMatcher.DISNEY,
        ),
        search("marvel", "Marvel", listOf("Marvel", "Avengers", "Spider-Man", "X-Men", "Guardians of the Galaxy")),
        PtvHomeRowSpec(
            id = "star-wars",
            title = "Star Wars",
            type = PtvRowType.SEARCH,
            groupKey = "collections-franchises",
            groupKicker = "Curated worlds",
            groupTitle = "Collections & Franchises",
            presentation = PtvRowPresentation.STANDARD,
            tier = 2,
            queryLimit = 18,
            candidateLimit = 220,
            searchTerms = listOf("Star Wars", "A New Hope", "Empire Strikes Back", "Return of the Jedi", "Phantom Menace", "Attack of the Clones", "Revenge of the Sith", "Force Awakens", "Last Jedi", "Rise of Skywalker", "Rogue One", "The Mandalorian", "Andor", "Ahsoka", "Clone Wars", "Bad Batch", "Rebels", "Obi-Wan Kenobi", "Boba Fett"),
            studioKeywords = listOf("lucasfilm"),
            studioNames = listOf("Lucasfilm", "Lucasfilm Ltd."),
            matcher = PtvItemMatcher.STAR_WARS,
        ),
        PtvHomeRowSpec(
            id = "star-trek",
            title = "Star Trek",
            type = PtvRowType.SEARCH,
            groupKey = "collections-franchises",
            groupKicker = "Curated worlds",
            groupTitle = "Collections & Franchises",
            presentation = PtvRowPresentation.STANDARD,
            tier = 2,
            queryLimit = 18,
            candidateLimit = 220,
            searchTerms = listOf("Star Trek", "The Original Series", "The Next Generation", "Deep Space Nine", "Voyager", "Star Trek Enterprise", "Star Trek Discovery", "Star Trek Picard", "Strange New Worlds", "Lower Decks", "Star Trek Prodigy", "Wrath of Khan", "Search for Spock", "Voyage Home", "Undiscovered Country", "First Contact"),
            studioKeywords = listOf("paramount", "cbs studios", "desilu"),
            studioNames = listOf("Paramount", "Paramount Pictures", "CBS Studios", "Desilu Productions"),
            matcher = PtvItemMatcher.STAR_TREK,
        ),
        search("adult-animation", "Adult Animation", listOf("adult animation")),
        genre("crime", "Crime", "Crime"),
    )

    val homeRows: List<PtvHomeRowSpec> = rows.withGroupHeaders()

    private fun genre(
        id: String,
        title: String,
        vararg genres: String,
    ) = genre(id = id, title = title, genres = genres.toList())

    private fun genre(
        id: String,
        title: String,
        genres: List<String>,
        matcher: PtvItemMatcher = PtvItemMatcher.NONE,
    ) = PtvHomeRowSpec(
        id = id,
        title = title,
        type = PtvRowType.GENRE,
        groupKey = "explore-genres",
        groupKicker = "Mood shelves",
        groupTitle = "Explore Genres",
        rowKicker = "Compact discovery",
        presentation = PtvRowPresentation.COMPACT,
        tier = 3,
        genres = genres,
        matcher = matcher,
    )

    private fun search(
        id: String,
        title: String,
        searchTerms: List<String>,
    ) = PtvHomeRowSpec(
        id = id,
        title = title,
        type = PtvRowType.SEARCH,
        groupKey = "collections-franchises",
        groupKicker = "Curated worlds",
        groupTitle = "Collections & Franchises",
        presentation = PtvRowPresentation.STANDARD,
        tier = 2,
        searchTerms = searchTerms,
    )

    private fun List<PtvHomeRowSpec>.withGroupHeaders(): List<PtvHomeRowSpec> {
        var previousGroup = ""

        return map { row ->
            val showHeader = row.groupKey.isNotBlank() && row.groupKey != previousGroup

            if (row.groupKey.isNotBlank()) previousGroup = row.groupKey
            row.copy(showGroupHeader = showHeader)
        }
    }
}

internal val movieSeriesTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)

internal fun BaseItemDto.matchesPtvRow(row: PtvHomeRowSpec): Boolean = when (row.matcher) {
    PtvItemMatcher.NONE -> true
    PtvItemMatcher.ANIME -> scorePtvBrand(
        strongKeywords = listOf("anime", "manga", "crunchyroll", "funimation", "toonami"),
        supportingKeywords = listOf("anime", "manga", "animation", "animated", "japan", "japanese"),
        titleMatches = emptyList(),
        minScore = 2,
    )
    PtvItemMatcher.DISNEY -> scorePtvBrand(
        strongKeywords = listOf("walt disney", "disney animation", "disney feature animation", "disney television animation", "disneytoon", "disney channel", "disney junior", "buena vista"),
        supportingKeywords = listOf("animation", "animated", "cartoon", "family", "children", "kids", "pixar", "blue sky"),
        titleMatches = disneyTitleMatches,
        minScore = 6,
    )
    PtvItemMatcher.STAR_WARS -> scorePtvBrand(
        strongKeywords = listOf("star wars", "lucasfilm", "jedi", "sith", "skywalker", "mandalorian", "clone wars", "galactic empire", "rebel alliance"),
        supportingKeywords = listOf("jedi", "sith", "skywalker", "mandalorian", "clone wars", "galactic empire", "rebel alliance"),
        titleMatches = starWarsTitleMatches,
        minScore = 4,
    )
    PtvItemMatcher.STAR_TREK -> scorePtvBrand(
        strongKeywords = listOf("star trek", "starfleet", "federation", "klingon", "vulcan", "romulan", "deep space nine"),
        supportingKeywords = listOf("starfleet", "federation", "klingon", "vulcan", "romulan", "uss enterprise", "deep space nine"),
        titleMatches = starTrekTitleMatches,
        minScore = 4,
    )
    PtvItemMatcher.FAMILY_ANIMATION -> isPtvFamilyAnimation()
}

private data class PtvTitleMatch(val aliases: List<String>, val needsSignal: Boolean = false, val score: Int = 4)

private val disneyTitleMatches = listOf(
    PtvTitleMatch(listOf("lion king", "the lion king")),
    PtvTitleMatch(listOf("frozen"), needsSignal = true),
    PtvTitleMatch(listOf("moana")),
    PtvTitleMatch(listOf("aladdin"), needsSignal = true),
    PtvTitleMatch(listOf("little mermaid", "the little mermaid")),
    PtvTitleMatch(listOf("beauty and the beast")),
    PtvTitleMatch(listOf("mulan"), needsSignal = true),
    PtvTitleMatch(listOf("lilo and stitch", "lilo stitch")),
    PtvTitleMatch(listOf("tangled"), needsSignal = true),
    PtvTitleMatch(listOf("zootopia")),
    PtvTitleMatch(listOf("encanto")),
    PtvTitleMatch(listOf("ducktales", "duck tales", "disneys ducktales")),
    PtvTitleMatch(listOf("gravity falls")),
    PtvTitleMatch(listOf("phineas and ferb")),
)

private val starWarsTitleMatches = listOf(
    PtvTitleMatch(listOf("star wars")),
    PtvTitleMatch(listOf("a new hope")),
    PtvTitleMatch(listOf("empire strikes back", "the empire strikes back")),
    PtvTitleMatch(listOf("return of the jedi")),
    PtvTitleMatch(listOf("phantom menace", "the phantom menace")),
    PtvTitleMatch(listOf("attack of the clones")),
    PtvTitleMatch(listOf("revenge of the sith")),
    PtvTitleMatch(listOf("force awakens", "the force awakens")),
    PtvTitleMatch(listOf("last jedi", "the last jedi")),
    PtvTitleMatch(listOf("rise of skywalker", "the rise of skywalker")),
    PtvTitleMatch(listOf("rogue one")),
    PtvTitleMatch(listOf("solo"), needsSignal = true),
    PtvTitleMatch(listOf("clone wars", "the clone wars")),
    PtvTitleMatch(listOf("the bad batch", "bad batch"), needsSignal = true),
    PtvTitleMatch(listOf("rebels"), needsSignal = true),
    PtvTitleMatch(listOf("mandalorian", "the mandalorian")),
    PtvTitleMatch(listOf("andor"), needsSignal = true),
    PtvTitleMatch(listOf("ahsoka"), needsSignal = true),
    PtvTitleMatch(listOf("obi wan kenobi", "obi-wan kenobi")),
    PtvTitleMatch(listOf("book of boba fett", "the book of boba fett")),
)

private val starTrekTitleMatches = listOf(
    PtvTitleMatch(listOf("star trek")),
    PtvTitleMatch(listOf("the original series"), needsSignal = true),
    PtvTitleMatch(listOf("next generation", "the next generation"), needsSignal = true),
    PtvTitleMatch(listOf("deep space nine", "ds9")),
    PtvTitleMatch(listOf("voyager"), needsSignal = true),
    PtvTitleMatch(listOf("enterprise"), needsSignal = true),
    PtvTitleMatch(listOf("discovery"), needsSignal = true),
    PtvTitleMatch(listOf("picard"), needsSignal = true),
    PtvTitleMatch(listOf("strange new worlds")),
    PtvTitleMatch(listOf("lower decks")),
    PtvTitleMatch(listOf("prodigy"), needsSignal = true),
    PtvTitleMatch(listOf("wrath of khan", "the wrath of khan")),
    PtvTitleMatch(listOf("search for spock", "the search for spock")),
    PtvTitleMatch(listOf("voyage home", "the voyage home")),
    PtvTitleMatch(listOf("undiscovered country", "the undiscovered country")),
    PtvTitleMatch(listOf("generations"), needsSignal = true),
    PtvTitleMatch(listOf("first contact"), needsSignal = true),
    PtvTitleMatch(listOf("insurrection"), needsSignal = true),
    PtvTitleMatch(listOf("nemesis"), needsSignal = true),
)

private fun BaseItemDto.scorePtvBrand(
    strongKeywords: List<String>,
    supportingKeywords: List<String>,
    titleMatches: List<PtvTitleMatch>,
    minScore: Int,
): Boolean {
    if (type !in movieSeriesTypes) return false

    val buckets = ptvMetadataBuckets()
    val strongScore = countPtvMatches(buckets.studioText, strongKeywords) * 5 +
        countPtvMatches(buckets.providerText, strongKeywords) * 4 +
        countPtvMatches(buckets.productionText, strongKeywords) * 3 +
        countPtvMatches(buckets.genreTagText, strongKeywords) * 2
    val supportingScore = countPtvMatches(buckets.genreTagText, supportingKeywords) * 2 +
        countPtvMatches(buckets.allText, supportingKeywords)
    val hasSignal = strongScore > 0 || supportingScore > 0
    val titleScore = titleMatches.sumOf { match ->
        val matched = buckets.titleFields.any { title ->
            match.aliases.any { alias -> title.includesPtvPhrase(alias.normalizePtvText()) }
        }

        if (matched && (!match.needsSignal || hasSignal)) match.score else 0
    }

    return strongScore + supportingScore + titleScore >= minScore
}

private fun BaseItemDto.isPtvFamilyAnimation(): Boolean {
    if (type !in movieSeriesTypes) return false

    val buckets = ptvMetadataBuckets()
    val metadata = listOf(buckets.studioText, buckets.providerText, buckets.productionText, buckets.genreTagText)
        .joinToString(" ")
        .normalizePtvText()
    val animationScore = countPtvMatches(
        metadata,
        listOf("animation", "animated", "cartoon", "walt disney animation", "disney television animation", "pixar", "dreamworks animation", "illumination", "studio ghibli", "cartoon network", "nickelodeon"),
    )
    val familyScore = countPtvMatches(
        metadata,
        listOf("family", "children", "kids", "disney", "pixar", "dreamworks", "illumination", "cartoon network", "nickelodeon", "disney channel", "disney junior"),
    )
    val adultScore = countPtvMatches(metadata, listOf("adult animation", "adult swim", "mature", "tv ma"))

    return animationScore > 0 && familyScore > 0 && adultScore == 0
}

private data class PtvMetadataBuckets(
    val allText: String,
    val genreTagText: String,
    val productionText: String,
    val providerText: String,
    val studioText: String,
    val titleFields: List<String>,
)

private fun BaseItemDto.ptvMetadataBuckets(): PtvMetadataBuckets {
    val studioText = studios.orEmpty().mapNotNull { it.name }.joinToString(" ").normalizePtvText()
    val providerText = providerIds.orEmpty().flatMap { (key, value) -> listOfNotNull(key, value) }.joinToString(" ").normalizePtvText()
    val productionText = productionLocations.orEmpty().joinToString(" ").normalizePtvText()
    val genreTagText = (genres.orEmpty() + tags.orEmpty()).joinToString(" ").normalizePtvText()
    val titleFields = listOfNotNull(name, originalTitle, seriesName)
        .map { it.normalizePtvText() }
        .filter { it.isNotBlank() }
    val allText = listOf(studioText, providerText, productionText, genreTagText, titleFields.joinToString(" "))
        .joinToString(" ")
        .normalizePtvText()

    return PtvMetadataBuckets(
        allText = allText,
        genreTagText = genreTagText,
        productionText = productionText,
        providerText = providerText,
        studioText = studioText,
        titleFields = titleFields,
    )
}

private fun countPtvMatches(text: String, keywords: List<String>) = keywords.count { keyword ->
    val normalizedKeyword = keyword.normalizePtvText()

    text.includesPtvPhrase(normalizedKeyword) || text.contains(normalizedKeyword)
}

internal fun String.normalizePtvText(): String = lowercase()
    .replace("&", " and ")
    .replace("'", "")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

private fun String.includesPtvPhrase(phrase: String) =
    this == phrase || startsWith("$phrase ") || endsWith(" $phrase") || contains(" $phrase ")
