package com.dineshdevkota.cinevault.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.container
import com.dineshdevkota.cinevault.core.NasAddress
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.data.saveSourceWithExclusions
import com.dineshdevkota.cinevault.smb.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NasSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCineVaultContent { NasSetupScreen(onDone = ::finish) }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, NasSetupActivity::class.java))
    }
}

private enum class SetupStep(val title: String) {
    Devices("Device"), Connect("Sign in"), Shares("Shared folder"), Folders("Media folder")
}

@Composable
private fun NasSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val browser = remember { NasBrowser() }
    val sourceId = remember { UUID.randomUUID().toString() }
    val listState = rememberLazyListState()
    var step by remember { mutableStateOf(SetupStep.Devices) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    // Password deliberately lives only in memory until the source is saved.
    var password by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
    var ignoredFolders by remember { mutableStateOf(emptyList<String>()) }
    var ignoredFiles by remember { mutableStateOf(emptyList<String>()) }
    var exclusionsNotice by remember { mutableStateOf<String?>(null) }
    var discovery by remember { mutableStateOf(NasDiscoveryState(searching = true)) }
    var refresh by remember { mutableIntStateOf(0) }
    var shares by remember { mutableStateOf(emptyList<NasShare>()) }
    var folders by remember { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var credentials by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var manualShare by remember { mutableStateOf(false) }
    var folderReady by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    var committing by remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }
    // Decide once per step so typing an address never moves focus to Connect.
    val focusAddress = remember(step) { host.isBlank() }
    var focusReady by remember(step, rootPath) { mutableStateOf(false) }
    var focusedStage by remember(step, rootPath) { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var generation by remember { mutableIntStateOf(0) }
    var requestJob by remember { mutableStateOf<Job?>(null) }

    fun clearExclusionsForSourceChange() {
        if (ignoredFolders.isNotEmpty() || ignoredFiles.isNotEmpty()) {
            ignoredFolders = emptyList(); ignoredFiles = emptyList()
            exclusionsNotice = "NAS connection or media folder changed. Previous ignore rules were cleared."
        }
    }

    fun cancelRequest() {
        generation++
        requestJob?.cancel()
        busy = false
        error = null
    }

    fun invalidate() {
        cancelRequest()
        shares = emptyList()
        folders = emptyList()
        folderReady = false
    }

    fun config(requireShare: Boolean = false): SourceConfig? {
        val parsedPort = port.trim().toIntOrNull()
        error = when {
            host.isBlank() -> "Enter a NAS address or select a device."
            port.isNotBlank() && (parsedPort == null || parsedPort !in 1..65535) -> "Enter a port between 1 and 65535."
            requireShare && share.isBlank() -> "Enter a shared folder name."
            rootPath.replace('\\', '/').split('/').any { it == ".." } -> "Choose a folder within this share."
            else -> null
        }
        if (error != null) return null
        val address = try { NasAddress.parse(host, parsedPort) }
        catch (failure: IllegalArgumentException) { error = failure.message; return null }
        return SourceConfig(sourceId, address.host, address.port, share.trim().trim('/', '\\'),
            rootPath.replace('\\', '/').trim('/'), username.trim(), password, ignoredFolders, ignoredFiles)
    }

    fun request(message: String, onFailure: (Exception) -> Unit = {}, action: suspend (Int) -> Unit) {
        requestJob?.cancel()
        val token = ++generation
        busy = true
        busyText = message
        error = null
        requestJob = scope.launch {
            try {
                action(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == token) {
                    error = SmbService.describe(failure)
                    onFailure(failure)
                }
            } finally {
                if (generation == token) { busy = false; committing = false }
            }
        }
    }

    fun loadShares() {
        val source = config() ?: return
        invalidate()
        clearExclusionsForSourceChange()
        share = ""
        rootPath = ""
        request("Finding shared folders…", onFailure = {
            if (SmbService.isAuthenticationFailure(it)) credentials = true
            step = SetupStep.Connect
        }) { token ->
            val result = browser.listShares(source)
            if (generation == token) {
                shares = result
                step = SetupStep.Shares
                manualShare = result.isEmpty()
            }
        }
    }

    fun browse(selectedShare: String, path: String) {
        if (path != rootPath || selectedShare != share) clearExclusionsForSourceChange()
        share = selectedShare
        rootPath = path
        folders = emptyList()
        folderReady = false
        val source = config(requireShare = true) ?: return
        step = SetupStep.Folders
        request("Opening folder…") { token ->
            val result = browser.listFolders(source)
            if (generation == token) {
                folders = result
                rootPath = source.rootPath
                folderReady = true
                step = SetupStep.Folders
            }
        }
    }

    fun back() {
        if (committing) return
        cancelRequest()
        if (step == SetupStep.Folders && rootPath.isNotEmpty()) {
            browse(share, rootPath.substringBeforeLast('/', ""))
            return
        }
        step = when (step) {
            SetupStep.Devices -> { onDone(); SetupStep.Devices }
            SetupStep.Connect -> SetupStep.Devices
            SetupStep.Shares -> SetupStep.Connect
            SetupStep.Folders -> if (shares.isNotEmpty()) SetupStep.Shares else SetupStep.Connect
        }
    }

    BackHandler { back() }
    LaunchedEffect(step, rootPath) { listState.scrollToItem(0) }
    LaunchedEffect(step, rootPath, focusReady, busy, windowFocused) {
        if (focusReady && windowFocused && !busy && !focusedStage) focusedStage = primaryFocus.requestFocus()
    }
    LaunchedEffect(refresh, step == SetupStep.Devices) {
        if (step == SetupStep.Devices) NasDiscovery(context).discover().collect { discovery = it }
    }
    DisposableEffect(Unit) {
        onDispose { generation++; requestJob?.cancel() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(if (step == SetupStep.Connect && !credentials) "Connect to NAS" else step.title,
                kicker = "ADD NAS", showBack = false)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { back() }, enabled = !committing) { Text(if (busy) "Cancel" else "Back") }
                if (step != SetupStep.Devices) {
                    Text(runCatching { NasAddress.parse(host, port.trim().toIntOrNull()).authority }
                        .getOrDefault("Choose your NAS"), color = SecondaryText)
                }
            }
        }
        if (busy) item { NasStatusPanel(busyText, working = true) }
        error?.let { detail -> item { NasStatusPanel("Connection needs attention", detail, error = true) } }
        exclusionsNotice?.let { note -> item { Text(note, color = SecondaryText) } }
        when (step) {
            SetupStep.Devices -> {
                item {
                    NasStatusPanel(if (discovery.searching) "Looking for devices…" else "Devices on your network",
                        "Choose a NAS on this network, or enter its address.", working = discovery.searching)
                    discovery.error?.let { Text(it, color = SecondaryText) }
                    if (!discovery.searching && discovery.devices.isEmpty()) {
                        Text("Your NAS may still be reachable by address. Check that the TV and NAS use the same network.", color = SecondaryText)
                    }
                }
                items(discovery.devices, key = { "${it.host}:${it.port}" }) { device ->
                    Button(onClick = {
                        invalidate()
                        if (host != device.host || port != device.port.toString()) clearExclusionsForSourceChange()
                        name = device.name; host = device.host; port = device.port.takeIf { it != 445 }?.toString().orEmpty()
                        username = ""; password = ""; credentials = false; manualShare = false
                        step = SetupStep.Connect
                        loadShares()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name)
                            Text(device.host, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›")
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { step = SetupStep.Connect },
                            modifier = Modifier.focusRequester(primaryFocus).onGloballyPositioned { focusReady = true }) { Text("Enter NAS address") }
                        OutlinedButton(enabled = !discovery.searching, onClick = { refresh++ }) {
                            Text(if (discovery.searching) "Searching…" else "Search again")
                        }
                    }
                }
            }
            SetupStep.Connect -> {
                item {
                    TvTextField(host, { invalidate(); clearExclusionsForSourceChange(); host = it; username = ""; password = ""; share = ""; rootPath = "" },
                        "NAS address", Modifier.fillMaxWidth().then(if (focusAddress)
                            Modifier.focusRequester(primaryFocus).onGloballyPositioned { focusReady = true } else Modifier), enabled = !busy)
                }
                item {
                    OutlinedButton(onClick = { advanced = !advanced }, enabled = !busy) { Text(if (advanced) "Hide connection options" else "Connection options") }
                    if (advanced) TvTextField(port, { invalidate(); if (port != it) clearExclusionsForSourceChange(); port = it }, "Port (usually 445)", keyboardType = KeyboardType.Number, enabled = !busy)
                }
                if (credentials) {
                    item { Text("Use an account with access to your media folder.", color = SecondaryText) }
                    item { TvTextField(username, { invalidate(); username = it }, "Username", Modifier.fillMaxWidth(), enabled = !busy) }
                    item { TvTextField(password, { invalidate(); password = it }, "Password", Modifier.fillMaxWidth(), isPassword = true, enabled = !busy) }
                }
                item {
                    if (!credentials) Text("Guest access is tried first. Sign in if your NAS needs an account.", color = SecondaryText)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { loadShares() }, enabled = !busy,
                            modifier = if (!focusAddress) Modifier.focusRequester(primaryFocus).onGloballyPositioned { focusReady = true } else Modifier) { Text("Connect") }
                        OutlinedButton(onClick = {
                            invalidate()
                            credentials = !credentials
                            if (!credentials) { username = ""; password = "" }
                        }, enabled = !busy) { Text(if (credentials) "Use guest access" else "Sign in") }
                        OutlinedButton(onClick = { manualShare = !manualShare }, enabled = !busy) { Text(if (manualShare) "Hide shared folder" else "Enter shared folder") }
                    }
                }
                if (manualShare) {
                    item { Text("If your NAS restricts browsing, enter a share you can access.", color = SecondaryText) }
                    item { TvTextField(share, { invalidate(); if (share != it || rootPath.isNotEmpty()) clearExclusionsForSourceChange(); share = it; rootPath = "" }, "Share name", enabled = !busy) }
                    item { Button(onClick = { browse(share, "") }, enabled = !busy) { Text("Open shared folder") } }
                }
            }
            SetupStep.Shares -> {
                item { Text("Choose the shared folder that contains your media.", color = SecondaryText) }
                if (shares.isEmpty()) item { Text("No shares returned. You can enter a share name manually.", color = SecondaryText) }
                items(shares, key = { it.name }) { entry ->
                    Button(onClick = { browse(entry.name, "") }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().then(if (entry == shares.firstOrNull()) Modifier.focusRequester(primaryFocus).onGloballyPositioned { focusReady = true } else Modifier)) {
                        Column { Text(entry.name); if (entry.comment.isNotBlank()) Text(entry.comment, color = SecondaryText) }
                    }
                }
                item { OutlinedButton(onClick = { manualShare = true; step = SetupStep.Connect }, enabled = !busy,
                    modifier = if (shares.isEmpty()) Modifier.focusRequester(primaryFocus).onGloballyPositioned { focusReady = true } else Modifier) { Text("Enter share manually") } }
            }
            SetupStep.Folders -> {
                item {
                    Text("$share / ${rootPath.ifBlank { "Top level" }}", style = MaterialTheme.typography.titleMedium)
                    Text("Choose a parent folder to include its movies, shows and music. You can exclude folders or files below.", color = SecondaryText)
                    TvTextField(name, { name = it }, "Library name (optional)", enabled = !busy)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !busy && folderReady,
                            modifier = Modifier.focusRequester(primaryFocus).onGloballyPositioned { focusReady = true }, onClick = {
                            val source = config(requireShare = true) ?: return@Button
                            val savedName = name.trim().ifBlank { source.host }
                            request("Adding your media folder…") { token ->
                                browser.listFolders(source)
                                if (generation != token) return@request
                                val encrypted = withContext(Dispatchers.Default) { container.secrets.encrypt(source.password) }
                                if (generation != token) return@request
                                committing = true
                                // Once persisted, always hand the scan to the application scope, even
                                // if Android closes this screen during the database write.
                                withContext(NonCancellable) {
                                    container.database.saveSourceWithExclusions(SourceEntity(
                                        id = sourceId, name = savedName, host = source.host, port = source.port,
                                        share = source.share, rootPath = source.rootPath, username = source.username,
                                        encryptedPassword = encrypted,
                                        ignoredFolders = source.ignoredFolders, ignoredFiles = source.ignoredFiles,
                                    ))
                                    container.scope.launch { container.scanner.scan(sourceId) }
                                }
                                if (generation == token) onDone()
                            }
                        }) { Text("Use this folder & scan") }
                        OutlinedButton(onClick = { browse(share, rootPath.substringBeforeLast('/', "")) }, enabled = !busy && rootPath.isNotBlank()) { Text("Up") }
                    }
                }
                items(folders, key = { it }) { folder ->
                    OutlinedButton(onClick = { browse(share, listOf(rootPath, folder).filter(String::isNotBlank).joinToString("/")) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(folder, Modifier.weight(1f)); Text("›")
                    }
                }
                if (error != null) item { Button(onClick = { browse(share, rootPath) }, enabled = !busy) { Text("Try again") } }
                if (folderReady && folders.isEmpty()) item { Text("No subfolders. You can use this folder.", color = SecondaryText) }
                item {
                    IgnoredFoldersEditor(SourceConfig(sourceId, host, port.toIntOrNull(), share, rootPath, username, password),
                        ignoredFolders, ignoredFiles, { ignoredFolders = it }, { ignoredFiles = it }, !busy && folderReady)
                }
            }
        }
    }
}
