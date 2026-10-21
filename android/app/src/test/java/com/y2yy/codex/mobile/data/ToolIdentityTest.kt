package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.Session
import com.y2yy.codex.mobile.model.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolIdentityTest {
    @Test
    fun threeCommandsFromSdkAndHistoryBecomeThreeHistoricalCalls() {
        val commands = listOf("sleep 40", "printf 'native fixture\\n'", "git status --short")
        val sdk = commands.mapIndexed { index, command -> sdk(index, command, "inProgress", "stream-$index") }
        val history = commands.mapIndexed { index, command -> history(index, command, "completed", "final-$index") }
        val input = listOf(
            host("live:tools:turn-a", sdk),
            host("history-0", listOf(history[0])),
            host("history-1", listOf(history[1])),
            host("history-2", listOf(history[2])),
            host("final-answer", sdk, text = "最终说明必须保留", images = listOf("https://example.invalid/image.png"))
        )
        val reconciled = ToolIdentity.reconcile(input)
        val tools = reconciled.flatMap { it.tools }
        assertEquals(3, tools.size)
        assertEquals(history.map { it.id }, tools.map { it.id })
        assertTrue(tools.all { it.status == "completed" })
        tools.forEachIndexed { index, tool ->
            assertTrue(tool.output.contains("final-$index"))
            assertTrue(tool.output.contains("stream-$index"))
        }
        assertFalse(reconciled.any { it.id == "live:tools:turn-a" })
        assertEquals("最终说明必须保留", reconciled.last().text)
        assertEquals(listOf("https://example.invalid/image.png"), reconciled.last().images)
        assertTrue(reconciled.last().tools.isEmpty())
        assertEquals(reconciled, ToolIdentity.reconcile(reconciled))
    }

    @Test
    fun twoIdenticalCommandsRemainTwoExecutionsWithOneToOneOverlayPairing() {
        val input = listOf(
            host("sdk", listOf(sdk(0, "sleep 40", output = "first"), sdk(1, "sleep 40", output = "second"))),
            host("history", listOf(history(0, "sleep 40"), history(1, "sleep 40")))
        )
        val tools = ToolIdentity.reconcile(input).flatMap { it.tools }
        assertEquals(2, tools.size)
        assertEquals(listOf("call_fixture0:0", "call_fixture1:0"), tools.map { it.id })
        assertEquals(listOf("first", "second"), tools.map { it.output })

        val historyOnly = listOf(host("history-only", listOf(history(0, "sleep 40"), history(1, "sleep 40"))))
        assertEquals(2, ToolIdentity.reconcile(historyOnly).flatMap { it.tools }.size)
        val sdkOnly = listOf(host("sdk-only", listOf(sdk(0, "sleep 40"), sdk(1, "sleep 40"))))
        assertEquals(2, ToolIdentity.reconcile(sdkOnly).flatMap { it.tools }.size)
    }

    @Test
    fun excessSameSourceExecutionIsRetained() {
        val input = listOf(
            host("sdk", listOf(sdk(0, "pwd"), sdk(1, "pwd"))),
            host("history", listOf(history(0, "pwd")))
        )
        val tools = ToolIdentity.reconcile(input).flatMap { it.tools }
        assertEquals(2, tools.size)
        assertTrue(tools.any { it.id == history(0, "pwd").id })
        assertTrue(tools.any { it.id == sdk(1, "pwd").id })
    }

    @Test
    fun differentOrUnknownTurnsNeverMatchByCommand() {
        val input = listOf(
            host("sdk", listOf(sdk(0, "pwd")), turn = "turn-a"),
            host("history", listOf(history(0, "pwd")), turn = "turn-b"),
            host("unknown-sdk", listOf(sdk(1, "pwd")), turn = ""),
            host("unknown-history", listOf(history(1, "pwd")), turn = "")
        )
        assertEquals(4, ToolIdentity.reconcile(input).flatMap { it.tools }.size)
    }

    @Test
    fun sameIdMergesOutputAndKeepsTerminalStateDespiteStaleRunningOverlay() {
        val completed = history(0, "pwd", "completed", "line one\nline two")
        val stale = completed.copy(status = "inProgress", output = "line one")
        val input = listOf(
            host("first", listOf(completed)),
            host("later", listOf(stale)),
            host("deferred", listOf(stale), deferred = true),
            host("image-placeholder", listOf(stale)).copy(imagePending = true)
        )
        val result = ToolIdentity.reconcile(input)
        assertEquals(1, result.flatMap { it.tools }.size)
        assertEquals("completed", result.first().tools.single().status)
        assertEquals("line one\nline two", result.first().tools.single().output)
        assertTrue(result.any { it.id == "deferred" && it.deferred })
        assertTrue(result.any { it.id == "image-placeholder" && it.imagePending })
    }

    @Test
    fun sdkSingleCommandActionMatchesHistoryCommandIncludingQuotedShellWrapper() {
        val shell = "/bin/bash -lc 'sleep 40'"
        val sdkInput = JSONObject().put("command", shell)
            .put("commandActions", JSONArray().put(JSONObject().put("command", "sleep 40"))).toString()
        val input = listOf(
            host("sdk", listOf(sdk(0, "ignored").copy(input = sdkInput))),
            host("history", listOf(history(0, "sleep 40")))
        )
        assertEquals(listOf("call_fixture0:0"), ToolIdentity.reconcile(input).flatMap { it.tools }.map { it.id })
    }

    @Test
    fun unrecognizedSourcesMultipleActionsAndUncertainShellExpansionRemainSeparate() {
        val actionInput = JSONObject().put("commandActions", JSONArray()
            .put(JSONObject().put("command", "pwd")).put(JSONObject().put("command", "pwd"))).toString()
        val examples = listOf(
            sdk(0, "pwd").copy(id = "unknown-sdk-id"),
            sdk(0, "pwd").copy(input = actionInput),
            sdk(0, "pwd").copy(input = "/bin/bash -lc \"\$HOME\""),
            sdk(0, "pwd").copy(input = "/bin/bash -lc 'pwd' extra"),
            sdk(0, "pwd").copy(input = "{ malformed json"),
            sdk(0, "pwd").copy(name = "unrelatedTool")
        )
        examples.forEach { example ->
            assertEquals(2, ToolIdentity.reconcile(listOf(host("sdk", listOf(example)), host("history", listOf(history(0, "pwd"))))).flatMap { it.tools }.size)
        }
    }

    @Test
    fun commandWhitespaceInsideQuotesIsNotNormalizedAway() {
        val tools = ToolIdentity.reconcile(listOf(
            host("sdk", listOf(sdk(0, "printf 'a  b'"))),
            host("history", listOf(history(0, "printf 'a b'")))
        )).flatMap { it.tools }
        assertEquals(2, tools.size)
    }

    @Test
    fun historicalPrimaryRetainsAliasesAndAbsorbsLaterSdkOverlay() {
        val sdkTool = sdk(0, "pwd", "inProgress", "line one\n")
        val initial = ToolIdentity.reconcile(listOf(
            host("sdk", listOf(sdkTool)),
            host("history", listOf(history(0, "pwd", "completed", "line one\nline two\n")))
        ))
        val canonical = initial.flatMap { it.tools }.single()
        assertEquals("call_fixture0:0", canonical.id)
        assertTrue(sdkTool.id in canonical.aliases)

        val later = ToolIdentity.reconcile(initial + host("late-overlay", listOf(sdkTool.copy(output = "line one\nline two\nline three\n"))))
        val retained = later.flatMap { it.tools }.single()
        assertEquals(canonical.id, retained.id)
        assertEquals("completed", retained.status)
        assertEquals("line one\nline two\nline three\n", retained.output)
        assertTrue(sdkTool.id in retained.aliases)
        assertEquals(later, ToolIdentity.reconcile(later))
    }

    @Test
    fun sdkOutputDeltaAfterReconcileUpdatesHistoricalPrimaryWithoutNewLiveHost() {
        val sdkTool = sdk(0, "pwd", "inProgress", "line one\n")
        val reconciled = ToolIdentity.reconcile(listOf(
            host("live:tools:turn-a", listOf(sdkTool)),
            host("history", listOf(history(0, "pwd", "inProgress")))
        ))
        val original = AppUiState(
            selected = Session("thread-a", "synthetic identity test"),
            running = true, activeTurnId = "turn-a", messages = reconciled
        )
        val event = JSONObject().put("type", "app_server").put("message", JSONObject()
            .put("method", "item/commandExecution/outputDelta")
            .put("params", JSONObject().put("threadId", "thread-a").put("turnId", "turn-a")
                .put("itemId", sdkTool.id).put("delta", "line two\n")))
        val updated = StreamReducer.apply(original, event)
        val tool = updated.messages.flatMap { it.tools }.single()
        assertEquals("call_fixture0:0", tool.id)
        assertEquals("line one\nline two\n", tool.output)
        assertTrue(sdkTool.id in tool.aliases)
        assertFalse(updated.messages.any { it.id.startsWith("live:tools:") })
    }

    @Test
    fun alreadyPairedHistoryCannotConsumeANewSameCommandExecution() {
        val first = ToolIdentity.reconcile(listOf(
            host("sdk-first", listOf(sdk(0, "sleep 40"))),
            host("history-first", listOf(history(0, "sleep 40")))
        ))
        val whileLogCatchesUp = ToolIdentity.reconcile(first + host("sdk-second", listOf(sdk(1, "sleep 40"))))
        assertEquals(2, whileLogCatchesUp.flatMap { it.tools }.size)
        val afterLog = ToolIdentity.reconcile(whileLogCatchesUp + host("history-second", listOf(history(1, "sleep 40"))))
        assertEquals(listOf("call_fixture0:0", "call_fixture1:0"), afterLog.flatMap { it.tools }.map { it.id })
        assertEquals(afterLog, ToolIdentity.reconcile(afterLog))
    }

    @Test
    fun staleSdkStartedAndShortCompletionCannotEraseHistoricalTerminalOrOutput() {
        val sdkTool = sdk(0, "pwd")
        val canonical = history(0, "pwd", "completed", "line one\nline two\n").copy(aliases = setOf(sdkTool.id))
        val original = state(listOf(host("durable-history", listOf(canonical))))
        val started = StreamReducer.apply(original, toolEvent("item/started", "turn-a", JSONObject()
            .put("type", "commandExecution").put("id", sdkTool.id).put("command", "pwd").put("status", "inProgress")))
        val retained = started.messages.flatMap { it.tools }.single()
        assertEquals(canonical, retained)

        val completed = StreamReducer.apply(started, toolEvent("item/completed", "turn-a", JSONObject()
            .put("type", "commandExecution").put("id", sdkTool.id).put("command", "pwd")
            .put("status", "completed").put("aggregatedOutput", "line two\n")))
        assertEquals(canonical, completed.messages.flatMap { it.tools }.single())
    }

    @Test
    fun sdkAliasDeltaAndItemUpdatesCannotCrossKnownTurnBoundaries() {
        val sdkId = sdk(0, "pwd").id
        val first = history(0, "pwd", "completed", "turn A\n").copy(aliases = setOf(sdkId))
        val second = history(1, "pwd", "completed", "turn B\n").copy(aliases = setOf(sdkId))
        val original = state(listOf(
            host("history-a", listOf(first), turn = "turn-a"),
            host("history-b", listOf(second), turn = "turn-b")
        )).copy(activeTurnId = "turn-b")
        val delta = JSONObject().put("type", "app_server").put("message", JSONObject()
            .put("method", "item/commandExecution/outputDelta")
            .put("params", JSONObject().put("threadId", "thread-a").put("turnId", "turn-b")
                .put("itemId", sdkId).put("delta", "new B\n")))
        val streamed = StreamReducer.apply(original, delta)
        assertEquals(first, streamed.messages.first { it.turnId == "turn-a" }.tools.single())
        assertEquals("turn B\nnew B\n", streamed.messages.first { it.turnId == "turn-b" }.tools.single().output)
        val updated = StreamReducer.apply(streamed, toolEvent("item/started", "turn-b", JSONObject()
            .put("type", "commandExecution").put("id", sdkId).put("command", "pwd").put("status", "inProgress")))
        assertEquals(first, updated.messages.first { it.turnId == "turn-a" }.tools.single())
        assertEquals("completed", updated.messages.first { it.turnId == "turn-b" }.tools.single().status)
        assertEquals(2, updated.messages.flatMap { it.tools }.size)
    }

    @Test
    fun snapshotSdkAliasRetainsCurrentHistoricalPrimaryOutputAndTerminal() {
        val sdkTool = sdk(0, "pwd", "inProgress", "one")
        val canonical = history(0, "pwd", "completed", "one\ntwo\n").copy(aliases = setOf(sdkTool.id))
        val merged = BridgeParser.mergeSnapshot(
            snapshot = listOf(host("snapshot-final", listOf(sdkTool), text = "新的说明")),
            current = listOf(host("current-history", listOf(canonical)))
        )
        assertEquals(1, merged.size)
        assertEquals("新的说明", merged.single().text)
        assertEquals(canonical, merged.single().tools.single())
    }

    @Test
    fun snapshotAliasLookupIsScopedToItsTurnEvenWhenPrepending() {
        val sdkTool = sdk(0, "pwd", "inProgress", "turn B")
        val canonical = history(0, "pwd", "completed", "turn A").copy(aliases = setOf(sdkTool.id))
        val merged = BridgeParser.mergeSnapshot(
            snapshot = listOf(host("snapshot-b", listOf(sdkTool), turn = "turn-b")),
            current = listOf(host("current-a", listOf(canonical), turn = "turn-a")),
            prepend = true
        )
        assertEquals(canonical, merged.first { it.turnId == "turn-a" }.tools.single())
        assertEquals(sdkTool, merged.first { it.turnId == "turn-b" }.tools.single())
        assertEquals(2, merged.flatMap { it.tools }.size)
    }

    @Test
    fun uniqueLegacyHostWithoutTurnAdoptsKnownEventTurnAndRetainsSummary() {
        val tool = ToolCall("legacy-tool", "commandExecution", "inProgress", "pwd", "line one\n")
        val original = state(listOf(host("legacy-host", listOf(tool), turn = "")))
        val updated = StreamReducer.apply(original, toolEvent("item/completed", "turn-a", JSONObject()
            .put("type", "commandExecution").put("id", tool.id).put("command", "pwd").put("status", "completed")))
        assertEquals(1, updated.messages.size)
        assertEquals("turn-a", updated.messages.single().turnId)
        assertEquals("pwd", updated.messages.single().tools.single().input)
        assertEquals("line one\n", updated.messages.single().tools.single().output)
        assertEquals("completed", updated.messages.single().tools.single().status)
    }

    private fun state(messages: List<ChatMessage>) = AppUiState(
        selected = Session("thread-a", "synthetic identity test"),
        running = true, activeTurnId = "turn-a", messages = messages
    )

    private fun toolEvent(method: String, turn: String, item: JSONObject) = JSONObject()
        .put("type", "app_server").put("message", JSONObject().put("method", method)
            .put("params", JSONObject().put("threadId", "thread-a").put("turnId", turn).put("item", item)))

    private fun sdk(index: Int, command: String, status: String = "completed", output: String = "") = ToolCall(
        "exec-00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}",
        "commandExecution", status,
        JSONObject().put("commandActions", JSONArray().put(JSONObject().put("command", command))).toString(), output
    )

    private fun history(index: Int, command: String, status: String = "completed", output: String = "") = ToolCall(
        "call_fixture$index:0", "exec_command", status, JSONObject().put("command", command).toString(), output
    )

    private fun host(
        id: String,
        tools: List<ToolCall>,
        turn: String = "turn-a",
        text: String = "",
        images: List<String> = emptyList(),
        deferred: Boolean = false
    ) = ChatMessage(id, "assistant", text, images = images, tools = tools, turnId = turn, deferred = deferred)
}
