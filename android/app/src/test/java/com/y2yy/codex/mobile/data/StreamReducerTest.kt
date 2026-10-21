package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StreamReducerTest {
    private val selected = Session("thread-a", "Selected")
    private val other = Session("thread-b", "Other")

    private fun state() = AppUiState(loading = false, selected = selected, sessions = listOf(selected, other))

    private fun event(method: String, params: JSONObject) = JSONObject()
        .put("type", "app_server")
        .put("message", JSONObject().put("method", method).put("params", params))

    private fun params(thread: String = "thread-a", turn: String = "turn-a") = JSONObject()
        .put("threadId", thread).put("turnId", turn)

    private fun delta(text: String, thread: String = "thread-a") = event("item/agentMessage/delta",
        params(thread).put("itemId", "reply-a").put("delta", text))

    @Test
    fun textDeltasMergeExactlyIntoOneStableLiveMessage() {
        val first = StreamReducer.apply(state(), delta("你好"))
        val second = StreamReducer.apply(first, delta("，"))
        val third = StreamReducer.apply(second, delta("世界\nnext"))

        assertEquals(1, third.messages.size)
        assertEquals("live:reply-a", third.messages.single().id)
        assertEquals("你好，世界\nnext", third.messages.single().text)
        assertEquals("turn-a", third.messages.single().turnId)
        assertTrue(third.messages.single().streaming)
        assertTrue(third.running)
        assertEquals("turn-a", third.activeTurnId)
    }

    @Test
    fun deltasUpdateSnapshotMessageRatherThanCreatingAnotherBubble() {
        val original = ChatMessage("reply-a", "assistant", "已收到", turnId = "turn-a")
        val updated = StreamReducer.apply(state().copy(messages = listOf(original)), delta("，继续"))

        assertEquals(1, updated.messages.size)
        assertEquals("reply-a", updated.messages.single().id)
        assertEquals("已收到，继续", updated.messages.single().text)
    }

    @Test
    fun authoritativeMessageCompletionReconcilesLiveIdentityWithoutDuplicatingText() {
        val live = StreamReducer.apply(state(), delta("A"))
        val complete = event("item/completed", params().put("item", JSONObject()
            .put("type", "agentMessage").put("id", "reply-a").put("text", "ABC")))

        val updated = StreamReducer.apply(live, complete)

        assertEquals(1, updated.messages.size)
        assertEquals("reply-a", updated.messages.single().id)
        assertEquals("ABC", updated.messages.single().text)
        assertFalse(updated.messages.single().streaming)
    }

    @Test
    fun completionFromAnEarlierTurnCannotStopSuccessorTurn() {
        val running = state().copy(running = true, activeTurnId = "turn-new", activity = "正在执行",
            activeSessionIds = setOf("thread-a"),
            messages = listOf(ChatMessage("live:reply-new", "assistant", "new", streaming = true, turnId = "turn-new")))
        val oldCompletion = event("turn/completed", params(turn = "turn-old")
            .put("turn", JSONObject().put("id", "turn-old").put("status", "interrupted")))

        assertEquals(running, StreamReducer.apply(running, oldCompletion))
    }

    @Test
    fun currentTurnCompletionClearsRunningStateAndStreaming() {
        val running = state().copy(running = true, activeTurnId = "turn-a", activeSessionIds = setOf("thread-a"),
            messages = listOf(ChatMessage("reply-a", "assistant", "done", streaming = true)))
        val completion = event("turn/completed", params()
            .put("turn", JSONObject().put("id", "turn-a").put("status", "completed")))

        val updated = StreamReducer.apply(running, completion)

        assertFalse(updated.running)
        assertNull(updated.activeTurnId)
        assertFalse(updated.messages.single().streaming)
        assertFalse("thread-a" in updated.activeSessionIds)
    }

    @Test
    fun backgroundThreadTurnStartUpdatesGlobalTasksWithoutTouchingSelectedMessages() {
        val original = state().copy(messages = listOf(ChatMessage("reply-a", "assistant", "Keep this")))
        val start = event("turn/started", params("thread-b", "turn-b")
            .put("turn", JSONObject().put("id", "turn-b")))

        val updated = StreamReducer.apply(original, start)

        assertTrue("thread-b" in updated.activeSessionIds)
        assertEquals("active", updated.sessions.single { it.id == "thread-b" }.status)
        assertEquals(original.messages, updated.messages)
        assertFalse(updated.running)
        assertNull(updated.activeTurnId)
    }

    @Test
    fun backgroundThreadTurnCompletionRemovesTaskWithoutStoppingSelectedThread() {
        val original = state().copy(running = true, activeTurnId = "turn-a",
            activeSessionIds = setOf("thread-a", "thread-b"),
            sessions = listOf(selected.copy(status = "active"), other.copy(status = "active")))
        val complete = event("turn/completed", params("thread-b", "turn-b")
            .put("turn", JSONObject().put("id", "turn-b").put("status", "completed")))

        val updated = StreamReducer.apply(original, complete)

        assertEquals(setOf("thread-a"), updated.activeSessionIds)
        assertNotEquals("active", updated.sessions.single { it.id == "thread-b" }.status)
        assertTrue(updated.running)
        assertEquals("turn-a", updated.activeTurnId)
    }

    @Test
    fun backgroundMessageDeltaCannotLeakIntoSelectedConversation() {
        val original = state().copy(messages = listOf(ChatMessage("reply-a", "assistant", "Only selected")))

        assertEquals(original, StreamReducer.apply(original, delta("Other thread response", "thread-b")))
    }

    @Test
    fun commandOutputDeltasPreserveToolSummaryAndPriorOutput() {
        val started = event("item/started", params().put("item", JSONObject()
            .put("type", "commandExecution").put("id", "tool-a").put("command", "inspect fixtures")
            .put("status", "inProgress")))
        val first = StreamReducer.apply(state(), started)
        val second = StreamReducer.apply(first, event("item/commandExecution/outputDelta",
            params().put("itemId", "tool-a").put("delta", "line one\n")))
        val third = StreamReducer.apply(second, event("item/commandExecution/outputDelta",
            params().put("itemId", "tool-a").put("delta", "line two\n")))

        val tool = third.messages.flatMap { it.tools }.single()
        assertEquals("tool-a", tool.id)
        assertEquals("commandExecution", tool.name)
        assertEquals("inspect fixtures", tool.input)
        assertEquals("line one\nline two\n", tool.output)
        assertEquals(1, third.messages.size)
    }

    @Test
    fun toolCompletionWithoutAggregatedOutputKeepsAlreadyStreamedOutput() {
        val tool = ToolCall("tool-a", "commandExecution", "inProgress", "inspect fixtures", "line one\n")
        val original = state().copy(messages = listOf(ChatMessage("message-a", "assistant", "Inspecting", tools = listOf(tool))))
        val complete = event("item/completed", params().put("item", JSONObject()
            .put("type", "commandExecution").put("id", "tool-a").put("command", "inspect fixtures")
            .put("status", "completed")))

        val updated = StreamReducer.apply(original, complete)

        assertEquals("Inspecting", updated.messages.single().text)
        assertEquals("completed", updated.messages.single().tools.single().status)
        assertEquals("line one\n", updated.messages.single().tools.single().output)
    }

    @Test
    fun globalActivitySnapshotUpdatesTaskIdsAndLeavesConversationIntact() {
        val original = state().copy(messages = listOf(ChatMessage("message-a", "user", "Question")))
        val snapshot = JSONObject("""
            {"type":"bridge_thread_activity_snapshot","active_thread_ids":["thread-b"],
             "thread_states":{"thread-a":"completed","thread-b":"active"}}
        """.trimIndent())

        val updated = StreamReducer.apply(original, snapshot)

        assertEquals(setOf("thread-b"), updated.activeSessionIds)
        assertEquals("active", updated.sessions.single { it.id == "thread-b" }.status)
        assertEquals(original.messages, updated.messages)
        assertEquals(original.selected, updated.selected)
    }
}
