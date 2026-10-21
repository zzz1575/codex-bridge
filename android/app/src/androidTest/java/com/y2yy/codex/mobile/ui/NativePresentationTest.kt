package com.y2yy.codex.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.ModelOption
import com.y2yy.codex.mobile.model.ReasoningOption
import com.y2yy.codex.mobile.model.Session
import com.y2yy.codex.mobile.model.ToolCall
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native presentation fixtures only: no MainActivity, ViewModel or server connection. */
@RunWith(AndroidJUnit4::class)
class NativePresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun consecutiveToolsStartAsOneCollapsedGroupAndExpandOnDemand() {
        val openedTool = AtomicReference<String>()
        val state = fixture().copy(
            messages = listOf(
                ChatMessage("question", "user", "检查这些合成步骤"),
                ChatMessage("tool-message-1", "assistant", "", turnId = "fixture-turn", tools = listOf(
                    ToolCall("fixture-exec", "fixture_exec", "completed", "synthetic command", "synthetic result")
                )),
                ChatMessage("tool-message-2", "assistant", "", turnId = "fixture-turn", tools = listOf(
                    ToolCall("fixture-read", "fixture_read", "completed", "synthetic path", "read result")
                )),
                ChatMessage("tool-message-3", "assistant", "", turnId = "fixture-turn", tools = listOf(
                    ToolCall("fixture-search", "fixture_search", "completed", "synthetic query", "search result")
                ))
            )
        )
        compose.setContent { NativeApp(state, actions(onTool = openedTool::set)) }

        compose.onAllNodesWithTag("tool-group").assertCountEquals(1)
        compose.onNodeWithText("工具调用 · 3").assertIsDisplayed()
        listOf("fixture_exec", "fixture_read", "fixture_search", "synthetic result").forEach {
            compose.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }

        compose.onNodeWithContentDescription("展开工具调用").performClick()
        listOf("fixture_exec", "fixture_read", "fixture_search").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("fixture_exec").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("fixture-exec", openedTool.get()) }
        compose.onNodeWithText("synthetic command").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("synthetic result").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("收起工具调用").performScrollTo().performClick()
        compose.onAllNodesWithText("fixture_exec").assertCountEquals(0)
        compose.onAllNodesWithTag("tool-group").assertCountEquals(1)
    }

    @Test
    fun executionHidesModelAndUsageAndKeepsTopRightPickerAvailable() {
        val ui = mutableStateOf(fixture().copy(
            running = true,
            activeTurnId = "fixture-turn",
            activity = "正在检查合成步骤",
            usage = "周剩余 93% · fixture-model-hidden",
            composerModel = "fixture-model-hidden",
            composerEffort = "high",
            modelOptions = listOf(ModelOption("fixture-model-hidden", "测试模型", defaultEffort = "high", efforts = listOf(ReasoningOption("high")))),
            selected = Session("presentation-fixture", "原生展示回归", model = "fixture-model-hidden")
        ))
        compose.setContent { NativeApp(ui.value, actions()) }

        compose.onAllNodesWithText("周剩余", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("fixture-model-hidden", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("停止当前任务").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择模型和思考强度").assertIsDisplayed().performClick()
        compose.onNodeWithTag("model-selector").assertIsDisplayed()
        compose.onNodeWithTag("effort-selector").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()

        compose.runOnIdle { ui.value = ui.value.copy(running = false, activeTurnId = null, activity = "") }
        compose.onAllNodesWithText("fixture-model-hidden", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("选择模型和思考强度").assertIsDisplayed()
        val container = compose.onNodeWithTag("composer-container").fetchSemanticsNode().boundsInRoot
        listOf("添加图片或粘贴", "发送消息").forEach { label ->
            val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("$label remains inside the unified composer", bounds.left >= container.left && bounds.right <= container.right && bounds.top >= container.top && bounds.bottom <= container.bottom)
        }
    }

    @Test
    fun modelPickerUsesOnlySupportedEffortsAndSavesChosenPair() {
        val chosen = AtomicReference<Pair<String, String>>()
        val ui = mutableStateOf(fixture().copy(
            composerModel = "fixture-alpha",
            composerEffort = "high",
            modelOptions = listOf(
                ModelOption(
                    id = "fixture-alpha", name = "合成 Alpha", defaultEffort = "high",
                    efforts = listOf(ReasoningOption("high"), ReasoningOption("ultra"))
                ),
                ModelOption(
                    id = "fixture-beta", name = "合成 Beta", defaultEffort = "low",
                    efforts = listOf(ReasoningOption("low"), ReasoningOption("medium"))
                )
            )
        ))
        val callbacks = actions(onSaveModelSettings = { model, effort ->
            chosen.set(model to effort)
            ui.value = ui.value.copy(composerModel = model, composerEffort = effort)
        })
        compose.setContent { NativeApp(ui.value, callbacks) }
        compose.onAllNodesWithContentDescription("选择模型和思考强度").onFirst().performClick()
        compose.onNodeWithText("模型与思考强度").assertIsDisplayed()
        compose.onNodeWithTag("model-selector").performClick()
        compose.onNodeWithText("合成 Beta").performClick()
        compose.onNodeWithTag("effort-selector").performClick()
        compose.onNodeWithText("medium").assertIsDisplayed()
        compose.onAllNodesWithText("high").assertCountEquals(0)
        compose.onAllNodesWithText("ultra").assertCountEquals(0)
        compose.onNodeWithText("medium").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("fixture-beta" to "medium", chosen.get()) }
    }

    @Test
    fun connectionCacheAndReadOnlyChangesKeepChatAndComposerStationary() {
        val anchor = "固定的消息锚点"
        val ui = mutableStateOf(fixture().copy(
            connected = false, fromCache = true, readOnly = true, connectionText = "正在连接",
            messages = listOf(ChatMessage("fixed-message", "assistant", anchor))
        ))
        compose.setContent { NativeApp(ui.value, actions()) }
        compose.waitForIdle()
        val input = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        input.assertIsDisplayed()
        val initialInput = input.fetchSemanticsNode().boundsInRoot
        val initialMessage = compose.onNodeWithTag("chat-message:fixed-message").fetchSemanticsNode().boundsInRoot
        val initialMessageHeader = compose.onNodeWithText("Codex").fetchSemanticsNode().boundsInRoot
        val initialStatus = compose.onNodeWithTag("chat-status").fetchSemanticsNode().boundsInRoot

        val startupStates = listOf(
            ui.value.copy(connected = true, fromCache = true, readOnly = true, connectionText = "已连接"),
            ui.value.copy(connected = true, fromCache = false, readOnly = true, connectionText = "已连接"),
            ui.value.copy(connected = true, fromCache = false, readOnly = false, connectionText = "已连接"),
            ui.value.copy(connected = false, fromCache = false, readOnly = false, connectionText = "正在重新连接")
        )
        startupStates.forEachIndexed { index, state ->
            compose.runOnIdle { ui.value = state }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val currentInput = input.fetchSemanticsNode().boundsInRoot
            val currentMessage = compose.onNodeWithTag("chat-message:fixed-message").fetchSemanticsNode().boundsInRoot
            val currentMessageHeader = compose.onNodeWithText("Codex").fetchSemanticsNode().boundsInRoot
            val currentStatus = compose.onNodeWithTag("chat-status").fetchSemanticsNode().boundsInRoot
            assertEquals("Composer top at startup step $index", initialInput.top, currentInput.top, 1f)
            assertEquals("Composer bottom at startup step $index", initialInput.bottom, currentInput.bottom, 1f)
            assertEquals("Message must not jump at startup step $index", initialMessage.top, currentMessage.top, 1f)
            assertEquals("Message header at startup step $index", initialMessageHeader.top, currentMessageHeader.top, 1f)
            assertEquals("Status line top at startup step $index", initialStatus.top, currentStatus.top, 1f)
            assertEquals("Status line height at startup step $index", initialStatus.height, currentStatus.height, 1f)
            compose.onNodeWithText("本地缓存 · 正在同步服务器").assertDoesNotExist()
            compose.onNodeWithText("连接中断 · 自动重连后补齐进度").assertDoesNotExist()
            compose.onNodeWithText("当前会话只读 · 其他客户端正在控制任务").assertDoesNotExist()
        }
    }

    private fun fixture() = AppUiState(
        loading = false,
        connected = true,
        connectionText = "已连接",
        selected = Session("presentation-fixture", "原生展示回归"),
        messages = listOf(ChatMessage("fixture-message", "assistant", "合成测试消息"))
    )

    private fun actions(
        onTool: (String) -> Unit = {},
        onSaveModelSettings: (String, String) -> Unit = { _, _ -> }
    ) = NativeActions(
        onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {},
        onApproval = { _, _ -> }, onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {},
        onOlder = {}, onClearError = {}, onTheme = {}, onClearCache = {}, onTool = onTool,
        onSaveModelSettings = onSaveModelSettings
    )
}
