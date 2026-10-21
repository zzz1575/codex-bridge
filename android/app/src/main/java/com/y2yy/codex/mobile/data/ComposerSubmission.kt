package com.y2yy.codex.mobile.data

import org.json.JSONObject

/** The server decides whether a turn exists; cached UI activity must not select queueing. */
object ComposerSubmission {
    suspend fun submit(
        body: JSONObject,
        mode: String = "steer",
        command: suspend (JSONObject) -> JSONObject
    ): JSONObject {
        require(mode in setOf("steer", "queue")) { "请选择跟进或排队" }
        val first = JSONObject(body.toString()).put("command", if (mode == "steer") "steer" else "send")
        val result = try {
            command(first)
        } catch (failure: BridgeException) {
            if (mode != "steer" || failure.code != "no_active_turn") throw failure
            // Reuse the exact submission identity and encoded attachments. Ambiguous failures,
            // ownership conflicts and timeouts must never cause another mutation.
            command(JSONObject(body.toString()).put("command", "send"))
        }
        return confirmed(result, body)
    }

    /** A transport success is not an acknowledgement of the submitted message. */
    private fun confirmed(result: JSONObject, body: JSONObject): JSONObject {
        val status = result.optString("status")
        if (status == "failed") {
            throw BridgeException("submission_failed", result.optString("error").ifBlank { "发送失败，输入和附件已保留" })
        }
        if (status !in setOf("queued", "accepted", "processing", "dequeued", "steered", "applied") ||
            (result.optString("thread_id").isNotBlank() && result.optString("thread_id") != body.optString("thread_id")) ||
            (result.optString("pending_id").isNotBlank() && result.optString("pending_id") != body.optString("submission_id"))) {
            throw BridgeException("submission_unconfirmed", "服务器尚未确认接收，请同步后再确认；输入和附件已保留")
        }
        // A partial queue/start receipt confirms the queue add. Do not resend it.
        return result
    }
}
