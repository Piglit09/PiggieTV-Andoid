package org.jellyfin.mobile.app

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jellyfin.mobile.auth.transport.AuthTransportRealm
import org.jellyfin.mobile.auth.transport.INTERNAL_LIBRARY_AUTH_HEADER
import org.jellyfin.mobile.auth.transport.INTERNAL_LIBRARY_AUTH_VALUE
import org.jellyfin.mobile.auth.transport.authTransportEpoch
import org.jellyfin.mobile.auth.transport.authTransportRealm
import org.junit.jupiter.api.Test

class LibraryAuthenticationRoutingTest {
    @Test
    fun `library credentials never take ownership of Jellyfin routed same-origin traffic`() {
        val explicitlyJellyfin = request(SAME_ORIGIN_URL)
            .authTransportRealm(AuthTransportRealm.JELLYFIN)
            .build()
        val epochRouted = request(SAME_ORIGIN_URL)
            .authTransportEpoch(7)
            .build()
        val untaggedCurrentOrigin = request(SAME_ORIGIN_URL).build()

        shouldApplyLibraryAuthentication(explicitlyJellyfin, LIBRARY_URL.toHttpUrl()).shouldBeFalse()
        shouldApplyLibraryAuthentication(epochRouted, LIBRARY_URL.toHttpUrl()).shouldBeFalse()
        shouldApplyLibraryAuthentication(untaggedCurrentOrigin, LIBRARY_URL.toHttpUrl()).shouldBeFalse()
    }

    @Test
    fun `explicit OPDS traffic can use library credentials on a shared origin`() {
        val request = request(SAME_ORIGIN_URL)
            .authTransportRealm(AuthTransportRealm.LIBRARY)
            .build()

        shouldApplyLibraryAuthentication(request, LIBRARY_URL.toHttpUrl()).shouldBeTrue()
    }

    @Test
    fun `OPDS image marker keeps same-origin cover traffic in the library realm`() {
        val request = request(SAME_ORIGIN_URL)
            .header(INTERNAL_LIBRARY_AUTH_HEADER, INTERNAL_LIBRARY_AUTH_VALUE)
            .build()

        shouldApplyLibraryAuthentication(request, LIBRARY_URL.toHttpUrl()).shouldBeTrue()
    }

    @Test
    fun `excluded signup traffic never receives library credentials on a shared origin`() {
        val request = request(SAME_ORIGIN_URL)
            .authTransportRealm(AuthTransportRealm.EXCLUDED)
            .build()

        shouldApplyLibraryAuthentication(request, LIBRARY_URL.toHttpUrl()).shouldBeFalse()
    }

    @Test
    fun `foreign and preauthorized requests are never claimed as library traffic`() {
        val foreign = request("https://attacker.invalid/image").build()
        val preauthorized = request(SAME_ORIGIN_URL)
            .header("Authorization", "Bearer already-owned")
            .build()

        shouldApplyLibraryAuthentication(foreign, LIBRARY_URL.toHttpUrl()).shouldBeFalse()
        shouldApplyLibraryAuthentication(preauthorized, LIBRARY_URL.toHttpUrl()).shouldBeFalse()
    }

    private fun request(url: String): Request.Builder = Request.Builder().url(url).get()

    private companion object {
        const val LIBRARY_URL = "https://media.example.test/opds"
        const val SAME_ORIGIN_URL = "https://media.example.test/Items/1"
    }
}
