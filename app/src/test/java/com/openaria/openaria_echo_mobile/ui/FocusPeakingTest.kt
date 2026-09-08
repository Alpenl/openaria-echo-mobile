package com.openaria.openaria_echo_mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlin.math.abs
import kotlin.random.Random

class FocusPeakingTest {
    private val peak = 0xffe858ff.toInt()

    @Test
    fun `flat field has no focus peaks`() {
        val pixels = IntArray(7 * 5) { 0xff707070.toInt() }

        val mask = FocusPeaking.computeMask(pixels, 7, 5, peakColorArgb = peak)

        assertTrue(mask.pixels.all { it == 0 })
    }

    @Test
    fun `central difference marks a sharp vertical edge`() {
        val width = 7
        val height = 5
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            if (x < 3) 0xff000000.toInt() else 0xffffffff.toInt()
        }

        val mask = FocusPeaking.computeMask(pixels, width, height, peakColorArgb = peak)

        for (y in 1 until height - 1) {
            assertEquals(peak, mask.pixels[y * width + 2])
            assertEquals(peak, mask.pixels[y * width + 3])
        }
        assertEquals(0, mask.pixels[0])
        assertEquals(0, mask.pixels[width - 1])
    }

    @Test
    fun `threshold rejects weak contrast and admits strong contrast`() {
        val width = 5
        val pixels = intArrayOf(
            0xff646464.toInt(), 0xff646464.toInt(), 0xff787878.toInt(), 0xff787878.toInt(), 0xff787878.toInt(),
            0xff646464.toInt(), 0xff646464.toInt(), 0xff787878.toInt(), 0xff787878.toInt(), 0xff787878.toInt(),
            0xff646464.toInt(), 0xff646464.toInt(), 0xff787878.toInt(), 0xff787878.toInt(), 0xff787878.toInt(),
        )

        val strict = FocusPeaking.computeMask(pixels, width, 3, threshold = 50, peakColorArgb = peak)
        val permissive = FocusPeaking.computeMask(pixels, width, 3, threshold = 10, peakColorArgb = peak)

        assertTrue(strict.pixels.all { it == 0 })
        assertEquals(peak, permissive.pixels[width + 1])
    }

    @Test
    fun `compact luminance matches central differences for every output pixel`() {
        val random = Random(809)
        val dimensions = listOf(1 to 1, 2 to 7, 7 to 2, 3 to 3, 13 to 11, 640 to 480)
        for ((width, height) in dimensions) {
            val pixels = IntArray(width * height) { random.nextInt() }
            fun luminance(index: Int): Int {
                val pixel = pixels[index]
                return (77 * (pixel ushr 16 and 255) +
                    150 * (pixel ushr 8 and 255) + 29 * (pixel and 255)) ushr 8
            }
            for (threshold in listOf(0, 1, 72, 510)) {
                val expected = IntArray(pixels.size)
                for (y in 1 until height - 1) {
                    for (x in 1 until width - 1) {
                        val index = y * width + x
                        val gradient = abs(luminance(index + 1) - luminance(index - 1)) +
                            abs(luminance(index + width) - luminance(index - width))
                        if (gradient >= threshold) expected[index] = peak
                    }
                }
                val actual = FocusPeaking.computeMask(pixels, width, height, threshold, peak)
                assertContentEquals(expected, actual.pixels, "$width x $height, threshold=$threshold")
            }
        }
    }

    @Test
    fun `sampling never exceeds the processing pixel budget`() {
        val cases = listOf(640 to 480, 1920 to 1080, 3840 to 2160, 16_384 to 16_384)

        cases.forEach { (width, height) ->
            val sample = FocusPeaking.sampleSizeFor(width, height)
            val sampledWidth = (width + sample - 1) / sample
            val sampledHeight = (height + sample - 1) / sample
            assertTrue(sampledWidth.toLong() * sampledHeight <= FOCUS_PROCESSING_PIXEL_BUDGET)
        }
        assertFailsWith<IllegalArgumentException> {
            FocusPeaking.computeMask(
                argb = IntArray(FOCUS_PROCESSING_PIXEL_BUDGET + 1),
                width = FOCUS_PROCESSING_PIXEL_BUDGET + 1,
                height = 1,
                peakColorArgb = peak,
            )
        }
    }

    @Test
    fun `only newest ticket in current generation may publish`() {
        val gate = PreviewFrameGate()
        val firstGeneration = gate.beginGeneration()
        val first = requireNotNull(gate.submit(firstGeneration))
        val newest = requireNotNull(gate.submit(firstGeneration))

        assertFalse(gate.shouldPublish(first))
        assertTrue(gate.shouldPublish(newest))

        val secondGeneration = gate.beginGeneration()
        assertFalse(gate.shouldPublish(newest))
        val current = requireNotNull(gate.submit(secondGeneration))
        assertTrue(gate.shouldPublish(current))

        gate.invalidatePending(secondGeneration)
        assertFalse(gate.shouldPublish(current))
    }

    @Test
    fun `slow preview response cannot publish after background or device switch`() {
        val gate = PreviewFrameGate()
        val foregroundGeneration = gate.beginGeneration()
        val slowForegroundFrame = requireNotNull(gate.submit(foregroundGeneration))

        val resumedGeneration = gate.beginGeneration()

        assertFalse(gate.shouldPublish(slowForegroundFrame), "backgrounding must invalidate an in-flight frame")
        val oldDeviceFrame = requireNotNull(gate.submit(resumedGeneration))

        gate.beginGeneration()

        assertFalse(gate.shouldPublish(oldDeviceFrame), "switching devices must reject the previous response")
    }
}
