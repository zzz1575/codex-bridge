package com.y2yy.codex.mobile.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.y2yy.codex.mobile.data.StreamReducer
import com.y2yy.codex.mobile.model.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Compose + real reducer fixtures only; no ViewModel, network or server mutation. */
@RunWith(AndroidJUnit4::class)
class SessionActivityUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun delegatedWorkerAppearsInListAndTasksWithoutRefresh() {
        val ui = mutableStateOf(fixture())
        var refreshes = 0
        compose.setContent { NativeApp(ui.value, actions(onRefresh = { refreshes++ })) }
        compose.onNodeWithText("合成工作会话").assertIsDisplayed()
        compose.onAllNodesWithText("执行中").assertCountEquals(0)

        compose.runOnIdle { ui.value = StreamReducer.apply(ui.value, ack()) }
        compose.onNodeWithText("执行中").assertIsDisplayed()
        compose.onNode(hasText("任务") and hasClickAction()).performClick()
        compose.onNodeWithText("1 个会话").assertIsDisplayed()
        compose.onNodeWithText("合成工作会话").assertIsDisplayed()
        screenshot("delegated-worker-running.png")

        compose.runOnIdle { ui.value = StreamReducer.apply(ui.value, complete("turn-a", 20)) }
        compose.onNodeWithText("当前没有执行中的会话").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

    @Test fun backgroundTransitionsPreserveSelectedChatAndUnsentDraft() {
        val base = fixture()
        val ui = mutableStateOf(base.copy(selected = base.sessions.first(), messages = listOf(ChatMessage("keep", "assistant", "保留当前会话内容"))))
        compose.setContent { NativeApp(ui.value, actions()) }
        compose.onNodeWithTag("composer-input").performTextInput("未发送的草稿")
        compose.runOnIdle {
            ui.value = StreamReducer.apply(ui.value, ack())
            ui.value = StreamReducer.apply(ui.value, complete("turn-a", 20))
        }
        compose.onNodeWithTag("composer-input").assertTextEquals("未发送的草稿")
        // Markdown uses an Android TextView; assert the Compose message host,
        // rather than its zero-sized interoperability text semantics node.
        compose.onNodeWithTag("chat-message:keep").assertIsDisplayed()
        compose.onNodeWithContentDescription("停止当前任务").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("coordinator", ui.value.selected?.id)
            assertEquals("保留当前会话内容", ui.value.messages.single().text)
        }
    }

    @Test fun selectedWorkerRetainsStopButtonAcrossOldSnapshotsAndOldCompletions() {
        val base = fixture()
        val ui = mutableStateOf(base.copy(selected = base.sessions.last()))
        compose.setContent { NativeApp(ui.value, actions()) }
        compose.runOnIdle {
            ui.value = StreamReducer.apply(ui.value, ack())
            ui.value = StreamReducer.apply(ui.value, ack("turn-b", 15))
            ui.value = StreamReducer.apply(ui.value, complete("turn-a", 18))
            ui.value = StreamReducer.apply(ui.value, JSONObject("""{"type":"bridge_thread_activity_snapshot","active_thread_ids":[],"thread_states":{"worker":"completed"},"thread_turn_ids":{"worker":"turn-a"},"thread_sequences":{"worker":10}}"""))
        }
        compose.onNodeWithContentDescription("停止当前任务").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送跟进").assertExists()
        compose.runOnIdle { ui.value = StreamReducer.apply(ui.value, complete("turn-b", 21)) }
        compose.onNodeWithContentDescription("停止当前任务").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送消息").assertExists()
    }

    private fun fixture() = AppUiState(loading = false, connected = true, connectionText = "已连接",
        sessions = listOf(Session("coordinator", "合成统筹会话"), Session("worker", "合成工作会话")))

    private fun ack(turn: String = "turn-a", sequence: Long = 10) = JSONObject()
        .put("type", "bridge_thread_run_state").put("thread_id", "worker").put("turn_id", turn)
        .put("run_state", "active").put("bridge_sequence", sequence)

    private fun complete(turn: String, sequence: Long) = JSONObject().put("type", "app_server")
        .put("bridge_sequence", sequence).put("message", JSONObject().put("method", "turn/completed")
            .put("params", JSONObject().put("threadId", "worker").put("turn", JSONObject().put("id", turn).put("status", "completed"))))

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun actions(onRefresh: () -> Unit = {}) = NativeActions(
        onRefresh = onRefresh, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {},
        onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {},
        onOlder = {}, onClearError = {}, onTheme = {}, onClearCache = {}, onTool = {}
    )
}
