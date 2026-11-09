package com.oak404.redcast.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.oak404.redcast.RedcastApp
import com.oak404.redcast.data.SearchQuery
import com.oak404.redcast.data.Settings
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The real app, driven through its screens, against [FakeRedgifs]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h1280dp")
class AppFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val fake = FakeRedgifs()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val app get() = ApplicationProvider.getApplicationContext<RedcastApp>()

    @Before
    fun launch() {
        RedcastApp.httpForTests = OkHttpClient.Builder().addInterceptor(fake).build()
        app.getSharedPreferences("library", Context.MODE_PRIVATE).edit()
            .putBoolean("age_confirmed", true)
            .putBoolean("swipe_hint_seen", true)
            .putString("settings", Settings(checkForUpdates = false).toJson().toString())
            .commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario.close()
        RedcastApp.httpForTests = null
    }

    @Test
    fun aSearchForSeveralTagsCanBeSavedAndShowsItsNewClips() {
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("blo")
        waitForText("Blonde")
        compose.onNodeWithText("Blonde").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("bea")
        waitForText("Beach")
        compose.onNodeWithText("Beach").performClick()
        compose.onNodeWithText("Search Blonde + Beach").performClick()

        waitFor { fake.searchesFor("Blonde", "Beach").isNotEmpty() }
        waitForText("Blonde + Beach")
        compose.onNodeWithContentDescription("Save to Searches").performClick()
        assertEquals(
            listOf(SearchQuery(tags = listOf("Blonde", "Beach"))),
            app.library.savedSearches.value.map { it.query },
        )

        // A clip posted after saving counts as new on the Searches tab.
        fake.clips = fake.clips + FakeRedgifs.Clip("cliplater", "user9", listOf("Blonde", "Beach"), FakeRedgifs.NOW + 5000)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Searches").performClick()
        waitForText("new", substring = true)
        compose.onNodeWithText("Blonde + Beach").assertExists()
        compose.onNodeWithText("Searches", substring = true).assertExists()
    }

    @Test
    fun mutingATagFromThePlayerStepsOverEveryClipWithIt() {
        compose.onNodeWithText("Trending").performClick()
        waitFor { compose.onAllNodesWithContentDescription("Video by user0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Video by user0").performClick()
        waitForText("user0")

        compose.onNodeWithText("Blonde").performTouchInput { longClick() }
        waitForText("Mute “blonde” everywhere")
        compose.onNodeWithText("Mute “blonde” everywhere").performClick()

        // The next clip is Blonde too, so the player goes on to the one after it.
        waitForText("user2")
        assertTrue("blonde" in app.library.taste.value.mutedWords)
    }

    @Test
    fun moreLikeThisFollowsTheClipsLeastCommonTags() {
        // Plenty of clips share all three of the first clip's tags.
        fake.clips = fake.clips + List(70) { FakeRedgifs.Clip("same$it", "maker$it", listOf("Blonde", "Beach", "Sunset"), 0) }
        openMoreLikeTheFirstClip()
        waitForText("Like this: Sunset + Beach + Blonde")
    }

    @Test
    fun moreLikeThisDropsTagsUntilItFindsEnough() {
        // Only the first clip has all three tags, and Sunset + Beach finds no more, so it falls
        // back to the rarest tag alone.
        openMoreLikeTheFirstClip()
        waitForText("Like this: Sunset")
    }

    private fun openMoreLikeTheFirstClip() {
        compose.onNodeWithText("Trending").performClick()
        waitFor { compose.onAllNodesWithContentDescription("Video by user0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Video by user0").performClick()
        waitForText("user0")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("More like this").performClick()
    }

    @Test
    fun anExportImportsIntoAnotherPhone() {
        val library = app.library
        library.addToList("Faves", com.oak404.redcast.data.Gif("a", "u", "https://m/a.mp4", "https://m/a.mp4", "", "", 5.0, listOf("Blonde")))
        library.saveSearch(SearchQuery(tags = listOf("Blonde", "Beach")), 100)
        library.updateTaste { it.copy(mutedWords = setOf("gay")) }
        val file = org.json.JSONObject(library.exportJson().toString())

        // Another phone's library, with something of its own already.
        val other = com.oak404.redcast.data.Library(FreshContext(app))
        other.addToList("Faves", com.oak404.redcast.data.Gif("b", "u", "https://m/b.mp4", "https://m/b.mp4", "", "", 5.0, emptyList()))
        val result = other.importJson(file)

        assertEquals(listOf("b", "a"), other.lists.value.getValue("Faves").map { it.id })
        assertEquals(listOf(SearchQuery(tags = listOf("Blonde", "Beach"))), other.savedSearches.value.map { it.query })
        assertEquals(setOf("gay"), other.taste.value.mutedWords)
        assertEquals("Imported 1 list (1 clip), 1 saved search, 1 muted word", result.summary)
        // Importing the same file again changes nothing.
        other.importJson(file)
        assertEquals(listOf("b", "a"), other.lists.value.getValue("Faves").map { it.id })
    }

    /** A context whose preferences are separate from the app's, standing in for another phone. */
    private class FreshContext(base: Context) : android.content.ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("other-$name", mode)
    }

    @Test
    fun aChangedApiIsSaidPlainlyInsteadOfAnEmptyFeed() {
        fake.listingsChanged = true
        compose.onNodeWithText("Trending").performClick()
        waitForText("Redcast can't read RedGIFs' clip listings any more", substring = true)
        compose.onAllNodesWithText("Check for updates").onFirst().assertExists()
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        waitFor { compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }

    /** Runs the main thread, including delayed work such as debounces, until [condition] holds. */
    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "Timed out. Requests:\n" + fake.requests.joinToString("\n") +
                        "\nScreen:\n" + compose.onRoot(useUnmergedTree = true).printToString(),
                )
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(20)
        }
    }

}
