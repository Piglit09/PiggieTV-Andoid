package org.jellyfin.mobile

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import coil3.ImageLoader
import coil3.SingletonImageLoader
import org.jellyfin.mobile.app.apiModule
import org.jellyfin.mobile.app.applicationModule
import org.jellyfin.mobile.core.PtvCoreRuntime
import org.jellyfin.mobile.data.databaseModule
import org.jellyfin.mobile.downloads.AuthenticatedDownloadIndexSanitizer
import org.jellyfin.mobile.utils.JellyTree
import org.jellyfin.mobile.utils.isWebViewSupported
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.fragment.koin.fragmentFactory
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import timber.log.Timber

@Suppress("unused")
class JellyfinApplication :
    Application(),
    KoinComponent {
    override fun onCreate() {
        super.onCreate()

        // Setup logging
        Timber.plant(JellyTree())

        val webViewSupported = isWebViewSupported()
        if (webViewSupported) {
            purgeLegacyWebViewCredentials()
        }

        if (BuildConfig.DEBUG && webViewSupported) {
            // Enable WebView debugging
            WebView.setWebContentsDebuggingEnabled(true)
        }

        startKoin {
            androidContext(this@JellyfinApplication)
            fragmentFactory()

            modules(
                applicationModule,
                apiModule,
                databaseModule,
            )
        }

        get<AuthenticatedDownloadIndexSanitizer>().sanitizeAtStartup()

        SingletonImageLoader.setSafe {
            get<ImageLoader>()
        }

        PtvCoreRuntime.initialize(this)
    }

    @Suppress("DEPRECATION")
    private fun purgeLegacyWebViewCredentials() {
        try {
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(this).apply {
                clearFormData()
                clearHttpAuthUsernamePassword()
            }
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
            }
        } catch (_: RuntimeException) {
            Timber.w("Legacy WebView credential purge was unavailable")
        }
    }
}
