package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.AppUiState
import com.y2yy.codex.mobile.model.Session
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServiceTiersTest {
    @Test
    fun catalogueOffersFastOnlyWhenServerAdvertisesPriority() {
        val models = BridgeParser.modelOptions(JSONObject("""{"models":[
            {"id":"fast-looking-name","efforts":[{"id":"high"}]},
            {"id":"supported","efforts":[{"id":"high"}],"service_tiers":[
                {"id":"priority","name":"Fast","description":"Server advertised"},
                {"id":"priority"},{"id":"flex"},{"id":"fast"},{"id":""}]}
        ]}"""))
        assertFalse(ServiceTiers.supported(models[0], "priority"))
        assertTrue(ServiceTiers.supported(models[0], "standard"))
        assertEquals(listOf("priority"), models[1].serviceTiers.map { it.id })
        assertEquals("Server advertised", models[1].serviceTiers.single().description)
        assertTrue(ServiceTiers.supported(models[1], "priority"))
        assertFalse(ServiceTiers.supported(models[1], "fast"))
    }

    @Test
    fun explicitStandardClearsPriorityWhileMissingFieldPreservesSession() {
        assertEquals("priority", ServiceTiers.fromSettings(JSONObject(), "priority"))
        assertEquals("standard", ServiceTiers.fromSettings(JSONObject().put("serviceTier", JSONObject.NULL), "priority"))
        assertEquals("standard", ServiceTiers.fromSettings(JSONObject().put("service_tier", "standard"), "priority"))
        assertEquals("priority", ServiceTiers.fromSettings(JSONObject().put("service_tier", "priority")))
    }

    @Test
    fun nativeDefaultTierConfirmsStandardWithoutAcceptingUnresolvedBridgeValues() {
        val native = JSONObject().put("serviceTier", "default")
        assertEquals("standard", ServiceTiers.fromSettings(native, "priority"))
        assertEquals("standard", ServiceTiers.confirmed(native, "standard"))
        assertEquals("unknown", ServiceTiers.fromSettings(JSONObject().put("service_tier", JSONObject.NULL)))
        assertEquals("unknown", ServiceTiers.fromSettings(JSONObject().put("service_tier", "default")))
        for (invalid in listOf(JSONObject().put("service_tier", "default"), JSONObject().put("serviceTier", "future-tier"))) {
            try {
                ServiceTiers.confirmed(invalid, "standard")
                fail("Only native default is a confirmed Standard alias")
            } catch (expected: BridgeException) {
                assertEquals("service_tier_unconfirmed", expected.code)
            }
        }
    }

    @Test
    fun unresolvedComposerStatusIsUnknownRatherThanAConfirmedStandard() {
        val unknown = JSONObject().put("service_tier", JSONObject.NULL).put("service_tier_error", "synthetic unavailable")
        assertEquals("unknown", ServiceTiers.fromSettings(unknown, "priority"))
        assertEquals("unknown", ServiceTiers.fromSettings(JSONObject().put("service_tier", JSONObject.NULL)))
        assertEquals("standard", ServiceTiers.fromSettings(JSONObject().put("serviceTier", JSONObject.NULL)))
        assertEquals(ServiceTiers.UNCONFIRMED_MESSAGE, ServiceTiers.errorFor("unknown", null))
        assertNull(ServiceTiers.errorFor("standard", ServiceTiers.UNCONFIRMED_MESSAGE))
        assertEquals("another error", ServiceTiers.errorFor("priority", "another error"))
        try {
            ServiceTiers.confirmed(unknown, "standard")
            fail("Unresolved Bridge null cannot confirm Standard")
        } catch (expected: BridgeException) {
            assertEquals("service_tier_unconfirmed", expected.code)
        }
    }

    @Test
    fun unknownCurrentSessionCannotOverwriteRememberedNewSessionSpeed() {
        val selected = Session("history", "History", model = "model")
        val state = AppUiState(selected = selected, sessions = listOf(selected), composerServiceTier = "priority",
            preferredServiceTier = "priority")
        val event = JSONObject().put("type", "app_server").put("message", JSONObject()
            .put("method", "thread/settings/updated").put("params", JSONObject().put("threadId", "history")
                .put("threadSettings", JSONObject().put("service_tier", JSONObject.NULL).put("service_tier_error", "unknown"))))
        val updated = StreamReducer.apply(state, event)
        assertEquals("unknown", updated.composerServiceTier)
        assertEquals(ServiceTiers.UNCONFIRMED_MESSAGE, updated.modelSettingsError)
        assertEquals("priority", updated.preferredServiceTier)
    }

    @Test
    fun onlyMatchingServerReadbackConfirmsAnExplicitSelection() {
        assertEquals("priority", ServiceTiers.confirmed(JSONObject().put("service_tier", "priority"), "priority"))
        assertEquals("standard", ServiceTiers.confirmed(JSONObject().put("serviceTier", JSONObject.NULL), "standard"))
        listOf(JSONObject(), JSONObject().put("service_tier", "standard"),
            JSONObject().put("service_tier", "unknown-future-tier")).forEach { result ->
            try {
                ServiceTiers.confirmed(result, "priority")
                fail("Missing, mismatched and unknown read-backs cannot remember Fast")
            } catch (expected: BridgeException) {
                assertEquals("service_tier_unconfirmed", expected.code)
            }
        }
        try {
            ServiceTiers.confirmed(JSONObject().put("service_tier", "unknown-future-tier"), "standard")
            fail("Unknown values must not be treated as a confirmed Standard selection")
        } catch (expected: BridgeException) {
            assertEquals("service_tier_unconfirmed", expected.code)
        }
    }

    @Test
    fun settingsAndCreationCarryOnlyExplicitTierAndDoNotUseNativeServiceTierField() {
        val legacy = SessionCommands.modelSettings("thread", "model", "high")
        assertFalse(legacy.has("service_tier"))
        val standard = SessionCommands.modelSettings("thread", "model", "high", "standard")
        assertEquals("standard", standard.getString("service_tier"))
        assertFalse(standard.has("serviceTier"))
        val fast = SessionCommands.create("/qa/project", "synthetic", "priority")
        assertEquals("thread_create", fast.getString("command"))
        assertEquals("priority", fast.getString("service_tier"))
        assertEquals("synthetic", fast.getString("prompt"))
        assertFalse(SessionCommands.create(null, "").has("service_tier"))
    }

    @Test
    fun historicalAndBackgroundSettingsDoNotOverwriteNewSessionPreference() {
        val selected = Session("history-a", "History", model = "model")
        val original = AppUiState(selected = selected, sessions = listOf(selected, Session("history-b", "Other")),
            composerServiceTier = "standard", preferredServiceTier = "priority")
        fun event(id: String, value: Any) = JSONObject().put("type", "app_server").put("message",
            JSONObject().put("method", "thread/settings/updated").put("params",
                JSONObject().put("threadId", id).put("threadSettings", JSONObject().put("serviceTier", value))))
        val historical = StreamReducer.apply(original, event("history-a", JSONObject.NULL))
        assertEquals("standard", historical.composerServiceTier)
        assertEquals("priority", historical.preferredServiceTier)
        val fastHistory = StreamReducer.apply(historical, event("history-a", "priority"))
        assertEquals("priority", fastHistory.composerServiceTier)
        val background = StreamReducer.apply(historical, event("history-b", "priority"))
        assertEquals("standard", background.composerServiceTier)
        assertEquals("priority", background.preferredServiceTier)
    }

    @Test
    fun invalidTierCannotEnterAPersistedPreferenceOrMutation() {
        listOf("fast", "flex", "", "PRIORITY").forEach { invalid ->
            try { ServiceTiers.requireValid(invalid); fail("Invalid stored tier") } catch (_: IllegalArgumentException) { }
            try { SessionCommands.create(null, "", invalid); fail("Invalid create tier") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("standard", ServiceTiers.normalized("corrupt-preference"))
        assertEquals("standard", ServiceTiers.normalized(null))
    }
}
