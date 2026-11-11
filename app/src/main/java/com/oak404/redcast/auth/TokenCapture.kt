package com.oak404.redcast.auth

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Picks up the API token the real site attaches to its own requests.
 *
 * Login is a hosted page behind a Cloudflare captcha, so it has to run in a browser. A small
 * script at document start watches the site's outgoing Authorization headers and posts the
 * bearer token back. Both the script and its message channel are limited to the site's origin,
 * so pages on other origins inside the WebView can't reach them.
 */
object TokenCapture {

    const val SITE = "https://www.redgifs.com"
    private const val TAG = "RedcastAuth"
    private const val RENEW_TIMEOUT_MS = 25_000L
    private val ORIGINS = setOf(SITE)

    private const val HOOK = """
(function () {
  if (window.__rcHook) return;
  window.__rcHook = true;
  function report(value) {
    if (typeof value === 'string' && value.indexOf('Bearer ') === 0) {
      rcBridge.postMessage(value.slice(7));
    }
  }
  var setHeader = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
    if (String(name).toLowerCase() === 'authorization') report(value);
    return setHeader.apply(this, arguments);
  };
})();
"""

    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    fun install(webView: WebView, onToken: (String) -> Unit) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        WebViewCompat.addWebMessageListener(webView, "rcBridge", ORIGINS) { _, message, _, _, _ ->
            message.data?.let { token ->
                Log.d(TAG, if (Jwt.parse(token)?.isUser == true) "Saw user token" else "Saw guest token")
                onToken(token)
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, HOOK, ORIGINS)
    }

    sealed interface Renewal {
        data class Renewed(val token: String) : Renewal
        data object SessionEnded : Renewal
        data object Failed : Renewal
    }

    /** Loads the site off-screen so its own session cookie mints a fresh user token. */
    suspend fun renew(context: Context): Renewal = withContext(Dispatchers.Main) {
        if (!isSupported()) return@withContext Renewal.Failed

        val userToken = CompletableDeferred<String>()
        var sawGuestToken = false
        var pageLoaded = false
        val webView = WebView(context)
        try {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded = true
                }
            }
            install(webView) { token ->
                val jwt = Jwt.parse(token)
                when {
                    jwt?.isUser == true -> userToken.complete(token)
                    jwt != null -> sawGuestToken = true
                }
            }
            webView.layout(0, 0, 1080, 1920)
            webView.loadUrl(SITE)

            val token = withTimeoutOrNull(RENEW_TIMEOUT_MS) { userToken.await() }
            renewalOutcome(token, sawGuestToken, pageLoaded)
        } finally {
            // Also when the caller gives up while waiting: a WebView left alive keeps the site running.
            webView.destroy()
        }
    }

    /**
     * What a renewal attempt came to. The session only counts as over when the site finished
     * loading and still spoke as a guest: on a slow connection its first requests can be a
     * guest's while the page is still coming in, and signing out for that would be wrong.
     */
    internal fun renewalOutcome(userToken: String?, sawGuestToken: Boolean, pageLoaded: Boolean): Renewal = when {
        userToken != null -> Renewal.Renewed(userToken)
        sawGuestToken && pageLoaded -> Renewal.SessionEnded
        else -> Renewal.Failed
    }

}
