package com.y2yy.codex.mobile.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.y2yy.codex.mobile.BuildConfig
import com.y2yy.codex.mobile.data.*
import com.y2yy.codex.mobile.data.local.NativeStore
import com.y2yy.codex.mobile.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class CodexViewModel(application: Application): AndroidViewModel(application) {
    val api = BridgeApi(application)
    private val store = NativeStore(application)
    private val mutable = MutableStateFlow(AppUiState(serverLabel = BuildConfig.SERVER_URL, version = BuildConfig.VERSION_NAME))
    val state: StateFlow<AppUiState> = mutable.asStateFlow()
    private val refreshLock = Mutex()
    private var selectedRefreshRequested = false
    private var foreground = true
    private var streamJob: Job? = null
    private var streamGeneration = 0L
    private var refreshJob: Job? = null
    private val sessionListRefreshQueue = SessionRefreshQueue(viewModelScope) { loadSessions() }
    private var streamSequence = -1L
    private var sessionSeeded = false
    private var restorePending = true
    private var navigationRevision = 0L
    private var sessionGeneration = 0L
    private var pendingRevision = 0L
    private var lastListRefresh = 0L
    private val sessionRefreshGate = SessionRefreshGate()
    private var confirmedSessionPins: JSONObject? = null
    private var pageStart = -1
    private var liveRevision = 0L
    private var composerRevision = 0L
    private val messageIndices = mutableMapOf<String, Int>()
    private val toolRefs = mutableMapOf<String, Pair<Int, Int>>()
    private val toolPaths = mutableMapOf<String, String>()
    private val approvalOperations = mutableSetOf<Pair<Long, Long>>()
    private val serverRequestsLock = Mutex()
    private var serverRequestGeneration = 0L
    private var serverRequestProtocol = 0
    private val hydratedImages = mutableSetOf<String>()
    private val imageReferences = mutableMapOf<String, JSONObject>()
    private val expandedHistoryTurns = mutableSetOf<String>()
    private val fetchedTools = mutableSetOf<String>()
    private val hydratedToolImages = mutableSetOf<String>()
    private val toolImageReads = Semaphore(2)
    private val toolFetchLocks = mutableMapOf<String, Mutex>()
    private val toolImageLoads = mutableMapOf<String, ToolImageLoadStatus>()
    private var retrySubmission: Triple<String, String, String>? = null
    private val pendingDeletes = mutableSetOf<Pair<Long, String>>()

    init {
        viewModelScope.launch { store.darkTheme.collect { dark -> mutable.update { it.copy(darkTheme = dark) } } }
        viewModelScope.launch { store.preferredServiceTier.collect { tier -> mutable.update {
            it.copy(preferredServiceTier = tier,
                composerServiceTier = if (it.selected == null) tier else it.composerServiceTier)
        } } }
        viewModelScope.launch {
            store.cached("sessions")?.let { cached -> runCatching { JSONObject(cached) }.getOrNull()?.let { snapshot ->
                confirmedSessionPins = SessionOrdering.cachedPins(snapshot)
                mutable.update { it.copy(sessions = BridgeParser.sessions(snapshot), fromCache = true, loading = false) }
            } }
            val savedId = store.lastSession.first()
            mutable.value.sessions.find { it.id == savedId }?.takeIf { navigationRevision == 0L }?.let { session ->
                store.cached("messages:$savedId")?.let { cached -> runCatching {
                    if (navigationRevision != 0L) return@runCatching
                    val raw = JSONObject(cached)
                    val page = BridgeParser.messages(raw, savedId)
                    indexPage(raw, savedId)
                    pageStart = page.start
                    mutable.update { it.copy(selected = session, messages = page.messages, fromCache = true, loading = false, hasOlder = page.hasOlder,
                        composerModel = session.model, composerServiceTier = ServiceTiers.UNKNOWN,
                        modelSettingsError = ServiceTiers.UNCONFIRMED_MESSAGE) }
                    hydrateImages(raw, savedId)
                } }
            }
            startStream()
            refresh()
            while (isActive) {
                delay(5000)
                if (foreground) runCatching {
                    refreshSelected()
                    if (System.currentTimeMillis() - lastListRefresh > 15000) loadSessions()
                }.onFailure { e -> if (BuildConfig.DEBUG) android.util.Log.w("NativeBridge", "Background sync failed: ${e.javaClass.simpleName}") }
            }
        }
    }

    fun setForeground(active: Boolean) {
        foreground = active
        if (active) { mutable.update { it.copy(connected = false, connectionText = "正在连接") }; startStream(); scheduleRefresh() }
        else { streamGeneration++; streamJob?.cancel(); streamJob = null; invalidateServerRequests(); mutable.update { it.copy(connected = false, connectionText = "已转入后台", pendingVerified = false, pendingSyncError = "重新连接后同步队列") } }
    }
    private fun startStream() {
        if (streamJob?.isActive == true || !foreground) return
        val generation = ++streamGeneration
        streamJob = viewModelScope.launch {
            var delayMs = 700L
            while (isActive && foreground) {
                try {
                    if (!sessionSeeded) {
                        sessionSeeded = true
                        // Basic auth also protects every request. Seed the cookie only once,
                        // without blocking reconnects on the gateway's login rate limit.
                        runCatching { api.authenticate() }
                    }
                    api.events().collect { event ->
                        if (generation != streamGeneration) return@collect
                        val type = event.string("type")
                        if (type == "native_connected") {
                            delayMs = 700
                            invalidateServerRequests()
                            mutable.update { it.copy(connected = true, connectionText = "已连接", error = null) }
                            mutable.value.selected?.id?.let { id -> launch {
                                runCatching {
                                    val watch = api.command(JSONObject().put("command", "thread_watch").put("thread_id", id))
                                    if (mutable.value.selected?.id == id) mutable.update { it.copy(readOnly = watch.optJSONObject("writer_lock")?.optBoolean("read_only") ?: false) }
                                }
                            } }
                            scheduleRefresh()
                            mutable.value.selected?.id?.let { id -> launch { loadComposer(id) } }
                        }
                        val sequence = event.optLong("bridge_sequence", -1)
                        if (type == "bridge_event_stream") {
                            streamSequence = event.optLong("sequence", -1)
                            scheduleRefresh()
                        } else if (sequence >= 0) {
                            if (sequence <= streamSequence) return@collect
                            if (streamSequence >= 0 && sequence > streamSequence + 1) { scheduleRefresh(); mutable.value.selected?.id?.let { id -> launch { loadComposer(id) } } }
                            streamSequence = sequence
                        }
                        if (type in listOf("app_server", "bridge_thread_run_state", "bridge_thread_activity_snapshot", "bridge_event_stream")) liveRevision++
                        if (type == "app_server" && event.optJSONObject("message")?.string("method") == "thread/settings/updated") composerRevision++
                        mutable.update { StreamReducer.apply(it, event) }
                        if (type == "bridge_thread_run_state") {
                            val id = event.string("thread_id")
                            if (id == mutable.value.selected?.id) scheduleRefresh()
                            if (id.isNotBlank() && mutable.value.sessions.none { it.id == id }) scheduleListRefresh()
                        }
                        if (type == "bridge_thread_pins_updated") {
                            SessionOrdering.pins(event)?.let { confirmedSessionPins = it }
                            sessionRefreshGate.begin() // Invalidate reads started before this native order change.
                            scheduleListRefresh()
                        }
                        if (type in listOf("bridge_approvals_changed", "bridge_server_requests_changed", "app_server_request")) launch { runCatching { loadApprovals() } }
                        if (type == "bridge_event_gap") { scheduleRefresh(); scheduleListRefresh(); mutable.value.selected?.id?.let { id -> launch { loadComposer(id) } } }
                        if (type == "bridge_app_server_connection") {
                            if (event.string("status") != "connected") invalidateServerRequests()
                            else launch { runCatching { loadServerRequests() } }
                            mutable.update {
                            val ready = event.string("status") == "connected"
                            it.copy(connected = ready, connectionText = if (ready) "已连接" else "服务器执行端正在恢复",
                                pendingVerified = if (ready) it.pendingVerified else false,
                                pendingSyncError = if (ready) it.pendingSyncError else "执行端恢复后同步队列")
                            }
                        }
                        if (type == "app_server") {
                            val method = event.optJSONObject("message")?.string("method").orEmpty()
                            if (event.optJSONObject("message")?.optJSONObject("params")?.string("threadId") == mutable.value.selected?.id &&
                                method in listOf("turn/completed", "item/completed", "thread/status/changed", "turn/started")) scheduleRefresh()
                            if (method == "serverRequest/resolved") launch { runCatching { loadServerRequests() } }
                        }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { if (generation == streamGeneration) mutable.update { it.copy(connected = false, connectionText = "连接中断，正在重连", pendingVerified = false, pendingSyncError = "重新连接后同步队列") } }
                finally { if (generation == streamGeneration) { invalidateServerRequests(); mutable.update { it.copy(connected = false, connectionText = if (foreground) "连接中断，正在重连" else "已转入后台", pendingVerified = false, pendingSyncError = "重新连接后同步队列") } } }
                if (foreground) { delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(15000) }
            }
        }
    }
    fun refresh() { viewModelScope.launch {
        val restore = restorePending
        val revision = navigationRevision
        restorePending = false
        try {
            loadSessions()
            val saved = store.lastSession.first()
            if (mutable.value.selected == null && restore && revision == navigationRevision && saved.isNotBlank()) {
                mutable.value.sessions.find { it.id == saved }?.let { open(it) }
            } else {
                refreshSelected()
                mutable.value.selected?.id?.let { loadComposer(it) }
            }
            loadApprovals()
            mutable.update { it.copy(loading = false, error = null, fromCache = false) }
        } catch (e: Exception) { if (e is CancellationException) throw e; error(e) }
    } }
    private suspend fun loadSessions() {
        val refreshTicket = sessionRefreshGate.begin()
        val (sessionsJson, loadedPins) = coroutineScope {
            val listing = async { api.command(JSONObject().put("command", "ls").put("limit", 200).put("include_archived", false)) }
            val pins = async {
                try { api.command(JSONObject().put("command", "thread_pins")) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } // Older Bridge versions keep their original ls order.
            }
            val nativePins = SessionOrdering.pins(pins.await())
            SessionOrdering.snapshot(listing.await(), nativePins, confirmedSessionPins) to nativePins
        }
        val readLimit = Semaphore(4)
        val reads = coroutineScope {
            BridgeParser.sessions(sessionsJson).map { session -> async {
                readLimit.withPermit {
                    val token = SessionActivity.capture(mutable.value, session.id, streamSequence)
                    try {
                        val canonical = readThread(session.id)
                        val updated = session.copy(
                            title = canonical.string("name").ifBlank { session.title },
                            projectPath = canonical.string("cwd").ifBlank { session.projectPath },
                            model = canonical.string("model").ifBlank { session.model }
                        )
                        updated to SessionActivity.Observation(token, SessionActivity.statusType(canonical.opt("status")))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val previous = mutable.value.sessions.find { it.id == session.id }
                        session.copy(projectPath = previous?.projectPath ?: session.projectPath) to SessionActivity.Observation(token, null)
                    }
                }
            } }.awaitAll()
        }
        val sessions = reads.map { it.first }
        val projects = runCatching { BridgeParser.projects(api.command(JSONObject().put("command", "projects").put("include_archived", false))) }.getOrElse { mutable.value.projects }
        val byId = sessions.associateBy { it.id }
        sessionsJson.optJSONArray("threads")?.objects()?.forEach { raw -> byId[raw.string("id")]?.let { canonical ->
            raw.put("title", canonical.title).put("cwd", canonical.projectPath).put("model", canonical.model)
        } }
        sessionRefreshGate.publish(refreshTicket) {
            if (loadedPins != null) confirmedSessionPins = loadedPins
            mutable.update { old -> SessionActivity.mergeSessions(old, sessions, reads.map { it.second }).copy(
                selected = old.selected?.let { selected -> sessions.find { it.id == selected.id }?.copy(model = old.composerModel.ifBlank { selected.model }) ?: selected },
                projects = projects, loading = false, fromCache = false) }
            store.cache("sessions", sessionsJson.toString())
            lastListRefresh = System.currentTimeMillis()
        }
    }
    fun navigationChanged() { navigationRevision++; restorePending = false }
    fun select(session: Session) { navigationChanged(); viewModelScope.launch { open(session) } }
    private suspend fun open(session: Session) {
        sessionGeneration++
        val generation = sessionGeneration
        composerRevision++
        pageStart = -1; messageIndices.clear(); toolRefs.clear(); toolPaths.clear(); hydratedImages.clear(); fetchedTools.clear(); hydratedToolImages.clear(); toolFetchLocks.clear(); toolImageLoads.clear()
        imageReferences.clear(); expandedHistoryTurns.clear()
        mutable.update { it.copy(selected = session, messages = emptyList(), pending = emptyList(), pendingVerified = false, pendingSyncError = null, pendingDeletingIds = emptySet(), attachments = emptyList(), activeTurnId = it.sessionRunStates[session.id]?.turnId.takeIf { _ -> session.id in it.activeSessionIds },
            running = session.id in it.activeSessionIds, hasOlder = false, loading = true, activity = "", usage = "", readOnly = false, error = null,
            loadingHistoryTurns = emptySet(), historyErrors = emptyMap(), composerModel = session.model, composerEffort = "",
            composerServiceTier = ServiceTiers.UNKNOWN,
            savingModelSettings = false, modelSettingsError = ServiceTiers.UNCONFIRMED_MESSAGE, sessionActionBusy = false, sessionActionError = null) }
        store.setLastSession(session.id)
        store.cached("messages:${session.id}")?.let { cached -> runCatching {
            val raw = JSONObject(cached)
            val page = BridgeParser.messages(raw, session.id)
            if (mutable.value.selected?.id == session.id && generation == sessionGeneration) {
                indexPage(raw, session.id); pageStart = page.start
                mutable.update { it.copy(messages = page.messages, fromCache = true, loading = false, hasOlder = page.hasOlder) }
                hydrateImages(raw, session.id)
            }
        } }
        try {
            val activityToken = SessionActivity.capture(mutable.value, session.id, streamSequence)
            val watch = api.command(JSONObject().put("command", "thread_watch").put("thread_id", session.id))
            if (mutable.value.selected?.id != session.id || generation != sessionGeneration) return
            val lock = watch.optJSONObject("writer_lock")
            val status = SessionActivity.statusType(watch.optJSONObject("thread")?.opt("status"))
            mutable.update { SessionActivity.applyRead(it, activityToken, status).copy(readOnly = lock?.optBoolean("read_only", false) ?: false, loading = false) }
            refreshSelected()
            loadComposer(session.id)
        } catch (e: Exception) { if (e is CancellationException) throw e; if (mutable.value.selected?.id == session.id && generation == sessionGeneration) error(e) }
    }
    fun back() { navigationChanged(); sessionGeneration++; mutable.update { it.copy(selected = null, attachments = emptyList()) } }
    fun clearSessionActionError() { mutable.update { it.copy(sessionActionError = null) } }
    fun renameSession(name: String) { viewModelScope.launch {
        val current = mutable.value
        val selected = current.selected ?: return@launch
        if (current.sessionActionBusy) return@launch
        if (!current.connected || current.readOnly) {
            mutable.update { it.copy(sessionActionError = if (current.readOnly) "当前会话由其他客户端控制，暂时无法修改" else "连接恢复后可保存") }
            return@launch
        }
        val title = name.trim()
        if (title.isBlank() || title.codePointCount(0, title.length) > 200) {
            mutable.update { it.copy(sessionActionError = "会话名称需为 1～200 个字符") }
            return@launch
        }
        mutable.update { it.copy(sessionActionBusy = true, sessionActionError = null) }
        try {
            val result = api.command(SessionCommands.rename(selected.id, title))
            val saved = result.string("name", title)
            mutable.update { old -> old.copy(
                sessions = old.sessions.map { if (it.id == selected.id) it.copy(title = saved) else it },
                selected = old.selected?.let { if (it.id == selected.id) it.copy(title = saved) else it }
            ) }
            runCatching { loadSessions() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (mutable.value.selected?.id == selected.id) mutable.update { it.copy(sessionActionError = if (e is IOException) "会话名称保存失败，请检查网络后重试" else e.message ?: "会话名称保存失败") }
        } finally {
            if (mutable.value.selected?.id == selected.id) mutable.update { it.copy(sessionActionBusy = false) }
        }
    } }
    private suspend fun readThread(id: String): JSONObject = api.command(JSONObject().put("command", "app_server_rpc").put("method", "thread/read")
        .put("params", JSONObject().put("threadId", id).put("includeTurns", false)))
        .optJSONObject("result")?.optJSONObject("thread") ?: throw IOException("服务器未返回会话状态")
    fun moveSession(projectId: String?) { viewModelScope.launch {
        val current = mutable.value
        val selected = current.selected ?: return@launch
        if (current.sessionActionBusy) return@launch
        if (!current.connected || current.readOnly || current.running) {
            mutable.update { it.copy(sessionActionError = when {
                current.running -> "当前任务结束后可移动会话"
                current.readOnly -> "当前会话由其他客户端控制，暂时无法修改"
                else -> "连接恢复后可保存"
            }) }
            return@launch
        }
        mutable.update { it.copy(sessionActionBusy = true, sessionActionError = null) }
        try {
            // Negotiate before mutation; a failed move must never be retried through a weaker API.
            val serverStatus = api.command(JSONObject().put("command", "status"))
            val protocolVersion = serverStatus.optInt("protocol_version", 0)
            val target = if (projectId == null) {
                serverStatus.optJSONObject("configuration")?.string("projectless_workspace_root")
                    ?.takeIf { it.startsWith("/") } ?: throw IOException("服务器未返回独立会话目录，请刷新后重试")
            } else current.projects.find { it.id == projectId }?.path ?: throw IllegalArgumentException("目标项目不存在，请刷新项目列表")
            val before = readThread(selected.id)
            check(before.optJSONObject("status")?.string("type") != "active") { "当前任务结束后可移动会话" }
            val result = api.command(SessionCommands.move(selected.id, target, protocolVersion))
            val savedCwd = SessionCommands.movedCwd(result, target, protocolVersion)
            var applied = false
            for (attempt in 0 until 30) {
                if (readThread(selected.id).string("cwd") == savedCwd) { applied = true; break }
                delay(200)
            }
            check(applied) { "服务器尚未确认目录变更，请刷新后检查" }
            mutable.update { old -> old.copy(
                sessions = old.sessions.map { if (it.id == selected.id) it.copy(projectPath = savedCwd) else it },
                selected = old.selected?.let { if (it.id == selected.id) it.copy(projectPath = savedCwd) else it }
            ) }
            runCatching { loadSessions() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (mutable.value.selected?.id == selected.id) mutable.update { it.copy(sessionActionError = if (e is IOException) "移动未完成，请检查网络后刷新确认" else e.message ?: "移动会话失败") }
        } finally {
            if (mutable.value.selected?.id == selected.id) mutable.update { it.copy(sessionActionBusy = false) }
        }
    } }
    private fun scheduleRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { delay(350); runCatching { refreshSelected() } }
    }
    private fun scheduleListRefresh() {
        sessionListRefreshQueue.request()
    }
    private suspend fun refreshSelected() {
        if (!refreshLock.tryLock()) { selectedRefreshRequested = true; return }
        val id = mutable.value.selected?.id
        val generation = sessionGeneration
        try {
            if (BuildConfig.DEBUG) android.util.Log.d("NativeBridge", "Sync selected=${id != null}")
            if (id != null) {
                loadMessages(id, false)
                if (mutable.value.selected?.id != id || generation != sessionGeneration) return
                val revision = liveRevision
                val activityToken = SessionActivity.capture(mutable.value, id, streamSequence)
                val activity = api.command(JSONObject().put("command", "thread_activity").put("thread_id", id)).optJSONObject("activity") ?: JSONObject()
                val liveThread = runCatching { readThread(id) }.getOrNull()
                val liveStatus = SessionActivity.statusType(liveThread?.opt("status"))
                val pendingReadRevision = pendingRevision
                val pending = readPending(id)
                if (mutable.value.selected?.id == id && generation == sessionGeneration) mutable.update { old ->
                    val reconciled = SessionActivity.applyRead(old, activityToken, liveStatus)
                    val running = reconciled.running
                    val phase = when (activity.string("phase")) { "tool" -> "正在执行：${activity.string("active_tool", "工具")}"; "compacting" -> "正在整理上下文"; "model" -> "正在思考"; else -> old.activity }
                    val canonicalPath = liveThread?.string("cwd")?.takeIf { it.isNotBlank() }
                    PendingMessages.reduce(reconciled, pending, id, generation, sessionGeneration, pendingReadRevision, pendingRevision).copy(
                        selected = old.selected?.let { it.copy(projectPath = canonicalPath ?: it.projectPath) },
                        sessions = if (canonicalPath == null) reconciled.sessions else reconciled.sessions.map { if (it.id == id) it.copy(projectPath = canonicalPath) else it },
                        messages = if (!running && liveStatus == "idle" && revision == liveRevision) old.messages.map { m -> m.copy(tools = m.tools.map { t -> if (t.status.lowercase() in listOf("inprogress", "in_progress", "running", "started")) t.copy(status = "interrupted") else t }) } else old.messages,
                        activity = if (revision != liveRevision) reconciled.activity else if (!running && old.running) "已完成" else phase, loading = false)
                }
            }
            loadApprovals()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (id != null && mutable.value.selected?.id == id && generation == sessionGeneration) mutable.update {
                it.copy(pendingVerified = false, pendingSyncError = "队列同步未确认，请重新同步")
            }
            throw e
        } finally {
            refreshLock.unlock()
            if (selectedRefreshRequested) {
                selectedRefreshRequested = false
                if (foreground) viewModelScope.launch { runCatching { refreshSelected() } }
            }
        }
    }
    private suspend fun readPending(id: String): PendingSnapshot = try {
        BridgeParser.pendingSnapshot(api.command(JSONObject().put("command", "pending_messages").put("thread_id", id)), id)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        PendingSnapshot(emptyList(), available = false, authoritative = false, error = "队列同步未确认，请重新同步")
    }
    fun cancelPending(pendingId: String) { viewModelScope.launch {
        val current = mutable.value
        val id = current.selected?.id ?: return@launch
        val generation = sessionGeneration
        val item = current.pending.firstOrNull { it.id == pendingId } ?: return@launch
        if (!current.connected || !PendingMessages.canCancel(item, current.pendingVerified)) return@launch
        val operation = generation to pendingId
        if (!pendingDeletes.add(operation)) return@launch
        mutable.update { it.copy(pendingDeletingIds = it.pendingDeletingIds + pendingId) }
        try {
            refreshLock.withLock {
                if (mutable.value.selected?.id != id || generation != sessionGeneration || !mutable.value.connected) return@withLock
                val readRevision = pendingRevision
                val fresh = readPending(id)
                mutable.update { PendingMessages.reduce(it, fresh, id, generation, sessionGeneration, readRevision, pendingRevision) }
                if (readRevision != pendingRevision) return@withLock
                if (!fresh.available || !fresh.authoritative || fresh.error != null) return@withLock
                val queued = fresh.messages.firstOrNull { it.id == pendingId }
                if (queued == null || !PendingMessages.canCancel(queued, true)) return@withLock
                if (mutable.value.selected?.id != id || generation != sessionGeneration || !mutable.value.connected) return@withLock
                api.command(JSONObject().put("command", "pending_message_delete").put("thread_id", id).put("id", pendingId))
                pendingRevision++
                if (mutable.value.selected?.id == id && generation == sessionGeneration) {
                    mutable.update { it.copy(pending = it.pending.filterNot { p -> p.id == pendingId }, pendingVerified = false) }
                    val confirmedReadRevision = pendingRevision
                    val confirmed = readPending(id)
                    mutable.update { PendingMessages.reduce(it, confirmed, id, generation, sessionGeneration, confirmedReadRevision, pendingRevision) }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (mutable.value.selected?.id == id && generation == sessionGeneration) mutable.update {
                it.copy(pendingVerified = false, pendingSyncError = "撤回结果未确认，请先重新同步；不会自动重复撤回")
            }
        } finally {
            pendingDeletes.remove(operation)
            if (mutable.value.selected?.id == id && generation == sessionGeneration) mutable.update { it.copy(pendingDeletingIds = it.pendingDeletingIds - pendingId) }
            scheduleRefresh()
        }
    } }
    private suspend fun loadComposer(id: String) {
        val revision = composerRevision
        runCatching {
            val j = api.command(JSONObject().put("command", "composer_status").put("thread_id", id))
            val usage = j.optJSONObject("weekly_usage")
            val text = listOfNotNull(j.string("model").takeIf { it.isNotBlank() }, j.string("reasoning_effort").takeIf { it.isNotBlank() }, usage?.let { "周剩余 ${it.optInt("remaining_percent")}%" }).joinToString(" · ")
            if (mutable.value.selected?.id == id && revision == composerRevision) mutable.update { old ->
                val model = j.string("model").ifBlank { old.composerModel.ifBlank { old.selected?.model.orEmpty() } }
                val effort = j.string("reasoning_effort").ifBlank { old.modelOptions.find { it.id == model }?.defaultEffort.orEmpty() }
                val tier = ServiceTiers.fromSettings(j, old.composerServiceTier)
                old.copy(usage = text, composerModel = model, composerEffort = effort,
                    composerServiceTier = tier, modelSettingsError = ServiceTiers.errorFor(tier, old.modelSettingsError))
            }
        }
    }
    fun loadModels() { viewModelScope.launch {
        if (mutable.value.loadingModelOptions) return@launch
        mutable.update { it.copy(loadingModelOptions = true, modelSettingsError = null) }
        try {
            val options = BridgeParser.modelOptions(api.command(JSONObject().put("command", "composer_options")))
            require(options.isNotEmpty()) { "服务器暂未返回可用模型，请重试" }
            mutable.update { old -> old.copy(modelOptions = options,
                composerEffort = old.composerEffort.ifBlank { options.find { it.id == old.composerModel }?.defaultEffort.orEmpty() }) }
            mutable.value.selected?.id?.let { loadComposer(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutable.update { it.copy(modelSettingsError = if (e is IOException) "模型列表加载失败，请检查网络后重试" else e.message ?: "模型列表加载失败") }
        } finally { mutable.update { it.copy(loadingModelOptions = false) } }
    } }
    /** Legacy callers omit speed entirely, preserving the current server setting. */
    fun saveModelSettings(model: String, effort: String) = saveComposerSettingsNow(model, effort, null)
    fun saveComposerSettings(model: String, effort: String, serviceTier: String) =
        saveComposerSettingsNow(model, effort, serviceTier)
    private fun saveComposerSettingsNow(model: String, effort: String, serviceTier: String?) { viewModelScope.launch {
        val current = mutable.value
        val id = current.selected?.id
        val generation = sessionGeneration
        if (current.savingModelSettings) return@launch
        if (id != null && (current.readOnly || !current.connected)) {
            mutable.update { it.copy(modelSettingsError = if (current.readOnly) "当前会话由其他客户端控制，暂时不能修改模型" else "连接服务器后才能保存模型设置") }
            return@launch
        }
        val option = current.modelOptions.find { it.id == model }
        if (option == null || option.efforts.none { it.id == effort }) {
            mutable.update { it.copy(modelSettingsError = "请选择服务器支持的模型和思考强度") }
            return@launch
        }
        if (serviceTier != null && serviceTier !in setOf(ServiceTiers.STANDARD, ServiceTiers.PRIORITY)) {
            mutable.update { it.copy(modelSettingsError = "请明确选择标准或快速模式后保存") }
            return@launch
        }
        if (serviceTier != null && !ServiceTiers.supported(option, serviceTier)) {
            mutable.update { it.copy(modelSettingsError = "所选模型不支持快速模式，请选择标准模式") }
            return@launch
        }
        composerRevision++
        mutable.update { it.copy(savingModelSettings = true, modelSettingsError = null) }
        try {
            if (id == null) {
                if (serviceTier != null) {
                    store.setPreferredServiceTier(serviceTier)
                    mutable.update { old -> if (old.selected == null) old.copy(composerModel = model,
                        composerEffort = effort, composerServiceTier = serviceTier, preferredServiceTier = serviceTier) else old }
                }
                return@launch
            }
            val result = api.command(SessionCommands.modelSettings(id, model, effort, serviceTier))
            val savedModel = result.string("model", model)
            val savedEffort = result.string("reasoning_effort", effort)
            val savedTier = if (serviceTier != null) ServiceTiers.confirmed(result, serviceTier)
                else ServiceTiers.fromSettings(result, current.composerServiceTier)
            if (serviceTier != null) store.setPreferredServiceTier(savedTier)
            if (mutable.value.selected?.id == id && generation == sessionGeneration) mutable.update { it.copy(
                composerModel = savedModel, composerEffort = savedEffort, composerServiceTier = savedTier,
                selected = it.selected?.copy(model = savedModel),
                sessions = it.sessions.map { session -> if (session.id == id) session.copy(model = savedModel) else session }, modelSettingsError = null
            ) }
            loadComposer(id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (mutable.value.selected?.id == id && generation == sessionGeneration) mutable.update { it.copy(
                composerServiceTier = if (id != null && serviceTier != null) ServiceTiers.UNKNOWN else it.composerServiceTier,
                modelSettingsError = if (e is IOException) "模型设置保存失败，请检查网络后重试" else e.message ?: "模型设置保存失败") }
        } finally {
            if (mutable.value.selected?.id == id && generation == sessionGeneration) mutable.update { it.copy(savingModelSettings = false) }
        }
    } }
    private suspend fun loadMessages(id: String, older: Boolean) {
        val generation = sessionGeneration
        val request = JSONObject().put("command", "messages").put("thread_id", id).put("limit", 30)
        if (older) request.put("before", pageStart)
        val json = api.command(request)
        if (mutable.value.selected?.id != id || generation != sessionGeneration) return
        val page = BridgeParser.messages(json, id)
        indexPage(json, id)
        if (older || pageStart < 0 || mutable.value.messages.isEmpty()) pageStart = page.start else pageStart = minOf(pageStart, page.start)
        mutable.update { old ->
            val before = if (!older) old.messages.filter { (messageIndices[it.id] ?: Int.MAX_VALUE) < page.start } else emptyList()
            val merged = if (older) BridgeParser.mergeSnapshot(page.messages, old.messages, true) else before + BridgeParser.mergeSnapshot(page.messages, old.messages.filter { it !in before })
            val visible = merged.filterNot { it.deferred && it.turnId in expandedHistoryTurns && it.text.isBlank() && it.tools.isEmpty() && it.images.isEmpty() && !it.imagePending }
            old.copy(messages = visible.distinctBy { it.id }, selected = page.thread?.copy(title = old.selected?.title ?: page.thread.title, projectPath = old.selected?.projectPath ?: page.thread.projectPath) ?: old.selected, hasOlder = pageStart > 0, fromCache = false, loading = false)
        }
        if (!older) store.cache("messages:$id", json.toString())
        if (generation == sessionGeneration) hydrateImages(json, id)
    }
    private fun indexPage(json: JSONObject, id: String) {
        for (m in json.optJSONArray("messages")?.objects().orEmpty()) {
            val index = m.optInt("message_index")
            val messageId = m.string("id").ifBlank { "$id:$index" }
            messageIndices[messageId] = index
            m.optJSONArray("tools")?.objects()?.forEach { t ->
                val toolId = t.string("call_id", "$id:$index:${t.optInt("tool_index")}")
                toolRefs[toolId] = index to t.optInt("tool_index")
                if (t.string("image_path").isNotBlank()) toolPaths[toolId] = t.string("image_path")
            }
        }
    }
    fun older() { viewModelScope.launch {
        if (mutable.value.loadingOlder || !mutable.value.hasOlder) return@launch
        val id = mutable.value.selected?.id ?: return@launch
        mutable.update { it.copy(loadingOlder = true) }
        try { loadMessages(id, true) } catch (e: Exception) { if (e is CancellationException) throw e; error(e) }
        finally { mutable.update { it.copy(loadingOlder = false) } }
    } }
    fun historyTurn(turnId: String) { viewModelScope.launch {
        val id = mutable.value.selected?.id ?: return@launch
        val generation = sessionGeneration
        if (turnId.isBlank() || turnId in expandedHistoryTurns || turnId in mutable.value.loadingHistoryTurns) return@launch
        mutable.update { it.copy(loadingHistoryTurns = it.loadingHistoryTurns + turnId, historyErrors = it.historyErrors - turnId) }
        try {
            val key = "turn:$id:$turnId"
            val cached = store.cached(key)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val json = cached ?: api.command(JSONObject().put("command", "turn_messages").put("thread_id", id).put("turn_id", turnId))
            if (mutable.value.selected?.id != id || generation != sessionGeneration) return@launch
            val details = BridgeParser.messages(json, id).messages
            indexPage(json, id)
            expandedHistoryTurns.add(turnId)
            mutable.update { old ->
                val first = old.messages.indexOfFirst { it.turnId == turnId }
                if (first < 0) old else {
                    val expanded = BridgeParser.mergeSnapshot(details, old.messages.filter { it.turnId == turnId })
                        .map { it.copy(deferred = false) }
                    old.copy(messages = (old.messages.take(first) + expanded + old.messages.drop(first).filter { it.turnId != turnId }).distinctBy { it.id })
                }
            }
            if (cached == null) store.cache(key, json.toString())
            if (generation == sessionGeneration) hydrateImages(json, id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (mutable.value.selected?.id == id) mutable.update { it.copy(historyErrors = it.historyErrors + (turnId to "历史详情加载失败，请重试")) }
        } finally {
            if (mutable.value.selected?.id == id) mutable.update { it.copy(loadingHistoryTurns = it.loadingHistoryTurns - turnId) }
        }
    } }
    private fun hydrateImages(json: JSONObject, threadId: String) {
        if (mutable.value.selected?.id != threadId) return
        val generation = sessionGeneration
        for (m in json.optJSONArray("messages")?.objects().orEmpty()) {
            val index = m.optInt("message_index")
            val mid = m.string("id").ifBlank { "$threadId:$index" }
            for (rawTool in m.optJSONArray("tools")?.objects().orEmpty()) {
                if (!rawTool.optBoolean("has_image") && rawTool.string("image_path").isBlank()) continue
                val toolId = rawTool.string("call_id", "$threadId:$index:${rawTool.optInt("tool_index")}")
                if (hydratedToolImages.add(toolId)) {
                    mutable.update { state -> state.copy(messages = state.messages.map { message ->
                        if (message.id == mid && message.images.isEmpty()) message.copy(imagePending = true, imageError = null) else message
                    }) }
                    requestTool(toolId, threadId, forceImage = true)
                }
            }
            val contexts = m.optJSONArray("content")?.objects()?.filter { it.string("kind") == "context" && it.string("label").contains("Image", true) }.orEmpty()
            if (contexts.isEmpty()) continue
            imageReferences[mid] = m
            if (hydratedImages.add(mid)) viewModelScope.launch { loadImageMessage(m, threadId, false, generation) }
        }
    }
    fun retryImage(messageId: String) {
        val threadId = mutable.value.selected?.id ?: return
        val raw = imageReferences[messageId]
        if (raw == null) {
            val imageTools = mutable.value.messages.firstOrNull { it.id == messageId }?.tools?.filter { tool ->
                tool.hasImage || (tool.aliases + tool.id).any { it in toolPaths || it in toolImageLoads }
            }.orEmpty()
            val failed = imageTools.filter { tool -> (tool.aliases + tool.id).any { toolImageLoads[it] == ToolImageLoadStatus.FAILED } }
            val toolIds = (failed.ifEmpty { imageTools }).mapNotNull { tool ->
                (listOf(tool.id) + tool.aliases).firstOrNull { it in toolRefs }
            }
            if (toolIds.isNotEmpty()) {
                mutable.update { it.copy(messages = it.messages.map { m -> if (m.id == messageId) m.copy(imagePending = true, imageError = null) else m }) }
                toolIds.forEach { toolId ->
                    requestTool(toolId, threadId, forceImage = true)
                }
            }
            return
        }
        mutable.update { it.copy(messages = it.messages.map { m -> if (m.id == messageId) m.copy(images = emptyList(), imagePending = true, imageError = null) else m }) }
        val generation = sessionGeneration
        viewModelScope.launch { loadImageMessage(raw, threadId, true, generation) }
    }
    private suspend fun loadImageMessage(raw: JSONObject, threadId: String, force: Boolean, generation: Long) {
        if (mutable.value.selected?.id != threadId || generation != sessionGeneration) return
        val index = raw.optInt("message_index")
        val mid = raw.string("id").ifBlank { "$threadId:$index" }
        try {
            val key = "images:$threadId:$mid"
            val cached = if (force) null else store.cached(key)?.let { runCatching { JSONArray(it) }.getOrNull() }
            val images = mutableListOf<String>()
            if (cached != null) for (i in 0 until cached.length()) cached.optString(i).takeIf { it.isNotBlank() }?.let(images::add)
            if (images.isEmpty()) {
                val contexts = raw.optJSONArray("content")?.objects()?.filter { it.string("kind") == "context" && it.string("label").contains("Image", true) }.orEmpty()
                for (part in contexts.take(6)) {
                    val request = JSONObject().put("command", "message_content").put("thread_id", threadId).put("message_index", index).put("content_index", part.optInt("content_index"))
                    if (part.has("content_end")) request.put("content_end", part.optInt("content_end"))
                    collectImages(api.command(request).opt("content"), images, 0)
                }
                require(images.isNotEmpty()) { "服务器未返回图片" }
                store.cache(key, JSONArray(images.distinct()).toString())
            }
            if (mutable.value.selected?.id == threadId && generation == sessionGeneration) mutable.update { it.copy(messages = it.messages.map { m ->
                if (m.id == mid) m.copy(images = images.distinct(), imagePending = false, imageError = null) else m
            }) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (mutable.value.selected?.id == threadId && generation == sessionGeneration) mutable.update { it.copy(messages = it.messages.map { m ->
                if (m.id == mid) m.copy(imagePending = false, imageError = "历史图片加载失败，请重试") else m
            }) }
        }
    }
    private fun collectImages(raw: Any?, into: MutableList<String>, depth: Int) {
        if (depth > 8 || into.size >= 6) return
        into.addAll(MessageImages.collect(raw).filterNot { it in into }.take(6 - into.size))
    }
    fun tool(id: String) { mutable.value.selected?.id?.let { requestTool(id, it) } }
    private fun requestTool(id: String, threadId: String, forceImage: Boolean = false) {
      val generation = sessionGeneration
      viewModelScope.launch {
        if (mutable.value.selected?.id != threadId || generation != sessionGeneration) return@launch
        val currentTool = mutable.value.messages.flatMap { it.tools }.find { it.id == id || id in it.aliases }
        val refId = (listOfNotNull(currentTool?.id, id) + currentTool?.aliases.orEmpty()).firstOrNull { it in toolRefs } ?: return@launch
        val ref = toolRefs.getValue(refId)
        val imagePath = toolPaths[refId]
        val fetchId = currentTool?.id ?: id
        val requestIds = (listOf(fetchId, id, refId) + currentTool?.aliases.orEmpty()).filter { it.isNotBlank() }.toSet()
        val imageRead = forceImage || currentTool?.hasImage == true || imagePath != null
        fun matches(tool: ToolCall) = (tool.aliases + tool.id).any { it in requestIds }
        val fetchLock = toolImageLoadLock(toolFetchLocks, requestIds.map { "$generation:$it" }.toSet())
        fetchLock.withLock {
        if (mutable.value.selected?.id != threadId || generation != sessionGeneration) return@withLock
        if (forceImage) fetchedTools.removeAll(requestIds)
        if (requestIds.any { it in fetchedTools }) return@withLock
        fetchedTools.addAll(requestIds)
        if (imageRead) {
            markToolImageLoad(toolImageLoads, requestIds, ToolImageLoadStatus.LOADING)
            mutable.update { state -> state.copy(messages = state.messages.map { message ->
                if (message.tools.any(::matches)) withToolImageLoads(message, toolImageLoads) else message
            }) }
        }
        try {
            val result = toolImageReads.withPermit { api.command(JSONObject().put("command", "tool_content").put("thread_id", threadId).put("message_index", ref.first).put("tool_index", ref.second)) }
            val tool = result.optJSONObject("tool") ?: JSONObject()
            val images = mutableListOf<String>()
            if (imageRead || tool.optBoolean("has_image")) collectImages(tool.opt("output"), images, 0)
            imagePath?.let { path -> runCatching {
                api.preview(JSONObject().put("thread_id", threadId).put("path", path).put("message_index", ref.first).put("tool_index", ref.second))?.let { images.add(it) }
            } }
            if (imageRead) require(images.isNotEmpty()) { "服务器未返回工具图片" }
            if (generation == sessionGeneration && imageRead) markToolImageLoad(toolImageLoads, requestIds, ToolImageLoadStatus.LOADED)
            if (mutable.value.selected?.id == threadId && generation == sessionGeneration) mutable.update { s -> s.copy(messages = s.messages.map { m ->
              val updated = m.copy(
                images = if (m.tools.any(::matches)) (m.images + images).distinct() else m.images,
                tools = m.tools.map { t -> if (matches(t)) t.copy(input = result.string("display_input", tool.opt("input")?.toString().orEmpty()).take(64000), output = tool.opt("output")?.toString().orEmpty().takeLast(64000)) else t })
              if (imageRead && m.tools.any(::matches)) withToolImageLoads(updated, toolImageLoads) else updated
            }) }
        } catch (e: Exception) {
            if (generation == sessionGeneration) fetchedTools.removeAll(requestIds); if (e is CancellationException) throw e
            if (generation == sessionGeneration && imageRead) markToolImageLoad(toolImageLoads, requestIds, ToolImageLoadStatus.FAILED)
            if (mutable.value.selected?.id == threadId && generation == sessionGeneration && imageRead) mutable.update { s -> s.copy(messages = s.messages.map { m ->
                if (m.tools.any(::matches)) withToolImageLoads(m, toolImageLoads) else m
            }) }
            if (mutable.value.selected?.id == threadId && generation == sessionGeneration && !imageRead) error(e)
        }
        }
    } }
    private suspend fun loadApprovals() {
        val generation = serverRequestGeneration
        val approvals = BridgeParser.approvals(api.command(JSONObject().put("command", "pending"))).map { it.copy(connectionGeneration = generation) }
        if (generation != serverRequestGeneration) return
        mutable.update { it.copy(approvals = approvals) }
        loadServerRequests()
        loadAsyncQuestions()
    }
    private fun invalidateServerRequests() {
        serverRequestGeneration++
        serverRequestProtocol = 0
        approvalOperations.clear()
        mutable.update { it.copy(approvals = emptyList(), serverRequests = emptyList(), respondingServerRequestIds = emptySet(), serverRequestErrors = emptyMap(),
            asyncQuestions = emptyList(), respondingAsyncQuestionIds = emptySet(), asyncQuestionErrors = emptyMap()) }
    }
    private suspend fun loadServerRequests() = serverRequestsLock.withLock {
        val generation = serverRequestGeneration
        if (serverRequestProtocol == 0) {
            val version = api.command(JSONObject().put("command", "status")).optInt("protocol_version")
            require(version > 0) { "服务器未返回协议版本" }
            if (generation != serverRequestGeneration) return@withLock
            serverRequestProtocol = version
        }
        if (serverRequestProtocol < 43) return@withLock
        val requests = ServerRequests.parse(api.command(JSONObject().put("command", "server_requests")))
            .map { it.copy(connectionGeneration = generation) }
        if (generation != serverRequestGeneration) return@withLock
        mutable.update { old -> old.copy(serverRequests = requests,
            serverRequestErrors = old.serverRequestErrors.filterKeys { id -> requests.any { it.id == id } }) }
    }
    fun respondServerRequest(prompt: ServerPrompt, response: JSONObject) { viewModelScope.launch {
        val current = mutable.value
        if (!current.connected || prompt.connectionGeneration != serverRequestGeneration || prompt !in current.serverRequests || prompt.id in current.respondingServerRequestIds) return@launch
        mutable.update { it.copy(respondingServerRequestIds = it.respondingServerRequestIds + prompt.id,
            serverRequestErrors = it.serverRequestErrors - prompt.id) }
        try {
            // Answers live only in this request. Never store, log or automatically retry them.
            api.command(ServerRequests.command(prompt, response))
            if (prompt.connectionGeneration == serverRequestGeneration) mutable.update { old -> old.copy(
                serverRequests = old.serverRequests.filterNot { it == prompt }, serverRequestErrors = old.serverRequestErrors - prompt.id) }
            runCatching { loadServerRequests() }
            scheduleRefresh()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (prompt.connectionGeneration == serverRequestGeneration) mutable.update { old -> old.copy(
                serverRequestErrors = old.serverRequestErrors + (prompt.id to if (failure is IOException) "回复发送失败，请检查连接后重试" else failure.message.orEmpty().ifBlank { "回复未完成，请重试" })) }
            runCatching { loadServerRequests() }
        } finally {
            if (prompt.connectionGeneration == serverRequestGeneration) mutable.update { it.copy(respondingServerRequestIds = it.respondingServerRequestIds - prompt.id) }
        }
    } }
    private suspend fun loadAsyncQuestions() {
        val id = mutable.value.selected?.id ?: return
        val generation = serverRequestGeneration
        val prompts = ServerRequests.parseAsync(api.command(JSONObject().put("command", "async_questions").put("thread_id", id)), id)
            .map { it.copy(connectionGeneration = generation) }
        if (generation != serverRequestGeneration || mutable.value.selected?.id != id) return
        mutable.update { old -> old.copy(asyncQuestions = old.asyncQuestions.filterNot { it.threadId == id } + prompts,
            asyncQuestionErrors = old.asyncQuestionErrors.filterKeys { key -> key !in old.asyncQuestions.filter { it.threadId == id }.map { it.itemId } || prompts.any { it.itemId == key } }) }
    }
    fun respondAsyncQuestion(prompt: AsyncQuestionPrompt, answers: List<String>, mode: String) { viewModelScope.launch {
        val current = mutable.value
        if (!current.connected || current.readOnly || current.selected?.id != prompt.threadId || prompt.connectionGeneration != serverRequestGeneration ||
            prompt !in current.asyncQuestions || prompt.itemId in current.respondingAsyncQuestionIds) return@launch
        mutable.update { it.copy(respondingAsyncQuestionIds = it.respondingAsyncQuestionIds + prompt.itemId,
            asyncQuestionErrors = it.asyncQuestionErrors - prompt.itemId) }
        try {
            require(mode in listOf("steer", "new_turn")) { "请选择回复方式" }
            val live = readThread(prompt.threadId)
            val status = live.optJSONObject("status")?.string("type")
            require(status in listOf("active", "idle", "notLoaded")) { "无法确认会话状态，请刷新后重试" }
            val activity = if (status == "active") api.command(JSONObject().put("command", "thread_activity").put("thread_id", prompt.threadId)).optJSONObject("activity") else null
            val activeId = activity?.string("active_turn_id")?.takeIf { it.isNotBlank() }
            require(status != "active" || activeId != null) { "会话正在执行，暂时无法确认可跟进的任务" }
            require(mutable.value.connected && !mutable.value.readOnly && prompt.connectionGeneration == serverRequestGeneration && prompt in mutable.value.asyncQuestions && mutable.value.selected?.id == prompt.threadId) { "会话连接或权限已变化，请刷新问题后回答" }
            val command = ServerRequests.replyAsync(prompt, answers, activeId, allowNewTurn = mode == "new_turn")
            require(command.string("mode") == mode) { "会话状态已变化，请刷新后选择回复方式" }
            api.command(command)
            if (prompt.connectionGeneration == serverRequestGeneration) mutable.update { old -> old.copy(
                asyncQuestions = old.asyncQuestions.filterNot { it == prompt }, asyncQuestionErrors = old.asyncQuestionErrors - prompt.itemId) }
            scheduleRefresh()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (prompt.connectionGeneration == serverRequestGeneration) mutable.update { old -> old.copy(
                asyncQuestionErrors = old.asyncQuestionErrors + (prompt.itemId to if (failure is IOException) "回复发送失败，请检查连接后重试" else failure.message.orEmpty().ifBlank { "回复未完成，请重试" })) }
            runCatching { loadAsyncQuestions() }
        } finally {
            if (prompt.connectionGeneration == serverRequestGeneration) mutable.update { it.copy(respondingAsyncQuestionIds = it.respondingAsyncQuestionIds - prompt.itemId) }
        }
    } }
    fun approval(id: Long, accept: Boolean) { mutable.value.approvals.firstOrNull { it.id == id }?.let { approvalRequest(it, accept) } }
    fun approvalRequest(approval: Approval, accept: Boolean) { viewModelScope.launch {
        val key = approval.connectionGeneration to approval.id
        if (!mutable.value.connected || approval.connectionGeneration != serverRequestGeneration || approval !in mutable.value.approvals || !approvalOperations.add(key)) return@launch
        try { api.command(JSONObject().put("command", if (accept) "approve" else "decline").put("id", approval.id)); loadApprovals() }
        catch (e: Exception) { if (e is CancellationException) throw e; error(e); runCatching { loadApprovals() } }
        finally { approvalOperations.remove(key) }
    } }
    fun stop() { viewModelScope.launch {
        val current = mutable.value
        val id = current.selected?.id ?: return@launch
        try {
            val body = JSONObject().put("command", "interrupt").put("thread_id", id)
            current.activeTurnId?.let { body.put("turn_id", it) }
            api.command(body); scheduleRefresh()
        } catch (e: Exception) { if (e is CancellationException) throw e; error(e) }
    } }
    fun attach(uris: List<Uri>) {
        mutable.update { s ->
            val added = uris.map { Attachment(it.toString(), "图片.jpg", "image/jpeg") }
            s.copy(attachments = (s.attachments + added).distinctBy { it.uri }.take(6))
        }
    }
    fun removeAttachment(uri: String) { mutable.update { it.copy(attachments = it.attachments.filterNot { a -> a.uri == uri }) } }
    private suspend fun encodeImage(uri: String): String = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, Uri.parse(uri))) { decoder, info, _ ->
            val scale = minOf(1.0, 1600.0 / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        val bytes = ByteArrayOutputStream().use { output -> flattened.compress(Bitmap.CompressFormat.JPEG, 82, output); output.toByteArray() }
        bitmap.recycle(); flattened.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    fun send(text: String, mode: String = "steer") { viewModelScope.launch { sendNow(text, mode) } }
    private suspend fun sendNow(text: String, mode: String) {
        val current = mutable.value
        val id = current.selected?.id ?: return
        val generation = sessionGeneration
        if (current.sending || current.readOnly || (text.isBlank() && current.attachments.isEmpty())) return
        mutable.update { it.copy(sending = true, error = null) }
        val fingerprint = id + "\n" + text + "\n" + current.attachments.joinToString { it.uri } + "\n" + mode
        val submission = retrySubmission?.takeIf { it.first == fingerprint }?.second ?: UUID.randomUUID().toString()
        try {
            val attachments = JSONArray()
            for (a in current.attachments) attachments.put(JSONObject().put("type", "image").put("name", a.name).put("url", encodeImage(a.uri)))
            require(attachments.toString().toByteArray().size <= 10 * 1024 * 1024) { "图片过大，请减少附件后重试" }
            val body = JSONObject().put("thread_id", id)
                .put("text", text).put("submission_id", submission).put("attachments", attachments)
            retrySubmission = Triple(fingerprint, submission, text)
            val receipt = ComposerSubmission.submit(body, mode, api::command)
            retrySubmission = null
            pendingRevision++
            mutable.update { old ->
                val sameScope = old.selected?.id == id && generation == sessionGeneration
                val entry = PendingInput(receipt.string("pending_id", submission), text, receipt.string("status"),
                    if (mode == "queue") "queue" else "steer", threadId = id, source = "client_receipt",
                    startError = receipt.optJSONObject("start_error")?.string("message"), partial = receipt.optBoolean("partial"))
                old.copy(attachments = if (sameScope && old.attachments == current.attachments) emptyList() else old.attachments,
                    pending = if (sameScope) old.pending.filterNot { it.id == entry.id } + entry else old.pending,
                    pendingVerified = if (sameScope) false else old.pendingVerified, error = null,
                    lastSentId = submission, lastSentThreadId = id, lastSentText = text)
            }
            runCatching { refreshSelected() }.onFailure { if (it is CancellationException) throw it; scheduleRefresh() }
        } catch (e: Exception) { if (e is CancellationException) throw e; error(e) }
        finally { mutable.update { it.copy(sending = false) } }
    }
    fun create(project: Project?, prompt: String) { navigationChanged(); viewModelScope.launch {
        if (mutable.value.sending) return@launch
        mutable.update { it.copy(sending = true) }
        try {
            val request = SessionCommands.create(project?.path, prompt, store.preferredServiceTier.first())
            val result = api.command(request)
            val thread = BridgeParser.session(result.getJSONObject("thread"))
            loadSessions(); open(thread)
            if (prompt.isNotBlank() && mutable.value.selected?.id == thread.id) { mutable.update { it.copy(sending = false) }; sendNow(prompt, "steer") }
        } catch (e: Exception) { if (e is CancellationException) throw e; error(e) }
        finally { mutable.update { it.copy(sending = false) } }
    } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun theme(dark: Boolean) { viewModelScope.launch { store.setDarkTheme(dark) } }
    fun clearCache() { viewModelScope.launch { store.clearCache(); mutable.update { it.copy(error = "本地缓存已清理，服务器会话保留") } } }
    private fun error(e: Exception) {
        val message = if (e is IOException) "网络连接失败，请检查网络后重试；任务会继续在服务器执行。" else e.message ?: "操作未完成，请重试"
        mutable.update { it.copy(error = message, loading = false, loadingOlder = false) }
    }
}
