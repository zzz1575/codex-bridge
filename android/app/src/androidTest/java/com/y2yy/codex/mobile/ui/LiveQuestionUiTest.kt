package com.y2yy.codex.mobile.ui

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.y2yy.codex.mobile.MainActivity
import com.y2yy.codex.mobile.data.BridgeApi
import com.y2yy.codex.mobile.data.BridgeParser
import com.y2yy.codex.mobile.data.ServerRequests
import com.y2yy.codex.mobile.data.local.NativeStore
import com.y2yy.codex.mobile.data.objects
import com.y2yy.codex.mobile.data.string
import com.y2yy.codex.mobile.model.ServerPrompt
import com.y2yy.codex.mobile.viewmodel.CodexViewModel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real MainActivity -> VM -> default TLS/BridgeApi -> isolated Bridge/Codex checks.
 * No activity is launched before the independent QA-home and synthetic-project guards pass.
 * Every mutation is bounded to a thread ID created by this test instance. No fake replies.
 */
@RunWith(AndroidJUnit4::class)
class LiveQuestionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var api: BridgeApi
    private lateinit var store: NativeStore
    private var scenario: ActivityScenario<MainActivity>? = null
    private var qaVerified = false
    private var previousSession: String? = null
    private val owned = ConcurrentHashMap.newKeySet<String>()
    private val existing = hashSetOf<String>()
    private val imageChildren = ConcurrentHashMap<String, String>()
    private val imageTurns = ConcurrentHashMap<String, String>()
    private val imageStartCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val imageCompletionCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val completedImageChildren = ConcurrentHashMap.newKeySet<String>()
    private var eventScope: CoroutineScope? = null
    private var eventJob: Job? = null
    private val observerFailure = AtomicReference<String?>(null)
    private val marker = UUID.randomUUID().toString().replace("-", "").take(12)

    @Before fun verifyIsolatedBoundaryBeforeLaunchingAnyActivity() = runBlocking {
        val root = InstrumentationRegistry.getArguments().getString("qaRoot")
        assumeTrue("Live UI tests require an explicit isolated qaRoot", root != null)
        assertEquals("Only the designated QA runtime is permitted", QA_HOME, root)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        api = BridgeApi(context)
        api.authenticate()
        assertQaBoundary()
        existing += api.command(request("ls").put("limit", 200).put("include_archived", true))
            .optJSONArray("threads")?.objects().orEmpty().map { it.string("id") }
        store = NativeStore(context)
        previousSession = store.lastSession.first()
        qaVerified = true
        val connected = CompletableDeferred<Unit>()
        eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
            eventJob = scope.launch {
                try { api.events().collect { event ->
                    if (event.string("type") == "native_connected") connected.complete(Unit)
                    val message = event.optJSONObject("message")
                    val params = message?.optJSONObject("params")
                    val item = params?.optJSONObject("item")
                    val id = params?.string("threadId").orEmpty()
                    val method = message?.string("method")
                    if (method in setOf("item/started", "item/completed") && id in owned && item?.string("type") == "mcpToolCall" &&
                        item.string("server") == "android_qa_fixture" && item.string("tool") == "render_red_image") {
                        val childId = item.string("id")
                        if (method == "item/started") {
                            imageStartCounts.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                            childId.takeIf { it.isNotBlank() }?.let { imageChildren.putIfAbsent(id, it) }
                            params?.string("turnId")?.takeIf { it.isNotBlank() }?.let { imageTurns.putIfAbsent(id, it) }
                        } else {
                            imageCompletionCounts.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                            childId.takeIf { it.isNotBlank() }?.let { completedImageChildren += it }
                        }
                    }
                } } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    observerFailure.set("realtime_observer_failed")
                }
            }
        }
        withTimeout(15_000) { connected.await() }
    }

    @After fun closeActivityInterruptAndArchiveOnlyOurOwnQaThreads() = runBlocking {
        scenario?.close(); scenario = null
        eventJob?.cancelAndJoin(); eventScope?.cancel()
        if (!qaVerified) return@runBlocking
        var failures = 0
        for (id in owned) {
            try {
                assertQaBoundary()
                if (readThread(id).optJSONObject("status")?.string("type") == "active") {
                    ownedWrite(request("interrupt").put("thread_id", id), id)
                    awaitValue("test-owned interrupted turn", 15_000) { true.takeIf { readThread(id).optJSONObject("status")?.string("type") != "active" } }
                }
                ownedWrite(request("thread_archive").put("thread_id", id), id)
            } catch (_: Exception) { failures++ }
        }
        previousSession?.let { store.setLastSession(it) }
        checkpoint(if (failures == 0) "live-ui-own-threads-archived" else "live-ui-own-cleanup-failed")
        assertEquals("Cleanup may only interrupt/archive test-owned QA threads", 0, failures)
    }

    @Test(timeout = 240_000) fun planUserInputTravelsThroughNativeFormAndRealModel() = runBlocking {
        val id = createAndLaunch("Plan")
        val presets = rpc("collaborationMode/list", JSONObject()).optJSONArray("data")?.objects().orEmpty()
        assertTrue("This runtime must explicitly support Plan mode", presets.any { it.string("mode") == "plan" })
        val token = "QA_USER_INPUT_OK_$marker"
        val instructions = "This is an isolated Android QA interaction. Before anything else, call request_user_input exactly once, with one question id qa_choice, header QA scope, question Choose the QA scope, and options QA Alpha (read-only synthetic check) and QA Beta (alternative synthetic check). Wait for the human. After receiving QA Alpha, reply exactly $token. Do not use any other tool, read files, access network, change anything, or ask another question."
        val params = JSONObject().put("threadId", id).put("input", JSONArray().put(JSONObject().put("type", "text").put("text", instructions)))
            .put("collaborationMode", JSONObject().put("mode", "plan").put("settings", JSONObject().put("model", "gpt-6.1-sol").put("reasoning_effort", "low").put("developer_instructions", JSONObject.NULL)))
        ownedRpc("turn/start", params, id)
        val prompt = awaitPrompt(id, "item/tool/requestUserInput")
        assertEquals("The real request must contain one question", 1, prompt.questions.size)
        val question = prompt.questions.single()
        assertTrue("The model must supply the requested real option", question.options.any { it.label == "QA Alpha" })
        openPrompt(prompt)
        compose.onNodeWithText("QA Alpha").performScrollTo().performClick()
        compose.onNodeWithTag("server-question:${question.id}").performScrollTo().performClick().performTextInput("Android native QA confirmation")
        guardUiWrite(id)
        compose.onNodeWithText("提交回答").performClick()
        awaitPromptGone(id, prompt.id)
        awaitAssistantAndIdle(id, token)
        assertReplyVisible(id, token)
        checkpoint("live-plan-user-input-ui-model-pass")
    }

    @Test(timeout = 210_000) fun realMcpFormKeepsBooleanIntegerAndChoiceTypes() = runBlocking {
        val id = createAndLaunch("Form")
        assertFixtureTool("ask_form")
        sendViaUi(id, "For this isolated Android QA check, call android_qa_fixture.ask_form exactly once with arguments {}. Wait for the human form response. When the tool returns QA_FORM_OK, reply exactly QA_FORM_OK. Use no other tools, files, network or commands.")
        val prompt = awaitPrompt(id, "mcpServer/elicitation/request")
        assertEquals("The fixture must emit a standard form", "form", prompt.mode)
        assertTrue("The real form must be supported", prompt.supported && !prompt.jsonForm)
        assertEquals(setOf("confirmed", "count", "choice"), prompt.fields.map { it.name }.toSet())
        openPrompt(prompt)
        compose.onNodeWithText("是").performScrollTo().performClick()
        compose.onNodeWithTag("server-field:count").performScrollTo().performClick().performTextInput("3")
        compose.onNodeWithText("beta").performScrollTo().performClick()
        guardUiWrite(id)
        compose.onNodeWithText("提交回答").performClick()
        awaitPromptGone(id, prompt.id)
        awaitAssistantAndIdle(id, "QA_FORM_OK")
        assertReplyVisible(id, "QA_FORM_OK")
        checkpoint("live-mcp-typed-form-ui-model-pass")
    }

    @Test(timeout = 210_000) fun toolDetailOpenedBeforeImageCompletionAutomaticallyShowsRealRedPixels() = runBlocking {
        val id = createAndLaunch("Image")
        assertFixtureTool("render_red_image")
        sendViaUi(id, "For this isolated QA check call android_qa_fixture.render_red_image exactly once with arguments {} inside functions.exec. Put // @exec: {\"yield_time_ms\":1000} on the first line of that exec code. Await the real tool, but only text(\"QA_IMAGE_TOOL_DONE\") after it resolves. Never call image(), generatedImage(), or emit/dump the tool result: the MCP child itself must be the only image source. If exec yields, use functions.wait only to finish that same execution; never invoke the MCP tool again. Then reply exactly QA_IMAGE_OK. Do not use files, network, shell commands, or any other tools.")
        val childId = awaitValue("the real MCP child start event", 75_000) {
            check(observerFailure.get() == null) { "The read-only realtime observer failed" }
            imageChildren[id]
        }
        val turnId = awaitValue("the real MCP child's turn identity", 5_000) { imageTurns[id] }
        val earlyParent = findEarlyRenderParent(id, turnId, childId)
        if (earlyParent != null) {
            val parent = earlyParent.second
            // A short-yield exec may already be completed while its MCP child is still
            // running. The child's actual lifecycle, not the parent's status, is decisive.
            val parentId = parent.string("call_id")
            assertTrue("The real durable parent must have a tool identity", parentId.isNotBlank())
            compose.onNodeWithContentDescription("会话选项").performClick()
            compose.onNodeWithText("刷新会话").performClick()
            compose.waitUntil(6_000) {
                var indexed = false
                scenario!!.onActivity { activity ->
                    val state = ViewModelProvider(activity)[CodexViewModel::class.java].state.value
                    indexed = state.selected?.id == id && state.messages.any { message ->
                        message.turnId == turnId && message.tools.any { tool -> tool.id == parentId || parentId in tool.aliases }
                    }
                }
                indexed
            }
            // Consecutive tool-only messages share one row. Their individual message
            // tags intentionally do not exist; expand the real group and tool instead.
            if (compose.onAllNodesWithContentDescription("展开工具调用").fetchSemanticsNodes().isNotEmpty()) {
                compose.onAllNodesWithContentDescription("展开工具调用").onFirst().performScrollTo().performClick()
            }
            val parentName = parent.string("name")
            compose.waitUntil(3_000) { compose.onAllNodesWithText(parentName).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(parentName).onFirst().performScrollTo().performClick()
            assertEquals("Details must be expanded while the real child is active", "active", readThread(id).optJSONObject("status")?.string("type"))
            assertFalse("The image-producing child must not yet have completed", childId in completedImageChildren)
            checkpoint("live-real-parent-details-opened-before-child-image")
        } else {
            // Some SDK/rollout combinations expose no durable parent before completion.
            // Do not invent an index or let that optional cache timing block real media QA.
            checkpoint("live-early-parent-index-unavailable-core-image-check-continues")
        }
        awaitAssistantAndIdle(id, "QA_IMAGE_OK")
        awaitValue("the same real MCP child's completion event", 10_000) { true.takeIf { childId in completedImageChildren } }
        assertEquals("There must be one real MCP invocation, not a substring-count guess", 1, imageStartCounts[id]?.get())
        assertEquals("That same invocation must finish exactly once", 1, imageCompletionCounts[id]?.get())
        hideIme()
        val imageHost = awaitValue("the same real MCP child to gain its historical image index", 15_000) {
            BridgeParser.messages(api.command(request("turn_messages").put("thread_id", id).put("turn_id", turnId)), id).messages
                .firstOrNull { message -> message.tools.any { it.id == childId && it.name.contains("render_red_image") && it.hasImage } }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat-message:${imageHost.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-message:${imageHost.id}").performScrollTo()
        waitForRedPixel("消息图片", 25_000)
        compose.onAllNodesWithContentDescription("消息图片").assertCountEquals(1)
        assertSingleImageHost(imageHost.id)
        compose.onAllNodesWithContentDescription("消息图片").onFirst().performScrollTo().performClick()
        waitForRedPixel("图片大图预览", 10_000)
        compose.onNodeWithContentDescription("关闭图片预览").performClick()
        scenario!!.close()
        assertQaBoundary()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("chat-message:${imageHost.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-message:${imageHost.id}").performScrollTo()
        waitForRedPixel("消息图片", 25_000)
        compose.onAllNodesWithContentDescription("消息图片").assertCountEquals(1)
        assertSingleImageHost(imageHost.id)
        checkpoint("live-early-tool-detail-auto-image-pixels-preview-pass")
    }

    @Test(timeout = 210_000) fun unnegotiatedMcpUrlModeDoesNotFabricateAnAuthorizationRequest() = runBlocking {
        val id = createAndLaunch("URL")
        assertFixtureTool("ask_url")
        // Codex 0.163's stdio MCP client negotiates 2025-06-18, which has no URL
        // elicitation. The real fixture must report that boundary instead of forging
        // a newer-protocol request. Explicit URL UI actions are covered synthetically.
        sendViaUi(id, "Call android_qa_fixture.ask_url exactly once with arguments {}. If it returns QA_URL_CLIENT_UNSUPPORTED, reply exactly QA_URL_CLIENT_UNSUPPORTED. Never fabricate an elicitation or an authorization confirmation. Do not open URLs, authorize anything, or use other tools, files, network or commands.")
        awaitAssistantAndIdle(id, "QA_URL_CLIENT_UNSUPPORTED")
        assertTrue("An unnegotiated URL request must not become a pending native form", ServerRequests.parse(api.command(request("server_requests"))).none { it.threadId == id })
        compose.onNodeWithTag("server-request-dialog").assertDoesNotExist()
        assertReplyVisible(id, "QA_URL_CLIENT_UNSUPPORTED")
        checkpoint("live-mcp-url-capability-boundary-pass")
    }

    private suspend fun createAndLaunch(label: String): String {
        assertEquals("Every live flow requires explicit opt-in", QA_HOME, InstrumentationRegistry.getArguments().getString("qaRoot"))
        check(qaVerified)
        assertQaBoundary(); assertQaBoundary()
        val created = api.command(request("thread_create").put("project_path", QA_PROJECT).put("worktree", false).put("model", "gpt-6.1-sol")).getJSONObject("thread")
        val id = created.string("id")
        assertTrue("Only newly created thread identities may be writable", id.isNotBlank() && id !in existing && owned.add(id))
        assertEquals(QA_PROJECT, created.string("cwd"))
        ownedWrite(request("thread_watch").put("thread_id", id), id)
        val title = "Android UI QA $label $marker"
        ownedWrite(request("thread_rename").put("thread_id", id).put("name", title), id)
        ownedWrite(request("thread_settings_update").put("thread_id", id).put("model", "gpt-6.1-sol").put("effort", "low"), id)
        store.setLastSession(id)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(35_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithContentDescription("返回会话列表").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertIsDisplayed()
        return id
    }

    private suspend fun sendViaUi(id: String, promptText: String) {
        guardUiWrite(id)
        compose.onNodeWithTag("composer-input").performClick().performTextInput(promptText)
        guardUiWrite(id)
        compose.waitUntil(15_000) {
            val nodes = compose.onAllNodesWithContentDescription("发送消息").fetchSemanticsNodes()
            nodes.size == 1 && !nodes.single().config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithContentDescription("发送消息").assertIsEnabled().performClick()
        hideIme()
        awaitValue("a durable user message after the VM submission acknowledgement", 15_000) {
            true.takeIf { api.command(request("show").put("thread_id", id).put("last", 100)).optJSONArray("messages")?.objects().orEmpty()
                .any { it.string("role") == "user" && text(it.opt("content")).contains(promptText) } }
        }
    }

    private suspend fun openPrompt(prompt: ServerPrompt) {
        guardUiWrite(prompt.threadId)
        compose.waitUntil(25_000) { compose.onAllNodesWithContentDescription("处理待回答问题:${prompt.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("处理待回答问题:${prompt.id}").performScrollTo().performClick()
        compose.onNodeWithTag("server-request-dialog").assertIsDisplayed()
    }

    private suspend fun assertQaBoundary() {
        val s = api.command(request("status"))
        assertEquals("Production must never be written by these tests", QA_HOME, s.getJSONObject("rollout_store").string("codex_home"))
        assertEquals("QA configuration must be isolated", "$QA_BASE/qa-bridge.toml", s.getJSONObject("configuration").string("path"))
        assertEquals(QA_CHATS, s.getJSONObject("configuration").string("projectless_workspace_root"))
        assertEquals("QA app-server socket must be isolated", "$QA_BASE/qa-app-server.sock", s.getJSONObject("write_backend").string("app_server_socket"))
        val projects = api.command(request("projects").put("include_archived", false)).optJSONArray("projects")?.objects().orEmpty().filter { it.string("kind") == "project" }
        assertEquals(listOf(QA_PROJECT), projects.map { it.string("path") })
        assertEquals("Android QA", projects.single().string("name"))
    }

    private suspend fun guardUiWrite(id: String) {
        check(qaVerified && id in owned) { "Only this test's freshly created QA thread may be mutated" }
        assertQaBoundary()
        assertEquals("The owned session must still use the QA project", QA_PROJECT, readThread(id).string("cwd"))
        assertQaBoundary()
    }

    private suspend fun ownedWrite(body: JSONObject, id: String): JSONObject {
        assertEquals(id, body.string("thread_id")); guardUiWrite(id)
        return api.command(body)
    }

    private suspend fun ownedRpc(method: String, params: JSONObject, id: String): JSONObject {
        assertEquals(id, params.string("threadId")); guardUiWrite(id)
        return rpc(method, params)
    }

    private suspend fun rpc(method: String, params: JSONObject): JSONObject = api.command(request("app_server_rpc").put("method", method).put("params", params)).getJSONObject("result")
    private suspend fun readThread(id: String): JSONObject { check(id in owned); return rpc("thread/read", JSONObject().put("threadId", id).put("includeTurns", false)).getJSONObject("thread") }

    private suspend fun assertFixtureTool(name: String) {
        val status = rpc("mcpServerStatus/list", JSONObject())
        assertTrue("The isolated fixture must expose the requested real MCP tool", status.optJSONArray("data")?.objects().orEmpty().any { it.string("name") == "android_qa_fixture" && it.optJSONObject("tools")?.has(name) == true })
    }

    private suspend fun awaitPrompt(id: String, method: String): ServerPrompt = awaitValue("the real native server request", 75_000) {
        ServerRequests.parse(api.command(request("server_requests"))).firstOrNull { it.threadId == id && it.method == method }
    }

    private suspend fun awaitPromptGone(id: String, requestId: Long) = awaitValue("the original request to be resolved", 20_000) {
        true.takeIf { ServerRequests.parse(api.command(request("server_requests"))).none { it.threadId == id && it.id == requestId } }
    }

    private suspend fun awaitAssistantAndIdle(id: String, token: String) = awaitValue("the real model confirmation and completed turn", 75_000) {
        val show = api.command(request("show").put("thread_id", id).put("last", 100))
        true.takeIf { show.optJSONArray("messages")?.objects().orEmpty().any { it.string("role") == "assistant" && text(it.opt("content")).contains(token) } && readThread(id).optJSONObject("status")?.string("type") == "idle" }
    }

    private suspend fun assertReplyVisible(id: String, token: String) {
        val page = BridgeParser.messages(api.command(request("messages").put("thread_id", id).put("limit", 100)), id)
        val message = page.messages.lastOrNull { it.role == "assistant" && it.text.contains(token) } ?: throw AssertionError("The model confirmation must be durable")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("chat-message:${message.id}").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForRedPixel(description: String, timeoutMs: Long) = compose.waitUntil(timeoutMs) {
        if (compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isEmpty()) false
        else runCatching {
            compose.onAllNodesWithContentDescription(description).onFirst().captureToImage().toPixelMap().let { pixels ->
                pixels.width > 0 && pixels.height > 0 && pixels[pixels.width / 2, pixels.height / 2].let { it.alpha > .9f && it.red > .9f && it.green < .1f && it.blue < .1f }
            }
        }.getOrDefault(false)
    }

    private fun assertSingleImageHost(expectedHost: String) {
        // LazyColumn does not compose off-screen duplicates. Inspect the actual activity's
        // existing ViewModel as well as visible pixels so hidden duplicate hosts fail too.
        scenario!!.onActivity { activity ->
            val state = ViewModelProvider(activity)[CodexViewModel::class.java].state.value
            assertEquals(listOf(expectedHost), state.messages.filter { it.images.isNotEmpty() }.map { it.id })
        }
    }

    private fun hideIme() { scenario?.onActivity { activity -> activity.currentFocus?.clearFocus(); WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) } }
    private suspend fun findEarlyRenderParent(id: String, turnId: String, childId: String): Pair<JSONObject, JSONObject>? {
        val deadline = SystemClock.elapsedRealtime() + 7_000
        while (SystemClock.elapsedRealtime() < deadline && childId !in completedImageChildren) {
            if (readThread(id).optJSONObject("status")?.string("type") != "active") return null
            val raw = api.command(request("messages").put("thread_id", id).put("limit", 100))
            val candidates = mutableListOf<Pair<JSONObject, JSONObject>>()
            for (message in raw.optJSONArray("messages")?.objects().orEmpty().filter { it.string("turn_id") == turnId }) {
                for (tool in message.optJSONArray("tools")?.objects().orEmpty().filter { it.string("name") in setOf("exec", "functions.exec") }) {
                    val detail = api.command(request("tool_content").put("thread_id", id)
                        .put("message_index", message.getInt("message_index")).put("tool_index", tool.getInt("tool_index")))
                    val content = detail.optJSONObject("tool") ?: continue
                    val input = content.opt("input")?.toString().orEmpty()
                    if (!input.contains("render_red_image") || !input.contains("android_qa_fixture")) continue
                    assertTrue("The real parent detail cached before child completion must have no image",
                        com.y2yy.codex.mobile.data.MessageImages.collect(content.opt("output")).isEmpty())
                    candidates += message to tool
                }
            }
            if (childId in completedImageChildren) return null
            if (candidates.size == 1) return candidates.single()
            delay(150)
        }
        return null
    }
    private suspend fun <T : Any> awaitValue(label: String, timeoutMs: Long, probe: suspend () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) { probe()?.let { return it }; delay(250) }
        throw AssertionError("Timed out waiting for $label")
    }
    private fun text(value: Any?): String = when (value) { is String -> value; is JSONArray -> (0 until value.length()).joinToString("\n") { text(value.opt(it)) }; is JSONObject -> value.string("text").ifBlank { text(value.opt("content")) }; else -> "" }
    private fun request(command: String) = JSONObject().put("command", command)
    private fun checkpoint(value: String) { InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("liveQuestionCheckpoint", value) }) }
    private companion object {
        const val QA_BASE = "/tmp/codex-bridge-android-qa"
        const val QA_HOME = "$QA_BASE/codex-home"
        const val QA_PROJECT = "$QA_BASE/project"
        const val QA_CHATS = "$QA_BASE/chats"
    }
}
