package com.y2yy.codex.mobile.data

import java.net.URI
import java.util.Locale
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Extracts supported image sources from bridge message/tool content, without fetching or logging. */
object MessageImages {
    private const val MAX_IMAGES = 6
    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 4_096
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_ENCODED = ((MAX_BYTES + 2) / 3) * 4
    private const val MAX_DATA_URL = MAX_ENCODED + 97
    private const val MAX_JSON_STRING = 32 * 1024 * 1024
    private val imageMime = Regex("image/(?:png|jpeg|jpg|webp|gif|bmp|avif|heic|heif|x-icon|vnd\\.microsoft\\.icon)")

    fun collect(raw: Any?): List<String> {
        val images = linkedSetOf<String>()
        var remainingNodes = MAX_NODES

        fun add(value: String) {
            if (images.size >= MAX_IMAGES || value.length > MAX_DATA_URL) return
            val candidate = value.trim()
            if (validDataImage(candidate) || validHttps(candidate)) images += candidate
        }

        fun visit(value: Any?, depth: Int) {
            if (depth > MAX_DEPTH || images.size >= MAX_IMAGES || --remainingNodes < 0) return
            when (value) {
                is JSONObject -> {
                    for (key in listOf("url", "data_url")) (value.opt(key) as? String)?.let(::add)
                    when (val imageUrl = value.opt("image_url")) {
                        is String -> add(imageUrl)
                        is JSONObject -> visit(imageUrl, depth + 1)
                    }
                    if (value.optString("type") in listOf("image", "input_image")) {
                        val source = value.optJSONObject("source")?.takeIf { it.optString("type") == "base64" }
                        val data = (value.opt("data") as? String) ?: (source?.opt("data") as? String)
                        if (!data.isNullOrBlank() && data.length <= MAX_ENCODED) {
                            val mime = (value.opt("mimeType") as? String)
                                ?: (value.opt("mime_type") as? String) ?: (source?.opt("media_type") as? String) ?: "image/png"
                            add("data:$mime;base64,$data")
                        }
                    }
                    if (value.optString("type") == "imageGeneration") {
                        (value.opt("result") as? String)?.let { generatedImage(it)?.let(::add) }
                    }
                    // Some MCP results put structured content in an explicitly typed text
                    // item. A plain text field or a displayed data URL is not image media.
                    if (value.optString("type") in listOf("text", "input_text")) {
                        (value.opt("text") as? String)?.trim()?.takeIf { it.startsWith('{') || it.startsWith('[') }
                            ?.let { visit(it, depth + 1) }
                    }
                    for (key in listOf("content", "contentItems", "output", "result", "results", "item", "media", "images", "attachments")) visit(value.opt(key), depth + 1)
                }
                is JSONArray -> {
                    for (index in 0 until minOf(value.length(), MAX_NODES)) {
                        visit(value.opt(index), depth + 1)
                        if (images.size >= MAX_IMAGES || remainingNodes < 0) break
                    }
                }
                is String -> {
                    if (value.length > MAX_JSON_STRING) return
                    val text = value.trim()
                    when {
                        text.startsWith("data:", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> add(text)
                        text.startsWith('{') || text.startsWith('[') -> {
                            val parsed = runCatching {
                                val reader = JSONTokener(text)
                                val item = reader.nextValue()
                                item.takeIf { reader.nextClean() == '\u0000' }
                            }.getOrNull()
                            if (parsed is JSONObject || parsed is JSONArray) visit(parsed, depth + 1)
                        }
                    }
                }
            }
        }

        visit(raw, 0)
        return images.toList()
    }

    /** Only a typed image-generation result may infer MIME from raw base64 bytes. */
    private fun generatedImage(value: String): String? {
        if (value.startsWith("data:image/", ignoreCase = true)) return value
        if (value.isEmpty() || value.length > MAX_ENCODED || value.length % 4 != 0) return null
        val header = runCatching { Base64.getDecoder().decode(value.take(32)) }.getOrNull() ?: return null
        val signature = String(header, Charsets.ISO_8859_1)
        val mime = when {
            header.size >= 8 && header.take(8) == listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x1a.toByte(), 0x0a.toByte()) -> "image/png"
            header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "image/jpeg"
            signature.startsWith("GIF87a") || signature.startsWith("GIF89a") -> "image/gif"
            signature.length >= 12 && signature.startsWith("RIFF") && signature.substring(8, 12) == "WEBP" -> "image/webp"
            signature.length >= 12 && signature.substring(4, 8) == "ftyp" && signature.substring(8, 12) in setOf("avif", "avis") -> "image/avif"
            else -> return null
        }
        return "data:$mime;base64,$value"
    }

    private fun validHttps(value: String): Boolean {
        if (!value.startsWith("https://", ignoreCase = true) || value.length > 64 * 1024) return false
        return runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        }.getOrDefault(false)
    }

    private fun validDataImage(value: String): Boolean {
        if (!value.startsWith("data:", ignoreCase = true) || value.length > MAX_DATA_URL) return false
        val comma = value.indexOf(',')
        if (comma !in 5..96) return false
        val header = value.substring(5, comma).lowercase(Locale.ROOT)
        if (!header.endsWith(";base64") || !imageMime.matches(header.removeSuffix(";base64"))) return false
        val payloadStart = comma + 1
        val size = value.length - payloadStart
        if (size == 0 || size > MAX_ENCODED || size % 4 != 0) return false
        val padding = if (value.endsWith("==")) 2 else if (value.endsWith('=')) 1 else 0
        if (size.toLong() / 4 * 3 - padding !in 1..MAX_BYTES.toLong()) return false
        val end = value.length - padding
        for (index in payloadStart until end) {
            val char = value[index]
            if (char !in 'A'..'Z' && char !in 'a'..'z' && char !in '0'..'9' && char != '+' && char != '/') return false
        }
        return true
    }
}
