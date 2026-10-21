package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.*
import org.json.JSONObject

object StreamReducer {
    fun apply(state: AppUiState, event: JSONObject): AppUiState {
        val activityState = SessionActivity.apply(state, event)
        if (event.string("type") != "app_server") return activityState
        val message = event.optJSONObject("message") ?: return state
        val method = message.string("method")
        val p = message.optJSONObject("params") ?: return state
        val threadId = p.string("threadId")
        var next = activityState
        if (method == "thread/name/updated") {
            val name = p.string("threadName").takeIf { it.isNotBlank() } ?: return state
            return state.copy(sessions = state.sessions.map { if (it.id == threadId) it.copy(title = name) else it },
                selected = state.selected?.let { if (it.id == threadId) it.copy(title = name) else it })
        }
        if (method == "thread/settings/updated") {
            val settings = p.optJSONObject("threadSettings") ?: return state
            val model = settings.string("model").ifBlank {
                if (threadId == state.selected?.id) state.composerModel.ifBlank { state.selected.model }
                else state.sessions.find { it.id == threadId }?.model.orEmpty()
            }
            val cwd = settings.string("cwd").takeIf { it.isNotBlank() }
            next = state.copy(sessions = state.sessions.map { if (it.id == threadId) it.copy(model = model, projectPath = cwd ?: it.projectPath) else it })
            if (threadId != state.selected?.id) return next
            val effort = if (settings.has("effort")) settings.string("effort").ifBlank { state.modelOptions.find { it.id == model }?.defaultEffort.orEmpty() } else state.composerEffort
            val tier = ServiceTiers.fromSettings(settings, state.composerServiceTier)
            return next.copy(selected = state.selected?.copy(model = model, projectPath = cwd ?: state.selected.projectPath),
                composerModel = model, composerEffort = effort,
                composerServiceTier = tier, modelSettingsError = ServiceTiers.errorFor(tier, state.modelSettingsError))
        }
        if (method == "thread/status/changed") return next
        val turn = p.optJSONObject("turn")
        val turnId = p.string("turnId", turn?.string("id").orEmpty())
        if (method == "turn/completed" && (activityState === state || !SessionActivity.completionMatches(state, threadId, turnId))) return next
        if (threadId != state.selected?.id) return next
        when (method) {
            "turn/started" -> return next
            "turn/completed" -> {
                val status = turn?.string("status").orEmpty()
                return next.copy(running = false, activeTurnId = null, activity = when (status) { "failed" -> "执行失败"; "interrupted" -> "已停止"; else -> "已完成" },
                    messages = next.messages.map { m -> m.copy(streaming = false, tools = m.tools.map { t ->
                        if (m.turnId == turnId && t.status.lowercase() in listOf("inprogress", "in_progress", "running", "started")) t.copy(status = "interrupted") else t
                    }) }, activeSessionIds = next.activeSessionIds - threadId)
            }
            "item/agentMessage/delta" -> {
                val id = p.string("itemId")
                val existing = next.messages.find { it.id == id || it.id == "live:$id" }
                val updated = existing?.copy(text = existing.text + p.string("delta"), streaming = true)
                    ?: ChatMessage("live:$id", "assistant", p.string("delta"), streaming = true, turnId = turnId)
                return next.copy(messages = if (existing == null) next.messages + updated else next.messages.map { if (it.id == existing.id) updated else it }, running = true, activeTurnId = turnId.ifBlank { next.activeTurnId }, activity = "正在回复")
            }
            "item/started", "item/completed" -> {
                val item = p.optJSONObject("item") ?: return next
                val id = item.string("id")
                if (item.string("type") == "agentMessage") {
                    val existing = next.messages.find { it.id == id || it.id == "live:$id" }
                    val updated = ChatMessage(id, "assistant", item.string("text"), streaming = method == "item/started", turnId = turnId)
                    return if (updated.text.isBlank()) next else next.copy(messages = if (existing == null) next.messages + updated else next.messages.map { if (it.id == existing.id) updated else it })
                }
                val tool = typedTool(item, method == "item/completed")
                if (tool != null) {
                    val images = if (item.string("type") == "webSearch") emptyList() else MessageImages.collect(item)
                    val mediaTool = tool.copy(hasImage = images.isNotEmpty() ||
                        (item.string("type") == "imageView" && item.string("path").isNotBlank()) ||
                        (item.string("type") == "imageGeneration" && item.string("savedPath").isNotBlank()))
                    val updated = upsertTool(next, mediaTool, turnId.ifBlank { next.activeTurnId.orEmpty() })
                    return updated.copy(
                        messages = if (images.isEmpty()) updated.messages else updated.messages.map { host ->
                            if (host.tools.any { it.id == tool.id || tool.id in it.aliases }) host.copy(images = (host.images + images).distinct()) else host
                        },
                        activity = if (method == "item/started") "正在执行：${tool.name}" else "继续处理"
                    )
                }
            }
            "item/commandExecution/outputDelta" -> {
                val id = p.string("itemId")
                val toolTurn = turnId.ifBlank { next.activeTurnId.orEmpty() }
                val host = findToolHost(next.messages, setOf(id), toolTurn)
                val existing = next.messages.getOrNull(host)?.tools?.find { it.id == id || id in it.aliases }
                    ?: ToolCall(id, "执行命令", "inProgress")
                return upsertTool(next, existing.copy(output = (existing.output + p.string("delta")).takeLast(64000)), toolTurn)
            }
        }
        return next
    }

    /** All 19 audited SDK variants are classified; messages/lifecycle items are not tools. */
    private fun typedTool(item: JSONObject, completed: Boolean): ToolCall? {
        val type = item.string("type")
        val id = item.string("id").takeIf { it.isNotBlank() } ?: return null
        fun qualified(namespace: String, name: String) = listOf(namespace, name).filter { it.isNotBlank() }.joinToString(".")
        val name = when (type) {
            "userMessage", "hookPrompt", "agentMessage", "plan", "reasoning", "subAgentActivity", "enteredReviewMode", "exitedReviewMode", "contextCompaction", "" -> return null
            "commandExecution", "fileChange" -> type
            "mcpToolCall" -> qualified(item.string("server"), item.string("tool", "mcp_tool"))
            "dynamicToolCall" -> qualified(item.string("namespace"), item.string("tool", "dynamic_tool"))
            "collabAgentToolCall" -> item.string("tool", "collab_agent")
            "functionCallOutput" -> qualified(item.string("namespace"), item.string("name").takeIf { it.isNotBlank() } ?: return null)
            "webSearch" -> "web_search"
            "imageView" -> "view_image"
            "sleep" -> "clock.sleep"
            "imageGeneration" -> "image_generation"
            else -> type // Future extensions remain inspectable and can carry supported media.
        }
        val input = JSONObject(item.toString()).apply { remove("id"); remove("status") }
        val output = JSONObject()
        for (field in listOf("aggregatedOutput", "exitCode", "durationMs", "result", "error", "contentItems", "success", "output", "failure", "results")) {
            if (field == "durationMs" && type == "sleep") continue
            if (input.has(field)) input.remove(field)?.takeIf { it !== JSONObject.NULL }?.let { output.put(field, it) }
        }
        val outputText = if (type == "commandExecution") buildString {
            append(item.string("aggregatedOutput"))
            if (item.has("exitCode") && !item.isNull("exitCode")) { if (isNotEmpty()) append('\n'); append("退出码：").append(item.opt("exitCode")) }
            if (item.has("durationMs") && !item.isNull("durationMs")) { if (isNotEmpty()) append('\n'); append("耗时毫秒：").append(item.opt("durationMs")) }
        } else if (output.length() > 0) output.toString(2) else ""
        return ToolCall(id, name, item.string("status", if (completed) "completed" else "inProgress"),
            if (type == "commandExecution") item.string("command", input.toString(2)).take(64000) else input.toString(2).take(64000),
            outputText.takeLast(64000))
    }
    private fun upsertTool(state: AppUiState, tool: ToolCall, turnId: String): AppUiState {
        val incomingIds = tool.aliases + tool.id
        val host = findToolHost(state.messages, incomingIds, turnId)
        // Keep both observations until reconciliation: replacing first would lose an existing
        // terminal state, richer output and the historical command/primary ID before the merge.
        if (host >= 0) return state.copy(messages = ToolIdentity.reconcile(state.messages.mapIndexed { i, m ->
            if (i == host) m.copy(turnId = turnId.ifBlank { m.turnId }, tools = m.tools + tool) else m
        }))
        val id = "live:tools:$turnId"
        val index = state.messages.indexOfFirst { it.id == id }
        if (index >= 0) return state.copy(messages = ToolIdentity.reconcile(state.messages.mapIndexed { i, m -> if (i == index) m.copy(tools = m.tools + tool) else m }))
        return state.copy(messages = ToolIdentity.reconcile(state.messages + ChatMessage(id, "assistant", "", tools = listOf(tool), turnId = turnId)))
    }

    private fun findToolHost(messages: List<ChatMessage>, ids: Set<String>, turnId: String): Int {
        val matches = messages.indices.filter { index -> messages[index].tools.any { tool ->
            (tool.aliases + tool.id).any { it.isNotBlank() && it in ids }
        } }
        matches.firstOrNull { messages[it].turnId == turnId }?.let { return it }
        // Older cached hosts may lack turn metadata. Adopt the event's turn only when a
        // strong identity has one unambiguous host; never cross an already-known turn.
        return matches.singleOrNull()?.takeIf { turnId.isNotBlank() && messages[it].turnId.isBlank() } ?: -1
    }
}
