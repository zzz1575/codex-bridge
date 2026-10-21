package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TypedProtocolTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1EAAAAASUVORK5CYII="
    private val state = AppUiState(selected = Session("thread-a", "Fixture"), running = true, activeTurnId = "turn-a")
    private fun event(item: JSONObject, thread: String = "thread-a") = JSONObject().put("type", "app_server")
        .put("message", JSONObject().put("method", "item/completed").put("params", JSONObject()
            .put("threadId", thread).put("turnId", "turn-a").put("item", item)))

    @Test fun allTenSdkToolVariantsRemainInspectableWithoutTreatingMetadataAsTools() {
        val items = listOf(
            JSONObject().put("type", "commandExecution").put("command", "printf fixture").put("aggregatedOutput", "fixture").put("exitCode", 0),
            JSONObject().put("type", "fileChange").put("changes", JSONArray().put(JSONObject().put("path", "fixture.txt"))),
            JSONObject().put("type", "mcpToolCall").put("server", "fixture").put("tool", "lookup").put("result", JSONObject().put("answer", 42)),
            JSONObject().put("type", "dynamicToolCall").put("namespace", "fixture").put("tool", "render").put("success", true),
            JSONObject().put("type", "collabAgentToolCall").put("tool", "wait_agent").put("receiverThreadIds", JSONArray().put("agent-a")),
            JSONObject().put("type", "webSearch").put("query", "fixture").put("results", JSONArray().put(JSONObject().put("url", "https://example.com/article"))),
            JSONObject().put("type", "imageView").put("path", "/workspace/fixture.png"),
            JSONObject().put("type", "sleep").put("durationMs", 1500),
            JSONObject().put("type", "imageGeneration").put("result", png),
            JSONObject().put("type", "functionCallOutput").put("namespace", "fixture").put("name", "lookup").put("output", "opaque output")
        )
        items.forEachIndexed { index, item ->
            item.put("id", "tool-$index")
            val updated = StreamReducer.apply(state, event(item))
            val tool = updated.messages.flatMap { it.tools }.single()
            assertEquals("tool-$index", tool.id)
            assertEquals("completed", tool.status)
            assertTrue(updated.running)
            if (item.string("type") == "webSearch") assertTrue(updated.messages.flatMap { it.images }.isEmpty())
            if (item.string("type") == "imageGeneration") {
                assertTrue(tool.hasImage)
                assertEquals(listOf("data:image/png;base64,$png"), updated.messages.flatMap { it.images })
            }
            if (item.string("type") == "imageView") assertTrue(tool.hasImage)
        }
    }

    @Test fun nineSdkMessageAndLifecycleVariantsDoNotInventIndependentToolCalls() {
        for (type in listOf("userMessage", "hookPrompt", "agentMessage", "plan", "reasoning", "subAgentActivity", "enteredReviewMode", "exitedReviewMode", "contextCompaction")) {
            val updated = StreamReducer.apply(state, event(JSONObject().put("type", type).put("id", "meta-$type")))
            assertTrue(updated.messages.flatMap { it.tools }.isEmpty())
            assertEquals(state.running, updated.running)
        }
    }

    @Test fun structuredMcpAndDynamicOutputsRetainDetailsAndInlineImageMedia() {
        val image = JSONObject().put("type", "image").put("mimeType", "image/png").put("data", png)
        val item = JSONObject().put("type", "dynamicToolCall").put("id", "visual-a").put("namespace", "fixture")
            .put("tool", "render").put("contentItems", JSONArray().put(image)).put("success", true)
        val updated = StreamReducer.apply(state, event(item))
        val tool = updated.messages.flatMap { it.tools }.single()
        assertEquals("fixture.render", tool.name)
        assertTrue(tool.hasImage)
        assertTrue(tool.output.contains("contentItems"))
        assertEquals(listOf("data:image/png;base64,$png"), updated.messages.flatMap { it.images })
    }

    @Test fun unknownExtensionsPreserveOpaquePayloadAndBoundedMediaInsteadOfDisappearing() {
        val item = JSONObject().put("type", "futureVisualTool").put("id", "future-a").put("status", "failed")
            .put("opaque", JSONObject().put("newField", 42)).put("media", JSONObject().put("image_url", "data:image/png;base64,$png"))
            .put("result", JSONObject().put("reason", "fixture failure"))
        val updated = StreamReducer.apply(state, event(item))
        val tool = updated.messages.flatMap { it.tools }.single()
        assertEquals("futureVisualTool", tool.name)
        assertEquals("failed", tool.status)
        assertTrue(tool.input.contains("newField"))
        assertTrue(tool.output.contains("fixture failure"))
        assertEquals(listOf("data:image/png;base64,$png"), updated.messages.flatMap { it.images })
        assertEquals(state, StreamReducer.apply(state, event(item, "other-thread")))
    }

    @Test fun toolWrapperImagesSupportNestedResultContentItemsAndUnknownItemMedia() {
        val image = JSONObject().put("type", "image").put("mimeType", "image/png").put("data", png)
        val wrappedMcp = JSONObject().put("result", JSONObject().put("content", JSONArray().put(image)))
        val sourcedImage = JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/png").put("data", png))
        val wrappedUnknown = JSONObject().put("item", JSONObject().put("media", JSONObject().put("image_url", "data:image/png;base64,$png")))
        for (wrapper in listOf(wrappedMcp, JSONObject().put("contentItems", JSONArray().put(sourcedImage)), wrappedUnknown)) {
            assertEquals(listOf("data:image/png;base64,$png"), MessageImages.collect(wrapper))
        }
    }

    @Test fun compactToolMediaFlagsSurviveDeferredAndFullHistoryParsing() {
        for (deferred in listOf(false, true)) {
            val tools = JSONArray().put(JSONObject().put("call_id", "image-a").put("name", "fixture.render").put("has_image", true))
                .put(JSONObject().put("call_id", "image-b").put("name", "view_image").put("image_path", "/workspace/fixture.png"))
                .put(JSONObject().put("call_id", "text-a").put("name", "web_search"))
            val payload = JSONObject().put("messages", JSONArray().put(JSONObject().put("id", "message-a").put("role", "assistant")
                .put("turn_id", "turn-a").put("deferred", deferred).put("tools", tools)))
            val parsed = BridgeParser.messages(payload, "thread-a").messages.single().tools
            assertTrue(parsed.first { it.id == "image-a" }.hasImage)
            assertTrue(parsed.first { it.id == "image-b" }.hasImage)
            assertFalse(parsed.first { it.id == "text-a" }.hasImage)
        }
    }

    @Test fun onlyTypedGeneratedImagesInferMimeAndInvalidOrNonImageBase64StaysText() {
        assertEquals(listOf("data:image/png;base64,$png"), MessageImages.collect(JSONObject().put("type", "imageGeneration").put("result", png)))
        assertTrue(MessageImages.collect(JSONObject().put("type", "functionCallOutput").put("output", png)).isEmpty())
        assertTrue(MessageImages.collect(JSONObject().put("type", "imageGeneration").put("result", "YWJj")).isEmpty())
        assertTrue(MessageImages.collect(JSONObject().put("type", "imageGeneration").put("result", "UklGRg==")).isEmpty())
        assertTrue(MessageImages.collect(JSONObject().put("type", "imageGeneration").put("result", png.dropLast(1) + "!")).isEmpty())
    }

    @Test fun terminalAndCompactObservationsCannotEraseToolImageAvailability() {
        val compact = ToolCall("call-visual", "fixture.render", "completed", hasImage = true)
        val stale = compact.copy(status = "inProgress", hasImage = false)
        for (observations in listOf(listOf(compact, stale), listOf(stale, compact))) {
            val merged = ToolIdentity.reconcile(listOf(ChatMessage("host", "assistant", "", tools = observations, turnId = "turn-a")))
                .flatMap { it.tools }.single()
            assertTrue(merged.hasImage)
            assertEquals("completed", merged.status)
        }
        val snapshot = ChatMessage("host", "assistant", "", tools = listOf(stale), turnId = "turn-a")
        val current = snapshot.copy(tools = listOf(compact))
        val refreshed = BridgeParser.mergeSnapshot(listOf(snapshot), listOf(current)).single().tools.single()
        assertTrue(refreshed.hasImage)
        assertEquals("completed", refreshed.status)
    }

    @Test fun onlyExplicitTypedTextJsonCanCarryNestedImageMedia() {
        val dataUrl = "data:image/png;base64,$png"
        val structured = JSONObject().put("image_url", dataUrl).toString()
        assertTrue(MessageImages.collect(JSONObject().put("unknown_image", dataUrl).put("text", dataUrl)).isEmpty())
        assertTrue(MessageImages.collect(JSONObject().put("text", structured)).isEmpty())
        assertTrue(MessageImages.collect(JSONObject().put("type", "text").put("text", dataUrl)).isEmpty())
        assertTrue(MessageImages.collect(JSONObject().put("type", "text").put("text", "$structured trailing text")).isEmpty())
        for (type in listOf("text", "input_text")) {
            assertEquals(listOf(dataUrl), MessageImages.collect(JSONObject().put("type", type).put("text", structured)))
        }
    }
}
