package com.y2yy.codex.mobile.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic foreground clipboard/camera-file tests; no MainActivity, model or server requests. */
@RunWith(AndroidJUnit4::class)
class AttachmentInputsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var clipboard: ClipboardManager
    private var originalClip: ClipData? = null
    private val created = mutableListOf<Uri>()

    @Before
    fun setUp() {
        compose.setContent {
            val current = LocalContext.current
            SideEffect { context = current }
            BasicText("Attachment input test")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            clipboard = context.getSystemService(ClipboardManager::class.java)!!
            originalClip = clipboard.primaryClip
        }
    }

    @After
    fun tearDown() {
        if (::clipboard.isInitialized) compose.runOnIdle {
            originalClip?.let { clipboard.setPrimaryClip(it) } ?: clipboard.clearPrimaryClip()
        }
        if (::context.isInitialized) created.distinct().forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    @Test
    fun cameraFileProviderUriIsUniqueAndWritableWithoutCameraPermission() {
        val first = cameraUri()
        val second = cameraUri()
        assertEquals("content", first.scheme)
        assertEquals("${context.packageName}.attachments", first.authority)
        assertFalse(first == second)
        assertEquals("image/jpeg", context.contentResolver.getType(first))
        val bytes = imageBytes(Bitmap.CompressFormat.JPEG)
        context.contentResolver.openOutputStream(first)!!.use { it.write(bytes) }
        assertArrayEquals(bytes, context.contentResolver.openInputStream(first)!!.use { it.readBytes() })
    }

    @Test
    fun normalTextAndWebUrlsRemainText() {
        val text = "这是一段剪贴板文字\nhttps://example.invalid/project?item=1"
        setClip(ClipData.newPlainText("synthetic text", text))
        val result = read()
        assertEquals(text, result.text)
        assertTrue(result.images.isEmpty())
        assertNull(result.message)

        val url = "https://example.invalid/another"
        setClip(ClipData.newRawUri("synthetic URL", Uri.parse(url)))
        assertEquals(url, read().text)
    }

    @Test
    fun clipboardImageIsCopiedToReadablePrivateCacheWithoutUriText() {
        val source = cameraUri()
        val bytes = imageBytes(Bitmap.CompressFormat.JPEG)
        context.contentResolver.openOutputStream(source)!!.use { it.write(bytes) }
        setClip(ClipData.newUri(context.contentResolver, "synthetic image", source))
        val result = read()

        assertTrue(result.text.isEmpty())
        assertEquals(1, result.images.size)
        assertNull(result.message)
        assertEquals("image/jpeg", context.contentResolver.getType(result.images.single()))
        assertArrayEquals(bytes, context.contentResolver.openInputStream(result.images.single())!!.use { it.readBytes() })
    }

    @Test
    fun clipboardInlinePngIsDecodedToFileInsteadOfPastedAsText() {
        val bytes = imageBytes(Bitmap.CompressFormat.PNG)
        val data = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        setClip(ClipData.newPlainText("synthetic inline image", data))
        val result = read()
        assertEquals("", result.text)
        assertEquals(1, result.images.size)
        assertNull(result.message)
        assertEquals("image/png", context.contentResolver.getType(result.images.single()))
        assertArrayEquals(bytes, context.contentResolver.openInputStream(result.images.single())!!.use { it.readBytes() })
    }

    @Test
    fun malformedImageAndInaccessibleProviderReturnNoticeWithoutThrowing() {
        val fakeImage = cameraUri()
        context.contentResolver.openOutputStream(fakeImage)!!.use { it.write("not an image".toByteArray()) }
        setClip(ClipData.newUri(context.contentResolver, "synthetic invalid image", fakeImage))
        val invalid = read()
        assertTrue(invalid.images.isEmpty())
        assertEquals("", invalid.text)
        assertNotNull(invalid.message)

        setClip(ClipData.newRawUri("synthetic unavailable", Uri.parse("content://invalid.example.provider/absent.jpg")))
        val inaccessible = read()
        assertTrue(inaccessible.images.isEmpty())
        assertNotNull(inaccessible.message)
    }

    @Test
    fun clipboardAcceptsAtMostSixImagesAndReportsTheLimit() {
        val bytes = imageBytes(Bitmap.CompressFormat.JPEG)
        val images = (0 until 7).map {
            cameraUri().also { uri ->
                context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            }
        }
        val clip = ClipData.newUri(context.contentResolver, "synthetic multiple images", images.first())
        images.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        setClip(clip)
        val result = read()
        assertEquals(6, result.images.size)
        assertTrue(result.text.isEmpty())
        assertNotNull(result.message)
    }

    private fun setClip(clip: ClipData) = compose.runOnIdle { clipboard.setPrimaryClip(clip) }

    private fun read(): ClipboardInput = AttachmentInputs.readClipboard(context).also { created += it.images }

    private fun cameraUri(): Uri = AttachmentInputs.newCameraUri(context).also { created += it }

    private fun imageBytes(format: Bitmap.CompressFormat): ByteArray {
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return ByteArrayOutputStream().use { output ->
            image.compress(format, 90, output)
            image.recycle()
            output.toByteArray()
        }
    }
}
