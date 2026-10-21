package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.PendingInput
import com.y2yy.codex.mobile.model.PendingSnapshot
import org.json.JSONObject

/** Queue absence is only evidence when the native, scoped read is authoritative. */
object PendingMessages {
    private const val UNCONFIRMED = "队列状态尚未确认，请重新同步"
    private val nativeSources = setOf("app_server_queue", "demo_wasm")

    internal fun entries(j: JSONObject): List<PendingInput> = j.optJSONArray("messages")?.objects()?.map { entry ->
        val startError = errorMessage(entry.opt("start_error"))
        PendingInput(
            id = entry.string("id"), text = entry.string("text"), status = entry.string("status"),
            action = entry.string("action"), threadId = entry.string("thread_id"), source = entry.string("source"),
            queuedSubmissionId = entry.string("queued_submission_id"), error = errorMessage(entry.opt("error")),
            startError = startError, partial = entry.opt("partial") == true || startError != null
        )
    }.orEmpty()

    fun parse(j: JSONObject, threadId: String): PendingSnapshot {
        val raw = j.optJSONArray("messages")
        val parsed = entries(j)
        val scoped = parsed.filter { it.threadId == threadId && it.id.isNotBlank() }
        val invalid = threadId.isBlank() || raw == null || raw.length() != parsed.size ||
            parsed.any { it.threadId != threadId || it.id.isBlank() } ||
            parsed.groupBy { it.id }.any { (_, entries) -> entries.distinct().size > 1 }
        val available = !invalid && j.opt("queue_available") == true
        val authoritative = available && j.opt("queue_authoritative") == true &&
            j.string("queue_backend") in setOf("app_server", "demo_wasm")
        val error = errorMessage(j.opt("queue_error")) ?: when {
            invalid -> "队列响应与当前会话不一致，请重新同步"
            !available || !authoritative -> UNCONFIRMED
            else -> null
        }
        return PendingSnapshot(scoped.distinctBy { it.id }, available, authoritative && error == null, error)
    }

    /** Both generation and thread must still match, including A -> B -> A selections. */
    fun reduce(
        state: AppUiState,
        snapshot: PendingSnapshot,
        threadId: String,
        requestGeneration: Long,
        currentGeneration: Long,
        requestRevision: Long = 0,
        currentRevision: Long = 0
    ): AppUiState {
        if (requestGeneration != currentGeneration || requestRevision != currentRevision || state.selected?.id != threadId) return state
        val scoped = snapshot.messages.all { it.threadId == threadId && it.id.isNotBlank() }
        val verified = snapshot.available && snapshot.authoritative && snapshot.error == null && scoped
        return if (verified) state.copy(
            pending = snapshot.messages, pendingVerified = true, pendingSyncError = null
        ) else state.copy(
            pendingVerified = false, pendingSyncError = snapshot.error ?: UNCONFIRMED
        )
    }

    fun canCancel(input: PendingInput, verified: Boolean): Boolean = verified &&
        input.status == "queued" && input.source in nativeSources && input.id.isNotBlank() && input.threadId.isNotBlank()

    fun label(input: PendingInput, verified: Boolean): String = when (input.status) {
        "queued" -> if (canCancel(input, verified)) "排队中" else "同步未确认"
        "accepted", "dequeued" -> if (input.action == "steer") "已跟进" else "已提交 · 等待历史同步"
        "steered" -> "已跟进"
        "applied" -> "已同步到会话"
        "processing" -> "处理中"
        "failed" -> "发送失败"
        else -> "同步未确认"
    }

    fun warning(input: PendingInput): String? = listOfNotNull(input.error, input.startError)
        .filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")
        ?: if (input.partial) "消息已进入队列，但自动启动失败，请重新同步后确认" else null

    private fun errorMessage(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject -> value.string("message").takeIf { it.isNotBlank() }
        else -> null
    }
}
