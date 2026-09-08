package com.openaria.openaria_echo_mobile.body.api

import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewJpegReaderTest {
    @Test
    fun `accepts a frame at the byte limit`() {
        val bytes = ByteArray(PREVIEW_JPEG_BYTE_LIMIT) { (it % 251).toByte() }
        assertContentEquals(bytes, bytes.inputStream().readPreviewJpeg())
    }

    @Test
    fun `stops an unbounded response after one byte beyond the limit`() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int {
                consumed++
                return 0
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(0, offset, offset + length)
                consumed += length
                return length
            }
        }

        assertNull(stream.readPreviewJpeg())
        assertEquals(PREVIEW_JPEG_BYTE_LIMIT + 1, consumed)
    }

    @Test
    fun `handles short reads without truncating a frame`() {
        val bytes = ByteArray(37) { it.toByte() }
        val stream = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(3, length))
        }

        assertContentEquals(bytes, stream.readPreviewJpeg())
    }
}
