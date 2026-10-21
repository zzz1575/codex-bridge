package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.ToolCall
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic bridge protocol fixtures; no production conversation data. */
class BridgeParserTest {
    @Test
    fun compactRefreshKeepsImageFailureVisibleInsteadOfRestartingSpinner() {
        val compact = ChatMessage("image-a", "user", "", imagePending = true)
        val failed = compact.copy(imagePending = false, imageError = "Unable to load")
        val merged = BridgeParser.mergeSnapshot(listOf(compact), listOf(failed)).single()
        assertFalse(merged.imagePending)
        assertEquals("Unable to load", merged.imageError)
    }

    @Test
    fun compactRefreshRetainsHydratedImageAndClearsStaleFailure() {
        val compact = ChatMessage("image-a", "user", "", imagePending = true)
        val hydrated = compact.copy(images = listOf("data:image/png;base64,AAAA"), imagePending = false, imageError = "Stale error")
        val merged = BridgeParser.mergeSnapshot(listOf(compact), listOf(hydrated)).single()
        assertEquals(hydrated.images, merged.images)
        assertFalse(merged.imagePending)
        assertNull(merged.imageError)
    }
    @Test
    fun compactKindsProduceVisibleTextAndStableFallbackIdentities() {
        val fixture = JSONObject("""
            {"thread":{"id":"thread-a","title":null,"cwd":"/workspace/demo"},
             "messages":[
              {"id":null,"message_index":17,"turn_id":"turn-a","role":"user",
               "content":[{"kind":"text","text":"检查这个请求"},
                          {"kind":"context","label":"Injected context","text":"hidden context"},
                          {"kind":"pasted_text","text":"补充说明"}],"tools":[]},
              {"id":"assistant-a","message_index":18,"turn_id":"turn-a","role":"assistant",
               "content":[{"kind":"text","text":"正在检查"}],
               "tools":[{"call_id":null,"tool_index":2,"name":"exec_command",
                         "status":"completed","preview":"inspect fixtures","has_output":true}]}
             ],"page":{"start":17,"end":19,"total":19,"has_more":true}}
        """.trimIndent())

        val first = BridgeParser.messages(fixture, "thread-a")
        val second = BridgeParser.messages(JSONObject(fixture.toString()), "thread-a")

        assertEquals("thread-a:17", first.messages[0].id)
        assertEquals("检查这个请求\n\n补充说明", first.messages[0].text)
        assertFalse(first.messages[0].text.contains("hidden context"))
        assertEquals("assistant-a", first.messages[1].id)
        assertEquals("thread-a:18:2", first.messages[1].tools.single().id)
        assertEquals(first.messages.map { it.id }, second.messages.map { it.id })
        assertEquals("turn-a", first.messages[1].turnId)
        assertEquals(17, first.start)
        assertEquals(19, first.total)
        assertTrue(first.hasOlder)
    }

    @Test
    fun overlappingSnapshotFusesLiveMessageWithoutRollbackOrDuplicate() {
        val durable = ChatMessage("reply-a", "assistant", "第一段", turnId = "turn-a")
        val live = durable.copy(id = "live:reply-a", text = "第一段，继续回复", streaming = true)

        val merged = BridgeParser.mergeSnapshot(listOf(durable), listOf(live))

        assertEquals(1, merged.size)
        assertEquals("reply-a", merged.single().id)
        assertEquals("第一段，继续回复", merged.single().text)
        assertTrue(merged.single().streaming)
    }

    @Test
    fun confirmedSnapshotReplacesLiveIdentityAndKeepsOneMessage() {
        val live = ChatMessage("live:reply-a", "assistant", "已完成", streaming = true, turnId = "turn-a")
        val durable = live.copy(id = "reply-a", streaming = false)

        val merged = BridgeParser.mergeSnapshot(listOf(durable), listOf(live))

        assertEquals(listOf(durable), merged)
    }

    @Test
    fun snapshotWithoutNewLiveMessageDoesNotDiscardThatMessage() {
        val durable = ChatMessage("user-a", "user", "问题")
        val live = ChatMessage("live:reply-a", "assistant", "正在回答", streaming = true, turnId = "turn-a")

        val merged = BridgeParser.mergeSnapshot(listOf(durable), listOf(durable, live))

        assertEquals(listOf("user-a", "live:reply-a"), merged.map { it.id })
        assertEquals("正在回答", merged.last().text)
    }

    @Test
    fun toolSnapshotFusesLiveOutputAndPreservesSummaryWithoutDuplicateHost() {
        val loggedTool = ToolCall("tool-a", "exec_command", "inProgress", "inspect fixtures", "line one\n")
        val logged = ChatMessage("message-a", "assistant", "正在检查", tools = listOf(loggedTool), turnId = "turn-a")
        val live = ChatMessage("live:tools:turn-a", "assistant", "",
            tools = listOf(loggedTool.copy(output = "line one\nline two\n")), turnId = "turn-a")

        val merged = BridgeParser.mergeSnapshot(listOf(logged), listOf(live))

        assertEquals(1, merged.size)
        assertEquals("正在检查", merged.single().text)
        assertEquals("inspect fixtures", merged.single().tools.single().input)
        assertEquals("line one\nline two\n", merged.single().tools.single().output)
    }

    @Test
    fun movingAllLiveToolsIntoDurableHostMovesTheirImagesOnceForRefreshAndPrepend() {
        val image = "data:image/png;base64,AAAA"
        val liveImageTool = ToolCall("exec-11111111-1111-4111-8111-111111111111", "render_red_image", "completed", hasImage = true)
        val liveOtherTool = ToolCall("exec-22222222-2222-4222-8222-222222222222", "fixture", "completed")
        val live = ChatMessage("live:tools:turn-a", "assistant", "", images = listOf(image),
            tools = listOf(liveImageTool, liveOtherTool), turnId = "turn-a")
        val durable = ChatMessage("final-a", "assistant", "Image ready", tools = listOf(
            liveImageTool.copy(id = "call_image", aliases = setOf(liveImageTool.id)),
            liveOtherTool.copy(id = "call_other", aliases = setOf(liveOtherTool.id))
        ), turnId = "turn-a", imagePending = true)

        for (prepend in listOf(false, true)) {
            val merged = BridgeParser.mergeSnapshot(listOf(durable), listOf(live), prepend)
            assertEquals("The former pure tool host must not survive as a second image", 1, merged.size)
            assertEquals("final-a", merged.single().id)
            assertEquals(listOf(image), merged.single().images)
            assertFalse(merged.single().imagePending)
            assertEquals(setOf("call_image", "call_other"), merged.single().tools.map { it.id }.toSet())
            val refreshed = BridgeParser.mergeSnapshot(listOf(durable), merged, prepend)
            assertEquals(merged, refreshed)
        }
    }

    @Test
    fun toolMediaMigrationKeepsEqualImagesFromDistinctCallsAndTurns() {
        val image = "data:image/png;base64,AAAA"
        val tool = ToolCall("same-scoped-id", "fixture", "completed", hasImage = true)
        val liveA = ChatMessage("live:tools:turn-a", "assistant", "", images = listOf(image), tools = listOf(tool), turnId = "turn-a")
        val liveB = liveA.copy(id = "live:tools:turn-b", turnId = "turn-b")
        val durableA = ChatMessage("final-a", "assistant", "A", tools = listOf(tool), turnId = "turn-a")
        val durableB = durableA.copy(id = "final-b", text = "B", turnId = "turn-b")
        val user = ChatMessage("user-a", "user", "Original image", images = listOf(image), turnId = "turn-a")

        for (prepend in listOf(false, true)) {
            val merged = BridgeParser.mergeSnapshot(listOf(user, durableA, durableB), listOf(user, liveA, liveB), prepend)
            assertEquals(listOf("user-a", "final-a", "final-b"), merged.map { it.id })
            assertEquals(listOf(listOf(image), listOf(image), listOf(image)), merged.map { it.images })
        }
    }

    @Test
    fun splitCodeModeParentAndImageChildMoveMediaOnlyToTheImageToolHost() {
        val image = "data:image/png;base64,AAAA"
        val parent = ToolCall("parent-exec", "exec", "completed")
        val child = ToolCall("image-child", "android_qa_fixture.render_red_image", "completed", hasImage = true)
        val live = ChatMessage("live:tools:turn-a", "assistant", "", images = listOf(image),
            tools = listOf(parent, child), turnId = "turn-a")
        val durableParent = ChatMessage("exec-host", "assistant", "", tools = listOf(parent), turnId = "turn-a")
        val durableChild = ChatMessage("final-a", "assistant", "Image ready", tools = listOf(child), turnId = "turn-a", imagePending = true)

        for (prepend in listOf(false, true)) {
            val merged = BridgeParser.mergeSnapshot(listOf(durableParent, durableChild), listOf(live), prepend)
            assertEquals(listOf("exec-host", "final-a"), merged.map { it.id })
            assertTrue(merged.first().images.isEmpty())
            assertEquals(listOf(image), merged.last().images)
            assertFalse(merged.last().imagePending)
        }
    }

    @Test
    fun partialToolMigrationAndTextHostsKeepTheirOriginalMedia() {
        val image = "data:image/png;base64,AAAA"
        val moved = ToolCall("tool-moved", "fixture", "completed", hasImage = true)
        val pending = ToolCall("tool-pending", "fixture", "inProgress")
        val partial = ChatMessage("live:tools:turn-a", "assistant", "", images = listOf(image),
            tools = listOf(moved, pending), turnId = "turn-a")
        val textHost = ChatMessage("live:tools:turn-b", "assistant", "Keep this explanation", images = listOf(image),
            tools = listOf(moved), turnId = "turn-b")
        val durableA = ChatMessage("final-a", "assistant", "A", tools = listOf(moved), turnId = "turn-a")
        val durableB = durableA.copy(id = "final-b", text = "B", turnId = "turn-b")

        for (prepend in listOf(false, true)) {
            val merged = BridgeParser.mergeSnapshot(listOf(durableA, durableB), listOf(partial, textHost), prepend)
            assertEquals(listOf(image), merged.single { it.id == partial.id }.images)
            assertEquals(listOf(pending), merged.single { it.id == partial.id }.tools)
            assertEquals("Keep this explanation", merged.single { it.id == textHost.id }.text)
            assertEquals(listOf(image), merged.single { it.id == textHost.id }.images)
            assertTrue(merged.filter { it.id.startsWith("final-") }.all { it.images.isEmpty() })
        }
    }

    @Test
    fun fullToolMediaMigrationPreservesPartialImageFailureAlongsideSuccessfulImage() {
        val tool = ToolCall("tool-image", "fixture", "completed", hasImage = true)
        val live = ChatMessage("live:tools:turn-a", "assistant", "", images = listOf("data:image/png;base64,AAAA"),
            tools = listOf(tool), turnId = "turn-a", imageError = "One image is unavailable")
        val durable = ChatMessage("final-a", "assistant", "Image ready", tools = listOf(tool), turnId = "turn-a", imagePending = true)

        for (prepend in listOf(false, true)) {
            val merged = BridgeParser.mergeSnapshot(listOf(durable), listOf(live), prepend).single()
            assertEquals(live.images, merged.images)
            assertEquals(live.imageError, merged.imageError)
            assertFalse(merged.imagePending)
        }
    }

    @Test
    fun prependingHistoryDoesNotDuplicateAlreadyVisibleMessages() {
        val old = ChatMessage("message-old", "user", "Earlier")
        val overlap = ChatMessage("message-current", "assistant", "Current")

        val merged = BridgeParser.mergeSnapshot(listOf(old, overlap), listOf(overlap), prepend = true)

        assertEquals(listOf("message-old", "message-current"), merged.map { it.id })
    }

    @Test
    fun approvalUsesBridgeNumericIdentityInsteadOfUnderlyingRpcIdentity() {
        val parsed = BridgeParser.approvals(JSONObject("""
            {"requests":[{"id":42,"thread_id":"thread-a","request":{
             "id":"rpc-request-900","method":"item/commandExecution/requestApproval",
             "params":{"threadId":"thread-a","turnId":"turn-a","reason":"Read diagnostic logs","command":"inspect fixture"}
            }}]}
        """.trimIndent())).single()

        assertEquals(42L, parsed.id)
        assertEquals("thread-a", parsed.threadId)
        assertEquals("Read diagnostic logs", parsed.reason)
        assertTrue(parsed.details.contains("inspect fixture"))
    }

    @Test
    fun missingApprovalReasonFallsBackToMethodAndEmptyCollectionsAreSafe() {
        val parsed = BridgeParser.approvals(JSONObject("""
            {"requests":[{"id":7,"thread_id":"thread-a","request":{
              "method":"item/permissions/requestApproval","params":{}}}]}
        """.trimIndent()))

        assertEquals("item/permissions/requestApproval", parsed.single().reason)
        assertTrue(BridgeParser.approvals(JSONObject()).isEmpty())
        assertTrue(BridgeParser.messages(JSONObject(), "thread-a").messages.isEmpty())
    }

    @Test
    fun imageOnlyLazyContextKeepsItsMessageUntilHydrationCompletes() {
        val fixture = JSONObject("""
            {"messages":[{"id":"image-message-a","message_index":4,"role":"user",
              "content":[{"kind":"context","label":"Image attachment","bytes":1024,
                          "message_index":4,"content_index":0,"content_end":3}],"tools":[]}],
             "page":{"start":4,"end":5,"total":5,"has_more":true}}
        """.trimIndent())

        val message = BridgeParser.messages(fixture, "thread-a").messages.single()

        assertEquals("image-message-a", message.id)
        assertEquals("user", message.role)
        assertTrue(message.text.isEmpty())
        assertTrue(message.images.isEmpty())
        assertTrue(message.imagePending)
    }

    @Test
    fun deferredEmptyMessageIsPreservedWithTurnIdentityForHistoryLoading() {
        val fixture = JSONObject("""
            {"messages":[{"id":"deferred-a","message_index":12,"turn_id":"turn-a",
              "role":"assistant","phase":"commentary","content":[],"tools":[],
              "deferred":true,"deferred_tool_count":2}],
             "page":{"start":12,"end":13,"total":13,"has_more":true}}
        """.trimIndent())

        val message = BridgeParser.messages(fixture, "thread-a").messages.single()

        assertEquals("deferred-a", message.id)
        assertEquals("turn-a", message.turnId)
        assertTrue(message.deferred)
        assertTrue(message.text.isEmpty())
        assertTrue(message.tools.isEmpty())
    }

    @Test
    fun expandedTurnParsesWithoutPaginationAndKeepsCompactMessageIdentity() {
        val compact = BridgeParser.messages(JSONObject("""
            {"messages":[{"id":null,"message_index":12,"turn_id":"turn-a","role":"assistant",
              "content":[],"tools":[],"deferred":true,"deferred_tool_count":1}]}
        """.trimIndent()), "thread-a").messages.single()
        val expanded = BridgeParser.messages(JSONObject("""
            {"thread_id":"thread-a","turn_id":"turn-a","messages":[
              {"id":null,"message_index":12,"turn_id":"turn-a","role":"assistant",
               "content":[{"kind":"text","text":"Earlier diagnostic commentary"}],
               "tools":[{"call_id":"tool-a","tool_index":0,"name":"exec_command",
                         "status":"completed","preview":"inspect fixture"}]}]}
        """.trimIndent()), "thread-a")

        assertEquals(compact.id, expanded.messages.single().id)
        assertEquals("thread-a:12", expanded.messages.single().id)
        assertEquals("turn-a", expanded.messages.single().turnId)
        assertFalse(expanded.messages.single().deferred)
        assertEquals("Earlier diagnostic commentary", expanded.messages.single().text)
        assertEquals("tool-a", expanded.messages.single().tools.single().id)
    }

    @Test
    fun compactPollingCannotCollapseExpandedHistoryOrDropHydratedToolOutput() {
        val expanded = ChatMessage("message-a", "assistant", "Earlier diagnostic commentary",
            tools = listOf(ToolCall("tool-a", "exec_command", "completed", "inspect fixture", "full diagnostic output")),
            turnId = "turn-a")
        val compact = expanded.copy(text = "", tools = emptyList(), deferred = true)

        val firstRefresh = BridgeParser.mergeSnapshot(listOf(compact), listOf(expanded))
        val secondRefresh = BridgeParser.mergeSnapshot(listOf(compact), firstRefresh)

        assertEquals(listOf(expanded), firstRefresh)
        assertEquals(listOf(expanded), secondRefresh)
        assertFalse(secondRefresh.single().deferred)
    }

    @Test
    fun prependedCompactPageAlsoPreservesExpandedOverlappingHistory() {
        val older = ChatMessage("message-old", "user", "Older question", turnId = "turn-old")
        val expanded = ChatMessage("message-a", "assistant", "Expanded explanation",
            tools = listOf(ToolCall("tool-a", "exec_command", "completed", "inspect fixture", "details")),
            turnId = "turn-a")
        val compact = expanded.copy(text = "", tools = emptyList(), deferred = true)

        val merged = BridgeParser.mergeSnapshot(listOf(older, compact), listOf(expanded), prepend = true)

        assertEquals(listOf(older, expanded), merged)
    }

    @Test
    fun unexpandedDeferredSnapshotRemainsLoadableInsteadOfPretendingToBeExpanded() {
        val compact = ChatMessage("message-a", "assistant", "", turnId = "turn-a", deferred = true)

        val merged = BridgeParser.mergeSnapshot(listOf(compact), listOf(compact))

        assertEquals(1, merged.size)
        assertTrue(merged.single().deferred)
    }
}
