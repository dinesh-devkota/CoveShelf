package com.dineshdevkota.cinevault.core

import org.junit.Assert.*
import org.junit.Test

class PhotoDecodePolicyTest {
    @Test fun largeImagesAndPanoramasFitBothDimensionAndMemoryBudgets() {
        for ((width, height) in listOf(12_000 to 8_000, 60_000 to 1_000, 1_000 to 60_000, 96 to 64)) {
            for (edge in listOf(512, 4096)) {
                val size = PhotoDecodePolicy.targetSize(width, height, edge)
                assertTrue(size.width in 1..edge && size.height in 1..edge)
                assertTrue(size.width.toLong() * size.height <= PhotoDecodePolicy.MAX_DECODED_PIXELS)
                assertTrue(size.width <= width && size.height <= height)
                val sample = PhotoDecodePolicy.sampleSize(width, height, size)
                assertEquals(0, sample and (sample - 1))
                assertTrue((width.toLong() + sample - 1) / sample <= size.width)
                assertTrue((height.toLong() + sample - 1) / sample <= size.height)
            }
        }
        assertEquals(PhotoDecodePolicy.Size(96, 64), PhotoDecodePolicy.targetSize(96, 64, 512))
    }

    @Test fun invalidAndExcessiveHeadersAreRejectedBeforePixelAllocation() {
        for ((width, height) in listOf(0 to 64, -1 to 64, Int.MAX_VALUE to 100, 30_000 to 30_000)) {
            assertTrue(runCatching { PhotoDecodePolicy.targetSize(width, height, 512) }.isFailure)
        }
    }
}
