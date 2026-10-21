package com.y2yy.codex.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.Session
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic image pixels only; private uploaded screenshots are never copied into tests. */
@RunWith(AndroidJUnit4::class)
class UploadedImageRenderingTest {
    @get:Rule val compose = createComposeRule()
    private var previousLoader: ImageLoader? = null
    private var fixtureLoader: ImageLoader? = null
    private var pendingFetch: CompletableDeferred<Unit>? = null

    @After fun restoreImageLoader() {
        pendingFetch?.complete(Unit)
        previousLoader?.let { Coil.setImageLoader(it) }
        fixtureLoader?.shutdown()
    }

    @Test fun portraitJpegShowsPixelsAndOpensReadableFullScreenPreview() {
        val bitmap = Bitmap.createBitmap(368, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(AndroidColor.WHITE)
            drawRect(0f, 200f, 368f, 600f, Paint().apply { color = AndroidColor.BLUE })
        }
        val bytes = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream); stream.toByteArray() }
        bitmap.recycle()
        val url = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        compose.setContent { NativeApp(fixture(url), actions()) }
        waitForPixel("消息图片", blue = true)
        val thumbnail = compose.onNodeWithContentDescription("消息图片")
        val bounds = thumbnail.fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("A portrait screenshot must use a bounded thumbnail", bounds.height <= 260f * density + 2f)
        thumbnail.performClick()
        waitForPixel("图片大图预览", blue = true)
        val large = compose.onNodeWithContentDescription("图片大图预览").fetchSemanticsNode().boundsInRoot
        assertTrue("Full-screen preview must offer more image space", large.height > bounds.height)
        compose.onNodeWithContentDescription("关闭图片预览").performClick()
        thumbnail.assertIsDisplayed()
    }

    @Test fun sameUriCanRetryLocallyAndLoadingNeverBecomesBlank() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fetchCount = AtomicInteger()
        val retryCount = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        pendingFetch = release
        previousLoader = Coil.imageLoader(context)
        val factory = object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                if (data.scheme != "native-image-fixture") return null
                return object : Fetcher {
                    override suspend fun fetch(): FetchResult {
                        if (fetchCount.incrementAndGet() == 1) throw IOException("Synthetic first image failure")
                        release.await()
                        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.RED) }
                        return DrawableResult(BitmapDrawable(context.resources, bitmap), false, DataSource.MEMORY)
                    }
                }
            }
        }
        fixtureLoader = ImageLoader.Builder(context).components { add(factory) }.build().also { Coil.setImageLoader(it) }
        // The callback deliberately leaves the exact URI unchanged; retry must work within MessageImage.
        compose.setContent { NativeApp(fixture("native-image-fixture://same-uri"), actions(onRetry = { retryCount.incrementAndGet() })) }
        compose.waitUntil(10_000) { fetchCount.get() >= 1 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("历史图片加载失败").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("历史图片加载失败").assertIsDisplayed()
        compose.onNodeWithText("重试加载图片").performClick()
        compose.waitUntil(10_000) { fetchCount.get() >= 2 }
        compose.onNodeWithTag("image-loading").assertIsDisplayed()
        compose.onNodeWithText("正在加载图片").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retryCount.get()); release.complete(Unit) }
        waitForPixel("消息图片", blue = false)
        compose.onNodeWithText("历史图片加载失败").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, fetchCount.get()) }
    }

    private fun waitForPixel(description: String, blue: Boolean) {
        compose.waitUntil(15_000) {
            if (compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isEmpty()) false
            else {
                val pixels = compose.onNodeWithContentDescription(description).captureToImage().toPixelMap()
                if (pixels.width == 0 || pixels.height == 0) false
                else pixels[pixels.width / 2, pixels.height / 2].let {
                    it.alpha > .9f && it.green < .2f && if (blue) it.blue > .8f && it.red < .2f else it.red > .8f && it.blue < .2f
                }
            }
        }
    }

    private fun fixture(image: String) = AppUiState(
        loading = false, connected = true, connectionText = "已连接", darkTheme = true,
        selected = Session("uploaded-image-fixture", "上传图片回归"),
        messages = listOf(ChatMessage("uploaded-image", "user", "合成截图", images = listOf(image)))
    )

    private fun actions(onRetry: (String) -> Unit = {}) = NativeActions(
        onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {},
        onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {},
        onOlder = {}, onClearError = {}, onTheme = {}, onClearCache = {}, onTool = {}, onRetryImage = onRetry
    )
}
