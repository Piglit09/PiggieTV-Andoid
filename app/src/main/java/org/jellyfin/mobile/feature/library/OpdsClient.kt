@file:Suppress(
    "ArgumentListWrapping",
    "BinaryExpressionWrapping",
    "ClassSignature",
    "CyclomaticComplexMethod",
    "FunctionExpressionBody",
    "FunctionLiteral",
    "FunctionSignature",
    "ImportOrdering",
    "MaximumLineLength",
    "ParameterListWrapping",
    "ThrowsCount",
    "Wrapping",
)

package org.jellyfin.mobile.feature.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jellyfin.mobile.auth.transport.AuthTransportRealm
import org.jellyfin.mobile.auth.transport.authTransportRealm
import org.jellyfin.mobile.auth.transport.stripAuthenticationQueryParameters
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
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun fetchFeed(baseUrl: String, pathOrUrl: String, authConfig: OpdsAuthConfig): OpdsFeed = withContext(Dispatchers.IO) {
        val url = resolveAuthenticatedOpdsUrl(baseUrl, pathOrUrl)
        val requestBuilder = Request.Builder()
            .url(url)
            .authTransportRealm(AuthTransportRealm.LIBRARY)
            .header("Accept", "application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8")

        authConfig.bearerToken?.takeIf(String::isNotBlank)?.let { token ->
            requestBuilder.header("Authorization", "Bearer $token")
        } ?: authConfig.username?.takeIf(String::isNotBlank)?.let { username ->
            val password = authConfig.password.orEmpty()
            requestBuilder.header("Authorization", Credentials.basic(username, password))
        }

        try {
            val request = requestBuilder.build()
            executeTrustedGet(request, baseUrl).use { response ->
                if (response.code == HTTP_UNAUTHORIZED) {
                    val loggedIn = authConfig.username
                        ?.takeIf(String::isNotBlank)
                        ?.let { username -> authenticateSession(baseUrl, username, authConfig.password.orEmpty()) }
                        ?: false

                    if (loggedIn) {
                        executeTrustedGet(request, baseUrl).use { retryResponse ->
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
        } catch (_: SocketTimeoutException) {
            throw IOException("Library server timed out while loading OPDS.")
        }
    }

    private fun authenticateSession(baseUrl: String, username: String, password: String): Boolean {
        val loginUrl = resolveAuthenticatedOpdsUrl(baseUrl, "/login")
        val loginPage = runCatching {
            executeTrustedGet(
                request = Request.Builder()
                    .url(loginUrl)
                    .authTransportRealm(AuthTransportRealm.LIBRARY)
                    .get()
                    .build(),
                trustedBaseUrl = baseUrl,
            ).use { response ->
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
                    .authTransportRealm(AuthTransportRealm.LIBRARY)
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
                val resolvedHref = resolveUrl(feedUrl, href) ?: return@mapNotNull null
                OpdsLink(
                    href = resolvedHref,
                    rel = link.attr("rel").ifBlank { null },
                    type = link.attr("type").ifBlank { null },
                    title = link.attr("title").ifBlank { null },
                    lengthBytes = link.attr("length").toLongOrNull(),
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
                val resolvedHref = resolveUrl(feedUrl, href) ?: return@mapNotNull null
                OpdsLink(
                    href = resolvedHref,
                    rel = link.attr("rel").ifBlank { null },
                    type = link.attr("type").ifBlank { null },
                    title = link.attr("title").ifBlank { null },
                    lengthBytes = link.attr("length").toLongOrNull(),
                )
            },
        )
    }

    private fun resolveUrl(baseUrl: String, pathOrUrl: String): String? =
        resolveOpdsLinkUrl(baseUrl, pathOrUrl)

    private fun executeTrustedGet(request: Request, trustedBaseUrl: String): Response {
        val trustedOrigin = trustedBaseUrl.toHttpUrlOrNull()
            ?: throw IOException("Library server URL is invalid")
        var currentRequest = request

        repeat(MAX_OPDS_REDIRECTS + 1) { redirectCount ->
            val response = feedClient.newCall(currentRequest).execute()
            if (!response.isRedirect) return response

            val redirectedUrl = response.header("Location")
                ?.let(response.request.url::resolve)
                ?: return response
            if (!trustedOrigin.hasSameOrigin(redirectedUrl)) {
                response.close()
                throw IOException("Library server redirected outside its authenticated origin")
            }
            if (redirectCount == MAX_OPDS_REDIRECTS) {
                response.close()
                throw IOException("Library server returned too many redirects")
            }

            response.close()
            currentRequest = currentRequest.newBuilder().url(redirectedUrl).get().build()
        }
        error("Unreachable")
    }

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
private const val MAX_OPDS_REDIRECTS = 5
private val CSRF_TOKEN_REGEX = Regex("""name=["']csrf_token["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

internal fun resolveAuthenticatedOpdsUrl(baseUrl: String, pathOrUrl: String): String {
    val base = baseUrl.trimEnd('/').plus('/').toHttpUrlOrNull()
        ?.withoutAuthenticationMaterial()
        ?: throw IOException("Library server URL is invalid")
    val resolved = base.resolve(pathOrUrl)
        ?.withoutAuthenticationMaterial()
        ?: throw IOException("Library feed URL is invalid")
    if (!base.hasSameOrigin(resolved)) {
        throw IOException("Library feed URL is outside its authenticated origin")
    }
    return resolved.toString()
}

internal fun sanitizeOpdsLinkUrl(value: String): String? = value.toHttpUrlOrNull()
    ?.withoutAuthenticationMaterial()
    ?.toString()

internal fun resolveOpdsLinkUrl(baseUrl: String, pathOrUrl: String): String? = runCatching {
    URI(baseUrl.trimEnd('/') + "/").resolve(pathOrUrl).toString()
}.getOrNull()?.let(::sanitizeOpdsLinkUrl)

private fun HttpUrl.withoutAuthenticationMaterial(): HttpUrl = stripAuthenticationQueryParameters()
    .newBuilder()
    .username("")
    .password("")
    .fragment(null)
    .build()

private fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

data class OpdsFeed(
    val url: String,
    val title: String,
    val entries: List<OpdsEntry>,
    val links: List<OpdsLink>,
) {
    override fun toString(): String =
        "OpdsFeed(content=[REDACTED], entries=${entries.size}, links=${links.size})"
}

data class OpdsEntry(
    val id: String,
    val title: String,
    val subtitle: String?,
    val summary: String?,
    val authors: List<String>,
    val categories: List<String>,
    val updated: String?,
    val links: List<OpdsLink>,
) {
    override fun toString(): String =
        "OpdsEntry(content=[REDACTED], links=${links.size})"
}

data class OpdsLink(
    val href: String,
    val rel: String?,
    val type: String?,
    val title: String?,
    val lengthBytes: Long?,
) {
    override fun toString(): String = "OpdsLink(content=[REDACTED], lengthBytes=$lengthBytes)"
}
