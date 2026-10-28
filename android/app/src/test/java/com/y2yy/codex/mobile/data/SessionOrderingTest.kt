package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic snapshots only: sorting must never start a model turn or change the server. */
class SessionOrderingTest {
    @Test fun nativePinnedOrderWinsOverRecentActivityAndTitles() {
        val listing = listing("latest", "finance", "control")
        val result = SessionOrdering.snapshot(listing, pins("control", "finance"))

        assertEquals(listOf("control", "finance", "latest"), ids(result))
        assertEquals(listOf("latest", "finance", "control"), ids(listing))
    }

    @Test fun unpinnedEntriesRetainTheirServerOrder() {
        val result = SessionOrdering.snapshot(listing("recent-b", "control", "recent-a", "old"), pins("control"))

        assertEquals(listOf("control", "recent-b", "recent-a", "old"), ids(result))
    }

    @Test fun missingArchivedDuplicateAndInvalidPinIdsNeverDropOrDuplicateSessions() {
        val response = JSONObject().put("thread_ids", JSONArray().put("archived").put("finance")
            .put("finance").put(JSONObject.NULL).put(3).put("").put("control"))
        val result = SessionOrdering.snapshot(listing("latest", "control", "finance"), response)

        assertEquals(listOf("finance", "control", "latest"), ids(result))
        assertEquals(3, BridgeParser.sessions(result).size)
    }

    @Test fun anEmptyNativePinnedSectionKeepsTheWholeServerList() {
        val result = SessionOrdering.snapshot(listing("b", "a"), pins())

        assertEquals(listOf("b", "a"), ids(result))
        assertEquals(0, result.getJSONArray("pinned_thread_ids").length())
    }

    @Test fun olderBridgeOrFailedPinReadFallsBackWithoutClearingTheList() {
        for (response in listOf(null, JSONObject(), JSONObject().put("thread_ids", JSONObject.NULL),
            JSONObject().put("thread_ids", "unsupported"))) {
            val result = SessionOrdering.snapshot(listing("b", "a"), response)

            assertEquals(listOf("b", "a"), ids(result))
            assertFalse(result.has("pinned_thread_ids"))
        }
    }

    @Test fun serializedCacheRestoresTheDisplayedOrderAndSessionMetadata() {
        val ordered = SessionOrdering.snapshot(listing("latest", "finance", "control"), pins("control", "finance"))
        val cached = JSONObject(ordered.toString())

        assertEquals(ids(ordered), BridgeParser.sessions(cached).map { it.id })
        assertEquals(listOf("control", "finance"), cached.getJSONArray("pinned_thread_ids").let { array ->
            (0 until array.length()).map { array.getString(it) }
        })
        val session = BridgeParser.sessions(cached).first()
        assertEquals("Title control", session.title)
        assertEquals("/workspace/control", session.projectPath)
        assertEquals("active", session.status)
        assertEquals("fixture-model", session.model)
    }

    @Test fun aTemporaryPinRpcFailureRetainsConfirmedOrderForTheFreshListAndCache() {
        val previous = SessionOrdering.snapshot(listing("finance", "control", "old"), pins("control", "finance"))
        val confirmed = SessionOrdering.cachedPins(JSONObject(previous.toString()))
        val fresh = SessionOrdering.snapshot(listing("new", "finance", "control", "old"), null, confirmed)

        assertEquals(listOf("control", "finance", "new", "old"), ids(fresh))
        val restored = JSONObject(fresh.toString())
        assertEquals(ids(fresh), ids(restored))
        assertEquals(listOf("control", "finance"),
            SessionOrdering.cachedPins(restored)!!.getJSONArray("thread_ids").let { array ->
                (0 until array.length()).map { array.getString(it) }
            })
    }

    @Test fun confirmedEmptyNativeOrderReplacesThePreviouslyCachedPins() {
        val previous = SessionOrdering.cachedPins(SessionOrdering.snapshot(listing("b", "a"), pins("a")))
        val fresh = SessionOrdering.snapshot(listing("b", "a"), SessionOrdering.pins(pins()), previous)

        assertEquals(listOf("b", "a"), ids(fresh))
        assertEquals(0, SessionOrdering.cachedPins(fresh)!!.getJSONArray("thread_ids").length())
    }

    @Test fun malformedPinResponsePreservesConfirmedPinsWhileFirstConnectionFallsBack() {
        val invalid = JSONObject().put("thread_ids", "temporarily-unavailable")
        val known = SessionOrdering.pins(pins("control", "finance"))

        assertNull(SessionOrdering.pins(invalid))
        assertEquals(listOf("control", "finance"), ids(SessionOrdering.snapshot(
            listing("finance", "control"), SessionOrdering.pins(invalid), known)))
        assertEquals(listOf("finance", "control"), ids(SessionOrdering.snapshot(
            listing("finance", "control"), SessionOrdering.pins(invalid))))
    }

    @Test fun orderingRefreshPreservesSelectionMessagesAndActiveTurn() {
        val originalSessions = BridgeParser.sessions(listing("finance", "control"))
        val original = AppUiState(sessions = originalSessions, selected = originalSessions.first(),
            messages = listOf(ChatMessage("message", "assistant", "保留内容")), running = true, activeTurnId = "turn")
        val sorted = BridgeParser.sessions(SessionOrdering.snapshot(listing("finance", "control"), pins("control", "finance")))
        val result = SessionActivity.mergeSessions(original, sorted, emptyList())

        assertEquals(listOf("control", "finance"), result.sessions.map { it.id })
        assertEquals(original.selected, result.selected)
        assertEquals(original.messages, result.messages)
        assertEquals(original.activeTurnId, result.activeTurnId)
        assertEquals(original.running, result.running)
    }

    @Test fun staleConcurrentLoadCannotOverwriteANewerScreenOrCache() = runBlocking {
        val gate = SessionRefreshGate()
        val older = gate.begin()
        val newer = gate.begin()
        var screen = ""
        var cache = ""

        assertTrue(gate.publish(newer) { screen = "new"; cache = "new" })
        assertFalse(gate.publish(older) { screen = "old"; cache = "old" })
        assertEquals("new", screen)
        assertEquals("new", cache)
    }

    @Test fun cacheWritesAreSerializedWithTheScreenPublication() = runBlocking {
        val gate = SessionRefreshGate()
        val first = gate.begin()
        val writingCache = CompletableDeferred<Unit>()
        val releaseCache = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val firstWrite = async {
            gate.publish(first) {
                writes += "old-screen"
                writingCache.complete(Unit)
                releaseCache.await()
                writes += "old-cache"
            }
        }
        writingCache.await()
        val stale = gate.begin()
        val latest = gate.begin()
        val newWrite = async { gate.publish(latest) { writes += "new-screen"; writes += "new-cache" } }
        val staleWrite = async { gate.publish(stale) { writes += "stale" } }
        releaseCache.complete(Unit)

        assertTrue(firstWrite.await())
        assertTrue(newWrite.await())
        assertFalse(staleWrite.await())
        assertEquals(listOf("old-screen", "old-cache", "new-screen", "new-cache"), writes)
    }

    @Test fun aPinsEventDuringAnOldReadSchedulesAnotherReadImmediatelyAfterIt() = runBlocking {
        withTimeout(3000) {
            val gate = SessionRefreshGate()
            val firstReadStarted = CompletableDeferred<Unit>()
            val finishFirstRead = CompletableDeferred<Unit>()
            val newestPublished = CompletableDeferred<Unit>()
            var nativePins = pins("finance", "control")
            var displayed = emptyList<String>()
            var cached = ""
            var reads = 0
            val queue = SessionRefreshQueue(this, debounceMillis = 0) {
                val ticket = gate.begin()
                val observedPins = nativePins
                reads++
                if (reads == 1) {
                    firstReadStarted.complete(Unit)
                    finishFirstRead.await()
                }
                val snapshot = SessionOrdering.snapshot(listing("finance", "control"), observedPins)
                gate.publish(ticket) {
                    displayed = ids(snapshot)
                    cached = snapshot.toString()
                    newestPublished.complete(Unit)
                }
            }
            queue.request()
            firstReadStarted.await()
            nativePins = pins("control", "finance")
            gate.begin() // The pins event invalidates the in-flight read before requesting a refresh.
            queue.request()
            finishFirstRead.complete(Unit)
            newestPublished.await()

            assertEquals(2, reads)
            assertEquals(listOf("control", "finance"), displayed)
            assertEquals(displayed, ids(JSONObject(cached)))
        }
    }

    @Test fun aChangeArrivingDuringAFailedReadIsNotLost() = runBlocking {
        withTimeout(3000) {
            val firstReadStarted = CompletableDeferred<Unit>()
            val finishFirstRead = CompletableDeferred<Unit>()
            val retried = CompletableDeferred<Unit>()
            var reads = 0
            val queue = SessionRefreshQueue(this, debounceMillis = 0) {
                reads++
                if (reads == 1) {
                    firstReadStarted.complete(Unit)
                    finishFirstRead.await()
                    throw IllegalStateException("Temporary RPC failure")
                }
                retried.complete(Unit)
            }
            queue.request()
            firstReadStarted.await()
            queue.request()
            finishFirstRead.complete(Unit)
            retried.await()

            assertEquals(2, reads)
        }
    }

    private fun listing(vararg ids: String): JSONObject = JSONObject().put("threads", JSONArray(ids.map { id ->
        JSONObject().put("id", id).put("title", "Title $id").put("cwd", "/workspace/$id")
            .put("model", "fixture-model").put("status", JSONObject().put("type", "active"))
    }))

    private fun pins(vararg ids: String): JSONObject = JSONObject().put("thread_ids", JSONArray(ids.toList()))
    private fun ids(snapshot: JSONObject): List<String> = BridgeParser.sessions(snapshot).map { it.id }
}
