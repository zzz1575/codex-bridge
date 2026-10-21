package com.y2yy.codex.mobile.data

import android.content.Context
import com.y2yy.codex.mobile.BuildConfig
import com.y2yy.codex.mobile.security.SecureCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BridgeException(val code: String, message: String): Exception(message)

class BridgeApi(context: Context) {
    private val origin = BuildConfig.SERVER_URL.toHttpUrl()
    private val authorization = Credentials.basic(BuildConfig.SERVER_USER, BuildConfig.SERVER_PASSWORD)
    val client = OkHttpClient.Builder().cookieJar(SecureCookieJar(context))
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).writeTimeout(40, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS).followRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val sameOrigin = request.url.scheme == origin.scheme && request.url.host == origin.host && request.url.port == origin.port
            val builder = request.newBuilder()
            if (sameOrigin) builder.header("Authorization", authorization).header("Origin", origin.toString().trimEnd('/'))
            else builder.removeHeader("Authorization").removeHeader("Cookie")
            chain.proceed(builder.build())
        }.build()

    suspend fun authenticate() = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(origin.resolve("api/auth")!!).build()).execute().use { r ->
            if (!r.isSuccessful) throw BridgeException("authentication", "服务器认证失败（${r.code}）")
        }
    }
    suspend fun command(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(origin.resolve("api/command")!!)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BridgeException("http_${response.code}", "服务器请求失败（${response.code}）")
            val result = JSONObject(response.body?.string().orEmpty())
            if (!result.optBoolean("ok")) {
                val error = result.optJSONObject("error") ?: JSONObject()
                throw BridgeException(error.string("code", "request_failed"), error.string("message", "请求未完成"))
            }
            result.optJSONObject("result") ?: JSONObject()
        }
    }
    suspend fun preview(body: JSONObject): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(origin.resolve("api/file-preview")!!)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.string("kind") != "image") return@withContext null
            origin.resolve(json.string("preview_url"))?.toString()
        }
    }
    fun events(): Flow<JSONObject> = callbackFlow {
        val request = Request.Builder().url(origin.resolve("api/events")!!).build()
        val socket = client.newWebSocket(request, object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { trySend(JSONObject().put("type", "native_connected")) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.onSuccess {
                    if (trySend(it).isFailure) close(BridgeException("stream_gap", "实时事件积压，重新同步会话"))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
        awaitClose { socket.cancel() }
    }
}
