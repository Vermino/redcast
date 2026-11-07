package com.oak404.redcast

import com.oak404.redcast.data.AppTheme
import com.oak404.redcast.data.FeedLayout
import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.Settings
import com.oak404.redcast.data.VideoQuality
import com.oak404.redcast.data.aspectRatio
import com.oak404.redcast.data.pushRecent
import com.oak404.redcast.data.settingsFrom
import com.oak404.redcast.data.videoUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    private fun clip(width: Int = 0, height: Int = 0) =
        Gif("a", "user", "https://m/a.mp4", "https://m/a-mobile.mp4", "", "", 10.0, emptyList(), width, height)

    @Test
    fun settingsSurviveASaveAndRead() {
        val settings = Settings(AppTheme.Forest, VideoQuality.Sd, FeedLayout.Staggered, 4, "Liked")
        assertEquals(settings, settingsFrom(JSONObject(settings.toJson().toString())))
    }

    @Test
    fun unknownOrOutOfRangeSettingsFallBack() {
        val read = settingsFrom(JSONObject().put("theme", "Neon").put("columns", 9))
        assertEquals(AppTheme.System, read.theme)
        assertEquals(4, read.columns)
    }

    @Test
    fun autoPicksSdOnlyOnMeteredConnections() {
        assertEquals(clip().hdUrl, clip().videoUrl(VideoQuality.Auto, metered = false))
        assertEquals(clip().sdUrl, clip().videoUrl(VideoQuality.Auto, metered = true))
    }

    @Test
    fun aFixedQualityIgnoresTheConnection() {
        assertEquals(clip().hdUrl, clip().videoUrl(VideoQuality.Hd, metered = true))
        assertEquals(clip().sdUrl, clip().videoUrl(VideoQuality.Sd, metered = false))
    }

    @Test
    fun recentsMoveRepeatsToTheFrontAndStayCapped() {
        assertEquals(listOf("b", "a", "c"), pushRecent(listOf("a", "b", "c"), "b", 5))
        assertEquals(listOf("d", "a"), pushRecent(listOf("a", "b", "c"), "d", 2))
    }

    @Test
    fun aspectRatioHasASaneDefaultAndLimits() {
        assertEquals(9f / 16f, clip().aspectRatio, 0.001f)
        assertEquals(1.8f, clip(1000, 100).aspectRatio, 0.001f)
        assertEquals(0.5f, clip(100, 1000).aspectRatio, 0.001f)
    }
}
