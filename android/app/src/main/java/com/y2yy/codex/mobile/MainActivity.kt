package com.y2yy.codex.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.Coil
import coil.ImageLoader
import com.y2yy.codex.mobile.ui.NativeActions
import com.y2yy.codex.mobile.ui.NativeApp
import com.y2yy.codex.mobile.viewmodel.CodexViewModel

class MainActivity: ComponentActivity() {
    private val model: CodexViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Coil.setImageLoader(ImageLoader.Builder(this).okHttpClient(model.api.client).build())
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            NativeApp(state, NativeActions(
                onRefresh = { model.navigationChanged(); model.refresh() }, onSelect = model::select, onBack = model::back,
                onSend = model::send, onStop = model::stop, onApproval = model::approval,
                onNew = model::create, onAttach = model::attach, onRemoveAttachment = model::removeAttachment,
                onOlder = model::older, onClearError = model::clearError, onTheme = model::theme,
                onClearCache = model::clearCache, onTool = model::tool,
                onHistoryTurn = model::historyTurn, onRetryImage = model::retryImage,
                onLoadModels = model::loadModels, onSaveModelSettings = model::saveModelSettings,
                onSaveComposerSettings = model::saveComposerSettings,
                onRenameSession = model::renameSession, onMoveSession = model::moveSession,
                onClearSessionActionError = model::clearSessionActionError, onNavigate = model::navigationChanged,
                onServerResponse = model::respondServerRequest,
                onAsyncResponse = model::respondAsyncQuestion,
                onCancelPending = model::cancelPending,
                onApprovalRequest = model::approvalRequest
            ))
        }
    }
    override fun onStart() { super.onStart(); model.setForeground(true) }
    override fun onStop() { model.setForeground(false); super.onStop() }
}
