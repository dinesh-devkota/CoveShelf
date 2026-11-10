@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.dineshdevkota.cinevault.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.container
import com.dineshdevkota.cinevault.core.NasAddress
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.data.saveSourceWithExclusions
import com.dineshdevkota.cinevault.smb.SmbService
import com.dineshdevkota.cinevault.smb.SourceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class SourceEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
        setCineVaultContent { SourceEditorScreen(sourceId, onClose = ::finish) }
    }

    companion object {
        private const val EXTRA_SOURCE_ID = "source_id"

        fun start(context: Context, sourceId: String?) {
            context.startActivity(Intent(context, SourceEditorActivity::class.java).putExtra(EXTRA_SOURCE_ID, sourceId))
        }
    }
}

@Composable
private fun SourceEditorScreen(sourceId: String?, onClose: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val scanning by container.scanner.isScanning.collectAsStateWithLifecycle()
    val scanStatus by container.scanner.status.collectAsStateWithLifecycle()
    val draftId = remember { UUID.randomUUID().toString() }
    var existing by remember { mutableStateOf<SourceEntity?>(null) }
    var loaded by remember { mutableStateOf(sourceId == null) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
    var ignoredFolders by remember { mutableStateOf(emptyList<String>()) }
    var ignoredFiles by remember { mutableStateOf(emptyList<String>()) }
    var manualRoot by remember { mutableStateOf(false) }
    var exclusionsNotice by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var workDescription by remember { mutableStateOf("") }
    var confirmRemoval by remember { mutableStateOf(false) }

    LaunchedEffect(sourceId) {
        if (sourceId == null || loaded) return@LaunchedEffect
        val source = container.database.sources().get(sourceId)
        if (source == null) {
            onClose()
            return@LaunchedEffect
        }
        existing = source
        name = source.name
        host = source.host
        port = source.port?.toString().orEmpty()
        share = source.share
        rootPath = source.rootPath
        ignoredFolders = source.ignoredFolders
        ignoredFiles = source.ignoredFiles
        username = source.username
        try {
            password = withContext(Dispatchers.IO) { container.secrets.decrypt(source.encryptedPassword) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = e.message ?: "Could not read the saved password. Enter it again."
            messageIsError = true
        }
        loaded = true
    }
    if (!loaded) {
        Column(Modifier.padding(ScreenPadding)) { NasStatusPanel("Opening source…", working = true) }
        return
    }
    val firstField = remember { FocusRequester() }
    var firstFieldReady by remember { mutableStateOf(false) }
    var initialFocusSet by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(firstFieldReady, windowFocused) {
        if (firstFieldReady && windowFocused && !initialFocusSet) initialFocusSet = firstField.requestFocus()
    }

    // This pure validation also gates browsing, so a malformed nonempty port cannot silently
    // turn into the default port. Keep all draft values unchanged until the user corrects them.
    fun validatedConfig(): SourceConfig {
        val portText = port.trim()
        val portValue = portText.toIntOrNull()?.takeIf { it in 1..65535 }
        require(portText.isEmpty() || portValue != null) { "Port must be a number from 1 to 65535." }
        val hostValue = host.trim()
        val shareValue = share.trim().trim('/', '\\')
        require(hostValue.isNotEmpty() && shareValue.isNotEmpty()) {
            "Enter the NAS address and share name."
        }
        require(rootPath.replace('\\', '/').split('/').none { it == ".." }) {
            "Choose a folder inside the shared folder."
        }
        val address = NasAddress.parse(hostValue, portValue)
        return SourceConfig(
            id = existing?.id ?: draftId,
            host = address.host,
            port = address.port,
            share = shareValue,
            rootPath = rootPath.trim().replace('\\', '/').trim('/'),
            username = username.trim(),
            password = password,
            ignoredFolders = ignoredFolders,
            ignoredFiles = ignoredFiles,
        )
    }

    fun config(): SourceConfig? = try { validatedConfig() }
    catch (failure: IllegalArgumentException) {
        message = failure.message
        messageIsError = true
        null
    }

    fun clearExclusionsForSourceChange() {
        message = null
        if (ignoredFolders.isNotEmpty() || ignoredFiles.isNotEmpty()) {
            ignoredFolders = emptyList(); ignoredFiles = emptyList()
            exclusionsNotice = "NAS connection or scan folder changed. Previous ignore rules were cleared; choose new rules below."
        }
    }

    fun changeRoot(value: String) {
        if (value != rootPath) clearExclusionsForSourceChange()
        rootPath = value
    }

    fun launchWork(description: String, work: suspend () -> Unit) {
        busy = true
        workDescription = description
        message = null
        messageIsError = false
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Failed: ${SmbService.describe(e)}"
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    val idle = !busy && !scanning
    val browseValidation = runCatching { validatedConfig() }
    val browsingEnabled = idle && browseValidation.isSuccess
    // Keep the disabled picker controls visible without giving them an unvalidated connection.
    val browseConfig = browseValidation.getOrNull() ?: SourceConfig(existing?.id ?: draftId,
        "", null, "", rootPath.trim().replace('\\', '/').trim('/'), "", "")
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(if (sourceId == null) "Add NAS" else "Edit NAS", kicker = "SOURCES")
        if (busy) NasStatusPanel(workDescription, working = true)
        else if (scanning) NasStatusPanel("Library scan in progress", scanStatus ?: "You can save these changes after the scan finishes.", working = true)
        message?.let { NasStatusPanel(if (messageIsError) "Connection needs attention" else "Connection verified", it, error = messageIsError) }
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                TvTextField(
                    name,
                    { name = it },
                    "Name (e.g. Living Room NAS)",
                    Modifier.fillMaxWidth().focusRequester(firstField).onGloballyPositioned { firstFieldReady = true },
                    enabled = !busy,
                )
                TvTextField(
                    host,
                    { if (host != it) clearExclusionsForSourceChange(); host = it },
                    "Host or IP (e.g. 192.168.1.20)",
                    Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Uri,
                    enabled = !busy,
                )
                TvTextField(
                    port,
                    { if (port != it) clearExclusionsForSourceChange(); port = it },
                    "Port (optional, default 445)",
                    Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Number,
                    enabled = !busy,
                )
                Text("Sign in", style = MaterialTheme.typography.titleMedium)
                TvTextField(username, { message = null; username = it }, "Username (empty for guest)", Modifier.fillMaxWidth(), enabled = !busy)
                TvTextField(password, { message = null; password = it }, "Password", Modifier.fillMaxWidth(), isPassword = true, enabled = !busy)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Media folder", style = MaterialTheme.typography.titleMedium)
                TvTextField(share, { if (share != it) clearExclusionsForSourceChange(); share = it }, "Share (e.g. media)", Modifier.fillMaxWidth(), enabled = !busy)
                NasRootFolderPicker(browseConfig, browsingEnabled, ::changeRoot)
                browseValidation.exceptionOrNull()?.message?.let { Text(it, color = SecondaryText) }
                OutlinedButton(enabled = idle, onClick = { manualRoot = !manualRoot }) { Text("Enter folder path manually") }
                if (manualRoot) TvTextField(rootPath, ::changeRoot, "Folder inside share (empty for share root)",
                    Modifier.fillMaxWidth(), imeAction = ImeAction.Done, enabled = idle)
                Text(
                    "Use a NAS account with read access. The password is encrypted on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                )
            }
        }
        exclusionsNotice?.let { Text(it, color = SecondaryText) }
        IgnoredFoldersEditor(browseConfig, ignoredFolders, ignoredFiles,
            { ignoredFolders = it }, { ignoredFiles = it }, browsingEnabled)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(
                enabled = idle,
                onClick = {
                    val c = config() ?: return@OutlinedButton
                    launchWork("Checking connection…") {
                        container.smb.testConnection(c)
                        message = "Connected successfully."
                    }
                },
            ) { Text("Test Connection") }
            Button(
                enabled = idle,
                onClick = {
                    val c = config() ?: return@Button
                    val savedName = name.trim().ifEmpty { c.host }
                    launchWork("Saving source…") {
                        val sealed = if (c.username.isEmpty()) "" else withContext(Dispatchers.Default) {
                            container.secrets.encrypt(c.password)
                        }
                        val entity = SourceEntity(
                            id = c.id,
                            name = savedName,
                            host = c.host,
                            port = c.port,
                            share = c.share,
                            rootPath = c.rootPath,
                            username = c.username,
                            encryptedPassword = sealed,
                            lastScanAt = existing?.lastScanAt,
                            ignoredFolders = c.ignoredFolders,
                            ignoredFiles = c.ignoredFiles,
                        )
                        // Saving and queueing its scan form one operation after the commit begins.
                        withContext(NonCancellable) {
                            container.database.saveSourceWithExclusions(entity)
                            container.smb.reset(entity.id)
                            container.photos.resetSource(entity.id)
                            container.scope.launch { container.scanner.scan(entity.id) }
                        }
                        onClose()
                    }
                },
            ) { Text("Save & Scan") }
            if (existing != null) {
                OutlinedButton(
                    enabled = idle,
                    onClick = { confirmRemoval = true },
                ) { Text("Remove source") }
            }
        }
    }
    if (confirmRemoval) {
        Dialog(onDismissRequest = { confirmRemoval = false }) {
            Column(Modifier.widthIn(max = 520.dp).background(SurfaceColor, RoundedCornerShape(16.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Remove ${existing?.name.orEmpty()}?", style = MaterialTheme.typography.headlineSmall)
                Text("This removes its library entries and saved playback progress from this device. Media files stay on the NAS.", color = SecondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { confirmRemoval = false }) { Text("Keep source") }
                    OutlinedButton(onClick = {
                        confirmRemoval = false
                        val source = existing ?: return@OutlinedButton
                        launchWork("Removing source…") {
                            container.database.withTransaction {
                                container.database.movies().deleteForSource(source.id)
                                container.database.sources().delete(source.id)
                            }
                            container.smb.reset(source.id)
                            container.photos.resetSource(source.id)
                            onClose()
                        }
                    }, enabled = idle) { Text("Remove") }
                }
            }
        }
    }
}
