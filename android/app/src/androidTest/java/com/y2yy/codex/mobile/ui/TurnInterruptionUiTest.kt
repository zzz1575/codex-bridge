package com.y2yy.codex.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.y2yy.codex.mobile.data.TurnInterruption
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.Session
import com.y2yy.codex.mobile.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic/offline native UI only; disclosure never controls execution or sends messages. */
@RunWith(AndroidJUnit4::class)
class TurnInterruptionUiTest {
    @get:Rule val compose = createComposeRule()
    private val raw = "<turn_aborted> The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed. </turn_aborted>"

    @Test fun defaultsToChineseStoppedCardWithSeparateRawDisclosure() {
        compose.setContent { TurnInterruptionCard("notice", TurnInterruption.parse(raw)!!) }
        compose.onNodeWithText("本轮已中断").assertIsDisplayed()
        compose.onAllNodesWithText("The user interrupted", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("turn-interruption-toggle:notice").performClick()
        compose.onNodeWithText("用户已中断上一轮回复。").assertIsDisplayed()
        compose.onAllNodesWithText("The user interrupted", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("turn-interruption-raw-toggle:notice").performClick()
        compose.onNodeWithTag("turn-interruption-raw:notice").assertIsDisplayed()
        compose.onNodeWithTag("turn-interruption-toggle:notice").performClick()
        compose.onAllNodesWithText("The user interrupted", substring = true).assertCountEquals(0)
    }

    @Test fun fullHistoryRefreshKeepsCardAndUserQuestionWithoutExecutingAnything() {
        var calls = 0
        val state = mutableStateOf(AppUiState(loading = false, connected = true, connectionText = "已连接", selected = Session("thread", "合成中断回归"),
            messages = listOf(ChatMessage("notice", "user", raw), ChatMessage("question", "user", "继续检查客户搜索")), running = false))
        compose.setContent { NativeApp(state.value, actions { calls++ }) }
        compose.onNodeWithTag("turn-interruption:notice").assertIsDisplayed()
        compose.onAllNodesWithTag("chat-message:notice").assertCountEquals(0)
        compose.onNodeWithTag("chat-message:question").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("停止当前任务").assertCountEquals(0)
        compose.onNodeWithTag("turn-interruption-toggle:notice").performClick()
        compose.runOnIdle { state.value = state.value.copy(messages = state.value.messages.map { it.copy() }) }
        compose.onNodeWithText("用户已中断上一轮回复。").assertIsDisplayed()
        compose.onNodeWithTag("turn-interruption-raw-toggle:notice").performClick()
        compose.onNodeWithTag("turn-interruption-raw:notice").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun userExplanationContainingTheWrapperStaysACompleteChatMessage() {
        val message = ChatMessage("mixed", "user", "请解释这个报错：\n$raw")
        compose.setContent { NativeApp(AppUiState(loading = false, connected = true, selected = Session("thread", "混合内容回归"), messages = listOf(message)), actions {}) }
        compose.onNodeWithTag("chat-message:mixed").assertIsDisplayed()
        compose.onAllNodesWithTag("turn-interruption:mixed").assertCountEquals(0)
    }

    @Test fun noFirstOutputShowsWaitingAndAnActiveToolShowsItsRealProgress() {
        val state = mutableStateOf(AppUiState(loading = false, connected = true, selected = Session("thread", "等待响应回归"),
            running = true, activeTurnId = "current", activity = "正在思考",
            messages = listOf(ChatMessage("previous", "assistant", "上一轮已完成", turnId = "previous"))))
        compose.setContent { NativeApp(state.value, actions {}) }
        compose.onNodeWithText("等待模型响应").assertIsDisplayed()
        compose.onNodeWithContentDescription("停止当前任务").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(activity = "正在执行：exec_command", messages = state.value.messages +
            ChatMessage("tool", "assistant", "", turnId = "current", tools = listOf(ToolCall("tool", "exec_command", "running")))) }
        compose.onAllNodesWithText("等待模型响应").assertCountEquals(0)
        compose.onNodeWithText("正在执行：exec_command").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(activity = "正在回复", messages = state.value.messages +
            ChatMessage("answer", "assistant", "开始回答当前问题", turnId = "current", streaming = true)) }
        compose.onAllNodesWithText("等待模型响应").assertCountEquals(0)
    }

    private fun actions(onCommand: () -> Unit) = NativeActions(
        onRefresh = onCommand, onSelect = { onCommand() }, onBack = onCommand,
        onSend = { _, _ -> onCommand() }, onStop = onCommand, onApproval = { _, _ -> onCommand() },
        onNew = { _, _ -> onCommand() }, onAttach = { onCommand() }, onRemoveAttachment = { onCommand() },
        onOlder = onCommand, onClearError = onCommand, onTheme = { onCommand() }, onClearCache = onCommand, onTool = { onCommand() }
    )
}
