package com.openaria.openaria_echo_mobile.body.api

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val PREVIEW_JPEG_BYTE_LIMIT = 8 * 1024 * 1024

/** Also bounds chunked responses, where Content-Length cannot protect the reader. */
internal fun InputStream.readPreviewJpeg(): ByteArray? {
    val output = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer, 0, minOf(buffer.size, PREVIEW_JPEG_BYTE_LIMIT - output.size() + 1))
        if (read < 0) return output.toByteArray()
        if (read > PREVIEW_JPEG_BYTE_LIMIT - output.size()) return null
        output.write(buffer, 0, read)
    }
}
