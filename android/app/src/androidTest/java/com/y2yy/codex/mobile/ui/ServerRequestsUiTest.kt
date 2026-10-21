package com.y2yy.codex.mobile.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.y2yy.codex.mobile.data.ServerRequests
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.AsyncQuestionItem
import com.y2yy.codex.mobile.model.AsyncQuestionPrompt
import com.y2yy.codex.mobile.model.ServerPrompt
import com.y2yy.codex.mobile.model.Session
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic prompts only; never starts MainActivity, calls a server or stores real answers. */
@RunWith(AndroidJUnit4::class)
class ServerRequestsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun userAnswersNeedExplicitSubmissionAndFailedRequestsCanRetryWithoutDoubleSubmit() {
        val prompt = userPrompt()
        val ui = mutableStateOf(fixture(prompt))
        val calls = AtomicInteger()
        val reply = AtomicReference<JSONObject>()
        compose.setContent { NativeApp(ui.value, actions { original, response ->
            assertEquals(prompt, original)
            calls.incrementAndGet(); reply.set(response)
            ui.value = ui.value.copy(respondingServerRequestIds = setOf(prompt.id), serverRequestErrors = emptyMap())
        }) }
        open(prompt)
        compose.runOnIdle { assertEquals(0, calls.get()) }
        compose.onNodeWithText("提交回答").assertIsNotEnabled()
        compose.onNodeWithText("只读检查").performClick()
        compose.onNodeWithTag("server-question:secret").performScrollTo().performClick().performTextInput("synthetic-secret")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertExists()
        compose.onNodeWithText("提交回答").assertIsEnabled().performClick()
        compose.onNode(hasText("正在提交") and hasAnyAncestor(hasTestTag("server-request-dialog"))).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, calls.get())
            val answers = reply.get().getJSONObject("answers")
            assertEquals("只读检查", answers.getJSONObject("scope").getJSONArray("answers").getString(0))
            assertEquals("synthetic-secret", answers.getJSONObject("secret").getJSONArray("answers").getString(0))
            ui.value = ui.value.copy(respondingServerRequestIds = emptySet(), serverRequestErrors = mapOf(prompt.id to "合成网络错误，请重试"))
        }
        compose.onNodeWithText("合成网络错误，请重试").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, calls.get()) }
        compose.onNodeWithText("提交回答").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, calls.get()) }
    }

    @Test fun closingDisconnectingAndReusingIdsNeverRestoreSecretAnswers() {
        val prompt = userPrompt()
        val ui = mutableStateOf(fixture(prompt))
        val calls = AtomicInteger()
        compose.setContent { NativeApp(ui.value, actions { _, _ -> calls.incrementAndGet() }) }
        open(prompt)
        compose.onNodeWithTag("server-question:secret").performScrollTo().performClick().performTextInput("synthetic-to-discard")
        compose.onNodeWithText("关闭表单").performClick()
        open(prompt)
        assertEquals("", compose.onNodeWithTag("server-question:secret").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("server-question:secret").performScrollTo().performClick().performTextInput("another-synthetic-secret")
        compose.runOnIdle { ui.value = ui.value.copy(connected = false) }
        compose.onNodeWithTag("server-request-dialog").assertDoesNotExist()
        compose.runOnIdle { ui.value = fixture(prompt.copy(connectionGeneration = prompt.connectionGeneration + 1, requestIdentity = "rpc-new-identity")) }
        open(prompt)
        assertEquals("", compose.onNodeWithTag("server-question:secret").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { ui.value = ui.value.copy(serverRequests = emptyList()) }
        compose.onNodeWithTag("server-request-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls.get()) }
    }

    @Test fun mcpFormSubmitsTypedFieldsAndUnsupportedSchemaOnlyOffersSafeActions() {
        val prompt = parsePrompt("mcpServer/elicitation/request", JSONObject("""{
          "mode":"form","title":"合成字段表单","requestedSchema":{
            "type":"object","properties":{
              "count":{"type":"integer","title":"数量","minimum":1},
              "allowed":{"type":"boolean","title":"是否继续"},
              "colour":{"type":"string","enum":["blue","red"],"enumNames":["蓝色","红色"]},
              "items":{"type":"array","items":{"type":"string","enum":["截图","文档"]},"minItems":1}
            },"required":["count","allowed","colour","items"],"additionalProperties":false}}
        """))
        val ui = mutableStateOf(fixture(prompt))
        val reply = AtomicReference<JSONObject>()
        val calls = AtomicInteger()
        compose.setContent { NativeApp(ui.value, actions { _, response -> reply.set(response); calls.incrementAndGet() }) }
        open(prompt)
        compose.onNodeWithText("提交回答").assertIsNotEnabled()
        compose.onNodeWithTag("server-field:count").performScrollTo().performClick().performTextInput("3")
        compose.onNodeWithText("否").performScrollTo().performClick()
        compose.onNodeWithText("蓝色").performScrollTo().performClick()
        compose.onNodeWithText("截图").performScrollTo().performClick()
        compose.onNodeWithText("提交回答").assertIsEnabled().performClick()
        compose.runOnIdle {
            val content = reply.get().getJSONObject("content")
            assertEquals("accept", reply.get().getString("action"))
            assertEquals(3, content.getInt("count")); assertFalse(content.getBoolean("allowed"))
            assertEquals("blue", content.getString("colour")); assertEquals("截图", content.getJSONArray("items").getString(0))
            ui.value = fixture(parsePrompt("mcpServer/elicitation/request", JSONObject("""{"mode":"form","requestedSchema":{"${'$'}ref":"https://example.invalid/schema"}}""")))
        }
        // A new request identity replaces the submitted form, even if the numeric id is reused.
        compose.onNodeWithTag("server-request-dialog").assertDoesNotExist()
        open(prompt)
        compose.onNodeWithText("提交回答").assertDoesNotExist()
        compose.onNodeWithText("取消请求").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, calls.get()); assertEquals("cancel", reply.get().getString("action")); assertFalse(reply.get().has("content")) }
    }

    @Test fun urlDoesNotOpenOrCompleteWithoutExplicitUserActions() {
        val prompt = parsePrompt("mcpServer/elicitation/request", JSONObject("""{"mode":"url","title":"合成网页操作","url":"https://example.invalid/authorize"}"""))
        val opened = AtomicReference<String>()
        val replies = AtomicInteger()
        val reply = AtomicReference<JSONObject>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened.set(uri) } }
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides handler) { NativeApp(fixture(prompt), actions { _, response -> reply.set(response); replies.incrementAndGet() }) } }
        open(prompt)
        compose.runOnIdle { assertEquals(null, opened.get()); assertEquals(0, replies.get()) }
        compose.onNodeWithText("确认已完成").assertIsNotEnabled()
        compose.onNodeWithText("打开授权网页").performClick()
        compose.runOnIdle { assertEquals("https://example.invalid/authorize", opened.get()); assertEquals(0, replies.get()) }
        compose.onNodeWithText("我已完成网页操作").performClick()
        compose.onNodeWithText("确认已完成").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, replies.get()); assertEquals("accept", reply.get().getString("action")); assertFalse(reply.get().has("content")) }
    }

    @Test fun asyncQuestionInItsActiveTurnOnlySendsAnExplicitFollowUp() {
        val prompt = asyncPrompt()
        val ui = mutableStateOf(asyncFixture(prompt).copy(running = true, activeTurnId = prompt.turnId, activeSessionIds = setOf(prompt.threadId)))
        val calls = AtomicInteger()
        val mode = AtomicReference<String>()
        val answers = AtomicReference<List<String>>()
        compose.setContent { NativeApp(ui.value, asyncActions { original, reply, selectedMode ->
            assertEquals(prompt, original); calls.incrementAndGet(); mode.set(selectedMode); answers.set(reply)
            ui.value = ui.value.copy(respondingAsyncQuestionIds = setOf(prompt.itemId))
        }) }
        compose.onNodeWithContentDescription("回答会话问题:${prompt.itemId}").performClick()
        compose.onNodeWithText("跟进回答").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls.get()) }
        compose.onNodeWithText("先检查文档").performClick()
        compose.onNodeWithText("跟进回答").assertIsEnabled().performClick()
        compose.onNode(hasText("正在提交") and hasAnyAncestor(hasTestTag("async-question-dialog"))).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls.get()); assertEquals("steer", mode.get()); assertEquals(listOf("先检查文档"), answers.get()) }
    }

    @Test fun otherAsyncTurnBlocksSubmissionAndIdleRequiresANewTaskButtonWithNoRestoredDraft() {
        val prompt = asyncPrompt()
        val ui = mutableStateOf(asyncFixture(prompt).copy(running = true, activeTurnId = "different-active-turn", activeSessionIds = setOf(prompt.threadId)))
        val replies = AtomicInteger()
        val mode = AtomicReference<String>()
        compose.setContent { NativeApp(ui.value, asyncActions { _, _, selectedMode -> replies.incrementAndGet(); mode.set(selectedMode) }) }
        compose.onNodeWithContentDescription("回答会话问题:${prompt.itemId}").performClick()
        compose.onNodeWithTag("async-answer:0").performScrollTo().performClick().performTextInput("尚未提交的合成回答")
        compose.onNodeWithText("暂时无法回答").assertIsNotEnabled()
        compose.onNodeWithText("关闭表单").performClick()
        compose.onNodeWithContentDescription("回答会话问题:${prompt.itemId}").performClick()
        assertEquals("", compose.onNodeWithTag("async-answer:0").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { ui.value = ui.value.copy(running = false, activeTurnId = null, activeSessionIds = emptySet()) }
        compose.onNodeWithText("作为新任务回答").assertIsNotEnabled()
        compose.onNodeWithText("检查日志").performClick()
        compose.runOnIdle { assertEquals("Idle must not automatically start a new turn", 0, replies.get()) }
        compose.onNodeWithText("作为新任务回答").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, replies.get()); assertEquals("new_turn", mode.get()) }
    }

    private fun open(prompt: ServerPrompt) { compose.onNodeWithContentDescription("处理待回答问题:${prompt.id}").performClick() }

    private fun fixture(prompt: ServerPrompt) = AppUiState(loading = false, connected = true,
        selected = Session("prompt-session", "合成问答会话"), serverRequests = listOf(prompt))

    private fun asyncPrompt() = AsyncQuestionPrompt("prompt-session", "async-fixture-item", "question-turn",
        listOf(AsyncQuestionItem("请选择下一步", listOf("先检查文档", "检查日志"))), connectionGeneration = 3)

    private fun asyncFixture(prompt: AsyncQuestionPrompt) = AppUiState(loading = false, connected = true,
        selected = Session("prompt-session", "合成异步问答会话"), asyncQuestions = listOf(prompt))

    private fun userPrompt() = parsePrompt("item/tool/requestUserInput", JSONObject("""{"questions":[
      {"id":"scope","header":"范围","question":"请选择检查范围","options":[{"label":"只读检查","description":"不修改文件"},{"label":"检查文档","description":"合成选项"}]},
      {"id":"secret","header":"验证信息","question":"输入合成验证信息","isSecret":true,"options":null}]}"""))

    private fun parsePrompt(method: String, params: JSONObject): ServerPrompt = ServerRequests.parse(JSONObject().put("requests", JSONArray().put(
        JSONObject().put("id", 71).put("thread_id", "prompt-session").put("request", JSONObject().put("id", "fixture-${params.hashCode()}").put("method", method).put("params", params))
    ))).single().copy(connectionGeneration = 3)

    private fun actions(onResponse: (ServerPrompt, JSONObject) -> Unit) = NativeActions(
        onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {}, onApproval = { _, _ -> },
        onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {}, onOlder = {}, onClearError = {}, onTheme = {},
        onClearCache = {}, onTool = {}, onServerResponse = onResponse
    )

    private fun asyncActions(onResponse: (AsyncQuestionPrompt, List<String>, String) -> Unit) = NativeActions(
        onRefresh = {}, onSelect = {}, onBack = {}, onSend = { _, _ -> }, onStop = {}, onApproval = { _, _ -> },
        onNew = { _, _ -> }, onAttach = {}, onRemoveAttachment = {}, onOlder = {}, onClearError = {}, onTheme = {},
        onClearCache = {}, onTool = {}, onAsyncResponse = onResponse
    )
}
