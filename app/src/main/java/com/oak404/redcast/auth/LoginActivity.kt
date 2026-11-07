package com.oak404.redcast.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.oak404.redcast.RedcastApp

/** Shows the real RedGIFs sign-in page and closes itself once the site sends a user token. */
class LoginActivity : ComponentActivity() {

    private lateinit var webView: WebView

    @SuppressLint("RequiresFeature")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TokenCapture.isSupported()) {
            Toast.makeText(this, "Update Android System WebView to sign in", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        webView = WebView(this)
        setContentView(webView)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // The sign-in page and its captcha run on separate hosts.
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                request.url.scheme != "https"
        }
        TokenCapture.install(webView) { token ->
            if (Jwt.parse(token)?.isUser == true && !isFinishing) {
                CookieManager.getInstance().flush()
                RedcastApp.get(this).session.onUserTokenCaptured(token)
                setResult(RESULT_OK)
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.loadUrl("${TokenCapture.SITE}/auth/signin")
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
