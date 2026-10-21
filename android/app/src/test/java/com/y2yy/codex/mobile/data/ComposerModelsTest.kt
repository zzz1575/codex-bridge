package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.ChatMessage
import com.y2yy.codex.mobile.model.ModelOption
import com.y2yy.codex.mobile.model.ReasoningOption
import com.y2yy.codex.mobile.model.Session
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic dynamic model catalogue and app-server settings notifications. */
class ComposerModelsTest {
    @Test
    fun modelCatalogueUsesServerProvidedIdsAndOnlySupportedReasoningOptions() {
        val parsed = BridgeParser.modelOptions(JSONObject("""
            {"models":[
              {"id":"future-model","name":"Future Model","description":"Dynamic catalogue entry",
               "default_effort":"ultra","efforts":[
                 {"id":"high","description":"More analysis"},{"id":"ultra","description":"Maximum analysis"}]},
              {"id":"light-model","name":"Light Model","default_effort":"medium",
               "efforts":[{"id":"low"},{"id":"medium"},{"id":"high"}]}
            ]}
        """.trimIndent()))

        assertEquals(listOf("future-model", "light-model"), parsed.map { it.id })
        assertEquals("Dynamic catalogue entry", parsed[0].description)
        assertEquals("ultra", parsed[0].defaultEffort)
        assertEquals(listOf("high", "ultra"), parsed[0].efforts.map { it.id })
        assertEquals("Maximum analysis", parsed[0].efforts[1].description)
        assertEquals(listOf("low", "medium", "high"), parsed[1].efforts.map { it.id })
        assertFalse("Unavailable ultra must not be invented for a lighter model", parsed[1].efforts.any { it.id == "ultra" })
    }

    @Test
    fun invalidDefaultsFallBackToFirstSupportedEffortAndNamesFallBackToId() {
        val parsed = BridgeParser.modelOptions(JSONObject("""
            {"models":[{"id":"dynamic-model","name":"","default_effort":"unsupported",
             "efforts":[{"id":"high","description":"First supported"},
                        {"id":"high","description":"Duplicate"},{"id":"max"}]}]}
        """.trimIndent())).single()

        assertEquals("dynamic-model", parsed.name)
        assertEquals("high", parsed.defaultEffort)
        assertEquals(listOf("high", "max"), parsed.efforts.map { it.id })
    }

    @Test
    fun modelsWithoutIdentityOrAnyValidEffortAreIgnored() {
        val parsed = BridgeParser.modelOptions(JSONObject("""
            {"models":[
              {"id":"","efforts":[{"id":"high"}]},
              {"id":null,"efforts":[{"id":"high"}]},
              {"id":"no-efforts","efforts":[]},
              {"id":"invalid-efforts","efforts":[{"id":""},{"id":null},{}]},
              {"id":"valid","efforts":[{"id":""},{"id":"low"},{"id":"low"}]}
            ]}
        """.trimIndent()))

        assertEquals(listOf("valid"), parsed.map { it.id })
        assertEquals(listOf("low"), parsed.single().efforts.map { it.id })
        assertEquals("low", parsed.single().defaultEffort)
    }

    @Test
    fun missingOrNullCatalogueDoesNotCreateHardcodedModels() {
        assertTrue(BridgeParser.modelOptions(JSONObject()).isEmpty())
        assertTrue(BridgeParser.modelOptions(JSONObject("""{"models":null}""")).isEmpty())
        assertTrue(BridgeParser.modelOptions(JSONObject("""{"models":[]}""")).isEmpty())
    }

    @Test
    fun selectedThreadSettingsUpdateComposerAndSessionWithoutStoppingItsTurn() {
        val original = state().copy(
            running = true, activeTurnId = "turn-a", activity = "正在执行",
            messages = listOf(ChatMessage("message-a", "assistant", "Live answer", streaming = true))
        )

        val updated = StreamReducer.apply(original, settings("thread-a", "future-model", "ultra"))

        assertEquals("future-model", updated.composerModel)
        assertEquals("ultra", updated.composerEffort)
        assertEquals("future-model", updated.selected?.model)
        assertEquals("future-model", updated.sessions.single { it.id == "thread-a" }.model)
        assertEquals("previous-model", updated.sessions.single { it.id == "thread-b" }.model)
        assertEquals(original.messages, updated.messages)
        assertEquals(original.activity, updated.activity)
        assertTrue(updated.running)
        assertEquals("turn-a", updated.activeTurnId)
    }

    @Test
    fun backgroundThreadSettingsUpdateOnlyThatSessionAndCannotPolluteCurrentComposer() {
        val original = state()

        val updated = StreamReducer.apply(original, settings("thread-b", "future-model", "ultra"))

        assertEquals("future-model", updated.sessions.single { it.id == "thread-b" }.model)
        assertEquals(original.selected, updated.selected)
        assertEquals(original.composerModel, updated.composerModel)
        assertEquals(original.composerEffort, updated.composerEffort)
        assertEquals(original.sessions.single { it.id == "thread-a" }, updated.sessions.single { it.id == "thread-a" })
    }

    @Test
    fun nullEffortFallsBackToTheNewModelsDefaultRatherThanPreviousModelEffort() {
        val original = state().copy(composerEffort = "ultra")

        val updated = StreamReducer.apply(original, settings("thread-a", "light-model", null))

        assertEquals("light-model", updated.composerModel)
        assertEquals("medium", updated.composerEffort)
        assertEquals("light-model", updated.selected?.model)
    }

    @Test
    fun settingsForAnUnknownThreadCannotMutateCurrentSelectionOrCatalogue() {
        val original = state()

        val updated = StreamReducer.apply(original, settings("not-listed", "future-model", "high"))

        assertEquals(original, updated)
    }

    private fun state(): AppUiState {
        val selected = Session("thread-a", "Selected", model = "previous-model")
        return AppUiState(
            loading = false, selected = selected,
            sessions = listOf(selected, Session("thread-b", "Background", model = "previous-model")),
            composerModel = "previous-model", composerEffort = "high",
            modelOptions = listOf(
                ModelOption("future-model", "Future", defaultEffort = "high", efforts = listOf(ReasoningOption("high"), ReasoningOption("ultra"))),
                ModelOption("light-model", "Light", defaultEffort = "medium", efforts = listOf(ReasoningOption("low"), ReasoningOption("medium"), ReasoningOption("high")))
            )
        )
    }

    private fun settings(threadId: String, model: String, effort: String?): JSONObject = JSONObject()
        .put("type", "app_server")
        .put("message", JSONObject().put("method", "thread/settings/updated").put("params",
            JSONObject().put("threadId", threadId).put("threadSettings",
                JSONObject().put("model", model).put("effort", effort ?: JSONObject.NULL))))
}
