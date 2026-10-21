package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionActivityTest {
    private fun fixture(selected: String? = "coordinator") = AppUiState(loading = false, connected = true,
        selected = selected?.let { Session(it, it) },
        sessions = listOf(Session("coordinator", "统筹"), Session("worker", "合成工作会话")),
        messages = listOf(ChatMessage("keep", "user", "Keep the open conversation")),
        attachments = listOf(Attachment("content://fixture/image", "fixture.png", "image/png")))

    private fun ack(turn: String = "turn-a", sequence: Long = 10, id: String = "worker") = JSONObject()
        .put("type", "bridge_thread_run_state").put("thread_id", id).put("turn_id", turn)
        .put("run_state", "active").put("bridge_sequence", sequence)

    private fun snapshot(status: String, sequence: Long, turn: String = "turn-a") = JSONObject()
        .put("type", "bridge_thread_activity_snapshot")
        .put("active_thread_ids", JSONArray(if (status == "active") listOf("worker") else emptyList<String>()))
        .put("thread_states", JSONObject().put("worker", status))
        .put("thread_turn_ids", JSONObject().put("worker", turn))
        .put("thread_sequences", JSONObject().put("worker", sequence))

    private fun complete(turn: String = "turn-a", sequence: Long = 20, status: String = "completed") = JSONObject()
        .put("type", "app_server").put("bridge_sequence", sequence).put("message", JSONObject()
            .put("method", "turn/completed").put("params", JSONObject().put("threadId", "worker")
                .put("turn", JSONObject().put("id", turn).put("status", status))))

    @Test fun delegatedReceiptUpdatesListAndTasksWithoutNavigatingOrChangingMessages() {
        val original = fixture()
        val updated = StreamReducer.apply(original, ack())
        assertEquals(setOf("worker"), updated.activeSessionIds)
        assertEquals("active", updated.sessions.single { it.id == "worker" }.status)
        assertEquals("turn-a", updated.sessionRunStates["worker"]?.turnId)
        assertEquals(original.selected, updated.selected)
        assertSame(original.messages, updated.messages)
        assertSame(original.attachments, updated.attachments)
        assertFalse(updated.running)
    }

    @Test fun receiptForSelectedConversationShowsExecutionImmediately() {
        val updated = StreamReducer.apply(fixture("worker"), ack())
        assertTrue(updated.running)
        assertEquals("turn-a", updated.activeTurnId)
        assertEquals("正在思考", updated.activity)
    }

    @Test fun delayedIdleSnapshotCannotClearAJustDispatchedWorker() {
        val current = StreamReducer.apply(fixture("worker"), ack("turn-new", 12))
        val updated = StreamReducer.apply(current, snapshot("completed", 10, "turn-old"))
        assertTrue(updated.running)
        assertEquals("turn-new", updated.activeTurnId)
        assertTrue("worker" in updated.activeSessionIds)
    }

    @Test fun delayedActiveSnapshotCannotRestoreACompletedWorker() {
        val current = StreamReducer.apply(StreamReducer.apply(fixture("worker"), ack()), complete())
        val updated = StreamReducer.apply(current, snapshot("active", 10))
        assertFalse(updated.running)
        assertNull(updated.activeTurnId)
        assertFalse("worker" in updated.activeSessionIds)
    }

    @Test fun oldBackgroundCompletionCannotStopItsSuccessor() {
        val first = StreamReducer.apply(fixture(), ack())
        val next = StreamReducer.apply(first, ack("turn-b", 15))
        val old = StreamReducer.apply(next, complete("turn-a", 18))
        assertEquals(next, old)
        val done = StreamReducer.apply(old, complete("turn-b", 21))
        assertFalse("worker" in done.activeSessionIds)
        assertEquals("completed", done.sessions.single { it.id == "worker" }.status)
    }

    @Test fun oldSelectedCompletionCannotClearItsSuccessorOrStreamingMessages() {
        val current = StreamReducer.apply(fixture("worker"), ack("turn-b", 15)).copy(
            messages = listOf(ChatMessage("live", "assistant", "reply", streaming = true, turnId = "turn-b")))
        assertEquals(current, StreamReducer.apply(current, complete("turn-a", 18)))
    }

    @Test fun outOfOrderSelectedCompletionCannotBypassTheActivitySequenceGuard() {
        val current = StreamReducer.apply(fixture("worker"), ack("turn-a", 30))
        assertEquals(current, StreamReducer.apply(current, complete("turn-a", 20)))
    }

    @Test fun readStartedBeforeAnAcknowledgementCannotOverwriteIt() {
        val original = fixture("worker")
        val read = SessionActivity.capture(original, "worker", 5)
        val current = StreamReducer.apply(original, ack())
        assertEquals(current, SessionActivity.applyRead(current, read, "idle"))
    }

    @Test fun readStartedBeforeCompletionCannotRestoreRunning() {
        val original = StreamReducer.apply(fixture("worker"), ack())
        val read = SessionActivity.capture(original, "worker", 12)
        val current = StreamReducer.apply(original, complete())
        assertEquals(current, SessionActivity.applyRead(current, read, "active"))
    }

    @Test fun sessionListRefreshKeepsTheLatestActivityProjection() {
        val original = fixture()
        val read = SessionActivity.capture(original, "worker", 5)
        val current = StreamReducer.apply(original, ack())
        val updated = SessionActivity.mergeSessions(current, original.sessions,
            listOf(SessionActivity.Observation(read, "idle")))
        assertEquals("active", updated.sessions.single { it.id == "worker" }.status)
        assertTrue("worker" in updated.activeSessionIds)
    }

    @Test fun nativeReadRecoversAMissedCompletionForEveryPage() {
        val original = StreamReducer.apply(fixture("worker"), ack())
        val read = SessionActivity.capture(original, "worker", 30)
        val updated = SessionActivity.applyRead(original, read, "idle")
        assertFalse(updated.running)
        assertNull(updated.activeTurnId)
        assertFalse("worker" in updated.activeSessionIds)
        assertEquals("idle", updated.sessions.single { it.id == "worker" }.status)
        assertEquals("已完成", updated.activity)
    }

    @Test fun oldGlobalSnapshotCannotUndoANewerDirectNativeRead() {
        val original = StreamReducer.apply(fixture("worker"), ack())
        val read = SessionActivity.capture(original, "worker", 30)
        val current = SessionActivity.applyRead(original, read, "idle")
        val updated = StreamReducer.apply(current, snapshot("active", 10))
        assertFalse(updated.running)
        assertFalse("worker" in updated.activeSessionIds)
    }

    @Test fun failedOrUnknownNativeReadsRetainTheLastConfirmedState() {
        val original = StreamReducer.apply(fixture("worker"), ack())
        val token = SessionActivity.capture(original, "worker", 15)
        for (status in listOf(null, "", "notLoaded", "future-unknown")) {
            assertEquals(original, SessionActivity.applyRead(original, token, status))
        }
    }

    @Test fun nativeActiveReadCannotReuseTheIdentityOfAnAlreadyCompletedTurn() {
        val finished = StreamReducer.apply(StreamReducer.apply(fixture("worker"), ack()), complete())
        val token = SessionActivity.capture(finished, "worker", 30)
        val running = SessionActivity.applyRead(finished, token, "active")
        assertTrue(running.running)
        assertNull(running.activeTurnId)
        assertNull(running.sessionRunStates["worker"]?.turnId)
    }

    @Test fun reconnectResetsCursorsAndRejectsAnEarlierPendingRead() {
        val original = StreamReducer.apply(fixture("worker"), ack("old-turn", 500))
        val read = SessionActivity.capture(original, "worker", 510)
        val reset = StreamReducer.apply(original, JSONObject().put("type", "bridge_event_stream").put("status", "ready").put("sequence", 1))
        assertEquals(reset, SessionActivity.applyRead(reset, read, "idle"))
        val recovered = StreamReducer.apply(reset, snapshot("active", 2, "new-turn"))
        assertEquals("new-turn", recovered.activeTurnId)
        val finished = StreamReducer.apply(recovered, snapshot("completed", 3, "new-turn"))
        assertFalse(finished.running)
    }

    @Test fun authoritativeEmptySnapshotAfterReconnectClearsAnOldRunningFlag() {
        val original = StreamReducer.apply(fixture("worker"), ack())
        val reset = StreamReducer.apply(original, JSONObject().put("type", "bridge_event_stream").put("status", "ready"))
        val updated = StreamReducer.apply(reset, JSONObject().put("type", "bridge_thread_activity_snapshot").put("active_thread_ids", JSONArray()).put("thread_states", JSONObject()))
        assertFalse(updated.running)
        assertTrue(updated.activeSessionIds.isEmpty())
    }

    @Test fun newerSnapshotRecoversAMissedStartAndItsNativeTurnIdentity() {
        val current = StreamReducer.apply(fixture("worker"), ack("turn-a", 10))
        val updated = StreamReducer.apply(current, snapshot("active", 35, "turn-b"))
        assertTrue(updated.running)
        assertEquals("turn-b", updated.activeTurnId)
    }

    @Test fun nativeFailureAndCancellationKeepTheirTerminalOutcome() {
        for ((native, expected, label) in listOf(Triple("failed", "failed", "执行失败"), Triple("interrupted", "cancelled", "已停止"))) {
            val updated = StreamReducer.apply(StreamReducer.apply(fixture("worker"), ack()), complete(status = native))
            assertEquals(expected, updated.sessions.single { it.id == "worker" }.status)
            assertEquals(label, updated.activity)
            assertFalse(updated.running)
        }
    }

    @Test fun nativeSystemErrorClearsStaleExecutionWithoutRemovingConversationOrAttachments() {
        val original = StreamReducer.apply(fixture("worker"), ack())
        for (status in listOf<Any>("systemError", JSONObject().put("type", "systemError"))) {
            val event = JSONObject().put("type", "app_server").put("bridge_sequence", 20)
                .put("message", JSONObject().put("method", "thread/status/changed")
                    .put("params", JSONObject().put("threadId", "worker").put("status", status)))
            val failed = StreamReducer.apply(original, event)
            assertFalse(failed.running)
            assertNull(failed.activeTurnId)
            assertFalse("worker" in failed.activeSessionIds)
            assertEquals("failed", failed.sessions.single { it.id == "worker" }.status)
            assertEquals("执行失败", failed.activity)
            assertSame(original.messages, failed.messages)
            assertSame(original.attachments, failed.attachments)
            assertEquals(original.selected, failed.selected)
        }
    }

    @Test fun nativeSystemErrorReadAndReconnectSnapshotAgreeOnFailure() {
        val original = StreamReducer.apply(fixture("worker"), ack())
        val token = SessionActivity.capture(original, "worker", 25)
        val read = SessionActivity.applyRead(original, token, SessionActivity.statusType(JSONObject().put("type", "systemError")))
        val reconnect = StreamReducer.apply(original, JSONObject().put("type", "bridge_event_stream").put("status", "ready"))
        val restored = StreamReducer.apply(reconnect, snapshot("systemError", 25))
        for (failed in listOf(read, restored)) {
            assertFalse(failed.running)
            assertEquals("failed", failed.sessionRunStates["worker"]?.status)
            assertEquals("执行失败", failed.activity)
            assertSame(original.messages, failed.messages)
        }
    }

    @Test fun recoveredTurnRemainsActiveWhenAnOldFailureOrCompletionArrives() {
        val original = StreamReducer.apply(fixture("worker"), ack())
        val failed = SessionActivity.applyRead(original, SessionActivity.capture(original, "worker", 20), "systemError")
        val recovered = StreamReducer.apply(failed, ack("turn-recovered", 30))
        val delayed = StreamReducer.apply(recovered, snapshot("systemError", 20))
        assertEquals(recovered.sessionRunStates["worker"], delayed.sessionRunStates["worker"])
        assertTrue(delayed.running)
        assertEquals("turn-recovered", delayed.activeTurnId)
        assertSame(original.messages, delayed.messages)
        assertEquals(recovered, StreamReducer.apply(recovered, complete("turn-a", 35, "failed")))
        assertTrue(recovered.running)
        assertEquals("turn-recovered", recovered.activeTurnId)
        assertSame(original.messages, recovered.messages)
    }

    @Test fun malformedSnapshotsAndUnconfirmedReceiptsNeverFabricateExecution() {
        val original = fixture()
        assertEquals(original, StreamReducer.apply(original, JSONObject().put("type", "bridge_thread_activity_snapshot")))
        for (invalid in listOf(ack(turn = ""), ack().put("run_state", "queued"), ack(id = ""))) {
            assertEquals(original, StreamReducer.apply(original, invalid))
        }
    }

    @Test fun duplicateReceiptKeepsCurrentToolPhaseAndMessageIdentity() {
        val original = StreamReducer.apply(fixture("worker"), ack()).copy(activity = "正在执行：fixture-tool")
        val updated = StreamReducer.apply(original, ack(sequence = 12))
        assertEquals(original.activity, updated.activity)
        assertSame(original.messages, updated.messages)
    }

    @Test fun stringAndObjectNativeStatusesUseTheSameSelectedState() {
        for (status in listOf<Any>("idle", JSONObject().put("type", "idle"))) {
            val current = StreamReducer.apply(fixture("worker"), ack())
            val event = JSONObject().put("type", "app_server").put("bridge_sequence", 20).put("message", JSONObject()
                .put("method", "thread/status/changed").put("params", JSONObject().put("threadId", "worker").put("status", status)))
            val updated = StreamReducer.apply(current, event)
            assertFalse(updated.running)
            assertNull(updated.activeTurnId)
            assertFalse("worker" in updated.activeSessionIds)
        }
    }
}
