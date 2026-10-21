package com.y2yy.codex.mobile.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.Session
import com.y2yy.codex.mobile.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Isolated history/image UI regressions; no ViewModel, network or business operations. */
@RunWith(AndroidJUnit4::class)
class HistoryNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dataUrlImageActuallyRendersKnownRedPixels() {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.RED) }
        val png = ByteArrayOutputStream().use { buffer ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
            buffer.toByteArray()
        }
        bitmap.recycle()
        val dataUrl = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        val session = Session("pixel-fixture", "图片像素回归测试")
        val state = AppUiState(
            loading = false, connected = true, connectionText = "已连接", selected = session,
            messages = listOf(ChatMessage("red-image", "user", "", images = listOf(dataUrl)))
        )
        compose.setContent { NativeApp(state, actions()) }
        // Existence of the image node alone cannot prove Coil fetched/decoded the data URL.
        // A red center pixel requires the actual image to have reached the rendered surface.
        compose.waitUntil(15_000) {
            if (compose.onAllNodesWithContentDescription("消息图片").fetchSemanticsNodes().isEmpty()) false
            else {
                val pixels = compose.onNodeWithContentDescription("消息图片").captureToImage().toPixelMap()
                if (pixels.width == 0 || pixels.height == 0) false
                else pixels[pixels.width / 2, pixels.height / 2].let { it.alpha > .9f && it.red > .9f && it.green < .1f && it.blue < .1f }
            }
        }
        compose.onNodeWithText("历史图片加载失败").assertDoesNotExist()
    }

    @Test fun deferredTurnLoadsOnceAggregatesAndAllowsExplicitRetry() {
        val turnId = "history-fixture-turn"
        val session = Session("history-fixture", "历史回归测试")
        val ui = mutableStateOf(AppUiState(
            loading = false, connected = true, connectionText = "已连接", selected = session,
            messages = listOf(
                ChatMessage("old-question", "user", "此前的检查任务"),
                ChatMessage("deferred-1", "assistant", "", turnId = turnId, deferred = true, deferredToolCount = 2, deferredFileCount = 1),
                ChatMessage("deferred-2", "assistant", "此前的说明文字", turnId = turnId, deferred = true, deferredToolCount = 1, deferredFileCount = 2),
                ChatMessage("old-answer", "assistant", "此前任务已经结束。")
            )
        ))
        val requests = AtomicInteger()
        val callbacks = actions(onHistory = { id ->
            assertEquals(turnId, id)
            requests.incrementAndGet()
            ui.value = ui.value.copy(loadingHistoryTurns = setOf(id), historyErrors = emptyMap())
        })
        compose.setContent { NativeApp(ui.value, callbacks) }
        compose.onNodeWithContentDescription("会话选项").performClick()
        compose.onNodeWithText("查看历史消息").performClick()
        compose.waitUntil(5_000) { requests.get() == 1 }
        compose.onAllNodesWithText("正在加载历史执行过程").assertCountEquals(1)
        compose.onNodeWithText("3 次工具调用 · 3 项文件操作").assertIsDisplayed()

        compose.runOnIdle {
            ui.value = ui.value.copy(loadingHistoryTurns = emptySet(), historyErrors = mapOf(turnId to "测试加载失败"))
        }
        compose.onNodeWithText("加载失败：测试加载失败").assertIsDisplayed()
        compose.runOnIdle { assertEquals("An error must not automatically retry", 1, requests.get()) }
        compose.onNodeWithText("重试加载历史过程").performClick()
        compose.waitUntil(5_000) { requests.get() == 2 }

        compose.runOnIdle {
            ui.value = ui.value.copy(
                loadingHistoryTurns = emptySet(), historyErrors = emptyMap(),
                messages = listOf(
                    ChatMessage("old-question", "user", "此前的检查任务"),
                    ChatMessage("history-result", "assistant", "已加载历史步骤", turnId = turnId, tools = listOf(ToolCall("old-exec", "exec_command", "completed", "read-only fixture", "done"))),
                    ChatMessage("old-answer", "assistant", "此前任务已经结束。")
                )
            )
        }
        compose.onNodeWithText("历史执行过程").assertDoesNotExist()
        compose.onNodeWithText("正在加载历史执行过程").assertDoesNotExist()
        compose.onNodeWithText("exec_command").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, requests.get()) }
    }

    @Test fun historicalImageFailureOffersRetryAndReturnsToLoading() {
        val session = Session("image-fixture", "历史图片回归测试")
        val message = ChatMessage("historical-image", "user", "", imageError = "图片预览已失效")
        val ui = mutableStateOf(AppUiState(loading = false, connected = true, connectionText = "已连接", selected = session, messages = listOf(message)))
        val retried = AtomicReference<String>()
        val callbacks = actions(onRetryImage = { id ->
            retried.set(id)
            ui.value = ui.value.copy(messages = listOf(message.copy(imageError = null, imagePending = true)))
        })
        compose.setContent { NativeApp(ui.value, callbacks) }
        compose.onNodeWithText("历史图片加载失败").assertIsDisplayed()
        compose.onNodeWithText("图片预览已失效").assertIsDisplayed()
        compose.onNodeWithText("重试加载图片").performClick()
        compose.waitUntil(5_000) { retried.get() == message.id }
        compose.onNodeWithText("正在加载图片").assertIsDisplayed()
        compose.onNodeWithText("历史图片加载失败").assertDoesNotExist()
    }

    private fun actions(onHistory: (String) -> Unit = {}, onRetryImage: (String) -> Unit = {}) = NativeActions(
        onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {},
        onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {},
        onOlder = {}, onClearError = {}, onTheme = {}, onClearCache = {}, onTool = {},
        onHistoryTurn = onHistory, onRetryImage = onRetryImage
    )
}
