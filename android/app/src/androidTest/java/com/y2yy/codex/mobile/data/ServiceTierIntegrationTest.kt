package com.y2yy.codex.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in QA only. Uses the shipped URL/authentication/TLS and never starts a model turn. */
@RunWith(AndroidJUnit4::class)
class ServiceTierIntegrationTest {
    private lateinit var api: BridgeApi
    private var qaVerified = false
    private val ownedThreads = mutableSetOf<String>()
    private val initialThreadIds = mutableSetOf<String>()

    @Before
    fun requireExplicitFastModeQaHomeBeforeAnyMutation() = runBlocking {
        val requestedRoot = InstrumentationRegistry.getArguments().getString("qaRoot")
        assumeTrue("Real Fast checks require an explicit isolated qaRoot", requestedRoot != null)
        assertEquals("Only the designated Fast QA home is permitted", QA_HOME, requestedRoot)
        api = BridgeApi(InstrumentationRegistry.getInstrumentation().targetContext)
        api.authenticate()
        assertQaBoundary()
        initialThreadIds += api.command(request("ls").put("limit", 1000).put("include_archived", true))
            .optJSONArray("threads")?.objects().orEmpty().map { it.string("id") }
        qaVerified = true
    }

    @After
    fun archiveOnlyThisTestsNewEmptyQaThreads() = runBlocking {
        if (!qaVerified) return@runBlocking
        var archiveFailures = 0
        ownedThreads.forEach { id ->
            try {
                ownedCommand(request("thread_archive").put("thread_id", id), id)
            } catch (_: Exception) {
                archiveFailures++
            }
        }
        assertEquals("All and only this test's created QA threads must be archived", 0, archiveFailures)
    }

    @Test(timeout = 90_000)
    fun realCatalogueCreationAndPriorityStandardPriorityReadbackPreserveModelAndEffort() = runBlocking {
        withTimeout(75_000) {
            assertQaBoundary()
            val models = BridgeParser.modelOptions(api.command(request("composer_options")))
            assertTrue("The real catalogue must advertise at least one priority-capable model",
                models.any { model -> model.serviceTiers.any { it.id == ServiceTiers.PRIORITY } })
            val model = models.first { candidate ->
                candidate.serviceTiers.any { it.id == ServiceTiers.PRIORITY } && candidate.efforts.isNotEmpty()
            }
            val create = SessionCommands.create(null, "", ServiceTiers.PRIORITY).put("model", model.id)
            assertFalse("The synthetic empty session must have no prompt", create.has("prompt"))
            assertQaBoundary()
            val created = api.command(create).getJSONObject("thread")
            val id = created.string("id")
            assertEquals("A complete native thread ID is required", id.lowercase(), UUID.fromString(id).toString())
            assertFalse("Existing QA history must never be writable by this test", id in initialThreadIds)
            assertTrue("Each created thread is owned exactly once", ownedThreads.add(id))
            val createdCwd = created.string("cwd")
            assertTrue("Creation must stay within the isolated projectless workspace",
                createdCwd.startsWith("$QA_CHATS/") && ".." !in createdCwd.split('/'))

            val watched = ownedCommand(request("thread_watch").put("thread_id", id), id)
            if (watched.optJSONObject("writer_lock")?.optBoolean("read_only", true) != false) {
                // This is the protocol's actual acquire command, confined to this newly created ID.
                val acquired = ownedCommand(request("thread_writer_acquire").put("thread_id", id), id)
                assertEquals("The isolated Bridge must own this empty thread", false,
                    acquired.getJSONObject("writer_lock").getBoolean("read_only"))
            }

            val initial = awaitComposer(id, model.id, null, ServiceTiers.PRIORITY)
            val effort = initial.string("reasoning_effort").takeIf { value -> model.efforts.any { it.id == value } }
                ?: model.defaultEffort
            for (tier in listOf(ServiceTiers.PRIORITY, ServiceTiers.STANDARD, ServiceTiers.PRIORITY)) {
                val saved = ownedCommand(SessionCommands.modelSettings(id, model.id, effort, tier), id)
                assertEquals("Every settings mutation must return a confirmed tier", tier, ServiceTiers.confirmed(saved, tier))
                val composer = awaitComposer(id, model.id, effort, tier)
                assertEquals(model.id, composer.string("model"))
                assertEquals(effort, composer.string("reasoning_effort"))
                assertFalse("A confirmed speed must not carry an unresolved-tier error",
                    composer.has("service_tier_error") && !composer.isNull("service_tier_error"))
            }
            val native = api.command(request("app_server_rpc").put("method", "thread/read")
                .put("params", JSONObject().put("threadId", id).put("includeTurns", false)))
                .getJSONObject("result").getJSONObject("thread")
            assertEquals(id, native.string("id"))
            assertEquals(createdCwd, native.string("cwd"))
            assertEquals("Settings must never start model work", "idle", native.getJSONObject("status").string("type"))
        }
    }

    private suspend fun assertQaBoundary() {
        val status = api.command(request("status"))
        assertEquals("Production or unrelated runtimes must never be mutated", QA_HOME,
            status.getJSONObject("rollout_store").string("codex_home"))
        assertTrue("Fast requires protocol 44 or newer", status.optInt("protocol_version") >= 44)
        assertEquals("Projectless writes must stay in QA", QA_CHATS,
            status.getJSONObject("configuration").string("projectless_workspace_root"))
    }

    private suspend fun ownedCommand(body: JSONObject, id: String): JSONObject {
        check(qaVerified && id in ownedThreads && id !in initialThreadIds) { "A test-created QA thread is required" }
        assertEquals(id, body.string("thread_id"))
        assertQaBoundary()
        return api.command(body)
    }

    private suspend fun awaitComposer(id: String, model: String, effort: String?, tier: String): JSONObject {
        check(id in ownedThreads)
        return withTimeout(10_000) {
            while (true) {
                val value = api.command(request("composer_status").put("thread_id", id))
                if (value.string("model") == model && value.string("service_tier") == tier &&
                    (effort == null || value.string("reasoning_effort") == effort)) return@withTimeout value
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE")
            error("Composer readback was not confirmed")
        }
    }

    private fun request(command: String) = JSONObject().put("command", command)

    companion object {
        private const val QA_BASE = "/tmp/codex-bridge-android-qa"
        private const val QA_HOME = "$QA_BASE/codex-home"
        private const val QA_CHATS = "$QA_BASE/chats"
    }
}
