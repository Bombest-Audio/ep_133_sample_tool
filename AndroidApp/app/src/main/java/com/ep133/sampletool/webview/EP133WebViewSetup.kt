package com.ep133.sampletool.webview

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Configures the WebView for hosting the EP-133 Sample Tool web app with
 * WASM support and the MIDI bridge polyfill.
 */
object EP133WebViewSetup {

    private const val ASSET_HOST = "appassets.androidplatform.net"
    private const val ASSETS_PATH = "/assets/"
    private const val DATA_URL_PATH = "/assets/data/"

    fun configure(context: Context, webView: WebView, midiBridge: MIDIBridge) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Disable WebView's built-in scaling — we handle scaling via CSS transform
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)

        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true

        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false

        // Register the MIDI bridge JS interface
        webView.addJavascriptInterface(midiBridge, "EP133Bridge")

        // Set up asset loader and polyfill injection
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(ASSETS_PATH, WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        val polyfillJS = loadPolyfill(context)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url

                // Intercept mobile.html or index.html to inject the MIDI polyfill
                if (url.host == ASSET_HOST && polyfillJS != null) {
                    if (url.path?.endsWith("mobile.html") == true) {
                        return injectPolyfillIntoHTML(context, polyfillJS, "mobile.html")
                    }
                    if (url.path?.endsWith("index.html") == true) {
                        return injectPolyfillIntoHTML(context, polyfillJS, "data/index.html")
                    }
                }

                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.setBackgroundColor(android.graphics.Color.BLACK)
    }

    fun loadApp(webView: WebView) {
        webView.loadUrl("https://$ASSET_HOST/assets/mobile.html")
    }

    /**
     * Reads mobile.html from assets, injects the MIDI polyfill into <head>,
     * and returns the modified HTML as a WebResourceResponse.
     * No scaling needed — mobile.html is a purpose-built mobile layout.
     */
    private fun injectPolyfillIntoHTML(
        context: Context,
        polyfillJS: String,
        assetPath: String = "mobile.html",
    ): WebResourceResponse? {
        return try {
            val html = context.assets.open(assetPath)
                .bufferedReader()
                .readText()

            val scriptTag = "<script>\n$polyfillJS\n</script>"
            val injected = html.replace("<head>", "<head>\n$scriptTag")

            WebResourceResponse(
                "text/html",
                "UTF-8",
                ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8))
            )
        } catch (e: Exception) {
            android.util.Log.e("EP133", "Failed to inject polyfill: ${e.message}")
            null
        }
    }

    private fun loadPolyfill(context: Context): String? {
        return try {
            context.assets.open("data/MIDIBridgePolyfill.js")
                .bufferedReader()
                .readText()
        } catch (_: Exception) {
            // Fallback: try loading from the raw shared directory
            try {
                context.assets.open("MIDIBridgePolyfill.js")
                    .bufferedReader()
                    .readText()
            } catch (_: Exception) {
                android.util.Log.e("EP133", "MIDIBridgePolyfill.js not found in assets")
                null
            }
        }
    }
}
