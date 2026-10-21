package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.Session
import com.y2yy.codex.mobile.model.SessionRunState
import org.json.JSONObject

/** One activity projection for the conversation list, task page and selected chat. */
object SessionActivity {
    data class ReadToken(val threadId: String, val revision: Long, val generation: Long, val sequence: Long)
    data class Observation(val token: ReadToken, val status: String?)

    fun capture(state: AppUiState, threadId: String, sequence: Long): ReadToken = ReadToken(
        threadId, state.sessionRunStates[threadId]?.revision ?: 0, state.sessionActivityGeneration, sequence
    )

    fun applyRead(state: AppUiState, token: ReadToken, status: String?): AppUiState {
        if (token.generation != state.sessionActivityGeneration ||
            token.revision != (state.sessionRunStates[token.threadId]?.revision ?: 0)) return state
        val normalized = normalize(status) ?: return state
        val previous = state.sessionRunStates[token.threadId]
        return update(state, token.threadId, normalized, previous?.turnId.takeIf { normalized == "active" && previous?.status == "active" },
            maxOf(previous?.sequence ?: -1, token.sequence))
    }

    fun mergeSessions(state: AppUiState, sessions: List<Session>, observations: List<Observation>): AppUiState {
        val updated = observations.fold(state) { current, observation ->
            applyRead(current, observation.token, observation.status)
        }
        return updated.copy(sessions = sessions.map { session ->
            session.copy(status = updated.sessionRunStates[session.id]?.status
                ?: if (session.id in updated.activeSessionIds) "active"
                else state.sessions.find { it.id == session.id }?.status ?: session.status)
        })
    }

    fun completionMatches(state: AppUiState, threadId: String, turnId: String): Boolean {
        val current = state.sessionRunStates[threadId]?.turnId
            ?: state.activeTurnId.takeIf { threadId == state.selected?.id }
        return current.isNullOrBlank() || turnId.isBlank() || current == turnId
    }

    fun apply(state: AppUiState, event: JSONObject): AppUiState {
        when (event.string("type")) {
            "bridge_event_stream" -> if (event.string("status") == "ready") return resetCursors(state)
            "bridge_event_gap" -> if (event.string("reason") == "sequence_reset") return resetCursors(state)
            "bridge_thread_run_state" -> {
                val id = event.string("thread_id")
                val turn = event.string("turn_id")
                if (id.isBlank() || turn.isBlank() || event.string("run_state") != "active") return state
                return update(state, id, "active", turn, event.optLong("bridge_sequence", -1))
            }
            "bridge_thread_activity_snapshot" -> return snapshot(state, event)
            "app_server" -> {
                val message = event.optJSONObject("message") ?: return state
                val params = message.optJSONObject("params") ?: return state
                val id = params.string("threadId")
                if (id.isBlank()) return state
                val turn = params.optJSONObject("turn")
                val turnId = params.string("turnId", turn?.string("id").orEmpty())
                val sequence = event.optLong("bridge_sequence", -1)
                return when (message.string("method")) {
                    "turn/started" -> update(state, id, "active", turnId.takeIf { it.isNotBlank() }, sequence)
                    "turn/completed" -> if (completionMatches(state, id, turnId)) {
                        update(state, id, completionStatus(turn), turnId.takeIf { it.isNotBlank() }, sequence)
                    } else state
                    "thread/status/changed" -> {
                        val status = statusType(params.opt("status")) ?: return state
                        val previous = state.sessionRunStates[id]
                        update(state, id, status, previous?.turnId.takeIf { status == "active" && previous?.status == "active" }, sequence)
                    }
                    else -> state
                }
            }
        }
        return state
    }

    fun statusType(value: Any?): String? = normalize(if (value is JSONObject) value.string("type") else value as? String)

    private fun normalize(status: String?): String? = when (status?.lowercase()) {
        "active", "running", "inprogress", "in_progress", "started", "busy", "streaming" -> "active"
        "idle", "ready" -> "idle"
        "completed", "complete", "success", "succeeded", "done" -> "completed"
        "failed", "failure", "error" -> "failed"
        "interrupted", "cancelled", "canceled", "stopped" -> "cancelled"
        else -> null
    }

    private fun completionStatus(turn: JSONObject?): String = normalize(turn?.string("status"))
        ?: if (turn?.has("error") == true && !turn.isNull("error")) "failed" else "completed"

    private fun resetCursors(state: AppUiState): AppUiState = state.copy(
        sessionRunStates = state.sessionRunStates.mapValues { (_, entry) -> entry.copy(sequence = -1, turnId = null) },
        sessionActivityGeneration = state.sessionActivityGeneration + 1,
        activeTurnId = null
    )

    private fun snapshot(state: AppUiState, event: JSONObject): AppUiState {
        val array = event.optJSONArray("active_thread_ids") ?: return state
        val active = (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
        val statuses = event.optJSONObject("thread_states")
        val turns = event.optJSONObject("thread_turn_ids")
        val sequences = event.optJSONObject("thread_sequences")
        val ids = state.sessionRunStates.keys + state.sessions.map { it.id } + state.activeSessionIds + active +
            (statuses?.keys()?.asSequence()?.toSet() ?: emptySet())
        var next = state
        for (id in ids) {
            val explicit = id in active || statuses?.has(id) == true
            val sequence = sequences?.optLong(id, -1) ?: -1
            val previous = next.sessionRunStates[id]
            // An omitted entry cannot undo a newly observed start while the
            // server is still building its next complete snapshot.
            if (!explicit && (previous?.sequence ?: -1) >= 0) continue
            val status = if (id in active) "active" else if (statuses?.has(id) == true)
                normalize(statuses.string(id)) ?: continue else "idle"
            val turn = turns?.string(id)?.takeIf(String::isNotBlank)
                ?: previous?.turnId.takeIf { status == "active" && previous?.status == "active" }
            next = update(next, id, status, turn, sequence)
        }
        return next
    }

    private fun update(state: AppUiState, id: String, status: String, turnId: String?, sequence: Long): AppUiState {
        val previous = state.sessionRunStates[id]
        if (sequence >= 0 && previous != null && sequence < previous.sequence) return state
        val active = status == "active"
        val selected = state.selected?.id == id
        val revision = state.sessionActivityRevision + 1
        val knownTurn = turnId ?: previous?.turnId.takeIf { active && previous?.status == "active" }
        val newTurn = active && !knownTurn.isNullOrBlank() && knownTurn != previous?.turnId
        val activity = if (!selected) state.activity else when {
            active && (newTurn || !state.running) -> "正在思考"
            status == "failed" -> "执行失败"
            status == "cancelled" -> "已停止"
            !active && state.running -> "已完成"
            else -> state.activity
        }
        return state.copy(
            sessionRunStates = state.sessionRunStates + (id to SessionRunState(status, knownTurn, maxOf(sequence, previous?.sequence ?: -1), revision)),
            sessionActivityRevision = revision,
            activeSessionIds = if (active) state.activeSessionIds + id else state.activeSessionIds - id,
            sessions = state.sessions.map { if (it.id == id) it.copy(status = status) else it },
            running = if (selected) active else state.running,
            activeTurnId = if (!selected) state.activeTurnId else if (active) knownTurn ?: state.activeTurnId else null,
            activity = activity
        )
    }
}
