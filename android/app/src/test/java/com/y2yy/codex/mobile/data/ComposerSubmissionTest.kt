package com.y2yy.codex.mobile.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ComposerSubmissionTest {
    private fun submission() = JSONObject().put("thread_id", "qa-thread")
        .put("text", "follow up").put("submission_id", "submission-a")
        .put("attachments", JSONArray().put(JSONObject().put("type", "image").put("url", "data:image/png;base64,AAAA")))

    @Test fun activeServerReceivesSteerEvenWhenTheUiCouldBeStale() = runBlocking {
        val seen = mutableListOf<JSONObject>()
        ComposerSubmission.submit(submission(), "steer") { seen += it; JSONObject().put("status", "steered") }
        assertEquals(listOf("steer"), seen.map { it.getString("command") })
    }

    @Test fun definitivelyIdleServerFallsBackOnceWithIdenticalIdentityTextAndImages() = runBlocking {
        val seen = mutableListOf<JSONObject>()
        ComposerSubmission.submit(submission(), "steer") {
            seen += it
            if (seen.size == 1) throw BridgeException("no_active_turn", "idle")
            JSONObject().put("status", "accepted")
        }
        assertEquals(listOf("steer", "send"), seen.map { it.getString("command") })
        for (key in listOf("thread_id", "text", "submission_id", "attachments"))
            assertEquals(seen[0].get(key).toString(), seen[1].get(key).toString())
    }

    @Test fun explicitQueueNeverBecomesSteer() = runBlocking {
        val seen = mutableListOf<String>()
        ComposerSubmission.submit(submission(), "queue") { seen += it.getString("command"); JSONObject().put("status", "queued") }
        assertEquals(listOf("send"), seen)
    }

    @Test fun ownershipProtocolAndTimeoutErrorsDoNotReplayTheMutation() = runBlocking {
        for (code in listOf("thread_writer_conflict", "thread_read_only", "app_server_timeout", "app_server_protocol_error", "request_failed")) {
            var calls = 0
            try {
                ComposerSubmission.submit(submission(), "steer") { calls++; throw BridgeException(code, "rejected") }
                fail("Expected failure for $code")
            } catch (failure: BridgeException) { assertEquals(code, failure.code) }
            assertEquals(1, calls)
        }
    }

    @Test fun connectionLossDoesNotReplayAndFailedFallbackIsNotRetried() = runBlocking {
        var calls = 0
        try {
            ComposerSubmission.submit(submission(), "steer") { calls++; throw IOException("lost") }
            fail("Expected network failure")
        } catch (_: IOException) { assertEquals(1, calls) }
        calls = 0
        try {
            ComposerSubmission.submit(submission(), "steer") { calls++; throw BridgeException("no_active_turn", "idle") }
            fail("Expected rejected fallback")
        } catch (_: BridgeException) { assertEquals(2, calls) }
    }

    @Test fun defaultSubmissionAlwaysSteersBeforeTheServerDecidesIdle() = runBlocking {
        val commands = mutableListOf<String>()
        ComposerSubmission.submit(submission()) {
            commands += it.getString("command")
            JSONObject().put("status", "steered")
        }
        assertEquals(listOf("steer"), commands)
    }

    @Test fun failedOrUnknownReceiptNeverAcknowledgesOrResubmits() = runBlocking {
        for (status in listOf("failed", "", "unknown")) {
            var calls = 0
            try {
                ComposerSubmission.submit(submission(), "steer") { calls++; JSONObject().put("status", status) }
                fail("A transport success must not acknowledge $status")
            } catch (failure: BridgeException) {
                assertEquals(if (status == "failed") "submission_failed" else "submission_unconfirmed", failure.code)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun partialQueueAddIsAcknowledgedOnceWithoutRetryingStartup() = runBlocking {
        var calls = 0
        val receipt = ComposerSubmission.submit(submission(), "queue") {
            calls++
            JSONObject().put("status", "queued").put("partial", true)
                .put("start_error", JSONObject().put("code", "app_server_timeout").put("message", "start unknown"))
        }
        assertEquals(1, calls)
        assertTrue(receipt.getBoolean("partial"))
        assertEquals("queued", receipt.getString("status"))
    }

    @Test fun foreignThreadOrSubmissionReceiptCannotClearTheCurrentDraft() = runBlocking {
        for ((key, value) in listOf("thread_id" to "other-thread", "pending_id" to "other-submission")) {
            var calls = 0
            try {
                ComposerSubmission.submit(submission(), "steer") { calls++; JSONObject().put("status", "accepted").put(key, value) }
                fail("A receipt for a different identity must not acknowledge this draft")
            } catch (failure: BridgeException) { assertEquals("submission_unconfirmed", failure.code) }
            assertEquals(1, calls)
        }
    }

    @Test fun unknownModeDoesNotSilentlyQueue() = runBlocking {
        var calls = 0
        try {
            ComposerSubmission.submit(submission(), "unexpected") { calls++; JSONObject().put("status", "queued") }
            fail("Unknown mode must fail before mutation")
        } catch (_: IllegalArgumentException) { assertEquals(0, calls) }
    }
}
