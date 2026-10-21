package com.y2yy.codex.mobile.data

import java.util.Base64
import java.util.Locale

private const val MAX_IMAGE_DATA_BYTES = 20 * 1024 * 1024
private const val MAX_IMAGE_DATA_ENCODED_CHARS = ((MAX_IMAGE_DATA_BYTES + 2) / 3) * 4
private const val MAX_IMAGE_DATA_HEADER_CHARS = 96

private val rasterImageTypes = setOf(
    "image/png",
    "image/jpeg",
    "image/jpg",
    "image/webp",
    "image/gif",
    "image/bmp",
    "image/avif",
    "image/heic",
    "image/heif",
    "image/x-icon",
    "image/vnd.microsoft.icon"
)

/**
 * Converts inline raster image data to a Coil-supported ByteArray model.
 *
 * Coil 2 does not fetch `data:` URLs itself. Reject unknown/active MIME types, invalid
 * Base64 and payloads over 20 MiB before decoding; bitmap validation remains Coil's job.
 * This function intentionally does not log its input or place it in exception messages.
 */
fun decodeImageDataUrl(value: String): ByteArray? {
    if (value.length > MAX_IMAGE_DATA_ENCODED_CHARS + MAX_IMAGE_DATA_HEADER_CHARS) return null
    if (!value.startsWith("data:", ignoreCase = true)) return null

    val comma = value.indexOf(',')
    if (comma !in 1..MAX_IMAGE_DATA_HEADER_CHARS) return null
    val header = value.substring(5, comma).lowercase(Locale.ROOT)
    if (!header.endsWith(";base64")) return null
    if (header.removeSuffix(";base64") !in rasterImageTypes) return null

    val start = comma + 1
    val encodedLength = value.length - start
    if (encodedLength == 0 || encodedLength > MAX_IMAGE_DATA_ENCODED_CHARS) return null
    // RFC 4648 Base64 in a data URL is padded to complete four-character groups.
    if (encodedLength % 4 != 0) return null
    val padding = when {
        value.endsWith("==") -> 2
        value.endsWith("=") -> 1
        else -> 0
    }
    val decodedLength = encodedLength.toLong() / 4 * 3 - padding
    if (decodedLength !in 1..MAX_IMAGE_DATA_BYTES.toLong()) return null

    return try {
        Base64.getDecoder().decode(value.substring(start)).takeIf {
            it.size == decodedLength.toInt() && it.size <= MAX_IMAGE_DATA_BYTES
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}
