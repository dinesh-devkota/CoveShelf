package com.dineshdevkota.cinevault

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Condition
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.ui.SourceEditorActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceEditorPickerTest {
    private val application = ApplicationProvider.getApplicationContext<CineVaultApp>()
    private val container get() = application.container
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val form = UiScrollable(UiSelector().scrollable(true).className("android.widget.ScrollView"))
        .setAsVerticalList().setMaxSearchSwipes(8)

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
                press("Ignore folder", beside = "Folder: Ignored ▸")
                press("Ignore file", beside = "File: Keep.mp4")
                press("Close ignore picker ▴")
                press("Save & Scan")

                // An idle scanner alone could mean its queued coroutine has not started yet.
                withTimeout(45_000) {
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

    private fun nodes(text: String): List<AccessibilityNodeInfo> {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.text?.toString() == text && !bounds(node).isEmpty) matches += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        // Compose publishes virtual accessibility nodes. Walk their children as UIAutomator does.
        automation.rootInActiveWindow?.let(::visit)
        return matches
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun label(text: String): AccessibilityNodeInfo {
        return device.wait(Condition<UiDevice, AccessibilityNodeInfo?> {
            nodes(text).firstOrNull() ?: run {
                if (form.exists()) form.scrollTextIntoView(text)
                nodes(text).firstOrNull()
            }
        }, 15_000) ?: throw AssertionError("Could not find editor label '$text'")
    }

    private fun press(text: String, beside: String? = null) {
        val target = if (beside == null) label(text) else {
            // Ignore buttons repeat. Match the action in the same visual row as this exact entry.
            val row = bounds(label(beside))
            nodes(text).firstOrNull { button ->
                val box = bounds(button)
                box.left >= row.right && box.top < row.bottom && row.top < box.bottom
            } ?: throw AssertionError("No '$text' action beside '$beside'")
        }
        val button = generateSequence(target) { it.parent }.firstOrNull { it.isClickable && it.isFocusable }
            ?: throw AssertionError("No focusable button for '$text'")
        assertTrue("Could not focus '$text'", button.isFocused || button.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        assertTrue("Could not select '$text'", device.pressDPadCenter())
        instrumentation.waitForIdleSync()
    }
}
