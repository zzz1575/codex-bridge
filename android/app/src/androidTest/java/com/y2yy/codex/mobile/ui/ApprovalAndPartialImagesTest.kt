package com.y2yy.codex.mobile.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.Approval
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.Session
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No runtime/approval endpoints: stale identity and partial-media presentation fixtures only. */
@RunWith(AndroidJUnit4::class)
class ApprovalAndPartialImagesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disconnectedOrReplacedApprovalClosesOldConfirmationWithoutSubmittingIt() {
        val original = Approval(19, "fixture-thread", "合成权限请求", "只读合成命令", connectionGeneration = 4)
        val ui = mutableStateOf(fixture().copy(approvals = listOf(original)))
        val calls = AtomicInteger()
        val approved = AtomicReference<Approval>()
        compose.setContent { NativeApp(ui.value, actions(onApprovalRequest = { approval, _ -> calls.incrementAndGet(); approved.set(approval) })) }
        compose.onNodeWithText("仅批准本次").performClick()
        compose.onNodeWithTag("approval-confirmation").assertIsDisplayed()
        compose.runOnIdle { ui.value = ui.value.copy(connected = false) }
        compose.onNodeWithTag("approval-confirmation").assertDoesNotExist()
        compose.runOnIdle { ui.value = ui.value.copy(connected = true, approvals = listOf(original.copy(connectionGeneration = 5))) }
        compose.onNodeWithText("仅批准本次").performClick()
        compose.onNodeWithTag("approval-confirmation").assertIsDisplayed()
        compose.runOnIdle { ui.value = ui.value.copy(approvals = listOf(original.copy(connectionGeneration = 6))) }
        compose.onNodeWithTag("approval-confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls.get()) }
        compose.onNodeWithText("仅批准本次").performClick()
        compose.onNodeWithTag("approval-confirm-submit").performClick()
        compose.runOnIdle { assertEquals(1, calls.get()); assertEquals(6L, approved.get().connectionGeneration) }
    }

    @Test fun successfulImageStaysVisibleWhileAnotherImageCanBeRetried() {
        val message = ChatMessage("partial-image", "assistant", "", images = listOf(redPng()), imageError = "合成的另一张图片失败")
        val ui = mutableStateOf(fixture().copy(messages = listOf(message)))
        val retried = AtomicReference<String>()
        compose.setContent { NativeApp(ui.value, actions(onRetryImage = { id ->
            retried.set(id)
            ui.value = ui.value.copy(messages = listOf(message.copy(imagePending = true, imageError = null)))
        })) }
        waitForRed()
        compose.onNodeWithText("部分图片加载失败").assertIsDisplayed()
        compose.onNodeWithText("重试加载图片").performClick()
        compose.runOnIdle { assertEquals(message.id, retried.get()) }
        compose.onNodeWithText("部分图片加载失败").assertDoesNotExist()
        waitForRed()
    }

    private fun waitForRed() = compose.waitUntil(15_000) {
        if (compose.onAllNodesWithContentDescription("消息图片").fetchSemanticsNodes().isEmpty()) false
        else compose.onNodeWithContentDescription("消息图片").captureToImage().toPixelMap().let { pixels ->
            pixels.width > 0 && pixels.height > 0 && pixels[pixels.width / 2, pixels.height / 2].let { it.alpha > .9f && it.red > .9f && it.green < .1f && it.blue < .1f }
        }
    }

    private fun fixture() = AppUiState(loading = false, connected = true, selected = Session("fixture-thread", "合成审批与图片会话"))

    private fun redPng(): String {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.RED) }
        val bytes = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }
        bitmap.recycle()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun actions(onApprovalRequest: (Approval, Boolean) -> Unit = { _, _ -> }, onRetryImage: (String) -> Unit = {}) = NativeActions(
        onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {}, onApproval = { _, _ -> },
        onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {}, onOlder = {}, onClearError = {}, onTheme = {},
        onClearCache = {}, onTool = {}, onApprovalRequest = onApprovalRequest, onRetryImage = onRetryImage
    )
}
