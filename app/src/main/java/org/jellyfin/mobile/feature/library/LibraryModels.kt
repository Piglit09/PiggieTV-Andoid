package org.jellyfin.mobile.feature.library

data class LibraryBook(
    val id: String,
    val title: String,
    val subtitle: String?,
    val authors: List<String>,
    val summary: String?,
    val coverUrl: String?,
    val categories: List<String>,
    val series: String?,
    val updated: String?,
    val detailUrl: String?,
    val acquisitionLinks: List<LibraryLink>,
    val readLinks: List<LibraryLink>,
)

data class LibraryLink(
    val title: String,
    val href: String,
    val type: String?,
    val rel: String?,
)

data class LibraryFacet(
    val id: String,
    val title: String,
    val subtitle: String?,
    val href: String,
)

data class LibraryHome(
    val serverBaseUrl: String,
    val allBooks: List<LibraryBook>,
    val recentBooks: List<LibraryBook>,
    val authors: List<LibraryFacet>,
    val series: List<LibraryFacet>,
    val categories: List<LibraryFacet>,
)

data class OpdsAuthConfig(
    val username: String? = null,
    val password: String? = null,
    val bearerToken: String? = null,
)
