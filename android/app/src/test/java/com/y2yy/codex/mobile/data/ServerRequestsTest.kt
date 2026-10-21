package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protocol payloads only. These tests never contact an actual Bridge or save answers. */
class ServerRequestsTest {
    private fun prompt(method: String = "item/tool/requestUserInput", params: JSONObject? = null): ServerPrompt {
        val actual = params ?: JSONObject("""{"questions":[{"id":"q","header":"Choice","question":"Choose a fixture","isSecret":true,"options":[{"label":"A","description":"First fixture"}]}]}""")
        val entry = JSONObject().put("id", 19).put("thread_id", "thread-a").put("request",
            JSONObject().put("id", "opaque-native-id").put("method", method).put("params", actual))
        return ServerRequests.parse(JSONObject().put("requests", JSONArray().put(entry))).single()
    }
    private fun form(schema: JSONObject) = prompt("mcpServer/elicitation/request",
        JSONObject().put("mode", "form").put("requestedSchema", schema))

    @Test fun nativeQuestionPreservesSecretOptionsOpaqueIdentityAndUsesOnlyBridgeNumericId() {
        val p = prompt().copy(connectionGeneration = 8)
        assertEquals(ServerPromptKind.USER_INPUT, p.kind)
        assertEquals("s:opaque-native-id", p.requestIdentity)
        assertTrue(p.questions.single().isSecret)
        assertEquals("A", p.questions.single().options.single().value)
        val command = ServerRequests.command(p, ServerRequests.userInput(p, mapOf("q" to listOf("A", "Additional fixture note"))))
        assertEquals("server_request_respond", command.getString("command"))
        assertEquals(19L, command.getLong("id"))
        assertEquals("thread-a", command.getString("thread_id"))
        assertFalse(command.has("connectionGeneration"))
        assertFalse(command.has("requestIdentity"))
        assertEquals(8L, p.connectionGeneration)
    }

    @Test fun nativeAnswersMustCoverTheActualQuestionKeysAndSkipIsExplicit() {
        val p = prompt()
        assertEquals(0, ServerRequests.skip(p).getJSONObject("answers").length())
        for (answers in listOf(mapOf("other" to listOf("A")), mapOf("q" to emptyList()), mapOf("q" to listOf(" ")))) {
            assertThrows(IllegalArgumentException::class.java) { ServerRequests.userInput(p, answers) }
        }
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.validateResponse(p,
            JSONObject().put("answers", JSONObject()).put("extra", true)) }
    }

    @Test fun unicodeAndResponseSizeLimitsUseActualUtf8Bytes() {
        val p = prompt()
        ServerRequests.userInput(p, mapOf("q" to listOf("字".repeat(5333))))
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.userInput(p, mapOf("q" to listOf("字".repeat(5334)))) }
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.userInput(p, mapOf("q" to List(33) { "A" })) }
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.userInput(p, mapOf("q" to List(8) { "a".repeat(10000) })) }
    }

    @Test fun simpleFormKeepsRealTypesOptionalOmissionAndChoiceArrays() {
        val schema = JSONObject("""{"type":"object","properties":{"name":{"type":"string","minLength":1},"count":{"type":"integer","minimum":1},"enabled":{"type":"boolean"},"tags":{"type":"array","items":{"enum":["a","b"]},"uniqueItems":true},"optional":{"type":"string"}},"required":["name","count","enabled","tags"]}""")
        val p = form(schema)
        assertTrue(p.supported)
        assertFalse(p.jsonForm)
        val response = ServerRequests.formValues(p, mapOf("name" to "Fixture", "count" to "2", "enabled" to "false"), mapOf("tags" to listOf("b")))
        val content = response.getJSONObject("content")
        assertEquals(2, content.getInt("count"))
        assertEquals(false, content.getBoolean("enabled"))
        assertFalse(content.has("optional"))
        assertEquals("b", content.getJSONArray("tags").getString(0))
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.formValues(p, mapOf("name" to "Fixture", "count" to "2.5", "enabled" to "false"), mapOf("tags" to listOf("b"))) }
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.formValues(p, mapOf("name" to "Fixture", "count" to "2", "enabled" to "false"), mapOf("tags" to listOf("c"))) }
    }

    @Test fun complexSupportedSchemaUsesJsonAndEnforcesCompositionTypesAndUnknownProperties() {
        val p = form(JSONObject("""{"type":"object","properties":{"nested":{"oneOf":[{"type":"string","const":"a"},{"type":"integer","minimum":2}]},"nullable":{"type":["string","null"]}},"required":["nested"],"additionalProperties":false}"""))
        assertTrue(p.jsonForm)
        ServerRequests.form(p, JSONObject().put("nested", "a").put("nullable", JSONObject.NULL))
        ServerRequests.form(p, JSONObject().put("nested", 3))
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.form(p, JSONObject().put("nested", 1)) }
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.form(p, JSONObject().put("nested", "a").put("extra", true)) }
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.form(p, JSONObject()) }
    }

    @Test fun unsupportedSchemasCannotBeAcceptedButRemainExplicitlyCancellable() {
        for (schema in listOf(JSONObject().put("\$ref", "https://example.com/schema"), JSONObject().put("type", "string").put("format", "password"), JSONObject().put("type", "string").put("pattern", ".*"))) {
            val p = form(schema)
            assertFalse(p.supported)
            assertThrows(IllegalArgumentException::class.java) { ServerRequests.form(p, JSONObject()) }
            assertEquals("cancel", ServerRequests.cancel(p).getString("action"))
            assertEquals("decline", ServerRequests.decline(p).getString("action"))
        }
    }

    @Test fun urlVerificationIsExplicitAndCannotCarryInventedProof() {
        val p = prompt("mcpServer/elicitation/request", JSONObject().put("mode", "url").put("url", "https://example.com/verify"))
        assertTrue(p.supported)
        assertEquals("accept", ServerRequests.completeUrl(p).getString("action"))
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.validateResponse(p, JSONObject().put("action", "accept").put("content", JSONObject().put("proof", "fabricated"))) }
        for (url in listOf("javascript:alert(1)", "intent://fixture", "file:///fixture", "data:text/plain,fixture", "https://user:pass@example.com/verify")) {
            val unsafe = prompt("mcpServer/elicitation/request", JSONObject().put("mode", "url").put("url", url))
            assertFalse(unsafe.supported)
            assertNull(unsafe.url)
            assertThrows(IllegalArgumentException::class.java) { ServerRequests.completeUrl(unsafe) }
            ServerRequests.cancel(unsafe)
        }
    }

    @Test fun specialVerificationModesOnlyAllowCancelOrDeclineAndNeverContent() {
        val p = prompt("mcpServer/elicitation/request", JSONObject().put("mode", "openai/userVerification"))
        assertEquals(ServerPromptKind.UNSUPPORTED, p.kind)
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.completeUrl(p) }
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.validateResponse(p, JSONObject().put("action", "decline").put("content", JSONObject())) }
        assertEquals("cancel", ServerRequests.cancel(p).getString("action"))
        assertEquals("decline", ServerRequests.decline(p).getString("action"))
    }

    @Test fun formatValidationRejectsImpossibleDatesAndMalformedEmailOrUris() {
        for ((format, valid, invalid) in listOf(Triple("date", "2024-02-29", "2025-02-29"), Triple("date-time", "2024-02-29T12:30:01Z", "2024-02-29T99:30:01Z"), Triple("email", "fixture@example.com", "fixture@@example.com"), Triple("uri", "urn:fixture:item", "relative path"))) {
            val p = form(JSONObject().put("type", "string").put("format", format))
            ServerRequests.form(p, valid)
            assertThrows(IllegalArgumentException::class.java) { ServerRequests.form(p, invalid) }
        }
    }

    @Test fun unsupportedAndMalformedRequestIdentitiesAreNotConfusedWithLegacyApprovals() {
        val entries = JSONArray().put(JSONObject().put("id", "19").put("thread_id", "thread-a"))
            .put(JSONObject().put("id", 0).put("thread_id", "thread-a"))
            .put(JSONObject().put("id", 20).put("thread_id", "thread-a").put("request", JSONObject().put("method", "item/permissions/requestApproval")))
        val json = JSONObject().put("requests", entries)
        assertTrue(ServerRequests.parse(json).isEmpty())
    }

    private fun asyncPrompt(): AsyncQuestionPrompt = ServerRequests.parseAsync(JSONObject("""{"questions":[{"item_id":"async-item","turn_id":"turn-a","questions":[{"title":"Fixture?","options":["A","B"]}]}]}"""), "thread-a").single()

    @Test fun asyncQuestionUsesItsDistinctProtocolAndOnlySteersIntoTheSameTurn() {
        val p = asyncPrompt()
        assertEquals("Fixture?", p.questions.single().title)
        assertEquals(listOf("A", "B"), p.questions.single().options)
        val command = ServerRequests.replyAsync(p, listOf("A"), "turn-a")
        assertEquals("async_question_reply", command.getString("command"))
        assertEquals("steer", command.getString("mode"))
        assertEquals("async-item", command.getString("item_id"))
        assertFalse(command.has("id"))
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.replyAsync(p, listOf("A"), "another-turn", true) }
    }

    @Test fun finishedAsyncQuestionRequiresExplicitNewTurnAndNeverSilentlyFallsBack() {
        val p = asyncPrompt()
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.replyAsync(p, listOf("A"), null) }
        assertEquals("new_turn", ServerRequests.replyAsync(p, listOf("Custom fixture answer"), null, true).getString("mode"))
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.replyAsync(p, emptyList(), null, true) }
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.replyAsync(p, listOf(" "), null, true) }
        assertThrows(IllegalArgumentException::class.java) { ServerRequests.replyAsync(p, listOf("字".repeat(5334)), null, true) }
    }

    @Test fun invalidAsyncQuestionIdentityNeverProducesAnEmptyOrGuessedReply() {
        assertTrue(ServerRequests.parseAsync(JSONObject("""{"questions":[{"item_id":"","turn_id":"turn-a","questions":[{}]}]}"""), "thread-a").isEmpty())
        assertTrue(ServerRequests.parseAsync(JSONObject("""{"questions":[{"item_id":"item","turn_id":"turn-a","questions":[]}]}"""), "thread-a").isEmpty())
        assertTrue(ServerRequests.parseAsync(JSONObject(), "").isEmpty())
    }
}
