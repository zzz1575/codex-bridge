package com.y2yy.codex.mobile.data

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID

data class ClipboardInput(
    val text: String = "",
    val images: List<Uri> = emptyList(),
    val message: String? = null
)

/** Explicit user actions only. This object never monitors the clipboard in the background. */
object AttachmentInputs {
    private const val MAX_IMAGES = 6
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    private const val MAX_PIXELS = 100_000_000L
    private const val MAX_CLIP_ITEMS = 64
    private const val MAX_TEXT_CHARS = 1_000_000

    /** For ACTION_IMAGE_CAPTURE/TakePicture. The external camera receives this grantable URI. */
    fun newCameraUri(context: Context): Uri {
        val file = File.createTempFile("camera-", ".jpg", capturesDirectory(context))
        return try {
            fileUri(context, file)
        } catch (failure: RuntimeException) {
            file.delete()
            throw IllegalStateException("无法准备拍照附件，请重新打开应用后重试。", failure)
        }
    }

    /**
     * Call from the paste button on Dispatchers.IO: provider reads and image validation may block.
     * Images are copied to private cache so later clipboard changes cannot revoke their access.
     */
    fun readClipboard(context: Context): ClipboardInput {
        val clip = try {
            context.getSystemService(ClipboardManager::class.java)?.primaryClip
        } catch (_: RuntimeException) {
            return ClipboardInput(message = "暂时无法读取剪贴板，请回到应用后重新点击粘贴。")
        } ?: return ClipboardInput(message = "剪贴板为空，或当前无法读取，请复制内容后重试。")

        val images = mutableListOf<Uri>()
        val texts = linkedSetOf<String>()
        val seenImages = hashSetOf<String>()
        val notices = linkedSetOf<String>()
        var textLength = 0

        fun addText(value: String) {
            if (value.isEmpty() || value in texts) return
            if (value.length > MAX_TEXT_CHARS - textLength) {
                notices += "剪贴板文字过长，请分段粘贴。"
            } else {
                texts += value
                textLength += value.length
            }
        }

        fun addImage(source: String, read: () -> Uri) {
            if (source in seenImages) return
            seenImages += source
            if (images.size >= MAX_IMAGES) {
                notices += "一次最多粘贴 6 张图片，其余图片未添加。"
                return
            }
            try {
                images += read()
            } catch (failure: InvalidAttachment) {
                notices += failure.notice
            } catch (_: SecurityException) {
                notices += "没有权限读取剪贴板图片，请重新复制或使用本地上传。"
            } catch (_: Exception) {
                notices += "无法读取剪贴板图片，请重新复制或使用本地上传。"
            }
        }

        fun readDataImage(value: String) {
            addImage(value) {
                val bytes = decodeImageDataUrl(value)
                    ?: throw InvalidAttachment("剪贴板图片格式无效，或超过 20 MB。")
                copyImage(context, ByteArrayInputStream(bytes))
            }
        }

        fun readUri(uri: Uri): Boolean {
            val normalized = uri.normalizeScheme()
            return when (normalized.scheme) {
                "data" -> {
                    readDataImage(normalized.toString())
                    true
                }
                "content", "file" -> {
                    addImage(normalized.toString()) {
                        val mime = context.contentResolver.getType(normalized)
                            ?: if (normalized.scheme == "file") {
                                android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(File(normalized.path.orEmpty()).extension.lowercase(Locale.ROOT))
                            } else clip.description.filterMimeTypes("image/*")?.firstOrNull()
                        if (!mime.orEmpty().startsWith("image/", ignoreCase = true)) {
                            throw InvalidAttachment("剪贴板附件不是图片，请复制图片或粘贴文字。")
                        }
                        val stream = context.contentResolver.openInputStream(normalized)
                            ?: throw InvalidAttachment("无法读取剪贴板图片，请重新复制或使用本地上传。")
                        copyImage(context, stream)
                    }
                    true
                }
                else -> false
            }
        }

        for (index in 0 until minOf(clip.itemCount, MAX_CLIP_ITEMS)) {
            val item = clip.getItemAt(index)
            val uri = item.uri
            val uriHandled = uri?.let(::readUri) ?: false
            val text = item.text?.toString()
            if (text != null) {
                val trimmed = text.trim()
                when {
                    uriHandled && trimmed == uri.toString() -> Unit
                    trimmed.startsWith("data:", ignoreCase = true) -> readDataImage(trimmed)
                    uri == null && (trimmed.startsWith("content://", ignoreCase = true) ||
                        trimmed.startsWith("file://", ignoreCase = true)) -> readUri(Uri.parse(trimmed))
                    else -> addText(text)
                }
            } else if (uri != null && !uriHandled) {
                // A copied normal web URL remains text; it is never fetched implicitly.
                addText(uri.toString())
            }
        }
        if (clip.itemCount > MAX_CLIP_ITEMS) notices += "剪贴板项目过多，仅处理前 64 项。"
        return ClipboardInput(texts.joinToString("\n"), images, notices.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }

    private fun copyImage(context: Context, source: InputStream): Uri {
        var file: File? = null
        try {
            return source.use { input ->
                val directory = capturesDirectory(context)
                var currentFile = File.createTempFile("clipboard-", ".image", directory)
                file = currentFile
                currentFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_IMAGE_BYTES) throw InvalidAttachment("剪贴板图片超过 20 MB，请压缩后再粘贴。")
                        output.write(buffer, 0, count)
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(currentFile.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                    bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_PIXELS) {
                    throw InvalidAttachment("剪贴板图片格式无效或尺寸过大，请重新选择图片。")
                }
                val extension = when (bounds.outMimeType?.lowercase(Locale.ROOT)) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    "image/bmp" -> "bmp"
                    "image/avif" -> "avif"
                    "image/heic" -> "heic"
                    "image/heif" -> "heif"
                    "image/x-icon", "image/vnd.microsoft.icon" -> "ico"
                    else -> throw InvalidAttachment("暂不支持此图片格式，请转换为 PNG、JPEG 或 WebP。")
                }
                val namedFile = File(directory, "clipboard-${UUID.randomUUID()}.$extension")
                if (!currentFile.renameTo(namedFile)) throw InvalidAttachment("无法保存剪贴板图片，请稍后重试。")
                currentFile = namedFile
                file = currentFile
                fileUri(context, currentFile)
            }
        } catch (failure: Exception) {
            file?.delete()
            throw failure
        }
    }

    private fun capturesDirectory(context: Context): File = File(context.cacheDir, "captures").apply {
        if (!isDirectory && !mkdirs()) throw IllegalStateException("无法创建图片附件缓存。")
    }

    private fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)

    private class InvalidAttachment(val notice: String) : Exception(notice)
}
