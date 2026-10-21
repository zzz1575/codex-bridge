package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.string(key: String, fallback: String = ""): String = if (isNull(key)) fallback else optString(key, fallback)

object BridgeParser {
    fun session(j: JSONObject) = Session(
        id = j.string("id"), title = j.string("title").ifBlank { "新会话" },
        projectPath = j.string("cwd"), updatedAt = time(j.string("updated_at_ms", j.string("created_at"))),
        status = j.optJSONObject("status")?.string("type") ?: "idle", model = j.string("model")
    )
    private fun time(value: String): String = runCatching {
        val instant = value.toLongOrNull()?.let(Instant::ofEpochMilli) ?: Instant.parse(value)
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(instant)
    }.getOrDefault("")
    fun sessions(j: JSONObject): List<Session> = j.optJSONArray("threads")?.objects()?.map(::session).orEmpty()
    fun projects(j: JSONObject): List<Project> = j.optJSONArray("projects")?.objects()?.filter { it.string("kind") == "project" }
        ?.map { Project(it.string("path"), it.string("name"), it.string("path")) }.orEmpty()
    fun modelOptions(j: JSONObject): List<ModelOption> = j.optJSONArray("models")?.objects()?.mapNotNull { m ->
        val id = m.string("id")
        val efforts = m.optJSONArray("efforts")?.objects()?.mapNotNull { e ->
            e.string("id").takeIf { it.isNotBlank() }?.let { ReasoningOption(it, e.string("description")) }
        }.orEmpty().distinctBy { it.id }
        if (id.isBlank() || efforts.isEmpty()) null else ModelOption(
            id, m.string("name").ifBlank { id }, m.string("description"),
            m.string("default_effort").takeIf { value -> efforts.any { it.id == value } } ?: efforts.first().id,
            efforts,
            m.optJSONArray("service_tiers")?.objects()?.mapNotNull { tier ->
                tier.string("id").takeIf { it == ServiceTiers.PRIORITY }?.let {
                    ServiceTierOption(it, tier.string("name"), tier.string("description"))
                }
            }.orEmpty().distinctBy { it.id }
        )
    }.orEmpty().distinctBy { it.id }
    fun messages(j: JSONObject, threadId: String): MessagePage {
        val messages = j.optJSONArray("messages")?.objects()?.map { m ->
            val index = m.optInt("message_index", -1)
            val parts = m.optJSONArray("content")?.objects().orEmpty()
            val text = parts.filter { it.string("kind") in listOf("text", "pasted_text") }
                .map { it.string("text") }.filter { it.isNotBlank() }.joinToString("\n\n")
            val tools = m.optJSONArray("tools")?.objects()?.map { t -> ToolCall(
                t.string("call_id", "$threadId:$index:${t.optInt("tool_index")}"), t.string("name", "工具"),
                t.string("status"), t.string("preview"), t.string("output"), hasImage = t.optBoolean("has_image") || t.string("image_path").isNotBlank()
            ) }.orEmpty()
            val imageUrls = parts.filter { it.string("kind") == "image" }.mapNotNull { imageUrl(it) }
            ChatMessage(
                id = m.string("id").ifBlank { "$threadId:$index" }, role = m.string("role"), text = text,
                tools = tools, images = imageUrls, turnId = m.string("turn_id"),
                imagePending = parts.any { it.string("kind") == "context" && it.string("label").contains("Image", true) },
                deferred = m.optBoolean("deferred", false),
                deferredToolCount = m.optInt("deferred_tool_count"), deferredFileCount = m.optInt("deferred_file_count")
            )
        }?.filter { it.text.isNotBlank() || it.tools.isNotEmpty() || it.images.isNotEmpty() || it.imagePending || it.deferred }.orEmpty()
        val page = j.optJSONObject("page") ?: JSONObject()
        return MessagePage(j.optJSONObject("thread")?.let(::session), ToolIdentity.reconcile(messages), page.optInt("start"), page.optInt("total"), page.optBoolean("has_more"))
    }
    fun imageUrl(j: JSONObject): String? = sequenceOf(j.string("url"), j.string("data_url"), j.string("image_url"), j.optJSONObject("image_url")?.string("url").orEmpty())
        .firstOrNull { it.startsWith("https://") || it.startsWith("data:image/") }
    fun approvals(j: JSONObject): List<Approval> = j.optJSONArray("requests")?.objects()?.map { a ->
        val request = a.optJSONObject("request") ?: JSONObject()
        val params = request.optJSONObject("params") ?: JSONObject()
        Approval(a.optLong("id"), a.string("thread_id"), params.string("reason").ifBlank { request.string("method") }, params.toString(2))
    }.orEmpty()
    fun pending(j: JSONObject): List<PendingInput> = PendingMessages.entries(j)
    fun pendingSnapshot(j: JSONObject, threadId: String): PendingSnapshot = PendingMessages.parse(j, threadId)

    /** Snapshot data must not remove streamed content until the durable log confirms it. */
    fun mergeSnapshot(snapshot: List<ChatMessage>, current: List<ChatMessage>, prepend: Boolean = false): List<ChatMessage> {
        val snapshotIds = snapshot.map { it.id }.toSet()
        val currentTools = current.flatMap { message -> message.tools.flatMap { tool ->
            (tool.aliases + tool.id).filter { it.isNotBlank() }.map { ToolKey(message.turnId, it) to tool }
        } }.groupBy({ it.first }, { it.second })
        val reconciled = snapshot.map { original ->
            val overlay = current.find { it.id == original.id || it.id == "live:${original.id}" }
            // Completed-turn snapshots deliberately omit historical commentary/tools. A later
            // compact poll must not undo the full turn that the reader has already expanded.
            val expanded = if (original.deferred && overlay != null && !overlay.deferred) {
                val retainedImages = (overlay.images + original.images).distinct()
                original.copy(
                    text = original.text.ifBlank { overlay.text },
                    tools = overlay.tools + original.tools.filter { tool -> overlay.tools.none { it.id == tool.id } },
                    images = retainedImages,
                    imagePending = retainedImages.isEmpty() && (original.imagePending || overlay.imagePending),
                    deferred = false
                )
            } else original
            val logged = expanded.copy(tools = expanded.tools.flatMap { tool ->
                val previous = (tool.aliases + tool.id).flatMap { id ->
                    currentTools[ToolKey(expanded.turnId, id)].orEmpty()
                }.distinct()
                // Preserve both observations for identity-aware merging. A current historical
                // primary can also match a snapshot that still exposes only its SDK alias.
                previous + tool
            })
            val withImages = when {
                logged.images.isEmpty() && overlay != null && overlay.images.isNotEmpty() -> logged.copy(images = overlay.images, imagePending = false, imageError = null)
                logged.imagePending && overlay?.imageError != null -> logged.copy(imagePending = false, imageError = overlay.imageError)
                else -> logged
            }
            if (overlay != null && overlay.text.length > logged.text.length && overlay.streaming)
                withImages.copy(text = overlay.text, streaming = true)
            else withImages
        }
        // SDK tool media initially belongs to a live tool-only host. The durable log may
        // later attach those same tools to a different assistant message. Move the media
        // with its complete, unambiguous tool identity rather than leaving an image-only
        // live host behind. Never deduplicate equal image URLs across unrelated messages.
        val toolDestinations = reconciled.flatMapIndexed { index, message -> message.tools.flatMap { tool ->
            (tool.aliases + tool.id).filter { it.isNotBlank() }.map { ToolKey(message.turnId, it) to index }
        } }.groupBy({ it.first }, { it.second })
        val migratedHosts = current.mapIndexedNotNull { index, message ->
            if (message.turnId.isBlank() || message.tools.isEmpty() ||
                message.role !in setOf("assistant", "tool") || message.text.isNotBlank() ||
                message.streaming || message.deferred || message.deferredToolCount != 0 || message.deferredFileCount != 0
            ) return@mapIndexedNotNull null
            val destinations = message.tools.map { tool ->
                val destination = (tool.aliases + tool.id).filter { it.isNotBlank() }
                    .flatMap { toolDestinations[ToolKey(message.turnId, it)].orEmpty() }
                    .distinct().singleOrNull() ?: return@mapIndexedNotNull null
                tool to destination
            }
            val allDestinations = destinations.map { it.second }.distinct()
            val mediaDestinations = destinations.filter { it.first.hasImage }.map { it.second }.distinct()
            val hasMedia = message.images.isNotEmpty() || message.imagePending || !message.imageError.isNullOrBlank()
            // A code-mode parent and its image-producing child can gain separate durable
            // hosts. The sole image tool still establishes ownership of all live media.
            // Multiple image owners remain uncertain and must not lose their source.
            val destination = when {
                !hasMedia -> allDestinations.firstOrNull()
                mediaDestinations.size == 1 -> mediaDestinations.single()
                mediaDestinations.isEmpty() -> allDestinations.singleOrNull()
                else -> null
            } ?: return@mapIndexedNotNull null
            if (message.id.removePrefix("live:") == reconciled[destination].id.removePrefix("live:"))
                return@mapIndexedNotNull null
            index to destination
        }.toMap()
        val migratedByDestination = migratedHosts.entries.groupBy({ it.value }, { current[it.key] })
        val withToolMedia = reconciled.mapIndexed { index, message ->
            val sources = migratedByDestination[index].orEmpty()
            if (sources.isEmpty()) message else {
                val images = (message.images + sources.flatMap { it.images }).distinct()
                val imageError = message.imageError?.takeIf { it.isNotBlank() } ?: sources.firstNotNullOfOrNull { it.imageError?.takeIf { error -> error.isNotBlank() } }
                message.copy(images = images, imageError = imageError,
                    imagePending = images.isEmpty() && imageError == null && (message.imagePending || sources.any { it.imagePending }))
            }
        }
        val retainedCurrent = current.filterIndexed { index, _ -> index !in migratedHosts }
        if (prepend) return ToolIdentity.reconcile((withToolMedia + retainedCurrent.filter { it.id.removePrefix("live:") !in snapshotIds }).distinctBy { it.id })
        val toolIds = withToolMedia.flatMap { message -> message.tools.flatMap { tool ->
            (tool.aliases + tool.id).filter { it.isNotBlank() }.map { ToolKey(message.turnId, it) }
        } }.toSet()
        val remaining = retainedCurrent.filter { it.id.startsWith("live:") || it.streaming }.mapNotNull { original ->
            if (original.id.removePrefix("live:") in snapshotIds || original.id in snapshotIds) return@mapNotNull null
            val tools = original.tools.filter { tool ->
                (tool.aliases + tool.id).none { ToolKey(original.turnId, it) in toolIds }
            }
            val removedEmptyHost = original.tools.isNotEmpty() && tools.isEmpty() &&
                original.role in setOf("assistant", "tool") && original.text.isBlank() &&
                original.images.isEmpty() && !original.imagePending && original.imageError.isNullOrBlank() &&
                !original.streaming && !original.deferred && original.deferredToolCount == 0 && original.deferredFileCount == 0
            if (removedEmptyHost) null else original.copy(tools = tools)
        }
        return ToolIdentity.reconcile(withToolMedia + remaining)
    }

    private data class ToolKey(val turnId: String, val id: String)
}
