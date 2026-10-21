package com.y2yy.codex.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.y2yy.codex.mobile.data.TaskProgress
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.Session
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI only: no ViewModel, network, credentials or business data. */
@RunWith(AndroidJUnit4::class)
class TaskProgressUiTest {
    @get:Rule val compose = createComposeRule()
    private val raw = """Y2YY_INTERNAL_SUMMARY {"changes":[{"change":"done","task_id":"T0001"},{"change":"awaiting_delivery:execution_finished_not_delivery_or_business_proof","task_id":"T0002"}],"event_id":"00000000-0000-5000-8000-000000000001"}
Read the ledger and summarize these changes. This is an internal notification, not a new task or authorization to write business data. Do not register this notification as a task."""

    @Test fun defaultsClosedAndRequiresSecondDisclosureForRawProtocol() {
        compose.setContent { TaskProgressCard("fixture", TaskProgress.parse(raw)!!) }
        compose.onNodeWithText("任务进度 · 2 项更新").assertIsDisplayed()
        compose.onAllNodesWithText("T0001", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Y2YY_INTERNAL_SUMMARY", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("task-progress-toggle:fixture").performClick()
        compose.onNodeWithText("T0001 · 任务已完成").assertIsDisplayed()
        compose.onNodeWithText("T0002 · 等待交付验证").assertIsDisplayed()
        compose.onNodeWithText("执行已结束，尚未完成交付或业务验证").assertIsDisplayed()
        compose.onAllNodesWithText("Y2YY_INTERNAL_SUMMARY", substring = true).assertCountEquals(0)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "task-progress-expanded.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("task-progress-raw-toggle:fixture").performClick()
        compose.onNodeWithTag("task-progress-raw:fixture").assertIsDisplayed()
        compose.onNodeWithTag("task-progress-toggle:fixture").performClick()
        compose.onAllNodesWithText("Y2YY_INTERNAL_SUMMARY", substring = true).assertCountEquals(0)
    }

    @Test fun refreshedMessagesRetainDisclosureAndKeepCoordinatorSummary() {
        val state = mutableStateOf(AppUiState(loading = false, connected = true,
            selected = Session("progress-fixture", "任务进度回归"),
            messages = listOf(ChatMessage("notice", "user", raw), ChatMessage("summary", "assistant", "统筹汇总：排查已完成，恢复操作仍待验证。"))))
        compose.setContent { NativeApp(state.value, actions()) }
        compose.onNodeWithTag("task-progress:notice").assertIsDisplayed()
        compose.onNodeWithTag("chat-message:summary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("task-progress-toggle:notice").performScrollTo().performClick()
        compose.onNodeWithText("T0001 · 任务已完成").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(messages = state.value.messages.map { it.copy() }, connectionText = "已连接 · 同步完成") }
        compose.onNodeWithText("T0001 · 任务已完成").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Y2YY_INTERNAL_SUMMARY", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("chat-message:summary").performScrollTo().assertIsDisplayed()
    }

    private fun actions() = NativeActions(
        onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {},
        onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {},
        onOlder = {}, onClearError = {}, onTheme = {}, onClearCache = {}, onTool = {}
    )
}
