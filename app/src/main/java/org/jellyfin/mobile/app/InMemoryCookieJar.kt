package org.jellyfin.mobile.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class InMemoryCookieJar : CookieJar {
    private val cookiesByHost = mutableMapOf<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookiesByHost[url.host] = (cookiesByHost[url.host].orEmpty() + cookies)
            .distinctBy { cookie -> "${cookie.name}|${cookie.domain}|${cookie.path}" }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val validCookies = cookiesByHost[url.host].orEmpty().filter { cookie -> cookie.matches(url) }
        cookiesByHost[url.host] = validCookies
        return validCookies
    }
}
