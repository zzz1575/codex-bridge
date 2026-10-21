package com.y2yy.codex.mobile.data

import com.y2yy.codex.mobile.model.ModelOption
import org.json.JSONObject

/** Native camel-case null is Standard; Bridge's unresolved snake-case null is unknown. */
object ServiceTiers {
    const val STANDARD = "standard"
    const val PRIORITY = "priority"
    const val UNKNOWN = "unknown"
    const val UNCONFIRMED_MESSAGE = "当前响应速度未确认，请刷新或明确选择后保存"

    fun normalized(value: String?): String = if (value == PRIORITY) PRIORITY else STANDARD

    fun requireValid(value: String): String {
        require(value == STANDARD || value == PRIORITY) { "请选择标准或快速模式" }
        return value
    }

    fun supported(model: ModelOption?, tier: String): Boolean =
        tier == STANDARD || tier == PRIORITY && model?.serviceTiers?.any { it.id == PRIORITY } == true

    fun fromSettings(settings: JSONObject, fallback: String = STANDARD): String = when {
        hasError(settings) -> UNKNOWN
        settings.has("service_tier") -> settings.string("service_tier").takeIf { it == STANDARD || it == PRIORITY } ?: UNKNOWN
        settings.has("serviceTier") -> if (settings.isNull("serviceTier")) STANDARD else
            when (settings.string("serviceTier")) {
                STANDARD, "default" -> STANDARD
                PRIORITY -> PRIORITY
                else -> UNKNOWN
            }
        else -> fallback.takeIf { it == STANDARD || it == PRIORITY || it == UNKNOWN } ?: UNKNOWN
    }

    fun errorFor(tier: String, currentError: String?): String? = when {
        tier == UNKNOWN -> UNCONFIRMED_MESSAGE
        currentError == UNCONFIRMED_MESSAGE -> null
        else -> currentError
    }

    private fun hasError(settings: JSONObject): Boolean =
        settings.has("service_tier_error") && !settings.isNull("service_tier_error") &&
            settings.string("service_tier_error").isNotBlank()

    /** A successful transport without read-back cannot persist an unconfirmed Fast selection. */
    fun confirmed(settings: JSONObject, requested: String): String {
        requireValid(requested)
        val key = when {
            settings.has("service_tier") -> "service_tier"
            settings.has("serviceTier") -> "serviceTier"
            else -> null
        }
        val actual = key?.let {
            if (it == "serviceTier" && (settings.isNull(it) || settings.string(it) == "default")) STANDARD else
                settings.string(it).takeIf { tier -> tier in setOf(STANDARD, PRIORITY) }
        }
        if (hasError(settings) || actual != requested) {
            throw BridgeException("service_tier_unconfirmed", "服务器尚未确认速度设置，请刷新后重试")
        }
        return requested
    }
}
