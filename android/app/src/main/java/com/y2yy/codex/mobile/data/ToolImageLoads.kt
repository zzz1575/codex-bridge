package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.ChatMessage
import kotlinx.coroutines.sync.Mutex

enum class ToolImageLoadStatus { LOADING, LOADED, FAILED }

/** All known identities refer to one request, including an SDK ID replaced by a durable ID. */
fun markToolImageLoad(loads: MutableMap<String, ToolImageLoadStatus>, identities: Set<String>, status: ToolImageLoadStatus) {
    identities.filter { it.isNotBlank() }.forEach { loads[it] = status }
}

/** Register newly discovered aliases against the existing request lock before suspending. */
fun toolImageLoadLock(locks: MutableMap<String, Mutex>, identities: Set<String>): Mutex {
    val valid = identities.filter { it.isNotBlank() }
    require(valid.isNotEmpty()) { "Tool image request requires an identity" }
    val lock = valid.firstNotNullOfOrNull { locks[it] } ?: Mutex()
    valid.forEach { locks[it] = lock }
    return lock
}

/** Aggregates only image requests belonging to this message, including reconciled SDK aliases. */
fun withToolImageLoads(message: ChatMessage, loads: Map<String, ToolImageLoadStatus>): ChatMessage {
    val statuses = message.tools.asSequence()
        .flatMap { tool -> (sequenceOf(tool.id) + tool.aliases.asSequence()) }
        .filter { it.isNotBlank() }
        .mapNotNull { loads[it] }
        .toList()
    if (statuses.isEmpty()) return message
    return message.copy(
        imagePending = ToolImageLoadStatus.LOADING in statuses,
        imageError = if (ToolImageLoadStatus.FAILED in statuses) "部分图片加载失败，请重试" else null
    )
}
