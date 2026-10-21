package com.y2yy.codex.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.Project
import com.y2yy.codex.mobile.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Isolated session controls; no MainActivity/ViewModel, network or server mutations. */
@RunWith(AndroidJUnit4::class)
class SessionActionsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renameKeepsDraftAcrossBusyFailureAndRetriesTrimmedTitle() {
        val ui = mutableStateOf(fixture())
        val count = AtomicInteger()
        val renamed = AtomicReference<String>()
        val callbacks = actions(onRename = { title ->
            renamed.set(title)
            count.incrementAndGet()
            ui.value = ui.value.copy(sessionActionBusy = true, sessionActionError = null)
        }, onClearError = { ui.value = ui.value.copy(sessionActionError = null) })
        compose.setContent { NativeApp(ui.value, callbacks) }
        openMenu("重命名会话")
        val input = compose.onNodeWithTag("session-title-input")
        assertEquals("原会话标题", input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        input.performTextReplacement("   ")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        input.performTextReplacement("  修改后的标题  ")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("修改后的标题", renamed.get()); assertEquals(1, count.get()) }
        input.assertExists().assertIsNotEnabled()
        compose.onNodeWithText("正在保存").assertIsNotEnabled()

        compose.runOnIdle { ui.value = ui.value.copy(sessionActionBusy = false, sessionActionError = "合成重命名失败，请重试") }
        compose.onNodeWithText("合成重命名失败，请重试").assertIsDisplayed()
        assertEquals("  修改后的标题  ", input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { assertEquals("Failure must not submit again automatically", 1, count.get()) }
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, count.get()); assertEquals("修改后的标题", renamed.get()) }
        compose.onNodeWithText("正在保存").assertIsNotEnabled()
        compose.runOnIdle {
            ui.value = ui.value.copy(sessionActionBusy = false, sessionActionError = null, selected = ui.value.selected!!.copy(title = renamed.get()))
        }
        compose.onNodeWithTag("session-title-input").assertDoesNotExist()
        compose.onNodeWithText("修改后的标题").assertIsDisplayed()
        compose.onNodeWithTag("chat-message:history-message").assertIsDisplayed()
    }

    @Test fun moveUsesChosenProjectIdAndPreservesCurrentSession() {
        val ui = mutableStateOf(fixture())
        val target = AtomicReference<String?>()
        val count = AtomicInteger()
        val callbacks = actions(onMove = { id ->
            target.set(id)
            count.incrementAndGet()
            ui.value = ui.value.copy(sessionActionBusy = true, sessionActionError = null)
        })
        compose.setContent { NativeApp(ui.value, callbacks) }
        openMenu("移动到其他项目")
        compose.onNodeWithText("聊天记录会保留，后续命令将在目标项目目录执行。").assertIsDisplayed()
        compose.onNodeWithTag("move-project:none").assertExists()
        compose.onNodeWithTag("move-project:fixture-project-b").performClick()
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, count.get()); assertEquals("fixture-project-b", target.get()) }
        compose.onNodeWithText("正在保存").assertIsNotEnabled()
        compose.runOnIdle {
            ui.value = ui.value.copy(sessionActionBusy = false, selected = ui.value.selected!!.copy(projectPath = "/fixture/project-b"))
        }
        compose.onNodeWithTag("move-project:fixture-project-b").assertDoesNotExist()
        compose.onNodeWithTag("chat-message:history-message").assertIsDisplayed()
        compose.runOnIdle { assertEquals("session-fixture", ui.value.selected?.id); assertEquals(1, ui.value.messages.size) }
    }

    @Test fun runningAndReadOnlyGuardsKeepHistoryAndModelControlInHeader() {
        val ui = mutableStateOf(fixture().copy(running = true, activeTurnId = "fixture-turn", composerModel = "fixture-model-private", composerEffort = "high"))
        val moves = AtomicInteger()
        val renames = AtomicInteger()
        compose.setContent { NativeApp(ui.value, actions(onMove = { moves.incrementAndGet() }, onRename = { renames.incrementAndGet() })) }
        compose.onAllNodesWithContentDescription("选择模型和思考强度").assertCountEquals(1)
        val modelBounds = compose.onNodeWithContentDescription("选择模型和思考强度").fetchSemanticsNode().boundsInRoot
        val composerBounds = compose.onNodeWithTag("composer-container").fetchSemanticsNode().boundsInRoot
        assertTrue("Model control must be above the chat composer", modelBounds.bottom < composerBounds.top)
        compose.onAllNodesWithText("fixture-model-private", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("会话选项").performClick()
        compose.onNodeWithText("查看历史消息").assertIsDisplayed()
        compose.onNodeWithText("移动到其他项目").performClick()
        compose.onNodeWithText("当前任务结束后可移动会话").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("chat-message:history-message").assertIsDisplayed()

        compose.runOnIdle { ui.value = ui.value.copy(running = false, activeTurnId = null, readOnly = true) }
        openMenu("重命名会话")
        compose.onNodeWithText("当前会话由其他客户端控制，暂时无法修改").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("chat-message:history-message").assertIsDisplayed()
        compose.onAllNodesWithText("fixture-model-private", substring = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, moves.get()); assertEquals(0, renames.get()); assertEquals("history-message", ui.value.messages.single().id) }
    }

    private fun openMenu(item: String) {
        compose.onNodeWithContentDescription("会话选项").performClick()
        compose.onNodeWithText(item).performClick()
    }

    private fun fixture() = AppUiState(
        loading = false, connected = true, connectionText = "已连接",
        selected = Session("session-fixture", "原会话标题", projectPath = "/fixture/project-a"),
        projects = listOf(Project("fixture-project-a", "合成项目 A", "/fixture/project-a"), Project("fixture-project-b", "合成项目 B", "/fixture/project-b")),
        messages = listOf(ChatMessage("history-message", "assistant", "保留已有历史消息。"))
    )

    private fun actions(onRename: (String) -> Unit = {}, onMove: (String?) -> Unit = {}, onClearError: () -> Unit = {}) = NativeActions(
        onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {},
        onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {},
        onOlder = {}, onClearError = {}, onTheme = {}, onClearCache = {}, onTool = {},
        onRenameSession = onRename, onMoveSession = onMove, onClearSessionActionError = onClearError
    )
}
