package com.y2yy.codex.mobile.model

/** A question attached to an async agent message, separate from native server RPC prompts. */
data class AsyncQuestionPrompt(
    val threadId: String,
    val itemId: String,
    val turnId: String,
    val questions: List<AsyncQuestionItem>,
    val connectionGeneration: Long = 0
)

data class AsyncQuestionItem(val title: String, val options: List<String> = emptyList())
