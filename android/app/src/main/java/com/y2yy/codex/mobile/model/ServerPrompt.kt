package com.y2yy.codex.mobile.model

enum class ServerPromptKind { USER_INPUT, FORM, URL, UNSUPPORTED }

/** Transient server interaction. Answers belong to UI memory, never to the local history store. */
data class ServerPrompt(
    val id: Long,
    val threadId: String,
    val method: String,
    val kind: ServerPromptKind = ServerPromptKind.UNSUPPORTED,
    val connectionGeneration: Long = 0,
    val requestIdentity: String = "",
    val title: String = "",
    val message: String = "",
    val serverName: String = "",
    val questions: List<ServerQuestion> = emptyList(),
    val fields: List<ServerFormField> = emptyList(),
    val schemaJson: String = "",
    val mode: String = "",
    val url: String? = null,
    val supported: Boolean = false,
    val jsonForm: Boolean = false
)

data class ServerOption(val value: String, val label: String, val description: String = "")

data class ServerQuestion(
    val id: String,
    val header: String,
    val question: String,
    val isSecret: Boolean = false,
    val options: List<ServerOption> = emptyList()
)

data class ServerFormField(
    val name: String,
    val title: String,
    val description: String = "",
    val type: String = "string",
    val required: Boolean = false,
    val options: List<ServerOption> = emptyList(),
    val isSecret: Boolean = false
)
