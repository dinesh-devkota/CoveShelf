package com.dineshdevkota.cinevault.photos

import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.core.ScanExclusions
import java.util.Locale

data class PhotoAlbum(val id: String, val sourceId: String, val folder: String, val photos: List<MovieEntity>) {
    val title: String get() = folder.substringAfterLast('/').ifBlank { "Shared folder" }
}

fun photoAlbumId(photo: MovieEntity): String = "${photo.sourceId}:${photo.filePath.replace('\\', '/').substringBeforeLast('/', "") }"

/** Photos from different NAS sources are never merged just because their folder names match. */
fun photoAvailable(photo: MovieEntity, source: SourceEntity?): Boolean {
    if (source == null || !photo.isPhoto || photo.isHidden || photo.isMissing) return false
    val path = ScanExclusions.normalizeFile(photo.filePath) ?: return false
    val root = if (source.rootPath.isBlank()) "" else ScanExclusions.normalizeFolder(source.rootPath) ?: return false
    return (root.isEmpty() || path.startsWith("$root/", ignoreCase = true)) &&
        !ScanExclusions.isFileExcluded(path, root, source.ignoredFolders, source.ignoredFiles)
}

fun photoAlbums(items: List<MovieEntity>, sources: List<SourceEntity>? = null): List<PhotoAlbum> {
    val sourceById = sources?.associateBy { it.id }
    return items.asSequence()
    .filter { it.isPhoto && !it.isHidden && !it.isMissing && (sourceById == null || photoAvailable(it, sourceById[it.sourceId])) }
    .groupBy(::photoAlbumId)
    .map { (id, photos) ->
        PhotoAlbum(id, photos.first().sourceId, photos.first().filePath.replace('\\', '/').substringBeforeLast('/', ""),
            photos.sortedWith(compareBy<MovieEntity> { it.fileName.lowercase(Locale.ROOT) }.thenBy { it.id }))
    }.sortedWith(compareBy<PhotoAlbum> { it.title.lowercase(Locale.ROOT) }.thenBy { it.sourceId }.thenBy { it.folder })
}
