package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.*
import java.net.URI
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** Bridge 43 transient prompt protocol. No storage, logging, network access or automatic answers. */
object ServerRequests {
    private const val USER_INPUT = "item/tool/requestUserInput"
    private const val ELICITATION = "mcpServer/elicitation/request"
    private val formModes = setOf("form", "openai/form", "openaiForm")
    private val schemaKeys = setOf("\$schema", "type", "title", "description", "default", "examples", "properties", "required", "additionalProperties", "enum", "enumNames", "const", "oneOf", "anyOf", "allOf", "items", "minItems", "maxItems", "uniqueItems", "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "format")
    private val types = setOf("object", "array", "string", "number", "integer", "boolean", "null")

    fun parse(json: JSONObject): List<ServerPrompt> = json.optJSONArray("requests")?.objects()?.mapNotNull { entry ->
        val id = (entry.opt("id") as? Number)?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val thread = (entry.opt("thread_id") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val request = entry.optJSONObject("request") ?: return@mapNotNull null
        if (bytes(request.toString()) > 64 * 1024) return@mapNotNull null
        val method = request.string("method")
        if (method !in setOf(USER_INPUT, ELICITATION)) return@mapNotNull null
        val p = request.optJSONObject("params") ?: JSONObject()
        val identity = request.opt("id")?.let { if (it is String) "s:$it" else "j:$it" }.orEmpty()
        val mode = p.string("mode")
        val schema = p.optJSONObject("requestedSchema")
        val validSchema = schema != null && supportedSchema(schema)
        val questions = p.optJSONArray("questions")?.objects()?.map { q ->
            ServerQuestion(q.string("id"), q.string("header"), q.string("question"), q.optBoolean("isSecret"),
                q.optJSONArray("options")?.objects()?.map { o -> ServerOption(o.string("label"), o.string("label"), o.string("description")) }.orEmpty())
        }.orEmpty()
        val validQuestions = questions.isNotEmpty() && questions.size <= 128 && questions.all { it.id.isNotBlank() } && questions.map { it.id }.distinct().size == questions.size && questions.size == p.optJSONArray("questions")?.length()
        val url = safeUrl(p.string("url"))
        val kind = when { method == USER_INPUT -> ServerPromptKind.USER_INPUT; mode in formModes -> ServerPromptKind.FORM; mode == "url" -> ServerPromptKind.URL; else -> ServerPromptKind.UNSUPPORTED }
        val supported = when (kind) { ServerPromptKind.USER_INPUT -> validQuestions; ServerPromptKind.FORM -> validSchema; ServerPromptKind.URL -> url != null; else -> false }
        val fields = if (validSchema) formFields(schema!!) else null
        ServerPrompt(id, thread, method, kind = kind, requestIdentity = identity, title = p.string("title"),
            message = p.string("message", p.string("description")), serverName = p.string("serverName"),
            questions = questions, fields = fields.orEmpty(), schemaJson = schema?.toString().orEmpty(), mode = mode,
            url = url, supported = supported, jsonForm = kind == ServerPromptKind.FORM && supported && fields == null)
    }.orEmpty().distinctBy { it.id }

    fun userInput(prompt: ServerPrompt, answers: Map<String, List<String>>): JSONObject {
        val objectAnswers = JSONObject()
        answers.forEach { (id, values) -> objectAnswers.put(id, JSONObject().put("answers", JSONArray(values))) }
        return validateResponse(prompt, JSONObject().put("answers", objectAnswers))
    }
    fun skip(prompt: ServerPrompt): JSONObject = userInput(prompt, emptyMap())

    fun parseAsync(json: JSONObject, threadId: String): List<AsyncQuestionPrompt> {
        if (threadId.isBlank()) return emptyList()
        return json.optJSONArray("questions")?.objects()?.mapNotNull { raw ->
            val item = raw.string("item_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val turn = raw.string("turn_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val items = raw.optJSONArray("questions") ?: return@mapNotNull null
            if (items.length() !in 1..128 || items.objects().size != items.length()) return@mapNotNull null
            val questions = items.objects().map { q ->
                AsyncQuestionItem(q.string("title"), q.optJSONArray("options")?.let(::strings).orEmpty())
            }
            AsyncQuestionPrompt(threadId, item, turn, questions)
        }.orEmpty().distinctBy { it.itemId to it.turnId }
    }

    fun replyAsync(prompt: AsyncQuestionPrompt, answers: List<String>, activeTurnId: String?, allowNewTurn: Boolean = false): JSONObject {
        require(prompt.threadId.isNotBlank() && prompt.itemId.isNotBlank() && prompt.turnId.isNotBlank()) { "问题身份无效，请刷新" }
        require(answers.size == prompt.questions.size && answers.isNotEmpty() && answers.all { it.isNotBlank() && bytes(it) <= 16000 }) { "请回答每个问题，且每项不超过 16000 字节" }
        val active = activeTurnId?.takeIf { it.isNotBlank() }
        require(active == null || active == prompt.turnId) { "另一个任务正在执行，请等待结束后再回答" }
        require(active != null || allowNewTurn) { "原任务已结束，请明确选择作为新任务回答" }
        return JSONObject().put("command", "async_question_reply").put("thread_id", prompt.threadId)
            .put("item_id", prompt.itemId).put("turn_id", prompt.turnId).put("answers", JSONArray(answers))
            .put("mode", if (active == prompt.turnId) "steer" else "new_turn")
    }
    fun form(prompt: ServerPrompt, content: Any?): JSONObject = validateResponse(prompt, JSONObject().put("action", "accept").put("content", content ?: JSONObject.NULL))
    fun completeUrl(prompt: ServerPrompt): JSONObject = validateResponse(prompt, JSONObject().put("action", "accept"))
    fun decline(prompt: ServerPrompt): JSONObject = validateResponse(prompt, JSONObject().put("action", "decline"))
    fun cancel(prompt: ServerPrompt): JSONObject = validateResponse(prompt, JSONObject().put("action", "cancel"))

    fun formValues(prompt: ServerPrompt, values: Map<String, String>, arrays: Map<String, List<String>> = emptyMap()): JSONObject {
        require(prompt.kind == ServerPromptKind.FORM && !prompt.jsonForm) { "请使用 JSON 表单填写此请求" }
        val names = prompt.fields.map { it.name }.toSet()
        require((values.keys + arrays.keys).all { it in names }) { "表单包含未请求的字段" }
        val content = JSONObject()
        for (field in prompt.fields) {
            val text = values[field.name]
            val selected = arrays[field.name]
            if (field.type == "array") {
                if (selected != null && (selected.isNotEmpty() || field.required)) content.put(field.name, JSONArray(selected))
            } else if (text != null && (text.isNotEmpty() || field.required)) {
                val value: Any = when (field.type) {
                    "boolean" -> { require(text == "true" || text == "false") { "请选择是或否" }; text == "true" }
                    "integer", "number" -> { val number = text.toBigDecimalOrNull(); require(number != null && number.toDouble().isFinite()) { "请输入有效数字" }; number.stripTrailingZeros() }
                    else -> text
                }
                content.put(field.name, value)
            }
        }
        return form(prompt, content)
    }

    fun command(prompt: ServerPrompt, response: JSONObject): JSONObject {
        require(prompt.id > 0 && prompt.threadId.isNotBlank()) { "请求身份无效，请刷新" }
        return JSONObject().put("command", "server_request_respond").put("id", prompt.id)
            .put("thread_id", prompt.threadId).put("response", validateResponse(prompt, response))
    }

    fun validateResponse(prompt: ServerPrompt, response: JSONObject): JSONObject {
        require(bytes(response.toString()) <= 64 * 1024) { "回答内容过长" }
        if (prompt.method == USER_INPUT) {
            require(response.keys().asSequence().toSet() == setOf("answers")) { "回答格式不正确" }
            val answers = response.optJSONObject("answers") ?: throw IllegalArgumentException("回答格式不正确")
            if (answers.length() == 0) return JSONObject().put("answers", JSONObject())
            require(prompt.supported && answers.keys().asSequence().toSet() == prompt.questions.map { it.id }.toSet()) { "请回答每个问题，或选择不回答" }
            for (q in prompt.questions) {
                val answer = answers.optJSONObject(q.id) ?: throw IllegalArgumentException("问题回答缺失")
                require(answer.keys().asSequence().toSet() == setOf("answers")) { "问题回答格式不正确" }
                val values = answer.optJSONArray("answers") ?: throw IllegalArgumentException("问题回答须为文字列表")
                require(values.length() in 1..32 && (0 until values.length()).all { (values.opt(it) as? String)?.let { text -> text.isNotBlank() && bytes(text) <= 16000 } == true }) { "请填写回答，且每项不超过 16000 字节" }
            }
        } else {
            require(prompt.method == ELICITATION && response.keys().asSequence().all { it in setOf("action", "content") }) { "请求类型或回答格式不支持" }
            when (response.string("action")) {
                "cancel", "decline" -> { require(!response.has("content") || response.isNull("content")) { "拒绝时不能发送表单内容" }; return JSONObject().put("action", response.string("action")) }
                "accept" -> when {
                    prompt.kind == ServerPromptKind.URL -> require(prompt.supported && safeUrl(prompt.url.orEmpty()) != null && (!response.has("content") || response.isNull("content"))) { "此验证请求不能附加内容或伪造验证凭据" }
                    prompt.kind == ServerPromptKind.FORM -> {
                        require(prompt.supported && response.has("content")) { "此表单不支持，请取消或使用兼容客户端" }
                        val schema = JSONObject(prompt.schemaJson)
                        require(supportedSchema(schema) && matchesSchema(schema, response.opt("content"), 0)) { "表单内容不符合请求的类型、必填项或取值限制" }
                    }
                    else -> throw IllegalArgumentException("此验证模式不支持接受，可取消或拒绝")
                }
                else -> throw IllegalArgumentException("请明确选择接受、拒绝或取消")
            }
        }
        return JSONObject(response.toString())
    }

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8).size
    private fun safeUrl(value: String): String? = runCatching { URI(value).takeIf { (value.startsWith("https://") || value.startsWith("http://")) && !it.host.isNullOrBlank() && it.rawUserInfo == null && !value.any(Char::isWhitespace) }?.let { value } }.getOrNull()
    private fun items(array: JSONArray) = (0 until array.length()).map { array.opt(it) }
    private fun strings(array: JSONArray) = items(array).mapNotNull { it as? String }

    private fun supportedSchema(s: JSONObject, depth: Int = 0): Boolean {
        if (depth > 8 || s.keys().asSequence().any { it !in schemaKeys }) return false
        if (s.has("type")) { val t = s.opt("type"); if (t !is String || t !in types) { if (t !is JSONArray || t.length() !in 1..7 || items(t).any { it !is String || it !in types }) return false } }
        if (s.has("properties")) { val p = s.optJSONObject("properties") ?: return false; if (p.length() > 128 || p.keys().asSequence().any { p.optJSONObject(it)?.let { child -> supportedSchema(child, depth + 1) } != true }) return false }
        if (s.has("required")) { val a = s.optJSONArray("required") ?: return false; if (a.length() > 128 || items(a).any { it !is String }) return false }
        if (s.has("additionalProperties") && s.opt("additionalProperties") !is Boolean) return false
        for (key in listOf("oneOf", "anyOf", "allOf")) if (s.has(key)) { val a = s.optJSONArray(key) ?: return false; if (a.length() !in 1..128 || items(a).any { it !is JSONObject || !supportedSchema(it, depth + 1) }) return false }
        if (s.has("items") && (s.optJSONObject("items")?.let { supportedSchema(it, depth + 1) } != true)) return false
        if (s.has("enum") && ((s.optJSONArray("enum")?.length() ?: 0) !in 1..128)) return false
        if (s.has("format") && s.opt("format") !in setOf("email", "uri", "date", "date-time")) return false
        for (key in listOf("minItems", "maxItems", "minLength", "maxLength")) if (s.has(key) && (s.opt(key) !is Number || s.optDouble(key).let { !it.isFinite() || it < 0 || it % 1.0 != 0.0 })) return false
        for (key in listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) if (s.has(key) && (s.opt(key) !is Number || !s.optDouble(key).isFinite())) return false
        return !s.has("uniqueItems") || s.opt("uniqueItems") is Boolean
    }

    private fun choices(s: JSONObject): List<ServerOption>? {
        s.optJSONArray("enum")?.let { a -> if (items(a).all { it is String }) return strings(a).mapIndexed { i, v -> ServerOption(v, s.optJSONArray("enumNames")?.optString(i)?.takeIf { it.isNotBlank() } ?: v) } }
        val variants = s.optJSONArray("oneOf") ?: s.optJSONArray("anyOf") ?: return null
        if (items(variants).all { it is JSONObject && it.opt("const") is String }) return variants.objects().map { ServerOption(it.getString("const"), it.string("title", it.getString("const"))) }
        return null
    }
    private fun formFields(s: JSONObject): List<ServerFormField>? {
        if (s.opt("type") != "object") return null
        val p = s.optJSONObject("properties") ?: return null
        val required = s.optJSONArray("required")?.let(::strings).orEmpty()
        val fields = mutableListOf<ServerFormField>()
        for (name in p.keys().asSequence()) {
            val field = p.getJSONObject(name)
            val options = choices(field)
            val type = field.string("type").ifBlank { if (options != null) "string" else "" }
            val arrayChoices = if (type == "array") field.optJSONObject("items")?.let(::choices) else null
            if (options == null && type !in setOf("string", "number", "integer", "boolean") && arrayChoices == null) return null
            fields += ServerFormField(name, field.string("title", name), field.string("description"), type, name in required, options ?: arrayChoices.orEmpty())
        }
        return fields
    }

    private fun equal(a: Any?, b: Any?): Boolean = when {
        a == null || a === JSONObject.NULL -> b == null || b === JSONObject.NULL
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { equal(a.opt(it), b.opt(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { equal(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> a.toString() == b.toString()
        else -> a == b
    }
    private fun matchesSchema(s: JSONObject, value: Any?, depth: Int): Boolean {
        if (depth > 8) return false
        fun type(name: String): Boolean = when (name) { "object" -> value is JSONObject; "array" -> value is JSONArray; "string" -> value is String; "boolean" -> value is Boolean; "null" -> value == null || value === JSONObject.NULL; "number" -> value is Number && value.toDouble().isFinite(); "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0; else -> false }
        val typeMatches = when (val requested = s.opt("type")) { is String -> type(requested); is JSONArray -> strings(requested).any(::type); else -> !s.has("type") }
        if (!typeMatches) return false
        if (s.has("const") && !equal(s.opt("const"), value)) return false
        if (s.optJSONArray("enum")?.let { items(it).none { item -> equal(item, value) } } == true) return false
        for (key in listOf("oneOf", "anyOf", "allOf")) s.optJSONArray(key)?.let { a -> val matched = a.objects().count { matchesSchema(it, value, depth + 1) }; if ((key == "oneOf" && matched != 1) || (key == "anyOf" && matched == 0) || (key == "allOf" && matched != a.length())) return false }
        if (value is JSONObject) {
            val props = s.optJSONObject("properties")
            if (s.optJSONArray("required")?.let { strings(it).any { name -> !value.has(name) } } == true) return false
            if ((s.opt("additionalProperties") == false || (props != null && s.opt("additionalProperties") != true)) && value.keys().asSequence().any { props?.has(it) != true }) return false
            if (props != null && value.keys().asSequence().any { props.has(it) && !matchesSchema(props.getJSONObject(it), value.opt(it), depth + 1) }) return false
        }
        if (value is JSONArray) {
            if (value.length() > 128 || (s.has("minItems") && value.length() < s.optDouble("minItems")) || (s.has("maxItems") && value.length() > s.optDouble("maxItems"))) return false
            if (s.optJSONObject("items")?.let { child -> items(value).any { !matchesSchema(child, it, depth + 1) } } == true) return false
            if (s.optBoolean("uniqueItems") && items(value).withIndex().any { (i, v) -> (0 until i).any { equal(v, value.opt(it)) } }) return false
        }
        if (value is String) {
            val length = value.codePointCount(0, value.length)
            if ((s.has("minLength") && length < s.optDouble("minLength")) || (s.has("maxLength") && length > s.optDouble("maxLength"))) return false
            if (s.has("format") && !validFormat(s.getString("format"), value)) return false
        }
        if (value is Number) { val n = value.toDouble(); if (!n.isFinite()) return false; if ((s.has("minimum") && n < s.optDouble("minimum")) || (s.has("maximum") && n > s.optDouble("maximum")) || (s.has("exclusiveMinimum") && n <= s.optDouble("exclusiveMinimum")) || (s.has("exclusiveMaximum") && n >= s.optDouble("exclusiveMaximum"))) return false }
        return true
    }
    private fun validFormat(format: String, value: String): Boolean = when (format) {
        "email" -> value.indexOf('@') > 0 && value.substringAfter('@').isNotEmpty() && '@' !in value.substringAfter('@') && !value.any(Char::isWhitespace)
        "uri" -> runCatching { URI(value).isAbsolute && !value.any(Char::isWhitespace) }.getOrDefault(false)
        "date", "date-time" -> runCatching {
            require(value.all { it.code < 128 })
            val date = if (format == "date") value else value.substringBefore('T')
            require(date.length == 10 && LocalDate.parse(date).year > 0)
            if (format == "date") true else {
                val match = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T([0-9]{2}):([0-9]{2}):([0-9]{2}(?:\\.[0-9]+)?)(Z|[+-][0-9]{2}:[0-9]{2})").matchEntire(value)
                if (match == null) false else { val g = match.groupValues
                    g[1].toInt() < 24 && g[2].toInt() < 60 && g[3].toDouble() < 60 && (g[4] == "Z" || (g[4].substring(1, 3).toInt() <= 23 && g[4].substring(4).toInt() <= 59))
                }
            }
        }.getOrDefault(false)
        else -> false
    }
}
