package com.oak404.redcast

import com.oak404.redcast.auth.Jwt
import com.oak404.redcast.data.parseGif
import com.oak404.redcast.data.parseGifPage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ParsingTest {

    private fun gifJson() = JSONObject(
        """{"id":"abc","type":1,"userName":"someone","duration":12.5,"gallery":null,
            "tags":["One","Two"],
            "urls":{"hd":"https://m/a.mp4","sd":"https://m/a-mobile.mp4",
                    "poster":"https://m/a.jpg","thumbnail":"https://m/a-mobile.jpg"}}""",
    )

    private fun jwt(sub: String, exp: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payload = encoder.encodeToString("""{"sub":"$sub","exp":$exp}""".toByteArray())
        return "header.$payload.signature"
    }

    @Test
    fun parsesAPlayableVideo() {
        val gif = parseGif(gifJson())!!
        assertEquals("https://m/a.mp4", gif.hdUrl)
        assertEquals(listOf("One", "Two"), gif.tags)
        assertEquals(12.5, gif.durationSec, 0.0)
    }

    @Test
    fun skipsImagesAdsAndGalleries() {
        assertNull(parseGif(gifJson().put("type", 2)))
        assertNull(parseGif(gifJson().put("promoted", true)))
        assertNull(parseGif(gifJson().put("gallery", "g1")))
    }

    @Test
    fun aPageKeepsOnlyPlayableEntries() {
        val json = JSONObject().put("pages", 3)
            .put("gifs", JSONArray().put(gifJson()).put(gifJson().put("type", 2)))
        val page = parseGifPage(json)
        assertEquals(1, page.gifs.size)
        assertEquals(3, page.pages)
    }

    @Test
    fun tellsUserTokensFromGuestTokens() {
        assertFalse(Jwt.parse(jwt("client/1823c31f7d3", 2000))!!.isUser)
        assertTrue(Jwt.parse(jwt("kp_0123456789abcdef", 2000))!!.isUser)
        assertFalse(Jwt.parse(jwt("", 2000))!!.isUser)
    }

    @Test
    fun treatsTokensNearExpiryAsExpired() {
        val token = Jwt.parse(jwt("kp_x", exp = 1000))!!
        assertTrue(token.isValid(nowSec = 900))
        assertFalse(token.isValid(nowSec = 950))
    }

    @Test
    fun rejectsMalformedTokens() {
        assertNull(Jwt.parse("not-a-jwt"))
        assertNull(Jwt.parse("expired"))
        assertNotNull(Jwt.parse(jwt("x", 1)))
    }
}
