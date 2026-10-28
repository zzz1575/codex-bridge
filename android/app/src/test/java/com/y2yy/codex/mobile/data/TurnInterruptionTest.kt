package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.Session
import com.y2yy.codex.mobile.model.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures reproduce the native wrapper and Bridge history/event transport. */
class TurnInterruptionTest {
    private val raw = "<turn_aborted> The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed. </turn_aborted>"

    @Test fun recognizesExactNativeWrapperWithLineBreaksWithoutRewritingHistory() {
        val wrapped = " \r\n${raw.replace(" Any ", "\r\nAny ").replace(" If ", "\n\nIf ")}\n "
        val message = ChatMessage("notice", "user", wrapped, turnId = "old-turn")
        assertEquals(wrapped, TurnInterruption.presentation(message)!!.raw)
        assertEquals(wrapped, message.text)
        assertNotNull(TurnInterruption.presentation(message.copy(role = "system")))
        assertNull(TurnInterruption.presentation(message.copy(role = "assistant")))
        assertNull(TurnInterruption.presentation(message.copy(role = "developer")))
    }

    @Test fun preservesMixedUserTextCodeUnknownAndMalformedWrappers() {
        listOf("继续处理\n$raw", "$raw\n补充一个要求", "```text\n$raw\n```", "> $raw",
            raw.replace("on purpose.", "because the server failed."), raw.dropLast(1), "$raw\n$raw",
            "<turn_aborted>用户自己的标记</turn_aborted>", raw.replace("<turn_aborted>", "<turn_aborted reason=\"manual\">"))
            .forEach { assertNull("Must preserve: $it", TurnInterruption.parse(it)) }
    }

    @Test fun preservesImagesToolsDeferredAndUnfinishedMessages() {
        val message = ChatMessage("notice", "user", raw)
        listOf(message.copy(images = listOf("data:image/png;base64,AAAA")), message.copy(imagePending = true),
            message.copy(imageError = "synthetic failure"), message.copy(tools = listOf(ToolCall("tool", "exec", "completed"))),
            message.copy(deferred = true), message.copy(streaming = true)).forEach { assertNull(TurnInterruption.presentation(it)) }
    }

    @Test fun bridgeHistoryAndRefreshKeepIdentityOriginalTextAndSubsequentQuestion() {
        fun row(id: String, text: String, kind: String = "text") = JSONObject().put("id", id).put("role", "user").put("turn_id", "turn-before-stop")
            .put("content", JSONArray().put(JSONObject().put("kind", kind).put("text", text)))
        val fixture = JSONObject().put("messages", JSONArray().put(row("notice", raw, "pasted_text"))
            .put(row("question", "继续检查客户搜索"))).put("page", JSONObject().put("total", 2))
        val first = BridgeParser.messages(fixture, "thread").messages
        val refreshed = BridgeParser.mergeSnapshot(BridgeParser.messages(JSONObject(fixture.toString()), "thread").messages, first)
        assertEquals(first, refreshed)
        assertEquals(raw, refreshed[0].text)
        assertEquals("notice", refreshed[0].id)
        assertNotNull(TurnInterruption.presentation(refreshed[0]))
        assertEquals("继续检查客户搜索", refreshed[1].text)
        assertNull(TurnInterruption.presentation(refreshed[1]))
    }

    @Test fun bridgeSplitContentKeepsRealUserContentAndAttachment() {
        val contents = JSONArray().put(JSONObject().put("kind", "text").put("text", raw))
            .put(JSONObject().put("kind", "pasted_text").put("text", "这是我粘贴的报错，帮我分析"))
            .put(JSONObject().put("kind", "image").put("url", "data:image/png;base64,AAAA"))
        val fixture = JSONObject().put("messages", JSONArray().put(JSONObject().put("id", "mixed").put("role", "user").put("content", contents)))
        val parsed = BridgeParser.messages(fixture, "thread").messages.single()
        assertNull(TurnInterruption.presentation(parsed))
        assertEquals("$raw\n\n这是我粘贴的报错，帮我分析", parsed.text)
        assertEquals(listOf("data:image/png;base64,AAAA"), parsed.images)
    }

    @Test fun abortedTurnEventStopsActivityWhileHistoryNoticeNeverRestartsOrSubmits() {
        val message = ChatMessage("notice", "user", raw, turnId = "turn")
        val state = AppUiState(selected = Session("thread", "合成会话"), messages = listOf(message), running = true,
            activeTurnId = "turn", activeSessionIds = setOf("thread"))
        val event = JSONObject().put("type", "app_server").put("message", JSONObject().put("method", "turn/completed")
            .put("params", JSONObject().put("threadId", "thread").put("turn", JSONObject().put("id", "turn").put("status", "interrupted"))))
        val stopped = StreamReducer.apply(state, event)
        assertFalse(stopped.running)
        assertNull(stopped.activeTurnId)
        assertEquals("已停止", stopped.activity)
        assertEquals(listOf(message), stopped.messages)
        assertNotNull(TurnInterruption.presentation(stopped.messages.single()))
        assertTrue(stopped.pending.isEmpty())
    }
}
