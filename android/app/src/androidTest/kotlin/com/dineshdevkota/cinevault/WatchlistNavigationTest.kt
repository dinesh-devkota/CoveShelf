package com.dineshdevkota.cinevault

import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.dineshdevkota.cinevault.core.LibraryFacets
import com.dineshdevkota.cinevault.core.LibraryFilter
import com.dineshdevkota.cinevault.core.LibrarySort
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.ui.MovieDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses local rows and a refused loopback port; no NAS or metadata account is needed. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class WatchlistNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val application = ApplicationProvider.getApplicationContext<CineVaultApp>()
    private val container get() = application.container
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val sources = mutableListOf<SourceEntity>()

    @Before
    fun prepareFreshLibrary() = runBlocking {
        awaitScannerIdle()
        container.playbackProgress.await { }
        withContext(Dispatchers.IO) { container.database.clearAllTables() }
        resetPreferences()
        container.settings.tmdbApiKey = ""
        container.scanner.clearStatus()
    }

    @After
    fun removeFixtureLibrary() = runBlocking {
        // Every launched MainActivity is awaited before its scenario closes, including relaunches.
        awaitScannerIdle()
        container.playbackProgress.await { }
        withContext(Dispatchers.IO) { container.database.clearAllTables() }
        sources.forEach { container.smb.reset(it.id) }
        resetPreferences()
        container.scanner.clearStatus()
    }

    @Test
    fun detailWatchlistTogglePersistsAndUpdatesTheHomeRail() = runBlocking {
        val source = source("Watchlist A")
        val movie = movie(source, "Saved for Later", 1994)
        launchDetail(movie).use { detail ->
            visible("Add to Watchlist")
            // Detail starts on Play; the first library action is directly below it.
            device.pressDPadDown()
            device.pressDPadCenter()
            visible("Remove from Watchlist")
            assertTrue(container.database.movies().get(movie.id)!!.isWatchlisted)
            detail.recreate()
            visible("Remove from Watchlist")
        }
        launchDetail(movie).use { visible("Remove from Watchlist") }
        launchHomeAfterScan().use { visible("Watchlist") }

        launchDetail(movie).use {
            visible("Remove from Watchlist")
            device.pressDPadDown()
            device.pressDPadCenter()
            visible("Add to Watchlist")
            assertFalse(container.database.movies().get(movie.id)!!.isWatchlisted)
        }
        launchHomeAfterScan().use {
            visible("FEATURED") // Wait for the movie rows, rather than the initial empty state.
            assertFalse("An empty Watchlist rail should disappear", hasVisibleText("Watchlist"))
        }
    }

    @Test
    fun watchlistSourceAndDecadeFiltersPersistAcrossRecreationAndRelaunch() = runBlocking {
        val first = source("Watchlist A")
        val second = source("Watchlist B")
        val alpha = movie(first, "Alpha Nineties", 1994, watchlisted = true)
        movie(first, "Alpha Recent", 2021, watchlisted = true, genre = "Comedy")
        movie(second, "Beta Nineties", 1996, watchlisted = true)
        movie(first, "Alpha Other", 1995)

        // Simulate upgrading with a genre selected in the previous version.
        application.getSharedPreferences("settings", 0).edit()
            .remove("library_genres").putString("library_genre", "Drama").commit()
        launchHomeAfterScan().use { main ->
            device.pressDPadRight()
            visible("Genre: Drama")
            activate("Show: All")
            visible("Unwatched")
            activate("Watchlist")
            activate("Genre: Drama")
            val focusedDrama = hasText("Drama") and hasClickAction() and isFocused()
            compose.waitUntilExactlyOneExists(focusedDrama, timeoutMillis = 10_000)
            container.database.movies().update(alpha.copy(genres = listOf("Action", "Drama")))
            visible("Action")
            compose.onNode(hasText("Drama") and hasClickAction()).assertIsFocused()
            activate("Comedy")
            device.pressBack()
            visible("Genre: Drama")
            compose.onNode(hasText("Genre: Drama") and hasClickAction()).assertIsFocused()
            assertEquals(setOf("Drama"), container.settings.libraryFacets.genres)
            activate("Genre: Drama")
            activate("Comedy")
            device.pressDPadRight()
            device.pressDPadCenter()
            activate("Source: All sources")
            activate("Watchlist A")
            activate("Watchlist B")
            activate("Apply")
            activate("Decade: All decades")
            activate("1990s")
            activate("2020s")
            activate("Apply")
            assertSelections()
            activate("Sort: Title")
            activate("Year")
            val savedFacets = container.settings.libraryFacets
            activate("Section: Movies")
            activate("Movies")
            assertSelections()
            assertEquals(savedFacets, container.settings.libraryFacets)
            assertEquals(LibrarySort.YEAR, container.settings.librarySort)
            main.recreate()
            assertSelections()
        }
        launchHomeAfterScan().use {
            device.pressDPadRight()
            assertSelections()
            container.database.sources().delete(first.id)
            visible("Source: Watchlist B")
            visible("Beta Nineties")
            visible("1 titles")
            assertFalse(hasVisibleText("Alpha Nineties"))
            withTimeout(10_000) {
                while (container.settings.libraryFacets.sourceIds != setOf(second.id)) delay(50)
            }
            activate("Show: Watchlist")
            activate("Hidden")
            visible("No matching movies")
            activate("Section: Movies")
            activate("Music")
            visible("Show: All")
            visible("Genre: All genres")
            visible("Decade: All decades")
            visible("Source: Watchlist B")
            visible("Sort: Year")
            assertEquals(LibraryFacets(sourceIds = setOf(second.id)), container.settings.libraryFacets)
            assertEquals(LibraryFilter.ALL, container.settings.libraryFilter)
            assertEquals(LibrarySort.YEAR, container.settings.librarySort)
            activate("Section: Music")
            activate("Movies")
            visible("Beta Nineties")
            activate("Clear filters")
            visible("Show: All")
            visible("Genre: All genres")
            visible("Decade: All decades")
            visible("Source: All sources")
            visible("Beta Nineties")
            assertEquals(LibraryFacets(), container.settings.libraryFacets)
        }
    }

    private fun assertSelections() {
        visible("Show: Watchlist")
        visible("Genre: 2 selected")
        visible("Source: 2 selected")
        visible("Decade: 2 selected")
        visible("3 titles")
        visible("Alpha Nineties")
        visible("Alpha Recent")
        visible("Beta Nineties")
        assertFalse(hasVisibleText("Alpha Other"))
    }

    private fun activate(text: String) {
        val matcher = hasText(text) and hasClickAction()
        compose.waitUntilExactlyOneExists(matcher, timeoutMillis = 10_000)
        val button = compose.onNode(matcher)
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private fun launchDetail(movie: MovieEntity): ActivityScenario<MovieDetailActivity> = ActivityScenario.launch(
        Intent(application, MovieDetailActivity::class.java).putExtra("movie_id", movie.id),
    )

    private suspend fun launchHomeAfterScan(): ActivityScenario<MainActivity> {
        container.scanner.clearStatus()
        val lastSource = container.database.sources().all().last()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            withTimeout(15_000) {
                combine(container.scanner.status, container.scanner.isScanning) { status, scanning ->
                    !scanning && status?.startsWith("Scan failed for ${lastSource.name}:") == true
                }.first { it }
            }
            visible("Welcome to Cine-Media")
            return scenario
        } catch (error: Throwable) {
            scenario.close()
            throw error
        }
    }

    private suspend fun awaitScannerIdle() {
        withTimeout(15_000) { container.scanner.isScanning.first { !it } }
    }

    private suspend fun source(name: String): SourceEntity = SourceEntity(
        name = name, host = "127.0.0.1", port = 1, share = "synthetic",
    ).also {
        sources += it
        container.database.sources().upsert(it)
    }

    private suspend fun movie(source: SourceEntity, title: String, year: Int, watchlisted: Boolean = false, genre: String = "Drama"): MovieEntity = MovieEntity(
        sourceId = source.id, filePath = "$title.mp4", fileName = "$title.mp4", fileSize = 60L * 1024 * 1024,
        parsedTitle = title, parsedYear = year, title = title, year = year,
        metadataFetched = true, isWatchlisted = watchlisted, genres = listOf(genre),
    ).also { container.database.movies().insert(it) }

    private fun resetPreferences() {
        container.settings.librarySort = LibrarySort.TITLE
        container.settings.libraryFilter = LibraryFilter.ALL
        container.settings.libraryFacets = LibraryFacets()
    }

    private fun visible(text: String) {
        compose.waitUntil("visible TV text '$text'", timeoutMillis = 10_000) { hasVisibleText(text) }
    }

    private fun hasVisibleText(text: String): Boolean {
        val matches = compose.onAllNodes(hasText(text))
        return matches.fetchSemanticsNodes(atLeastOneRootRequired = false).indices.any { matches[it].isDisplayed() }
    }
}
