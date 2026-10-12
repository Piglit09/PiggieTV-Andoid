package org.jellyfin.mobile.webapp

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.inject
import timber.log.Timber
import java.util.Locale

@Suppress("UNUSED_PARAMETER")
abstract class JellyfinWebViewClient(
    coroutineScope: CoroutineScope,
    server: ServerEntity,
    private val assetsPathHandler: AssetsPathHandler,
    apiClientController: ApiClientController,
) : WebViewClientCompat() {

    abstract fun onConnectedToWebapp()

    abstract fun onErrorReceived()

    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val path = url.path?.lowercase(Locale.ROOT) ?: return null
        return when {
            path.matches(Constants.MAIN_BUNDLE_PATH_REGEX) && "deferred" !in url.query.orEmpty() -> {
                onConnectedToWebapp()
                assetsPathHandler.inject("native/injectionScript.js")
            }

            // Load injected scripts from application assets
            path.contains("/native/") -> assetsPathHandler.inject("native/${url.lastPathSegment}")

            // Load the chrome.cast.js library instead
            path.endsWith(Constants.CAST_SDK_PATH) -> assetsPathHandler.inject("native/chrome.cast.js")

            else -> null
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        Timber.e(
            "Received WebView HTTP error status=%d mainFrame=%s",
            errorResponse.statusCode,
            request.isForMainFrame,
        )

        if (request.isForMainFrame) onErrorReceived()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
        val errorCode = when {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE) -> error.errorCode
            else -> ERROR_UNKNOWN
        }
        Timber.e("Received WebView resource error code=%d mainFrame=%s", errorCode, request.isForMainFrame)

        if (request.isForMainFrame) onErrorReceived()
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        Timber.e("Received WebView SSL error code=%d", error.primaryError)
        handler.cancel()
    }
}
