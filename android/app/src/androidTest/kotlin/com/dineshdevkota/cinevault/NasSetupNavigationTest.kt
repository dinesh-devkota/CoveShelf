package com.dineshdevkota.cinevault

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.dineshdevkota.cinevault.ui.NasSetupActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses remote confirmation/back with only the disposable CI NAS fixture. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class NasSetupNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val application = ApplicationProvider.getApplicationContext<CineVaultApp>()
    private val container get() = application.container
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun invalidAddressAndPortKeepTheFormAvailableWithoutStartingAConnection() {
        ActivityScenario.launch(NasSetupActivity::class.java).use {
            press("Enter NAS address")
            val address = visible(hasText("NAS address") and hasSetTextAction())
            address.assertIsFocused().performTextReplacement("not a valid address")
            address.assertIsFocused()
            press("Connect")
            label("Enter a valid NAS hostname or IP address. Enter credentials in the sign-in fields.")
            fill("NAS address", "nas.example")
            press("Connection options")
            fill("Port (usually 445)", "70000")
            press("Connect")
            label("Enter a port between 1 and 65535.")
            compose.onNode(hasText("Username") and hasSetTextAction()).assertDoesNotExist()
            press("Sign in")
            fill("Username", "example-user")
            press("Use guest access")
            compose.onNode(hasText("Username") and hasSetTextAction()).assertDoesNotExist()
        }
    }

    @Test
    fun authenticatedSetupRecoversFromMissingShareNavigatesParentsAndStartsScan() = runBlocking<Unit> {
        val host = InstrumentationRegistry.getArguments().getString("nasHost")
        assumeTrue("Pass nasHost for the synthetic SMB fixture", !host.isNullOrBlank())
        val emptyLibrary = container.database.openHelper.readableDatabase
            .query("SELECT NOT EXISTS(SELECT 1 FROM sources)").use { it.moveToFirst() && it.getInt(0) == 1 }
        assumeTrue("Use a disposable emulator with an empty library for setup tests", emptyLibrary)
        var addedId: String? = null
        try {
            ActivityScenario.launch(NasSetupActivity::class.java).use {
                press("Enter NAS address")
                // An embedded port must work without opening the optional connection settings.
                fill("NAS address", "smb://$host:1445")
                press("Connect")
                fill("Username", "cinevault")
                fill("Password", "fixture-only")
                press("Connect")
                press("CINEVAULT")
                press("MediaChecks")
                press("Ignored")
                label("CINEVAULT / MediaChecks/Ignored")
                device.pressBack()
                label("CINEVAULT / MediaChecks")
                press("Ignored")
                device.pressBack()
                label("CINEVAULT / MediaChecks")
                device.pressBack()
                label("CINEVAULT / Top level")
                device.pressBack()
                label("Choose the shared folder that contains your media.")
                device.pressBack()
                press("Enter shared folder")
                fill("Share name", "DOES_NOT_EXIST")
                press("Open shared folder")
                label("DOES_NOT_EXIST / Top level")
                press("Try again")
                label("Connection needs attention")
                device.pressBack()
                fill("Share name", "CINEVAULT")
                press("Open shared folder")
                press("MediaChecks")
                // Wait for a result so saving cannot race the folder connection check.
                label("Ignored")
                press("Use this folder & scan")
                withTimeout(60_000) {
                    while (addedId == null) {
                        addedId = container.database.sources().observeAll().first().singleOrNull()?.id
                        if (addedId == null) delay(100)
                    }
                    while (container.database.sources().get(addedId!!)?.lastScanAt == null) delay(100)
                    container.scanner.isScanning.first { scanning -> !scanning }
                }
            }
            val saved = requireNotNull(container.database.sources().get(addedId!!))
            assertEquals(host, saved.host)
            assertEquals(1445, saved.port)
            assertEquals("CINEVAULT", saved.share)
            assertEquals("MediaChecks", saved.rootPath)
            assertEquals("cinevault", saved.username)
            assertEquals(listOf("MediaChecks/Ignored/Hidden.mp4", "MediaChecks/Keep.mp4", "MediaChecks/clip.ts"),
                container.database.movies().forSource(saved.id).filterNot { it.isMissing }.map { it.filePath }.sorted())
        } finally {
            // No owner data is present (guarded above); remove only the source this test just added.
            addedId?.let { container.database.sources().delete(it); container.smb.reset(it) }
        }
    }

    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        // Lazy rows may not exist yet, and SMB results arrive outside Compose's idle tracking.
        compose.waitUntil(timeoutMillis = 45_000) {
            try {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
                compose.onNode(matcher).assertIsDisplayed()
                true
            } catch (_: AssertionError) { false }
        }
        return compose.onNode(matcher).assertIsDisplayed()
    }

    private fun label(text: String) = visible(hasText(text))
    private fun fill(label: String, value: String) {
        visible(hasText(label) and hasSetTextAction()).performTextReplacement(value)
    }
    private fun press(text: String) {
        val button = visible(hasText(text) and hasClickAction() and isEnabled())
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        button.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
    }
}
