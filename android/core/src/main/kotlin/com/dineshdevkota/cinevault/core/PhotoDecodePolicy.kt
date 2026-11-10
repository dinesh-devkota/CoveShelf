package com.dineshdevkota.cinevault.core

import kotlin.math.min
import kotlin.math.sqrt

/** Bounds are checked before decoding; even panoramas and malformed headers have fixed budgets. */
object PhotoDecodePolicy {
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_SOURCE_PIXELS = 200_000_000L
    const val MAX_DECODED_PIXELS = 4_194_304L
    const val THUMBNAIL_EDGE = 512
    const val VIEWER_EDGE = 4096
    data class Size(val width: Int, val height: Int)

    fun targetSize(width: Int, height: Int, maxEdge: Int): Size {
        require(width in 1..65_535 && height in 1..65_535 && width.toLong() * height <= MAX_SOURCE_PIXELS) {
            "This photo's dimensions are too large or invalid."
        }
        require(maxEdge in 1..VIEWER_EDGE)
        val scale = min(1.0, min(maxEdge.toDouble() / maxOf(width, height),
            sqrt(MAX_DECODED_PIXELS.toDouble() / (width.toLong() * height))))
        return Size((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    fun sampleSize(width: Int, height: Int, target: Size): Int {
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > target.width ||
            (height.toLong() + sample - 1) / sample > target.height) sample *= 2
        return sample
    }
}
