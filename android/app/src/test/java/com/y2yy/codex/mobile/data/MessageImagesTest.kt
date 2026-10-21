package com.y2yy.codex.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageImagesTest {
    private val jpeg = "data:image/jpeg;base64,/9j/2Q=="
    private val png = "data:image/png;base64,iVBORw0KGgo="

    @Test
    fun structuredInputImageMatchesRealBridgeShape() {
        val content = JSONObject().put("type", "input_image").put("image_url", jpeg).put("detail", "auto")
        assertEquals(listOf(jpeg), MessageImages.collect(content))
    }

    @Test
    fun imageUrlObjectAndInlineBase64ImageAreRecognized() {
        val nested = JSONObject().put("type", "input_image").put("image_url", JSONObject().put("url", png))
        val inline = JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", "/9j/2Q==")
        assertEquals(listOf(png, jpeg), MessageImages.collect(JSONArray().put(nested).put(inline)))
    }

    @Test
    fun rawDataAndHttpsStringsAreAcceptedWithoutFetching() {
        val https = "https://example.invalid/preview/image.webp?token=synthetic"
        assertEquals(listOf(jpeg), MessageImages.collect("  $jpeg  "))
        assertEquals(listOf(https), MessageImages.collect(https))
    }

    @Test
    fun nestedArraysContentAndOutputIncludeStringSources() {
        val source = JSONObject().put("content", JSONArray().put(JSONArray()
            .put(JSONObject().put("output", JSONArray().put(jpeg).put(JSONObject().put("url", png))))))
        assertEquals(listOf(jpeg, png), MessageImages.collect(source))
    }

    @Test
    fun jsonStringContentIsParsedAndImagesDeduplicatedInOrder() {
        val source = JSONArray().put(jpeg).put(JSONObject().put("image_url", jpeg)).put(png).toString()
        assertEquals(listOf(jpeg, png), MessageImages.collect(source))
    }

    @Test
    fun stopsAtSixUniqueImagesRatherThanSixRepeatedEntries() {
        val input = JSONArray()
        repeat(8) { input.put(jpeg) }
        repeat(8) { input.put("https://example.invalid/image-$it.png") }
        assertEquals(listOf(jpeg) + (0 until 5).map { "https://example.invalid/image-$it.png" }, MessageImages.collect(input))
    }

    @Test
    fun rejectsUnsafeSchemesMalformedPayloadAndUnknownImageFormats() {
        val invalid = JSONArray()
        listOf(
            "http://example.invalid/image.png", "file:///private/image.png", "content://example/image",
            "javascript:alert(1)", "https://user:password@example.invalid/image.png", "https:///missing-host",
            "data:text/html;base64,AAAA", "data:image/svg+xml;base64,AAAA", "data:image/unknown;base64,AAAA",
            "data:image/jpeg;base64,", "data:image/jpeg;base64,!!==", "data:image/png;base64,AA=A",
            "data:image/png;base64,AAAA====", "data:image/png;base64,AAAA,AAAA"
        ).forEach(invalid::put)
        invalid.put(JSONObject().put("unknown_image", jpeg).put("text", jpeg))
        invalid.put("not valid JSON { image_url: $jpeg }")
        assertTrue(MessageImages.collect(invalid).isEmpty())
    }

    @Test
    fun deeplyNestedStructuresAndOversizedImagePayloadAreRejected() {
        var nested: Any = jpeg
        repeat(12) { nested = JSONObject().put("content", nested) }
        assertTrue(MessageImages.collect(nested).isEmpty())
        val maxBytes = 20 * 1024 * 1024
        val encodedSize = ((maxBytes + 1 + 2) / 3) * 4
        assertTrue(MessageImages.collect("data:image/jpeg;base64," + "A".repeat(encodedSize)).isEmpty())
    }
}
