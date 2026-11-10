package com.dineshdevkota.cinevault

import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.ui.SourceEditorActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SourceEditorPickerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val application = ApplicationProvider.getApplicationContext<CineVaultApp>()
    private val container get() = application.container

    @Test
    fun editPickersPersistFolderAndFileExclusionsAndScanTheSelectedRoot() = runBlocking<Unit> {
        val host = InstrumentationRegistry.getArguments().getString("nasHost")
        assumeTrue("Pass nasHost for the synthetic SMB fixture", !host.isNullOrBlank())
        // Save & Scan also starts the app-wide metadata pass. Only run on an empty test library.
        val emptyLibrary = container.database.openHelper.readableDatabase
            .query("SELECT NOT EXISTS(SELECT 1 FROM sources)").use { it.moveToFirst() && it.getInt(0) == 1 }
        assumeTrue("Use a disposable emulator with an empty library for the editor test", emptyLibrary)

        val source = SourceEntity(name = "Picker fixture", host = host!!, port = 1445, share = "CINEVAULT",
            username = "cinevault", encryptedPassword = container.secrets.encrypt("fixture-only"))
        container.database.sources().upsert(source)
        try {
            launchEditor(source.id).use {
                press("Choose scan folder ▾")
                press("MediaChecks ▸")
                // This child is only available after the chosen folder's SMB listing completed.
                label("Ignored ▸")
                press("Use this folder")
                label("Scan folder: MediaChecks")
                press("Choose folders or files to ignore ▾")
                press("Ignore folder", hasTestTag("ignore-toggle:Ignored"))
                press("Ignore file", hasTestTag("ignore-toggle:Keep.mp4"))
                press("Close ignore picker ▴")
                val save = visible(hasText("Save & Scan") and hasClickAction() and isEnabled())
                save.performSemanticsAction(SemanticsActions.RequestFocus)
                save.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }

                // An idle scanner alone could mean its queued coroutine has not started yet.
                withTimeout(60_000) {
                    while (container.database.sources().get(source.id)?.lastScanAt == null) delay(100)
                    container.scanner.isScanning.first { scanning -> !scanning }
                }
            }
            val saved = requireNotNull(container.database.sources().get(source.id))
            assertEquals("MediaChecks", saved.rootPath)
            assertEquals(listOf("Ignored"), saved.ignoredFolders)
            assertEquals(listOf("Keep.mp4"), saved.ignoredFiles)
            val indexed = container.database.movies().forSource(source.id).filterNot { it.isMissing }
            assertEquals(listOf("MediaChecks/clip.ts"), indexed.map { it.filePath })

            launchEditor(source.id).use {
                label("Scan folder: MediaChecks")
                label("Folder: Ignored")
                label("File: Keep.mp4")
            }
        } finally {
            // Movie rows cascade from this unique source ID, including on assertion failures.
            container.database.sources().delete(source.id)
            container.smb.reset(source.id)
        }
    }

    private fun launchEditor(id: String): ActivityScenario<SourceEditorActivity> = ActivityScenario.launch(
        Intent(application, SourceEditorActivity::class.java).putExtra("source_id", id),
    )

    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // SMB listings arrive outside Compose's idle tracking. Offscreen rows already exist in the
        // editor's scrolling Column, so wait for the result, then let Compose scroll it into view.
        compose.waitUntilExactlyOneExists(matcher, timeoutMillis = 45_000)
        return compose.onNode(matcher).performScrollTo().assertIsDisplayed()
    }

    private fun label(text: String) = visible(hasText(text))

    private fun press(text: String, match: SemanticsMatcher = hasClickAction()) {
        visible(hasText(text) and match and isEnabled()).performSemanticsAction(SemanticsActions.OnClick)
    }
}
