package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.ModelOption
import com.y2yy.codex.mobile.model.ReasoningOption
import com.y2yy.codex.mobile.model.Session
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protocol payloads only: never sends commands or reads server credentials. */
class SessionActionsTest {
    @Test fun renameTrimsTitleAndUsesTheBridgeDedicatedCommand() {
        val request = SessionCommands.rename("thread-a", "  项目诊断  ")

        assertEquals("thread_rename", request.getString("command"))
        assertEquals("thread-a", request.getString("thread_id"))
        assertEquals("项目诊断", request.getString("name"))
    }

    @Test fun renameLimitCountsUnicodeCodePointsInsteadOfUtf16Units() {
        val title = "\uD83D\uDE80".repeat(200)

        assertEquals(title, SessionCommands.rename("thread-a", title).getString("name"))
        assertThrows(IllegalArgumentException::class.java) { SessionCommands.rename("thread-a", title + "\uD83D\uDE80") }
    }

    @Test fun renameRejectsEmptyNamesAndMoreThanTwoHundredCharacters() {
        assertThrows(IllegalArgumentException::class.java) { SessionCommands.rename("thread-a", " \n\t ") }
        assertThrows(IllegalArgumentException::class.java) { SessionCommands.rename("thread-a", "a".repeat(201)) }
        assertEquals(200, SessionCommands.rename("thread-a", "a".repeat(200)).getString("name").length)
    }

    @Test fun renameRejectsMissingThreadIdentity() {
        assertThrows(IllegalArgumentException::class.java) { SessionCommands.rename("", "Valid title") }
    }

    @Test fun protocol42MovePatchesSavedCwdWithoutResettingPermissionsOrModel() {
        val request = SessionCommands.move("thread-a", "/workspace/project-b", 42)
        val params = request.getJSONObject("params")

        assertEquals("app_server_rpc", request.getString("command"))
        assertEquals("thread/settings/update", request.getString("method"))
        assertEquals("thread-a", params.getString("threadId"))
        assertEquals("/workspace/project-b", params.getString("cwd"))
        assertEquals(setOf("threadId", "cwd"), params.keys().asSequence().toSet())
        for (field in listOf("approvalPolicy", "sandbox", "sandboxPolicy", "permissions", "model", "effort", "projectId")) {
            assertFalse("Moving a session must retain its original $field", params.has(field))
        }
    }

    @Test fun protocol43AndFutureVersionsUseTheDedicatedMoveWithNoPermissionOverrides() {
        for (version in listOf(43, 44, 100)) {
            val request = SessionCommands.move("thread-a", "/workspace/project-b", version)

            assertEquals("thread_cwd_update", request.getString("command"))
            assertEquals("thread-a", request.getString("thread_id"))
            assertEquals("/workspace/project-b", request.getString("cwd"))
            assertEquals(setOf("command", "thread_id", "cwd"), request.keys().asSequence().toSet())
        }
    }

    @Test fun missingProtocolNeverSilentlyFallsBackToAnUnindexedMove() {
        for (version in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                SessionCommands.move("thread-a", "/workspace/project-b", version)
            }
        }
    }

    @Test fun movePreservesSpacesAndSupportsAnApiDiscoveredProjectlessRoot() {
        for (version in listOf(42, 43)) {
            for (path in listOf("/workspace/project with spaces", "/workspace/chats")) {
                val request = SessionCommands.move("thread-a", path, version)
                assertEquals(path, if (version >= 43) request.getString("cwd") else request.getJSONObject("params").getString("cwd"))
            }
        }
    }

    @Test fun protocol43ReadBackUsesServerCanonicalCwdWhenProjectAssignmentIsUnavailable() {
        val response = JSONObject().put("action", "thread_cwd_update")
            .put("cwd", "/workspace/real-project").put("project_assigned", false).put("workspace_roots_applied", false)

        assertEquals("/workspace/real-project", SessionCommands.movedCwd(response, "/workspace/project-link", 43))
        assertEquals("/workspace/project-link", SessionCommands.movedCwd(JSONObject(), "/workspace/project-link", 42))
    }

    @Test fun protocol43NeverTreatsMissingOrInvalidCanonicalCwdAsSuccessfulMove() {
        for (response in listOf(JSONObject(), JSONObject().put("cwd", JSONObject.NULL), JSONObject().put("cwd", "relative/path"))) {
            assertThrows(IllegalArgumentException::class.java) {
                SessionCommands.movedCwd(response, "/workspace/requested", 43)
            }
        }
    }

    @Test fun moveRejectsMembershipUrisAndRelativeOrInvalidPaths() {
        for (path in listOf("", "relative/project", "codex-bridge://chats", "file:///workspace/project", "/workspace/\u0000invalid")) {
            for (version in listOf(42, 43)) assertThrows("A server filesystem path is required", IllegalArgumentException::class.java) { SessionCommands.move("thread-a", path, version) }
        }
    }

    @Test fun moveRejectsMissingThreadIdentity() {
        assertThrows(IllegalArgumentException::class.java) { SessionCommands.move(" \t", "/workspace/project", 43) }
    }

    @Test fun renameNotificationOnlyChangesTargetTitleAndPreservesActiveConversation() {
        val original = eventState()

        val updated = StreamReducer.apply(original, notification("thread/name/updated",
            JSONObject().put("threadId", "thread-a").put("threadName", "Renamed session")))

        assertEquals("Renamed session", updated.selected?.title)
        assertEquals("Renamed session", updated.sessions.single { it.id == "thread-a" }.title)
        assertEquals(original.sessions.single { it.id == "thread-b" }, updated.sessions.single { it.id == "thread-b" })
        assertEquals(original.messages, updated.messages)
        assertEquals(original.running, updated.running)
        assertEquals(original.activeTurnId, updated.activeTurnId)
        assertEquals(original.composerModel, updated.composerModel)
        assertEquals(original.composerEffort, updated.composerEffort)
        assertEquals(original.selected?.projectPath, updated.selected?.projectPath)
    }

    @Test fun cwdOnlyNotificationMovesTargetWithoutResettingModelEffortOrHistory() {
        val original = eventState()

        val updated = StreamReducer.apply(original, notification("thread/settings/updated",
            JSONObject().put("threadId", "thread-a").put("threadSettings",
                JSONObject().put("cwd", "/workspace/target"))))

        assertEquals("/workspace/target", updated.selected?.projectPath)
        assertEquals("/workspace/target", updated.sessions.single { it.id == "thread-a" }.projectPath)
        assertEquals(original.sessions.single { it.id == "thread-b" }, updated.sessions.single { it.id == "thread-b" })
        assertEquals(original.selected?.model, updated.selected?.model)
        assertEquals(original.composerModel, updated.composerModel)
        assertEquals("high", updated.composerEffort)
        assertEquals(original.messages, updated.messages)
        assertEquals(original.running, updated.running)
        assertEquals(original.activeTurnId, updated.activeTurnId)
    }

    @Test fun backgroundSessionRenameAndMoveNeverReplaceCurrentSelection() {
        val original = eventState()
        val renamed = StreamReducer.apply(original, notification("thread/name/updated",
            JSONObject().put("threadId", "thread-b").put("threadName", "Background renamed")))
        val moved = StreamReducer.apply(renamed, notification("thread/settings/updated",
            JSONObject().put("threadId", "thread-b").put("threadSettings",
                JSONObject().put("cwd", "/workspace/background-target"))))

        assertEquals("Background renamed", moved.sessions.single { it.id == "thread-b" }.title)
        assertEquals("/workspace/background-target", moved.sessions.single { it.id == "thread-b" }.projectPath)
        assertEquals(original.selected, moved.selected)
        assertEquals(original.sessions.single { it.id == "thread-a" }, moved.sessions.single { it.id == "thread-a" })
        assertEquals(original.composerModel, moved.composerModel)
        assertEquals(original.composerEffort, moved.composerEffort)
        assertEquals(original.messages, moved.messages)
        assertEquals(original.running, moved.running)
        assertEquals(original.activeTurnId, moved.activeTurnId)
    }

    private fun eventState(): AppUiState {
        val selected = Session("thread-a", "Selected", projectPath = "/workspace/original", model = "dynamic-model")
        return AppUiState(
            loading = false, selected = selected,
            sessions = listOf(selected, Session("thread-b", "Background", projectPath = "/workspace/background", model = "other-model")),
            messages = listOf(ChatMessage("message-a", "assistant", "Preserved answer", streaming = true, turnId = "turn-a")),
            running = true, activeTurnId = "turn-a", composerModel = "dynamic-model", composerEffort = "high",
            modelOptions = listOf(ModelOption("dynamic-model", "Dynamic", defaultEffort = "medium",
                efforts = listOf(ReasoningOption("medium"), ReasoningOption("high"))))
        )
    }

    private fun notification(method: String, params: JSONObject): JSONObject = JSONObject()
        .put("type", "app_server")
        .put("message", JSONObject().put("method", method).put("params", params))
}
