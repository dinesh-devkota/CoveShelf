package com.dineshdevkota.cinevault.ui

import com.dineshdevkota.cinevault.core.LibraryQuery
import com.dineshdevkota.cinevault.core.MediaKind
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.photos.photoAlbums
import org.junit.Assert.*
import org.junit.Test

class PhotoAlbumsTest {
    private fun photo(source: String, path: String) = MovieEntity(sourceId = source, filePath = path,
        fileName = path.substringAfterLast('/'), fileSize = 100, parsedTitle = "photo", parsedYear = null,
        title = "photo", year = null)

    @Test fun foldersAndSourcesRemainDistinctAndUnavailablePhotosAreOmitted() {
        val first = photo("a", "Photos/Trip/02.jpg")
        val before = photo("a", "Photos/Trip/01.PNG")
        val otherSource = photo("b", "Photos/Trip/03.webp")
        val otherParent = photo("a", "Old/Trip/04.heic")
        val hidden = photo("a", "Photos/Trip/hidden.jpg").copy(isHidden = true)
        val missing = photo("a", "Photos/Trip/missing.jpg").copy(isMissing = true)
        val video = photo("a", "Photos/Trip/movie.mp4")
        val albums = photoAlbums(listOf(first, before, otherSource, otherParent, hidden, missing, video))
        assertEquals(3, albums.size)
        assertEquals(listOf(before, first), albums.single { it.sourceId == "a" && it.folder == "Photos/Trip" }.photos)
        assertEquals(3, albums.map { it.id }.distinct().size)
    }

    @Test fun televisionLikePhotoNamesAndStoredProgressNeverBecomeEpisodesOrContinueWatching() {
        val image = photo("a", "Photos/Family.S01E01.jpg").copy(seriesTitle = "Family", seriesTmdbId = 42,
            seasonNumber = 1, episodeNumber = 1, playbackPositionMs = 60_000)
        assertNull(image.episodeInfo)
        assertEquals(MediaKind.PHOTO, image.mediaKind)
        assertTrue(LibraryQuery.continueWatching(listOf(image)).isEmpty())
    }

    @Test fun sourceRootAndIgnoreChangesImmediatelyRemoveOldRowsFromAlbums() {
        val source = SourceEntity(id = "a", name = "NAS", host = "nas", share = "media", rootPath = "Photos")
        val trip = photo("a", "Photos/Trip/a.jpg")
        val old = photo("a", "Old/Trip/b.jpg")
        assertEquals(listOf(trip), photoAlbums(listOf(trip, old), listOf(source)).single().photos)
        assertTrue(photoAlbums(listOf(trip), listOf(source.copy(ignoredFolders = listOf("Trip")))).isEmpty())
        assertTrue(photoAlbums(listOf(trip), listOf(source.copy(rootPath = "Family"))).isEmpty())
        assertTrue(photoAlbums(listOf(trip), emptyList()).isEmpty())
    }
}
