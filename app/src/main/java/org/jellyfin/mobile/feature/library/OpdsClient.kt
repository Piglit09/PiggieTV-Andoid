package org.jellyfin.mobile.feature.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class OpdsClient(private val okHttpClient: OkHttpClient) {
    private val feedClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(OPDS_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(OPDS_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(OPDS_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchFeed(baseUrl: String, pathOrUrl: String, authConfig: OpdsAuthConfig): OpdsFeed = withContext(Dispatchers.IO) {
        val url = resolveUrl(baseUrl, pathOrUrl)
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8")

        authConfig.bearerToken?.takeIf(String::isNotBlank)?.let { token ->
            requestBuilder.header("Authorization", "Bearer $token")
        } ?: authConfig.username?.takeIf(String::isNotBlank)?.let { username ->
            val password = authConfig.password.orEmpty()
            requestBuilder.header("Authorization", Credentials.basic(username, password))
        }

        try {
            val request = requestBuilder.build()
            feedClient.newCall(request).execute().use { response ->
                if (response.code == HTTP_UNAUTHORIZED) {
                    val loggedIn = authConfig.username
                        ?.takeIf(String::isNotBlank)
                        ?.let { username -> authenticateSession(baseUrl, username, authConfig.password.orEmpty()) }
                        ?: false

                    if (loggedIn) {
                        feedClient.newCall(request).execute().use { retryResponse ->
                            if (retryResponse.code == HTTP_UNAUTHORIZED) {
                                throw LibraryLoginRequiredException()
                            }
                            if (!retryResponse.isSuccessful) {
                                throw IOException("Library server returned HTTP ${retryResponse.code}")
                            }

                            val body = retryResponse.body?.bytes() ?: throw IOException("Library server returned an empty OPDS feed")
                            return@withContext parseFeed(baseUrl, url, body)
                        }
                    }

                    throw LibraryLoginRequiredException()
                }
                if (!response.isSuccessful) {
                    throw IOException("Library server returned HTTP ${response.code}")
                }

                val body = response.body?.bytes() ?: throw IOException("Library server returned an empty OPDS feed")
                parseFeed(baseUrl, url, body)
            }
        } catch (error: SocketTimeoutException) {
            throw IOException("Library server timed out while loading OPDS.", error)
        }
    }

    private fun authenticateSession(baseUrl: String, username: String, password: String): Boolean {
        val loginUrl = resolveUrl(baseUrl, "/login")
        val loginPage = runCatching {
            feedClient.newCall(Request.Builder().url(loginUrl).get().build()).execute().use { response ->
                response.body?.string().orEmpty()
            }
        }.getOrDefault("")
        val csrfToken = CSRF_TOKEN_REGEX.find(loginPage)?.groupValues?.getOrNull(1).orEmpty()
        val formBuilder = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add("remember_me", "on")

        if (csrfToken.isNotBlank()) {
            formBuilder.add("csrf_token", csrfToken)
        }

        return runCatching {
            feedClient.newCall(
                Request.Builder()
                    .url(loginUrl)
                    .post(formBuilder.build())
                    .build(),
            ).execute().use { response ->
                response.isSuccessful || response.isRedirect
            }
        }.getOrDefault(false)
    }

    private fun parseFeed(baseUrl: String, feedUrl: String, body: ByteArray): OpdsFeed {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(body))
        val root = document.documentElement
        val entries = root.childElements("entry").map { entry ->
            val links = entry.childElements("link").mapNotNull { link ->
                val href = link.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (href.isUriTemplate()) return@mapNotNull null
                OpdsLink(
                    href = resolveUrl(feedUrl, href),
                    rel = link.attr("rel").ifBlank { null },
                    type = link.attr("type").ifBlank { null },
                    title = link.attr("title").ifBlank { null },
                )
            }
            OpdsEntry(
                id = entry.childText("id").ifBlank { entry.childText("title") },
                title = entry.childText("title").ifBlank { "Untitled" },
                subtitle = entry.childText("subtitle").ifBlank { null },
                summary = entry.childText("summary").ifBlank { entry.childText("content").ifBlank { null } },
                authors = entry.childElements("author").mapNotNull { it.childText("name").takeIf(String::isNotBlank) },
                categories = entry.childElements("category").mapNotNull { it.attr("term").ifBlank { it.attr("label") }.takeIf(String::isNotBlank) },
                updated = entry.childText("updated").ifBlank { null },
                links = links,
            )
        }

        return OpdsFeed(
            url = feedUrl,
            title = root.childText("title").ifBlank { baseUrl },
            entries = entries,
            links = root.childElements("link").mapNotNull { link ->
                val href = link.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (href.isUriTemplate()) return@mapNotNull null
                OpdsLink(
                    href = resolveUrl(feedUrl, href),
                    rel = link.attr("rel").ifBlank { null },
                    type = link.attr("type").ifBlank { null },
                    title = link.attr("title").ifBlank { null },
                )
            },
        )
    }

    private fun resolveUrl(baseUrl: String, pathOrUrl: String): String =
        URI(baseUrl.trimEnd('/') + "/").resolve(pathOrUrl).toString()

    private fun String.isUriTemplate(): Boolean = '{' in this || '}' in this

    private fun Element.childElements(localName: String): List<Element> {
        val elements = mutableListOf<Element>()
        for (index in 0 until childNodes.length) {
            val node = childNodes.item(index)
            if (node is Element && node.matchesName(localName)) elements += node
        }
        return elements
    }

    private fun Element.childText(localName: String): String =
        childElements(localName).firstOrNull()?.textContent?.trim().orEmpty()

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty().trim()

    private fun Node.matchesName(name: String): Boolean =
        localName == name || nodeName == name || nodeName.endsWith(":$name")
}

private const val HTTP_UNAUTHORIZED = 401
private const val OPDS_CONNECT_TIMEOUT_SECONDS = 30L
private const val OPDS_READ_TIMEOUT_SECONDS = 90L
private const val OPDS_CALL_TIMEOUT_SECONDS = 120L
private val CSRF_TOKEN_REGEX = Regex("""name=["']csrf_token["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

data class OpdsFeed(
    val url: String,
    val title: String,
    val entries: List<OpdsEntry>,
    val links: List<OpdsLink>,
)

data class OpdsEntry(
    val id: String,
    val title: String,
    val subtitle: String?,
    val summary: String?,
    val authors: List<String>,
    val categories: List<String>,
    val updated: String?,
    val links: List<OpdsLink>,
)

data class OpdsLink(
    val href: String,
    val rel: String?,
    val type: String?,
    val title: String?,
)
