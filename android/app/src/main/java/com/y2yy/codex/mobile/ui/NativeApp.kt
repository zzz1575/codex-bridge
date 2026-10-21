@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.y2yy.codex.mobile.ui

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.y2yy.codex.mobile.data.decodeImageDataUrl
import com.y2yy.codex.mobile.data.AttachmentInputs
import com.y2yy.codex.mobile.data.ServerRequests
import com.y2yy.codex.mobile.data.PendingMessages
import com.y2yy.codex.mobile.model.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

data class NativeActions(
    val onRefresh: () -> Unit,
    val onSelect: (Session) -> Unit,
    val onBack: () -> Unit,
    val onSend: (String, String) -> Unit,
    val onStop: () -> Unit,
    val onApproval: (Long, Boolean) -> Unit,
    val onNew: (Project?, String) -> Unit,
    val onAttach: (List<Uri>) -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    val onOlder: () -> Unit,
    val onClearError: () -> Unit,
    val onTheme: (Boolean) -> Unit,
    val onClearCache: () -> Unit,
    val onTool: (String) -> Unit,
    val onHistoryTurn: (String) -> Unit = {},
    val onRetryImage: (String) -> Unit = {},
    val onLoadModels: () -> Unit = {},
    val onSaveModelSettings: (String, String) -> Unit = { _, _ -> },
    val onSaveComposerSettings: (String, String, String) -> Unit = { model, effort, _ -> onSaveModelSettings(model, effort) },
    val onRenameSession: (String) -> Unit = {},
    val onMoveSession: (String?) -> Unit = {},
    val onClearSessionActionError: () -> Unit = {},
    val onNavigate: () -> Unit = {},
    val onServerResponse: (ServerPrompt, JSONObject) -> Unit = { _, _ -> },
    val onAsyncResponse: (AsyncQuestionPrompt, List<String>, String) -> Unit = { _, _, _ -> },
    val onCancelPending: (String) -> Unit = {},
    val onApprovalRequest: (Approval, Boolean) -> Unit = { approval, accepted -> onApproval(approval.id, accepted) }
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF126D5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5F2E8), onPrimaryContainer = Color(0xFF034C3E),
    secondary = Color(0xFF52665F), surface = Color(0xFFFCFCF9),
    background = Color(0xFFF6F7F3), surfaceVariant = Color(0xFFE9EDE6),
    outlineVariant = Color(0xFFDCE3DA)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D7BB), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF174D3F), onPrimaryContainer = Color(0xFFC1F4E2),
    secondary = Color(0xFFBCCDC4), surface = Color(0xFF171D1A),
    background = Color(0xFF101612), surfaceVariant = Color(0xFF26322C),
    outlineVariant = Color(0xFF3A4940)
)
private val LocalChatFontSize = staticCompositionLocalOf { 15f }

@Composable
fun NativeApp(state: AppUiState, actions: NativeActions) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("native-ui", android.content.Context.MODE_PRIVATE) }
    var chatFontSize by remember { mutableFloatStateOf(preferences.getFloat("chat-font-size", 15f).coerceIn(13f, 21f)) }
    CompositionLocalProvider(LocalChatFontSize provides chatFontSize) {
    MaterialTheme(colorScheme = if (state.darkTheme) DarkColors else LightColors) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var historyRequest by remember { mutableIntStateOf(0) }
        var newDialog by remember { mutableStateOf(false) }
        var stopDialog by remember { mutableStateOf(false) }
        var modelDialog by remember { mutableStateOf(false) }
        var sessionMenu by remember { mutableStateOf(false) }
        var sessionAction by remember { mutableStateOf<String?>(null) }
        var confirmApproval by remember { mutableStateOf<Pair<Approval, Boolean>?>(null) }
        // Form answers are deliberately not saveable, cached or shared with the composer.
        var openServerPrompt by remember { mutableStateOf<String?>(null) }
        var openAsyncPrompt by remember { mutableStateOf<String?>(null) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { openServerPrompt = null; openAsyncPrompt = null; confirmApproval = null } }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(state.connected, state.selected?.id) { openServerPrompt = null; openAsyncPrompt = null; confirmApproval = null }
        LaunchedEffect(state.approvals) { if (confirmApproval?.first !in state.approvals) confirmApproval = null }
        val draftSaver = remember {
            mapSaver<SnapshotStateMap<String, String>>(
                save = { it.toMap() },
                restore = { saved -> mutableStateMapOf<String, String>().apply { saved.forEach { (key, value) -> this[key] = value as String } } }
            )
        }
        val drafts = rememberSaveable(saver = draftSaver) { mutableStateMapOf<String, String>() }
        LaunchedEffect(state.lastSentId) {
            if (state.lastSentId.isNotBlank() && drafts[state.lastSentThreadId]?.trim() == state.lastSentText.trim()) {
                drafts[state.lastSentThreadId] = ""
            }
        }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.error) {
            state.error?.let { snackbar.showSnackbar(it, actionLabel = "关闭", duration = SnackbarDuration.Long); actions.onClearError() }
        }
        BackHandler(enabled = state.selected != null) { actions.onBack() }
        LaunchedEffect(state.selected?.id) { modelDialog = false; sessionMenu = false; sessionAction = null }
        val openModels = { modelDialog = true; actions.onLoadModels() }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (state.selected != null) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(state.selected.title.ifBlank { "新会话" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                ConnectionLabel(state.connected, chatStatusLabel(state), Modifier.testTag("chat-status"))
                            }
                        },
                        navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回会话列表") } },
                        actions = {
                            TextButton(onClick = openModels, modifier = Modifier.height(48.dp).widthIn(min = 84.dp, max = 104.dp).testTag("composer-settings-button").semantics { contentDescription = "选择模型和思考强度"; stateDescription = "响应速度：${serviceTierName(state.composerServiceTier)}" }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(serviceTierName(state.composerServiceTier), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box {
                                IconButton(onClick = { sessionMenu = true }) { Icon(Icons.Outlined.MoreVert, "会话选项") }
                                DropdownMenu(expanded = sessionMenu, onDismissRequest = { sessionMenu = false }) {
                                    DropdownMenuItem(text = { Text("查看历史消息") }, leadingIcon = { Icon(Icons.Outlined.History, null) }, modifier = Modifier.semantics { contentDescription = "查看历史消息" }, onClick = { sessionMenu = false; historyRequest++ })
                                    DropdownMenuItem(text = { Text("刷新会话") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, modifier = Modifier.semantics { contentDescription = "刷新会话" }, onClick = { sessionMenu = false; actions.onRefresh() })
                                    DropdownMenuItem(text = { Text("重命名会话") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, enabled = !state.sessionActionBusy, onClick = { sessionMenu = false; actions.onClearSessionActionError(); sessionAction = "rename" })
                                    DropdownMenuItem(text = { Text("移动到其他项目") }, leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) }, enabled = !state.sessionActionBusy, onClick = { sessionMenu = false; actions.onClearSessionActionError(); sessionAction = "move" })
                                }
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(listOf("会话", "任务进度", "设置")[tab], fontWeight = FontWeight.Bold)
                                if (tab != 2) ConnectionLabel(state.connected, state.connectionText)
                            }
                        },
                        actions = {
                            IconButton(onClick = actions.onRefresh) { Icon(Icons.Outlined.Refresh, "刷新") }
                            if (tab == 0) IconButton(onClick = { actions.onNavigate(); newDialog = true }) { Icon(Icons.Outlined.AddComment, "新建会话") }
                        }
                    )
                }
            },
            bottomBar = {
                if (state.selected == null) NavigationBar {
                    val labels = listOf("会话", "任务", "设置")
                    val icons = listOf(Icons.Outlined.Forum, Icons.Outlined.Timeline, Icons.Outlined.Settings)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index, onClick = { actions.onNavigate(); tab = index },
                            icon = {
                                if (index == 1 && (state.approvals.isNotEmpty() || state.serverRequests.isNotEmpty() || state.asyncQuestions.isNotEmpty())) BadgedBox(badge = { Badge { Text("${state.approvals.size + state.serverRequests.size + state.asyncQuestions.size}") } }) { Icon(icons[index], label) }
                                else Icon(icons[index], label)
                            }, label = { Text(label) }
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets.safeDrawing
        ) { padding ->
            // Scaffold already reserves safeDrawing (including IME) space. Mark it
            // consumed so nested imePadding does not subtract the keyboard twice.
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when {
                    state.selected != null -> ChatPage(
                        state, actions, drafts[state.selected.id].orEmpty(),
                        onDraft = { drafts[state.selected.id] = it },
                        onSend = actions.onSend,
                        onStop = { stopDialog = true },
                        onApproval = { approval, accepted -> confirmApproval = approval to accepted },
                        onServerPrompt = { openServerPrompt = serverPromptKey(it) },
                        onAsyncPrompt = { openAsyncPrompt = asyncPromptKey(it) },
                        historyRequest = historyRequest
                    )
                    tab == 0 -> SessionsPage(state, actions, onNew = { actions.onNavigate(); newDialog = true })
                    tab == 1 -> TasksPage(state, actions, onApproval = { approval, accepted -> confirmApproval = approval to accepted }, onServerPrompt = { openServerPrompt = serverPromptKey(it) }, onAsyncPrompt = { openAsyncPrompt = asyncPromptKey(it) })
                    else -> SettingsPage(state, actions, chatFontSize) { value -> chatFontSize = value; preferences.edit().putFloat("chat-font-size", value).apply() }
                }
            }
        }
        if (newDialog) NewSessionDialog(state.projects, onDismiss = { newDialog = false }) { project, prompt ->
            newDialog = false; actions.onNew(project, prompt)
        }
        if (stopDialog) AlertDialog(
            onDismissRequest = { stopDialog = false }, title = { Text("停止当前任务？") },
            text = { Text("会话记录会保留。已完成的文件或数据修改不会自动撤销。") },
            confirmButton = { TextButton(onClick = { stopDialog = false; actions.onStop() }) { Text("停止任务", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { stopDialog = false }) { Text("继续执行") } }
        )
        if (modelDialog && state.selected != null) ModelSettingsDialog(state, actions, onDismiss = { modelDialog = false })
        if (sessionAction != null && state.selected != null) SessionActionDialog(sessionAction!!, state, actions, onDismiss = { sessionAction = null })
        if (state.connected) state.serverRequests.firstOrNull { serverPromptKey(it) == openServerPrompt }?.let { prompt ->
            key(serverPromptKey(prompt)) {
                ServerRequestDialog(prompt, prompt.id in state.respondingServerRequestIds, state.serverRequestErrors[prompt.id],
                    onDismiss = { openServerPrompt = null }, onResponse = { actions.onServerResponse(prompt, it) })
            }
        }
        if (state.connected) state.asyncQuestions.firstOrNull { asyncPromptKey(it) == openAsyncPrompt }?.let { prompt ->
            key(asyncPromptKey(prompt)) {
                AsyncQuestionDialog(prompt, asyncReplyMode(prompt, state), prompt.itemId in state.respondingAsyncQuestionIds, state.asyncQuestionErrors[prompt.itemId],
                    onDismiss = { openAsyncPrompt = null }, onResponse = { answers, mode -> actions.onAsyncResponse(prompt, answers, mode) })
            }
        }
        confirmApproval?.takeIf { (approval, _) -> state.connected && approval in state.approvals && (state.selected == null || state.selected.id == approval.threadId) }?.let { (approval, accepted) ->
            AlertDialog(
                modifier = Modifier.testTag("approval-confirmation"),
                onDismissRequest = { confirmApproval = null },
                title = { Text(if (accepted) "批准这次操作？" else "拒绝这次操作？") },
                text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(approval.reason.ifBlank { "请确认本次权限请求" })
                    if (approval.details.isNotBlank()) { Spacer(Modifier.height(12.dp)); SelectionBlock(approval.details) }
                } },
                confirmButton = { TextButton(onClick = { confirmApproval = null; actions.onApprovalRequest(approval, accepted) }, modifier = Modifier.testTag("approval-confirm-submit")) { Text(if (accepted) "仅批准本次" else "拒绝操作") } },
                dismissButton = { TextButton(onClick = { confirmApproval = null }) { Text("取消") } }
            )
        }
    }
    }
}

@Composable
private fun ConnectionLabel(connected: Boolean, label: String, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).background(if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, CircleShape))
        Text(label.ifBlank { if (connected) "已连接" else "连接中" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SessionsPage(state: AppUiState, actions: NativeActions, onNew: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var projectPath by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(state.sessions, search, projectPath) {
        state.sessions.filter { (projectPath == null || it.projectPath == projectPath) && (search.isBlank() || it.title.contains(search, true) || it.projectPath.contains(search, true)) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search, onValueChange = { search = it }, placeholder = { Text("搜索会话或项目") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true,
            trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, "清除搜索") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(18.dp)
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = projectPath == null, onClick = { projectPath = null }, label = { Text("全部") })
            state.projects.forEach { project -> FilterChip(selected = projectPath == project.path, onClick = { projectPath = project.path }, label = { Text(project.name) }) }
        }
        if (state.fromCache) Text("正在显示本地缓存，连接后自动更新", modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            state.loading && state.sessions.isEmpty() -> LoadingState("正在同步会话…")
            filtered.isEmpty() -> EmptyState(Icons.Outlined.Forum, if (search.isNotBlank()) "没有匹配的会话" else "开始你的第一个任务", if (search.isNotBlank()) "换一个关键词试试" else "连接服务器，在手机上查看代码任务与执行进度", "新建会话", onNew)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { session -> SessionCard(session, state.projects, actions.onSelect) }
            }
        }
    }
}

@Composable
private fun SessionCard(session: Session, projects: List<Project>, onSelect: (Session) -> Unit) {
    val project = projects.firstOrNull { it.path == session.projectPath }?.name ?: session.projectPath.substringAfterLast('/').ifBlank { "独立会话" }
    ElevatedCard(onClick = { onSelect(session) }, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(session.title.ifBlank { "新会话" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(project, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(session.status)
                    if (session.updatedAt.isNotBlank()) Text(displayTime(session.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp).align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val active = isRunning(status)
    val error = status.lowercase().contains("fail") || status.lowercase().contains("error")
    val color = if (error) MaterialTheme.colorScheme.error else if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (active) CircularProgressIndicator(Modifier.size(10.dp), color = color, strokeWidth = 1.5.dp)
        else Box(Modifier.size(5.dp).background(color.copy(alpha = .7f), CircleShape))
        Text(statusLabel(status), color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ChatPage(state: AppUiState, actions: NativeActions, draft: String, onDraft: (String) -> Unit, onSend: (String, String) -> Unit, onStop: () -> Unit, onApproval: (Approval, Boolean) -> Unit, onServerPrompt: (ServerPrompt) -> Unit, onAsyncPrompt: (AsyncQuestionPrompt) -> Unit, historyRequest: Int) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var mode by remember(state.selected?.id) { mutableStateOf("steer") }
    LaunchedEffect(state.lastSentId, state.selected?.id) {
        if (state.lastSentId.isNotBlank() && state.lastSentThreadId == state.selected?.id) mode = "steer"
    }
    val nearBottom by remember { derivedStateOf { !listState.canScrollForward } }
    var follow by rememberSaveable(state.selected?.id) { mutableStateOf(true) }
    var handledHistoryRequest by remember(state.selected?.id) { mutableIntStateOf(historyRequest) }
    LaunchedEffect(historyRequest, state.selected?.id) {
        if (historyRequest != handledHistoryRequest) {
            handledHistoryRequest = historyRequest
            follow = false
            if (listState.layoutInfo.totalItemsCount > 0) listState.scrollToItem(0)
            if (state.hasOlder && !state.loadingOlder) actions.onOlder()
        }
    }
    LaunchedEffect(listState.isScrollInProgress, nearBottom) {
        if (listState.isScrollInProgress) follow = nearBottom
        else if (nearBottom) follow = true
    }
    val contentVersion = state.messages.lastOrNull()?.let { "${it.id}:${it.text.length}:${it.tools.sumOf { tool -> tool.output.length + tool.status.length }}" }
    LaunchedEffect(state.selected?.id, contentVersion) {
        if (follow && !state.loadingOlder && !listState.isScrollInProgress) {
            withFrameNanos { }
            if (follow && !listState.isScrollInProgress && listState.layoutInfo.totalItemsCount > 0) listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }
    val historyGroups = remember(state.messages) {
        state.messages.filter { it.deferred && it.turnId.isNotBlank() }.groupBy { it.turnId }
    }
    val rows = remember(state.messages) { groupChatRows(state.messages) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loading && state.messages.isEmpty()) LoadingState("正在加载对话…")
            else if (state.messages.isEmpty() && !state.running) EmptyState(Icons.Outlined.AutoAwesome, "开始一个任务", "发送消息或截图，让 Codex 在服务器上执行", null, {})
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (state.hasOlder || state.loadingOlder) item(key = "older") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = { follow = false; actions.onOlder() }, enabled = !state.loadingOlder) { if (state.loadingOlder) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.History, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.loadingOlder) "正在加载" else "加载更早消息") } }
                }
                items(rows, key = { it.id }) { row ->
                    val message = row.messages.first()
                    val deferred = historyGroups[message.turnId]
                    if (message.deferred && deferred != null && deferred.firstOrNull()?.id == message.id) {
                        HistoryTurnCard(
                            message.turnId,
                            deferred.sumOf { it.deferredToolCount },
                            deferred.sumOf { it.deferredFileCount },
                            message.turnId in state.loadingHistoryTurns,
                            state.historyErrors[message.turnId],
                            actions.onHistoryTurn
                        )
                    }
                    if (!message.deferred || message.text.isNotBlank() || message.images.isNotEmpty() || message.tools.isNotEmpty() || message.imagePending || !message.imageError.isNullOrBlank()) {
                        if (message.deferred && deferred?.firstOrNull()?.id == message.id) Spacer(Modifier.height(10.dp))
                        if (row.toolOnly && row.tools.size > 1) ToolGroupCard(row.id, row.tools, actions.onTool)
                        else MessageView(message, actions.onTool, actions.onRetryImage)
                    }
                }
                if (state.running) item(key = "activity") { ActivityCard(state) }
                item(key = "end") { Spacer(Modifier.height(4.dp)) }
            }
            if (!nearBottom && state.messages.isNotEmpty()) SmallFloatingActionButton(onClick = { follow = true; scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Outlined.ArrowDownward, "滚动到最新消息") }
        }
        val relevantApprovals = state.approvals.filter { it.threadId == state.selected?.id }
        val relevantPrompts = state.serverRequests.filter { it.threadId == state.selected?.id }
        val relevantAsync = state.asyncQuestions.filter { it.threadId == state.selected?.id }
        if (relevantApprovals.isNotEmpty() || relevantPrompts.isNotEmpty() || relevantAsync.isNotEmpty()) Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            relevantPrompts.forEach { ServerPromptCard(it, state.connected, it.id in state.respondingServerRequestIds, onServerPrompt) }
            relevantAsync.forEach { AsyncQuestionCard(it, state.connected, it.itemId in state.respondingAsyncQuestionIds, onAsyncPrompt) }
            relevantApprovals.forEach { ApprovalCard(it, onApproval) }
        }
        if (state.pending.isNotEmpty() || state.pendingSyncError != null) PendingList(state, actions)
        Composer(state, draft, onDraft, mode, { mode = it }, onSend, onStop, actions)
    }
}

@Composable
private fun HistoryTurnCard(turnId: String, toolCount: Int, fileCount: Int, loading: Boolean, error: String?, onLoad: (String) -> Unit) {
    LaunchedEffect(turnId) {
        if (!loading && error.isNullOrBlank()) onLoad(turnId)
    }
    OutlinedCard(shape = RoundedCornerShape(16.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.History, null, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (loading) "正在加载历史执行过程" else "历史执行过程", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            val summary = buildList {
                if (toolCount > 0) add("$toolCount 次工具调用")
                if (fileCount > 0) add("$fileCount 项文件操作")
            }.joinToString(" · ")
            Text(summary.ifBlank { "展开此前的执行步骤和工具结果" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!error.isNullOrBlank()) {
                Text("加载失败：$error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onLoad(turnId) }, enabled = !loading, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.height(32.dp)) { Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("重试加载历史过程") }
            }
        }
    }
}

@Composable
private fun MessageView(message: ChatMessage, onTool: (String) -> Unit, onRetryImage: (String) -> Unit) {
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().testTag("chat-message:${message.id}"), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (!user) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            Box(Modifier.size(25.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp)) }
            Text(if (message.role == "assistant") "Codex" else roleLabel(message.role), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 18.dp, topEnd = if (user) 5.dp else 18.dp, bottomStart = if (user) 18.dp else 5.dp, bottomEnd = 18.dp), modifier = Modifier.fillMaxWidth(if (user) .92f else 1f)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                message.images.forEach { image -> MessageImage(image, message.id, onRetryImage) }
                if (!message.imageError.isNullOrBlank()) {
                    if (message.images.isEmpty()) ImageFailure(message.id, onRetryImage, message.imageError)
                    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.BrokenImage, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp))
                        Text("部分图片加载失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRetryImage(message.id) }) { Text("重试加载图片") }
                    }
                }
                else if (message.imagePending && message.images.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Text("正在加载图片", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (message.text.isNotBlank()) MarkdownText(message.text, user)
                if (message.tools.size > 1) ToolGroupCard("message:${message.id}", message.tools, onTool)
                else message.tools.forEach { ToolView(it, onTool) }
                if (message.streaming) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp); Text("正在生成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (message.text.isNotBlank()) Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }, contentPadding = PaddingValues(horizontal = 5.dp), modifier = Modifier.height(32.dp)) { Icon(Icons.Outlined.ContentCopy, "复制消息", modifier = Modifier.size(13.dp)); Spacer(Modifier.width(5.dp)); Text("复制", style = MaterialTheme.typography.labelSmall) }
            val code = remember(message.text) { Regex("```[^\\n]*\\n([\\s\\S]*?)```").findAll(message.text).map { it.groupValues[1].trimEnd() }.toList() }
            code.forEachIndexed { index, value -> TextButton(onClick = { clipboard.setText(AnnotatedString(value)) }, contentPadding = PaddingValues(horizontal = 5.dp), modifier = Modifier.height(32.dp)) { Text(if (code.size == 1) "复制代码" else "复制代码 ${index + 1}", style = MaterialTheme.typography.labelSmall) } }
        }
    }
}

@Composable
private fun MessageImage(image: String, messageId: String, onRetryImage: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissInput() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    var failed by remember(image) { mutableStateOf(false) }
    var coilLoading by remember(image) { mutableStateOf(true) }
    var loaded by remember(image) { mutableStateOf(false) }
    var retryCount by remember(image) { mutableIntStateOf(0) }
    var ratio by remember(image) { mutableFloatStateOf(1.5f) }
    var failureReason by remember(image) { mutableStateOf<String?>(null) }
    var preview by remember(image) { mutableStateOf(false) }
    val context = LocalContext.current
    val source by produceState(initialValue = ImageSource(null, true), key1 = image, key2 = retryCount) {
        value = ImageSource(null, true)
        val model = if (image.startsWith("data:image/", ignoreCase = true)) {
            withContext(Dispatchers.Default) { decodeImageDataUrl(image) }
        } else image
        value = ImageSource(model, false)
    }
    val request = remember(source.model, retryCount, context) {
        // A fixed decoding target avoids request-size loops when the thumbnail learns its ratio.
        ImageRequest.Builder(context).data(source.model).size(1600).build()
    }
    val retry: (String) -> Unit = { id ->
        failed = false; coilLoading = true; loaded = false; failureReason = null; retryCount++
        onRetryImage(id)
    }
    LaunchedEffect(source.loading, coilLoading, failed, retryCount) {
        if (!source.loading && source.model != null && coilLoading && !failed) {
            delay(25_000)
            failureReason = "图片加载超时，请重试"
            failed = true; coilLoading = false
        }
    }
    if (source.loading) ImageLoading(Modifier.fillMaxWidth().height(96.dp))
    else if (source.model == null || failed) ImageFailure(messageId, retry, failureReason)
    else BoxWithConstraints(Modifier.fillMaxWidth()) {
        val thumbnailHeight = (maxWidth.value / ratio.coerceAtLeast(.05f)).dp.coerceIn(96.dp, 260.dp)
        Box(Modifier.fillMaxWidth().height(thumbnailHeight).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f))) {
            key(retryCount) {
                AsyncImage(
                    model = request, contentDescription = "消息图片", contentScale = ContentScale.Fit,
                    onLoading = { coilLoading = true },
                    onError = { failed = true; coilLoading = false; failureReason = "图片未能读取或解码，请重试" },
                    onSuccess = { result ->
                        failed = false; coilLoading = false; loaded = true
                        val drawable = result.result.drawable
                        if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                    },
                    modifier = Modifier.fillMaxSize().clickable(enabled = loaded && !coilLoading, onClickLabel = "查看大图") { dismissInput(); preview = true }
                )
            }
            if (coilLoading) ImageLoading(Modifier.fillMaxSize())
            else Icon(Icons.Outlined.ZoomIn, "点按查看大图", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .85f), CircleShape).padding(5.dp).size(17.dp))
        }
    }
    if (preview && source.model != null) ImagePreview(source.model!!, onDismiss = { preview = false; dismissInput() }, onRetry = { retry(messageId) })
}

private data class ImageSource(val model: Any?, val loading: Boolean)

@Composable
private fun ImageLoading(modifier: Modifier) {
    Column(modifier.testTag("image-loading"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text("正在加载图片", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ImagePreview(model: Any, onDismiss: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    var loading by remember(model) { mutableStateOf(true) }
    var failed by remember(model) { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 5f)
        val maxX = area.width * (zoom - 1f) / 2f
        val maxY = area.height * (zoom - 1f) / 2f
        offset = Offset((offset.x + panChange.x).coerceIn(-maxX, maxX), (offset.y + panChange.y).coerceIn(-maxY, maxY))
    }
    val request = remember(model, context) { ImageRequest.Builder(context).data(model).size(2400).build() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                AsyncImage(model = request, contentDescription = "图片大图预览", contentScale = ContentScale.Fit,
                    onLoading = { loading = true }, onError = { loading = false; failed = true }, onSuccess = { loading = false; failed = false },
                    modifier = Modifier.fillMaxSize().onSizeChanged { area = it }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y }.transformable(transform))
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                if (failed) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("图片加载失败", color = Color.White)
                    TextButton(onClick = { onDismiss(); onRetry() }) { Text("重试加载图片", color = Color.White) }
                }
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭图片预览", tint = Color.White) }
                    Text("图片预览", color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("重置", color = Color.White) }
                }
                Text("双指缩放查看细节", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

@Composable
private fun ImageFailure(messageId: String, onRetryImage: (String) -> Unit, reason: String? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .5f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Outlined.BrokenImage, null, modifier = Modifier.size(18.dp))
                Text("历史图片加载失败", style = MaterialTheme.typography.labelLarge)
            }
            if (!reason.isNullOrBlank()) Text(reason, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { onRetryImage(messageId) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) { Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("重试加载图片") }
        }
    }
}

@Composable
private fun MarkdownText(text: String, user: Boolean = false) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.builder(context).usePlugin(TablePlugin.create(context)).usePlugin(StrikethroughPlugin.create()).usePlugin(LinkifyPlugin.create()).build() }
    val color = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val link = MaterialTheme.colorScheme.primary
    val fontSize = LocalChatFontSize.current
    AndroidView(
        factory = { TextView(it).apply { textSize = 15f; setTextIsSelectable(true); setLineSpacing(4f * resources.displayMetrics.density, 1f); setBackgroundColor(AndroidColor.TRANSPARENT); setPadding(0, 0, 0, 0) } },
        update = { view -> view.textSize = fontSize; view.setTextColor(color.toArgb()); view.setLinkTextColor(link.toArgb()); if (view.tag != text) { markwon.setMarkdown(view, text); view.tag = text } },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ToolView(tool: ToolCall, onTool: (String) -> Unit) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded; if (expanded) onTool(tool.id) }.padding(11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(17.dp))
                Text(tool.name.ifBlank { "工具调用" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(tool.status)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "折叠工具详情" else "展开工具详情", modifier = Modifier.size(19.dp))
            }
            if (expanded) Column(Modifier.padding(start = 11.dp, end = 11.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tool.input.isNotBlank()) { Text("命令 / 输入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); SelectionBlock(tool.input) }
                if (tool.output.isNotBlank()) { Text("执行结果", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); SelectionBlock(tool.output) }
                if (tool.input.isBlank() && tool.output.isBlank()) Text("正在获取详情；部分工具没有详细输出", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ToolGroupCard(groupId: String, tools: List<ToolCall>, onTool: (String) -> Unit) {
    var expanded by rememberSaveable(groupId) { mutableStateOf(false) }
    val running = tools.count { isRunning(it.status) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f), shape = RoundedCornerShape(14.dp), modifier = Modifier.testTag("tool-group")) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.semantics { contentDescription = if (expanded) "收起工具调用" else "展开工具调用" }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(18.dp))
                Text("工具调用 · ${tools.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (running > 0) Text("$running 项执行中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, modifier = Modifier.size(20.dp))
            }
            if (expanded) Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tools.forEach { ToolView(it, onTool) }
            }
        }
    }
}

@Composable
private fun SelectionBlock(value: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
    }
}

@Composable
private fun ActivityCard(state: AppUiState) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.running) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Info, null, modifier = Modifier.size(17.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(state.activity.ifBlank { "正在执行任务…" }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AsyncQuestionCard(prompt: AsyncQuestionPrompt, connected: Boolean, busy: Boolean, onOpen: (AsyncQuestionPrompt) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("async-prompt:${prompt.itemId}"), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .4f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Codex 有问题需要你回答", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(prompt.questions.firstOrNull()?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FilledTonalButton(onClick = { onOpen(prompt) }, enabled = connected && !busy,
                modifier = Modifier.semantics { contentDescription = "回答会话问题:${prompt.itemId}" }) { Text(if (busy) "正在提交" else "填写回答") }
        }
    }
}

private fun asyncPromptKey(prompt: AsyncQuestionPrompt): String =
    "${prompt.connectionGeneration}:${prompt.threadId}:${prompt.turnId}:${prompt.itemId}"

private fun asyncReplyMode(prompt: AsyncQuestionPrompt, state: AppUiState): String? {
    val selected = state.selected?.id == prompt.threadId
    if (selected && state.readOnly) return null
    val active = prompt.threadId in state.activeSessionIds || (selected && state.running)
    if (!active) return "new_turn"
    return if (selected && prompt.turnId.isNotBlank() && state.activeTurnId == prompt.turnId) "steer" else null
}

@Composable
private fun AsyncQuestionDialog(prompt: AsyncQuestionPrompt, mode: String?, busy: Boolean, error: String?, onDismiss: () -> Unit, onResponse: (List<String>, String) -> Unit) {
    val text = remember { mutableStateMapOf<Int, String>() }
    val choices = remember { mutableStateMapOf<Int, String>() }
    var submitted by remember { mutableStateOf(false) }
    var observedBusy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(busy, error) {
        if (submitted && busy) observedBusy = true
        if (submitted && !busy && (observedBusy || error != null)) { submitted = false; observedBusy = false }
    }
    val answers = prompt.questions.indices.map { index -> text[index]?.takeIf { it.isNotBlank() } ?: choices[index].orEmpty() }
    val valid = mode != null && runCatching { ServerRequests.replyAsync(prompt, answers, if (mode == "steer") prompt.turnId else null, allowNewTurn = mode == "new_turn") }.isSuccess
    fun close() { text.clear(); choices.clear(); localError = null; onDismiss() }
    AlertDialog(modifier = Modifier.testTag("async-question-dialog"), onDismissRequest = { close() }, title = { Text("回答会话问题") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(when (mode) {
                    "steer" -> "回答会跟进当前这一轮任务。"
                    "new_turn" -> "原任务已结束。点击“作为新任务回答”会开始新一轮任务。"
                    else -> "当前有其他任务正在执行，或此会话暂不可写。请打开对应会话确认后再回答。"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                prompt.questions.forEachIndexed { index, question ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(question.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        question.options.forEach { option -> PromptOption(option, "", choices[index] == option, !busy && !submitted) { choices[index] = option; text.remove(index) } }
                        OutlinedTextField(value = text[index].orEmpty(), onValueChange = { text[index] = it; if (it.isNotBlank()) choices.remove(index) },
                            label = { Text(if (question.options.isEmpty()) "回答" else "或填写自己的回答") }, enabled = !busy && !submitted,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().testTag("async-answer:$index"))
                    }
                }
                Text("回答仅在本次表单中保留，关闭后清除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy || submitted) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = valid && !busy && !submitted, onClick = {
            if (!busy && !submitted && mode != null) {
                submitted = true; observedBusy = false; localError = null
                try { onResponse(answers, mode) } catch (_: Exception) { submitted = false; localError = "回答未提交，请检查连接后重试" }
            }
        }) { Text(if (busy || submitted) "正在提交" else when (mode) { "steer" -> "跟进回答"; "new_turn" -> "作为新任务回答"; else -> "暂时无法回答" }) } },
        dismissButton = { TextButton(onClick = { close() }) { Text("关闭表单") } }
    )
}

@Composable
private fun ServerPromptCard(prompt: ServerPrompt, connected: Boolean, busy: Boolean, onOpen: (ServerPrompt) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("server-prompt:${prompt.id}"), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .4f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Outlined.QuestionAnswer, null, modifier = Modifier.size(18.dp))
                Text("需要你的回答 · 任务正在等待", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Text(prompt.title.ifBlank { "Codex 请求补充信息" }, style = MaterialTheme.typography.bodyMedium)
            if (prompt.message.isNotBlank()) Text(prompt.message, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FilledTonalButton(onClick = { onOpen(prompt) }, enabled = connected && !busy,
                modifier = Modifier.semantics { contentDescription = "处理待回答问题:${prompt.id}" }) {
                Text(if (busy) "正在提交" else if (!prompt.supported || prompt.kind == ServerPromptKind.UNSUPPORTED) "查看请求" else "填写回答")
            }
        }
    }
}

private fun serverPromptKey(prompt: ServerPrompt): String =
    "${prompt.connectionGeneration}:${prompt.id}:${prompt.threadId}:${prompt.requestIdentity}"

@Composable
private fun ServerRequestDialog(prompt: ServerPrompt, busy: Boolean, error: String?, onDismiss: () -> Unit, onResponse: (JSONObject) -> Unit) {
    // Never rememberSaveable: answers (including secrets) disappear when this form closes,
    // loses its connection, changes request identity, or the app leaves the foreground.
    val values = remember { mutableStateMapOf<String, String>() }
    val selections = remember { mutableStateMapOf<String, List<String>>() }
    var jsonText by remember { mutableStateOf("") }
    var urlConfirmed by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var observedBusy by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val disabled = busy || submitted
    val supported = prompt.supported && prompt.kind != ServerPromptKind.UNSUPPORTED
    LaunchedEffect(busy, error) {
        if (submitted && busy) observedBusy = true
        if (submitted && !busy && (observedBusy || error != null)) { submitted = false; observedBusy = false }
    }
    fun close() {
        values.clear(); selections.clear(); jsonText = ""; urlConfirmed = false; localError = null
        onDismiss()
    }
    fun submit(response: JSONObject) {
        if (busy || submitted) return
        localError = null; submitted = true; observedBusy = false
        try { onResponse(ServerRequests.validateResponse(prompt, response)) }
        catch (_: Exception) { submitted = false; localError = "回答未提交，请检查格式后重试" }
    }
    val response = if (!supported) null else runCatching {
        when (prompt.kind) {
            ServerPromptKind.USER_INPUT -> ServerRequests.userInput(prompt, prompt.questions.associate { question ->
                question.id to (selections[question.id].orEmpty() + values[question.id].orEmpty().takeIf { it.isNotBlank() }.let { listOfNotNull(it) })
            })
            ServerPromptKind.FORM -> if (prompt.jsonForm) {
                val json = JSONTokener(jsonText)
                val content = json.nextValue()
                require(json.nextClean() == '\u0000')
                ServerRequests.form(prompt, content)
            }
                else ServerRequests.formValues(prompt, values.toMap(), selections.toMap())
            ServerPromptKind.URL -> if (urlConfirmed) ServerRequests.completeUrl(prompt) else null
            ServerPromptKind.UNSUPPORTED -> null
        }
    }.getOrNull()
    AlertDialog(
        modifier = Modifier.testTag("server-request-dialog"), onDismissRequest = { close() },
        title = { Text(prompt.title.ifBlank { "回答 Codex 的问题" }) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (prompt.serverName.isNotBlank()) Text("来自 ${prompt.serverName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (prompt.message.isNotBlank()) Text(prompt.message, style = MaterialTheme.typography.bodyMedium)
                if (!supported) {
                    Text("此请求的格式暂不支持在手机上填写。可以安全取消，或到网页版处理。", style = MaterialTheme.typography.bodyMedium)
                } else when (prompt.kind) {
                    ServerPromptKind.USER_INPUT -> prompt.questions.forEach { question ->
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(question.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            question.options.forEach { option ->
                                PromptOption(option.label, option.description, selections[question.id]?.contains(option.value) == true, !disabled) {
                                    selections[question.id] = listOf(option.value)
                                }
                            }
                            OutlinedTextField(value = values[question.id].orEmpty(), onValueChange = { values[question.id] = it },
                                label = { Text(if (question.options.isEmpty()) question.header.ifBlank { "回答" } else "补充回答（可选）") },
                                enabled = !disabled, singleLine = question.isSecret,
                                visualTransformation = if (question.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (question.isSecret) KeyboardType.Password else KeyboardType.Text),
                                modifier = Modifier.fillMaxWidth().testTag("server-question:${question.id}"))
                        }
                    }
                    ServerPromptKind.FORM -> {
                        if (prompt.jsonForm) {
                            Text("请按照下列格式填写 JSON。只会在你点击提交后发送。", style = MaterialTheme.typography.bodySmall)
                            SelectionBlock(prompt.schemaJson.take(24_000))
                            OutlinedTextField(value = jsonText, onValueChange = { jsonText = it }, label = { Text("JSON 回答") }, enabled = !disabled,
                                minLines = 4, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().testTag("server-json-input"))
                        } else prompt.fields.forEach { field ->
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(field.title.ifBlank { field.name } + if (field.required) " *" else "", style = MaterialTheme.typography.bodyMedium)
                                if (field.description.isNotBlank()) Text(field.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                when {
                                    field.type == "boolean" -> {
                                        if (!field.required) PromptOption("不提供", "", values[field.name].isNullOrBlank(), !disabled) { values.remove(field.name) }
                                        PromptOption("是", "", values[field.name] == "true", !disabled) { values[field.name] = "true" }
                                        PromptOption("否", "", values[field.name] == "false", !disabled) { values[field.name] = "false" }
                                    }
                                    field.type == "array" -> field.options.forEach { option ->
                                        Row(Modifier.fillMaxWidth().toggleable(value = option.value in selections[field.name].orEmpty(), enabled = !disabled, role = Role.Checkbox, onValueChange = { checked ->
                                            val old = selections[field.name].orEmpty()
                                            selections[field.name] = if (checked) (old + option.value).distinct() else old - option.value
                                        }), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = option.value in selections[field.name].orEmpty(), onCheckedChange = null, enabled = !disabled)
                                            Text(option.label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    field.options.isNotEmpty() -> {
                                        if (!field.required) PromptOption("不提供", "", values[field.name].isNullOrBlank(), !disabled) { values.remove(field.name) }
                                        field.options.forEach { option -> PromptOption(option.label, option.description, values[field.name] == option.value, !disabled) { values[field.name] = option.value } }
                                    }
                                    else -> OutlinedTextField(value = values[field.name].orEmpty(), onValueChange = { values[field.name] = it },
                                        enabled = !disabled, singleLine = field.type != "string" || field.isSecret,
                                        visualTransformation = if (field.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = when {
                                            field.isSecret -> KeyboardType.Password
                                            field.type == "integer" || field.type == "number" -> KeyboardType.Decimal
                                            else -> KeyboardType.Text
                                        }), modifier = Modifier.fillMaxWidth().testTag("server-field:${field.name}"))
                                }
                            }
                        }
                    }
                    ServerPromptKind.URL -> {
                        Text("请在浏览器中完成操作，然后回到这里确认。不会自动打开网页或自动授权。", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !disabled && prompt.url != null, onClick = {
                            runCatching { uriHandler.openUri(prompt.url!!) }.onFailure { localError = "无法打开网页，请稍后重试" }
                        }) { Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("打开授权网页") }
                        Row(Modifier.fillMaxWidth().toggleable(value = urlConfirmed, enabled = !disabled, role = Role.Checkbox, onValueChange = { urlConfirmed = it }), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = urlConfirmed, onCheckedChange = null, enabled = !disabled)
                            Text("我已完成网页操作", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    ServerPromptKind.UNSUPPORTED -> Unit
                }
                if (supported && prompt.kind != ServerPromptKind.URL) Text("请填写所有必答项。回答仅在本次表单中保留，关闭后清除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (disabled) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !disabled, onClick = { submit(if (prompt.kind == ServerPromptKind.USER_INPUT) ServerRequests.skip(prompt) else ServerRequests.cancel(prompt)) }) {
                        Text(if (prompt.kind == ServerPromptKind.USER_INPUT) "跳过问题" else "取消请求")
                    }
                    if (prompt.kind != ServerPromptKind.USER_INPUT) TextButton(enabled = !disabled, onClick = { submit(ServerRequests.decline(prompt)) }) { Text("拒绝提供") }
                }
            }
        },
        confirmButton = { if (supported) TextButton(enabled = !disabled && response != null, onClick = { response?.let(::submit) }) { Text(if (disabled) "正在提交" else if (prompt.kind == ServerPromptKind.URL) "确认已完成" else "提交回答") } },
        dismissButton = { TextButton(onClick = { close() }) { Text("关闭表单") } }
    )
}

@Composable
private fun PromptOption(label: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ApprovalCard(approval: Approval, onApproval: (Approval, Boolean) -> Unit) {
    var expanded by rememberSaveable(approval.id) { mutableStateOf(false) }
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .4f)), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = .5f)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) { Icon(Icons.Outlined.AdminPanelSettings, null, modifier = Modifier.size(18.dp)); Text("等待你的批准 · 任务已暂停", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
            Text(approval.reason.ifBlank { "Codex 请求执行一项需要批准的操作" }, style = MaterialTheme.typography.bodySmall)
            if (approval.details.isNotBlank()) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(30.dp)) { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, modifier = Modifier.size(16.dp)); Text(if (expanded) "收起命令和权限范围" else "查看命令和权限范围", style = MaterialTheme.typography.labelSmall) }
                if (expanded) SelectionBlock(approval.details)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onApproval(approval, true) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("仅批准本次") }
                TextButton(onClick = { onApproval(approval, false) }) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun PendingList(state: AppUiState, actions: NativeActions) {
    var expanded by remember(state.selected?.id) { mutableStateOf(false) }
    val inputs = state.pending.filter { it.threadId.isBlank() || it.threadId == state.selected?.id }
    val verified = state.pendingVerified && state.connected
    val uncertain = !verified || state.pendingSyncError != null
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), modifier = Modifier.testTag("pending-tray")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.semantics { contentDescription = if (expanded) "折叠待处理消息" else "查看待处理消息" }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PlaylistPlay, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp))
                Text("待处理消息 ${inputs.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, modifier = Modifier.size(18.dp))
            }
            if (uncertain) Row(Modifier.fillMaxWidth().testTag("pending-sync-status"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("队列状态同步未确认", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    state.pendingSyncError?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                TextButton(onClick = actions.onRefresh, enabled = state.connected) { Text("重新同步") }
            }
            if (expanded) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                inputs.forEach { input ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 7.dp).testTag("pending-item:${input.id}"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(PendingMessages.label(input, verified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("pending-status:${input.id}"))
                            Text(input.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            PendingMessages.warning(input)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("pending-warning:${input.id}")) }
                        }
                        if (PendingMessages.canCancel(input, verified)) {
                            val deleting = input.id in state.pendingDeletingIds
                            TextButton(onClick = { actions.onCancelPending(input.id) }, enabled = !deleting && !uncertain, modifier = Modifier.semantics { contentDescription = "撤回排队消息:${input.id}" }) {
                                if (deleting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.Undo, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (deleting) "撤回中…" else "撤回")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(state: AppUiState, draft: String, onDraft: (String) -> Unit, mode: String, onMode: (String) -> Unit, onSend: (String, String) -> Unit, onStop: () -> Unit, actions: NativeActions) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val latestDraft by rememberUpdatedState(draft)
    val latestOnDraft by rememberUpdatedState(onDraft)
    val latestActions by rememberUpdatedState(actions)
    val latestSession by rememberUpdatedState(state.selected?.id)
    var attachmentMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var composerFocused by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    fun dismissInput() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> dismissInput(); if (uris.isNotEmpty()) actions.onAttach(uris) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris -> dismissInput(); if (uris.isNotEmpty()) actions.onAttach(uris) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        dismissInput()
        val uri = pendingCameraUri?.let(Uri::parse)
        pendingCameraUri = null
        if (success && uri != null) actions.onAttach(listOf(uri))
    }
    fun takePhoto() {
        dismissInput()
        try {
            val uri = AttachmentInputs.newCameraUri(context)
            pendingCameraUri = uri.toString()
            camera.launch(uri)
        } catch (_: Exception) {
            pendingCameraUri = null
            Toast.makeText(context, "无法打开相机，请确认手机已安装相机应用", Toast.LENGTH_LONG).show()
        }
    }
    fun pasteClipboard() {
        if (pasting) return
        val sessionId = latestSession
        scope.launch {
            pasting = true
            try {
                val input = withContext(Dispatchers.IO) { AttachmentInputs.readClipboard(context) }
                if (latestSession != sessionId) return@launch
                if (input.images.isNotEmpty()) latestActions.onAttach(input.images)
                if (input.text.isNotEmpty()) latestOnDraft(if (latestDraft.isBlank()) input.text else latestDraft + "\n" + input.text)
                val notice = input.message ?: if (input.images.isEmpty() && input.text.isEmpty()) "剪贴板中没有可用的图片或文字" else null
                if (notice != null) Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { Toast.makeText(context, "读取剪贴板失败，请重新复制后再试", Toast.LENGTH_LONG).show() }
            finally { pasting = false }
        }
    }
    val canSend = !state.sending && !state.readOnly && state.connected && (draft.isNotBlank() || state.attachments.isNotEmpty())
    fun send() { if (canSend) onSend(draft.trim(), if (state.running) mode else "steer") }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("composer-container"),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(if (composerFocused) 1.5.dp else 1.dp, if (composerFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (state.attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.attachments.forEach { attachment -> Box(Modifier.size(68.dp)) {
                    AsyncImage(model = attachment.uri, contentDescription = attachment.name, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                    IconButton(onClick = { actions.onRemoveAttachment(attachment.uri) }, modifier = Modifier.align(Alignment.TopEnd).size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .85f), CircleShape)) { Icon(Icons.Outlined.Close, "移除附件 ${attachment.name}", modifier = Modifier.size(16.dp)) }
                } }
            }
            OutlinedTextField(value = draft, onValueChange = onDraft, placeholder = { Text(if (state.running) "补充要求或下一个任务…" else "给 Codex 发送消息…") }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).onFocusChanged { composerFocused = it.hasFocus }.testTag("composer-input"), shape = RoundedCornerShape(20.dp), maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent, errorBorderColor = Color.Transparent, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), keyboardActions = KeyboardActions(onSend = { send() }))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = {
                    dismissInput()
                    try { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    catch (_: Exception) { Toast.makeText(context, "无法打开相册，请使用加号菜单中的相册/文件", Toast.LENGTH_LONG).show() }
                }, enabled = !state.sending && !pasting) { Icon(Icons.Outlined.PhotoLibrary, "从相册添加图片") }
                Box {
                    IconButton(onClick = { dismissInput(); attachmentMenu = true }, enabled = !state.sending && !pasting) {
                        if (pasting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Add, "添加图片或粘贴")
                    }
                    DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                        DropdownMenuItem(text = { Text("拍照") }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) }, onClick = { attachmentMenu = false; takePhoto() })
                        DropdownMenuItem(text = { Text("相册/文件") }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) }, onClick = { attachmentMenu = false; dismissInput(); picker.launch(arrayOf("image/*")) })
                        DropdownMenuItem(text = { Text("粘贴图片或文字") }, leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) }, onClick = { attachmentMenu = false; pasteClipboard() })
                    }
                }
                if (state.running) {
                    Box {
                        TextButton(onClick = { modeMenu = true }, modifier = Modifier.width(64.dp).height(48.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                            Text(if (mode == "steer") "跟进" else "排队", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(14.dp))
                        }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            DropdownMenuItem(text = { Column { Text("跟进"); Text("补充要求，继续当前任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { onMode("steer"); modeMenu = false })
                            DropdownMenuItem(text = { Column { Text("排队"); Text("当前任务结束后发送", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { onMode("queue"); modeMenu = false })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (state.running) FilledIconButton(onClick = onStop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Icon(Icons.Outlined.Stop, "停止当前任务") }
                FilledIconButton(onClick = { send() }, enabled = canSend) { if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Outlined.Send, if (state.running && mode == "queue") "排队发送" else if (state.running) "发送跟进" else "发送消息") }
            }
        }
    }
}

@Composable
private fun TasksPage(state: AppUiState, actions: NativeActions, onApproval: (Approval, Boolean) -> Unit, onServerPrompt: (ServerPrompt) -> Unit, onAsyncPrompt: (AsyncQuestionPrompt) -> Unit) {
    val active = state.sessions.filter { isRunning(it.status) || it.id in state.activeSessionIds }
    val tools = state.messages.flatMap { it.tools }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("需要处理", "${state.approvals.size} 项审批 · ${state.serverRequests.size + state.asyncQuestions.size} 项问答") }
        if (state.approvals.isEmpty() && state.serverRequests.isEmpty() && state.asyncQuestions.isEmpty()) item { Text("暂无待审批请求", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
        items(state.serverRequests, key = { "prompt-${serverPromptKey(it)}" }) { prompt -> ServerPromptCard(prompt, state.connected, prompt.id in state.respondingServerRequestIds, onServerPrompt) }
        items(state.asyncQuestions, key = { "async-${asyncPromptKey(it)}" }) { prompt -> AsyncQuestionCard(prompt, state.connected, prompt.itemId in state.respondingAsyncQuestionIds, onAsyncPrompt) }
        items(state.approvals, key = { "approval-${it.id}" }) { approval -> ApprovalCard(approval, onApproval) }
        item { SectionTitle("正在执行", "${active.size} 个会话") }
        if (active.isEmpty()) item { Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Outlined.TaskAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)); Text("当前没有执行中的会话", style = MaterialTheme.typography.bodyMedium); Text("任务启动后可在这里查看状态", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        items(active, key = { "session-${it.id}" }) { SessionCard(it, state.projects, actions.onSelect) }
        if (state.activity.isNotBlank()) item { ActivityCard(state) }
        if (tools.isNotEmpty()) {
            item { SectionTitle("当前会话工具", "${tools.size} 次调用") }
            item { if (tools.size > 1) ToolGroupCard("task-tools", tools, actions.onTool) else ToolView(tools.first(), actions.onTool) }
        }
        item { Text("会话和任务继续在服务器执行，退出应用不会中断。重新打开后会自动同步状态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun SettingsPage(state: AppUiState, actions: NativeActions, fontSize: Float, onFontSize: (Float) -> Unit) {
    var clearDialog by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) { Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer); Text("Y2YY Codex", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("原生移动客户端 · ${state.version}", style = MaterialTheme.typography.bodySmall); ConnectionLabel(state.connected, state.connectionText) } } }
        item { SectionTitle("连接", "") }
        item { SettingsCard {
            ListItem(headlineContent = { Text("服务器") }, supportingContent = { Text(state.serverLabel.ifBlank { "Y2YY Codex 服务器" }) }, leadingContent = { Icon(Icons.Outlined.Dns, null) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            ListItem(headlineContent = { Text("重新连接并同步") }, supportingContent = { Text("更新会话列表和执行进度") }, leadingContent = { Icon(Icons.Outlined.Sync, null) }, modifier = Modifier.clickable(onClick = actions.onRefresh), trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
        } }
        item { SectionTitle("偏好", "") }
        item { SettingsCard {
            ListItem(headlineContent = { Text("深色模式") }, leadingContent = { Icon(Icons.Outlined.DarkMode, null) }, trailingContent = { Switch(checked = state.darkTheme, onCheckedChange = actions.onTheme) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("聊天字号", style = MaterialTheme.typography.bodyLarge); Text("${fontSize.toInt()} sp", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
                Slider(value = fontSize, onValueChange = onFontSize, valueRange = 13f..21f, steps = 7)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            ListItem(headlineContent = { Text("清除本地缓存") }, supportingContent = { Text("服务器上的会话记录会保留") }, leadingContent = { Icon(Icons.Outlined.CleaningServices, null) }, modifier = Modifier.clickable { clearDialog = true }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
        } }
        item { Text("访问凭据在设备上加密保存。应用会自动认证，无需重复登录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
    }
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("清除本地缓存？") }, text = { Text("只清除手机上的会话缓存。服务器记录和登录状态会保留，下次连接会重新同步。") }, confirmButton = { TextButton(onClick = { clearDialog = false; actions.onClearCache() }) { Text("清除") } }, dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } })
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) { Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) { Column(content = content) } }

@Composable
private fun SessionActionDialog(kind: String, state: AppUiState, actions: NativeActions, onDismiss: () -> Unit) {
    val rename = kind == "rename"
    val movableProjects = remember(state.projects) { state.projects.filter { it.path != "codex-bridge://chats" } }
    var title by remember(state.selected?.id, kind) { mutableStateOf(state.selected?.title.orEmpty()) }
    var projectId by remember(state.selected?.id, kind) { mutableStateOf(movableProjects.firstOrNull { it.path == state.selected?.projectPath }?.id) }
    var submitted by remember { mutableStateOf(false) }
    var observedBusy by remember { mutableStateOf(false) }
    LaunchedEffect(state.sessionActionBusy, state.sessionActionError) {
        if (submitted && state.sessionActionBusy) observedBusy = true
        if (submitted && observedBusy && !state.sessionActionBusy && state.sessionActionError == null) onDismiss()
    }
    val trimmedTitle = title.trim()
    val titleTooLong = rename && trimmedTitle.codePointCount(0, trimmedTitle.length) > 200
    val canSave = state.connected && !state.readOnly && !state.sessionActionBusy && (!rename || (trimmedTitle.isNotEmpty() && !titleTooLong)) && (rename || !state.running)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rename) "重命名会话" else "移动到其他项目") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (rename) "更改会话标题，会话记录会保留。" else "聊天记录会保留，后续命令将在目标项目目录执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rename) OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("会话名称") }, singleLine = true, enabled = !state.sessionActionBusy, modifier = Modifier.fillMaxWidth().testTag("session-title-input"))
                else {
                    MoveProjectRow("无项目", null, projectId == null, !state.sessionActionBusy) { projectId = null }
                    movableProjects.forEach { project -> MoveProjectRow(project.name, project.id, projectId == project.id, !state.sessionActionBusy) { projectId = project.id } }
                }
                if (state.sessionActionBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.sessionActionError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (titleTooLong) Text("会话名称最多 200 个字符", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (state.readOnly) Text("当前会话由其他客户端控制，暂时无法修改", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!rename && state.running) Text("当前任务结束后可移动会话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.connected) Text("连接恢复后可保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = canSave, onClick = { submitted = true; observedBusy = false; if (rename) actions.onRenameSession(title.trim()) else actions.onMoveSession(projectId) }) { Text(if (state.sessionActionBusy) "正在保存" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun MoveProjectRow(name: String, id: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("move-project:${id ?: "none"}").selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ModelSettingsDialog(state: AppUiState, actions: NativeActions, onDismiss: () -> Unit) {
    var modelId by remember(state.selected?.id) { mutableStateOf(state.composerModel.ifBlank { state.selected?.model.orEmpty() }) }
    var effortId by remember(state.selected?.id) { mutableStateOf(state.composerEffort) }
    var serviceTier by remember(state.selected?.id) { mutableStateOf(state.composerServiceTier) }
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    var speedEdited by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var observedSaving by remember { mutableStateOf(false) }
    LaunchedEffect(state.modelOptions, state.composerModel, state.composerEffort, state.composerServiceTier) {
        val preferredModel = if (edited) modelId else state.composerModel.ifBlank { state.selected?.model.orEmpty() }
        val option = state.modelOptions.firstOrNull { it.id == preferredModel } ?: state.modelOptions.firstOrNull().takeIf { preferredModel.isBlank() && state.selected == null }
        if (option != null) {
            modelId = option.id
            val currentEffort = if (edited) effortId else state.composerEffort.ifBlank { effortId }
            effortId = if (currentEffort.isNotBlank()) currentEffort else supportedEffort(option, currentEffort)
        }
        if (!speedEdited) serviceTier = state.composerServiceTier
    }
    LaunchedEffect(state.savingModelSettings, state.modelSettingsError) {
        if (submitted && state.savingModelSettings) observedSaving = true
        if (submitted && observedSaving && !state.savingModelSettings && state.modelSettingsError == null) onDismiss()
    }
    val option = state.modelOptions.firstOrNull { it.id == modelId }
    val fastAvailable = option?.serviceTiers?.any { it.id == "priority" } == true
    val busy = state.loadingModelOptions || state.savingModelSettings
    val validEffort = effortId.isNotBlank() && (option?.efforts.isNullOrEmpty() || option?.efforts?.any { it.id == effortId } == true)
    val validTier = serviceTier == "standard" || (serviceTier == "priority" && fastAvailable)
    val canSave = option != null && validEffort && validTier && state.connected && !state.readOnly && !busy
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型与思考强度") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(if (state.running) "后续请求使用，当前任务继续执行" else "设置用于当前会话的后续请求", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.loadingModelOptions) LinearProgressIndicator(Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(expanded = modelMenu, onExpandedChange = { if (!busy && state.modelOptions.isNotEmpty()) modelMenu = it }) {
                    OutlinedTextField(value = option?.let { it.name.ifBlank { it.id } } ?: modelId.ifBlank { if (state.loadingModelOptions) "加载模型列表" else "请选择模型" }, onValueChange = {}, readOnly = true, label = { Text("模型") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth().testTag("model-selector"), enabled = !busy && state.modelOptions.isNotEmpty())
                    ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        state.modelOptions.forEach { item ->
                            DropdownMenuItem(text = { Text(item.name.ifBlank { item.id }) }, onClick = {
                                edited = true; modelId = item.id; effortId = supportedEffort(item, effortId)
                                if (serviceTier == "priority" && item.serviceTiers.none { it.id == "priority" }) { serviceTier = "standard"; speedEdited = true }
                                modelMenu = false
                            })
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = effortMenu, onExpandedChange = { if (!busy && !option?.efforts.isNullOrEmpty()) effortMenu = it }) {
                    OutlinedTextField(value = effortId.ifBlank { "默认" }, onValueChange = {}, readOnly = true, label = { Text("思考强度") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(effortMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth().testTag("effort-selector"), enabled = !busy && option != null)
                    ExposedDropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                        option?.efforts.orEmpty().forEach { effort -> DropdownMenuItem(text = { Column { Text(effort.id); val description = effortDescription(effort); if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { edited = true; effortId = effort.id; effortMenu = false }) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("service-tier-selector")) {
                    Text("响应速度", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = serviceTier == "standard", onClick = { serviceTier = "standard"; speedEdited = true }, enabled = !busy, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2), modifier = Modifier.testTag("service-tier-standard")) { Text("标准") }
                        SegmentedButton(selected = serviceTier == "priority", onClick = { serviceTier = "priority"; speedEdited = true }, enabled = !busy && fastAvailable, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2), modifier = Modifier.testTag("service-tier-priority")) { Text("快速") }
                    }
                    Text(if (state.loadingModelOptions) "正在读取速度选项" else if (serviceTier == "unknown") "当前响应速度未确认，请明确选择后保存" else if (!fastAvailable) "当前模型暂不提供快速模式" else if (serviceTier == "priority") "快速模式会增加额度消耗" else "新会话沿用上次保存的速度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.readOnly) Text("当前会话只读，无法修改模型设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else if (!state.connected) Text("连接恢复后可保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.modelSettingsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (state.modelOptions.isEmpty() && !state.loadingModelOptions) TextButton(onClick = actions.onLoadModels) { Text("重新加载模型列表") }
            }
        },
        confirmButton = { TextButton(onClick = { submitted = true; observedSaving = false; actions.onSaveComposerSettings(modelId, effortId, serviceTier) }, enabled = canSave) { if (state.savingModelSettings) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(7.dp)) }; Text(if (state.savingModelSettings) "正在保存" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun serviceTierName(tier: String): String = when (tier) { "priority" -> "快速"; "standard" -> "标准"; else -> "未确认" }

private fun supportedEffort(option: ModelOption, current: String): String =
    current.takeIf { value -> option.efforts.any { it.id == value } }
        ?: option.defaultEffort.takeIf { value -> value.isNotBlank() && (option.efforts.isEmpty() || option.efforts.any { it.id == value }) }
        ?: option.efforts.firstOrNull()?.id.orEmpty()

private fun effortDescription(option: ReasoningOption): String = when (option.id.lowercase()) {
    "low" -> "轻量分析，优先响应速度"
    "medium" -> "兼顾速度与分析深度"
    "high" -> "深入分析复杂问题"
    "xhigh" -> "更深入分析，可能耗时更长"
    "max" -> "高强度分析困难任务"
    "ultra" -> "极高强度分析，适合复杂推理"
    else -> option.description
}

@Composable
private fun NewSessionDialog(projects: List<Project>, onDismiss: () -> Unit, onCreate: (Project?, String) -> Unit) {
    var selected by remember { mutableStateOf<Project?>(projects.firstOrNull()) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("新建会话") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(value = selected?.name ?: "独立会话", onValueChange = {}, readOnly = true, label = { Text("项目") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("独立会话") }, onClick = { selected = null; expanded = false })
                    projects.forEach { project -> DropdownMenuItem(text = { Text(project.name) }, onClick = { selected = project; expanded = false }) }
                }
            }
            if (selected != null) Text(selected!!.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("首条消息（可选）") }, placeholder = { Text("描述你想完成的任务…") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { onCreate(selected, prompt.trim()) }) { Text("创建会话") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun LoadingState(label: String) { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp); Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, button: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(70.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (button != null) FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(button) }
    }
}

private data class ChatRow(val id: String, val messages: List<ChatMessage>, val toolOnly: Boolean) {
    val tools: List<ToolCall> get() = messages.flatMap { it.tools }.distinctBy { it.id }
}

private fun groupChatRows(messages: List<ChatMessage>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    messages.forEach { message ->
        val toolOnly = message.role == "assistant" && message.tools.isNotEmpty() && message.text.isBlank() &&
            message.images.isEmpty() && !message.imagePending && message.imageError.isNullOrBlank() && !message.deferred
        val previous = rows.lastOrNull()
        if (toolOnly && previous != null && previous.toolOnly && previous.messages.last().turnId == message.turnId) {
            rows[rows.lastIndex] = previous.copy(messages = previous.messages + message)
        } else rows += ChatRow(message.id, listOf(message), toolOnly)
    }
    return rows
}

private fun chatStatusLabel(state: AppUiState): String = buildList {
    add(state.connectionText.ifBlank { if (state.connected) "已连接" else "正在连接" })
    if (state.fromCache) add("本地缓存")
    if (state.readOnly) add("只读")
    if (state.approvals.any { it.threadId == state.selected?.id }) add("等待批准")
    else if (state.running) add("执行中")
}.joinToString(" · ")

private fun isRunning(status: String): Boolean = status.lowercase() in setOf("running", "inprogress", "in_progress", "active", "streaming", "busy", "started")
private fun statusLabel(status: String): String = when (status.lowercase()) {
    "running", "inprogress", "in_progress", "active", "streaming", "busy", "started" -> "执行中"
    "completed", "complete", "success", "succeeded", "done" -> "已完成"
    "failed", "failure", "error" -> "失败"
    "interrupted", "cancelled", "canceled", "stopped" -> "已停止"
    "pending", "queued", "queue" -> "排队中"
    "waiting", "approval", "waitingforapproval", "waiting_for_approval" -> "等待批准"
    "idle", "ready", "" -> "就绪"
    else -> status
}
private fun roleLabel(role: String): String = when (role) { "system" -> "系统"; "developer" -> "环境与规则"; "tool" -> "工具"; else -> role }
private fun displayTime(value: String): String = value.replace('T', ' ').removeSuffix("Z").take(16)
