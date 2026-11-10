@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.dineshdevkota.cinevault.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.dineshdevkota.cinevault.container
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.photos.NasPhotoLoader
import com.dineshdevkota.cinevault.photos.photoAlbumId
import com.dineshdevkota.cinevault.photos.photoAlbums
import kotlinx.coroutines.CancellationException

private sealed interface PhotoImageState {
    data object Loading : PhotoImageState
    data class Ready(val bitmap: ImageBitmap) : PhotoImageState
    data class Failed(val message: String, val attempt: Int) : PhotoImageState
}

@Composable
private fun photoImage(photo: MovieEntity, thumbnail: Boolean, sourceVersion: Int, retry: Int = 0): State<PhotoImageState> {
    val container = LocalContext.current.container
    val source by remember(photo.sourceId) { container.database.sources().observe(photo.sourceId) }.collectAsStateWithLifecycle(null)
    return produceState<PhotoImageState>(PhotoImageState.Loading, photo, thumbnail, source, sourceVersion, retry) {
        value = PhotoImageState.Loading
        try { value = PhotoImageState.Ready(container.photos.load(photo.id, thumbnail).asImageBitmap()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { value = PhotoImageState.Failed(NasPhotoLoader.describe(failure), retry) }
    }
}

@Composable
private fun PhotoThumbnail(photo: MovieEntity, sourceVersion: Int = 0) {
    val state by photoImage(photo, thumbnail = true, sourceVersion)
    Box(Modifier.fillMaxSize().background(SurfaceColor), contentAlignment = Alignment.Center) {
        when (val image = state) {
            PhotoImageState.Loading -> CircularProgressIndicator(Modifier.size(24.dp), color = Accent, strokeWidth = 2.dp)
            is PhotoImageState.Ready -> Image(image.bitmap, photo.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            is PhotoImageState.Failed -> Text("Open to retry", style = MaterialTheme.typography.labelMedium, color = SecondaryText)
        }
    }
}

@Composable
internal fun PhotoTile(photo: MovieEntity, modifier: Modifier = Modifier, sourceVersion: Int = 0, onClick: () -> Unit) {
    Column {
        Card(onClick, modifier.fillMaxWidth().aspectRatio(4f / 3f).pointerClick(onClick = onClick),
            shape = CardDefaults.shape(RoundedCornerShape(12.dp))) { PhotoThumbnail(photo, sourceVersion) }
        Text(photo.title, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun PhotosTab(movies: List<MovieEntity>) {
    val context = LocalContext.current
    val sources by remember { context.container.database.sources().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val sourceById = remember(sources) { sources.associateBy { it.id } }
    val albums = remember(movies, sources) { photoAlbums(movies, sources) }
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    val album = albums.firstOrNull { it.id == albumId }
    val targetAlbumId = lastAlbumId?.takeIf { id -> albums.any { it.id == id } } ?: albums.firstOrNull()?.id
    val targetPhotoId = selectedPhoto?.takeIf { id -> album?.photos?.any { it.id == id } == true } ?: album?.photos?.firstOrNull()?.id
    val albumsState = rememberLazyGridState()
    val photosState = key(albumId) { rememberLazyGridState() }
    val restoreFocus = remember { FocusRequester() }
    val transitionFocus = remember { FocusRequester() }
    var holdingFocus by remember { mutableStateOf(false) }
    var focusReady by remember(albumId, targetPhotoId, targetAlbumId) { mutableStateOf(false) }
    var focusPending by remember { mutableStateOf(false) }
    var focusRequest by remember { mutableIntStateOf(0) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val selectedAlbumId = albumId
    val selectedPhotoId = selectedPhoto
    val requestedFocus = focusRequest
    val requestedPending = focusPending
    val requestedReady = focusReady
    val requestedWindow = windowFocused
    fun requestIsCurrent() = focusRequest == requestedFocus && albumId == selectedAlbumId && selectedPhoto == selectedPhotoId
    fun holdFocus() {
        // Keep focus inside Photos while its old grid is removed. Otherwise the tab row can take
        // focus and its onFocus handler changes to Home before the replacement card is laid out.
        holdingFocus = true
        focusRequest++
        transitionFocus.requestFocus()
    }
    fun backToAlbums() { holdFocus(); albumId = null; selectedPhoto = null; focusPending = true }
    fun openAlbum(id: String, firstPhoto: String) {
        holdFocus(); lastAlbumId = id; albumId = id; selectedPhoto = firstPhoto; focusPending = true
    }
    BackHandler(enabled = albumId != null) { backToAlbums() }
    LaunchedEffect(windowFocused) {
        if (requestedWindow && selectedPhotoId != null) { focusRequest++; focusPending = true }
    }
    LaunchedEffect(selectedAlbumId, albums) {
        // Read one composition snapshot. A pending album-list effect must not clear a new click.
        if (selectedAlbumId != null && albums.none { it.id == selectedAlbumId } && albumId == selectedAlbumId) backToAlbums()
    }
    LaunchedEffect(selectedAlbumId, targetAlbumId, targetPhotoId, requestedPending, requestedFocus) {
        if (requestedPending && requestIsCurrent()) {
            val grid = if (album == null) albumsState else photosState
            val target = if (album == null) targetAlbumId else targetPhotoId
            val index = if (album == null) albums.indexOfFirst { it.id == target }
                else album.photos.indexOfFirst { it.id == target }
            if (index >= 0 && grid.layoutInfo.visibleItemsInfo.none { it.key == target }) grid.scrollToItem(index)
        }
    }
    LaunchedEffect(requestedWindow, requestedReady, requestedPending, requestedFocus) {
        if (requestedWindow && requestedReady && requestedPending && requestIsCurrent() && restoreFocus.requestFocus()) {
            focusPending = false
            holdingFocus = false
        }
    }

    Column(Modifier.fillMaxSize().focusRequester(transitionFocus).focusProperties { canFocus = holdingFocus }.focusable(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(album?.title ?: "Photos", Modifier.padding(horizontal = ScreenPadding),
            kicker = if (album == null) "YOUR PHOTO ALBUMS" else sourceById[album.sourceId]?.name ?: "YOUR NAS",
            trailing = album?.let { "${it.photos.size} ${if (it.photos.size == 1) "photo" else "photos"}" }
                ?: "${albums.size} ${if (albums.size == 1) "album" else "albums"}")
        if (album != null) Row(Modifier.padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = ::backToAlbums) { Text("All albums") }
            Text(album.folder.ifBlank { "Shared folder" }, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (albums.isEmpty()) {
            Column(Modifier.padding(horizontal = ScreenPadding)) {
                EmptyState("Your photos, on the big screen", "Add a NAS folder containing photos, then scan it. Each folder becomes an album.")
                Button(onClick = { NasSetupActivity.start(context) },
                    modifier = Modifier.focusRequester(restoreFocus).onGloballyPositioned { focusReady = true }) { Text("Add photo folder") }
            }
        } else LazyVerticalGrid(GridCells.Adaptive(220.dp), Modifier.weight(1f),
            state = if (album == null) albumsState else photosState,
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (album == null) items(albums, key = { it.id }) { item ->
                Column {
                    val focus = if (item.id == targetAlbumId) Modifier.focusRequester(restoreFocus).onGloballyPositioned { focusReady = true } else Modifier
                    Card(onClick = { openAlbum(item.id, item.photos.first().id) },
                        modifier = focus.fillMaxWidth().aspectRatio(4f / 3f).testTag("photo-album:${item.id}").pointerClick {
                            openAlbum(item.id, item.photos.first().id)
                        }, shape = CardDefaults.shape(RoundedCornerShape(12.dp))) {
                        PhotoThumbnail(item.photos.first(), sourceById[item.sourceId]?.hashCode() ?: 0)
                    }
                    Text(item.title, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium)
                    Text("${sourceById[item.sourceId]?.name ?: "NAS"} · ${item.photos.size} ${if (item.photos.size == 1) "photo" else "photos"}", color = SecondaryText,
                        style = MaterialTheme.typography.labelMedium)
                    Text(item.folder.ifBlank { "Shared folder" }, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall)
                }
            } else items(album.photos, key = { it.id }) { photo ->
                PhotoTile(photo, Modifier.testTag("photo:${photo.id}").then(if (photo.id == targetPhotoId)
                    Modifier.focusRequester(restoreFocus).onGloballyPositioned { focusReady = true } else Modifier),
                    sourceById[photo.sourceId]?.hashCode() ?: 0) {
                    selectedPhoto = photo.id
                    PhotoViewerActivity.start(context, photo.id)
                }
            }
        }
    }
}

class PhotoViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("photo_id") ?: return finish()
        setCineVaultContent { PhotoViewerScreen(id, ::finish) }
    }
    companion object {
        fun start(context: Context, photoId: String) = context.startActivity(
            Intent(context, PhotoViewerActivity::class.java).putExtra("photo_id", photoId))
    }
}

@Composable
private fun PhotoViewerScreen(initialId: String, onClose: () -> Unit) {
    val container = LocalContext.current.container
    val movies by remember { container.database.movies().observeAll() }.collectAsStateWithLifecycle(initialValue = null)
    val sources by remember { container.database.sources().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var selectedId by rememberSaveable(initialId) { mutableStateOf(initialId) }
    var albumId by rememberSaveable(initialId) { mutableStateOf<String?>(null) }
    var retry by remember(selectedId) { mutableIntStateOf(0) }
    LaunchedEffect(movies) { if (albumId == null) albumId = movies?.firstOrNull { it.id == initialId }?.let(::photoAlbumId) }
    val album = remember(movies, albumId, sources) { photoAlbums(movies.orEmpty(), sources).firstOrNull { it.id == albumId } }
    val photos = album?.photos.orEmpty()
    val index = photos.indexOfFirst { it.id == selectedId }
    val photo = photos.getOrNull(index)
    val primary = remember { FocusRequester() }
    var ready by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var photoFocused by remember { mutableStateOf(false) }
    val imageState = if (photo != null) photoImage(photo, thumbnail = false,
        sources.firstOrNull { it.id == photo.sourceId }?.hashCode() ?: 0, retry).value else null
    val retryFocus = remember { FocusRequester() }
    var retryReady by remember(selectedId, imageState is PhotoImageState.Failed) { mutableStateOf(false) }
    LaunchedEffect(ready, windowFocused) { if (ready && windowFocused && !focused) focused = primary.requestFocus() }
    LaunchedEffect(retryReady, windowFocused) { if (retryReady && windowFocused) retryFocus.requestFocus() }
    BackHandler { onClose() }
    fun move(delta: Int) { photos.getOrNull(index + delta)?.let { selectedId = it.id } }

    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onClose) { Text("Back") }
            Column(Modifier.weight(1f)) {
                Text(photo?.title ?: "Photo", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(sources.firstOrNull { it.id == photo?.sourceId }?.name, album?.title).joinToString(" · "), color = SecondaryText)
            }
            if (index >= 0) Text("${index + 1} of ${photos.size}", color = SecondaryText)
        }
        Box(Modifier.weight(1f).fillMaxWidth().focusRequester(primary).onGloballyPositioned { ready = true }
            .onFocusChanged { photoFocused = it.isFocused }.onPreviewKeyEvent { event ->
                if (photoFocused && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                    if (event.type == KeyEventType.KeyDown) move(if (event.key == Key.DirectionLeft) -1 else 1)
                    true
                } else false
            }.focusable(), contentAlignment = Alignment.Center) {
            if (movies == null) NasStatusPanel("Opening photo…", working = true)
            else if (photo == null) NasStatusPanel("Photo unavailable", "It may have been hidden, ignored or removed. Return to Photos and rescan its source.", error = true)
            else {
                when (val image = imageState) {
                    null, PhotoImageState.Loading -> NasStatusPanel("Opening photo…", working = true)
                    is PhotoImageState.Ready -> Image(image.bitmap, photo.title, Modifier.fillMaxSize().testTag("full-photo:${photo.id}"), contentScale = ContentScale.Fit)
                    is PhotoImageState.Failed -> Box(Modifier.testTag("photo-error:${photo.id}:${image.attempt}")) {
                        NasStatusPanel("Couldn't open photo", image.message, error = true)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = index > 0, onClick = { move(-1) }) { Text("Previous") }
            if (imageState is PhotoImageState.Failed) Button(onClick = { retry++ },
                modifier = Modifier.focusRequester(retryFocus).onGloballyPositioned { retryReady = true }) { Text("Try again") }
            Button(enabled = index >= 0 && index < photos.lastIndex, onClick = { move(1) }) { Text("Next") }
            Text("Select the photo, then use Left / Right to browse", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
