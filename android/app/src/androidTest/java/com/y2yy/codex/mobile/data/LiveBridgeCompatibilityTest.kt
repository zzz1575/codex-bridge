package com.y2yy.codex.mobile.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real-runtime checks. The signed app's default URL, authentication, cookie jar,
 * certificate verifier and WebSocket implementation are deliberately unchanged.
 *
 * A transport supplied by the operator must route that origin to the isolated QA Bridge.
 * Merely supplying an argument cannot authorize production: status and project roots are
 * checked before every mutation, and thread mutations require a locally created ID.
 */
@RunWith(AndroidJUnit4::class)
class LiveBridgeCompatibilityTest {
    private lateinit var api: BridgeApi
    private var qaVerified = false
    private var scope: CoroutineScope? = null
    private var eventJob: Job? = null
    private val connected = CompletableDeferred<Unit>()
    private val events = CopyOnWriteArrayList<JSONObject>()
    private val streamFailure = AtomicReference<String?>(null)
    private val ownedThreads = ConcurrentHashMap.newKeySet<String>()
    private val initialThreadIds = mutableSetOf<String>()
    private val marker = "ANDROID_QA_${UUID.randomUUID().toString().replace("-", "")}"

    @Before
    fun requireExplicitIsolatedQaRuntime() = runBlocking {
        val requestedRoot = InstrumentationRegistry.getArguments().getString("qaRoot")
        assumeTrue("Live API tests require an explicit isolated qaRoot argument", requestedRoot != null)
        assertEquals("Only the designated QA home may be used", QA_HOME, requestedRoot)
        api = BridgeApi(InstrumentationRegistry.getInstrumentation().targetContext)
        api.authenticate()
        assertQaBoundary()
        initialThreadIds += api.command(request("ls").put("limit", 200).put("include_archived", true))
            .optJSONArray("threads").objectsOrEmpty().map { it.string("id") }
        qaVerified = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { eventScope ->
            eventJob = eventScope.launch {
                try {
                    api.events().collect { event ->
                        if (event.string("type") == "native_connected") connected.complete(Unit)
                        val threadId = event.optJSONObject("message")?.optJSONObject("params")?.string("threadId")
                        if (threadId != null && threadId in ownedThreads) {
                            if (events.size >= 2_048) events.removeAt(0)
                            events += event
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    // Record only a generic failure category, never a URL, body or credential.
                    streamFailure.set("realtime_connection_failed")
                }
            }
        }
        withTimeout(15_000) { connected.await() }
        checkpoint("qa-boundary-and-realtime-ready")
    }

    @After
    fun archiveOnlyThreadsCreatedByThisTest() = runBlocking {
        eventJob?.cancelAndJoin()
        scope?.cancel()
        if (!qaVerified) return@runBlocking
        var failures = 0
        ownedThreads.forEach { id ->
            try {
                // No deletion, metadata rewrites or process operations during cleanup.
                assertQaBoundary()
                api.command(request("thread_archive").put("thread_id", id))
            } catch (_: Exception) {
                failures++
            }
        }
        checkpoint(if (failures == 0) "own-qa-threads-archived" else "own-qa-archive-failed")
        assertEquals("Every test-owned QA thread must be archived", 0, failures)
    }

    @Test(timeout = 210_000)
    fun textRealtimeSettingsRenameMoveAndImageRoundTrip() = runBlocking {
        withTimeout(180_000) {
            val status = api.command(request("status"))
            assertEquals("Protocol 43 is required for guarded project moves", 43, status.optInt("protocol_version"))
            val backend = status.getJSONObject("write_backend")
            assertEquals("0.163.0", backend.string("schema_version"))
            assertTrue("The real stable runtime must be active", backend.string("runtime_version").startsWith("0.163.0"))
            assertTrue("Bridge stable release must identify itself", backend.string("runtime_user_agent").contains("codex-bridge; 0.3.11"))
            val models = BridgeParser.modelOptions(api.command(request("composer_options")))
            assertEquals("The authenticated account should expose all seven models", EXPECTED_MODELS, models.map { it.id }.toSet())
            assertTrue("Every model must advertise usable effort options", models.all { it.efforts.isNotEmpty() })
            val primary = models.first { it.id == "gpt-6.1-sol" }
            val alternate = models.first { it.id == "gpt-6-luna" }
            val effort = primary.efforts.first { it.id == "low" }.id
            val id = createThread(primary.id)
            setModel(id, primary.id, effort)

            val textToken = "${marker}_TEXT"
            submit(id, "Reply with exactly $textToken. Do not use tools, files or network.")
            val textReply = awaitAssistantAndIdle(id, textToken, 65_000)
            val textTurnId = replyTurnId(textReply, textToken)
            awaitValue("real response deltas for the completed text turn", 8_000) {
                notifications(id, "item/agentMessage/delta").firstOrNull { it.string("turnId") == textTurnId }
            }
            awaitCompleted(id, textTurnId, "completed")
            assertEquals("The realtime connection must remain healthy", null, streamFailure.get())
            checkpoint("text-stream-history-pass")

            setModel(id, alternate.id, alternate.efforts.first { it.id == "low" }.id)
            setModel(id, primary.id, effort)
            val title = "Android QA ${marker.takeLast(12)}"
            ownedCommand(SessionCommands.rename(id, title), id)
            awaitValue("renamed native thread", 8_000) {
                readThread(id).takeIf { it.string("name") == title }
            }
            val original = readThread(id)
            val projectId = original.string("projectId")
            assertTrue("A project session must have a native assignment", projectId.isNotBlank())
            val permissions = permissionSignature(resumeSettings(id))
            val history = historySignatures(show(id))
            assertTrue("The text turn must be durable before relocation", history.isNotEmpty())

            moveAndVerify(id, QA_CHATS, assigned = false)
            assertEquals("Projectless move must preserve permissions", permissions, permissionSignature(resumeSettings(id)))
            assertHistoryPreserved(history, show(id))
            assertComposer(id, primary.id, effort)
            moveAndVerify(id, QA_PROJECT, assigned = true)
            assertEquals("Returning to the project must preserve native assignment", projectId, readThread(id).string("projectId"))
            assertEquals("Returning to the project must preserve permissions", permissions, permissionSignature(resumeSettings(id)))
            assertHistoryPreserved(history, show(id))
            assertEquals("Relocation must preserve the saved title", title, readThread(id).string("name"))
            assertComposer(id, primary.id, effort)
            checkpoint("model-rename-relocation-retention-pass")

            val colorToken = "${marker}_COLOR"
            val attachment = JSONObject().put("type", "image").put("name", "synthetic-red.png").put("url", redImage())
            submit(id, "Identify the dominant color of the attached image. Reply with exactly $colorToken:<color in English>. Do not use tools, files or network.", JSONArray().put(attachment))
            val imageReply = awaitAssistantAndIdle(id, colorToken, 65_000)
            assertTrue("The real model must recognize the uploaded red bitmap", assistantText(imageReply).contains("$colorToken:RED", ignoreCase = true))
            val page = api.command(request("messages").put("thread_id", id).put("limit", 100))
            val imageMessage = page.optJSONArray("messages").objectsOrEmpty().lastOrNull { message ->
                message.string("role") == "user" && message.toString().contains(colorToken)
            } ?: throw AssertionError("The uploaded image's user message must be indexed")
            val images = mutableListOf<String>()
            imageMessage.optJSONArray("content").objectsOrEmpty().forEach { part ->
                images += MessageImages.collect(part)
                val contentIndex = part.optInt("content_index", -1)
                if (contentIndex >= 0) {
                    val detail = request("message_content").put("thread_id", id)
                        .put("message_index", imageMessage.getInt("message_index")).put("content_index", contentIndex)
                    if (part.has("content_end")) detail.put("content_end", part.getInt("content_end"))
                    val content = api.command(detail)
                    images += MessageImages.collect(content.opt("content"))
                }
            }
            val imageBytes = images.firstNotNullOfOrNull(::decodeImageDataUrl)
                ?: throw AssertionError("Durable message_content must return the uploaded bitmap")
            val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: throw AssertionError("The historical image must decode into real pixels")
            try {
                val pixel = decoded.getPixel(decoded.width / 2, decoded.height / 2)
                assertTrue("Historical image pixels must remain red", Color.red(pixel) > 220 && Color.green(pixel) < 35 && Color.blue(pixel) < 35)
            } finally {
                decoded.recycle()
            }
            checkpoint("real-image-model-and-durable-content-pass")
        }
    }

    @Test(timeout = 180_000)
    fun activeToolAcceptsSteerQueuesSeparatelyAndInterrupts() = runBlocking {
        withTimeout(150_000) {
            val id = createThread("gpt-6.1-sol")
            setModel(id, "gpt-6.1-sol", "low")
            try {
                submit(id, "This is an isolated QA timing check. Run exactly one shell command sleep 20 with exec_command, using a short yield if supported. Wait for that same command to finish; do not read or change any files, access network, or start another command. Then reply ${marker}_SLEEP_DONE.")
                awaitValue("a genuine running sleep tool", 55_000) {
                    notifications(id, "item/started").firstOrNull { params ->
                        val item = params.optJSONObject("item")
                        item?.string("type") == "commandExecution" && item.string("command").contains("sleep 20")
                    }
                }
                val activity = api.command(request("thread_activity").put("thread_id", id)).getJSONObject("activity")
                val turnId = activity.string("active_turn_id")
                assertTrue("The tool must belong to a real active turn", turnId.isNotBlank())
                val steerBody = JSONObject().put("thread_id", id)
                    .put("submission_id", UUID.randomUUID().toString())
                    .put("text", "Keep waiting for the same sleep command. Do not start another command. When done reply ${marker}_STEER_DONE.")
                // Omit mode deliberately: the app must default to follow-up even if
                // its cached activity flag has not caught up with the real runtime.
                val steered = ComposerSubmission.submit(steerBody, command = { ownedCommand(it, id) })
                assertEquals("steered", steered.string("status"))
                val queueToken = "${marker}_QUEUE_DONE"
                val queueBody = JSONObject().put("thread_id", id)
                    .put("submission_id", UUID.randomUUID().toString())
                    .put("text", "For this separate queued turn reply exactly $queueToken. Do not use any tools.")
                val queued = ComposerSubmission.submit(queueBody, mode = "queue", command = { ownedCommand(it, id) })
                assertEquals("Explicit send during an active turn must queue separately", "queued", queued.string("status"))
                val pendingId = queued.string("pending_id")
                assertEquals("Queue receipt must retain the submitted identity", queueBody.string("submission_id"), pendingId)

                val secondId = createThread("gpt-6.1-sol")
                val emptyOther = BridgeParser.pendingSnapshot(api.command(request("pending_messages").put("thread_id", secondId)), secondId)
                assertTrue("The second owned thread must have an authoritative empty queue", emptyOther.available && emptyOther.authoritative)
                assertTrue("The second owned thread must not inherit the first thread's queue", emptyOther.messages.isEmpty())
                // Do not refresh the original queue between this empty other-thread
                // read and replay: that would conceal the old cross-thread mutation.
                val replayed = ComposerSubmission.submit(queueBody, mode = "queue", command = { ownedCommand(it, id) })
                assertTrue("The same submission must be recognized as a replay", replayed.optBoolean("replayed"))
                assertEquals("A different thread's empty queue must not turn this receipt into accepted", "queued", replayed.string("status"))
                assertEquals("Replay must retain the original pending identity", pendingId, replayed.string("pending_id"))
                val pending = BridgeParser.pendingSnapshot(api.command(request("pending_messages").put("thread_id", id)), id)
                assertTrue("The first queue must remain available and authoritative", pending.available && pending.authoritative)
                assertEquals("Replay must leave exactly one native submission", listOf(pendingId), pending.messages.filter {
                    it.status == "queued" && it.source == "app_server_queue"
                }.map { it.id })
                assertTrue("The queued input must be visible independently of steering", pending.messages.any {
                    it.id == pendingId && it.action == "queue"
                })

                val withdrawBody = JSONObject().put("thread_id", id).put("submission_id", UUID.randomUUID().toString())
                    .put("text", "${marker}_WITHDRAW_ONLY: This synthetic input must be withdrawn before execution.")
                val withdrawReceipt = ComposerSubmission.submit(withdrawBody, mode = "queue", command = { ownedCommand(it, id) })
                assertEquals("The extra synthetic input must enter the genuine native queue", "queued", withdrawReceipt.string("status"))
                val withdrawId = withdrawReceipt.string("pending_id")
                val beforeWithdraw = BridgeParser.pendingSnapshot(api.command(request("pending_messages").put("thread_id", id)), id)
                assertTrue("Withdrawal requires an authoritative queue read", beforeWithdraw.available && beforeWithdraw.authoritative)
                val withdrawEntry = beforeWithdraw.messages.single { it.id == withdrawId }
                assertTrue("The actual queued entry must expose cancellation eligibility", PendingMessages.canCancel(withdrawEntry, beforeWithdraw.authoritative))
                ownedCommand(request("pending_message_delete").put("thread_id", id).put("id", withdrawId), id)
                val afterWithdraw = BridgeParser.pendingSnapshot(api.command(request("pending_messages").put("thread_id", id)), id)
                assertTrue("Withdrawal must preserve an authoritative queue", afterWithdraw.available && afterWithdraw.authoritative)
                assertEquals("Withdrawal must remove only the selected native queue entry", listOf(pendingId), afterWithdraw.messages.filter {
                    it.status == "queued" && it.source == "app_server_queue"
                }.map { it.id })
                assertFalse("The withdrawn input must no longer appear", afterWithdraw.messages.any { it.id == withdrawId })
                ownedCommand(request("interrupt").put("thread_id", id).put("turn_id", turnId), id)
                awaitValue("the interrupted original turn event", 15_000) {
                    notifications(id, "turn/completed").firstOrNull { params ->
                        val turn = params.optJSONObject("turn")
                        turn?.string("id") == turnId && turn.string("status") == "interrupted"
                    }
                }
                // Explicit interruption intentionally pauses native queue processing. The
                // existing queued submission must survive and be started by identity;
                // resending its text would hide a queue loss or create a duplicate turn.
                awaitValue("idle after explicit interruption", 8_000) {
                    readThread(id).takeIf { it.optJSONObject("status")?.string("type") == "idle" }
                }
                val retainedQueue = api.command(request("pending_messages").put("thread_id", id))
                    .optJSONArray("messages").objectsOrEmpty().firstOrNull {
                        it.string("id") == pendingId && it.string("status") == "queued" && it.string("action") == "queue"
                    } ?: throw AssertionError("Interruption must preserve the original queued submission")
                val queuedNativeId = retainedQueue.string("queued_submission_id")
                assertTrue("The retained queue item must keep its native identity", queuedNativeId.isNotBlank())
                val started = ownedCommand(request("pending_message_start").put("thread_id", id).put("id", pendingId), id)
                assertEquals("accepted", started.string("status"))
                assertEquals("Starting the queue must reuse the same pending identity", pendingId, started.string("pending_id"))
                assertEquals("Starting the queue must reuse the same native submission", queuedNativeId, started.string("queued_submission_id"))
                val final = awaitAssistantAndIdle(id, queueToken, 55_000)
                assertTrue("The separate queued input must survive interruption", userText(final).contains(queueToken))
                val queuedTurnId = replyTurnId(final, queueToken)
                assertNotEquals("Queue processing must create a separate turn", turnId, queuedTurnId)
                assertEquals("The reply must belong to the explicitly started turn", started.string("turn_id"), queuedTurnId)
                awaitCompleted(id, queuedTurnId, "completed")
                val completedQueue = BridgeParser.pendingSnapshot(api.command(request("pending_messages").put("thread_id", id)), id)
                assertTrue("The completed queue read must remain available and authoritative", completedQueue.available && completedQueue.authoritative)
                assertTrue("History echo must remove every stale accepted receipt", completedQueue.messages.isEmpty())
                assertFalse("The withdrawn synthetic input must never enter durable history", userText(final).contains("${marker}_WITHDRAW_ONLY"))
                assertEquals("The realtime connection must remain healthy", null, streamFailure.get())
                checkpoint("live-tool-steer-queue-interrupt-pass")
            } finally {
                // An interrupted assertion must not leave a deliberately sleeping QA turn behind.
                // This is part of the bounded test flow; @After only archives owned sessions.
                val active = readThread(id).optJSONObject("status")?.string("type") == "active"
                if (active) {
                    ownedCommand(request("interrupt").put("thread_id", id), id)
                    awaitValue("test-owned turn cleanup", 30_000) {
                        readThread(id).takeIf { it.optJSONObject("status")?.string("type") == "idle" }
                    }
                }
            }
        }
    }

    private suspend fun assertQaBoundary() {
        val status = api.command(request("status"))
        assertEquals("A production or unrelated Bridge must never be mutated", QA_HOME,
            status.getJSONObject("rollout_store").string("codex_home"))
        assertEquals("QA configuration must be isolated", "$QA_BASE/qa-bridge.toml",
            status.getJSONObject("configuration").string("path"))
        assertEquals("The projectless root must remain inside QA", QA_CHATS,
            status.getJSONObject("configuration").string("projectless_workspace_root"))
        assertEquals("The runtime socket must remain isolated", "$QA_BASE/qa-app-server.sock",
            status.getJSONObject("write_backend").string("app_server_socket"))
        val projects = api.command(request("projects").put("include_archived", false))
            .optJSONArray("projects").objectsOrEmpty().filter { it.string("kind") == "project" }
        assertEquals("Only the registered synthetic project may be exposed", listOf(QA_PROJECT), projects.map { it.string("path") })
        assertEquals("Android QA", projects.single().string("name"))
    }

    private suspend fun createThread(model: String): String {
        check(qaVerified) { "QA boundary was not verified" }
        assertQaBoundary()
        val result = api.command(request("thread_create").put("project_path", QA_PROJECT)
            .put("worktree", false).put("model", model))
        val thread = result.getJSONObject("thread")
        val id = thread.string("id")
        assertTrue("Creation must return a new thread ID", id.isNotBlank())
        assertFalse("Pre-existing QA threads are never writable by this test", id in initialThreadIds)
        assertTrue("Each created ID must be unique", ownedThreads.add(id))
        assertEquals("The new thread must be inside the synthetic project", QA_PROJECT, thread.string("cwd"))
        val watch = ownedCommand(request("thread_watch").put("thread_id", id), id)
        assertTrue("The QA Bridge must own its newly created thread", watch.optBoolean("subscribed"))
        return id
    }

    private suspend fun ownedCommand(body: JSONObject, id: String): JSONObject {
        check(qaVerified && id in ownedThreads) { "Mutation requires a test-created QA thread" }
        assertEquals("The mutation target must be explicit", id, body.string("thread_id"))
        assertQaBoundary()
        return api.command(body)
    }

    private suspend fun submit(id: String, text: String, attachments: JSONArray = JSONArray()): JSONObject {
        check(id in ownedThreads)
        val body = JSONObject().put("thread_id", id).put("text", text)
            .put("submission_id", UUID.randomUUID().toString()).put("attachments", attachments)
        return ComposerSubmission.submit(body, "steer") { ownedCommand(it, id) }
    }

    private suspend fun setModel(id: String, model: String, effort: String) {
        ownedCommand(request("thread_settings_update").put("thread_id", id).put("model", model).put("effort", effort), id)
        awaitValue("model and effort readback", 8_000) {
            val composer = api.command(request("composer_status").put("thread_id", id))
            composer.takeIf { it.string("model") == model && it.string("reasoning_effort") == effort }
        }
    }

    private suspend fun assertComposer(id: String, model: String, effort: String) {
        val composer = api.command(request("composer_status").put("thread_id", id))
        assertEquals("Configured model must be retained", model, composer.string("model"))
        assertEquals("Configured reasoning effort must be retained", effort, composer.string("reasoning_effort"))
    }

    private suspend fun readThread(id: String): JSONObject {
        check(id in ownedThreads)
        return api.command(request("app_server_rpc").put("method", "thread/read")
            .put("params", JSONObject().put("threadId", id).put("includeTurns", false)))
            .getJSONObject("result").getJSONObject("thread")
    }

    private suspend fun resumeSettings(id: String): JSONObject {
        check(qaVerified && id in ownedThreads)
        assertQaBoundary()
        assertNotEquals("Only idle QA threads can be resumed for settings readback", "active", readThread(id).optJSONObject("status")?.string("type"))
        return api.command(request("app_server_rpc").put("method", "thread/resume")
            .put("params", JSONObject().put("threadId", id).put("excludeTurns", true))).getJSONObject("result")
    }

    private suspend fun moveAndVerify(id: String, cwd: String, assigned: Boolean) {
        require(cwd == QA_PROJECT || cwd == QA_CHATS)
        val moved = ownedCommand(SessionCommands.move(id, cwd, 43), id)
        assertEquals(cwd, SessionCommands.movedCwd(moved, cwd, 43))
        assertEquals(assigned, moved.optBoolean("project_assigned"))
        awaitValue("canonical cwd and project assignment readback", 8_000) {
            readThread(id).takeIf { thread ->
                thread.string("id") == id && thread.string("cwd") == cwd &&
                    (thread.string("projectId").isNotBlank() == assigned)
            }
        }
        awaitValue("Bridge project grouping readback", 8_000) {
            val projectIds = projectThreadIds(QA_PROJECT)
            val chatsIds = projectThreadIds("codex-bridge://chats")
            true.takeIf { (id in projectIds) == assigned && (id in chatsIds) != assigned }
        }
    }

    private suspend fun projectThreadIds(path: String): Set<String> = api.command(request("project_threads")
        .put("project_path", path).put("include_archived", false).put("offset", 0).put("limit", 200))
        .optJSONArray("threads").objectsOrEmpty().map { it.string("id") }.toSet()

    private suspend fun show(id: String): JSONObject {
        check(id in ownedThreads)
        return api.command(request("show").put("thread_id", id).put("last", 200))
    }

    private suspend fun awaitAssistantAndIdle(id: String, token: String, timeoutMs: Long): JSONObject =
        awaitValue("durable model reply and idle state", timeoutMs) {
            val stored = try {
                show(id)
            } catch (failure: BridgeException) {
                if (failure.code != "thread_not_found") throw failure
                JSONObject()
            }
            stored.takeIf { assistantText(it).contains(token) && readThread(id).optJSONObject("status")?.string("type") == "idle" }
        }

    private fun notifications(id: String, method: String): List<JSONObject> = events.mapNotNull { event ->
        val message = event.optJSONObject("message") ?: return@mapNotNull null
        val params = message.optJSONObject("params") ?: return@mapNotNull null
        params.takeIf { message.string("method") == method && it.string("threadId") == id }
    }

    private suspend fun awaitCompleted(id: String, turnId: String, status: String) {
        awaitValue("the matching streamed turn/completed event", 8_000) {
            notifications(id, "turn/completed").firstOrNull { params ->
                val turn = params.optJSONObject("turn")
                turn?.string("id") == turnId && turn.string("status") == status
            }
        }
    }

    private fun replyTurnId(show: JSONObject, token: String): String {
        val id = show.optJSONArray("messages").objectsOrEmpty().lastOrNull {
            it.string("role") == "assistant" && textContent(it.opt("content")).contains(token)
        }?.string("turn_id").orEmpty()
        assertTrue("The durable model reply must retain its turn identity", id.isNotBlank())
        return id
    }

    private suspend fun <T : Any> awaitValue(label: String, timeoutMs: Long, probe: suspend () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            probe()?.let { return it }
            delay(350)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    private fun permissionSignature(settings: JSONObject): String {
        val selected = JSONObject()
        listOf("approvalPolicy", "approvalsReviewer", "sandbox").forEach { key ->
            assertTrue("Runtime settings must explicitly expose permission fields", settings.has(key))
            selected.put(key, settings.get(key))
        }
        selected.put("activePermissionProfile", settings.opt("activePermissionProfile") ?: JSONObject.NULL)
        return canonical(selected)
    }

    private fun historySignatures(show: JSONObject): Map<String, String> = show.optJSONArray("messages")
        .objectsOrEmpty().filter { it.string("role") in setOf("user", "assistant") }.associate { message ->
            val id = message.string("id")
            assertTrue("Durable messages must retain an identity", id.isNotBlank())
            id to sha256(message.string("role") + canonical(message.opt("content")))
        }

    private fun assertHistoryPreserved(before: Map<String, String>, after: JSONObject) {
        val current = historySignatures(after)
        assertTrue("Relocation must preserve every original message and its content", before.all { (id, digest) -> current[id] == digest })
    }

    private fun assistantText(show: JSONObject) = roleText(show, "assistant")
    private fun userText(show: JSONObject) = roleText(show, "user")
    private fun roleText(show: JSONObject, role: String): String = show.optJSONArray("messages").objectsOrEmpty()
        .filter { it.string("role") == role }.joinToString("\n") { textContent(it.opt("content")) }

    private fun textContent(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("\n") { textContent(value.opt(it)) }
        is JSONObject -> value.string("text").ifBlank { textContent(value.opt("content")) }
        else -> ""
    }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.opt(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun redImage(): String {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.RED)
            val bytes = ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    private fun request(command: String) = JSONObject().put("command", command)
    private fun JSONArray?.objectsOrEmpty(): List<JSONObject> = this?.objects().orEmpty()
    private fun checkpoint(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("liveBridgeCheckpoint", name)
        })
    }

    private companion object {
        const val QA_BASE = "/tmp/codex-bridge-android-qa"
        const val QA_HOME = "$QA_BASE/codex-home"
        const val QA_PROJECT = "$QA_BASE/project"
        const val QA_CHATS = "$QA_BASE/chats"
        val EXPECTED_MODELS = setOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
    }
}
