package com.y2yy.codex.mobile.security

import android.content.Context
import android.util.Base64
import com.y2yy.codex.mobile.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** A private cookie store that never attaches the bridge session to a different origin. */
class SecureCookieJar(context: Context) : CookieJar {
    private val server = BuildConfig.SERVER_URL.toHttpUrl()
    private val purpose = "$PURPOSE:${server.scheme}://${server.host}:${server.port}"
    private val preferences = context.applicationContext
        .getSharedPreferences("native_secure_cookies", Context.MODE_PRIVATE)
    private val vault = EncryptedVault(context)
    private val cookies = linkedMapOf<String, Cookie>()
    private var loaded = false
    private val lock = Any()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!sameOrigin(url)) return
        synchronized(lock) {
            load()
            val now = System.currentTimeMillis()
            cookies.filter { it.domain == server.host }.forEach { cookie ->
                if (cookie.expiresAt <= now) this.cookies.remove(key(cookie))
                else this.cookies[key(cookie)] = cookie
            }
            removeExpired(now)
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!sameOrigin(url)) return emptyList()
        return synchronized(lock) {
            load()
            if (removeExpired(System.currentTimeMillis())) persist()
            cookies.values.filter { it.matches(url) }
        }
    }

    /** Used on explicit reset; clearing message cache does not sign the user out. */
    fun clear() = synchronized(lock) {
        cookies.clear()
        loaded = true
        preferences.edit().remove(PAYLOAD).apply()
    }

    private fun sameOrigin(url: HttpUrl) =
        url.scheme == server.scheme && url.host == server.host && url.port == server.port

    private fun key(cookie: Cookie) = "${cookie.name}\u0000${cookie.domain}\u0000${cookie.path}"

    private fun removeExpired(now: Long): Boolean {
        val before = cookies.size
        cookies.entries.removeAll { it.value.expiresAt <= now }
        return cookies.size != before
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val encoded = preferences.getString(PAYLOAD, null) ?: return
        val json = runCatching {
            vault.decrypt(purpose, Base64.decode(encoded, Base64.NO_WRAP))
        }.getOrNull()
        if (json == null) {
            preferences.edit().remove(PAYLOAD).apply()
            return
        }
        runCatching {
            val values = JSONArray(json)
            val now = System.currentTimeMillis()
            for (i in 0 until values.length()) {
                val item = values.getJSONObject(i)
                if (item.getString("domain") != server.host) continue
                if (item.getLong("expiresAt") <= now) continue
                val builder = Cookie.Builder()
                    .name(item.getString("name"))
                    .value(item.getString("value"))
                    .path(item.getString("path"))
                if (item.optBoolean("hostOnly")) builder.hostOnlyDomain(server.host)
                else builder.domain(server.host)
                if (item.optBoolean("persistent")) builder.expiresAt(item.getLong("expiresAt"))
                if (item.optBoolean("secure")) builder.secure()
                if (item.optBoolean("httpOnly")) builder.httpOnly()
                val cookie = builder.build()
                cookies[key(cookie)] = cookie
            }
        }.onFailure {
            cookies.clear()
            preferences.edit().remove(PAYLOAD).apply()
        }
    }

    private fun persist() {
        if (cookies.isEmpty()) {
            preferences.edit().remove(PAYLOAD).apply()
            return
        }
        val values = JSONArray()
        cookies.values.forEach { cookie ->
            values.put(
                JSONObject()
                    .put("name", cookie.name)
                    .put("value", cookie.value)
                    .put("domain", cookie.domain)
                    .put("path", cookie.path)
                    .put("expiresAt", cookie.expiresAt)
                    .put("persistent", cookie.persistent)
                    .put("hostOnly", cookie.hostOnly)
                    .put("secure", cookie.secure)
                    .put("httpOnly", cookie.httpOnly)
            )
        }
        // No plaintext cookie is passed to SharedPreferences or the log system.
        val encoded = Base64.encodeToString(vault.encrypt(purpose, values.toString()), Base64.NO_WRAP)
        preferences.edit().putString(PAYLOAD, encoded).apply()
    }

    companion object {
        private const val PAYLOAD = "encrypted_payload_v1"
        private const val PURPOSE = "bridge-cookies:v1"
    }
}
