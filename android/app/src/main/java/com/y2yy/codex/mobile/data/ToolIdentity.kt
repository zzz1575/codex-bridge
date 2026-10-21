package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.ToolCall
import org.json.JSONObject

/** Reconciles durable tool calls with SDK overlays without hiding genuine repeated executions. */
object ToolIdentity {
    fun reconcile(messages: List<ChatMessage>): List<ChatMessage> {
        val records = mutableListOf<Record>()
        val byId = hashMapOf<ScopedId, Record>()
        messages.forEachIndexed { messageIndex, message ->
            message.tools.forEachIndexed { toolIndex, tool ->
                val incoming = Record(messageIndex, toolIndex, message.turnId, tool, source(tool.id))
                val keys = identities(tool).map { ScopedId(message.turnId, it) }
                val previous = keys.mapNotNull(byId::get).distinct()
                val record = if (previous.isEmpty()) {
                    val record = incoming
                    records += record
                    record
                } else {
                    val record = previous.first()
                    previous.drop(1).forEach { duplicate ->
                        mergeInto(record, duplicate, newer = false)
                        duplicate.removed = true
                    }
                    // A later snapshot may enrich the same ID, but cannot undo an observed terminal state.
                    mergeInto(record, incoming, newer = true)
                    record
                }
                identities(record.tool).forEach { byId[ScopedId(message.turnId, it)] = record }
            }
        }

        val commands = linkedMapOf<CommandKey, SourceQueues>()
        records.forEach { record ->
            if (record.removed || record.turnId.isBlank() || record.source == Source.UNKNOWN) return@forEach
            // A previously paired historical occurrence must not consume a new execution
            // of the same command while that execution's durable log is still catching up.
            if (record.source == Source.HISTORY && record.tool.aliases.any { source(it) == Source.SDK }) return@forEach
            if (record.source == Source.SDK && record.tool.aliases.any { source(it) == Source.HISTORY }) return@forEach
            val command = command(record.tool) ?: return@forEach
            val queues = commands.getOrPut(CommandKey(record.turnId, command)) { SourceQueues() }
            when (record.source) {
                Source.HISTORY -> queues.history += record
                Source.SDK -> queues.sdk += record
                Source.UNKNOWN -> Unit
            }
        }
        commands.values.forEach { queues ->
            // Pair occurrences rather than distinct(command): two executions must remain two.
            for (index in 0 until minOf(queues.history.size, queues.sdk.size)) {
                val history = queues.history[index]
                val sdk = queues.sdk[index]
                history.tool = merge(history.tool, sdk.tool)
                sdk.removed = true
            }
        }

        val retained = Array(messages.size) { mutableListOf<ToolCall>() }
        records.filterNot { it.removed }.sortedWith(compareBy({ it.messageIndex }, { it.toolIndex }))
            .forEach { retained[it.messageIndex] += it.tool }
        return messages.mapIndexedNotNull { index, original ->
            val tools = retained[index]
            if (original.tools.isNotEmpty() && tools.isEmpty() && removableHost(original)) null
            else if (original.tools == tools) original
            else original.copy(tools = tools)
        }
    }

    private fun removableHost(message: ChatMessage) =
        message.role in setOf("assistant", "tool") && message.text.isBlank() &&
            message.images.isEmpty() && !message.streaming && !message.imagePending &&
            message.imageError.isNullOrBlank() && !message.deferred &&
            message.deferredToolCount == 0 && message.deferredFileCount == 0

    private fun merge(primary: ToolCall, other: ToolCall): ToolCall = primary.copy(
        name = primary.name.ifBlank { other.name },
        input = primary.input.ifBlank { other.input },
        status = when {
            terminal(primary.status) -> primary.status
            terminal(other.status) -> other.status
            else -> primary.status.ifBlank { other.status }
        },
        output = mergeOutput(primary.output, other.output),
        hasImage = primary.hasImage || other.hasImage,
        aliases = (primary.aliases + other.aliases + other.id).filter { it.isNotBlank() && it != primary.id }.toSet()
    )

    private fun identities(tool: ToolCall) = (tool.aliases + tool.id).filter { it.isNotBlank() }

    private fun mergeInto(record: Record, incoming: Record, newer: Boolean) {
        val replacePrimary = incoming.source.ordinal < record.source.ordinal
        if (replacePrimary) {
            record.tool = merge(incoming.tool, record.tool)
            record.messageIndex = incoming.messageIndex
            record.toolIndex = incoming.toolIndex
            record.source = incoming.source
        } else if (incoming.source == record.source && incoming.tool.id == record.tool.id && newer) {
            record.tool = merge(incoming.tool, record.tool)
        } else record.tool = merge(record.tool, incoming.tool)
    }

    private fun mergeOutput(primary: String, other: String): String {
        if (primary.isBlank()) return other
        if (other.isBlank() || primary.contains(other)) return primary
        if (other.contains(primary)) return other
        val forward = overlap(primary, other)
        val backward = overlap(other, primary)
        if (forward > 0 && forward >= backward) return primary + other.substring(forward)
        if (backward > 0) return other + primary.substring(backward)
        return primary + (if (primary.endsWith('\n') || other.startsWith('\n')) "" else "\n") + other
    }

    /** Linear-time suffix/prefix overlap prevents growing streamed output from being appended twice. */
    private fun overlap(value: String, prefix: String): Int {
        val failure = IntArray(prefix.length)
        for (index in 1 until prefix.length) {
            var length = failure[index - 1]
            while (length > 0 && prefix[index] != prefix[length]) length = failure[length - 1]
            if (prefix[index] == prefix[length]) length++
            failure[index] = length
        }
        var length = 0
        value.forEach { char ->
            while (length > 0 && (length == prefix.length || char != prefix[length])) length = failure[length - 1]
            if (char == prefix[length]) length++
        }
        return length
    }

    private fun terminal(status: String) = status.lowercase() in setOf(
        "completed", "complete", "succeeded", "success", "failed", "error",
        "interrupted", "cancelled", "canceled", "done"
    )

    private fun source(id: String): Source = when {
        sdkId.matches(id) -> Source.SDK
        historyId.matches(id) -> Source.HISTORY
        else -> Source.UNKNOWN
    }

    private fun command(tool: ToolCall): String? {
        val value = tool.input.trim().takeIf { it.isNotEmpty() && it.length <= MAX_COMMAND_CHARS } ?: return null
        if (!executionName(tool.name)) return null
        if (!value.startsWith('{')) return normalizeCommand(value)

        val json = runCatching { JSONObject(value) }.getOrNull() ?: return null
        val direct = when {
            json.has("command") -> (json.opt("command") as? String)?.let(::normalizeCommand)
            json.has("cmd") -> (json.opt("cmd") as? String)?.let(::normalizeCommand)
            else -> null
        }
        if (!json.has("commandActions")) return direct
        val actions = json.optJSONArray("commandActions") ?: return null
        if (actions.length() != 1) return null
        val action = actions.optJSONObject(0) ?: return null
        val actionCommand = (action.opt("command") as? String)?.let(::normalizeCommand) ?: return null
        // Conflicting shell and action descriptions do not establish an identity.
        if ((json.has("command") || json.has("cmd")) && direct != actionCommand) return null
        return actionCommand
    }

    private fun executionName(name: String): Boolean {
        val simple = name.substringAfterLast('.').lowercase()
        return simple in setOf("exec_command", "commandexecution", "exec", "shell", "执行命令")
    }

    private fun normalizeCommand(raw: String): String? {
        var value = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        repeat(3) {
            val wrapper = shellWrapper.matchEntire(value) ?: return value
            value = singleShellArgument(wrapper.groupValues[1])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        }
        // More nested shells are deliberately left uncertain.
        return value.takeUnless { shellWrapper.matches(it) }
    }

    /** Parses one literal shell argument without evaluating expansion or executing any input. */
    private fun singleShellArgument(raw: String): String? {
        val result = StringBuilder()
        var quote: Char? = null
        var index = 0
        var started = false
        while (index < raw.length) {
            val char = raw[index]
            when (quote) {
                '\'' -> if (char == '\'') quote = null else result.append(char)
                '"' -> when (char) {
                    '"' -> quote = null
                    '$', '`' -> return null
                    '\\' -> {
                        if (++index >= raw.length) return null
                        val escaped = raw[index]
                        if (escaped in "\\\"\$`") result.append(escaped)
                        else if (escaped != '\n') result.append('\\').append(escaped)
                    }
                    else -> result.append(char)
                }
                else -> when {
                    char.isWhitespace() -> return if (started && raw.substring(index).all { it.isWhitespace() }) result.toString() else null
                    char == '\'' || char == '"' -> { quote = char; started = true }
                    char == '\\' -> {
                        if (++index >= raw.length) return null
                        if (raw[index] != '\n') result.append(raw[index])
                        started = true
                    }
                    char in "\$`*?[]~{}();&|<>" -> return null
                    else -> { result.append(char); started = true }
                }
            }
            index++
        }
        return if (quote == null && started) result.toString() else null
    }

    private enum class Source { HISTORY, SDK, UNKNOWN }
    private data class ScopedId(val turnId: String, val id: String)
    private data class CommandKey(val turnId: String, val command: String)
    private class SourceQueues(
        val history: MutableList<Record> = mutableListOf(),
        val sdk: MutableList<Record> = mutableListOf()
    )
    private class Record(
        var messageIndex: Int,
        var toolIndex: Int,
        val turnId: String,
        var tool: ToolCall,
        var source: Source,
        var removed: Boolean = false
    )

    private const val MAX_COMMAND_CHARS = 128 * 1024
    private val sdkId = Regex("exec-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val historyId = Regex("call_[A-Za-z0-9_-]+(?::[0-9]+)?")
    private val shellWrapper = Regex("(?:/(?:usr/)?bin/)?(?:bash|sh)\\s+-(?:lc|c)\\s+(.+)", RegexOption.DOT_MATCHES_ALL)
}
