package com.y2yy.codex.mobile.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Native pinned order is shared with the web client; unpinned entries retain ls order. */
object SessionOrdering {
    fun pins(response: JSONObject?): JSONObject? {
        val ids = response?.optJSONArray("thread_ids") ?: return null
        val ordered = linkedSetOf<String>()
        for (index in 0 until ids.length()) {
            val id = ids.opt(index) as? String ?: continue
            if (id.isNotBlank()) ordered += id
        }
        return JSONObject().put("thread_ids", JSONArray(ordered))
    }

    fun cachedPins(snapshot: JSONObject): JSONObject? = snapshot.optJSONArray("pinned_thread_ids")?.let {
        pins(JSONObject().put("thread_ids", it))
    }

    fun snapshot(listing: JSONObject, pins: JSONObject?, confirmedPins: JSONObject? = null): JSONObject {
        val result = JSONObject(listing.toString())
        val ids = pins?.optJSONArray("thread_ids") ?: confirmedPins?.optJSONArray("thread_ids") ?: return result
        val rank = linkedMapOf<String, Int>()
        for (index in 0 until ids.length()) {
            val id = ids.opt(index) as? String ?: continue
            if (id.isNotBlank() && id !in rank) rank[id] = rank.size
        }
        val threads = result.optJSONArray("threads")?.objects() ?: return result
        val ordered = threads.withIndex().sortedWith(compareBy(
            { rank[it.value.string("id")] ?: Int.MAX_VALUE }, { it.index }
        )).map { it.value }
        // Cache the same ordered JSON that drives the screen, including canonical metadata.
        return result.put("threads", JSONArray(ordered)).put("pinned_thread_ids", JSONArray(rank.keys))
    }
}

/** Coalesces bursts while retaining one more refresh when a change arrives during an RPC read. */
class SessionRefreshQueue(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 350,
    private val refresh: suspend () -> Unit
) {
    private var job: Job? = null
    private var requested = false

    fun request() {
        requested = true
        if (job?.isActive == true) return
        job = scope.launch {
            do {
                requested = false
                delay(debounceMillis)
                try { refresh() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A later event or foreground poll retries the read. */ }
            } while (requested)
        }
    }
}

/** A slower, older list read must never overwrite a newer list or its encrypted cache. */
class SessionRefreshGate {
    private val revision = AtomicLong()
    private val publishLock = Mutex()

    fun begin(): Long = revision.incrementAndGet()

    suspend fun publish(ticket: Long, block: suspend () -> Unit): Boolean = publishLock.withLock {
        if (ticket != revision.get()) return@withLock false
        block()
        true
    }
}
