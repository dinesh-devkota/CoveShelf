package com.dineshdevkota.cinevault.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import com.dineshdevkota.cinevault.core.MediaFile
import com.dineshdevkota.cinevault.core.PhotoDecodePolicy
import com.dineshdevkota.cinevault.core.ScanExclusions
import com.dineshdevkota.cinevault.data.CineVaultDatabase
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.data.SecretBox
import com.dineshdevkota.cinevault.data.SourceEntity
import com.dineshdevkota.cinevault.smb.SmbService
import com.dineshdevkota.cinevault.smb.SourceConfig
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class PhotoLoadException(message: String) : Exception(message)

/** Local-only image loading. Neither source credentials nor photo bytes enter an HTTP/image URL. */
class NasPhotoLoader(context: Context, private val database: CineVaultDatabase,
    private val secrets: SecretBox, private val smb: SmbService = photoService()) {
    private val cacheDirectory = File(context.cacheDir, "nas-photos").apply {
        mkdirs()
        // Only our own temporary files can survive a killed process; no persistent photo copies.
        listFiles()?.filter { it.isFile && it.name.startsWith("image-") && it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }
    private val slots = Semaphore(2)
    private class CacheKey(val photoId: String, val path: String, val source: SourceEntity, val fileSize: Long, val edge: Int) {
        override fun equals(other: Any?): Boolean = other is CacheKey && photoId == other.photoId &&
            path == other.path && source == other.source && fileSize == other.fileSize && edge == other.edge
        override fun hashCode(): Int = arrayOf(photoId, path, source, fileSize, edge).contentHashCode()
    }
    private val cache = object : LruCache<CacheKey, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: CacheKey, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun load(photoId: String, thumbnail: Boolean = false): Bitmap = withContext(Dispatchers.IO) {
        slots.withPermit {
            currentCoroutineContext().ensureActive()
            val photo = database.movies().get(photoId) ?: throw PhotoLoadException("This photo is no longer in the library.")
            val source = database.sources().get(photo.sourceId) ?: throw PhotoLoadException("This photo's NAS source was removed.")
            val config = source.config(secrets)
            validate(photo, config)
            val edge = if (thumbnail) PhotoDecodePolicy.THUMBNAIL_EDGE else PhotoDecodePolicy.VIEWER_EDGE
            // Source changes and completed rescans invalidate decoded content without putting secrets in a key.
            val key = CacheKey(photo.id, photo.filePath, source, photo.fileSize, edge)
            cache.get(key)?.let { return@withPermit it }
            val temporary = File.createTempFile("image-", ".tmp", cacheDirectory)
            var decoded: Bitmap? = null
            try {
                val loadContext = currentCoroutineContext()
                runInterruptible { smb.open(config, photo.filePath).use { remote ->
                    if (remote.length > PhotoDecodePolicy.MAX_FILE_BYTES) throw PhotoLoadException("This photo is larger than the 64 MB viewing limit.")
                    remote.stream(0).use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                loadContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > PhotoDecodePolicy.MAX_FILE_BYTES) throw PhotoLoadException("This photo is larger than the 64 MB viewing limit.")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } }
                currentCoroutineContext().ensureActive()
                decoded = if (Build.VERSION.SDK_INT >= 28) decodeModern(temporary, edge) else decodeLegacy(temporary, edge)
                currentCoroutineContext().ensureActive()
                // A source edit/ignore can finish during an SMB read. Do not display the old reply.
                val currentPhoto = database.movies().get(photoId)
                    ?: throw PhotoLoadException("This photo was removed from the library.")
                val currentSource = database.sources().get(source.id)
                    ?: throw PhotoLoadException("This photo's NAS source was removed.")
                if (currentSource.copy(name = source.name, lastScanAt = source.lastScanAt) != source ||
                    currentPhoto.sourceId != photo.sourceId || currentPhoto.filePath != photo.filePath) {
                    throw PhotoLoadException("The NAS source changed. Open this photo again.")
                }
                validate(currentPhoto, config)
                val bitmap = requireNotNull(decoded)
                if (bitmap.allocationByteCount > PhotoDecodePolicy.MAX_DECODED_PIXELS * 4) {
                    throw PhotoLoadException("This photo needs more memory than this viewer allows.")
                }
                cache.put(key, bitmap)
                decoded = null // The cache and visible composables now own this bitmap.
                bitmap
            } catch (error: OutOfMemoryError) {
                cache.evictAll()
                throw PhotoLoadException("There isn't enough memory to open this photo. Try again.")
            } finally {
                decoded?.recycle()
                temporary.delete()
            }
        }
    }

    fun clearMemory() = cache.evictAll()
    suspend fun resetSource(sourceId: String) { clearMemory(); smb.reset(sourceId) }

    private fun validate(photo: MovieEntity, config: SourceConfig) {
        if (!MediaFile.isPhoto(photo.filePath) || photo.isMissing || photo.isHidden) {
            throw PhotoLoadException("This photo is unavailable or hidden. Check the source and rescan.")
        }
        val path = ScanExclusions.normalizeFile(photo.filePath)
            ?: throw PhotoLoadException("This photo has an invalid folder path.")
        val root = config.rootPath.replace('\\', '/').trim('/')
        if (root.isNotEmpty() && !path.startsWith("$root/", ignoreCase = true)) {
            throw PhotoLoadException("This photo is outside the selected media folder. Rescan this source.")
        }
        if (!MediaFile.shouldIndex(path, photo.fileSize) ||
            ScanExclusions.isFileExcluded(path, root, config.ignoredFolders, config.ignoredFiles)) {
            throw PhotoLoadException("This photo is ignored by its source settings.")
        }
    }

    @RequiresApi(28)
    private fun decodeModern(file: File, edge: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        val size = PhotoDecodePolicy.targetSize(info.size.width, info.size.height, edge)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setTargetSize(size.width, size.height)
        // Partial/corrupt images must produce a recoverable error rather than an incomplete success.
        decoder.setOnPartialImageListener { false }
    }

    private fun decodeLegacy(file: File, edge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val size = PhotoDecodePolicy.targetSize(bounds.outWidth, bounds.outHeight, edge)
        val options = BitmapFactory.Options().apply {
            inSampleSize = PhotoDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight, size)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options)
            ?: throw PhotoLoadException("This photo's format isn't supported on this TV, or the file is damaged.")
        val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { transform.setRotate(90f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { transform.setRotate(270f); transform.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
        }
        if (transform.isIdentity) return bitmap
        return try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true) }
        finally { bitmap.recycle() }
    }

    companion object {
        /** Short photo timeouts never interrupt or reset the separate playback connection cache. */
        private fun photoService(): SmbService = SmbService(SMBClient(SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider()).withTimeout(8, TimeUnit.SECONDS)
            .withSoTimeout(10, TimeUnit.SECONDS).withReadBufferSize(64 * 1024).build()))

        fun describe(error: Throwable): String = when (error) {
            is PhotoLoadException -> error.message.orEmpty()
            is IllegalArgumentException -> "This photo's dimensions are too large or invalid."
            else -> if (Build.VERSION.SDK_INT >= 28 && error is ImageDecoder.DecodeException)
                "This photo's format isn't supported on this TV, or the file is damaged." else SmbService.describe(error)
        }
    }
}
