package com.y2yy.codex.mobile.data

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageDataTest {
    @Test
    fun decodesRealInlinePngToBytes() {
        val encoded = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6NFwAAAAASUVORK5CYII="
        assertArrayEquals(Base64.getDecoder().decode(encoded), decodeImageDataUrl("data:image/png;base64,$encoded"))
    }

    @Test
    fun supportsWebpAndCaseInsensitiveMimeHeader() {
        val webpHeader = byteArrayOf(82, 73, 70, 70, 4, 0, 0, 0, 87, 69, 66, 80)
        val encoded = Base64.getEncoder().encodeToString(webpHeader)
        assertArrayEquals(webpHeader, decodeImageDataUrl("DATA:IMAGE/WEBP;BASE64,$encoded"))
    }

    @Test
    fun rejectsNonImageActiveContentAndUnrelatedUrls() {
        val payload = Base64.getEncoder().encodeToString("synthetic payload".toByteArray())
        listOf(
            "https://example.invalid/image.png",
            "content://synthetic/image/1",
            "data:text/html;base64,$payload",
            "data:application/octet-stream;base64,$payload",
            "data:image/svg+xml;base64,$payload",
            "data:image/unknown;base64,$payload"
        ).forEach { assertNull(decodeImageDataUrl(it)) }
    }

    @Test
    fun rejectsMalformedHeadersAndBase64WithoutThrowing() {
        listOf(
            "",
            "data:",
            "data:,",
            "data:image/png;base64,",
            "data:image/png,AAAA",
            "data:image/png;charset=utf-8;base64,AAAA",
            "data:image/png;base64AAAA",
            "data:image/png;base64,!!!!",
            "data:image/png;base64,AA=A",
            "data:image/png;base64,AAAA====",
            "data:image/png;base64,SGVsbG8_",
            "data:image/png;base64,SGVs\nbG8=",
            "data:image/png;base64,AA",
            "data:image/png;base64,AAAA,AAAA"
        ).forEach { assertNull(decodeImageDataUrl(it)) }
    }

    @Test
    fun rejectsOversizedDecodedPayloadBeforeAllocatingDecodedBytes() {
        val maxBytes = 20 * 1024 * 1024
        // 20 MiB + 1 bytes has the same encoded character count as exactly 20 MiB.
        // The padding-aware size guard must reject this without attempting a decode.
        val encodedChars = ((maxBytes + 1 + 2) / 3) * 4
        assertNull(decodeImageDataUrl("data:image/webp;base64," + "A".repeat(encodedChars)))
    }

    @Test
    fun acceptsExactDecodedSizeLimit() {
        val expected = ByteArray(20 * 1024 * 1024) { (it % 251).toByte() }
        val encoded = Base64.getEncoder().encodeToString(expected)
        assertArrayEquals(expected, decodeImageDataUrl("data:image/webp;base64,$encoded"))
    }
}
