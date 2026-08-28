package com.openaria.openaria_echo_mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs

internal const val FOCUS_PROCESSING_PIXEL_BUDGET = 512 * 1024
internal const val PREVIEW_JPEG_BYTE_LIMIT = 8 * 1024 * 1024
private const val PREVIEW_SOURCE_DIMENSION_LIMIT = 16_384
internal const val DEFAULT_FOCUS_PEAK_THRESHOLD = 72

internal data class FocusPeakingMask(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

internal data class PreviewVisualFrame(
    val image: ImageBitmap,
    val focusMask: ImageBitmap?,
)

internal data class PreviewFrameWork(
    val bytes: ByteArray,
    val ticket: PreviewFrameTicket,
    val includeFocusMask: Boolean,
)

internal object FocusPeaking {
    fun sampleSizeFor(
        width: Int,
        height: Int,
        pixelBudget: Int = FOCUS_PROCESSING_PIXEL_BUDGET,
    ): Int {
        require(width > 0 && height > 0)
        require(pixelBudget > 0)

        var sampleSize = 1
        while (sampledPixels(width, height, sampleSize) > pixelBudget) {
            check(sampleSize <= (1 shl 29)) { "preview dimensions exceed the supported sampling range" }
            sampleSize *= 2
        }
        return sampleSize
    }

    fun computeMask(
        argb: IntArray,
        width: Int,
        height: Int,
        threshold: Int = DEFAULT_FOCUS_PEAK_THRESHOLD,
        peakColorArgb: Int,
        pixelBudget: Int = FOCUS_PROCESSING_PIXEL_BUDGET,
    ): FocusPeakingMask {
        require(width > 0 && height > 0)
        require(width.toLong() * height.toLong() <= pixelBudget.toLong()) {
            "focus peaking input exceeds the processing pixel budget"
        }
        require(argb.size == width * height)
        require(threshold in 0..510)

        val luminance = IntArray(argb.size)
        argb.indices.forEach { index ->
            val pixel = argb[index]
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            luminance[index] = (77 * red + 150 * green + 29 * blue) ushr 8
        }

        val mask = IntArray(argb.size)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val horizontal = abs(luminance[index + 1] - luminance[index - 1])
                val vertical = abs(luminance[index + width] - luminance[index - width])
                if (horizontal + vertical >= threshold) {
                    mask[index] = peakColorArgb
                }
            }
        }
        return FocusPeakingMask(width, height, mask)
    }

    private fun sampledPixels(width: Int, height: Int, sampleSize: Int): Long {
        val sampledWidth = (width + sampleSize - 1L) / sampleSize
        val sampledHeight = (height + sampleSize - 1L) / sampleSize
        return sampledWidth * sampledHeight
    }
}

internal data class PreviewFrameTicket(
    val generation: Long,
    val sequence: Long,
)

/** Rejects both superseded frames and results from a previous preview lifecycle. */
internal class PreviewFrameGate {
    private var generation = 0L
    private var sequence = 0L
    private var latestSequence = 0L

    @Synchronized
    fun beginGeneration(): Long {
        generation += 1L
        sequence += 1L
        latestSequence = sequence
        return generation
    }

    @Synchronized
    fun submit(requestGeneration: Long): PreviewFrameTicket? {
        if (requestGeneration != generation) return null
        sequence += 1L
        latestSequence = sequence
        return PreviewFrameTicket(requestGeneration, sequence)
    }

    @Synchronized
    fun invalidatePending(requestGeneration: Long) {
        if (requestGeneration != generation) return
        sequence += 1L
        latestSequence = sequence
    }

    @Synchronized
    fun shouldPublish(ticket: PreviewFrameTicket): Boolean {
        return ticket.generation == generation && ticket.sequence == latestSequence
    }
}

internal fun decodeAndProcessPreviewFrame(
    bytes: ByteArray,
    includeFocusMask: Boolean,
    peakColorArgb: Int,
    threshold: Int = DEFAULT_FOCUS_PEAK_THRESHOLD,
): PreviewVisualFrame? {
    if (bytes.isEmpty() || bytes.size > PREVIEW_JPEG_BYTE_LIMIT) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sourceWidth = bounds.outWidth
    val sourceHeight = bounds.outHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    if (sourceWidth > PREVIEW_SOURCE_DIMENSION_LIMIT || sourceHeight > PREVIEW_SOURCE_DIMENSION_LIMIT) return null

    val sampleSize = FocusPeaking.sampleSizeFor(sourceWidth, sourceHeight)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    if (bitmap.width.toLong() * bitmap.height.toLong() > FOCUS_PROCESSING_PIXEL_BUDGET.toLong()) {
        bitmap.recycle()
        return null
    }

    val mask = if (includeFocusMask) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val focusMask = FocusPeaking.computeMask(
            argb = pixels,
            width = bitmap.width,
            height = bitmap.height,
            threshold = threshold,
            peakColorArgb = peakColorArgb,
        )
        Bitmap.createBitmap(
            focusMask.pixels,
            focusMask.width,
            focusMask.height,
            Bitmap.Config.ARGB_8888,
        ).asImageBitmap()
    } else {
        null
    }
    return PreviewVisualFrame(bitmap.asImageBitmap(), mask)
}
