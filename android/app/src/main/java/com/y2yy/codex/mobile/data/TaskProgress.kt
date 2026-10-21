package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.ChatMessage
import org.json.JSONObject
import org.json.JSONTokener

data class TaskProgressUpdate(val taskId: String, val status: String, val detail: String)
data class TaskProgressNotice(val eventId: String, val updates: List<TaskProgressUpdate>, val raw: String)

/** A view of the exact coordinator envelope. Stored history and assistant summaries stay intact. */
object TaskProgress {
    private const val prefix = "Y2YY_INTERNAL_SUMMARY "
    private const val notice = "Read the ledger and summarize these changes. This is an internal notification, not a new task or authorization to write business data. Do not register this notification as a task."
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val taskId = Regex("T[0-9]+")
    private val statuses = mapOf(
        "done" to "任务已完成", "failed" to "执行失败", "canceled" to "已取消",
        "ambiguous" to "执行状态待核对", "external_conflict" to "会话冲突，待核对",
        "blocked_dependency" to "前置任务未满足", "awaiting_delivery" to "等待交付验证",
        "awaiting_evidence" to "等待业务验证"
    )
    private val reasons = mapOf(
        "execution_finished_not_delivery_or_business_proof" to "执行已结束，尚未完成交付或业务验证",
        "original_delivery_receipt_unavailable_or_changed" to "交付回执不可用或已变化，需要重新核验",
        "predecessor_not_successful" to "前置任务尚未成功完成",
        "explicit_business_verification_not_recorded" to "尚未记录业务验证结果",
        "native_failed" to "执行过程失败，请查看统筹汇总",
        "native_interrupted" to "执行已中断，请查看统筹汇总"
    )

    fun presentation(message: ChatMessage): TaskProgressNotice? {
        if (message.role != "user" || message.images.isNotEmpty() || message.tools.isNotEmpty() ||
            message.imagePending || !message.imageError.isNullOrBlank()) return null
        return parse(message.text)
    }

    fun parse(text: String): TaskProgressNotice? = runCatching {
        val envelope = text.trim().replace("\r\n", "\n")
        val end = "\n$notice"
        if (!envelope.startsWith(prefix) || !envelope.endsWith(end)) return null
        val tokener = JSONTokener(envelope.substring(prefix.length, envelope.length - end.length))
        val payload = tokener.nextValue() as? JSONObject ?: return null
        if (tokener.nextClean() != '\u0000' || payload.keys().asSequence().toSet() != setOf("changes", "event_id")) return null
        val eventId = payload.opt("event_id") as? String ?: return null
        if (!uuid.matches(eventId)) return null
        val changes = payload.optJSONArray("changes") ?: return null
        if (changes.length() == 0) return null
        val updates = (0 until changes.length()).map { index ->
            val entry = changes.optJSONObject(index) ?: return null
            if (entry.keys().asSequence().toSet() != setOf("change", "task_id")) return null
            val id = entry.opt("task_id") as? String ?: return null
            val change = entry.opt("change") as? String ?: return null
            if (!taskId.matches(id) || change.isEmpty()) return null
            val status = change.substringBefore(':')
            val detail = if (':' in change) reasons[change.substringAfter(':')] ?: "详细情况请查看统筹汇总" else ""
            TaskProgressUpdate(id, statuses[status] ?: "任务状态已更新，待确认", detail)
        }
        TaskProgressNotice(eventId, updates, text)
    }.getOrNull()
}
