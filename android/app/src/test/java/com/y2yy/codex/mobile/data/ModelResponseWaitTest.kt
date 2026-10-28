package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelResponseWaitTest {
    private fun state() = AppUiState(selected = Session("thread", "合成会话"), running = true, activeTurnId = "new-turn", activity = "正在思考")

    @Test fun waitsForNewTurnDespiteOldRepliesToolsAndCurrentUserInput() {
        val input = state().copy(messages = listOf(
            ChatMessage("old-reply", "assistant", "之前的回答", turnId = "old-turn"),
            ChatMessage("old-tool", "assistant", "", tools = listOf(ToolCall("tool", "exec", "completed")), turnId = "old-turn"),
            ChatMessage("new-user", "user", "新问题", turnId = "new-turn")), modelProgressTurnId = "old-turn")
        assertTrue(ModelResponseWait.isWaiting(input))
        assertTrue(input.running)
        assertEquals("new-turn", input.activeTurnId)
    }

    @Test fun firstReplyOrToolImmediatelyClearsWaitingWithoutTimingOutLongTools() {
        for (message in listOf(
            ChatMessage("reply", "assistant", "正在检查", turnId = "new-turn", streaming = true),
            ChatMessage("tool", "assistant", "", turnId = "new-turn", tools = listOf(ToolCall("tool", "exec", "running"))),
            ChatMessage("tool-role", "tool", "", turnId = "new-turn", tools = listOf(ToolCall("tool", "exec", "running")))
        )) assertFalse(ModelResponseWait.isWaiting(state().copy(messages = listOf(message))))
        assertFalse(ModelResponseWait.isWaiting(state().copy(activity = "正在执行：exec_command")))
        assertFalse(ModelResponseWait.isWaiting(state().copy(activity = "正在整理上下文")))
        assertFalse(ModelResponseWait.isWaiting(state().copy(activity = "正在回复")))
    }

    @Test fun idleUnknownTurnAndStoppedNeverShowWaiting() {
        assertFalse(ModelResponseWait.isWaiting(state().copy(running = false)))
        assertFalse(ModelResponseWait.isWaiting(state().copy(activeTurnId = null)))
        assertFalse(ModelResponseWait.isWaiting(state().copy(activity = "已停止")))
    }

    @Test fun nativeReasoningWithoutPublicTextCountsOnlyForTheCurrentTurn() {
        fun event(turn: String) = JSONObject().put("type", "app_server").put("message", JSONObject().put("method", "item/started")
            .put("params", JSONObject().put("threadId", "thread").put("turnId", turn).put("item", JSONObject().put("type", "reasoning").put("id", "reason"))))
        val waiting = state()
        val old = StreamReducer.apply(waiting, event("old-turn"))
        assertTrue(ModelResponseWait.isWaiting(old))
        val progress = StreamReducer.apply(old, event("new-turn"))
        assertFalse(ModelResponseWait.isWaiting(progress))
        assertTrue(progress.messages.isEmpty())
        assertTrue(progress.pending.isEmpty())
        assertTrue(progress.running)
        val delayed = StreamReducer.apply(progress, event("old-turn"))
        assertFalse(ModelResponseWait.isWaiting(delayed))
        assertTrue(ModelResponseWait.isWaiting(progress.copy(activeTurnId = "another-turn")))
    }

    @Test fun liveFirstReplyClearsTheWaitingProjectionWithExistingReducer() {
        val event = JSONObject().put("type", "app_server").put("message", JSONObject().put("method", "item/agentMessage/delta")
            .put("params", JSONObject().put("threadId", "thread").put("turnId", "new-turn").put("itemId", "reply").put("delta", "已收到")))
        assertFalse(ModelResponseWait.isWaiting(StreamReducer.apply(state(), event)))
    }
}
