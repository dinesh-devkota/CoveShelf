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
import com.dineshdevkota.cinevault.core.MediaKind
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.metadata.MetadataService
import com.dineshdevkota.cinevault.metadata.TmdbClient
import com.dineshdevkota.cinevault.photos.photoAlbumId
import com.dineshdevkota.cinevault.photos.photoAlbums
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Only the authenticated COVEPHOTOS synthetic share; never clears an existing library. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class NasPhotoIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val application = ApplicationProvider.getApplicationContext<CineVaultApp>()
    private val container get() = application.container
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun scansAndDecodesPhotosWithoutMetadataRequestsAndRejectsInvalidFiles() = runBlocking<Unit> {
        val source = fixture()
        try {
            container.scanner.scan(source.id)
            val photos = container.database.movies().forSource(source.id)
            assertEquals(6, photos.size)
            assertTrue(photos.all { it.mediaKind == MediaKind.PHOTO && it.metadataFetched && it.episodeInfo == null })
            assertEquals(3, photoAlbums(photos).size)
            for (name in listOf("01.jpg", "02.png", "03.webp")) {
                val bitmap = container.photos.load(photos.single { it.fileName == name }.id)
                assertEquals(96, bitmap.width)
                assertEquals(64, bitmap.height)
            }
            val portrait = container.photos.load(photos.single { it.fileName == "portrait.jpg" }.id)
            assertEquals(64, portrait.width)
            assertEquals(96, portrait.height)
            assertTrue(runCatching { container.photos.load(photos.single { it.fileName == "broken.jpg" }.id) }.isFailure)
            val tooLarge = runCatching { container.photos.load(photos.single { it.fileName == "TooLarge.jpg" }.id) }.exceptionOrNull()
            assertTrue(tooLarge?.message?.contains("64 MB") == true)

            val requests = AtomicInteger(0)
            val client = OkHttpClient.Builder().addInterceptor {
                requests.incrementAndGet()
                throw IOException("Photo tests must not call external metadata services")
            }.build()
            val metadata = MetadataService(container.database, TmdbClient(client) { "fixture-only" })
            metadata.refreshAll()
            metadata.match(photos.first())
            metadata.apply(123, photos.first().id, force = true)
            metadata.resetToFile(photos.first().id)
            assertEquals(0, requests.get())
            val first = photos.single { it.fileName == "01.jpg" }
            container.database.movies().setHidden(first.id, true)
            assertTrue("A cached photo must still honor hidden state", runCatching { container.photos.load(first.id) }.isFailure)
            container.database.movies().setHidden(first.id, false)
            container.database.sources().upsert(source.copy(ignoredFolders = listOf("Photos/Trip")))
            assertTrue("A cached photo must still honor source exclusions", runCatching { container.photos.load(first.id) }.isFailure)
        } finally { cleanup(source) }
    }

    @Test
    fun remoteOpensAlbumBrowsesPhotosRetriesErrorsAndReturnsToTheSameAlbum() = runBlocking<Unit> {
        val source = fixture()
        try {
            container.scanner.scan(source.id)
            val photos = container.database.movies().forSource(source.id)
            val first = photos.single { it.fileName == "01.jpg" }
            val second = photos.single { it.fileName == "02.png" }
            val third = photos.single { it.fileName == "03.webp" }
            val broken = photos.single { it.fileName == "broken.jpg" }
            ActivityScenario.launch(MainActivity::class.java).use {
                visible(hasText("Welcome to CoveShelf"))
                device.pressDPadRight()
                visible(hasText("No movies yet"))
                device.pressDPadRight()
                visible(hasText("3 albums"))
                activate(hasTestTag("photo-album:${photoAlbumId(first)}"))
                activate(hasTestTag("photo:${first.id}"))
                visible(hasTestTag("full-photo:${first.id}"))
                visible(hasText("1 of 4"))
                device.pressDPadRight()
                visible(hasTestTag("full-photo:${second.id}"))
                device.pressDPadRight()
                visible(hasTestTag("full-photo:${third.id}"))
                device.pressDPadRight()
                visible(hasTestTag("photo-error:${broken.id}:0"))
                focusedButton("Try again")
                device.pressDPadCenter()
                // Observe a completed new attempt, not the still-visible previous error.
                visible(hasTestTag("photo-error:${broken.id}:1"))
                focusedButton("Try again")
                device.pressDPadLeft()
                focusedButton("Previous")
                device.pressDPadCenter()
                visible(hasTestTag("full-photo:${third.id}"))
                device.pressDPadRight()
                focusedButton("Next")
                device.pressDPadCenter()
                visible(hasText("Couldn't open photo"))
                focusedButton("Try again")
                device.pressDPadLeft()
                focusedButton("Previous")
                device.pressDPadCenter()
                visible(hasTestTag("full-photo:${third.id}"))
                device.pressBack()
                visible(hasText("4 photos"))
                visible(hasTestTag("photo:${first.id}")).assertIsFocused()
                device.pressBack()
                visible(hasText("3 albums"))
                visible(hasTestTag("photo-album:${photoAlbumId(first)}")).assertIsFocused()
                container.scanner.isScanning.first { scanning -> !scanning }
            }
        } finally { cleanup(source) }
    }

    private suspend fun fixture(): SourceEntity {
        val host = InstrumentationRegistry.getArguments().getString("nasHost")
        assumeTrue("Pass nasHost for the synthetic photo fixture", !host.isNullOrBlank())
        val empty = container.database.sources().all().isEmpty()
        assumeTrue("Use a disposable emulator with an empty library", empty)
        return SourceEntity(name = "Photo fixture", host = host!!, port = 1445, share = "COVEPHOTOS",
            username = "cinevault", encryptedPassword = container.secrets.encrypt("fixture-only"))
            .also { container.database.sources().upsert(it) }
    }

    private suspend fun cleanup(source: SourceEntity) {
        container.scanner.isScanning.first { scanning -> !scanning }
        container.database.sources().delete(source.id)
        container.photos.resetSource(source.id)
        container.smb.reset(source.id)
    }

    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntilExactlyOneExists(matcher, timeoutMillis = 30_000)
        return compose.onNode(matcher).assertIsDisplayed()
    }

    private fun activate(matcher: SemanticsMatcher) {
        val target = visible(matcher)
        target.performSemanticsAction(SemanticsActions.RequestFocus)
        target.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private fun focusedButton(text: String) {
        val matcher = hasText(text) and hasClickAction() and isFocused()
        compose.waitUntilExactlyOneExists(matcher, timeoutMillis = 10_000)
        compose.onNode(matcher).assertIsFocused()
    }
}
