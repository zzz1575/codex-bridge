package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.AppUiState

/** A display projection, not a timeout, retry policy or execution-state authority. */
object ModelResponseWait {
    fun isWaiting(state: AppUiState): Boolean {
        val turnId = state.activeTurnId?.takeIf { it.isNotBlank() } ?: return false
        if (!state.running || state.activity !in setOf("", "正在思考", "等待模型响应")) return false
        if (state.modelProgressTurnId == turnId) return false
        return state.messages.none { message ->
            message.turnId == turnId && message.role != "user" &&
                (message.text.isNotBlank() || message.tools.isNotEmpty() || message.images.isNotEmpty() || message.imagePending)
        }
    }
}
