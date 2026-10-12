package org.jellyfin.mobile.webapp

import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class JellyfinWebChromeClient(private val fileChooserListener: FileChooserListener) : WebChromeClient() {
    /** Consume console output because message text and source URLs are not secret-safe diagnostics. */
    @Suppress("UNUSED_PARAMETER")
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        if (fileChooserParams == null) {
            filePathCallback.onReceiveValue(null)
            return true
        }

        fileChooserListener.onShowFileChooser(fileChooserParams.createIntent(), filePathCallback)
        return true
    }

    interface FileChooserListener {
        fun onShowFileChooser(intent: Intent, filePathCallback: ValueCallback<Array<Uri>>)
    }
}
