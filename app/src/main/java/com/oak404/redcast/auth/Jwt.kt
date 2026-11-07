package com.oak404.redcast.auth

import org.json.JSONObject
import java.util.Base64

/** Reads the claims we route on. The server validates tokens; nothing here is a security check. */
internal class Jwt private constructor(val subject: String, val expiresAtSec: Long) {

    /** Guest tokens from /v2/auth/temporary are issued to "client/<id>"; user tokens are not. */
    val isUser: Boolean get() = subject.isNotEmpty() && !subject.startsWith("client/")

    fun isValid(nowSec: Long): Boolean = expiresAtSec - EXPIRY_MARGIN_SEC > nowSec

    companion object {
        private const val EXPIRY_MARGIN_SEC = 60L

        fun parse(token: String): Jwt? = runCatching {
            val payload = token.split('.')[1]
            val claims = JSONObject(String(Base64.getUrlDecoder().decode(payload)))
            Jwt(claims.optString("sub"), claims.getLong("exp"))
        }.getOrNull()
    }
}
