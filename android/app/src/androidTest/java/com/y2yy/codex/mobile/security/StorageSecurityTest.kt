package com.y2yy.codex.mobile.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.y2yy.codex.mobile.BuildConfig
import com.y2yy.codex.mobile.data.local.NativeDatabase
import com.y2yy.codex.mobile.data.local.NativeStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses synthetic data, an in-memory database and per-test preferences; never contacts the server. */
@RunWith(AndroidJUnit4::class)
class StorageSecurityTest {
    private lateinit var context: ScopedTestContext
    private lateinit var database: NativeDatabase
    private lateinit var preferenceFile: File
    private lateinit var preferenceJob: Job
    private lateinit var preferences: DataStore<Preferences>
    private val server = BuildConfig.SERVER_URL.toHttpUrl()

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        context = ScopedTestContext(target, UUID.randomUUID().toString())
        database = Room.inMemoryDatabaseBuilder(context, NativeDatabase::class.java).build()
        preferenceFile = File(context.cacheDir, "storage-tests-${context.namespace}.preferences_pb")
        openPreferences()
    }

    @After
    fun tearDown() = runBlocking {
        preferenceJob.cancelAndJoin()
        database.close()
        context.getSharedPreferences("native_secure_cookies", Context.MODE_PRIVATE)
            .edit().clear().commit()
        preferenceFile.delete()
        Unit
    }

    @Test
    fun vaultRoundTripsRandomizedCiphertextAndRejectsTampering() {
        val vault = EncryptedVault(context)
        val purpose = "test:${context.namespace}"
        val plaintext = "private synthetic chat: ${context.namespace} 中文"
        val first = vault.encrypt(purpose, plaintext)
        val second = vault.encrypt(purpose, plaintext)

        assertEquals(plaintext, vault.decrypt(purpose, first))
        assertFalse("Each encryption needs a fresh nonce", first.contentEquals(second))
        assertFalse(String(first, Charsets.UTF_8).contains(plaintext))
        assertNull("Ciphertext cannot move between cache entries", vault.decrypt("$purpose-other", first))

        val damaged = first.copyOf()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        assertNull(vault.decrypt(purpose, damaged))
        assertNull(vault.decrypt(purpose, byteArrayOf(1, 12, 0)))
    }

    @Test
    fun nativeCacheStoresCiphertextAndClearPreservesPreferences() = runBlocking {
        val store = NativeStore(context, database, preferences)
        val key = "test:messages:${context.namespace}"
        val json = """{"message":"private-test-${context.namespace}"}"""
        store.setLastSession("test-session-${context.namespace}")
        store.setDarkTheme(true)
        store.cache(key, json)

        assertEquals(json, store.cached(key))
        val entry = database.cache().find(key)
        assertNotNull(entry)
        assertFalse(String(entry!!.encryptedPayload, Charsets.UTF_8).contains(json))
        assertTrue(entry.updatedAt > 0)

        store.clearCache()
        assertNull(store.cached(key))
        assertTrue(withTimeout(5_000) { store.darkTheme.first() })
        assertEquals("test-session-${context.namespace}", withTimeout(5_000) { store.lastSession.first() })
    }

    @Test
    fun themeAndLastSessionSurvivePreferencesReopen() = runBlocking {
        val store = NativeStore(context, database, preferences)
        val session = "test-session-${context.namespace}"
        store.setDarkTheme(true)
        store.setLastSession(session)
        store.setPreferredServiceTier("priority")
        assertTrue(withTimeout(5_000) { store.darkTheme.first() })
        preferenceJob.cancelAndJoin()
        openPreferences()

        val reopened = NativeStore(context, database, preferences)
        assertTrue(withTimeout(5_000) { reopened.darkTheme.first() })
        assertEquals(session, withTimeout(5_000) { reopened.lastSession.first() })
        assertEquals("priority", withTimeout(5_000) { reopened.preferredServiceTier.first() })
        reopened.clearCache()
        reopened.setLastSession("historical-session-${context.namespace}")
        assertEquals("priority", withTimeout(5_000) { reopened.preferredServiceTier.first() })
        reopened.setDarkTheme(false)
        assertFalse(withTimeout(5_000) { reopened.darkTheme.first() })
    }

    @Test
    fun cookiesRejectCrossOriginSavesAndRequests() {
        val jar = SecureCookieJar(context)
        val cookie = cookie("test_cookie", "synthetic-cookie-${context.namespace}")
        val foreignHost = server.newBuilder().host("example.invalid").build()
        val foreignPort = server.newBuilder().port(if (server.port == 65_535) 65_534 else server.port + 1).build()
        val foreignScheme = server.newBuilder().scheme(if (server.isHttps) "http" else "https").build()

        listOf(foreignHost, foreignPort, foreignScheme).forEach { foreign ->
            jar.saveFromResponse(foreign, listOf(cookie))
            assertTrue(jar.loadForRequest(server).isEmpty())
        }
        jar.saveFromResponse(server, listOf(cookie))
        assertEquals(cookie.value, jar.loadForRequest(server).single().value)
        listOf(foreignHost, foreignPort, foreignScheme).forEach { foreign ->
            assertTrue(jar.loadForRequest(foreign).isEmpty())
        }
        val wrongDomain = Cookie.Builder().name("other").value("synthetic")
            .hostOnlyDomain("example.invalid").path("/").build()
        jar.saveFromResponse(server, listOf(wrongDomain))
        assertEquals(1, jar.loadForRequest(server).size)
    }

    @Test
    fun cookiesRespectPathExpiryAndPersistWithoutPlaintext() {
        val jar = SecureCookieJar(context)
        val path = "/storage-tests/${context.namespace}"
        val value = "synthetic-private-cookie-${context.namespace}"
        val sessionCookie = cookie("test_session", value, path)
        val insidePath = server.newBuilder().encodedPath("$path/progress").build()
        val outsidePath = server.newBuilder().encodedPath("/other").build()
        jar.saveFromResponse(insidePath, listOf(sessionCookie))

        assertEquals(value, jar.loadForRequest(insidePath).single().value)
        assertTrue(jar.loadForRequest(outsidePath).isEmpty())
        val reopened = SecureCookieJar(context)
        val restored = reopened.loadForRequest(insidePath).single()
        assertEquals(value, restored.value)
        assertFalse("Session-cookie semantics are retained", restored.persistent)
        assertTrue(restored.httpOnly)

        val stored = context.getSharedPreferences("native_secure_cookies", Context.MODE_PRIVATE).all
        assertTrue(stored.isNotEmpty())
        assertFalse(stored.values.any { it.toString().contains(value) })
        val expired = Cookie.Builder().name(sessionCookie.name).value("expired")
            .hostOnlyDomain(server.host).path(path).expiresAt(System.currentTimeMillis() - 1).build()
        reopened.saveFromResponse(insidePath, listOf(expired))
        assertTrue(reopened.loadForRequest(insidePath).isEmpty())
        assertTrue(SecureCookieJar(context).loadForRequest(insidePath).isEmpty())
    }

    private fun cookie(name: String, value: String, path: String = "/"): Cookie {
        val builder = Cookie.Builder().name(name).value(value).hostOnlyDomain(server.host)
            .path(path).httpOnly()
        if (server.isHttps) builder.secure()
        return builder.build()
    }

    private fun openPreferences() {
        preferenceJob = SupervisorJob()
        preferences = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + preferenceJob),
            produceFile = { preferenceFile }
        )
    }

    private class ScopedTestContext(base: Context, val namespace: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("test-storage-$namespace-$name", mode)
    }
}
