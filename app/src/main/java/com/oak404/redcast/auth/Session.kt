package com.oak404.redcast.auth

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.oak404.redcast.data.RedgifsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Holds the two kinds of API token.
 *
 * Guest tokens are cached because RedGIFs blocks clients that request too many per hour.
 * The user token comes from the real site's login inside a WebView ([TokenCapture]); the
 * password never reaches this app, and the WebView's cookies are what renew the token.
 */
class Session(context: Context, private val http: OkHttpClient) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private var guestToken: String? = prefs.getString(KEY_GUEST, null)
    private var userToken: String? = prefs.getString(KEY_USER, null)
    private var nextRenewalAtSec = 0L

    private val _signedIn = MutableStateFlow(userToken != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** The signed-in user's token if there is a session, otherwise a guest token. */
    suspend fun anyToken(): String = userToken() ?: guestToken()

    /** A valid user token, renewing it through the WebView session when needed. */
    suspend fun userToken(): String? = mutex.withLock {
        val current = userToken ?: return@withLock null
        if (Jwt.parse(current)?.isValid(now()) == true) return@withLock current
        if (now() < nextRenewalAtSec) return@withLock null

        when (val result = TokenCapture.renew(appContext)) {
            is TokenCapture.Renewal.Renewed -> {
                storeUser(result.token)
                result.token
            }
            // The site loaded and treated us as a guest, so the session is really over.
            TokenCapture.Renewal.SessionEnded -> {
                clearUser()
                null
            }
            // Offline or slow: keep the session, and don't stall every request retrying.
            TokenCapture.Renewal.Failed -> {
                nextRenewalAtSec = now() + RENEW_RETRY_SEC
                null
            }
        }
    }

    /** Called with the token a request was rejected with, so the next call fetches a new one. */
    suspend fun invalidate(token: String) = mutex.withLock {
        if (token == guestToken) {
            guestToken = null
            prefs.edit().remove(KEY_GUEST).apply()
        }
        if (token == userToken) {
            // Keep the session; backdating the token makes the next userToken() renew it.
            userToken = EXPIRED_MARKER
        }
    }

    fun onUserTokenCaptured(token: String) {
        storeUser(token)
    }

    suspend fun signOut() = withContext(Dispatchers.Main) {
        mutex.withLock { clearUser() }
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }

    private fun storeUser(token: String) {
        userToken = token
        nextRenewalAtSec = 0
        prefs.edit().putString(KEY_USER, token).apply()
        _signedIn.value = true
    }

    private fun clearUser() {
        userToken = null
        prefs.edit().remove(KEY_USER).apply()
        _signedIn.value = false
    }

    private suspend fun guestToken(): String = mutex.withLock {
        guestToken?.takeIf { Jwt.parse(it)?.isValid(now()) == true }?.let { return@withLock it }

        val fresh = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${RedgifsApi.API_BASE}/v2/auth/temporary")
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Couldn't reach RedGIFs (${response.code})")
                JSONObject(response.body?.string().orEmpty()).getString("token")
            }
        }
        guestToken = fresh
        prefs.edit().putString(KEY_GUEST, fresh).apply()
        fresh
    }

    private fun now() = System.currentTimeMillis() / 1000

    companion object {
        const val USER_AGENT = "Redcast/1.0 (Android)"
        private const val KEY_GUEST = "guest_token"
        private const val KEY_USER = "user_token"
        private const val EXPIRED_MARKER = "expired"
        private const val RENEW_RETRY_SEC = 60L
    }
}
