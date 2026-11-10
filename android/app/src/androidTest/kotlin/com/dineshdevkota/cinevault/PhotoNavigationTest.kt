package com.dineshdevkota.cinevault

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.photos.photoAlbumId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local rows only. A refused loopback port exercises the album UI without a real NAS. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class PhotoNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val application = ApplicationProvider.getApplicationContext<CineVaultApp>()
    private val container get() = application.container
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun openingAlbumsSurvivesSourceUpdatesAndBackRestoresTheOrigin() = runBlocking<Unit> {
        assumeTrue("Use a disposable empty test library", container.database.sources().all().isEmpty())
        val source = SourceEntity(name = "Local photo navigation", host = "127.0.0.1", port = 1, share = "synthetic")
        container.database.sources().upsert(source)
        val photos = listOf("Photos/Trip/01.jpg", "Photos/Trip/02.png", "Photos/Family/portrait.jpg").map { path ->
            MovieEntity(sourceId = source.id, filePath = path, fileName = path.substringAfterLast('/'), fileSize = 100,
                parsedTitle = path.substringAfterLast('/').substringBeforeLast('.'), parsedYear = null,
                title = path.substringAfterLast('/').substringBeforeLast('.'), year = null, metadataFetched = true)
                .also { container.database.movies().insert(it) }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                visible(hasText("Welcome to CoveShelf"))
                device.pressDPadRight()
                visible(hasText("No movies yet"))
                device.pressDPadRight()
                visible(hasText("2 albums"))
                val album = hasTestTag("photo-album:${photoAlbumId(photos.first())}")
                repeat(4) { update ->
                    // Rescans/source observations can arrive while an album's click is handled.
                    container.database.sources().upsert(source.copy(lastScanAt = update.toLong()))
                    val tile = visible(album)
                    tile.performSemanticsAction(SemanticsActions.RequestFocus)
                    tile.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
                    visible(hasText("2 photos"))
                    focused(hasTestTag("photo:${photos.first().id}"))
                    device.pressBack()
                    visible(hasText("2 albums"))
                    focused(album)
                }
                container.scanner.isScanning.first { !it }
            }
        } finally {
            container.scanner.isScanning.first { !it }
            container.database.sources().delete(source.id)
            container.photos.resetSource(source.id)
            container.smb.reset(source.id)
        }
    }

    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            compose.waitUntilExactlyOneExists(matcher, timeoutMillis = 10_000)
            return compose.onNode(matcher).assertIsDisplayed()
        } catch (failure: Throwable) {
            // This class only contains local synthetic rows: include the selected tab and focused
            // node so a lost album click can be distinguished from an accidental tab transition.
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 12) }
                .getOrElse { "Could not capture Compose state: ${it.javaClass.simpleName}" }
            throw AssertionError("Expected $matcher; package=${device.currentPackageName}\n${tree.take(20_000)}", failure)
        }
    }
    private fun focused(matcher: SemanticsMatcher) {
        compose.waitUntilExactlyOneExists(matcher and isFocused(), timeoutMillis = 10_000)
        compose.onNode(matcher).assertIsFocused()
    }
}
