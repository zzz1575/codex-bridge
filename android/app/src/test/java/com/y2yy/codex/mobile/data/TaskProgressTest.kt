package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskProgressTest {
    private val footer = "Read the ledger and summarize these changes. This is an internal notification, not a new task or authorization to write business data. Do not register this notification as a task."
    private fun payload() = JSONObject().put("event_id", "00000000-0000-5000-8000-000000000001").put("changes", JSONArray()
        .put(JSONObject().put("task_id", "T0001").put("change", "done"))
        .put(JSONObject().put("task_id", "T0002").put("change", "awaiting_delivery:execution_finished_not_delivery_or_business_proof")))
    private fun envelope(value: JSONObject = payload()) = "Y2YY_INTERNAL_SUMMARY $value\n$footer"

    @Test fun translatesHistoryAndLeavesOriginalRecordsIntact() {
        val message = ChatMessage("fixture", "user", envelope())
        val progress = TaskProgress.presentation(message)!!
        assertEquals(listOf(TaskProgressUpdate("T0001", "任务已完成", ""), TaskProgressUpdate("T0002", "等待交付验证", "执行已结束，尚未完成交付或业务验证")), progress.updates)
        assertEquals(message.text, progress.raw)
        assertEquals(2, TaskProgress.parse(" \n${message.text.replace("\n", "\r\n")}\n ")!!.updates.size)
    }

    @Test fun preservesSummariesOrdinaryTextExamplesAndAttachments() {
        val raw = envelope()
        listOf("普通消息", "解释一下\n$raw", "$raw\n补充要求", "```\n$raw\n```", raw.dropLast(4), "Y2YY_INTERNAL_SUMMARY {bad}\n$footer").forEach { assertNull(TaskProgress.parse(it)) }
        assertNull(TaskProgress.presentation(ChatMessage("fixture", "assistant", raw)))
        assertNull(TaskProgress.presentation(ChatMessage("fixture", "user", raw, images = listOf("synthetic-image"))))
    }

    @Test fun rejectsMalformedOrPartiallyValidEnvelopes() {
        listOf(payload().put("event_id", "invalid"), payload().put("event_id", JSONArray().put("00000000-0000-5000-8000-000000000001")),
            payload().put("changes", JSONArray()), payload().put("extra", "preserve me"), payload().put("changes", JSONArray().put(JSONObject.NULL)),
            payload().put("changes", JSONArray().put(JSONObject().put("task_id", "T0001").put("change", "done").put("extra", "preserve me"))))
            .forEach { assertNull(TaskProgress.parse(envelope(it))) }
    }

    @Test fun unknownFutureStatusNeverClaimsCompletionOrLeaksEnglish() {
        val raw = envelope(payload().put("changes", JSONArray().put(JSONObject().put("task_id", "T0099").put("change", "future_state:future_reason"))))
        assertEquals(TaskProgressUpdate("T0099", "任务状态已更新，待确认", "详细情况请查看统筹汇总"), TaskProgress.parse(raw)!!.updates.single())
    }
}
