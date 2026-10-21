package com.y2yy.codex.mobile.data

import org.json.JSONObject

/** Session metadata operations; the caller handles writer/idle state and reads back applied changes. */
object SessionCommands {
    /** Omission preserves old server behavior; Standard explicitly clears native priority. */
    fun modelSettings(threadId: String, model: String, effort: String, serviceTier: String? = null): JSONObject {
        require(threadId.isNotBlank()) { "请选择会话" }
        require(model.isNotBlank() && effort.isNotBlank()) { "请选择模型和思考强度" }
        return JSONObject().put("command", "thread_settings_update").put("thread_id", threadId)
            .put("model", model).put("effort", effort).apply {
                serviceTier?.let { put("service_tier", ServiceTiers.requireValid(it)) }
            }
    }

    /** Only creation carries the new-session default; sending never silently changes a session. */
    fun create(projectPath: String?, prompt: String, serviceTier: String? = null): JSONObject = JSONObject()
        .put("command", "thread_create").put("project_path", projectPath ?: JSONObject.NULL)
        .put("worktree", false).apply {
            if (prompt.isNotBlank()) put("prompt", prompt)
            serviceTier?.let { put("service_tier", ServiceTiers.requireValid(it)) }
        }

    fun rename(threadId: String, name: String): JSONObject {
        require(threadId.isNotBlank()) { "请选择会话" }
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= 200) {
            "会话名称须为 1 至 200 个字符"
        }
        return JSONObject()
            .put("command", "thread_rename")
            .put("thread_id", threadId)
            .put("name", trimmed)
    }

    /** Protocol 43 also synchronizes project membership and the Bridge directory index. */
    fun move(threadId: String, targetPath: String, protocolVersion: Int): JSONObject {
        require(threadId.isNotBlank()) { "请选择会话" }
        requireServerPath(targetPath)
        require(protocolVersion > 0) { "服务器未返回有效协议版本，请刷新后重试" }
        if (protocolVersion >= 43) return JSONObject()
            .put("command", "thread_cwd_update")
            .put("thread_id", threadId)
            .put("cwd", targetPath)
        return JSONObject()
            .put("command", "app_server_rpc")
            .put("method", "thread/settings/update")
            .put("params", JSONObject().put("threadId", threadId).put("cwd", targetPath))
    }

    /** The dedicated command canonicalizes symlinks; read-back must verify its returned path. */
    fun movedCwd(result: JSONObject, requestedPath: String, protocolVersion: Int): String {
        require(protocolVersion > 0) { "服务器未返回有效协议版本，请刷新后重试" }
        val cwd = if (protocolVersion >= 43) result.string("cwd") else requestedPath
        requireServerPath(cwd)
        return cwd
    }

    private fun requireServerPath(path: String) {
        require(path.startsWith('/') && '\u0000' !in path) { "目标项目必须使用服务器绝对目录" }
    }
}
