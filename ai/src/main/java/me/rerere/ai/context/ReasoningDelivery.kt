package me.rerere.ai.context

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json

data class ReasoningDelivery(val omissions: List<ContextExclusion>, val notices: List<String>)

/** Inspect the built native fields, rather than equating stored reasoning with transmitted reasoning. */
fun inspectReasoningDelivery(messages: List<UIMessage>, protocol: String, body: String): ReasoningDelivery {
    val visible = mutableListOf<String>()
    fun text(value: JsonElement?) {
        when (value) {
            is JsonPrimitive -> if (value.isString) visible += value.content
            is JsonArray -> value.forEach { text(it) }
            is JsonObject -> value.forEach { (key, child) -> if (key in setOf("text", "thinking", "summary", "content")) text(child) }
            else -> Unit
        }
    }
    fun walk(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                text(value["reasoning_content"])
                text(value["reasoning_details"])
                (value["reasoning"] as? JsonPrimitive)?.let { text(it) }
                val type = (value["type"] as? JsonPrimitive)?.content
                if (type in setOf("reasoning", "thinking", "thought")) {
                    text(value["text"]); text(value["thinking"]); text(value["summary"]); text(value["content"])
                }
                if ((value["thought"] as? JsonPrimitive)?.content == "true") text(value["text"])
                value.values.forEach(::walk)
            }
            is JsonArray -> value.forEach(::walk)
            else -> Unit
        }
    }
    walk(json.parseToJsonElement(body))
    val omissions = messages.flatMap { message -> message.parts.mapIndexedNotNull { index, part ->
        if (part is UIMessagePart.Reasoning && part.reasoning.isNotBlank() && visible.none { part.reasoning in it })
            ContextExclusion(message.id.toString(), index, "实际 $protocol 请求的原生思考字段未包含这段文本。")
        else null
    } }
    return ReasoningDelivery(omissions, omissions.map { it.reason }.distinct())
}
