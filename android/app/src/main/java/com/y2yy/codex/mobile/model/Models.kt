package com.y2yy.codex.mobile.model

data class Project(val id: String, val name: String, val path: String)
data class Session(
    val id: String,
    val title: String,
    val projectPath: String = "",
    val updatedAt: String = "",
    val status: String = "idle",
    val model: String = ""
)

/** Ephemeral native activity; never restore execution truth from the disk cache. */
data class SessionRunState(
    val status: String,
    val turnId: String? = null,
    val sequence: Long = -1,
    val revision: Long = 0
)
data class ToolCall(val id: String, val name: String, val status: String, val input: String = "", val output: String = "", val aliases: Set<String> = emptySet(), val hasImage: Boolean = false)
data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val images: List<String> = emptyList(),
    val tools: List<ToolCall> = emptyList(),
    val streaming: Boolean = false,
    val turnId: String = "",
    val imagePending: Boolean = false,
    val imageError: String? = null,
    val deferred: Boolean = false,
    val deferredToolCount: Int = 0,
    val deferredFileCount: Int = 0
)
data class Approval(val id: Long, val threadId: String, val reason: String, val details: String, val connectionGeneration: Long = 0)
data class PendingInput(
    val id: String,
    val text: String,
    val status: String,
    val action: String,
    val threadId: String = "",
    val source: String = "",
    val queuedSubmissionId: String = "",
    val error: String? = null,
    val startError: String? = null,
    val partial: Boolean = false
)
data class PendingSnapshot(
    val messages: List<PendingInput>,
    val available: Boolean,
    val authoritative: Boolean,
    val error: String? = null
)
data class Attachment(val uri: String, val name: String, val mime: String)
data class ReasoningOption(val id: String, val description: String = "")
data class ServiceTierOption(val id: String, val name: String = "", val description: String = "")
data class ModelOption(
    val id: String,
    val name: String,
    val description: String = "",
    val defaultEffort: String = "medium",
    val efforts: List<ReasoningOption> = emptyList(),
    val serviceTiers: List<ServiceTierOption> = emptyList()
)
data class AppUiState(
    val loading: Boolean = true,
    val connected: Boolean = false,
    val connectionText: String = "正在连接",
    val error: String? = null,
    val projects: List<Project> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val selected: Session? = null,
    val messages: List<ChatMessage> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val serverRequests: List<ServerPrompt> = emptyList(),
    val respondingServerRequestIds: Set<Long> = emptySet(),
    val serverRequestErrors: Map<Long, String> = emptyMap(),
    val asyncQuestions: List<AsyncQuestionPrompt> = emptyList(),
    val respondingAsyncQuestionIds: Set<String> = emptySet(),
    val asyncQuestionErrors: Map<String, String> = emptyMap(),
    val pending: List<PendingInput> = emptyList(),
    val pendingVerified: Boolean = false,
    val pendingSyncError: String? = null,
    val pendingDeletingIds: Set<String> = emptySet(),
    val activity: String = "",
    val running: Boolean = false,
    val activeTurnId: String? = null,
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val sending: Boolean = false,
    val usage: String = "",
    val fromCache: Boolean = false,
    val darkTheme: Boolean = false,
    val serverLabel: String = "",
    val version: String = "2.2.9",
    val activeSessionIds: Set<String> = emptySet(),
    val sessionRunStates: Map<String, SessionRunState> = emptyMap(),
    val sessionActivityRevision: Long = 0,
    val sessionActivityGeneration: Long = 0,
    val readOnly: Boolean = false,
    val lastSentId: String = "",
    val lastSentThreadId: String = "",
    val lastSentText: String = "",
    val loadingHistoryTurns: Set<String> = emptySet(),
    val historyErrors: Map<String, String> = emptyMap(),
    val modelOptions: List<ModelOption> = emptyList(),
    val composerModel: String = "",
    val composerEffort: String = "",
    val composerServiceTier: String = "standard",
    val preferredServiceTier: String = "standard",
    val loadingModelOptions: Boolean = false,
    val savingModelSettings: Boolean = false,
    val modelSettingsError: String? = null,
    val sessionActionBusy: Boolean = false,
    val sessionActionError: String? = null
)
data class MessagePage(val thread: Session?, val messages: List<ChatMessage>, val start: Int, val total: Int, val hasOlder: Boolean)
