package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.PendingInput
import com.y2yy.codex.mobile.model.PendingSnapshot
import com.y2yy.codex.mobile.model.Session
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PendingMessagesTest {
    private fun entry(
        id: String = "submission-a",
        threadId: String = "thread-a",
        status: String = "queued",
        source: String = "app_server_queue"
    ) = JSONObject().put("id", id).put("thread_id", threadId).put("text", "same intentional input")
        .put("status", status).put("action", "queue").put("source", source)
        .put("queued_submission_id", "native-$id")

    private fun response(vararg entries: JSONObject) = JSONObject()
        .put("messages", JSONArray(entries.toList())).put("queue_available", true)
        .put("queue_authoritative", true).put("queue_backend", "app_server")

    private fun state() = AppUiState(
        selected = Session("thread-a", "Own thread"),
        pending = listOf(PendingInput("old", "keep this input", "queued", "queue", "thread-a", "app_server_queue")),
        pendingVerified = true, pendingDeletingIds = setOf("old"),
        messages = listOf(ChatMessage("message-a", "assistant", "Current progress")),
        running = true, activeTurnId = "turn-a"
    )

    @Test fun nativeQueueIdentityAndStatusAreParsedWithoutUsingHistoricalActionAsState() {
        val snapshot = BridgeParser.pendingSnapshot(response(entry(), entry("accepted", status = "accepted")), "thread-a")
        assertTrue(snapshot.available)
        assertTrue(snapshot.authoritative)
        assertNull(snapshot.error)
        val queued = snapshot.messages[0]
        assertEquals("thread-a", queued.threadId)
        assertEquals("app_server_queue", queued.source)
        assertEquals("native-submission-a", queued.queuedSubmissionId)
        assertEquals("排队中", PendingMessages.label(queued, true))
        assertTrue(PendingMessages.canCancel(queued, true))
        val accepted = snapshot.messages[1]
        assertEquals("queue", accepted.action)
        assertEquals("已提交 · 等待历史同步", PendingMessages.label(accepted, true))
        assertFalse(PendingMessages.canCancel(accepted, true))
    }

    @Test fun partialStartFailureKeepsTheQueuedReceiptAndExplainsTheWarning() {
        val item = entry().put("start_error", JSONObject().put("code", "app_server_timeout").put("message", "Start timed out"))
        val pending = BridgeParser.pendingSnapshot(response(item), "thread-a").messages.single()
        assertTrue(pending.partial)
        assertEquals("Start timed out", pending.startError)
        assertEquals("Start timed out", PendingMessages.warning(pending))
        assertEquals("queued", pending.status)
        assertEquals("排队中", PendingMessages.label(pending, true))
        assertTrue(PendingMessages.canCancel(pending, true))
        assertFalse(PendingMessages.canCancel(pending, false))
        assertEquals("同步未确认", PendingMessages.label(pending, false))
    }

    @Test fun failedAndCompletedReceiptsNeverBecomeWithdrawableEvenWithQueueAction() {
        val expected = mapOf(
            "failed" to "发送失败", "steered" to "已跟进", "applied" to "已同步到会话",
            "processing" to "处理中", "dequeued" to "已提交 · 等待历史同步", "future_state" to "同步未确认"
        )
        for ((status, label) in expected) {
            val item = BridgeParser.pendingSnapshot(response(entry(status = status)), "thread-a").messages.single()
            assertEquals(label, PendingMessages.label(item, true))
            assertFalse("$status cannot be cancelled", PendingMessages.canCancel(item, true))
        }
        val failed = BridgeParser.pendingSnapshot(response(entry(status = "failed").put("error", "Original rejection")), "thread-a").messages.single()
        assertEquals("Original rejection", PendingMessages.warning(failed))
        assertEquals("已跟进", PendingMessages.label(failed.copy(status = "accepted", action = "steer"), true))
    }

    @Test fun missingFlagsAndMissingSourceAreConservativeForOlderServers() {
        val old = JSONObject().put("messages", JSONArray().put(entry().removeField("source")))
        val snapshot = BridgeParser.pendingSnapshot(old, "thread-a")
        assertFalse(snapshot.available)
        assertFalse(snapshot.authoritative)
        assertNotNull(snapshot.error)
        assertFalse(PendingMessages.canCancel(snapshot.messages.single(), true))
        val before = state()
        val after = PendingMessages.reduce(before, snapshot, "thread-a", 8, 8)
        assertEquals(before.pending, after.pending)
        assertFalse(after.pendingVerified)
        assertNotNull(after.pendingSyncError)
    }

    @Test fun readableCompatibilityEmptyAndUnavailableEmptyDoNotEraseLastGoodQueue() {
        val before = state()
        val compatibility = response().put("queue_backend", "compatibility").put("queue_authoritative", false)
        val unavailable = response().put("queue_available", false).put("queue_authoritative", false)
            .put("queue_error", JSONObject().put("code", "queue_state_unavailable").put("message", "Queue read unavailable"))
        for (body in listOf(compatibility, unavailable)) {
            val snapshot = BridgeParser.pendingSnapshot(body, "thread-a")
            val after = PendingMessages.reduce(before, snapshot, "thread-a", 4, 4)
            assertEquals(before.pending, after.pending)
            assertFalse(after.pendingVerified)
            assertNotNull(after.pendingSyncError)
            assertEquals(before.messages, after.messages)
            assertTrue(after.running)
            assertEquals("turn-a", after.activeTurnId)
            assertEquals(setOf("old"), after.pendingDeletingIds)
        }
        assertEquals("Queue read unavailable", BridgeParser.pendingSnapshot(unavailable, "thread-a").error)
    }

    @Test fun authoritativeEmptyClearsOnlyTheSelectedQueueAndItsSyncError() {
        val before = state().copy(pendingSyncError = "previous read failed", pendingVerified = false)
        val after = PendingMessages.reduce(before, BridgeParser.pendingSnapshot(response(), "thread-a"), "thread-a", 4, 4)
        assertTrue(after.pending.isEmpty())
        assertTrue(after.pendingVerified)
        assertNull(after.pendingSyncError)
        assertEquals(before.messages, after.messages)
        assertEquals(before.selected, after.selected)
        assertEquals(before.running, after.running)
        assertEquals(before.activeTurnId, after.activeTurnId)
    }

    @Test fun crossThreadOrUnscopedRowsCannotVerifyOrClearTheCurrentThread() {
        val before = state()
        for (body in listOf(response(entry(threadId = "thread-b")), response(entry().removeField("thread_id")), response(entry(), entry("other", "thread-b")))) {
            val snapshot = BridgeParser.pendingSnapshot(body, "thread-a")
            assertFalse(snapshot.available)
            assertFalse(snapshot.authoritative)
            assertTrue(snapshot.messages.all { it.threadId == "thread-a" })
            assertEquals(before.pending, PendingMessages.reduce(before, snapshot, "thread-a", 2, 2).pending)
        }
        // The reducer also rejects foreign messages if a caller constructs a snapshot directly.
        val foreign = PendingSnapshot(listOf(before.pending.single().copy(threadId = "thread-b")), true, true)
        assertEquals(before.pending, PendingMessages.reduce(before, foreign, "thread-a", 2, 2).pending)
    }

    @Test fun aToBToASelectionDoesNotAcceptTheFirstAResponse() {
        val before = state()
        val snapshot = BridgeParser.pendingSnapshot(response(entry("new")), "thread-a")
        assertSame(before, PendingMessages.reduce(before, snapshot, "thread-a", requestGeneration = 1, currentGeneration = 3))
        assertSame(before, PendingMessages.reduce(before, snapshot, "thread-b", requestGeneration = 3, currentGeneration = 3))
        val newest = PendingMessages.reduce(before, snapshot, "thread-a", requestGeneration = 3, currentGeneration = 3)
        assertEquals(listOf("new"), newest.pending.map { it.id })
        assertTrue(newest.pendingVerified)
    }

    @Test fun oldAuthoritativeEmptyReadCannotEraseANewerAcknowledgedSubmission() {
        val acknowledged = state().copy(
            pending = listOf(PendingInput("new-ack", "new message", "queued", "steer", "thread-a", "client_receipt")),
            pendingVerified = false
        )
        val oldEmpty = BridgeParser.pendingSnapshot(response(), "thread-a")
        assertTrue(oldEmpty.authoritative)
        val after = PendingMessages.reduce(
            acknowledged, oldEmpty, "thread-a", requestGeneration = 5, currentGeneration = 5,
            requestRevision = 1, currentRevision = 2
        )
        assertSame(acknowledged, after)
        assertEquals("new-ack", after.pending.single().id)
        assertFalse(after.pendingVerified)
    }

    @Test fun malformedArraysStringFlagsAndConflictingIdentitiesNeverProveAnEmptyQueue() {
        val malformed = response().put("messages", JSONArray().put("not an entry"))
        val missing = response().removeField("messages")
        val stringFlags = response().put("queue_available", "true").put("queue_authoritative", "true")
        val conflicting = response(entry(), entry().put("text", "different payload for same id"))
        for (body in listOf(malformed, missing, stringFlags, conflicting)) {
            val snapshot = BridgeParser.pendingSnapshot(body, "thread-a")
            assertFalse(snapshot.authoritative)
            assertNotNull(snapshot.error)
            assertEquals(state().pending, PendingMessages.reduce(state(), snapshot, "thread-a", 1, 1).pending)
        }
    }

    @Test fun identicalTextsWithDifferentSubmissionIdsRemainSeparateMessages() {
        val snapshot = BridgeParser.pendingSnapshot(response(entry("first"), entry("second")), "thread-a")
        assertTrue(snapshot.authoritative)
        assertEquals(listOf("first", "second"), snapshot.messages.map { it.id })
        assertEquals(1, snapshot.messages.map { it.text }.distinct().size)
        val duplicate = BridgeParser.pendingSnapshot(response(entry(), entry()), "thread-a")
        assertTrue(duplicate.authoritative)
        assertEquals(1, duplicate.messages.size)
    }

    @Test fun compatibilityAndUnrecognizedSourcesNeverExposeCancelEvenWhenFlagsAreTrue() {
        for (source in listOf("", "codex_queue", "bridge_cli", "future_source")) {
            val pending = BridgeParser.pendingSnapshot(response(entry(source = source)), "thread-a").messages.single()
            assertFalse(PendingMessages.canCancel(pending, true))
            assertEquals("同步未确认", PendingMessages.label(pending, true))
        }
        val contradictory = response().put("queue_backend", "compatibility")
        assertFalse(BridgeParser.pendingSnapshot(contradictory, "thread-a").authoritative)
    }

    private fun JSONObject.removeField(key: String): JSONObject = apply { remove(key) }
}
