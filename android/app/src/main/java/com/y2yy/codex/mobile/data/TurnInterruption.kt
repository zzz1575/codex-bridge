package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.ChatMessage

data class TurnInterruptionNotice(val raw: String)

/** Presentation only: never change history, execution state or pending user submissions. */
object TurnInterruption {
    private const val body = "The user interrupted the previous turn on purpose. Any running unified exec processes may still be running in the background. If any tools/commands were aborted, they may have partially executed."
    private val envelope = Regex("^<turn_aborted>\\s*(.*?)\\s*</turn_aborted>$", RegexOption.DOT_MATCHES_ALL)
    private val whitespace = Regex("\\s+")

    fun presentation(message: ChatMessage): TurnInterruptionNotice? {
        if (message.role !in setOf("user", "system") || message.images.isNotEmpty() ||
            message.tools.isNotEmpty() || message.imagePending || !message.imageError.isNullOrBlank() ||
            message.streaming || message.deferred) return null
        return parse(message.text)
    }

    fun parse(text: String): TurnInterruptionNotice? {
        val match = envelope.matchEntire(text.trim()) ?: return null
        if (match.groupValues[1].trim().replace(whitespace, " ") != body) return null
        // Unknown/mixed envelopes remain ordinary messages, so actual user text is never hidden.
        return TurnInterruptionNotice(text)
    }
}
